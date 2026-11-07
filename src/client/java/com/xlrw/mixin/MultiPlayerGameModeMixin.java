package com.xlrw.mixin;

import com.xlrw.builder.ServerSafe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers every block right-click, so a screen that pops up right after a bot click can be recognised and closed. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void xlrw$noteClick(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        ServerSafe.noteClick(Minecraft.getInstance().level, hit.getBlockPos());
    }
}
