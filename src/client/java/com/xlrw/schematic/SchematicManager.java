package com.xlrw.schematic;

import com.xlrw.config.BuilderConfig;
import com.xlrw.config.WorldStateManager;
import com.xlrw.schematic.parser.LegacySchematicParser;
import com.xlrw.schematic.parser.LitematicaParser;
import com.xlrw.schematic.parser.SpongeSchematicParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SchematicManager {
    private static final SchematicManager INSTANCE = new SchematicManager();

    private final File schematicsDir;
    private Schematic activeSchematic = null;
    private String activeSchematicFileName = null;

    private SchematicManager() {
        this.schematicsDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "schematics");
        if (!schematicsDir.exists()) {
            schematicsDir.mkdirs();
        }
    }

    public static SchematicManager getInstance() {
        return INSTANCE;
    }

    public File getSchematicsDir() {
        return schematicsDir;
    }

    public Schematic getActiveSchematic() {
        return activeSchematic;
    }

    public String getActiveSchematicFileName() {
        return activeSchematicFileName;
    }

    public void setActiveSchematic(Schematic schematic) {
        setActiveSchematic(schematic, true, true);
    }

    public void setActiveSchematic(Schematic schematic, boolean resetProgress, boolean saveSession) {
        this.activeSchematic = schematic;
        if (schematic != null) {
            if (resetProgress) {
                BuilderConfig.getInstance().currentLayer = 0;
                BuilderConfig.getInstance().scaffoldPillarPos = null;
                BuilderConfig.save();
            }
        } else {
            this.activeSchematicFileName = null;
            if (resetProgress) {
                BuilderConfig.getInstance().scaffoldPillarPos = null;
                BuilderConfig.save();
            }
        }
        if (saveSession) {
            WorldStateManager.saveCurrentSession(Minecraft.getInstance());
        }
    }

    public List<File> getAvailableSchematics() {
        List<File> result = new ArrayList<>();
        File[] files = schematicsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".litematic") || name.endsWith(".schem") || name.endsWith(".schematic")) {
                        result.add(f);
                    }
                }
            }
        }
        return result;
    }

    public boolean loadFile(File file) {
        return loadFile(file, true, true);
    }

    public boolean loadFile(File file, boolean autoAnchor) {
        return loadFile(file, autoAnchor, true);
    }

    public boolean loadFile(File file, boolean autoAnchor, boolean resetProgress) {
        if (!file.exists()) return false;
        try {
            String name = file.getName().toLowerCase();
            Schematic loaded;
            if (name.endsWith(".litematic")) {
                loaded = LitematicaParser.parse(file);
            } else if (name.endsWith(".schem")) {
                loaded = SpongeSchematicParser.parse(file);
            } else if (name.endsWith(".schematic")) {
                loaded = LegacySchematicParser.parse(file);
            } else {
                return false;
            }

            Minecraft mc = Minecraft.getInstance();
            if (autoAnchor && mc.player != null) {
                loaded.setAnchor(mc.player.blockPosition());
            }

            this.activeSchematicFileName = file.getName();
            setActiveSchematic(loaded, resetProgress, true);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void unload() {
        unload(true);
    }

    public void unload(boolean saveSession) {
        this.activeSchematic = null;
        this.activeSchematicFileName = null;
        BuilderConfig.getInstance().scaffoldPillarPos = null;
        BuilderConfig.save();
        if (saveSession) {
            WorldStateManager.saveCurrentSession(Minecraft.getInstance());
        }
    }

    public void anchorToPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (activeSchematic != null && mc.player != null) {
            activeSchematic.setAnchor(mc.player.blockPosition());
            BuilderConfig.getInstance().scaffoldPillarPos = null;
            BuilderConfig.save();
            WorldStateManager.saveCurrentSession(mc);
        }
    }

    public void moveAnchor(int dx, int dy, int dz) {
        if (activeSchematic != null) {
            BlockPos current = activeSchematic.getAnchor();
            activeSchematic.setAnchor(current.offset(dx, dy, dz));
            BuilderConfig.getInstance().scaffoldPillarPos = null;
            BuilderConfig.save();
            WorldStateManager.saveCurrentSession(Minecraft.getInstance());
        }
    }

    public void rotate() {
        if (activeSchematic != null) {
            activeSchematic.rotateNext();
            BuilderConfig.getInstance().scaffoldPillarPos = null;
            BuilderConfig.save();
            WorldStateManager.saveCurrentSession(Minecraft.getInstance());
        }
    }
}
