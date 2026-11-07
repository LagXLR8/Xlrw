package com.xlrw.mixin;

import com.xlrw.builder.ServerSafe;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server acknowledged our block interactions up to {@code sequence}: after this the client world shows the REAL result. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void xlrw$onBlockChangedAck(int sequence, CallbackInfo ci) {
        ServerSafe.onBlockAck((ClientLevel) (Object) this, sequence);
    }
}
