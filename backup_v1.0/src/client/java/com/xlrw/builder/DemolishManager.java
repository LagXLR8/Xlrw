package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.schematic.Schematic;
import com.xlrw.printer.PrinterMovement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Autonomously detects and breaks incorrectly placed or obstructive blocks using a pickaxe/tool.
 */
public class DemolishManager {
    private static final DemolishManager INSTANCE = new DemolishManager();

    private BlockPos activeBreakPos = null;
    private int breakTicks = 0;
    private Direction breakFace = Direction.UP;
    private BlockPos collectingPos = null;
    private int collectTicks = 0;

    public static DemolishManager getInstance() {
        return INSTANCE;
    }

    public boolean isBreaking() {
        return activeBreakPos != null || collectingPos != null;
    }

    public boolean isCollecting() {
        return collectingPos != null;
    }

    public BlockPos getActiveBreakPos() {
        return activeBreakPos;
    }

    public void stopBreaking(Minecraft mc) {
        if (activeBreakPos != null && mc != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        activeBreakPos = null;
        breakTicks = 0;
        collectingPos = null;
        collectTicks = 0;
    }

    /**
     * Checks if a block in the world is placed incorrectly according to the schematic.
     */
    public static boolean isMisplaced(ClientLevel level, Schematic schematic, BlockPos pos) {
        if (level == null || schematic == null || pos == null) return false;

        // Active support beams holding unplaced floating blocks MUST NEVER be broken!
        if (SupportBeamManager.getInstance().isProtectedSupportBlock(pos)) return false;

        BlockState worldState = level.getBlockState(pos);
        if (worldState.isAir() || BuildRules.isLiquid(worldState)) return false;

        // Never attempt to break unbreakable blocks (bedrock, etc.)
        if (worldState.getDestroySpeed(level, pos) < 0) return false;

        BlockState expected = schematic.getBlock(pos);

        // 1. Expected air/nothing, but an obstructive block is present
        if (expected == null || expected.isAir()) {
            // Temporary scaffolding in empty space outside structure can remain unless blocking build
            if (worldState.is(Blocks.SCAFFOLDING)) return false;
            return true;
        }

        // 2. Expected a block, but a completely wrong block type is placed (e.g. dirt or stone instead of glass)
        if (worldState.getBlock() != expected.getBlock()) {
            return true;
        }

        // 3. Same block, but orientation cannot be adjusted via right-click (e.g. stairs facing wrong way, slab at wrong half)
        if (!BuildRules.orientationMatches(worldState, expected) && !BlockStateAdjuster.needsAdjustment(worldState, expected)) {
            return true;
        }

        return false;
    }

    /**
     * Finds the nearest misplaced block on the active layer that is within reach distance.
     */
    public BlockPos findMisplacedBlock(Minecraft mc, LocalPlayer player, Schematic schematic, int activeLayerY) {
        if (mc.level == null || player == null || schematic == null) return null;

        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.autoDemolish) return null;

        Vec3 eyePos = player.getEyePosition();
        double maxReach = config.reachDistance;
        double maxReachSq = maxReach * maxReach;

        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        List<BlockPos> misplacedList = new ArrayList<>();

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();
            if (isMisplaced(mc.level, schematic, pos)) {
                if (pos.distToCenterSqr(eyePos) <= maxReachSq) {
                    misplacedList.add(pos);
                }
            }
        }

        if (misplacedList.isEmpty()) return null;

        // If an active 3x3 zone exists, prioritize misplaced blocks in that zone first
        ZoneCoord activeZone = WorkZoneManager.getInstance().getActiveZone();
        if (activeZone != null) {
            for (BlockPos p : misplacedList) {
                if (activeZone.contains(p)) return p;
            }
        }

        // Sort by closest to player
        misplacedList.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(eyePos)));
        return misplacedList.get(0);
    }

    /**
     * Ticks the demolition state machine every client tick.
     * @return true if currently breaking a block or collecting dropped items.
     */
    public boolean tickBreaking(Minecraft mc, LocalPlayer player) {
        if (mc.level == null || player == null || mc.gameMode == null) {
            stopBreaking(mc);
            return false;
        }

        // 0. Item Pickup Phase: vacuum dropped items from previously demolished block
        if (collectingPos != null) {
            collectTicks++;
            if (collectTicks > 30 || player.isCreative()) {
                collectingPos = null;
                collectTicks = 0;
                return false;
            }

            // Search for dropped ItemEntities around collectingPos
            AABB searchBox = new AABB(collectingPos).inflate(3.5);
            List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, searchBox, ItemEntity::isAlive);

            if (items.isEmpty()) {
                // Wait at least 6 ticks to allow item drop packets to arrive from server
                if (collectTicks >= 6) {
                    collectingPos = null;
                    collectTicks = 0;
                    return false;
                }
                return true;
            }

            // Move towards the closest dropped item to pick it up
            ItemEntity closest = null;
            double minDistSq = Double.MAX_VALUE;
            for (ItemEntity item : items) {
                double dSq = player.distanceToSqr(item);
                if (dSq < minDistSq) {
                    minDistSq = dSq;
                    closest = item;
                }
            }

            if (closest != null) {
                if (minDistSq > 1.2) {
                    PrinterMovement.moveTo(mc, closest.position(), 0.5);
                }
            }
            return true;
        }

        if (activeBreakPos == null) {
            return false;
        }

        BlockState state = mc.level.getBlockState(activeBreakPos);

        // 1. Block is successfully broken!
        if (state.isAir()) {
            mc.gameMode.stopDestroyBlock();
            TaskTracker.getInstance().clearPending();
            StationPlanner.getInstance().dropPlan();
            PlacementFinder.recordBadPlacementSpot(activeBreakPos, player.blockPosition());
            StationPlanner.getInstance().recordBadPlacementStation(activeBreakPos, player.blockPosition());
            collectingPos = activeBreakPos;
            collectTicks = 0;
            activeBreakPos = null;
            breakTicks = 0;
            return true; // continue into item collection phase!
        }

        // 2. Range safety: if moved out of reach, abort
        Vec3 eyePos = player.getEyePosition();
        double distSq = activeBreakPos.distToCenterSqr(eyePos);
        double maxReach = BuilderConfig.getInstance().reachDistance + 0.6;
        if (distSq > maxReach * maxReach) {
            stopBreaking(mc);
            return false;
        }

        // 3. Timeout safety (e.g. 120 ticks = 6 seconds)
        breakTicks++;
        if (breakTicks > 120) {
            stopBreaking(mc);
            return false;
        }

        // 4. Equip best tool (pickaxe, axe, shovel, etc.)
        ToolHelper.equipBestTool(player, state);

        // 5. Aim directly at block center
        Vec3 blockCenter = Vec3.atCenterOf(activeBreakPos);
        float[] rots = HumanAim.calculateRotations(eyePos, blockCenter);
        HumanAim.stepRotation(player, rots[0], rots[1]);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround()));

        // 6. Execute authentic destroy packets
        breakFace = Direction.getNearest(eyePos.x - blockCenter.x, eyePos.y - blockCenter.y, eyePos.z - blockCenter.z);

        if (breakTicks == 1) {
            mc.gameMode.startDestroyBlock(activeBreakPos, breakFace);
        } else {
            mc.gameMode.continueDestroyBlock(activeBreakPos, breakFace);
        }

        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public boolean startBreaking(Minecraft mc, LocalPlayer player, BlockPos pos) {
        if (pos == null || mc.level == null || player == null) return false;
        PlacementFinder.recordBadPlacementSpot(pos, player.blockPosition());
        StationPlanner.getInstance().recordBadPlacementStation(pos, player.blockPosition());
        collectingPos = null;
        collectTicks = 0;
        activeBreakPos = pos;
        breakTicks = 0;
        Vec3 eyePos = player.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        breakFace = Direction.getNearest(eyePos.x - blockCenter.x, eyePos.y - blockCenter.y, eyePos.z - blockCenter.z);
        return tickBreaking(mc, player);
    }
}
