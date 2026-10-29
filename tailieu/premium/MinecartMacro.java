package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import com.nnpg.alice.premium.AliceHandler;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public class MinecartMacro extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum distance from the eyes to place the rail/cart.")
        .defaultValue(4.0)
        .min(2.0)
        .sliderMax(6.0)
        .build()
    );

    public final Setting<Boolean> refillSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("refill")
        .description("Refills TNT Minecarts into your hotbar using Grim safe refill.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> refillSlot = sgGeneral.add(new IntSetting.Builder()
        .name("refill-slot")
        .description("The hotbar slot to refill TNT minecarts into (1-9).")
        .defaultValue(9)
        .min(1)
        .max(9)
        .sliderMax(9)
        .visible(refillSetting::get)
        .build()
    );

    private final GrimHotbarRefill refill = new GrimHotbarRefill(this);
    private int bowCharge = 0;
    private BlockPos pendingCartPos = null;
    private int pendingCartTick = 0;

    private static final MethodHandle SEQUENCED_PACKET_SENDER = findSequencedPacketSender();

    private static MethodHandle findSequencedPacketSender() {
        for (Method method : ClientPlayerInteractionManager.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() != void.class
                || parameters.length != 2
                || parameters[0] != ClientWorld.class
                || parameters[1] != SequencedPacketCreator.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                return MethodHandles.lookup().unreflect(method);
            } catch (IllegalAccessException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private void sendSequencedPacket(BlockHitResult hit) {
        if (SEQUENCED_PACKET_SENDER == null) return;
        SequencedPacketCreator creator = sequence ->
            new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, sequence);
        try {
            SEQUENCED_PACKET_SENDER.invoke(
                mc.interactionManager,
                mc.world,
                creator
            );
        } catch (Throwable ignored) {
        }
    }

    public MinecartMacro() {
        super(AliceAddon.PVP_CATEGORY, "minecart-macro", "Places a rail and TNT minecart where your arrow lands. Skidded from ThunderHack.");
    }

    @Override
    public void onDeactivate() {
        refill.cancel();
    }

    @AliceHandler(priority = 100)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        refill.onPlayerTickMovement();

        if (pendingCartPos != null) {
            if (pendingCartTick > 0) {
                pendingCartTick--;
            } else {
                FindItemResult cartResult = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cartResult.found()) {
                    InvUtils.swap(cartResult.slot(), true);
                    BlockHitResult cartHit = new BlockHitResult(new Vec3d(pendingCartPos.getX() + 0.5, pendingCartPos.up().getY() + 0.125, pendingCartPos.getZ() + 0.5), Direction.UP, pendingCartPos.up(), false);
                    sendSequencedPacket(cartHit);
                    InvUtils.swapBack();
                }
                pendingCartPos = null;
            }
        }
    }

    @AliceHandler(priority = -200)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        refill.onSendMovementPacketsPost();
    }

    @AliceHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player.isUsingItem() && mc.player.getMainHandStack().getItem() == Items.BOW) {
            bowCharge = mc.player.getItemUseTime();
        }

        if (!refillSetting.get()) {
            refill.cancel();
            return;
        }

        refill.requestExact(Items.TNT_MINECART, refillSlot.get() - 1);
    }

    @AliceHandler
    private void onPacketSend(PacketEvent.Sent event) {
        if (event.packet instanceof PlayerActionC2SPacket action && action.getAction() == PlayerActionC2SPacket.Action.RELEASE_USE_ITEM) {
            if (mc.player.getMainHandStack().getItem() == Items.BOW) {
                BlockPos bp = calcTrajectory(mc.player.getYaw());
                if (bp != null) {
                    double distSq = mc.player.squaredDistanceTo(bp.toCenterPos());
                    if (distSq <= maxDistance.get() * maxDistance.get() && distSq > 3) {
                        FindItemResult railResult = InvUtils.findInHotbar(Items.RAIL, Items.ACTIVATOR_RAIL, Items.DETECTOR_RAIL, Items.POWERED_RAIL);
                        FindItemResult cartResult = InvUtils.findInHotbar(Items.TNT_MINECART);

                        if (railResult.found() && cartResult.found()) {
                            InvUtils.swap(railResult.slot(), true);
                            BlockHitResult railHit = new BlockHitResult(new Vec3d(bp.getX() + 0.5, bp.getY() + 1.0, bp.getZ() + 0.5), Direction.UP, bp, false);
                            sendSequencedPacket(railHit);
                            InvUtils.swapBack();

                            pendingCartPos = bp;
                            pendingCartTick = 1;
                        }
                    }
                }
            }
        }
    }

    private BlockPos calcTrajectory(float yaw) {
        double x = mc.player.getX();
        double y = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()) - 0.1000000014901161;
        double z = mc.player.getZ();

        double motionX = -MathHelper.sin(yaw / 180.0f * 3.1415927f) * MathHelper.cos(mc.player.getPitch() / 180.0f * 3.1415927f);
        double motionY = -MathHelper.sin(mc.player.getPitch() / 180.0f * 3.141593f);
        double motionZ = MathHelper.cos(yaw / 180.0f * 3.1415927f) * MathHelper.cos(mc.player.getPitch() / 180.0f * 3.1415927f);
        float power = bowCharge / 20.0f;

        power = (power * power + power * 2.0f) / 3.0f;
        if (power > 1.0f) {
            power = 1.0f;
        }

        final float distance = MathHelper.sqrt((float) (motionX * motionX + motionY * motionY + motionZ * motionZ));
        motionX /= distance;
        motionY /= distance;
        motionZ /= distance;

        final float pow = power * 3;
        motionX *= pow;
        motionY *= pow;
        motionZ *= pow;

        if (!mc.player.isOnGround())
            motionY += mc.player.getVelocity().getY();

        Vec3d lastPos;
        for (int i = 0; i < 300; i++) {
            lastPos = new Vec3d(x, y, z);
            x += motionX;
            y += motionY;
            z += motionZ;

            motionX *= 0.99;
            motionY *= 0.99;
            motionZ *= 0.99;

            motionY -= 0.05000000074505806;
            Vec3d pos = new Vec3d(x, y, z);

            for (Entity ent : mc.world.getEntities()) {
                if (ent instanceof ArrowEntity || ent.equals(mc.player)) continue;
                if (ent.getBoundingBox().intersects(new Box(x - 0.3, y - 0.3, z - 0.3, x + 0.3, y + 0.3, z + 0.3)))
                    return null;
            }

            BlockHitResult bhr = mc.world.raycast(new RaycastContext(lastPos, pos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
            if (bhr != null && bhr.getType() == HitResult.Type.BLOCK) {
                return bhr.getBlockPos();
            }

            if (y <= -65) break;
        }
        return null;
    }
}
