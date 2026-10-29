package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.debug.XlrwDebug;
import com.xlrw.printer.LocalPathfinder;
import com.xlrw.printer.PrinterMovement;
import com.xlrw.printer.ScaffoldManager;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Autonomously resolves floating / mid-air blocks in schematics that lack solid neighbors.
 * Supports:
 * 1. Vertical Scaffolding Column: Erects a scaffolding shaft from the ground up to targetPos.below()
 *    using Vanilla Minecraft side-face stacking. Highly efficient and collapses in a single hit!
 * 2. Vertical Solid Pillar: Erects disposable blocks (Glass, Dirt, Cobblestone, Planks) straight up from the floor.
 * 3. Horizontal Support Beam: Bridges horizontally from the nearest solid anchor to targetPos.below().
 * 
 * Once targetPos is placed in the world, the temporary support blocks are safely demolished.
 */
public class SupportBeamManager {
    private static final SupportBeamManager INSTANCE = new SupportBeamManager();

    public enum SupportType {
        VERTICAL_SCAFFOLD,
        VERTICAL_SOLID,
        HORIZONTAL_BEAM
    }

    // Maps each active floating targetPos -> the full set of beam/pillar blocks built to support it.
    // As long as targetPos has NOT been confirmed placed in the world, EVERY block in this set is 100% PROTECTED.
    private final Map<BlockPos, Set<BlockPos>> beamForTarget = new HashMap<>();

    // Support blocks whose targets are completely finished and are ready to be safely demolished.
    private final Set<BlockPos> pendingDemolish = new HashSet<>();

    private final Map<BlockPos, Long> failedTargets = new HashMap<>();
    private final Map<BlockPos, Integer> stepAttempts = new HashMap<>();

    private BlockPos currentFloatingTarget = null;
    private SupportType currentSupportType = null;
    private BlockPos currentGroundPos = null;
    private List<BlockPos> currentSupportPath = new ArrayList<>();
    private long lastActionMs = 0;
    private long currentTargetStartMs = 0;

    public static SupportBeamManager getInstance() {
        return INSTANCE;
    }

    public boolean isProtectedSupportBlock(BlockPos pos) {
        if (pos == null) return false;
        for (Set<BlockPos> beamBlocks : beamForTarget.values()) {
            if (beamBlocks.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasActiveFloatingTarget() {
        return currentFloatingTarget != null;
    }

    public void clear() {
        beamForTarget.clear();
        pendingDemolish.clear();
        failedTargets.clear();
        stepAttempts.clear();
        currentFloatingTarget = null;
        currentSupportType = null;
        currentGroundPos = null;
        currentSupportPath.clear();
        currentTargetStartMs = 0;
    }

    /**
     * Handles floating support construction or cleanup for the active layer.
     * @return true if currently handling a support action (building or demolishing finished support)
     */
    public boolean handleFloatingSupport(Minecraft mc, LocalPlayer player, Schematic schematic, int activeLayerY) {
        if (mc.level == null || player == null || schematic == null) return false;
        long now = System.currentTimeMillis();

        // 1. Cleanup Phase: Demolish any support blocks whose floating targets have been placed
        if (cleanFinishedSupports(mc, player, schematic)) {
            return true;
        }

        // Timeout check: If building support for currentFloatingTarget took > 10000ms, abort it!
        if (currentFloatingTarget != null && now - currentTargetStartMs > 10000) {
            XlrwDebug.log("SUPPORT/TIMEOUT", "Support timeout (>10s) cho " + currentFloatingTarget.toShortString() + ". Hủy bỏ và đưa vào blacklist!");
            failedTargets.put(currentFloatingTarget.immutable(), now + 15_000);
            currentFloatingTarget = null;
            currentSupportType = null;
            currentGroundPos = null;
            currentSupportPath.clear();
        }

        // 2. Validate current floating target: only mark finished when the block is ACTUALLY placed!
        if (currentFloatingTarget != null) {
            BlockState inWorld = mc.level.getBlockState(currentFloatingTarget);
            BlockState expected = schematic.getBlock(currentFloatingTarget);
            if (expected != null && inWorld.getBlock() == expected.getBlock()) {
                // Target has been successfully placed in the world!
                Set<BlockPos> finishedBeam = beamForTarget.remove(currentFloatingTarget);
                if (finishedBeam != null) {
                    pendingDemolish.addAll(finishedBeam);
                }
                currentFloatingTarget = null;
                currentSupportType = null;
                currentGroundPos = null;
                currentSupportPath.clear();
                currentTargetStartMs = 0;
            }
        }

        // 3. Find next floating block needing support on current layer
        if (currentFloatingTarget == null) {
            currentFloatingTarget = findFloatingBlockOnLayer(mc.level, player, schematic, activeLayerY);
            if (currentFloatingTarget == null) {
                return false;
            }
            currentTargetStartMs = now;
            if (!planSupportForTarget(mc.level, player, currentFloatingTarget)) {
                failedTargets.put(currentFloatingTarget.immutable(), now + 15_000);
                currentFloatingTarget = null;
                return false;
            }
        }

        // 4. Execute the support construction plan
        if (currentSupportType == SupportType.VERTICAL_SCAFFOLD) {
            return executeVerticalScaffold(mc, player, currentFloatingTarget, currentGroundPos, now);
        } else if (currentSupportType == SupportType.VERTICAL_SOLID) {
            return executeVerticalSolid(mc, player, currentFloatingTarget, currentSupportPath, now);
        } else {
            return executeHorizontalBeam(mc, player, currentFloatingTarget, currentSupportPath, now);
        }
    }

    /**
     * Finds the closest missing block on the active layer that has NO solid neighbor anywhere in the world.
     */
    public BlockPos findFloatingBlockOnLayer(ClientLevel level, LocalPlayer player, Schematic schematic, int activeLayerY) {
        List<Map.Entry<BlockPos, BlockState>> entries = schematic.getBlocksAtY(activeLayerY);
        Vec3 playerPos = player.position();

        BlockPos best = null;
        double minDstSq = Double.MAX_VALUE;

        for (Map.Entry<BlockPos, BlockState> e : entries) {
            BlockPos pos = e.getKey();
            Long failUntil = failedTargets.get(pos);
            if (failUntil != null) {
                if (System.currentTimeMillis() < failUntil) continue;
                failedTargets.remove(pos);
            }
            BlockState expected = e.getValue();
            if (expected == null || expected.isAir() || BuildRules.isSkipped(expected)) continue;

            BlockState inWorld = level.getBlockState(pos);
            if (inWorld.getBlock() == expected.getBlock()) continue; // Already placed

            // Floating check: has NO solid neighbor anywhere in the world
            if (!BuilderController.hasSolidNeighbor(level, pos)) {
                // Also verify that the block has NO planned solid neighbor in the schematic!
                // If the schematic has adjacent solid blocks, they will support it once built.
                boolean hasSchematicSupport = false;
                for (Direction d : Direction.values()) {
                    BlockState sn = schematic.getBlock(pos.relative(d));
                    if (sn != null && !sn.isAir() && !BuildRules.isLiquid(sn) && !(sn.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) {
                        hasSchematicSupport = true;
                        break;
                    }
                }
                if (hasSchematicSupport) continue;

                double dstSq = pos.distToCenterSqr(playerPos);
                if (dstSq < minDstSq) {
                    minDstSq = dstSq;
                    best = pos;
                }
            }
        }

        return best;
    }

    /**
     * Formulates the best support strategy (Vertical Scaffold, Vertical Solid, or Horizontal Beam).
     */
    private boolean planSupportForTarget(ClientLevel level, LocalPlayer player, BlockPos targetPos) {
        BlockPos supportTarget = targetPos.below();
        BuilderConfig config = BuilderConfig.getInstance();

        // 1. Check for solid ground beneath supportTarget (up to 15 blocks down)
        BlockPos ground = findGroundBeneath(level, supportTarget, 15);
        if (ground != null) {
            boolean shaftClear = isVerticalShaftClear(level, ground, supportTarget);
            List<BlockPos> shaft = new ArrayList<>();
            for (int y = ground.getY() + 1; y <= supportTarget.getY(); y++) {
                shaft.add(new BlockPos(supportTarget.getX(), y, supportTarget.getZ()));
            }
            // Never put temporary support inside cells that belong to the build (it would be demolished again)
            if (shaftClear && !touchesSchematic(shaft)) {
                currentGroundPos = ground;
                currentSupportPath.clear();
                currentSupportPath.addAll(shaft);

                // Prefer Scaffolding if enabled and available in player inventory
                if (config.useScaffolding && InventoryHelper.hasScaffolding(player)) {
                    currentSupportType = SupportType.VERTICAL_SCAFFOLD;
                    XlrwDebug.log("SUPPORT/PLAN", "Lập kế hoạch VERTICAL_SCAFFOLD cho " + targetPos.toShortString() 
                            + " từ sàn " + ground.toShortString() + " lên Y=" + supportTarget.getY());
                    return true;
                }

                // Fallback to Solid Pillar (Glass / Dirt / Cobblestone / Planks)
                Item solidItem = InventoryHelper.getAvailableSolidSupportItem(player);
                if (solidItem != null) {
                    currentSupportType = SupportType.VERTICAL_SOLID;
                    XlrwDebug.log("SUPPORT/PLAN", "Lập kế hoạch VERTICAL_SOLID cho " + targetPos.toShortString() 
                            + " từ sàn " + ground.toShortString() + " lên Y=" + supportTarget.getY());
                    return true;
                }
            }
        }

        // 2. Horizontal Beam fallback from nearest solid anchor
        BlockPos anchor = findNearestSolidAnchor(level, supportTarget, supportTarget.getY());
        if (anchor != null) {
            currentSupportType = SupportType.HORIZONTAL_BEAM;
            currentSupportPath = planHorizontalBeamPath(level, anchor, supportTarget);
            if (!currentSupportPath.isEmpty() && !touchesSchematic(currentSupportPath)) {
                XlrwDebug.log("SUPPORT/PLAN", "Lập kế hoạch HORIZONTAL_BEAM cho " + targetPos.toShortString() 
                        + " từ anchor " + anchor.toShortString() + " (dài " + currentSupportPath.size() + " blocks)");
                return true;
            }
        }

        XlrwDebug.log("SUPPORT/FAIL", "Không tìm thấy phương án support nào khả thi cho " + targetPos.toShortString());
        return false;
    }

    /**
     * Executes the construction of a vertical scaffolding column from ground up to supportTarget.
     */
    private boolean executeVerticalScaffold(Minecraft mc, LocalPlayer player, BlockPos targetPos, BlockPos ground, long now) {
        ClientLevel level = mc.level;
        if (level == null || ground == null) return false;

        BlockPos supportTarget = targetPos.below();
        BlockPos basePos = ground.above();
        int targetTopY = supportTarget.getY();

        int currentTopY = ScaffoldManager.getInstance().getColumnTopY(mc, basePos);
        if (currentTopY >= targetTopY) {
            // Scaffolding column reached supportTarget! Support is ready directly underneath targetPos!
            beamForTarget.computeIfAbsent(targetPos.immutable(), k -> new HashSet<>()).addAll(currentSupportPath);
            return guidePlayerToPlaceSpot(mc, player, targetPos, supportTarget);
        }

        // If player is already within comfortable reach of basePos (<= 3.2m), place immediately without moving!
        double distToBase = player.getEyePosition().distanceTo(Vec3.atCenterOf(basePos));
        if (distToBase > 3.2) {
            BlockPos standPos = findStandPosNear(level, player, basePos);
            if (standPos != null && player.position().distanceTo(Vec3.atCenterOf(standPos)) > 1.2) {
                PrinterMovement.moveTo(mc, new Vec3(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5), 0.45);
                BuilderController.getInstance().setStatusMessage("3D Printer: Di chuyển tới chân cột giàn giáo tại " + basePos.toShortString());
                return true;
            }
        }

        // Equip scaffolding
        InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, Items.SCAFFOLDING);
        if (eq == InventoryHelper.EquipResult.SWITCHED || !player.getMainHandItem().is(Items.SCAFFOLDING)) {
            return true;
        }

        if (now - lastActionMs < 180) {
            return true;
        }

        if (currentTopY < basePos.getY()) {
            // Base scaffolding block doesn't exist yet: place it on the top face of ground!
            Vec3 hitVec = new Vec3(basePos.getX() + 0.5, ground.getY() + 1.0, basePos.getZ() + 0.5);
            float[] rots = HumanAim.calculateRotations(player.getEyePosition(), hitVec);
            player.setYRot(rots[0]);
            player.setXRot(rots[1]);
            player.connection.send(com.xlrw.compat.MC.rot(rots[0], rots[1], player.onGround()));

            mc.options.keyShift.setDown(false);
            if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

            BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, ground, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
            player.swing(InteractionHand.MAIN_HAND);
            lastActionMs = now;
            BuilderController.getInstance().setStatusMessage("3D Printer: Đặt chân giàn giáo tại " + basePos.toShortString());
            return true;
        } else {
            // Base scaffolding exists! In Vanilla Minecraft, click on a SIDE face without sneak
            // to automatically stack the scaffolding onto the very top of the column!
            Direction sideFace = com.xlrw.compat.MC.nearestDir((float)(player.getX() - (basePos.getX() + 0.5)), 0.0f, (float)(player.getZ() - (basePos.getZ() + 0.5)));
            if (sideFace.getAxis() == Direction.Axis.Y) sideFace = Direction.NORTH;

            Vec3 hitVec = new Vec3(
                    basePos.getX() + 0.5 + sideFace.getStepX() * 0.49,
                    basePos.getY() + 0.5,
                    basePos.getZ() + 0.5 + sideFace.getStepZ() * 0.49
            );

            float[] rots = HumanAim.calculateRotations(player.getEyePosition(), hitVec);
            player.setYRot(rots[0]);
            player.setXRot(rots[1]);
            player.connection.send(com.xlrw.compat.MC.rot(rots[0], rots[1], player.onGround()));

            mc.options.keyShift.setDown(false);
            if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

            BlockHitResult bhr = new BlockHitResult(hitVec, sideFace, basePos, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
            player.swing(InteractionHand.MAIN_HAND);
            lastActionMs = now;
            BuilderController.getInstance().setStatusMessage("3D Printer: Nâng giàn giáo lên Y=" + (currentTopY + 1));
            return true;
        }
    }

    /**
     * Executes the construction of a vertical solid pillar (Glass/Dirt/Cobble) from ground up to supportTarget.
     */
    private boolean executeVerticalSolid(Minecraft mc, LocalPlayer player, BlockPos targetPos, List<BlockPos> path, long now) {
        ClientLevel level = mc.level;
        if (level == null || path.isEmpty()) return false;

        BlockPos supportTarget = targetPos.below();

        int unplacedIdx = -1;
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() || BuildRules.isLiquid(s)) {
                unplacedIdx = i;
                break;
            }
        }

        if (unplacedIdx == -1) {
            // Whole pillar is solid!
            beamForTarget.computeIfAbsent(targetPos.immutable(), k -> new HashSet<>()).addAll(path);
            return guidePlayerToPlaceSpot(mc, player, targetPos, supportTarget);
        }

        BlockPos step = path.get(unplacedIdx);
        BlockPos prev = (unplacedIdx == 0) ? currentGroundPos : path.get(unplacedIdx - 1);
        if (prev == null) prev = step.below();

        // Player must not collide with step
        if (player.getBoundingBox().intersects(new AABB(step))) {
            BlockPos stand = findStandPosNear(level, player, step);
            if (stand != null) {
                PrinterMovement.moveTo(mc, new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), 0.45);
            }
            return true;
        }

        // Distance check: only move if eyes are out of reach (> 3.2m)
        double distToPrev = player.getEyePosition().distanceTo(Vec3.atCenterOf(prev));
        if (distToPrev > 3.2) {
            BlockPos stand = findStandPosNear(level, player, step);
            if (stand != null && player.position().distanceTo(Vec3.atCenterOf(stand)) > 1.2) {
                PrinterMovement.moveTo(mc, new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), 0.5);
                BuilderController.getInstance().setStatusMessage("3D Printer: Di chuyển tới cột support tại " + step.toShortString());
                return true;
            }
        }

        Item supportItem = InventoryHelper.getAvailableSolidSupportItem(player);
        if (supportItem == null) {
            BuilderController.getInstance().setStatusMessage("3D Printer: Cần khối support (Glass/Dirt/Cobble/Planks)!");
            return false;
        }

        InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, supportItem);
        if (eq == InventoryHelper.EquipResult.SWITCHED || !player.getMainHandItem().is(supportItem)) {
            return true;
        }

        if (now - lastActionMs < 180) return true;

        Vec3 hitVec = new Vec3(prev.getX() + 0.5, prev.getY() + 1.0, prev.getZ() + 0.5);
        float[] rots = HumanAim.calculateRotations(player.getEyePosition(), hitVec);
        player.setYRot(rots[0]);
        player.setXRot(rots[1]);
        player.connection.send(com.xlrw.compat.MC.rot(rots[0], rots[1], player.onGround()));

        mc.options.keyShift.setDown(false);
        if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, prev, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
        player.swing(InteractionHand.MAIN_HAND);
        lastActionMs = now;

        beamForTarget.computeIfAbsent(targetPos.immutable(), k -> new HashSet<>()).add(step.immutable());
        String itemName = supportItem.getName(new ItemStack(supportItem)).getString();
        BuilderController.getInstance().setStatusMessage("3D Printer: Đặt trụ support (" + itemName + ") tại " + step.toShortString());
        return true;
    }

    /**
     * Executes the construction of a horizontal support beam from solid anchor to supportTarget.
     */
    private boolean executeHorizontalBeam(Minecraft mc, LocalPlayer player, BlockPos targetPos, List<BlockPos> path, long now) {
        ClientLevel level = mc.level;
        if (level == null || path.isEmpty()) return false;

        BlockPos supportTarget = targetPos.below();

        int unplacedIdx = -1;
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() || BuildRules.isLiquid(s)) {
                unplacedIdx = i;
                break;
            }
        }

        if (unplacedIdx == -1) {
            beamForTarget.computeIfAbsent(targetPos.immutable(), k -> new HashSet<>()).addAll(path);
            return guidePlayerToPlaceSpot(mc, player, targetPos, supportTarget);
        }

        BlockPos step = path.get(unplacedIdx);
        BlockPos prev = (unplacedIdx == 0) ? findNearestSolidAnchor(level, step, step.getY()) : path.get(unplacedIdx - 1);
        if (prev == null) prev = player.blockPosition().below();

        int manhattan = Math.abs(step.getX() - prev.getX()) + Math.abs(step.getY() - prev.getY()) + Math.abs(step.getZ() - prev.getZ());
        if (manhattan != 1) {
            XlrwDebug.log("SUPPORT/FAIL", "Horizontal step " + step.toShortString() + " không chạm mặt prev " + prev.toShortString());
            failedTargets.put(targetPos.immutable(), now + 15_000);
            currentFloatingTarget = null;
            currentSupportPath.clear();
            return false;
        }

        if (player.getBoundingBox().intersects(new AABB(step))) {
            PrinterMovement.moveTo(mc, new Vec3(prev.getX() + 0.5, prev.getY() + 1.0, prev.getZ() + 0.5), 0.5);
            return true;
        }

        double distToPrev = player.position().distanceTo(Vec3.atCenterOf(prev));
        if (distToPrev > 2.8) {
            PrinterMovement.moveTo(mc, new Vec3(prev.getX() + 0.5, prev.getY() + 1.0, prev.getZ() + 0.5), 1.2);
            BuilderController.getInstance().setStatusMessage("3D Printer: Di chuyển trên thanh dầm tới " + step.toShortString());
            return true;
        }

        Item supportItem = InventoryHelper.getAvailableSolidSupportItem(player);
        if (supportItem == null) {
            BuilderController.getInstance().setStatusMessage("3D Printer: Cần khối solid support để bắc dầm ngang!");
            return false;
        }

        InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, supportItem);
        if (eq == InventoryHelper.EquipResult.SWITCHED || !player.getMainHandItem().is(supportItem)) {
            return true;
        }

        if (now - lastActionMs < 180) return true;

        int dx = step.getX() - prev.getX();
        int dy = step.getY() - prev.getY();
        int dz = step.getZ() - prev.getZ();
        Direction clickFace = com.xlrw.compat.MC.dirFromDelta(dx, dy, dz);
        if (clickFace == null) clickFace = Direction.UP;

        Vec3 hitVec = new Vec3(
                prev.getX() + 0.5 + clickFace.getStepX() * 0.48,
                prev.getY() + 0.5 + clickFace.getStepY() * 0.48,
                prev.getZ() + 0.5 + clickFace.getStepZ() * 0.48
        );

        float[] rots = HumanAim.calculateRotations(player.getEyePosition(), hitVec);
        player.setYRot(rots[0]);
        player.setXRot(rots[1]);
        player.connection.send(com.xlrw.compat.MC.rot(rots[0], rots[1], player.onGround()));

        mc.options.keyShift.setDown(false);
        if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

        BlockHitResult bhr = new BlockHitResult(hitVec, clickFace, prev, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
        player.swing(InteractionHand.MAIN_HAND);
        lastActionMs = now;

        int attempts = stepAttempts.merge(step, 1, Integer::sum);
        if (attempts >= 4) {
            failedTargets.put(targetPos.immutable(), now + 15_000);
            stepAttempts.remove(step);
            currentFloatingTarget = null;
            currentSupportPath.clear();
            return false;
        }

        beamForTarget.computeIfAbsent(targetPos.immutable(), k -> new HashSet<>()).add(step.immutable());
        String itemName = supportItem.getName(new ItemStack(supportItem)).getString();
        BuilderController.getInstance().setStatusMessage("3D Printer: Đặt dầm support (" + itemName + ") tại " + step.toShortString());
        return true;
    }

    /**
     * Once support is ready underneath targetPos, steers the player into valid placement reach.
     * Returns false when player is already positioned in reach and ready to place targetPos!
     */
    private boolean guidePlayerToPlaceSpot(Minecraft mc, LocalPlayer player, BlockPos targetPos, BlockPos supportTarget) {
        ClientLevel level = mc.level;
        if (level == null) return false;

        // Verify if player is already within comfortable reach (1.2m <= distance <= 3.8m)
        // and NOT intersecting targetPos!
        double distToTarget = player.getEyePosition().distanceTo(Vec3.atCenterOf(targetPos));
        boolean intersects = player.getBoundingBox().intersects(new AABB(targetPos));

        if (!intersects && distToTarget >= 1.2 && distToTarget <= 3.8) {
            PrinterMovement.stop(mc);
            return false; // Stand position verified! Let PlacementFinder place targetPos immediately!
        }

        // Search for a standable spot around targetPos
        BlockPos bestSpot = null;
        double minScore = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos cand = targetPos.offset(dx, dy, dz);
                    if (cand.equals(targetPos) || cand.above().equals(targetPos)) continue;

                    if (LocalPathfinder.isPassable(level, cand)
                            && LocalPathfinder.isPassable(level, cand.above())
                            && LocalPathfinder.isStandable(level, cand.below())) {

                        Vec3 eyeCand = new Vec3(cand.getX() + 0.5, cand.getY() + 1.62, cand.getZ() + 0.5);
                        double d = eyeCand.distanceTo(Vec3.atCenterOf(targetPos));
                        if (d >= 1.2 && d <= 3.8) {
                            double playerDist = player.position().distanceTo(Vec3.atCenterOf(cand));
                            if (playerDist < minScore) {
                                minScore = playerDist;
                                bestSpot = cand;
                            }
                        }
                    }
                }
            }
        }

        if (bestSpot != null) {
            double d = player.position().distanceTo(Vec3.atCenterOf(bestSpot));
            if (d > 1.2) {
                PrinterMovement.moveTo(mc, new Vec3(bestSpot.getX() + 0.5, bestSpot.getY(), bestSpot.getZ() + 0.5), 0.45);
                BuilderController.getInstance().setStatusMessage("3D Printer: Di chuyển tới vị trí đặt " + targetPos.toShortString());
                return true;
            }
        }

        PrinterMovement.stop(mc);
        return false;
    }

    /**
     * Safely demolishes support blocks that are in pendingDemolish (all targets above them are 100% placed).
     * If the support was a vertical scaffolding column, breaking the single base block collapses the whole shaft!
     */
    private boolean cleanFinishedSupports(Minecraft mc, LocalPlayer player, Schematic schematic) {
        ClientLevel level = mc.level;
        if (level == null || pendingDemolish.isEmpty()) return false;

        // Clean out blocks that are already air
        pendingDemolish.removeIf(p -> level.getBlockState(p).isAir() || BuildRules.isLiquid(level.getBlockState(p)));
        if (pendingDemolish.isEmpty()) return false;

        BlockPos playerStand = player.blockPosition().below();
        BlockPos playerFeet = player.blockPosition();
        Vec3 eyePos = player.getEyePosition();

        // 1. Scaffolding Fast-Collapse: Find any base scaffolding block
        for (BlockPos sPos : pendingDemolish) {
            if (level.getBlockState(sPos).is(Blocks.SCAFFOLDING)) {
                // Find lowest scaffolding block in this vertical column
                BlockPos base = sPos;
                while (level.getBlockState(base.below()).is(Blocks.SCAFFOLDING)) {
                    base = base.below();
                }

                if (base.equals(playerStand) || base.equals(playerFeet)) continue;
                if (isProtectedSupportBlock(base)) continue;

                if (base.distToCenterSqr(eyePos) <= 20.25) { // Within 4.5m reach
                    DemolishManager.getInstance().startBreaking(mc, player, base);
                    // Remove all scaffolding in this column from pendingDemolish
                    BlockPos finalBase = base;
                    pendingDemolish.removeIf(p -> p.getX() == finalBase.getX() && p.getZ() == finalBase.getZ() && level.getBlockState(p).is(Blocks.SCAFFOLDING));
                    BuilderController.getInstance().setStatusMessage("3D Printer: Thu hồi giàn giáo tại chân trụ " + base.toShortString());
                    return true;
                }
            }
        }

        // 2. Normal disposable solid blocks: demolish individual blocks
        BlockPos bestDemolish = null;
        double minDstSq = Double.MAX_VALUE;

        for (BlockPos sPos : pendingDemolish) {
            if (sPos.equals(playerStand) || sPos.equals(playerFeet)) continue;
            if (isProtectedSupportBlock(sPos)) continue;

            BlockState sState = level.getBlockState(sPos);
            if (sState.isAir() || BuildRules.isLiquid(sState)) continue;

            double d = sPos.distToCenterSqr(eyePos);
            if (d <= 20.25) {
                if (d < minDstSq) {
                    minDstSq = d;
                    bestDemolish = sPos;
                }
            }
        }

        if (bestDemolish != null) {
            DemolishManager.getInstance().startBreaking(mc, player, bestDemolish);
            pendingDemolish.remove(bestDemolish);
            BuilderController.getInstance().setStatusMessage("3D Printer: Dọn dẹp khối support tại " + bestDemolish.toShortString());
            return true;
        }

        return false;
    }

    private static boolean touchesSchematic(List<BlockPos> cells) {
        Schematic sch = com.xlrw.schematic.SchematicManager.getInstance().getActiveSchematic();
        if (sch == null) return false;
        for (BlockPos p : cells) {
            BlockState exp = sch.getBlock(p);
            if (exp != null && !BuildRules.isSkipped(exp)) return true;
        }
        return false;
    }

    public static BlockPos findGroundBeneath(ClientLevel level, BlockPos pos, int maxDepth) {
        if (level == null || pos == null) return null;
        int startY = pos.getY() - 1;
        int minY = Math.max(com.xlrw.compat.MC.minY(level) + 1, pos.getY() - maxDepth);
        for (int y = startY; y >= minY; y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState s = level.getBlockState(check);
            if (!s.isAir() && !s.canBeReplaced() && !BuildRules.isLiquid(s)) {
                return check;
            }
        }
        return null;
    }

    public static boolean isVerticalShaftClear(ClientLevel level, BlockPos ground, BlockPos top) {
        if (level == null || ground == null || top == null) return false;
        for (int y = ground.getY() + 1; y <= top.getY(); y++) {
            BlockPos p = new BlockPos(top.getX(), y, top.getZ());
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.canBeReplaced() && !s.is(Blocks.SCAFFOLDING)) {
                return false;
            }
        }
        return true;
    }

    private BlockPos findStandPosNear(ClientLevel level, LocalPlayer player, BlockPos basePos) {
        BlockPos playerFoot = player.blockPosition();
        BlockPos best = null;
        double minDst = Double.MAX_VALUE;

        // Check 4 direct cardinal neighbors first
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction d : dirs) {
            BlockPos p = basePos.relative(d);
            if (LocalPathfinder.isPassable(level, p)
                    && LocalPathfinder.isPassable(level, p.above())
                    && LocalPathfinder.isStandable(level, p.below())) {
                boolean reachable = PrinterMovement.canWalkDirectly(level, player.position(), p)
                        || LocalPathfinder.hasPath(level, playerFoot, p, 200);
                if (reachable) {
                    double dst = p.distSqr(playerFoot);
                    if (dst < minDst) {
                        minDst = dst;
                        best = p;
                    }
                }
            }
        }
        if (best != null) return best;

        // Check radius 2
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                BlockPos p = new BlockPos(basePos.getX() + dx, basePos.getY(), basePos.getZ() + dz);
                if (LocalPathfinder.isPassable(level, p)
                        && LocalPathfinder.isPassable(level, p.above())
                        && LocalPathfinder.isStandable(level, p.below())) {
                    boolean reachable = PrinterMovement.canWalkDirectly(level, player.position(), p)
                            || LocalPathfinder.hasPath(level, playerFoot, p, 200);
                    if (reachable) {
                        double dst = p.distSqr(playerFoot);
                        if (dst < minDst) {
                            minDst = dst;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findNearestSolidAnchor(ClientLevel level, BlockPos supportTarget, int beamY) {
        BlockPos best = null;
        double minDstSq = Double.MAX_VALUE;

        for (int r = 1; r <= 15; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos p = new BlockPos(supportTarget.getX() + dx, beamY + dy, supportTarget.getZ() + dz);
                        BlockState s = level.getBlockState(p);
                        if (!s.isAir() && !s.canBeReplaced() && !BuildRules.isLiquid(s)) {
                            double d = p.distSqr(supportTarget);
                            if (d < minDstSq) {
                                minDstSq = d;
                                best = p;
                            }
                        }
                    }
                }
            }
            if (best != null && minDstSq <= (r + 1) * (r + 1)) {
                return best;
            }
        }
        return best;
    }

    private List<BlockPos> planHorizontalBeamPath(ClientLevel level, BlockPos anchor, BlockPos supportTarget) {
        List<BlockPos> path = new ArrayList<>();
        int curX = anchor.getX();
        int curY = anchor.getY();
        int curZ = anchor.getZ();
        int targetY = supportTarget.getY();

        // 1. Step up vertically at anchor to reach beam elevation if anchor is lower
        while (curY < targetY) {
            curY++;
            path.add(new BlockPos(curX, curY, curZ));
        }

        // 2. Step horizontally along X
        int targetX = supportTarget.getX();
        int stepX = Integer.compare(targetX, curX);
        while (curX != targetX) {
            curX += stepX;
            path.add(new BlockPos(curX, curY, curZ));
        }

        // 3. Step horizontally along Z
        int targetZ = supportTarget.getZ();
        int stepZ = Integer.compare(targetZ, curZ);
        while (curZ != targetZ) {
            curZ += stepZ;
            path.add(new BlockPos(curX, curY, curZ));
        }

        return path;
    }
}
