package com.xlrw.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.xlrw.gui.Arcade.*;

/** More arcade games: MAZE BIT, FLAP BIT, BIT DASH, MINE 2D. Original characters (Bit) and art. */
final class ArcadeMore {

    private ArcadeMore() {}

    static boolean held(int key) {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key);
    }

    static boolean jumpKey(int key) {
        return key == InputConstants.KEY_SPACE || key == InputConstants.KEY_UP || key == InputConstants.KEY_W;
    }

    // ================================================================================================
    // MAZE BIT - eat every bolt, avoid the glitches, chips make them vulnerable
    // ================================================================================================

    static final class Maze extends Game {
        private static final String[] LAYOUT = {
                "###################", "#........#........#", "#O##.###.#.###.##O#", "#.................#",
                "#.##.#.#####.#.##.#", "#....#...#...#....#", "####.###.#.###.####", "   #.#.......#.#   ",
                "####.#.##-##.#.####", "    ...#GGG#...    ", "####.#.#####.#.####", "   #.#.......#.#   ",
                "####.#.#####.#.####", "#........#........#", "#.##.###.#.###.##.#", "#O.#.....P.....#.O#",
                "##.#.#.#####.#.#.##", "#....#...#...#....#", "#.######.#.######.#", "#.................#",
                "###################"};
        private static final int C = 19, R = 21;
        private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        private char[][] map;
        private int pellets, lives, level;
        private final float[] pl = new float[5];       // x, y (tile), dir, progress, wantDir
        private final float[][] gl = new float[4][6];  // x, y, dir, progress, respawnTimer, eaten
        private float fright;
        private int chain;
        private final List<float[]> fxs = new ArrayList<>();
        private static final int[] GCOL = {PINK, 0xFF7FE3F0, ORANGE, GREEN};

        Maze() { super("MAZE BIT"); }

        @Override String hint() { return "arrows / WASD"; }

        @Override boolean wantsKeys() { return true; }

        @Override void icon(GuiGraphics g, long now) {
            g.fill(0, 2, 7, 9, CREAM);
            g.fill(1, 4, 6, 7, SLATE);
            g.fill(2, 5, 3, 6, ORANGE);
            g.fill(4, 5, 5, 6, ORANGE);
            g.fill(9, 5, 10, 6, CREAM);
            g.fill(12, 5, 13, 6, CREAM);
        }

        @Override void reset(int fw, int fh) {
            lives = 3;
            level = 1;
            loadMap();
        }

        private void loadMap() {
            map = new char[R][C];
            pellets = 0;
            for (int y = 0; y < R; y++) for (int x = 0; x < C; x++) {
                char ch = LAYOUT[y].charAt(x);
                map[y][x] = ch;
                if (ch == '.' || ch == 'O') pellets++;
                if (ch == 'P') { pl[0] = x; pl[1] = y; map[y][x] = ' '; }
            }
            resetActors();
        }

        private void resetActors() {
            for (int y = 0; y < R; y++) for (int x = 0; x < C; x++) if (LAYOUT[y].charAt(x) == 'P') { pl[0] = x; pl[1] = y; }
            pl[2] = 2; pl[3] = 0; pl[4] = 2;
            for (int i = 0; i < 4; i++) {
                gl[i][0] = 8 + (i % 3);
                gl[i][1] = 9;
                gl[i][2] = 3;
                gl[i][3] = 0;
                gl[i][4] = 1.2f + i * 1.6f; // staggered release
                gl[i][5] = 0;
            }
            fright = 0;
        }

        private boolean open(int x, int y, boolean ghost) {
            if (y < 0 || y >= R) return false;
            x = Math.floorMod(x, C);
            char ch = map[y][x];
            if (ch == '#') return false;
            if (ch == '-' || ch == 'G') return ghost;
            return true;
        }

        @Override boolean key(int key) {
            if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_D) pl[4] = 0;
            else if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) pl[4] = 1;
            else if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_A) pl[4] = 2;
            else if (key == InputConstants.KEY_UP || key == InputConstants.KEY_W) pl[4] = 3;
            else return false;
            if ((int) pl[4] == ((int) pl[2] + 2) % 4 && pl[3] > 0) { pl[2] = pl[4]; pl[3] = 1 - pl[3]; // instant reverse
                int[] d = DIRS[((int) pl[2] + 2) % 4];
                pl[0] = Math.floorMod((int) pl[0] + d[0], C); pl[1] += d[1];
            }
            return true;
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            float speed = 5.5f + level * 0.4f;
            // player: at a tile centre, turn if possible, else keep going if possible
            pl[3] += speed * dt;
            while (pl[3] >= 1) {
                int[] d = DIRS[(int) pl[2]];
                if (open((int) pl[0] + d[0], (int) pl[1] + d[1], false)) {
                    pl[0] = Math.floorMod((int) pl[0] + d[0], C);
                    pl[1] += d[1];
                }
                pl[3] -= 1;
                eat();
                int[] w = DIRS[(int) pl[4]];
                if (open((int) pl[0] + w[0], (int) pl[1] + w[1], false)) pl[2] = pl[4];
                int[] cur = DIRS[(int) pl[2]];
                if (!open((int) pl[0] + cur[0], (int) pl[1] + cur[1], false)) pl[3] = 0;
            }
            if (fright > 0) fright -= dt;
            for (int i = 0; i < 4; i++) moveGhost(i, dt, speed);
            collide(fw, fh);
            if (pellets <= 0) {
                level++;
                XlrwSounds.play("game.wave", 1f, 0.5f);
                loadMap();
            }
            tickParticles(fxs, dt, 0);
        }

        private void eat() {
            int x = (int) pl[0], y = (int) pl[1];
            char ch = map[y][x];
            if (ch == '.') { map[y][x] = ' '; pellets--; score += 10; if (pellets % 4 == 0) XlrwSounds.playVar("bit.step", 1.6f, 0.25f, 0.1f); }
            else if (ch == 'O') {
                map[y][x] = ' ';
                pellets--;
                score += 50;
                fright = 7f - Math.min(4, level);
                chain = 0;
                XlrwSounds.play("game.coin", 0.8f, 0.45f);
                for (float[] gh : gl) gh[2] = (gh[2] + 2) % 4;
            }
        }

        private void moveGhost(int i, float dt, float speed) {
            float[] gh = gl[i];
            if (gh[4] > 0) { gh[4] -= dt; return; }
            float sp = speed * (fright > 0 ? 0.55f : 0.9f + i * 0.03f);
            gh[3] += sp * dt;
            while (gh[3] >= 1) {
                gh[3] -= 1;
                int[] d = DIRS[(int) gh[2]];
                if (open((int) gh[0] + d[0], (int) gh[1] + d[1], true)) {
                    gh[0] = Math.floorMod((int) gh[0] + d[0], C);
                    gh[1] += d[1];
                }
                // choose next direction at the tile
                int px = (int) pl[0], py = (int) pl[1];
                int[] pd = DIRS[(int) pl[2]];
                float tx = px, ty = py;
                if (i == 1) { tx += pd[0] * 4; ty += pd[1] * 4; }
                if (i == 2 && Math.hypot(gh[0] - px, gh[1] - py) < 6) { tx = 1; ty = R - 2; }
                if (i == 3 && RND.nextInt(3) == 0) { tx = RND.nextInt(C); ty = RND.nextInt(R); }
                if (map[(int) gh[1]][(int) gh[0]] == 'G') { tx = 9; ty = 7; } // leave the pen
                int best = -1;
                double bd = Double.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    if (k == ((int) gh[2] + 2) % 4) continue;
                    int[] dd = DIRS[k];
                    int nx = (int) gh[0] + dd[0], ny = (int) gh[1] + dd[1];
                    if (!open(nx, ny, true)) continue;
                    if (map[(int) gh[1]][(int) gh[0]] != 'G' && map[(int) gh[1]][(int) gh[0]] != '-' && ny >= 0 && ny < R
                            && map[ny][Math.floorMod(nx, C)] == '-') continue; // do not go back into the pen
                    double dist = fright > 0 ? RND.nextDouble() : Math.hypot(nx - tx, ny - ty);
                    if (dist < bd) { bd = dist; best = k; }
                }
                if (best < 0) best = ((int) gh[2] + 2) % 4;
                gh[2] = best;
            }
        }

        private float[] pos(float[] a) {
            int[] d = DIRS[(int) a[2]];
            boolean moving = open((int) a[0] + d[0], (int) a[1] + d[1], a == pl ? false : true);
            float p = moving ? a[3] : 0;
            float x = a[0] + d[0] * p, y = a[1] + d[1] * p;
            if (x > C - 0.5f) x -= C;
            return new float[]{x, y};
        }

        private void collide(int fw, int fh) {
            float[] p = pos(pl);
            for (int i = 0; i < 4; i++) {
                float[] gh = gl[i];
                if (gh[4] > 0) continue;
                float[] q = pos(gh);
                if (Math.hypot(p[0] - q[0], p[1] - q[1]) < 0.6) {
                    if (fright > 0) {
                        chain++;
                        score += 200 * chain;
                        XlrwSounds.play("game.pop", 1.2f, 0.5f);
                        gh[0] = 9; gh[1] = 9; gh[2] = 3; gh[3] = 0; gh[4] = 3f;
                    } else {
                        lives--;
                        XlrwSounds.play("game.hurt", 1f, 0.5f);
                        if (lives <= 0) { gameOver(); return; }
                        resetActors();
                        return;
                    }
                }
            }
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            if (map == null) reset(fw, fh);
            int T = Math.max(4, Math.min(fw / C, (fh - 10) / R));
            int ox = (fw - C * T) / 2, oy = 10 + (fh - 10 - R * T) / 2;
            for (int y = 0; y < R; y++) for (int x = 0; x < C; x++) {
                char ch = map[y][x];
                int px = ox + x * T, py = oy + y * T;
                if (ch == '#') {
                    g.fill(px, py, px + T, py + T, 0xFF2B3A55);
                    g.fill(px + 1, py + 1, px + T - 1, py + T - 1, 0xFF1A2232);
                } else if (ch == '-') g.fill(px, py + T / 2, px + T, py + T / 2 + 1, PINK);
                else if (ch == '.') g.fill(px + T / 2, py + T / 2, px + T / 2 + 1, py + T / 2 + 1, CREAM);
                else if (ch == 'O' && (now / 250) % 2 == 0) g.fill(px + T / 2 - 1, py + T / 2 - 1, px + T / 2 + 2, py + T / 2 + 2, ORANGE);
            }
            // player: little Bit face looking the way it goes
            float[] p = pos(pl);
            int px = ox + Math.round(p[0] * T), py = oy + Math.round(p[1] * T);
            g.fill(px, py, px + T - 1, py + T - 1, CREAM);
            g.fill(px + 1, py + 2, px + T - 2, py + T - 3, SLATE);
            int[] d = DIRS[(int) pl[2]];
            int ex = px + T / 2 - 1 + d[0], ey = py + 3 + Math.max(0, d[1]);
            g.fill(ex - 1, ey, ex, ey + 1, ORANGE);
            g.fill(ex + 2, ey, ex + 3, ey + 1, ORANGE);
            // glitches
            for (int i = 0; i < 4; i++) {
                float[] gh = gl[i];
                float[] q = pos(gh);
                int gx = ox + Math.round(q[0] * T), gy = oy + Math.round(q[1] * T);
                boolean scared = fright > 0 && gh[4] <= 0;
                int col = scared ? ((fright < 2 && (now / 150) % 2 == 0) ? CREAM : 0xFF3B5BDB) : GCOL[i];
                if (gh[4] > 0) col = (col & 0xFFFFFF) | 0x77000000; // waiting in the pen
                int jit = (now / 90 + i) % 3 == 0 ? 1 : 0;
                g.fill(gx + jit, gy, gx + T - 1 + jit, gy + T - 2, col);
                for (int k = 0; k < T - 1; k += 2) g.fill(gx + k, gy + T - 2, gx + k + 1, gy + T - 1, col);
                g.fill(gx + 1, gy + 2, gx + 2, gy + 3, scared ? CREAM : INK);
                g.fill(gx + T - 3, gy + 2, gx + T - 2, gy + 3, scared ? CREAM : INK);
            }
            drawParticles(g, fxs);
            for (int i = 0; i < lives; i++) g.fill(3 + i * 6, 2, 7 + i * 6, 6, ORANGE);
            String lv = "LV" + level;
            g.drawString(font, lv, fw - 3 - font.width(lv), 1, DIM, false);
        }
    }

    // ================================================================================================
    // FLAP BIT - tap to fly through the pylons
    // ================================================================================================

    static final class Flap extends Game {
        private float y, vy, scroll;
        private final List<float[]> pylons = new ArrayList<>(); // x, gapY, passed
        private final List<float[]> fxs = new ArrayList<>();

        Flap() { super("FLAP BIT"); }

        @Override String hint() { return "click / SPACE = flap"; }

        @Override boolean wantsKeys() { return true; }

        @Override void icon(GuiGraphics g, long now) {
            tinyBitAt(g, 0, 1, now, 0.8f);
            g.fill(10, 0, 12, 10, SLATE);
            g.fill(9, 0, 13, 2, ORANGE);
        }

        @Override void reset(int fw, int fh) {
            y = fh / 2f;
            vy = 0;
            scroll = 0;
            pylons.clear();
            for (int i = 0; i < 4; i++) pylons.add(new float[]{fw + 20 + i * 70, 30 + RND.nextFloat() * (fh - 80), 0});
        }

        private void flap() {
            vy = -135;
            XlrwSounds.playVar("bit.chirp", 1.3f, 0.2f, 0.1f);
        }

        @Override boolean mouseDown(float mx, float my, int fw, int fh) { flap(); return true; }

        @Override boolean key(int key) {
            if (jumpKey(key)) { flap(); return true; }
            return false;
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            vy += 400 * dt;
            y += vy * dt;
            float sp = 55 + Math.min(40, score * 1.5f);
            scroll += sp * dt;
            float bx = fw * 0.28f;
            int gapH = Math.max(34, 50 - score / 3);
            for (float[] p : pylons) {
                p[0] -= sp * dt;
                if (p[0] < -16) {
                    float maxX = 0;
                    for (float[] q : pylons) maxX = Math.max(maxX, q[0]);
                    p[0] = maxX + 70;
                    p[1] = 24 + RND.nextFloat() * (fh - 24 - gapH - 20);
                    p[2] = 0;
                }
                if (p[2] == 0 && p[0] + 12 < bx) { p[2] = 1; score++; XlrwSounds.play("game.coin", 1.2f, 0.3f); }
                boolean inX = bx + 4 > p[0] && bx - 4 < p[0] + 12;
                if (inX && (y - 4 < p[1] || y + 4 > p[1] + gapH)) { crash(bx); return; }
            }
            if (y > fh - 10 || y < -10) crash(bx);
            tickParticles(fxs, dt, 150);
        }

        private void crash(float bx) {
            burst(fxs, bx, y, 12, CREAM, 80);
            XlrwSounds.play("game.hurt", 1f, 0.5f);
            gameOver();
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            g.fill(0, 0, fw, fh, 0xFF1D2B44);
            for (int i = 0; i < 6; i++) { // far city skyline (parallax)
                int w = 16 + (i * 7) % 12, x = (int) (Math.floorMod((long) (i * 40 - scroll * 0.25f), fw + 40)) - 20;
                g.fill(x, fh - 30 - (i * 13) % 26, x + w, fh - 10, 0xFF26375A);
            }
            int gapH = Math.max(34, 50 - score / 3);
            for (float[] p : pylons) {
                int x = (int) p[0];
                g.fill(x, 0, x + 12, (int) p[1], SLATE);
                g.fill(x - 1, (int) p[1] - 4, x + 13, (int) p[1], ORANGE);
                g.fill(x, (int) p[1] + gapH, x + 12, fh - 10, SLATE);
                g.fill(x - 1, (int) p[1] + gapH, x + 13, (int) p[1] + gapH + 4, ORANGE);
                g.fill(x + 2, 0, x + 3, (int) p[1] - 4, 0x33E8D8C9);
            }
            g.fill(0, fh - 10, fw, fh, 0xFF3A2C22);
            for (int x = 0; x < fw; x += 6) g.fill(x - (int) (scroll % 6), fh - 10, x - (int) (scroll % 6) + 3, fh - 9, 0xFF6B513C);
            float bx = fw * 0.28f;
            if (state != State.OVER) {
                g.pose().pushPose();
                g.pose().translate(bx, y, 0);
                g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(Math.max(-25, Math.min(60, vy * 0.25f))));
                tinyBitAt(g, -5, -5, now, 1f);
                int rot = (int) ((now / 50) % 3); // propeller
                g.fill(-4 + rot, -7, 4 - rot, -6, CREAM);
                g.pose().popPose();
            }
            drawParticles(g, fxs);
            g.drawCenteredString(font, String.valueOf(score), fw / 2, 4, CREAM);
        }
    }

    static void tinyBitAt(GuiGraphics g, float x, float y, long now, float scale) {
        tinyBitAt(g, x, y, now, scale, false);
    }

    static void tinyBitAt(GuiGraphics g, float x, float y, long now, float scale, boolean mirror) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        tinyBit(g, 0, 0, now, false, mirror);
        g.pose().popPose();
    }

    // ================================================================================================
    // BIT DASH - fast auto-runner: jump (double jump), springs, collect bolts, dodge spikes
    // ================================================================================================

    static final class Dash extends Game {
        // world objects (x in world px)
        private final List<float[]> ground = new ArrayList<>();    // x0, x1, y
        private final List<float[]> plats = new ArrayList<>();     // x0, x1, y   (one-way, jump through from below)
        private final List<float[]> spikes = new ArrayList<>();    // x, y
        private final List<float[]> springs = new ArrayList<>();   // x, y, squash
        private final List<float[]> boosts = new ArrayList<>();    // x, y
        private final List<float[]> bolts = new ArrayList<>();     // x, y, taken
        private final List<float[]> foes = new ArrayList<>();      // x, y, type(0 crawler,1 flyer), vx, alive, baseX
        private final List<float[]> items = new ArrayList<>();     // x, y, type(0 shield,1 magnet), taken
        private final List<float[]> loops = new ArrayList<>();     // x, y, r, used
        private final List<float[]> fxs = new ArrayList<>();
        private final List<float[]> texts = new ArrayList<>();     // x, y, life (score pop-ups, screen space)
        private final List<String> textStr = new ArrayList<>();
        private float dist, speed, y, vy, genX;
        private boolean onGround, doubleUsed, shield;
        private int carried, combo, zone;
        private long hurtUntil, boostUntil, magnetUntil, zoneBanner;
        private float loopT = -1, loopR, loopCy;               // riding a loop
        private static final int[][] ZONES = { // sky top, sky bottom, far hills, near hills, ground fill
                {0xFF22335A, 0xFF35527F, 0xFF2C4470, 0xFF35527F, 0xFF3A2C22},
                {0xFF5A2E3E, 0xFFD9774A, 0xFF7A3B3B, 0xFF9C4E3A, 0xFF3B2420},  // sunset
                {0xFF0E1224, 0xFF1F2A4A, 0xFF1A2440, 0xFF243256, 0xFF1E1A26},  // night
                {0xFF1F3A2E, 0xFF3F7A5A, 0xFF2D5A44, 0xFF3A7050, 0xFF2E2A1E},  // jungle
        };

        Dash() { super("BIT DASH"); }

        @Override String hint() { return "click / SPACE jump x2 · stomp foes"; }

        @Override boolean wantsKeys() { return true; }

        @Override void icon(GuiGraphics g, long now) {
            tinyBitAt(g, 1, 0, now, 0.8f);
            for (int i = 0; i < 3; i++) g.fill(-3, 3 + i * 2, 0, 4 + i * 2, ORANGE);
        }

        @Override void reset(int fw, int fh) {
            ground.clear(); plats.clear(); spikes.clear(); springs.clear(); boosts.clear(); bolts.clear();
            foes.clear(); items.clear(); loops.clear(); fxs.clear(); texts.clear(); textStr.clear();
            dist = 0; speed = 75; carried = 0; combo = 0; zone = 0; shield = false;
            boostUntil = magnetUntil = hurtUntil = 0;
            loopT = -1;
            ground.add(new float[]{-50, 220, fh - 20});
            genX = 220;
            y = fh - 20; vy = 0; onGround = true; doubleUsed = false;
            zoneBanner = System.currentTimeMillis();
        }

        private void pop(String s, float x, float yy) {
            texts.add(new float[]{x, yy, 0.9f});
            textStr.add(s);
        }

        private void arc(float x, float yy, int n, float h) {
            for (int i = 0; i < n; i++) bolts.add(new float[]{x + i * 9, yy - 14 - (float) Math.sin(i / (float) Math.max(1, n - 1) * Math.PI) * h, 0});
        }

        /** Next stretch of track: one of several hand-made patterns. */
        private void generate(int fw, int fh) {
            while (genX < dist + fw + 140) {
                float last = ground.get(ground.size() - 1)[2];
                float gap = 16 + RND.nextFloat() * (12 + speed * 0.1f);
                float yy = Math.max(fh * 0.5f, Math.min(fh - 14, last + (RND.nextFloat() - 0.5f) * 30));
                float x0 = genX + gap;
                int pattern = RND.nextInt(dist < 600 ? 4 : 8);
                float len;
                switch (pattern) {
                    case 0 -> { // plain run with spikes + bolt arc
                        len = 90 + RND.nextFloat() * 90;
                        ground.add(new float[]{x0, x0 + len, yy});
                        spikes.add(new float[]{x0 + len * 0.6f, yy});
                        arc(x0 + len * 0.6f - 18, yy, 5, 16);
                    }
                    case 1 -> { // crawler patrol
                        len = 120 + RND.nextFloat() * 60;
                        ground.add(new float[]{x0, x0 + len, yy});
                        float fx = x0 + len * 0.55f;
                        foes.add(new float[]{fx, yy, 0, 18, 1, fx});
                        arc(x0 + 15, yy, 3, 6);
                    }
                    case 2 -> { // spring over a wide pit to a high ledge
                        len = 50;
                        ground.add(new float[]{x0, x0 + len, yy});
                        springs.add(new float[]{x0 + 30, yy, 0});
                        float hy = Math.max(fh * 0.35f, yy - 34);
                        float x2 = x0 + len + 50;
                        ground.add(new float[]{x2, x2 + 90, hy});
                        arc(x0 + 40, yy - 30, 6, 20);
                        len = x2 + 90 - x0;
                    }
                    case 3 -> { // floating platform stairs over a pit with a flyer
                        len = 40;
                        ground.add(new float[]{x0, x0 + len, yy});
                        float px = x0 + len + 20;
                        for (int i = 0; i < 3; i++) {
                            float py = yy - 16 - i * 12;
                            plats.add(new float[]{px + i * 42, px + i * 42 + 30, py});
                            arc(px + i * 42 + 6, py, 3, 3);
                        }
                        float x2 = px + 3 * 42 + 10;
                        ground.add(new float[]{x2, x2 + 70, yy - 20 > fh * 0.45f ? yy - 20 : yy});
                        foes.add(new float[]{px + 60, yy - 46, 1, 0, 1, px + 60});
                        len = x2 + 70 - x0;
                    }
                    case 4 -> { // boost pad into a loop
                        len = 200;
                        ground.add(new float[]{x0, x0 + len, yy});
                        boosts.add(new float[]{x0 + 25, yy});
                        loops.add(new float[]{x0 + 110, yy, 22, 0});
                        arc(x0 + 160, yy, 4, 0);
                    }
                    case 5 -> { // spike gauntlet with items
                        len = 160;
                        ground.add(new float[]{x0, x0 + len, yy});
                        spikes.add(new float[]{x0 + 50, yy});
                        spikes.add(new float[]{x0 + 95, yy});
                        spikes.add(new float[]{x0 + 140, yy});
                        items.add(new float[]{x0 + 72, yy - 26, RND.nextInt(2), 0});
                    }
                    case 6 -> { // twin crawlers + flyer, stomp chain
                        len = 190;
                        ground.add(new float[]{x0, x0 + len, yy});
                        foes.add(new float[]{x0 + 60, yy, 0, 14, 1, x0 + 60});
                        foes.add(new float[]{x0 + 110, yy, 0, -14, 1, x0 + 110});
                        foes.add(new float[]{x0 + 160, yy - 24, 1, 0, 1, x0 + 160});
                    }
                    default -> { // high platform shortcut with lots of bolts
                        len = 180;
                        ground.add(new float[]{x0, x0 + len, yy});
                        plats.add(new float[]{x0 + 40, x0 + 140, yy - 28});
                        arc(x0 + 45, yy - 28, 10, 0);
                        spikes.add(new float[]{x0 + 90, yy});
                    }
                }
                genX = x0 + len;
            }
            float cut = dist - 80;
            ground.removeIf(s -> s[1] < cut); plats.removeIf(s -> s[1] < cut);
            spikes.removeIf(s -> s[0] < cut); springs.removeIf(s -> s[0] < cut); boosts.removeIf(s -> s[0] < cut);
            bolts.removeIf(s -> s[0] < cut); foes.removeIf(s -> s[0] < cut); items.removeIf(s -> s[0] < cut);
            loops.removeIf(s -> s[0] + s[2] * 2 < cut);
        }

        private void jump() {
            if (loopT >= 0) return;
            if (onGround) { vy = -200; onGround = false; XlrwSounds.playVar("bit.grab", 1.3f, 0.3f, 0.05f); }
            else if (!doubleUsed) { vy = -175; doubleUsed = true; XlrwSounds.playVar("bit.grab", 1.6f, 0.3f, 0.05f); }
        }

        @Override boolean mouseDown(float mx, float my, int fw, int fh) { jump(); return true; }

        @Override boolean key(int key) {
            if (jumpKey(key)) { jump(); return true; }
            return false;
        }

        private float surfaceAt(float wx, float prevY, float newY) {
            for (float[] s : ground) if (wx >= s[0] && wx <= s[1]) return s[2];
            for (float[] s : plats) if (wx >= s[0] && wx <= s[1] && prevY <= s[2] + 1 && newY >= s[2]) return s[2];
            return Float.NaN;
        }

        private boolean hurt(long now, int fw) {
            if (now < hurtUntil || now < boostUntil) return false;
            if (shield) {
                shield = false;
                hurtUntil = now + 1000;
                burst(fxs, fw * 0.25f, y - 5, 10, 0xFF7FE3F0, 90);
                XlrwSounds.play("bit.surprise", 1f, 0.45f);
                vy = -150;
                return false;
            }
            if (carried > 0) {
                burst(fxs, fw * 0.25f, y - 5, Math.min(16, carried + 3), GOLD, 100);
                carried = 0;
                hurtUntil = now + 1200;
                vy = -150;
                XlrwSounds.play("game.hurt", 1.2f, 0.45f);
                return false;
            }
            crash(fw);
            return true;
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            boolean boosting = now < boostUntil;
            float target = Math.min(200, 75 + dist / 60f) + (boosting ? 90 : 0);
            speed += (target - speed) * Math.min(1, dt * (boosting ? 4 : 1.2f));
            dist += speed * dt;
            int z = (int) (dist / 1600) % ZONES.length;
            if (z != zone) { zone = z; zoneBanner = now; XlrwSounds.play("game.wave", 1.1f, 0.45f); }
            score = (int) (dist / 10) + carried * 10;
            generate(fw, fh);
            float px = dist + fw * 0.25f;

            // riding a loop: the camera keeps scrolling, Bit goes round the circle
            if (loopT >= 0) {
                dist -= speed * dt; // the world holds still while Bit runs round the ring
                loopT += dt * 1.6f;
                if (loopT >= 1) { loopT = -1; onGround = true; }
                tickParticles(fxs, dt, 160);
                return;
            }

            float prevY = y;
            vy += 560 * dt;
            y += vy * dt;
            float gy = surfaceAt(px, prevY, y);
            onGround = false;
            if (!Float.isNaN(gy) && prevY <= gy + 1 && y >= gy && vy >= 0) {
                y = gy; vy = 0; onGround = true; doubleUsed = false; combo = 0;
            } else if (!Float.isNaN(gy) && y > gy + 2 && prevY > gy + 2) {
                // ran into the side of a higher ground block (platforms are jump-through)
                boolean wall = false;
                for (float[] s : ground) if (px >= s[0] && px <= s[1] && s[2] == gy) wall = true;
                if (wall) { crash(fw); return; }
            }
            for (float[] s : springs) {
                if (Math.abs(s[0] - px) < 6 && Math.abs(s[1] - y) < 3 && vy >= 0) {
                    vy = -330; onGround = false; s[2] = 1;
                    XlrwSounds.play("game.wave", 1.6f, 0.35f);
                }
                s[2] = Math.max(0, s[2] - dt * 4);
            }
            for (float[] b : boosts) {
                if (Math.abs(b[0] - px) < 6 && Math.abs(b[1] - y) < 3 && now > boostUntil - 1500) {
                    boostUntil = now + 2200;
                    XlrwSounds.play("ui.power_on", 1.5f, 0.35f);
                }
            }
            for (float[] l : loops) {
                if (l[3] == 0 && onGround && px >= l[0] - 2 && px <= l[0] + 4) {
                    l[3] = 1; loopT = 0; loopR = l[2]; loopCy = l[1] - l[2];
                    score += 100; pop("LOOP +100", fw * 0.25f, y - 20);
                    XlrwSounds.play("game.clear", 1.3f, 0.4f);
                }
            }
            boolean magnet = now < magnetUntil;
            for (float[] b : bolts) {
                if (b[2] != 0) continue;
                if (magnet) {
                    float dx = px - b[0], dy = (y - 5) - b[1], d = (float) Math.hypot(dx, dy);
                    if (d < 60) { b[0] += dx / d * 140 * dt + speed * dt * 0.0f; b[1] += dy / d * 140 * dt; }
                }
                if (Math.abs(b[0] - px) < 6 && Math.abs(b[1] - (y - 5)) < 8) {
                    b[2] = 1; carried++;
                    XlrwSounds.playVar("game.coin", 1.3f, 0.18f, 0.1f);
                }
            }
            for (float[] it : items) {
                if (it[3] != 0) continue;
                if (Math.abs(it[0] - px) < 8 && Math.abs(it[1] - (y - 5)) < 9) {
                    it[3] = 1;
                    if (it[2] == 0) { shield = true; pop("SHIELD", fw * 0.25f, y - 22); }
                    else { magnetUntil = now + 8000; pop("MAGNET", fw * 0.25f, y - 22); }
                    XlrwSounds.play("bit.happy", 1.2f, 0.45f);
                }
            }
            // foes
            for (float[] f : foes) {
                if (f[4] == 0) continue;
                if (f[2] == 0) { // crawler patrols around its base
                    f[0] += f[3] * dt;
                    if (Math.abs(f[0] - f[5]) > 22) f[3] = -f[3];
                } else f[1] += (float) Math.sin(now / 250.0 + f[5]) * 20 * dt;
                float fy = f[2] == 0 ? f[1] - 4 : f[1];
                if (Math.abs(f[0] - px) < 7 && Math.abs(fy - (y - 5)) < 8) {
                    boolean stomp = vy > 0 && y - 5 < fy;
                    if (stomp || boosting) {
                        f[4] = 0; combo++;
                        int pts = 50 * combo;
                        score += pts;
                        pop((combo > 1 ? "x" + combo + " " : "") + "+" + pts, fw * 0.25f, y - 20);
                        burst(fxs, fw * 0.25f, fy, 10, PINK, 90);
                        XlrwSounds.play("game.pop", 1f + combo * 0.08f, 0.45f);
                        if (stomp) { vy = -190; doubleUsed = false; }
                    } else if (hurt(now, fw)) return;
                }
            }
            if (now > hurtUntil && now > boostUntil) {
                for (float[] s : spikes) {
                    if (Math.abs(s[0] - px) < 5 && y > s[1] - 6 && y <= s[1] + 1) { if (hurt(now, fw)) return; break; }
                }
            }
            if (y > fh + 20) { crash(fw); return; }
            if (boosting && RND.nextFloat() < 0.6f) fxs.add(new float[]{fw * 0.25f - 6, y - 2 - RND.nextFloat() * 8, -80, 0, 0.3f, ORANGE});
            tickParticles(fxs, dt, 160);
            for (int i = texts.size() - 1; i >= 0; i--) {
                float[] t = texts.get(i);
                t[1] -= 20 * dt; t[2] -= dt;
                if (t[2] <= 0) { texts.remove(i); textStr.remove(i); }
            }
        }

        private void crash(int fw) {
            burst(fxs, fw * 0.25f, y - 5, 12, CREAM, 90);
            XlrwSounds.play("game.hurt", 0.9f, 0.5f);
            gameOver();
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            int[] zc = ZONES[zone];
            g.fill(0, 0, fw, fh, zc[0]);
            g.fill(0, fh / 3, fw, fh, zc[1]);
            if (zone == 2) for (int i = 0; i < 20; i++) { // stars at night
                int sx = (int) Math.floorMod((long) (i * 53 - dist * 0.05f), fw), sy = (i * 29) % (fh / 2);
                g.fill(sx, sy, sx + 1, sy + 1, 0xAAE8D8C9);
            }
            for (int layer = 0; layer < 2; layer++) {
                int col = zc[2 + layer];
                float f = layer == 0 ? 0.15f : 0.35f;
                for (int x = 0; x < fw; x += 2) {
                    double h = (layer == 0 ? 26 : 16) + Math.sin((x + dist * f) * (layer == 0 ? 0.03 : 0.06)) * (layer == 0 ? 10 : 6);
                    g.fill(x, (int) (fh * (layer == 0 ? 0.55f : 0.68f) - h), x + 2, fh, col);
                }
            }
            for (float[] l : loops) { // loop ring behind the track
                int cx = (int) (l[0] - dist), cy = (int) (l[1] - l[2]);
                for (int a = 0; a < 48; a++) {
                    double t = a * Math.PI * 2 / 48;
                    int x = cx + (int) (Math.cos(t) * l[2]), yy = cy + (int) (Math.sin(t) * l[2]);
                    g.fill(x - 1, yy - 1, x + 2, yy + 2, a % 2 == 0 ? ORANGE : CREAM);
                }
            }
            for (float[] s : ground) {
                int x0 = (int) (s[0] - dist), x1 = (int) (s[1] - dist), yy = (int) s[2];
                g.fill(x0, yy, x1, fh, zc[4]);
                for (int x = x0; x < x1; x += 8) g.fill(x, yy, Math.min(x1, x + 4), yy + 3, ORANGE);
                for (int x = x0 + 4; x < x1; x += 8) g.fill(x, yy, Math.min(x1, x + 4), yy + 3, CREAM);
                g.fill(x0, yy + 3, x1, yy + 4, 0x55000000);
            }
            for (float[] s : plats) {
                int x0 = (int) (s[0] - dist), x1 = (int) (s[1] - dist), yy = (int) s[2];
                g.fill(x0, yy, x1, yy + 4, SLATE);
                g.fill(x0, yy, x1, yy + 1, CREAM);
                g.fill(x0 + 2, yy + 4, x0 + 4, yy + 7, 0x88141416);
                g.fill(x1 - 4, yy + 4, x1 - 2, yy + 7, 0x88141416);
            }
            for (float[] s : spikes) {
                int x = (int) (s[0] - dist), yy = (int) s[1];
                for (int i = 0; i < 4; i++) g.fill(x - 4 + i, yy - 1 - i * 2, x + 4 - i, yy - i * 2 + 1, 0xFFB9C0CC);
            }
            for (float[] s : springs) {
                int x = (int) (s[0] - dist), yy = (int) s[1];
                int up = s[2] > 0 ? 3 : 0;
                g.fill(x - 3, yy - 2 - up, x + 3, yy, SLATE);
                g.fill(x - 4, yy - 4 - up, x + 4, yy - 2 - up, PINK);
            }
            for (float[] b : boosts) {
                int x = (int) (b[0] - dist), yy = (int) b[1];
                g.fill(x - 6, yy - 2, x + 6, yy, 0xFF2B3240);
                int ph = (int) ((now / 80) % 3);
                for (int i = 0; i < 3; i++) g.fill(x - 5 + i * 4, yy - 2, x - 3 + i * 4, yy - 1, i == ph ? ORANGE : 0xFF8A3F12);
            }
            for (float[] b : bolts) {
                if (b[2] != 0) continue;
                int x = (int) (b[0] - dist), yy = (int) b[1];
                boolean spin = (now / 120 + x) % 4 == 0;
                g.fill(x - (spin ? 1 : 2), yy - 2, x + (spin ? 1 : 2), yy + 2, GOLD);
                g.fill(x, yy - 1, x + 1, yy, INK);
            }
            for (float[] it : items) {
                if (it[3] != 0) continue;
                int x = (int) (it[0] - dist), yy = (int) (it[1] + Math.sin(now / 200.0) * 2);
                circle(g, x, yy, 5, it[2] == 0 ? 0xAA7FE3F0 : 0xAAE07AA0);
                g.drawCenteredString(font, it[2] == 0 ? "◆" : "U", x, yy - 4, INK);
            }
            for (float[] f : foes) {
                if (f[4] == 0) continue;
                int x = (int) (f[0] - dist), yy = (int) f[1];
                if (f[2] == 0) { // crawler: spiky glitch blob
                    int bob = (int) ((now / 120) % 2);
                    g.fill(x - 5, yy - 7 + bob, x + 5, yy, 0xFF9C4DCC);
                    for (int k = -5; k < 5; k += 2) g.fill(x + k, yy - 9 + bob, x + k + 1, yy - 7 + bob, 0xFFB9C0CC);
                    g.fill(x + (f[3] < 0 ? -3 : 1), yy - 5 + bob, x + (f[3] < 0 ? -1 : 3), yy - 3 + bob, CREAM);
                } else { // flyer: drone with blinking eye
                    g.fill(x - 5, yy - 3, x + 5, yy + 2, 0xFF6079A0);
                    int w = (now / 60) % 2 == 0 ? 7 : 4;
                    g.fill(x - w, yy - 5, x + w, yy - 4, CREAM);
                    g.fill(x - 1, yy - 1, x + 1, yy + 1, (now / 300) % 2 == 0 ? 0xFFE0403A : ORANGE);
                }
            }
            drawParticles(g, fxs);
            boolean blink = now < hurtUntil && (now / 90) % 2 == 0;
            if (state != State.OVER && !blink) {
                float px = fw * 0.25f, py = y - 5;
                double th = loopT >= 0 ? loopT * Math.PI * 2 : 0;
                if (loopT >= 0) { // ride the circle: start at the bottom, run up the right side, over the top
                    px = fw * 0.25f + (float) (Math.sin(th) * (loopR - 5));
                    py = loopCy + (float) (Math.cos(th) * (loopR - 5));
                }
                g.pose().pushPose();
                g.pose().translate(px, py, 0);
                if (loopT >= 0) g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) -Math.toDegrees(th)));
                else if (!onGround) g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((now * 1.2f) % 360));
                tinyBitAt(g, -5, -5, now, 1f);
                g.pose().popPose();
                if (shield) circle(g, px, py, 9, 0x447FE3F0);
                if (now < magnetUntil && (now / 100) % 2 == 0) g.fill((int) px - 1, (int) py - 12, (int) px + 1, (int) py - 10, PINK);
                if (onGround && speed > 120) for (int i = 0; i < 3; i++) g.fill((int) px - 10 - i * 5, (int) y - 4 - i * 2, (int) px - 6 - i * 5, (int) y - 3 - i * 2, 0x88E8D8C9);
            }
            for (int i = 0; i < texts.size(); i++) {
                float[] t = texts.get(i);
                int a = (int) (Math.min(1, t[2] * 2) * 255);
                g.drawString(font, textStr.get(i), (int) t[0], (int) t[1], (a << 24) | 0xF3C04E, true);
            }
            g.drawString(font, String.valueOf(score), 3, 3, CREAM, false);
            String b = "⚙" + carried;
            g.drawString(font, b, fw - 3 - font.width(b), 3, GOLD, false);
            if (now - zoneBanner < 1600 && state == State.PLAY) g.drawCenteredString(font, "ZONE " + (zone + 1), fw / 2, fh / 3, ORANGE);
            int spd = (int) (speed / 200 * (fw / 3f)); // speed meter
            g.fill(3, 13, 3 + fw / 3, 15, 0x55141416);
            g.fill(3, 13, 3 + spd, 15, now < boostUntil ? ORANGE : 0xFF7FE3F0);
        }
    }

    // ================================================================================================
    // MINE 2D - side-view sandbox with real block textures
    // ================================================================================================

    static final class Mine2D extends Game {
        private static final int W = 96, H = 40, T = 8;
        // ids
        private static final int AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, COAL = 4, IRON = 5, GOLDO = 6, DIAMOND = 7,
                LOG = 8, LEAVES = 9, BEDROCK = 10, COBBLE = 11, PLANKS = 12, SAND = 13, WATER = 14, LAVA = 15;
        private static final int SEA = 16;   // rows at / below this that are open above the ground fill with water
        private static final Block[] BLOCKS = {Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.COAL_ORE,
                Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.DIAMOND_ORE, Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.BEDROCK,
                Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.SAND, Blocks.WATER, Blocks.LAVA};
        private static final float[] HARD = {0, 0.35f, 0.35f, 0.9f, 1.1f, 1.2f, 1.2f, 1.5f, 0.6f, 0.12f, 999, 0.9f, 0.5f, 0.3f, 999, 999};
        private boolean faceLeft;
        private float flowAcc;
        private long lavaOuchAt;

        private static boolean fluid(int id) { return id == WATER || id == LAVA; }
        private int[][] world;
        private float px, py, vx, vy;           // feet centre, px units
        private boolean onGround, mining;
        private int mineX = -1, mineY = -1, selected;
        private float mineProg;
        private final Map<Integer, Integer> inv = new LinkedHashMap<>();
        private final List<float[]> fxs = new ArrayList<>();
        private float camX, camY;
        private boolean rightDown;

        Mine2D() { super("MINE 2D"); }

        @Override String hint() { return "AD move · W jump · LMB mine · RMB place · 1-7"; }

        @Override boolean wantsKeys() { return true; }

        @Override void icon(GuiGraphics g, long now) {
            TextureAtlasSprite s = sprite(GRASS);
            if (s != null) g.blit(0, 0, 0, 10, 10, sprite(DIRT));
            g.fill(0, 0, 10, 3, 0xFF5E9D34);
        }

        private static TextureAtlasSprite sprite(int id) {
            try {
                return Minecraft.getInstance().getBlockRenderer().getBlockModel(BLOCKS[id].defaultBlockState()).getParticleIcon();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override void reset(int fw, int fh) {
            world = new int[H][W];
            double ph1 = RND.nextDouble() * 10, ph2 = RND.nextDouble() * 10;
            int[] surf = new int[W];
            for (int x = 0; x < W; x++) {
                surf[x] = 14 + (int) Math.round(3 * Math.sin(x * 0.13 + ph1) + 2 * Math.sin(x * 0.051 + ph2));
                for (int y = surf[x]; y < H; y++) {
                    int depth = y - surf[x];
                    int id;
                    if (y == H - 1) id = BEDROCK;
                    else if (depth == 0) id = surf[x] >= 16 ? SAND : GRASS;
                    else if (depth < 4) id = surf[x] >= 16 && depth < 2 ? SAND : DIRT;
                    else {
                        float r = RND.nextFloat();
                        if (depth > 16 && r < 0.012f) id = DIAMOND;
                        else if (depth > 10 && r < 0.03f) id = GOLDO;
                        else if (depth > 4 && r < 0.06f) id = IRON;
                        else if (r < 0.10f) id = COAL;
                        else id = STONE;
                    }
                    world[y][x] = id;
                }
                // little caves
                if (RND.nextFloat() < 0.04f) {
                    int cy = surf[x] + 6 + RND.nextInt(12), cx = x;
                    for (int k = 0; k < 14; k++) {
                        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                            int yy = cy + dy, xx = cx + dx;
                            if (yy > 0 && yy < H - 1 && xx >= 0 && xx < W && world[yy][xx] != 0) world[yy][xx] = AIR;
                        }
                        cx += RND.nextInt(3) - 1;
                        cy += RND.nextInt(3) - 1;
                    }
                }
            }
            for (int x = 3; x < W - 3; x++) { // trees
                if (world[surf[x]][x] != GRASS || RND.nextFloat() > 0.09f) continue;
                int top = surf[x] - 5;
                for (int y = surf[x] - 1; y > top; y--) world[y][x] = LOG;
                for (int dy = -2; dy <= 0; dy++) for (int dx = -2; dx <= 2; dx++) {
                    if (Math.abs(dx) == 2 && dy == -2) continue;
                    int yy = top + dy + 1, xx = x + dx;
                    if (yy >= 0 && world[yy][xx] == AIR) world[yy][xx] = LEAVES;
                }
                x += 4;
            }
            // seas / ponds: open cells at or below sea level above the ground
            for (int x = 0; x < W; x++) for (int y = SEA; y < H; y++) {
                if (world[y][x] != AIR) break;
                world[y][x] = WATER;
            }
            // lava pools in deep caves
            for (int x = 0; x < W; x++) for (int y = H - 2; y > H - 9; y--) {
                if (world[y][x] == AIR && world[y + 1][x] != AIR && !fluid(world[y + 1][x]) && RND.nextFloat() < 0.5f) world[y][x] = LAVA;
            }
            px = W / 2f * T;
            int sx = W / 2;
            int sy = 0;
            while (sy < H && (world[sy][sx] == AIR || fluid(world[sy][sx]))) sy++;
            py = sy * T;
            vx = vy = 0;
            inv.clear();
            inv.put(PLANKS, 16);
            selected = 0;
        }

        private boolean solid(int tx, int ty) {
            if (tx < 0 || tx >= W) return true;
            if (ty < 0) return false;
            if (ty >= H) return true;
            int id = world[ty][tx];
            return id != AIR && !fluid(id);
        }

        /** Fluid the player's body overlaps (WATER, LAVA or AIR). */
        private int bodyFluid() {
            int x0 = (int) Math.floor((px - 3) / T), x1 = (int) Math.floor((px + 2.99f) / T);
            int y0 = (int) Math.floor((py - 9) / T), y1 = (int) Math.floor((py - 0.01f) / T);
            int found = AIR;
            for (int y = Math.max(0, y0); y <= Math.min(H - 1, y1); y++) for (int x = Math.max(0, x0); x <= Math.min(W - 1, x1); x++) {
                if (world[y][x] == LAVA) return LAVA;
                if (world[y][x] == WATER) found = WATER;
            }
            return found;
        }

        /** Very small fluid sim: fluid falls into air below, otherwise spreads one cell sideways into air (water only
         * keeps a level surface, so dug holes next to a lake fill up). */
        private void flow(int fw, int fh) {
            int x0 = Math.max(0, (int) (camX / T) - 8), x1 = Math.min(W - 1, (int) ((camX + fw) / T) + 8);
            for (int y = H - 2; y >= 0; y--) for (int x = x0; x <= x1; x++) {
                int id = world[y][x];
                if (!fluid(id)) continue;
                if (world[y + 1][x] == AIR) { world[y + 1][x] = id; world[y][x] = AIR; continue; }
                if (id == WATER && fluid(world[y + 1][x]) && world[y + 1][x] != id) { world[y + 1][x] = OBSIDIAN_LIKE; world[y][x] = AIR; continue; }
                int dir = RND.nextBoolean() ? 1 : -1;
                for (int k = 0; k < 2; k++, dir = -dir) {
                    int nx = x + dir;
                    if (nx < 0 || nx >= W || world[y][nx] != AIR) continue;
                    // only spread if there is more fluid above / beside (keeps puddles from crawling forever)
                    boolean fed = y > 0 && fluid(world[y - 1][x]) || (x - dir >= 0 && x - dir < W && fluid(world[y][x - dir]));
                    if (fed || world[y + 1][nx] == AIR) { world[y][nx] = id; if (!fed) world[y][x] = AIR; break; }
                }
            }
        }
        private static final int OBSIDIAN_LIKE = COBBLE; // water meeting lava turns it into stone

        private boolean boxFree(float cx, float feet) {
            float hw = 3f, h = 9f;
            int x0 = (int) Math.floor((cx - hw) / T), x1 = (int) Math.floor((cx + hw - 0.01f) / T);
            int y0 = (int) Math.floor((feet - h) / T), y1 = (int) Math.floor((feet - 0.01f) / T);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if (solid(x, y)) return false;
            return true;
        }

        @Override boolean key(int key) {
            if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_7) { selected = key - InputConstants.KEY_1; return true; }
            return key == InputConstants.KEY_A || key == InputConstants.KEY_D || key == InputConstants.KEY_W
                    || key == InputConstants.KEY_SPACE || key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_UP;
        }

        @Override boolean mouseDownB(float mx, float my, int fw, int fh, int button) {
            if (button == 0) { mining = true; return true; }
            if (button == 1) { place(mx, my); return true; }
            return false;
        }

        @Override void mouseUpB(float mx, float my, int fw, int fh, int button) {
            if (button == 0) { mining = false; mineProg = 0; }
        }

        private List<Integer> hotbar() {
            List<Integer> l = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : inv.entrySet()) if (e.getValue() > 0) l.add(e.getKey());
            return l;
        }

        private int[] tileAt(float mx, float my) {
            return new int[]{(int) Math.floor((mx + camX) / T), (int) Math.floor((my + camY) / T)};
        }

        private boolean inReach(int tx, int ty) {
            return Math.hypot(tx * T + T / 2f - px, ty * T + T / 2f - (py - 5)) <= 4.5 * T;
        }

        private void place(float mx, float my) {
            List<Integer> hb = hotbar();
            if (hb.isEmpty()) return;
            int id = hb.get(Math.min(selected, hb.size() - 1));
            int[] t = tileAt(mx, my);
            if (t[0] < 0 || t[0] >= W || t[1] < 0 || t[1] >= H || !inReach(t[0], t[1])) return;
            if (world[t[1]][t[0]] != AIR && !fluid(world[t[1]][t[0]])) return;
            boolean support = solid(t[0] + 1, t[1]) || solid(t[0] - 1, t[1]) || solid(t[0], t[1] + 1) || solid(t[0], t[1] - 1);
            if (!support) return;
            int before = world[t[1]][t[0]];
            world[t[1]][t[0]] = id;
            if (!boxFree(px, py)) { world[t[1]][t[0]] = before; return; }
            inv.merge(id, -1, Integer::sum);
            XlrwSounds.playVar("bit.place", 1f, 0.45f, 0.1f);
        }

        @Override void update(float dt, int fw, int fh, float mx, float my, long now) {
            if (world == null) reset(fw, fh);
            float in = 0;
            if (held(InputConstants.KEY_A) || held(InputConstants.KEY_LEFT)) in -= 1;
            if (held(InputConstants.KEY_D) || held(InputConstants.KEY_RIGHT)) in += 1;
            if (in < 0) faceLeft = true;
            if (in > 0) faceLeft = false;
            int liquid = bodyFluid();
            boolean up = held(InputConstants.KEY_W) || held(InputConstants.KEY_SPACE) || held(InputConstants.KEY_UP);
            vx = in * (liquid == AIR ? 46 : liquid == WATER ? 28 : 16);
            if (liquid == AIR) {
                if (up && onGround) { vy = -128; onGround = false; }
                vy = Math.min(220, vy + 420 * dt);
            } else {
                // swimming: slow sinking, hold jump to rise
                vy += (up ? -260 : 90) * dt;
                vy = Math.max(-55, Math.min(liquid == LAVA ? 25 : 40, vy));
                if (RND.nextFloat() < dt * 4) fxs.add(new float[]{px - camX + (RND.nextFloat() - 0.5f) * 4, py - 9 - camY, 0, -20, 0.6f,
                        liquid == WATER ? 0xFFB9D7F2 : 0xFFF3A51E});
                if (liquid == LAVA && now - lavaOuchAt > 600) {
                    lavaOuchAt = now;
                    vy = -110;
                    burst(fxs, px - camX, py - 5 - camY, 6, ORANGE, 60);
                    XlrwSounds.play("game.hurt", 1.3f, 0.35f);
                }
            }
            flowAcc += dt;
            if (flowAcc > 0.2f) { flowAcc = 0; flow(fw, fh); }
            // x
            float nx = px + vx * dt;
            if (boxFree(nx, py)) px = nx;
            else if (onGround && boxFree(nx, py - T) && boxFree(px, py - T)) { px = nx; py -= T; } // auto step up
            // y
            float ny = py + vy * dt;
            if (boxFree(px, ny)) { py = ny; onGround = false; }
            else {
                if (vy > 0) { py = (float) Math.floor(ny / T) * T; onGround = true; }
                vy = 0;
            }
            // mining
            if (mining && mx >= 0) {
                int[] t = tileAt(mx, my);
                if (t[0] != mineX || t[1] != mineY) { mineX = t[0]; mineY = t[1]; mineProg = 0; }
                if (t[0] >= 0 && t[0] < W && t[1] >= 0 && t[1] < H && world[t[1]][t[0]] != AIR && !fluid(world[t[1]][t[0]]) && inReach(t[0], t[1])) {
                    int id = world[t[1]][t[0]];
                    mineProg += dt / HARD[id];
                    if ((now / 160) % 2 == 0 && RND.nextFloat() < 0.3f) burst(fxs, t[0] * T + 4 - camX, t[1] * T + 4 - camY, 1, 0xFF8D97AB, 30);
                    if (mineProg >= 1) {
                        world[t[1]][t[0]] = AIR;
                        int drop = id == GRASS ? DIRT : id == STONE ? COBBLE : id;
                        if (id != LEAVES || RND.nextInt(4) == 0) inv.merge(drop == LEAVES ? PLANKS : drop, 1, Integer::sum);
                        score++;
                        mineProg = 0;
                        burst(fxs, t[0] * T + 4 - camX, t[1] * T + 4 - camY, 7, id == DIAMOND ? 0xFF7FE3F0 : id == GOLDO ? GOLD : 0xFFB9A996, 60);
                        XlrwSounds.playVar(id == DIAMOND || id == GOLDO ? "game.coin" : "bit.break", 1f, 0.4f, 0.1f);
                    }
                } else mineProg = 0;
            }
            camX = Math.max(0, Math.min(W * T - fw, px - fw / 2f));
            camY = Math.max(0, Math.min(H * T - fh, py - fh / 2f));
            tickParticles(fxs, dt, 120);
        }

        @Override void render(GuiGraphics g, Font font, int fw, int fh, float mx, float my, long now) {
            if (world == null) reset(fw, fh);
            g.fill(0, 0, fw, fh, 0xFF7FB2E0);
            g.fill(0, fh / 2, fw, fh, 0xFF95C2EA);
            int x0 = Math.max(0, (int) (camX / T)), x1 = Math.min(W - 1, (int) ((camX + fw) / T) + 1);
            int y0 = Math.max(0, (int) (camY / T)), y1 = Math.min(H - 1, (int) ((camY + fh) / T) + 1);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                int id = world[y][x];
                int sx = Math.round(x * T - camX), sy = Math.round(y * T - camY);
                if (id == AIR) {
                    // darker background behind dug-out underground
                    if (y > 16) g.fill(sx, sy, sx + T, sy + T, 0xFF3A3530);
                    continue;
                }
                if (fluid(id)) {
                    if (y > 16) g.fill(sx, sy, sx + T, sy + T, 0xFF3A3530);
                    TextureAtlasSprite fs = sprite(id);
                    boolean surface = y == 0 || world[y - 1][x] == AIR;
                    if (fs != null) {
                        if (id == WATER) g.blit(sx, sy + (surface ? 1 : 0), 0, T, T - (surface ? 1 : 0), fs, 0.25f, 0.45f, 0.95f, 0.72f);
                        else g.blit(sx, sy, 0, T, T, fs);
                    } else g.fill(sx, sy, sx + T, sy + T, id == WATER ? 0xB0305CC8 : 0xFFE0602A);
                    if (surface) g.fill(sx, sy + 1, sx + T, sy + 2, id == WATER ? 0x88B9D7F2 : 0xFFFFC266);
                    continue;
                }
                TextureAtlasSprite sp = sprite(id == GRASS ? DIRT : id);
                if (sp == null) { g.fill(sx, sy, sx + T, sy + T, 0xFF888888); continue; }
                if (id == LEAVES) g.blit(sx, sy, 0, T, T, sp, 0.42f, 0.72f, 0.3f, 1f);
                else g.blit(sx, sy, 0, T, T, sp);
                if (id == GRASS) g.fill(sx, sy, sx + T, sy + 2, 0xFF5E9D34);
            }
            // target outline + cracks
            if (mx >= 0 && state == State.PLAY) {
                int[] t = tileAt(mx, my);
                if (t[0] >= 0 && t[0] < W && t[1] >= 0 && t[1] < H && inReach(t[0], t[1])) {
                    int sx = Math.round(t[0] * T - camX), sy = Math.round(t[1] * T - camY);
                    g.fill(sx, sy, sx + T, sy + 1, 0xCC141416);
                    g.fill(sx, sy + T - 1, sx + T, sy + T, 0xCC141416);
                    g.fill(sx, sy, sx + 1, sy + T, 0xCC141416);
                    g.fill(sx + T - 1, sy, sx + T, sy + T, 0xCC141416);
                    if (mining && mineProg > 0) {
                        int k = (int) (mineProg * 4);
                        if (k >= 1) g.fill(sx + 2, sy + 2, sx + 3, sy + 6, INK);
                        if (k >= 2) g.fill(sx + 3, sy + 4, sx + 6, sy + 5, INK);
                        if (k >= 3) g.fill(sx + 5, sy + 1, sx + 6, sy + 7, INK);
                    }
                }
            }
            // player (Bit, scaled to ~1 tile wide)
            float sx = px - camX, sy = py - camY;
            tinyBitAt(g, sx - 4, sy - 9, now, 0.75f, faceLeft);
            drawParticles(g, fxs);
            // hotbar
            List<Integer> hb = hotbar();
            int n = Math.min(7, hb.size());
            int hx = (fw - n * 12) / 2, hy = fh - 13;
            for (int i = 0; i < n; i++) {
                int id = hb.get(i);
                int bx = hx + i * 12;
                g.fill(bx - 1, hy - 1, bx + 11, hy + 11, i == Math.min(selected, n - 1) ? ORANGE : 0xAA141416);
                TextureAtlasSprite sp = sprite(id == GRASS ? DIRT : id);
                if (sp != null) {
                    if (id == LEAVES) g.blit(bx + 1, hy + 1, 0, 8, 8, sp, 0.42f, 0.72f, 0.3f, 1f);
                    else g.blit(bx + 1, hy + 1, 0, 8, 8, sp);
                }
                g.pose().pushPose();
                g.pose().translate(bx + 10, hy + 6, 0);
                g.pose().scale(0.5f, 0.5f, 1f);
                String c = String.valueOf(inv.get(id));
                g.drawString(font, c, -font.width(c), 0, CREAM, true);
                g.pose().popPose();
            }
        }
    }
}
