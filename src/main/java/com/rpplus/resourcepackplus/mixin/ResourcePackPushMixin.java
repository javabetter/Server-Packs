package com.rpplus.resourcepackplus.mixin;

import com.rpplus.resourcepackplus.ResourcePackPlusClient;
import com.rpplus.resourcepackplus.core.PackDownloadManager;
import com.rpplus.resourcepackplus.core.PackPreferences;
import com.rpplus.resourcepackplus.core.ServerKeyUtil;
import com.rpplus.resourcepackplus.gui.PackPromptScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.UUID;

/**
 * Intercepts the server->client resource pack push packet before vanilla's own handler runs.
 *
 * NOTE FOR PORTING: the two things most likely to need small renames on a new Minecraft
 * version are (1) the "handleResourcePackPush" method name below, and (2) the
 * ServerboundResourcePackPacket constructor / Action enum names. Everything else in this
 * class is our own code and won't need changes.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ResourcePackPushMixin {

    // Lets us call the target class's own (public) `send` method from this mixin.
    @Shadow
    public abstract void send(Packet<?> packet);

    // The listener's own connection + server info. Unlike Minecraft.getCurrentServer() (which is
    // null while the resource pack is pushed during the config/transfer phase), these are populated
    // on the listener immediately — so we key per-server off them instead of getting "unknown-server".
    @Shadow @Final protected Connection connection;
    @Shadow @Final protected ServerData serverData;

    /** Stable per-server key that works during the configuration phase. Prefers the multiplayer-list
     *  address (also lets the config screen match the server's icon/name), falling back to the live
     *  connection's remote address. */
    private String resourcepackplus$serverKey() {
        if (this.serverData != null && this.serverData.ip != null && !this.serverData.ip.isBlank()) {
            return this.serverData.ip.toLowerCase(Locale.ROOT);
        }
        return ServerKeyUtil.fromConnection(this.connection);
    }

    /** Phase-1 diagnostic helper: logs every outbound status so the exact handshake sequence we
     *  send is visible in the log, without changing any behavior. Remove once Wynncraft-style
     *  compliance is understood/implemented. */
    private void diagSend(String serverKey, UUID packId, ServerboundResourcePackPacket.Action action) {
        ResourcePackPlusClient.LOGGER.info("[Server Packs+ DIAG] {} <- sending {} for pack {}", serverKey, action, packId);
        send(new ServerboundResourcePackPacket(packId, action));
    }

    private static void notifyResult(Minecraft client, boolean success, String serverKey) {
        String msg = success
                ? "[Server Packs+] Pack installed for " + serverKey
                : "[Server Packs+] Pack download FAILED for " + serverKey + " — see log for details.";
        if (success) {
            ResourcePackPlusClient.LOGGER.info(msg);
        } else {
            ResourcePackPlusClient.LOGGER.error(msg);
        }
        if (client.player != null) {
            client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
        }
    }

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void resourcepackplus$onPackPush(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        String serverKey = resourcepackplus$serverKey();

        // Per-server master switch (goal #7): if the player turned Server Packs+ off for this
        // server, don't intercept at all — let vanilla show its own prompt and handle the pack.
        if (!PackPreferences.isEnabled(serverKey)) {
            ResourcePackPlusClient.LOGGER.info("Server Packs+ is disabled for {} — letting vanilla handle its pack", serverKey);
            return;
        }

        ci.cancel(); // We fully take over handling — vanilla's prompt never shows.

        UUID packId = packet.id();
        String url = packet.url();
        String hash = packet.hash();
        boolean required = packet.required();

        // Remember where the server is offering its pack so "Update from server" can re-download later.
        PackPreferences.recordServerOffer(serverKey, url, hash);

        ResourcePackPlusClient.LOGGER.info(
                "[Server Packs+ DIAG] {} pushed pack {} — url='{}' hash='{}' required={}",
                serverKey, packId, url, hash, required);

        PackPreferences.Choice existing = PackPreferences.get(serverKey);
        Minecraft client = Minecraft.getInstance();

        // Remember which server's pack we're handling, so the disconnect cleanup knows whether to
        // keep it applied (this fires for user-initiated disconnects, which skip onDisconnect).
        PackDownloadManager.setCurrentServerKey(serverKey);

        // "Cache" = keep applied. The pack is already loaded when either (a) we kept the server pack
        // applied across a disconnect this session, or (b) after a restart, Minecraft loaded our saved
        // copy from options.txt. Either way, just confirm it — no download, no reload.
        if (PackPreferences.isKeepApplied(serverKey)
                && (PackDownloadManager.isServerPackKept(serverKey) || PackDownloadManager.isKeptCopyApplied(serverKey))) {
            ResourcePackPlusClient.LOGGER.info("[Server Packs+] {} pack already kept applied — accepting without reload", serverKey);
            diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
            return;
        }

        // Some servers (e.g. CubeCraft) first push a placeholder pack with a non-HTTP URL like
        // "cdn://cubecraft_java" and immediately follow with the real .zip. We can't download a
        // non-HTTP URL, so skip it quietly (no prompt) — the real push arrives right after.
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            ResourcePackPlusClient.LOGGER.info("[Server Packs+] Skipping non-downloadable pack URL '{}' for {} (waiting for a real one)", url, serverKey);
            diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.INVALID_URL);
            return;
        }

        if (existing == PackPreferences.Choice.ACCEPT_EACH_TIME) {
            ResourcePackPlusClient.LOGGER.info("Re-fetching fresh pack for {} (accept each time)", serverKey);
            diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.ACCEPTED);
            PackDownloadManager.vanillaDownloadAndApply(client, serverKey, packId, url, hash, success -> client.execute(() -> {
                var action = success
                        ? ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                        : ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD;
                diagSend(serverKey, packId, action);
                notifyResult(client, success, serverKey);
            }));
            return;
        }
        if (existing == PackPreferences.Choice.ACCEPT_AND_CACHE) {
            // "Accept" — behave like vanilla. If this server's pack is already kept applied (cached
            // or converted), there's nothing to reload; just confirm it loaded. Otherwise download
            // and apply it for this session, exactly as vanilla would.
            if (PackDownloadManager.applyCachedConvertedPack(client, serverKey)) {
                ResourcePackPlusClient.LOGGER.info("Pack already applied for {} — accepting without a download", serverKey);
                diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
            } else {
                ResourcePackPlusClient.LOGGER.info("Accepting + downloading pack for {} (vanilla-style)", serverKey);
                diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.ACCEPTED);
                PackDownloadManager.vanillaDownloadAndApply(client, serverKey, packId, url, hash, success -> client.execute(() -> {
                    diagSend(serverKey, packId, success
                            ? ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                            : ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD);
                    notifyResult(client, success, serverKey);
                }));
            }
            return;
        }
        if (existing == PackPreferences.Choice.REJECT_AND_JOIN) {
            ResourcePackPlusClient.LOGGER.info("Auto-skipping pack download for {}", serverKey);
            diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
            return;
        }

        // ASK_EACH_TIME (the default) or a brand-new server: prompt on the FIRST join, then remember
        // whatever the player picks so we don't ask again.
        client.execute(() -> client.setScreenAndShow(new PackPromptScreen(
                serverKey, url, hash, required,
                () -> { // "Download & apply" — download + apply, and remember "Accept" for next time
                    diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.ACCEPTED);
                    PackDownloadManager.vanillaDownloadAndApply(client, serverKey, packId, url, hash, success -> client.execute(() -> {
                        diagSend(serverKey, packId, success
                                ? ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                                : ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD);
                        if (success) {
                            PackPreferences.setChoice(serverKey, PackPreferences.Choice.ACCEPT_AND_CACHE);
                        }
                        // On failure we deliberately do NOT remember any choice, so the next join
                        // re-prompts and genuinely retries.
                        notifyResult(client, success, serverKey);
                    }));
                },
                () -> { // "Join without downloading" — remember "Reject"
                    PackPreferences.setChoice(serverKey, PackPreferences.Choice.REJECT_AND_JOIN);
                    diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
                },
                () -> // "Reject" / "Disconnect" — a true one-time decline, never remembered
                        diagSend(serverKey, packId, ServerboundResourcePackPacket.Action.DECLINED)
        )));
    }
}
