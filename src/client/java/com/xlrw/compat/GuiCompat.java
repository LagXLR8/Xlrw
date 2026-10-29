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
}
