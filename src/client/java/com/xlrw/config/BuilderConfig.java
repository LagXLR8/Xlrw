package com.xlrw.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class BuilderConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "xlrw.json");
    private static BuilderConfig INSTANCE = new BuilderConfig();

    // Master switch
    public boolean enabled = false;

    // Timers & Delays (ms) - Calm, reliable human building pace
    public int placeDelayMs = 120;
    public int jitterMs = 25;
    public int turnPenaltyMs = 40;

    // Fluid Aim Simulation
    public boolean smoothAim = true;
    public float aimSpeed = 28.0f; // degrees per tick (fast, snappy)
    public boolean aimNoise = true; // slight micro-movement jitter
    public float aimTolerance = 5.0f; // degrees tolerance before clicking

    // Validation & Limits
    public double reachDistance = 4.2; // vanilla reach ~4.5, legit 4.2
    public boolean strictRaycast = true; // line of sight check
    public boolean checkSupport = true; // ensure block has adjacent support face

    // Layer Settings
    public boolean layerMode = true;
    public int currentLayer = 0;
    public int layerRange = 1;
    public boolean autoLayer = true; // Automatically advance to next layer when current layer finishes!

    // 3D Printer Automation System
    public boolean autoMove = true; // Autonomous nozzle navigation towards blocks/chest/scaffolding
    public boolean autoRefill = true; // Auto refill from chest when out of building blocks
    public boolean autoDemolish = true; // Auto break misplaced/wrong blocks with pickaxe or appropriate tool
    public boolean useScaffolding = true; // Support scaffolding elevator system
    public BlockPos materialChestPos = null; // Position of primary supply chest (backward compatible)
    public java.util.List<BlockPos> materialChestPositions = new java.util.ArrayList<>(); // Multi-chest support
    public BlockPos scaffoldPillarPos = null; // Position of the scaffolding elevator pillar
    public boolean cleanupTempBlocks = true; // Remove throw-away bridge/pillar blocks when a layer is finished
    public boolean throughWallPlace = true; // master switch: place through walls (server checks distance only)
    /** On: through-wall placing is used ANYWHERE in reach (also lets the bot extend the layer it stands on).
     *  Off: only for boxed-in cells (old behaviour). Turn off on servers with anti-cheat that checks line of sight. */
    public boolean throughWallAnywhere = true;
    /** Dry run: show the plan markers (stations, towers, problem blocks) in the world. */
    public boolean renderDryRun = true;
    /** Xlrw account server ("" = built-in default). */
    public String accountServer = "";
    /** Plus only: place from 5 blocks away instead of the normal reach. */
    public boolean plusReach = false;
    /** Last time Bit asked to log in (ms). */
    public long loginPromptAt = 0;
    /** Plus only: deck colour theme (index into UiTheme.ALL, 0 = original). */
    public int uiTheme = 0;
    public boolean rotationSpoof = true; // place facing blocks (piston, observer...) without really looking that way
    public boolean chorusEscape = true; // stuck in a deep hole: eat a chorus fruit instead of pillaring out
    public boolean workWithInventoryOpen = true; // keep building while the player has the inventory open
    public boolean fastSimpleBlocks = true; // plain blocks (no facing / no redstone / no state) are placed in bursts
    public int fastBlocksPerTick = 3; // max plain blocks per tick in fast mode
        public boolean airPlace = true; // Place blocks directly in the air (no neighbour needed), like the AirPlace mod
    public boolean debugLog = true; // Large-scale diagnostic logging to logs/xlrw_debug.log
    public boolean debugChat = false; // Echo debug warnings into player chat

    public java.util.List<BlockPos> getChestPositions() {
        if (materialChestPositions == null) {
            materialChestPositions = new java.util.ArrayList<>();
        }
        if (materialChestPositions.isEmpty() && materialChestPos != null) {
            materialChestPositions.add(materialChestPos);
        }
        return materialChestPositions;
    }

    public void addChestPos(BlockPos pos) {
        if (pos == null) return;
        java.util.List<BlockPos> list = getChestPositions();
        if (!list.contains(pos)) {
            list.add(pos);
            materialChestPos = list.get(0);
            save();
        }
    }

    public void removeChestPos(BlockPos pos) {
        java.util.List<BlockPos> list = getChestPositions();
        list.remove(pos);
        materialChestPos = list.isEmpty() ? null : list.get(0);
        save();
    }

    public void clearChestPositions() {
        if (materialChestPositions != null) {
            materialChestPositions.clear();
        }
        materialChestPos = null;
        save();
    }

    // Inventory & Actions
    public boolean autoSwitchHotbar = true;
    public boolean creativeAutoPick = true;

    // Visuals & Overlays
    public boolean renderHologram = true;
    public boolean jukeboxCustom = false; // HUB jukebox plays your own files (config/xlrw/music)
    public boolean ui3d = true; // tilted cockpit panels with perspective + 3D schematic preview
    public boolean renderOnlyCurrentLayer = false;
    public boolean showHud = true; // Shows top-left target block HUD overlay
    public boolean renderBoundingBox = true;
    public boolean renderTargetBox = true;

    public static BuilderConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BuilderConfig();
        }
        return INSTANCE;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                BuilderConfig loaded = GSON.fromJson(reader, BuilderConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            save();
        }
    }

    public static void save() {
        try {
            if (!CONFIG_FILE.getParentFile().exists()) {
                CONFIG_FILE.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(getInstance(), writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
