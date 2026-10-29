package com.xlrw.gui;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import com.xlrw.builder.BuilderController;
import com.xlrw.config.BuilderConfig;
import com.xlrw.printer.ChestManager;
import com.xlrw.schematic.MaterialReport;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * XLRW control deck - cyberpunk ("2077") styled, low-text, animated.
 *
 * <p>Immediate-mode UI: every frame the widgets are drawn and register their click areas; clicks are matched against
 * the areas of the last frame, so drawing and hit-testing can never get out of sync. Animations (open, tab slide,
 * hover glow, switch knobs, progress) are time based and smoothed per widget id.
 */
public class XlrwScreen extends Screen {

    // ------------------------------------------------------------------------------------------------
    // Palette (night city: deep maroon-black, hot coral red, cold teal panels)
    // ------------------------------------------------------------------------------------------------
    // Palette: Zenless Zone Zero "retro" trio - cream #E8D8C9, slate blue #4B607F, signal orange #F3701E - on ink black
    private static final int BG = 0xF21A1A1D;
    private static final int BG2 = 0xFF232429;
    private static final int RED = 0xFFF3701E;      // accent (orange)
    private static final int RED_DIM = 0xFF4B607F;  // slate blue
    private static final int RED_DEEP = 0xFF3B2A20; // warm dark (selected rows)
    private static final int TEAL = 0xFF2B3240;     // panel / button
    private static final int TEAL_HI = 0xFF4B607F;  // hover
    private static final int CYAN = 0xFFE8D8C9;     // cream
    private static final int TEXT_DIM = 0xFF8D97AB;
    private static final int OK = 0xFF7FD18B;
    private static final int CREAM = 0xFFE8D8C9;
    private static final int INK = 0xFF141416;

    private enum Tab {
        DECK("◆", "DECK"), FILES("▤", "FILES"), PLACE("✥", "PLACE"), ENGINE("⚙", "ENGINE"), VIEW("◉", "VIEW"), HUB("✦", "HUB");
        final String icon, label;

        Tab(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private Tab tab = Tab.DECK;
    private long openedAt;
    private long tabChangedAt;
    private boolean showMaterials = false;
    private MaterialReport materialReport;
    private int fileScroll = 0;
    private int matScroll = 0;
    private List<File> files = new ArrayList<>();
    private int selectedFile = 0;

    private int wx, wy, ww, wh;          // window
    private int cx, cy, cw, ch;          // content area

    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> anim = new HashMap<>();
    private String tooltip = null;
    private float shownProgress = 0f;

    // --- 3D cockpit: two panels with real perspective (keystone via the model-view matrix) + parallax ---
    private int pvx, pvy, pvw, pvh;      // right panel (hologram preview)
    private boolean showPreview;
    private int panel = 0;                // panel currently drawing (0 = deck, 1 = preview)
    private final Tilt[] tilts = {new Tilt(), new Tilt()};
    private final HoloPreview holo = new HoloPreview();
    private static final Mascot MASCOT = new Mascot();
    private boolean lastEnabled = BuilderConfig.getInstance().enabled;
    private final Map<String, Boolean> hoverIn = new HashMap<>();
    private long lastHoverSound;
    private double lastMx, lastMy;

    /** Perspective of one panel: local (x, y) -> screen, w = 1 + a * (x - px). */
    private static final class Tilt {
        float px, py, a, ox, oy;

        float[] toScreen(float x, float y) {
            float X = x - px, Y = y - py, w = 1 + a * X;
            return new float[]{px + ox + X / w, py + oy + Y / w};
        }

        double[] toLocal(double sx, double sy) {
            double U = sx - px - ox, V = sy - py - oy, d = 1 - a * U;
            if (d < 0.05) d = 0.05;
            return new double[]{px + U / d, py + V / d};
        }

        Matrix4f matrix() {
            // T(p + o) * P(a) * T(-p); JOML constructor is column-major, m03 = a puts a*x into w
            Matrix4f persp = new Matrix4f(1, 0, 0, a, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
            return new Matrix4f().translation(px + ox, py + oy, 0).mul(persp).translate(-px, -py, 0);
        }
    }

    private record Hit(int panel, int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public XlrwScreen() {
        super(Component.literal("XLRW"));
    }

    @Override
    protected void init() {
        super.init();
        files = SchematicManager.getInstance().getAvailableSchematics();
        int gap = 14;
        pvw = Mth.clamp(width - 40 - 440 - gap, 0, 220);
        showPreview = pvw >= 150;
        ww = showPreview ? Math.min(440, width - 40 - pvw - gap) : Math.min(440, width - 16);
        wh = Math.min(270, height - (BuilderConfig.getInstance().ui3d ? 44 : 16) - 30); // 30: status strip below
        int total = ww + (showPreview ? gap + pvw : 0);
        wx = (width - total) / 2;
        wy = (height - wh) / 2;
        pvx = wx + ww + gap;
        pvy = wy;
        pvh = wh;
        cx = wx + 86;
        cy = wy + 48;
        cw = ww - 86 - 10;
        ch = wh - 48 - 10;
        if (System.currentTimeMillis() - openedAt > 1000) XlrwSounds.play("ui.open", 1f, 0.55f);
        openedAt = System.currentTimeMillis();
        tabChangedAt = openedAt;
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        shownProgress = s != null ? s.getProgressPercentage() : 0f;
    }

    // ------------------------------------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float pt) {
        g.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
    }

    @Override
    protected void renderMenuBackground(GuiGraphics g) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        tooltip = null;
        lastMx = mx;
        lastMy = my;
        long now = System.currentTimeMillis();
        float open = ease(Mth.clamp((now - openedAt) / 420f, 0f, 1f));
        boolean d3 = BuilderConfig.getInstance().ui3d;

        renderBackground(g, mx, my, pt);

        // Panels swing in from a steep angle and then follow the mouse a little (parallax)
        float nx = Mth.clamp((mx - width / 2f) / (width / 2f), -1f, 1f);
        float ny = Mth.clamp((my - height / 2f) / (height / 2f), -1f, 1f);
        float base = d3 ? 0.00042f : 0f;
        float swing = d3 ? (1f - open) * 0.0016f : 0f;
        Tilt tl = tilts[0], tr = tilts[1];
        tl.px = wx + ww / 2f; tl.py = wy + wh / 2f;
        tl.a = smooth("tiltL", base * (1f + 0.45f * nx), 0.12f) + swing;
        tl.ox = (d3 ? -nx * 3f : 0f) - (1f - open) * 40f;
        tl.oy = d3 ? -ny * 2f : 0f;
        tr.px = pvx + pvw / 2f; tr.py = pvy + pvh / 2f;
        tr.a = -(smooth("tiltR", base * (1.25f - 0.45f * nx), 0.12f) + swing);
        tr.ox = (d3 ? -nx * 5f : 0f) + (1f - open) * 40f;
        tr.oy = d3 ? -ny * 3f : 0f;

        // ---- left: control deck ----
        panel = 0;
        double[] l = tl.toLocal(mx, my);
        int smx = (int) Math.floor(l[0]), smy = (int) Math.floor(l[1]);
        beginPanels(g);
        drawStatusStrip(g, smx, smy, now);   // drawn first: it slides out from under the panel
        drawFrame(g, now, open);
        drawHeader(g, smx, smy, now, open);
        drawNav(g, smx, smy, now);

        // Tab content: slide in from the right
        float tIn = ease(Mth.clamp((now - tabChangedAt) / 200f, 0f, 1f));
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, (1f - tIn) * 14f, 0, 0);
        scissorLocal(g, tl, cx - 2, cy - 2, cx + cw + 2, cy + ch + 2);
        // huge slanted tab name as a watermark (Persona menu vibe)
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, cx + cw - font.width(tab.label) * 4.2f - 4, cy + ch - 36, 0);
        shear(g, -0.3f);
        com.xlrw.compat.GuiCompat.scale(g, 4.2f, 4.2f, 1f);
        g.drawString(font, tab.label, 0, 0, 0x10E8D8C9, false);
        com.xlrw.compat.GuiCompat.pop(g);
        UiImages.draw(g, tab.name(), true, cx, cy, smx, smy);   // pictures sent behind the widgets
        switch (tab) {
            case DECK -> drawDeck(g, smx, smy, now);
            case FILES -> drawFiles(g, smx, smy, now);
            case PLACE -> drawPlace(g, smx, smy);
            case ENGINE -> drawEngine(g, smx, smy);
            case VIEW -> drawView(g, smx, smy);
            case HUB -> HubWidgets.draw(g, font, cx, cy, cw, ch, smx, smy, now, this::hit);
        }
        UiImages.draw(g, tab.name(), false, cx, cy, smx, smy);  // pictures in front
        editHitStart = hits.size();
        if (UiImages.editing) drawImageToolbar(g, smx, smy);
        UiImages.drawPicker(g, font, cx, cy, cw, ch, smx, smy, this::hit, tab.name(), cx, cy);
        g.disableScissor();
        com.xlrw.compat.GuiCompat.pop(g);
        drawScanline(g, now);

        // ---- right: hologram preview ----
        if (showPreview) {
            panel = 1;
            double[] r = tr.toLocal(mx, my);
            drawPreviewPanel(g, (int) Math.floor(r[0]), (int) Math.floor(r[1]), now, open);
        }
        panel = 0;
        endPanels(g);
        composite(tl, wx - 8, wy - 8, wx + ww + 8, wy + wh + 40);
        if (showPreview) composite(tr, pvx - 8, pvy - 8, pvx + pvw + 8, pvy + pvh + 8);

        // Bit lives in screen space on the projected top edges of both panels
        float[] l0 = tl.toScreen(wx + 4, wy), l1 = tl.toScreen(wx + ww - 12, wy);
        Mascot.Edge le = new Mascot.Edge(l0[0], l0[1], l1[0], l1[1]), re = null;
        if (showPreview) {
            float[] r0 = tr.toScreen(pvx + 4, pvy), r1 = tr.toScreen(pvx + pvw - 12, pvy);
            re = new Mascot.Edge(r0[0], r0[1], r1[0], r1[1]);
        }
        if (open > 0.85f) MASCOT.render(g, font, le, re, mx, my, now);
        g.flush();

        if (tooltip != null) drawTooltip(g, mx, my, tooltip);
    }

    /*
     * The panels are drawn flat into an off-screen texture and that texture is then drawn as two quads with the
     * perspective applied. Drawing the widgets themselves through the perspective matrix made overlapping layers
     * (same depth, different w) fight in the depth buffer and flicker.
     */
    private TextureTarget panelTarget;

    private void beginPanels(GuiGraphics g) {
        g.flush();
        var win = minecraft.getWindow();
        int pw = win.getWidth(), ph = win.getHeight();
        if (panelTarget == null) {
            panelTarget = new TextureTarget(pw, ph, true, Minecraft.ON_OSX);
            panelTarget.setFilterMode(9729); // GL_LINEAR: smooth when the panel is shrunk by the perspective
        } else if (panelTarget.width != pw || panelTarget.height != ph) {
            panelTarget.resize(pw, ph, Minecraft.ON_OSX);
            panelTarget.setFilterMode(9729);
        }
        panelTarget.setClearColor(0f, 0f, 0f, 0f);
        panelTarget.clear(Minecraft.ON_OSX);
        panelTarget.bindWrite(true);
    }

    private void endPanels(GuiGraphics g) {
        g.flush();
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    private void composite(Tilt t, int x0, int y0, int x1, int y1) {
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.mul(t.matrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, panelTarget.getColorTextureId());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(1, 771); // ONE, ONE_MINUS_SRC_ALPHA: the texture holds premultiplied colour
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        double gs = minecraft.getWindow().getGuiScale();
        float tw = panelTarget.width, th = panelTarget.height;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(x0, y0, 0).setUv((float) (x0 * gs / tw), 1f - (float) (y0 * gs / th));
        bb.addVertex(x0, y1, 0).setUv((float) (x0 * gs / tw), 1f - (float) (y1 * gs / th));
        bb.addVertex(x1, y1, 0).setUv((float) (x1 * gs / tw), 1f - (float) (y1 * gs / th));
        bb.addVertex(x1, y0, 0).setUv((float) (x1 * gs / tw), 1f - (float) (y0 * gs / th));
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    // ---- walk while the deck is open (bot off: WASD / jump / sneak / sprint pass through) ----
    private boolean passedKeys;

    private net.minecraft.client.KeyMapping[] moveKeys() {
        var o = minecraft.options;
        return new net.minecraft.client.KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint};
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.player == null) return;
        if (BuilderConfig.getInstance().enabled) return; // the bot drives the keys itself
        if (HubWidgets.capturesKeys()) {                  // a mini game uses WASD / arrows
            if (passedKeys) for (var k : moveKeys()) k.setDown(false);
            return;
        }
        long w = minecraft.getWindow().getWindow();
        for (var k : moveKeys()) {
            com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(k.saveString());
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
                k.setDown(com.mojang.blaze3d.platform.InputConstants.isKeyDown(w, key.getValue()));
                passedKeys = true;
            }
        }
    }

    @Override
    public void removed() {
        if (passedKeys) {
            for (var k : moveKeys()) k.setDown(false);
            passedKeys = false;
        }
        if (panelTarget != null) {
            panelTarget.destroyBuffers();
            panelTarget = null;
        }
        super.removed();
    }

    /** Scissor around a panel-local rectangle. */
    private void scissorLocal(GuiGraphics g, Tilt t, int x0, int y0, int x1, int y1) {
        g.enableScissor(x0, y0, x1, y1); // panels are drawn flat off-screen, so local == screen here
    }

    // ------------------------------------------------------------------------------------------------
    // Right panel: live 3D model of the schematic
    // ------------------------------------------------------------------------------------------------

    private void drawPreviewPanel(GuiGraphics g, int mx, int my, long now, float open) {
        int x = pvx, y = pvy, w = pvw, h = pvh;
        chamfer(g, x, y, w, h, 10, BG);
        chamferOutline(g, x, y, w, h, 10, alpha(RED_DIM, 0.9f));
        // corner brackets on the two square corners (the other two are chamfered), same as the left panel
        g.fill(x + 3, y + 3, x + 14, y + 4, RED);
        g.fill(x + 3, y + 3, x + 4, y + 14, RED);
        g.fill(x + w - 14, y + h - 4, x + w - 3, y + h - 3, RED);
        g.fill(x + w - 4, y + h - 14, x + w - 3, y + h - 3, RED);
        // title chip
        skewRect(g, x + 10 + 3, y + 10 + 2, 58, 14, 5, RED);
        skewRect(g, x + 10, y + 10, 58, 14, 5, CREAM);
        g.drawString(font, "HOLO", x + 22, y + 13, INK, false);
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        BuilderConfig cfg = BuilderConfig.getInstance();
        int vx = x + 8, vy = y + 30, vw = w - 16, vh = h - 30 - 34;
        // viewport: dark well + grid floor lines
        g.fill(vx, vy, vx + vw, vy + vh, 0xFF121215);
        for (int i = 1; i < 8; i++) g.fill(vx, vy + vh * i / 8, vx + vw, vy + vh * i / 8 + 1, 0x0FE8D8C9);
        for (int i = 1; i < 8; i++) g.fill(vx + vw * i / 8, vy, vx + vw * i / 8 + 1, vy + vh, 0x0FE8D8C9);
        if (s == null) {
            g.drawCenteredString(font, "∅", vx + vw / 2, vy + vh / 2 - 4, TEXT_DIM);
        } else {
            String sz = holo.sizeLabel();
            drawRight(g, sz, x + w - 12, y + 14, TEXT_DIM);
            scissorLocal(g, tilts[1], vx, vy, vx + vw, vy + vh);
            if (open > 0.6f) holo.render(g, vx, vy, vw, vh, s, s.getMinY() + cfg.currentLayer, now);
            g.disableScissor();
            // corner readouts
            g.drawString(font, Math.round(s.getProgressPercentage()) + "%", vx + 4, vy + vh - 11, RED, false);
            drawRight(g, "L" + cfg.currentLayer, vx + vw - 4, vy + vh - 11, CREAM);
            hit(vx, vy, vw, vh, () -> {
                holo.dragging = true;
                holo.touch();
            });
        }
        // footer buttons
        int by = y + h - 28;
        int bw = (w - 16 - 6) * 2 / 3;
        bigButton(g, mx, my, "edit3d", x + 8, by, bw, 20, "✥  EDIT 3D", RED, INK, this::openEditor);
        tip("edit3d", "Free cam: kéo mũi tên / vòng xoay để đặt schematic", mx, my, x + 8, by, bw, 20);
        smallButton(g, mx, my, "pvrot", x + 14 + bw, by, w - 22 - bw, 20, "⟳", false, () -> SchematicManager.getInstance().rotate());
        // the little robot living on the top edge
        if (cfg.enabled != lastEnabled) {
            lastEnabled = cfg.enabled;
            MASCOT.react(cfg.enabled ? "on" : "off");
            XlrwSounds.play(cfg.enabled ? "ui.power_on" : "ui.power_off", 1f, 0.55f);
        }
    }

    private final java.util.Random rand = new java.util.Random();

    private boolean soundDone; // the clicked action already played its own sound
    private int editHitStart = Integer.MAX_VALUE;

    /** Floating toolbar while editing pictures: add (+), open folder, done. */
    private void drawImageToolbar(GuiGraphics g, int mx, int my) {
        int y = cy + 2, x = cx + cw - 4;
        String[] labels = {"✓", "▤", "+"};
        Runnable[] acts = {() -> { UiImages.editing = false; UiImages.picker = false; }, UiImages::openFolder, () -> UiImages.picker = !UiImages.picker};
        for (int i = 0; i < labels.length; i++) {
            int w = 18;
            x -= w + 3;
            float hv = hover("imgtb" + i, mx, my, x, y, w, 14);
            com.xlrw.compat.GuiCompat.push(g);
            com.xlrw.compat.GuiCompat.translate(g, 0, 0, 150);
            chamfer(g, x, y, w, 14, 3, i == 2 ? lerpColor(RED, 0xFFFF8F45, hv) : lerpColor(TEAL, TEAL_HI, hv));
            g.drawCenteredString(font, labels[i], x + w / 2, y + 3, i == 2 ? INK : CREAM);
            com.xlrw.compat.GuiCompat.pop(g);
            hit(x, y, w, 14, acts[i]);
        }
        String hint = "kéo: dời · góc cam: co giãn · cuộn: scale · chuột phải: xoá · F: trước/sau";
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, cx + 4, cy + ch - 9, 150);
        com.xlrw.compat.GuiCompat.scale(g, 0.6f, 0.6f, 1f);
        g.fill(-2, -2, font.width(hint) + 2, 9, 0xCC141416);
        g.drawString(font, hint, 0, 0, CREAM, false);
        com.xlrw.compat.GuiCompat.pop(g);
    }

    private void openEditor() {
        if (SchematicManager.getInstance().getActiveSchematic() == null) {
            notifyPlayer("§cChưa chọn schematic");
            return;
        }
        MASCOT.react("edit");
        XlrwSounds.play("ui.edit", 1f, 0.55f);
        minecraft.setScreen(new SchematicEditorScreen(this));
    }

    // ------------------------------------------------------------------------------------------------
    // Chrome
    // ------------------------------------------------------------------------------------------------

    private void drawFrame(GuiGraphics g, long now, float open) {
        chamfer(g, wx, wy, ww, wh, 10, BG);
        chamferOutline(g, wx, wy, ww, wh, 10, alpha(RED_DIM, 0.9f));
        // corner brackets
        int c = RED;
        g.fill(wx + 3, wy + 3, wx + 14, wy + 4, c);                     // top-left
        g.fill(wx + 3, wy + 3, wx + 4, wy + 14, c);
        g.fill(wx + ww - 14, wy + wh - 4, wx + ww - 3, wy + wh - 3, c); // bottom-right
        g.fill(wx + ww - 4, wy + wh - 14, wx + ww - 3, wy + wh - 3, c);
        // side ticks (left rail ruler)
        for (int i = 0; i < 12; i++) {
            int ty = wy + 48 + i * ((wh - 60) / 12);
            g.fill(wx + 2, ty, wx + (i % 3 == 0 ? 7 : 5), ty + 1, alpha(RED_DIM, 0.8f));
        }
        // header divider with a moving segment
        int dy = wy + 40;
        g.fill(wx + 8, dy, wx + ww - 8, dy + 1, alpha(RED_DIM, 0.7f));
        int seg = (int) ((now / 6) % (ww - 60));
        g.fill(wx + 8 + seg, dy, wx + 8 + seg + 40, dy + 1, RED);
    }

    private void drawHeader(GuiGraphics g, int mx, int my, long now, float open) {
        BuilderConfig cfg = BuilderConfig.getInstance();
        // logo block
        chamfer(g, wx + 10, wy + 8, 26, 26, 5, RED);
        g.drawString(font, "X", wx + 20, wy + 17, INK, false);
        // Persona-style title: tilted cream slab with an orange offset shadow, ink letters sheared forward
        String title = "XLRW";
        int gx = open < 1f && (now / 40) % 2 == 0 ? 1 : 0;
        int tw = (int) (font.width(title) * 1.6f) + 14;
        skewRect(g, wx + 44 + 3, wy + 9 + 2, tw, 14, 5, RED);
        skewRect(g, wx + 44, wy + 9, tw, 14, 5, CREAM);
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, wx + 52 + gx, wy + 11, 0);
        shear(g, -0.25f);
        com.xlrw.compat.GuiCompat.scale(g, 1.6f, 1.25f, 1f);
        g.drawString(font, title, 0, 0, INK, false);
        com.xlrw.compat.GuiCompat.pop(g);
        g.drawString(font, "PRINTER", wx + 44 + tw + 8, wy + 13, RED, false);
        // diagonal halftone stripes (P5 flavour) behind the status dots
        for (int i = 0; i < 14; i++) {
            int sx = wx + ww / 2 - 30 + i * 6;
            skewRect(g, sx, wy + 6, 2, 28, 10, 0x14E8D8C9);
        }
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        String sub = s != null ? trim(s.getName().replaceFirst("\\.[^.]+$", ""), 26).toUpperCase() : "NO SCHEMATIC";
        g.drawString(font, sub, wx + 44, wy + 27, TEXT_DIM, false);

        // compass-like status dots
        int dotsX = wx + ww / 2 + 10;
        for (int i = 0; i < 9; i++) {
            boolean lit = ((now / 180) % 9) == i;
            g.fill(dotsX + i * 7, wy + 14, dotsX + i * 7 + 2, wy + 16, lit ? RED : RED_DIM);
        }

        // power pill
        int pw = 70, px = wx + ww - pw - 10, py = wy + 10;
        float h = hover("power", mx, my, px, py, pw, 18);
        boolean on = cfg.enabled;
        int base = on ? RED : TEAL;
        chamfer(g, px, py, pw, 18, 5, lerpColor(base, on ? 0xFFFF8F45 : TEAL_HI, h));
        float blink = on ? (0.6f + 0.4f * (float) Math.sin(now / 160.0)) : 1f;
        g.fill(px + 7, py + 7, px + 11, py + 11, on ? alpha(INK, blink) : RED_DIM);
        g.drawString(font, on ? "ONLINE" : "OFFLINE", px + 16, py + 5, on ? INK : RED, false);
        hit(px, py, pw, 18, () -> {
            cfg.enabled = !cfg.enabled;
            BuilderConfig.save();
        });
        tip("power", "Bật/tắt bot (phím B)", mx, my, px, py, pw, 18);
        // picture edit mode
        int ix = px - 24;
        float ih = hover("imgEdit", mx, my, ix, py, 20, 18);
        boolean ie = UiImages.editing;
        chamfer(g, ix, py, 20, 18, 4, ie ? RED : lerpColor(TEAL, TEAL_HI, ih));
        g.drawCenteredString(font, "▣", ix + 10, py + 5, ie ? INK : lerpColor(TEXT_DIM, CREAM, ih));
        hit(ix, py, 20, 18, () -> { UiImages.editing = !UiImages.editing; UiImages.picker = false; });
        tip("imgEdit", "Thêm / chỉnh ảnh trên tab", mx, my, ix, py, 20, 18);
    }

    private void drawNav(GuiGraphics g, int mx, int my, long now) {
        int x = wx + 12, y = wy + 50, w = 54, h = Math.min(36, (wh - 56) / Tab.values().length);
        for (Tab t : Tab.values()) {
            boolean active = t == tab;
            float hv = hover("nav" + t, mx, my, x, y, w, h);
            float act = smooth("navAct" + t, active ? 1f : 0f, 0.25f);
            int pop = Math.round(4 * act + 1.5f * hv * (1f - act)); // active tag pops out to the right
            if (act > 0.02f) skewRect(g, x + pop + 2, y + 2, w, h - 4, 5, alpha(RED, act)); // orange shadow
            int bg = lerpColor(lerpColor(TEAL, TEAL_HI, hv * 0.7f), CREAM, act);
            skewRect(g, x + pop, y, w, h - 4, 5, bg);
            int col = active ? INK : lerpColor(TEXT_DIM, CREAM, hv);
            g.drawCenteredString(font, t.icon, x + pop + w / 2, y + 6, active ? RED : col);
            com.xlrw.compat.GuiCompat.push(g);
            com.xlrw.compat.GuiCompat.translate(g, x + pop + w / 2f, y + 19, 0);
            shear(g, -0.2f);
            com.xlrw.compat.GuiCompat.scale(g, 0.8f, 0.8f, 1f);
            g.drawString(font, t.label, -font.width(t.label) / 2, 0, col, false);
            com.xlrw.compat.GuiCompat.pop(g);
            final Tab tt = t;
            hit(x, y, w + 8, h - 4, () -> switchTab(tt));
            y += h;
        }
    }

    private void drawScanline(GuiGraphics g, long now) {
        int span = wh - 20;
        int sy = wy + 10 + (int) ((now / 14) % span);
        g.fill(wx + 2, sy, wx + ww - 2, sy + 1, 0x12F3701E);
    }

    // ------------------------------------------------------------------------------------------------
    // DECK: overview
    // ------------------------------------------------------------------------------------------------

    private void drawDeck(GuiGraphics g, int mx, int my, long now) {
        BuilderConfig cfg = BuilderConfig.getInstance();
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        BuilderController bc = BuilderController.getInstance();

        // Big progress module
        int x = cx, y = cy, w = cw;
        chamfer(g, x, y, w, 84, 8, BG2);
        chamferOutline(g, x, y, w, 84, 8, RED_DIM);
        float target = s != null ? s.getProgressPercentage() : 0f;
        shownProgress += (target - shownProgress) * 0.12f;
        String pct = String.format("%.0f", shownProgress);
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, x + 12, y + 14, 0);
        com.xlrw.compat.GuiCompat.scale(g, 3.5f, 3.5f, 1f);
        g.drawString(font, pct, 0, 0, RED, false);
        com.xlrw.compat.GuiCompat.pop(g);
        g.drawString(font, "%", x + 15 + (int) (font.width(pct) * 3.5f), y + 34, RED_DIM, false);
        // segmented bar
        int bx = x + 12, by = y + 64, bw = w - 24, segs = 40;
        float filled = shownProgress / 100f * segs;
        for (int i = 0; i < segs; i++) {
            int sx = bx + i * bw / segs;
            int ex = bx + (i + 1) * bw / segs - 1;
            int c = i < (int) filled ? RED : (i == (int) filled && (now / 250) % 2 == 0 ? RED_DIM : 0xFF2B2F3A);
            g.fill(sx, by, ex, by + 8, c);
        }
        // right side stats (numbers, few words)
        int rx = x + w - 12;
        String layer = s != null ? "L" + cfg.currentLayer + "/" + (s.getMaxY() - s.getMinY()) : "L-";
        String blocks = s != null ? s.getPlacedBlocks() + "/" + s.getTotalBlocks() : "-";
        drawRight(g, layer, rx, y + 16, RED);
        drawRight(g, blocks, rx, y + 30, TEXT_DIM);

        // State line
        String st = bc.getPrinterState().name().replace('_', ' ');
        int sy = y + 92;
        chamfer(g, x, sy, w, 20, 5, TEAL);
        g.fill(x + 6, sy + 7, x + 10, sy + 11, cfg.enabled ? alpha(RED, 0.5f + 0.5f * (float) Math.sin(now / 150.0)) : RED_DIM);
        g.drawString(font, st, x + 16, sy + 6, RED, false);
        String msg = stripCodes(bc.getStatusMessage());
        msg = trimToWidth(msg.replace("3D Printer: ", ""), w - 30 - font.width(st));
        drawRight(g, msg, x + w - 8, sy + 6, TEXT_DIM);

        // Big actions
        int ay = sy + 28, half = (w - 6) / 2;   // 34 high action buttons
        bigButton(g, mx, my, "run", x, ay, half, 32, cfg.enabled ? "■  STOP" : "▶  START", cfg.enabled ? RED : TEAL_HI,
                cfg.enabled ? INK : RED, () -> {
                    cfg.enabled = !cfg.enabled;
                    BuilderConfig.save();
                });
        bigButton(g, mx, my, "step", x + half + 6, ay, half, 32, "⏵  STEP", TEAL, RED,
                () -> BuilderController.getInstance().placeSingleStep(minecraft));
        tip("step", "Đặt đúng 1 block (phím P)", mx, my, x + half + 6, ay, half, 32);

        // Quick switches row
        int qy = ay + 40, qw = (w - 12) / 3;
        toggle(g, mx, my, "q.move", x, qy, qw, "MOVE", () -> cfg.autoMove, v -> cfg.autoMove = v, "Tự di chuyển tới chỗ đặt");
        toggle(g, mx, my, "q.demo", x + qw + 6, qy, qw, "FIX", () -> cfg.autoDemolish, v -> cfg.autoDemolish = v, "Tự đập block sai");
        toggle(g, mx, my, "q.layer", x + (qw + 6) * 2, qy, qw, "AUTO L", () -> cfg.autoLayer, v -> cfg.autoLayer = v, "Tự lên layer tiếp");
    }

    // ------------------------------------------------------------------------------------------------
    // FILES
    // ------------------------------------------------------------------------------------------------

    private void drawFiles(GuiGraphics g, int mx, int my, long now) {
        int listW = cw * 46 / 100;
        int x = cx, y = cy;
        // header chips
        smallButton(g, mx, my, "f.list", x, y, 60, 16, "LIST", !showMaterials, () -> showMaterials = false);
        smallButton(g, mx, my, "f.mat", x + 64, y, 60, 16, "ITEMS", showMaterials, () -> {
            showMaterials = true;
            materialReport = null;
        });
        smallButton(g, mx, my, "f.ref", x + cw - 20, y, 20, 16, "↻", false, () -> {
            files = SchematicManager.getInstance().getAvailableSchematics();
            materialReport = null;
        });
        tip("f.ref", "Quét lại", mx, my, x + cw - 20, y, 20, 16);

        int top = y + 22, h = ch - 22;
        if (showMaterials) {
            drawMaterials(g, mx, my, x, top, cw, h);
            return;
        }

        // file list
        chamfer(g, x, top, listW, h, 6, BG2);
        int rowH = 18, rows = (h - 8) / rowH;
        fileScroll = Mth.clamp(fileScroll, 0, Math.max(0, files.size() - rows));
        if (files.isEmpty()) {
            g.drawString(font, "∅ /schematics", x + 8, top + 8, TEXT_DIM, false);
        }
        for (int i = 0; i < rows && i + fileScroll < files.size(); i++) {
            int idx = i + fileScroll;
            int ry = top + 4 + i * rowH;
            boolean sel = idx == selectedFile;
            float hv = hover("file" + idx, mx, my, x + 4, ry, listW - 8, rowH - 2);
            int bg = sel ? RED_DEEP : lerpColor(0x00000000, TEAL, hv);
            g.fill(x + 4, ry, x + listW - 4, ry + rowH - 2, bg);
            if (sel) g.fill(x + 4, ry, x + 6, ry + rowH - 2, RED);
            String n = files.get(idx).getName().replaceFirst("\\.[^.]+$", "");
            g.drawString(font, trimToWidth(n, listW - 18), x + 10, ry + 5, sel ? RED : lerpColor(TEXT_DIM, RED, hv), false);
            final int fi = idx;
            hit(x + 4, ry, listW - 8, rowH - 2, () -> selectedFile = fi);
        }

        // detail card
        int dx = x + listW + 6, dw = cw - listW - 6;
        chamfer(g, dx, top, dw, h, 6, BG2);
        chamferOutline(g, dx, top, dw, h, 6, RED_DIM);
        if (files.isEmpty() || selectedFile >= files.size()) return;
        File f = files.get(selectedFile);
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        boolean loaded = s != null && s.getName().equalsIgnoreCase(f.getName());
        g.drawString(font, trimToWidth(f.getName().replaceFirst("\\.[^.]+$", "").toUpperCase(), dw - 16), dx + 8, top + 8, RED, false);
        g.drawString(font, (f.length() / 1024) + " KB", dx + 8, top + 20, TEXT_DIM, false);
        if (loaded) {
            statRow(g, dx + 8, top + 38, dw - 16, "SIZE", s.getSize().getX() + "×" + s.getSize().getY() + "×" + s.getSize().getZ());
            statRow(g, dx + 8, top + 52, dw - 16, "BLOCKS", String.valueOf(s.getTotalBlocks()));
            statRow(g, dx + 8, top + 66, dw - 16, "DONE", String.format("%.1f%%", s.getProgressPercentage()));
            g.fill(dx + 8, top + 82, dx + 8 + (int) ((dw - 16) * s.getProgressPercentage() / 100f), top + 84, RED);
            g.fill(dx + 8 + (int) ((dw - 16) * s.getProgressPercentage() / 100f), top + 82, dx + dw - 8, top + 84, 0xFF2B2F3A);
        }
        int by = top + h - 26;
        if (loaded) {
            bigButton(g, mx, my, "unload", dx + 8, by, dw - 16, 20, "✖  UNLOAD", TEAL, RED, () -> {
                SchematicManager.getInstance().unload();
                materialReport = null;
            });
        } else {
            bigButton(g, mx, my, "load", dx + 8, by, dw - 16, 20, "⇣  LOAD", RED, INK, () -> {
                SchematicManager.getInstance().loadFile(f);
                materialReport = null;
            });
        }
    }

    private void drawMaterials(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        chamfer(g, x, y, w, h, 6, BG2);
        if (s == null) {
            g.drawString(font, "∅ LOAD A SCHEMATIC", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        if (materialReport == null) materialReport = MaterialReport.generate(minecraft, s);
        List<MaterialReport.Entry> list = materialReport.getEntries();
        // grid of item tiles: icon + count, colour = status
        int tile = 46, cols = Math.max(1, (w - 8) / tile), rowsVisible = Math.max(1, (h - 8) / 34);
        int maxScroll = Math.max(0, (list.size() + cols - 1) / cols - rowsVisible);
        matScroll = Mth.clamp(matScroll, 0, maxScroll);
        for (int i = 0; i < list.size(); i++) {
            int row = i / cols - matScroll, col = i % cols;
            if (row < 0 || row >= rowsVisible) continue;
            MaterialReport.Entry e = list.get(i);
            int tx = x + 4 + col * tile, ty = y + 4 + row * 34;
            int status = e.isLayerReady() && e.isTotalReady() ? OK : e.isLayerReady() ? 0xFFF2C14E : RED;
            float hv = hover("mat" + i, mx, my, tx, ty, tile - 4, 30);
            g.fill(tx, ty, tx + tile - 4, ty + 30, lerpColor(TEAL, TEAL_HI, hv));
            g.fill(tx, ty + 28, tx + tile - 4, ty + 30, status);
            g.renderItem(new ItemStack(e.item), tx + 3, ty + 4);
            int have = e.inPlayerInv + e.inChests;
            String need = compact(Math.max(0, e.totalRequired - e.placedCount));
            g.drawString(font, need, tx + tile - 6 - font.width(need), ty + 6, RED, false);
            g.drawString(font, compact(have), tx + tile - 6 - font.width(compact(have)), ty + 17, TEXT_DIM, false);
            if (hv > 0.5f) {
                tooltip = e.name + "  ·  cần " + (e.totalRequired - e.placedCount) + "  ·  túi " + e.inPlayerInv + "  ·  rương " + e.inChests;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // PLACE: anchor + layer
    // ------------------------------------------------------------------------------------------------

    private void drawPlace(GuiGraphics g, int mx, int my) {
        BuilderConfig cfg = BuilderConfig.getInstance();
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        SchematicManager sm = SchematicManager.getInstance();
        int x = cx, y = cy, half = (cw - 6) / 2;

        // Anchor module
        chamfer(g, x, y, half, ch, 6, BG2);
        label(g, x + 8, y + 6, "ANCHOR");
        if (s == null) {
            g.drawString(font, "∅", x + 8, y + 22, TEXT_DIM, false);
        } else {
            BlockPos a = s.getAnchor();
            coord(g, x + 8, y + 22, "X", a.getX());
            coord(g, x + 8, y + 36, "Y", a.getY());
            coord(g, x + 8, y + 50, "Z", a.getZ());
            g.drawString(font, "⟳ " + s.getRotation().name().replace("CLOCKWISE_", "").replace("COUNTERCLOCKWISE_", "-"), x + 8, y + 66, TEXT_DIM, false);

            // free-cam editor: drag arrows / ring in the world instead of nudge buttons
            int ex = x + half - 104, ey = y + 20, ew = 96, eh = 58;
            float eh0 = hover("edit3dP", mx, my, ex, ey, ew, eh);
            skewRect(g, ex + 3, ey + 3, ew, eh, 6, alpha(RED, 0.55f + 0.45f * eh0));
            com.xlrw.compat.GuiCompat.push(g);
            float es = 1f + 0.05f * eh0;
            com.xlrw.compat.GuiCompat.translate(g, ex + ew / 2f - 2f * eh0, ey + eh / 2f - 2.5f * eh0, 0);
            com.xlrw.compat.GuiCompat.scale(g, es, es, 1f);
            com.xlrw.compat.GuiCompat.translate(g, -(ex + ew / 2f), -(ey + eh / 2f), 0);
            skewRect(g, ex, ey, ew, eh, 6, lerpColor(CREAM, 0xFFFFF1E4, eh0));
            com.xlrw.compat.GuiCompat.push(g);
            com.xlrw.compat.GuiCompat.translate(g, ex + ew / 2f + 3, ey + 12, 0);
            com.xlrw.compat.GuiCompat.scale(g, 2f, 2f, 1f);
            g.drawCenteredString(font, "✥", 0, 0, RED);
            com.xlrw.compat.GuiCompat.pop(g);
            g.drawCenteredString(font, "EDIT 3D", ex + ew / 2 + 2, ey + eh - 16, INK);
            com.xlrw.compat.GuiCompat.pop(g);
            hit(ex, ey, ew + 6, eh, this::openEditor);
            tip("edit3dP", "Free cam: kéo mũi tên dời, kéo vòng xoay", mx, my, ex, ey, ew, eh);

            int by = y + ch - 52;
            bigButton(g, mx, my, "anchorme", x + 8, by, half - 16, 20, "⌖  TO ME", RED, INK, sm::anchorToPlayer);
            tip("anchorme", "Đặt gốc schematic tại chỗ bạn đứng", mx, my, x + 8, by, half - 16, 20);
            bigButton(g, mx, my, "rotate", x + 8, by + 24, half - 16, 20, "⟳  ROTATE", TEAL, RED, sm::rotate);
        }

        // Layer module
        int lx = x + half + 6;
        chamfer(g, lx, y, half, ch, 6, BG2);
        label(g, lx + 8, y + 6, "LAYER");
        if (s != null) {
            int max = s.getMaxY() - s.getMinY();
            String big = String.valueOf(cfg.currentLayer);
            com.xlrw.compat.GuiCompat.push(g);
            com.xlrw.compat.GuiCompat.translate(g, lx + 8, y + 22, 0);
            com.xlrw.compat.GuiCompat.scale(g, 2.5f, 2.5f, 1f);
            g.drawString(font, big, 0, 0, RED, false);
            com.xlrw.compat.GuiCompat.pop(g);
            g.drawString(font, "/" + max + "   Y " + (s.getMinY() + cfg.currentLayer), lx + 12 + (int) (font.width(big) * 2.5f), y + 34, TEXT_DIM, false);
            // vertical layer meter
            int mxx = lx + half - 14, mtop = y + 20, mh = ch - 90;
            g.fill(mxx, mtop, mxx + 4, mtop + mh, 0xFF2B2F3A);
            int fillH = max > 0 ? (int) (mh * (cfg.currentLayer / (float) max)) : 0;
            g.fill(mxx, mtop + mh - fillH, mxx + 4, mtop + mh, RED);

            int by = y + 52, bw = (half - 40) / 3;
            smallButton(g, mx, my, "l-", lx + 8, by, bw, 18, "−", false, () -> {
                if (cfg.currentLayer > 0) cfg.currentLayer--;
                BuilderConfig.save();
            });
            smallButton(g, mx, my, "l+", lx + 8 + bw + 3, by, bw, 18, "+", false, () -> {
                cfg.currentLayer++;
                BuilderConfig.save();
            });
            smallButton(g, mx, my, "l0", lx + 8 + (bw + 3) * 2, by, bw, 18, "0", false, () -> {
                cfg.currentLayer = 0;
                BuilderConfig.save();
            });
        }
        int ty = y + ch - 52;
        toggle(g, mx, my, "p.layer", lx + 8, ty, half - 16, "BY LAYER", () -> cfg.layerMode, v -> cfg.layerMode = v, "Xây từng tầng một");
        toggle(g, mx, my, "p.auto", lx + 8, ty + 24, half - 16, "AUTO NEXT", () -> cfg.autoLayer, v -> cfg.autoLayer = v, "Xong tầng tự lên tầng tiếp");
    }

    // ------------------------------------------------------------------------------------------------
    // ENGINE
    // ------------------------------------------------------------------------------------------------

    private void drawEngine(GuiGraphics g, int mx, int my) {
        BuilderConfig cfg = BuilderConfig.getInstance();
        int x = cx, y = cy;
        int colW = (cw - 6) / 2;
        // switch grid (2 columns)
        String[][] items = {
                {"MOVE", "Tự di chuyển"}, {"SCAFFOLD", "Dựng giàn giáo leo cao"},
                {"FIX", "Tự đập block sai"}, {"AIR PLACE", "Đặt block giữa không trung"},
                {"FAST", "Đặt nhanh block thường"}, {"SPOOF", "Đặt block có hướng không cần nhìn đúng hướng"},
                {"CHORUS", "Ăn chorus thoát hố"}, {"CLEAN", "Dọn block tạm & trụ thừa"},
                {"WALL", "Đặt xuyên tường ở mọi nơi trong tầm với (tắt nếu server có anti-cheat)"}, {"DRY MARK", "Hiện khung đánh dấu của chạy thử"},
        };
        BooleanSupplier[] get = {
                () -> cfg.autoMove, () -> cfg.useScaffolding, () -> cfg.autoDemolish, () -> cfg.airPlace,
                () -> cfg.fastSimpleBlocks, () -> cfg.rotationSpoof, () -> cfg.chorusEscape, () -> cfg.cleanupTempBlocks,
                () -> cfg.throughWallAnywhere, () -> cfg.renderDryRun
        };
        List<java.util.function.Consumer<Boolean>> set = List.of(
                v -> cfg.autoMove = v, v -> cfg.useScaffolding = v, v -> cfg.autoDemolish = v, v -> cfg.airPlace = v,
                v -> cfg.fastSimpleBlocks = v, v -> cfg.rotationSpoof = v, v -> cfg.chorusEscape = v, v -> cfg.cleanupTempBlocks = v,
                v -> cfg.throughWallAnywhere = v, v -> cfg.renderDryRun = v
        );
        for (int i = 0; i < items.length; i++) {
            int tx = x + (i % 2) * (colW + 6), ty = y + (i / 2) * 24;
            toggle(g, mx, my, "e" + i, tx, ty, colW, items[i][0], get[i], set.get(i), items[i][1]);
        }

        // Supply chests
        int sy = y + 5 * 24 + 4;
        chamfer(g, x, sy, cw, ch - (sy - y), 6, BG2);
        label(g, x + 8, sy + 6, "SUPPLY");
        List<BlockPos> chests = ChestManager.getInstance().getChestPositions();
        g.drawString(font, "×" + chests.size(), x + 56, sy + 6, RED, false);
        toggle(g, mx, my, "refill", x + cw - 96, sy + 3, 88, "REFILL", () -> cfg.autoRefill, v -> cfg.autoRefill = v, "Hết đồ tự đi lấy ở rương");
        int ly = sy + 24;
        for (int i = 0; i < Math.min(3, chests.size()); i++) {
            BlockPos p = chests.get(i);
            g.drawString(font, "▣ " + p.getX() + " " + p.getY() + " " + p.getZ(), x + 8, ly + i * 11, TEXT_DIM, false);
        }
        if (chests.size() > 3) g.drawString(font, "+" + (chests.size() - 3), x + 8, ly + 33, TEXT_DIM, false);

        int bx = x + cw - 8 - 3 * 26, by = sy + ch - (sy - y) - 24;
        padButton(g, mx, my, "c.add", bx, by, "+", () -> {
            if (minecraft.hitResult instanceof BlockHitResult bhr) {
                ChestManager.getInstance().addChestPos(bhr.getBlockPos());
                notifyPlayer("§c[XLRW] §f+ " + bhr.getBlockPos().toShortString());
            }
        }, "Thêm rương đang nhìn");
        padButton(g, mx, my, "c.pop", bx + 26, by, "−", () -> {
            List<BlockPos> l = ChestManager.getInstance().getChestPositions();
            if (!l.isEmpty()) ChestManager.getInstance().removeChestPos(l.get(l.size() - 1));
        }, "Xóa rương cuối");
        padButton(g, mx, my, "c.clr", bx + 52, by, "✖", () -> ChestManager.getInstance().clearChestPositions(), "Xóa tất cả rương");

        // speed
        g.drawString(font, "⏱ " + cfg.placeDelayMs + "ms", x + 8, by + 7, RED, false);
        padButton(g, mx, my, "d-", x + 70, by, "−", () -> {
            cfg.placeDelayMs = Math.max(50, cfg.placeDelayMs - 20);
            BuilderConfig.save();
        }, "Nhanh hơn");
        padButton(g, mx, my, "d+", x + 96, by, "+", () -> {
            cfg.placeDelayMs = Math.min(800, cfg.placeDelayMs + 20);
            BuilderConfig.save();
        }, "Chậm hơn");
        // dry run (also on key N)
        padButton(g, mx, my, "dry", x + 130, by, com.xlrw.builder.DryRun.isRunning() ? "…" : "◎", () -> {
            com.xlrw.builder.DryRun.toggle(minecraft);
            onClose();
        }, "Chạy thử (dry run): ước tính thời gian, giàn giáo, chỗ bị kẹt. Bấm lại để tắt");
    }

    // ------------------------------------------------------------------------------------------------
    // VIEW
    // ------------------------------------------------------------------------------------------------

    private void drawView(GuiGraphics g, int mx, int my) {
        BuilderConfig cfg = BuilderConfig.getInstance();
        int colW = (cw - 6) / 2;
        String[][] items = {
                {"HOLOGRAM", "Hiện block ma của schematic"}, {"TARGET", "Khung block sắp đặt"},
                {"BOUNDS", "Khung bao schematic"}, {"ONE LAYER", "Chỉ hiện tầng đang xây"},
                {"SMOOTH AIM", "Quay chuột mượt như người"}, {"RAYCAST", "Không đặt xuyên tường"},
                {"3D UI", "Bảng nghiêng 3D + parallax"},
        };
        BooleanSupplier[] get = {() -> cfg.renderHologram, () -> cfg.renderTargetBox, () -> cfg.renderBoundingBox,
                () -> cfg.renderOnlyCurrentLayer, () -> cfg.smoothAim, () -> cfg.strictRaycast, () -> cfg.ui3d};
        List<java.util.function.Consumer<Boolean>> set = List.of(v -> cfg.renderHologram = v, v -> cfg.renderTargetBox = v,
                v -> cfg.renderBoundingBox = v, v -> cfg.renderOnlyCurrentLayer = v, v -> cfg.smoothAim = v, v -> cfg.strictRaycast = v,
                v -> cfg.ui3d = v);
        for (int i = 0; i < items.length; i++) {
            int tx = cx + (i % 2) * (colW + 6), ty = cy + (i / 2) * 24;
            toggle(g, mx, my, "v" + i, tx, ty, colW, items[i][0], get[i], set.get(i), items[i][1]);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------------------------------------

    private void toggle(GuiGraphics g, int mx, int my, String id, int x, int y, int w, String text,
                        BooleanSupplier get, java.util.function.Consumer<Boolean> set, String tipText) {
        boolean on = get.getAsBoolean();
        float hv = hover(id, mx, my, x, y, w, 20);
        float k = smooth(id + ".k", on ? 1f : 0f, 0.3f);
        liftBegin(g, hv, x, y, w, 20, 5);
        chamfer(g, x, y, w, 20, 5, lerpColor(TEAL, TEAL_HI, hv));
        if (k > 0.01f) g.fill(x + 5, y + 18, x + 5 + (int) ((w - 5) * k), y + 19, RED); // underline grows when on
        g.drawString(font, text, x + 7, y + 6, lerpColor(TEXT_DIM, RED, Math.max(k, hv * 0.6f)), false);
        // switch
        int sw = 22, sx = x + w - sw - 6, sy = y + 6;
        g.fill(sx, sy, sx + sw, sy + 8, lerpColor(0xFF2B2F3A, RED_DEEP, k));
        int kx = sx + 1 + (int) ((sw - 9) * k);
        g.fill(kx, sy + 1, kx + 7, sy + 7, lerpColor(TEXT_DIM, RED, k));
        liftEnd(g);
        hit(x, y, w, 20, () -> {
            set.accept(!get.getAsBoolean());
            BuilderConfig.save();
            XlrwSounds.play(get.getAsBoolean() ? "ui.toggle_on" : "ui.toggle_off", 1f, 0.5f);
            soundDone = true;
        });
        tip(id, tipText, mx, my, x, y, w, 20);
    }

    private void bigButton(GuiGraphics g, int mx, int my, String id, int x, int y, int w, int h, String text, int bg, int fg, Runnable r) {
        float hv = hover(id, mx, my, x, y, w, h);
        liftBegin(g, hv, x, y, w, h, 6);
        chamfer(g, x, y, w, h, 6, lerpColor(bg, brighten(bg), hv));
        // hover sweep
        if (hv > 0.02f) g.fill(x + 6, y + h - 2, x + 6 + (int) ((w - 8) * hv), y + h - 1, fg);
        g.drawCenteredString(font, text, x + w / 2, y + (h - 8) / 2, fg);
        liftEnd(g);
        hit(x, y, w, h, r);
    }

    private void smallButton(GuiGraphics g, int mx, int my, String id, int x, int y, int w, int h, String text, boolean active, Runnable r) {
        float hv = hover(id, mx, my, x, y, w, h);
        int bg = active ? RED : lerpColor(TEAL, TEAL_HI, hv);
        liftBegin(g, hv, x, y, w, h, 4);
        chamfer(g, x, y, w, h, 4, bg);
        g.drawCenteredString(font, text, x + w / 2, y + (h - 8) / 2, active ? INK : lerpColor(TEXT_DIM, RED, hv));
        liftEnd(g);
        hit(x, y, w, h, r);
    }

    /**
     * Hover "lift": the widget rises toward the viewer - a soft shadow stays on the panel, an orange side edge shows
     * the thickness and the face grows a little and moves up-left. Pair with {@link #liftEnd}.
     */
    private void liftBegin(GuiGraphics g, float hv, int x, int y, int w, int h, int cut) {
        if (hv > 0.01f) {
            // only a soft contact shadow under the bottom edge - no second copy of the button behind it
            g.fill(x + cut, y + h - 1, x + w + 1, y + h + 1, alpha(0xFF000000, 0.45f * hv));
            g.fill(x + cut + 2, y + h + 1, x + w - 1, y + h + 2, alpha(0xFF000000, 0.22f * hv));
        }
        com.xlrw.compat.GuiCompat.push(g);
        float s = 1f + 0.05f * hv, px = x + w / 2f, py = y + h / 2f;
        com.xlrw.compat.GuiCompat.translate(g, px - 1.5f * hv, py - 2f * hv, 0);
        com.xlrw.compat.GuiCompat.scale(g, s, s, 1f);
        com.xlrw.compat.GuiCompat.translate(g, -px, -py, 0);
    }

    private void liftEnd(GuiGraphics g) {
        com.xlrw.compat.GuiCompat.pop(g);
    }

    private void padButton(GuiGraphics g, int mx, int my, String id, int x, int y, String glyph, Runnable r, String tipText) {
        smallButton(g, mx, my, id, x, y, 22, 20, glyph, false, r);
        tip(id, tipText, mx, my, x, y, 22, 20);
    }

    private void label(GuiGraphics g, int x, int y, String s) {
        skewRect(g, x, y, 4, 8, 2, RED);
        g.drawString(font, s, x + 7, y, CREAM, false);
    }

    private void coord(GuiGraphics g, int x, int y, String axis, int v) {
        g.drawString(font, axis, x, y, RED_DIM, false);
        g.drawString(font, String.valueOf(v), x + 12, y, RED, false);
    }

    private void statRow(GuiGraphics g, int x, int y, int w, String k, String v) {
        g.drawString(font, k, x, y, TEXT_DIM, false);
        drawRight(g, v, x + w, y, RED);
    }

    private void drawTooltip(GuiGraphics g, int mx, int my, String text) {
        int w = font.width(text) + 10, h = 14;
        int x = Math.min(mx + 8, width - w - 2), y = Math.max(2, my - 16);
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, 0, 0, 400);
        chamfer(g, x, y, w, h, 3, 0xF0201418);
        g.fill(x, y + h - 1, x + w, y + h, RED);
        g.drawString(font, text, x + 5, y + 3, 0xFFFFC9C4, false);
        com.xlrw.compat.GuiCompat.pop(g);
    }

    // ------------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (UiImages.editing || UiImages.picker) {
            double[] el = tilts[0].toLocal(mouseX, mouseY);
            if (button == 0) {
                for (int i = hits.size() - 1; i >= editHitStart && i >= 0; i--) { // toolbar / picker first
                    Hit h = hits.get(i);
                    if (h.panel == 0 && h.contains(el[0], el[1])) {
                        h.action.run();
                        XlrwSounds.playVar("ui.click", 1f, 0.45f, 0.06f);
                        return true;
                    }
                }
                if (UiImages.picker) return true;
            }
            if (el[0] >= cx && el[0] < cx + cw && el[1] >= cy && el[1] < cy + ch
                    && UiImages.mouseDown(tab.name(), el[0], el[1], button, cx, cy)) return true;
        }
        if (tab == Tab.HUB) {
            double[] hl = tilts[0].toLocal(mouseX, mouseY);
            if (HubWidgets.mouseDown(hl[0], hl[1], button)) return true;
        }
        if (button == 0 && MASCOT.contains(mouseX, mouseY)) { // Bit is on top of everything
            MASCOT.grab(mouseX, mouseY);
            return true;
        }
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                double[] l = tilts[h.panel].toLocal(mouseX, mouseY);
                if (h.contains(l[0], l[1])) {
                    soundDone = false;
                    boolean en = BuilderConfig.getInstance().enabled;
                    h.action.run();
                    if (en != BuilderConfig.getInstance().enabled) soundDone = true; // power sound plays on its own
                    if (!soundDone) XlrwSounds.playVar("ui.click", 1f, 0.45f, 0.06f);
                    MASCOT.react("click");
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        holo.dragging = false;
        if (UiImages.mouseUp()) return true;
        if (tab == Tab.HUB) {
            double[] hl = tilts[0].toLocal(mouseX, mouseY);
            HubWidgets.mouseUp(hl[0], hl[1], button);
        }
        if (MASCOT.held()) MASCOT.release();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        double[] dl = tilts[0].toLocal(mouseX, mouseY);
        if (UiImages.mouseDrag(dl[0], dl[1], cx, cy)) return true;
        if (MASCOT.held() && button == 0) {
            MASCOT.drag(mouseX, mouseY);
            return true;
        }
        if (holo.dragging && button == 0) {
            holo.yaw += (float) dx * 1.2f;
            holo.pitch = Mth.clamp(holo.pitch + (float) dy * 0.8f, -10f, 85f);
            holo.touch();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    private boolean overPreview(double mouseX, double mouseY) {
        if (!showPreview) return false;
        double[] l = tilts[1].toLocal(mouseX, mouseY);
        return l[0] >= pvx && l[0] < pvx + pvw && l[1] >= pvy && l[1] < pvy + pvh;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        double[] sl = tilts[0].toLocal(mouseX, mouseY);
        if (UiImages.scroll(tab.name(), sl[0], sl[1], dy, cx, cy)) return true;
        if (overPreview(mouseX, mouseY)) {
            holo.zoom = Mth.clamp(holo.zoom * (dy > 0 ? 1.12f : 1 / 1.12f), 0.4f, 4f);
            holo.touch();
            return true;
        }
        if (tab == Tab.FILES) {
            if (showMaterials) matScroll -= (int) Math.signum(dy);
            else fileScroll -= (int) Math.signum(dy);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    private void switchTab(Tab t) {
        if (t == tab) return;
        tab = t;
        tabChangedAt = System.currentTimeMillis();
        XlrwSounds.playVar("ui.tab", 1f, 0.5f, 0.05f);
        soundDone = true;
        if (t == Tab.FILES) files = SchematicManager.getInstance().getAvailableSchematics();
    }

    private void hit(int x, int y, int w, int h, Runnable r) {
        hits.add(new Hit(panel, x, y, w, h, r));
    }

    private void tip(String id, String text, int mx, int my, int x, int y, int w, int h) {
        if (mx >= x && mx < x + w && my >= y && my < y + h && anim.getOrDefault(id + ".hold", 0f) > 0.95f) tooltip = text;
    }

    @Override
    public void onClose() {
        XlrwSounds.play("ui.close", 1f, 0.45f);
        BuilderConfig.save();
        super.onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (tab == Tab.HUB && HubWidgets.keyPressed(key)) return true;
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_F && UiImages.toggleLayer()) return true;
        // the same key that opens the deck (V by default) closes it again
        for (net.minecraft.client.KeyMapping km : minecraft.options.keyMappings) {
            if ("key.xlrw.gui".equals(km.getName()) && km.matches(key, scan)) {
                onClose();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------------------------------------
    // Status strip under the left panel (progress while another tab is open)
    // ------------------------------------------------------------------------------------------------

    private void drawStatusStrip(GuiGraphics g, int mx, int my, long now) {
        float t = ease(smooth("strip", tab != Tab.DECK ? 1f : 0f, 0.16f));
        if (t < 0.01f) return;
        int h = 24, x = wx + 8, w = ww - 16;
        int y = wy + wh - h + Math.round((h + 6) * t);
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        BuilderConfig cfg = BuilderConfig.getInstance();
        float hv = hover("strip.h", mx, my, x, y, w, h);
        chamfer(g, x, y, w, h, 6, lerpColor(BG, 0xF2232429, hv));
        chamferOutline(g, x, y, w, h, 6, alpha(RED_DIM, 0.9f));
        float target = s != null ? s.getProgressPercentage() : 0f;
        shownProgress += (target - shownProgress) * 0.12f;
        String pct = String.format("%.0f%%", shownProgress);
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, x + 9, y + 6, 0);
        com.xlrw.compat.GuiCompat.scale(g, 1.5f, 1.5f, 1f);
        g.drawString(font, pct, 0, 0, RED, false);
        com.xlrw.compat.GuiCompat.pop(g);
        String blocks = s != null ? compact(s.getPlacedBlocks()) + "/" + compact(s.getTotalBlocks()) : "-";
        String layer = s != null ? "L" + cfg.currentLayer + "/" + (s.getMaxY() - s.getMinY()) : "";
        int right = x + w - 10;
        drawRight(g, layer, right, y + 8, CREAM);
        int bxEnd = right - font.width(layer) - 10;
        drawRight(g, blocks, bxEnd, y + 8, TEXT_DIM);
        int bx0 = x + 16 + (int) (font.width(pct) * 1.5f), bx1 = bxEnd - font.width(blocks) - 10, segs = Math.max(4, (bx1 - bx0) / 5);
        float filled = shownProgress / 100f * segs;
        for (int i = 0; i < segs && bx1 > bx0; i++) {
            int sx = bx0 + i * (bx1 - bx0) / segs, ex = bx0 + (i + 1) * (bx1 - bx0) / segs - 1;
            int c = i < (int) filled ? RED : (i == (int) filled && (now / 250) % 2 == 0 ? RED_DIM : 0xFF2B2F3A);
            g.fill(sx, y + 9, ex, y + 15, c);
        }
        // live dot
        g.fill(x + w - 5, y + 3, x + w - 3, y + 5, cfg.enabled ? alpha(RED, 0.5f + 0.5f * (float) Math.sin(now / 150.0)) : RED_DIM);
        hit(x, y, w, h, () -> switchTab(Tab.DECK));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void notifyPlayer(String s) {
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(s), true);
    }

    // ------------------------------------------------------------------------------------------------
    // Animation helpers
    // ------------------------------------------------------------------------------------------------

    /** Smoothed hover amount 0..1 for a widget; also tracks a slower "hold" value used to delay tooltips. */
    private float hover(String id, int mx, int my, int x, int y, int w, int h) {
        boolean in = mx >= x && mx < x + w && my >= y && my < y + h;
        Boolean was = hoverIn.put(id, in);
        if (in && Boolean.FALSE.equals(was) && System.currentTimeMillis() - lastHoverSound > 45) {
            lastHoverSound = System.currentTimeMillis();
            XlrwSounds.playVar("ui.hover", 1f, 0.3f, 0.05f); // soft tick on hover-in
        }
        smooth(id + ".hold", in ? 1f : 0f, in ? 0.03f : 1f);
        return smooth(id, in ? 1f : 0f, 0.3f);
    }

    private float smooth(String id, float target, float speed) {
        float v = anim.getOrDefault(id, target == 1f ? 0f : target);
        v += (target - v) * speed;
        if (Math.abs(target - v) < 0.002f) v = target;
        anim.put(id, v);
        return v;
    }

    private static float ease(float t) {
        return 1f - (float) Math.pow(1f - t, 3);
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing helpers
    // ------------------------------------------------------------------------------------------------

    /** Shear the current pose so text / shapes lean forward like Persona menus. */
    private static void shear(GuiGraphics g, float k) {
        com.xlrw.compat.GuiCompat.shearX(g, (k));
    }

    /** Parallelogram leaning right: the top edge is shifted {@code slant} px to the right of the bottom edge. */
    private static void skewRect(GuiGraphics g, int x, int y, int w, int h, int slant, int color) {
        if (h <= 0 || w <= 0) return;
        int band = Math.max(1, h / Math.max(1, slant));
        for (int row = 0; row < h; row += band) {
            int rh = Math.min(band, h - row);
            int off = (int) Math.round(slant * (1.0 - (row + rh / 2.0) / h));
            g.fill(x + off, y + row, x + off + w, y + row + rh, color);
        }
    }

    /** Rectangle with the top-right and bottom-left corners cut at 45° (the 2077 panel shape). */
    private static void chamfer(GuiGraphics g, int x, int y, int w, int h, int cut, int color) {
        cut = Math.min(cut, Math.min(w, h) / 2);
        for (int i = 0; i < cut; i++) {
            g.fill(x, y + i, x + w - (cut - i), y + i + 1, color);              // top rows (right corner cut)
            g.fill(x + (cut - i), y + h - 1 - i, x + w, y + h - i, color);    // bottom rows (left corner cut)
        }
        g.fill(x, y + cut, x + w, y + h - cut, color);
    }

    private static void chamferOutline(GuiGraphics g, int x, int y, int w, int h, int cut, int color) {
        cut = Math.min(cut, Math.min(w, h) / 2);
        g.fill(x, y, x + w - cut, y + 1, color);                 // top
        g.fill(x + w - 1, y + cut, x + w, y + h, color);         // right
        g.fill(x + cut, y + h - 1, x + w, y + h, color);         // bottom
        g.fill(x, y, x + 1, y + h - cut, color);                 // left
        for (int i = 0; i < cut; i++) {
            g.fill(x + w - cut + i, y + i, x + w - cut + i + 1, y + i + 1, color);   // top-right diagonal
            g.fill(x + i, y + h - cut + i, x + i + 1, y + h - cut + i + 1, color);   // bottom-left diagonal
        }
    }

    private void drawRight(GuiGraphics g, String s, int rightX, int y, int color) {
        g.drawString(font, s, rightX - font.width(s), y, color, false);
    }

    private void drawCentered(GuiGraphics g, String s, int cxp, int y, int color, float scale) {
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, cxp, y, 0);
        com.xlrw.compat.GuiCompat.scale(g, scale, scale, 1f);
        g.drawString(font, s, -font.width(s) / 2, 0, color, false);
        com.xlrw.compat.GuiCompat.pop(g);
    }

    private String trimToWidth(String s, int w) {
        if (font.width(s) <= w) return s;
        while (s.length() > 1 && font.width(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String stripCodes(String s) {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    private static String compact(int n) {
        if (n >= 10000) return (n / 1000) + "k";
        if (n >= 1000) return String.format("%.1fk", n / 1000f);
        return String.valueOf(n);
    }

    private static int alpha(int c, float a) {
        int al = Mth.clamp((int) (((c >>> 24) & 0xFF) * a), 0, 255);
        return (al << 24) | (c & 0xFFFFFF);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static int brighten(int c) {
        return lerpColor(c, 0xFFFFFFFF, 0.15f) | 0xFF000000;
    }
}
