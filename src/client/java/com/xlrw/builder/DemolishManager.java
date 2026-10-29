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
    private int expectedTicks = 120;
    private final java.util.Map<BlockPos, Long> unbreakableUntil = new java.util.HashMap<>();
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

        // Outside the schematic: not ours to judge (natural ground, chests, our own towers...)
        if (expected == null) return false;

        // 1. Expected air, but an obstructive block is present
        if (expected.isAir()) {
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

        long now = System.currentTimeMillis();
        unbreakableUntil.values().removeIf(t -> t < now);
        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();
            if (unbreakableUntil.containsKey(pos)) continue;
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

        // Water / lava flowed into the cell (or it is a liquid in the first place): nothing to break
        if (!state.isAir() && (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock || state.canBeReplaced())) {
            mc.gameMode.stopDestroyBlock();
            com.xlrw.debug.XlrwDebug.log("DEMOLISH/SKIP", "Ô " + activeBreakPos.toShortString() + " giờ là " + state.getBlock().getName().getString() + ", không cần đập");
            activeBreakPos = null;
            breakTicks = 0;
            return false;
        }

        // 1. Block is successfully broken!
        if (state.isAir()) {
            mc.gameMode.stopDestroyBlock();
            TaskTracker.getInstance().clearPending();
            StationPlanner.getInstance().dropPlan();
            com.xlrw.debug.XlrwDebug.log("DEMOLISH/DONE", "Đã đập xong " + activeBreakPos.toShortString() + " sau " + breakTicks + " tick");
            // The cell is empty now: put the correct block back FIRST, before wandering off to other work
            TaskTracker.getInstance().unskip(activeBreakPos);
            StationPlanner.getInstance().prioritize(activeBreakPos);
            PlacementFinder.prioritize(activeBreakPos);
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

        // 3. Timeout: based on how long THIS block really takes with the best tool we have (obsidian ~9.4s with a
        //    diamond pickaxe). A fixed 6s limit used to abort obsidian every time and restart it from zero forever.
        breakTicks++;
        if (breakTicks == 1) {
            ToolHelper.equipBestTool(player, state);
            float perTick = state.getDestroyProgress(player, mc.level, activeBreakPos);
            expectedTicks = perTick > 0 ? (int) Math.ceil(1.0f / perTick) : Integer.MAX_VALUE;
            if (expectedTicks > 1200) { // > 60s: not breakable in practice with what we carry
                com.xlrw.debug.XlrwDebug.log("DEMOLISH/SKIP", "Không thể đập " + state.getBlock().getName().getString() + " tại "
                        + activeBreakPos.toShortString() + " (cần ~" + (expectedTicks == Integer.MAX_VALUE ? "∞" : expectedTicks / 20 + "s") + " với dụng cụ hiện có)");
                unbreakableUntil.put(activeBreakPos.immutable(), System.currentTimeMillis() + 120_000);
                stopBreaking(mc);
                return false;
            }
            com.xlrw.debug.XlrwDebug.log("DEMOLISH/START", "Đập " + state.getBlock().getName().getString() + " tại "
                    + activeBreakPos.toShortString() + " (dự kiến ~" + String.format("%.1f", expectedTicks / 20.0) + "s)");
        }
        if (breakTicks > expectedTicks * 2 + 40) {
            com.xlrw.debug.XlrwDebug.log("DEMOLISH/TIMEOUT", "Đập " + activeBreakPos.toShortString() + " quá lâu, bỏ qua tạm thời");
            unbreakableUntil.put(activeBreakPos.immutable(), System.currentTimeMillis() + 30_000);
            stopBreaking(mc);
            return false;
        }

        // 4. Equip best tool (pickaxe, axe, shovel, etc.)
        ToolHelper.equipBestTool(player, state);

        // 5. Aim directly at block center
        Vec3 blockCenter = Vec3.atCenterOf(activeBreakPos);
        float[] rots = HumanAim.calculateRotations(eyePos, blockCenter);
        HumanAim.stepRotation(player, rots[0], rots[1]);
        player.connection.send(com.xlrw.compat.MC.rot(player.getYRot(), player.getXRot(), player.onGround()));

        // 6. Execute authentic destroy packets
        breakFace = com.xlrw.compat.MC.nearestDir(eyePos.x - blockCenter.x, eyePos.y - blockCenter.y, eyePos.z - blockCenter.z);

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
        Long until = unbreakableUntil.get(pos);
        if (until != null && until > System.currentTimeMillis()) return false;
        BlockState st = mc.level.getBlockState(pos);
        if (st.isAir() || st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock || st.canBeReplaced()) return false;
        collectingPos = null;
        collectTicks = 0;
        activeBreakPos = pos;
        breakTicks = 0;
        Vec3 eyePos = player.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        breakFace = com.xlrw.compat.MC.nearestDir(eyePos.x - blockCenter.x, eyePos.y - blockCenter.y, eyePos.z - blockCenter.z);
        return tickBreaking(mc, player);
    }
}
