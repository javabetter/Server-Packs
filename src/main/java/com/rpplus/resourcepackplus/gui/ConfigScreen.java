package com.rpplus.resourcepackplus.gui;

import com.rpplus.resourcepackplus.ResourcePackPlusClient;
import com.rpplus.resourcepackplus.core.PackDownloadManager;
import com.rpplus.resourcepackplus.core.PackPreferences;
import com.rpplus.resourcepackplus.core.ResourcePackInstaller;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server Packs+ main config screen — a card per remembered server showing its multiplayer-list
 * icon and name, with every per-server option available inline as toggle/action buttons.
 *
 * Reachable via the /resourcepackplus command or, if ModMenu is installed, via its config button
 * (see compat/ModMenuIntegration). Uses manual pagination rather than the vanilla scrolling-list
 * widget so it stays easy to port across Minecraft versions.
 */
public class ConfigScreen extends Screen {

    private static final Identifier PLACEHOLDER_ICON =
            Identifier.fromNamespaceAndPath("resourcepackplus", "textures/gui/icon.png");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM d, yyyy HH:mm");

    private static final int LIST_TOP = 54;
    private static final int CARD_H = 80;
    private static final int CARD_STRIDE = 88;
    // Height reserved at the bottom for the status line, progress bar, and footer buttons — the
    // scrollable card viewport ends this many px above the bottom edge, so nothing overlaps it.
    private static final int FOOTER_RESERVE = 56;

    // Draggable scrollbar, drawn in the right margin.
    private static final int SCROLLBAR_W = 5;
    private boolean draggingScrollbar = false;
    private double dragGrabOffset = 0; // where within the thumb the drag started

    // Drag-to-reorder: index of the card currently being dragged, or -1. cardOrderChanged tracks
    // whether an actual move happened so we only persist on a real reorder.
    private int draggingCardIndex = -1;
    private boolean cardOrderChanged = false;

    private int scrollbarX() {
        return this.width - SCROLLBAR_W - 4;
    }

    private final Screen parent;
    private List<String> serverKeys = new ArrayList<>();
    private String statusMessage = null;
    // Live convert/extract progress, 0.0–1.0 while a conversion runs, or -1 when idle. Read by
    // render() to draw the progress bar. volatile: written from the client thread via execute().
    private volatile double convertProgress = -1;

    // Smooth scrolling: targetScrollY is where the wheel wants us; scrollY eases toward it each
    // frame so the list glides instead of jumping. Both are pixel offsets from the top of the list.
    private double scrollY = 0;
    private double targetScrollY = 0;
    // Card buttons stay real vanilla widgets (so clicks/tooltips/greying all work); we just move
    // them with the scroll and hide the ones scrolled out of the viewport each frame.
    private final List<CardWidget> cardWidgets = new ArrayList<>();

    /** A card's button plus which card it belongs to and its y-offset within that card, so the
     *  per-frame layout can place it at (cardTop(index) + offsetY). */
    private record CardWidget(net.minecraft.client.gui.components.AbstractWidget widget, int cardIndex, int offsetY) {}

    // Server-list metadata + lazily-created favicon textures, keyed by lowercase server address.
    private final Map<String, ServerData> serverDataByKey = new HashMap<>();
    private final Map<String, FaviconTexture> faviconByKey = new HashMap<>();

    public ConfigScreen(Screen parent) {
        super(Component.literal("Server Packs+"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        loadServerMetadata();
        refreshServerList();
        rebuild();
    }

    private void refreshServerList() {
        this.serverKeys = new ArrayList<>(PackPreferences.getAll().keySet());
        clampScroll();
    }

    // ---------- scroll math ----------

    private int listBottom() {
        return this.height - FOOTER_RESERVE;
    }

    private int viewportHeight() {
        return listBottom() - LIST_TOP;
    }

    /** Total pixel height of all cards stacked (no trailing gap after the last card). */
    private int contentHeight() {
        int n = serverKeys.size();
        return n == 0 ? 0 : (n - 1) * CARD_STRIDE + CARD_H;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - viewportHeight());
    }

    private void clampScroll() {
        double max = maxScroll();
        targetScrollY = Math.max(0, Math.min(max, targetScrollY));
        scrollY = Math.max(0, Math.min(max, scrollY));
    }

    /** Top y of the card at the given index, accounting for the current scroll offset. */
    private int cardTopFor(int index) {
        return (int) Math.round(LIST_TOP - scrollY + index * CARD_STRIDE);
    }

    /** Loads the multiplayer server list (for names + icons) once when the screen opens. */
    private void loadServerMetadata() {
        try {
            ServerList list = new ServerList(this.minecraft);
            list.load();
            for (int i = 0; i < list.size(); i++) {
                ServerData sd = list.get(i);
                if (sd != null && sd.ip != null) {
                    serverDataByKey.putIfAbsent(sd.ip.toLowerCase(Locale.ROOT), sd);
                }
            }
        } catch (Exception e) {
            ResourcePackPlusClient.LOGGER.warn("[Server Packs+] Couldn't load server list for icons/names", e);
        }
        ServerData current = this.minecraft.getCurrentServer();
        if (current != null && current.ip != null) {
            serverDataByKey.putIfAbsent(current.ip.toLowerCase(Locale.ROOT), current);
        }
    }

    // ---------- widget layout ----------

    private void rebuild() {
        this.clearWidgets();
        this.cardWidgets.clear();

        // Top controls.
        this.addRenderableWidget(Button.builder(Component.literal("Open resourcepacks folder"), b -> openPacksFolder())
                .bounds(16, 30, 180, 20).build());

        PackPreferences.GlobalSettings settings = PackPreferences.getGlobalSettings();
        this.addRenderableWidget(Button.builder(
                Component.literal("Verbose logging: " + (settings.verboseLogging ? "ON" : "OFF")), b -> {
            settings.verboseLogging = !settings.verboseLogging;
            PackPreferences.setGlobalSettings(settings);
            rebuild();
        }).bounds(this.width - 196, 30, 180, 20).build());

        // Per-server cards — all of them; they scroll. Buttons are created here and repositioned /
        // culled per frame by layoutCardWidgets() based on the scroll offset.
        Map<String, PackPreferences.ServerEntry> all = PackPreferences.getAll();
        int cardX = 16;
        int cardW = this.width - 32;
        for (int i = 0; i < serverKeys.size(); i++) {
            String serverKey = serverKeys.get(i);
            PackPreferences.ServerEntry entry = all.get(serverKey);
            if (entry == null) continue;
            addCardButtons(serverKey, entry, cardX, cardW, i);
        }
        clampScroll();
        layoutCardWidgets();

        // Footer (single row now that pagination is gone).
        this.addRenderableWidget(Button.builder(Component.literal("Forget All"), b -> {
            PackPreferences.clearAll();
            refreshServerList();
            rebuild();
        }).bounds(this.width / 2 - 154, this.height - 24, 150, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(this.width / 2 + 4, this.height - 24, 150, 20).build());
    }

    /** Registers a card button as a normal widget AND records it for per-frame scroll layout. */
    private void addCard(net.minecraft.client.gui.components.AbstractWidget w, int cardIndex, int offsetY) {
        this.addRenderableWidget(w);
        this.cardWidgets.add(new CardWidget(w, cardIndex, offsetY));
    }

    /** Places every card button at its scrolled position and hides the ones outside the viewport
     *  (hidden widgets don't render and can't be clicked, so no clipping-into-the-header issues). */
    private void layoutCardWidgets() {
        int top = LIST_TOP;
        int bottom = listBottom();
        for (CardWidget cw : cardWidgets) {
            int y = cardTopFor(cw.cardIndex()) + cw.offsetY();
            cw.widget().setY(y);
            cw.widget().visible = y >= top && (y + cw.widget().getHeight()) <= bottom;
        }
    }

    /**
     * Card controls: a single bottom row of four settings/action buttons on the left
     * (Pack toggle, On-join mode, Conflict policy, Convert &amp; Apply), plus a vertical strip of
     * three compact icon buttons on the right edge (Update / Revert / Forget), each with its full
     * label shown on hover.
     */
    private void addCardButtons(String serverKey, PackPreferences.ServerEntry entry, int cardX, int cardW, int cardIndex) {
        int gap = 4;
        int cardTop = cardTopFor(cardIndex);

        // ---- Right icon strip: 3 stacked SQUARE buttons with enlarged glyphs (full label on hover) ----
        int iconSz = 24;
        int iconX = cardX + cardW - iconSz - 6;
        int off = 52;                    // bottom row + bottom (Forget) icon share this offset
        int[] iconOffs = {2, 27, off};
        int rowH = iconSz;               // buttons match the icon square height so they line up

        boolean canUpdate = entry.convertedPackFile != null && entry.lastUrl != null && !entry.lastUrl.isEmpty();
        IconButton update = new IconButton(iconX, cardTop + iconOffs[0], iconSz, "⟳", 1.6f, true, b -> doUpdate(serverKey, entry));
        withTip(update, "§lUpdate Pack§r\n\nPull new/changed textures from the server into the converted pack.");
        update.active = canUpdate;
        addCard(update, cardIndex, iconOffs[0]);

        IconButton revert = new IconButton(iconX, cardTop + iconOffs[1], iconSz, "↺", 2.5f, false, b -> {
            boolean ok = PackDownloadManager.restoreLatestBackup(serverKey);
            statusMessage = ok ? "Reverted to the previous version for " + serverKey : "No backup found for " + serverKey;
            if (ok) ResourcePackInstaller.reloadActiveResources();
        }).nudge(0.5f, -1.0f);
        withTip(revert, "§lRevert Pack§r\n\nRoll the converted pack back to its previous backed-up version.");
        revert.active = PackDownloadManager.hasBackup(serverKey);
        addCard(revert, cardIndex, iconOffs[1]);

        IconButton forget = new IconButton(iconX, cardTop + iconOffs[2], iconSz, "✖", 1.3f, true, b -> {
            PackPreferences.reset(serverKey);
            refreshServerList();
            rebuild();
        }).withColor(0xFFE0554E);
        withTip(forget, "§lForget Server§r\n\nForget this server — you'll be prompted again next time you join.");
        addCard(forget, cardIndex, iconOffs[2]);

        // ---- Bottom row: four equal settings/action buttons, aligned with the bottom icon ----
        int y = cardTop + off;
        int rowLeft = cardX + 8;
        int rowRight = iconX - 6;                 // stop before the icon strip
        int rowW = rowRight - rowLeft;
        int bw = (rowW - 3 * gap) / 4;
        int bx = rowLeft;

        addCard(withTip(Button.builder(
                Component.literal(entry.enabled ? "✔ Pack: Enabled" : "✖ Pack: Disabled"), b -> {
            PackPreferences.setEnabled(serverKey, !entry.enabled);
            rebuild();
        }).bounds(bx, y, bw, rowH).build(),
                entry.enabled ? "ON — Server Packs+ manages this server's pack" : "OFF — vanilla handles this server's pack"),
                cardIndex, off);
        bx += bw + gap;

        addCard(withTip(Button.builder(
                Component.literal("On join: " + midChoice(entry.choice)), b -> {
            PackPreferences.setChoice(serverKey, nextChoice(entry.choice));
            rebuild();
        }).bounds(bx, y, bw, rowH).build(), "On join: " + describeChoice(entry.choice) + " — click to cycle"),
                cardIndex, off);
        bx += bw + gap;

        boolean kept = PackPreferences.isKeepApplied(serverKey);
        addCard(withTip(Button.builder(Component.literal(kept ? "Cached ✔" : "Cache"), b -> {
            if (kept) {
                PackPreferences.setKeepApplied(serverKey, false);
                PackDownloadManager.clearServerPackKept(serverKey);
                // Drop back to the plain server pack (if on the server) and remove the kept copy.
                PackDownloadManager.uncacheAndRestoreServerPack(this.minecraft, serverKey);
                rebuild();
            } else {
                PackPreferences.setKeepApplied(serverKey, true);
                // Save a copy so the kept pack also survives a full close/relaunch (no live reload).
                PackDownloadManager.persistKeptPackForRestart(this.minecraft, serverKey,
                        ok -> this.minecraft.execute(this::rebuild));
                rebuild();
            }
        }).bounds(bx, y, bw, rowH).build(),
                "Keep this server's pack applied: it won't unapply when you leave, and it survives closing/relaunching the game. Click to toggle."),
                cardIndex, off);
        bx += bw + gap;

        Button convert = withTip(Button.builder(Component.literal("Convert & Apply"), b -> doConvert(serverKey))
                .bounds(bx, y, bw, rowH).build(),
                "Turn the pack downloaded this session into a normal pack and apply it.\nLarge packs can take a while — the time depends on the pack size, your CPU, and disk speed.");
        convert.active = PackDownloadManager.canConvertNow(serverKey);
        addCard(convert, cardIndex, off);
    }

    /** A square button whose single glyph is drawn at an arbitrary scale, so tiny font glyphs (like
     *  the revert arrow) can be enlarged well past the normal 8px font size. {@code heavy} redraws
     *  the glyph with sub-pixel offsets to fatten its strokes (faux extra-bold). */
    private static class IconButton extends Button {
        private final String glyph;
        private final float scale;
        private final boolean heavy;
        private int color = 0xFFFFFFFF;
        private float nudgeX = 0f, nudgeY = 0f; // per-glyph centering correction, in glyph-space px
        IconButton(int x, int y, int size, String glyph, float scale, boolean heavy, OnPress onPress) {
            super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
            this.glyph = glyph;
            this.scale = scale;
            this.heavy = heavy;
        }
        IconButton withColor(int rgb) { this.color = rgb; return this; }
        IconButton nudge(float dx, float dy) { this.nudgeX = dx; this.nudgeY = dy; return this; }
        @Override
        protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderDefaultSprite(g);
            net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
            int c = this.active ? color : 0xFF7A7A7A;
            var pose = g.pose();
            pose.pushMatrix();
            // Center on the button, scale, then offset by half the glyph's cell — all in float space
            // (via matrix translates) so the centering stays exact at any scale. No drop shadow, so
            // the glyph sits perfectly centered rather than weighted toward the shadow.
            pose.translate(this.getX() + this.getWidth() / 2f, this.getY() + this.getHeight() / 2f);
            pose.scale(scale, scale);
            pose.translate(-font.width(glyph) / 2f + nudgeX, -(font.lineHeight - 1) / 2f + nudgeY);
            g.drawString(font, glyph, 0, 0, c, false);
            if (heavy) {
                // Redraw shifted a fraction of a pixel to thicken the strokes.
                pose.translate(0.55f, 0f);
                g.drawString(font, glyph, 0, 0, c, false);
            }
            pose.popMatrix();
        }
    }

    private static Button withTip(Button b, String tip) {
        b.setTooltip(Tooltip.create(Component.literal(tip)));
        return b;
    }

    // ---------- actions ----------

    private void doConvert(String serverKey) {
        statusMessage = "Converting pack for " + serverKey + "...";
        convertProgress = 0;
        PackDownloadManager.convertAndApply(this.minecraft, serverKey,
                status -> this.minecraft.execute(() -> statusMessage = status),
                fraction -> this.minecraft.execute(() -> convertProgress = fraction),
                success -> this.minecraft.execute(() -> { convertProgress = -1; refreshServerList(); rebuild(); }));
    }

    private void doUpdate(String serverKey, PackPreferences.ServerEntry entry) {
        statusMessage = "Downloading the server's current pack for " + serverKey + "...";
        PackDownloadManager.mergeUpdate(serverKey, entry.lastUrl, entry.lastHash, result ->
                this.minecraft.execute(() -> {
                    if (!result.success) {
                        statusMessage = "Update failed: " + result.errorMessage;
                        return;
                    }
                    if (!result.conflicts.isEmpty()) {
                        this.minecraft.setScreen(new ConflictResolutionScreen(
                                this, result.incomingTempDir, result.packFolder, result.conflicts,
                                () -> {
                                    PackDownloadManager.cleanupTempDir(result.incomingTempDir);
                                    ResourcePackInstaller.reloadActiveResources();
                                    statusMessage = "Updated: " + result.addedCount + " new file(s), "
                                            + result.conflicts.size() + " conflict(s) resolved.";
                                    rebuild();
                                }));
                    } else {
                        ResourcePackInstaller.reloadActiveResources();
                        statusMessage = "Updated: " + result.addedCount + " new file(s), "
                                + result.unchangedCount + " unchanged.";
                        rebuild();
                    }
                }));
    }

    private void openPacksFolder() {
        try {
            Path packsDir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
            Files.createDirectories(packsDir);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(packsDir.toFile());
            } else {
                statusMessage = "Folder: " + packsDir;
            }
        } catch (IOException e) {
            statusMessage = "Couldn't open folder — see logs.";
        }
    }

    // ---------- rendering ----------

    /** Draws the tinted background and the server cards. Called from {@link #render} BEFORE the
     *  widgets so the buttons render on top.
     *
     *  NOTE: we deliberately do NOT call super.renderBackground() here. The screen pipeline already
     *  renders (and blurs) the background once per frame; calling renderBackground again triggers a
     *  second blur, which the engine forbids ("Can only blur once per frame"). Instead we lay our
     *  own near-opaque gradient scrim over whatever's already there. */
    private void drawBackgroundAndCards(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Dark gradient scrim across the whole screen for contrast/eye-candy (no blur).
        g.fillGradient(0, 0, this.width, this.height, 0xE8121A24, 0xF2060A10);

        if (serverKeys.isEmpty()) return;

        Map<String, PackPreferences.ServerEntry> all = PackPreferences.getAll();
        int cardX = 16;
        int cardW = this.width - 32;
        int top = LIST_TOP;
        int bottom = listBottom();

        // Clip card graphics to the viewport so partially-scrolled cards slide smoothly under the
        // header/footer instead of drawing over them.
        g.enableScissor(0, top, this.width, bottom);
        for (int i = 0; i < serverKeys.size(); i++) {
            int cardTop = cardTopFor(i);
            if (cardTop > bottom || cardTop + CARD_H < top) continue; // fully off-screen: skip
            String serverKey = serverKeys.get(i);
            PackPreferences.ServerEntry entry = all.get(serverKey);
            if (entry == null) continue;
            boolean hovered = mouseX >= cardX && mouseX <= cardX + cardW
                    && mouseY >= Math.max(top, cardTop) && mouseY <= Math.min(bottom, cardTop + CARD_H);
            drawCard(g, serverKey, entry, cardX, cardW, cardTop, hovered, i == draggingCardIndex);
        }
        g.disableScissor();

        // Scrollbar in the RIGHT margin (only when the list overflows). Click/drag to scroll.
        if (maxScroll() > 0) {
            int sx = scrollbarX();
            g.fill(sx, top, sx + SCROLLBAR_W, bottom, 0x33FFFFFF); // track
            int thumbY = scrollbarThumbY();
            int thumbH = scrollbarThumbHeight();
            boolean hot = draggingScrollbar || isOverScrollbar(mouseX, mouseY);
            g.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH,
                    hot ? 0xFFDCEEFF : 0xC0BFE6FF); // thumb (brighter while hovered/dragged)
        }
    }

    // ---------- scrollbar geometry + dragging ----------

    private int scrollbarThumbHeight() {
        int trackH = viewportHeight();
        int content = contentHeight();
        if (content <= 0) return trackH;
        return Math.max(24, (int) (trackH * (viewportHeight() / (double) content)));
    }

    private int scrollbarThumbY() {
        double max = maxScroll();
        int trackH = viewportHeight();
        int thumbH = scrollbarThumbHeight();
        int travel = trackH - thumbH;
        return LIST_TOP + (max <= 0 ? 0 : (int) (travel * (scrollY / max)));
    }

    /** True if the cursor is over the scrollbar track (with a couple px of slop for easy grabbing). */
    private boolean isOverScrollbar(double mx, double my) {
        int sx = scrollbarX();
        return maxScroll() > 0
                && mx >= sx - 2 && mx <= sx + SCROLLBAR_W + 2
                && my >= LIST_TOP && my <= listBottom();
    }

    /** Index of the card under the given cursor position, or -1 if none / outside the viewport. */
    private int cardIndexAt(double mx, double my, int cardX, int cardW) {
        if (my < LIST_TOP || my > listBottom() || mx < cardX || mx > cardX + cardW) return -1;
        for (int i = 0; i < serverKeys.size(); i++) {
            int cardTop = cardTopFor(i);
            if (my >= cardTop && my <= cardTop + CARD_H) return i;
        }
        return -1;
    }

    /** Moves the scroll so the thumb tracks the cursor (accounting for where it was grabbed). */
    private void scrollThumbTo(double mouseY) {
        int travel = viewportHeight() - scrollbarThumbHeight();
        if (travel <= 0) return;
        double frac = (mouseY - LIST_TOP - dragGrabOffset) / travel;
        frac = Math.max(0.0, Math.min(1.0, frac));
        double target = frac * maxScroll();
        // Dragging should feel direct, so move both the eased value and its target together.
        targetScrollY = target;
        scrollY = target;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && isOverScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            int thumbY = scrollbarThumbY();
            int thumbH = scrollbarThumbHeight();
            if (event.y() >= thumbY && event.y() <= thumbY + thumbH) {
                dragGrabOffset = event.y() - thumbY;   // grabbed the thumb: preserve offset
            } else {
                dragGrabOffset = thumbH / 2.0;         // clicked the track: jump thumb to cursor
                scrollThumbTo(event.y());
            }
            return true;
        }
        // Let the card buttons take the click first; only start a card drag on the empty card body.
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == 0) {
            int idx = cardIndexAt(event.x(), event.y(), 16, this.width - 32);
            if (idx >= 0) {
                draggingCardIndex = idx;
                cardOrderChanged = false;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollThumbTo(event.y());
            return true;
        }
        if (draggingCardIndex >= 0) {
            // Move the dragged entry into whichever slot the cursor is over, live.
            int target = (int) Math.floor((event.y() - LIST_TOP + scrollY) / CARD_STRIDE);
            target = Math.max(0, Math.min(serverKeys.size() - 1, target));
            if (target != draggingCardIndex) {
                String key = serverKeys.remove(draggingCardIndex);
                serverKeys.add(target, key);
                draggingCardIndex = target;
                cardOrderChanged = true;
                rebuild(); // reposition each card's buttons for the new order
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        if (draggingCardIndex >= 0) {
            if (cardOrderChanged) PackPreferences.setOrder(serverKeys);
            draggingCardIndex = -1;
            cardOrderChanged = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void drawCard(GuiGraphics g, String serverKey, PackPreferences.ServerEntry entry,
                          int cardX, int cardW, int cardTop, boolean hovered, boolean dragging) {
        int x2 = cardX + cardW;
        int y2 = cardTop + CARD_H;

        // Card panel + border (brighter while being dragged, then while hovered).
        g.fill(cardX, cardTop, x2, y2, dragging ? 0x90305878 : hovered ? 0x70243850 : 0x55152230);
        int border = dragging ? 0xFF9CC6F0 : hovered ? 0xFF5B8CC2 : 0xFF2B3A4A;
        g.fill(cardX, cardTop, x2, cardTop + 1, border);
        g.fill(cardX, y2 - 1, x2, y2, border);
        g.fill(cardX, cardTop, cardX + 1, y2, border);
        g.fill(x2 - 1, cardTop, x2, y2, border);

        // Status color: green when the pack is applied (converted-active, or kept-applied), yellow
        // when converted but not applied, gray when nothing, red when disabled.
        boolean kept = PackPreferences.isKeepApplied(serverKey);
        boolean converted = entry.convertedPackFile != null;
        boolean convActive = PackDownloadManager.isPersistedPackActive(serverKey);
        int accent = (convActive || kept) ? 0xFF56C246 : converted ? 0xFFC9B34A : 0xFF6A6A6A;
        if (!entry.enabled) accent = 0xFF9A3B3B;
        g.fill(cardX, cardTop, cardX + 3, y2, accent);

        // Server icon (favicon or the mod placeholder) with a status-colored ring.
        int ix = cardX + 12, iy = cardTop + 6, isz = 40;
        g.fill(ix - 2, iy - 2, ix + isz + 2, iy + isz + 2, accent);
        g.fill(ix - 1, iy - 1, ix + isz + 1, iy + isz + 1, 0xFF0A0F14);
        Identifier icon = iconFor(serverKey);
        g.blit(RenderPipelines.GUI_TEXTURED, icon != null ? icon : PLACEHOLDER_ICON,
                ix, iy, 0.0F, 0.0F, isz, isz, isz, isz);

        // Name + address.
        int tx = cardX + 62;
        ServerData sd = serverDataByKey.get(serverKey);
        String name = (sd != null && sd.name != null && !sd.name.isBlank()) ? sd.name : serverKey;
        g.drawString(this.font, name, tx, cardTop + 6, 0xFFFFFFFF);
        if (!name.equals(serverKey)) {
            g.drawString(this.font, serverKey, tx, cardTop + 18, 0xFF8AA0B4);
        }

        // Status pills. Order: ENABLED, CONVERTED, CACHED (keep-applied).
        int px = tx;
        int py = cardTop + 30;
        if (entry.enabled) {
            px = drawPill(g, px, py, "ENABLED", 0xFF2E7D46, 0xFFDFFFE6);
        } else {
            px = drawPill(g, px, py, "DISABLED", 0xFF8E2E2E, 0xFFFFE0E0);
        }
        if (converted) {
            px = drawPill(g, px, py, convActive ? "CONVERTED" : "CONVERTED (OFF)",
                    convActive ? 0xFF2E7D46 : 0xFF7A6A1E, convActive ? 0xFFDFFFE6 : 0xFFFFF3C8);
        }
        if (kept) {
            px = drawPill(g, px, py, "CACHED", 0xFF2E5F8E, 0xFFD8ECFF);
        }
        if (!converted && !kept) {
            px = drawPill(g, px, py, "NOT CACHED", 0xFF3A4048, 0xFFB8C0C8);
        }
        if (entry.lastUpdatedMillis > 0) {
            g.drawString(this.font, "updated " + DATE_FORMAT.format(new Date(entry.lastUpdatedMillis)),
                    px + 4, py + 2, 0xFF8AA0B4);
        }
    }

    /** Draws a small rounded-look status pill and returns the x just past its right edge. */
    private int drawPill(GuiGraphics g, int x, int y, String text, int bg, int fg) {
        int w = this.font.width(text) + 8;
        int h = 12;
        // Fake rounded corners by insetting the top/bottom edge rows by 1px.
        g.fill(x + 1, y, x + w - 1, y + h, bg);
        g.fill(x, y + 1, x + w, y + h - 1, bg);
        g.drawString(this.font, text, x + 4, y + 2, fg);
        return x + w + 4;
    }

    /** Lazily creates and caches a favicon texture for a server address; null if it has no icon. */
    private Identifier iconFor(String serverKey) {
        if (faviconByKey.containsKey(serverKey)) {
            FaviconTexture f = faviconByKey.get(serverKey);
            return f == null ? null : f.textureLocation();
        }
        ServerData sd = serverDataByKey.get(serverKey);
        byte[] bytes = sd == null ? null : sd.getIconBytes();
        if (bytes == null) {
            faviconByKey.put(serverKey, null);
            return null;
        }
        try {
            FaviconTexture fav = FaviconTexture.forServer(this.minecraft.getTextureManager(), serverKey);
            fav.upload(NativeImage.read(bytes)); // FaviconTexture takes ownership of the image
            faviconByKey.put(serverKey, fav);
            return fav.textureLocation();
        } catch (Exception e) {
            ResourcePackPlusClient.LOGGER.warn("[Server Packs+] Failed to load favicon for {}", serverKey, e);
            faviconByKey.put(serverKey, null);
            return null;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Advance the smooth-scroll easing and reposition/cull card buttons for this frame BEFORE
        // anything draws, so the cards and their buttons move together.
        updateScroll();
        layoutCardWidgets();

        drawBackgroundAndCards(g, mouseX, mouseY, partialTick); // background + cards, behind widgets
        super.render(g, mouseX, mouseY, partialTick);           // widgets (buttons) on top

        // Header: mod icon + title, drawn on top.
        int titleW = this.font.width(this.title);
        int hx = this.width / 2 - (titleW + 22) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, PLACEHOLDER_ICON, hx, 4, 0.0F, 0.0F, 18, 18, 18, 18);
        g.drawString(this.font, this.title, hx + 22, 9, 0xFFFFFFFF);

        if (serverKeys.isEmpty()) {
            g.drawCenteredString(this.font,
                    Component.literal("No servers remembered yet — join a server with a resource pack to start."),
                    this.width / 2, this.height / 2 - 6, 0xFFA0A0A0);
        }

        // Status line + progress bar live in the reserved footer band (below the card viewport,
        // above the Forget All / Done buttons) so they never overlap the bottom card.
        if (statusMessage != null) {
            g.drawCenteredString(this.font, statusMessage, this.width / 2, this.height - 52, 0xFFFFD060);
        }

        // Live extraction progress bar (only while a conversion is running).
        double prog = this.convertProgress;
        if (prog >= 0) {
            double clamped = Math.max(0.0, Math.min(1.0, prog));
            int barW = 240, barH = 6;
            int bx = this.width / 2 - barW / 2;
            int by = this.height - 40;
            g.fill(bx - 1, by - 1, bx + barW + 1, by + barH + 1, 0xFF10171F); // outer border
            g.fill(bx, by, bx + barW, by + barH, 0xFF0A0E13);                 // empty track
            int fillW = (int) Math.round(barW * clamped);
            if (fillW > 0) {
                g.fill(bx, by, bx + fillW, by + barH, 0xFF4CC3FF);            // filled portion
            }
            String pct = (int) Math.round(clamped * 100) + "%";
            g.drawString(this.font, pct, bx + barW + 6, by - 1, 0xFFBFE6FF);
        }
    }

    /** Eases the current scroll offset toward the wheel target for smooth motion. */
    private void updateScroll() {
        clampScroll();
        double diff = targetScrollY - scrollY;
        if (Math.abs(diff) < 0.5) {
            scrollY = targetScrollY;
        } else {
            scrollY += diff * 0.35;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollXAmount, double scrollYAmount) {
        if (maxScroll() > 0 && mouseY >= LIST_TOP && mouseY <= listBottom()) {
            targetScrollY = Math.max(0, Math.min(maxScroll(), targetScrollY - scrollYAmount * 36));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollXAmount, scrollYAmount);
    }

    @Override
    public void removed() {
        for (FaviconTexture f : faviconByKey.values()) {
            if (f != null) f.close();
        }
        faviconByKey.clear();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // ---------- option label helpers ----------

    private static PackPreferences.Choice nextChoice(PackPreferences.Choice current) {
        // Cycle: Ask on first join -> Accept -> Reject. "Each time" is no longer offered, but if a
        // server was already set to it, cycling moves on to Accept.
        return switch (current) {
            case ASK_EACH_TIME -> PackPreferences.Choice.ACCEPT_AND_CACHE;
            case ACCEPT_AND_CACHE -> PackPreferences.Choice.REJECT_AND_JOIN;
            case REJECT_AND_JOIN -> PackPreferences.Choice.ASK_EACH_TIME;
            case ACCEPT_EACH_TIME -> PackPreferences.Choice.ACCEPT_AND_CACHE;
        };
    }

    private static String midChoice(PackPreferences.Choice choice) {
        return switch (choice) {
            case ASK_EACH_TIME -> "Ask on first join";
            case ACCEPT_EACH_TIME -> "Each time";
            case ACCEPT_AND_CACHE -> "Accept";
            case REJECT_AND_JOIN -> "Reject";
        };
    }

    private static String describeChoice(PackPreferences.Choice choice) {
        return switch (choice) {
            case ASK_EACH_TIME -> "Prompt on first join, then remember";
            case ACCEPT_EACH_TIME -> "Accept & re-download fresh each join";
            case ACCEPT_AND_CACHE -> "Accept & apply the pack (like vanilla)";
            case REJECT_AND_JOIN -> "Reject pack and join";
        };
    }

    private static PackPreferences.ConflictPolicy nextConflict(PackPreferences.ConflictPolicy current) {
        return switch (current) {
            case ASK -> PackPreferences.ConflictPolicy.KEEP_MINE;
            case KEEP_MINE -> PackPreferences.ConflictPolicy.TAKE_NEW;
            case TAKE_NEW -> PackPreferences.ConflictPolicy.ASK;
        };
    }

    private static String midConflict(PackPreferences.ConflictPolicy policy) {
        return switch (policy) {
            case ASK -> "Ask";
            case KEEP_MINE -> "Keep mine";
            case TAKE_NEW -> "Take new";
        };
    }

    private static String describeConflict(PackPreferences.ConflictPolicy policy) {
        return switch (policy) {
            case ASK -> "ask me each time";
            case KEEP_MINE -> "keep my version";
            case TAKE_NEW -> "take the server's";
        };
    }
}
