package com.xlrw.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages per-world and per-server session persistence.
 * Prevents schematic data from disappearing upon quitting, and prevents data
 * from bleeding between different singleplayer worlds or servers.
 */
public class WorldStateManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File SESSIONS_DIR = new File(FabricLoader.getInstance().getConfigDir().toFile(), "xlrw_worlds");
    private static String activeWorldId = null;
    private static int ticksSinceLastAutoSave = 0;

    public static class SessionData {
        public String schematicFileName = null;
        public int anchorX = 0;
        public int anchorY = 0;
        public int anchorZ = 0;
        public boolean hasAnchor = false;
        public String rotation = "NONE";
        public int currentLayer = 0;
        public boolean layerMode = true;
        public BlockPos scaffoldPillarPos = null;
        public List<BlockPos> chestPositions = new ArrayList<>();
    }

    public static String resolveWorldId(Minecraft mc) {
        if (mc == null) return activeWorldId;
        try {
            if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                String levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
                if (levelName != null && !levelName.isEmpty()) {
                    return "sp_" + levelName.replaceAll("[^a-zA-Z0-9._-]", "_");
                }
            }
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return "mp_" + mc.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9._-]", "_");
            }
            if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
                java.net.SocketAddress addr = mc.getConnection().getConnection().getRemoteAddress();
                if (addr != null) {
                    return "net_" + addr.toString().replaceAll("[^a-zA-Z0-9._-]", "_");
                }
            }
        } catch (Exception ignored) {}
        return activeWorldId;
    }

    public static void saveCurrentSession(Minecraft mc) {
        String worldId = activeWorldId != null ? activeWorldId : resolveWorldId(mc);
        if (worldId == null) return;
        activeWorldId = worldId;

        if (!SESSIONS_DIR.exists()) {
            SESSIONS_DIR.mkdirs();
        }

        SessionData data = new SessionData();
        BuilderConfig config = BuilderConfig.getInstance();
        data.currentLayer = config.currentLayer;
        data.layerMode = config.layerMode;
        data.scaffoldPillarPos = config.scaffoldPillarPos;
        data.chestPositions = new ArrayList<>(config.getChestPositions());

        Schematic activeSchematic = SchematicManager.getInstance().getActiveSchematic();
        if (activeSchematic != null) {
            data.schematicFileName = SchematicManager.getInstance().getActiveSchematicFileName();
            BlockPos anchor = activeSchematic.getAnchor();
            if (anchor != null) {
                data.anchorX = anchor.getX();
                data.anchorY = anchor.getY();
                data.anchorZ = anchor.getZ();
                data.hasAnchor = true;
            }
            if (activeSchematic.getRotation() != null) {
                data.rotation = activeSchematic.getRotation().name();
            }
        }

        File sessionFile = new File(SESSIONS_DIR, worldId + ".json");
        try (FileWriter writer = new FileWriter(sessionFile)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void loadSessionForWorld(Minecraft mc) {
        String worldId = resolveWorldId(mc);
        if (worldId == null) return;
        activeWorldId = worldId;

        // Unload old in-memory schematic cleanly without triggering saveCurrentSession
        SchematicManager.getInstance().unload(false);

        File sessionFile = new File(SESSIONS_DIR, worldId + ".json");
        if (!sessionFile.exists()) {
            // Brand new world: start with clean world state
            BuilderConfig config = BuilderConfig.getInstance();
            config.currentLayer = 0;
            config.scaffoldPillarPos = null;
            config.clearChestPositions();
            BuilderConfig.save();
            return;
        }

        try (FileReader reader = new FileReader(sessionFile)) {
            SessionData data = GSON.fromJson(reader, SessionData.class);
            if (data == null) return;

            // Restore active schematic without resetting layer progress or auto-anchoring
            if (data.schematicFileName != null && !data.schematicFileName.isEmpty()) {
                File schemFile = new File(SchematicManager.getInstance().getSchematicsDir(), data.schematicFileName);
                if (schemFile.exists()) {
                    boolean success = SchematicManager.getInstance().loadFile(schemFile, false, false);
                    if (success) {
                        Schematic loaded = SchematicManager.getInstance().getActiveSchematic();
                        if (loaded != null) {
                            if (data.hasAnchor) {
                                loaded.setAnchor(new BlockPos(data.anchorX, data.anchorY, data.anchorZ));
                            }
                            if (data.rotation != null) {
                                try {
                                    loaded.setRotation(Rotation.valueOf(data.rotation));
                                } catch (Exception ignored) {}
                            }
                            if (mc.player != null) {
                                mc.player.displayClientMessage(
                                        Component.literal("§b[XLRW] §aRestored schematic: §e" + data.schematicFileName + " §7(Layer #" + data.currentLayer + ")"),
                                        false
                                );
                            }
                        }
                    }
                }
            }

            BuilderConfig config = BuilderConfig.getInstance();
            config.currentLayer = data.currentLayer;
            config.layerMode = data.layerMode;
            config.scaffoldPillarPos = data.scaffoldPillarPos;
            if (data.chestPositions != null) {
                config.materialChestPositions = new ArrayList<>(data.chestPositions);
                config.materialChestPos = data.chestPositions.isEmpty() ? null : data.chestPositions.get(0);
            }
            BuilderConfig.save();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void onWorldDisconnect(Minecraft mc) {
        if (activeWorldId != null) {
            saveCurrentSession(mc);
        }
        activeWorldId = null;
        SchematicManager.getInstance().unload(false);
        BuilderConfig config = BuilderConfig.getInstance();
        config.scaffoldPillarPos = null;
        BuilderConfig.save();
    }

    public static void tickAutoSave(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        ticksSinceLastAutoSave++;
        if (ticksSinceLastAutoSave >= 200) { // Auto-save every 10 seconds
            ticksSinceLastAutoSave = 0;
            saveCurrentSession(mc);
        }
    }
}
