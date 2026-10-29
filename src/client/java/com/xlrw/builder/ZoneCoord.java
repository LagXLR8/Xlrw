package com.xlrw.builder;

import net.minecraft.core.BlockPos;

/**
 * Represents a 3x3 horizontal sub-area (work station) on a build layer.
 * Enforces systematic chunked building: finishing all blocks within the 3x3 zone
 * before calmly moving to the next adjacent zone.
 */
public record ZoneCoord(int zx, int zz) {
    public static final int ZONE_SIZE = 3;

    public static ZoneCoord fromBlockPos(BlockPos pos) {
        if (pos == null) return new ZoneCoord(0, 0);
        return new ZoneCoord(Math.floorDiv(pos.getX(), ZONE_SIZE), Math.floorDiv(pos.getZ(), ZONE_SIZE));
    }

    public boolean contains(BlockPos pos) {
        if (pos == null) return false;
        int minX = zx * ZONE_SIZE;
        int minZ = zz * ZONE_SIZE;
        return pos.getX() >= minX && pos.getX() < minX + ZONE_SIZE
                && pos.getZ() >= minZ && pos.getZ() < minZ + ZONE_SIZE;
    }

    public BlockPos getCenter(int y) {
        return new BlockPos(zx * ZONE_SIZE + 1, y, zz * ZONE_SIZE + 1);
    }

    public double distanceSqTo(BlockPos pos) {
        if (pos == null) return Double.MAX_VALUE;
        double cx = zx * ZONE_SIZE + 1.5;
        double cz = zz * ZONE_SIZE + 1.5;
        double dx = pos.getX() + 0.5 - cx;
        double dz = pos.getZ() + 0.5 - cz;
        return dx * dx + dz * dz;
    }
}
