package com.rpplus.resourcepackplus.core;

import com.rpplus.resourcepackplus.ResourcePackPlusClient;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.concurrent.CompletableFuture;

/**
 * Thin bridge into Minecraft's resource pack repository. This is the one piece of this
 * file that genuinely needs Minecraft API calls, so it's kept tiny and isolated — if a
 * future version renames these methods, this is the only place to fix.
 *
 * NOTE FOR PORTING: the method names most likely to need a rename on a new Minecraft
 * version are {@code getResourcePackRepository}, {@code PackRepository#addPack/removePack/
 * setSelected/getSelectedIds/getAvailableIds/reload}, {@code Options#updateResourcePacks},
 * and {@code Minecraft#reloadResourcePacks}. Everything else here is our own logic.
 */
public final class ResourcePackInstaller {

    private ResourcePackInstaller() {}

    /**
     * The repository id a folder-source resource pack file gets. Vanilla's client-side
     * resourcepacks/ folder source uses the "file" prefix, so a file named
     * {@code foo.zip} is addressed as {@code file/foo.zip}.
     */
    public static String repositoryIdFor(String zipFileName) {
        return "file/" + zipFileName;
    }

    /**
     * Re-scans the resourcepacks folder so a newly-downloaded pack shows up in the
     * pack selection screen immediately, without requiring the player to restart
     * the game or reopen the folder manually.
     */
    public static void refreshList(String newlyAddedFileName) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                client.getResourcePackRepository().reload();
                ResourcePackPlusClient.LOGGER.info("Installed resource pack: {}", newlyAddedFileName);
            } catch (Exception e) {
                ResourcePackPlusClient.LOGGER.error("Failed to refresh resource pack list", e);
            }
        });
    }

    /**
     * Triggers a full client resource reload so freshly-merged files in an already-active pack
     * folder take effect (used after "Update from server" adds/updates textures). Must be called
     * on the render/client thread. Returns the reload future.
     */
    public static CompletableFuture<Void> reloadActiveResources() {
        return Minecraft.getInstance().reloadResourcePacks();
    }

    /**
     * Synchronously drops any server-controlled packs (id {@code "server/..."}) from the repository's
     * selection. Convert calls this right after {@code DownloadedPackSource.popPack}, whose own removal
     * is asynchronous — without this the popped server pack is still selected when we write options.txt,
     * so it gets persisted, and the Resource Packs screen then reloads later when it recomputes the
     * (now server-pack-free) selection and finds it differs from options.txt.
     */
    public static void removeServerPacksFromSelection() {
        PackRepository repo = Minecraft.getInstance().getResourcePackRepository();
        for (String id : new java.util.ArrayList<>(repo.getSelectedIds())) {
            if (id.startsWith("server/")) {
                repo.removePack(id);
                ResourcePackPlusClient.LOGGER.info("[Server Packs+] Dropped server pack {} from selection before persisting", id);
            }
        }
    }

    /** True if the given resourcepacks/ file is currently in the active (selected) pack list. */
    public static boolean isEnabled(String zipFileName) {
        PackRepository repo = Minecraft.getInstance().getResourcePackRepository();
        return repo.getSelectedIds().contains(repositoryIdFor(zipFileName));
    }

    /**
     * Enables a resourcepacks/ pack as a <em>normal</em>, user-toggleable resource pack (the same
     * thing that happens when you tick it on in vanilla's Resource Packs screen), persists the
     * selection to options.txt, and triggers a resource reload so its textures take effect.
     * <p>
     * Unlike the server-pack mechanism this survives disconnects and appears in the normal pack
     * list — exactly what "convert a server pack into a regular, usable pack" is supposed to mean.
     * <p>
     * MUST be called on the render/client thread (callers already are, via {@code client.execute}
     * or a GUI button handler). Returns the reload future, or a completed-exceptionally future if
     * the pack file couldn't be found in the repository after a rescan.
     */
    public static CompletableFuture<Void> enablePack(String zipFileName) {
        return switchToPack(zipFileName, null);
    }

    /**
     * Enables {@code newZipFileName} as a normal pack and, in the same reload, drops
     * {@code previousZipFileName} if it's non-null and different (so repeated conversions of the
     * same server don't leave a stack of stale converted packs all active at once). Passing null
     * for the previous name just enables the new pack. See {@link #enablePack(String)} for the
     * threading/return-value contract.
     */
    public static CompletableFuture<Void> switchToPack(String newZipFileName, String previousZipFileName) {
        Minecraft client = Minecraft.getInstance();
        PackRepository repo = client.getResourcePackRepository();
        repo.reload(); // make sure a freshly-copied file is discovered
        String newId = repositoryIdFor(newZipFileName);
        if (!repo.getAvailableIds().contains(newId)) {
            ResourcePackPlusClient.LOGGER.error(
                    "[Server Packs+] Can't enable '{}' — not found in resource pack repository after rescan. Available: {}",
                    newId, repo.getAvailableIds());
            return CompletableFuture.failedFuture(new IllegalStateException("resource pack not found: " + newId));
        }
        if (previousZipFileName != null && !previousZipFileName.equals(newZipFileName)) {
            repo.removePack(repositoryIdFor(previousZipFileName)); // no-op if it wasn't active
        }
        boolean changed = repo.addPack(newId);
        // Re-scan so the selection settles into the repository's natural order (the same order the
        // vanilla Resource Packs screen recomputes on open/close). Writing options.txt in this order
        // means closing that screen afterwards won't detect a phantom difference and reload again.
        repo.reload();
        // A server pack being replaced (convert) may have been re-selected by the reload above, because
        // DownloadedPackSource.popPack's removal is asynchronous and it auto-selects while still pending.
        // Drop server packs here — AFTER the last reload, just before we persist — so options.txt is
        // written without them (otherwise the transient server pack would be persisted and then trigger
        // a screen reload once it's actually gone). persistAndReload only does a resource reload, not a
        // repository rescan, so nothing re-selects it after this point.
        removeServerPacksFromSelection();
        ResourcePackPlusClient.LOGGER.info(
                "[Server Packs+] Enabled converted pack {} (newly added: {}). Selected now: {}",
                newId, changed, repo.getSelectedIds());
        return persistAndReload(client, repo);
    }

    /**
     * Removes a resourcepacks/ pack from the active list, persists the change, and reloads.
     * MUST be called on the render/client thread. Returns the reload future (or a no-op completed
     * future if the pack wasn't selected in the first place).
     */
    public static CompletableFuture<Void> disablePack(String zipFileName) {
        Minecraft client = Minecraft.getInstance();
        PackRepository repo = client.getResourcePackRepository();
        String id = repositoryIdFor(zipFileName);
        if (!repo.getSelectedIds().contains(id)) {
            // Not applied live, but it may still be referenced in options.txt (e.g. added there for
            // restart persistence without a live reload) — make sure it's cleared there too.
            boolean removed = client.options.resourcePacks.remove(id);
            client.options.incompatibleResourcePacks.remove(id);
            if (removed) client.options.save();
            return CompletableFuture.completedFuture(null);
        }
        repo.removePack(id);
        ResourcePackPlusClient.LOGGER.info(
                "[Server Packs+] Disabled pack {}. Selected now: {}", id, repo.getSelectedIds());
        return persistAndReload(client, repo);
    }

    /**
     * Writes the repository's current selection into options.txt and reloads, returning the reload
     * future. This is intentionally a byte-for-byte copy of vanilla's {@code Options.updateResourcePacks}
     * (same filters, same order, reload ONLY when the persisted list actually changed). Matching it
     * exactly matters: the vanilla Resource Packs screen runs {@code updateResourcePacks} on close and
     * reloads only if what it recomputes differs from options.txt — so if we wrote options.txt any
     * differently (e.g. skipping server packs), closing that screen would trigger a spurious reload.
     */
    private static CompletableFuture<Void> persistAndReload(Minecraft client, PackRepository repo) {
        java.util.List<String> before = new java.util.ArrayList<>(client.options.resourcePacks);
        client.options.resourcePacks.clear();
        client.options.incompatibleResourcePacks.clear();
        for (Pack pack : repo.getSelectedPacks()) {
            if (pack.isFixedPosition()) continue; // vanilla/built-in/mod packs aren't stored in options
            client.options.resourcePacks.add(pack.getId());
            if (!pack.getCompatibility().isCompatible()) {
                client.options.incompatibleResourcePacks.add(pack.getId());
            }
        }
        client.options.save();
        if (!client.options.resourcePacks.equals(before)) {
            return client.reloadResourcePacks();
        }
        return CompletableFuture.completedFuture(null);
    }
}
