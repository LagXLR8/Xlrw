package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.printer.LocalPathfinder;
import com.xlrw.printer.PrinterMovement;
import com.xlrw.printer.ScaffoldManager;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Baritone-style goal planner with Unified Action Planning.
 *
 * Provides end-to-end commitment for both direct walking and scaffolding expeditions:
 *  1. Pick an ANCHOR block deterministically.
 *  2. Compute STATIONS (vantage spots).
 *  3. Check DIRECT_WALK reachability via A*; if blocked (walls/elevation), seamlessly fall back
 *     to SCAFFOLD_EXPEDITION with a dedicated tower and elevation plan.
 *  4. COMMIT to the sequence through structured phases (APPROACH_TOWER_BASE -> BUILD_TOWER -> CLIMB_TOWER -> BRIDGE -> AT_STATION).
 */
public class StationPlanner {
    private static final StationPlanner INSTANCE = new StationPlanner();

    private static final long PLAN_TIMEOUT_MS = 25_000;
    private static final int MAX_ANCHORS_TRIED = 40;
    private static final int MAX_STATIONS_PATH_CHECKED = 8;
    private static final int ARRIVED_IDLE_LIMIT = 25;

    public enum Kind { PLACE, INTERACT, FLUID, BREAK }

    public enum PlanKind {
        DIRECT_WALK,
        SCAFFOLD_EXPEDITION
    }

    public enum PlanPhase {
        APPROACH_STATION,
        APPROACH_TOWER_BASE,
        BUILD_TOWER,
        CLIMB_TOWER,
        BRIDGE_TO_STATION,
        AT_STATION
    }

    public static final class StationResult {
        public final BlockPos station;
        public final PlanKind planKind;
        public final BlockPos towerPos;
        public final int targetElevation;

        StationResult(BlockPos station, PlanKind planKind, BlockPos towerPos, int targetElevation) {
            this.station = station;
            this.planKind = planKind;
            this.towerPos = towerPos;
            this.targetElevation = targetElevation;
        }
    }

    public static final class Plan {
        public final BlockPos anchor;
        public final BlockPos stationBlock;
        public final Vec3 station;
        public final Kind kind;
        public final PlanKind planKind;
        public final BlockPos scaffoldTowerPos;
        public final int targetElevation;
        public PlanPhase phase;
        public final long createdAt;
        public long phaseStartedAt;

        Plan(BlockPos anchor, BlockPos stationBlock, Kind kind, PlanKind planKind, BlockPos scaffoldTowerPos, int targetElevation, PlanPhase initialPhase) {
            this.anchor = anchor;
            this.stationBlock = stationBlock;
            this.station = new Vec3(stationBlock.getX() + 0.5, stationBlock.getY(), stationBlock.getZ() + 0.5);
            this.kind = kind;
            this.planKind = planKind;
            this.scaffoldTowerPos = scaffoldTowerPos;
            this.targetElevation = targetElevation;
            this.phase = initialPhase;
            this.createdAt = System.currentTimeMillis();
            this.phaseStartedAt = this.createdAt;
        }

        public void transitionTo(PlanPhase newPhase) {
            if (this.phase != newPhase) {
                this.phase = newPhase;
                this.phaseStartedAt = System.currentTimeMillis();
            }
        }

        public boolean arrived(LocalPlayer player) {
            BlockPos foot = player.blockPosition();
            if (foot.equals(stationBlock) || foot.below().equals(stationBlock)) {
                return true;
            }
            double dx = player.getX() - station.x;
            double dz = player.getZ() - station.z;
            double dy = Math.abs(player.getY() - station.y);
            return Math.hypot(dx, dz) <= 0.65 && dy <= 1.2;
        }
    }

    private static final class Anchor {
        final BlockPos pos;
        final BlockState expected;
        final Kind kind;
        double score;

        Anchor(BlockPos pos, BlockState expected, Kind kind) {
            this.pos = pos;
            this.expected = expected;
            this.kind = kind;
        }
    }

    private static final class StationCandidate {
        final BlockPos feet;
        final double cost;

        StationCandidate(BlockPos feet, double cost) {
            this.feet = feet;
            this.cost = cost;
        }
    }

    private Plan plan = null;
    private long nextPlanAt = 0;
    private int arrivedIdleTicks = 0;
    private final Map<BlockPos, Long> skipUntil = new HashMap<>();
    private final Map<BlockPos, Long> badStations = new HashMap<>();
    private final Map<BlockPos, Set<BlockPos>> badStationsForAnchor = new HashMap<>();

    public static StationPlanner getInstance() {
        return INSTANCE;
    }

    public Plan getPlan() {
        return plan;
    }

    public void clear() {
        plan = null;
        nextPlanAt = 0;
        arrivedIdleTicks = 0;
        skipUntil.clear();
        badStations.clear();
        badStationsForAnchor.clear();
    }

    public void recordBadPlacementStation(BlockPos anchorPos, BlockPos station) {
        if (anchorPos == null || station == null) return;
        badStationsForAnchor.computeIfAbsent(anchorPos.immutable(), k -> new HashSet<>()).add(station.immutable());
        badStations.put(station.immutable(), System.currentTimeMillis() + 25_000);
    }

    public boolean isBadStationForAnchor(BlockPos anchorPos, BlockPos station) {
        if (anchorPos == null || station == null) return false;
        Set<BlockPos> bads = badStationsForAnchor.get(anchorPos);
        if (bads == null || bads.isEmpty()) return false;
        for (BlockPos b : bads) {
            if (Math.abs(b.getX() - station.getX()) <= 1
                    && Math.abs(b.getZ() - station.getZ()) <= 1
                    && Math.abs(b.getY() - station.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** Drops only the current commitment (keeps the skip lists). */
    public void dropPlan() {
        plan = null;
        arrivedIdleTicks = 0;
    }

    /** The plan could not be completed: skip the anchor and the station for a while and re-plan. */
    public void failPlan(long skipMs) {
        long now = System.currentTimeMillis();
        if (plan != null) {
            com.xlrw.debug.XlrwDebug.logStationFail(plan.anchor, "failPlan: skip anchor for " + skipMs + "ms, mark station " + plan.stationBlock.toShortString() + " bad");
            skipUntil.put(plan.anchor, now + skipMs);
            badStations.put(plan.stationBlock, now + skipMs * 2);
        }
        dropPlan();
    }

    public boolean isSkipped(BlockPos pos) {
        Long until = skipUntil.get(pos);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            skipUntil.remove(pos);
            return false;
        }
        return true;
    }

    private boolean isBadStation(BlockPos pos) {
        Long until = badStations.get(pos);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            badStations.remove(pos);
            return false;
        }
        return true;
    }

    public void markStationBad(BlockPos pos, long durationMs) {
        if (pos != null) {
            badStations.put(pos, System.currentTimeMillis() + durationMs);
        }
    }

    /**
     * Called every tick while nothing can be built from the current spot.
     * Returns the committed plan (creating one if needed), or null if the layer has no reachable work.
     */
    public Plan ensurePlan(ClientLevel level, LocalPlayer player, Schematic schematic, int layerIndex, BlockPos lastPlaced) {
        if (level == null || player == null || schematic == null) return null;

        if (plan != null) {
            if (!isPlanStillValid(level, player, schematic)) {
                dropPlan();
            }
        }
        if (plan == null) {
            long nowMs = System.currentTimeMillis();
            if (nowMs < nextPlanAt) return null;
            plan = selectPlan(level, player, schematic, layerIndex, lastPlaced);
            arrivedIdleTicks = 0;
            if (plan == null) {
                nextPlanAt = nowMs + 500; // nothing reachable: don't rescan every tick
            }
        }
        return plan;
    }

    /**
     * The bot reached its station but PlacementFinder still has nothing to build.
     * Allow a short grace period (server latency / pending blocks), then give up on this anchor.
     * @return true if the plan was abandoned.
     */
    public boolean tickArrivedIdle() {
        arrivedIdleTicks++;
        if (arrivedIdleTicks >= ARRIVED_IDLE_LIMIT) {
            failPlan(8_000);
            return true;
        }
        return false;
    }

    public void resetArrivedIdle() {
        arrivedIdleTicks = 0;
    }

    private boolean isPlanStillValid(ClientLevel level, LocalPlayer player, Schematic schematic) {
        long now = System.currentTimeMillis();
        if (now - plan.createdAt > PLAN_TIMEOUT_MS) {
            failPlan(10_000);
            return false;
        }
        if (isSkipped(plan.anchor) || TaskTracker.getInstance().isPermanentlySkipped(plan.anchor)) {
            return false;
        }
        if (!anchorNeedsWork(level, schematic, plan.anchor)) {
            return false;
        }
        // The station itself must still be somewhere we can stand
        BlockPos feet = plan.stationBlock;
        if (plan.planKind == PlanKind.SCAFFOLD_EXPEDITION) {
            // For scaffolding expeditions, tower shaft must be clear to target elevation
            if (!ScaffoldManager.getInstance().isColumnClear(level, plan.scaffoldTowerPos, plan.targetElevation)) {
                return false;
            }
            if (plan.phase == PlanPhase.AT_STATION) {
                return LocalPathfinder.isPassable(level, feet)
                        && LocalPathfinder.isPassable(level, feet.above())
                        && (LocalPathfinder.isStandable(level, feet.below()) || level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING));
            }
            return true;
        }

        boolean standable = LocalPathfinder.isPassable(level, feet)
                && LocalPathfinder.isPassable(level, feet.above())
                && LocalPathfinder.isStandable(level, feet.below());
        return standable;
    }

    private boolean anchorNeedsWork(ClientLevel level, Schematic schematic, BlockPos pos) {
        BuilderConfig config = BuilderConfig.getInstance();
        BlockState expected = schematic.getBlock(pos);
        if (expected == null) return false;
        BlockState inWorld = level.getBlockState(pos);

        if (config.autoDemolish && DemolishManager.isMisplaced(level, schematic, pos)) return true;

        BuildRules.BuildStatus status = BuildRules.buildStatus(inWorld, expected);
        if (status == BuildRules.BuildStatus.MISSING
                || status == BuildRules.BuildStatus.STACKABLE
                || status == BuildRules.BuildStatus.ADJUST) {
            return true;
        }
        BuildRules.FluidStatus fs = BuildRules.fluidStatus(inWorld, expected);
        return fs == BuildRules.FluidStatus.PLACE_SOURCE || fs == BuildRules.FluidStatus.WATERLOG;
    }

    // ------------------------------------------------------------------------------------------------
    // Anchor selection (independent of the player's position)
    // ------------------------------------------------------------------------------------------------

    private Plan selectPlan(ClientLevel level, LocalPlayer player, Schematic schematic, int layerIndex, BlockPos lastPlaced) {
        BuilderConfig config = BuilderConfig.getInstance();
        TaskTracker tracker = TaskTracker.getInstance();
        int layerY = schematic.getMinY() + layerIndex;

        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode
                ? schematic.getBlocksAtY(layerY)
                : new ArrayList<>(schematic.getWorldBlocks().entrySet());

        List<Anchor> solidNormal = new ArrayList<>();
        List<Anchor> solidPiston = new ArrayList<>();
        List<Anchor> fluid = new ArrayList<>();

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();
            BlockState expected = entry.getValue();

            if (tracker.isBlocked(pos) || isSkipped(pos)) continue;

            BlockState inWorld = level.getBlockState(pos);

            if (config.autoDemolish && DemolishManager.isMisplaced(level, schematic, pos)) {
                solidNormal.add(new Anchor(pos, expected, Kind.BREAK));
                continue;
            }

            boolean isPiston = BuildRules.isPiston(expected);

            BuildRules.BuildStatus status = BuildRules.buildStatus(inWorld, expected);
            if (status == BuildRules.BuildStatus.MISSING) {
                if (!BuilderController.isAccessible(level, pos)) continue;
                if (expected.getBlock() instanceof FallingBlock && level.getBlockState(pos.below()).isAir()) continue;
                // Needs something to place against, otherwise it can't be built yet
                if (!BuilderController.hasSolidNeighbor(level, pos)) continue;
                Item item = InventoryHelper.getItemForBlockState(expected);
                if (item == null) continue;
                if (!player.isCreative() && !InventoryHelper.hasItem(player, item)) continue;

                if (isPiston) {
                    solidPiston.add(new Anchor(pos, expected, Kind.PLACE));
                } else {
                    solidNormal.add(new Anchor(pos, expected, Kind.PLACE));
                }
            } else if (status == BuildRules.BuildStatus.STACKABLE) {
                Item item = InventoryHelper.getItemForBlockState(expected);
                if (item == null) continue;
                if (!player.isCreative() && !InventoryHelper.hasItem(player, item)) continue;
                if (isPiston) {
                    solidPiston.add(new Anchor(pos, expected, Kind.INTERACT));
                } else {
                    solidNormal.add(new Anchor(pos, expected, Kind.INTERACT));
                }
            } else if (status == BuildRules.BuildStatus.ADJUST) {
                if (isPiston) {
                    solidPiston.add(new Anchor(pos, expected, Kind.INTERACT));
                } else {
                    solidNormal.add(new Anchor(pos, expected, Kind.INTERACT));
                }
            } else {
                BuildRules.FluidStatus fs = BuildRules.fluidStatus(inWorld, expected);
                if (fs == BuildRules.FluidStatus.WATERLOG) {
                    if (!player.isCreative() && !InventoryHelper.hasItem(player, Items.WATER_BUCKET) && !InventoryHelper.hasItem(player, Items.BUCKET)) continue;
                    fluid.add(new Anchor(pos, expected, Kind.FLUID));
                } else if (fs == BuildRules.FluidStatus.PLACE_SOURCE) {
                    Item bucket = expected.is(Blocks.WATER) ? Items.WATER_BUCKET : Items.LAVA_BUCKET;
                    if (!player.isCreative() && !InventoryHelper.hasItem(player, bucket) && (!expected.is(Blocks.WATER) || !InventoryHelper.hasItem(player, Items.BUCKET))) continue;
                    fluid.add(new Anchor(pos, expected, Kind.FLUID));
                }
            }
        }

        // If in Survival with an empty bucket and no water bucket, prioritize refilling from an available source!
        if (!player.isCreative() && !InventoryHelper.hasItem(player, Items.WATER_BUCKET) && InventoryHelper.hasItem(player, Items.BUCKET) && !fluid.isEmpty()) {
            BlockPos refillSource = PlacementFinder.findNearbyWaterSource(level, player.getEyePosition(), 16.0 * 16.0, schematic);
            if (refillSource != null) {
                fluid.clear();
                fluid.add(new Anchor(refillSource, Blocks.WATER.defaultBlockState(), Kind.FLUID));
            }
        }

        // Strict 3-tier Build Priority:
        // Priority 1: Normal blocks & redstone
        // Priority 2: Pistons (only after normal blocks on layer are done)
        // Priority 3: Water & Lava (only after normal blocks and pistons are done)
        List<Anchor> anchors;
        if (!solidNormal.isEmpty()) {
            anchors = solidNormal;
        } else if (!solidPiston.isEmpty()) {
            anchors = solidPiston;
        } else {
            anchors = fluid;
        }
        if (anchors.isEmpty()) return null;

        // Cluster anchor scoring:
        // Strongly cluster around lastPlaced if within immediate workspace (<= 25 blocks sq),
        // but prioritize anchors close to the player's current position to eliminate
        // cross-schematic ping-pong runs.
        final BlockPos playerPos = player.blockPosition();
        final BlockPos ref = (lastPlaced != null && lastPlaced.getY() == layerY) ? lastPlaced : playerPos;
        for (Anchor a : anchors) {
            double distToPlayerSq = a.pos.distSqr(playerPos);
            double distToRefSq = a.pos.distSqr(ref);
            double clusterBonus = (distToRefSq <= 25.0) ? 0.0 : 45.0;
            a.score = distToPlayerSq + clusterBonus + Math.min(distToRefSq, 100.0) * 0.4;
        }

        // Sort anchors:
        // 1. Anti-sealing: never pick an anchor that seals an unplaced neighbor if non-sealing options exist
        // 2. Proximity to last placed block (clustering)
        // 3. Deterministic coordinate tie-break
        anchors.sort(Comparator
                .comparing((Anchor a) -> BuildRules.wouldSealUnplacedNeighbor(level, schematic, a.pos))
                .thenComparingDouble((Anchor a) -> a.score)
                .thenComparingInt(a -> a.pos.getX())
                .thenComparingInt(a -> a.pos.getZ()));

        int tried = 0;
        for (Anchor anchor : anchors) {
            if (tried++ >= MAX_ANCHORS_TRIED) break;
            StationResult stationRes = findStation(level, player, schematic, anchor);
            if (stationRes != null) {
                PlanPhase initialPhase;
                if (stationRes.planKind == PlanKind.DIRECT_WALK) {
                    initialPhase = PlanPhase.APPROACH_STATION;
                } else {
                    // Check where player is relative to the scaffolding tower
                    BlockPos towerPos = stationRes.towerPos;
                    double distToTower = Math.hypot(player.getX() - (towerPos.getX() + 0.5), player.getZ() - (towerPos.getZ() + 0.5));
                    boolean onScaffoldPlatform = player.getY() >= stationRes.targetElevation - 0.6
                            && level.getBlockState(player.blockPosition().below()).is(Blocks.SCAFFOLDING);
                    if (onScaffoldPlatform) {
                        initialPhase = PlanPhase.BRIDGE_TO_STATION;
                    } else if (distToTower <= 0.85) {
                        initialPhase = PlanPhase.BUILD_TOWER;
                    } else {
                        initialPhase = PlanPhase.APPROACH_TOWER_BASE;
                    }
                }

                com.xlrw.debug.XlrwDebug.logStationPlan(anchor.pos, anchor.expected, stationRes.station,
                        "Kind=" + anchor.kind + ", PlanKind=" + stationRes.planKind + ", InitialPhase=" + initialPhase + ", Score=" + anchor.score);
                return new Plan(anchor.pos, stationRes.station, anchor.kind, stationRes.planKind, stationRes.towerPos, stationRes.targetElevation, initialPhase);
            }
            com.xlrw.debug.XlrwDebug.logStationFail(anchor.pos, "Không tìm thấy standing spot (station) hợp lệ hoặc không có đường đi A* tới station");
            // No reachable standing spot right now; retry later instead of burning the block forever
            skipUntil.put(anchor.pos, System.currentTimeMillis() + 4_000);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------
    // Station search
    // ------------------------------------------------------------------------------------------------

    private StationResult findStation(ClientLevel level, LocalPlayer player, Schematic schematic, Anchor anchor) {
        BuilderConfig config = BuilderConfig.getInstance();
        // Slightly shorter than the real reach so the finder (which uses the full reach) always agrees
        double reach = Math.max(2.5, config.reachDistance - 0.35);
        double reachSq = reach * reach;
        int range = (int) Math.ceil(reach) + 1;

        BlockPos anchorPos = anchor.pos;
        Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);
        BlockPos playerFeet = player.blockPosition();

        List<StationCandidate> found = new ArrayList<>();

        // Reach allows placing blocks up to ~3.8 blocks above feet.
        // If feet are 4 blocks below anchor (dy = -4), eye is at anchor - 2.38 blocks, easily in reach.
        // Only if the player is actively elevated high on a scaffolding tower (> 2.5 blocks above ground)
        // do we restrict dropping all the way back down to ground.
        boolean playerElevatedOnScaffold = (level.getBlockState(playerFeet).is(Blocks.SCAFFOLDING)
                || level.getBlockState(playerFeet.below()).is(Blocks.SCAFFOLDING))
                && player.getY() > schematic.getMinY() + 2.5;
        int lowestDy = playerElevatedOnScaffold ? -2 : -4;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = 1; dy >= lowestDy; dy--) {
                    BlockPos feet = anchorPos.offset(dx, dy, dz);

                    if (isBadStation(feet)) continue;
                    if (isBadStationForAnchor(anchorPos, feet)) continue;
                    if (PlacementFinder.isBadPlacementSpot(anchorPos, feet)) continue;

                    // Never stand inside / on top of the block we want to build (no pillaring with materials)
                    if (dx == 0 && dz == 0 && anchorPos.getY() <= feet.getY() + 1) continue;

                    if (!LocalPathfinder.isPassable(level, feet)) continue;
                    if (!LocalPathfinder.isPassable(level, feet.above())) continue;
                    if (!LocalPathfinder.isStandable(level, feet.below())) continue;

                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
                    double distSq = eye.distanceToSqr(anchorCenter);
                    if (distSq > reachSq) continue;

                    if (!canActFrom(level, player, anchor, eye, reach)) continue;

                    double walk = Math.hypot(feet.getX() + 0.5 - player.getX(), feet.getZ() + 0.5 - player.getZ())
                            + Math.abs(feet.getY() - playerFeet.getY()) * 0.8;
                    // Prefer standing close enough that many neighbouring blocks are also in reach (clustering)
                    double cost = walk + Math.sqrt(distSq) * 0.35;

                    // If this anchor has bad placement spots (e.g. wrong observer/piston facing),
                    // strongly penalize standing on the same vantage side and reward the opposite/alternate side!
                    Set<BlockPos> badSpots = PlacementFinder.getBadPlacementSpots(anchorPos);
                    if (badSpots != null && !badSpots.isEmpty()) {
                        for (BlockPos bad : badSpots) {
                            double badDx = bad.getX() - anchorPos.getX();
                            double badDz = bad.getZ() - anchorPos.getZ();
                            double candDx = feet.getX() - anchorPos.getX();
                            double candDz = feet.getZ() - anchorPos.getZ();
                            double dot = badDx * candDx + badDz * candDz;
                            if (dot > 0) {
                                cost += 40.0; // heavy penalty for standing on the same side that previously failed!
                            } else {
                                cost -= 25.0; // strong bonus for standing on the opposite or different side!
                            }
                        }
                    }

                    found.add(new StationCandidate(feet.immutable(), cost));
                }
            }
        }

        if (!found.isEmpty()) {
            found.sort(Comparator.comparingDouble(c -> c.cost));

            // 1. Direct ground walk check:
            int checked = 0;
            for (StationCandidate c : found) {
                if (checked++ >= MAX_STATIONS_PATH_CHECKED) break;
                if (isReachable(level, playerFeet, c.feet)) {
                    return new StationResult(c.feet, PlanKind.DIRECT_WALK, null, 0);
                }
            }

            // 2. Obstruction / elevation fallback: deploy unified scaffolding expedition!
            if (config.useScaffolding && ScaffoldManager.getInstance().hasScaffolding(player)) {
                StationCandidate best = found.get(0);
                int targetElev = Math.max((int) player.getY() + 3, Math.max(best.feet.getY() + 1, anchor.pos.getY() + 1));
                BlockPos towerPos = ScaffoldManager.getInstance().getScaffoldPos(schematic, player, targetElev, anchor.pos);
                if (towerPos != null && !towerPos.equals(BlockPos.ZERO)) {
                    return new StationResult(best.feet, PlanKind.SCAFFOLD_EXPEDITION, towerPos, targetElev);
                }
            }
        }

        // 3. Fallback when NO valid standing spot exists on the ground (e.g. obscured angles, raycast blocked, high wall):
        // Deploy a scaffolding expedition directly near the anchor to elevate player overhead!
        if (config.useScaffolding && ScaffoldManager.getInstance().hasScaffolding(player)) {
            int targetElev = Math.max((int) player.getY() + 3, anchor.pos.getY() + 1);
            BlockPos towerPos = ScaffoldManager.getInstance().getScaffoldPos(schematic, player, targetElev, anchor.pos);
            if (towerPos != null && !towerPos.equals(BlockPos.ZERO)) {
                BlockPos overheadStation = new BlockPos(towerPos.getX(), targetElev, towerPos.getZ());
                com.xlrw.debug.XlrwDebug.logStationPlan(anchor.pos, anchor.expected, overheadStation,
                        "Elevating via Scaffolding Expedition overhead because ground vantage is obstructed/unavailable!");
                return new StationResult(overheadStation, PlanKind.SCAFFOLD_EXPEDITION, towerPos, targetElev);
            }
        }

        return null;
    }

    private boolean canActFrom(ClientLevel level, LocalPlayer player, Anchor anchor, Vec3 eye, double reach) {
        BlockPos pos = anchor.pos;
        if (anchor.kind == Kind.PLACE) {
            return PlacementFinder.canPlaceFrom(level, player, pos, anchor.expected, eye, reach);
        }
        if (anchor.kind == Kind.FLUID) {
            return PlacementFinder.canPlaceFluidFrom(level, player, pos, anchor.expected, eye, reach);
        }

        // Interact / break: the target block must be visible from here
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().equals(pos);
        }
        return false;
    }

    /** Fast reachability check: direct walk first, followed by a bounded A* pathfinder. */
    private boolean isReachable(ClientLevel level, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) return true;
        Vec3 startVec = new Vec3(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        if (start.distSqr(goal) <= 36.0) {
            if (PrinterMovement.canWalkDirectly(level, startVec, goal)) {
                return true;
            }
        }
        List<BlockPos> path = LocalPathfinder.findPath(level, start, goal, 500);
        if (path == null || path.isEmpty()) return false;
        BlockPos last = path.get(path.size() - 1);
        return last.distSqr(goal) <= 2.25;
    }
}
