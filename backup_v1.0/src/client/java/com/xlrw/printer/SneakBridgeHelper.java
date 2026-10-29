package com.xlrw.printer;

import com.xlrw.builder.BuildRules;
import com.xlrw.builder.InventoryHelper;
import com.xlrw.debug.XlrwDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Baritone-style Sneak Bridging Mechanics.
 *
 * Credit: Inspired by Baritone (cabaletta/baritone, LGPL v3.0 / MIT compatible concept).
 * Reference: Baritone MovementBridge & MovementTraverse sneak-placement logic.
 *
 * Provides human-authentic, safe crouch-bridging across gaps and ledges:
 *  1. Holds sneak (shift) continuously while near a ledge to lock the player's bounding box to the current block.
 *  2. Equips a disposable bridging block (Scaffolding, Glass, Dirt, Cobblestone, Planks...).
 *  3. Tilts view angle downward (pitch ~76-82 deg) aimed directly at the exposed side face of the supporting block.
 *  4. Right-clicks the side face to extend the walking platform across the void.
 *  5. Once the block materializes, steps forward onto the new foothold and repeats seamlessly.
 */
public class SneakBridgeHelper {

    private static long lastBridgeActionMs = 0;
    private static int bridgeFailStreak = 0;

    /**
     * Attempts to perform an authentic Baritone-style sneak-bridge action toward {@code bridgeDir}.
     *
     * @param mc Minecraft client instance
     * @param player Local player
     * @param level Client world
     * @param footPos BlockPos of the player's feet
     * @param bridgeDir Desired horizontal direction to extend the bridge
     * @return true if sneak-bridging is actively handling movement/placement, false if unable to bridge
     */
    public static boolean trySneakBridge(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos footPos, Direction bridgeDir) {
        if (mc == null || player == null || level == null || bridgeDir == null) return false;
        if (bridgeDir.getAxis() == Direction.Axis.Y) return false;

        // The block currently under the player's feet providing solid ground
        BlockPos currentSupport = footPos.below();
        if (!LocalPathfinder.isStandable(level, currentSupport)) {
            // Player is not standing on a solid support block
            return false;
        }

        // The target air space where the bridge block should be placed
        BlockPos targetPos = currentSupport.relative(bridgeDir);
        if (!level.getBlockState(targetPos).isAir() && !level.getBlockState(targetPos).canBeReplaced()) {
            // Already occupied by a solid block: bridge exists, simply walk onto it!
            return false;
        }

        // Ensure target position does not conflict with schematic
        com.xlrw.schematic.Schematic sch = com.xlrw.builder.BuilderController.getInstance().getSchematic();
        if (sch != null) {
            net.minecraft.world.level.block.state.BlockState exp = sch.getBlock(targetPos);
            if (exp != null && !exp.isAir()) {
                return false; // Reserved for schematic!
            }
        }

        // Ensure target position has passable headroom for player to walk into
        if (!LocalPathfinder.isPassable(level, targetPos.above()) || !LocalPathfinder.isPassable(level, targetPos.above(2))) {
            XlrwDebug.logRateLimited("BRIDGE_HEADROOM_" + targetPos.asLong(), 1000, "SNEAK_BRIDGE",
                    "Cannot bridge toward " + targetPos.toShortString() + ": Headroom blocked");
            return false;
        }

        // 1. ENGAGE SNEAK LOCK IMMEDIATELY: prevents player from ever falling off the ledge!
        mc.options.keyShift.setDown(true);
        if (player.input != null) {
            player.input.shiftKeyDown = true;
        }

        // 2. Select bridging material from inventory
        Item bridgeItem = InventoryHelper.findBridgingBlockItem(player);
        if (bridgeItem == net.minecraft.world.item.Items.SCAFFOLDING && !level.getBlockState(currentSupport).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
            // In Vanilla Minecraft, scaffolding horizontally attached to non-scaffolding drops into the void!
            bridgeItem = InventoryHelper.getAvailableSolidSupportItem(player);
        }
        if (bridgeItem == null) {
            XlrwDebug.logRateLimited("BRIDGE_NO_ITEM", 2000, "SNEAK_BRIDGE", "No bridging blocks available in inventory!");
            return false;
        }

        InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, bridgeItem);
        if (eq == InventoryHelper.EquipResult.SWITCHED) {
            // Slot switched this tick; pause 1 tick to let client/server item state sync
            return true;
        }
        if (!player.getMainHandItem().is(bridgeItem)) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastBridgeActionMs < 140) {
            // Rate limit placement actions (approx. 1 action per 140ms, matching authentic human clicks)
            return true;
        }

        // 3. Compute raycast target on the exposed side face of the supporting block
        // Click face is bridgeDir (e.g. facing North, click North face of currentSupport)
        Direction clickFace = bridgeDir;
        double faceOffsetX = bridgeDir.getStepX() * 0.5;
        double faceOffsetZ = bridgeDir.getStepZ() * 0.5;

        // Position slightly below top surface on the outer face
        Vec3 hitVec = new Vec3(
                currentSupport.getX() + 0.5 + faceOffsetX,
                currentSupport.getY() + 0.65,
                currentSupport.getZ() + 0.5 + faceOffsetZ
        );

        // 4. Calculate camera Yaw & Pitch looking downward at the side face
        Vec3 eyePos = player.getEyePosition();
        double dX = hitVec.x - eyePos.x;
        double dY = hitVec.y - eyePos.y;
        double dZ = hitVec.z - eyePos.z;
        double distXZ = Math.hypot(dX, dZ);

        float targetYaw = (float) (Mth.atan2(dZ, dX) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) (-(Mth.atan2(dY, distXZ) * (180.0 / Math.PI)));
        targetPitch = Mth.clamp(targetPitch, 65.0f, 85.0f);

        // Sync view angles to client and send server rotation packet
        player.setYRot(targetYaw);
        player.setXRot(targetPitch);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(targetYaw, targetPitch, player.onGround()));

        // 5. Gently steer toward the block edge while sneak is locked
        double edgeTargetX = currentSupport.getX() + 0.5 + bridgeDir.getStepX() * 0.42;
        double edgeTargetZ = currentSupport.getZ() + 0.5 + bridgeDir.getStepZ() * 0.42;
        steerTowardsEdge(mc, player, edgeTargetX, edgeTargetZ, 0.05);

        // 6. Execute place action (right click side face)
        BlockHitResult hitResult = new BlockHitResult(hitVec, clickFace, currentSupport, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);
        lastBridgeActionMs = now;

        XlrwDebug.logRateLimited("BRIDGE_PLACE_" + targetPos.asLong(), 500, "SNEAK_BRIDGE",
                String.format("Sneak-bridge block placed at %s via side face %s | Item=%s, Pitch=%.1f",
                        targetPos.toShortString(), clickFace.getName(), bridgeItem.getDescription().getString(), targetPitch));

        return true;
    }

    private static void steerTowardsEdge(Minecraft mc, LocalPlayer p, double tx, double tz, double dead) {
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
        mc.options.keyShift.setDown(true); // Maintain sneak lock!
        if (p.input != null) {
            p.input.up = up;
            p.input.down = down;
            p.input.left = l;
            p.input.right = r;
            p.input.shiftKeyDown = true;
            p.input.forwardImpulse = up ? 1.0f : (down ? -1.0f : 0.0f);
            p.input.leftImpulse = l ? 1.0f : (r ? -1.0f : 0.0f);
        }
        p.setSprinting(false);
    }

    public static void reset() {
        lastBridgeActionMs = 0;
        bridgeFailStreak = 0;
    }
}
