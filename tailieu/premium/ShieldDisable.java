package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

public final class ShieldDisable extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> hitDelay = sgGeneral.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("tick giua cac lan danh riu pha khien")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build());

    private final Setting<Integer> switchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("tick cho sau khi chuyen sang riu")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build());

    private final Setting<Boolean> switchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("switch-back")
        .description("quay ve slot cu khi muc tieu ngung dung khien")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> stun = sgGeneral.add(new BoolSetting.Builder()
        .name("stun")
        .description("danh them 1 phat riu khi pha khien")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> clickSimulation = sgGeneral.add(new BoolSetting.Builder()
        .name("click-simulation")
        .description("chi chuyen sang riu va de cho click vat ly thuc hien cu danh")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> requireHoldAxe = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-axe")
        .description("chi chay khi dang cam riu")
        .defaultValue(false)
        .build());

    private int previousSlot = -1;
    private int hitClock;
    private int switchClock;

    public ShieldDisable() {
        super(
            AliceAddon.PVP_CATEGORY,
            "ShieldDisable",
            "Switches to an axe and attacks players who are actively blocking."
        );
    }

    @Override
    public void onActivate() {
        previousSlot = -1;
        hitClock = hitDelay.get();
        switchClock = switchDelay.get();
        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        restorePreviousSlot();
        super.onDeactivate();
    }

    @AliceHandler
    private void onTick(TickEvent.Post event) {
        if (
            mc.player == null
                || mc.world == null
                || mc.interactionManager == null
                || mc.currentScreen != null
        ) {
            restorePreviousSlot();
            return;
        }

        if (!(mc.crosshairTarget instanceof EntityHitResult entityHit)) {
            restorePreviousSlot();
            return;
        }

        if (!(entityHit.getEntity() instanceof PlayerEntity target) || !target.isBlocking()) {
            restorePreviousSlot();
            return;
        }

        if (mc.player.isUsingItem()) return;
        if (
            requireHoldAxe.get()
                && !(mc.player.getMainHandStack().getItem() instanceof AxeItem)
        ) {
            return;
        }

        if (previousSlot == -1) {
            previousSlot = mc.player.getInventory().getSelectedSlot();
        }

        if (!(mc.player.getMainHandStack().getItem() instanceof AxeItem)) {
            if (switchClock > 0) {
                switchClock--;
                return;
            }

            int axeSlot = findAxeSlot();
            if (axeSlot == -1) {
                restorePreviousSlot();
                return;
            }

            InvUtils.swap(axeSlot, false);
            switchClock = switchDelay.get();
            return;
        }

        if (switchClock > 0) {
            switchClock--;
            return;
        }

        if (hitClock > 0) {
            hitClock--;
            return;
        }

        if (clickSimulation.get()) return;

        attack(target);
        if (stun.get()) attack(target);

        hitClock = hitDelay.get();
        switchClock = switchDelay.get();
    }

    private void attack(PlayerEntity target) {
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private int findAxeSlot() {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).getItem() instanceof AxeItem) return slot;
        }
        return -1;
    }

    private void restorePreviousSlot() {
        if (
            previousSlot != -1
                && switchBack.get()
                && mc.player != null
                && mc.interactionManager != null
        ) {
            InvUtils.swap(previousSlot, false);
        }

        previousSlot = -1;
        hitClock = hitDelay.get();
        switchClock = switchDelay.get();
    }
}
