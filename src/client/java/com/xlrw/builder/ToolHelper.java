package com.xlrw.builder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Automatically inspects inventory and equips the most effective tool (pickaxe, axe, shovel, etc.)
 * for breaking a targeted block state. Prioritizes pickaxes as requested.
 */
public class ToolHelper {

    /**
     * Finds and equips the best tool in hotbar or inventory to destroy the given block state.
     * Prioritizes pickaxes and tools with highest destroy speed.
     * @return true if a suitable tool is now equipped in the main hand.
     */
    public static boolean equipBestTool(LocalPlayer player, BlockState state) {
        if (player == null || state == null || state.isAir()) return false;

        Inventory inv = player.getInventory();
        ItemStack currentStack = player.getMainHandItem();
        float currentSpeed = currentStack.getDestroySpeed(state);

        int bestHotbarSlot = -1;
        float bestSpeed = currentSpeed;
        boolean bestIsCorrectTool = currentStack.isCorrectToolForDrops(state);

        // 1. Search Hotbar (0 - 8)
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;

            float speed = stack.getDestroySpeed(state);
            boolean isCorrect = stack.isCorrectToolForDrops(state);

            if (isBetterTool(stack, speed, isCorrect, bestSpeed, bestIsCorrectTool)) {
                bestSpeed = speed;
                bestIsCorrectTool = isCorrect;
                bestHotbarSlot = slot;
            }
        }

        // If hotbar has a better tool, select it
        if (bestHotbarSlot != -1) {
            InventoryHelper.selectHotbarSlot(player, bestHotbarSlot);
            return true;
        }

        // If current hand is already effective (> 1.0f or is correct tool), keep it
        if (bestIsCorrectTool || bestSpeed > 1.0f) {
            return true;
        }

        // 2. Search Main Inventory (9 - 35) and swap into selected hotbar slot
        int bestInvSlot = -1;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;

            float speed = stack.getDestroySpeed(state);
            boolean isCorrect = stack.isCorrectToolForDrops(state);

            if (isBetterTool(stack, speed, isCorrect, bestSpeed, bestIsCorrectTool)) {
                bestSpeed = speed;
                bestIsCorrectTool = isCorrect;
                bestInvSlot = slot;
            }
        }

        if (bestInvSlot != -1) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode != null) {
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, bestInvSlot, com.xlrw.compat.MC.selected(inv), ClickType.SWAP, player);
                return true;
            }
        }

        return false;
    }

    private static boolean isBetterTool(ItemStack stack, float speed, boolean isCorrect, float currentBestSpeed, boolean currentIsCorrect) {
        if (isCorrect && !currentIsCorrect) return true;
        if (!isCorrect && currentIsCorrect) return false;
        if (speed > currentBestSpeed) return true;
        if (speed == currentBestSpeed) {
            // Tie-break: Prefer Pickaxes
            return stack.is(net.minecraft.tags.ItemTags.PICKAXES);
        }
        return false;
    }
}
