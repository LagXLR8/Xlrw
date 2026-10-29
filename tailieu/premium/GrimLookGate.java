package com.nnpg.alice.premium;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Requires one completed vanilla movement cycle looking at an interaction
 * target before the interaction is sent. This satisfies both the pre-flying
 * and post-flying rays used by Grim RotationPlace after its buffer is armed.
 */
final class GrimLookGate {
    private long movementEpoch;
    private Target armedTarget;
    private long armedEpoch;

    void onMovementPacketsPost() {
        movementEpoch++;
    }

    boolean readyFor(BlockHitResult hit) {
        if (hit == null) {
            invalidate();
            return false;
        }
        return readyFor(new BlockTarget(
            hit.getBlockPos().toImmutable(),
            hit.getSide()
        ));
    }

    boolean readyForEntity(int entityId) {
        return readyFor(new EntityTarget(entityId));
    }

    void invalidate() {
        armedTarget = null;
    }

    private boolean readyFor(Target target) {
        if (!target.equals(armedTarget)) {
            armedTarget = target;
            armedEpoch = movementEpoch;
            return false;
        }
        if (movementEpoch <= armedEpoch) return false;

        armedTarget = null;
        return true;
    }

    private sealed interface Target {
    }

    private record BlockTarget(BlockPos pos, Direction side)
        implements Target {
    }

    private record EntityTarget(int entityId) implements Target {
    }
}
