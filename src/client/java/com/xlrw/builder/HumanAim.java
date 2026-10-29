package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public class HumanAim {
    private static final Random RANDOM = new Random();

    public static float[] calculateRotations(Vec3 eyePos, Vec3 targetVec) {
        double dx = targetVec.x - eyePos.x;
        double dy = targetVec.y - eyePos.y;
        double dz = targetVec.z - eyePos.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));

        return new float[]{yaw, pitch};
    }

    public static boolean stepRotation(LocalPlayer player, float targetYaw, float targetPitch) {
        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.smoothAim) {
            player.setYRot(targetYaw);
            player.setXRot(targetPitch);
            return true;
        }

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float diffYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        float diffPitch = targetPitch - currentPitch;

        float absDiffYaw = Math.abs(diffYaw);
        float absDiffPitch = Math.abs(diffPitch);
        float tolerance = Math.max(1.8f, config.aimTolerance);

        if (absDiffYaw <= tolerance && absDiffPitch <= tolerance) {
            applyRotation(player, targetYaw, targetPitch);
            return true;
        }

        float maxSpeed = Math.max(20.0f, config.aimSpeed);
        // Exponential deceleration as crosshair nears target face
        float factor = Math.min(1.0f, (absDiffYaw + absDiffPitch) / 20.0f);
        float speed = Math.max(4.0f, maxSpeed * factor);

        float stepYaw = Mth.clamp(diffYaw * 0.60f, -speed, speed);
        float stepPitch = Mth.clamp(diffPitch * 0.60f, -speed * 0.80f, speed * 0.80f);

        if (config.aimNoise && (absDiffYaw > 3.0f || absDiffPitch > 3.0f)) {
            stepYaw += (RANDOM.nextFloat() - 0.5f) * 0.20f;
            stepPitch += (RANDOM.nextFloat() - 0.5f) * 0.12f;
        }

        applyRotation(player, currentYaw + stepYaw, Mth.clamp(currentPitch + stepPitch, -90.0f, 90.0f));

        float newDiffYaw = Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot()));
        float newDiffPitch = Math.abs(targetPitch - player.getXRot());
        if (newDiffYaw <= tolerance && newDiffPitch <= tolerance) {
            applyRotation(player, targetYaw, targetPitch);
            return true;
        }
        return false;
    }

    private static void applyRotation(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yHeadRot = yaw;
        player.yHeadRotO = yaw;
        player.yBodyRot = yaw;
    }

    public static boolean isLookingAtFace(net.minecraft.client.Minecraft mc, LocalPlayer player, net.minecraft.core.BlockPos targetBlock, net.minecraft.core.Direction targetFace, double reach) {
        if (mc.level == null || player == null) return false;
        Vec3 eyePos = player.getEyePosition();
        Vec3 viewVec = player.getViewVector(1.0f);
        Vec3 traceEnd = eyePos.add(viewVec.scale(reach));
        net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                eyePos, traceEnd, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            if (hit.getBlockPos().equals(targetBlock) && (targetFace == null || hit.getDirection() == targetFace)) {
                return true;
            }
            // Scaffolding has an open mesh - allow placing/looking through scaffolding
            if (mc.level.getBlockState(hit.getBlockPos()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                Vec3 pastScaffold = hit.getLocation().add(viewVec.scale(0.05));
                if (pastScaffold.distanceToSqr(eyePos) < reach * reach) {
                    net.minecraft.world.phys.BlockHitResult hit2 = mc.level.clip(new net.minecraft.world.level.ClipContext(
                            pastScaffold, traceEnd, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                    return hit2.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                            && hit2.getBlockPos().equals(targetBlock)
                            && (targetFace == null || hit2.getDirection() == targetFace);
                }
            }
        }
        return false;
    }
}
