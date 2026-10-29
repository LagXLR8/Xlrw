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

public class LegacySchematicParser {

    public static Schematic parse(File file) throws Exception {
        CompoundTag root;
        try (InputStream is = Files.newInputStream(file.toPath())) {
            root = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
        }

        int width = NbtCompat.getShort(root, "Width");
        int height = NbtCompat.getShort(root, "Height");
        int length = NbtCompat.getShort(root, "Length");

        byte[] blockIds = NbtCompat.getByteArray(root, "Blocks");
        byte[] blockData = NbtCompat.getByteArray(root, "Data");
        byte[] addBlocks = root.contains("AddBlocks") ? NbtCompat.getByteArray(root, "AddBlocks") : null;

        Map<BlockPos, BlockState> blocks = new HashMap<>();

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int index = (y * length + z) * width + x;
                    if (index >= blockIds.length) continue;

                    int id = blockIds[index] & 0xFF;
                    if (addBlocks != null && (index >> 1) < addBlocks.length) {
                        int add = addBlocks[index >> 1] & 0xFF;
                        if ((index & 1) == 0) {
                            id |= (add & 0x0F) << 8;
                        } else {
                            id |= (add & 0xF0) << 4;
                        }
                    }

                    int data = (index < blockData.length) ? (blockData[index] & 0xFF) : 0;
                    if (id != 0) {
                        BlockState state = BlockStateParser.fromLegacyId(id, data);
                        if (!state.isAir()) {
                            blocks.put(new BlockPos(x, y, z), state);
                        }
                    }
                }
            }
        }

        String name = file.getName();
        return new Schematic(name, new Vec3i(width, height, length), blocks);
    }
}
