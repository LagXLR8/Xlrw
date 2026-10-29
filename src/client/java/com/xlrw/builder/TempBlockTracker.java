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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Remembers every throw-away block the bot places for itself (sneak-bridge blocks, pillar steps...) so that they
 * can be removed again instead of being left all over the site.
 *
 * <p>Cleanup runs when a layer is finished (before advancing) and when the whole print is finished.
 */
public final class TempBlockTracker {

    private static final class Entry {
        final Block block;
        final long placedAt;

        Entry(Block block, long placedAt) {
            this.block = block;
            this.placedAt = placedAt;
        }
    }

    private static final Map<BlockPos, Entry> TEMP = new LinkedHashMap<>();
    private static final Map<BlockPos, Long> GIVE_UP_AT = new HashMap<>();
    private static BlockPos cleanupTarget = null;

    private TempBlockTracker() {}

    public static void record(BlockPos pos, Item item) {
        if (pos == null || !(item instanceof BlockItem bi)) return;
        TEMP.put(pos.immutable(), new Entry(bi.getBlock(), System.currentTimeMillis()));
        XlrwDebug.log("TEMP/PLACE", "Ghi nhận block tạm " + bi.getBlock().getName().getString() + " tại " + pos.toShortString());
    }

    public static boolean isTemp(BlockPos pos) {
        return pos != null && TEMP.containsKey(pos);
    }

    public static void clear() {
        TEMP.clear();
        GIVE_UP_AT.clear();
        cleanupTarget = null;
    }

    /** Drops entries that are gone from the world (broken, replaced by the real schematic block...). */
    private static void prune(ClientLevel level, Schematic schematic) {
        long now = System.currentTimeMillis();
        TEMP.entrySet().removeIf(e -> {
            if (now - e.getValue().placedAt < 2_000) return false; // server may not have confirmed yet
            BlockState s = level.getBlockState(e.getKey());
            if (s.getBlock() != e.getValue().block) return true;
            // It became part of the build (same block the schematic wants there): keep it, it is not debris
            BlockState expected = schematic != null ? schematic.getBlock(e.getKey()) : null;
            return expected != null && expected.getBlock() == s.getBlock();
        });
    }

    public static boolean hasPending(ClientLevel level, Schematic schematic) {
        if (level == null || TEMP.isEmpty()) return false;
        prune(level, schematic);
        return !TEMP.isEmpty();
    }

    /**
     * One tick of the cleanup routine.
     *
     * @return true while cleanup is busy (the caller should not do anything else this tick)
     */
    public static boolean tickCleanup(Minecraft mc, LocalPlayer player, Schematic schematic) {
        ClientLevel level = mc.level;
        if (level == null || player == null) return false;
        if (!BuilderConfig.getInstance().cleanupTempBlocks) return false;
        if (!hasPending(level, schematic)) {
            cleanupTarget = null;
            return false;
        }
        long now = System.currentTimeMillis();

        if (cleanupTarget == null || !TEMP.containsKey(cleanupTarget)) {
            cleanupTarget = null;
            double best = Double.MAX_VALUE;
            for (BlockPos p : TEMP.keySet()) {
                if (supportsPlayer(player, p)) continue;
                double d = p.distToCenterSqr(player.position());
                if (d < best) {
                    best = d;
                    cleanupTarget = p;
                }
            }
            if (cleanupTarget == null) return false;
            GIVE_UP_AT.put(cleanupTarget, now + 12_000);
        }

        BlockPos target = cleanupTarget;
        Long giveUp = GIVE_UP_AT.get(target);
        if (giveUp != null && now > giveUp) {
            XlrwDebug.log("TEMP/SKIP", "Không tới được block tạm tại " + target.toShortString() + ", bỏ qua.");
            TEMP.remove(target);
            GIVE_UP_AT.remove(target);
            cleanupTarget = null;
            return true;
        }

        if (supportsPlayer(player, target)) {
            // Step off it first
            BlockPos off = findStandNear(level, target, player);
            if (off != null) {
                PrinterMovement.moveTo(mc, new Vec3(off.getX() + 0.5, off.getY(), off.getZ() + 0.5), 0.3);
                return true;
            }
            TEMP.remove(target);
            cleanupTarget = null;
            return true;
        }

        double reach = BuilderConfig.getInstance().reachDistance - 0.2;
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach) {
            PrinterMovement.stop(mc);
            BuilderController.getInstance().setStatusMessage("3D Printer: Dọn block tạm tại " + target.toShortString());
            XlrwDebug.log("TEMP/CLEAN", "Phá block tạm tại " + target.toShortString());
            DemolishManager.getInstance().startBreaking(mc, player, target);
            TEMP.remove(target);
            GIVE_UP_AT.remove(target);
            cleanupTarget = null;
            return true;
        }

        BlockPos stand = findStandNear(level, target, player);
        if (stand == null) {
            TEMP.remove(target);
            cleanupTarget = null;
            return true;
        }
        BuilderController.getInstance().setStatusMessage("3D Printer: Đi dọn block tạm tại " + target.toShortString());
        PrinterMovement.moveTo(mc, new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), 0.4);
        return true;
    }

    /**
     * Breaks one recorded throw-away block that is within reach right now (and not under the player).
     * Called whenever nothing can be built from the current spot, so escape pillars and bridges are removed as soon
     * as the bot has left them instead of staying until the end of the layer.
     */
    public static boolean tickNearby(Minecraft mc, LocalPlayer player, Schematic schematic) {
        ClientLevel level = mc.level;
        if (level == null || player == null || TEMP.isEmpty()) return false;
        if (!BuilderConfig.getInstance().cleanupTempBlocks) return false;
        if (DemolishManager.getInstance().isBreaking()) return false;
        prune(level, schematic);
        long now = System.currentTimeMillis();
        double reach = BuilderConfig.getInstance().reachDistance - 0.2;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Entry> e : TEMP.entrySet()) {
            BlockPos p = e.getKey();
            if (now - e.getValue().placedAt < 1_500) continue; // give the server time, and don't pull it from under a jump
            if (level.getBlockState(p).getBlock() != e.getValue().block) continue; // gone / replaced (e.g. by water)
            if (supportsPlayer(player, p)) continue;
            if (underPlayer(player, p)) continue; // part of the column we are standing on: breaking it drops us back down
            double d = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
            if (d > reach * reach) continue;
            // For a scaffolding column, breaking the lowest piece drops the whole column at once
            BlockPos base = p;
            if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                while (level.getBlockState(base.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING) && TEMP.containsKey(base.below())) {
                    base = base.below();
                }
                if (supportsPlayer(player, base) || underPlayer(player, base) || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(base)) > reach * reach) {
                    base = p;
                }
            }
            if (d < bestD) {
                bestD = d;
                best = base;
            }
        }
        if (best == null) return false;
        XlrwDebug.log("TEMP/CLEAN", "Đập block tạm tại " + best.toShortString() + " (" + level.getBlockState(best).getBlock().getName().getString() + ")");
        BuilderController.getInstance().setStatusMessage("3D Printer: Đập block tạm tại " + best.toShortString());
        PrinterMovement.stop(mc);
        DemolishManager.getInstance().startBreaking(mc, player, best);
        TEMP.remove(best);
        return true;
    }

    private static boolean underPlayer(LocalPlayer player, BlockPos p) {
        if (p.getY() >= player.getY()) return false;
        AABB box = player.getBoundingBox();
        return box.maxX > p.getX() && box.minX < p.getX() + 1 && box.maxZ > p.getZ() && box.minZ < p.getZ() + 1;
    }

    private static boolean supportsPlayer(LocalPlayer player, BlockPos p) {
        AABB feetZone = player.getBoundingBox().move(0, -0.6, 0);
        return feetZone.intersects(new AABB(p));
    }

    private static BlockPos findStandNear(ClientLevel level, BlockPos target, LocalPlayer player) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos p = target.offset(dx, dy, dz);
                    if (p.below().equals(target)) continue; // never stand on the block we want to remove
                    if (!LocalPathfinder.isValidStand(level, p)) continue;
                    Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                    if (eye.distanceToSqr(Vec3.atCenterOf(target)) > 3.8 * 3.8) continue;
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
