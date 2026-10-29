package com.xlrw.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "Bit": a tiny pixel robot that lives on the top edges of the two deck panels (screen space).
 *
 * <ul>
 *   <li>Wanders, naps, hops, dances, looks at the cursor, reacts to the UI.</li>
 *   <li>To get to the other panel it bridges the gap block by block, walks over and then breaks the bridge behind it.</li>
 *   <li>Can be grabbed and dropped with the mouse; falls if dropped over nothing.</li>
 *   <li>Optional skin: {@code config/xlrw/mascot.png} (your own picture) replaces the pixel robot.</li>
 * </ul>
 */
final class Mascot {

    enum Mood { IDLE, WALK, JUMP, SLEEP, HAPPY, SURPRISED, LOOK, DANCE, CROSS, HELD, FALL }

    /** Straight walkable segment (a panel's top edge) in screen coordinates. */
    record Edge(float x0, float y0, float x1, float y1) {
        float yAt(float x) {
            return x1 == x0 ? y0 : y0 + (y1 - y0) * (x - x0) / (x1 - x0);
        }

        boolean covers(float x) {
            return x >= x0 && x <= x1;
        }
    }

    private static final int INK = 0xFF141416, CREAM = 0xFFE8D8C9, SHADE = 0xFFC9B6A5, SLATE = 0xFF4B607F, ORANGE = 0xFFF3701E;
    private static final int P = 2;
    static final int W = 11 * P, H = 10 * P;
    private static final float B = 7f;          // bridge block size (px)

    private final Random rnd = new Random();
    private float x = -1, y, vy;                // x = centre, y = feet
    private int dir = 1;
    private Mood mood = Mood.IDLE;
    private long moodUntil;
    private float targetX;
    private long blinkAt;
    private String bubble;
    private long bubbleUntil;
    private long lastNanos = System.nanoTime();
    private long lastStep, lastAction;
    private float grabDx, grabDy, lastMx, swing;
    private boolean armOut;

    private Edge left, right;
    // bridge between left.x1 and right.x0
    private boolean[] placed = new boolean[0];
    private float[] crack = new float[0];
    private boolean cleanup;          // just crossed: break the bridge behind
    private int cleanupDir;

    private record Bit(float[] s, int color) {}         // particle: x, y, vx, vy, life
    private final List<Bit> particles = new ArrayList<>();

    /** Event queued from outside the deck (e.g. "win" when a build finishes). */
    static volatile String pending;

    /** Bit is inside a mini game (gold miner): hide from the panel edges. */
    static boolean away;

    // custom skin
    private static boolean skinTried;
    private static ResourceLocation skin;
    private static int skinW, skinH;

    // ------------------------------------------------------------------------------------------------

    private void setMood(Mood m, long now, long ms) {
        mood = m;
        moodUntil = now + ms;
        if ((m == Mood.JUMP || m == Mood.HAPPY || m == Mood.SURPRISED) && onGround()) vy = m == Mood.SURPRISED ? -70f : -95f;
    }

    private void say(String s, long now, long ms) {
        bubble = s;
        bubbleUntil = now + ms;
    }

    private Edge edgeUnder(float px) {
        if (left != null && left.covers(px)) return left;
        if (right != null && right.covers(px)) return right;
        return null;
    }

    private float gapX0() { return left.x1(); }

    private float gapX1() { return right.x0(); }

    private int slots() {
        if (left == null || right == null) return 0;
        float gap = gapX1() - gapX0();
        return gap <= 3 ? 0 : (int) Math.ceil(gap / B);
    }

    private int slotAt(float px) {
        return (int) Math.floor((px - gapX0()) / ((gapX1() - gapX0()) / slots()));
    }

    private float bridgeY(float px) {
        float t = (px - gapX0()) / Math.max(1e-3f, gapX1() - gapX0());
        return left.y1() + (right.y0() - left.y1()) * t;
    }

    /** Surface height at px, or NaN where there is nothing to stand on. */
    private float groundAt(float px) {
        Edge e = edgeUnder(px);
        if (e != null) return e.yAt(px);
        if (left != null && right != null && px > gapX0() && px < gapX1()) {
            int n = slots();
            if (n == 0) return bridgeY(px);
            int i = slotAt(px);
            if (i >= 0 && i < n && placed[i]) return bridgeY(px);
        }
        return Float.NaN;
    }

    private boolean onGround() {
        float g = groundAt(x);
        return !Float.isNaN(g) && Math.abs(y - g) < 0.75f && vy >= 0;
    }

    private float minX() { return left != null ? left.x0() + W / 2f : 0; }

    private float maxX() { return (right != null ? right.x1() : left != null ? left.x1() : 0) - W / 2f; }

    private void pickMood(long now) {
        int r = rnd.nextInt(100);
        boolean onLeft = left != null && x <= gapX0Safe();
        if (right != null && r < 16) {
            // go visit the other panel
            Edge to = onLeft ? right : left;
            targetX = to.x0() + W + rnd.nextFloat() * Math.max(1, to.x1() - to.x0() - 2 * W);
            dir = targetX > x ? 1 : -1;
            setMood(Mood.CROSS, now, 60_000);
            say(rnd.nextBoolean() ? "→" : "!", now, 900);
            return;
        }
        if (r < 46) {
            Edge e = edgeUnder(x);
            if (e == null) e = left;
            targetX = Math.max(e.x0() + W / 2f, Math.min(e.x1() - W / 2f, e.x0() + rnd.nextFloat() * (e.x1() - e.x0())));
            dir = targetX > x ? 1 : -1;
            setMood(Mood.WALK, now, 9000);
        } else if (r < 60) setMood(Mood.IDLE, now, 1800 + rnd.nextInt(2500));
        else if (r < 71) setMood(Mood.LOOK, now, 2500 + rnd.nextInt(2000));
        else if (r < 79) {
            setMood(Mood.JUMP, now, 900);
            XlrwSounds.playVar("bit.chirp", 1.1f, 0.35f, 0.15f);
            if (rnd.nextBoolean()) say(rnd.nextBoolean() ? "♪" : "!", now, 1200);
        } else if (r < 88) {
            setMood(Mood.SLEEP, now, 5000 + rnd.nextInt(4000));
            XlrwSounds.play("bit.sleep", 1f, 0.25f);
        } else if (r < 94) {
            setMood(Mood.DANCE, now, 2400);
            say("♪♫", now, 2000);
        } else {
            setMood(Mood.HAPPY, now, 1500);
            XlrwSounds.playVar("bit.happy", 1f, 0.35f, 0.1f);
            say(new String[]{"build!", "hehe", "♥", "...", "?"}[rnd.nextInt(5)], now, 1600);
        }
    }

    private float gapX0Safe() {
        return left != null ? (right != null ? (left.x1() + right.x0()) / 2f : left.x1()) : 0;
    }

    // ------------------------------------------------------------------------------------------------
    // UI events + mouse
    // ------------------------------------------------------------------------------------------------

    void react(String event) {
        long now = System.currentTimeMillis();
        if (mood == Mood.HELD || mood == Mood.FALL) return;
        switch (event) {
            case "on" -> { setMood(Mood.JUMP, now, 1000); say("GO!", now, 1500); XlrwSounds.play("bit.happy", 1.2f, 0.4f); }
            case "off" -> { setMood(Mood.SLEEP, now, 6000); say("zZ", now, 1500); XlrwSounds.play("bit.sleep", 1f, 0.3f); }
            case "click" -> {
                if (mood == Mood.SLEEP) { setMood(Mood.SURPRISED, now, 800); say("!?", now, 900); XlrwSounds.play("bit.surprise", 1f, 0.4f); }
                else if (rnd.nextInt(4) == 0 && mood != Mood.CROSS) setMood(Mood.LOOK, now, 1500);
            }
            case "win" -> { setMood(Mood.DANCE, now, 7000); say("YAY!", now, 3000); XlrwSounds.play("bit.happy", 1.1f, 0.5f); }
            case "edit" -> { setMood(Mood.HAPPY, now, 1200); say("✥", now, 1200); }
            default -> {
            }
        }
    }

    boolean contains(double mx, double my) {
        return x >= 0 && mx >= x - W / 2f - 3 && mx < x + W / 2f + 3 && my >= y - H - 3 && my < y + 2;
    }

    void grab(double mx, double my) {
        grabDx = (float) (mx - x);
        grabDy = (float) (my - y);
        lastMx = (float) mx;
        mood = Mood.HELD;
        moodUntil = Long.MAX_VALUE;
        say("!!", System.currentTimeMillis(), 700);
        XlrwSounds.play("bit.grab", 1f, 0.45f);
    }

    boolean held() {
        return mood == Mood.HELD;
    }

    void drag(double mx, double my) {
        if (mood != Mood.HELD) return;
        swing += ((float) mx - lastMx) * 1.5f;
        lastMx = (float) mx;
        x = (float) mx - grabDx;
        y = (float) my - grabDy;
    }

    void release() {
        if (mood != Mood.HELD) return;
        vy = 0;
        float g = groundAt(x);
        if (!Float.isNaN(g) && y > g) y = g; // dropped "inside" a panel: pop onto its edge
        mood = Mood.FALL;
        moodUntil = Long.MAX_VALUE;
        XlrwSounds.play("bit.drop", 1f, 0.35f);
    }

    // ------------------------------------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------------------------------------

    private void syncBridge() {
        int n = slots();
        if (placed.length != n) {
            boolean[] p = new boolean[n];
            float[] c = new float[n];
            for (int i = 0; i < Math.min(n, placed.length); i++) { p[i] = placed[i]; c[i] = crack[i]; }
            placed = p;
            crack = c;
        }
    }

    private void burst(float px, float py, int color, int count) {
        for (int i = 0; i < count; i++) {
            particles.add(new Bit(new float[]{px, py, (rnd.nextFloat() - 0.5f) * 60f, -rnd.nextFloat() * 60f - 20f, 0.6f + rnd.nextFloat() * 0.4f}, color));
        }
    }

    private void slotCenter(int i, float[] out) {
        float sw = (gapX1() - gapX0()) / slots();
        out[0] = gapX0() + sw * (i + 0.5f);
        out[1] = bridgeY(out[0]);
    }

    private void simulate(long now, float dt, double mx) {
        syncBridge();
        int n = slots();
        if (x < 0 && left != null) { // spawn
            x = left.x0() + 40;
            y = left.yAt(x) - 60;
            vy = 0;
            mood = Mood.FALL;
        }
        armOut = false;

        if (mood == Mood.HELD) {
            swing *= 0.85f;
            return;
        }
        swing *= 0.8f;

        // --- moving along / building ---
        if (mood == Mood.CROSS && n > 0 && !cleanup) {
            boolean crossed = dir > 0 ? x - W / 2f > gapX1() + 1 : x + W / 2f < gapX0() - 1;
            boolean any = false;
            for (boolean b : placed) any |= b;
            if (crossed && any) {
                cleanup = true;          // break the bridge behind before walking on
                cleanupDir = -dir;
            }
        }
        if ((mood == Mood.WALK || mood == Mood.CROSS) && !cleanup) {
            float sp = (mood == Mood.CROSS ? 26f : 20f) * dt;
            dir = targetX > x ? 1 : -1;
            float next = x + dir * sp;
            float front = next + dir * (W / 2f - 3);
            if (Math.abs(targetX - x) <= sp) {
                x = targetX;
                setMood(Mood.IDLE, now, 700 + rnd.nextInt(800));
            } else if (n > 0 && front > gapX0() && front < gapX1() && !placed[Math.max(0, Math.min(n - 1, slotAt(front)))]) {
                // need a block in front
                armOut = true;
                if (now - lastAction > 380) {
                    int i = Math.max(0, Math.min(n - 1, slotAt(front)));
                    placed[i] = true;
                    crack[i] = 0;
                    lastAction = now;
                    float[] c = new float[2];
                    slotCenter(i, c);
                    burst(c[0], c[1] - B / 2, CREAM, 4);
                    XlrwSounds.playVar("bit.place", 1f, 0.5f, 0.12f);
                }
            } else if (!Float.isNaN(groundAt(next))) {
                x = next;
                if (now - lastStep > 260) {
                    lastStep = now;
                    XlrwSounds.playVar("bit.step", 1f, 0.12f, 0.2f);
                }
            } else if (mood == Mood.WALK) {
                setMood(Mood.IDLE, now, 800);
            }
        }

        // --- break the bridge after crossing ---
        if (cleanup && onGround()) {
            int target = -1;
            // nearest placed slot first
            if (cleanupDir < 0) { for (int i = n - 1; i >= 0; i--) if (placed[i]) { target = i; break; } }
            else { for (int i = 0; i < n; i++) if (placed[i]) { target = i; break; } }
            if (target < 0 || edgeUnder(x) == null) {
                cleanup = false;
                if (target < 0 && rnd.nextBoolean()) say("✓", now, 900);
            } else {
                dir = cleanupDir;
                armOut = true;
                moodUntil = Math.max(moodUntil, now + 300);
                crack[target] += dt * 3.2f;
                if (now - lastAction > 120) {
                    lastAction = now;
                    float[] c = new float[2];
                    slotCenter(target, c);
                    burst(c[0], c[1] - B / 2, SLATE, 1);
                }
                if (crack[target] >= 1f) {
                    placed[target] = false;
                    crack[target] = 0;
                    float[] c = new float[2];
                    slotCenter(target, c);
                    burst(c[0], c[1] - B / 2, SLATE, 7);
                    burst(c[0], c[1] - B / 2, CREAM, 3);
                    XlrwSounds.playVar("bit.break", 1f, 0.5f, 0.15f);
                }
            }
        }

        if (mood == Mood.DANCE) {
            dir = (now / 300) % 4 < 2 ? 1 : -1;
            if (onGround() && (now / 600) % 2 == 0) vy = -40f;
        }

        // --- gravity / landing ---
        float g = groundAt(x);
        boolean wasFalling = mood == Mood.FALL;
        if (Float.isNaN(g) || y < g - 0.5f || vy < 0) {
            float prevY = y;
            vy += 320f * dt;
            y += vy * dt;
            if (!Float.isNaN(g) && prevY <= g + 0.5f && y >= g && vy > 0) {
                y = g;
                if (vy > 120 || wasFalling) {
                    XlrwSounds.play("bit.land", 1f, 0.4f);
                    burst(x, y, CREAM, 3);
                }
                vy = 0;
                if (wasFalling) {
                    setMood(Mood.SURPRISED, now, 700);
                    say(rnd.nextBoolean() ? "oof" : "!", now, 900);
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (y > mc.getWindow().getGuiScaledHeight() + 40) { // fell off: respawn from the top
                x = -1;
                cleanup = false;
            }
        } else {
            y = g; // follow the (parallax-moving) panel edge
            vy = 0;
            if (mood == Mood.FALL) setMood(Mood.IDLE, now, 600);
        }
        if (x >= 0 && mood != Mood.FALL) x = Math.max(minX(), Math.min(maxX(), x));

        if (now > moodUntil && onGround() && !cleanup) pickMood(now);
        if (now > blinkAt + 3000 + rnd.nextInt(2000)) blinkAt = now;

        // particles
        for (int i = particles.size() - 1; i >= 0; i--) {
            float[] s = particles.get(i).s();
            s[3] += 300f * dt;
            s[0] += s[2] * dt;
            s[1] += s[3] * dt;
            s[4] -= dt;
            if (s[4] <= 0) particles.remove(i);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------------

    void render(GuiGraphics g, Font font, Edge leftEdge, Edge rightEdge, double mx, double my, long now) {
        left = leftEdge;
        right = rightEdge;
        if (left == null) return;
        if (pending != null) {
            String ev = pending;
            pending = null;
            react(ev);
        }
        if (away) {           // playing in the arcade; drop back in from the top afterwards
            x = -1;
            particles.clear();
            return;
        }
        long n = System.nanoTime();
        float dt = Math.min(0.05f, (n - lastNanos) / 1e9f);
        lastNanos = n;
        simulate(now, dt, mx);
        if (x < 0) return;

        drawBridge(g);
        for (Bit b : particles) {
            float[] s = b.s();
            int a = (int) (Math.min(1f, s[4] * 2f) * 255);
            g.fill((int) s[0], (int) s[1], (int) s[0] + 2, (int) s[1] + 2, (a << 24) | (b.color() & 0xFFFFFF));
        }

        int sx = Math.round(x - W / 2f), sy = Math.round(y - H);
        float gy = groundAt(x);
        if (!Float.isNaN(gy) && mood != Mood.HELD) {
            float air = Math.max(0, gy - y);
            int shw = Math.max(4, W - 4 - (int) (air / 2));
            g.fill(Math.round(x - shw / 2f), Math.round(gy) - 1, Math.round(x + shw / 2f), Math.round(gy), 0x55000000);
        }

        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, 0, 0, 300);
        if (mood == Mood.HELD || Math.abs(swing) > 0.5f) {
            com.xlrw.compat.GuiCompat.translate(g, x, sy, 0);
            com.xlrw.compat.GuiCompat.rotateZ(g, Math.max(-35, Math.min(35, swing)));
            com.xlrw.compat.GuiCompat.translate(g, -x, -sy, 0);
        }
        loadSkin();
        if (skin != null) drawSkin(g, sx, sy);
        else drawRobot(g, font, sx, sy, mx, my, now);
        com.xlrw.compat.GuiCompat.pop(g);

        if (bubble != null && now < bubbleUntil) {
            int tw = font.width(bubble);
            int bx = Math.round(x) - tw / 2 - 3, by = sy - 14;
            com.xlrw.compat.GuiCompat.push(g);
            com.xlrw.compat.GuiCompat.translate(g, 0, 0, 310);
            g.fill(bx, by, bx + tw + 6, by + 11, CREAM);
            g.fill(bx - 1, by + 1, bx, by + 10, CREAM);
            g.fill(bx + tw + 6, by + 1, bx + tw + 7, by + 10, CREAM);
            g.fill(Math.round(x) - 1, by + 11, Math.round(x) + 2, by + 13, CREAM);
            g.drawString(font, bubble, bx + 3, by + 2, INK, false);
            com.xlrw.compat.GuiCompat.pop(g);
        }
    }

    private void drawBridge(GuiGraphics g) {
        int n = slots();
        if (n == 0) return;
        float sw = (gapX1() - gapX0()) / n;
        float[] c = new float[2];
        for (int i = 0; i < n; i++) {
            if (!placed[i]) continue;
            slotCenter(i, c);
            int bx0 = Math.round(c[0] - sw / 2f), bx1 = Math.round(c[0] + sw / 2f), by1 = Math.round(c[1]) + (int) B, by0 = Math.round(c[1]);
            g.fill(bx0, by0, bx1, by1, SLATE);
            g.fill(bx0, by0, bx1, by0 + 2, CREAM);          // top face
            g.fill(bx1 - 1, by0 + 2, bx1, by1, 0xFF33435C); // side shade
            if (crack[i] > 0) {
                int k = (int) (crack[i] * 4);
                if (k >= 1) g.fill(bx0 + 2, by0 + 3, bx0 + 3, by1 - 1, INK);
                if (k >= 2) g.fill(bx0 + 3, by0 + 4, bx1 - 2, by0 + 5, INK);
                if (k >= 3) g.fill(bx1 - 3, by0 + 2, bx1 - 2, by1 - 2, INK);
            }
        }
    }

    private void drawRobot(GuiGraphics g, Font font, int sx, int sy, double mx, double my, long now) {
        boolean held = mood == Mood.HELD, falling = mood == Mood.FALL;
        boolean walking = mood == Mood.WALK || mood == Mood.CROSS;
        int step = walking ? (int) ((now / 140) % 2) : 0;
        // legs (dangle while held / falling)
        int legDrop = held || falling ? 1 : 0;
        px(g, sx, sy, 2, 9 + legDrop - (step == 1 ? 1 : 0), 2, 1, INK);
        px(g, sx, sy, 7, 9 + legDrop - (step == 0 && walking ? 1 : 0), 2, 1, INK);
        // body
        px(g, sx, sy, 1, 2, 9, 7, INK);
        px(g, sx, sy, 0, 3, 11, 5, INK);
        px(g, sx, sy, 1, 3, 9, 5, CREAM);
        g.fill(sx + 2 * P, sy + 7 * P, sx + 9 * P, sy + 8 * P, SHADE);
        // arm (building / breaking / held up)
        if (armOut) {
            int ac = dir > 0 ? 11 : -1;
            px(g, sx, sy, ac, 5 + (int) ((now / 90) % 2), 1, 1, INK);
        }
        if (held) {
            px(g, sx, sy, -1, 3, 1, 1, INK);
            px(g, sx, sy, 11, 3, 1, 1, INK);
        }
        // antenna
        int ax = 5 + (walking ? -dir : 0);
        px(g, sx, sy, 5, 1, 1, 1, INK);
        boolean tipOn = (now / 500) % 2 == 0 || mood == Mood.HAPPY || held;
        px(g, sx, sy, ax, 0, 1, 1, tipOn ? ORANGE : SLATE);
        // visor
        px(g, sx, sy, 2, 4, 7, 3, SLATE);

        int look = dir, eyeRow = 5;
        if (mood == Mood.LOOK) {
            double cx = sx + W / 2.0, cy = sy + H / 2.0;
            look = mx < cx - 6 ? -1 : mx > cx + 6 ? 1 : 0;
            if (my < cy - 14) eyeRow = 4;
        }
        int e1 = 3 + Math.max(0, look) + (look == 0 ? 1 : 0), e2 = e1 + 3;
        boolean blink = System.currentTimeMillis() - blinkAt < 140;
        switch (mood) {
            case SLEEP -> {
                g.fill(sx + e1 * P, sy + 5 * P + 1, sx + (e1 + 1) * P, sy + 5 * P + 2, ORANGE);
                g.fill(sx + e2 * P, sy + 5 * P + 1, sx + (e2 + 1) * P, sy + 5 * P + 2, ORANGE);
                int zz = (int) ((now / 600) % 3);
                g.drawString(font, "z", sx + W + zz * 2, sy - 2 - zz * 4, ((int) ((1f - zz * 0.3f) * 255) << 24) | (CREAM & 0xFFFFFF), false);
            }
            case HAPPY, DANCE -> {
                for (int e : new int[]{e1, e2}) {
                    g.fill(sx + e * P - 1, sy + 5 * P, sx + e * P + 1, sy + 5 * P + 1, ORANGE);
                    g.fill(sx + e * P, sy + 4 * P + 1, sx + e * P + 2, sy + 5 * P, ORANGE);
                    g.fill(sx + e * P + 1, sy + 5 * P, sx + e * P + 3, sy + 5 * P + 1, ORANGE);
                }
                g.fill(sx + P, sy + 7 * P, sx + 2 * P, sy + 7 * P + 1, 0xFFF39A6E);
                g.fill(sx + 9 * P, sy + 7 * P, sx + 10 * P, sy + 7 * P + 1, 0xFFF39A6E);
            }
            case SURPRISED, HELD, FALL -> {
                px(g, sx, sy, e1 - 1, 4, 2, 2, ORANGE);
                px(g, sx, sy, e2, 4, 2, 2, ORANGE);
            }
            default -> {
                if (blink) {
                    g.fill(sx + e1 * P, sy + eyeRow * P + 1, sx + (e1 + 1) * P, sy + eyeRow * P + 2, ORANGE);
                    g.fill(sx + e2 * P, sy + eyeRow * P + 1, sx + (e2 + 1) * P, sy + eyeRow * P + 2, ORANGE);
                } else {
                    px(g, sx, sy, e1, eyeRow, 1, 1, ORANGE);
                    px(g, sx, sy, e2, eyeRow, 1, 1, ORANGE);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Optional skin (config/xlrw/mascot.png)
    // ------------------------------------------------------------------------------------------------

    private static void loadSkin() {
        if (skinTried) return;
        skinTried = true;
        try {
            File f = new File(Minecraft.getInstance().gameDirectory, "config/xlrw/mascot.png");
            if (!f.isFile()) return;
            try (FileInputStream in = new FileInputStream(f)) {
                NativeImage img = NativeImage.read(in);
                skinW = img.getWidth();
                skinH = img.getHeight();
                ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("xlrw", "dynamic/mascot");
                Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
                skin = loc;
            }
        } catch (Throwable t) {
            skin = null;
        }
    }

    private void drawSkin(GuiGraphics g, int sx, int sy) {
        int h = H + 2, w = Math.max(4, Math.round(h * (skinW / (float) Math.max(1, skinH))));
        float bob = (mood == Mood.WALK || mood == Mood.CROSS) ? ((System.currentTimeMillis() / 140) % 2) : 0;
        int dx = Math.round(x - w / 2f), dy = Math.round(y - h - bob);
        // face left by flipping the texture coordinates (a negative pose scale would get the quad culled)
        if (dir < 0) g.blit(skin, dx, dy, w, h, skinW, 0, -skinW, skinH, skinW, skinH);
        else g.blit(skin, dx, dy, w, h, 0, 0, skinW, skinH, skinW, skinH);
    }

    private static void px(GuiGraphics g, int sx, int sy, int c, int r, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(sx + c * P, sy + r * P, sx + (c + w) * P, sy + (r + h) * P, color);
    }
}
