package com.rpplus.resourcepackplus.mixin;

import com.rpplus.resourcepackplus.ResourcePackPlusClient;
import com.rpplus.resourcepackplus.core.PackDownloadManager;
import net.minecraft.client.resources.server.DownloadedPackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Cache" = keep the server pack applied across a disconnect. On disconnect, Minecraft calls
 * {@code clearDownloadedResourcePacks()} -> {@link DownloadedPackSource#cleanupAfterDisconnect()},
 * which removes all server packs (and resets the manager). When the just-left server is marked
 * "keep applied" (recorded in {@link ResourcePackPushMixin}'s onDisconnect hook), we cancel that
 * cleanup so the pack's textures stay applied. Nothing is copied — the real pack is left in place.
 * The next connection reconfigures the manager, so skipping the reset here is safe.
 */
@Mixin(DownloadedPackSource.class)
public class DownloadedPackSourceMixin {

    @Inject(method = "cleanupAfterDisconnect", at = @At("HEAD"), cancellable = true)
    private void resourcepackplus$keepAppliedOnDisconnect(CallbackInfo ci) {
        String serverKey = PackDownloadManager.consumeKeepAppliedOnDisconnect();
        if (serverKey != null) {
            ResourcePackPlusClient.LOGGER.info(
                    "[Server Packs+] Keeping server pack applied for {} (cache = keep applied) — skipping disconnect cleanup", serverKey);
            ci.cancel();
        }
    }
}
