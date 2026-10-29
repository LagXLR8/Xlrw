package com.xlrw.gui;

import com.mojang.math.Axis;
import com.xlrw.render.SchematicRenderer;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "BUILD COMPLETE" party: a fanfare, a Persona-style banner with confetti over the HUD, Bit dancing on it and a
 * client-side firework show around the finished schematic.
 */
public final class Celebration {

    private static final int INK = 0xFF141416, CREAM = 0xFFE8D8C9, ORANGE = 0xFFF3701E, SLATE = 0xFF4B607F;
    private static final int[] CONFETTI = {ORANGE, CREAM, 0xFF6F95C9, 0xFF7FD18B, 0xFFE07AA0, 0xFFF3C04E};
    private static final long DURATION = 9000;
    private static final Random RND = new Random();

    private static long startedAt;
    private static String title = "", sub = "";
    private static final List<float[]> confetti = new ArrayList<>(); // x, y, vx, vy, spin, phase, color, size
    private static final List<double[]> rockets = new ArrayList<>(); // launchAt, x, y0, z, y1, color, done
    private static long lastNanos;

    private Celebration() {}

    public static boolean active() {
        return startedAt != 0 && System.currentTimeMillis() - startedAt < DURATION;
    }

    /** Build finished. */
    public static void start(String schematicName, int blocks, long uptimeMs) {
        startedAt = System.currentTimeMillis();
        title = "BUILD COMPLETE";
        long s = uptimeMs / 1000;
        sub = (schematicName == null ? "" : schematicName.replaceFirst("\\.[^.]+$", "").toUpperCase()) + "   ·   "
                + blocks + " BLOCKS" + (s > 0 ? "   ·   " + String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : "");
        confetti.clear();
        XlrwSounds.play("ui.victory", 1f, 0.8f);
        Mascot.pending = "win";
        // fireworks around the build
        rockets.clear();
        Minecraft mc = Minecraft.getInstance();
        Schematic sc = SchematicManager.getInstance().getActiveSchematic();
        SchematicRenderer.Bounds b = SchematicRenderer.Bounds.of(sc);
        Vec3 c = b != null ? b.center() : (mc.player != null ? mc.player.position() : Vec3.ZERO);
        double rx = b != null ? (b.maxX - b.minX) / 2 + 3 : 6, rz = b != null ? (b.maxZ - b.minZ) / 2 + 3 : 6;
        double top = b != null ? b.maxY : c.y + 4;
        for (int i = 0; i < 12; i++) {
            double a = RND.nextDouble() * Math.PI * 2;
            rockets.add(new double[]{startedAt + 300 + i * 450L + RND.nextInt(200), c.x + Math.cos(a) * rx, top - 2,
                    c.z + Math.sin(a) * rz, top + 6 + RND.nextInt(8), CONFETTI[RND.nextInt(CONFETTI.length)] & 0xFFFFFF, 0});
        }
    }

    /** Client tick: rockets. */
    public static void tick(Minecraft mc) {
        if (rockets.isEmpty() || mc.level == null) return;
        long now = System.currentTimeMillis();
        for (double[] r : rockets) {
            if (r[6] != 0 || now < r[0]) continue;
            double flight = 1100;
            double f = (now - r[0]) / flight;
            if (f < 1) {
                double y = r[2] + (r[4] - r[2]) * (1 - (1 - f) * (1 - f));
                mc.level.addParticle(ParticleTypes.FIREWORK, r[1], y, r[3], (RND.nextDouble() - 0.5) * 0.02, -0.05, (RND.nextDouble() - 0.5) * 0.02);
            } else {
                r[6] = 1;
                FireworkExplosion.Shape[] shapes = {FireworkExplosion.Shape.LARGE_BALL, FireworkExplosion.Shape.STAR,
                        FireworkExplosion.Shape.BURST, FireworkExplosion.Shape.SMALL_BALL};
                int col = (int) r[5];
                int col2 = CONFETTI[RND.nextInt(CONFETTI.length)] & 0xFFFFFF;
                FireworkExplosion ex = new FireworkExplosion(shapes[RND.nextInt(shapes.length)], IntList.of(col, col2),
                        IntList.of(0xE8D8C9), RND.nextBoolean(), true);
                mc.level.createFireworks(r[1], r[4], r[3], 0, 0, 0, List.of(ex));
            }
        }
        rockets.removeIf(r -> r[6] != 0);
    }

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1f - (float) Math.pow(1f - t, 3);
    }

    /** Draw over everything (HUD, or on top of the deck when it is open). */
    public static void render(GuiGraphics g) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        long now = System.currentTimeMillis();
        long t = now - startedAt;
        long n = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.05f, (n - lastNanos) / 1e9f);
        lastNanos = n;

        // confetti: two bursts from the top corners, then a steady drizzle
        if (t < 6000 && (confetti.size() < 220)) {
            int spawn = t < 300 ? 12 : 2;
            for (int i = 0; i < spawn; i++) {
                boolean left = RND.nextBoolean();
                float x = t < 900 ? (left ? -4 : w + 4) : RND.nextFloat() * w;
                float vx = t < 900 ? (left ? 1 : -1) * (60 + RND.nextFloat() * 160) : (RND.nextFloat() - 0.5f) * 20;
                float vy = t < 900 ? -40 - RND.nextFloat() * 120 : 20 + RND.nextFloat() * 30;
                confetti.add(new float[]{x, t < 900 ? h * 0.15f + RND.nextFloat() * 30 : -6, vx, vy, 3 + RND.nextFloat() * 6,
                        RND.nextFloat() * 6.28f, CONFETTI[RND.nextInt(CONFETTI.length)], 2 + RND.nextInt(3)});
            }
        }
        for (int i = confetti.size() - 1; i >= 0; i--) {
            float[] c = confetti.get(i);
            c[3] = Math.min(60, c[3] + 120 * dt);
            c[2] *= (1 - 1.2f * dt);
            c[5] += c[4] * dt;
            c[0] += (c[2] + (float) Math.sin(c[5]) * 12) * dt;
            c[1] += c[3] * dt;
            if (c[1] > h + 10) confetti.remove(i);
        }

        g.pose().pushPose();
        g.pose().translate(0, 0, 400);

        // banner in / out
        float in = ease(t / 520f);
        float out = t > DURATION - 700 ? ease((t - (DURATION - 700)) / 700f) : 0;
        float slide = (1 - in) * -w + out * w;
        int bw = Math.min(w - 40, 300), bh = 36;
        int bx = (w - bw) / 2 + (int) slide, by = (int) (h * 0.26f);

        // rotating light rays behind the banner
        float rayA = Math.min(1, t / 500f) * (1 - out);
        if (rayA > 0.02f) {
            g.pose().pushPose();
            g.pose().translate(w / 2f + slide, by + bh / 2f, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees((t / 40f) % 360));
            for (int i = 0; i < 12; i++) {
                g.pose().mulPose(Axis.ZP.rotationDegrees(30));
                int a = (int) (rayA * (i % 2 == 0 ? 0x30 : 0x18));
                g.fill(0, -3, (int) (w * 0.6f), 3, (a << 24) | 0xF3C04E);
            }
            g.pose().popPose();
        }

        // slanted slabs: orange shadow + cream face (Persona flavour)
        skew(g, bx + 5, by + 4, bw, bh, 10, ORANGE);
        skew(g, bx, by, bw, bh, 10, CREAM);
        g.pose().pushPose();
        float pop = 1f + 0.08f * (float) Math.max(0, Math.sin(Math.min(1, t / 600f) * Math.PI));
        g.pose().translate(bx + bw / 2f, by + bh / 2f, 0);
        g.pose().scale(2.2f * pop, 2.2f * pop, 1f);
        g.pose().last().pose().mul(new org.joml.Matrix4f().m10(-0.25f)); // lean forward
        g.drawString(font, title, -font.width(title) / 2, -4, INK, false);
        g.pose().popPose();
        // sub line chip
        int sw = font.width(sub) + 16, sx = (w - sw) / 2 + (int) slide, sy = by + bh + 8;
        g.fill(sx, sy, sx + sw, sy + 14, 0xE61A1A1D);
        g.fill(sx, sy, sx + 3, sy + 14, ORANGE);
        g.drawString(font, sub, sx + 9, sy + 3, CREAM, false);

        // Bit dancing on the banner
        float hop = (float) Math.abs(Math.sin(t / 180.0)) * 8;
        g.pose().pushPose();
        g.pose().translate(bx + bw - 40, by - 20 - hop, 0);
        g.pose().scale(2f, 2f, 1f);
        Arcade.tinyBit(g, 0, 0, now, (t / 180) % 2 == 0, (t / 360) % 2 == 0);
        g.pose().popPose();

        // confetti on top
        for (float[] c : confetti) {
            float flip = (float) Math.cos(c[5] * 1.7);
            int s = (int) c[7];
            int fw = Math.max(1, Math.round(s * Math.abs(flip)));
            int col = (int) c[6];
            if (flip < 0) col = (col & 0xFF000000) | ((col & 0xFEFEFE) >> 1); // back side darker
            g.fill((int) c[0], (int) c[1], (int) c[0] + fw, (int) c[1] + s, col);
        }
        g.pose().popPose();
    }

    private static void skew(GuiGraphics g, int x, int y, int w, int h, int slant, int color) {
        for (int row = 0; row < h; row++) {
            int off = Math.round(slant * (1f - (row + 0.5f) / h));
            g.fill(x + off, y + row, x + off + w, y + row + 1, color);
        }
    }
}
