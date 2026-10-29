package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

public class PlacementFinder {

    private static final Direction[] DIRECTIONS = Direction.values();

    // Tracks standing spots that resulted in misplaced/wrong orientation for target blocks
    private static final Map<BlockPos, Set<BlockPos>> BAD_PLACEMENT_SPOTS = new HashMap<>();
    private static final Map<BlockPos, Long> BAD_PLACEMENT_EXPIRY = new HashMap<>();

    public static void recordBadPlacementSpot(BlockPos targetPos, BlockPos standingSpot) {
        if (targetPos == null || standingSpot == null) return;
        BlockPos tKey = targetPos.immutable();
        BlockPos sKey = standingSpot.immutable();
        BAD_PLACEMENT_SPOTS.computeIfAbsent(tKey, k -> new HashSet<>()).add(sKey);
        BAD_PLACEMENT_EXPIRY.put(tKey, System.currentTimeMillis() + 60_000);
        com.xlrw.debug.XlrwDebug.log("BUILD/BAD_SPOT", "Đã ghi nhận điểm đứng lỗi " + sKey.toShortString() 
                + " cho target " + tKey.toShortString() + ". Bot sẽ không đặt lại block này từ điểm đứng này!");
    }

    public static boolean isBadPlacementSpot(BlockPos targetPos, BlockPos standingSpot) {
        if (targetPos == null || standingSpot == null) return false;
        Long expiry = BAD_PLACEMENT_EXPIRY.get(targetPos);
        if (expiry == null || System.currentTimeMillis() > expiry) {
            BAD_PLACEMENT_SPOTS.remove(targetPos);
            BAD_PLACEMENT_EXPIRY.remove(targetPos);
            return false;
        }
        Set<BlockPos> badSet = BAD_PLACEMENT_SPOTS.get(targetPos);
        if (badSet == null || badSet.isEmpty()) return false;

        for (BlockPos bad : badSet) {
            if (Math.abs(bad.getX() - standingSpot.getX()) <= 1
                    && Math.abs(bad.getZ() - standingSpot.getZ()) <= 1
                    && Math.abs(bad.getY() - standingSpot.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    public static Set<BlockPos> getBadPlacementSpots(BlockPos targetPos) {
        if (targetPos == null) return Collections.emptySet();
        Long expiry = BAD_PLACEMENT_EXPIRY.get(targetPos);
        if (expiry == null || System.currentTimeMillis() > expiry) {
            BAD_PLACEMENT_SPOTS.remove(targetPos);
            BAD_PLACEMENT_EXPIRY.remove(targetPos);
            return Collections.emptySet();
        }
        Set<BlockPos> spots = BAD_PLACEMENT_SPOTS.get(targetPos);
        return spots != null ? spots : Collections.emptySet();
    }

    public static void clearBadPlacementSpot(BlockPos targetPos) {
        if (targetPos != null) {
            BAD_PLACEMENT_SPOTS.remove(targetPos);
            BAD_PLACEMENT_EXPIRY.remove(targetPos);
        }
    }

    public static void clearAllBadPlacementSpots() {
        BAD_PLACEMENT_SPOTS.clear();
        BAD_PLACEMENT_EXPIRY.clear();
    }

    /**
     * Finds the next best placement/adjustment/fluid action.
     * Enforces two strict phases:
     * 1. Build Phase: All solid and decorative blocks (with verified orientation).
     * 2. Fluid Phase: Only after all solid blocks on the layer are built, pours water/lava and waterlogs blocks.
     */
    public static PlacementCandidate findBestCandidate() {
        Schematic schematic = SchematicManager.getInstance().getActiveSchematic();
        if (schematic == null) return null;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return null;

        BuilderConfig config = BuilderConfig.getInstance();
        Vec3 eyePos = player.getEyePosition();
        double maxReach = config.reachDistance;
        double maxReachSq = maxReach * maxReach;

        int minY = schematic.getMinY();
        int activeLayerY = minY + config.currentLayer;
        TaskTracker tracker = TaskTracker.getInstance();

        // Check unfinished status by build priority groups:
        // Priority 1: Normal blocks & redstone
        // Priority 2: Pistons
        // Priority 3: Fluids
        boolean hasUnfinishedNormal = checkHasUnfinishedNormalBlocks(schematic, level, config, activeLayerY, minY);
        boolean hasUnfinishedPistons = checkHasUnfinishedPistons(schematic, level, config, activeLayerY, minY);
        boolean hasMisplacedBlocksOnLayer = config.autoDemolish && DemolishManager.getInstance().findMisplacedBlock(mc, player, schematic, activeLayerY) != null;

        List<PlacementCandidate> candidates = new ArrayList<>();

        // 1. Normal blocks & redstone phase
        findBuildCandidates(level, player, eyePos, schematic, config, activeLayerY, minY, maxReach, maxReachSq, tracker, null, false, candidates);

        // 2. Piston phase: ONLY when normal blocks & redstone on this layer are completed!
        if (candidates.isEmpty() && !hasUnfinishedNormal) {
            findBuildCandidates(level, player, eyePos, schematic, config, activeLayerY, minY, maxReach, maxReachSq, tracker, null, true, candidates);
        }

        // 3. Fluid phase:
        // Prioritize normal blocks and pistons first. However, fluids MUST be allowed if:
        //  a) All normal blocks and pistons on the layer are completed, OR
        //  b) StationPlanner has committed to a FLUID plan (meaning normal blocks cannot be placed right now), OR
        //  c) No normal blocks or pistons can be placed from the current station/reach.
        StationPlanner.Plan currentPlan = StationPlanner.getInstance().getPlan();
        boolean isFluidPlan = currentPlan != null && currentPlan.kind == StationPlanner.Kind.FLUID;
        boolean allowFluids = !hasMisplacedBlocksOnLayer && (
                (!hasUnfinishedNormal && !hasUnfinishedPistons) || isFluidPlan
        );
        if (candidates.isEmpty() && allowFluids) {
            findFluidCandidates(level, player, eyePos, schematic, config, activeLayerY, minY, maxReach, maxReachSq, tracker, candidates);
        }

        if (candidates.isEmpty()) {
            // Diagnostic reporting: why can't we place anything right now?
            int missingCount = 0;
            int outOfReach = 0;
            int missingItem = 0;
            int raycastBlocked = 0;
            int noSupport = 0;
            String sampleReject = null;

            List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                    schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

            for (Map.Entry<BlockPos, BlockState> entry : entries) {
                BlockPos pos = entry.getKey();
                BlockState req = entry.getValue();
                BuildRules.BuildStatus s = BuildRules.buildStatus(level.getBlockState(pos), req);
                if (s == BuildRules.BuildStatus.MISSING || s == BuildRules.BuildStatus.STACKABLE || s == BuildRules.BuildStatus.ADJUST) {
                    missingCount++;
                    double dSq = pos.distToCenterSqr(eyePos);
                    if (dSq > maxReachSq) {
                        outOfReach++;
                    } else {
                        net.minecraft.world.item.Item reqItem = InventoryHelper.getItemForBlockState(req);
                        if (!player.isCreative() && !InventoryHelper.hasItem(player, reqItem) && s != BuildRules.BuildStatus.ADJUST) {
                            missingItem++;
                            if (sampleReject == null) sampleReject = String.format("Thiếu vật phẩm [%s] cho block tại %s", reqItem.getDescription().getString(), pos.toShortString());
                        } else {
                            // Within reach and has item, but rejected: check support / raycast
                            boolean hasSolidNeighbor = false;
                            for (Direction d : DIRECTIONS) {
                                BlockState ns = level.getBlockState(pos.relative(d));
                                if (!ns.isAir() && !ns.canBeReplaced() && !BuildRules.isLiquid(ns)) {
                                    hasSolidNeighbor = true;
                                    break;
                                }
                            }
                            if (!hasSolidNeighbor) {
                                noSupport++;
                                if (sampleReject == null) sampleReject = String.format("Chưa có block tựa (support) liền kề để đặt tại %s [%s]", pos.toShortString(), req.getBlock().getName().getString());
                            } else {
                                raycastBlocked++;
                                if (sampleReject == null) sampleReject = String.format("Góc nhìn/mặt tiếp xúc bị che khuất tại %s [%s]", pos.toShortString(), req.getBlock().getName().getString());
                            }
                        }
                    }
                }
            }

            if (missingCount > 0) {
                String summary = String.format("Layer #%d còn %d block chưa đặt. Tầm với: %d ngoài tầm (dist > %.1fm). Trong tầm: %d thiếu item, %d chưa có support, %d bị cản raycast. Mẫu: %s",
                        config.currentLayer, missingCount, outOfReach, maxReach, missingItem, noSupport, raycastBlocked,
                        sampleReject != null ? sampleReject : "Đang chờ điều kiện đặt");
                com.xlrw.debug.XlrwDebug.logRateLimited("NO_CANDIDATE_LAYER_" + activeLayerY, 2000, "BUILD/NO_CANDIDATE", summary);
            }
            return null;
        }

        // Spatial Clustering: Prioritize blocks adjacent or closest to the block we just placed (lastConfirmedPos)!
        BlockPos lastPlaced = BuilderController.getInstance().getLastConfirmedPos();

        // Strict Sorting:
        // 1. Anti-sealing: Never seal an unplaced neighbor if a non-sealing block is available!
        // 2. Y level: bottom to top
        // 3. Proximity to last placed block (clustering)
        // 4. Distance to player
        // 5. Deterministic tie-break
        candidates.sort(Comparator
                .comparing((PlacementCandidate c) -> BuildRules.wouldSealUnplacedNeighbor(level, schematic, c.targetPos))
                .thenComparingInt((PlacementCandidate c) -> c.targetPos.getY())
                .thenComparingDouble(c -> lastPlaced != null ? c.targetPos.distSqr(lastPlaced) : c.distanceSq)
                .thenComparingDouble(c -> c.distanceSq)
                .thenComparingInt(c -> c.targetPos.getX() * 31 + c.targetPos.getZ())
        );

        return candidates.get(0);
    }

    public static boolean checkHasUnfinishedNormalBlocks(Schematic schematic, ClientLevel level, BuilderConfig config, int activeLayerY, int minY) {
        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockState expected = entry.getValue();
            if (BuildRules.isPiston(expected) || BuildRules.isLiquid(expected) || expected.is(net.minecraft.world.level.block.Blocks.WATER) || expected.is(net.minecraft.world.level.block.Blocks.LAVA)) {
                continue;
            }

            BlockPos pos = entry.getKey();
            BuildRules.BuildStatus status = BuildRules.buildStatus(level.getBlockState(pos), expected);
            if (status == BuildRules.BuildStatus.MISSING || status == BuildRules.BuildStatus.STACKABLE || status == BuildRules.BuildStatus.ADJUST) {
                return true;
            }
        }
        return false;
    }

    public static boolean checkHasUnfinishedPistons(Schematic schematic, ClientLevel level, BuilderConfig config, int activeLayerY, int minY) {
        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockState expected = entry.getValue();
            if (!BuildRules.isPiston(expected)) {
                continue;
            }

            BlockPos pos = entry.getKey();
            BuildRules.BuildStatus status = BuildRules.buildStatus(level.getBlockState(pos), expected);
            if (status == BuildRules.BuildStatus.MISSING || status == BuildRules.BuildStatus.STACKABLE || status == BuildRules.BuildStatus.ADJUST) {
                return true;
            }
        }
        return false;
    }

    public static boolean checkHasUnfinishedBlocks(Schematic schematic, ClientLevel level, BuilderConfig config, int activeLayerY, int minY) {
        return checkHasUnfinishedNormalBlocks(schematic, level, config, activeLayerY, minY)
                || checkHasUnfinishedPistons(schematic, level, config, activeLayerY, minY);
    }

    private static void findBuildCandidates(ClientLevel level, LocalPlayer player, Vec3 eyePos, Schematic schematic,
                                           BuilderConfig config, int activeLayerY, int minY, double maxReach, double maxReachSq,
                                           TaskTracker tracker, ZoneCoord filterZone, boolean pistonsOnly, List<PlacementCandidate> candidates) {
        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        AABB playerBox = player.getBoundingBox().inflate(0.01);

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos targetPos = entry.getKey();

            // Filter zone check: if filterZone is specified, only include blocks inside that 3x3 zone!
            if (filterZone != null && !filterZone.contains(targetPos)) {
                continue;
            }
            BlockState requiredState = entry.getValue();

            // Build priority filter: pistons vs normal blocks
            boolean isPiston = BuildRules.isPiston(requiredState);
            if (pistonsOnly && !isPiston) continue;
            if (!pistonsOnly && isPiston) continue;

            if (tracker.isBlocked(targetPos)) continue;

            // Never place this block from a standing spot where it previously produced a wrong orientation!
            if (isBadPlacementSpot(targetPos, player.blockPosition())) continue;

            BlockState currentInWorld = level.getBlockState(targetPos);
            BuildRules.BuildStatus status = BuildRules.buildStatus(currentInWorld, requiredState);

            if (status == BuildRules.BuildStatus.DONE || status == BuildRules.BuildStatus.IGNORED || status == BuildRules.BuildStatus.BLOCKED) {
                continue;
            }

            // Must have required item in inventory (or creative)
            net.minecraft.world.item.Item requiredItem = InventoryHelper.getItemForBlockState(requiredState);
            if (!player.isCreative() && !InventoryHelper.hasItem(player, requiredItem) && status != BuildRules.BuildStatus.ADJUST) {
                continue;
            }

            // Quick distance prune
            if (targetPos.distToCenterSqr(eyePos) > (maxReach + 2.0) * (maxReach + 2.0)) {
                continue;
            }

            // Case A: State Adjustment (Open/Close, Repeater Delay, Comparator Mode, Lever...)
            if (status == BuildRules.BuildStatus.ADJUST) {
                if (targetPos.distToCenterSqr(eyePos) <= maxReachSq) {
                    Vec3 targetCenter = Vec3.atCenterOf(targetPos);
                    if (config.strictRaycast) {
                        ClipContext clipContext = new ClipContext(eyePos, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
                        BlockHitResult hitResult = level.clip(clipContext);
                        if (hitResult.getType() != HitResult.Type.BLOCK || !hitResult.getBlockPos().equals(targetPos)) {
                            continue;
                        }
                    }
                    float[] rots = HumanAim.calculateRotations(eyePos, targetCenter);
                    if (BlockStateAdjuster.isAdjustYawAcceptable(currentInWorld, requiredState, rots[0])) {
                        candidates.add(new PlacementCandidate(PlacementCandidate.Type.ADJUST, targetPos, requiredState,
                                targetPos, Direction.UP, targetCenter, rots[0], rots[1],
                                targetPos.distToCenterSqr(eyePos), false, null, ClipContext.Fluid.NONE));
                    }
                }
                continue;
            }

            // Case B: Stackable click (double slab, candles, pickles, snow layers...)
            if (status == BuildRules.BuildStatus.STACKABLE) {
                if (targetPos.distToCenterSqr(eyePos) <= maxReachSq) {
                    Vec3 targetCenter = Vec3.atCenterOf(targetPos);
                    if (config.strictRaycast) {
                        ClipContext clipContext = new ClipContext(eyePos, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
                        BlockHitResult hitResult = level.clip(clipContext);
                        if (hitResult.getType() != HitResult.Type.BLOCK || !hitResult.getBlockPos().equals(targetPos)) {
                            continue;
                        }
                    }
                    float[] rots = HumanAim.calculateRotations(eyePos, targetCenter);
                    boolean sneak = BuildRules.isInteractable(currentInWorld);
                    candidates.add(new PlacementCandidate(PlacementCandidate.Type.STACK, targetPos, requiredState,
                            targetPos, Direction.UP, targetCenter, rots[0], rots[1],
                            targetPos.distToCenterSqr(eyePos), sneak, requiredItem, ClipContext.Fluid.NONE));
                }
                continue;
            }

            // Case C: Missing block placement
            // Player collision & footprint check: Never place inside player's body or directly beneath feet!
            // Strictly prevents pillaring with building materials instead of scaffolding!
            AABB targetBox = new AABB(targetPos);
            if (playerBox.intersects(targetBox)) {
                continue;
            }
            double dxFoot = Math.abs(player.getX() - (targetPos.getX() + 0.5));
            double dzFoot = Math.abs(player.getZ() - (targetPos.getZ() + 0.5));
            if (dxFoot < 0.65 && dzFoot < 0.65 && targetPos.getY() <= player.getBlockY()) {
                continue; // Do not place building blocks directly underneath feet to pillar!
            }

            // Gravity block check
            if (requiredState.getBlock() instanceof FallingBlock) {
                BlockState belowState = level.getBlockState(targetPos.below());
                if (belowState.isAir() || belowState.canBeReplaced()) {
                    continue;
                }
            }

            // Search neighbor faces and simulate placement to guarantee correct orientation!
            PlacementCandidate bestForPos = findValidNeighborPlacement(level, player, targetPos, requiredState, requiredItem, eyePos, maxReachSq, config.strictRaycast);
            if (bestForPos != null) {
                candidates.add(bestForPos);
            }
        }
    }

    private static void findFluidCandidates(ClientLevel level, LocalPlayer player, Vec3 eyePos, Schematic schematic,
                                            BuilderConfig config, int activeLayerY, int minY, double maxReach, double maxReachSq,
                                            TaskTracker tracker, List<PlacementCandidate> candidates) {
        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                schematic.getBlocksAtY(activeLayerY) : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos targetPos = entry.getKey();
            BlockState requiredState = entry.getValue();

            if (tracker.isBlocked(targetPos)) continue;

            BlockState currentInWorld = level.getBlockState(targetPos);
            BuildRules.FluidStatus status = BuildRules.fluidStatus(currentInWorld, requiredState);

            if (status == BuildRules.FluidStatus.DONE || status == BuildRules.FluidStatus.IGNORED || status == BuildRules.FluidStatus.WAITING) {
                continue;
            }

            // Waterlogging a block (e.g. leaves, stairs, slabs, fences, trapdoors, walls)
            if (status == BuildRules.FluidStatus.WATERLOG) {
                if (!player.isCreative() && !InventoryHelper.hasItem(player, Items.WATER_BUCKET)) {
                    continue;
                }
                if (targetPos.distToCenterSqr(eyePos) <= maxReachSq) {
                    Vec3 targetCenter = Vec3.atCenterOf(targetPos);
                    Direction clickFace = Direction.UP;
                    Vec3 hitPoint = targetCenter;
                    ClipContext clipContext = new ClipContext(eyePos, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
                    BlockHitResult hitResult = level.clip(clipContext);
                    if (hitResult.getType() == HitResult.Type.BLOCK && hitResult.getBlockPos().equals(targetPos)) {
                        clickFace = hitResult.getDirection();
                        hitPoint = hitResult.getLocation();
                    } else if (config.strictRaycast) {
                        continue;
                    }
                    float[] rots = HumanAim.calculateRotations(eyePos, hitPoint);
                    candidates.add(new PlacementCandidate(PlacementCandidate.Type.WATERLOG, targetPos, requiredState,
                            targetPos, clickFace, hitPoint, rots[0], rots[1],
                            targetPos.distToCenterSqr(eyePos), false, Items.WATER_BUCKET, ClipContext.Fluid.NONE));
                }
                continue;
            }

            // Pouring liquid source (water / lava bucket)
            if (status == BuildRules.FluidStatus.PLACE_SOURCE) {
                net.minecraft.world.item.Item bucketItem = requiredState.is(net.minecraft.world.level.block.Blocks.WATER) ?
                        Items.WATER_BUCKET : Items.LAVA_BUCKET;
                if (!player.isCreative() && !InventoryHelper.hasItem(player, bucketItem)) {
                    continue;
                }

                // Pour against a neighbor solid block
                PlacementCandidate pourCandidate = findNeighborFaceSimple(level, player, targetPos, requiredState, bucketItem,
                        PlacementCandidate.Type.FLUID_SOURCE, eyePos, maxReachSq, config.strictRaycast);
                if (pourCandidate != null) {
                    candidates.add(pourCandidate);
                }
            }
        }

        // Auto-refill empty bucket from a nearby water source if we need water and have an empty bucket!
        if (candidates.isEmpty() && !player.isCreative() && !InventoryHelper.hasItem(player, Items.WATER_BUCKET) && InventoryHelper.hasItem(player, Items.BUCKET)) {
            boolean needsWater = false;
            for (Map.Entry<BlockPos, BlockState> entry : entries) {
                BuildRules.FluidStatus s = BuildRules.fluidStatus(level.getBlockState(entry.getKey()), entry.getValue());
                if (s == BuildRules.FluidStatus.WATERLOG || (s == BuildRules.FluidStatus.PLACE_SOURCE && entry.getValue().is(net.minecraft.world.level.block.Blocks.WATER))) {
                    needsWater = true;
                    break;
                }
            }
            if (needsWater) {
                BlockPos refillSource = findNearbyWaterSource(level, eyePos, maxReachSq, schematic);
                if (refillSource != null) {
                    Vec3 center = Vec3.atCenterOf(refillSource);
                    float[] rots = HumanAim.calculateRotations(eyePos, center);
                    candidates.add(new PlacementCandidate(PlacementCandidate.Type.BUCKET_FILL, refillSource,
                            net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), refillSource, Direction.UP, center,
                            rots[0], rots[1], center.distanceToSqr(eyePos), false, Items.BUCKET, ClipContext.Fluid.SOURCE_ONLY));
                }
            }
        }
    }

    public static BlockPos findNearbyWaterSource(ClientLevel level, Vec3 eyePos, double maxReachSq, Schematic schematic) {
        BlockPos playerPos = BlockPos.containing(eyePos);
        int r = Math.min(16, (int) Math.ceil(Math.sqrt(maxReachSq)));
        int maxDy = Math.min(6, Math.max(2, r / 2));
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -maxDy; dy <= maxDy; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = playerPos.offset(dx, dy, dz);
                    if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.WATER) || !level.getBlockState(p).getFluidState().isSource()) {
                        continue;
                    }
                    boolean outsideSchematic = (schematic == null || !schematic.contains(p));
                    boolean infinite = isInfiniteWaterSource(level, p);
                    if (!outsideSchematic && !infinite) {
                        continue;
                    }

                    Vec3 center = Vec3.atCenterOf(p);
                    double dSq = center.distanceToSqr(eyePos);
                    if (dSq <= maxReachSq && dSq < bestDistSq) {
                        best = p.immutable();
                        bestDistSq = dSq;
                    }
                }
            }
        }
        return best;
    }

    public static boolean isInfiniteWaterSource(ClientLevel level, BlockPos pos) {
        int count = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).is(net.minecraft.world.level.block.Blocks.WATER) && level.getBlockState(n).getFluidState().isSource()) {
                count++;
            }
        }
        return count >= 2;
    }

    public static boolean isFaceVisible(Vec3 eyePos, BlockPos neighborPos, Direction face) {
        double nx = face.getStepX();
        double ny = face.getStepY();
        double nz = face.getStepZ();

        double fx = neighborPos.getX() + 0.5 + nx * 0.5;
        double fy = neighborPos.getY() + 0.5 + ny * 0.5;
        double fz = neighborPos.getZ() + 0.5 + nz * 0.5;

        double toEyeX = eyePos.x - fx;
        double toEyeY = eyePos.y - fy;
        double toEyeZ = eyePos.z - fz;

        double dot = toEyeX * nx + toEyeY * ny + toEyeZ * nz;
        if (face == Direction.UP) {
            // In Minecraft, players can reach the top face rim even if eyes are slightly below or level (-0.25m)
            // as long as raycast validation passes!
            return dot > -0.25;
        }
        return dot > 0.05;
    }

    /**
     * True if {@code targetPos} could be placed from an eye position {@code eye}: some solid neighbour has a face that
     * points at the eye, is within reach and (when strictRaycast is on) is unobstructed. Mirrors the checks of
     * {@link #findValidNeighborPlacement} so the StationPlanner never picks a spot the placer would reject.
     */
    public static boolean canPlaceFrom(ClientLevel level, LocalPlayer player, BlockPos targetPos, BlockState requiredState,
                                       Vec3 eye, double reach) {
        BlockPos stationFeet = new BlockPos((int) Math.floor(eye.x), (int) Math.floor(eye.y - 1.62), (int) Math.floor(eye.z));
        if (isBadPlacementSpot(targetPos, stationFeet)) return false;

        boolean strict = BuilderConfig.getInstance().strictRaycast;
        double reachSq = reach * reach;
        ItemStack tempStack = null;
        net.minecraft.world.item.Item item = InventoryHelper.getItemForBlockState(requiredState);
        if (item != null && item != Items.AIR) {
            tempStack = new ItemStack(item);
        }

        for (Direction dir : DIRECTIONS) {
            BlockPos neighborPos = targetPos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.isAir() || neighborState.canBeReplaced() || BuildRules.isLiquid(neighborState)) continue;

            Direction clickFace = dir.getOpposite();
            if (!isFaceVisible(eye, neighborPos, clickFace) && !neighborState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) continue;

            Vec3 hit = findVisibleFacePoint(level, player, eye, neighborPos, clickFace, requiredState, reachSq, strict);
            if (hit != null && hit.distanceToSqr(eye) <= reachSq) {
                if (tempStack != null && tempStack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                    float[] rots = HumanAim.calculateRotations(eye, hit);
                    BlockHitResult bhr = new BlockHitResult(hit, clickFace, neighborPos, false);
                    PlacementSimulator.Result sim = PlacementSimulator.simulate(level, player, tempStack, bhr, rots[0], rots[1], requiredState.getBlock());
                    if (sim == null || !sim.pos().equals(targetPos)) {
                        continue;
                    }
                    if (BuildRules.hasOrientation(requiredState) && !BuildRules.orientationMatches(sim.state(), requiredState)) {
                        continue;
                    }
                }
                return true;
            }
        }
        return false;
    }

    public static boolean canPlaceFluidFrom(ClientLevel level, LocalPlayer player, BlockPos targetPos, BlockState requiredState,
                                           Vec3 eye, double reach) {
        BuildRules.FluidStatus status = BuildRules.fluidStatus(level.getBlockState(targetPos), requiredState);
        if (status == BuildRules.FluidStatus.WATERLOG) {
            Vec3 center = Vec3.atCenterOf(targetPos);
            ClipContext clipContext = new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
            BlockHitResult hitResult = level.clip(clipContext);
            return hitResult.getType() == HitResult.Type.BLOCK && hitResult.getBlockPos().equals(targetPos);
        }
        if (status == BuildRules.FluidStatus.PLACE_SOURCE) {
            double reachSq = reach * reach;
            boolean strict = BuilderConfig.getInstance().strictRaycast;
            for (Direction dir : DIRECTIONS) {
                BlockPos neighborPos = targetPos.relative(dir);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (neighborState.isAir() || neighborState.canBeReplaced() || BuildRules.isLiquid(neighborState)) continue;

                Direction clickFace = dir.getOpposite();
                if (!isFaceVisible(eye, neighborPos, clickFace) && !neighborState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) continue;

                Vec3 hit = findVisibleFacePoint(level, player, eye, neighborPos, clickFace, requiredState, reachSq, strict);
                if (hit != null && hit.distanceToSqr(eye) <= reachSq) {
                    return true;
                }
            }
        }
        return false;
    }

    private static PlacementCandidate findValidNeighborPlacement(ClientLevel level, LocalPlayer player, BlockPos targetPos,
                                                                 BlockState requiredState, net.minecraft.world.item.Item requiredItem,
                                                                 Vec3 eyePos, double maxReachSq, boolean strictRaycast) {
        PlacementCandidate best = null;
        double minAngleDiff = Double.MAX_VALUE;

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        ItemStack tempStack = new ItemStack(requiredItem);

        for (Direction dir : DIRECTIONS) {
            BlockPos neighborPos = targetPos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);

            // Neighbor must be an existing solid block to place against
            if (neighborState.isAir() || neighborState.canBeReplaced() || BuildRules.isLiquid(neighborState)) {
                continue;
            }

            Direction clickFace = dir.getOpposite();

            // Geometric face visibility: Face must physically point towards the player's eyes (scaffolding allows open side placement)
            if (!isFaceVisible(eyePos, neighborPos, clickFace) && !neighborState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                continue;
            }

            // Find a visible, unobstructed point on the face using raycast validation
            Vec3 hitVec = findVisibleFacePoint(level, player, eyePos, neighborPos, clickFace, requiredState, maxReachSq, strictRaycast);
            if (hitVec == null) {
                continue;
            }

            double distSq = hitVec.distanceToSqr(eyePos);
            if (distSq > maxReachSq) {
                continue;
            }

            float[] rots = HumanAim.calculateRotations(eyePos, hitVec);

            // Simulate placement to ensure correct facing, orientation and block state!
            if (tempStack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                BlockHitResult bhr = new BlockHitResult(hitVec, clickFace, neighborPos, false);
                PlacementSimulator.Result sim = PlacementSimulator.simulate(level, player, tempStack, bhr, rots[0], rots[1], requiredState.getBlock());

                if (sim == null || !sim.pos().equals(targetPos)) {
                    continue;
                }

                if (BuildRules.hasOrientation(requiredState) && !BuildRules.orientationMatches(sim.state(), requiredState)) {
                    continue; // Orientation mismatch! Skip this face to preserve schematic accuracy
                }
            }

            boolean requiresSneak = BuildRules.isInteractable(neighborState);
            if (requiredState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && neighborState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                requiresSneak = false;
            }

            float diffYaw = Math.abs(Mth.wrapDegrees(rots[0] - currentYaw));
            float diffPitch = Math.abs(rots[1] - currentPitch);
            double angleDiff = diffYaw + diffPitch;

            if (angleDiff < minAngleDiff) {
                minAngleDiff = angleDiff;
                best = new PlacementCandidate(PlacementCandidate.Type.PLACE, targetPos, requiredState, neighborPos,
                        clickFace, hitVec, rots[0], rots[1], distSq, requiresSneak, requiredItem, ClipContext.Fluid.NONE);
            }
        }

        return best;
    }

    private static PlacementCandidate findNeighborFaceSimple(ClientLevel level, LocalPlayer player, BlockPos targetPos,
                                                             BlockState requiredState, net.minecraft.world.item.Item item,
                                                             PlacementCandidate.Type type, Vec3 eyePos, double maxReachSq, boolean strictRaycast) {
        PlacementCandidate best = null;
        double minAngleDiff = Double.MAX_VALUE;
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        for (Direction dir : DIRECTIONS) {
            BlockPos neighborPos = targetPos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);

            if (neighborState.isAir() || neighborState.canBeReplaced() || BuildRules.isLiquid(neighborState)) {
                continue;
            }

            Direction clickFace = dir.getOpposite();

            if (!isFaceVisible(eyePos, neighborPos, clickFace) && !neighborState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                continue;
            }

            Vec3 hitVec = findVisibleFacePoint(level, player, eyePos, neighborPos, clickFace, requiredState, maxReachSq, strictRaycast);
            if (hitVec == null) continue;

            double distSq = hitVec.distanceToSqr(eyePos);
            if (distSq > maxReachSq) continue;

            float[] rots = HumanAim.calculateRotations(eyePos, hitVec);
            boolean requiresSneak = BuildRules.isInteractable(neighborState);

            float diffYaw = Math.abs(Mth.wrapDegrees(rots[0] - currentYaw));
            float diffPitch = Math.abs(rots[1] - currentPitch);
            double angleDiff = diffYaw + diffPitch;

            if (angleDiff < minAngleDiff) {
                minAngleDiff = angleDiff;
                best = new PlacementCandidate(type, targetPos, requiredState, neighborPos, clickFace, hitVec,
                        rots[0], rots[1], distSq, requiresSneak, item, ClipContext.Fluid.NONE);
            }
        }
        return best;
    }

    private static Vec3 findVisibleFacePoint(ClientLevel level, LocalPlayer player, Vec3 eyePos, BlockPos pos, Direction face,
                                             BlockState requiredState, double maxReachSq, boolean strictRaycast) {
        // Base center of the face
        double nx = face.getStepX();
        double ny = face.getStepY();
        double nz = face.getStepZ();

        double cx = pos.getX() + 0.5 + nx * 0.5;
        double cy = pos.getY() + 0.5 + ny * 0.5;
        double cz = pos.getZ() + 0.5 + nz * 0.5;

        // Inward vector towards block volume to ensure clean raycast intersection
        Vec3 inward = new Vec3(-nx * 0.02, -ny * 0.02, -nz * 0.02);

        // Define search offsets perpendicular to the face normal
        double[][] sampleOffsets;
        if (face.getAxis() == Direction.Axis.Y) {
            sampleOffsets = new double[][]{
                    {0.0, 0.0},
                    {0.2, 0.2},
                    {-0.2, -0.2},
                    {-0.2, 0.2},
                    {0.2, -0.2}
            };
        } else if (face.getAxis() == Direction.Axis.X) {
            sampleOffsets = new double[][]{
                    {0.0, 0.0},
                    {0.0, 0.2},
                    {0.0, -0.2},
                    {0.2, 0.0},
                    {-0.2, 0.0}
            };
        } else {
            sampleOffsets = new double[][]{
                    {0.0, 0.0},
                    {0.2, 0.0},
                    {-0.2, 0.0},
                    {0.0, 0.2},
                    {0.0, -0.2}
            };
        }

        for (double[] off : sampleOffsets) {
            double tx, ty, tz;
            if (face.getAxis() == Direction.Axis.Y) {
                tx = cx + off[0];
                ty = cy;
                tz = cz + off[1];
            } else if (face.getAxis() == Direction.Axis.X) {
                tx = cx;
                ty = cy + off[1];
                tz = cz + off[0];
            } else {
                tx = cx + off[0];
                ty = cy + off[1];
                tz = cz;
            }

            // Adjust for slabs/stairs if needed
            if (requiredState != null && face.getAxis() != Direction.Axis.Y) {
                if (requiredState.hasProperty(BlockStateProperties.HALF)) {
                    ty = (requiredState.getValue(BlockStateProperties.HALF) == Half.TOP) ? pos.getY() + 0.75 : pos.getY() + 0.25;
                } else if (requiredState.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                    SlabType st = requiredState.getValue(BlockStateProperties.SLAB_TYPE);
                    if (st == SlabType.TOP) ty = pos.getY() + 0.75;
                    else if (st == SlabType.BOTTOM) ty = pos.getY() + 0.25;
                }
            }

            Vec3 testPoint = new Vec3(tx, ty, tz).add(inward);
            if (testPoint.distanceToSqr(eyePos) > maxReachSq) continue;

            if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                return new Vec3(tx, ty, tz);
            }

            if (strictRaycast) {
                ClipContext clipContext = new ClipContext(eyePos, testPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
                BlockHitResult hitResult = level.clip(clipContext);

                // STRICT: Must hit neighborPos ON clickFace!
                if (hitResult.getType() == HitResult.Type.BLOCK) {
                    if (hitResult.getBlockPos().equals(pos) && hitResult.getDirection() == face) {
                        return hitResult.getLocation();
                    }
                    if (level.getBlockState(hitResult.getBlockPos()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                        Vec3 pastScaffold = hitResult.getLocation().add(testPoint.subtract(eyePos).normalize().scale(0.05));
                        if (pastScaffold.distanceToSqr(eyePos) < maxReachSq) {
                            BlockHitResult hitResult2 = level.clip(new ClipContext(pastScaffold, testPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                            if (hitResult2.getType() == HitResult.Type.BLOCK && hitResult2.getBlockPos().equals(pos) && hitResult2.getDirection() == face) {
                                return hitResult2.getLocation();
                            }
                        }
                    }
                }
            } else {
                return new Vec3(tx, ty, tz);
            }
        }

        return null;
    }
}
