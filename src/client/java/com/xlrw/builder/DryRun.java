package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.debug.XlrwDebug;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Dry run: plans the whole remaining build on a virtual copy of the world WITHOUT sending a single packet.
 *
 * <p>Layer by layer (bottom up) it simulates what the bot will do: walk to a standing spot that reaches the most
 * blocks, place everything in reach (support grows as blocks are placed), raise a scaffolding tower when nothing
 * can be reached from where it can walk, and step onto the blocks it placed. The result is an estimate of the
 * time, the scaffolding and the materials needed, plus world markers for every spot where the bot would get stuck.
 *
 * <p>The work is time-sliced over client ticks (a few ms each) so even a 200x200 build never freezes the game.
 */
public final class DryRun {

    private DryRun() {}

    // ---------------------------------------------------------------------------------------------
    // Result (read by the renderer / HUD)
    // ---------------------------------------------------------------------------------------------

    public static final class Tower {
        public final BlockPos base;
        public final int topY; // feet Y on top of the tower
        Tower(BlockPos base, int topY) { this.base = base; this.topY = topY; }
    }

    public static final class Report {
        public final List<BlockPos> stations = new ArrayList<>();
        public final List<Tower> towers = new ArrayList<>();
        public final Map<BlockPos, String> problems = new LinkedHashMap<>();
        public final Set<BlockPos> floatingWarnings = new LinkedHashSet<>();
        public final Map<Item, Integer> needed = new HashMap<>();
        public int actions = 0;
        public int scaffoldBlocks = 0;
        public double walkMeters = 0;
        public int layersDone = 0;
        public int layersTotal = 0;
        public boolean finished = false;
        public long startedAt;
        public long tookMs;
    }

    private static final long TICK_BUDGET_NS = 12_000_000L; // 12 ms of work per client tick
    private static final int MAX_TOWER_HEIGHT = 24;
    private static final int MARKER_CAP = 4000;

    private static Report report = null;
    private static Job job = null;

    public static Report get() {
        return report;
    }

    public static boolean isRunning() {
        return job != null;
    }

    public static void clear() {
        job = null;
        report = null;
    }

    /** Starts (or restarts) a dry run of the active schematic from the current layer. */
    public static void start(Minecraft mc) {
        Schematic schematic = SchematicManager.getInstance().getActiveSchematic();
        if (mc.player == null || mc.level == null) return;
        if (schematic == null) {
            chat(mc, "§c[XLRW] Chưa nạp schematic nào để chạy thử.");
            return;
        }
        report = new Report();
        report.startedAt = System.currentTimeMillis();
        job = new Job(mc.level, mc.player, schematic, report);
        chat(mc, "§b[XLRW] §fĐang chạy thử (dry run)... §7(không gửi gói tin nào lên server)");
    }

    /** Toggle used by the keybind: running/showing -> clear, otherwise start. */
    public static void toggle(Minecraft mc) {
        if (job != null || report != null) {
            clear();
            chat(mc, "§b[XLRW] §7Đã tắt kết quả chạy thử.");
        } else {
            start(mc);
        }
    }

    public static void tick(Minecraft mc) {
        if (job == null) return;
        if (mc.level == null || mc.player == null || mc.level != job.level) {
            clear();
            return;
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        try {
            while (System.nanoTime() < deadline) {
                if (!job.step()) {
                    report.finished = true;
                    report.tookMs = System.currentTimeMillis() - report.startedAt;
                    job = null;
                    printReport(mc, report);
                    return;
                }
            }
        } catch (Throwable t) {
            XlrwDebug.log("DRYRUN/ERROR", "Chạy thử lỗi: " + t);
            chat(mc, "§c[XLRW] Chạy thử bị lỗi: " + t.getClass().getSimpleName() + " (xem xlrw_debug.log)");
            job = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------------------------------

    private static final class Job {
        final ClientLevel level;
        final LocalPlayer player;
        final Schematic schematic;
        final Report out;
        final BuilderConfig cfg = BuilderConfig.getInstance();
        final double reach;
        final int minX, maxX, minZ, maxZ, minY, maxY;

        /** Schematic cells already placed in the simulation (or already correct in the world). */
        final Set<BlockPos> placed = new HashSet<>();
        /** Scaffolding the simulation put up. */
        final Set<BlockPos> scaffold = new HashSet<>();

        int layerY;
        final int lastLayerY;
        boolean layerStarted = false;

        final Set<BlockPos> pending = new HashSet<>();
        final Set<BlockPos> reachable = new HashSet<>();
        final ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        BlockPos cur;
        /** Spots the bot can always get back to: where it started, and the top/bottom of every tower it raised. */
        final List<BlockPos> seeds = new ArrayList<>();
        int stuckGuard = 0;

        Job(ClientLevel level, LocalPlayer player, Schematic schematic, Report out) {
            this.level = level;
            this.player = player;
            this.schematic = schematic;
            this.out = out;
            this.reach = Math.max(2.5, cfg.reachDistance - 0.3); // same margin as the StationPlanner
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (BlockPos p : schematic.getWorldBlocks().keySet()) {
                x0 = Math.min(x0, p.getX()); x1 = Math.max(x1, p.getX());
                z0 = Math.min(z0, p.getZ()); z1 = Math.max(z1, p.getZ());
            }
            this.minX = x0 - 12; this.maxX = x1 + 12;
            this.minZ = z0 - 12; this.maxZ = z1 + 12;
            this.minY = schematic.getMinY() - 8;
            this.maxY = schematic.getMaxY() + 4;
            int startLayer = cfg.layerMode ? Math.max(0, cfg.currentLayer) : 0;
            this.layerY = schematic.getMinY() + startLayer;
            this.lastLayerY = schematic.getMaxY();
            out.layersTotal = Math.max(0, lastLayerY - layerY + 1);
            this.cur = player.blockPosition();

            // Everything already correct counts as placed; blocks below the start layer are assumed to be built
            for (Map.Entry<BlockPos, BlockState> e : schematic.getWorldBlocks().entrySet()) {
                BlockState want = e.getValue();
                if (BuildRules.isSkipped(want) || BuildRules.isLiquid(want)) continue;
                BuildRules.BuildStatus st = BuildRules.buildStatus(level.getBlockState(e.getKey()), want);
                if (st == BuildRules.BuildStatus.DONE || e.getKey().getY() < layerY) placed.add(e.getKey().immutable());
            }
            seeds.add(cur);
            reachable.add(cur);
            frontier.add(cur);
        }

        /** One unit of work. Returns false when the whole run is finished. */
        boolean step() {
            if (layerY > lastLayerY) return false;
            if (!layerStarted) {
                startLayer();
                return true;
            }
            if (!frontier.isEmpty()) {
                expand(4000);
                return true;
            }
            if (pending.isEmpty() || ++stuckGuard > 20_000) {
                out.layersDone++;
                layerY++;
                layerStarted = false;
                return true;
            }
            if (placeFromBestStation()) return true;
            if (raiseTower()) return true;
            resolveLeftovers();
            return true;
        }

        void startLayer() {
            layerStarted = true;
            stuckGuard = 0;
            pending.clear();
            for (Map.Entry<BlockPos, BlockState> e : schematic.getBlocksAtY(layerY)) {
                BlockPos p = e.getKey();
                BlockState want = e.getValue();
                if (BuildRules.isSkipped(want) || BuildRules.isLiquid(want) || placed.contains(p)) continue;
                pending.add(p.immutable());
                Item item = InventoryHelper.getItemForBlockState(want);
                if (item != null && item != Items.AIR) out.needed.merge(item, 1, Integer::sum);
            }
            // Re-flood from everything we can stand on: last layer's surface is walkable now, and towers can be
            // climbed both ways
            reachable.clear();
            frontier.clear();
            reachable.add(cur);
            frontier.add(cur);
            for (BlockPos s : seeds) tryAdd(s);
        }

        // ----- world model ---------------------------------------------------------------------

        boolean expectedSolid(BlockPos p) {
            BlockState want = schematic.getBlock(p);
            return want != null && want.blocksMotion();
        }

        boolean solid(BlockPos p) {
            if (scaffold.contains(p)) return true;
            if (placed.contains(p)) return expectedSolid(p);
            BlockState s = level.getBlockState(p);
            return !s.getCollisionShape(level, p).isEmpty();
        }

        boolean passable(BlockPos p) {
            if (solid(p)) return false;
            return !level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA);
        }

        boolean standable(BlockPos p) {
            return passable(p) && passable(p.above()) && solid(p.below());
        }

        boolean inBounds(BlockPos p) {
            return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ
                    && p.getY() >= minY && p.getY() <= maxY;
        }

        /** Incremental walk flood (same idea as LocalPathfinder: step up 1, drop up to 3). */
        void expand(int budget) {
            while (!frontier.isEmpty() && budget-- > 0) {
                BlockPos c = frontier.poll();
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos side = c.relative(d);
                    // up one (jump): head room above us
                    BlockPos up = side.above();
                    if (passable(c.above(2)) && tryAdd(up)) continue;
                    if (tryAdd(side)) continue;
                    // drop down 1..3: the column we step into must be free
                    if (!passable(side) || !passable(side.above())) continue;
                    for (int dy = 1; dy <= 3; dy++) {
                        BlockPos down = side.below(dy);
                        if (!passable(down)) break;
                        if (tryAdd(down)) break;
                    }
                }
            }
        }

        boolean tryAdd(BlockPos p) {
            if (!inBounds(p) || reachable.contains(p) || !standable(p)) return false;
            BlockPos im = p.immutable();
            reachable.add(im);
            frontier.add(im);
            return true;
        }

        /** New blocks change what is walkable: their tops become standing cells next to the reachable area. */
        void onPlaced(BlockPos p) {
            reachable.remove(p);
            reachable.remove(p.below()); // its head cell is now blocked
            BlockPos top = p.above();
            if (!standable(top)) return;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = top.relative(d);
                if (reachable.contains(n) || reachable.contains(n.below()) || reachable.contains(n.above())) {
                    if (reachable.add(top.immutable())) frontier.add(top.immutable());
                    return;
                }
            }
        }

        boolean supported(BlockPos p) {
            if (cfg.airPlace) return true;
            for (Direction d : Direction.values()) {
                if (solid(p.relative(d))) return true;
            }
            return false;
        }

        boolean inReach(BlockPos feet, BlockPos target) {
            if (target.equals(feet) || target.equals(feet.above())) return false;
            Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
            return eye.distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach;
        }

        /** Places every pending block reachable from {@code feet}, repeating while new support appears. */
        int placeAllFrom(BlockPos feet) {
            int r = (int) Math.ceil(reach);
            int placedNow = 0;
            boolean progress = true;
            while (progress) {
                progress = false;
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -r; dy <= r + 2; dy++) {
                    BlockPos t = feet.offset(dx, dy, dz);
                    if (!pending.contains(t) || !inReach(feet, t) || !supported(t)) continue;
                    pending.remove(t);
                    placed.add(t);
                    out.actions++;
                    placedNow++;
                    progress = true;
                    onPlaced(t);
                }
            }
            return placedNow;
        }

        int countFrom(BlockPos feet) {
            int r = (int) Math.ceil(reach);
            int n = 0;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -r; dy <= r + 2; dy++) {
                BlockPos t = feet.offset(dx, dy, dz);
                if (pending.contains(t) && inReach(feet, t) && supported(t)) n++;
            }
            return n;
        }

        /** Seed = nearest placeable block; station = the reachable spot in reach of it that covers the most work. */
        boolean placeFromBestStation() {
            BlockPos seed = null;
            double bestD = Double.MAX_VALUE;
            for (BlockPos p : pending) {
                if (!supported(p)) continue;
                double d = p.distSqr(cur);
                if (d < bestD && hasStationFor(p)) {
                    bestD = d;
                    seed = p;
                }
            }
            if (seed == null) return false;
            int r = (int) Math.ceil(reach);
            BlockPos best = null;
            double bestScore = -Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -4; dy <= 2; dy++) {
                BlockPos f = seed.offset(dx, dy, dz);
                if (!reachable.contains(f) || !inReach(f, seed)) continue;
                double score = countFrom(f) * 2.0 - Math.sqrt(f.distSqr(cur)) * 0.25;
                if (score > bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            if (best == null) return false;
            out.walkMeters += Math.sqrt(best.distSqr(cur)) * 1.25;
            cur = best;
            if (out.stations.size() < MARKER_CAP) out.stations.add(best);
            placeAllFrom(best);
            return true;
        }

        boolean hasStationFor(BlockPos t) {
            int r = (int) Math.ceil(reach);
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -4; dy <= 2; dy++) {
                BlockPos f = t.offset(dx, dy, dz);
                if (reachable.contains(f) && inReach(f, t)) return true;
            }
            return false;
        }

        /** A tower cell may only hold scaffolding if it is free now and not part of the build at/below this layer. */
        boolean columnCellFree(BlockPos p) {
            BlockState want = schematic.getBlock(p);
            if (want != null && !BuildRules.isSkipped(want) && p.getY() <= layerY) return false;
            return passable(p) && !scaffold.contains(p);
        }

        /** Nothing reachable from the ground: raise the tower whose top covers the most of the remaining work. */
        boolean raiseTower() {
            if (!cfg.useScaffolding) return false;
            BlockPos bestBase = null;
            int bestTop = 0, bestCover = 0;
            double bestScore = -Double.MAX_VALUE;
            int tried = 0;
            List<BlockPos> seeds = new ArrayList<>();
            for (BlockPos p : pending) if (supported(p)) seeds.add(p);
            seeds.sort(Comparator.comparingDouble(p -> p.distSqr(cur)));
            for (BlockPos seed : seeds) {
                if (tried++ >= 12) break;
                for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int elev : new int[]{seed.getY() + 1, seed.getY(), seed.getY() + 2}) {
                        BlockPos top = new BlockPos(seed.getX() + dx, elev, seed.getZ() + dz);
                        if (!inReach(top, seed)) continue;
                        if (!columnCellFree(top) || !columnCellFree(top.above())) continue;
                        int y = elev - 1;
                        while (y > elev - MAX_TOWER_HEIGHT - 1 && columnCellFree(new BlockPos(top.getX(), y, top.getZ()))) y--;
                        BlockPos ground = new BlockPos(top.getX(), y, top.getZ());
                        if (!solid(ground)) continue;
                        int height = elev - (y + 1);
                        if (height < 1 || height > MAX_TOWER_HEIGHT) continue;
                        BlockPos base = ground.above();
                        if (!standNear(base)) continue;
                        int cover = countFrom(top);
                        double score = cover * 3.0 - height * 0.8 - Math.sqrt(base.distSqr(cur)) * 0.3;
                        if (score > bestScore) {
                            bestScore = score;
                            bestBase = base;
                            bestTop = elev;
                            bestCover = cover;
                        }
                        break;
                    }
                }
            }
            if (bestBase == null || bestCover == 0) return false;
            for (int y = bestBase.getY(); y < bestTop; y++) scaffold.add(new BlockPos(bestBase.getX(), y, bestBase.getZ()));
            int h = bestTop - bestBase.getY();
            out.scaffoldBlocks += h;
            out.towers.add(new Tower(bestBase, bestTop));
            out.walkMeters += Math.sqrt(bestBase.distSqr(cur)) * 1.25 + h; // walk there + climb
            BlockPos top = new BlockPos(bestBase.getX(), bestTop, bestBase.getZ());
            cur = top;
            if (reachable.add(top)) frontier.add(top); // from the top the bot can step onto the layer
            seeds.add(top);
            for (Direction d : Direction.Plane.HORIZONTAL) { // ... and climb back down
                BlockPos n = bestBase.relative(d);
                if (standable(n)) seeds.add(n.immutable());
            }
            placeAllFrom(top);
            return true;
        }

        boolean standNear(BlockPos base) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dz == 0) continue;
                if (reachable.contains(base.offset(dx, dy, dz))) return true;
            }
            return false;
        }

        /** Whatever is left can neither be reached nor gets support: record it and move on. */
        void resolveLeftovers() {
            // Floating blocks first: the real bot raises a support column under one, then builds the rest normally
            for (BlockPos p : new ArrayList<>(pending)) {
                if (supported(p)) continue;
                out.floatingWarnings.add(p);
                int y = p.getY() - 1, h = 0;
                while (h < MAX_TOWER_HEIGHT && passable(new BlockPos(p.getX(), y, p.getZ()))) { y--; h++; }
                out.scaffoldBlocks += h;
                pending.remove(p);
                placed.add(p);
                out.actions++;
                onPlaced(p);
                return; // one at a time: the next ones may now have support
            }
            for (BlockPos p : pending) {
                if (out.problems.size() < MARKER_CAP) {
                    out.problems.put(p, "Không có chỗ đứng tới được và không dựng được trụ giàn giáo");
                }
            }
            // Treat them as placed so the layers above are still evaluated
            for (BlockPos p : pending) placed.add(p);
            pending.clear();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Report
    // ---------------------------------------------------------------------------------------------

    private static void printReport(Minecraft mc, Report r) {
        BuilderConfig cfg = BuilderConfig.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        double perBlockSec = (cfg.placeDelayMs + cfg.jitterMs * 0.5 + 60) / 1000.0;
        if (cfg.fastSimpleBlocks) perBlockSec *= 0.6;
        double sec = r.actions * perBlockSec
                + r.walkMeters / 4.3
                + r.scaffoldBlocks * 0.75
                + r.towers.size() * 3.0;

        int haveScaffold = player.isCreative() ? Integer.MAX_VALUE : count(player, Items.SCAFFOLDING);
        List<String> missing = new ArrayList<>();
        if (!player.isCreative()) {
            List<Map.Entry<Item, Integer>> need = new ArrayList<>(r.needed.entrySet());
            need.sort((a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<Item, Integer> e : need) {
                int have = count(player, e.getKey());
                if (have < e.getValue()) missing.add((e.getValue() - have) + "× " + com.xlrw.compat.MC.itemName(e.getKey()).getString());
            }
        }

        chat(mc, "§b§l[XLRW] Kết quả chạy thử §7(" + r.tookMs + " ms, " + r.layersDone + " tầng)");
        chat(mc, "§f⏱ Ước tính: §e" + formatTime(sec) + " §7| §f" + r.actions + " lần đặt, " + r.stations.size()
                + " chỗ đứng, đi bộ ~" + Math.round(r.walkMeters) + " m");
        chat(mc, "§f🏗 Giàn giáo: §e" + r.towers.size() + " trụ, ~" + r.scaffoldBlocks + " khối"
                + (haveScaffold == Integer.MAX_VALUE ? "" : haveScaffold >= r.scaffoldBlocks
                ? " §a(đủ: có " + haveScaffold + ")" : " §c(thiếu " + (r.scaffoldBlocks - haveScaffold) + ", có " + haveScaffold + ")"));
        if (!r.problems.isEmpty()) {
            chat(mc, "§c⚠ " + r.problems.size() + " block bot không với tới được §7(khối đỏ trong thế giới)");
            BlockPos first = r.problems.keySet().iterator().next();
            chat(mc, "§7   vd: " + first.toShortString());
        } else {
            chat(mc, "§a✔ Không có block nào bị kẹt.");
        }
        if (!r.floatingWarnings.isEmpty()) {
            chat(mc, "§6• " + r.floatingWarnings.size() + " block lơ lửng cần cột đỡ" + (cfg.airPlace ? "" : " §7(bật AIR PLACE để bỏ qua)"));
        }
        if (!missing.isEmpty()) {
            chat(mc, "§6• Thiếu vật liệu (trong túi): §f" + String.join(", ", missing.subList(0, Math.min(5, missing.size())))
                    + (missing.size() > 5 ? " §7+" + (missing.size() - 5) + " loại" : "") + " §7(sẽ lấy ở rương nếu có)");
        }
        if (!PlacementFinder.throughWallAnywhere()) {
            chat(mc, "§7  (Đặt xuyên tường đang tắt: số liệu có thể lạc quan hơn thực tế)");
        }
        chat(mc, "§7  Bấm lại phím chạy thử để tắt các khung đánh dấu.");

        XlrwDebug.log("DRYRUN/RESULT", String.format("actions=%d stations=%d towers=%d scaffold=%d walk=%.0fm problems=%d floating=%d est=%s",
                r.actions, r.stations.size(), r.towers.size(), r.scaffoldBlocks, r.walkMeters, r.problems.size(),
                r.floatingWarnings.size(), formatTime(sec)));
        for (Map.Entry<BlockPos, String> e : r.problems.entrySet()) {
            XlrwDebug.log("DRYRUN/STUCK", e.getKey().toShortString() + " " + describe(e.getKey()) + ": " + e.getValue());
        }
    }

    private static String describe(BlockPos p) {
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        BlockState st = s != null ? s.getBlock(p) : null;
        return st != null ? st.getBlock().getName().getString() : "?";
    }

    private static String formatTime(double sec) {
        long s = Math.round(sec);
        if (s < 60) return s + " giây";
        long m = s / 60;
        if (m < 60) return m + " phút " + (s % 60) + " giây";
        return (m / 60) + " giờ " + (m % 60) + " phút";
    }

    private static int count(LocalPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static void chat(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), false);
    }
}
