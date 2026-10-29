package com.xlrw.compat;

import net.minecraft.client.gui.GuiGraphics;

/**
 * GUI transform stack. 1.21.1 uses a 3D PoseStack, 1.21.11 a 2D Matrix3x2fStack (z is ignored there: draw order
 * decides what is on top).
 */
public final class GuiCompat {
    private GuiCompat() {}

    public static void push(GuiGraphics g) {
        //? if >=1.21.11 {
        /*g.pose().pushMatrix();
        *///?} else {
        g.pose().pushPose();
        //?}
    }

    public static void pop(GuiGraphics g) {
        //? if >=1.21.11 {
        /*g.pose().popMatrix();
        *///?} else {
        g.pose().popPose();
        //?}
    }

    public static void translate(GuiGraphics g, double x, double y, double z) {
        //? if >=1.21.11 {
        /*g.pose().translate((float) x, (float) y);
        *///?} else {
        g.pose().translate(x, y, z);
        //?}
    }

    public static void scale(GuiGraphics g, float x, float y, float z) {
        //? if >=1.21.11 {
        /*g.pose().scale(x, y);
        *///?} else {
        g.pose().scale(x, y, z);
        //?}
    }

    public static void rotateZ(GuiGraphics g, float degrees) {
        //? if >=1.21.11 {
        /*g.pose().rotate((float) Math.toRadians(degrees));
        *///?} else {
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(degrees));
        //?}
    }

    /** x += k * y (a slanted / leaning look). */
    public static void shearX(GuiGraphics g, float k) {
        //? if >=1.21.11 {
        /*g.pose().mul(new org.joml.Matrix3x2f(1f, 0f, k, 1f, 0f, 0f));
        *///?} else {
        g.pose().last().pose().mul(new org.joml.Matrix4f().m10(k));
        //?}
    }

    /** False on 1.21.11: the perspective-tilted panels need raw OpenGL that the new renderer no longer exposes. */
    public static final boolean SUPPORTS_3D_PANELS =
            //? if >=1.21.11 {
            /*false;
            *///?} else {
            true;
            //?}

    public static void flush(GuiGraphics g) {
        //? if <1.21.11 {
        g.flush();
        //?}
    }

    public static boolean keyDown(int key) {
        //? if >=1.21.11 {
        /*return com.mojang.blaze3d.platform.InputConstants.isKeyDown(net.minecraft.client.Minecraft.getInstance().getWindow(), key);
        *///?} else {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(net.minecraft.client.Minecraft.getInstance().getWindow().getWindow(), key);
        //?}
    }

    public static boolean keyMatches(net.minecraft.client.KeyMapping km, int key, int scan, int mods) {
        //? if >=1.21.11 {
        /*return km.matches(new net.minecraft.client.input.KeyEvent(key, scan, mods));
        *///?} else {
        return km.matches(key, scan);
        //?}
    }

    /** Draws an atlas sprite at (x, y) with size (w, h). */
    public static void sprite(GuiGraphics g, int x, int y, int w, int h, net.minecraft.client.renderer.texture.TextureAtlasSprite sp) {
        //? if >=1.21.11 {
        /*g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sp, x, y, w, h);
        *///?} else {
        g.blit(x, y, 0, w, h, sp);
        //?}
    }

    public static void sprite(GuiGraphics g, int x, int y, int w, int h, net.minecraft.client.renderer.texture.TextureAtlasSprite sp,
                              float r, float gr, float b, float a) {
        //? if >=1.21.11 {
        /*int col = ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (gr * 255) << 8) | (int) (b * 255);
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sp, x, y, w, h, col);
        *///?} else {
        g.blit(x, y, 0, w, h, sp, r, gr, b, a);
        //?}
    }

    /** Part (u, v, uw, vh) of a texture of size (tw, th) stretched over (x, y, w, h). */
    public static void blitTex(GuiGraphics g, net.minecraft.resources.ResourceLocation tex, int x, int y, int w, int h,
                               float u, float v, int uw, int vh, int tw, int th) {
        //? if >=1.21.11 {
        /*g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, uw, vh, tw, th);
        *///?} else {
        g.blit(tex, x, y, w, h, u, v, uw, vh, tw, th);
        //?}
    }

    public static net.minecraft.client.renderer.texture.DynamicTexture texture(com.mojang.blaze3d.platform.NativeImage img) {
        //? if >=1.21.11 {
        /*return new net.minecraft.client.renderer.texture.DynamicTexture(() -> "xlrw", img);
        *///?} else {
        return new net.minecraft.client.renderer.texture.DynamicTexture(img);
        //?}
    }

    /** Pixel in ABGR order (what NativeImage stores). */
    public static void setPixelABGR(com.mojang.blaze3d.platform.NativeImage img, int x, int y, int abgr) {
        //? if >=1.21.11 {
        /*img.setPixelABGR(x, y, abgr);
        *///?} else {
        img.setPixelRGBA(x, y, abgr);
        //?}
    }

    public static void openFile(java.io.File f) {
        //? if >=1.21.11 {
        /*net.minecraft.util.Util.getPlatform().openFile(f);
        *///?} else {
        net.minecraft.Util.getPlatform().openFile(f);
        //?}
    }
}
