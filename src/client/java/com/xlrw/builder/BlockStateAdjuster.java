package com.xlrw.builder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Handles right-click state changes of already placed blocks:
 * doors / trapdoors / fence gates (open), repeater delay, comparator mode,
 * daylight detector inversion, levers and note block pitch.
 * <p>
 * Waterlogging is NOT handled here - it belongs to the fluid phase (see {@link BuildRules#fluidStatus}).
 */
public class BlockStateAdjuster {

    /**
     * Checks if a placed block has a hand-adjustable state difference compared to the schematic.
     */
    public static boolean needsAdjustment(BlockState actual, BlockState expected) {
        if (actual.getBlock() != expected.getBlock()) {
            return false;
        }
        Block block = actual.getBlock();

        // 1. Open / Closed (doors, trapdoors, fence gates)
        if (expected.hasProperty(BlockStateProperties.OPEN) && actual.hasProperty(BlockStateProperties.OPEN)) {
            if (!expected.getValue(BlockStateProperties.OPEN).equals(actual.getValue(BlockStateProperties.OPEN))
                    && canToggleOpenByHand(actual)) {
                return true;
            }
        }

        // 2. Repeater delay
        if (differs(actual, expected, BlockStateProperties.DELAY)) return true;

        // 3. Comparator mode
        if (differs(actual, expected, BlockStateProperties.MODE_COMPARATOR)) return true;

        // 4. Daylight detector inverted
        if (block instanceof DaylightDetectorBlock && differs(actual, expected, BlockStateProperties.INVERTED)) return true;

        // 5. Lever powered
        if (block instanceof LeverBlock && differs(actual, expected, BlockStateProperties.POWERED)) return true;

        // 6. Note block pitch
        if (block instanceof NoteBlock && differs(actual, expected, BlockStateProperties.NOTE)) return true;

        return false;
    }

    private static boolean differs(BlockState a, BlockState e, net.minecraft.world.level.block.state.properties.Property<?> p) {
        return a.hasProperty(p) && e.hasProperty(p) && !a.getValue(p).equals(e.getValue(p));
    }

    /**
     * Iron doors/trapdoors cannot be opened by hand, upper door halves are toggled through the lower half,
     * and redstone-powered openables would immediately be reverted - never spam-click those.
     */
    public static boolean canToggleOpenByHand(BlockState state) {
        if (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR)) return false;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return false;
        if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) return false;
        return true;
    }

    /**
     * Fence gates rotate towards the player when opened from behind.
     * Returns false if opening from this yaw would end with the wrong facing.
     */
    public static boolean isAdjustYawAcceptable(BlockState actual, BlockState expected, float yaw) {
        if (!(actual.getBlock() instanceof FenceGateBlock)) return true;
        if (!actual.hasProperty(BlockStateProperties.OPEN) || actual.getValue(BlockStateProperties.OPEN)) return true;
        Direction facing = actual.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Direction playerDir = Direction.fromYRot(yaw);
        Direction result = playerDir == facing.getOpposite() ? playerDir : facing;
        return result == expected.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    /**
     * Selects a hotbar slot whose item has no right-click action of its own, so the click
     * interacts with the block (instead of placing a block / pouring a bucket / using bone meal...).
     * @return true if the held item changed (caller should wait one tick before clicking)
     */
    public static boolean equipSafeItem(LocalPlayer player) {
        if (isSafeItem(player.getMainHandItem())) return false;

        // Prefer an empty slot, then a harmless non-block item
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (pass == 0 ? stack.isEmpty() : isSafeItem(stack)) {
                    InventoryHelper.selectHotbarSlot(player, i);
                    return true;
                }
            }
        }
        return false; // keep current item; non-sneaking click still interacts for most blocks
    }

    private static boolean isSafeItem(ItemStack stack) {
        if (stack.isEmpty()) return true;
        Item item = stack.getItem();
        if (item instanceof BlockItem) return false;
        if (item instanceof BucketItem || item instanceof SolidBucketItem || item instanceof MobBucketItem) return false;
        if (item instanceof FlintAndSteelItem || item instanceof FireChargeItem || item instanceof BoneMealItem) return false;
        if (item instanceof SpawnEggItem || item instanceof EnderEyeItem || item instanceof ShearsItem) return false;
        if (item instanceof AxeItem || item instanceof ShovelItem || item instanceof HoeItem) return false; // strip/path/till
        if (item instanceof HoneycombItem || item instanceof PotionItem || item == Items.GLASS_BOTTLE) return false;
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return false;
        return true;
    }

    public static boolean isClickTarget(Minecraft mc, BlockPos pos) {
        return mc.level != null && !mc.level.getBlockState(pos).getShape(mc.level, pos).isEmpty();
    }
}
