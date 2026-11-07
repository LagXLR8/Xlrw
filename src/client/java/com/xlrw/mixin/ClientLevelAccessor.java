package com.xlrw.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code ClientLevel#getBlockStatePredictionHandler()} is package-private: read the field directly. */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
    @Accessor("blockStatePredictionHandler")
    BlockStatePredictionHandler xlrw$getPredictionHandler();
}
