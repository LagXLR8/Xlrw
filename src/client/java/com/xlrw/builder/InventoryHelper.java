package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class InventoryHelper {

    public static Item getItemForBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.REDSTONE_WIRE) return Items.REDSTONE;
        if (block == Blocks.TRIPWIRE) return Items.STRING;
        if (block == Blocks.SWEET_BERRY_BUSH) return Items.SWEET_BERRIES;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (block == Blocks.MELON_STEM) return Items.MELON_SEEDS;
        if (block == Blocks.PUMPKIN_STEM) return Items.PUMPKIN_SEEDS;
        if (block == Blocks.WATER_CAULDRON || block == Blocks.LAVA_CAULDRON || block == Blocks.POWDER_SNOW_CAULDRON) return Items.CAULDRON;
        if (block == Blocks.WATER) return Items.WATER_BUCKET;
        if (block == Blocks.LAVA) return Items.LAVA_BUCKET;
        return block.asItem();
    }

    public static boolean hasItemInHand(LocalPlayer player, BlockState state) {
        Item requiredItem = getItemForBlockState(state);
        return player.getMainHandItem().is(requiredItem) || player.getOffhandItem().is(requiredItem);
    }

    public static boolean hasItemInInventory(LocalPlayer player, BlockState state) {
        if (player.isCreative()) return true;
        Item requiredItem = getItemForBlockState(state);
        if (requiredItem == Items.AIR) return false;

        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(requiredItem)) {
                return true;
            }
        }
        return player.getOffhandItem().is(requiredItem);
    }

    public static InteractionHand getHandWithItem(LocalPlayer player, BlockState state) {
        Item requiredItem = getItemForBlockState(state);
        if (player.getMainHandItem().is(requiredItem)) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().is(requiredItem)) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    public static boolean equipBlock(LocalPlayer player, BlockState state) {
        if (hasItemInHand(player, state)) {
            return true;
        }

        Item requiredItem = getItemForBlockState(state);
        if (requiredItem == Items.AIR) {
            return false;
        }

        Inventory inv = player.getInventory();

        // 1. Check hotbar slots 0 to 8
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(requiredItem)) {
                selectHotbarSlot(player, slot);
                return true;
            }
        }

        // 2. Creative mode auto-pick with server synchronization
        if (player.isCreative() && BuilderConfig.getInstance().creativeAutoPick) {
            ItemStack creativeStack = new ItemStack(requiredItem);
            inv.setItem(inv.selected, creativeStack);
            player.connection.send(new ServerboundSetCreativeModeSlotPacket(36 + inv.selected, creativeStack));
            return true;
        }

        // 3. Search main inventory (slots 9 to 35) and swap with server container click
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(requiredItem)) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, player);
                    return true;
                }
            }
        }

        return false;
    }

    public static void selectHotbarSlot(LocalPlayer player, int slot) {
        if (player.getInventory().selected != slot) {
            player.getInventory().selected = slot;
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    public static boolean hasItem(LocalPlayer player, Item item) {
        if (item == null || item == Items.AIR) return false;
        if (player.isCreative()) return true;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(item)) return true;
        }
        return player.getOffhandItem().is(item);
    }

    public enum EquipResult { IN_HAND, SWITCHED, MISSING }

    /**
     * Puts the item in the main hand. SWITCHED means the slot changed this tick -
     * a real player needs a moment too, so the caller should click on the next tick.
     */
    public static EquipResult equipItem(LocalPlayer player, Item item) {
        if (item == null || item == Items.AIR) return EquipResult.MISSING;
        if (player.getMainHandItem().is(item)) return EquipResult.IN_HAND;

        Inventory inv = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inv.getItem(slot).is(item)) {
                selectHotbarSlot(player, slot);
                return EquipResult.SWITCHED;
            }
        }

        if (player.isCreative() && BuilderConfig.getInstance().creativeAutoPick) {
            ItemStack creativeStack = new ItemStack(item);
            inv.setItem(inv.selected, creativeStack);
            player.connection.send(new ServerboundSetCreativeModeSlotPacket(36 + inv.selected, creativeStack));
            return EquipResult.SWITCHED;
        }

        for (int slot = 9; slot < 36; slot++) {
            if (inv.getItem(slot).is(item)) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, player);
                    return EquipResult.SWITCHED;
                }
            }
        }
        return EquipResult.MISSING;
    }

    /**
     * Finds an available temporary support material in inventory.
     * Prioritizes Glass (glass, tinted, stained glass), then falls back to other common solid blocks.
     */
    public static Item getAvailableSupportItem(LocalPlayer player) {
        return getAvailableSolidSupportItem(player);
    }

    /**
     * Finds an available solid (gravity-immune) support material in inventory.
     * Prioritizes Glass, then common solid blocks like Cobblestone, Dirt, Stone, Planks.
     * Never returns Scaffolding or Falling blocks (Sand/Gravel).
     */
    public static Item getAvailableSolidSupportItem(LocalPlayer player) {
        if (player == null) return null;
        if (player.isCreative()) return Items.COBBLESTONE;

        // 1. Priority: Common disposable solid blocks (Cobblestone, Dirt, Stone, Planks)
        Item[] fallbackItems = {
                Items.COBBLESTONE, Items.DIRT, Items.COBBLED_DEEPSLATE, Items.NETHERRACK,
                Items.STONE, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.COARSE_DIRT
        };
        for (Item item : fallbackItems) {
            if (hasItem(player, item)) return item;
        }

        // 2. Glass (only if player has no standard building blocks)
        Item[] glassItems = {
                Items.GLASS, Items.TINTED_GLASS,
                Items.WHITE_STAINED_GLASS, Items.LIGHT_GRAY_STAINED_GLASS, Items.GRAY_STAINED_GLASS,
                Items.BLACK_STAINED_GLASS, Items.BROWN_STAINED_GLASS, Items.RED_STAINED_GLASS,
                Items.ORANGE_STAINED_GLASS, Items.YELLOW_STAINED_GLASS, Items.LIME_STAINED_GLASS,
                Items.GREEN_STAINED_GLASS, Items.CYAN_STAINED_GLASS, Items.LIGHT_BLUE_STAINED_GLASS,
                Items.BLUE_STAINED_GLASS, Items.PURPLE_STAINED_GLASS, Items.MAGENTA_STAINED_GLASS,
                Items.PINK_STAINED_GLASS
        };
        for (Item item : glassItems) {
            if (hasItem(player, item)) return item;
        }

        return null;
    }

    public static boolean hasScaffolding(LocalPlayer player) {
        return hasItem(player, Items.SCAFFOLDING);
    }

    /**
     * Finds an available bridging material in inventory.
     * Strictly prioritizes solid (gravity-immune) blocks (Cobblestone, Dirt, Planks, Stone)
     * so bridging across a void ledge never drops scaffolding into the abyss!
     * Scaffolding is only used when the player is already standing on a scaffolding platform.
     */
    public static Item findBridgingBlockItem(LocalPlayer player) {
        if (player == null) return null;
        if (player.isCreative()) return Items.COBBLESTONE;

        // 1. Strictly prioritize solid non-gravity blocks first!
        Item solidSupport = getAvailableSolidSupportItem(player);
        if (solidSupport != null) return solidSupport;

        // 2. Fallback to scaffolding ONLY if on scaffolding or no solid blocks exist
        if (hasItem(player, Items.SCAFFOLDING)) return Items.SCAFFOLDING;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
                net.minecraft.world.level.block.Block b = bi.getBlock();
                if (!b.defaultBlockState().isAir() && !BuildRules.isLiquid(b.defaultBlockState())
                        && !(b instanceof net.minecraft.world.level.block.FallingBlock)) {
                    return stack.getItem();
                }
            }
        }
        return null;
    }
}
