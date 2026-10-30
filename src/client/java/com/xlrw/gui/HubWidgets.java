package com.xlrw.gui;

import com.xlrw.config.BuilderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * HUB tab: clock (real / in-game / bot uptime), a jukebox playing Minecraft's music discs and "EGG RAID", a small
 * original space shooter against a flock of chickens. All state is static so it survives closing the deck.
 */
public final class HubWidgets {

    interface Hitter { void hit(int x, int y, int w, int h, Runnable r); }

    private static int INK = 0xFF141416, CREAM = 0xFFE8D8C9, ORANGE = 0xFFF3701E, SLATE = 0xFF4B607F,
            PANEL = 0xFF232429, DIM = 0xFF8D97AB, WELL = 0xFF121215;

    /** Follows the deck colour theme. */
    static void theme(UiTheme t) {
        INK = t.ink; CREAM = t.cream; ORANGE = t.accent; SLATE = t.outline; PANEL = t.bg2; DIM = t.textDim;
        WELL = UiTheme.mix(t.bg2, 0xFF000000, 0.45f);
    }
    private static final Random RND = new Random();

    private HubWidgets() {}

    // ================================================================================================
    // Bot uptime (fed from the client tick)
    // ================================================================================================

    private static long runMs, lastTickAt;

    public static long uptimeMs() { return runMs; }

    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (lastTickAt != 0 && BuilderConfig.getInstance().enabled) runMs += Math.min(1000, now - lastTickAt);
        lastTickAt = now;
        Jukebox.tick(mc);
    }

    // ================================================================================================
    // Layout
    // ================================================================================================

    static void draw(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my, long now, Hitter hit) {
        int lw = (int) ((w - 6) * 0.47f), rw = w - 6 - lw;
        int clockH = 92;
        drawClock(g, font, x, y, lw, clockH, now);
        Jukebox.draw(g, font, x, y + clockH + 6, lw, h - clockH - 6, mx, my, now, hit);
        Arcade.draw(g, font, x + lw + 6, y, rw, h, mx, my, now, hit);
    }

    static void card(GuiGraphics g, Font font, int x, int y, int w, int h, String title) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.fill(x, y, x + 3, y + h, ORANGE);
        g.fill(x + 3, y, x + w, y + 1, 0x33E8D8C9);
        g.drawString(font, title, x + 8, y + 5, CREAM, false);
    }

    // ================================================================================================
    // Clock
    // ================================================================================================

    private static void drawClock(GuiGraphics g, Font font, int x, int y, int w, int h, long now) {
        card(g, font, x, y, w, h, "CLOCK");
        LocalDateTime t = LocalDateTime.now();
        // real time, big
        String hhmm = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, x + 8, y + 17, 0);
        com.xlrw.compat.GuiCompat.scale(g, 2f, 2f, 1f);
        g.drawString(font, hhmm, 0, 0, ORANGE, false);
        com.xlrw.compat.GuiCompat.pop(g);
        String ss = t.format(DateTimeFormatter.ofPattern("ss"));
        g.drawString(font, ss, x + 10 + font.width(hhmm) * 2, y + 25, CREAM, false);
        g.drawString(font, t.format(DateTimeFormatter.ofPattern("EEE dd/MM")).toUpperCase(), x + w - 6 - font.width(t.format(DateTimeFormatter.ofPattern("EEE dd/MM")).toUpperCase()), y + 5, DIM, false);
        // seconds ring as a thin progress line
        float sec = (t.getSecond() + t.getNano() / 1e9f) / 60f;
        g.fill(x + 8, y + 37, x + w - 6, y + 38, 0xFF2B2F3A);
        g.fill(x + 8, y + 37, x + 8 + (int) ((w - 14) * sec), y + 38, ORANGE);

        // in-game time with a sun / moon on an arc
        Minecraft mc = Minecraft.getInstance();
        int gy = y + 44;
        if (mc.level != null) {
            long dt = mc.level.getDayTime();
            long day = dt / 24000L + 1;
            int tod = (int) (dt % 24000L);
            int hours = (tod / 1000 + 6) % 24, mins = (int) ((tod % 1000) * 60 / 1000);
            String game = String.format("%02d:%02d", hours, mins);
            g.drawString(font, "☀", x + 8, gy, 0xFFF3C04E, false);
            g.drawString(font, game, x + 20, gy, CREAM, false);
            g.drawString(font, "DAY " + day, x + 20 + font.width(game) + 6, gy, DIM, false);
            // arc
            int ax0 = x + w - 52, ax1 = x + w - 8, ay = gy + 9;
            for (int i = 0; i <= 20; i++) {
                double a = Math.PI * i / 20;
                int px = (int) (ax0 + (ax1 - ax0) * (i / 20.0)), py = (int) (ay - Math.sin(a) * 9);
                g.fill(px, py, px + 1, py + 1, 0x55E8D8C9);
            }
            boolean isDay = tod < 12500 || tod > 23500;
            float f = isDay ? ((tod + 500) % 24000) / 13000f : (tod - 12500) / 11000f;
            f = Math.max(0, Math.min(1, f));
            int sx = (int) (ax0 + (ax1 - ax0) * f), sy = (int) (ay - Math.sin(Math.PI * f) * 9);
            g.fill(sx - 2, sy - 2, sx + 2, sy + 2, isDay ? 0xFFF3C04E : 0xFFB9C6E0);
        }
        // bot uptime
        int by = gy + 18;
        long s = runMs / 1000;
        String up = String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        boolean on = BuilderConfig.getInstance().enabled;
        g.fill(x + 9, by + 2, x + 13, by + 6, on ? (((now / 400) % 2 == 0) ? ORANGE : 0xFF8A3F12) : SLATE);
        g.drawString(font, "BOT", x + 20, by, DIM, false);
        g.drawString(font, up, x + 20 + font.width("BOT "), by, on ? CREAM : DIM, false);
    }

    // ================================================================================================
    // Jukebox
    // ================================================================================================

    static final class Jukebox {
        private record Track(String name, String artist, Holder<SoundEvent> ev, int seconds) {}

        private static final Track[] TRACKS = {
                new Track("cat", "C418", SoundEvents.MUSIC_DISC_CAT, 185),
                new Track("blocks", "C418", SoundEvents.MUSIC_DISC_BLOCKS, 345),
                new Track("chirp", "C418", SoundEvents.MUSIC_DISC_CHIRP, 185),
                new Track("far", "C418", SoundEvents.MUSIC_DISC_FAR, 174),
                new Track("mall", "C418", SoundEvents.MUSIC_DISC_MALL, 197),
                new Track("mellohi", "C418", SoundEvents.MUSIC_DISC_MELLOHI, 96),
                new Track("stal", "C418", SoundEvents.MUSIC_DISC_STAL, 150),
                new Track("strad", "C418", SoundEvents.MUSIC_DISC_STRAD, 188),
                new Track("ward", "C418", SoundEvents.MUSIC_DISC_WARD, 251),
                new Track("wait", "C418", SoundEvents.MUSIC_DISC_WAIT, 238),
                new Track("13", "C418", SoundEvents.MUSIC_DISC_13, 178),
                new Track("11", "C418", SoundEvents.MUSIC_DISC_11, 71),
                new Track("pigstep", "Lena Raine", SoundEvents.MUSIC_DISC_PIGSTEP, 149),
                new Track("otherside", "Lena Raine", SoundEvents.MUSIC_DISC_OTHERSIDE, 195),
                new Track("5", "Samuel Åberg", SoundEvents.MUSIC_DISC_5, 178),
                new Track("relic", "Aaron Cherof", SoundEvents.MUSIC_DISC_RELIC, 218),
                new Track("precipice", "Aaron Cherof", SoundEvents.MUSIC_DISC_PRECIPICE, 299),
                new Track("creator", "Lena Raine", SoundEvents.MUSIC_DISC_CREATOR, 176),
                new Track("creator (music box)", "Lena Raine", SoundEvents.MUSIC_DISC_CREATOR_MUSIC_BOX, 73),
        };

        private static int index = 0;
        private static SimpleSoundInstance playing;
        private static long startedAt;
        private static boolean shuffle;
        private static final float[] bars = new float[14];

        static void tick(Minecraft mc) {
            ExternalMusic.tick(mc);
            if (playing == null) return;
            mc.getMusicManager().stopPlaying(); // no background music on top of ours
            long el = System.currentTimeMillis() - startedAt;
            if (el > 1500 && !mc.getSoundManager().isActive(playing)) next(mc, true);
        }

        private static void play(Minecraft mc) {
            stop(mc);
            Track t = TRACKS[index];
            playing = SimpleSoundInstance.forMusic(t.ev().value());
            mc.getMusicManager().stopPlaying();
            mc.getSoundManager().play(playing);
            startedAt = System.currentTimeMillis();
        }

        private static void stop(Minecraft mc) {
            if (playing != null) mc.getSoundManager().stop(playing);
            playing = null;
        }

        private static void next(Minecraft mc, boolean auto) {
            index = shuffle ? RND.nextInt(TRACKS.length) : (index + 1) % TRACKS.length;
            if (playing != null || auto) play(mc);
        }

        private static void prev(Minecraft mc) {
            index = (index - 1 + TRACKS.length) % TRACKS.length;
            if (playing != null) play(mc);
        }

        private static boolean custom() { return com.xlrw.config.BuilderConfig.getInstance().jukeboxCustom; }

        private static void setCustom(Minecraft mc, boolean v) {
            if (v == custom()) return;
            if (v) stop(mc); else ExternalMusic.stop();
            com.xlrw.config.BuilderConfig.getInstance().jukeboxCustom = v;
            com.xlrw.config.BuilderConfig.save();
        }

        static void draw(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my, long now, Hitter hit) {
            Minecraft mc = Minecraft.getInstance();
            card(g, font, x, y, w, h, "JUKEBOX");
            // mode switch: Minecraft discs / your own files (+ opens the folder)
            boolean cm = custom();
            int hx = x + w - 6;
            if (cm) {
                hx -= 14;
                button(g, font, hx, y + 2, 14, 12, "+", true, mx, my);
                hit.hit(hx, y + 2, 14, 12, ExternalMusic::openFolder);
                hx -= 3;
            }
            hx -= 20;
            button(g, font, hx, y + 2, 20, 12, "MY", cm, mx, my);
            hit.hit(hx, y + 2, 20, 12, () -> setCustom(mc, true));
            hx -= 22;
            button(g, font, hx, y + 2, 20, 12, "MC", !cm, mx, my);
            hit.hit(hx, y + 2, 20, 12, () -> setCustom(mc, false));
            if (cm) { drawCustom(g, font, x, y, w, h, mx, my, now, hit); return; }
            Track t = TRACKS[index];
            boolean on = playing != null;
            // spinning disc
            int dcx = x + 22, dcy = y + 36, r = 12;
            for (int yy = -r; yy <= r; yy++) {
                int half = (int) Math.sqrt(r * r - yy * yy);
                g.fill(dcx - half, dcy + yy, dcx + half, dcy + yy + 1, 0xFF1C1C20);
            }
            double ang = on ? (now / 240.0) : 0;
            for (int k = 0; k < 2; k++) { // shine marks
                double a = ang + k * Math.PI;
                int px = dcx + (int) (Math.cos(a) * 8), py = dcy + (int) (Math.sin(a) * 8);
                g.fill(px - 1, py - 1, px + 1, py + 1, 0xFF55555E);
            }
            g.fill(dcx - 3, dcy - 3, dcx + 3, dcy + 3, ORANGE);
            g.fill(dcx - 1, dcy - 1, dcx + 1, dcy + 1, INK);
            // title
            int tx = x + 40;
            String name = t.name().toUpperCase();
            int maxW = w - 46;
            if (font.width(name) > maxW) name = font.plainSubstrByWidth(name, maxW - 6) + "…";
            g.drawString(font, name, tx, y + 24, on ? ORANGE : CREAM, false);
            g.drawString(font, t.artist(), tx, y + 35, DIM, false);
            g.drawString(font, (index + 1) + "/" + TRACKS.length, tx, y + 45, 0xFF5E6678, false);
            // progress
            int py = y + 56;
            float el = on ? (System.currentTimeMillis() - startedAt) / 1000f : 0;
            float f = Math.min(1f, el / t.seconds());
            g.fill(x + 8, py, x + w - 6, py + 2, 0xFF2B2F3A);
            g.fill(x + 8, py, x + 8 + (int) ((w - 14) * f), py + 2, ORANGE);
            String time = String.format("%d:%02d / %d:%02d", (int) el / 60, (int) el % 60, t.seconds() / 60, t.seconds() % 60);
            g.drawString(font, time, x + 8, py + 5, DIM, false);
            // equalizer
            int eqx = x + w - 6 - bars.length * 4, eqy = py + 13;
            for (int i = 0; i < bars.length; i++) {
                float target = on ? (float) (0.25 + 0.75 * Math.abs(Math.sin(now / (130.0 + i * 23) + i * 1.7)) * (0.6 + 0.4 * RND.nextFloat())) : 0.05f;
                bars[i] += (target - bars[i]) * 0.25f;
                int bh = Math.max(1, (int) (bars[i] * 10));
                g.fill(eqx + i * 4, eqy - bh, eqx + i * 4 + 3, eqy, i % 3 == 0 ? ORANGE : SLATE);
            }
            // controls
            int by = y + h - 24, bw = 24;
            int bx = x + 8;
            button(g, font, bx, by, bw, 18, "⏮", false, mx, my);
            hit.hit(bx, by, bw, 18, () -> prev(mc));
            button(g, font, bx + bw + 4, by, bw + 8, 18, on ? "■" : "▶", true, mx, my);
            hit.hit(bx + bw + 4, by, bw + 8, 18, () -> { if (playing != null) stop(mc); else play(mc); });
            button(g, font, bx + bw * 2 + 16, by, bw, 18, "⏭", false, mx, my);
            hit.hit(bx + bw * 2 + 16, by, bw, 18, () -> next(mc, false));
            button(g, font, bx + bw * 3 + 24, by, bw, 18, "⤨", shuffle, mx, my);
            hit.hit(bx + bw * 3 + 24, by, bw, 18, () -> shuffle = !shuffle);
        }

        private static void disc(GuiGraphics g, int dcx, int dcy, boolean on, long now, int label) {
            int r = 12;
            for (int yy = -r; yy <= r; yy++) {
                int half = (int) Math.sqrt(r * r - yy * yy);
                g.fill(dcx - half, dcy + yy, dcx + half, dcy + yy + 1, 0xFF1C1C20);
            }
            double ang = on ? (now / 240.0) : 0;
            for (int k = 0; k < 2; k++) {
                double a = ang + k * Math.PI;
                int px = dcx + (int) (Math.cos(a) * 8), py = dcy + (int) (Math.sin(a) * 8);
                g.fill(px - 1, py - 1, px + 1, py + 1, 0xFF55555E);
            }
            g.fill(dcx - 3, dcy - 3, dcx + 3, dcy + 3, label);
            g.fill(dcx - 1, dcy - 1, dcx + 1, dcy + 1, INK);
        }

        /** Your own music files. */
        private static void drawCustom(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my, long now, Hitter hit) {
            List<java.io.File> fs = ExternalMusic.files();
            boolean on = ExternalMusic.playing();
            disc(g, x + 22, y + 36, on && !ExternalMusic.isPaused(), now, 0xFF6F95C9);
            int tx = x + 40, maxW = w - 46;
            if (fs.isEmpty()) {
                g.drawString(font, "Chưa có nhạc", tx, y + 24, CREAM, false);
                g.drawString(font, "bấm + để mở thư mục", tx, y + 35, DIM, false);
                g.drawString(font, "ogg mp3 wav aiff au", tx, y + 45, 0xFF5E6678, false);
            } else {
                java.io.File f = ExternalMusic.playingFile() != null ? ExternalMusic.playingFile() : fs.get(Math.min(ExternalMusic.index, fs.size() - 1));
                String nm = f.getName();
                int dot = nm.lastIndexOf('.');
                String ext = dot > 0 ? nm.substring(dot + 1).toUpperCase() : "";
                if (dot > 0) nm = nm.substring(0, dot);
                if (font.width(nm) > maxW) nm = font.plainSubstrByWidth(nm, maxW - 6) + "…";
                g.drawString(font, nm, tx, y + 24, on ? ORANGE : CREAM, false);
                String err = ExternalMusic.error();
                g.drawString(font, err != null ? font.plainSubstrByWidth("lỗi: " + err, maxW) : ext + " · local", tx, y + 35, err != null ? 0xFFE0403A : DIM, false);
                g.drawString(font, (Math.min(ExternalMusic.index, fs.size() - 1) + 1) + "/" + fs.size(), tx, y + 45, 0xFF5E6678, false);
            }
            int py = y + 56;
            float el = on ? ExternalMusic.elapsed() : 0, len = ExternalMusic.length();
            float f = len > 0 ? Math.min(1f, el / len) : 0;
            g.fill(x + 8, py, x + w - 6, py + 2, 0xFF2B2F3A);
            g.fill(x + 8, py, x + 8 + (int) ((w - 14) * f), py + 2, 0xFF6F95C9);
            String time = String.format("%d:%02d", (int) el / 60, (int) el % 60) + (len > 0 ? String.format(" / %d:%02d", (int) len / 60, (int) len % 60) : "");
            g.drawString(font, time, x + 8, py + 5, DIM, false);
            int eqx = x + w - 6 - bars.length * 4, eqy = py + 13;
            float lv = on && !ExternalMusic.isPaused() ? ExternalMusic.level[0] : 0;
            for (int i = 0; i < bars.length; i++) {
                float target = lv > 0 ? (float) (0.15 + Math.min(1, lv * 1.6) * (0.5 + 0.5 * Math.abs(Math.sin(now / (110.0 + i * 19) + i * 1.3)))) : 0.05f;
                bars[i] += (target - bars[i]) * 0.3f;
                int bh = Math.max(1, (int) (bars[i] * 10));
                g.fill(eqx + i * 4, eqy - bh, eqx + i * 4 + 3, eqy, i % 3 == 0 ? 0xFF6F95C9 : SLATE);
            }
            int by = y + h - 24, bw = 24, bx = x + 8;
            button(g, font, bx, by, bw, 18, "⏮", false, mx, my);
            hit.hit(bx, by, bw, 18, ExternalMusic::prev);
            String mid = !on ? "▶" : ExternalMusic.isPaused() ? "▶" : "Ⅱ";
            button(g, font, bx + bw + 4, by, bw + 8, 18, mid, true, mx, my);
            hit.hit(bx + bw + 4, by, bw + 8, 18, () -> { if (on) ExternalMusic.togglePause(); else ExternalMusic.play(ExternalMusic.index); });
            button(g, font, bx + bw * 2 + 16, by, bw, 18, "⏭", false, mx, my);
            hit.hit(bx + bw * 2 + 16, by, bw, 18, ExternalMusic::next);
            button(g, font, bx + bw * 3 + 24, by, bw, 18, on ? "■" : "⤨", on || ExternalMusic.shuffle, mx, my);
            hit.hit(bx + bw * 3 + 24, by, bw, 18, () -> { if (on) ExternalMusic.stop(); else ExternalMusic.shuffle = !ExternalMusic.shuffle; });
        }
    }

    static void button(GuiGraphics g, Font font, int x, int y, int w, int h, String label, boolean accent, int mx, int my) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        int lift = hov ? 1 : 0;
        if (hov) g.fill(x + 1, y + h - 1, x + w + 1, y + h + 1, 0x66000000);
        g.fill(x - lift, y - lift, x + w - lift, y + h - lift, accent ? (hov ? 0xFFFF8F45 : ORANGE) : (hov ? SLATE : 0xFF2B3240));
        g.drawCenteredString(font, label, x + w / 2 - lift, y + (h - 8) / 2 - lift, accent ? INK : CREAM);
    }

    // input routing for the arcade (panel-local coordinates)
    static boolean mouseDown(double lx, double ly, int button) { return Arcade.mouseDown(lx, ly, button); }

    static void mouseUp(double lx, double ly, int button) { Arcade.mouseUp(lx, ly, button); }

    static boolean keyPressed(int key) { return Arcade.keyPressed(key); }

    static boolean capturesKeys() { return Arcade.capturesKeys(); }
}
