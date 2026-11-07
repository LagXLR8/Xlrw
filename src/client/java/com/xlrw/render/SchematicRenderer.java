package com.xlrw.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xlrw.builder.BuilderController;
import com.xlrw.builder.BuildRules;
import com.xlrw.builder.PlacementCandidate;
import com.xlrw.config.BuilderConfig;
import com.xlrw.gui.SchematicEditorScreen;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * World overlay: holographic ghost blocks (real block models, translucent, cyan hologram tint with a travelling
 * shimmer), red "wrong block" cages, a pulsing orange target, the active layer frame, and the move/rotate gizmo of
 * the placement editor.
 */
public class SchematicRenderer {

    private static final RandomSource RAND = RandomSource.create();
    private static final Direction[] FACES_AND_NULL = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
    private static final int MODEL_RANGE = 48;      // full holographic block models within this distance
    private static final int OUTLINE_RANGE = 48;    // beyond: thin outlines only
    private static final int MODEL_CAP = 6000;      // safety cap per frame (nearest first)

    public static void init() {
        com.xlrw.compat.RenderCompat.registerWorldRender(SchematicRenderer::render);
    }

    private static void render(PoseStack ps, MultiBufferSource consumers) {
        BuilderConfig config = BuilderConfig.getInstance();
        Schematic schematic = SchematicManager.getInstance().getActiveSchematic();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (schematic == null || level == null || mc.player == null) return;
        SchematicEditorScreen editor = SchematicEditorScreen.ACTIVE;
        if (!config.renderHologram && editor == null) return;

        Vec3 cam = com.xlrw.compat.RenderCompat.cameraPos();
        long now = System.currentTimeMillis();

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        // 1. Holographic ghost blocks (+ wrong-block cages)
        renderGhosts(ps, consumers, level, schematic, config, cam, now, editor != null);
        com.xlrw.compat.RenderCompat.endTranslucent(consumers);
        com.xlrw.compat.RenderCompat.endQuads(consumers);

        VertexConsumer lines = com.xlrw.compat.RenderCompat.lines(consumers);
        // 2. Bounds with animated corner brackets, and the active layer frame
        Bounds b = Bounds.of(schematic);
        if (b != null && (config.renderBoundingBox || editor != null)) {
            renderBounds(ps, lines, b, now, editor != null);
            if (config.layerMode && editor == null) renderLayerFrame(ps, lines, b, schematic.getMinY() + config.currentLayer, now);
        }
        // 3. Next block: pulsing orange cage
        PlacementCandidate candidate = BuilderController.getInstance().getCurrentCandidate();
        if (candidate != null && config.renderTargetBox && editor == null) {
            float p = 0.55f + 0.45f * (float) Math.sin(now * 0.012);
            BlockPos t = candidate.targetPos;
            com.xlrw.compat.RenderCompat.lineBox(ps, lines, new AABB(t).inflate(0.02 + 0.03 * p), 0.95f, 0.44f, 0.12f, 0.6f + 0.4f * p);
        }
        // 4. Supply chests
        for (BlockPos chestPos : config.getChestPositions()) {
            com.xlrw.compat.RenderCompat.lineBox(ps, lines, new AABB(chestPos).inflate(0.01), 0.91f, 0.85f, 0.79f, 0.8f);
        }
        // 4b. Dry run markers: stuck blocks (red), scaffolding towers (yellow), standing spots (cyan)
        com.xlrw.builder.DryRun.Report dry = com.xlrw.builder.DryRun.get();
        if (dry != null && config.renderDryRun && com.xlrw.account.Plus.on() && editor == null) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(now * 0.008);
            for (BlockPos p : dry.problems.keySet()) {
                if (p.distToCenterSqr(cam) > 96 * 96) continue;
                com.xlrw.compat.RenderCompat.lineBox(ps, lines, new AABB(p).inflate(0.04), 1.0f, 0.15f, 0.15f, pulse);
            }
            for (BlockPos p : dry.floatingWarnings) {
                if (p.distToCenterSqr(cam) > 96 * 96) continue;
                com.xlrw.compat.RenderCompat.lineBox(ps, lines, new AABB(p).inflate(0.02), 1.0f, 0.6f, 0.1f, 0.8f);
            }
            for (com.xlrw.builder.DryRun.Tower t : dry.towers) {
                if (t.base.distToCenterSqr(cam) > 128 * 128) continue;
                com.xlrw.compat.RenderCompat.lineBox(ps, lines, new AABB(t.base.getX() + 0.15, t.base.getY(), t.base.getZ() + 0.15,
                        t.base.getX() + 0.85, t.topY, t.base.getZ() + 0.85), 1.0f, 0.85f, 0.1f, 0.9f);
            }
            for (BlockPos p : dry.stations) {
                if (p.distToCenterSqr(cam) > 64 * 64) continue;
                com.xlrw.compat.RenderCompat.lineBox(ps, lines, new AABB(p.getX() + 0.35, p.getY(), p.getZ() + 0.35,
                        p.getX() + 0.65, p.getY() + 0.1, p.getZ() + 0.65), 0.2f, 0.85f, 1.0f, 0.7f);
            }
        }
        com.xlrw.compat.RenderCompat.endLines(consumers);

        // 5. Editor gizmo, always on top
        if (editor != null && b != null) {
            com.xlrw.compat.RenderCompat.clearDepth(); // the gizmo must never hide inside blocks
            editor.renderGizmo(ps, consumers, b, now);
            com.xlrw.compat.RenderCompat.endQuads(consumers);
            com.xlrw.compat.RenderCompat.endLines(consumers);
        }
        ps.popPose();
    }

    // ------------------------------------------------------------------------------------------------
    // Ghost blocks
    // ------------------------------------------------------------------------------------------------

    private static void renderGhosts(PoseStack ps, MultiBufferSource consumers, ClientLevel level, Schematic schematic,
                                     BuilderConfig config, Vec3 cam, long now, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        // Never hold two buffers at once: asking a BufferSource for another (non-fixed) type ends the previous batch.
        // Pass 1 draws the cubes, pass 2 the translucent models, pass 3 cubes for models without quads.
        VertexConsumer quads = com.xlrw.compat.RenderCompat.quads(consumers);
        List<Object[]> modelJobs = new ArrayList<>();
        int minY = schematic.getMinY();
        int models = 0;
        double phase = (now / 90.0) % 40.0;   // travelling shimmer band
        for (Map.Entry<BlockPos, BlockState> e : schematic.getWorldBlocks().entrySet()) {
            BlockPos pos = e.getKey();
            BlockState expected = e.getValue();
            double d2 = pos.distToCenterSqr(cam);
            if (d2 > OUTLINE_RANGE * OUTLINE_RANGE) continue;
            if (!editing && (config.renderOnlyCurrentLayer || config.layerMode)) {
                int layer = pos.getY() - minY;
                if (layer < config.currentLayer || layer >= config.currentLayer + config.layerRange) continue;
            }
            BlockState actual = level.getBlockState(pos);
            boolean liquid = expected.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
            boolean missing = liquid ? BuildRules.fluidStatus(actual, expected) == BuildRules.FluidStatus.PLACE_SOURCE
                    : BuildRules.buildStatus(actual, expected) == BuildRules.BuildStatus.MISSING;
            boolean wrong = !liquid && !missing && !actual.isAir() && actual.getBlock() != expected.getBlock();
            if (!missing && !wrong && !editing) continue;

            if (wrong) {
                // red glass cage around a block that has to go
                cube(ps, quads, pos.getX() - 0.01, pos.getY() - 0.01, pos.getZ() - 0.01, 1.02, 1.0f, 0.23f, 0.2f, 0.22f);
                continue;
            }
            if (BuildRules.isLiquid(expected) || expected.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                boolean lava = expected.is(Blocks.LAVA);
                cube(ps, quads, pos.getX() + 0.02, pos.getY() + 0.02, pos.getZ() + 0.02, 0.96,
                        lava ? 1f : 0.3f, lava ? 0.45f : 0.6f, lava ? 0.1f : 1f, 0.28f);
                continue;
            }
            // shimmer: a soft bright band sweeping diagonally over the hologram
            double band = Math.abs(((pos.getX() + pos.getZ() + pos.getY() * 0.5) % 40.0 + 40.0) % 40.0 - phase);
            float glow = (float) Math.max(0, 1.0 - band / 3.0);
            float a = editing ? 0.55f : 0.30f + 0.35f * glow;
            modelJobs.add(new Object[]{expected, pos, new float[]{0.55f + 0.45f * glow, 0.85f + 0.15f * glow, 1.0f, a}, d2});
            models++;
        }
        com.xlrw.compat.RenderCompat.endQuads(consumers);
        if (modelJobs.isEmpty()) return;
        // nearest MODEL_CAP blocks only, then far -> near so the translucent layers blend correctly
        modelJobs.sort((x, y) -> Double.compare((double) x[3], (double) y[3]));
        if (modelJobs.size() > MODEL_CAP) modelJobs = new ArrayList<>(modelJobs.subList(0, MODEL_CAP));
        java.util.Collections.reverse(modelJobs);
        VertexConsumer holo = com.xlrw.compat.RenderCompat.translucentBlocks(consumers);
        List<Object[]> noModel = new ArrayList<>();
        for (Object[] j : modelJobs) {
            float[] c = (float[]) j[2];
            if (!ghostModel(ps, holo, mc, (BlockState) j[0], (BlockPos) j[1], c[0], c[1], c[2], c[3])) noModel.add(j);
        }
        com.xlrw.compat.RenderCompat.endTranslucent(consumers);
        if (noModel.isEmpty()) return;
        // block entities / special renderers (chests, beds...): a hologram cube instead
        quads = com.xlrw.compat.RenderCompat.quads(consumers);
        for (Object[] j : noModel) {
            BlockPos pos = (BlockPos) j[1];
            float[] c = (float[]) j[2];
            cube(ps, quads, pos.getX() + 0.1, pos.getY() + 0.1, pos.getZ() + 0.1, 0.8, c[0], c[1], c[2], c[3] * 0.8f);
        }
        com.xlrw.compat.RenderCompat.endQuads(consumers);
    }

    /** The real block model, slightly shrunk so it never z-fights with neighbours, tinted and translucent. */
    private static boolean ghostModel(PoseStack ps, VertexConsumer vc, Minecraft mc, BlockState state, BlockPos pos,
                                   float r, float g, float b, float a) {
        ps.pushPose();
        ps.translate(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        ps.scale(0.985f, 0.985f, 0.985f);
        ps.translate(-0.5, -0.5, -0.5);
        boolean any = com.xlrw.compat.RenderCompat.blockModel(ps.last(), vc, state, r, g, b, a);
        ps.popPose();
        return any;
    }

    /** Axis-aligned cube made of quads (POSITION_COLOR). */
    public static void cube(PoseStack ps, VertexConsumer vc, double x, double y, double z, double s, float r, float g, float b, float a) {
        box(ps, vc, x, y, z, x + s, y + s, z + s, r, g, b, a);
    }

    public static void box(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1,
                           float r, float g, float b, float a) {
        PoseStack.Pose p = ps.last();
        float ax = (float) x0, ay = (float) y0, az = (float) z0, bx = (float) x1, by = (float) y1, bz = (float) z1;
        // down
        v(vc, p, ax, ay, az, r, g, b, a); v(vc, p, bx, ay, az, r, g, b, a); v(vc, p, bx, ay, bz, r, g, b, a); v(vc, p, ax, ay, bz, r, g, b, a);
        // up
        v(vc, p, ax, by, az, r, g, b, a); v(vc, p, ax, by, bz, r, g, b, a); v(vc, p, bx, by, bz, r, g, b, a); v(vc, p, bx, by, az, r, g, b, a);
        // north
        v(vc, p, ax, ay, az, r, g, b, a); v(vc, p, ax, by, az, r, g, b, a); v(vc, p, bx, by, az, r, g, b, a); v(vc, p, bx, ay, az, r, g, b, a);
        // south
        v(vc, p, ax, ay, bz, r, g, b, a); v(vc, p, bx, ay, bz, r, g, b, a); v(vc, p, bx, by, bz, r, g, b, a); v(vc, p, ax, by, bz, r, g, b, a);
        // west
        v(vc, p, ax, ay, az, r, g, b, a); v(vc, p, ax, ay, bz, r, g, b, a); v(vc, p, ax, by, bz, r, g, b, a); v(vc, p, ax, by, az, r, g, b, a);
        // east
        v(vc, p, bx, ay, az, r, g, b, a); v(vc, p, bx, by, az, r, g, b, a); v(vc, p, bx, by, bz, r, g, b, a); v(vc, p, bx, ay, bz, r, g, b, a);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float r, float g, float b, float a) {
        vc.addVertex(p, x, y, z).setColor(r, g, b, a);
    }

    // ------------------------------------------------------------------------------------------------
    // Frames
    // ------------------------------------------------------------------------------------------------

    private static void renderBounds(PoseStack ps, VertexConsumer lines, Bounds b, long now, boolean editing) {
        float a = editing ? 0.9f : 0.45f;
        com.xlrw.compat.RenderCompat.lineBox(ps, lines, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, 0.29f, 0.38f, 0.5f, a);
        // orange corner brackets breathing in and out
        double len = 1.2 + 0.4 * Math.sin(now / 300.0);
        double[] xs = {b.minX, b.maxX}, ys = {b.minY, b.maxY}, zs = {b.minZ, b.maxZ};
        for (double x : xs) for (double y : ys) for (double z : zs) {
            double sx = x == b.minX ? 1 : -1, sy = y == b.minY ? 1 : -1, sz = z == b.minZ ? 1 : -1;
            line(ps, lines, x, y, z, x + sx * len, y, z, 0.95f, 0.44f, 0.12f, 1f);
            line(ps, lines, x, y, z, x, y + sy * len, z, 0.95f, 0.44f, 0.12f, 1f);
            line(ps, lines, x, y, z, x, y, z + sz * len, 0.95f, 0.44f, 0.12f, 1f);
        }
    }

    private static void renderLayerFrame(PoseStack ps, VertexConsumer lines, Bounds b, int y, long now) {
        double yy = y + 0.01;
        com.xlrw.compat.RenderCompat.lineBox(ps, lines, b.minX - 0.05, yy, b.minZ - 0.05, b.maxX + 0.05, yy + 1, b.maxZ + 0.05, 0.95f, 0.44f, 0.12f, 0.55f);
        // a dash running around the layer
        double per = 2 * ((b.maxX - b.minX) + (b.maxZ - b.minZ));
        double t = (now / 40.0) % per;
        double[] p = perimeterPoint(b, t), q = perimeterPoint(b, (t + 2.5) % per);
        line(ps, lines, p[0], yy, p[1], q[0], yy, q[1], 0.91f, 0.85f, 0.79f, 1f);
    }

    private static double[] perimeterPoint(Bounds b, double t) {
        double w = b.maxX - b.minX, d = b.maxZ - b.minZ;
        if (t < w) return new double[]{b.minX + t, b.minZ};
        t -= w;
        if (t < d) return new double[]{b.maxX, b.minZ + t};
        t -= d;
        if (t < w) return new double[]{b.maxX - t, b.maxZ};
        t -= w;
        return new double[]{b.minX, b.maxZ - Math.min(t, d)};
    }

    public static void line(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a) {
        com.xlrw.compat.RenderCompat.line(ps, vc, x0, y0, z0, x1, y1, z1, r, g, b, a);
    }

    /** World-space bounding box of the schematic (cached per anchor / rotation). */
    public static final class Bounds {
        public final double minX, minY, minZ, maxX, maxY, maxZ;
        private static Bounds cached;
        private static Object cacheKey;

        private Bounds(double a, double b, double c, double d, double e, double f) {
            minX = a; minY = b; minZ = c; maxX = d; maxY = e; maxZ = f;
        }

        public Vec3 center() {
            return new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
        }

        public static Bounds of(Schematic s) {
            if (s == null) return null;
            Object key = java.util.List.of(s, s.getAnchor(), s.getRotation());
            if (key.equals(cacheKey)) return cached;
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (BlockPos p : s.getWorldBlocks().keySet()) {
                x0 = Math.min(x0, p.getX()); z0 = Math.min(z0, p.getZ());
                x1 = Math.max(x1, p.getX()); z1 = Math.max(z1, p.getZ());
            }
            if (x0 == Integer.MAX_VALUE) return null;
            cached = new Bounds(x0, s.getMinY(), z0, x1 + 1, s.getMaxY() + 1, z1 + 1);
            cacheKey = key;
            return cached;
        }
    }
}
