package com.xlrw.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Path-following walker.
 *
 * <p>Design rules (each one fixes a failure seen in the diagnostic logs):
 * <ul>
 *   <li>It walks the A* path from {@link LocalPathfinder}, which uses exactly the same move rules as the planner's
 *       flood. A planned drop is executed; an unplanned drop is prevented by SNEAKING at the ledge (vanilla keeps a
 *       sneaking player on the block) instead of freezing and failing the plan.</li>
 *   <li>Obstacles are detected in the direction of travel, not the camera direction.</li>
 *   <li>Keys are projected onto the camera yaw, so the player keeps moving toward the waypoint while turning.</li>
 *   <li>Scaffolding: hold JUMP to climb a waypoint straight above, hold SNEAK to sink to a waypoint straight below.</li>
 *   <li>Sneak-bridging is only a last resort when there is no path at all, never toward a lower goal and never into
 *       a cell that belongs to the schematic. Every bridge block is recorded so it can be cleaned up later.</li>
 * </ul>
 */
public class PrinterMovement {

    private static final int PATH_NODES = 5000;

    private static List<BlockPos> currentPath = null;
    private static int pathIndex = 0;
    private static BlockPos lastGoal = null;
    private static long lastPathCalcTime = 0;
    private static boolean pathIsPartial = false;

    private static int stuckTicks = 0;
    private static Vec3 lastPosition = Vec3.ZERO;
    private static int jumpHoldTicks = 0;
    private static int noPathTicks = 0;

    // ------------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------------

    public static boolean moveTo(Minecraft mc, Vec3 targetPos, double stopDistance) {
        return moveTo(mc, targetPos, stopDistance, false, false);
    }

    /**
     * Moves the player toward {@code targetPos}.
     *
     * @return true once the player is within {@code stopDistance} (horizontally) of the target.
     */
    public static boolean moveTo(Minecraft mc, Vec3 targetPos, double stopDistance, boolean keepMomentum, boolean isAimingAtBlock) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return false;

        // A running scaffold jump-place owns the keys
        ScaffoldManager scaffoldManager = ScaffoldManager.getInstance();
        if (scaffoldManager.isPerformingScaffoldJump()) {
            if (scaffoldManager.tickScaffoldJump(mc, player)) return false;
        }

        BlockPos foot = player.blockPosition();

        // Deep water (nothing to stand on): swim up and head for the nearest dry land.
        // Shallow water is handled by the normal path, which never walks against the current.
        if ((player.isInWater() || player.isUnderWater()) && !LocalPathfinder.isShallowWater(level, foot)
                && !LocalPathfinder.isStandable(level, foot.below())) {
            setJump(mc, player, true);
            BlockPos dryLand = findNearestDryLand(level, foot, 8);
            if (dryLand != null) {
                targetPos = new Vec3(dryLand.getX() + 0.5, dryLand.getY(), dryLand.getZ() + 0.5);
            }
        }

        double dxT = targetPos.x - player.getX();
        double dzT = targetPos.z - player.getZ();
        double dyT = targetPos.y - player.getY();
        double distXZ = Math.hypot(dxT, dzT);

        // ---- Arrival ----
        if (distXZ <= stopDistance && Math.abs(dyT) <= 1.2) {
            if (!keepMomentum) {
                stop(mc);
                resetPath();
            }
            return true;
        }

        BlockPos goalBlock = BlockPos.containing(targetPos);
        BlockPos startCell = LocalPathfinder.normalizeStart(level, foot);
        long now = System.currentTimeMillis();

        // ---- Stuck tracking (only while we are actually trying to move) ----
        Vec3 curPos = player.position();
        double moved = Math.hypot(curPos.x - lastPosition.x, curPos.z - lastPosition.z) + Math.abs(curPos.y - lastPosition.y);
        lastPosition = curPos;
        if (moved < 0.02 && anyMoveKeyDown(mc)) {
            stuckTicks++;
        } else if (moved > 0.06) {
            stuckTicks = Math.max(0, stuckTicks - 2);
        }

        // ---- Path management ----
        boolean goalChanged = lastGoal == null || !lastGoal.equals(goalBlock);
        boolean offPath = currentPath != null && pathIndex < currentPath.size()
                && currentPath.get(pathIndex).distSqr(startCell) > 8.0 && player.onGround();
        boolean stuckRepath = stuckTicks >= 20 && now - lastPathCalcTime > 1000;
        boolean needPath = goalChanged || currentPath == null || offPath || stuckRepath
                || (pathIsPartial && pathIndex >= currentPath.size() - 2);
        if (needPath && (goalChanged || stuckRepath || now - lastPathCalcTime > 500)) {
            lastPathCalcTime = now;
            lastGoal = goalBlock;
            List<BlockPos> path = LocalPathfinder.findPath(level, startCell, goalBlock, PATH_NODES);
            pathIsPartial = false;
            if (path.isEmpty() && startCell.distSqr(goalBlock) > 24 * 24) {
                // Far away (e.g. supply chest): walk the best partial route toward it
                path = findPartialPath(level, startCell, goalBlock);
                pathIsPartial = !path.isEmpty();
            }
            currentPath = path.isEmpty() ? null : path;
            pathIndex = 0;
            if (offPath || stuckRepath) {
                com.xlrw.debug.XlrwDebug.logRateLimited("REPATH_" + goalBlock.asLong(), 1500, "MOVE/REPATH",
                        String.format("Tính lại đường tới %s (offPath=%b, stuck=%d) -> %s", goalBlock.toShortString(), offPath, stuckTicks,
                                currentPath == null ? "KHÔNG CÓ ĐƯỜNG" : currentPath.size() + " bước"));
            }
        }

        // ---- Advance along the path ----
        if (currentPath != null) {
            // If we are already standing on a later waypoint, jump ahead to it
            int limit = Math.min(currentPath.size(), pathIndex + 6);
            for (int i = limit - 1; i >= pathIndex; i--) {
                if (currentPath.get(i).equals(startCell)) {
                    pathIndex = i + 1;
                    break;
                }
            }
            // String pulling: cut corners on flat ground when the straight line is safe
            if (pathIndex < currentPath.size() && player.onGround()) {
                int maxLook = Math.min(currentPath.size() - 1, pathIndex + 3);
                for (int k = maxLook; k > pathIndex; k--) {
                    BlockPos wp = currentPath.get(k);
                    if (wp.getY() != startCell.getY()) continue;
                    boolean flatSoFar = true;
                    for (int j = pathIndex; j <= k; j++) {
                        if (currentPath.get(j).getY() != startCell.getY()) { flatSoFar = false; break; }
                    }
                    if (flatSoFar && canWalkDirectly(level, player.position(), wp)) {
                        pathIndex = k;
                        break;
                    }
                }
            }
            if (pathIndex >= currentPath.size()) {
                currentPath = null;
            }
        }

        // ---- Choose the active waypoint ----
        Vec3 waypoint;
        BlockPos wpBlock;
        if (currentPath != null) {
            wpBlock = currentPath.get(pathIndex);
            waypoint = new Vec3(wpBlock.getX() + 0.5, wpBlock.getY(), wpBlock.getZ() + 0.5);
            noPathTicks = 0;
        } else if (canWalkDirectly(level, player.position(), goalBlock) || startCell.distSqr(goalBlock) <= 2) {
            wpBlock = goalBlock;
            waypoint = targetPos;
            noPathTicks = 0;
        } else {
            // No path at all. Last resort: a short, safe sneak-bridge on the same level toward the goal.
            noPathTicks++;
            if (tryBridgeTowardGoal(mc, player, level, foot, goalBlock)) {
                return false;
            }
            stop(mc);
            com.xlrw.debug.XlrwDebug.logRateLimited("UNREACHABLE_GOAL_" + goalBlock.asLong(), 1500, "MOVE/UNREACHABLE",
                    String.format("Không có đường tới %s từ %s", goalBlock.toShortString(), startCell.toShortString()));
            if (noPathTicks >= 10) {
                noPathTicks = 0;
                com.xlrw.builder.StationPlanner.getInstance().markStationBad(goalBlock, 15000);
                com.xlrw.builder.StationPlanner.getInstance().failPlan(8000);
                resetPath();
            }
            return false;
        }

        // ---- Hard stuck: give the planner a chance to pick something else ----
        if (stuckTicks >= 60) {
            com.xlrw.debug.XlrwDebug.log("MOVE/STUCK_TIMEOUT", String.format("Kẹt 3s tại %s khi đi tới %s. Đánh dấu trạm xấu và lập kế hoạch lại.",
                    startCell.toShortString(), goalBlock.toShortString()));
            com.xlrw.builder.StationPlanner.getInstance().markStationBad(goalBlock, 15000);
            com.xlrw.builder.StationPlanner.getInstance().failPlan(8000);
            resetPath();
            stop(mc);
            return false;
        }

        steerToWaypoint(mc, player, level, startCell, wpBlock, waypoint, targetPos, isAimingAtBlock);
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // Steering
    // ------------------------------------------------------------------------------------------------

    private static void steerToWaypoint(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos startCell, BlockPos wpBlock,
                                        Vec3 waypoint, Vec3 finalTarget, boolean isAimingAtBlock) {
        double toWpX = waypoint.x - player.getX();
        double toWpZ = waypoint.z - player.getZ();
        double wpDistXZ = Math.hypot(toWpX, toWpZ);
        double wpDy = wpBlock.getY() - startCell.getY();

        boolean feetInScaffold = level.getBlockState(player.blockPosition()).is(Blocks.SCAFFOLDING);
        boolean scaffoldBelow = level.getBlockState(player.blockPosition().below()).is(Blocks.SCAFFOLDING);
        boolean sameColumn = wpBlock.getX() == startCell.getX() && wpBlock.getZ() == startCell.getZ();

        // ---- Vertical scaffolding moves ----
        if (sameColumn && wpDy > 0 && (feetInScaffold || scaffoldBelow)) {
            centerHorizontally(mc, player, startCell);
            setSneak(mc, player, false);
            setJump(mc, player, true);
            return;
        }
        if (sameColumn && wpDy < 0 && scaffoldBelow) {
            centerHorizontally(mc, player, startCell);
            setJump(mc, player, false);
            holdSneakToDescend(mc, player);
            return;
        }

        // ---- Horizontal movement ----
        double finalDist = Math.hypot(finalTarget.x - player.getX(), finalTarget.z - player.getZ());
        boolean onWaypoint = wpDistXZ < 0.2;
        double moveX = onWaypoint ? 0 : toWpX / Math.max(wpDistXZ, 1e-4);
        double moveZ = onWaypoint ? 0 : toWpZ / Math.max(wpDistXZ, 1e-4);

        // Camera follows the travel direction unless something else is aiming
        if (!isAimingAtBlock && !onWaypoint && wpDistXZ > 0.45) {
            float targetYaw = (float) (Math.toDegrees(Math.atan2(toWpZ, toWpX)) - 90.0);
            float diff = Mth.wrapDegrees(targetYaw - player.getYRot());
            float step = Mth.clamp(diff * 0.45f, -30.0f, 30.0f);
            if (Math.abs(step) < 1.0f) step = diff;
            player.setYRot(player.getYRot() + step);
        }

        // Project the desired direction onto the camera: works while turning and while aiming elsewhere
        double yawRad = Math.toRadians(player.getYRot());
        double fwdX = -Math.sin(yawRad), fwdZ = Math.cos(yawRad);
        double leftX = Math.cos(yawRad), leftZ = Math.sin(yawRad);
        double fwd = moveX * fwdX + moveZ * fwdZ;
        double left = moveX * leftX + moveZ * leftZ;
        boolean up = fwd > 0.3, down = fwd < -0.3, l = left > 0.3, r = left < -0.3;
        setMoveKeys(mc, player, up, down, l, r, (float) (up ? 1 : down ? -1 : 0), (float) (l ? 1 : r ? -1 : 0));

        // ---- Edge safety: allow exactly the drop the path asked for, sneak against every other ledge ----
        boolean sneak = false;
        boolean jump = false;
        int allowedDrop = wpDy < 0 ? (int) -wpDy : 0;
        if (player.onGround() && !feetInScaffold && !onWaypoint) {
            BlockPos probe = BlockPos.containing(player.getX() + moveX * 0.55, player.getY() + 0.01, player.getZ() + moveZ * 0.55);
            // Stepping onto / into a scaffolding column is never a ledge. (While sneaking, scaffolding has NO top
            // collision, so vanilla edge protection would refuse the step and the bot would hang at the rim forever.)
            boolean scaffoldStep = LocalPathfinder.isScaffold(level, probe) || LocalPathfinder.isScaffold(level, probe.below());
            if (!scaffoldStep && !probe.equals(player.blockPosition()) && LocalPathfinder.isPassable(level, probe)) {
                int depth = floorDepth(level, probe);       // 1 = flat, 2 = one block down ... 0 = nothing within 4
                int drop = depth == 0 ? 99 : depth - 1;
                if (drop > allowedDrop) {
                    boolean parkour = isParkourStep(level, startCell, wpBlock);
                    if (parkour && wpDistXZ > 1.1) {
                        jump = true;               // leap the gap
                    } else {
                        sneak = true;              // vanilla edge protection: slide along, never fall
                        com.xlrw.debug.XlrwDebug.logRateLimited("EDGE_" + startCell.asLong(), 3000, "MOVE/EDGE",
                                String.format("Mép tại %s hướng %s (rơi %s, đường cho phép %d) -> giữ Shift",
                                        startCell.toShortString(), probe.toShortString(), drop == 99 ? ">3" : String.valueOf(drop), allowedDrop));
                    }
                }
            }
        }

        // ---- Step up: jump when the path climbs or we bump into a 1-block step ----
        // In water the player floats (not "on ground"): to get out onto a 1-block ledge, swim up + push forward
        if (player.isInWater() && !feetInScaffold && (wpDy > 0 || player.horizontalCollision)) {
            jump = true;
        }
        if (!sneak && player.onGround() && !feetInScaffold) {
            boolean pathClimbs = wpDy > 0 && wpDistXZ < 1.4;
            BlockPos ahead = BlockPos.containing(player.getX() + moveX * 0.6, player.getY() + 0.01, player.getZ() + moveZ * 0.6);
            boolean bump = player.horizontalCollision && !LocalPathfinder.isPassable(level, ahead)
                    && LocalPathfinder.isPassable(level, ahead.above()) && LocalPathfinder.isPassable(level, ahead.above(2))
                    && LocalPathfinder.isPassable(level, player.blockPosition().above(2));
            boolean fenceAhead = LocalPathfinder.isTallCollision(level, ahead); // jumping can never clear a fence / wall
            if (!fenceAhead && (pathClimbs || bump || (stuckTicks >= 10 && stuckTicks % 10 == 0 && LocalPathfinder.isPassable(level, player.blockPosition().above(2))))) {
                jump = true;
            }
        }
        if (jump) jumpHoldTicks = 3;
        if (jumpHoldTicks > 0) {
            jumpHoldTicks--;
            setJump(mc, player, true);
        } else {
            setJump(mc, player, false);
        }

        // Fine positioning near the final target: sneak-walk so we stop exactly on the station
        BlockPos targetCell = BlockPos.containing(finalTarget.x, finalTarget.y + 0.01, finalTarget.z);
        boolean scaffoldTarget = LocalPathfinder.isScaffold(level, targetCell) || LocalPathfinder.isScaffold(level, targetCell.below())
                || LocalPathfinder.isScaffold(level, wpBlock) || LocalPathfinder.isScaffold(level, wpBlock.below());
        if (finalDist < 0.9 && Math.abs(finalTarget.y - player.getY()) < 0.6 && !scaffoldTarget) {
            sneak = true;
        }
        if (scaffoldTarget && !(sameColumn && wpDy < 0)) {
            sneak = false; // walking onto a scaffolding column: sneaking would make it non-solid under us
        }
        if (sneak && scaffoldBelow) {
            // Sneaking on top of scaffolding would sink us into it: brake instead
            sneak = false;
            if (finalDist >= 0.9) {
                setMoveKeys(mc, player, false, false, false, false, 0f, 0f);
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(0, v.y, 0);
                up = false;
            }
        }
        setSneak(mc, player, sneak);

        boolean canSprint = up && fwd > 0.85 && !sneak && !feetInScaffold && finalDist > 3.5
                && !jump && wpDy == 0 && !hasDeepDropNear(level, player, 1.6)
                && (player.getFoodData() == null || player.getFoodData().getFoodLevel() > 6);
        mc.options.keySprint.setDown(canSprint || jump && isParkourStep(level, startCell, wpBlock));
        if (canSprint) player.setSprinting(true);
        else if (!jump) player.setSprinting(false);

        if (stuckTicks == 8) {
            com.xlrw.debug.XlrwDebug.logMoveStuck(player.position(), waypoint, stuckTicks,
                    String.format("up=%b down=%b left=%b right=%b sneak=%b", up, down, l, r, sneak),
                    String.format("wp=%s, collision=%b", wpBlock.toShortString(), player.horizontalCollision));
        }
    }

    /** 1 = floor right below (flat), 2..4 = drop of 1..3 blocks, 0 = no floor within 4 blocks. */
    private static int floorDepth(ClientLevel level, BlockPos cell) {
        for (int k = 1; k <= 4; k++) {
            BlockPos p = cell.below(k);
            if (LocalPathfinder.isStandable(level, p)) return k;
            if (!LocalPathfinder.isPassable(level, p)) return 0; // hazard (water/lava...) - treat as no landing
        }
        return 0;
    }

    private static boolean isParkourStep(ClientLevel level, BlockPos from, BlockPos wp) {
        int dx = wp.getX() - from.getX();
        int dz = wp.getZ() - from.getZ();
        if (wp.getY() != from.getY()) return false;
        if (!((Math.abs(dx) == 2 && dz == 0) || (Math.abs(dz) == 2 && dx == 0))) return false;
        BlockPos gap = from.offset(Integer.signum(dx), 0, Integer.signum(dz));
        return !LocalPathfinder.isStandable(level, gap.below());
    }

    private static void centerHorizontally(Minecraft mc, LocalPlayer player, BlockPos cell) {
        setMoveKeys(mc, player, false, false, false, false, 0f, 0f);
        mc.options.keySprint.setDown(false);
        double cx = cell.getX() + 0.5, cz = cell.getZ() + 0.5;
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(Mth.clamp((cx - player.getX()) * 0.35, -0.15, 0.15), v.y, Mth.clamp((cz - player.getZ()) * 0.35, -0.15, 0.15));
    }

    // ------------------------------------------------------------------------------------------------
    // Bridging (last resort)
    // ------------------------------------------------------------------------------------------------

    private static boolean tryBridgeTowardGoal(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos foot, BlockPos goal) {
        if (!player.onGround()) return false;
        if (goal.getY() < foot.getY()) return false;                 // never bridge out over a goal that is below us
        if (foot.distSqr(goal) > 12 * 12) return false;              // only short hops
        double dx = goal.getX() + 0.5 - player.getX();
        double dz = goal.getZ() + 0.5 - player.getZ();
        Direction dir = Direction.getNearest(dx, 0, dz);
        if (dir.getAxis() == Direction.Axis.Y) return false;

        BlockPos gapFloor = foot.below().relative(dir);
        // Only a 1-2 block gap with real ground on the far side is worth a temporary block
        boolean landing1 = LocalPathfinder.isStandable(level, gapFloor.relative(dir));
        boolean landing2 = LocalPathfinder.isStandable(level, gapFloor.relative(dir, 2));
        if (!landing1 && !landing2) return false;

        com.xlrw.schematic.Schematic sch = com.xlrw.builder.BuilderController.getInstance().getSchematic();
        if (sch != null && sch.getBlock(gapFloor) != null) return false; // reserved for the build

        return SneakBridgeHelper.trySneakBridge(mc, player, level, foot, dir);
    }

    private static List<BlockPos> findPartialPath(ClientLevel level, BlockPos start, BlockPos goal) {
        // Aim at an intermediate point ~20 blocks toward the goal at the same height, then retry from there
        double dx = goal.getX() - start.getX();
        double dz = goal.getZ() - start.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1) return java.util.Collections.emptyList();
        for (int dist = 20; dist >= 6; dist -= 7) {
            int ix = start.getX() + (int) Math.round(dx / len * dist);
            int iz = start.getZ() + (int) Math.round(dz / len * dist);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos p = new BlockPos(ix, start.getY() + dy, iz);
                if (LocalPathfinder.isValidStand(level, p)) {
                    List<BlockPos> path = LocalPathfinder.findPath(level, start, p, 2500);
                    if (!path.isEmpty()) return path;
                }
            }
        }
        return java.util.Collections.emptyList();
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    public static boolean canWalkDirectly(ClientLevel level, Vec3 from, BlockPos to) {
        if (level == null || to == null) return false;
        double dy = Math.abs(from.y - to.getY());
        if (dy > 0.6) return false;

        double targetX = to.getX() + 0.5;
        double targetZ = to.getZ() + 0.5;
        double dx = targetX - from.x;
        double dz = targetZ - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.3) return true;

        // Sample the centre line AND both shoulders of the player's 0.6 wide body
        double nx = -dz / dist * 0.3, nz = dx / dist * 0.3;
        int steps = Math.max(2, (int) Math.ceil(dist / 0.4));
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            double sx = from.x + dx * f;
            double sz = from.z + dz * f;
            for (int side = -1; side <= 1; side++) {
                BlockPos s = BlockPos.containing(sx + nx * side, from.y + 0.01, sz + nz * side);
                if (!LocalPathfinder.isPassable(level, s) || !LocalPathfinder.isPassable(level, s.above())) return false;
                if (side == 0 && !LocalPathfinder.isStandable(level, s.below())) return false;
            }
        }
        return true;
    }

    public static void resetPath() {
        currentPath = null;
        pathIndex = 0;
        stuckTicks = 0;
        jumpHoldTicks = 0;
        noPathTicks = 0;
        lastGoal = null;
        pathIsPartial = false;
        ScaffoldManager.getInstance().cancelJump();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.keySprint.setDown(false);
        if (mc != null && mc.player != null) mc.player.setSprinting(false);
    }

    public static void stop(Minecraft mc) {
        jumpHoldTicks = 0;
        if (mc != null && mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.keySprint.setDown(false);
        }
        if (mc != null && mc.player != null) {
            mc.player.setSprinting(false);
            if (mc.player.input != null) {
                mc.player.input.up = false;
                mc.player.input.down = false;
                mc.player.input.left = false;
                mc.player.input.right = false;
                mc.player.input.forwardImpulse = 0.0f;
                mc.player.input.leftImpulse = 0.0f;
                mc.player.input.jumping = false;
                mc.player.input.shiftKeyDown = false;
            }
            // Anti-fall: when stopping next to a drop, keep SNEAK held so leftover momentum (or a later nudge)
            // can never carry the bot over the edge while it stands there building
            if (mc.level != null && isNearDangerousEdge(mc.level, mc.player)) {
                mc.options.keyShift.setDown(true);
                if (mc.player.input != null) mc.player.input.shiftKeyDown = true;
            }
        }
    }

    /** Any cell within {@code radius} of the player (at feet level) with a drop of 3+ blocks: no sprinting there. */
    public static boolean hasDeepDropNear(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.client.player.LocalPlayer p, double radius) {
        int fy = (int) Math.floor(p.getY() + 0.01);
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double cx = Math.floor(p.getX()) + dx + 0.5, cz = Math.floor(p.getZ()) + dz + 0.5;
                if (Math.hypot(cx - p.getX(), cz - p.getZ()) > radius + 0.5) continue;
                BlockPos cell = new BlockPos((int) Math.floor(cx), fy, (int) Math.floor(cz));
                if (!LocalPathfinder.isPassable(level, cell)) continue;
                if (LocalPathfinder.isStandable(level, cell.below()) || LocalPathfinder.isStandable(level, cell.below(2))
                        || LocalPathfinder.isStandable(level, cell.below(3))) continue;
                return true;
            }
        }
        return false;
    }

    /**
     * True when the player stands on solid ground (not scaffolding, not in water) and part of the area around its
     * feet (hitbox + 0.3) is a drop of 2 or more blocks.
     */
    public static boolean isNearDangerousEdge(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.client.player.LocalPlayer p) {
        if (p == null || !p.onGround() || p.isInWater() || p.isInLava()) return false;
        BlockPos feet = p.blockPosition();
        if (level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)
                || level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) return false;
        double r = 0.3 + 0.3;
        double[][] offs = {{r, 0}, {-r, 0}, {0, r}, {0, -r}, {r, r}, {r, -r}, {-r, r}, {-r, -r}};
        int fy = (int) Math.floor(p.getY() + 0.01);
        for (double[] o : offs) {
            int cx = (int) Math.floor(p.getX() + o[0]);
            int cz = (int) Math.floor(p.getZ() + o[1]);
            BlockPos cell = new BlockPos(cx, fy, cz);
            if (!LocalPathfinder.isPassable(level, cell)) continue; // a wall there, not an edge
            if (LocalPathfinder.isStandable(level, cell.below())) continue;
            if (LocalPathfinder.isStandable(level, cell.below(2))) continue; // a 1-block step down is harmless
            return true; // a real drop (also into water / lava: hazards are not standable)
        }
        return false;
    }

    private static boolean anyMoveKeyDown(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown() || mc.options.keyJump.isDown();
    }

    private static void setMoveKeys(Minecraft mc, LocalPlayer p, boolean up, boolean down, boolean left, boolean right,
                                    float forwardImpulse, float leftImpulse) {
        mc.options.keyUp.setDown(up);
        mc.options.keyDown.setDown(down);
        mc.options.keyLeft.setDown(left);
        mc.options.keyRight.setDown(right);
        if (p.input != null) {
            p.input.up = up;
            p.input.down = down;
            p.input.left = left;
            p.input.right = right;
            p.input.forwardImpulse = forwardImpulse;
            p.input.leftImpulse = leftImpulse;
        }
    }

    private static void setJump(Minecraft mc, LocalPlayer p, boolean jump) {
        mc.options.keyJump.setDown(jump);
        if (p.input != null) p.input.jumping = jump;
    }

    private static double descendLastY = Double.NaN;
    private static int descendStall = 0;
    private static int descendRelease = 0;

    /**
     * Hold SNEAK to sink down a scaffolding column. Sometimes the game does not register a sneak that was already
     * held when the bot stepped onto the scaffolding (the player just stands there sneaking): if we do not go down
     * for half a second, let go of SNEAK for 2 ticks and press it again - like tapping shift by hand.
     */
    public static void holdSneakToDescend(Minecraft mc, LocalPlayer p) {
        if (descendRelease > 0) {
            descendRelease--;
            setSneak(mc, p, false);
            descendLastY = p.getY();
            return;
        }
        if (!Double.isNaN(descendLastY) && descendLastY - p.getY() < 0.02) {
            if (++descendStall >= 10) {
                descendStall = 0;
                descendRelease = 2;
                com.xlrw.debug.XlrwDebug.logRateLimited("SNEAK_RETAP", 3000, "MOVE/SNEAK_RETAP",
                        "Đè Shift trên giàn giáo mà không xuống -> nhả ra rồi đè lại tại " + p.blockPosition().toShortString());
                setSneak(mc, p, false);
                descendLastY = p.getY();
                return;
            }
        } else {
            descendStall = 0;
        }
        descendLastY = p.getY();
        setSneak(mc, p, true);
    }

    private static void setSneak(Minecraft mc, LocalPlayer p, boolean sneak) {
        mc.options.keyShift.setDown(sneak);
        if (p.input != null) p.input.shiftKeyDown = sneak;
    }

    private static BlockPos findNearestDryLand(ClientLevel level, BlockPos start, int radius) {
        if (level == null || start == null) return null;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos check = start.offset(dx, dy, dz);
                    if (LocalPathfinder.isPassable(level, check)
                            && LocalPathfinder.isPassable(level, check.above())
                            && LocalPathfinder.isStandable(level, check.below())) {
                        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(check);
                        if (!s.is(Blocks.WATER) && s.getFluidState().isEmpty()) {
                            double d = start.distSqr(check);
                            if (d < bestDistSq) {
                                bestDistSq = d;
                                best = check;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}
