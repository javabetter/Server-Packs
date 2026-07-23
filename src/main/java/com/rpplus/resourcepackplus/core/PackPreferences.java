package com.rpplus.resourcepackplus.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rpplus.resourcepackplus.ResourcePackPlusClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers, per server, what the player wants done with that server's resource pack, plus
 * global mod settings. Pure file I/O + Gson — no Minecraft-version-specific code, so this
 * file should never need changes when porting to a newer Minecraft version.
 */
public final class PackPreferences {

    /** What happens automatically each time this server pushes its pack. */
    public enum Choice {
        /** Default for a new server: prompt on the first join, then remember whatever is picked. */
        ASK_EACH_TIME,
        /** Re-download and reinstall fresh every time you join — always up to date, no prompt. */
        ACCEPT_EACH_TIME,
        /** "Accept": accept + apply the pack like vanilla. Reuses an already-applied cached/converted
         *  copy with no reload; otherwise downloads and applies it. (Legacy enum name.) */
        ACCEPT_AND_CACHE,
        /** Never download; tell the server the pack loaded successfully anyway — no prompt. */
        REJECT_AND_JOIN
    }

    /** How re-grab should handle a file that exists locally but differs from the server's copy. */
    public enum ConflictPolicy {
        ASK, KEEP_MINE, TAKE_NEW
    }

    public static class ServerEntry {
        /** Master per-server switch: when false, Server Packs+ ignores this server and lets vanilla
         *  handle its pack prompt normally. */
        public boolean enabled = true;
        public Choice choice = Choice.ASK_EACH_TIME;
        public String lastUrl;
        public String lastHash;
        public long lastUpdatedMillis;
        public ConflictPolicy conflictPolicy = ConflictPolicy.ASK;
        /**
         * File name (inside resourcepacks/) of the converted, EDITABLE resource pack for this
         * server, if one has been made via Convert. Null until converted at least once.
         */
        public String convertedPackFile;
        /**
         * "Cache" = keep applied. When true, Server Packs+ stops the vanilla server pack from being
         * removed when you disconnect, so its textures stay applied in-session.
         */
        public boolean keepApplied = false;
        /**
         * File name (inside resourcepacks/) of the copy saved so a kept pack also survives a full
         * game restart (added to options.txt so Minecraft loads it on startup). Null if none.
         */
        public String cachedPackFile;
    }

    public static class GlobalSettings {
        public boolean verboseLogging = false;
    }

    private static class RootData {
        Map<String, ServerEntry> servers = new LinkedHashMap<>();
        GlobalSettings global = new GlobalSettings();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("resourcepackplus").resolve("server_preferences.json");

    private static RootData data;

    private PackPreferences() {}

    public static synchronized Choice get(String serverKey) {
        load();
        ServerEntry entry = data.servers.get(serverKey);
        return entry == null ? null : entry.choice;
    }

    public static synchronized ServerEntry getEntry(String serverKey) {
        load();
        return data.servers.get(serverKey);
    }

    /** Returns a snapshot of every server with a remembered entry, for display in the config screen. */
    public static synchronized Map<String, ServerEntry> getAll() {
        load();
        return new LinkedHashMap<>(data.servers);
    }

    public static synchronized GlobalSettings getGlobalSettings() {
        load();
        return data.global;
    }

    public static synchronized void setGlobalSettings(GlobalSettings settings) {
        load();
        data.global = settings;
        save();
    }

    /** Rearranges the stored servers to match {@code orderedKeys} (used by the config screen's
     *  drag-to-reorder). Keys not present are skipped; any stored keys not listed are appended in
     *  their existing order, so nothing is ever lost even if the two lists disagree. */
    public static synchronized void setOrder(java.util.List<String> orderedKeys) {
        load();
        LinkedHashMap<String, ServerEntry> reordered = new LinkedHashMap<>();
        for (String key : orderedKeys) {
            ServerEntry entry = data.servers.get(key);
            if (entry != null) reordered.put(key, entry);
        }
        for (Map.Entry<String, ServerEntry> e : data.servers.entrySet()) {
            reordered.putIfAbsent(e.getKey(), e.getValue());
        }
        data.servers = reordered;
        save();
    }

    /** Forgets everything about one server, so the player is prompted again on next join. */
    public static synchronized void reset(String serverKey) {
        load();
        data.servers.remove(serverKey);
        save();
    }

    /** Forgets every remembered server. */
    public static synchronized void clearAll() {
        load();
        data.servers.clear();
        save();
    }

    /** Sets (or creates) the behavior choice for a server, without touching its other fields. */
    public static synchronized void setChoice(String serverKey, Choice choice) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.choice = choice;
        save();
    }

    /** Turns Server Packs+ on or off for a single server. */
    public static synchronized void setEnabled(String serverKey, boolean enabled) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.enabled = enabled;
        save();
    }

    /** True unless this server has an entry that's explicitly disabled. */
    public static synchronized boolean isEnabled(String serverKey) {
        load();
        ServerEntry entry = data.servers.get(serverKey);
        return entry == null || entry.enabled;
    }

    /** Records the URL/hash the server most recently offered its pack at, so "Update from server"
     *  knows where to re-download from. Does not bump lastUpdatedMillis (that tracks real installs). */
    public static synchronized void recordServerOffer(String serverKey, String url, String hash) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.lastUrl = url;
        entry.lastHash = hash;
        save();
    }

    /** Records that a pack was just (re)installed for this server, e.g. after a download or re-grab. */
    public static synchronized void recordInstalled(String serverKey, String url, String hash) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.lastUrl = url;
        entry.lastHash = hash;
        entry.lastUpdatedMillis = System.currentTimeMillis();
        save();
    }

    /** Records the file name of the converted, normal resource pack made for this server. */
    public static synchronized void recordConverted(String serverKey, String convertedPackFile) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.convertedPackFile = convertedPackFile;
        save();
    }

    /** The converted pack file name recorded for this server, or null if none. */
    public static synchronized String getConvertedPackFile(String serverKey) {
        load();
        ServerEntry entry = data.servers.get(serverKey);
        return entry == null ? null : entry.convertedPackFile;
    }

    /** Turns "keep applied" (Cache) on/off for a server. */
    public static synchronized void setKeepApplied(String serverKey, boolean keep) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.keepApplied = keep;
        save();
    }

    /** True if this server's pack should be kept applied when you disconnect. */
    public static synchronized boolean isKeepApplied(String serverKey) {
        load();
        ServerEntry entry = data.servers.get(serverKey);
        return entry != null && entry.keepApplied;
    }

    /** Records (or clears, with null) the restart-persistence copy file name for a server. */
    public static synchronized void recordCached(String serverKey, String cachedPackFile) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.cachedPackFile = cachedPackFile;
        save();
    }

    public static synchronized String getCachedPackFile(String serverKey) {
        load();
        ServerEntry entry = data.servers.get(serverKey);
        return entry == null ? null : entry.cachedPackFile;
    }

    public static synchronized void setConflictPolicy(String serverKey, ConflictPolicy policy) {
        load();
        ServerEntry entry = data.servers.computeIfAbsent(serverKey, k -> new ServerEntry());
        entry.conflictPolicy = policy;
        save();
    }

    private static void load() {
        if (data != null) return;
        data = new RootData();
        if (Files.exists(FILE)) {
            try {
                String json = Files.readString(FILE);
                RootData loaded = GSON.fromJson(json, RootData.class);
                if (loaded != null) {
                    if (loaded.servers != null) data.servers = loaded.servers;
                    if (loaded.global != null) data.global = loaded.global;
                }
            } catch (IOException e) {
                ResourcePackPlusClient.LOGGER.warn("Failed to load Server Packs+ preferences", e);
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data));
        } catch (IOException e) {
            ResourcePackPlusClient.LOGGER.warn("Failed to save Server Packs+ preferences", e);
        }
    }
}
