package com.xlrw;

import com.xlrw.builder.BuilderController;
import com.xlrw.config.BuilderConfig;
import com.xlrw.config.WorldStateManager;
import com.xlrw.keybinds.ModKeybinds;
import com.xlrw.render.SchematicRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XlrwClient implements ClientModInitializer {
    public static final String MOD_ID = "xlrw";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[XLRW] Initializing XLRW Client...");

        // Load configuration
        BuilderConfig.load();

        // Initialize diagnostic logging system
        com.xlrw.debug.XlrwDebug.init();

        // Xlrw account (optional login, Plus)
        com.xlrw.account.AccountManager.get().init();

        // Initialize keybindings
        ModKeybinds.init();

        // Initialize world rendering (hologram & highlights)
        SchematicRenderer.init();
        //? if >=1.21.11 {
        /*com.xlrw.gui.HoloPip.register(); // 3D preview in the deck (picture-in-picture)
        *///?}

        // Initialize in-game HUD overlay (top-left next block status)
        com.xlrw.gui.BuilderHud.init();

        // Register world session load on join
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LOGGER.info("[XLRW] Joined world/server! Loading world session data...");
            WorldStateManager.loadSessionForWorld(client);
        });

        // Register world session save on disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("[XLRW] Disconnecting from world/server! Saving world session data...");
            WorldStateManager.onWorldDisconnect(client);
            BuilderController.getInstance().reset();
        });

        // Register client tick listener for auto-building loop and periodic auto-save
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BuilderController.getInstance().onClientTick(client);
            com.xlrw.builder.DryRun.tick(client);
            com.xlrw.account.AccountManager.get().tick(client);
            WorldStateManager.tickAutoSave(client);
        });

        LOGGER.info("[XLRW] XLRW Client loaded successfully! Press 'V' to open the control panel, 'B' to toggle AutoBuild.");
    }
}
