package com.xlrw.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads what the client last told the server (rotation in the last movement packet, last sent sneak state). */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("yRotLast")
    float xlrw$getYRotLast();

    @Accessor("xRotLast")
    float xlrw$getXRotLast();

    @Accessor("wasShiftKeyDown")
    boolean xlrw$getWasShiftKeyDown();
}
