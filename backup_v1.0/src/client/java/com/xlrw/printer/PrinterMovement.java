package com.xlrw.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Baritone-inspired autonomous movement controller.
 * Manages path following, corner clearance, obstacle stepping, and active scaffolding usage.
 */
public class PrinterMovement {

    private static List<BlockPos> currentPath = null;
    private static int pathIndex = 0;
    private static int stuckTicks = 0;
    private static Vec3 lastPosition = Vec3.ZERO;
    private static BlockPos lastGoal = null;
    private static long lastPathCalcTime = 0;
    private static int jumpHoldTicks = 0;
    private static boolean isAscendingStep = false;
    private static double ascendTargetY = 0.0;
    private static int edgeBlockedTicks = 0;
    private static int unreachableGoalTicks = 0;

    /**
     * Autonomously navigates the player towards the target world position.
     * Uses 3D local A* pathfinding and active scaffolding when facing obstacles.
     * @return true if the player has arrived at the destination.
     */
    public static boolean moveTo(Minecraft mc, Vec3 targetPos, double stopDistance) {
        return moveTo(mc, targetPos, stopDistance, false, false);
    }

    public static boolean moveTo(Minecraft mc, Vec3 targetPos, double stopDistance, boolean keepMomentum, boolean isAimingAtBlock) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;

        // 1. If currently executing a multi-tick scaffold jump, let it run
        ScaffoldManager scaffoldManager = ScaffoldManager.getInstance();
        if (scaffoldManager.isPerformingScaffoldJump()) {
            boolean active = scaffoldManager.tickScaffoldJump(mc, player);
            if (active) return false;
        }

        BlockPos foot = player.blockPosition();

        // Water recovery & auto-swim ("né các vùng có nước ra tránh rơi xuống rồi lại không bơi lên lại")
        if (player.isInWater() || player.isUnderWater()) {
            mc.options.keyJump.setDown(true);
            if (player.input != null) player.input.jumping = true;
            mc.options.keySprint.setDown(true);
            player.setSprinting(true);

            BlockPos dryLand = findNearestDryLand(mc.level, foot, 8);
            if (dryLand != null) {
                targetPos = new Vec3(dryLand.getX() + 0.5, dryLand.getY(), dryLand.getZ() + 0.5);
            }
        }

        double dx = targetPos.x - player.getX();
        double dz = targetPos.z - player.getZ();
        double dy = targetPos.y - player.getY();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        BlockPos targetBlock = BlockPos.containing(targetPos);
        boolean sameBlock = foot.equals(targetBlock) || foot.below().equals(targetBlock);

        boolean onTopOfScaffold = mc.level.getBlockState(foot.below()).is(Blocks.SCAFFOLDING);
        boolean insideScaffold = mc.level.getBlockState(foot).is(Blocks.SCAFFOLDING);

        // Check if arrived within threshold (relaxed on scaffolding or same block to prevent edge oscillations)
        double effectiveStopDist = (onTopOfScaffold || insideScaffold) ? Math.max(stopDistance, 0.65) : stopDistance;
        if (sameBlock || (distXZ <= effectiveStopDist && Math.abs(dy) <= 1.8)) {
            if (!keepMomentum) {
                stop(mc);
                resetPath();
            }
            return true;
        }

        // Track horizontal movement progress to accurately detect being stuck against obstacles
        Vec3 curPos = player.position();
        double horizontalMoved = Math.hypot(curPos.x - lastPosition.x, curPos.z - lastPosition.z);
        lastPosition = curPos;

        BlockPos footPos = player.blockPosition();
        if (horizontalMoved < 0.04 && mc.options.keyUp.isDown()) {
            stuckTicks++;
            if (stuckTicks >= 6) {
                Vec3 fwd = player.getForward();
                Direction fDir = Direction.getNearest(fwd.x, 0, fwd.z);
                BlockPos obs = footPos.relative(fDir);
                BlockState obsState = mc.level.getBlockState(obs);
                String detail = String.format("Vật cản=(%d,%d,%d)[%s], Trên đầu=(%d,%d,%d)[%s], Dưới chân=(%d,%d,%d)[%s], Collision=%b",
                        obs.getX(), obs.getY(), obs.getZ(), obsState.getBlock().getName().getString(),
                        obs.getX(), obs.getY() + 1, obs.getZ(), mc.level.getBlockState(obs.above()).getBlock().getName().getString(),
                        footPos.getX(), footPos.getY() - 1, footPos.getZ(), mc.level.getBlockState(footPos.below()).getBlock().getName().getString(),
                        player.horizontalCollision);
                com.xlrw.debug.XlrwDebug.logMoveStuck(curPos, targetPos, stuckTicks, "keyUp=true", detail);
            }
            if (stuckTicks >= 16 && player.onGround()) {
                // Try an unstuck hop if head space is clear
                if (LocalPathfinder.isPassable(mc.level, footPos.above(2))) {
                    mc.options.keyJump.setDown(true);
                    if (player.input != null) player.input.jumping = true;
                }
            }
            if (stuckTicks >= 25) {
                // Stuck firmly for ~1.25s: fail current plan and re-route
                com.xlrw.debug.XlrwDebug.log("MOVE/STUCK_TIMEOUT", String.format("Kẹt quá 25 ticks tại (%d, %d, %d)! Huỷ trạm hiện tại và tìm trạm khác...", footPos.getX(), footPos.getY(), footPos.getZ()));
                com.xlrw.builder.StationPlanner.getInstance().failPlan(6000);
                com.xlrw.builder.StationPlanner.getInstance().markStationBad(targetBlock, 12000);
                resetPath();
                stop(mc);
                stuckTicks = 0;
                return false;
            }
        } else if (horizontalMoved > 0.08) {
            stuckTicks = Math.max(0, stuckTicks - 2);
        }

        BlockPos goalBlock = BlockPos.containing(targetPos);
        long now = System.currentTimeMillis();

        // 2. Path Calculation: compute path if goal changed, path is empty, or stuck
        boolean needNewPath = currentPath == null || lastGoal == null
                || !lastGoal.equals(goalBlock)
                || (stuckTicks > 6 && now - lastPathCalcTime > 600);

        if (needNewPath && now - lastPathCalcTime > 400) {
            lastPathCalcTime = now;
            lastGoal = goalBlock;
            List<BlockPos> path = LocalPathfinder.findPath(mc.level, footPos, goalBlock, 1200);
            if (!path.isEmpty()) {
                currentPath = path;
                pathIndex = 0;
            }
        }

        // 3. Path smoothing (string pulling): skip intermediate waypoints if line of sight is clear
        if (currentPath != null && pathIndex < currentPath.size()) {
            int maxLookahead = Math.min(currentPath.size() - 1, pathIndex + 3);
            for (int k = maxLookahead; k > pathIndex; k--) {
                BlockPos futureWp = currentPath.get(k);
                if (canWalkDirectly(mc.level, player.position(), futureWp)) {
                    pathIndex = k;
                    break;
                }
            }
        }

        Vec3 activeWaypoint = targetPos;
        if (currentPath != null && pathIndex < currentPath.size()) {
            BlockPos wp = currentPath.get(pathIndex);
            activeWaypoint = new Vec3(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);

            double wpDistXZ = Math.sqrt(Math.pow(player.getX() - activeWaypoint.x, 2) + Math.pow(player.getZ() - activeWaypoint.z, 2));
            double wpDistY = Math.abs(player.getY() - activeWaypoint.y);

            // Reached waypoint: advance to next without stopping
            if (wpDistXZ < 0.70 && wpDistY < 1.4) {
                pathIndex++;
                if (pathIndex >= currentPath.size()) {
                    currentPath = null;
                }
            }
        }

        // Safety: If no path exists and player cannot walk directly to goal across walls/void
        boolean directWalkable = canWalkDirectly(mc.level, player.position(), goalBlock);
        if ((currentPath == null || pathIndex >= currentPath.size())
                && footPos.distSqr(goalBlock) > 2.25
                && !directWalkable) {
            unreachableGoalTicks++;
            com.xlrw.debug.XlrwDebug.logRateLimited("UNREACHABLE_GOAL_" + goalBlock.asLong(), 1200, "MOVE/UNREACHABLE",
                    String.format("Không có đường đi & không thể bước trực tiếp tới Goal (%d, %d, %d)! Chân tại (%d, %d, %d). DirectWalkable=false",
                            goalBlock.getX(), goalBlock.getY(), goalBlock.getZ(),
                            footPos.getX(), footPos.getY(), footPos.getZ()));
            // Immediately stop keys to prevent pushing forward into walls or off drops
            stop(mc);
            if (unreachableGoalTicks >= 5) {
                // If trapped in a hole or pit, break out immediately!
                if (com.xlrw.builder.BuilderController.isTrappedInPit(mc.level, player)) {
                    com.xlrw.builder.BuilderController.getInstance().handlePitEscape(mc, player, com.xlrw.builder.BuilderController.getInstance().getSchematic());
                    unreachableGoalTicks = 0;
                    return false;
                }
                // Cannot find any path and cannot walk directly: switch direction / alternate station!
                com.xlrw.builder.StationPlanner.getInstance().failPlan(8000);
                com.xlrw.builder.StationPlanner.getInstance().markStationBad(targetBlock, 15000);
                resetPath();
                stop(mc);
                unreachableGoalTicks = 0;
                return false;
            }
            return false;
        } else {
            unreachableGoalTicks = 0;
        }

        // 4. Obstacle & Scaffolding check along travel vector
        Vec3 forward = player.getForward();
        Direction faceDir = Direction.getNearest(forward.x, 0, forward.z);
        BlockPos inFrontStep = footPos.relative(faceDir);

        boolean obstacleAhead = !LocalPathfinder.isPassable(mc.level, inFrontStep);
        boolean spaceAboveObstacle = LocalPathfinder.isPassable(mc.level, inFrontStep.above())
                && LocalPathfinder.isPassable(mc.level, inFrontStep.above(2));
        boolean twoBlockObstacle = obstacleAhead && !spaceAboveObstacle;
        boolean wallBlocked = twoBlockObstacle || (player.horizontalCollision && !spaceAboveObstacle);

        // 4. Obstacle check along travel vector
        boolean onScaffoldingStructure = insideScaffold || onTopOfScaffold;
        if (wallBlocked && !onScaffoldingStructure) {
            com.xlrw.debug.XlrwDebug.logMoveBlocked(player.position(), inFrontStep, mc.level.getBlockState(inFrontStep),
                    String.format("Tường 2 block hoặc đâm sầm vào vật cản! horizontalCollision=%b, obstacleAhead=%b",
                            player.horizontalCollision, obstacleAhead));
            List<BlockPos> detourPath = LocalPathfinder.findPath(mc.level, footPos, goalBlock, 1200);
            if (detourPath != null && !detourPath.isEmpty()) {
                // Detour exists! Follow path around obstacle smoothly without placing scaffolding
                currentPath = detourPath;
                pathIndex = 0;
                stuckTicks = 0;
                BlockPos wp = currentPath.get(0);
                activeWaypoint = new Vec3(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
            } else {
                // Impassable wall with no detour: halt immediately, never push into wall or snap camera
                stop(mc);
                stuckTicks++;
                if (stuckTicks >= 6) {
                    com.xlrw.builder.StationPlanner.getInstance().failPlan(8000);
                    com.xlrw.builder.StationPlanner.getInstance().markStationBad(targetBlock, 15000);
                    resetPath();
                    stuckTicks = 0;
                    return false;
                }
            }
        } else if (stuckTicks > 10) {
            // Stuck: refresh pathfinder
            currentPath = null;
            lastPathCalcTime = 0;
            stuckTicks = 0;
        }

        // 5. Scaffolding presence check (inside the scaffolding mesh)
        boolean inScaffolding = mc.level.getBlockState(footPos).is(Blocks.SCAFFOLDING);

        // 6. Steering & Movement - Decoupled Strafe Movement when aiming at blocks!
        double toWpX = activeWaypoint.x - player.getX();
        double toWpZ = activeWaypoint.z - player.getZ();
        float currentYaw = player.getYRot();
        BlockPos wpBlock = BlockPos.containing(activeWaypoint);
        boolean atWpBlock = footPos.equals(wpBlock) || footPos.below().equals(wpBlock);

        // Practically standing on the waypoint or on the same block: never turn camera or walk (prevents spinning)
        boolean onWaypoint = atWpBlock || Math.hypot(toWpX, toWpZ) < (onTopOfScaffold ? 0.60 : 0.38);
        float targetYaw = onWaypoint ? currentYaw : (float) (Math.toDegrees(Math.atan2(toWpZ, toWpX)) - 90.0);
        float diffYaw = onWaypoint ? 0.0f : Mth.wrapDegrees(targetYaw - currentYaw);


        if (isAimingAtBlock) {
            // Decoupled Strafe Movement: Head angle is controlled by HumanAim,
            // while WASD keys dynamically project momentum towards waypoint!
            double rad = Math.toRadians(diffYaw);
            double forwardImpulse = onWaypoint ? 0.0 : Math.cos(rad);
            double strafeImpulse = onWaypoint ? 0.0 : -Math.sin(rad); // negative = right, positive = left

            mc.options.keyUp.setDown(forwardImpulse > 0.25);
            mc.options.keyDown.setDown(forwardImpulse < -0.25);
            mc.options.keyLeft.setDown(strafeImpulse > 0.25);
            mc.options.keyRight.setDown(strafeImpulse < -0.25);

            if (player.input != null) {
                player.input.up = forwardImpulse > 0.25;
                player.input.down = forwardImpulse < -0.25;
                player.input.left = strafeImpulse > 0.25;
                player.input.right = strafeImpulse < -0.25;
                player.input.forwardImpulse = (float) forwardImpulse;
                player.input.leftImpulse = (float) strafeImpulse;
            }

            boolean canSprint = !player.isShiftKeyDown()
                    && !inScaffolding
                    && forwardImpulse > 0.35
                    && (player.getFoodData() == null || player.getFoodData().getFoodLevel() > 6);
            if (canSprint) {
                mc.options.keySprint.setDown(true);
                player.setSprinting(true);
            } else {
                mc.options.keySprint.setDown(false);
            }
        } else {
            // Free Navigation: Camera smoothly tracks waypoint with natural exponential decay turning
            if (!onWaypoint) {
                float smoothStep = diffYaw * 0.40f;
                float clampedStep = Mth.clamp(smoothStep, -28.0f, 28.0f);
                if (Math.abs(clampedStep) < 1.5f && Math.abs(diffYaw) > 0.1f) {
                    clampedStep = Math.signum(diffYaw) * Math.min(Math.abs(diffYaw), 1.5f);
                }
                player.setYRot(currentYaw + clampedStep);
            }

            // Walk / Sprint forward when generally facing waypoint (or during ascent lock)
            boolean facingTarget = Math.abs(diffYaw) < 65.0f && !onWaypoint;
            double wpDiffY = activeWaypoint.y - player.getY();
            double distToWpHoriz = Math.hypot(toWpX, toWpZ);
            // Only lock horizontal keys when strictly ascending vertically straight up inside a vertical shaft!
            boolean verticalClimbShaft = inScaffolding && wpDiffY > 0.3 && distToWpHoriz < 0.35 && mc.level.getBlockState(footPos.below()).is(Blocks.SCAFFOLDING);

            if (verticalClimbShaft) {
                // In mid-air inside a vertical scaffold shaft: lock horizontal keys and keep centered
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(false);
                mc.options.keyLeft.setDown(false);
                mc.options.keyRight.setDown(false);
                mc.options.keySprint.setDown(false);
                if (player.input != null) {
                    player.input.up = false;
                    player.input.down = false;
                    player.input.left = false;
                    player.input.right = false;
                    player.input.forwardImpulse = 0.0f;
                    player.input.leftImpulse = 0.0f;
                }
                double cx = footPos.getX() + 0.5;
                double cz = footPos.getZ() + 0.5;
                Vec3 vel = player.getDeltaMovement();
                player.setDeltaMovement(Mth.clamp((cx - player.getX()) * 0.35, -0.15, 0.15), vel.y, Mth.clamp((cz - player.getZ()) * 0.35, -0.15, 0.15));
            } else if ((facingTarget || isAscendingStep) && !onWaypoint) {
                mc.options.keyUp.setDown(true);
                mc.options.keyDown.setDown(false);
                mc.options.keyLeft.setDown(false);
                mc.options.keyRight.setDown(false);
                if (player.input != null) {
                    player.input.up = true;
                    player.input.down = false;
                    player.input.left = false;
                    player.input.right = false;
                    player.input.forwardImpulse = 1.0f;
                    player.input.leftImpulse = 0.0f;
                }

                boolean canSprint = !player.isShiftKeyDown()
                        && !inScaffolding
                        && (player.getFoodData() == null || player.getFoodData().getFoodLevel() > 6);
                if (canSprint) {
                    mc.options.keySprint.setDown(true);
                    player.setSprinting(true);
                } else {
                    mc.options.keySprint.setDown(false);
                }
            } else {
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
                if (player.input != null) {
                    player.input.up = false;
                    player.input.forwardImpulse = 0.0f;
                }
            }
        }

        // 7. Jump over 1-block steps (Baritone MovementAscend style) - only if head space is clear!
        double wpDiffY = activeWaypoint.y - player.getY();
        boolean isOneBlockStep = obstacleAhead && spaceAboveObstacle;
        onScaffoldingStructure = insideScaffold || onTopOfScaffold;
        boolean shouldJump = !onScaffoldingStructure && (wpDiffY > 0.35 || isOneBlockStep) && spaceAboveObstacle && player.onGround();

        if (shouldJump && !isAscendingStep) {
            isAscendingStep = true;
            ascendTargetY = Math.max(activeWaypoint.y, player.getY() + 1.0);
            jumpHoldTicks = 8;
        }

        if (isAscendingStep) {
            if (player.getY() >= ascendTargetY - 0.1 && player.onGround()) {
                isAscendingStep = false;
                jumpHoldTicks = 0;
            } else if (jumpHoldTicks > 0) {
                jumpHoldTicks--;
                mc.options.keyJump.setDown(true);
                if (player.input != null) player.input.jumping = true;
            } else {
                mc.options.keyJump.setDown(false);
                if (player.input != null) player.input.jumping = false;
                if (player.onGround() && player.getY() < ascendTargetY - 0.4) {
                    isAscendingStep = false;
                }
            }
            // Continuous forward propulsion throughout the entire jump so player lands safely on top of step
            mc.options.keyUp.setDown(true);
            if (player.input != null) {
                player.input.up = true;
                player.input.forwardImpulse = 1.0f;
            }
        } else if (jumpHoldTicks <= 0) {
            mc.options.keyJump.setDown(false);
            if (player.input != null) player.input.jumping = false;
        }

        // 8. Scaffolding climbing & elevator vertical movement
        insideScaffold = mc.level.getBlockState(footPos).is(Blocks.SCAFFOLDING);
        onTopOfScaffold = mc.level.getBlockState(footPos.below()).is(Blocks.SCAFFOLDING);
        boolean onScaffold = insideScaffold || onTopOfScaffold;

        if (onScaffold) {
            // User requirement: Disable holding shift completely when on or in scaffolding!
            mc.options.keyShift.setDown(false);
            if (player.input != null) player.input.shiftKeyDown = false;
        }

        if (insideScaffold) {
            mc.options.keyShift.setDown(false);
            if (player.input != null) player.input.shiftKeyDown = false;

            boolean hasScaffoldAbove = mc.level.getBlockState(footPos.above()).is(Blocks.SCAFFOLDING);
            if (hasScaffoldAbove && dy > 0.3) {
                // Climbing up through scaffold column
                mc.options.keyJump.setDown(true);
                if (player.input != null) player.input.jumping = true;
            } else {
                // Pop up onto the top surface of the scaffolding or step out onto an adjacent block
                double platformY = footPos.getY() + 1.0;
                if (player.getY() < platformY + 0.12) {
                    // Hold Space continuously to ascend and clear the scaffolding rim
                    mc.options.keyJump.setDown(true);
                    if (player.input != null) player.input.jumping = true;
                    if (!onWaypoint) {
                        mc.options.keyUp.setDown(true);
                        if (player.input != null) {
                            player.input.up = true;
                            player.input.forwardImpulse = 1.0f;
                        }
                    }
                } else {
                    if (player.onGround() || player.getDeltaMovement().y <= 0) {
                        mc.options.keyJump.setDown(false);
                        if (player.input != null) player.input.jumping = false;
                    }
                }
            }
        } else {
            // 8. Height Sensing & Edge Fall Prevention ("Cảm ứng độ cao")
            // Checks the terrain ahead in the exact direction of movement.
            // If the next step is an open drop (> 2 blocks of air), prevent walking off,
            // check for adjacent safe solid stepping stones, or deploy horizontal scaffolding bridging.
            double stepDist = 0.45;
            double moveDirX = toWpX != 0 ? (toWpX / Math.hypot(toWpX, toWpZ)) : forward.x;
            double moveDirZ = toWpZ != 0 ? (toWpZ / Math.hypot(toWpX, toWpZ)) : forward.z;

            BlockPos stepPos = BlockPos.containing(player.getX() + moveDirX * stepDist, player.getY(), player.getZ() + moveDirZ * stepDist);
            boolean steppingOffCurrent = !stepPos.equals(footPos);

            boolean stepHasLanding = false;
            if (!steppingOffCurrent) {
                stepHasLanding = true;
            } else {
                // Check if stepPos or any level within 2 blocks down has standable ground
                boolean stepDirect = LocalPathfinder.isStandable(mc.level, stepPos);
                boolean stepBelow1 = LocalPathfinder.isStandable(mc.level, stepPos.below());
                boolean stepBelow2 = LocalPathfinder.isStandable(mc.level, stepPos.below(2));
                boolean intentionalDrop = currentPath != null && pathIndex < currentPath.size()
                        && currentPath.get(pathIndex).getY() <= player.getBlockY() - 2
                        && LocalPathfinder.isStandable(mc.level, stepPos.below(3));

                stepHasLanding = stepDirect || stepBelow1 || stepBelow2 || intentionalDrop;
            }

            if (!stepHasLanding && player.onGround()) {
                // Void / Drop ahead! Halt lateral propulsion keys immediately
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(false);
                mc.options.keyLeft.setDown(false);
                mc.options.keyRight.setDown(false);
                mc.options.keySprint.setDown(false);

                // Zero out horizontal velocity immediately to prevent sliding off the ledge into the abyss
                Vec3 curVel = player.getDeltaMovement();
                player.setDeltaMovement(0, curVel.y, 0);

                if (player.input != null) {
                    player.input.up = false;
                    player.input.down = false;
                    player.input.left = false;
                    player.input.right = false;
                    player.input.forwardImpulse = 0.0f;
                    player.input.leftImpulse = 0.0f;
                }

                // 1. Can we parkour / jump across this 1-block gap to a safe landing on the other side?
                Direction jumpDir = Direction.getNearest(moveDirX, 0, moveDirZ);
                BlockPos gapLanding = stepPos.relative(jumpDir);
                boolean canParkourGap = (LocalPathfinder.isStandable(mc.level, gapLanding.below())
                        || LocalPathfinder.isStandable(mc.level, gapLanding.below(2)))
                        && LocalPathfinder.isPassable(mc.level, gapLanding)
                        && LocalPathfinder.isPassable(mc.level, gapLanding.above())
                        && LocalPathfinder.isPassable(mc.level, stepPos.above());

                String landingInfo = String.format("stepPos=%s (air=%b), stepBelow1=%s, stepBelow2=%s",
                        stepPos.toShortString(), mc.level.getBlockState(stepPos).isAir(),
                        mc.level.getBlockState(stepPos.below()).getBlock().getName().getString(),
                        mc.level.getBlockState(stepPos.below(2)).getBlock().getName().getString());
                com.xlrw.debug.XlrwDebug.logEdgeStop(player.position(), footPos, stepPos, jumpDir, landingInfo,
                        canParkourGap ? "Nhảy Parkour sang bên kia" : (onScaffold ? "Bắc cầu Scaffolding ngang" : "Chặn đứng mép vực"));

                if (canParkourGap && (currentPath != null || footPos.distSqr(goalBlock) <= 9)) {
                    com.xlrw.debug.XlrwDebug.logParkourJump(player.position(), stepPos, gapLanding, "Chỗ đáp vững chắc tại " + gapLanding.toShortString());
                    // Safe parkour landing found across the gap! Leap forward!
                    isAscendingStep = true;
                    ascendTargetY = gapLanding.getY();
                    jumpHoldTicks = 9;
                    mc.options.keyJump.setDown(true);
                    mc.options.keyUp.setDown(true);
                    mc.options.keySprint.setDown(true);
                    if (player.input != null) {
                        player.input.jumping = true;
                        player.input.up = true;
                        player.input.forwardImpulse = 1.0f;
                    }
                    edgeBlockedTicks = 0;
                    return false;
                }

                // 2. Look for an adjacent safe solid block that allows stepping towards the build/goal
                BlockPos safeAdjacent = findSafeAdjacentStep(mc.level, footPos, activeWaypoint);
                if (safeAdjacent != null) {
                    // Safe stepping stone found! Steer towards it
                    activeWaypoint = new Vec3(safeAdjacent.getX() + 0.5, safeAdjacent.getY(), safeAdjacent.getZ() + 0.5);
                    edgeBlockedTicks = 0;
                } else {
                    // 3. Baritone-style Sneak Bridging:
                    // Engages sneak (shift) to lock player safely to the ledge, tilts camera downward,
                    // and clicks the side face of the supporting block below feet to bridge across the void!
                    Direction bridgeDir = Direction.getNearest(toWpX, 0, toWpZ);
                    boolean bridgingActive = false;

                    // Only bridge if this is a short 1-2 block gap with a solid landing across,
                    // or if the goal itself is in immediate reaching distance!
                    BlockPos targetGap = footPos.relative(bridgeDir);
                    BlockPos landingAcross = targetGap.relative(bridgeDir);
                    boolean hasSolidLandingAcross = LocalPathfinder.isStandable(mc.level, landingAcross.below())
                            || footPos.distSqr(goalBlock) <= 6.25;

                    if (hasSolidLandingAcross && bridgeDir.getAxis() != Direction.Axis.Y) {
                        bridgingActive = SneakBridgeHelper.trySneakBridge(mc, player, mc.level, footPos, bridgeDir);
                    }

                    if (bridgingActive) {
                        edgeBlockedTicks = 0; // active progress being made!
                        return false;
                    } else {
                        mc.options.keyShift.setDown(false);
                        if (player.input != null) player.input.shiftKeyDown = false;
                        edgeBlockedTicks++;
                    }
                }

                if (edgeBlockedTicks >= 20) {
                    com.xlrw.debug.XlrwDebug.log("MOVE/EDGE_TIMEOUT", String.format("Bị kẹt tại mép vực (%d, %d, %d) quá 20 ticks! Fail plan và tìm trạm khác...", footPos.getX(), footPos.getY(), footPos.getZ()));
                    // Blocked at edge after ~1s without bridging ability: try another station / route
                    com.xlrw.builder.StationPlanner.getInstance().failPlan(8000);
                    com.xlrw.builder.StationPlanner.getInstance().markStationBad(targetBlock, 15000);
                    resetPath();
                    stop(mc);
                    edgeBlockedTicks = 0;
                    return false;
                }
            } else {
                edgeBlockedTicks = 0;
                mc.options.keyShift.setDown(false);
                if (player.input != null) player.input.shiftKeyDown = false;
            }
        }

        return false;
    }

    private static boolean canFindGroundDetour(net.minecraft.client.multiplayer.ClientLevel level, BlockPos start, BlockPos goal) {
        if (level == null || start == null || goal == null) return false;
        List<BlockPos> testPath = LocalPathfinder.findPath(level, start, goal, 600);
        return testPath != null && !testPath.isEmpty() && testPath.size() <= 16;
    }

    public static boolean canWalkDirectly(net.minecraft.client.multiplayer.ClientLevel level, Vec3 from, BlockPos to) {
        if (level == null || to == null) return false;
        double dy = Math.abs(from.y - to.getY());
        if (dy > 0.6) return false;

        double targetX = to.getX() + 0.5;
        double targetZ = to.getZ() + 0.5;
        double dx = targetX - from.x;
        double dz = targetZ - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.3) return true;

        int steps = Math.max(2, (int) Math.ceil(dist / 0.5));
        for (int i = 1; i <= steps; i++) {
            double fraction = (double) i / steps;
            double sx = from.x + dx * fraction;
            double sz = from.z + dz * fraction;
            BlockPos samplePos = BlockPos.containing(sx, from.y, sz);

            // Check head & feet clearance
            if (!LocalPathfinder.isPassable(level, samplePos) || !LocalPathfinder.isPassable(level, samplePos.above())) {
                return false;
            }
            // Check standable ground
            if (!LocalPathfinder.isStandable(level, samplePos.below())) {
                return false;
            }
        }
        return true;
    }

    public static void resetPath() {
        currentPath = null;
        pathIndex = 0;
        stuckTicks = 0;
        jumpHoldTicks = 0;
        isAscendingStep = false;
        ascendTargetY = 0.0;
        lastGoal = null;
        edgeBlockedTicks = 0;
        unreachableGoalTicks = 0;
        ScaffoldManager.getInstance().cancelJump();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.keySprint.setDown(false);
        if (mc != null && mc.player != null) mc.player.setSprinting(false);
    }

    public static void stop(Minecraft mc) {
        jumpHoldTicks = 0;
        isAscendingStep = false;
        ascendTargetY = 0.0;
        edgeBlockedTicks = 0;
        unreachableGoalTicks = 0;
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
        }
    }

    /**
     * Looks for an adjacent safe solid stepping stone around footPos (dx, dz in {-1, 0, 1})
     * that has clear headroom, standable ground (not an air drop), and moves closer to target.
     */
    private static BlockPos findSafeAdjacentStep(net.minecraft.client.multiplayer.ClientLevel level, BlockPos footPos, Vec3 target) {
        if (level == null || footPos == null || target == null) return null;

        BlockPos best = null;
        double currentDistSq = footPos.distToCenterSqr(target);
        double bestDistSq = currentDistSq;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;

                // Check same level and 1 block down
                for (int dy = 0; dy >= -1; dy--) {
                    BlockPos cand = footPos.offset(dx, dy, dz);

                    // Headroom & passable feet
                    if (!LocalPathfinder.isPassable(level, cand) || !LocalPathfinder.isPassable(level, cand.above())) {
                        continue;
                    }

                    // Must have standable floor (not air drop)
                    if (!LocalPathfinder.isStandable(level, cand.below()) && !LocalPathfinder.isStandable(level, cand)) {
                        continue;
                    }

                    double dSq = cand.distToCenterSqr(target);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = cand;
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos findNearestDryLand(net.minecraft.client.multiplayer.ClientLevel level, BlockPos start, int radius) {
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
