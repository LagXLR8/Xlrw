package com.xlrw.gui;

//? if <1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import org.joml.Matrix4f;
//?}
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turntable 3D model of the active schematic for the control deck. Only faces that can be seen are drawn; built blocks
 * show their real colours, unbuilt ones a dim blueprint tint and the layer in progress glows orange.
 */
final class HoloPreview {

    private record Cell(float x, float y, float z, BlockState state, int faces, boolean built, int worldY) {}

    private static final Direction[] FACES_AND_NULL = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null};
    private static final int CAP = 30000;
    private final RandomSource rand = RandomSource.create();

    private List<Cell> cells = new ArrayList<>();
    private Object key;
    private long builtAt;
    private float sx, sy, sz, diag;
    private int minWorldY;

    float yaw = 225f, pitch = 26f, zoom = 1f;
    boolean dragging;
    long lastTouch;
    private long lastNanos = System.nanoTime();

    void touch() {
        lastTouch = System.currentTimeMillis();
    }

    private void refresh(Schematic s, Level level) {
        Object k = List.of(s, s.getAnchor(), s.getRotation());
        long now = System.currentTimeMillis();
        if (k.equals(key) && now - builtAt < 1500) return;
        Map<BlockPos, BlockState> wb = s.getWorldBlocks();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : wb.keySet()) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
        }
        List<Cell> out = new ArrayList<>();
        if (x0 != Integer.MAX_VALUE) {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (Map.Entry<BlockPos, BlockState> e : wb.entrySet()) {
                BlockState st = e.getValue();
                if (st.isAir() || st.getRenderShape() != RenderShape.MODEL) continue;
                BlockPos p = e.getKey();
                int mask = 0;
                for (Direction d : Direction.values()) {
                    m.setWithOffset(p, d);
                    BlockState n = wb.get(m);
                    if (n == null || !solidRender(n)) mask |= 1 << d.ordinal();
                }
                boolean solid = solidRender(st);
                if (mask == 0 && solid) continue; // buried
                boolean built = level != null && level.getBlockState(p).getBlock() == st.getBlock();
                out.add(new Cell(p.getX() - x0, p.getY() - y0, p.getZ() - z0, st, solid ? mask : 0x3F, built, p.getY()));
                if (out.size() >= CAP) break;
            }
            sx = x1 - x0 + 1; sy = y1 - y0 + 1; sz = z1 - z0 + 1;
            diag = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            minWorldY = y0;
        }
        cells = out;
        key = k;
        builtAt = now;
    }

    private static boolean solidRender(BlockState st) {
        //? if >=1.21.11 {
        /*return st.isSolidRender();
        *///?} else {
        return st.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        //?}
    }

    /**
     * Flat fallback (1.21.11, no custom 3D in GUIs): the same turntable, each visible block drawn as a small square
     * in its map colour, painted back to front.
     */
    private void renderFlat(GuiGraphics g, int x, int y, int w, int h, int layerY, long now) {
        float sc = Math.min(w, h) * 0.8f / diag * zoom;
        double cyw = Math.cos(Math.toRadians(yaw)), syw = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        int n = Math.min(cells.size(), 12000);
        float[] px = new float[n], py = new float[n], depth = new float[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            double X = c.x() - sx / 2 + 0.5, Y = c.y() - sy / 2 + 0.5, Z = c.z() - sz / 2 + 0.5;
            double rx = X * cyw - Z * syw, rz = X * syw + Z * cyw;
            double ry = Y * cp - rz * sp, rz2 = Y * sp + rz * cp;
            px[i] = (float) (x + w / 2f + rx * sc);
            py[i] = (float) (y + h / 2f + h * 0.03f - ry * sc);
            depth[i] = (float) rz2;
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(depth[a], depth[b]));
        int half = Math.max(1, Math.round(sc * 0.55f));
        float pulse = 0.75f + 0.25f * (float) Math.sin(now / 220.0);
        for (int k = 0; k < n; k++) {
            int i = order[k];
            Cell c = cells.get(i);
            int col;
            if (c.built()) col = 0xFF000000 | c.state().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            else if (c.worldY() == layerY) col = 0xFF000000 | ((int) (255 * Math.min(1f, pulse + 0.25f)) << 16) | ((int) (140 * pulse) << 8) | (int) (64 * pulse);
            else col = 0xFF5C708F;
            int ix = Math.round(px[i]), iy = Math.round(py[i]);
            g.fill(ix - half, iy - half, ix + half, iy + half, col);
        }
    }

    //? if >=1.21.11 {
    /*/^* Draws the turntable into a PiP texture. {@code ps}: origin at the box centre, 1 unit = 1 GUI pixel, y up. ^/
    void draw3D(com.mojang.blaze3d.vertex.PoseStack ps, net.minecraft.client.renderer.MultiBufferSource.BufferSource bs,
                int w, int h, int layerY, long now) {
        if (cells.isEmpty() || diag <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        ps.pushPose();
        ps.translate(0f, -h * 0.03f, 0f);
        float sc = Math.min(w, h) * 0.8f / diag * zoom;
        ps.scale(sc, sc, -sc);
        ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
        ps.translate(-sx / 2f, -sy / 2f, -sz / 2f);
        com.mojang.blaze3d.vertex.VertexConsumer vc = bs.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.cutoutMovingBlock());
        var br = mc.getBlockRenderer();
        float pulse = 0.75f + 0.25f * (float) Math.sin(now / 220.0);
        for (Cell c : cells) {
            float tr, tg, tb;
            if (c.built) { tr = 1f; tg = 1f; tb = 1f; }
            else if (c.worldY == layerY) { tr = 1f * pulse + 0.25f; tg = 0.55f * pulse; tb = 0.25f * pulse; }
            else { tr = 0.36f; tg = 0.44f; tb = 0.58f; }
            ps.pushPose();
            ps.translate(c.x, c.y, c.z);
            com.mojang.blaze3d.vertex.PoseStack.Pose pose = ps.last();
            rand.setSeed(42L);
            List<net.minecraft.client.renderer.block.model.BlockModelPart> parts;
            try {
                parts = br.getBlockModel(c.state).collectParts(rand);
            } catch (Throwable t) {
                ps.popPose();
                continue;
            }
            for (net.minecraft.client.renderer.block.model.BlockModelPart part : parts) {
                for (Direction d : FACES_AND_NULL) {
                    if (d != null && (c.faces & (1 << d.ordinal())) == 0) continue;
                    for (net.minecraft.client.renderer.block.model.BakedQuad q : part.getQuads(d)) {
                        float shade = q.shade() ? faceShade(q.direction()) : 1f;
                        float r = tr * shade, gg = tg * shade, b = tb * shade;
                        if (q.isTinted()) {
                            int col = tint(mc, c.state, q.tintIndex());
                            r *= ((col >> 16) & 255) / 255f;
                            gg *= ((col >> 8) & 255) / 255f;
                            b *= (col & 255) / 255f;
                        }
                        vc.putBulkData(pose, q, Math.min(1f, r), Math.min(1f, gg), Math.min(1f, b), 1f,
                                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                    }
                }
            }
            ps.popPose();
        }
        bs.endBatch(net.minecraft.client.renderer.rendertype.RenderTypes.cutoutMovingBlock());

        // current layer plane
        if (layerY >= minWorldY && layerY < minWorldY + sy) {
            float py = layerY - minWorldY + 0.02f;
            com.mojang.blaze3d.vertex.VertexConsumer q = bs.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads());
            org.joml.Matrix4f m = ps.last().pose();
            float a0 = -0.4f, ax = sx + 0.4f, az = sz + 0.4f;
            int col = 0x40F3701E;
            q.addVertex(m, a0, py, a0).setColor(col);
            q.addVertex(m, a0, py, az).setColor(col);
            q.addVertex(m, ax, py, az).setColor(col);
            q.addVertex(m, ax, py, a0).setColor(col);
            q.addVertex(m, ax, py, a0).setColor(col);
            q.addVertex(m, ax, py, az).setColor(col);
            q.addVertex(m, a0, py, az).setColor(col);
            q.addVertex(m, a0, py, a0).setColor(col);
            bs.endBatch(net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads());
        }
        ps.popPose();
    }
    *///?}

    private static float faceShade(Direction d) {
        if (d == null) return 0.9f;
        return switch (d) {
            case UP -> 1f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            default -> 0.62f;
        };
    }

    /** Draw into the box (x, y, w, h) of the current GUI pose. */
    void render(GuiGraphics g, int x, int y, int w, int h, Schematic s, int layerY, long now) {
        Minecraft mc = Minecraft.getInstance();
        refresh(s, mc.level);
        long n = System.nanoTime();
        float dt = Math.min(0.1f, (n - lastNanos) / 1e9f);
        lastNanos = n;
        if (!dragging && now - lastTouch > 2500) yaw = (yaw + dt * 14f) % 360f;
        if (cells.isEmpty() || diag <= 0) return;

        //? if >=1.21.11 {
        /*// Real 3D through a picture-in-picture renderer (custom 3D is not allowed directly in 1.21.11 GUIs).
        // The PiP box is given in screen coordinates, so map the panel-local box through the current GUI transform.
        org.joml.Vector2f a0 = g.pose().transformPosition(x, y, new org.joml.Vector2f());
        org.joml.Vector2f a1 = g.pose().transformPosition(x + w, y + h, new org.joml.Vector2f());
        int sx0 = Math.round(Math.min(a0.x, a1.x)), sy0 = Math.round(Math.min(a0.y, a1.y));
        int sx1 = Math.round(Math.max(a0.x, a1.x)), sy1 = Math.round(Math.max(a0.y, a1.y));
        if (sx1 - sx0 < 4 || sy1 - sy0 < 4) return;
        g.guiRenderState.submitPicturesInPictureState(new HoloPip.State(this, layerY, now, sx0, sy0, sx1, sy1));
        *///?} else {
        g.flush();
        FogRenderer.setupNoFog();
        RenderSystem.clear(256, Minecraft.ON_OSX); // depth only: the HUD below may have written depth here
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(x + w / 2f, y + h / 2f + h * 0.03f, 260f);
        float sc = Math.min(w, h) * 0.8f / diag * zoom;
        ps.scale(sc, -sc, sc);
        ps.mulPose(Axis.XP.rotationDegrees(pitch));
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        ps.translate(-sx / 2f, -sy / 2f, -sz / 2f);

        MultiBufferSource.BufferSource bs = g.bufferSource();
        VertexConsumer vc = bs.getBuffer(RenderType.cutout());
        var br = mc.getBlockRenderer();
        float pulse = 0.75f + 0.25f * (float) Math.sin(now / 220.0);
        for (Cell c : cells) {
            BakedModel model = br.getBlockModel(c.state);
            float tr, tg, tb;
            if (c.built) { tr = 1f; tg = 1f; tb = 1f; }
            else if (c.worldY == layerY) { tr = 1f * pulse + 0.25f; tg = 0.55f * pulse; tb = 0.25f * pulse; }
            else { tr = 0.36f; tg = 0.44f; tb = 0.58f; }
            ps.pushPose();
            ps.translate(c.x, c.y, c.z);
            PoseStack.Pose pose = ps.last();
            for (Direction d : FACES_AND_NULL) {
                if (d != null && (c.faces & (1 << d.ordinal())) == 0) continue;
                rand.setSeed(42L);
                List<BakedQuad> quads;
                try {
                    quads = model.getQuads(c.state, d, rand);
                } catch (Throwable t) {
                    continue;
                }
                for (BakedQuad q : quads) {
                    float shade = q.isShade() ? faceShade(q.getDirection()) : 1f;
                    float r = tr * shade, gg = tg * shade, b = tb * shade;
                    if (q.isTinted()) {
                        int col = tint(mc, c.state, q.getTintIndex());
                        r *= ((col >> 16) & 255) / 255f;
                        gg *= ((col >> 8) & 255) / 255f;
                        b *= (col & 255) / 255f;
                    }
                    vc.putBulkData(pose, q, Math.min(1f, r), Math.min(1f, gg), Math.min(1f, b), 1f, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                }
            }
            ps.popPose();
        }
        bs.endBatch(RenderType.cutout());

        // current layer plane
        if (layerY >= minWorldY && layerY < minWorldY + sy) {
            float py = layerY - minWorldY + 0.02f;
            VertexConsumer q = bs.getBuffer(RenderType.gui());
            Matrix4f m = ps.last().pose();
            float a0 = -0.4f, ax = sx + 0.4f, az = sz + 0.4f;
            int col = 0x40F3701E;
            // both windings so it shows from above and below
            q.addVertex(m, a0, py, a0).setColor(col);
            q.addVertex(m, a0, py, az).setColor(col);
            q.addVertex(m, ax, py, az).setColor(col);
            q.addVertex(m, ax, py, a0).setColor(col);
            q.addVertex(m, ax, py, a0).setColor(col);
            q.addVertex(m, ax, py, az).setColor(col);
            q.addVertex(m, a0, py, az).setColor(col);
            q.addVertex(m, a0, py, a0).setColor(col);
            bs.endBatch(RenderType.gui());
        }
        ps.popPose();
        //?}
    }

    private static int tint(Minecraft mc, BlockState st, int idx) {
        try {
            int c = mc.getBlockColors().getColor(st, null, null, idx);
            return c == -1 ? 0xFFFFFF : c;
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    String sizeLabel() {
        return (int) sx + "×" + (int) sy + "×" + (int) sz;
    }
}
