package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.debug.XlrwDebug;
import com.xlrw.printer.LocalPathfinder;
import com.xlrw.printer.ScaffoldManager;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Goal planner: decides WHERE the bot should stand next.
 *
 * <p>How a plan is chosen:
 * <ol>
 *   <li>One Dijkstra flood from the player gives the real walking cost to every reachable standing cell
 *       (same movement rules as the walker, so a chosen station is always walkable).</li>
 *   <li>Unfinished blocks (anchors) are ranked; for each one every standing cell in reach is tested with the exact
 *       placement check. The anchor/station pair with the lowest real walking cost wins.</li>
 *   <li>Only if NO anchor can be done from the ground, a scaffolding tower is planned: a free column next to the
 *       anchor whose TOP is verified to be a valid placing spot. Existing scaffolding columns are reused.</li>
 * </ol>
 */
public class StationPlanner {
    private static final StationPlanner INSTANCE = new StationPlanner();

    private static final long PLAN_TIMEOUT_MS = 25_000;
    private static final long TOWER_PLAN_TIMEOUT_MS = 45_000;
    // private static final int MAX_ANCHORS_TRIED = 80; (now from Plus)
    // private static final int ANCHORS_WITH_STATION_TO_COMPARE = 8; (now from Plus)
    // private static final int MAX_TOWER_ANCHORS = 10; (now from Plus)
    private static final int ARRIVED_IDLE_LIMIT = 25;
    // private static final int FLOOD_NODES = 10000; (now from Plus)
    // private static final int FLOOD_RADIUS = 40; (now from Plus)
    private static final int MAX_TOWER_HEIGHT = 24;
    /** Cost bonus per OTHER unfinished block that can also be done from a station (ground / tower top). */
    private static final double COVER_WEIGHT_GROUND = 1.2;
    private static final double COVER_WEIGHT_TOWER = 3.0;
    private static final int COVER_CAP = 40;
    /** Ground stations per anchor whose coverage is evaluated (the cheapest by walking cost). */
    // private static final int GROUND_COVER_CANDIDATES = 6; (now from Plus)

    public enum Kind { PLACE, INTERACT, FLUID, BREAK }

    public enum PlanKind {
        DIRECT_WALK,
        SCAFFOLD_EXPEDITION,
        /** Raise a temporary scaffolding column under a floating block so it has something to be placed against. */
        SUPPORT_COLUMN
    }

    public enum PlanPhase {
        APPROACH_STATION,
        APPROACH_TOWER_BASE,
        BUILD_TOWER,
        CLIMB_TOWER,
        BRIDGE_TO_STATION,
        AT_STATION
    }

    public static final class Plan {
        public final BlockPos anchor;
        public final BlockPos stationBlock;
        public final Vec3 station;
        public final Kind kind;
        public final PlanKind planKind;
        /** Bottom block of the scaffolding column (tower plans only). */
        public final BlockPos scaffoldTowerPos;
        /** Feet Y the player stands at on top of the tower (tower plans only). */
        public final int targetElevation;
        /** Where to stand while raising the tower (tower plans only, may be null if the tower already exists). */
        public final BlockPos towerStand;
        /** Facing-sensitive anchor: stop close to the station centre. */
        public final boolean precise;
        public PlanPhase phase;
        /** Access tower: only gets the bot up onto the build surface; done as soon as it stands on top. */
        public boolean access = false;
        public final long createdAt;
        public long phaseStartedAt;

        Plan(BlockPos anchor, BlockPos stationBlock, Kind kind, PlanKind planKind, BlockPos scaffoldTowerPos,
             int targetElevation, BlockPos towerStand, PlanPhase initialPhase) {
            this.anchor = anchor;
            this.stationBlock = stationBlock;
            this.station = new Vec3(stationBlock.getX() + 0.5, stationBlock.getY(), stationBlock.getZ() + 0.5);
            this.kind = kind;
            this.planKind = planKind;
            this.scaffoldTowerPos = scaffoldTowerPos;
            this.targetElevation = targetElevation;
            this.towerStand = towerStand;
            com.xlrw.schematic.Schematic sch = com.xlrw.schematic.SchematicManager.getInstance().getActiveSchematic();
            this.precise = kind == Kind.PLACE && sch != null && BuildRules.hasOrientation(sch.getBlock(anchor));
            this.phase = initialPhase;
            this.createdAt = System.currentTimeMillis();
            this.phaseStartedAt = this.createdAt;
        }

        public boolean isTower() {
            return planKind == PlanKind.SCAFFOLD_EXPEDITION;
        }

        public boolean isSupport() {
            return planKind == PlanKind.SUPPORT_COLUMN;
        }

        public void transitionTo(PlanPhase newPhase) {
            if (this.phase != newPhase) {
                XlrwDebug.log("STATION/PHASE", "Plan " + anchor.toShortString() + ": " + this.phase + " -> " + newPhase);
                this.phase = newPhase;
                this.phaseStartedAt = System.currentTimeMillis();
            }
        }

        /** True when the player is standing in the station cell (close to its centre). */
        public boolean arrived(LocalPlayer player) {
            BlockPos foot = player.blockPosition();
            if (!foot.equals(stationBlock)) return false;
            if (isTower()) return true;
            double dx = player.getX() - station.x;
            double dz = player.getZ() - station.z;
            return Math.hypot(dx, dz) <= (precise ? 0.22 : 0.45);
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

    private static final class Choice {
        final Anchor anchor;
        final BlockPos station;
        final double cost;
        final BlockPos towerBase;
        final BlockPos towerStand;
        final int elevation;

        Choice(Anchor anchor, BlockPos station, double cost, BlockPos towerBase, BlockPos towerStand, int elevation) {
            this.anchor = anchor;
            this.station = station;
            this.cost = cost;
            this.towerBase = towerBase;
            this.towerStand = towerStand;
            this.elevation = elevation;
        }
    }

    private Plan plan = null;
    private long nextPlanAt = 0;
    private int arrivedIdleTicks = 0;
    private final Map<BlockPos, Long> skipUntil = new HashMap<>();
    /** Anchors an access tower was already built for (do not keep building new ones for the same work). */
    private final Map<BlockPos, Long> accessTriedAt = new HashMap<>();
    /** Tower columns used by a plan recently (the janitor must leave them alone). */
    private final Map<BlockPos, Long> towerUsedAt = new HashMap<>();

    private static boolean isStillShallowWater(ClientLevel level, BlockPos feet) {
        if (!LocalPathfinder.isShallowWater(level, feet)) return false;
        net.minecraft.world.level.material.FluidState fs = level.getFluidState(feet);
        if (!fs.isSource() || fs.getFlow(level, feet).lengthSqr() > 1.0e-4) return false;
        return LocalPathfinder.isPassable(level, feet.above()) || LocalPathfinder.isShallowWater(level, feet.above());
    }

    /** Same patch of work (6 blocks around, same height) already had an access tower recently. */
    private boolean nearAccessTried(BlockPos p) {
        for (BlockPos q : accessTriedAt.keySet()) {
            if (q.getY() == p.getY() && Math.abs(q.getX() - p.getX()) <= 6 && Math.abs(q.getZ() - p.getZ()) <= 6) return true;
        }
        return false;
    }

    /** Work the bot already went up an access tower for and still could not reach. */
    public boolean isHardSkipped(BlockPos p) {
        if (gaveUp.containsKey(p)) return true;
        if (nearAccessTried(p)) return true;
        Long since = noStationSince.get(p);
        return since != null && System.currentTimeMillis() - since > 90_000; // no standing spot for 1.5 minutes
    }

    // Anchors whose plans keep failing (the bot gets a station but can never actually place from it)
    private final Map<BlockPos, Integer> anchorFailCount = new HashMap<>();
    private final Map<BlockPos, Long> gaveUp = new HashMap<>(); // anchor -> time given up (retried after 3 min)

    /** Stop trying this block for now; it becomes a layer leftover (offered again later / placed by hand). */
    public void giveUp(BlockPos p, String why) {
        if (gaveUp.putIfAbsent(p.immutable(), System.currentTimeMillis()) == null) {
            XlrwDebug.log("STATION/GIVE_UP", "Tạm bỏ " + p.toShortString() + " (" + why + ") -> để lại, làm việc khác trước");
        }
    }

    public void onPlaced(BlockPos p) {
        anchorFailCount.remove(p);
        gaveUp.remove(p);
    }

    /** First time each anchor was found to have no reachable standing spot (cleared once a plan works for it). */
    private final Map<BlockPos, Long> noStationSince = new HashMap<>();
    private BlockPos noStationRef = null;

    public boolean isRecentlyUsedTower(BlockPos base) {
        Long t = towerUsedAt.get(columnKey(base));
        return t != null && System.currentTimeMillis() - t < 90_000;
    }
    private final Map<BlockPos, Long> badStations = new HashMap<>();
    private final Map<BlockPos, Set<BlockPos>> badStationsForAnchor = new HashMap<>();
    private final Map<BlockPos, Long> badTowers = new HashMap<>();
    /** Cells that were just demolished: put the right block back there before anything else. */
    private final Map<BlockPos, Long> priority = new HashMap<>();

    public void prioritize(BlockPos pos) {
        if (pos == null) return;
        BlockPos key = pos.immutable();
        priority.put(key, System.currentTimeMillis() + 30_000);
        skipUntil.remove(key);
        nextPlanAt = 0;
    }

    private boolean isPriority(BlockPos pos) {
        return !expired(priority, pos);
    }

    /** Missing blocks (with items) that currently have no solid neighbour at all, from the last anchor scan. */
    private final List<BlockPos> lastFloating = new ArrayList<>();

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
        badTowers.clear();
    }

    public void recordBadPlacementStation(BlockPos anchorPos, BlockPos station) {
        if (anchorPos == null || station == null) return;
        badStationsForAnchor.computeIfAbsent(anchorPos.immutable(), k -> new HashSet<>()).add(station.immutable());
    }

    public boolean isBadStationForAnchor(BlockPos anchorPos, BlockPos station) {
        if (anchorPos == null || station == null) return false;
        Set<BlockPos> bads = badStationsForAnchor.get(anchorPos);
        return bads != null && bads.contains(station);
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
            XlrwDebug.logStationFail(plan.anchor, "failPlan: skip anchor " + skipMs + "ms, station " + plan.stationBlock.toShortString() + " bad"
                    + (plan.isTower() ? " (tower " + plan.scaffoldTowerPos.toShortString() + ", phase " + plan.phase + ")" : ""));
            skipUntil.put(plan.anchor, now + skipMs);
            badStations.put(plan.stationBlock, now + skipMs);
            int n = anchorFailCount.merge(plan.anchor.immutable(), 1, Integer::sum);
            if (n >= 6) giveUp(plan.anchor, n + " kế hoạch liên tiếp thất bại");
            if ((plan.isTower() || plan.isSupport()) && plan.phase != PlanPhase.AT_STATION) {
                badTowers.put(columnKey(plan.scaffoldTowerPos), now + Math.max(skipMs, 20_000));
            }
        }
        dropPlan();
    }

    public boolean isSkipped(BlockPos pos) {
        Long gave = gaveUp.get(pos);
        if (gave != null) {
            if (System.currentTimeMillis() - gave < 180_000) return true;
            gaveUp.remove(pos);                 // try again now (other work may have opened a better spot)
            anchorFailCount.remove(pos);
        }
        Long until = skipUntil.get(pos);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            skipUntil.remove(pos);
            return false;
        }
        return true;
    }

    private static boolean expired(Map<BlockPos, Long> map, BlockPos key) {
        Long until = map.get(key);
        if (until == null) return true;
        if (System.currentTimeMillis() >= until) {
            map.remove(key);
            return true;
        }
        return false;
    }

    private boolean isBadStation(BlockPos pos) {
        return !expired(badStations, pos);
    }

    private boolean isBadTower(BlockPos base) {
        return !expired(badTowers, columnKey(base));
    }

    private static BlockPos columnKey(BlockPos p) {
        return new BlockPos(p.getX(), 0, p.getZ());
    }

    public void markStationBad(BlockPos pos, long durationMs) {
        if (pos != null) {
            badStations.put(pos.immutable(), System.currentTimeMillis() + durationMs);
        }
    }

    /**
     * Called every tick while nothing can be built from the current spot.
     * Returns the committed plan (creating one if needed), or null if the layer has no reachable work right now.
     */
    public Plan ensurePlan(ClientLevel level, LocalPlayer player, Schematic schematic, int layerIndex, BlockPos lastPlaced) {
        if (level == null || player == null || schematic == null) return null;

        if (plan != null && !isPlanStillValid(level, schematic)) {
            dropPlan();
            nextPlanAt = System.currentTimeMillis() + 300; // never re-plan the very same thing every tick
        }
        if (plan == null) {
            long nowMs = System.currentTimeMillis();
            if (nowMs < nextPlanAt) return null;
            plan = selectPlan(level, player, schematic, layerIndex, lastPlaced);
            arrivedIdleTicks = 0;
            if (plan != null && plan.scaffoldTowerPos != null) towerUsedAt.put(columnKey(plan.scaffoldTowerPos), System.currentTimeMillis());
            if (plan != null) noStationSince.remove(plan.anchor);
            if (plan == null) {
                nextPlanAt = nowMs + 750; // nothing reachable: don't rescan every tick
            }
        }
        if (plan != null && plan.scaffoldTowerPos != null) towerUsedAt.put(columnKey(plan.scaffoldTowerPos), System.currentTimeMillis());
        return plan;
    }

    /**
     * The bot reached its station but PlacementFinder still has nothing to build.
     * Allow a short grace period (server latency / pending blocks), then give up on this anchor.
     */
    public boolean tickArrivedIdle() {
        if (plan != null && plan.access) {
            XlrwDebug.log("TOWER/ACCESS", "Đã lên tới mặt trên công trình tại " + plan.stationBlock.toShortString() + ", lập kế hoạch từ đây");
            dropPlan();
            nextPlanAt = 0;
            return true;
        }
        arrivedIdleTicks++;
        if (arrivedIdleTicks >= ARRIVED_IDLE_LIMIT) {
            if (plan != null) {
                // Remember that THIS spot does not work for THIS block, so it is never chosen again for it
                recordBadPlacementStation(plan.anchor, plan.stationBlock);
            }
            failPlan(8_000);
            return true;
        }
        return false;
    }

    public void resetArrivedIdle() {
        arrivedIdleTicks = 0;
    }

    private boolean isPlanStillValid(ClientLevel level, Schematic schematic) {
        long now = System.currentTimeMillis();
        long timeout = (plan.isTower() || plan.isSupport()) ? TOWER_PLAN_TIMEOUT_MS : PLAN_TIMEOUT_MS;
        if (now - plan.createdAt > timeout) {
            failPlan(10_000);
            return false;
        }
        if (isSkipped(plan.anchor) || TaskTracker.getInstance().isPermanentlySkipped(plan.anchor)) {
            return false;
        }
        if (!anchorNeedsWork(level, schematic, plan.anchor)) {
            return false;
        }
        if (plan.isSupport()) {
            BlockPos base = plan.scaffoldTowerPos;
            for (int y = base.getY(); y < plan.targetElevation; y++) {
                if (!isColumnCellFree(level, schematic, new BlockPos(base.getX(), y, base.getZ()))) return false;
            }
            return true;
        }
        if (plan.isTower()) {
            BlockPos base = plan.scaffoldTowerPos;
            for (int y = base.getY(); y < plan.targetElevation; y++) {
                if (!isColumnCellFree(level, schematic, new BlockPos(base.getX(), y, base.getZ()))) return false;
            }
            return LocalPathfinder.isPassable(level, plan.stationBlock) && LocalPathfinder.isPassable(level, plan.stationBlock.above());
        }
        return LocalPathfinder.isValidStand(level, plan.stationBlock);
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
    // Plan selection
    // ------------------------------------------------------------------------------------------------

    private Plan selectPlan(ClientLevel level, LocalPlayer player, Schematic schematic, int layerIndex, BlockPos lastPlaced) {
        List<Anchor> anchors = collectAnchors(level, player, schematic, layerIndex);
        if (anchors.isEmpty() && lastFloating.isEmpty()) return null;

        BlockPos playerFeet = LocalPathfinder.normalizeStart(level, player.blockPosition());
        LocalPathfinder.Reachability reach = LocalPathfinder.flood(level, playerFeet, com.xlrw.account.Plus.floodNodes(), com.xlrw.account.Plus.floodRadius());
        // "No standing spot" is only meaningful for where we are: moved somewhere else (fell, chorus, climbed) ->
        // start counting again, so work is never given up on because of time spent stuck somewhere unrelated
        if (noStationRef == null || noStationRef.distSqr(playerFeet) > 16) {
            noStationRef = playerFeet;
            noStationSince.clear();
        }

        int layerY = schematic.getMinY() + layerIndex;
        activeLayerY = BuilderConfig.getInstance().layerMode ? layerY : Integer.MIN_VALUE;
        final BlockPos ref = (lastPlaced != null && lastPlaced.getY() == layerY) ? lastPlaced : playerFeet;
        for (Anchor a : anchors) {
            double distToPlayerSq = a.pos.distSqr(playerFeet);
            double distToRefSq = a.pos.distSqr(ref);
            a.score = distToPlayerSq + Math.min(distToRefSq, 100.0) * 0.4;
            if (isPriority(a.pos)) a.score -= 1.0e6;
            // Pockets first: fill a hole while it can still be reached/seen from the side
            int pressure = BuildRules.closingPressure(level, schematic, a.pos);
            if (pressure >= 3) a.score -= 5_000.0 * pressure;
        }
        anchors.sort(Comparator
                .comparing((Anchor a) -> BuildRules.wouldSealUnplacedNeighbor(level, schematic, a.pos))
                .thenComparing((Anchor a) -> BuildRules.wouldClosePocket(level, schematic, a.pos))
                .thenComparingDouble((Anchor a) -> a.score)
                .thenComparingInt(a -> a.pos.getX())
                .thenComparingInt(a -> a.pos.getZ()));

        // 1. Ground stations: compare several anchors by REAL walking cost, keep the cheapest
        Choice best = null;
        int withStation = 0;
        int tried = 0;
        List<Anchor> noGroundStation = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (tried++ >= com.xlrw.account.Plus.anchorsTried() || withStation >= com.xlrw.account.Plus.anchorsCompared()) break;
            Choice c = findGroundStation(level, player, anchor, reach, anchors);
            if (c == null) {
                if (noGroundStation.size() < com.xlrw.account.Plus.towerAnchors()) noGroundStation.add(anchor);
                continue;
            }
            withStation++;
            // Small tie-break so nearby clustered work wins between equally cheap stations
            double total = c.cost + Math.sqrt(anchor.pos.distSqr(ref)) * 0.15 - (isPriority(anchor.pos) ? 60.0 : 0.0);
            if (best == null || total < best.cost) {
                best = new Choice(anchor, c.station, total, null, null, 0);
            }
        }

        if (best != null) {
            XlrwDebug.logStationPlan(best.anchor.pos, best.anchor.expected, best.station,
                    String.format("Kind=%s, PlanKind=DIRECT_WALK, Cost=%.1f, flood=%d cells", best.anchor.kind, best.cost, reach.size()));
            return new Plan(best.anchor.pos, best.station, best.anchor.kind, PlanKind.DIRECT_WALK, null, 0, null, PlanPhase.APPROACH_STATION);
        }

        // 2. Nothing doable from the ground: scaffolding tower next to an anchor, verified from its top
        BuilderConfig config = BuilderConfig.getInstance();
        if (config.useScaffolding && (player.isCreative() || InventoryHelper.hasScaffolding(player))) {
            int scaffoldCount = player.isCreative() ? 999 : countItem(player, Items.SCAFFOLDING);
            Choice bestTower = null;
            for (Anchor anchor : noGroundStation) {
                Choice c = findTowerStation(level, player, schematic, anchor, reach, scaffoldCount, anchors);
                if (c != null && (bestTower == null || c.cost < bestTower.cost)) {
                    bestTower = c;
                }
            }
            if (bestTower != null) {
                PlanPhase phase = playerFeet.equals(bestTower.station) ? PlanPhase.AT_STATION : PlanPhase.APPROACH_TOWER_BASE;
                XlrwDebug.logTowerSelect(bestTower.towerBase, bestTower.elevation - 1,
                        "Anchor " + bestTower.anchor.pos.toShortString() + " không đặt được từ mặt đất. Đứng trên đỉnh trụ tại "
                                + bestTower.station.toShortString() + String.format(" | Cost=%.1f", bestTower.cost));
                XlrwDebug.logStationPlan(bestTower.anchor.pos, bestTower.anchor.expected, bestTower.station,
                        "Kind=" + bestTower.anchor.kind + ", PlanKind=SCAFFOLD_EXPEDITION, base=" + bestTower.towerBase.toShortString()
                                + ", stand=" + (bestTower.towerStand != null ? bestTower.towerStand.toShortString() : "-"));
                return new Plan(bestTower.anchor.pos, bestTower.station, bestTower.anchor.kind, PlanKind.SCAFFOLD_EXPEDITION,
                        bestTower.towerBase, bestTower.elevation, bestTower.towerStand, phase);
            }
        }

        // 2b. Still nothing: the remaining work can only be done from ON TOP of the build (e.g. a field of
        //     up-facing pistons in the middle of the layer). Raise one tower at the edge so the bot can step onto
        //     the layer's top surface and walk from there.
        long nowAcc = System.currentTimeMillis();
        accessTriedAt.values().removeIf(t -> nowAcc - t > 120_000);
        List<Anchor> accessCandidates = new ArrayList<>();
        for (Anchor a : noGroundStation) if (!nearAccessTried(a.pos)) accessCandidates.add(a);
        if (config.useScaffolding && (player.isCreative() || InventoryHelper.hasScaffolding(player)) && !accessCandidates.isEmpty()) {
            int scaffoldCount = player.isCreative() ? 999 : countItem(player, Items.SCAFFOLDING);
            Choice acc = findAccessTower(level, player, schematic, reach, accessCandidates, layerY, scaffoldCount);
            if (acc != null) {
                // One access tower per group of stuck work: if they are still unreachable from up there, give up on
                // them for a while instead of building tower after tower
                for (Anchor a : noGroundStation) accessTriedAt.put(a.pos, nowAcc);
                XlrwDebug.logTowerSelect(acc.towerBase, acc.elevation - 1, "Trụ lên mặt công trình (không chỗ nào dưới đất làm được) cho "
                        + acc.anchor.pos.toShortString() + ", đứng tại " + acc.station.toShortString() + String.format(" | Cost=%.1f", acc.cost));
                Plan p = new Plan(acc.anchor.pos, acc.station, acc.anchor.kind, PlanKind.SCAFFOLD_EXPEDITION,
                        acc.towerBase, acc.elevation, acc.towerStand,
                        playerFeet.equals(acc.station) ? PlanPhase.AT_STATION : PlanPhase.APPROACH_TOWER_BASE);
                p.access = true;
                return p;
            }
        }

        // 3. Floating sections (blocks with nothing to place against): raise a scaffolding column under one block
        if (!lastFloating.isEmpty() && (player.isCreative() || InventoryHelper.hasScaffolding(player))) {
            Plan support = planSupportColumn(level, player, schematic, reach, playerFeet);
            if (support != null) return support;
        }

        // 4. Nothing reachable at all: let those anchors rest a bit so other work gets a chance next time
        long nowSkip = System.currentTimeMillis();
        for (Anchor a : noGroundStation) {
            boolean accessDone = nearAccessTried(a.pos);
            // Already went up an access tower for it and still nothing: this block can only be placed from a spot the
            // bot can not get to (e.g. a down-facing piston over a closed shaft). Leave it for a minute.
            skipUntil.put(a.pos, nowSkip + (accessDone ? 60_000 : 4_000));
            noStationSince.putIfAbsent(a.pos.immutable(), nowSkip);
            XlrwDebug.logStationFail(a.pos, accessDone
                    ? "Vẫn không có chỗ đứng sau khi đã lên trụ tiếp cận -> tạm bỏ qua 60s (cần chỗ đứng không tới được)"
                    : "Không có chỗ đứng đi tới được (flood " + reach.size() + " ô) và không dựng được trụ");
        }
        return null;
    }

    private List<Anchor> collectAnchors(ClientLevel level, LocalPlayer player, Schematic schematic, int layerIndex) {
        BuilderConfig config = BuilderConfig.getInstance();
        TaskTracker tracker = TaskTracker.getInstance();
        int layerY = schematic.getMinY() + layerIndex;

        List<Map.Entry<BlockPos, BlockState>> entries = config.layerMode
                ? schematic.getBlocksAtY(layerY)
                : new ArrayList<>(schematic.getWorldBlocks().entrySet());
        if (config.layerMode) {
            entries = new ArrayList<>(entries);
            entries.addAll(BuilderController.getInstance().leftoverEntries(schematic, level));
        }

        lastFloating.clear();
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
            List<Anchor> target = isPiston ? solidPiston : solidNormal;

            BuildRules.BuildStatus status = BuildRules.buildStatus(inWorld, expected);
            if (status == BuildRules.BuildStatus.MISSING) {
                if (!BuilderController.isAccessible(level, pos)) continue;
                // Needs something to place against, otherwise it can't be built yet
                Item item = InventoryHelper.getItemForBlockState(expected);
                if (item == null) continue;
                if (!player.isCreative() && !InventoryHelper.hasItem(player, item)) continue;
                // Nothing to place against yet: part of a floating section, handled by support columns
                // (with Air Place on, such blocks are placed directly in the air instead)
                if (!config.airPlace && !BuilderController.hasSolidNeighbor(level, pos)) {
                    lastFloating.add(pos.immutable());
                    continue;
                }
                if (expected.getBlock() instanceof FallingBlock && level.getBlockState(pos.below()).isAir()) continue;
                target.add(new Anchor(pos, expected, Kind.PLACE));
            } else if (status == BuildRules.BuildStatus.STACKABLE) {
                Item item = InventoryHelper.getItemForBlockState(expected);
                if (item == null) continue;
                if (!player.isCreative() && !InventoryHelper.hasItem(player, item)) continue;
                target.add(new Anchor(pos, expected, Kind.INTERACT));
            } else if (status == BuildRules.BuildStatus.ADJUST) {
                target.add(new Anchor(pos, expected, Kind.INTERACT));
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

        // In survival with an empty bucket and no water bucket: refill from an available source first
        if (!player.isCreative() && !InventoryHelper.hasItem(player, Items.WATER_BUCKET) && InventoryHelper.hasItem(player, Items.BUCKET) && !fluid.isEmpty()) {
            BlockPos refillSource = PlacementFinder.findNearbyWaterSource(level, player.getEyePosition(), 16.0 * 16.0, schematic);
            if (refillSource != null) {
                fluid.clear();
                fluid.add(new Anchor(refillSource, Blocks.WATER.defaultBlockState(), Kind.FLUID));
            }
        }

        // Strict build priority: normal blocks & redstone > pistons > water/lava
        if (!solidNormal.isEmpty()) return solidNormal;
        if (!solidPiston.isEmpty()) return solidPiston;
        // Floating sections still need support: water/lava must wait (it would flood the support work)
        if (!lastFloating.isEmpty()) return new ArrayList<>();
        return fluid;
    }

    // ------------------------------------------------------------------------------------------------
    // Ground station search
    // ------------------------------------------------------------------------------------------------

    private Choice findGroundStation(ClientLevel level, LocalPlayer player, Anchor anchor, LocalPathfinder.Reachability reach,
                                     List<Anchor> all) {
        BuilderConfig config = BuilderConfig.getInstance();
        // Slightly shorter than the real reach so the placer (which uses the full reach) always agrees
        double reachDist = Math.max(2.5, com.xlrw.account.Plus.reach() - 0.3);
        double reachSq = reachDist * reachDist;
        int range = (int) Math.ceil(reachDist);

        BlockPos anchorPos = anchor.pos;
        Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);

        // feet -> cost, all valid stations (coverage is evaluated afterwards on the cheapest few only)
        List<Map.Entry<BlockPos, Double>> found = new ArrayList<>();
        double bestCost = Double.MAX_VALUE; // cheapest so far, only used to prune hopeless cells early
        boolean oriented = anchor.kind == Kind.PLACE && BuildRules.hasOrientation(anchor.expected);
        int playerFeetY = (int) Math.floor(player.getY() + 0.01);

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = 2; dy >= -4; dy--) {
                    BlockPos feet = anchorPos.offset(dx, dy, dz);

                    // Cheapest checks first: must be a reachable standing cell
                    double walk = reach.costTo(feet);
                    if (walk == Double.MAX_VALUE) continue;
                    // a station may still win on coverage, so keep anything up to a few blocks of extra walking
                    if (walk >= bestCost + 8.0) continue;

                    // Never stand inside / directly on top of the block we want to build
                    if (dx == 0 && dz == 0 && anchorPos.getY() <= feet.getY() + 1 && anchorPos.getY() >= feet.getY() - 1) continue;

                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
                    double distSq = eye.distanceToSqr(anchorCenter);
                    if (distSq > reachSq) continue;

                    if (isBadStation(feet)) continue;
                    if (isBadStationForAnchor(anchorPos, feet)) continue;
                    if (PlacementFinder.isBadPlacementSpot(anchorPos, feet)) continue;

                    // Must be a real standing cell (the flood start cell is included even when it is not one)
                    // and never in water (the current would push us off the spot while placing)
                    // Dry standing cell, or (when nothing dry works) knee-deep STILL water: a pool the build itself
                    // filled has no current to push us off the spot
                    boolean stillWater = isStillShallowWater(level, feet);
                    if (!stillWater) {
                        if (!LocalPathfinder.isValidStand(level, feet)) continue;
                        if (!level.getFluidState(feet).isEmpty()) continue;
                    }
                    // Never "stand" half way up inside a scaffolding column: the walker can not stop there
                    if (level.getBlockState(feet).is(Blocks.SCAFFOLDING)) continue;

                    if (!canActFrom(level, player, anchor, eye, reachDist)) continue;

                    // Prefer a comfortable distance (not hugging the block, not at the edge of reach)
                    double cost = walk + Math.abs(Math.sqrt(distSq) - 2.6) * 0.3 + (stillWater ? 6.0 : 0.0);
                    // Up on a tower / on top of the build: stay up there. Climbing down for one block and building a
                    // new tower right after was the "up a bit, down again, somewhere else" loop.
                    int drop = playerFeetY - feet.getY();
                    if (drop >= 2) cost += (drop - 1) * 8.0;
                    // Facing-sensitive blocks (observer, piston, stairs...): the result must not change if the bot
                    // stops a little off-centre. Spots on a facing boundary get a big penalty.
                    if (oriented && !robustFrom(level, player, anchor, eye, reachDist)) cost += 25.0;
                    found.add(new AbstractMap.SimpleEntry<>(feet.immutable(), cost));
                    if (cost < bestCost) bestCost = cost;
                }
            }
        }
        if (found.isEmpty()) return null;
        found.sort(Map.Entry.comparingByValue());
        BlockPos bestFeet = null;
        double bestAdj = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(com.xlrw.account.Plus.groundCoverCandidates(), found.size()); i++) {
            BlockPos feet = found.get(i).getKey();
            Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
            double adj = found.get(i).getValue() - coverage(level, player, eye, all, anchor, reachDist) * COVER_WEIGHT_GROUND;
            if (adj < bestAdj) {
                bestAdj = adj;
                bestFeet = feet;
            }
        }
        return new Choice(anchor, bestFeet, bestAdj, null, null, 0);
    }

    /**
     * How many OTHER unfinished blocks could also be done from this eye position. A station / tower top that covers
     * a whole patch of work beats one that is a little closer but only reaches its own block - that was the
     * "climb up, place one block, climb down, build the next tower" loop.
     */
    private int coverage(ClientLevel level, LocalPlayer player, Vec3 eye, List<Anchor> all, Anchor self, double reach) {
        if (all == null) return 0;
        double r2 = (reach + 0.5) * (reach + 0.5);
        int n = 0;
        for (Anchor a : all) {
            if (a == self || a.kind == Kind.FLUID) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(a.pos)) > r2) continue;
            BlockPos feet = BlockPos.containing(eye.x, eye.y - 1.62, eye.z);
            if (a.pos.equals(feet) || a.pos.equals(feet.above())) continue; // would be inside the player
            if (canActFrom(level, player, a, eye, reach)) {
                if (++n >= COVER_CAP) break;
            }
        }
        return n;
    }

    /** True if the action also works from 4 eye positions 0.25 off the station centre (and when crouching). */
    private boolean robustFrom(ClientLevel level, LocalPlayer player, Anchor anchor, Vec3 eye, double reach) {
        double[][] offs = {{0.25, 0, 0}, {-0.25, 0, 0}, {0, 0, 0.25}, {0, 0, -0.25}, {0, -0.35, 0}};
        for (double[] o : offs) {
            if (!canActFrom(level, player, anchor, eye.add(o[0], o[1], o[2]), reach)) return false;
        }
        return true;
    }

    private boolean canActFrom(ClientLevel level, LocalPlayer player, Anchor anchor, Vec3 eye, double reach) {
        BlockPos pos = anchor.pos;
        if (anchor.kind == Kind.PLACE) {
            return PlacementFinder.canPlaceFrom(level, player, pos, anchor.expected, eye, reach);
        }
        if (anchor.kind == Kind.FLUID) {
            return PlacementFinder.canPlaceFluidFrom(level, player, pos, anchor.expected, eye, reach);
        }

        // Interact / break: the target block must be visible from here (anywhere mode: in reach is enough)
        if (PlacementFinder.findInteractPoint(level, player, eye, pos, reach * reach) != null) return true;
        return anchor.kind == Kind.INTERACT && PlacementFinder.throughWallAnywhere()
                && eye.distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }

    // ------------------------------------------------------------------------------------------------
    // Scaffolding tower search
    // ------------------------------------------------------------------------------------------------

    /** A column cell may hold the tower / the player only if it is free in the world AND not part of the build. */
    /** Y of the layer being built (layer mode); cells above it are not built yet and only need to be free now. */
    private static int activeLayerY = Integer.MAX_VALUE;

    private static boolean isColumnCellFree(ClientLevel level, Schematic schematic, BlockPos p) {
        BlockState expected = schematic.getBlock(p);
        // A future layer's cell may hold the player / the top of a tower for now: anything left there is removed
        // as "misplaced" when that layer is built.
        if (expected != null && !BuildRules.isSkipped(expected) && p.getY() <= activeLayerY) return false;
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.is(Blocks.SCAFFOLDING) || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    private Choice findTowerStation(ClientLevel level, LocalPlayer player, Schematic schematic, Anchor anchor,
                                    LocalPathfinder.Reachability reach, int scaffoldCount, List<Anchor> all) {
        BuilderConfig config = BuilderConfig.getInstance();
        double reachDist = Math.max(2.5, com.xlrw.account.Plus.reach() - 0.3);
        double reachSq = reachDist * reachDist;
        Vec3 anchorCenter = Vec3.atCenterOf(anchor.pos);

        Choice best = null;
        int ay = anchor.pos.getY();
        // Candidate columns: the 7x7 around the anchor, plus EXISTING scaffolding columns a bit further away
        // (reusing / raising an old tower is far cheaper than building a new one right next to it)
        java.util.LinkedHashMap<Long, Integer> columns = new java.util.LinkedHashMap<>(); // xz -> existing top Y (or MIN)
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = anchor.pos.getX() + dx, cz = anchor.pos.getZ() + dz;
                int top = Integer.MIN_VALUE;
                for (int yy = ay + 3; yy >= ay - MAX_TOWER_HEIGHT; yy--) {
                    if (level.getBlockState(new BlockPos(cx, yy, cz)).is(Blocks.SCAFFOLDING)) { top = yy; break; }
                }
                boolean near = Math.abs(dx) <= 3 && Math.abs(dz) <= 3;
                if (near || top != Integer.MIN_VALUE) columns.put(((long) cx << 32) ^ (cz & 0xffffffffL), top);
            }
        }
        for (Map.Entry<Long, Integer> col : columns.entrySet()) {
            {
                int cx = (int) (col.getKey() >> 32);
                int cz = (int) (long) col.getKey().intValue();
                int existingTop = col.getValue();

                // Preferred: feet level with the TOP of the layer being built (anchor.y + 1). From there the bot can
                // step off the tower onto the blocks it just placed and keep building on that surface, instead of
                // perching just high enough for one block and climbing down again.
                // An existing column: first try standing right on its current top (nothing to place).
                java.util.List<Integer> elevs = new java.util.ArrayList<>();
                if (existingTop != Integer.MIN_VALUE && existingTop + 1 >= ay - 1 && existingTop + 1 <= ay + 2) elevs.add(existingTop + 1);
                for (int e : new int[]{ay + 1, ay, ay + 2, ay - 1}) if (!elevs.contains(e)) elevs.add(e);
                for (int elev : elevs) {
                    BlockPos top = new BlockPos(cx, elev, cz);
                    Vec3 eye = new Vec3(cx + 0.5, elev + 1.62, cz + 0.5);
                    if (eye.distanceToSqr(anchorCenter) > reachSq) continue;

                    // Player body cells on top of the tower
                    if (!isColumnCellFree(level, schematic, top) || !isColumnCellFree(level, schematic, top.above())) continue;
                    if (level.getBlockState(top).is(Blocks.SCAFFOLDING)) continue; // would be standing INSIDE the column

                    // Walk down the column to the ground
                    int y = elev - 1;
                    boolean tooHigh = false;
                    while (isColumnCellFree(level, schematic, new BlockPos(cx, y, cz))) {
                        if (elev - y > MAX_TOWER_HEIGHT) {
                            tooHigh = true;
                            break;
                        }
                        y--;
                    }
                    if (tooHigh) continue;
                    // y is now the first non-free cell under the column: that is what the tower stands on
                    BlockPos ground = new BlockPos(cx, y, cz);
                    BlockPos base = ground.above();
                    int height = elev - base.getY(); // number of scaffolding blocks the column needs
                    if (height < 1) continue; // that is a ground station, handled elsewhere
                    if (!LocalPathfinder.isStandable(level, ground) || level.getBlockState(ground).is(Blocks.SCAFFOLDING)) continue;
                    if (isBadTower(base)) continue;

                    if (isBadStation(top) || isBadStationForAnchor(anchor.pos, top) || PlacementFinder.isBadPlacementSpot(anchor.pos, top)) continue;

                    int existing = 0;
                    for (int yy = base.getY(); yy < elev; yy++) {
                        if (level.getBlockState(new BlockPos(cx, yy, cz)).is(Blocks.SCAFFOLDING)) existing++;
                        else break;
                    }
                    int toPlace = height - existing;
                    if (toPlace > scaffoldCount) continue;

                    // How do we get there?
                    double accessCost;
                    BlockPos stand = null;
                    if (toPlace == 0 && reach.isReachable(base)) {
                        accessCost = reach.costTo(base);
                    } else {
                        stand = findTowerStand(level, player, reach, base);
                        if (stand == null) continue;
                        accessCost = reach.costTo(stand);
                    }

                    if (!canActFrom(level, player, anchor, eye, reachDist)) continue;

                    // Reuse strongly: every existing piece saves a placement and the walk to a new base
                    double cost = accessCost + toPlace * 1.5 + height * 0.8 - Math.min(existing, 12) * 2.5
                            + (existing == 0 ? 6.0 : 0.0);
                    // A top that reaches a whole patch of the layer (or lets the bot step onto it) beats one that
                    // only reaches this single block
                    cost -= coverage(level, player, eye, all, anchor, reachDist) * COVER_WEIGHT_TOWER;
                    if (best == null || cost < best.cost) {
                        best = new Choice(anchor, top, cost, base, stand, elev);
                    }
                    break; // first valid elevation in preference order wins for this column
                }
            }
        }
        return best;
    }

    /**
     * Tower whose top (feet = layer Y + 1) is right next to a standing cell on the build's top surface that the bot
     * can not walk to yet. Searched in a radius of 8 around the stuck anchors.
     */
    private Choice findAccessTower(ClientLevel level, LocalPlayer player, Schematic schematic, LocalPathfinder.Reachability reach,
                                   List<Anchor> stuck, int layerY, int scaffoldCount) {
        Choice best = null;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int elev = layerY + 1;
        // Wide search: work deep inside a big build is often > 8 blocks from the nearest column that is free all the
        // way down to the ground (the old +-8 window found nothing and the bot just stood there)
        for (Anchor anchor : stuck) {
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    int cx = anchor.pos.getX() + dx, cz = anchor.pos.getZ() + dz;
                    if (!seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) continue;
                    BlockPos top = new BlockPos(cx, elev, cz);
                    if (!isColumnCellFree(level, schematic, top) || !isColumnCellFree(level, schematic, top.above())) continue;
                    if (level.getBlockState(top).is(Blocks.SCAFFOLDING)) continue;
                    if (reach.isReachable(top)) continue; // already up there

                    // a surface cell next to the top we could step onto (and can not reach today)
                    BlockPos surface = null;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = top.relative(d);
                        if (reach.isReachable(n)) continue;
                        if (!LocalPathfinder.isValidStand(level, n)) continue;
                        if (level.getBlockState(n.below()).is(Blocks.SCAFFOLDING) || level.getBlockState(n).is(Blocks.SCAFFOLDING)) continue;
                        surface = n;
                        break;
                    }
                    if (surface == null) continue;

                    int y = elev - 1;
                    boolean tooHigh = false;
                    while (isColumnCellFree(level, schematic, new BlockPos(cx, y, cz))) {
                        if (elev - y > MAX_TOWER_HEIGHT) { tooHigh = true; break; }
                        y--;
                    }
                    if (tooHigh) continue;
                    BlockPos ground = new BlockPos(cx, y, cz);
                    BlockPos base = ground.above();
                    int height = elev - base.getY();
                    if (height < 2) continue;
                    if (!LocalPathfinder.isStandable(level, ground) || level.getBlockState(ground).is(Blocks.SCAFFOLDING)) continue;
                    if (isBadTower(base) || isBadStation(top)) continue;

                    int existing = 0;
                    for (int yy = base.getY(); yy < elev; yy++) {
                        if (level.getBlockState(new BlockPos(cx, yy, cz)).is(Blocks.SCAFFOLDING)) existing++;
                        else break;
                    }
                    int toPlace = height - existing;
                    if (toPlace > scaffoldCount) continue;

                    double accessCost;
                    BlockPos stand = null;
                    if (toPlace == 0 && reach.isReachable(base)) {
                        accessCost = reach.costTo(base);
                    } else {
                        stand = findTowerStand(level, player, reach, base);
                        if (stand == null) continue;
                        accessCost = reach.costTo(stand);
                    }
                    double cost = accessCost + toPlace * 1.5 + Math.sqrt(dx * dx + dz * dz) * 1.0 - Math.min(existing, 6) * 2.0;
                    if (best == null || cost < best.cost) {
                        best = new Choice(anchor, top, cost, base, stand, elev);
                    }
                }
            }
        }
        return best;
    }

    /** A reachable cell next to the column base from where the column can be raised (clicking its side face). */
    private BlockPos findTowerStand(ClientLevel level, LocalPlayer player, LocalPathfinder.Reachability reach, BlockPos base) {
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    double c = reach.costTo(p);
                    if (c == Double.MAX_VALUE) continue;
                    if (level.getBlockState(p).is(Blocks.SCAFFOLDING)) continue; // standing in another column
                    Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                    if (eye.distanceToSqr(Vec3.atCenterOf(base)) > 3.6 * 3.6) continue;
                    // The side face of the base must be visible (no wall in between)
                    BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(base), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                    if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(base)
                            && !level.getBlockState(hit.getBlockPos()).is(Blocks.SCAFFOLDING)) continue;
                    double cost = c + (Math.abs(dx) + Math.abs(dz) == 1 ? 0 : 0.5);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }


    // ------------------------------------------------------------------------------------------------
    // Floating sections: temporary scaffolding support columns
    // ------------------------------------------------------------------------------------------------

    /** The support column is finished: drop the plan WITHOUT skipping the anchor, so it gets placed right away. */
    public void completeSupport() {
        if (plan != null) {
            XlrwDebug.log("SUPPORT/READY", "Cột đỡ dưới " + plan.anchor.toShortString() + " đã xong, chuyển sang đặt block.");
        }
        dropPlan();
        nextPlanAt = 0;
    }

    /**
     * Groups the floating blocks into connected sections and, for the cheapest one, plans ONE scaffolding column
     * from the ground up to just below a "seed" block. Once the seed is placed against the column top, the rest of
     * the section has a neighbour and is built normally.
     */
    private Plan planSupportColumn(ClientLevel level, LocalPlayer player, Schematic schematic,
                                   LocalPathfinder.Reachability reach, BlockPos playerFeet) {
        Set<BlockPos> floating = new HashSet<>(lastFloating);
        Set<BlockPos> seen = new HashSet<>();
        int scaffoldCount = player.isCreative() ? 999 : countItem(player, Items.SCAFFOLDING);

        BlockPos bestSeed = null, bestBase = null, bestStand = null;
        double bestCost = Double.MAX_VALUE;
        int sections = 0;

        for (BlockPos start : lastFloating) {
            if (!seen.add(start)) continue;
            // Flood the connected floating section
            List<BlockPos> section = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty() && section.size() < 400) {
                BlockPos p = queue.poll();
                section.add(p);
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (floating.contains(n) && seen.add(n)) queue.add(n);
                }
            }
            sections++;

            // Evaluate a handful of seeds per section (simple blocks first: no facing to get wrong)
            section.sort(Comparator.comparingInt((BlockPos p) -> BuildRules.hasOrientation(schematic.getBlock(p)) ? 1 : 0)
                    .thenComparingDouble(p -> p.distSqr(playerFeet)));
            int evaluated = 0;
            for (BlockPos seed : section) {
                if (evaluated++ >= 24) break;
                if (isSkipped(seed)) continue;

                // Column from the ground up to seed.y - 1
                int y = seed.getY() - 1;
                boolean tooHigh = false;
                while (isColumnCellFree(level, schematic, new BlockPos(seed.getX(), y, seed.getZ()))) {
                    if (seed.getY() - y > MAX_TOWER_HEIGHT) { tooHigh = true; break; }
                    y--;
                }
                if (tooHigh) continue;
                BlockPos ground = new BlockPos(seed.getX(), y, seed.getZ());
                if (!LocalPathfinder.isStandable(level, ground) || level.getBlockState(ground).is(Blocks.SCAFFOLDING)) continue;
                BlockPos base = ground.above();
                int height = seed.getY() - base.getY();
                if (height < 1) continue;
                if (isBadTower(base)) continue;

                int existing = 0;
                for (int yy = base.getY(); yy < seed.getY(); yy++) {
                    if (level.getBlockState(new BlockPos(seed.getX(), yy, seed.getZ())).is(Blocks.SCAFFOLDING)) existing++;
                    else break;
                }
                int toPlace = height - existing;
                if (toPlace > scaffoldCount) continue;

                BlockPos stand = findTowerStand(level, player, reach, base);
                if (stand == null) continue;

                double cost = reach.costTo(stand) + toPlace * 1.5
                        + (BuildRules.hasOrientation(schematic.getBlock(seed)) ? 6.0 : 0.0);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestSeed = seed;
                    bestBase = base;
                    bestStand = stand;
                }
            }
        }

        if (bestSeed == null) {
            XlrwDebug.logRateLimited("SUPPORT_NONE", 5000, "SUPPORT/FAIL",
                    lastFloating.size() + " block lơ lửng (" + sections + " cụm) nhưng không tìm được chỗ dựng cột đỡ đi tới được");
            return null;
        }

        BlockState expected = schematic.getBlock(bestSeed);
        XlrwDebug.log("SUPPORT/PLAN", String.format("%d block lơ lửng trong %d cụm. Dựng cột giàn giáo đỡ tại %s (cao tới Y=%d) dưới block %s [%s], đứng ở %s",
                lastFloating.size(), sections, bestBase.toShortString(), bestSeed.getY() - 1, bestSeed.toShortString(),
                expected != null ? expected.getBlock().getName().getString() : "?", bestStand.toShortString()));
        return new Plan(bestSeed, bestStand, Kind.PLACE, PlanKind.SUPPORT_COLUMN, bestBase, bestSeed.getY(), bestStand,
                PlanPhase.APPROACH_TOWER_BASE);
    }

    private static int countItem(LocalPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = player.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }
}
