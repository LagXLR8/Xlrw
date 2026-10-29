package com.xlrw.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Mini arcade for the HUB tab: a menu of small original games (art drawn here), each with pause / exit.
 * Field coordinates handed to the games are relative to the play field's top-left corner.
 */
final class Arcade {

    static final int INK = 0xFF141416, CREAM = 0xFFE8D8C9, ORANGE = 0xFFF3701E, SLATE = 0xFF4B607F, DIM = 0xFF8D97AB,
            WELL = 0xFF121215, GREEN = 0xFF7FD18B, PINK = 0xFFE07AA0, GOLD = 0xFFF3C04E, SKY = 0xFF7FB2E0;
    static final Random RND = new Random();

    enum State { READY, PLAY, OVER }

    // ------------------------------------------------------------------------------------------------
    // Shell
    // ------------------------------------------------------------------------------------------------

    private static final Game[] GAMES = {new EggRaid(), new BlockBlast(), new GoldMiner(), new Bubbles(), new Snake(), new Ducks(),
            new ArcadeMore.Maze(), new ArcadeMore.Flap(), new ArcadeMore.Dash(), new ArcadeMore.Mine2D()};
    private static Game current;
    private static boolean paused;
    private static long lastDrawAt, lastNanos;
    private static int fx, fy, fw, fh;      // field rect (panel-local) of the last frame
    private static float mxF = -1, myF = -1;

    static boolean capturesKeys() {
        return current != null && !paused && current.state == State.PLAY && current.wantsKeys()
                && System.currentTimeMillis() - lastDrawAt < 300;
    }

    static boolean active() {
        return current != null && System.currentTimeMillis() - lastDrawAt < 300;
    }

    private static void enter(Game g) {
        current = g;
        paused = false;
        g.state = State.READY;
        g.onEnter();
        XlrwSounds.play("ui.edit", 1.3f, 0.4f);
    }

    private static void exit() {
        if (current != null) current.onExit();
        current = null;
        paused = false;
    }

    static void draw(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my, long now, HubWidgets.Hitter hit) {
        long n = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.05f, (n - lastNanos) / 1e9f);
        lastNanos = n;
        if (current != null && now - lastDrawAt > 300 && current.state == State.PLAY) paused = true; // tab was hidden
        lastDrawAt = now;

        HubWidgets.card(g, font, x, y, w, h, current == null ? "ARCADE" : current.name);
        fx = x + 6;
        fy = y + 17;
        fw = w - 12;
        fh = h - 23;
        g.fill(fx, fy, fx + fw, fy + fh, WELL);

        if (current == null) {
            drawMenu(g, font, mx, my, now, hit);
            return;
        }
        // header buttons: pause / exit
        int bx = x + w - 6 - 16;
        HubWidgets.button(g, font, bx, y + 2, 16, 12, "✕", false, mx, my);
        hit.hit(bx, y + 2, 16, 12, Arcade::exit);
        if (current.state == State.PLAY) {
            HubWidgets.button(g, font, bx - 19, y + 2, 16, 12, paused ? "▶" : "Ⅱ", paused, mx, my);
            hit.hit(bx - 19, y + 2, 16, 12, () -> paused = !paused);
        }
        String sc = String.valueOf(current.score);
        g.drawString(font, sc, bx - 24 - font.width(sc), y + 5, ORANGE, false);

        boolean in = mx >= fx && mx < fx + fw && my >= fy && my < fy + fh;
        mxF = in ? mx - fx : -1;
        myF = in ? my - fy : -1;
        if (current.state == State.PLAY && !paused) current.update(dt, fw, fh, mxF, myF, now);
        else current.idle(dt, fw, fh, now);

        g.enableScissor(fx, fy, fx + fw, fy + fh);
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, fx, fy, 0);
        current.render(g, font, fw, fh, mxF, myF, now);
        com.xlrw.compat.GuiCompat.pop(g);

        if (current.state != State.PLAY || paused) {
            g.fill(fx, fy + fh / 2 - 20, fx + fw, fy + fh / 2 + 20, 0xD0141416);
            String t1 = paused ? "PAUSED" : current.state == State.READY ? current.name : "GAME OVER";
            g.drawCenteredString(font, t1, fx + fw / 2, fy + fh / 2 - 15, ORANGE);
            String t2 = paused ? "click / P" : current.state == State.READY ? current.hint() : "score " + current.score + "  ·  best " + current.best;
            g.drawCenteredString(font, t2, fx + fw / 2, fy + fh / 2 - 3, CREAM);
            if ((now / 500) % 2 == 0 && !paused) g.drawCenteredString(font, "click ▶", fx + fw / 2, fy + fh / 2 + 9, DIM);
        }
        g.disableScissor();
    }

    private static void drawMenu(GuiGraphics g, Font font, int mx, int my, long now, HubWidgets.Hitter hit) {
        int cols = 2, rows = (GAMES.length + 1) / 2, gap = 3;
        int tw = (fw - gap * (cols + 1)) / cols, th = (fh - gap * (rows + 1)) / rows;
        for (int i = 0; i < GAMES.length; i++) {
            Game gm = GAMES[i];
            int tx = fx + gap + (i % cols) * (tw + gap), ty = fy + gap + (i / cols) * (th + gap);
            boolean hov = mx >= tx && mx < tx + tw && my >= ty && my < ty + th;
            int lift = hov ? 1 : 0;
            if (hov) g.fill(tx + 1, ty + th - 1, tx + tw + 1, ty + th + 1, 0x77000000);
            g.fill(tx - lift, ty - lift, tx + tw - lift, ty + th - lift, hov ? 0xFF2B3240 : 0xFF1E2027);
            g.fill(tx - lift, ty - lift, tx + 2 - lift, ty + th - lift, hov ? ORANGE : SLATE);
            com.xlrw.compat.GuiCompat.push(g);
            com.xlrw.compat.GuiCompat.translate(g, tx - lift + 6, ty - lift + (th - 10) / 2f, 0);
            gm.icon(g, now);
            com.xlrw.compat.GuiCompat.pop(g);
            com.xlrw.compat.GuiCompat.push(g);
            com.xlrw.compat.GuiCompat.translate(g, tx - lift + 22, ty - lift + th / 2f - (gm.best > 0 ? 6 : 3), 0);
            com.xlrw.compat.GuiCompat.scale(g, 0.75f, 0.75f, 1f);
            g.drawString(font, gm.name, 0, 0, hov ? ORANGE : CREAM, false);
            if (gm.best > 0) g.drawString(font, "★ " + gm.best, 0, 10, DIM, false);
            com.xlrw.compat.GuiCompat.pop(g);
            hit.hit(tx, ty, tw, th, () -> enter(gm));
        }
    }

    /** Raw mouse press inside the HUB (panel-local). @return true if consumed */
    static boolean mouseDown(double lx, double ly, int button) {
        if (current == null) return false;
        if (lx < fx || lx >= fx + fw || ly < fy || ly >= fy + fh) return false;
        if (paused) { paused = false; return true; }
        if (current.state != State.PLAY) {
            if (button != 0) return true;
            current.score = 0;
            current.reset(fw, fh);
            current.state = State.PLAY;
            XlrwSounds.play("game.wave", 1.2f, 0.45f);
            return true;
        }
        return current.mouseDownB((float) (lx - fx), (float) (ly - fy), fw, fh, button);
    }

    static void mouseUp(double lx, double ly, int button) {
        if (current == null || paused || current.state != State.PLAY) return;
        current.mouseUpB((float) (lx - fx), (float) (ly - fy), fw, fh, button);
    }

    static boolean keyPressed(int key) {
        if (current == null || !active()) return false;
        if (key == InputConstants.KEY_P && current.state == State.PLAY) { paused = !paused; return true; }
        if (paused || current.state != State.PLAY) return false;
        return current.key(key);
    }

    // ------------------------------------------------------------------------------------------------
    // Shared drawing helpers
    // ------------------------------------------------------------------------------------------------

    static void sprite(GuiGraphics g, String[] rows, int x, int y, boolean mirror, java.util.function.IntUnaryOperator pal) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int len = row.length();
            for (int c = 0; c < len; c++) {
                int col = pal.applyAsInt(row.charAt(c));
                if (col == 0) continue;
                int cx = mirror ? len - 1 - c : c;
                g.fill(x + cx, y + r, x + cx + 1, y + r + 1, col);
            }
        }
    }

    static void circle(GuiGraphics g, float cx, float cy, float r, int color) {
        int ri = (int) Math.ceil(r);
        for (int yy = -ri; yy <= ri; yy++) {
            float hw = (float) Math.sqrt(Math.max(0, r * r - yy * yy));
            if (hw <= 0) continue;
            g.fill(Math.round(cx - hw), Math.round(cy + yy), Math.round(cx + hw), Math.round(cy + yy) + 1, color);
        }
    }

    static void line(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        float d = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        int n = Math.max(1, (int) d);
        for (int i = 0; i <= n; i++) {
            int x = Math.round(x0 + (x1 - x0) * i / n), y = Math.round(y0 + (y1 - y0) * i / n);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** Particle list: x, y, vx, vy, life, color. */
    static void burst(List<float[]> fx, float x, float y, int n, int color, float speed) {
        for (int i = 0; i < n; i++) {
            fx.add(new float[]{x, y, (RND.nextFloat() - 0.5f) * speed, (RND.nextFloat() - 0.8f) * speed, 0.4f + RND.nextFloat() * 0.5f, color});
        }
    }

    static void tickParticles(List<float[]> fx, float dt, float gravity) {
        for (int i = fx.size() - 1; i >= 0; i--) {
            float[] p = fx.get(i);
            p[3] += gravity * dt;
            p[0] += p[2] * dt;
            p[1] += p[3] * dt;
            p[4] -= dt;
            if (p[4] <= 0) fx.remove(i);
        }
    }

    static void drawParticles(GuiGraphics g, List<float[]> fx) {
        for (float[] p : fx) {
            int a = (int) (Math.min(1f, p[4] * 2) * 255);
            g.fill((int) p[0], (int) p[1], (int) p[0] + 1, (int) p[1] + 1, (a << 24) | ((int) p[5] & 0xFFFFFF));
        }
    }

    /** Small version of Bit (1 px per pixel). */
    static void tinyBit(GuiGraphics g, int x, int y, long now, boolean arm) {
        tinyBit(g, x, y, now, arm, false);
    }

    /**
     * Facing left is drawn by mirroring the pixel columns, never with a negative pose scale: a flipped pose reverses
     * the quad winding and the GUI culls it (Bit simply vanished).
     */
    static void tinyBit(GuiGraphics g, int x, int y, long now, boolean arm, boolean mirror) {
        BitPx p = (x0, y0, x1, y1, c) -> {
            if (mirror) { int a = 11 - x1, b = 11 - x0; x0 = a; x1 = b; }
            g.fill(x + x0, y + y0, x + x1, y + y1, c);
        };
        p.f(1, 2, 10, 9, INK);
        p.f(0, 3, 11, 8, INK);
        p.f(1, 3, 10, 8, CREAM);
        p.f(2, 4, 9, 7, SLATE);
        boolean blink = (now / 150) % 25 == 0;
        if (!blink) {
            p.f(5, 5, 6, 6, ORANGE);   // eyes sit towards the facing side
            p.f(8, 5, 9, 6, ORANGE);
        }
        p.f(5, 1, 6, 2, INK);
        p.f(5, 0, 6, 1, (now / 500) % 2 == 0 ? ORANGE : SLATE);
        p.f(2, 9, 4, 10, INK);
        p.f(7, 9, 9, 10, INK);
        if (arm) p.f(9, 6, 12, 7, INK);
    }

    private interface BitPx { void f(int x0, int y0, int x1, int y1, int c); }

    // ------------------------------------------------------------------------------------------------
    // Game base
    // ------------------------------------------------------------------------------------------------

    abstract static class Game {
        final String name;
        State state = State.READY;
        int score, best;

        Game(String name) { this.name = name; }

        abstract void reset(int fw, int fh);

        abstract void update(float dt, int fw, int fh, float mx, float my, long now);

        abstract void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now);

        abstract void icon(GuiGraphics g, long now);

        void idle(float dt, int fw, int fh, long now) {}

        boolean mouseDown(float mx, float my, int fw, int fh) { return false; }

        void mouseUp(float mx, float my, int fw, int fh) {}

        boolean mouseDownB(float mx, float my, int fw, int fh, int button) { return button == 0 && mouseDown(mx, my, fw, fh); }

        void mouseUpB(float mx, float my, int fw, int fh, int button) { if (button == 0) mouseUp(mx, my, fw, fh); }

        boolean key(int key) { return false; }

        boolean wantsKeys() { return false; }

        String hint() { return "click to start"; }

        void onEnter() {}

        void onExit() {}

        void gameOver() {
            state = State.OVER;
            best = Math.max(best, score);
            XlrwSounds.play("game.over", 1f, 0.5f);
        }
    }

    // ================================================================================================
    // 1. EGG RAID - space shooter vs chickens
    // ================================================================================================

    static final class EggRaid extends Game {
        private final List<float[]> shots = new ArrayList<>(), eggs = new ArrayList<>(), fxs = new ArrayList<>();
        private boolean[][] alive;
        private float formX, formDir = 1, formDrop, formSpeed, shipX = 0.5f;
        private int wave, lives;
        private long lastShot, hurtUntil, waveBanner;
        private final float[][] stars = new float[40][3];
        private static final int COLS = 6, ROWS = 3, CW = 10, CH = 10, GAPX = 8, GAPY = 6;
        static final String[] CHICK_A = {"....rr....", "...rWWr...", "..WWWWWW..", "w.WEWWEW.w", "wwWWyyWWww",
                "wwWWWyWWww", ".wWWWWWWw.", "..WWWWWW..", "...y..y...", "..y....y.."};
        static final String[] CHICK_B = {"....rr....", "w..rWWr..w", "ww.WWWW.ww", "wwWEWWEWww", ".wWWyyWWw.",
                "..WWWyWW..", "..WWWWWW..", "..WWWWWW..", "...y..y...", "...y..y..."};
        private static final String[] SHIP = {"....o....", "...cOc...", "..cCCCc..", ".sCCOCCs.", "sSCCCCCSs", "sS.c.c.Ss"};

        EggRaid() {
            super("EGG RAID");
            for (float[] s : stars) { s[0] = RND.nextFloat(); s[1] = RND.nextFloat(); s[2] = 0.2f + RND.nextFloat(); }
        }

        static int pal(int c) {
            return switch (c) {
                case 'W' -> 0xFFF4F1EA; case 'w' -> 0xFFCFC6B8; case 'r' -> 0xFFE0403A; case 'y' -> 0xFFF3A51E;
                case 'E' -> INK; case 'C' -> CREAM; case 'c' -> 0xFFB9A996; case 'O' -> ORANGE; case 'o' -> 0xFFFFC266;
                case 's' -> SLATE; case 'S' -> 0xFF6079A0; default -> 0;
            };
        }

        @Override String hint() { return "mouse = move, auto fire"; }

        @Override void icon(GuiGraphics g, long now) { sprite(g, (now / 260) % 2 == 0 ? CHICK_A : CHICK_B, 1, 0, false, EggRaid::pal); }

        private void newWave() {
            wave++;
            alive = new boolean[ROWS][COLS];
            for (boolean[] r : alive) Arrays.fill(r, true);
            formX = 0; formDrop = 0; formDir = 1;
            formSpeed = 14 + wave * 4;
            eggs.clear(); shots.clear();
            waveBanner = System.currentTimeMillis();
            if (wave > 1) XlrwSounds.play("game.wave", 1f, 0.5f);
        }

        @Override void reset(int fw, int fh) { wave = 0; lives = 3; newWave(); }

        private float[] chickPos(int r, int c, int fw) {
            float total = COLS * CW + (COLS - 1) * GAPX;
            float left = (fw - total) / 2f, sway = Math.max(0, (fw - total) / 2f - 4);
            return new float[]{left + formX * sway + c * (CW + GAPX), 14 + formDrop + r * (CH + GAPY)};
        }

        @Override void idle(float dt, int fw, int fh, long now) { stars(dt); }

        private void stars(float dt) {
            for (float[] s : stars) { s[1] += dt * 0.05f * s[2]; if (s[1] > 1) { s[1] = 0; s[0] = RND.nextFloat(); } }
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            stars(dt);
            if (alive == null) newWave();
            if (mx >= 0) {
                shipX += (Math.max(0, Math.min(1, mx / fw)) - shipX) * Math.min(1f, dt * 14f);
                if (now - lastShot > 230) {
                    lastShot = now;
                    shots.add(new float[]{shipX * (fw - 9) + 4, fh - 11});
                    XlrwSounds.playVar("game.shoot", 1f, 0.2f, 0.08f);
                }
            }
            float total = COLS * CW + (COLS - 1) * GAPX, sway = Math.max(1, (fw - total) / 2f - 4);
            formX += formDir * formSpeed * dt / sway;
            if (formX > 1) { formX = 1; formDir = -1; formDrop += 3; }
            if (formX < -1) { formX = -1; formDir = 1; formDrop += 3; }
            formDrop += dt * (0.6f + wave * 0.15f);
            int count = 0;
            for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
                if (!alive[r][c]) continue;
                count++;
                float[] p = chickPos(r, c, fw);
                if (p[1] + CH >= fh - 10) { gameOver(); return; }
                if (RND.nextFloat() < dt * (0.03f + wave * 0.012f)) eggs.add(new float[]{p[0] + 5, p[1] + CH, 40 + wave * 8});
            }
            if (count == 0) { newWave(); return; }
            for (int i = shots.size() - 1; i >= 0; i--) {
                float[] s = shots.get(i);
                s[1] -= 170 * dt;
                if (s[1] < -4) { shots.remove(i); continue; }
                boolean hit = false;
                for (int r = 0; r < ROWS && !hit; r++) for (int c = 0; c < COLS && !hit; c++) {
                    if (!alive[r][c]) continue;
                    float[] p = chickPos(r, c, fw);
                    if (s[0] >= p[0] && s[0] <= p[0] + CW && s[1] >= p[1] && s[1] <= p[1] + CH) {
                        alive[r][c] = false;
                        hit = true;
                        score += 10 * (ROWS - r) + wave * 2;
                        burst(fxs, p[0] + 5, p[1] + 5, 7, 0xFFF4F1EA, 70);
                        burst(fxs, p[0] + 5, p[1] + 5, 2, 0xFFE0403A, 70);
                        XlrwSounds.playVar("game.pop", 1f, 0.35f, 0.15f);
                    }
                }
                if (hit) shots.remove(i);
            }
            float sl = shipX * (fw - 9), st = fh - 9;
            for (int i = eggs.size() - 1; i >= 0; i--) {
                float[] e = eggs.get(i);
                e[1] += e[2] * dt;
                if (e[1] > fh) { burst(fxs, e[0], fh - 1, 3, GOLD, 50); eggs.remove(i); continue; }
                if (now > hurtUntil && e[0] >= sl && e[0] <= sl + 9 && e[1] + 4 >= st && e[1] <= st + 6) {
                    eggs.remove(i);
                    lives--;
                    hurtUntil = now + 1500;
                    burst(fxs, sl + 4, st + 3, 10, ORANGE, 70);
                    XlrwSounds.play("game.hurt", 1f, 0.5f);
                    if (lives <= 0) { gameOver(); return; }
                }
            }
            tickParticles(fxs, dt, 90);
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            for (float[] s : stars) {
                int a = (int) Math.min(255, 60 + 150 * s[2]);
                int sx = (int) (s[0] * fw), sy = (int) (s[1] * fh);
                g.fill(sx, sy, sx + 1, sy + 1, (a << 24) | 0xE8D8C9);
            }
            if (alive != null && state != State.READY) {
                String[] fr = (now / 260) % 2 == 0 ? CHICK_A : CHICK_B;
                for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
                    if (!alive[r][c]) continue;
                    float[] p = chickPos(r, c, fw);
                    sprite(g, fr, (int) p[0], (int) p[1], false, EggRaid::pal);
                }
            }
            for (float[] e : eggs) {
                int ex = (int) e[0], ey = (int) e[1];
                g.fill(ex - 1, ey, ex + 2, ey + 4, 0xFFF4F1EA);
                g.fill(ex, ey - 1, ex + 1, ey + 5, 0xFFF4F1EA);
            }
            for (float[] s : shots) g.fill((int) s[0], (int) s[1], (int) s[0] + 1, (int) s[1] + 4, ORANGE);
            drawParticles(g, fxs);
            boolean blink = now < hurtUntil && (now / 100) % 2 == 0;
            if (state != State.OVER && !blink) {
                int sx = (int) (shipX * (fw - 9)), sy = fh - 9;
                sprite(g, SHIP, sx, sy, false, EggRaid::pal);
                if (state == State.PLAY && (now / 80) % 2 == 0) {
                    g.fill(sx + 3, sy + 6, sx + 4, sy + 8, 0xFFFFC266);
                    g.fill(sx + 5, sy + 6, sx + 6, sy + 8, 0xFFFFC266);
                }
            }
            for (int i = 0; i < lives; i++) g.fill(fw - 6 - i * 6, 4, fw - 2 - i * 6, 8, ORANGE);
            if (state == State.PLAY && now - waveBanner < 1400) g.drawCenteredString(font, "WAVE " + wave, fw / 2, fh / 2 - 4, ORANGE);
        }
    }

    // ================================================================================================
    // 2. BLOCK BLAST - drag pieces on an 8x8 board, clear rows / columns
    // ================================================================================================

    static final class BlockBlast extends Game {
        private static final int N = 8;
        private static final int[][][] SHAPES = {
                {{1}}, {{1, 1}}, {{1}, {1}}, {{1, 1, 1}}, {{1}, {1}, {1}}, {{1, 1, 1, 1}}, {{1}, {1}, {1}, {1}},
                {{1, 1}, {1, 1}}, {{1, 1, 1}, {1, 1, 1}, {1, 1, 1}}, {{1, 0}, {1, 0}, {1, 1}}, {{1, 1, 1}, {1, 0, 0}},
                {{1, 1}, {0, 1}, {0, 1}}, {{0, 0, 1}, {1, 1, 1}}, {{1, 1, 1}, {0, 1, 0}}, {{0, 1}, {1, 1}, {0, 1}},
                {{1, 1, 0}, {0, 1, 1}}, {{1, 0}, {1, 1}, {0, 1}}, {{1, 1, 1, 1, 1}}, {{1, 1}, {1, 0}},
        };
        private static final int[] COLORS = {ORANGE, 0xFF6F95C9, GREEN, PINK, GOLD, 0xFFB48CE0, CREAM};
        private int[][] grid = new int[N][N];
        private final int[][][] tray = new int[3][][];
        private final int[] trayCol = new int[3];
        private int drag = -1;
        private final List<float[]> flash = new ArrayList<>(); // r, c, life, color
        private final List<float[]> fxs = new ArrayList<>();
        private int combo;

        BlockBlast() { super("BLOCK BLAST"); }

        @Override String hint() { return "drag pieces onto the board"; }

        @Override void icon(GuiGraphics g, long now) {
            int[] c = {ORANGE, 0xFF6F95C9, GREEN, PINK};
            for (int i = 0; i < 4; i++) g.fill(1 + (i % 2) * 5, (i / 2) * 5, 5 + (i % 2) * 5, 4 + (i / 2) * 5, c[(int) ((i + now / 600) % 4)]);
        }

        private void refill() {
            for (int i = 0; i < 3; i++) {
                tray[i] = SHAPES[RND.nextInt(SHAPES.length)];
                trayCol[i] = COLORS[RND.nextInt(COLORS.length)];
            }
        }

        @Override void reset(int fw, int fh) {
            grid = new int[N][N];
            refill();
            drag = -1;
            combo = 0;
            flash.clear();
        }

        private int cs(int fw, int fh) { return Math.max(4, Math.min((fw - 8) / N, (fh - 48) / N)); }

        private int gx(int fw, int fh) { return (fw - N * cs(fw, fh)) / 2; }

        private int trayY(int fw, int fh) { return 4 + N * cs(fw, fh) + 8; }

        private boolean fits(int[][] p, int r0, int c0) {
            for (int r = 0; r < p.length; r++) for (int c = 0; c < p[r].length; c++) {
                if (p[r][c] == 0) continue;
                int rr = r0 + r, cc = c0 + c;
                if (rr < 0 || cc < 0 || rr >= N || cc >= N || grid[rr][cc] != 0) return false;
            }
            return true;
        }

        private boolean anyFits() {
            for (int[][] p : tray) {
                if (p == null) continue;
                for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) if (fits(p, r, c)) return true;
            }
            return false;
        }

        private int[] dropCell(float mx, float my, int fw, int fh) {
            int cs = cs(fw, fh);
            int[][] p = tray[drag];
            float px = mx - p[0].length * cs / 2f, py = my - p.length * cs / 2f - cs; // piece floats above the cursor
            return new int[]{Math.round((py - 4) / cs), Math.round((px - gx(fw, fh)) / cs)};
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            for (int i = flash.size() - 1; i >= 0; i--) if ((flash.get(i)[2] -= dt * 2.5f) <= 0) flash.remove(i);
            tickParticles(fxs, dt, 120);
        }

        @Override boolean mouseDown(float mx, float my, int fw, int fh) {
            int ty = trayY(fw, fh), sw = fw / 3;
            if (my >= ty - 4) {
                int i = Math.max(0, Math.min(2, (int) (mx / sw)));
                if (tray[i] != null) { drag = i; XlrwSounds.play("bit.grab", 1.4f, 0.25f); }
                return true;
            }
            return false;
        }

        @Override void mouseUp(float mx, float my, int fw, int fh) {
            if (drag < 0) return;
            int[][] p = tray[drag];
            int[] rc = dropCell(mx, my, fw, fh);
            if (p != null && fits(p, rc[0], rc[1])) {
                int cells = 0;
                for (int r = 0; r < p.length; r++) for (int c = 0; c < p[r].length; c++) {
                    if (p[r][c] == 0) continue;
                    grid[rc[0] + r][rc[1] + c] = trayCol[drag];
                    cells++;
                }
                score += cells;
                tray[drag] = null;
                XlrwSounds.playVar("bit.place", 1.1f, 0.45f, 0.1f);
                clearLines(fw, fh);
                if (tray[0] == null && tray[1] == null && tray[2] == null) refill();
                if (!anyFits()) gameOver();
            }
            drag = -1;
        }

        private void clearLines(int fw, int fh) {
            boolean[] fr = new boolean[N], fc = new boolean[N];
            int lines = 0;
            for (int r = 0; r < N; r++) { fr[r] = true; for (int c = 0; c < N; c++) fr[r] &= grid[r][c] != 0; if (fr[r]) lines++; }
            for (int c = 0; c < N; c++) { fc[c] = true; for (int r = 0; r < N; r++) fc[c] &= grid[r][c] != 0; if (fc[c]) lines++; }
            if (lines == 0) { combo = 0; return; }
            combo++;
            int cs = cs(fw, fh), gx = gx(fw, fh);
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                if (!fr[r] && !fc[c]) continue;
                flash.add(new float[]{r, c, 1f, grid[r][c]});
                burst(fxs, gx + c * cs + cs / 2f, 4 + r * cs + cs / 2f, 2, grid[r][c], 60);
                grid[r][c] = 0;
            }
            score += 10 * lines * lines * combo;
            XlrwSounds.play("game.clear", 1f + Math.min(0.5f, combo * 0.1f), 0.5f);
        }

        private static void cell(GuiGraphics g, int x, int y, int s, int color) {
            g.fill(x, y, x + s, y + s, color);
            g.fill(x, y, x + s, y + 1, 0x55FFFFFF);
            g.fill(x, y + s - 1, x + s, y + s, 0x55000000);
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            int cs = cs(fw, fh), gx = gx(fw, fh), gy = 4;
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                int x = gx + c * cs, y = gy + r * cs;
                if (grid[r][c] != 0) cell(g, x, y, cs - 1, grid[r][c]);
                else g.fill(x, y, x + cs - 1, y + cs - 1, 0xFF1C1E25);
            }
            for (float[] f : flash) {
                int a = (int) (f[2] * 255);
                int x = gx + (int) f[1] * cs, y = gy + (int) f[0] * cs;
                g.fill(x, y, x + cs - 1, y + cs - 1, (a << 24) | 0xFFFFFF);
            }
            // ghost
            if (drag >= 0 && tray[drag] != null && mx >= 0) {
                int[] rc = dropCell(mx, my, fw, fh);
                boolean ok = fits(tray[drag], rc[0], rc[1]);
                if (ok) {
                    int[][] p = tray[drag];
                    for (int r = 0; r < p.length; r++) for (int c = 0; c < p[r].length; c++) {
                        if (p[r][c] == 0) continue;
                        int x = gx + (rc[1] + c) * cs, y = gy + (rc[0] + r) * cs;
                        g.fill(x, y, x + cs - 1, y + cs - 1, (0x66 << 24) | (trayCol[drag] & 0xFFFFFF));
                    }
                }
            }
            // tray
            int ty = trayY(fw, fh), sw = fw / 3, ts = Math.max(3, cs * 3 / 5);
            for (int i = 0; i < 3; i++) {
                int[][] p = tray[i];
                if (p == null || i == drag) continue;
                int pw = p[0].length * ts, ph = p.length * ts;
                int ox = i * sw + (sw - pw) / 2, oy = ty + Math.max(0, (fh - ty - ph) / 2);
                for (int r = 0; r < p.length; r++) for (int c = 0; c < p[r].length; c++)
                    if (p[r][c] != 0) cell(g, ox + c * ts, oy + r * ts, ts - 1, trayCol[i]);
            }
            if (drag >= 0 && tray[drag] != null && mx >= 0) {
                int[][] p = tray[drag];
                float px = mx - p[0].length * cs / 2f, py = my - p.length * cs / 2f - cs;
                for (int r = 0; r < p.length; r++) for (int c = 0; c < p[r].length; c++)
                    if (p[r][c] != 0) cell(g, (int) px + c * cs, (int) py + r * cs, cs - 1, trayCol[drag]);
            }
            drawParticles(g, fxs);
            if (combo > 1 && state == State.PLAY) g.drawString(font, "x" + combo, 3, fh - 10, ORANGE, false);
        }
    }

    // ================================================================================================
    // 3. GOLD MINER - Bit swings a claw from the top
    // ================================================================================================

    static final class GoldMiner extends Game {
        private final List<float[]> items = new ArrayList<>(); // x, y, r, type, value, weight
        private final List<float[]> fxs = new ArrayList<>();
        private float angle, angDir = 1, len = 8, time;
        private int hook, level, target;   // hook: 0 swing, 1 out, 2 back
        private float[] caught;
        private String popText;
        private long popUntil;
        private static final int GROUND = 26;

        GoldMiner() { super("GOLD MINER"); }

        @Override String hint() { return "click = drop the claw"; }

        @Override void onEnter() { Mascot.away = true; }

        @Override void onExit() { Mascot.away = false; }

        @Override void icon(GuiGraphics g, long now) {
            circle(g, 6, 5, 4.5f, GOLD);
            g.fill(4, 3, 6, 4, 0xFFFFF0B0);
        }

        @Override void reset(int fw, int fh) {
            level = 1;
            target = 400;
            startLevel(fw, fh);
        }

        private void startLevel(int fw, int fh) {
            time = 45;
            hook = 0; len = 8; caught = null;
            items.clear();
            int n = 8 + level * 2;
            int tries = 0;
            while (items.size() < n && tries++ < 500) {
                int t = RND.nextInt(100);
                float[] it;
                if (t < 22) it = new float[]{0, 0, 3, 0, 50, 1};          // small gold
                else if (t < 40) it = new float[]{0, 0, 5, 1, 120, 2.2f};  // gold
                else if (t < 48) it = new float[]{0, 0, 8, 2, 300, 4.5f};  // big gold
                else if (t < 70) it = new float[]{0, 0, 4, 3, 15, 3};      // rock
                else if (t < 82) it = new float[]{0, 0, 7, 4, 30, 6};      // boulder
                else if (t < 90) it = new float[]{0, 0, 2.5f, 5, 450, 0.8f}; // diamond
                else it = new float[]{0, 0, 4, 6, 0, 1.4f};                // mystery bag
                it[0] = 8 + RND.nextFloat() * (fw - 16);
                it[1] = GROUND + 18 + RND.nextFloat() * (fh - GROUND - 26);
                boolean ok = true;
                for (float[] o : items) if (Math.hypot(o[0] - it[0], o[1] - it[1]) < o[2] + it[2] + 3) { ok = false; break; }
                if (ok) items.add(it);
            }
        }

        @Override boolean mouseDown(float mx, float my, int fw, int fh) {
            if (hook == 0) { hook = 1; XlrwSounds.play("bit.grab", 0.8f, 0.35f); }
            return true;
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            float px = fw / 2f, py = GROUND - 2;
            if (hook == 0) {
                angle += angDir * dt * 1.5f;
                if (angle > 1.25f) { angle = 1.25f; angDir = -1; }
                if (angle < -1.25f) { angle = -1.25f; angDir = 1; }
            } else if (hook == 1) {
                len += 130 * dt;
                float tx = px + (float) Math.sin(angle) * len, ty = py + (float) Math.cos(angle) * len;
                if (tx < 0 || tx > fw || ty > fh) hook = 2;
                for (int i = 0; i < items.size(); i++) {
                    float[] it = items.get(i);
                    if (Math.hypot(it[0] - tx, it[1] - ty) < it[2] + 2) {
                        caught = items.remove(i);
                        hook = 2;
                        XlrwSounds.play("bit.place", 0.7f, 0.4f);
                        break;
                    }
                }
            } else {
                float sp = caught != null ? 140f / caught[5] : 170f;
                len -= sp * dt;
                if (caught != null) {
                    caught[0] = px + (float) Math.sin(angle) * (len + caught[2]);
                    caught[1] = py + (float) Math.cos(angle) * (len + caught[2]);
                }
                if (len <= 8) {
                    len = 8;
                    hook = 0;
                    if (caught != null) {
                        int v = (int) caught[4];
                        if (caught[3] == 6) v = RND.nextInt(10) == 0 ? 0 : 50 + RND.nextInt(10) * 50;
                        score += v;
                        popText = "+" + v;
                        popUntil = now + 900;
                        burst(fxs, px, py, 8, caught[3] == 5 ? 0xFF7FE3F0 : GOLD, 70);
                        XlrwSounds.play(v >= 100 ? "game.coin" : "bit.land", 1f, 0.5f);
                        caught = null;
                    }
                }
            }
            time -= dt;
            if (time <= 0) {
                if (score >= target) {
                    level++;
                    target += 450 + level * 200;
                    XlrwSounds.play("game.wave", 1f, 0.5f);
                    startLevel(fw, fh);
                } else gameOver();
            }
            tickParticles(fxs, dt, 100);
        }

        private int itemColor(int type) {
            return switch (type) {
                case 0, 1, 2 -> GOLD;
                case 3, 4 -> 0xFF7A7570;
                case 5 -> 0xFF7FE3F0;
                default -> 0xFFC9A46A;
            };
        }

        private void drawItem(GuiGraphics g, Font font, float[] it) {
            int col = itemColor((int) it[3]);
            circle(g, it[0], it[1], it[2], col);
            if (it[3] <= 2) g.fill((int) (it[0] - it[2] / 2), (int) (it[1] - it[2] / 2), (int) (it[0] - it[2] / 2) + 2, (int) (it[1] - it[2] / 2) + 1, 0xFFFFF0B0);
            if (it[3] == 5) g.fill((int) it[0], (int) it[1] - 1, (int) it[0] + 1, (int) it[1], 0xFFFFFFFF);
            if (it[3] == 6) g.fill((int) it[0] - 1, (int) (it[1] - it[2]) - 1, (int) it[0] + 1, (int) (it[1] - it[2]) + 1, INK);
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            g.fill(0, 0, fw, GROUND, 0xFF1E2A3A);
            g.fill(0, GROUND, fw, fh, 0xFF3A2C22);
            for (int i = 0; i < 40; i++) { // soil specks (stable pattern)
                int sx = (i * 37) % Math.max(1, fw), sy = GROUND + 4 + (i * 53) % Math.max(1, fh - GROUND - 4);
                g.fill(sx, sy, sx + 1, sy + 1, 0xFF4E3C2E);
            }
            g.fill(0, GROUND, fw, GROUND + 1, 0xFF6B513C);
            for (float[] it : items) drawItem(g, font, it);
            // Bit + winch
            float px = fw / 2f, py = GROUND - 2;
            g.fill((int) px - 9, GROUND - 3, (int) px + 9, GROUND, SLATE);
            tinyBit(g, (int) px - 5 - 9, GROUND - 14, now, hook != 0);
            float tx = px + (float) Math.sin(angle) * len, ty = py + (float) Math.cos(angle) * len;
            line(g, px, py, tx, ty, 0xFFB9C0CC);
            // claw
            float nx = (float) Math.cos(angle), ny = -(float) Math.sin(angle);
            line(g, tx - nx * 3, ty - ny * 3, tx + nx * 3, ty + ny * 3, CREAM);
            line(g, tx - nx * 3, ty - ny * 3, tx - nx * 3 + (float) Math.sin(angle) * 3, ty - ny * 3 + (float) Math.cos(angle) * 3, CREAM);
            line(g, tx + nx * 3, ty + ny * 3, tx + nx * 3 + (float) Math.sin(angle) * 3, ty + ny * 3 + (float) Math.cos(angle) * 3, CREAM);
            if (caught != null) drawItem(g, font, caught);
            drawParticles(g, fxs);
            // HUD
            g.drawString(font, "$" + score + "/" + target, 3, 3, GOLD, false);
            String t = String.valueOf((int) Math.ceil(Math.max(0, time)));
            g.drawString(font, t, fw - 3 - font.width(t), 3, time < 10 ? ORANGE : CREAM, false);
            g.drawString(font, "LV" + level, fw - 3 - font.width("LV" + level), 13, DIM, false);
            if (popText != null && now < popUntil) g.drawString(font, popText, (int) px + 12, GROUND - 20 - (int) ((popUntil - now) / 100 % 3), GOLD, true);
        }
    }

    // ================================================================================================
    // 4. BUBBLES - aim and pop 3+ of a colour
    // ================================================================================================

    static final class Bubbles extends Game {
        private static final int D = 10;
        private static final int[] COLS = {ORANGE, 0xFF6F95C9, GREEN, PINK, CREAM};
        private int cols, rowsMax, shift, shots, cur, next;
        private int[][] grid;
        private float bx, by, vx, vy;
        private boolean flying;
        private final List<float[]> fxs = new ArrayList<>();
        private int lastFw = -1, lastFh = -1;

        Bubbles() { super("BUBBLES"); }

        @Override String hint() { return "aim + click, match 3"; }

        @Override void icon(GuiGraphics g, long now) {
            circle(g, 3, 6, 2.6f, ORANGE);
            circle(g, 9, 6, 2.6f, 0xFF6F95C9);
            circle(g, 6, 2, 2.6f, GREEN);
        }

        private int off(int r) { return (r + shift) & 1; }

        private int colsIn(int r) { return off(r) == 1 ? cols - 1 : cols; }

        private float ox(int fw) { return (fw - cols * D) / 2f; }

        private float cx(int r, int c, int fw) { return ox(fw) + c * D + off(r) * D / 2f + D / 2f; }

        private float cy(int r) { return r * D * 0.87f + D / 2f + 1; }

        private int randomColor() {
            List<Integer> present = new ArrayList<>();
            for (int[] row : grid) for (int v : row) if (v != 0 && !present.contains(v)) present.add(v);
            return present.isEmpty() ? COLS[RND.nextInt(COLS.length)] : present.get(RND.nextInt(present.size()));
        }

        @Override void reset(int fw, int fh) {
            cols = Math.max(6, (fw - 2) / D);
            rowsMax = Math.max(8, (int) ((fh - 26) / (D * 0.87f)));
            lastFw = fw;
            lastFh = fh;
            grid = new int[rowsMax][cols];
            shift = 0;
            shots = 0;
            for (int r = 0; r < 5; r++) for (int c = 0; c < colsIn(r); c++) grid[r][c] = COLS[RND.nextInt(COLS.length)];
            cur = randomColor();
            next = randomColor();
            flying = false;
        }

        @Override boolean mouseDown(float mx, float my, int fw, int fh) {
            if (flying) return true;
            float sx = fw / 2f, sy = fh - 8;
            float dx = mx - sx, dy = Math.min(-6, my - sy);
            float l = (float) Math.hypot(dx, dy);
            vx = dx / l * 230;
            vy = dy / l * 230;
            bx = sx;
            by = sy;
            flying = true;
            XlrwSounds.playVar("game.shoot", 0.8f, 0.25f, 0.05f);
            return true;
        }

        private List<int[]> neighbors(int r, int c) {
            List<int[]> n = new ArrayList<>();
            int[][] cand = off(r) == 0 ? new int[][]{{r, c - 1}, {r, c + 1}, {r - 1, c - 1}, {r - 1, c}, {r + 1, c - 1}, {r + 1, c}}
                    : new int[][]{{r, c - 1}, {r, c + 1}, {r - 1, c}, {r - 1, c + 1}, {r + 1, c}, {r + 1, c + 1}};
            for (int[] p : cand) if (p[0] >= 0 && p[0] < rowsMax && p[1] >= 0 && p[1] < colsIn(p[0])) n.add(p);
            return n;
        }

        private void snap(int fw, int fh) {
            int best0 = -1, best1 = -1;
            float bd = Float.MAX_VALUE;
            int rEst = Math.round((by - D / 2f - 1) / (D * 0.87f));
            for (int r = Math.max(0, rEst - 1); r <= Math.min(rowsMax - 1, rEst + 1); r++) {
                for (int c = 0; c < colsIn(r); c++) {
                    if (grid[r][c] != 0) continue;
                    float d = (float) Math.hypot(cx(r, c, fw) - bx, cy(r) - by);
                    if (d < bd) { bd = d; best0 = r; best1 = c; }
                }
            }
            flying = false;
            if (best0 < 0) { gameOver(); return; }
            grid[best0][best1] = cur;
            // matches
            List<int[]> group = new ArrayList<>();
            ArrayDeque<int[]> q = new ArrayDeque<>();
            boolean[][] seen = new boolean[rowsMax][cols];
            q.add(new int[]{best0, best1});
            seen[best0][best1] = true;
            while (!q.isEmpty()) {
                int[] p = q.poll();
                group.add(p);
                for (int[] nb : neighbors(p[0], p[1])) {
                    if (!seen[nb[0]][nb[1]] && grid[nb[0]][nb[1]] == cur) { seen[nb[0]][nb[1]] = true; q.add(nb); }
                }
            }
            if (group.size() >= 3) {
                for (int[] p : group) {
                    burst(fxs, cx(p[0], p[1], fw), cy(p[0]), 4, grid[p[0]][p[1]], 60);
                    grid[p[0]][p[1]] = 0;
                }
                score += group.size() * 10;
                // floating bubbles
                boolean[][] conn = new boolean[rowsMax][cols];
                ArrayDeque<int[]> q2 = new ArrayDeque<>();
                for (int c = 0; c < colsIn(0); c++) if (grid[0][c] != 0) { conn[0][c] = true; q2.add(new int[]{0, c}); }
                while (!q2.isEmpty()) {
                    int[] p = q2.poll();
                    for (int[] nb : neighbors(p[0], p[1])) {
                        if (!conn[nb[0]][nb[1]] && grid[nb[0]][nb[1]] != 0) { conn[nb[0]][nb[1]] = true; q2.add(nb); }
                    }
                }
                int dropped = 0;
                for (int r = 0; r < rowsMax; r++) for (int c = 0; c < colsIn(r); c++) {
                    if (grid[r][c] != 0 && !conn[r][c]) {
                        burst(fxs, cx(r, c, fw), cy(r), 3, grid[r][c], 40);
                        grid[r][c] = 0;
                        dropped++;
                    }
                }
                score += dropped * 20;
                XlrwSounds.play("game.pop", 1f + Math.min(0.6f, group.size() * 0.05f), 0.45f);
            } else XlrwSounds.play("bit.place", 1.4f, 0.3f);
            shots++;
            if (shots % 7 == 0) addRow();
            boolean empty = true;
            for (int[] row : grid) for (int v : row) empty &= v == 0;
            if (empty) { score += 500; XlrwSounds.play("game.wave", 1f, 0.5f); for (int r = 0; r < 4; r++) addRow(); }
            for (int c = 0; c < cols; c++) if (grid[rowsMax - 1][c] != 0) { gameOver(); return; }
            cur = next;
            next = randomColor();
        }

        private void addRow() {
            if (grid[rowsMax - 1] != null) for (int v : grid[rowsMax - 1]) if (v != 0) { gameOver(); return; }
            for (int r = rowsMax - 1; r > 0; r--) grid[r] = grid[r - 1];
            grid[0] = new int[cols];
            shift ^= 1;
            for (int c = 0; c < colsIn(0); c++) grid[0][c] = COLS[RND.nextInt(COLS.length)];
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            if (fw != lastFw || fh != lastFh) { reset(fw, fh); return; }
            if (flying) {
                for (int step = 0; step < 4 && flying; step++) {
                    bx += vx * dt / 4;
                    by += vy * dt / 4;
                    if (bx < ox(fw) + D / 2f) { bx = ox(fw) + D / 2f; vx = Math.abs(vx); }
                    if (bx > ox(fw) + cols * D - D / 2f) { bx = ox(fw) + cols * D - D / 2f; vx = -Math.abs(vx); }
                    if (by <= D / 2f + 1) { snap(fw, fh); break; }
                    outer:
                    for (int r = 0; r < rowsMax; r++) for (int c = 0; c < colsIn(r); c++) {
                        if (grid[r][c] != 0 && Math.hypot(cx(r, c, fw) - bx, cy(r) - by) < D * 0.85f) { snap(fw, fh); break outer; }
                    }
                }
            }
            tickParticles(fxs, dt, 160);
        }

        private void ball(GuiGraphics g, float x, float y, int color) {
            circle(g, x, y, D / 2f - 0.6f, color);
            g.fill((int) x - 2, (int) y - 2, (int) x, (int) y - 1, 0x88FFFFFF);
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            if (grid == null) reset(fw, fh);
            float lineY = cy(rowsMax - 1) + D / 2f;
            g.fill(0, (int) lineY, fw, (int) lineY + 1, 0x55E07AA0);
            for (int r = 0; r < rowsMax; r++) for (int c = 0; c < colsIn(r); c++) if (grid[r][c] != 0) ball(g, cx(r, c, fw), cy(r), grid[r][c]);
            float sx = fw / 2f, sy = fh - 8;
            if (state == State.PLAY && !flying && mx >= 0) { // dotted aim
                float dx = mx - sx, dy = Math.min(-6, my - sy), l = (float) Math.hypot(dx, dy);
                float ax = sx, ay = sy, avx = dx / l, avy = dy / l;
                for (int i = 0; i < 40; i++) {
                    ax += avx * 4;
                    ay += avy * 4;
                    if (ax < ox(fw) || ax > ox(fw) + cols * D) avx = -avx;
                    if (ay < 2) break;
                    if (i % 2 == 0) g.fill((int) ax, (int) ay, (int) ax + 1, (int) ay + 1, 0x99E8D8C9);
                }
            }
            g.fill((int) sx - 8, fh - 3, (int) sx + 8, fh, SLATE);
            if (flying) ball(g, bx, by, cur);
            else ball(g, sx, sy, cur);
            ball(g, sx + 16, fh - 6, next);
            drawParticles(g, fxs);
        }
    }

    // ================================================================================================
    // 5. SNAKE - arrows / WASD
    // ================================================================================================

    static final class Snake extends Game {
        private static final int GS = 7;
        private final ArrayDeque<int[]> body = new ArrayDeque<>();
        private int dx = 1, dy = 0, ndx = 1, ndy = 0, cols, rows;
        private int[] food;
        private float acc, stepT;
        private final List<float[]> fxs = new ArrayList<>();

        Snake() { super("SNAKE"); }

        @Override String hint() { return "arrows / WASD"; }

        @Override boolean wantsKeys() { return true; }

        @Override void icon(GuiGraphics g, long now) {
            for (int i = 0; i < 4; i++) g.fill(i * 3, 6, i * 3 + 3, 9, i == 3 ? ORANGE : CREAM);
            g.fill(11, 2, 13, 4, PINK);
        }

        @Override void reset(int fw, int fh) {
            cols = Math.max(8, fw / GS);
            rows = Math.max(8, fh / GS);
            body.clear();
            for (int i = 0; i < 4; i++) body.addFirst(new int[]{cols / 2 - 3 + i, rows / 2});
            dx = 1; dy = 0; ndx = 1; ndy = 0;
            stepT = 0.13f;
            acc = 0;
            placeFood();
        }

        private void placeFood() {
            for (int t = 0; t < 200; t++) {
                int[] f = {RND.nextInt(cols), RND.nextInt(rows)};
                boolean onBody = false;
                for (int[] b : body) if (b[0] == f[0] && b[1] == f[1]) { onBody = true; break; }
                if (!onBody) { food = f; return; }
            }
        }

        @Override boolean key(int key) {
            int kx = 0, ky = 0;
            if (key == InputConstants.KEY_UP || key == InputConstants.KEY_W) ky = -1;
            else if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) ky = 1;
            else if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_A) kx = -1;
            else if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_D) kx = 1;
            else return false;
            if (kx == -dx && ky == -dy) return true; // no reversing into yourself
            ndx = kx;
            ndy = ky;
            return true;
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            acc += dt;
            while (acc >= stepT && state == State.PLAY) {
                acc -= stepT;
                dx = ndx;
                dy = ndy;
                int[] h = body.peekFirst();
                int[] n = {h[0] + dx, h[1] + dy};
                if (n[0] < 0 || n[1] < 0 || n[0] >= cols || n[1] >= rows) { gameOver(); return; }
                for (int[] b : body) if (b[0] == n[0] && b[1] == n[1]) { gameOver(); return; }
                body.addFirst(n);
                if (food != null && n[0] == food[0] && n[1] == food[1]) {
                    score += 10;
                    burst(fxs, ox(fw) + n[0] * GS + GS / 2f, oy(fh) + n[1] * GS + GS / 2f, 6, PINK, 60);
                    XlrwSounds.playVar("game.eat", 1f, 0.45f, 0.1f);
                    stepT = Math.max(0.055f, stepT * 0.97f);
                    placeFood();
                } else body.removeLast();
            }
            tickParticles(fxs, dt, 60);
        }

        private int ox(int fw) { return (fw - cols * GS) / 2; }

        private int oy(int fh) { return (fh - rows * GS) / 2; }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            if (cols == 0) reset(fw, fh);
            int ox = ox(fw), oy = oy(fh);
            g.fill(ox - 1, oy - 1, ox + cols * GS + 1, oy + rows * GS + 1, 0xFF2B3240);
            g.fill(ox, oy, ox + cols * GS, oy + rows * GS, 0xFF16171C);
            for (int x = 0; x < cols; x++) for (int y = 0; y < rows; y++)
                if ((x + y) % 2 == 0) g.fill(ox + x * GS + 3, oy + y * GS + 3, ox + x * GS + 4, oy + y * GS + 4, 0xFF22242C);
            if (food != null) {
                int fx = ox + food[0] * GS, fy = oy + food[1] * GS;
                g.fill(fx + 1, fy + 2, fx + GS - 1, fy + GS - 1, PINK);
                g.fill(fx + 3, fy, fx + 4, fy + 2, GREEN);
            }
            int i = 0;
            for (int[] b : body) {
                int x = ox + b[0] * GS, y = oy + b[1] * GS;
                g.fill(x, y, x + GS - 1, y + GS - 1, i == 0 ? ORANGE : (i % 2 == 0 ? CREAM : 0xFFD6C4B3));
                if (i == 0) {
                    g.fill(x + 1 + Math.max(0, dx) * 3, y + 1 + Math.max(0, dy) * 3, x + 2 + Math.max(0, dx) * 3, y + 2 + Math.max(0, dy) * 3, INK);
                }
                i++;
            }
            drawParticles(g, fxs);
        }
    }

    // ================================================================================================
    // 6. DUCKS - click to shoot, 3 shots per duck
    // ================================================================================================

    static final class Ducks extends Game {
        private final List<float[]> ducks = new ArrayList<>(); // x, y, vx, vy, state(0 fly,1 hit,2 fall,3 away), timer
        private final List<float[]> fxs = new ArrayList<>();
        private int round, ammo, hits, spawned;
        private final List<Boolean> marks = new ArrayList<>();
        private float spawnT;
        private long flashUntil;
        private float flashX, flashY;
        private static final int PER_ROUND = 10, NEED = 6;
        private static final String[] DUCK_A = {".....GG.....", "....GEGy....", "..b.GGGyy...", ".bbbwB......",
                "bbBBBBBB....", ".BBBBBBBB...", "..BBBBBB....", "...y..y....."};
        private static final String[] DUCK_B = {".....GG.....", "....GEGy....", "....GGGyy...", "....wB......",
                "..BBBBBB....", ".BBbbbBBB...", "..BbbbBB....", "...b.y......"};
        private static final String[] DUCK_HIT = {"....GG......", "...GxGy.....", "...GGGyy....", "..bwBb......",
                ".bBBBBBb....", "..BBBBBB....", "..BBBBB.....", "...y.y......"};

        Ducks() { super("DUCKS"); }

        @Override String hint() { return "click the ducks"; }

        static int pal(int c) {
            return switch (c) {
                case 'G' -> 0xFF3E8E5A; case 'B' -> 0xFF8A6A4A; case 'b' -> 0xFFC9B6A5; case 'y' -> 0xFFF3A51E;
                case 'E' -> INK; case 'x' -> 0xFFE0403A; case 'w' -> 0xFFF4F1EA; default -> 0;
            };
        }

        @Override void icon(GuiGraphics g, long now) { sprite(g, (now / 200) % 2 == 0 ? DUCK_A : DUCK_B, 0, 1, false, Ducks::pal); }

        @Override void reset(int fw, int fh) {
            round = 1;
            startRound();
        }

        private void startRound() {
            ducks.clear();
            marks.clear();
            hits = 0;
            spawned = 0;
            spawnT = 0.8f;
        }

        private int grass(int fh) { return fh - 18; }

        @Override boolean mouseDown(float mx, float my, int fw, int fh) {
            boolean anyFlying = false;
            for (float[] d : ducks) anyFlying |= d[4] == 0;
            if (!anyFlying || ammo <= 0) return true;
            ammo--;
            flashUntil = System.currentTimeMillis() + 80;
            flashX = mx;
            flashY = my;
            XlrwSounds.playVar("game.shoot", 0.6f, 0.4f, 0.05f);
            for (float[] d : ducks) {
                if (d[4] != 0) continue;
                if (Math.abs(mx - (d[0] + 6)) < 8 && Math.abs(my - (d[1] + 4)) < 7) {
                    d[4] = 1;
                    d[5] = 0.4f;
                    hits++;
                    marks.add(true);
                    score += 100 * round;
                    burst(fxs, d[0] + 6, d[1] + 4, 8, 0xFFC9B6A5, 60);
                    XlrwSounds.play("game.quack", 1f, 0.5f);
                    break;
                }
            }
            return true;
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            int gy = grass(fh);
            boolean active = false;
            for (float[] d : ducks) active |= d[4] == 0 || d[4] == 1 || d[4] == 2 || d[4] == 3;
            if (!active) {
                if (spawned < PER_ROUND) {
                    spawnT -= dt;
                    if (spawnT <= 0) {
                        float sp = 34 + round * 7;
                        ducks.add(new float[]{10 + RND.nextFloat() * (fw - 30), gy - 6, (RND.nextBoolean() ? 1 : -1) * sp, -sp * 0.8f, 0, 6f});
                        spawned++;
                        ammo = 3;
                        spawnT = 0.7f;
                    }
                } else {
                    if (hits >= NEED) { round++; XlrwSounds.play("game.wave", 1f, 0.5f); startRound(); }
                    else gameOver();
                    return;
                }
            }
            for (int i = ducks.size() - 1; i >= 0; i--) {
                float[] d = ducks.get(i);
                switch ((int) d[4]) {
                    case 0 -> {
                        d[0] += d[2] * dt;
                        d[1] += d[3] * dt;
                        if (d[0] < 0) { d[0] = 0; d[2] = Math.abs(d[2]); }
                        if (d[0] > fw - 12) { d[0] = fw - 12; d[2] = -Math.abs(d[2]); }
                        if (d[1] < 2) { d[1] = 2; d[3] = Math.abs(d[3]); }
                        if (d[1] > gy - 10) { d[1] = gy - 10; d[3] = -Math.abs(d[3]); }
                        if (RND.nextFloat() < dt * 0.6f) d[3] = -d[3];
                        d[5] -= dt;
                        if (d[5] <= 0 || ammo <= 0) { d[4] = 3; d[2] = 0; d[3] = -90; marks.add(false); XlrwSounds.play("bit.drop", 1.2f, 0.3f); }
                    }
                    case 1 -> { d[5] -= dt; if (d[5] <= 0) { d[4] = 2; d[3] = 20; } }
                    case 2 -> {
                        d[3] += 260 * dt;
                        d[1] += d[3] * dt;
                        if (d[1] > gy) { ducks.remove(i); XlrwSounds.play("bit.land", 0.8f, 0.3f); }
                    }
                    default -> { d[1] += d[3] * dt; if (d[1] < -12) ducks.remove(i); }
                }
            }
            tickParticles(fxs, dt, 80);
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            int gy = grass(fh);
            g.fill(0, 0, fw, gy, 0xFF2C4A6E);
            g.fill(0, gy / 2, fw, gy, 0xFF35587F);
            for (int i = 0; i < 3; i++) { // clouds
                int cx = (int) ((now / (60 + i * 25) + i * 70) % (fw + 40)) - 20, cy = 10 + i * 18;
                g.fill(cx, cy, cx + 18, cy + 4, 0x55E8D8C9);
                g.fill(cx + 4, cy - 3, cx + 12, cy, 0x55E8D8C9);
            }
            // tree
            g.fill(fw - 24, gy - 30, fw - 20, gy, 0xFF5A4030);
            circle(g, fw - 22, gy - 34, 9, 0xFF3E7A4A);
            for (float[] d : ducks) {
                String[] spr = d[4] >= 1 && d[4] <= 2 ? DUCK_HIT : ((now / 120) % 2 == 0 ? DUCK_A : DUCK_B);
                sprite(g, spr, (int) d[0], (int) d[1], d[2] < 0, Ducks::pal);
            }
            g.fill(0, gy, fw, fh, 0xFF5E8C3A);
            for (int x = 0; x < fw; x += 4) g.fill(x, gy - 2 + (x / 4) % 2, x + 1, gy, 0xFF5E8C3A);
            g.fill(0, gy + 6, fw, fh, 0xFF4A7030);
            drawParticles(g, fxs);
            // HUD on the grass
            for (int i = 0; i < ammo; i++) g.fill(4 + i * 4, fh - 9, 6 + i * 4, fh - 3, GOLD);
            for (int i = 0; i < PER_ROUND; i++) {
                int col = i < marks.size() ? (marks.get(i) ? ORANGE : SLATE) : 0xFF2B3240;
                g.fill(24 + i * 5, fh - 8, 27 + i * 5, fh - 5, col);
            }
            g.drawString(font, "R" + round, fw - 4 - font.width("R" + round), fh - 10, CREAM, false);
            if (now < flashUntil) circle(g, flashX, flashY, 4, 0xAAFFFFFF);
            if (state == State.PLAY && mx >= 0) {
                int x = (int) mx, y = (int) my;
                g.fill(x - 4, y, x - 1, y + 1, CREAM);
                g.fill(x + 2, y, x + 5, y + 1, CREAM);
                g.fill(x, y - 4, x + 1, y - 1, CREAM);
                g.fill(x, y + 2, x + 1, y + 5, CREAM);
                g.fill(x, y, x + 1, y + 1, ORANGE);
            }
        }
    }
}
