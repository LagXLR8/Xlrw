package com.xlrw.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class PlacementCandidate {

    public enum Type {
        PLACE,        // place a block against a neighbour face
        STACK,        // click the same block again (double slab, candles...)
        ADJUST,       // right-click to change state (open, delay, mode...)
        FLUID_SOURCE, // pour a bucket next to a neighbour face
        WATERLOG,     // pour water into a waterloggable block
        DRAIN,        // pick water out of a waterlogged block
        BUCKET_FILL   // refill an empty bucket from a water source
    }

    public final Type type;
    public final BlockPos targetPos;
    public final BlockState requiredState;
    /** Block that is actually clicked / looked at. */
    public final BlockPos neighborPos;
    public final Direction clickFace;
    public final Vec3 hitVec;
    public final float targetYaw;
    public final float targetPitch;
    public final double distanceSq;
    /** Placed through a wall (no line of sight) - only for boxed-in cells, see PlacementFinder.throughWallAllowed. */
    public boolean throughWall = false;
    /** True if the clicked block is interactable - must sneak so the click places instead of opening it. */
    public final boolean requiresSneak;
    /** Item that has to be held for this action (null = any harmless item). */
    public final Item item;
    /** Fluid mode used by vanilla's bucket raycast for bucket actions. */
    public final ClipContext.Fluid fluidMode;

    /** Kept for backwards compatibility with older code paths. */
    public final boolean isAdjustment;

    public PlacementCandidate(Type type, BlockPos targetPos, BlockState requiredState, BlockPos neighborPos,
                              Direction clickFace, Vec3 hitVec, float targetYaw, float targetPitch, double distanceSq,
                              boolean requiresSneak, Item item, ClipContext.Fluid fluidMode) {
        this.type = type;
        this.targetPos = targetPos;
        this.requiredState = requiredState;
        this.neighborPos = neighborPos;
        this.clickFace = clickFace;
        this.hitVec = hitVec;
        this.targetYaw = targetYaw;
        this.targetPitch = targetPitch;
        this.distanceSq = distanceSq;
        this.requiresSneak = requiresSneak;
        this.item = item;
        this.fluidMode = fluidMode;
        this.isAdjustment = type == Type.ADJUST;
    }

    public boolean isBucketAction() {
        return type == Type.FLUID_SOURCE || type == Type.WATERLOG || type == Type.DRAIN || type == Type.BUCKET_FILL;
    }

    public BlockPos getClickTargetPos() {
        return (type == Type.ADJUST || type == Type.STACK || type == Type.WATERLOG || type == Type.BUCKET_FILL) ? targetPos : neighborPos;
    }
}
