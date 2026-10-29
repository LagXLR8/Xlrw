package com.xlrw.builder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Runs vanilla's own placement logic ({@code Block#getStateForPlacement}) with a hypothetical
 * look direction + hit point. This tells us EXACTLY which facing / half / axis / hinge the block
 * will get if the player really looks there and clicks - so blocks are always placed with the
 * schematic orientation, using a 100% legit look direction.
 */
public final class PlacementSimulator {

    private PlacementSimulator() {}

    public record Result(BlockPos pos, BlockState state) {}

    public static Result simulate(ClientLevel level, LocalPlayer player, ItemStack stack,
                                  BlockHitResult hit, float yaw, float pitch, Block requiredBlock) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;

        // Standing/wall item variants: the clicked face decides which block the item creates
        Block useBlock = blockItem.getBlock();
        if (blockItem instanceof StandingAndWallBlockItem) {
            Direction standingFace = useBlock instanceof CeilingHangingSignBlock ? Direction.DOWN : Direction.UP;
            if (requiredBlock != null && requiredBlock != useBlock) {
                if (hit.getDirection().getAxis().isVertical()) return null; // wall variant needs a side face
                useBlock = requiredBlock;
            } else if (hit.getDirection() != standingFace) {
                return null;
            }
        }

        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        float oldYawO = player.yRotO;
        float oldPitchO = player.xRotO;
        float oldHead = player.yHeadRot;
        float oldHeadO = player.yHeadRotO;
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yaw;
            player.xRotO = pitch;
            // Pistons, observers, dispensers... use getNearestLookingDirection() which reads the HEAD yaw
            player.yHeadRot = yaw;
            player.yHeadRotO = yaw;

            BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
            if (!ctx.canPlace()) return null;
            BlockPlaceContext updated = blockItem.updatePlacementContext(ctx);
            if (updated == null) return null;

            BlockState state = useBlock.getStateForPlacement(updated);
            if (state == null) return null;
            BlockPos pos = updated.getClickedPos();
            if (!state.canSurvive(level, pos)) return null;
            return new Result(pos, state);
        } catch (Throwable t) {
            return null;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            player.yRotO = oldYawO;
            player.xRotO = oldPitchO;
            player.yHeadRot = oldHead;
            player.yHeadRotO = oldHeadO;
        }
    }
}
