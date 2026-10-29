package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.BlockPos;

public class MoreKnockback extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> inMove = sgGeneral.add(new BoolSetting.Builder()
        .name("in-move")
        .description("Apply even if you are not moving.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> hurtTime = sgGeneral.add(new IntSetting.Builder()
        .name("hurt-time")
        .description("Maximum hurt time of the entity to apply knockback.")
        .defaultValue(10)
        .min(0)
        .sliderMax(10)
        .build()
    );

    public final Setting<Integer> chance = sgGeneral.add(new IntSetting.Builder()
        .name("chance")
        .description("Chance to apply more knockback.")
        .defaultValue(100)
        .min(0)
        .sliderMax(100)
        .build()
    );

    public final Setting<Boolean> skipOnCrit = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-on-crit")
        .description("Skip applying more knockback if you are performing a critical hit.")
        .defaultValue(true)
        .build()
    );

    public MoreKnockback() {
        super(AliceAddon.PVP_CATEGORY, "more-knockback", "Increases the knockback dealt to enemies (W-Tap). Skidded from ThunderHack.");
    }

    @AliceHandler
    private void onAttack(AttackEntityEvent event) {
        if (!(event.entity instanceof LivingEntity livingEntity)) return;

        if (!PlayerUtils.isMoving() && !inMove.get()) return;

        if (livingEntity.hurtTime > hurtTime.get()) return;

        if (Math.random() * 100 > chance.get()) return;

        if (skipOnCrit.get() && isPerformingCrit()) return;

        if (mc.player.isSprinting()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));

        mc.player.setSprinting(true);
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
    }

    private boolean isPerformingCrit() {
        if (mc.player == null || mc.world == null) return false;

        boolean reasonForSkipCrit =
            mc.player.getAbilities().flying
            || mc.player.isGliding()
            || mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
            || mc.world.getBlockState(mc.player.getBlockPos()).isOf(Blocks.COBWEB)
            || mc.player.isInLava()
            || mc.player.isSubmergedInWater();

        if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f) {
            return false;
        }

        if (!reasonForSkipCrit) {
            return !mc.player.isOnGround() && mc.player.fallDistance > 0f;
        }

        return false;
    }
}
