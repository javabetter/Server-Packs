package com.rpplus.resourcepackplus;

import com.mojang.brigadier.Command;
import com.rpplus.resourcepackplus.core.PackDownloadManager;
import com.rpplus.resourcepackplus.core.ServerKeyUtil;
import com.rpplus.resourcepackplus.gui.ConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Client entrypoint. Kept intentionally thin — real logic lives in the
 * version-agnostic `core` package and the version-specific `compat`/`mixin`
 * packages, so porting to a new Minecraft version means touching those two
 * folders, not this one.
 */
public class ResourcePackPlusClient implements ClientModInitializer {
    public static final String MOD_ID = "resourcepackplus";
    public static final Logger LOGGER = LoggerFactory.getLogger("Server Packs+");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Server Packs+ loaded.");

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("serverpacksplus")
                    .executes(ResourcePackPlusClient::openConfig)
                    .then(literal("convert").executes(ResourcePackPlusClient::convertDownload)));
            dispatcher.register(literal("spplus")
                    .executes(ResourcePackPlusClient::openConfig)
                    .then(literal("convert").executes(ResourcePackPlusClient::convertDownload)));
        });
    }

    private static int openConfig(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        // Minecraft no longer exposes a "current screen" accessor to pass as a parent — a
        // command invocation never has a meaningful screen to return to anyway.
        client.execute(() -> client.setScreenAndShow(new ConfigScreen(null)));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * DEBUG STEP command: takes the file Minecraft's own downloader cached for the last
     * successful "/rpplus" vanilla download on this server, duplicates it into resourcepacks/
     * as a .zip, then forcibly removes the original server-pushed pack and applies the
     * converted copy in its place. Run this right after "Download & apply" has succeeded
     * (check the log for "Vanilla download/apply SUCCEEDED" first).
     */
    private static int convertDownload(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        String serverKey = ServerKeyUtil.currentServerKey();
        PackDownloadManager.convertAndApply(client, serverKey,
                status -> client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendSystemMessage(Component.literal("[Server Packs+] " + status));
                    } else {
                        LOGGER.info("[Server Packs+] {}", status);
                    }
                }),
                // Live extraction progress → the action bar (above the hotbar), which overwrites
                // itself each update so we get a smooth percentage without spamming chat.
                fraction -> client.execute(() -> {
                    if (client.player != null) {
                        int pct = (int) Math.round(fraction * 100);
                        client.player.sendOverlayMessage(
                                Component.literal("§b[Server Packs+]§r Extracting pack… " + pct + "%"));
                    }
                }),
                success -> { /* final status already reported through the status callback above */ });
        return Command.SINGLE_SUCCESS;
    }
}
