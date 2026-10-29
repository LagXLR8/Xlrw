package com.xlrw.debug;

import com.xlrw.builder.BuildRules;
import com.xlrw.builder.BuilderController;
import com.xlrw.builder.DemolishManager;
import com.xlrw.builder.InventoryHelper;
import com.xlrw.builder.StationPlanner;
import com.xlrw.config.BuilderConfig;
import com.xlrw.printer.LocalPathfinder;
import com.xlrw.printer.ScaffoldManager;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Behaviour monitor ("black box") for the builder bot.
 *
 * <ul>
 *   <li>Every second: one compact status line in {@code logs/xlrw_monitor.log} (position, motion, keys, state,
 *       plan, what it is doing and why).</li>
 *   <li>Progress tracking: a placed block, a finished demolish or really moving somewhere counts as progress.</li>
 *   <li>When there is NO progress for a while: a full snapshot (recent events, inventory, reachability and an
 *       ASCII map of the blocks around the bot) so the reason can be read off afterwards, and an escalation signal
 *       the controller uses to force its way out (climb out -> break out -> full reset).</li>
 * </ul>
 */
public final class BotMonitor {

    public enum Escalation { NONE, CLIMB_OUT, BREAK_OUT, RESET }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int RING_SIZE = 80;
    private static final Deque<String> RECENT = new ArrayDeque<>();

    private static PrintWriter writer;

    private static long lastProgressAt = System.currentTimeMillis();
    private static String lastProgressWhat = "start";
    private static Vec3 windowPos = null;
    private static int tickCounter = 0;
    private static int stage = 0;          // escalation steps already fired in this stuck episode
    private static int snapshotsThisEpisode = 0;
    private static long lastSnapshotAt = 0;
    private static boolean pendingSignalUsed = true;
    private static Escalation pendingSignal = Escalation.NONE;
    private static int placedSinceLastLine = 0;
    private static long lastTickAt = 0;
    private static volatile long lastPlacedAt = System.currentTimeMillis();

    /** Milliseconds since the last confirmed placement (pauses not excluded). */
    public static long sinceLastPlaced() {
        return System.currentTimeMillis() - lastPlacedAt;
    }
    private static Vec3 lastGroundPos = null;
    private static long lastStageAt = 0;

    private static final long[] STAGE_AT_MS = {15_000, 35_000, 60_000};

    private BotMonitor() {}

    // ------------------------------------------------------------------------------------------------
    // Event feed (called from XlrwDebug.log for every event)
    // ------------------------------------------------------------------------------------------------

    static synchronized void onEvent(String category, String formattedLine) {
        RECENT.addLast(formattedLine.length() > 300 ? formattedLine.substring(0, 300) + "…" : formattedLine);
        while (RECENT.size() > RING_SIZE) RECENT.removeFirst();
        if (category == null) return;
        if (category.equals("BUILD/OK")) {
            placedSinceLastLine++;
            lastPlacedAt = System.currentTimeMillis();
            progress("đặt block");
        } else if (category.equals("DEMOLISH/DONE")) {
            progress("đập xong block");
        } else if (category.equals("LAYER/ADVANCE")) {
            progress("lên layer mới");
        }
    }

    public static synchronized void progress(String what) {
        if (stage > 0) {
            write("[" + now() + "] [MON/RECOVERED] Có tiến triển lại (" + what + ") sau " + (System.currentTimeMillis() - lastProgressAt) / 1000 + "s kẹt");
        }
        lastProgressAt = System.currentTimeMillis();
        lastProgressWhat = what;
        stage = 0;
        snapshotsThisEpisode = 0;
    }

    /** Resets the stuck timer (printer paused / started / layer changed). */
    public static synchronized void reset() {
        lastProgressAt = System.currentTimeMillis();
        windowPos = null;
        stage = 0;
        snapshotsThisEpisode = 0;
        pendingSignal = Escalation.NONE;
        pendingSignalUsed = true;
    }

    /** One-shot escalation request for the controller; NONE most of the time. */
    public static synchronized Escalation pollEscalation() {
        if (pendingSignalUsed) return Escalation.NONE;
        pendingSignalUsed = true;
        return pendingSignal;
    }

    public static long stuckMillis() {
        return System.currentTimeMillis() - lastProgressAt;
    }

    // ------------------------------------------------------------------------------------------------
    // Per-tick
    // ------------------------------------------------------------------------------------------------

    public static void tick(Minecraft mc, LocalPlayer player, Schematic schematic, BuilderController ctl) {
        if (mc.level == null || player == null) return;
        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.enabled || schematic == null) {
            reset();
            return;
        }
        long nowMs = System.currentTimeMillis();
        // Game paused / alt-tabbed / a menu open: that time is not "stuck"
        if (lastTickAt != 0 && nowMs - lastTickAt > 500) {
            long gap = nowMs - lastTickAt;
            lastProgressAt += gap;
            lastStageAt += gap;
        }
        lastTickAt = nowMs;

        // Moving 3+ blocks away from where the last window started is progress too (long walks, tower climbs)
        Vec3 pos = player.position();
        if (windowPos == null) windowPos = pos;
        if (pos.distanceToSqr(windowPos) > 9.0) {
            windowPos = pos;
            progress("di chuyển");
        }

        // Unplanned falls: log where from / where to, so the cause can be found
        if (player.onGround()) {
            if (lastGroundPos != null && lastGroundPos.y - player.getY() >= 2.0) {
                StationPlanner.Plan pl = StationPlanner.getInstance().getPlan();
                XlrwDebug.log("MOVE/FELL", String.format("Rơi %.1f block: từ %.2f,%.2f,%.2f xuống %.2f,%.2f,%.2f | keys=%s | plan=%s",
                        lastGroundPos.y - player.getY(), lastGroundPos.x, lastGroundPos.y, lastGroundPos.z,
                        player.getX(), player.getY(), player.getZ(), keys(mc),
                        pl == null ? "-" : pl.planKind + " stand=" + pl.stationBlock.toShortString()));
            }
            lastGroundPos = player.position();
        } else if (player.isInWater() || player.onClimbable()) {
            lastGroundPos = player.position();
        }

        if (++tickCounter % 20 == 0) {
            write(statusLine(mc, player, ctl, schematic));
            placedSinceLastLine = 0;
        }

        long stuck = nowMs - lastProgressAt;
        // Snapshots: at 12s, then every 30s while still stuck (max 6 per episode)
        if (stuck > 12_000 && snapshotsThisEpisode < 6 && nowMs - lastSnapshotAt > (snapshotsThisEpisode == 0 ? 0 : 30_000)) {
            lastSnapshotAt = nowMs;
            snapshotsThisEpisode++;
            writeSnapshot(mc, player, schematic, ctl, stuck);
        }

        // Escalation: 15s climb out, 35s break out, 60s reset; then repeat the cycle every 30s
        long at = stage < STAGE_AT_MS.length ? STAGE_AT_MS[stage] : STAGE_AT_MS[STAGE_AT_MS.length - 1] + (stage - 2) * 30_000L;
        if (stuck > at && nowMs - lastStageAt > 10_000) {
            lastStageAt = nowMs;
            Escalation e = switch (stage % 3) {
                case 0 -> Escalation.CLIMB_OUT;
                case 1 -> Escalation.BREAK_OUT;
                default -> Escalation.RESET;
            };
            stage++;
            synchronized (BotMonitor.class) {
                pendingSignal = e;
                pendingSignalUsed = false;
            }
            write("[" + now() + "] [MON/ESCALATE] Không tiến triển " + stuck / 1000 + "s (lần cuối: " + lastProgressWhat + ") -> " + e);
            XlrwDebug.log("MON/ESCALATE", "Không tiến triển " + stuck / 1000 + "s -> " + e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------------------------------------

    private static String statusLine(Minecraft mc, LocalPlayer p, BuilderController ctl, Schematic schematic) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(now()).append("] [MON] ");
        sb.append(String.format("pos=%.2f,%.2f,%.2f v=%.2f,%.2f,%.2f %s%s%s",
                p.getX(), p.getY(), p.getZ(), p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z,
                p.onGround() ? "G" : "air", p.isInWater() ? " WATER" : "", p.isShiftKeyDown() ? " SNEAK" : ""));
        sb.append(" keys=").append(keys(mc));
        sb.append(" state=").append(ctl.getPrinterState());
        sb.append(" layer=").append(BuilderConfig.getInstance().currentLayer);
        StationPlanner.Plan plan = StationPlanner.getInstance().getPlan();
        if (plan != null) {
            sb.append(" plan=").append(plan.planKind).append('/').append(plan.phase)
                    .append(" anchor=").append(plan.anchor.toShortString())
                    .append(" stand=").append(plan.stationBlock.toShortString());
        } else {
            sb.append(" plan=-");
        }
        sb.append(" placed=").append(placedSinceLastLine);
        sb.append(" stuck=").append(stuckMillis() / 1000).append('s');
        BlockPos br = DemolishManager.getInstance().getActiveBreakPos();
        if (br != null) sb.append(" breaking=").append(br.toShortString());
        if (ScaffoldManager.getInstance().isPerformingScaffoldJump()) sb.append(" SCAFFOLD_JUMP");
        sb.append(" | ").append(ctl.getStatusMessage());
        return sb.toString();
    }

    private static String keys(Minecraft mc) {
        StringBuilder k = new StringBuilder();
        if (mc.options.keyUp.isDown()) k.append('W');
        if (mc.options.keyLeft.isDown()) k.append('A');
        if (mc.options.keyDown.isDown()) k.append('S');
        if (mc.options.keyRight.isDown()) k.append('D');
        if (mc.options.keyJump.isDown()) k.append('J');
        if (mc.options.keyShift.isDown()) k.append('C');
        if (mc.options.keySprint.isDown()) k.append('R');
        return k.length() == 0 ? "-" : k.toString();
    }

    private static void writeSnapshot(Minecraft mc, LocalPlayer p, Schematic schematic, BuilderController ctl, long stuck) {
        ClientLevel level = mc.level;
        StringBuilder sb = new StringBuilder();
        String line = "=".repeat(100);
        sb.append(line).append('\n');
        sb.append("[").append(now()).append("] [MON/SNAPSHOT] KHÔNG TIẾN TRIỂN ").append(stuck / 1000)
                .append("s (lần cuối có tiến triển: ").append(lastProgressWhat).append(")\n");
        sb.append(statusLine(mc, p, ctl, schematic)).append('\n');
        sb.append(String.format("yaw=%.1f pitch=%.1f  hp=%.1f  creative=%b  noPlanTicks=%d  climbOut=%b  escalationStage=%d%n",
                p.getYRot(), p.getXRot(), p.getHealth(), p.isCreative(), ctl.getNoPlanTicks(), ctl.isClimbOutActive(), stage));

        BlockPos feet = LocalPathfinder.normalizeStart(level, p.blockPosition());
        LocalPathfinder.Reachability r = LocalPathfinder.flood(level, feet, 2000, 20);
        sb.append("feet=").append(p.blockPosition().toShortString()).append(" normalized=").append(feet.toShortString())
                .append(" validStand=").append(LocalPathfinder.isValidStand(level, feet))
                .append(" flood(2000,r20)=").append(r.size())
                .append(" enclosed=").append(BuilderController.isEnclosed(level, p))
                .append(" trappedInPit=").append(BuilderController.isTrappedInPit(level, p)).append('\n');

        // Inventory
        int scaff = 0;
        StringBuilder hotbar = new StringBuilder();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.SCAFFOLDING)) scaff += s.getCount();
            if (i < 9) hotbar.append(i).append(':').append(s.isEmpty() ? "-" : s.getItem().getDescription().getString() + "x" + s.getCount()).append(' ');
        }
        Item support = InventoryHelper.getAvailableSolidSupportItem(p);
        sb.append("scaffolding=").append(scaff).append(" solidSupport=").append(support != null ? support.getDescription().getString() : "NONE")
                .append(" | hotbar: ").append(hotbar).append('\n');

        // Remaining work on the layer + nearest pieces
        int layerY = schematic.getMinY() + BuilderConfig.getInstance().currentLayer;
        int missing = 0;
        BlockPos nearest = null;
        double nd = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> e : schematic.getBlocksAtY(layerY)) {
            BuildRules.BuildStatus st = BuildRules.buildStatus(level.getBlockState(e.getKey()), e.getValue());
            if (st == BuildRules.BuildStatus.MISSING || st == BuildRules.BuildStatus.ADJUST || st == BuildRules.BuildStatus.STACKABLE) {
                missing++;
                double d = e.getKey().distSqr(p.blockPosition());
                if (d < nd) {
                    nd = d;
                    nearest = e.getKey();
                }
            }
        }
        sb.append("layerY=").append(layerY).append(" unfinished=").append(missing)
                .append(" nearestUnfinished=").append(nearest != null ? nearest.toShortString() + " (" + schematic.getBlock(nearest).getBlock().getName().getString() + ", " + String.format("%.1f", Math.sqrt(nd)) + "m)" : "-")
                .append('\n');

        // ASCII map
        sb.append("MAP (bắc ở trên, X tăng sang phải). Kí hiệu: @ bot  # block đúng  x block sai/ngoài bản vẽ  m thiếu block  s giàn giáo\n")
                .append("     ~ nước  L lava  , block mỏng (redstone, repeater...)  + chỗ đứng đi tới được  . trống\n");
        int R = 7;
        BlockPos c = p.blockPosition();
        for (int dy = 2; dy >= -2; dy--) {
            int y = c.getY() + dy;
            sb.append(String.format("  y=%d (%s%d)%s%n", y, dy >= 0 ? "+" : "", dy, dy == 0 ? " <- chân" : (dy == 1 ? " <- đầu" : "")));
            sb.append("      ");
            for (int dx = -R; dx <= R; dx++) sb.append(Math.floorMod(c.getX() + dx, 10));
            sb.append('\n');
            for (int dz = -R; dz <= R; dz++) {
                sb.append(String.format("%5d ", c.getZ() + dz));
                for (int dx = -R; dx <= R; dx++) {
                    BlockPos q = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                    char ch = cell(level, schematic, q, r);
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) ch = '@';
                    sb.append(ch);
                }
                sb.append('\n');
            }
        }

        sb.append("--- ").append(RECENT.size()).append(" sự kiện gần nhất ---\n");
        synchronized (BotMonitor.class) {
            for (String s : RECENT) sb.append(s).append('\n');
        }
        sb.append(line);
        write(sb.toString());
        XlrwDebug.logRaw("[" + now() + "] [MON/SNAPSHOT] Kẹt " + stuck / 1000 + "s - chi tiết trong logs/xlrw_monitor.log");
    }

    private static char cell(ClientLevel level, Schematic schematic, BlockPos q, LocalPathfinder.Reachability r) {
        BlockState w = level.getBlockState(q);
        BlockState exp = schematic.getBlock(q);
        if (w.is(Blocks.SCAFFOLDING)) return 's';
        if (w.is(Blocks.LAVA)) return 'L';
        if (w.is(Blocks.WATER)) return '~';
        if (w.isAir() || w.canBeReplaced()) {
            if (exp != null && !exp.isAir() && !BuildRules.isSkipped(exp) && !BuildRules.isLiquid(exp)) return 'm';
            return r.isReachable(q) && LocalPathfinder.isValidStand(level, q) ? '+' : '.';
        }
        if (w.getCollisionShape(level, q).isEmpty() || w.getShape(level, q).bounds().getYsize() < 0.5) {
            return LocalPathfinder.isValidStand(level, q) && r.isReachable(q) ? '+' : ',';
        }
        if (exp != null && exp.getBlock() == w.getBlock()) return '#';
        return 'x';
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private static synchronized void write(String text) {
        try {
            if (writer == null) {
                Minecraft mc = Minecraft.getInstance();
                File dir = new File(mc != null && mc.gameDirectory != null ? mc.gameDirectory : new File("."), "logs");
                dir.mkdirs();
                File f = new File(dir, "xlrw_monitor.log");
                if (f.exists() && f.length() > 8L * 1024 * 1024) {
                    File old = new File(dir, "xlrw_monitor_old.log");
                    old.delete();
                    f.renameTo(old);
                }
                writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8), true);
                writer.println("=== XLRW MONITOR STARTED " + LocalDateTime.now() + " ===");
            }
            writer.println(text);
            writer.flush();
        } catch (Exception ignored) {
        }
    }
}
