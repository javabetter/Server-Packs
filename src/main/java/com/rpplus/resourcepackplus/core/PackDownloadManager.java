package com.rpplus.resourcepackplus.core;

import com.rpplus.resourcepackplus.ResourcePackPlusClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.server.DownloadedPackSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads a server-pushed resource pack ourselves (plain HTTP, no Minecraft internals) and
 * installs it as an extracted folder under resourcepacks/, so it behaves exactly like a normal
 * manually-installed pack: reorderable, editable file-by-file, independent of the server connection.
 *
 * Two install modes:
 *  - freshInstall: full clean overwrite. Used for first-time downloads and for servers set to
 *    "accept pack each time" (always exactly mirrors the server's current pack).
 *  - mergeUpdate: careful update used by the manual "Re-grab" button. Adds new files, leaves
 *    identical files alone, and never silently overwrites a changed file — those are reported
 *    as conflicts for the player (or the server's remembered policy) to resolve.
 */
public final class PackDownloadManager {

    private PackDownloadManager() {}

    public static class MergeResult {
        public boolean success;
        public String errorMessage;
        public int addedCount;
        public int unchangedCount;
        public List<String> conflicts = new ArrayList<>(); // relative paths, unresolved
        public Path incomingTempDir; // kept around if conflicts still need resolving; caller must clean up
        public Path packFolder;
        public Path backupDir; // null if there was no previous install to back up
    }

    public static Path packFolderFor(String serverKey) {
        String safeName = "server_" + serverKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(safeName);
    }

    private static Path backupsRootDir() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(".resourcepackplus-backups");
    }

    // ---------- Fresh install (full overwrite) ----------

    public static void freshInstall(String serverKey, String url, String expectedHash, Consumer<Boolean> onDone) {
        runAsync(() -> {
            Path zip = null;
            ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] Starting download for {} — url='{}' hash='{}'", serverKey, url, expectedHash);
            try {
                zip = downloadToTemp(url);
                long size = Files.size(zip);
                ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] Download finished, {} bytes written to {}", size, zip);
                if (size == 0) {
                    throw new IOException("Downloaded file is empty (0 bytes) — server likely returned no content");
                }
                verifyHashLoosely(zip, expectedHash);

                Path packFolder = packFolderFor(serverKey);
                deleteRecursive(packFolder);
                Files.createDirectories(packFolder);
                extractZip(zip, packFolder);
                int extractedCount = countFiles(packFolder);
                ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] Extracted {} files into {}", extractedCount, packFolder);
                if (extractedCount == 0) {
                    throw new IOException("Zip extracted but contained 0 files — downloaded file may not be a real zip");
                }

                PackPreferences.recordInstalled(serverKey, url, expectedHash);
                ResourcePackInstaller.refreshList(packFolder.getFileName().toString());
                onDone.accept(true);
            } catch (Exception e) {
                ResourcePackPlusClient.LOGGER.error("[Server Packs+ DIAG] Fresh pack install FAILED for {} — url='{}'", serverKey, url, e);
                onDone.accept(false);
            } finally {
                deleteQuietly(zip);
            }
        });
    }

    // ---------- Vanilla download path (Minecraft's own downloader, no custom HTTP client) ----------

    /**
     * Downloads and applies the pack using Minecraft's own {@code DownloadedPackSource} — the
     * exact same code path vanilla uses when you click "Yes" on its own resource-pack prompt.
     * No custom HTTP client, no manual zip extraction, no manual hash checking: this just hands
     * the id/url/hash to vanilla and waits for vanilla to say whether it worked.
     * <p>
     * We still send the ServerboundResourcePackPacket ACCEPTED / SUCCESSFULLY_LOADED /
     * FAILED_DOWNLOAD replies ourselves from the mixin, exactly as before — DownloadedPackSource
     * defaults to a log-only feedback callback and won't talk to the server on its own unless
     * {@code configureForServerControl(...)} is called, which we deliberately never call.
     * <p>
     * NOTE: this does NOT (yet) extract the pack into an editable resourcepacks/ folder — it
     * lands wherever vanilla normally caches downloaded packs (a zip under .minecraft/downloads/).
     * Converting that into our own extracted, editable folder is a follow-up step once this
     * baseline "does the download+apply even work" path is confirmed working end-to-end.
     */
    /** Remembers info about the most recent successful vanilla download, per server, so
     *  "/rpplus convert" can find the right cached file under downloads/ without needing to
     *  know Minecraft's internal cache folder-naming scheme. */
    private static final java.util.Map<String, String> lastVanillaDownloadHash = new ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> lastVanillaDownloadStartMillis = new ConcurrentHashMap<>();
    private static final java.util.Map<String, UUID> lastVanillaDownloadPackId = new ConcurrentHashMap<>();

    public static void vanillaDownloadAndApply(Minecraft client, String serverKey, UUID packId, String url, String expectedHash, Consumer<Boolean> onDone) {
        long startMillis = System.currentTimeMillis();
        ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] Starting VANILLA download for pack {} — url='{}' hash='{}'", packId, url, expectedHash);
        // Everything below touches DownloadedPackSource/ServerPackManager state, which must run on
        // the client thread (vanilla forces the packet handler onto it too).
        client.execute(() -> {
            try {
                URL parsedUrl = URI.create(url).toURL();
                DownloadedPackSource packSource = client.getDownloadedPackSource();
                // CRITICAL: put the server-pack manager into the ALLOWED state before pushing.
                // Vanilla does this from the "Yes" handler of its own resource-pack confirm screen;
                // because our mixin cancels that screen, we must do it ourselves. Without it the
                // pack downloads but never ACTIVATES, so waitForPackFeedback never fires and we never
                // send SUCCESSFULLY_LOADED — which strict servers (e.g. Wynncraft) treat as a failed
                // pack and drop the player into limbo.
                packSource.allowServerPacks();
                packSource.pushPack(packId, parsedUrl, expectedHash);
                packSource.waitForPackFeedback(packId).whenComplete((ignored, throwable) -> client.execute(() -> {
                    boolean success = throwable == null;
                    if (success) {
                        ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] Vanilla download/apply SUCCEEDED for pack {}", packId);
                        // Record signals so "/rpplus convert" can find the right cached file, and
                        // later pop this exact pack when swapping in the converted version.
                        if (expectedHash != null && !expectedHash.isBlank()) {
                            lastVanillaDownloadHash.put(serverKey, expectedHash.trim().toLowerCase());
                        } else {
                            lastVanillaDownloadHash.remove(serverKey);
                        }
                        lastVanillaDownloadStartMillis.put(serverKey, startMillis);
                        lastVanillaDownloadPackId.put(serverKey, packId);
                    } else {
                        ResourcePackPlusClient.LOGGER.error("[Server Packs+ DIAG] Vanilla download/apply FAILED for pack {}", packId, throwable);
                    }
                    onDone.accept(success);
                }));
            } catch (Exception e) {
                ResourcePackPlusClient.LOGGER.error("[Server Packs+ DIAG] Failed to start vanilla pack download for pack {} url='{}'", packId, url, e);
                onDone.accept(false);
            }
        });
    }

    /**
     * Removes the original server-pushed pack from the active pack list, then enables the
     * converted zip as a <em>normal</em>, user-toggleable resource pack via the standard
     * {@link ResourcePackInstaller#enablePack(String)} path (PackRepository + options + reload).
     * <p>
     * This deliberately does NOT re-apply the file through {@code DownloadedPackSource}: that
     * mechanism keeps the pack as a transient server pack that vanilla drops on disconnect and
     * whose activation depends on private internal state we don't control. Enabling it as a
     * regular pack instead is what makes the conversion actually "a regular, usable pack" —
     * persistent across disconnects, visible and toggleable in the normal Resource Packs screen.
     * <p>
     * The converted file name is recorded against the server so "accept and cache" can re-apply
     * it on future joins without any download. Must run on the main client thread, so this
     * schedules itself via {@code client.execute(...)} regardless of the calling thread.
     */
    public static void popAndApplyConverted(Minecraft client, String serverKey, Path convertedPack, Consumer<Boolean> onDone) {
        client.execute(() -> {
            try {
                // 1. Drop the server-pushed pack (if one is active) so its textures stop overriding ours.
                DownloadedPackSource packSource = client.getDownloadedPackSource();
                UUID originalPackId = lastVanillaDownloadPackId.get(serverKey);
                if (originalPackId != null) {
                    ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] convert: popping original server pack {}", originalPackId);
                    packSource.popPack(originalPackId);
                    lastVanillaDownloadPackId.remove(serverKey); // it's gone now
                    // (switchToPack drops the server pack from the selection just before it persists —
                    // doing it here is undone by switchToPack's own repo.reload while popPack is pending.)
                } else {
                    ResourcePackPlusClient.LOGGER.warn("[Server Packs+ DIAG] convert: no recorded original server pack for {} — nothing to pop (continuing to apply the converted pack anyway)", serverKey);
                }

                // 2. Enable the converted folder as a normal, persistent resource pack, dropping any
                //    previous converted pack for this server in the same reload.
                String packName = convertedPack.getFileName().toString();
                String previous = PackPreferences.getConvertedPackFile(serverKey);
                ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] convert: enabling converted pack {} as a normal resource pack (replacing {})", packName, previous);
                ResourcePackInstaller.switchToPack(packName, previous).whenComplete((ignored, throwable) -> client.execute(() -> {
                    boolean success = throwable == null;
                    if (success) {
                        // Remember it so ACCEPT_AND_CACHE re-applies this exact pack on future joins.
                        PackPreferences.recordConverted(serverKey, packName);
                        ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] convert: converted pack {} enabled and reload complete", packName);
                        // Now that the reload has released any old handles, delete the previous
                        // converted artifact from resourcepacks/ (e.g. an older .zip from a prior
                        // run, or a differently-named folder) so stale copies don't accumulate.
                        if (previous != null && !previous.equals(packName)) {
                            deleteResourcepackArtifact(previous);
                        }
                    } else {
                        ResourcePackPlusClient.LOGGER.error("[Server Packs+ DIAG] convert: failed to enable converted pack {}", packName, throwable);
                    }
                    onDone.accept(success);
                }));
            } catch (Exception e) {
                ResourcePackPlusClient.LOGGER.error("[Server Packs+ DIAG] convert: failed to pop/apply converted pack for {}", serverKey, e);
                onDone.accept(false);
            }
        });
    }

    /**
     * Orchestrates the full "convert the last server pack and swap it in" flow used by both the
     * {@code /rpplus convert} command and the config-screen button:
     * <ol>
     *   <li>extract the vanilla-cached download into resourcepacks/&lt;server name&gt;/ as a folder pack,</li>
     *   <li>pop the server-pushed pack, and</li>
     *   <li>enable the converted folder as a normal, persistent resource pack.</li>
     * </ol>
     * The heavy step (1) runs on a background thread. Progress strings are handed to {@code status}
     * and the final boolean to {@code onComplete}; both callbacks may fire on a background thread, so
     * callers are responsible for marshaling to the client thread if they touch game/GUI state.
     */
    public static void convertAndApply(Minecraft client, String serverKey, Consumer<String> status, DoubleConsumer onProgress, Consumer<Boolean> onComplete) {
        status.accept("Converting last downloaded pack for " + serverKey + " ... (large packs take a while — depends on pack size, CPU, and disk speed)");
        runAsync(() -> {
            ConvertResult result = convertLastVanillaDownload(serverKey, onProgress);
            ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] {}", result.message);
            if (!result.success) {
                status.accept("Convert failed: " + result.message);
                onComplete.accept(false);
                return;
            }
            status.accept("Converted! Removing the server pack and applying " + result.destination.getFileName() + " ...");
            popAndApplyConverted(client, serverKey, result.destination, applySuccess -> {
                status.accept(applySuccess
                        ? "Done — server pack removed, " + result.destination.getFileName() + " is now active."
                        : "Converted the file, but swapping it in failed — check the log for details.");
                onComplete.accept(applySuccess);
            });
        });
    }

    /**
     * Re-applies this server's converted (editable) pack as a normal resource pack, with no network
     * download. Returns true if a converted pack exists and is applied (or an enable was kicked off);
     * false if there's nothing to re-apply. Skips the reload if the pack is already applied.
     */
    public static boolean applyCachedConvertedPack(Minecraft client, String serverKey) {
        String fileName = persistedPackFile(serverKey);
        if (fileName == null) return false;
        Path file = FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(fileName);
        if (!Files.exists(file)) {
            ResourcePackPlusClient.LOGGER.warn("[Server Packs+] Converted pack '{}' for {} no longer exists on disk", fileName, serverKey);
            return false;
        }
        if (ResourcePackInstaller.isEnabled(fileName)) {
            ResourcePackPlusClient.LOGGER.info("[Server Packs+] Converted pack '{}' for {} is already applied — no reload", fileName, serverKey);
            return true;
        }
        client.execute(() -> ResourcePackInstaller.enablePack(fileName).whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                ResourcePackPlusClient.LOGGER.info("[Server Packs+] Applied converted pack '{}' for {} (no download)", fileName, serverKey);
            } else {
                ResourcePackPlusClient.LOGGER.error("[Server Packs+] Failed to apply converted pack '{}' for {}", fileName, serverKey, throwable);
            }
        }));
        return true;
    }

    /** The persistent, editable pack file for a server (the converted one), or null. */
    public static String persistedPackFile(String serverKey) {
        String f = PackPreferences.getConvertedPackFile(serverKey);
        return (f == null || f.isBlank()) ? null : f;
    }

    /** True if this server's converted pack is currently applied. */
    public static boolean isPersistedPackActive(String serverKey) {
        String f = persistedPackFile(serverKey);
        return f != null && ResourcePackInstaller.isEnabled(f);
    }

    // ---------- "Cache" = keep the vanilla server pack applied across a disconnect ----------

    /** Servers whose vanilla server pack we've deliberately kept applied past a disconnect this
     *  session, so a re-join can just confirm it loaded without re-downloading or reloading. */
    private static final java.util.Set<String> keptServerPacks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The server whose pack we last handled — set when a pack is pushed (i.e. while connected).
     *  Used at disconnect (which doesn't always go through onDisconnect) to decide whether to keep
     *  the pack applied. */
    private static volatile String currentServerKey = null;

    public static void setCurrentServerKey(String serverKey) {
        currentServerKey = serverKey;
    }

    /** Called from the disconnect cleanup: if the just-left server wants "keep applied", marks its
     *  pack as kept and returns its key (so the cleanup is skipped); otherwise returns null. */
    public static String consumeKeepAppliedOnDisconnect() {
        String k = currentServerKey;
        currentServerKey = null;
        if (k != null && PackPreferences.isKeepApplied(k)) {
            markServerPackKept(k);
            return k;
        }
        return null;
    }

    public static void markServerPackKept(String serverKey) {
        if (serverKey != null) keptServerPacks.add(serverKey);
    }

    public static boolean isServerPackKept(String serverKey) {
        return serverKey != null && keptServerPacks.contains(serverKey);
    }

    public static void clearServerPackKept(String serverKey) {
        if (serverKey != null) keptServerPacks.remove(serverKey);
    }

    /** True if this server's restart-persistence copy is currently in the active pack list (i.e.
     *  Minecraft loaded it on startup from options.txt). Lets a re-join confirm without reloading. */
    public static boolean isKeptCopyApplied(String serverKey) {
        String f = PackPreferences.getCachedPackFile(serverKey);
        return f != null && ResourcePackInstaller.isEnabled(f);
    }

    /**
     * Restart persistence for "keep applied": saves a copy of the currently-downloaded pack into
     * resourcepacks/ and adds it to options.txt WITHOUT a live reload. The in-session skip keeps the
     * real server pack applied now; on the next launch Minecraft loads this copy natively, so the
     * textures survive a full close/relaunch. No visible reload happens when you click Cache.
     */
    public static void persistKeptPackForRestart(Minecraft client, String serverKey, Consumer<Boolean> onDone) {
        runAsync(() -> {
            try {
                Path found = findLastVanillaDownload(serverKey);
                if (found == null) {
                    ResourcePackPlusClient.LOGGER.warn("[Server Packs+] Keep-applied: no downloaded pack to save for {} (restart persistence skipped)", serverKey);
                    onDone.accept(false);
                    return;
                }
                Path packsDir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
                Files.createDirectories(packsDir);
                String fileName = folderNameForServer(serverKey) + ".zip";
                Files.copy(found, packsDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                String id = ResourcePackInstaller.repositoryIdFor(fileName);
                client.execute(() -> {
                    if (!client.options.resourcePacks.contains(id)) {
                        client.options.resourcePacks.add(id);
                    }
                    client.options.save();
                    PackPreferences.recordCached(serverKey, fileName);
                    ResourcePackPlusClient.LOGGER.info("[Server Packs+] Kept pack saved as resourcepacks/{} + added to options (loads on next launch)", fileName);
                    onDone.accept(true);
                });
            } catch (Exception e) {
                ResourcePackPlusClient.LOGGER.error("[Server Packs+] Failed to save kept pack for restart for {}", serverKey, e);
                onDone.accept(false);
            }
        });
    }

    /**
     * Uncache: turn "keep applied" off and leave the player in the normal vanilla state. If they're
     * currently on the server, re-download and apply the real (transient) server pack first — so they
     * end up with the plain server pack that unloads on leave — then remove the kept copy. Otherwise
     * just remove the copy.
     */
    public static void uncacheAndRestoreServerPack(Minecraft client, String serverKey) {
        PackPreferences.ServerEntry entry = PackPreferences.getEntry(serverKey);
        ServerData current = client.getCurrentServer();
        boolean onThisServer = current != null && current.ip != null && serverKey.equalsIgnoreCase(current.ip);
        boolean canRestore = entry != null && entry.lastUrl != null
                && (entry.lastUrl.startsWith("http://") || entry.lastUrl.startsWith("https://"));
        if (onThisServer && canRestore) {
            ResourcePackPlusClient.LOGGER.info("[Server Packs+] Uncaching {} while connected — restoring the live server pack", serverKey);
            // The copy stays applied during the download (no flicker); once the server pack is on, remove the copy.
            vanillaDownloadAndApply(client, serverKey, UUID.randomUUID(), entry.lastUrl, entry.lastHash,
                    success -> client.execute(() -> removeKeptPackFile(client, serverKey)));
        } else {
            removeKeptPackFile(client, serverKey);
        }
    }

    /**
     * Undoes {@link #persistKeptPackForRestart}: unapplies the copy from the live session (so it
     * disappears immediately when you're not on the server — e.g. after a relaunch where Minecraft
     * loaded it from options.txt), clears it from options.txt, and deletes the file.
     */
    public static void removeKeptPackFile(Minecraft client, String serverKey) {
        String fileName = PackPreferences.getCachedPackFile(serverKey);
        PackPreferences.recordCached(serverKey, null);
        if (fileName == null) return;
        client.execute(() -> ResourcePackInstaller.disablePack(fileName).whenComplete((v, t) ->
                client.execute(() -> deleteResourcepackArtifact(fileName))));
    }

    /** True if the given converted pack file is currently active in the normal resource pack list. */
    public static boolean isConvertedPackEnabled(String convertedPackFile) {
        return convertedPackFile != null && ResourcePackInstaller.isEnabled(convertedPackFile);
    }

    /**
     * True if a vanilla download happened this session for this server, so "/rpplus convert" (or the
     * config-screen button) has a specific cached file to convert and a server pack to swap out.
     * Used to enable/disable the config-screen Convert button per server.
     */
    public static boolean canConvertNow(String serverKey) {
        return lastVanillaDownloadPackId.containsKey(serverKey)
                || lastVanillaDownloadStartMillis.containsKey(serverKey)
                || lastVanillaDownloadHash.containsKey(serverKey);
    }

    // ---------- /rpplus convert: locate the vanilla-cached pack file and extract it into resourcepacks/<server name>/ ----------

    public static final class ConvertResult {
        public boolean success;
        public String message;
        public Path destination;
    }

    /** Minimum plausible size for a real resource pack zip — filters out marker/lock/empty files. */
    private static final long MIN_PLAUSIBLE_PACK_BYTES = 200;

    /**
     * The resourcepacks/ folder name a converted pack for this server gets: the server's name from
     * the multiplayer list (e.g. "Hypixel"), or its address/key if no list name is available,
     * made filesystem-safe. Stable per server, so re-converting the same server overwrites its
     * folder in place rather than piling up copies.
     */
    public static String folderNameForServer(String serverKey) {
        String raw = ServerKeyUtil.currentServerName();
        if (raw == null || raw.isBlank()) raw = serverKey;
        // Keep spaces as spaces (so "Hypixel Alpha" stays "Hypixel Alpha", not "Hypixel_Alpha").
        // Only characters that are actually unsafe in a folder name get turned into underscores.
        String safe = raw.replaceAll("[^a-zA-Z0-9._ -]", "_")
                .replaceAll(" {2,}", " ")   // collapse runs of spaces
                .replaceAll("_{2,}", "_");  // collapse runs of underscores
        // Trim separators/spaces from the ends — Windows silently strips trailing spaces/dots, and a
        // leading dot would make a hidden folder.
        safe = safe.strip().replaceAll("^[._-]+", "").replaceAll("[._-]+$", "").strip();
        return safe.isEmpty() ? "server" : safe;
    }

    /**
     * Finds the file Minecraft's own downloader cached for the last successful vanilla download on
     * this server and extracts it into {@code resourcepacks/<server name>/} as a normal, editable
     * folder pack, leaving no .zip behind. The original cached file under downloads/ is left
     * untouched.
     * <p>
     * Candidates are tried in order of reliability, and each candidate is verified to actually
     * look like a non-trivial zip (checked via the zip magic bytes + a minimum size) before we
     * accept it — if a candidate fails that check we move on to the next one rather than copying
     * garbage:
     * <ol>
     *   <li>Exact SHA-1 content match against the hash the server sent (most reliable, but many
     *       servers — Hypixel included — send a blank hash, so this often isn't available).</li>
     *   <li>The largest file anywhere under downloads/ whose last-modified time falls within the
     *       window of this specific download attempt (recorded by {@link #vanillaDownloadAndApply}).
     *       This deliberately ignores older, unrelated files sitting in downloads/ from past
     *       sessions, and prefers size over recency since small marker/metadata files can be
     *       touched around the same time as the real pack file.</li>
     *   <li>Last resort: the newest valid-looking zip anywhere under downloads/.</li>
     * </ol>
     */
    /**
     * Locates the file Minecraft's own downloader cached for the last successful vanilla download on
     * this server, trying (in order) an exact SHA-1 match, the largest file written during this
     * download's time window, then the newest valid-looking zip. Returns null if nothing plausible
     * is found. Shared by both Convert (which extracts it) and Cache (which copies it).
     */
    public static Path findLastVanillaDownload(String serverKey) {
        Path downloadsDir = FabricLoader.getInstance().getGameDir().resolve("downloads");
        if (!Files.exists(downloadsDir)) return null;
        try {
            String expectedHash = lastVanillaDownloadHash.get(serverKey);
            Long windowStart = lastVanillaDownloadStartMillis.get(serverKey);

            if (expectedHash != null && !expectedHash.isBlank()) {
                Path candidate = findFileByHash(downloadsDir, expectedHash);
                if (candidate != null && looksLikeZip(candidate)) return candidate;
            }
            if (windowStart != null) {
                // 3s buffer for filesystem timestamp granularity / clock skew.
                Path candidate = findLargestFileModifiedAfter(downloadsDir, windowStart - 3000);
                if (candidate != null && looksLikeZip(candidate)) return candidate;
            }
            return findNewestValidZip(downloadsDir);
        } catch (IOException e) {
            ResourcePackPlusClient.LOGGER.error("[Server Packs+] Failed locating last download for {}", serverKey, e);
            return null;
        }
    }

    public static ConvertResult convertLastVanillaDownload(String serverKey, DoubleConsumer onExtractProgress) {
        ConvertResult result = new ConvertResult();
        try {
            Path found = findLastVanillaDownload(serverKey);
            if (found == null) {
                result.message = "Couldn't find any file under downloads/ that looks like a valid resource pack zip.";
                return result;
            }

            Path packsDir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
            Files.createDirectories(packsDir);
            String folderName = folderNameForServer(serverKey);
            Path destination = packsDir.resolve(folderName);

            // Fresh extract: clear any previous contents so a re-convert can't leave stale files
            // that the new pack no longer ships. deleteRecursive is best-effort (ignores locked
            // files), and extractZip replaces anything that survives.
            deleteRecursive(destination);
            Files.createDirectories(destination);
            extractZip(found, destination, onExtractProgress);
            int extractedCount = countFiles(destination);
            if (extractedCount == 0) {
                throw new IOException("Cached file extracted but contained 0 files — it may not be a real resource pack zip");
            }

            result.success = true;
            result.destination = destination;
            result.message = "Extracted " + extractedCount + " files (" + Files.size(found) + " bytes) from "
                    + found + " -> " + destination;
            ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] convert: {}", result.message);
            return result;
        } catch (IOException e) {
            result.message = "Convert failed: " + e;
            ResourcePackPlusClient.LOGGER.error("[Server Packs+ DIAG] convert failed for {}", serverKey, e);
            return result;
        }
    }

    /** Reads the first 4 bytes and checks for a real zip local-file-header signature, plus a
     *  minimum size — filters out marker files, lock files, and truncated/empty downloads. */
    private static boolean looksLikeZip(Path p) {
        try {
            if (Files.size(p) < MIN_PLAUSIBLE_PACK_BYTES) return false;
            byte[] header = new byte[4];
            try (InputStream in = Files.newInputStream(p)) {
                int read = in.readNBytes(header, 0, 4);
                if (read < 4) return false;
            }
            // "PK\x03\x04" — standard zip local file header signature.
            return header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findFileByHash(Path root, String expectedHashHex) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                try {
                    if (sha1(p).equalsIgnoreCase(expectedHashHex)) {
                        return p;
                    }
                } catch (IOException ignored) {
                    // unreadable file (permissions, in-progress write, etc.) — skip it
                }
            }
        }
        return null;
    }

    private static Path findLargestFileModifiedAfter(Path root, long cutoffMillis) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() >= cutoffMillis;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .orElse(null);
        }
    }

    private static Path findNewestValidZip(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).reversed())
                    .filter(PackDownloadManager::looksLikeZip)
                    .findFirst()
                    .orElse(null);
        }
    }

    // ---------- Re-grab / merge update ----------

    public static void mergeUpdate(String serverKey, String url, String expectedHash, Consumer<MergeResult> onDone) {
        runAsync(() -> {
            MergeResult result = new MergeResult();
            Path zip = null;
            Path incoming = null;
            try {
                zip = downloadToTemp(url);
                verifyHashLoosely(zip, expectedHash);

                incoming = Files.createTempDirectory("resourcepackplus-incoming-");
                extractZip(zip, incoming);

                // Update straight into the converted, currently-active pack folder if we have one
                // (goal #4: pull the server's new/changed textures into the converted pack). Only
                // fall back to the legacy server_<key> folder if this server was never converted.
                Path packFolder;
                String convertedName = PackPreferences.getConvertedPackFile(serverKey);
                if (convertedName != null && !convertedName.isBlank()) {
                    packFolder = FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(convertedName);
                } else {
                    packFolder = packFolderFor(serverKey);
                }
                result.packFolder = packFolder;

                if (!Files.exists(packFolder)) {
                    // Nothing installed yet for this server — this is just a fresh install.
                    Files.createDirectories(packFolder);
                    copyRecursive(incoming, packFolder, null);
                    result.success = true;
                    result.addedCount = countFiles(incoming);
                    PackPreferences.recordInstalled(serverKey, url, expectedHash);
                    ResourcePackInstaller.refreshList(packFolder.getFileName().toString());
                    deleteRecursive(incoming);
                    onDone.accept(result);
                    return;
                }

                // Back up the existing pack before merging anything in.
                Path backup = backupsRootDir().resolve(serverKey.replaceAll("[^a-zA-Z0-9._-]", "_")
                        + "-" + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                Files.createDirectories(backup.getParent());
                copyRecursive(packFolder, backup, null);
                result.backupDir = backup;

                List<String> conflicts = new ArrayList<>();
                int[] added = {0};
                int[] unchanged = {0};

                try (Stream<Path> walk = Files.walk(incoming)) {
                    for (Path incomingFile : (Iterable<Path>) walk::iterator) {
                        if (Files.isDirectory(incomingFile)) continue;
                        Path relative = incoming.relativize(incomingFile);
                        Path existingFile = packFolder.resolve(relative.toString());

                        if (!Files.exists(existingFile)) {
                            Files.createDirectories(existingFile.getParent());
                            Files.copy(incomingFile, existingFile, StandardCopyOption.REPLACE_EXISTING);
                            added[0]++;
                        } else if (filesIdentical(incomingFile, existingFile)) {
                            unchanged[0]++;
                        } else {
                            conflicts.add(relative.toString().replace('\\', '/'));
                        }
                    }
                }

                result.addedCount = added[0];
                result.unchangedCount = unchanged[0];
                result.conflicts = conflicts;
                result.incomingTempDir = incoming; // caller resolves conflicts, then must clean this up
                result.success = true;

                PackPreferences.recordInstalled(serverKey, url, expectedHash);
                ResourcePackInstaller.refreshList(packFolder.getFileName().toString());
                onDone.accept(result);
            } catch (Exception e) {
                ResourcePackPlusClient.LOGGER.error("Pack re-grab failed for {}", serverKey, e);
                result.success = false;
                result.errorMessage = String.valueOf(e.getMessage());
                if (incoming != null) deleteQuietly(incoming);
                onDone.accept(result);
            } finally {
                deleteQuietly(zip);
            }
        });
    }

    /** Applies a single conflict resolution: copies the incoming (new) version over the existing file. */
    public static void applyTakeNew(Path incomingTempDir, Path packFolder, String relativePath) {
        try {
            Path from = incomingTempDir.resolve(relativePath);
            Path to = packFolder.resolve(relativePath);
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            ResourcePackPlusClient.LOGGER.error("Failed to apply updated texture {}", relativePath, e);
        }
    }

    /** Restores the most recent backup for a server, overwriting the current pack folder. */
    public static boolean restoreLatestBackup(String serverKey) {
        try {
            Path root = backupsRootDir();
            if (!Files.exists(root)) return false;
            String prefix = serverKey.replaceAll("[^a-zA-Z0-9._-]", "_") + "-";
            Path latest = null;
            try (Stream<Path> stream = Files.list(root)) {
                for (Path candidate : (Iterable<Path>) stream.filter(p -> p.getFileName().toString().startsWith(prefix)).sorted()::iterator) {
                    latest = candidate; // sorted lexicographically; timestamp format sorts chronologically too
                }
            }
            if (latest == null) return false;

            Path packFolder = packFolderFor(serverKey);
            deleteRecursive(packFolder);
            Files.createDirectories(packFolder);
            copyRecursive(latest, packFolder, null);
            ResourcePackInstaller.refreshList(packFolder.getFileName().toString());
            return true;
        } catch (IOException e) {
            ResourcePackPlusClient.LOGGER.error("Failed to restore backup for {}", serverKey, e);
            return false;
        }
    }

    public static boolean hasBackup(String serverKey) {
        Path root = backupsRootDir();
        if (!Files.exists(root)) return false;
        String prefix = serverKey.replaceAll("[^a-zA-Z0-9._-]", "_") + "-";
        try (Stream<Path> stream = Files.list(root)) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        } catch (IOException e) {
            return false;
        }
    }

    /** Deletes a temp directory created during a merge (e.g. after conflict resolution finishes). */
    public static void cleanupTempDir(Path dir) {
        deleteQuietly(dir);
    }

    // ---------- Helpers ----------

    private static void runAsync(Runnable task) {
        Thread thread = new Thread(task, "Server Packs+-Worker");
        thread.setDaemon(true);
        thread.start();
    }

    private static Path downloadToTemp(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // default is NEVER — many pack CDNs redirect at least once
                .version(HttpClient.Version.HTTP_1_1) // HTTP/2 streams seem to be getting corrupted somewhere on this network path
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ResourcePackPlus/0.1.0 (Fabric mod; Minecraft resource pack fetch)")
                .timeout(java.time.Duration.ofMinutes(5))
                .GET().build();

        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Path tempFile = Files.createTempFile("resourcepackplus-", ".zip");
            try {
                // NOTE: passing explicit OpenOptions REPLACES the defaults, so WRITE must be included
                // — without it the response file channel opens read-only and every write throws
                // NonWritableChannelException (the default no-option ofFile uses CREATE, WRITE, TRUNCATE_EXISTING).
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(
                        tempFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
                ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] Attempt {} — HTTP {} — content-type: {} — content-length header: {}",
                        attempt, response.statusCode(),
                        response.headers().firstValue("content-type").orElse("(none)"),
                        response.headers().firstValue("content-length").orElse("(none)"));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " downloading pack from " + url);
                }
                return tempFile;
            } catch (IOException e) {
                lastError = e;
                ResourcePackPlusClient.LOGGER.warn("[Server Packs+ DIAG] Attempt {} failed ({}), {}",
                        attempt, e, attempt < 3 ? "retrying..." : "giving up.");
                deleteQuietly(tempFile);
                if (attempt < 3) {
                    Thread.sleep(1000L * attempt); // brief backoff before retrying
                }
            }
        }
        throw lastError;
    }

    private static void verifyHashLoosely(Path file, String expectedSha1) throws IOException {
        if (expectedSha1 == null || expectedSha1.isEmpty()) return;
        String actual = sha1(file);
        if (!actual.equalsIgnoreCase(expectedSha1)) {
            // Some servers send a stale/blank hash — log it but don't block the install on it.
            ResourcePackPlusClient.LOGGER.warn("Pack hash mismatch (expected {}, got {}) — installing anyway", expectedSha1, actual);
        }
    }

    /**
     * Extracts a resource-pack zip into {@code destDir}. Uses {@link ZipFile} (which reads the zip's
     * central directory) rather than {@link java.util.zip.ZipInputStream} (which reads local file
     * headers sequentially). This matters because some servers — Wynncraft is one — ship "hardened"
     * packs whose <em>local</em> headers are deliberately poisoned with bogus sizes/dates to break
     * naive stream extractors (ZipInputStream stops after the first entry). The central directory is
     * intact, so ZipFile extracts them fully — this is also exactly how vanilla reads the pack.
     */
    private static void extractZip(Path zipFile, Path destDir) throws IOException {
        extractZip(zipFile, destDir, null);
    }

    /**
     * @param onProgress optional callback fed a 0.0–1.0 completion fraction as files are written.
     *                   Fires at most once per whole-percent change (so ~100 calls total, cheap
     *                   enough to drive a progress bar / action-bar readout). May be null.
     */
    private static void extractZip(Path zipFile, Path destDir, DoubleConsumer onProgress) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            int total = zip.size();
            int done = 0;
            int lastPct = -1;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = destDir.resolve(entry.getName()).normalize();
                if (target.startsWith(destDir)) { // guard against zip-slip
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                done++;
                if (onProgress != null && total > 0) {
                    int pct = (int) (done * 100L / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        onProgress.accept(done / (double) total);
                    }
                }
            }
        }
    }

    private static boolean filesIdentical(Path a, Path b) throws IOException {
        if (Files.size(a) != Files.size(b)) return false;
        return sha1(a).equals(sha1(b));
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static int countFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }

    private static void copyRecursive(Path source, Path target, Void unused) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path dest = target.resolve(source.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            if (Files.isDirectory(path)) deleteRecursive(path);
            else Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    /**
     * Deletes a converted-pack artifact (either an extracted folder or an old .zip) from
     * resourcepacks/ by name. Refuses to touch anything outside resourcepacks/ as a guard against a
     * malformed recorded name.
     */
    private static void deleteResourcepackArtifact(String name) {
        if (name == null || name.isBlank()) return;
        Path packsDir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks").normalize();
        Path target = packsDir.resolve(name).normalize();
        if (!target.startsWith(packsDir) || target.equals(packsDir)) {
            ResourcePackPlusClient.LOGGER.warn("[Server Packs+] Refusing to delete artifact outside resourcepacks/: {}", name);
            return;
        }
        if (!Files.exists(target)) return;
        deleteQuietly(target);
        ResourcePackPlusClient.LOGGER.info("[Server Packs+] Removed previous converted artifact {}", target);
    }
}
