package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.mixin.HandledScreenAccessor;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.lwjgl.glfw.GLFW;

public final class BundlePreview extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> activeKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("active-key")
        .description("mo bundle duoi con tro, hoac bundle dang cam o tay chinh")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_T))
        .build());

    public BundlePreview() {
        super(
            AliceAddon.CATEGORY,
            "Bundle Preview",
            "Shows every item stored in a Bundle, including nested Bundles."
        );
    }

    @AliceHandler(priority = 100)
    public void onKey(KeyEvent event) {
        if (
            !isActive()
                || event.action != KeyAction.Press
                || !activeKey.get().matches(true, event.key(), event.modifiers())
        ) {
            return;
        }

        if (mc.currentScreen instanceof BundlePreviewScreen previewScreen) {
            if (previewScreen.openHoveredBundle()) event.cancel();
            return;
        }

        if (openFromCurrentContext()) event.cancel();
    }

    String activeKeyLabel() {
        return activeKey.get().toString();
    }

    private boolean openFromCurrentContext() {
        if (mc.player == null) return false;

        Screen parent = mc.currentScreen;
        ItemStack stack;

        if (parent instanceof HandledScreen<?> handledScreen) {
            if (!(handledScreen instanceof HandledScreenAccessor accessor)) return false;

            Slot focusedSlot = accessor.meteor$getFocusedSlot();
            if (focusedSlot == null) return false;
            stack = focusedSlot.getStack();
        } else if (parent == null) {
            stack = mc.player.getMainHandStack();
        } else {
            return false;
        }

        if (!(stack.getItem() instanceof BundleItem)) return false;

        mc.setScreen(new BundlePreviewScreen(this, stack, parent));
        return true;
    }
}
