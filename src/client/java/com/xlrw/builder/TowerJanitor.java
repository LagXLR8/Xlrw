package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.debug.XlrwDebug;
import com.xlrw.printer.LocalPathfinder;
import com.xlrw.printer.PrinterMovement;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds scaffolding towers that no remaining work can use any more and knocks them down.
 *
 * <p>A tower (column of scaffolding that is not part of the schematic) is still useful when the active plan uses it,
 * the player stands on / in it, or there is unfinished work of the given layer close enough that standing on top of
 * the tower (or raising it a little) could reach it. Anything else is clutter. Breaking the bottom piece of a
 * scaffolding column drops the whole column at once.
 */
public final class TowerJanitor {

    private static final int SCAN_RADIUS = 24;
    private static final int USEFUL_RADIUS = 5;     // horizontal distance from the column to still-unfinished work
    private static long nextScanAt = 0;
    private static List<BlockPos> uselessCache = new ArrayList<>();
    private static final Map<BlockPos, Long> giveUp = new HashMap<>();
    private static BlockPos walkTarget = null;
    private static long walkUntil = 0;

    private TowerJanitor() {}

    /** Bottom pieces of all scaffolding columns around the player that are not part of the build. */
    private static List<BlockPos> findTowerBases(ClientLevel level, Schematic schematic, BlockPos center) {
        List<BlockPos> bases = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int minY = Math.max(com.xlrw.compat.MC.minY(level), center.getY() - 40);
        int maxY = Math.min(com.xlrw.compat.MC.maxYExclusive(level) - 1, center.getY() + 30);
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    if (!level.getBlockState(m).is(Blocks.SCAFFOLDING)) continue;
                    if (level.getBlockState(m.below()).is(Blocks.SCAFFOLDING)) continue;
                    BlockState exp = schematic.getBlock(m);
                    if (exp != null && exp.is(Blocks.SCAFFOLDING)) continue; // part of the build itself
                    bases.add(m.immutable());
                }
            }
        }
        return bases;
    }

    private static int columnTop(ClientLevel level, BlockPos base) {
        int y = base.getY();
        while (level.getBlockState(new BlockPos(base.getX(), y + 1, base.getZ())).is(Blocks.SCAFFOLDING)) y++;
        return y;
    }

    private static boolean isUseful(ClientLevel level, LocalPlayer player, Schematic schematic, BlockPos base, int layerIndex) {
        int top = columnTop(level, base);
        // The active plan uses it
        StationPlanner.Plan plan = StationPlanner.getInstance().getPlan();
        if (plan != null && plan.scaffoldTowerPos != null
                && plan.scaffoldTowerPos.getX() == base.getX() && plan.scaffoldTowerPos.getZ() == base.getZ()) return true;
        // Used by a plan in the last 90s (an access tower the bot just came up, a tower it may go back to)
        if (StationPlanner.getInstance().isRecentlyUsedTower(base)) return true;
        // The player is on it / in it
        AABB box = player.getBoundingBox();
        if (box.maxX > base.getX() && box.minX < base.getX() + 1 && box.maxZ > base.getZ() && box.minZ < base.getZ() + 1
                && player.getY() >= base.getY() - 1 && player.getY() <= top + 2.5) return true;

        // Unfinished work of the layer close enough to be done from this tower (as it is or raised a little)
        int layerY = schematic.getMinY() + layerIndex;
        for (Map.Entry<BlockPos, BlockState> e : schematic.getBlocksAtY(layerY)) {
            BlockPos p = e.getKey();
            if (Math.abs(p.getX() - base.getX()) > USEFUL_RADIUS || Math.abs(p.getZ() - base.getZ()) > USEFUL_RADIUS) continue;
            BuildRules.BuildStatus st = BuildRules.buildStatus(level.getBlockState(p), e.getValue());
            if (st == BuildRules.BuildStatus.MISSING || st == BuildRules.BuildStatus.ADJUST || st == BuildRules.BuildStatus.STACKABLE) {
                return true;
            }
        }
        return false;
    }

    private static void rescan(Minecraft mc, LocalPlayer player, Schematic schematic, int layerIndex) {
        long now = System.currentTimeMillis();
        if (now < nextScanAt) return;
        nextScanAt = now + 2_000;
        giveUp.values().removeIf(t -> t < now);
        List<BlockPos> useless = new ArrayList<>();
        for (BlockPos base : findTowerBases(mc.level, schematic, player.blockPosition())) {
            if (giveUp.containsKey(base)) continue;
            if (!isUseful(mc.level, player, schematic, base, layerIndex)) useless.add(base);
        }
        uselessCache = useless;
    }

    /** Breaks a useless tower whose base is within reach right now. */
    public static boolean tickNearby(Minecraft mc, LocalPlayer player, Schematic schematic) {
        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.cleanupTempBlocks || mc.level == null) return false;
        if (DemolishManager.getInstance().isBreaking()) return false;
        rescan(mc, player, schematic, config.currentLayer);
        if (uselessCache.isEmpty()) return false;
        double reach = config.reachDistance - 0.2;
        Vec3 eye = player.getEyePosition();
        for (BlockPos base : uselessCache) {
            if (!mc.level.getBlockState(base).is(Blocks.SCAFFOLDING)) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(base)) > reach * reach) continue;
            return breakBase(mc, player, base);
        }
        return false;
    }

    /**
     * Layer finished: walk to every tower within the scan radius that the NEXT layer can not use and remove it.
     * @return true while busy
     */
    public static boolean tickLayerCleanup(Minecraft mc, LocalPlayer player, Schematic schematic, int nextLayerIndex) {
        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.cleanupTempBlocks || mc.level == null) return false;
        if (DemolishManager.getInstance().isBreaking()) {
            DemolishManager.getInstance().tickBreaking(mc, player);
            return true;
        }
        int maxLayer = schematic.getMaxY() - schematic.getMinY();
        rescan(mc, player, schematic, Math.min(nextLayerIndex, maxLayer));
        uselessCache.removeIf(b -> !mc.level.getBlockState(b).is(Blocks.SCAFFOLDING));
        // Between layers only clean what is close and not far below: walking 20 blocks down to a tower base and
        // climbing back up costs more than the tower is in the way. Everything else is cleaned when the build ends.
        boolean lastLayer = nextLayerIndex > maxLayer;
        if (!lastLayer) {
            uselessCache.removeIf(b -> Math.abs(b.getX() - player.getBlockX()) > 10 || Math.abs(b.getZ() - player.getBlockZ()) > 10
                    || b.getY() < player.getBlockY() - 4);
        }
        if (uselessCache.isEmpty()) {
            walkTarget = null;
            return false;
        }
        BlockPos base = uselessCache.stream()
                .min(java.util.Comparator.comparingDouble(b -> b.distSqr(player.blockPosition()))).orElse(null);
        double reach = config.reachDistance - 0.2;
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(base)) <= reach * reach) {
            walkTarget = null;
            return breakBase(mc, player, base);
        }
        long now = System.currentTimeMillis();
        if (!base.equals(walkTarget)) {
            walkTarget = base;
            walkUntil = now + 20_000;
        } else if (now > walkUntil) {
            XlrwDebug.log("TOWER/CLEANUP", "Không tới được chân trụ thừa " + base.toShortString() + ", bỏ qua");
            giveUp.put(base, now + 120_000);
            uselessCache.remove(base);
            walkTarget = null;
            return true;
        }
        BlockPos stand = findStandNear(mc.level, base, player);
        if (stand == null) {
            giveUp.put(base, now + 120_000);
            uselessCache.remove(base);
            return true;
        }
        BuilderController.getInstance().setStatusMessage("3D Printer: Đi dọn trụ giàn giáo thừa tại " + base.toShortString());
        PrinterMovement.moveTo(mc, new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), 0.4);
        return true;
    }

    private static boolean breakBase(Minecraft mc, LocalPlayer player, BlockPos base) {
        int top = columnTop(mc.level, base);
        XlrwDebug.log("TOWER/CLEANUP", "Đập trụ giàn giáo thừa " + base.toShortString() + " (cao " + (top - base.getY() + 1) + ")");
        BuilderController.getInstance().setStatusMessage("3D Printer: Đập trụ giàn giáo thừa tại " + base.toShortString());
        PrinterMovement.stop(mc);
        boolean started = DemolishManager.getInstance().startBreaking(mc, player, base);
        uselessCache.remove(base);
        if (!started) giveUp.put(base.immutable(), System.currentTimeMillis() + 60_000);
        return started;
    }

    private static BlockPos findStandNear(ClientLevel level, BlockPos base, LocalPlayer player) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    if (p.getX() == base.getX() && p.getZ() == base.getZ()) continue;
                    if (!LocalPathfinder.isValidStand(level, p)) continue;
                    if (level.getBlockState(p).is(Blocks.SCAFFOLDING) || level.getBlockState(p.below()).is(Blocks.SCAFFOLDING)) continue;
                    Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                    if (eye.distanceToSqr(Vec3.atCenterOf(base)) > 3.8 * 3.8) continue;
                    double d = p.distSqr(player.blockPosition());
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }
}
