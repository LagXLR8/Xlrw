package com.xlrw.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

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
                    if (n == null || !n.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) mask |= 1 << d.ordinal();
                }
                boolean solid = st.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
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
