package com.xlrw.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiConsumer;

/** World rendering differences between 1.21.1 and 1.21.11 (render types, events, block models, line boxes). */
public final class RenderCompat {
    private RenderCompat() {}

    private static final RandomSource RAND = RandomSource.create();
    private static final Direction[] FACES_AND_NULL = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null};

    /** Calls {@code r} once per frame after the world (translucent pass) with the view pose and buffers. */
    public static void registerWorldRender(BiConsumer<PoseStack, MultiBufferSource> r) {
        //? if >=1.21.11 {
        /*net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_MAIN.register(ctx -> {
            if (ctx.matrices() != null && ctx.consumers() != null) r.accept(ctx.matrices(), ctx.consumers());
        });
        *///?} else {
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> r.accept(ctx.matrixStack(), ctx.consumers()));
        //?}
    }

    public static Vec3 cameraPos() {
        //? if >=1.21.11 {
        /*return Minecraft.getInstance().gameRenderer.getMainCamera().position();
        *///?} else {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        //?}
    }

    public static VertexConsumer lines(MultiBufferSource c) {
        //? if >=1.21.11 {
        /*return c.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.lines());
        *///?} else {
        return c.getBuffer(net.minecraft.client.renderer.RenderType.lines());
        //?}
    }

    public static VertexConsumer quads(MultiBufferSource c) {
        //? if >=1.21.11 {
        /*return c.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads());
        *///?} else {
        return c.getBuffer(net.minecraft.client.renderer.RenderType.debugQuads());
        //?}
    }

    public static VertexConsumer translucentBlocks(MultiBufferSource c) {
        //? if >=1.21.11 {
        /*return c.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.translucentMovingBlock());
        *///?} else {
        return c.getBuffer(net.minecraft.client.renderer.Sheets.translucentCullBlockSheet());
        //?}
    }

    public static void endLines(MultiBufferSource c) {
        if (!(c instanceof MultiBufferSource.BufferSource bs)) return;
        //? if >=1.21.11 {
        /*bs.endBatch(net.minecraft.client.renderer.rendertype.RenderTypes.lines());
        *///?} else {
        bs.endBatch(net.minecraft.client.renderer.RenderType.lines());
        //?}
    }

    public static void endQuads(MultiBufferSource c) {
        if (!(c instanceof MultiBufferSource.BufferSource bs)) return;
        //? if >=1.21.11 {
        /*bs.endBatch(net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads());
        *///?} else {
        bs.endBatch(net.minecraft.client.renderer.RenderType.debugQuads());
        //?}
    }

    public static void endTranslucent(MultiBufferSource c) {
        if (!(c instanceof MultiBufferSource.BufferSource bs)) return;
        //? if >=1.21.11 {
        /*bs.endBatch(net.minecraft.client.renderer.rendertype.RenderTypes.translucentMovingBlock());
        *///?} else {
        bs.endBatch(net.minecraft.client.renderer.Sheets.translucentCullBlockSheet());
        //?}
    }

    /** Clears the depth buffer so what follows is drawn on top (1.21.11: not available, no-op). */
    public static void clearDepth() {
        //? if <1.21.11 {
        com.mojang.blaze3d.systems.RenderSystem.clear(256, Minecraft.ON_OSX);
        //?}
    }

    /** One line segment vertex pair (the buffer must come from {@link #lines}). */
    public static void line(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a) {
        PoseStack.Pose p = ps.last();
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6) return;
        nx /= len; ny /= len; nz /= len;
        //? if >=1.21.11 {
        /*vc.addVertex(p, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a).setNormal(p, nx, ny, nz).setLineWidth(2f);
        vc.addVertex(p, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(p, nx, ny, nz).setLineWidth(2f);
        *///?} else {
        vc.addVertex(p, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a).setNormal(p, nx, ny, nz);
        vc.addVertex(p, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(p, nx, ny, nz);
        //?}
    }

    public static void lineBox(PoseStack ps, VertexConsumer vc, AABB bb, float r, float g, float b, float a) {
        lineBox(ps, vc, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, r, g, b, a);
    }

    public static void lineBox(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1,
                               float r, float g, float b, float a) {
        // bottom
        line(ps, vc, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(ps, vc, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(ps, vc, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(ps, vc, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // top
        line(ps, vc, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(ps, vc, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(ps, vc, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(ps, vc, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // pillars
        line(ps, vc, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(ps, vc, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(ps, vc, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(ps, vc, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    /** Puts the real block model's quads into {@code vc}, tinted. Returns false if the block has no model quads. */
    public static boolean blockModel(PoseStack.Pose pose, VertexConsumer vc, BlockState state, float r, float g, float b, float a) {
        Minecraft mc = Minecraft.getInstance();
        boolean any = false;
        //? if >=1.21.11 {
        /*RAND.setSeed(42L);
        for (net.minecraft.client.renderer.block.model.BlockModelPart part : mc.getBlockRenderer().getBlockModel(state).collectParts(RAND)) {
            for (Direction d : FACES_AND_NULL) {
                for (BakedQuad q : part.getQuads(d)) {
                    vc.putBulkData(pose, q, r, g, b, a, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    any = true;
                }
            }
        }
        *///?} else {
        net.minecraft.client.resources.model.BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        for (Direction d : FACES_AND_NULL) {
            RAND.setSeed(42L);
            for (BakedQuad q : model.getQuads(state, d, RAND)) {
                vc.putBulkData(pose, q, r, g, b, a, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                any = true;
            }
        }
        //?}
        return any;
    }

    public static net.minecraft.client.renderer.texture.TextureAtlasSprite particleIcon(BlockState state) {
        //? if >=1.21.11 {
        /*return Minecraft.getInstance().getBlockRenderer().getBlockModel(state).particleIcon();
        *///?} else {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state).getParticleIcon();
        //?}
    }
}
