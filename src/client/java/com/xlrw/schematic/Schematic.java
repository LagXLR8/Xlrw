package com.xlrw.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Schematic {
    private final String name;
    private final Vec3i size;
    private final Map<BlockPos, BlockState> localBlocks;

    private BlockPos anchor = BlockPos.ZERO;
    private Rotation rotation = Rotation.NONE;

    private Map<BlockPos, BlockState> worldBlocksCache = new HashMap<>();
    private final Map<Integer, java.util.List<Map.Entry<BlockPos, BlockState>>> byYCache = new HashMap<>();
    private int cachedMinY = 0;
    private int cachedMaxY = 0;
    private boolean cacheDirty = true;

    // Statistics
    private int totalBlocks = 0;
    private int placedBlocks = 0;
    private int missingBlocks = 0;
    private int wrongBlocks = 0;

    public Schematic(String name, Vec3i size, Map<BlockPos, BlockState> localBlocks) {
        this.name = name;
        this.size = size;
        this.localBlocks = new HashMap<>(localBlocks);
        this.totalBlocks = this.localBlocks.size();
        markDirty();
    }

    public String getName() {
        return name;
    }

    public Vec3i getSize() {
        return size;
    }

    public Map<BlockPos, BlockState> getLocalBlocks() {
        return Collections.unmodifiableMap(localBlocks);
    }

    public BlockPos getAnchor() {
        return anchor;
    }

    public void setAnchor(BlockPos anchor) {
        this.anchor = anchor;
        markDirty();
    }

    public Rotation getRotation() {
        return rotation;
    }

    public void setRotation(Rotation rotation) {
        this.rotation = rotation;
        markDirty();
    }

    public void rotateNext() {
        switch (this.rotation) {
            case NONE -> setRotation(Rotation.CLOCKWISE_90);
            case CLOCKWISE_90 -> setRotation(Rotation.CLOCKWISE_180);
            case CLOCKWISE_180 -> setRotation(Rotation.COUNTERCLOCKWISE_90);
            case COUNTERCLOCKWISE_90 -> setRotation(Rotation.NONE);
        }
    }

    public void markDirty() {
        this.cacheDirty = true;
    }

    public Map<BlockPos, BlockState> getWorldBlocks() {
        if (cacheDirty) {
            rebuildWorldCache();
        }
        return Collections.unmodifiableMap(worldBlocksCache);
    }

    private void rebuildWorldCache() {
        worldBlocksCache.clear();
        byYCache.clear();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, BlockState> entry : localBlocks.entrySet()) {
            BlockPos local = entry.getKey();
            BlockState state = entry.getValue();

            BlockPos rotatedPos = rotateRelativePos(local, rotation, size);
            BlockPos worldPos = anchor.offset(rotatedPos);
            BlockState rotatedState = state.rotate(rotation);

            worldBlocksCache.put(worldPos, rotatedState);
            minY = Math.min(minY, worldPos.getY());
            maxY = Math.max(maxY, worldPos.getY());
        }
        for (Map.Entry<BlockPos, BlockState> e : worldBlocksCache.entrySet()) {
            byYCache.computeIfAbsent(e.getKey().getY(), k -> new java.util.ArrayList<>()).add(e);
        }
        cachedMinY = minY == Integer.MAX_VALUE ? anchor.getY() : minY;
        cachedMaxY = maxY == Integer.MIN_VALUE ? anchor.getY() : maxY;
        cacheDirty = false;
    }

    /** All schematic entries on a given world Y level. */
    public java.util.List<Map.Entry<BlockPos, BlockState>> getBlocksAtY(int y) {
        if (cacheDirty) {
            rebuildWorldCache();
        }
        return byYCache.getOrDefault(y, Collections.emptyList());
    }

    public static BlockPos rotateRelativePos(BlockPos pos, Rotation rot, Vec3i bounds) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        return switch (rot) {
            case NONE -> new BlockPos(x, y, z);
            case CLOCKWISE_90 -> new BlockPos(bounds.getZ() - 1 - z, y, x);
            case CLOCKWISE_180 -> new BlockPos(bounds.getX() - 1 - x, y, bounds.getZ() - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, bounds.getX() - 1 - x);
        };
    }

    public BlockState getBlock(BlockPos worldPos) {
        return getWorldBlocks().get(worldPos);
    }

    public boolean contains(BlockPos worldPos) {
        return getWorldBlocks().containsKey(worldPos);
    }

    public int getMinY() {
        if (cacheDirty) {
            rebuildWorldCache();
        }
        return cachedMinY;
    }

    public int getMaxY() {
        if (cacheDirty) {
            rebuildWorldCache();
        }
        return cachedMaxY;
    }

    public void updateStats(Level level) {
        if (level == null) return;
        Map<BlockPos, BlockState> world = getWorldBlocks();
        int placed = 0;
        int missing = 0;
        int wrong = 0;

        for (Map.Entry<BlockPos, BlockState> entry : world.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState expected = entry.getValue();
            BlockState actual = level.getBlockState(pos);

            if (actual.equals(expected) || actual.getBlock() == expected.getBlock()) {
                placed++;
            } else if (actual.isAir() || actual.canBeReplaced()) {
                missing++;
            } else {
                wrong++;
            }
        }

        this.placedBlocks = placed;
        this.missingBlocks = missing;
        this.wrongBlocks = wrong;
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public int getPlacedBlocks() {
        return placedBlocks;
    }

    public int getMissingBlocks() {
        return missingBlocks;
    }

    public int getWrongBlocks() {
        return wrongBlocks;
    }

    public float getProgressPercentage() {
        if (totalBlocks == 0) return 0f;
        return (float) placedBlocks / totalBlocks * 100f;
    }
}
