package com.xlrw.schematic.parser;

import com.xlrw.compat.NbtCompat;

import com.xlrw.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class SpongeSchematicParser {

    public static Schematic parse(File file) throws Exception {
        CompoundTag root;
        try (InputStream is = Files.newInputStream(file.toPath())) {
            root = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
        }

        CompoundTag dataTag = root.contains("Schematic") ? NbtCompat.getCompound(root, "Schematic") : root;

        int width = NbtCompat.getShort(dataTag, "Width");
        int height = NbtCompat.getShort(dataTag, "Height");
        int length = NbtCompat.getShort(dataTag, "Length");

        CompoundTag paletteTag = NbtCompat.getCompound(dataTag, "Palette");
        Map<Integer, BlockState> paletteMap = new HashMap<>();

        for (String key : NbtCompat.keys(paletteTag)) {
            int id = NbtCompat.getInt(paletteTag, key);
            BlockState state = BlockStateParser.fromString(key);
            paletteMap.put(id, state);
        }

        byte[] blockData = NbtCompat.getByteArray(dataTag, "BlockData");
        Map<BlockPos, BlockState> blocks = new HashMap<>();

        int dataIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int paletteId = 0;
                    int shift = 0;
                    while (dataIndex < blockData.length) {
                        byte b = blockData[dataIndex++];
                        paletteId |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }

                    BlockState state = paletteMap.get(paletteId);
                    if (state != null && !state.isAir()) {
                        blocks.put(new BlockPos(x, y, z), state);
                    }
                }
            }
        }

        String name = file.getName();
        return new Schematic(name, new Vec3i(width, height, length), blocks);
    }
}
