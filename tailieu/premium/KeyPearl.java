package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

public final class KeyPearl extends AliceModule {
    private final Setting<Keybind> key = settings.getDefaultGroup().add(
        new KeybindSetting.Builder()
            .name("throw-key")
            .description("nem pearl tu trong inventory")
            .build()
    );

    private boolean pressed;

    public KeyPearl() {
        super(
            AliceAddon.PVP_CATEGORY,
            "key-pearl",
            "Throw pearl even if not in hotbar."
        );
    }

    @AliceHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (key.get().isPressed()) {
            if (!pressed) {
                throwPearl();
                pressed = true;
            }
        } else {
            pressed = false;
        }
    }

    private void throwPearl() {
        FindItemResult pearl = InvUtils.find(Items.ENDER_PEARL);
        if (!pearl.found()) return;

        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (pearl.slot() >= 9) {
            InvUtils.move().from(pearl.slot()).toHotbar(previousSlot);
        } else {
            InvUtils.swap(pearl.slot(), false);
        }

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        InvUtils.swap(previousSlot, false);
    }
}
