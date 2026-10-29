package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

public final class TriggerBot extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only")
        .description("chi danh thuc the nguoi choi")
        .defaultValue(true)
        .build());

    private final Setting<Integer> swordDelay = sgGeneral.add(new IntSetting.Builder()
        .name("sword-delay")
        .description("thoi gian tre giua cac lan danh khong phai riu (ms)")
        .defaultValue(600)
        .min(0)
        .sliderMax(1000)
        .build());

    private final Setting<Integer> axeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("axe-delay")
        .description("thoi gian tre giua cac lan danh bang riu (ms)")
        .defaultValue(800)
        .min(0)
        .sliderMax(1000)
        .build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("hien thi hieu ung vung tay sau khi danh")
        .defaultValue(true)
        .build());

    private long lastAttackTime;

    public TriggerBot() {
        super(
            AliceAddon.PVP_CATEGORY,
            "TriggerBot",
            "Attacks a valid entity when the crosshair is directly over it."
        );
    }

    @Override
    public void onActivate() {
        lastAttackTime = 0L;
        super.onActivate();
    }

    @AliceHandler
    private void onTick(TickEvent.Post event) {
        if (
            mc.player == null
                || mc.world == null
                || mc.interactionManager == null
                || mc.currentScreen != null
                || !(mc.crosshairTarget instanceof EntityHitResult entityHit)
        ) {
            return;
        }

        Entity target = entityHit.getEntity();
        if (target == mc.player || !target.isAlive() || target.isRemoved()) return;
        if (playersOnly.get() && !(target instanceof PlayerEntity)) return;

        long delay = mc.player.getMainHandStack().getItem() instanceof AxeItem
            ? axeDelay.get()
            : swordDelay.get();
        long now = System.currentTimeMillis();
        if (now - lastAttackTime < delay) return;

        mc.interactionManager.attackEntity(mc.player, target);
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        lastAttackTime = now;
    }
}
