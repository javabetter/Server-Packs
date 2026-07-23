package com.rpplus.resourcepackplus.gui;

import com.rpplus.resourcepackplus.core.PackDownloadManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shown after a "Re-grab" when the server's pack has files with the same name as ones you
 * already have, but different content (i.e. the server updated an existing texture). Lets
 * you choose, per file, whether to take the server's updated version or keep your own.
 */
public class ConflictResolutionScreen extends Screen {

    private static final int ROWS_PER_PAGE = 6;
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final Path incomingTempDir;
    private final Path packFolder;
    private final List<String> conflicts;
    /** true = take the server's new version, false (default) = keep the existing local one. */
    private final Map<String, Boolean> decisions = new HashMap<>();
    private final Runnable onFinished;

    private int page = 0;

    public ConflictResolutionScreen(Screen parent, Path incomingTempDir, Path packFolder,
                                     List<String> conflicts, Runnable onFinished) {
        super(Component.literal("Texture Updates Found"));
        this.parent = parent;
        this.incomingTempDir = incomingTempDir;
        this.packFolder = packFolder;
        this.conflicts = new ArrayList<>(conflicts);
        this.onFinished = onFinished;
        for (String path : conflicts) decisions.put(path, false);
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();

        int listTop = 46;
        int start = page * ROWS_PER_PAGE;
        int end = Math.min(start + ROWS_PER_PAGE, conflicts.size());

        for (int i = start; i < end; i++) {
            String path = conflicts.get(i);
            int rowY = listTop + (i - start) * ROW_HEIGHT;
            boolean takeNew = decisions.get(path);

            this.addRenderableWidget(Button.builder(
                    Component.literal(takeNew ? "Will use: New" : "Will use: Mine"), b -> {
                decisions.put(path, !decisions.get(path));
                rebuild();
            }).bounds(this.width - 150, rowY, 130, 20).build());
        }

        boolean hasPrev = page > 0;
        boolean hasNext = (page + 1) * ROWS_PER_PAGE < conflicts.size();

        Button prev = Button.builder(Component.literal("< Prev"), b -> { page--; rebuild(); })
                .bounds(this.width / 2 - 154, this.height - 56, 70, 20).build();
        prev.active = hasPrev;
        this.addRenderableWidget(prev);

        Button next = Button.builder(Component.literal("Next >"), b -> { page++; rebuild(); })
                .bounds(this.width / 2 - 80, this.height - 56, 70, 20).build();
        next.active = hasNext;
        this.addRenderableWidget(next);

        this.addRenderableWidget(Button.builder(Component.literal("Keep All Mine"), b -> {
            for (String p : conflicts) decisions.put(p, false);
            rebuild();
        }).bounds(this.width / 2 - 154, this.height - 84, 150, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Take All New"), b -> {
            for (String p : conflicts) decisions.put(p, true);
            rebuild();
        }).bounds(this.width / 2 + 4, this.height - 84, 150, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Apply & Close"), b -> applyAndClose())
                .bounds(this.width / 2 - 75, this.height - 28, 150, 20).build());
    }

    private void applyAndClose() {
        for (Map.Entry<String, Boolean> entry : decisions.entrySet()) {
            if (entry.getValue()) {
                PackDownloadManager.applyTakeNew(incomingTempDir, packFolder, entry.getKey());
            }
        }
        if (onFinished != null) onFinished.run();
        this.onClose();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        guiGraphics.centeredText(this.font,
                Component.literal(conflicts.size() + " texture(s) changed on the server since your last download."),
                this.width / 2, 26, 0xA0A0A0);

        int listTop = 46;
        int start = page * ROWS_PER_PAGE;
        int end = Math.min(start + ROWS_PER_PAGE, conflicts.size());
        for (int i = start; i < end; i++) {
            int rowY = listTop + (i - start) * ROW_HEIGHT;
            guiGraphics.text(this.font, conflicts.get(i), 20, rowY + 6, 0xE0E0E0);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
