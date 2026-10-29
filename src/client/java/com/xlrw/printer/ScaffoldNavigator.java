package com.xlrw.printer;

import com.xlrw.builder.BuildRules;
import com.xlrw.builder.InventoryHelper;
import com.xlrw.builder.PlacementFinder;
import com.xlrw.debug.XlrwDebug;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla-correct scaffolding locomotion.
 *
 * Mechanics this class relies on (Minecraft 1.21):
 *  - Scaffolding is climbable: while the player's FEET are inside a scaffolding block, holding JUMP rises at
 *    ~0.2 b/tick, nothing makes the player slide down, SNEAK holds position.
 *  - Once the feet are above the block, its top becomes solid (stable shape) so the player simply lands on it.
 *    Holding JUMP while standing on it would make a normal hop, which is what threw the old bot off the tower.
 *  - Right-click on the TOP face of a scaffolding block (no sneak) extends the row horizontally in the direction
 *    the player is FACING, at the same Y as the clicked block. Right-click on a SIDE face stacks upward instead.
 *  - A scaffolding block only survives while its "distance" (horizontal hops from a block resting on a sturdy
 *    floor) is below 7, so a bridge can only be extended from scaffolding, at most 6 blocks out.
 */
public final class ScaffoldNavigator {

    public enum BridgeResult { WORKING, ARRIVED, BLOCKED }

    private enum StepKind { NONE, WALK, PLACE }

    private static long lastPlaceMs = 0;
    private static BlockPos lastFloor = null;
    private static int sameFloorTicks = 0;
    private static long blockedUntil = 0;

    private ScaffoldNavigator() {}

    public static void reset() {
        lastPlaceMs = 0;
        lastFloor = null;
        sameFloorTicks = 0;
        blockedUntil = 0;
    }

    // ------------------------------------------------------------------------------------------------
    // Input helpers
    // ------------------------------------------------------------------------------------------------

    public static void releaseKeys(Minecraft mc, LocalPlayer p) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
        if (p.input != null) {
            p.input.up = false;
            p.input.down = false;
            p.input.left = false;
            p.input.right = false;
            p.input.jumping = false;
            p.input.shiftKeyDown = false;
            p.input.forwardImpulse = 0.0f;
            p.input.leftImpulse = 0.0f;
        }
        p.setSprinting(false);
    }

    /** Gently pushes the player toward (tx, tz) without touching the camera, never sprinting or sneaking. */
    private static void steer(Minecraft mc, LocalPlayer p, double tx, double tz, double dead) {
        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        double rad = Math.toRadians(p.getYRot());
        double fwd = dx * -Math.sin(rad) + dz * Math.cos(rad);
        double left = dx * Math.cos(rad) + dz * Math.sin(rad);

        boolean up = fwd > dead;
        boolean down = fwd < -dead;
        boolean l = left > dead;
        boolean r = left < -dead;

        mc.options.keyUp.setDown(up);
        mc.options.keyDown.setDown(down);
        mc.options.keyLeft.setDown(l);
        mc.options.keyRight.setDown(r);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        if (p.input != null) {
            p.input.up = up;
            p.input.down = down;
            p.input.left = l;
            p.input.right = r;
            p.input.shiftKeyDown = false;
            p.input.forwardImpulse = up ? 1.0f : (down ? -1.0f : 0.0f);
            p.input.leftImpulse = l ? 1.0f : (r ? -1.0f : 0.0f);
        }
        p.setSprinting(false);
    }

    private static void setJump(Minecraft mc, LocalPlayer p, boolean jump) {
        mc.options.keyJump.setDown(jump);
        if (p.input != null) p.input.jumping = jump;
    }

    // ------------------------------------------------------------------------------------------------
    // Vertical: climb a finished column
    // ------------------------------------------------------------------------------------------------

    /**
     * Climbs the column at {@code base} whose highest scaffolding block is at {@code topY}.
     * Ensures calm, steady vertical climbing: centers inside column, suppresses all horizontal
     * movement keys to prevent slipping out, holds jump until arriving on top platform.
     *
     * @param desiredFeetY the feet level the caller wants to end up standing at
     * @return true once the player is standing stably at (or above) desiredFeetY on solid footing
     */
    public static boolean climb(Minecraft mc, LocalPlayer p, BlockPos base, int topY, int desiredFeetY) {
        ClientLevel level = mc.level;
        if (level == null) return false;

        double cx = base.getX() + 0.5;
        double cz = base.getZ() + 0.5;
        double offset = Math.hypot(p.getX() - cx, p.getZ() - cz);
        BlockPos feet = p.blockPosition();
        boolean inColumn = feet.getX() == base.getX() && feet.getZ() == base.getZ();

        // Arrival: standing on the column top at (or above) the wanted height
        if (inColumn && p.getY() >= desiredFeetY - 0.05 && p.onGround()) {
            XlrwDebug.logTowerAction("ARRIVED_TARGET", base, "Standing at Y=" + String.format("%.2f", p.getY()) + " >= target " + desiredFeetY);
            releaseKeys(mc, p);
            return true;
        }

        // 1. Not inside the column footprint: walk into the base cell (or wait while falling)
        if (!inColumn) {
            setJump(mc, p, false);
            if (!p.onGround()) {
                releaseKeys(mc, p);
                return false;
            }
            XlrwDebug.logTowerAction("APPROACHING_BASE", base, "Walking into column (dist=" + String.format("%.2f", offset) + ")");
            PrinterMovement.moveTo(mc, new Vec3(cx, base.getY(), cz), 0.15, false, false);
            return false;
        }

        // Inside the column (especially in mid-air): Lock horizontal keys completely!
        // Suppress all WASD keys so the player NEVER walks out or drifts out of the scaffolding column!
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyShift.setDown(false);
        if (p.input != null) {
            p.input.up = false;
            p.input.down = false;
            p.input.left = false;
            p.input.right = false;
            p.input.shiftKeyDown = false;
            p.input.forwardImpulse = 0.0f;
            p.input.leftImpulse = 0.0f;
        }

        // Gently damp and center using velocity without teleporting/setPos or pressing WASD
        Vec3 v = p.getDeltaMovement();
        double pullX = Mth.clamp((cx - p.getX()) * 0.35, -0.15, 0.15);
        double pullZ = Mth.clamp((cz - p.getZ()) * 0.35, -0.15, 0.15);
        p.setDeltaMovement(pullX, v.y, pullZ);

        // Ascend: hold JUMP only while the feet are still inside scaffolding below the wanted height
        int targetPlatformY = Math.min(topY + 1, desiredFeetY);
        boolean feetInScaffold = level.getBlockState(feet).is(Blocks.SCAFFOLDING);

        if (p.getY() < targetPlatformY - 0.05) {
            XlrwDebug.logRateLimited("CLIMB_" + base.asLong(), 1000, "TOWER/ASCENDING",
                    "Trụ " + base.toShortString() + " Y=" + String.format("%.2f", p.getY()) + " -> " + targetPlatformY);
            setJump(mc, p, feetInScaffold || p.onGround());
            return false;
        }
        // At the top: let go of JUMP (holding it on the top surface would make a normal hop) and settle
        setJump(mc, p, false);
        releaseKeys(mc, p);
        if (topY + 1 < desiredFeetY) {
            XlrwDebug.logRateLimited("CLIMB_SHORT_" + base.asLong(), 2000, "TOWER/TOO_SHORT",
                    "Trụ " + base.toShortString() + " chỉ cao tới " + topY + ", cần đứng ở Y=" + desiredFeetY);
        }
        return p.onGround() && p.getY() >= desiredFeetY - 0.05;
    }

    // ------------------------------------------------------------------------------------------------
    // Horizontal: build a scaffolding walkway toward a target
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds and walks a scaffolding bridge at the player's current level toward {@code target}.
     * Must start from a scaffolding block (e.g. the tower top) because horizontal scaffolding needs a scaffolding
     * neighbour for support.
     */
    public static BridgeResult bridge(Minecraft mc, LocalPlayer p, Schematic schematic, BlockPos target) {
        ClientLevel level = mc.level;
        if (level == null || mc.gameMode == null || target == null) return BridgeResult.BLOCKED;

        long now = System.currentTimeMillis();
        if (now < blockedUntil) return BridgeResult.BLOCKED;

        // Guard 1: Never bridge horizontally if player is climbing inside a vertical column!
        boolean feetInScaffold = level.getBlockState(p.blockPosition()).is(Blocks.SCAFFOLDING);
        boolean aboveInScaffold = level.getBlockState(p.blockPosition().above()).is(Blocks.SCAFFOLDING);
        if (feetInScaffold && aboveInScaffold) {
            XlrwDebug.logScaffoldBridge("ABORT_INSIDE_SHAFT", p.blockPosition(), target, "Player is climbing inside column, cannot bridge horizontally");
            return BridgeResult.BLOCKED;
        }

        // Guard 2: Never bridge horizontally if target is higher than player! Horizontal bridges cannot ascend.
        if (target != null && target.getY() > p.getY() + 1.0) {
            XlrwDebug.logScaffoldBridge("ABORT_BELOW_TARGET", p.blockPosition(), target, "Player Y=" + String.format("%.1f", p.getY()) + " is below target Y=" + target.getY());
            return BridgeResult.BLOCKED;
        }

        double dxT = target.getX() + 0.5 - p.getX();
        double dzT = target.getZ() + 0.5 - p.getZ();
        if (Math.hypot(dxT, dzT) <= 1.2 || (Math.hypot(dxT, dzT) <= 2.6 && PlacementFinder.findBestCandidate() != null)) {
            XlrwDebug.logScaffoldBridge("ARRIVED", p.blockPosition(), target, "In reach of target (dist=" + String.format("%.2f", Math.hypot(dxT, dzT)) + ")");
            releaseKeys(mc, p);
            sameFloorTicks = 0;
            return BridgeResult.ARRIVED;
        }

        if (!p.onGround()) {
            releaseKeys(mc, p);
            return BridgeResult.WORKING;
        }

        BlockPos feet = p.blockPosition();
        BlockPos below = feet.below();
        BlockPos floor;
        if (level.getBlockState(below).is(Blocks.SCAFFOLDING)) {
            floor = below;
        } else if (level.getBlockState(feet).is(Blocks.SCAFFOLDING) && !level.getBlockState(feet.above()).is(Blocks.SCAFFOLDING) && p.getY() >= feet.getY() + 0.8) {
            floor = feet;
        } else {
            XlrwDebug.logScaffoldBridge("ABORT_NOT_SCAFFOLD", feet, target, "Not standing stably on scaffolding platform");
            return BridgeResult.BLOCKED;
        }

        // Stall detection: only triggers if player stays on the exact same floor without placing or walking
        if (floor.equals(lastFloor)) {
            if (++sameFloorTicks > 80) {
                sameFloorTicks = 0;
                blockedUntil = now + 4_000;
                XlrwDebug.logScaffoldBridge("BRIDGE_STALL", floor, target, "Stuck on floor for >80 ticks, cooling down for 4s");
                releaseKeys(mc, p);
                return BridgeResult.BLOCKED;
            }
        } else {
            lastFloor = floor.immutable();
            sameFloorTicks = 0;
        }

        Direction primary;
        Direction secondary;
        if (Math.abs(dxT) >= Math.abs(dzT)) {
            primary = dxT > 0 ? Direction.EAST : Direction.WEST;
            secondary = dzT > 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            primary = dzT > 0 ? Direction.SOUTH : Direction.NORTH;
            secondary = dxT > 0 ? Direction.EAST : Direction.WEST;
        }

        for (Direction dir : new Direction[]{primary, secondary}) {
            double deltaAxis = (dir.getAxis() == Direction.Axis.X) ? Math.abs(dxT) : Math.abs(dzT);
            int neededSteps = Math.max(1, (int) Math.round(deltaAxis));

            StepKind kind = evaluate(level, schematic, floor, dir, neededSteps);
            if (kind == StepKind.PLACE) {
                sameFloorTicks = 0; // placing scaffolding is active progress
                return placeNext(mc, p, level, floor, dir, now);
            }
            if (kind == StepKind.WALK) {
                BlockPos ahead = floor.relative(dir);
                // Safety check: ensure ahead block is actually placed and standable before walking forward!
                if (!LocalPathfinder.isStandable(level, ahead) && !LocalPathfinder.isStandable(level, ahead.below())) {
                    return BridgeResult.WORKING;
                }
                setJump(mc, p, false);
                steer(mc, p, ahead.getX() + 0.5, ahead.getZ() + 0.5, 0.08);
                return BridgeResult.WORKING;
            }
        }

        releaseKeys(mc, p);
        return BridgeResult.BLOCKED;
    }

    private static StepKind evaluate(ClientLevel level, Schematic schematic, BlockPos floor, Direction dir, int neededSteps) {
        // Count how many scaffolding blocks already exist continuously ahead along dir
        int existingCount = 0;
        BlockPos check = floor.relative(dir);
        while (existingCount < 6 && level.getBlockState(check).is(Blocks.SCAFFOLDING)) {
            existingCount++;
            check = check.relative(dir);
        }

        // If we haven't reached neededSteps yet, extend the scaffolding row further before walking
        if (existingCount < neededSteps && existingCount < 5) {
            BlockPos nextPos = check;

            // Two blocks of headroom above nextPos
            if (LocalPathfinder.isPassable(level, nextPos.above()) && LocalPathfinder.isPassable(level, nextPos.above(2))) {
                BlockState state = level.getBlockState(nextPos);
                boolean canPlaceAir = (state.isAir() || state.canBeReplaced()) && !state.is(Blocks.LAVA);

                // Never plant walkway inside a schematic block
                boolean schematicConflict = false;
                if (schematic != null) {
                    BlockState expected = schematic.getBlock(nextPos);
                    if (expected != null && !expected.isAir() && !BuildRules.isSkipped(expected)) {
                        schematicConflict = true;
                    }
                }

                // Check stability distance (must be < 7 to not collapse)
                boolean stable = ScaffoldingBlock.getDistance(level, nextPos) < 7;

                if (canPlaceAir && !schematicConflict && stable) {
                    return StepKind.PLACE;
                } else if (!stable) {
                    XlrwDebug.logScaffoldBridge("EVAL_MAX_DISTANCE", floor, nextPos, "Stability distance >= 7");
                } else if (schematicConflict) {
                    XlrwDebug.logScaffoldBridge("EVAL_SCHEMATIC_CONFLICT", floor, nextPos, "dir=" + dir);
                }
            } else {
                XlrwDebug.logScaffoldBridge("EVAL_NO_HEADROOM", floor, nextPos, "dir=" + dir);
            }
        }

        // If the path ahead already has scaffolding, we can safely walk forward along it!
        if (existingCount > 0) {
            BlockPos ahead = floor.relative(dir);
            if (LocalPathfinder.isPassable(level, ahead.above()) && LocalPathfinder.isPassable(level, ahead.above(2))) {
                return StepKind.WALK;
            }
        }

        return StepKind.NONE;
    }

    private static BridgeResult placeNext(Minecraft mc, LocalPlayer p, ClientLevel level, BlockPos floor,
                                          Direction dir, long now) {
        double fx = floor.getX() + 0.5;
        double fz = floor.getZ() + 0.5;

        // Stand reasonably centered on current block so the new block is placed straight ahead
        if (Math.hypot(p.getX() - fx, p.getZ() - fz) > 0.36) {
            setJump(mc, p, false);
            steer(mc, p, fx, fz, 0.06);
            return BridgeResult.WORKING;
        }
        releaseKeys(mc, p);

        if (now - lastPlaceMs < 160) return BridgeResult.WORKING;

        InventoryHelper.equipItem(p, Items.SCAFFOLDING);
        if (!p.getMainHandItem().is(Items.SCAFFOLDING)) {
            XlrwDebug.logScaffoldBridge("NO_SCAFFOLD_ITEM", floor, floor.relative(dir), "Scaffolding missing in hand");
            return BridgeResult.WORKING;
        }

        // Set player facing direction so client getDirection() matches dir!
        float yaw = dir.toYRot();
        float pitch = 55.0f;
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, p.onGround()));

        // Ensure sneak is OFF
        mc.options.keyShift.setDown(false);
        if (p.input != null) p.input.shiftKeyDown = false;

        Direction face = Direction.UP;
        Vec3 hit = new Vec3(fx + dir.getStepX() * 0.3, floor.getY() + 1.0, fz + dir.getStepZ() * 0.3);

        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, floor, false));
        p.swing(InteractionHand.MAIN_HAND);
        lastPlaceMs = now;
        XlrwDebug.logScaffoldBridge("PLACE_SUCCESS", floor, floor.relative(dir), "dir=" + dir);
        return BridgeResult.WORKING;
    }
}
