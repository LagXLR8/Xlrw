package com.xlrw.keybinds;

import com.xlrw.builder.BuilderController;
import com.xlrw.config.BuilderConfig;
import com.xlrw.gui.XlrwScreen;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class ModKeybinds {
    private static final String CATEGORY = "category.xlrw";

    public static KeyMapping TOGGLE_BUILD;
    public static KeyMapping OPEN_GUI;
    public static KeyMapping PLACE_STEP;
    public static KeyMapping LAYER_UP;
    public static KeyMapping LAYER_DOWN;

    public static void init() {
        TOGGLE_BUILD = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xlrw.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                CATEGORY
        ));

        OPEN_GUI = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xlrw.gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY
        ));

        PLACE_STEP = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xlrw.step",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                CATEGORY
        ));

        LAYER_UP = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xlrw.layer_up",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_PAGE_UP,
                CATEGORY
        ));

        LAYER_DOWN = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xlrw.layer_down",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_PAGE_DOWN,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(ModKeybinds::handleTick);
    }

    private static void handleTick(Minecraft mc) {
        if (mc.player == null) return;

        BuilderConfig config = BuilderConfig.getInstance();

        while (TOGGLE_BUILD.consumeClick()) {
            config.enabled = !config.enabled;
            BuilderConfig.save();
            String msg = config.enabled ? "§b[XLRW] §aAutoBuild: ON" : "§b[XLRW] §cAutoBuild: OFF";
            mc.player.displayClientMessage(Component.literal(msg), true);
        }

        while (OPEN_GUI.consumeClick()) {
            mc.setScreen(new XlrwScreen());
        }

        while (PLACE_STEP.consumeClick()) {
            BuilderController.getInstance().placeSingleStep(mc);
        }

        while (LAYER_UP.consumeClick()) {
            Schematic schematic = SchematicManager.getInstance().getActiveSchematic();
            if (schematic != null) {
                config.currentLayer++;
                BuilderConfig.save();
                mc.player.displayClientMessage(Component.literal("§b[XLRW] Current Layer: " + config.currentLayer), true);
            }
        }

        while (LAYER_DOWN.consumeClick()) {
            Schematic schematic = SchematicManager.getInstance().getActiveSchematic();
            if (schematic != null && config.currentLayer > 0) {
                config.currentLayer--;
                BuilderConfig.save();
                mc.player.displayClientMessage(Component.literal("§b[XLRW] Current Layer: " + config.currentLayer), true);
            }
        }
    }
}
