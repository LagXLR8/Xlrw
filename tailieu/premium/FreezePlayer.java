package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;

public final class FreezePlayer extends AliceModule {
    private double savedX;
    private double savedY;
    private double savedZ;

    public FreezePlayer() {
        super(AliceAddon.PVP_CATEGORY, "Freeze", "Freeze player.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) {
            savedX = mc.player.getX();
            savedY = mc.player.getY();
            savedZ = mc.player.getZ();
        }

        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) mc.player.setVelocity(0, 0, 0);
        super.onDeactivate();
    }

    @AliceHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        mc.player.setVelocity(0, 0, 0);
        mc.player.setPosition(savedX, savedY, savedZ);
    }
}
