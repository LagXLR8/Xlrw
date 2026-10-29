package com.xlrw.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pictures (png / jpg / jpeg / bmp / gif) the player pins onto the deck's tabs. Files live in config/xlrw/images,
 * placements in config/xlrw/ui_images.json (tab, position relative to the tab's content area, scale, layer).
 *
 * <p>Edit mode: drag to move, drag the orange corner to resize, scroll to scale, right click to remove,
 * F to send behind / bring to front. The "+" picker adds a picture to the current tab.
 */
final class UiImages {

    static final class Placed {
        String file;
        String tab;
        float x, y;       // relative to the content area's top-left
        float scale = 1f; // GUI px per image px (fit on add)
        boolean behind;
    }

    private record Tex(ResourceLocation loc, int w, int h, long stamp) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<Placed> placed;
    private static final Map<String, Tex> textures = new HashMap<>();
    private static int texCounter;

    static boolean editing;
    static boolean picker;
    private static Placed drag;
    private static boolean resizing;
    private static double grabDx, grabDy;
    private static float startScale, startW;
    private static Placed hovered;
    private static List<File> pickList = new ArrayList<>();
    private static long pickScanAt;

    private UiImages() {}

    // ------------------------------------------------------------------------------------------------
    // Files / persistence
    // ------------------------------------------------------------------------------------------------

    static File dir() {
        File d = new File(Minecraft.getInstance().gameDirectory, "config/xlrw/images");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File dataFile() {
        return new File(Minecraft.getInstance().gameDirectory, "config/xlrw/ui_images.json");
    }

    static void openFolder() {
        try { net.minecraft.Util.getPlatform().openFile(dir()); } catch (Throwable ignored) {}
    }

    private static List<Placed> all() {
        if (placed == null) {
            placed = new ArrayList<>();
            File f = dataFile();
            if (f.isFile()) {
                try (FileReader r = new FileReader(f, java.nio.charset.StandardCharsets.UTF_8)) {
                    List<Placed> l = GSON.fromJson(r, new TypeToken<List<Placed>>() {}.getType());
                    if (l != null) placed.addAll(l);
                } catch (Exception ignored) {
                }
            }
        }
        return placed;
    }

    static void save() {
        try {
            File f = dataFile();
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(all(), w);
            }
        } catch (Exception ignored) {
        }
    }

    static boolean supported(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".bmp") || n.endsWith(".gif");
    }

    /** Texture for a file, (re)loaded when the file changes on disk; null if missing / unreadable. */
    private static Tex tex(String file) {
        File f = new File(dir(), file);
        if (!f.isFile()) return null;
        Tex t = textures.get(file);
        if (t != null && t.stamp == f.lastModified()) return t;
        try {
            NativeImage img = load(f);
            if (img == null) return null;
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("xlrw", "dynamic/ui_image_" + (texCounter++));
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            if (t != null) Minecraft.getInstance().getTextureManager().release(t.loc);
            t = new Tex(loc, img.getWidth(), img.getHeight(), f.lastModified());
            textures.put(file, t);
            return t;
        } catch (Throwable e) {
            return null;
        }
    }

    private static NativeImage load(File f) throws Exception {
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            try (InputStream in = new FileInputStream(f)) {
                return NativeImage.read(in);
            }
        }
        // jpg / bmp / gif through ImageIO, copied into a NativeImage (ABGR)
        java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(f);
        if (bi == null) return null;
        int w = bi.getWidth(), h = bi.getHeight();
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int argb = bi.getRGB(x, y);
            int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
            img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
        }
        return img;
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing (panel-local coordinates, inside the content area)
    // ------------------------------------------------------------------------------------------------

    private static float[] rect(Placed p, Tex t, int cx, int cy) {
        float w = t.w * p.scale, h = t.h * p.scale;
        return new float[]{cx + p.x, cy + p.y, w, h};
    }

    static void draw(GuiGraphics g, String tab, boolean behindLayer, int cx, int cy, int mx, int my) {
        if (behindLayer) hovered = null; // the behind layer is drawn first each frame
        for (Placed p : all()) {
            if (!tab.equals(p.tab) || p.behind != behindLayer) continue;
            Tex t = tex(p.file);
            if (t == null) continue;
            float[] r = rect(p, t, cx, cy);
            int x0 = Math.round(r[0]), y0 = Math.round(r[1]), w = Math.max(1, Math.round(r[2])), h = Math.max(1, Math.round(r[3]));
            g.blit(t.loc, x0, y0, w, h, 0, 0, t.w, t.h, t.w, t.h);
            if (editing) {
                boolean hov = mx >= x0 && mx < x0 + w && my >= y0 && my < y0 + h;
                if (hov) hovered = p;
                int c = drag == p ? 0xFFF3701E : hov ? 0xFFE8D8C9 : 0x88E8D8C9;
                g.fill(x0, y0, x0 + w, y0 + 1, c);
                g.fill(x0, y0 + h - 1, x0 + w, y0 + h, c);
                g.fill(x0, y0, x0 + 1, y0 + h, c);
                g.fill(x0 + w - 1, y0, x0 + w, y0 + h, c);
                g.fill(x0 + w - 5, y0 + h - 5, x0 + w + 1, y0 + h + 1, 0xFFF3701E); // resize handle
                if (p.behind) g.fill(x0 + 1, y0 + 1, x0 + 4, y0 + 4, 0xFF4B607F);
            }
        }
    }

    /** "+" picker overlay listing the image folder. */
    static void drawPicker(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my, HubWidgets.Hitter hit, String tab, int cx, int cy) {
        if (!picker) return;
        long now = System.currentTimeMillis();
        if (now - pickScanAt > 1500) {
            pickScanAt = now;
            File[] l = dir().listFiles(f -> f.isFile() && supported(f.getName()));
            pickList = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
            pickList.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.fill(x, y, x + w, y + h, 0xF0141416);
        g.fill(x, y, x + 3, y + h, 0xFFF3701E);
        g.drawString(font, "ẢNH  ·  " + tab, x + 8, y + 5, 0xFFE8D8C9, false);
        HubWidgets.button(g, font, x + w - 64, y + 2, 40, 12, "FOLDER", false, mx, my);
        hit.hit(x + w - 64, y + 2, 40, 12, UiImages::openFolder);
        HubWidgets.button(g, font, x + w - 20, y + 2, 16, 12, "✕", false, mx, my);
        hit.hit(x + w - 20, y + 2, 16, 12, () -> picker = false);
        if (pickList.isEmpty()) {
            g.drawCenteredString(font, "Thư mục trống - bấm FOLDER rồi thả ảnh png / jpg vào", x + w / 2, y + h / 2 - 4, 0xFF8D97AB);
        }
        int cell = 46, gap = 6, cols = Math.max(1, (w - 12) / (cell + gap));
        for (int i = 0; i < pickList.size(); i++) {
            int col = i % cols, row = i / cols;
            int tx = x + 8 + col * (cell + gap), ty = y + 20 + row * (cell + gap + 8);
            if (ty + cell > y + h) break;
            File f = pickList.get(i);
            boolean hov = mx >= tx && mx < tx + cell && my >= ty && my < ty + cell;
            g.fill(tx - 1, ty - 1, tx + cell + 1, ty + cell + 1, hov ? 0xFFF3701E : 0xFF2B3240);
            g.fill(tx, ty, tx + cell, ty + cell, 0xFF1C1E25);
            Tex t = tex(f.getName());
            if (t != null) {
                float s = Math.min(cell / (float) t.w, cell / (float) t.h);
                int iw = Math.max(1, Math.round(t.w * s)), ih = Math.max(1, Math.round(t.h * s));
                g.blit(t.loc, tx + (cell - iw) / 2, ty + (cell - ih) / 2, iw, ih, 0, 0, t.w, t.h, t.w, t.h);
            } else g.drawCenteredString(font, "?", tx + cell / 2, ty + cell / 2 - 4, 0xFFE0403A);
            g.pose().pushPose();
            g.pose().translate(tx, ty + cell + 1, 0);
            g.pose().scale(0.5f, 0.5f, 1f);
            g.drawString(font, font.plainSubstrByWidth(f.getName(), cell * 2), 0, 0, 0xFF8D97AB, false);
            g.pose().popPose();
            final String name = f.getName();
            hit.hit(tx, ty, cell, cell, () -> add(name, tab, w, h));
        }
        g.pose().popPose();
    }

    private static void add(String file, String tab, int areaW, int areaH) {
        Tex t = tex(file);
        if (t == null) return;
        Placed p = new Placed();
        p.file = file;
        p.tab = tab;
        p.scale = Math.min(1f, Math.min(areaW * 0.4f / t.w, areaH * 0.5f / t.h));
        p.x = (areaW - t.w * p.scale) / 2f;
        p.y = (areaH - t.h * p.scale) / 2f;
        all().add(p);
        picker = false;
        editing = true;
        save();
        XlrwSounds.play("bit.place", 1.2f, 0.4f);
    }

    // ------------------------------------------------------------------------------------------------
    // Editing input (panel-local coordinates)
    // ------------------------------------------------------------------------------------------------

    /** Topmost picture under the cursor on this tab. */
    private static Placed at(String tab, double mx, double my, int cx, int cy) {
        List<Placed> l = all();
        for (int pass = 0; pass < 2; pass++) {
            boolean behind = pass == 1;
            for (int i = l.size() - 1; i >= 0; i--) {
                Placed p = l.get(i);
                if (!tab.equals(p.tab) || p.behind != behind) continue;
                Tex t = tex(p.file);
                if (t == null) continue;
                float[] r = rect(p, t, cx, cy);
                if (mx >= r[0] - 2 && mx < r[0] + r[2] + 2 && my >= r[1] - 2 && my < r[1] + r[3] + 2) return p;
            }
        }
        return null;
    }

    static boolean mouseDown(String tab, double mx, double my, int button, int cx, int cy) {
        if (!editing || picker) return false;
        Placed p = at(tab, mx, my, cx, cy);
        if (p == null) return false;
        if (button == 1) {
            all().remove(p);
            save();
            XlrwSounds.play("bit.break", 1f, 0.4f);
            return true;
        }
        if (button != 0) return false;
        Tex t = tex(p.file);
        float[] r = rect(p, t, cx, cy);
        drag = p;
        resizing = mx >= r[0] + r[2] - 7 && my >= r[1] + r[3] - 7;
        grabDx = mx - r[0];
        grabDy = my - r[1];
        startScale = p.scale;
        startW = r[2];
        // bring to top of its layer
        all().remove(p);
        all().add(p);
        return true;
    }

    static boolean mouseDrag(double mx, double my, int cx, int cy) {
        if (drag == null) return false;
        Tex t = tex(drag.file);
        if (t == null) return true;
        if (resizing) {
            float w = (float) (mx - (cx + drag.x));
            drag.scale = Math.max(0.02f, Math.min(8f, w / t.w));
        } else {
            drag.x = Math.round(mx - cx - grabDx);
            drag.y = Math.round(my - cy - grabDy);
        }
        return true;
    }

    static boolean mouseUp() {
        if (drag == null) return false;
        drag = null;
        save();
        return true;
    }

    static boolean scroll(String tab, double mx, double my, double dy, int cx, int cy) {
        if (!editing || picker) return false;
        Placed p = at(tab, mx, my, cx, cy);
        if (p == null) return false;
        Tex t = tex(p.file);
        float[] r = rect(p, t, cx, cy);
        float k = dy > 0 ? 1.1f : 1 / 1.1f;
        float ocx = r[0] + r[2] / 2 - cx, ocy = r[1] + r[3] / 2 - cy; // keep the centre in place
        p.scale = Math.max(0.02f, Math.min(8f, p.scale * k));
        p.x = ocx - t.w * p.scale / 2;
        p.y = ocy - t.h * p.scale / 2;
        save();
        return true;
    }

    /** F: send the hovered picture behind the widgets / bring it to the front. */
    static boolean toggleLayer() {
        if (!editing || hovered == null) return false;
        hovered.behind = !hovered.behind;
        save();
        return true;
    }
}
