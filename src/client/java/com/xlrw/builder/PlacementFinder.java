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

    private static final Map<BlockPos, Long> PRIORITY = new HashMap<>();

    /** A just-demolished cell: when several blocks are placeable, this one goes first. */
    public static void prioritize(BlockPos pos) {
        if (pos != null) PRIORITY.put(pos.immutable(), System.currentTimeMillis() + 30_000);
    }

    private static boolean isPriority(BlockPos pos) {
        Long t = PRIORITY.get(pos);
        if (t == null) return false;
        if (t < System.currentTimeMillis()) {
            PRIORITY.remove(pos);
            return false;
        }
        return true;
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
        double maxReach = com.xlrw.account.Plus.reach();
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

        // 0. Waterlog right after placing: leaves, mangrove roots... (full blocks, so the water can not leak out)
        //    get their water as soon as they stand instead of waiting for the fluid phase at the end of the layer.
        PlacementCandidate wl = findImmediateWaterlog(level, player, eyePos, schematic, config, activeLayerY, maxReachSq, tracker);
        if (wl != null) return wl;

        // 1. Normal blocks & redstone phase
        findBuildCandidates(level, player, eyePos, schematic, config, activeLayerY, minY, maxReach, maxReachSq, tracker, null, false, candidates);

        // 2. Piston phase: ONLY when normal blocks & redstone on this layer are completed!
        StationPlanner.Plan pistonPlan = StationPlanner.getInstance().getPlan();
        boolean plannerWantsPiston = pistonPlan != null && pistonPlan.anchor != null
                && BuildRules.isPiston(schematic.getBlock(pistonPlan.anchor) != null ? schematic.getBlock(pistonPlan.anchor) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        if (candidates.isEmpty() && (!hasUnfinishedNormal || plannerWantsPiston)) {
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
                            if (sampleReject == null) sampleReject = String.format("Thiếu vật phẩm [%s] cho block tại %s", com.xlrw.compat.MC.itemName(reqItem).getString(), pos.toShortString());
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
        Map<BlockPos, Integer> pressure = new HashMap<>();
        for (PlacementCandidate c : candidates) {
            int pr = BuildRules.closingPressure(level, schematic, c.targetPos);
            pressure.put(c.targetPos, pr >= 3 ? pr : 0);
        }
        candidates.sort(Comparator
                .comparing((PlacementCandidate c) -> !isPriority(c.targetPos))
                .thenComparing((PlacementCandidate c) -> BuildRules.wouldSealUnplacedNeighbor(level, schematic, c.targetPos))
                .thenComparing((PlacementCandidate c) -> BuildRules.wouldClosePocket(level, schematic, c.targetPos))
                .thenComparingInt((PlacementCandidate c) -> -pressure.getOrDefault(c.targetPos, 0))
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
                new ArrayList<>(schematic.getBlocksAtY(activeLayerY)) : new ArrayList<>(schematic.getWorldBlocks().entrySet());
        if (config.layerMode) entries.addAll(BuilderController.getInstance().leftoverEntries(schematic, level));

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
                    Vec3 targetCenter = findInteractPoint(level, player, eyePos, targetPos, maxReachSq);
                    boolean unseen = false;
                    if (targetCenter == null) {
                        if (config.strictRaycast && !throughWallAnywhere()) continue;
                        targetCenter = Vec3.atCenterOf(targetPos);
                        unseen = true;
                    }
                    float[] rots = HumanAim.calculateRotations(eyePos, targetCenter);
                    if (BlockStateAdjuster.isAdjustYawAcceptable(currentInWorld, requiredState, rots[0])) {
                        PlacementCandidate adj = new PlacementCandidate(PlacementCandidate.Type.ADJUST, targetPos, requiredState,
                                targetPos, Direction.UP, targetCenter, rots[0], rots[1],
                                targetPos.distToCenterSqr(eyePos), false, null, ClipContext.Fluid.NONE);
                        adj.throughWall = unseen;
                        candidates.add(adj);
                    }
                }
                continue;
            }

            // Case B: Stackable click (double slab, candles, pickles, snow layers...)
            if (status == BuildRules.BuildStatus.STACKABLE) {
                if (targetPos.distToCenterSqr(eyePos) <= maxReachSq) {
                    Vec3 targetCenter = findInteractPoint(level, player, eyePos, targetPos, maxReachSq);
                    boolean unseen = false;
                    if (targetCenter == null) {
                        if (config.strictRaycast && !throughWallAnywhere()) continue;
                        targetCenter = Vec3.atCenterOf(targetPos);
                        unseen = true;
                    }
                    float[] rots = HumanAim.calculateRotations(eyePos, targetCenter);
                    boolean sneak = BuildRules.isInteractable(currentInWorld);
                    PlacementCandidate stk = new PlacementCandidate(PlacementCandidate.Type.STACK, targetPos, requiredState,
                            targetPos, Direction.UP, targetCenter, rots[0], rots[1],
                            targetPos.distToCenterSqr(eyePos), sneak, requiredItem, ClipContext.Fluid.NONE);
                    stk.throughWall = unseen;
                    candidates.add(stk);
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
            // Air place: no usable neighbour face (floating part, or the needed facing is impossible against the
            // neighbours) -> click the empty cell itself, exactly like the AirPlace mod does
            if (bestForPos == null && config.airPlace) {
                bestForPos = findAirPlacement(level, player, targetPos, requiredState, requiredItem, eyePos, maxReachSq, config.strictRaycast);
            }
            // Boxed-in cell nothing can see: place "through the wall" (the server only checks the distance)
            if (bestForPos == null && throughWallAllowed(level, targetPos, eyePos)) {
                bestForPos = findValidNeighborPlacement(level, player, targetPos, requiredState, requiredItem, eyePos, maxReachSq, false);
                if (bestForPos == null && config.airPlace) {
                    bestForPos = findAirPlacement(level, player, targetPos, requiredState, requiredItem, eyePos, maxReachSq, false);
                }
                if (bestForPos != null) bestForPos.throughWall = true;
            }
            if (bestForPos != null) {
                candidates.add(bestForPos);
            }
        }
    }

    /**
     * Through-wall placement is a last resort and kept modest: only for a cell that is walled in on at least 4 of
     * its 6 sides (a hole / pocket in the build), and only from close by (eye within 4 blocks of it).
     */
    public static boolean throughWallAllowed(ClientLevel level, BlockPos target, Vec3 eye) {
        if (!BuilderConfig.getInstance().throughWallPlace) return false;
        // Anywhere mode: the vanilla server only checks the distance and that the hit point lies on the clicked
        // block, never the line of sight or which way the face points -> allowed for every cell in reach
        if (throughWallAnywhere()) return true;
        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > 4.0 * 4.0) return false;
        int solid = 0;
        for (Direction d : DIRECTIONS) {
            BlockState n = level.getBlockState(target.relative(d));
            if (!n.isAir() && !n.canBeReplaced() && !BuildRules.isLiquid(n)) solid++;
        }
        return solid >= 4;
    }

    /** Waterlogged full blocks (leaves, mangrove roots...) that are already placed and can take water right now. */
    private static PlacementCandidate findImmediateWaterlog(ClientLevel level, LocalPlayer player, Vec3 eyePos, Schematic schematic,
                                                           BuilderConfig config, int activeLayerY, double maxReachSq, TaskTracker tracker) {
        if (!player.isCreative() && !InventoryHelper.hasItem(player, Items.WATER_BUCKET)) return null;
        PlacementCandidate best = null;
        int scanMin = config.layerMode ? activeLayerY - 1 : Integer.MIN_VALUE;
        java.util.Collection<Map.Entry<BlockPos, BlockState>> entries = schematic.getWorldBlocks().entrySet();
        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos targetPos = entry.getKey();
            if (config.layerMode && (targetPos.getY() < scanMin || targetPos.getY() > activeLayerY)) continue;
            if (targetPos.distToCenterSqr(eyePos) > maxReachSq) continue;
            BlockState requiredState = entry.getValue();
            if (!requiredState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                    || !requiredState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) continue;
            if (tracker.isBlocked(targetPos)) continue;
            BlockState current = level.getBlockState(targetPos);
            if (BuildRules.fluidStatus(current, requiredState) != BuildRules.FluidStatus.WATERLOG) continue;
            // only blocks whose collision is a full cube: water inside them can not flow out into the build
            if (!net.minecraft.world.level.block.Block.isShapeFullBlock(current.getCollisionShape(level, targetPos))) continue;
            Vec3 hit = findInteractPoint(level, player, eyePos, targetPos, maxReachSq);
            if (hit == null) continue;
            BlockHitResult bhr = level.clip(new ClipContext(eyePos, hit.add(hit.subtract(eyePos).normalize().scale(0.05)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            Direction face = bhr.getType() == HitResult.Type.BLOCK ? bhr.getDirection() : Direction.UP;
            float[] rots = HumanAim.calculateRotations(eyePos, hit);
            double d = targetPos.distToCenterSqr(eyePos);
            if (best == null || d < best.distanceSq) {
                best = new PlacementCandidate(PlacementCandidate.Type.WATERLOG, targetPos, requiredState,
                        targetPos, face, hit, rots[0], rots[1], d, false, Items.WATER_BUCKET, ClipContext.Fluid.NONE);
            }
        }
        return best;
    }

    private static void findFluidCandidates(ClientLevel level, LocalPlayer player, Vec3 eyePos, Schematic schematic,
                                            BuilderConfig config, int activeLayerY, int minY, double maxReach, double maxReachSq,
                                            TaskTracker tracker, List<PlacementCandidate> candidates) {
        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode ?
                new ArrayList<>(schematic.getBlocksAtY(activeLayerY)) : new ArrayList<>(schematic.getWorldBlocks().entrySet());
        if (config.layerMode) entries.addAll(BuilderController.getInstance().leftoverEntries(schematic, level));

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
                if (fluidWouldReachPlayer(level, targetPos, player.position())) {
                    continue; // never pour lava / water onto or right next to ourselves
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

    /** Margin used for facing-sensitive blocks: the result must hold for this much yaw/pitch error in any direction. */
    private static final float ORIENTATION_MARGIN_DEG = 6.0f;

    /**
     * The look direction decides the facing of observers, pistons, stairs, etc. A look that lies right on the border
     * between two directions (e.g. pitch 37-45 deg for "down" vs "north") can come out differently on the server.
     * Only accept looks whose result stays the same when yaw and pitch are off by a few degrees.
     */
    public static boolean isOrientationRobust(ClientLevel level, LocalPlayer player, ItemStack stack, BlockHitResult bhr,
                                              float[] rots, BlockPos targetPos, BlockState requiredState) {
        float m = ORIENTATION_MARGIN_DEG;
        float[][] deltas = {{m, 0}, {-m, 0}, {0, m}, {0, -m}, {m, m}, {-m, -m}, {m, -m}, {-m, m}};
        for (float[] d : deltas) {
            float pitch = Mth.clamp(rots[1] + d[1], -90.0f, 90.0f);
            PlacementSimulator.Result sim = PlacementSimulator.simulate(level, player, stack, bhr, rots[0] + d[0], pitch, requiredState.getBlock());
            if (sim == null || !sim.pos().equals(targetPos) || !BuildRules.orientationMatches(sim.state(), requiredState)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Point on the block at {@code pos} that can really be clicked from {@code eye}. Uses the block's OUTLINE shape
     * (what the crosshair targets), so thin blocks work: a repeater/comparator is only 2px tall and a lever has no
     * collision at all - aiming at the cell centre with a collider raycast never hits them, which used to make every
     * "adjust delay" / "toggle" task in a hole look unreachable forever.
     */
    public static Vec3 findInteractPoint(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos, double maxReachSq) {
        BlockState state = level.getBlockState(pos);
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getShape(level, pos);
        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;
        double topY = box.maxY - 0.02;
        double[][] pts = {
                {cx, cy, cz},
                {cx, topY, cz},
                {cx + (box.maxX - cx) * 0.6, topY, cz}, {cx - (cx - box.minX) * 0.6, topY, cz},
                {cx, topY, cz + (box.maxZ - cz) * 0.6}, {cx, topY, cz - (cz - box.minZ) * 0.6},
        };
        for (double[] q : pts) {
            Vec3 t = new Vec3(pos.getX() + q[0], pos.getY() + q[1], pos.getZ() + q[2]);
            if (t.distanceToSqr(eye) > maxReachSq) continue;
            Vec3 end = t.add(t.subtract(eye).normalize().scale(0.05));
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit.getLocation();
        }
        return null;
    }

    /**
     * Rotation to send with a placement click. First the real look direction toward the hit point; if that would give
     * the wrong facing and "rotation spoof" is on, a look direction that DOES give the schematic facing (the server
     * takes the facing from the rotation the client reports, not from where the click really is - like the
     * Litematica printer / easy place). Returns null when nothing works.
     */
    public static float[] resolveRotation(ClientLevel level, LocalPlayer player, ItemStack stack, BlockHitResult bhr,
                                          float[] geom, BlockPos targetPos, BlockState requiredState) {
        boolean oriented = BuildRules.hasOrientation(requiredState);
        PlacementSimulator.Result sim = PlacementSimulator.simulate(level, player, stack, bhr, geom[0], geom[1], requiredState.getBlock());
        if (sim == null || !sim.pos().equals(targetPos)) return null;
        if (!oriented) return geom;
        if (BuildRules.orientationMatches(sim.state(), requiredState)
                && isOrientationRobust(level, player, stack, bhr, geom, targetPos, requiredState)) return geom;
        if (!BuilderConfig.getInstance().rotationSpoof) return null;
        float[] pitches = {0f, -90f, 90f, -60f, 60f, -30f, 30f};
        float[] yaws = {0f, 90f, 180f, -90f};
        for (float pitch : pitches) {
            for (float yaw : yaws) {
                float[] r = {yaw, pitch};
                PlacementSimulator.Result s2 = PlacementSimulator.simulate(level, player, stack, bhr, yaw, pitch, requiredState.getBlock());
                if (s2 == null || !s2.pos().equals(targetPos) || !BuildRules.orientationMatches(s2.state(), requiredState)) continue;
                if (!isOrientationRobust(level, player, stack, bhr, r, targetPos, requiredState)) continue;
                return r;
            }
        }
        return null;
    }

    public static boolean throughWallAnywhere() {
        BuilderConfig c = BuilderConfig.getInstance();
        return c.throughWallPlace && c.throughWallAnywhere;
    }

    /**
     * May this face be clicked from the eye? Strict (line-of-sight) searches need it to point at the eye. The
     * non-strict (through-wall) search in anywhere mode accepts every face: that is what lets the bot stand on top
     * of the layer and extend it sideways by clicking the side of the block under its feet, instead of building a
     * new tower for every block next to it.
     */
    private static boolean faceUsable(Vec3 eye, BlockPos neighborPos, Direction face, BlockState neighborState, boolean strict) {
        if (!strict && throughWallAnywhere()) return true;
        return isFaceVisible(eye, neighborPos, face) || neighborState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);
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
        boolean strict = BuilderConfig.getInstance().strictRaycast;
        if (canPlaceFrom(level, player, targetPos, requiredState, eye, reach, strict)) return true;
        return strict && throughWallAllowed(level, targetPos, eye) && canPlaceFrom(level, player, targetPos, requiredState, eye, reach, false);
    }

    private static boolean canPlaceFrom(ClientLevel level, LocalPlayer player, BlockPos targetPos, BlockState requiredState,
                                        Vec3 eye, double reach, boolean strict) {
        BlockPos stationFeet = new BlockPos((int) Math.floor(eye.x), (int) Math.floor(eye.y - 1.62), (int) Math.floor(eye.z));
        if (isBadPlacementSpot(targetPos, stationFeet)) return false;

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
            if (!faceUsable(eye, neighborPos, clickFace, neighborState, strict)) continue;

            Vec3 hit = findVisibleFacePoint(level, player, eye, neighborPos, clickFace, requiredState, reachSq, strict);
            if (hit != null && hit.distanceToSqr(eye) <= reachSq) {
                if (tempStack != null && tempStack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                    float[] rots = HumanAim.calculateRotations(eye, hit);
                    BlockHitResult bhr = new BlockHitResult(hit, clickFace, neighborPos, false);
                    if (resolveRotation(level, player, tempStack, bhr, rots, targetPos, requiredState) == null) {
                        continue;
                    }
                }
                return true;
            }
        }
        if (BuilderConfig.getInstance().airPlace && item != null) {
            return findAirPlacement(level, player, targetPos, requiredState, item, eye, reachSq, strict) != null;
        }
        return false;
    }

    /**
     * Air placement: right-click the empty target cell itself (no neighbour needed). The server accepts a click on
     * a replaceable cell and puts the block right there. Tries every face of the cell that points toward the eye,
     * aiming at that face, and keeps the first one whose simulated result has the schematic orientation.
     */
    public static PlacementCandidate findAirPlacement(ClientLevel level, LocalPlayer player, BlockPos targetPos,
                                                      BlockState requiredState, net.minecraft.world.item.Item item,
                                                      Vec3 eyePos, double maxReachSq, boolean strictRaycast) {
        if (item == null || item == Items.AIR) return null;
        BlockState current = level.getBlockState(targetPos);
        if (!current.isAir() && !current.canBeReplaced()) return null;
        if (!current.getFluidState().isEmpty()) return null; // a liquid cell would be clicked as water/lava instead

        ItemStack stack = new ItemStack(item);
        boolean oriented = BuildRules.hasOrientation(requiredState);

        // Aim points: face centres (and the upper/lower half of side faces for slabs, stairs, trapdoors)
        PlacementCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction face : DIRECTIONS) {
            if (!(!strictRaycast && throughWallAnywhere()) && !isFaceVisible(eyePos, targetPos, face)) continue;
            double[] heights = face.getAxis() == Direction.Axis.Y ? new double[]{0.5} : new double[]{0.5, 0.25, 0.75};
            for (double hy : heights) {
                Vec3 hit = new Vec3(
                        targetPos.getX() + 0.5 + face.getStepX() * 0.5,
                        face.getAxis() == Direction.Axis.Y ? targetPos.getY() + 0.5 + face.getStepY() * 0.5 : targetPos.getY() + hy,
                        targetPos.getZ() + 0.5 + face.getStepZ() * 0.5);
                if (hit.distanceToSqr(eyePos) > maxReachSq) continue;

                if (strictRaycast) {
                    // Nothing solid may be in the way between the eye and the aim point
                    BlockHitResult los = level.clip(new ClipContext(eyePos, hit, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                    if (los.getType() == HitResult.Type.BLOCK && los.getLocation().distanceToSqr(hit) > 1.0e-4
                            && !level.getBlockState(los.getBlockPos()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                        continue;
                    }
                }

                float[] rots = HumanAim.calculateRotations(eyePos, hit);
                BlockHitResult bhr = new BlockHitResult(hit, face, targetPos, false);
                if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                    float[] use = resolveRotation(level, player, stack, bhr, rots, targetPos, requiredState);
                    if (use == null) continue;
                    rots = use;
                }

                double score = hit.distanceToSqr(eyePos)
                        + Math.abs(Mth.wrapDegrees(rots[0] - player.getYRot())) * 0.01;
                if (score < bestScore) {
                    bestScore = score;
                    best = new PlacementCandidate(PlacementCandidate.Type.PLACE, targetPos, requiredState, targetPos,
                            face, hit, rots[0], rots[1], hit.distanceToSqr(eyePos), false, item, ClipContext.Fluid.NONE);
                }
                if (!oriented) return best; // any face works for blocks without orientation
            }
        }
        return best;
    }

    /**
     * True if a liquid source poured at {@code target} would sit in the player's body, or flow straight into it:
     * the target touches the player's hitbox (+0.15), or one of the feet / head cells is a free neighbour of it at the
     * same height or below (liquid spreads sideways and down).
     */
    public static boolean fluidWouldReachPlayer(ClientLevel level, BlockPos target, Vec3 feet) {
        // Already standing in water: one more water source around changes nothing (lava is still refused below)
        BlockPos feetCell = BlockPos.containing(feet.x, feet.y + 0.01, feet.z);
        com.xlrw.schematic.Schematic sch = com.xlrw.schematic.SchematicManager.getInstance().getActiveSchematic();
        BlockState want = sch != null ? sch.getBlock(target) : null;
        boolean lava = want == null || want.is(net.minecraft.world.level.block.Blocks.LAVA);
        if (!lava && level.getFluidState(feetCell).is(net.minecraft.tags.FluidTags.WATER)) return false;
        AABB body = new AABB(feet.x - 0.45, feet.y, feet.z - 0.45, feet.x + 0.45, feet.y + 1.8, feet.z + 0.45);
        if (body.intersects(new AABB(target))) return true;
        BlockPos f = BlockPos.containing(feet.x, feet.y + 0.01, feet.z);
        for (BlockPos cell : new BlockPos[]{f, f.above()}) {
            if (cell.getY() > target.getY()) continue; // liquid does not flow up
            int dx = Math.abs(cell.getX() - target.getX()), dz = Math.abs(cell.getZ() - target.getZ());
            if (dx + dz == 1 && cell.getY() >= target.getY() - 1) return true;
        }
        return false;
    }

    public static boolean canPlaceFluidFrom(ClientLevel level, LocalPlayer player, BlockPos targetPos, BlockState requiredState,
                                           Vec3 eye, double reach) {
        BuildRules.FluidStatus status = BuildRules.fluidStatus(level.getBlockState(targetPos), requiredState);
        if (status == BuildRules.FluidStatus.PLACE_SOURCE && fluidWouldReachPlayer(level, targetPos, eye.subtract(0, 1.62, 0))) {
            return false; // that standing spot would get us burned / washed away
        }
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

    public static PlacementCandidate findValidNeighborPlacement(ClientLevel level, LocalPlayer player, BlockPos targetPos,
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

            // Geometric face visibility: Face must physically point towards the player's eyes (scaffolding allows open
            // side placement). Through-wall anywhere mode (non-strict pass) accepts every face.
            if (!faceUsable(eyePos, neighborPos, clickFace, neighborState, strictRaycast)) {
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

            // Simulate placement to ensure correct facing, orientation and block state (with a spoofed look
            // direction when the real one would give the wrong facing)
            if (tempStack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                BlockHitResult bhr = new BlockHitResult(hitVec, clickFace, neighborPos, false);
                float[] use = resolveRotation(level, player, tempStack, bhr, rots, targetPos, requiredState);
                if (use == null) continue;
                rots = use;
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

    /** True if the view ray for (yaw, pitch) and for +-1.5 degrees around it all hit {@code pos} on {@code face}. */
    public static boolean bucketRayHits(ClientLevel level, LocalPlayer player, Vec3 eye, float[] rots, BlockPos pos, Direction face, double reach) {
        float[][] d = {{0, 0}, {1.5f, 0}, {-1.5f, 0}, {0, 1.5f}, {0, -1.5f}};
        for (float[] o : d) {
            BlockHitResult r = viewRay(level, player, eye, rots[0] + o[0], rots[1] + o[1], reach, ClipContext.Fluid.NONE);
            if (r.getType() != HitResult.Type.BLOCK || !r.getBlockPos().equals(pos) || (face != null && r.getDirection() != face)) return false;
        }
        return true;
    }

    /** Same ray a bucket uses on the server: from the eye along the look direction, OUTLINE shapes. */
    public static BlockHitResult viewRay(ClientLevel level, LocalPlayer player, Vec3 eye, float yaw, float pitch, double reach, ClipContext.Fluid fluid) {
        float f = Mth.cos(-yaw * ((float) Math.PI / 180F) - (float) Math.PI);
        float g = Mth.sin(-yaw * ((float) Math.PI / 180F) - (float) Math.PI);
        float h = -Mth.cos(-pitch * ((float) Math.PI / 180F));
        float k = Mth.sin(-pitch * ((float) Math.PI / 180F));
        Vec3 dir = new Vec3(g * h, k, f * h);
        return level.clip(new ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.OUTLINE, fluid, player));
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

            // A water bucket clicked on a waterloggable block (slab, stairs, fence, leaves...) fills THAT block with
            // water instead of pouring next to it - the water would end up in the wrong cell.
            if (type == PlacementCandidate.Type.FLUID_SOURCE && item == Items.WATER_BUCKET
                    && neighborState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer) {
                continue;
            }

            if (!isFaceVisible(eyePos, neighborPos, clickFace) && !neighborState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                continue;
            }

            Vec3 hitVec = findVisibleFacePoint(level, player, eyePos, neighborPos, clickFace, requiredState, maxReachSq, strictRaycast);
            if (hitVec == null) continue;

            double distSq = hitVec.distanceToSqr(eyePos);
            if (distSq > maxReachSq) continue;

            float[] rots = HumanAim.calculateRotations(eyePos, hitVec);
            // Buckets work from the VIEW ray (not from the clicked point): the ray must land on this very face, with
            // a little margin, or the liquid is poured on top of / beside the wrong block.
            if (type == PlacementCandidate.Type.FLUID_SOURCE
                    && !bucketRayHits(level, player, eyePos, rots, neighborPos, clickFace, Math.sqrt(maxReachSq) + 0.5)) {
                continue;
            }
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
                    {0.2, -0.2},
                    // deep 1x1 holes: the visible part of the floor is a thin strip on the far side
                    {0.38, 0.0},
                    {-0.38, 0.0},
                    {0.0, 0.38},
                    {0.0, -0.38}
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
