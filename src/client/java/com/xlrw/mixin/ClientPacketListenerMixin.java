package com.xlrw.mixin;

import com.xlrw.builder.ServerSafe;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server teleported / set back the player: clicks sent before the client confirms the teleport are ignored. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void xlrw$onServerSetback(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        ServerSafe.onServerSetback();
    }
}
