package com.xlrw.compat;

import net.minecraft.client.player.LocalPlayer;

/**
 * Direct writes to the player's movement input. On 1.21.1 these are public fields; on 1.21.11 the pressed keys are
 * one immutable record ({@code keyPresses}) and the move vector is computed from the key bindings every tick, so
 * the impulse setters are no-ops there (the callers also press the real key bindings).
 */
public final class InputCompat {
    private InputCompat() {}

    public static void up(LocalPlayer p, boolean v) { set(p, 0, v); }
    public static void down(LocalPlayer p, boolean v) { set(p, 1, v); }
    public static void left(LocalPlayer p, boolean v) { set(p, 2, v); }
    public static void right(LocalPlayer p, boolean v) { set(p, 3, v); }
    public static void jumping(LocalPlayer p, boolean v) { set(p, 4, v); }
    public static void shiftKeyDown(LocalPlayer p, boolean v) { set(p, 5, v); }

    public static boolean shiftKeyDown(LocalPlayer p) {
        if (p == null || p.input == null) return false;
        //? if >=1.21.11 {
        /*return p.input.keyPresses.shift();
        *///?} else {
        return p.input.shiftKeyDown;
        //?}
    }

    public static boolean jumping(LocalPlayer p) {
        if (p == null || p.input == null) return false;
        //? if >=1.21.11 {
        /*return p.input.keyPresses.jump();
        *///?} else {
        return p.input.jumping;
        //?}
    }

    public static void forwardImpulse(LocalPlayer p, float v) {
        if (p == null || p.input == null) return;
        //? if <1.21.11 {
        p.input.forwardImpulse = v;
        //?}
    }

    public static void leftImpulse(LocalPlayer p, float v) {
        if (p == null || p.input == null) return;
        //? if <1.21.11 {
        p.input.leftImpulse = v;
        //?}
    }

    private static void set(LocalPlayer p, int which, boolean v) {
        if (p == null || p.input == null) return;
        //? if >=1.21.11 {
        /*net.minecraft.world.entity.player.Input i = p.input.keyPresses;
        p.input.keyPresses = new net.minecraft.world.entity.player.Input(
                which == 0 ? v : i.forward(), which == 1 ? v : i.backward(), which == 2 ? v : i.left(),
                which == 3 ? v : i.right(), which == 4 ? v : i.jump(), which == 5 ? v : i.shift(), i.sprint());
        *///?} else {
        switch (which) {
            case 0 -> p.input.up = v;
            case 1 -> p.input.down = v;
            case 2 -> p.input.left = v;
            case 3 -> p.input.right = v;
            case 4 -> p.input.jumping = v;
            default -> p.input.shiftKeyDown = v;
        }
        //?}
    }
}
