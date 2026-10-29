package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import com.nnpg.alice.bridge.VelocityPlusPlusBridge;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Velocity module skidded from ThunderHack-Recode.
 *
 * <p>This is an independent Meteor/Minecraft 1.21.11 rewrite. Only the
 * ThunderHack 1.21 source was reviewed for feature vocabulary; the packet
 * mutation, jump input and Grim sequences are independently implemented.
 * Mode descriptions follow the source vocabulary.</p>
 *
 * <p>Exact local Grim 2.3.74-d54b49b source was audited: server-side
 * transaction sandwiches record the outbound entity/explosion vector before a
 * client can cancel the packet, so ThunderHack-style Cancel, reduced Custom,
 * and Grim sequences are not a reliable bypass and are expected to
 * produce AntiKB, AntiExplosion, Simulation or related position/packet flags.</p>
 */
public final class VelocityPlusPlus extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgJump = settings.createGroup("Jump");
    private final SettingGroup sgPause = settings.createGroup("Pause");

    private final Setting<Mode> mode = sgGeneral.add(
        new EnumSetting.Builder<Mode>()
            .name("mode")
            .description("Velocity mode.")
            .defaultValue(Mode.Custom)
            .build()
    );

    private final Setting<Double> horizontal = sgGeneral.add(
        new DoubleSetting.Builder()
            .name("horizontal")
            .description("Horizontal velocity percentage.")
            .defaultValue(0)
            .range(0.0, 100.0)
            .sliderRange(0.0, 100.0)
            .visible(() -> mode.get() == Mode.Custom || mode.get() == Mode.Jump)
            .build()
    );

    private final Setting<Double> vertical = sgGeneral.add(
        new DoubleSetting.Builder()
            .name("vertical")
            .description("Vertical velocity percentage.")
            .defaultValue(40.0)
            .range(0.0, 100.0)
            .sliderRange(0.0, 100.0)
            .visible(() -> mode.get() == Mode.Custom)
            .build()
    );

    private final Setting<Boolean> explosions = sgGeneral.add(
        new BoolSetting.Builder()
            .name("explosions")
            .description("Cancels explosion knockback.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> push = sgGeneral.add(
        new BoolSetting.Builder()
            .name("push")
            .description("Cancels entity push-out from blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> liquids = sgGeneral.add(
        new BoolSetting.Builder()
            .name("liquids")
            .description("Cancels flowing-liquid velocity.")
            .defaultValue(true)
            .build()
    );

    private final Setting<JumpMode> jumpMode = sgJump.add(
        new EnumSetting.Builder<JumpMode>()
            .name("jump-mode")
            .description("How to jump on hit.")
            .defaultValue(JumpMode.Jump)
            .visible(() -> mode.get() == Mode.Jump)
            .build()
    );

    private final Setting<Double> motion = sgJump.add(
        new DoubleSetting.Builder()
            .name("motion")
            .description("Vertical motion for Motion and Both modes.")
            .defaultValue(0.42)
            .range(0.1, 1.0)
            .sliderRange(0.1, 1.0)
            .visible(() -> mode.get() == Mode.Jump)
            .build()
    );

    private final Setting<Boolean> fail = sgJump.add(
        new BoolSetting.Builder()
            .name("smart-fail")
            .description("Randomly fails some jumps.")
            .defaultValue(true)
            .visible(() -> mode.get() == Mode.Jump)
            .build()
    );

    private final Setting<Double> failRate = sgJump.add(
        new DoubleSetting.Builder()
            .name("fail-rate")
            .description("Chance to fail a jump.")
            .defaultValue(0.3)
            .range(0.0, 1.0)
            .sliderRange(0.0, 1.0)
            .visible(() -> mode.get() == Mode.Jump && fail.get())
            .build()
    );

    private final Setting<Double> jumpRate = sgJump.add(
        new DoubleSetting.Builder()
            .name("fail-jump-rate")
            .description("Chance to actually jump after failing.")
            .defaultValue(0.25)
            .range(0.0, 1.0)
            .sliderRange(0.0, 1.0)
            .visible(() -> mode.get() == Mode.Jump && fail.get())
            .build()
    );

    private final Setting<Boolean> pauseOnFlag = sgPause.add(
        new BoolSetting.Builder()
            .name("pause-on-flag")
            .description("Pause velocity when Grim detects cheating.")
            .defaultValue(true)
            .build()
    );

    private boolean doJump;
    private boolean failJump;
    private boolean skip;
    private boolean flag;
    private int grimTicks;
    private int ccCooldown;
    private boolean paused;

    public VelocityPlusPlus() {
        super(
            AliceAddon.PVP_CATEGORY,
            "velocity++",
            "Modifies player velocity."
        );
    }

    @Override
    public void onActivate() {
        VelocityPlusPlusBridge.install(
            this,
            push::get,
            liquids::get,
            this::shouldCancelExplosion,
            this::shouldScaleVelocity
        );
        grimTicks = 0;
        paused = false;
        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        VelocityPlusPlusBridge.uninstall(this);
        paused = false;
        super.onDeactivate();
    }

    private boolean shouldCancelExplosion() {
        return explosions.get()
            && (mode.get() == Mode.Cancel || mode.get() == Mode.Grim)
            && !paused;
    }

    private boolean shouldScaleVelocity() {
        if (paused) return false;
        return switch (mode.get()) {
            case Custom -> horizontal.get() != 100.0 || vertical.get() != 100.0;
            case Jump -> horizontal.get() != 100.0;
            default -> false;
        };
    }

    @AliceHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        // Resume when flag is cleared
        if (paused && !flag) {
            paused = false;
        }

        // Pause velocity when flag is set
        if (flag && pauseOnFlag.get()) {
            paused = true;
            return;
        }

        // Entity velocity
        if (event.packet instanceof EntityVelocityUpdateS2CPacket packet) {
            if (packet.getEntityId() != mc.player.getId()) return;

            switch (mode.get()) {
                case Custom -> {
                    int h = horizontal.get().intValue();
                    int v = vertical.get().intValue();
                    if (h == 0 && v == 0) {
                        event.setCancelled(true);
                        return;
                    }
                    Vec3d vel = scaleVelocity(packet.getVelocity(), h, v);
                    mc.player.setVelocity(vel);
                    event.setCancelled(true);
                }
                case Cancel -> {
                    event.setCancelled(true);
                }
                case Jump -> {
                    int h = horizontal.get().intValue();
                    if (h == 0) {
                        event.setCancelled(true);
                        return;
                    }
                    Vec3d vel = scaleVelocity(packet.getVelocity(), h, 100);
                    mc.player.setVelocity(vel);
                    event.setCancelled(true);
                }
                case Grim -> {
                    if (!paused) {
                        event.setCancelled(true);
                        flag = true;
                    }
                }
            }
        }

        // Lagback
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            if (mode.get() == Mode.Grim) {
                ccCooldown = 5;
            }
        }
    }

    @AliceHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        switch (mode.get()) {
            case Jump -> {
                if ((failJump || mc.player.hurtTime > 6) && mc.player.isOnGround()) {
                    if (failJump) failJump = false;
                    if (!doJump) skip = true;

                    if (fail.get() && Math.random() <= failRate.get()) {
                        if (Math.random() <= jumpRate.get()) {
                            doJump = true;
                            failJump = true;
                        } else {
                            doJump = false;
                            failJump = false;
                        }
                    } else {
                        doJump = true;
                        failJump = false;
                    }

                    if (skip) {
                        skip = false;
                        return;
                    }

                    switch (jumpMode.get()) {
                        case Jump -> mc.player.jump();
                        case Motion -> mc.player.setVelocity(
                            mc.player.getVelocity().getX(),
                            motion.get(),
                            mc.player.getVelocity().getZ()
                        );
                        case Both -> {
                            mc.player.jump();
                            mc.player.setVelocity(
                                mc.player.getVelocity().getX(),
                                motion.get(),
                                mc.player.getVelocity().getZ()
                            );
                        }
                    }
                }
            }
            case Grim -> {
                if (flag) {
                    if (ccCooldown <= 0) {
                        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                            mc.player.getX(),
                            mc.player.getY(),
                            mc.player.getZ(),
                            mc.player.getYaw(),
                            mc.player.getPitch(),
                            mc.player.isOnGround(),
                            false
                        ));
                        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                            BlockPos.ofFloored(
                                mc.player.getX(),
                                mc.player.getY(),
                                mc.player.getZ()
                            ),
                            Direction.DOWN
                        ));
                    }
                    flag = false;
                }
            }
        }

        if (grimTicks > 0) grimTicks--;
        if (ccCooldown > 0) ccCooldown--;

        // Ping for Grim lag compensation
        if (mode.get() == Mode.Grim && grimTicks > 0) {
            grimTicks--;
        }
    }

    /**
     * Scales a velocity vector by horizontal (XZ) and vertical (Y) percentages.
     */
    public static Vec3d scaleVelocity(Vec3d incoming, int horizontal, int vertical) {
        if (horizontal == 0 && vertical == 0) return Vec3d.ZERO;

        double h = horizontal / 100.0;
        double v = vertical / 100.0;

        return new Vec3d(
            incoming.x * h,
            incoming.y * v,
            incoming.z * h
        );
    }

    public enum Mode {
        Custom,
        Cancel,
        Jump,
        Grim
    }

    public enum JumpMode {
        Motion,
        Jump,
        Both
    }
}
