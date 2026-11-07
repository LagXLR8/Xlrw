package com.xlrw.schematic.parser;

import com.xlrw.compat.NbtCompat;

import com.xlrw.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LitematicaParser {

    public static Schematic parse(File file) throws Exception {
        CompoundTag root;
        try (InputStream is = Files.newInputStream(file.toPath())) {
            root = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
        }

        String name = file.getName();
        if (NbtCompat.has(root, "Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag meta = NbtCompat.getCompound(root, "Metadata");
            if (NbtCompat.has(meta, "Name", Tag.TAG_STRING) && !NbtCompat.getString(meta, "Name").isBlank()) {
                name = NbtCompat.getString(meta, "Name");
            }
        }

        CompoundTag regionsTag = NbtCompat.getCompound(root, "Regions");
        Map<BlockPos, BlockState> blocks = new HashMap<>();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (String regionKey : NbtCompat.keys(regionsTag)) {
            CompoundTag region = NbtCompat.getCompound(regionsTag, regionKey);

            CompoundTag posTag = NbtCompat.getCompound(region, "Position");
            int posX = NbtCompat.getInt(posTag, "x");
            int posY = NbtCompat.getInt(posTag, "y");
            int posZ = NbtCompat.getInt(posTag, "z");

            CompoundTag sizeTag = NbtCompat.getCompound(region, "Size");
            int sizeX = NbtCompat.getInt(sizeTag, "x");
            int sizeY = NbtCompat.getInt(sizeTag, "y");
            int sizeZ = NbtCompat.getInt(sizeTag, "z");

            int absX = Math.abs(sizeX);
            int absY = Math.abs(sizeY);
            int absZ = Math.abs(sizeZ);

            int startX = posX + (sizeX < 0 ? sizeX + 1 : 0);
            int startY = posY + (sizeY < 0 ? sizeY + 1 : 0);
            int startZ = posZ + (sizeZ < 0 ? sizeZ + 1 : 0);

            // Decode Palette
            ListTag paletteList = NbtCompat.getList(region, "BlockStatePalette", Tag.TAG_COMPOUND);
            List<BlockState> palette = new ArrayList<>(paletteList.size());
            for (int i = 0; i < paletteList.size(); i++) {
                CompoundTag entry = NbtCompat.listCompound(paletteList, i);
                String blockName = NbtCompat.getString(entry, "Name");
                CompoundTag properties = NbtCompat.has(entry, "Properties", Tag.TAG_COMPOUND) ? NbtCompat.getCompound(entry, "Properties") : null;
                palette.add(BlockStateParser.fromNbt(blockName, properties));
            }

            long[] blockStates = NbtCompat.getLongArray(region, "BlockStates");
            int bitsPerBlock = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long mask = (1L << bitsPerBlock) - 1L;

            for (int y = 0; y < absY; y++) {
                for (int z = 0; z < absZ; z++) {
                    for (int x = 0; x < absX; x++) {
                        int index = (y * absZ + z) * absX + x;
                        int paletteIdx = 0;

                        if (blockStates.length > 0 && palette.size() > 1) {
                            int startBit = index * bitsPerBlock;
                            int startLong = startBit >> 6;
                            int endLong = ((index + 1) * bitsPerBlock - 1) >> 6;
                            int startOffset = startBit & 63;

                            if (startLong < blockStates.length) {
                                if (startLong == endLong) {
                                    paletteIdx = (int) ((blockStates[startLong] >>> startOffset) & mask);
                                } else if (endLong < blockStates.length) {
                                    int endOffset = 64 - startOffset;
                                    paletteIdx = (int) (((blockStates[startLong] >>> startOffset) | (blockStates[endLong] << endOffset)) & mask);
                                }
                            }
                        }

                        if (paletteIdx >= 0 && paletteIdx < palette.size()) {
                            BlockState state = palette.get(paletteIdx);
                            if (!state.isAir()) {
                                BlockPos worldPos = new BlockPos(startX + x, startY + y, startZ + z);
                                blocks.put(worldPos, state);

                                if (worldPos.getX() < minX) minX = worldPos.getX();
                                if (worldPos.getY() < minY) minY = worldPos.getY();
                                if (worldPos.getZ() < minZ) minZ = worldPos.getZ();

                                if (worldPos.getX() > maxX) maxX = worldPos.getX();
                                if (worldPos.getY() > maxY) maxY = worldPos.getY();
                                if (worldPos.getZ() > maxZ) maxZ = worldPos.getZ();
                            }
                        }
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            return new Schematic(name, new Vec3i(1, 1, 1), Map.of());
        }

        Map<BlockPos, BlockState> normalized = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos p = entry.getKey();
            normalized.put(new BlockPos(p.getX() - minX, p.getY() - minY, p.getZ() - minZ), entry.getValue());
        }

        Vec3i dimensions = new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        return new Schematic(name, dimensions, normalized);
    }
}
