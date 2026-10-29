package com.xlrw.schematic;

import com.xlrw.builder.InventoryHelper;
import com.xlrw.config.BuilderConfig;
import com.xlrw.printer.ChestManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class MaterialReport {

    public static class Entry {
        public final Item item;
        public final String name;
        public int totalRequired = 0;
        public int placedCount = 0;
        public int missingOnCurrentLayer = 0;
        public int inPlayerInv = 0;
        public int inChests = 0;

        public Entry(Item item) {
            this.item = item;
            this.name = item.getDescription().getString();
        }

        public int getMissingTotal() {
            return Math.max(0, totalRequired - placedCount);
        }

        public int getTotalAvailable() {
            return inPlayerInv + inChests;
        }

        public boolean isLayerReady() {
            return inPlayerInv >= missingOnCurrentLayer || getTotalAvailable() >= missingOnCurrentLayer;
        }

        public boolean isTotalReady() {
            return getTotalAvailable() >= getMissingTotal();
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int totalBlocks = 0;
    private int totalPlaced = 0;
    private int totalMissing = 0;
    private boolean layerReady = true;
    private boolean totalReady = true;

    public List<Entry> getEntries() {
        return entries;
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public int getTotalPlaced() {
        return totalPlaced;
    }

    public int getTotalMissing() {
        return totalMissing;
    }

    public boolean isLayerReady() {
        return layerReady;
    }

    public boolean isTotalReady() {
        return totalReady;
    }

    public static MaterialReport generate(Minecraft mc, Schematic schematic) {
        MaterialReport report = new MaterialReport();
        if (schematic == null || mc.level == null) return report;

        LocalPlayer player = mc.player;
        BuilderConfig config = BuilderConfig.getInstance();
        int minY = schematic.getMinY();
        int activeLayerY = minY + config.currentLayer;

        Map<Item, Entry> map = new HashMap<>();

        for (Map.Entry<BlockPos, BlockState> e : schematic.getWorldBlocks().entrySet()) {
            BlockPos pos = e.getKey();
            BlockState expected = e.getValue();
            if (expected.isAir()) continue;

            if (expected.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                if (expected.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) != 0) continue;
            } else if (expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && expected.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
                continue;
            } else if (expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                    && expected.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                continue;
            }

            Item item = InventoryHelper.getItemForBlockState(expected);
            if (item == Items.AIR) continue;

            Entry entry = map.computeIfAbsent(item, Entry::new);
            entry.totalRequired++;
            report.totalBlocks++;

            BlockState actual = mc.level.getBlockState(pos);
            if (actual.getBlock() == expected.getBlock() && !com.xlrw.builder.BlockStateAdjuster.needsAdjustment(actual, expected)) {
                entry.placedCount++;
                report.totalPlaced++;
            } else {
                report.totalMissing++;
                if (config.layerMode && pos.getY() == activeLayerY) {
                    entry.missingOnCurrentLayer++;
                } else if (!config.layerMode) {
                    entry.missingOnCurrentLayer++;
                }
            }
        }

        // Compute player inventory and chest quantities
        for (Entry entry : map.values()) {
            if (player != null) {
                entry.inPlayerInv = ChestManager.getInstance().getPlayerItemCount(player, entry.item);
            }
            entry.inChests = ChestManager.getInstance().getAllChestsItemCount(mc, entry.item);

            if (!entry.isLayerReady() && entry.missingOnCurrentLayer > 0) {
                report.layerReady = false;
            }
            if (!entry.isTotalReady() && entry.getMissingTotal() > 0) {
                report.totalReady = false;
            }

            report.entries.add(entry);
        }

        // Sort: missing materials first, then alphabetically
        report.entries.sort((a, b) -> {
            int cmpLayer = Boolean.compare(a.isLayerReady(), b.isLayerReady());
            if (cmpLayer != 0) return cmpLayer;
            int cmpTotal = Boolean.compare(a.isTotalReady(), b.isTotalReady());
            if (cmpTotal != 0) return cmpTotal;
            return Integer.compare(b.totalRequired, a.totalRequired);
        });

        return report;
    }
}
