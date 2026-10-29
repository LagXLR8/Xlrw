package com.xlrw.printer;

import com.xlrw.builder.InventoryHelper;
import com.xlrw.config.BuilderConfig;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class ChestManager {
    private static final ChestManager INSTANCE = new ChestManager();

    public enum RefillResult {
        IN_PROGRESS,
        FINISHED,
        NEXT_CHEST,
        ALL_CHESTS_EXHAUSTED
    }

    private long lastChestInteract = 0;
    private long lastRefillCompletionTime = 0;
    private int refillCooldown = 0;
    private int currentChestIndex = 0;

    private final Map<BlockPos, Map<Item, Integer>> chestContentsCache = new HashMap<>();

    public static ChestManager getInstance() {
        return INSTANCE;
    }

    public boolean hasAnyChest() {
        return !BuilderConfig.getInstance().getChestPositions().isEmpty();
    }

    public List<BlockPos> getChestPositions() {
        return BuilderConfig.getInstance().getChestPositions();
    }

    public BlockPos getTargetChestPos() {
        List<BlockPos> list = getChestPositions();
        if (list.isEmpty()) return null;
        if (currentChestIndex >= list.size()) {
            currentChestIndex = 0;
        }
        return list.get(currentChestIndex);
    }

    public void advanceToNextChest() {
        currentChestIndex++;
    }

    public boolean hasMoreChests() {
        return currentChestIndex < getChestPositions().size() - 1;
    }

    public void resetChestIndex() {
        currentChestIndex = 0;
    }

    public int getCurrentChestIndex() {
        return currentChestIndex;
    }

    public void addChestPos(BlockPos pos) {
        BuilderConfig.getInstance().addChestPos(pos);
    }

    public void removeChestPos(BlockPos pos) {
        BuilderConfig.getInstance().removeChestPos(pos);
        chestContentsCache.remove(pos);
    }

    public void clearChestPositions() {
        BuilderConfig.getInstance().clearChestPositions();
        chestContentsCache.clear();
        currentChestIndex = 0;
    }

    /**
     * Determines whether the targeted block is a single chest, double chest, barrel, or shulker.
     */
    public static String getChestDescription(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return "Chưa đặt";
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (block instanceof ChestBlock) {
            if (state.hasProperty(ChestBlock.TYPE)) {
                ChestType type = state.getValue(ChestBlock.TYPE);
                if (type == ChestType.SINGLE) {
                    return "Rương đơn (27 ô)";
                } else {
                    return "Rương đôi (54 ô)";
                }
            }
            return "Rương (27 ô)";
        } else if (block instanceof BarrelBlock) {
            return "Thùng gỗ (27 ô)";
        } else if (block instanceof ShulkerBoxBlock) {
            return "Hộp Shulker (27 ô)";
        }
        return block.getName().getString();
    }

    /**
     * Caches chest contents when container is open.
     */
    public void recordChestContent(BlockPos pos, AbstractContainerMenu menu) {
        if (pos == null || menu == null) return;
        int chestSlots = menu.slots.size() - 36;
        Map<Item, Integer> items = new HashMap<>();
        for (int i = 0; i < chestSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                items.put(item, items.getOrDefault(item, 0) + stack.getCount());
            }
        }
        chestContentsCache.put(pos, items);
    }

    /**
     * Inspects a specific chest in singleplayer or from cache.
     */
    public int getChestItemCount(Minecraft mc, BlockPos pos, Item item) {
        if (pos == null || item == null) return 0;

        // 1. Singleplayer Server inspection (instant live data, handles double chests)
        if (mc.getSingleplayerServer() != null && mc.level != null) {
            ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
            if (serverLevel != null) {
                BlockState state = serverLevel.getBlockState(pos);
                int count = 0;

                if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
                    ChestType type = state.getValue(ChestBlock.TYPE);
                    if (serverLevel.getBlockEntity(pos) instanceof Container c1) {
                        for (int i = 0; i < c1.getContainerSize(); i++) {
                            ItemStack s = c1.getItem(i);
                            if (!s.isEmpty() && s.is(item)) count += s.getCount();
                        }
                    }
                    if (type != ChestType.SINGLE) {
                        BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
                        if (serverLevel.getBlockEntity(otherPos) instanceof Container c2) {
                            for (int i = 0; i < c2.getContainerSize(); i++) {
                                ItemStack s = c2.getItem(i);
                                if (!s.isEmpty() && s.is(item)) count += s.getCount();
                            }
                        }
                    }
                    return count;
                } else if (serverLevel.getBlockEntity(pos) instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack s = container.getItem(i);
                        if (!s.isEmpty() && s.is(item)) count += s.getCount();
                    }
                    return count;
                }
            }
        }

        // 2. Cache from previous chest openings
        Map<Item, Integer> cached = chestContentsCache.get(pos);
        if (cached != null) {
            return cached.getOrDefault(item, 0);
        }

        // 3. Client block entity
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof Container container) {
            int count = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (!s.isEmpty() && s.is(item)) count += s.getCount();
            }
            return count;
        }

        return 0;
    }

    public int getAllChestsItemCount(Minecraft mc, Item item) {
        int total = 0;
        for (BlockPos pos : getChestPositions()) {
            total += getChestItemCount(mc, pos, item);
        }
        return total;
    }

    /**
     * Calculates the exact items that are STILL MISSING in the world for the active layer(s).
     */
    public Map<Item, Integer> getMissingBlocksNeeded(Minecraft mc, Schematic schematic) {
        Map<Item, Integer> missing = new HashMap<>();
        if (mc.level == null || schematic == null) return missing;

        BuilderConfig config = BuilderConfig.getInstance();
        int minY = schematic.getMinY();
        int maxY = schematic.getMaxY();

        int startY = config.layerMode ? (minY + config.currentLayer) : minY;
        int endY = config.layerMode ? Math.min(maxY, minY + config.currentLayer + config.layerRange - 1) : maxY;

        for (Map.Entry<BlockPos, BlockState> entry : schematic.getWorldBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            int y = pos.getY();
            if (y >= startY && y <= endY) {
                BlockState expected = entry.getValue();
                BlockState actual = mc.level.getBlockState(pos);
                if (expected.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                    if (expected.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) != 0) continue;
                } else if (expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && expected.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
                    continue;
                } else if (expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                        && expected.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                    continue;
                }

                if (actual.getBlock() != expected.getBlock() || com.xlrw.builder.BlockStateAdjuster.needsAdjustment(actual, expected)) {
                    Item item = InventoryHelper.getItemForBlockState(expected);
                    if (item != Items.AIR) {
                        missing.put(item, missing.getOrDefault(item, 0) + 1);
                    }
                }
            }
        }
        return missing;
    }

    /**
     * Counts how many of a specific item the player currently carries in inventory.
     */
    public int getPlayerItemCount(LocalPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        if (player.getOffhandItem().is(item)) {
            count += player.getOffhandItem().getCount();
        }
        return count;
    }

    /**
     * Calculates the exact items and counts that the player is DEFICIENT in for the current layer.
     */
    public Map<Item, Integer> getDeficitMaterials(Minecraft mc, LocalPlayer player, Schematic schematic) {
        Map<Item, Integer> missing = getMissingBlocksNeeded(mc, schematic);
        Map<Item, Integer> deficit = new HashMap<>();

        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            Item item = entry.getKey();
            int neededCount = entry.getValue();
            int playerHas = getPlayerItemCount(player, item);

            if (playerHas < neededCount) {
                deficit.put(item, neededCount - playerHas);
            }
        }

        if (BuilderConfig.getInstance().useScaffolding) {
            int scaffoldCount = getPlayerItemCount(player, Items.SCAFFOLDING);
            if (scaffoldCount < 16) {
                deficit.put(Items.SCAFFOLDING, 32 - scaffoldCount);
            }
        }

        return deficit;
    }

    /**
     * Checks if the player needs to visit the supply chest right now.
     */
    public boolean needsRefill(Minecraft mc, LocalPlayer player, Schematic schematic) {
        if (!hasAnyChest() || !BuilderConfig.getInstance().autoRefill || player.isCreative()) {
            return false;
        }

        // Refill cooldown (4s) after completing or exhausting chests to avoid infinite loops
        if (System.currentTimeMillis() - lastRefillCompletionTime < 4000) {
            return false;
        }

        Map<Item, Integer> missing = getMissingBlocksNeeded(mc, schematic);
        if (missing.isEmpty()) {
            return false;
        }

        for (Item item : missing.keySet()) {
            if (getPlayerItemCount(player, item) > 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Opens the currently targeted supply chest.
     */
    public void openChest(Minecraft mc, LocalPlayer player) {
        BlockPos chestPos = getTargetChestPos();
        if (chestPos == null) return;

        long now = System.currentTimeMillis();
        if (now - lastChestInteract < 1000) return;
        lastChestInteract = now;

        Vec3 hitVec = new Vec3(chestPos.getX() + 0.5, chestPos.getY() + 0.8, chestPos.getZ() + 0.5);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround()));

        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, chestPos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
    }

    /**
     * Transfers ONLY the exact missing building materials from the open chest into player inventory.
     */
    public RefillResult processRefill(Minecraft mc, LocalPlayer player, Schematic schematic) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu || mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
            return RefillResult.IN_PROGRESS;
        }

        if (++refillCooldown < 4) {
            return RefillResult.IN_PROGRESS;
        }
        refillCooldown = 0;

        BlockPos currentPos = getTargetChestPos();
        if (currentPos != null) {
            recordChestContent(currentPos, menu);
        }

        Map<Item, Integer> deficit = getDeficitMaterials(mc, player, schematic);

        if (deficit.isEmpty() || player.getInventory().getFreeSlot() == -1) {
            player.closeContainer();
            mc.setScreen(null);
            resetChestIndex();
            lastRefillCompletionTime = System.currentTimeMillis();
            return RefillResult.FINISHED;
        }

        int chestSlots = menu.slots.size() - 36;
        boolean transferredAny = false;

        for (int i = 0; i < chestSlots; i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();

            if (!stack.isEmpty()) {
                Item item = stack.getItem();

                if (deficit.containsKey(item) && deficit.get(item) > 0) {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, player);
                    transferredAny = true;

                    int count = stack.getCount();
                    int remaining = Math.max(0, deficit.get(item) - count);
                    deficit.put(item, remaining);
                    break;
                }
            }
        }

        boolean allDeficitsMet = true;
        for (int count : deficit.values()) {
            if (count > 0) {
                allDeficitsMet = false;
                break;
            }
        }

        if (allDeficitsMet) {
            player.closeContainer();
            mc.setScreen(null);
            resetChestIndex();
            lastRefillCompletionTime = System.currentTimeMillis();
            return RefillResult.FINISHED;
        }

        if (!transferredAny) {
            player.closeContainer();
            mc.setScreen(null);
            if (hasMoreChests()) {
                advanceToNextChest();
                return RefillResult.NEXT_CHEST;
            } else {
                resetChestIndex();
                lastRefillCompletionTime = System.currentTimeMillis();
                return RefillResult.ALL_CHESTS_EXHAUSTED;
            }
        }

        return RefillResult.IN_PROGRESS;
    }
}
