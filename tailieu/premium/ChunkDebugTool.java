package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk Debug Tool for ALICE ESP development.
 * Captures chunk-related packets to analyze underground base patterns on KingMC.
 * Logs to file for offline analysis.
 */
public class ChunkDebugTool extends AliceModule {
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCapture = settings.createGroup("Capture");
    private final SettingGroup sgLogging = settings.createGroup("Logging");

    private final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Bat/tat chunk debug tool")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> logChunkData = sgCapture.add(new BoolSetting.Builder()
        .name("log-chunk-data")
        .description("Log full chunk data packets")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> logBlockEntity = sgCapture.add(new BoolSetting.Builder()
        .name("log-block-entity")
        .description("Log tile entity packets (chests, hoppers, etc.)")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> logLightUpdate = sgCapture.add(new BoolSetting.Builder()
        .name("log-light-update")
        .description("Log light update packets")
        .defaultValue(false)
        .build());

    private final Setting<Integer> minY = sgCapture.add(new IntSetting.Builder()
        .name("min-y")
        .description("Y toi thieu de log (underground threshold)")
        .defaultValue(0)
        .range(-64, 10)
        .build());

    private final Setting<Boolean> notifyInChat = sgLogging.add(new BoolSetting.Builder()
        .name("notify-in-chat")
        .description("Hien thi thong bao trong chat khi phat hien underground activity")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> logToFile = sgLogging.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Log ra file de phan tich")
        .defaultValue(true)
        .build());

    private final Setting<Integer> logRetention = sgLogging.add(new IntSetting.Builder()
        .name("log-retention")
        .description("So dong log toi da truoc khi flush")
        .defaultValue(100)
        .range(10, 1000)
        .build());

    // Statistics
    private final Map<ChunkPos, ChunkStats> chunkStats = new HashMap<>();
    private final Set<ChunkPos> undergroundChunks = new HashSet<>();
    private final Set<BlockPos> knownUndergroundBlocks = new HashSet<>();

    // File logging
    private PrintWriter logWriter;
    private File logFile;
    private int logLineCount = 0;

    public ChunkDebugTool() {
        super(
            AliceAddon.ESP_CATEGORY,
            "chunk-debug-tool",
            "Thu thap packet data de phan tich underground base patterns tren KingMC"
        );
    }

    @Override
    public void onActivate() {
        if (logToFile.get()) {
            openLogFile();
        }
        chunkStats.clear();
        undergroundChunks.clear();
        knownUndergroundBlocks.clear();
        log("=== Chunk Debug Tool BAT ===");
        log("Min Y threshold: " + minY.get());
    }

    @Override
    public void onDeactivate() {
        log("=== Chunk Debug Tool TAT ===");
        dumpStatistics();
        closeLogFile();
        chunkStats.clear();
        undergroundChunks.clear();
        knownUndergroundBlocks.clear();
    }

    @AliceHandler(priority = 100)
    private void onChunkData(PacketEvent.Receive event) {
        if (!enabled.get()) return;
        if (!(event.packet instanceof ChunkDataS2CPacket packet)) return;
        if (!logChunkData.get()) return;

        // Get chunk position - use reflection-free approach
        ChunkPos pos = getChunkPosFromPacket(packet);
        if (pos == null) return;

        int chunkX = pos.x;
        int chunkZ = pos.z;

        WorldChunk chunk = mc.world != null ? mc.world.getChunk(chunkX, chunkZ) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("[CHUNK_DATA] ");
        sb.append(formatTime());
        sb.append(" chunk=").append(chunkX).append(",").append(chunkZ);

        if (chunk != null) {
            analyzeChunkForBaseBlocks(chunk, sb);
        }

        log(sb.toString());
        updateStats(pos, StatsType.ChunkData);
    }

    @AliceHandler(priority = 100)
    private void onBlockEntityUpdate(PacketEvent.Receive event) {
        if (!enabled.get()) return;
        if (!(event.packet instanceof BlockEntityUpdateS2CPacket packet)) return;
        if (!logBlockEntity.get()) return;

        BlockPos blockPos = getBlockEntityPos(packet);
        if (blockPos == null) return;

        int y = blockPos.getY();
        int chunkX = blockPos.getX() >> 4;
        int chunkZ = blockPos.getZ() >> 4;
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

        String blockEntityType = getBlockEntityType(packet);
        String blockType = getBlockTypeAt(blockPos);

        StringBuilder sb = new StringBuilder();
        sb.append("[BLOCK_ENTITY] ");
        sb.append(formatTime());
        sb.append(" chunk=").append(chunkX).append(",").append(chunkZ);
        sb.append(" pos=").append(blockPos.getX())
          .append(",").append(y)
          .append(",").append(blockPos.getZ());
        sb.append(" type=").append(blockEntityType);
        sb.append(" block=").append(blockType);

        boolean isUnderground = y <= minY.get();
        if (isUnderground) {
            sb.append(" [UNDERGROUND]");
            undergroundChunks.add(chunkPos);
            knownUndergroundBlocks.add(blockPos);
            if (notifyInChat.get()) {
                ChatUtils.warning("[ALICE] Phat hien " + blockEntityType + " underground tai " +
                    blockPos.getX() + "," + y + "," + blockPos.getZ());
            }
        }

        log(sb.toString());
        updateStats(chunkPos, StatsType.BlockEntity);
    }

    @AliceHandler(priority = 100)
    private void onLightUpdate(PacketEvent.Receive event) {
        if (!enabled.get()) return;
        if (!(event.packet instanceof LightUpdateS2CPacket packet)) return;
        if (!logLightUpdate.get()) return;

        int chunkX = getLightChunkX(packet);
        int chunkZ = getLightChunkZ(packet);
        if (chunkX == 0 && chunkZ == 0) return; // Invalid

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

        StringBuilder sb = new StringBuilder();
        sb.append("[LIGHT_UPDATE] ");
        sb.append(formatTime());
        sb.append(" chunk=").append(chunkX).append(",").append(chunkZ);

        // Check if this chunk has known underground blocks
        if (undergroundChunks.contains(chunkPos)) {
            sb.append(" [KNOWN_UNDERGROUND_CHUNK]");
        }

        log(sb.toString());
        updateStats(chunkPos, StatsType.LightUpdate);
    }

    private ChunkPos getChunkPosFromPacket(ChunkDataS2CPacket packet) {
        try {
            // Try direct field access via reflection
            // Minecraft 1.21.11 uses obfuscated names
            ChunkPos pos = null;

            // Try to get chunk from world if we have the coordinates
            if (mc.world != null) {
                // ChunkDataS2CPacket typically has chunk coordinates
                // We'll scan nearby chunks to find the right one
                int playerChunkX = mc.world.getChunk(mc.player.getBlockPos()).getPos().x;
                int playerChunkZ = mc.world.getChunk(mc.player.getBlockPos()).getPos().z;

                // Look in a 5-chunk radius
                for (int dx = -5; dx <= 5; dx++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        WorldChunk chunk = mc.world.getChunk(playerChunkX + dx, playerChunkZ + dz);
                        if (chunk != null && chunk.getPos().x == playerChunkX + dx && chunk.getPos().z == playerChunkZ + dz) {
                            return chunk.getPos();
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private BlockPos getBlockEntityPos(BlockEntityUpdateS2CPacket packet) {
        try {
            // BlockEntityUpdateS2CPacket has getPos() in 1.21.x
            return packet.getPos();
        } catch (Exception e) {
            return null;
        }
    }

    private String getBlockEntityType(BlockEntityUpdateS2CPacket packet) {
        try {
            NbtCompound nbt = packet.getNbt();
            if (nbt == null) return "NULL";

            if (nbt.contains("Items")) return "CONTAINER";
            if (nbt.contains("TransferCooldown")) return "HOPPER";
            if (nbt.contains("CookTime") || nbt.contains("BurnTime")) return "FURNACE";
            if (nbt.contains("SpawnData")) return "SPAWNER";
            if (nbt.contains("Command")) return "COMMAND_BLOCK";
            if (nbt.contains("z")) return "BEACON";
            if (nbt.contains("Base")) return "CONDUIT";
            if (nbt.contains("CustomName")) return "NAMED_BLOCK";
            return "BLOCK_ENTITY";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private int getLightChunkX(LightUpdateS2CPacket packet) {
        try {
            // LightUpdateS2CPacket has getChunkX() in 1.21.x
            return packet.getChunkX();
        } catch (Exception e) {
            return 0;
        }
    }

    private int getLightChunkZ(LightUpdateS2CPacket packet) {
        try {
            // LightUpdateS2CPacket has getChunkZ() in 1.21.x
            return packet.getChunkZ();
        } catch (Exception e) {
            return 0;
        }
    }

    private void analyzeChunkForBaseBlocks(WorldChunk chunk, StringBuilder sb) {
        if (chunk == null) return;

        int chestCount = 0;
        int hopperCount = 0;
        int furnaceCount = 0;
        int spawnerCount = 0;
        int redstoneCount = 0;
        int shulkerCount = 0;

        int threshold = minY.get();

        // Scan chunk for base-related blocks in underground region
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = threshold; y >= -64 && y < 10; y--) {
                    BlockPos pos = new BlockPos(chunk.getPos().x * 16 + x, y, chunk.getPos().z * 16 + z);
                    var state = chunk.getBlockState(pos);
                    Block block = state.getBlock();

                    if (isBaseBlock(block)) {
                        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) chestCount++;
                        else if (block == Blocks.HOPPER) hopperCount++;
                        else if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) furnaceCount++;
                        else if (block == Blocks.SPAWNER) spawnerCount++;
                        else if (isRedstoneBlock(block)) redstoneCount++;
                        else if (block == Blocks.SHULKER_BOX) shulkerCount++;
                    }
                }
            }
        }

        if (chestCount > 0 || hopperCount > 0 || furnaceCount > 0 || spawnerCount > 0 || redstoneCount > 0 || shulkerCount > 0) {
            sb.append(" | BASE_BLOCKS:");
            if (chestCount > 0) sb.append(" chest=").append(chestCount);
            if (shulkerCount > 0) sb.append(" shulker=").append(shulkerCount);
            if (hopperCount > 0) sb.append(" hopper=").append(hopperCount);
            if (furnaceCount > 0) sb.append(" furnace=").append(furnaceCount);
            if (spawnerCount > 0) sb.append(" spawner=").append(spawnerCount);
            if (redstoneCount > 0) sb.append(" redstone=").append(redstoneCount);

            // Mark chunk as underground if significant base blocks found
            if (chestCount > 0 || spawnerCount > 0) {
                undergroundChunks.add(chunk.getPos());
            }
        }
    }

    private boolean isBaseBlock(Block block) {
        return block == Blocks.CHEST ||
               block == Blocks.TRAPPED_CHEST ||
               block == Blocks.HOPPER ||
               block == Blocks.DROPPER ||
               block == Blocks.DISPENSER ||
               block == Blocks.FURNACE ||
               block == Blocks.BLAST_FURNACE ||
               block == Blocks.SMOKER ||
               block == Blocks.BARREL ||
               block == Blocks.SHULKER_BOX ||
               block == Blocks.SPAWNER ||
               block == Blocks.ENDER_CHEST ||
               block == Blocks.BEACON ||
               isRedstoneBlock(block);
    }

    private boolean isRedstoneBlock(Block block) {
        return block == Blocks.COMMAND_BLOCK ||
               block == Blocks.CHAIN_COMMAND_BLOCK ||
               block == Blocks.REPEATING_COMMAND_BLOCK ||
               block == Blocks.NOTE_BLOCK ||
               block == Blocks.COMPARATOR ||
               block == Blocks.REPEATER ||
               block == Blocks.REDSTONE_BLOCK;
    }

    private String getBlockTypeAt(BlockPos pos) {
        if (mc.world == null) return "NULL";
        try {
            Block block = mc.world.getBlockState(pos).getBlock();
            return block.getTranslationKey().replace("block.minecraft.", "");
        } catch (Exception e) {
            return "NULL";
        }
    }

    private void updateStats(ChunkPos pos, StatsType type) {
        ChunkStats stats = chunkStats.computeIfAbsent(pos, ChunkStats::new);
        stats.update(type);
    }

    private void dumpStatistics() {
        if (chunkStats.isEmpty()) {
            log("No chunk statistics collected");
            return;
        }

        log("=== CHUNK STATISTICS ===");
        log("Total chunks tracked: " + chunkStats.size());
        log("Underground chunks detected: " + undergroundChunks.size());
        log("Known underground blocks: " + knownUndergroundBlocks.size());

        // Find most active chunks
        List<Map.Entry<ChunkPos, ChunkStats>> sorted = new ArrayList<>(chunkStats.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().totalPackets, a.getValue().totalPackets));

        int count = 0;
        for (Map.Entry<ChunkPos, ChunkStats> e : sorted) {
            if (count++ >= 10) break;
            ChunkPos pos = e.getKey();
            ChunkStats stats = e.getValue();
            boolean isUnderground = undergroundChunks.contains(pos);
            log("Chunk " + pos.x + "," + pos.z + ": " +
                "total=" + stats.totalPackets +
                " data=" + stats.chunkData +
                " entities=" + stats.blockEntities +
                (isUnderground ? " [UNDERGROUND]" : ""));
        }

        // List known underground block positions
        if (!knownUndergroundBlocks.isEmpty()) {
            log("=== KNOWN UNDERGROUND BLOCKS ===");
            for (BlockPos pos : knownUndergroundBlocks) {
                log("  " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
        }

        log("======================");
    }

    private void openLogFile() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = "chunk_debug_" + timestamp + ".log";

            File gameDir = mc.runDirectory;
            logFile = new File(gameDir, "logs/" + filename);
            logFile.getParentFile().mkdirs();

            logWriter = new PrintWriter(new FileWriter(logFile, true));
            log("Log file: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            ChatUtils.error("[ALICE] Khong the tao file log: " + e.getMessage());
        }
    }

    private void closeLogFile() {
        if (logWriter != null) {
            logWriter.flush();
            logWriter.close();
            logWriter = null;
            ChatUtils.info("[ALICE] Log saved: " + (logFile != null ? logFile.getAbsolutePath() : "unknown"));
        }
    }

    private void log(String message) {
        System.out.println(message);

        if (logWriter != null && logToFile.get()) {
            logWriter.println(message);
            logLineCount++;

            if (logLineCount >= logRetention.get()) {
                logWriter.flush();
                logLineCount = 0;
            }
        }
    }

    private String formatTime() {
        return TIME_FORMAT.format(new Date());
    }

    private enum StatsType {
        ChunkData,
        BlockEntity,
        LightUpdate
    }

    private static class ChunkStats {
        public final ChunkPos pos;
        public int chunkData = 0;
        public int blockEntities = 0;
        public int lightUpdates = 0;
        public int totalPackets = 0;

        public ChunkStats(ChunkPos pos) {
            this.pos = pos;
        }

        public void update(StatsType type) {
            switch (type) {
                case ChunkData -> chunkData++;
                case BlockEntity -> blockEntities++;
                case LightUpdate -> lightUpdates++;
            }
            totalPackets++;
        }
    }
}
