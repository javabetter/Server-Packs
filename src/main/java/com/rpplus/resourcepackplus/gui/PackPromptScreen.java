package com.rpplus.resourcepackplus.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Replaces vanilla's "Accept / Reject" server resource pack prompt with a third option:
 * telling the server the pack was accepted and applied, while never actually downloading it.
 */
public class PackPromptScreen extends Screen {

    private final boolean required;
    private final Runnable onDownload;
    private final Runnable onSkip;
    private final Runnable onReject;

    public PackPromptScreen(String serverKey, String url, String hash, boolean required,
                             Runnable onDownload, Runnable onSkip, Runnable onReject) {
        super(Component.literal("Server Resource Pack"));
        this.required = required;
        this.onDownload = onDownload;
        this.onSkip = onSkip;
        this.onReject = onReject;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 10;

        this.addRenderableWidget(Button.builder(Component.literal("Download & apply pack"), b -> {
            onDownload.run();
            this.onClose();
        }).bounds(centerX - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Join without downloading"), b -> {
            onSkip.run();
            this.onClose();
        }).bounds(centerX - 100, y + 24, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal(required ? "Disconnect" : "Reject pack"), b -> {
            onReject.run();
            this.onClose();
        }).bounds(centerX - 100, y + 48, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        guiGraphics.centeredText(this.font,
                Component.literal("This server offers a custom resource pack."),
                this.width / 2, this.height / 2 - 45, 0xA0A0A0);
        if (required) {
            guiGraphics.centeredText(this.font,
                    Component.literal("The server marked this pack as required."),
                    this.width / 2, this.height / 2 - 33, 0xFF8080);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Force an explicit choice, matching vanilla's behavior for required packs.
        return false;
    }
}
