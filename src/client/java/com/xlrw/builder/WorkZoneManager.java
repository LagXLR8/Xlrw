package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Manages 3x3 sub-area (work station) partitioning for the 3D Printer.
 * Guarantees that the bot finishes all blocks within the selected 3x3 zone
 * before calmly advancing to the next adjacent 3x3 zone, eliminating wandering.
 */
public class WorkZoneManager {
    private static final WorkZoneManager INSTANCE = new WorkZoneManager();

    private ZoneCoord activeZone = null;
    private ZoneCoord lastCompletedZone = null;
    private int zoneStuckTicks = 0;

    public static WorkZoneManager getInstance() {
        return INSTANCE;
    }

    public ZoneCoord getActiveZone() {
        return activeZone;
    }

    public void clearZone() {
        activeZone = null;
        zoneStuckTicks = 0;
    }

    public ZoneCoord getLastCompletedZone() {
        return lastCompletedZone;
    }

    /**
     * Checks if the specified 3x3 zone has any remaining placeable, non-blacklisted blocks on the active layer.
     */
    public boolean isZoneComplete(Schematic schematic, ClientLevel level, int activeLayerY, ZoneCoord zone, LocalPlayer player) {
        if (schematic == null || level == null || zone == null) return true;

        BuilderConfig config = BuilderConfig.getInstance();
        TaskTracker tracker = TaskTracker.getInstance();

        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        boolean hasAnySupportedWork = false;

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();
            if (!zone.contains(pos)) continue;

            // Check if misplaced block needs to be demolished in this zone
            if (config.autoDemolish && DemolishManager.isMisplaced(level, schematic, pos)) {
                return false;
            }

            // Skip permanently skipped or temporarily blacklisted blocks
            if (tracker.isBlocked(pos) || tracker.isPermanentlySkipped(pos)) {
                continue;
            }

            BlockState expected = entry.getValue();
            BlockState inWorld = level.getBlockState(pos);

            BuildRules.BuildStatus status = BuildRules.buildStatus(inWorld, expected);
            if (status == BuildRules.BuildStatus.MISSING || status == BuildRules.BuildStatus.STACKABLE || status == BuildRules.BuildStatus.ADJUST) {
                // Must have required item
                net.minecraft.world.item.Item reqItem = InventoryHelper.getItemForBlockState(expected);
                if (player.isCreative() || InventoryHelper.hasItem(player, reqItem) || status == BuildRules.BuildStatus.ADJUST) {
                    if (status != BuildRules.BuildStatus.MISSING || (BuilderController.isAccessible(level, pos) && BuilderController.hasSolidNeighbor(level, pos))) {
                        hasAnySupportedWork = true;
                    }
                }
            }
        }

        return !hasAnySupportedWork;
    }

    /**
     * Ensures an active 3x3 zone is selected.
     * If the current zone is complete, advances to the closest adjacent unfinished zone.
     */
    public ZoneCoord ensureActiveZone(Schematic schematic, ClientLevel level, int activeLayerY, LocalPlayer player) {
        if (schematic == null || level == null || player == null) return null;

        if (activeZone != null) {
            if (isZoneComplete(schematic, level, activeLayerY, activeZone, player)) {
                lastCompletedZone = activeZone;
                activeZone = null;
                zoneStuckTicks = 0;
            }
        }

        if (activeZone == null) {
            activeZone = findNextBestZone(schematic, level, activeLayerY, player);
            zoneStuckTicks = 0;
        }

        return activeZone;
    }

    /**
     * Finds the next best 3x3 zone on the active layer.
     * Prioritizes zones adjacent to the last completed zone or closest to the player,
     * and zones that have supported blocks (solid neighbor present).
     */
    public ZoneCoord findNextBestZone(Schematic schematic, ClientLevel level, int activeLayerY, LocalPlayer player) {
        if (schematic == null || level == null || player == null) return null;

        BuilderConfig config = BuilderConfig.getInstance();
        TaskTracker tracker = TaskTracker.getInstance();

        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        Map<ZoneCoord, ZoneStats> zoneMap = new HashMap<>();

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();

            if (tracker.isBlocked(pos) || tracker.isPermanentlySkipped(pos)) continue;

            BlockState expected = entry.getValue();
            BlockState inWorld = level.getBlockState(pos);

            boolean isMisplaced = config.autoDemolish && DemolishManager.isMisplaced(level, schematic, pos);
            BuildRules.BuildStatus status = BuildRules.buildStatus(inWorld, expected);

            boolean needsWork = isMisplaced || status == BuildRules.BuildStatus.MISSING
                    || status == BuildRules.BuildStatus.STACKABLE || status == BuildRules.BuildStatus.ADJUST;

            if (needsWork) {
                // Accessibility and gravity check
                if (!isMisplaced && status == BuildRules.BuildStatus.MISSING) {
                    if (!BuilderController.isAccessible(level, pos)) continue;
                    if (expected.getBlock() instanceof FallingBlock && level.getBlockState(pos.below()).isAir()) continue;
                }

                ZoneCoord zc = ZoneCoord.fromBlockPos(pos);
                ZoneStats stats = zoneMap.computeIfAbsent(zc, k -> new ZoneStats());
                stats.blockCount++;
                if (isMisplaced) stats.misplacedCount++;
                if (BuilderController.hasSolidNeighbor(level, pos)) stats.supportedCount++;
            }
        }

        if (zoneMap.isEmpty()) return null;

        BlockPos referencePos = lastCompletedZone != null ? lastCompletedZone.getCenter(activeLayerY) : player.blockPosition();

        // Sort zones: zones with supported blocks first, then closest to reference point
        List<Map.Entry<ZoneCoord, ZoneStats>> sortedZones = new ArrayList<>(zoneMap.entrySet());
        sortedZones.sort(Comparator
                .comparingInt((Map.Entry<ZoneCoord, ZoneStats> e) -> e.getValue().supportedCount > 0 ? 0 : 1)
                .thenComparingDouble(e -> e.getKey().distanceSqTo(referencePos))
        );

        return sortedZones.get(0).getKey();
    }

    /**
     * Called when the bot is stuck or unable to place in the current zone for a long duration.
     * Temporarily yields the current zone so adjacent zones can be worked on.
     */
    public void yieldCurrentZone(long blacklistDurationMs) {
        if (activeZone != null) {
            lastCompletedZone = activeZone;
            activeZone = null;
            zoneStuckTicks = 0;
        }
    }

    private static class ZoneStats {
        int blockCount = 0;
        int supportedCount = 0;
        int misplacedCount = 0;
    }
}
