package com.xlrw.debug;

import com.xlrw.XlrwClient;
import com.xlrw.config.BuilderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Large-scale Comprehensive Diagnostic Logging System for XLRW Autonomous Builder.
 * Records precise step-by-step traces of:
 * - Movement and obstacle obstruction (where stuck, collided blocks, collision shapes)
 * - Pathfinding execution (start, goal, nodes expanded, closest reach, failure causes)
 * - Edge detection & fall prevention (coordinates, direction, landing depth, parkour jumps)
 * - Block placement & raycasting (missing materials, line-of-sight blockers, clicked faces)
 * - Scaffolding tower management & horizontal bridging (pillar choices, heights, extensions)
 *
 * All logs are written directly to: logs/xlrw_debug.log with rate-limiting & deduplication.
 */
public class XlrwDebug {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static File logFile = null;
    private static PrintWriter writer = null;
    private static final Map<String, RateLimitEntry> RATE_LIMITS = new ConcurrentHashMap<>();

    private static class RateLimitEntry {
        long lastTime;
        int repeatCount;

        RateLimitEntry(long lastTime) {
            this.lastTime = lastTime;
            this.repeatCount = 0;
        }
    }

    public static synchronized void init() {
        try {
            Minecraft mc = Minecraft.getInstance();
            File gameDir = mc != null && mc.gameDirectory != null ? mc.gameDirectory : new File(".");
            File logsDir = new File(gameDir, "logs");
            if (!logsDir.exists()) logsDir.mkdirs();

            logFile = new File(logsDir, "xlrw_debug.log");

            // Rotate if file exceeds 10MB
            if (logFile.exists() && logFile.length() > 10 * 1024 * 1024) {
                File backup = new File(logsDir, "xlrw_debug_old.log");
                if (backup.exists()) backup.delete();
                logFile.renameTo(backup);
            }

            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8), true);
            logRaw("\n==========================================================================================");
            logRaw("=== XLRW DIAGNOSTIC LOG STARTED [" + LocalDateTime.now() + "] ===");
            logRaw("==========================================================================================");
        } catch (Exception e) {
            XlrwClient.LOGGER.error("[XLRW Debug] Failed to initialize xlrw_debug.log", e);
        }
    }

    public static synchronized void logRaw(String line) {
        if (writer == null) init();
        if (writer != null) {
            writer.println(line);
            writer.flush();
        }
    }

    public static void log(String category, String message) {
        String time = LocalDateTime.now().format(TIME_FMT);
        String formatted = String.format("[%s] [%s] %s", time, category, message);

        logRaw(formatted);
        try { BotMonitor.onEvent(category, formatted); } catch (Throwable ignored) {}
        XlrwClient.LOGGER.info("[XLRW] [{}] {}", category, message);

        BuilderConfig cfg = BuilderConfig.getInstance();
        if (cfg != null && cfg.debugChat) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§e[XLRW/" + category + "] §f" + message), false);
            }
        }
    }

    public static void logRateLimited(String key, long minIntervalMs, String category, String message) {
        long now = System.currentTimeMillis();
        RateLimitEntry entry = RATE_LIMITS.computeIfAbsent(key, k -> new RateLimitEntry(0));

        synchronized (entry) {
            if (now - entry.lastTime >= minIntervalMs) {
                if (entry.repeatCount > 0) {
                    log(category, message + String.format(" [Repeated %d times in last %.1fs]", entry.repeatCount, (now - entry.lastTime) / 1000.0));
                    entry.repeatCount = 0;
                } else {
                    log(category, message);
                }
                entry.lastTime = now;
            } else {
                entry.repeatCount++;
            }
        }
    }

    // ============================================================================================
    // Movement & Obstacle Logging
    // ============================================================================================

    public static void logMoveObstacle(String action, BlockPos pos, String detail) {
        String key = "OBSTACLE_" + action + "_" + (pos != null ? pos.asLong() : 0);
        String msg = String.format("[%s] tại (%s) | %s", action, pos != null ? pos.toShortString() : "NONE", detail);
        logRateLimited(key, 800, "MOVE/OBSTACLE", msg);
    }

    public static void logMoveBlocked(Vec3 playerPos, BlockPos obstacle, BlockState state, String reason) {
        String key = "MOVE_BLOCKED_" + obstacle.asLong();
        String msg = String.format("Blocked at (%d, %d, %d) [%s] | Player at (%.2f, %.2f, %.2f) | Reason: %s",
                obstacle.getX(), obstacle.getY(), obstacle.getZ(),
                state != null ? state.getBlock().getName().getString() : "UNKNOWN",
                playerPos.x, playerPos.y, playerPos.z,
                reason);
        logRateLimited(key, 1200, "MOVE/BLOCKED", msg);
    }

    public static void logMoveStuck(Vec3 playerPos, Vec3 waypoint, int stuckTicks, String inputDetail, String terrainDetail) {
        String key = "MOVE_STUCK_" + BlockPos.containing(playerPos).asLong();
        String msg = String.format("Stuck (%d ticks) at (%.2f, %.2f, %.2f) -> Waypoint (%.2f, %.2f, %.2f) | Keys: %s | Terrain: %s",
                stuckTicks, playerPos.x, playerPos.y, playerPos.z,
                waypoint != null ? waypoint.x : 0, waypoint != null ? waypoint.y : 0, waypoint != null ? waypoint.z : 0,
                inputDetail, terrainDetail);
        logRateLimited(key, 1000, "MOVE/STUCK", msg);
    }

    public static void logEdgeStop(Vec3 playerPos, BlockPos footPos, BlockPos stepPos, Direction dir, String landingStatus, String action) {
        String key = "EDGE_STOP_" + footPos.asLong() + "_" + dir.name();
        String msg = String.format("Mép vực phát hiện! Chân tại (%d, %d, %d) | Bước tới (%d, %d, %d) [Hướng %s] | Điểm tiếp đất: %s | Hành động: %s",
                footPos.getX(), footPos.getY(), footPos.getZ(),
                stepPos.getX(), stepPos.getY(), stepPos.getZ(),
                dir.getName(), landingStatus, action);
        logRateLimited(key, 800, "MOVE/EDGE", msg);
    }

    public static void logParkourJump(Vec3 playerPos, BlockPos gapPos, BlockPos landingPos, String detail) {
        log("MOVE/PARKOUR", String.format("Bật nhảy Parkour qua khe hở (%d, %d, %d) -> Chỗ đáp (%d, %d, %d) | %s",
                gapPos.getX(), gapPos.getY(), gapPos.getZ(),
                landingPos.getX(), landingPos.getY(), landingPos.getZ(),
                detail));
    }

    // ============================================================================================
    // Pathfinding Logging
    // ============================================================================================

    public static void logPathSuccess(BlockPos start, BlockPos goal, int pathLength, int nodesExpanded) {
        logRateLimited("PATH_OK_" + goal.asLong(), 5000, "PATH/OK",
                String.format("Tìm thấy đường: (%d, %d, %d) -> (%d, %d, %d) | Độ dài: %d bước (Duyệt %d nodes)",
                        start.getX(), start.getY(), start.getZ(),
                        goal.getX(), goal.getY(), goal.getZ(),
                        pathLength, nodesExpanded));
    }

    public static void logPathFail(BlockPos start, BlockPos goal, int nodesExpanded, BlockPos closestNode, String reason) {
        String key = "PATH_FAIL_" + start.asLong() + "_" + goal.asLong();
        double distToGoal = closestNode != null ? Math.sqrt(closestNode.distSqr(goal)) : -1;
        String msg = String.format("KHÔNG tìm thấy đường từ (%d, %d, %d) đến (%d, %d, %d)! Duyệt %d nodes. Điểm gần nhất tới được: %s (còn cách %.1fm) | Nguyên nhân: %s",
                start.getX(), start.getY(), start.getZ(),
                goal.getX(), goal.getY(), goal.getZ(),
                nodesExpanded,
                closestNode != null ? closestNode.toShortString() : "NONE",
                distToGoal, reason);
        logRateLimited(key, 1500, "PATH/FAIL", msg);
    }

    // ============================================================================================
    // Block Placement & Raycast Logging
    // ============================================================================================

    public static void logBuildAttempt(BlockPos targetPos, BlockState expected, BlockPos supportPos, Direction clickFace, Vec3 hitVec, double dist) {
        log("BUILD/TRY", String.format("Đặt block [%s] tại (%d, %d, %d) | Support=(%d, %d, %d), Mặt=%s, Hit=(%.2f, %.2f, %.2f), Khoảng cách=%.2fm",
                expected.getBlock().getName().getString(),
                targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                supportPos.getX(), supportPos.getY(), supportPos.getZ(),
                clickFace.getName(), hitVec.x, hitVec.y, hitVec.z, dist));
    }

    public static void logBuildAttempt(BlockPos targetPos, String blockName, String detail) {
        log("BUILD/TRY", String.format("Thử đặt [%s] tại (%s) | %s",
                blockName, targetPos.toShortString(), detail));
    }

    public static void logBuildReject(BlockPos targetPos, BlockState expected, String cause, String blockerDetail) {
        logBuildReject(targetPos, expected != null ? expected.getBlock().getName().getString() : "block", cause, blockerDetail);
    }

    public static void logBuildReject(BlockPos targetPos, String blockName, String cause, String blockerDetail) {
        String key = "BUILD_REJECT_" + targetPos.asLong() + "_" + cause;
        String msg = String.format("Không thể đặt block (%d, %d, %d) [%s] | Lý do: %s | Chi tiết: %s",
                targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                blockName, cause, blockerDetail);
        logRateLimited(key, 1200, "BUILD/REJECT", msg);
    }

    public static void logBuildReject(BlockPos targetPos, String blockName, String reason) {
        logBuildReject(targetPos, blockName, reason, "");
    }

    public static void logBuildSuccess(BlockPos targetPos, BlockState placed) {
        logBuildSuccess(targetPos, placed != null ? placed.getBlock().getName().getString() : "block", "");
    }

    public static void logBuildSuccess(BlockPos targetPos, String blockName, String detail) {
        log("BUILD/OK", String.format("ĐÃ ĐẶT THÀNH CÔNG: (%s) -> [%s] %s",
                targetPos.toShortString(), blockName, detail));
    }

    // ============================================================================================
    // Scaffolding & Tower Logging
    // ============================================================================================

    public static void logTowerSelect(BlockPos pillarBase, int neededTopY, String reason) {
        log("TOWER/SELECT", String.format("Chọn vị trí trụ Scaffolding tại (%d, %d, %d), cần cao tới Y=%d | Lý do: %s",
                pillarBase.getX(), pillarBase.getY(), pillarBase.getZ(),
                neededTopY, reason));
    }

    public static void logTowerAction(String action, BlockPos pillarBase, int currentTopY, int neededTopY, String detail) {
        String key = "TOWER_ACT_" + action + "_" + pillarBase.asLong();
        String msg = String.format("[%s] Trụ (%d, %d, %d) | Chiều cao hiện tại Y=%d / Cần Y=%d | %s",
                action, pillarBase.getX(), pillarBase.getY(), pillarBase.getZ(),
                currentTopY, neededTopY, detail);
        logRateLimited(key, 1000, "TOWER/" + action, msg);
    }

    public static void logTowerAction(String action, BlockPos pillarBase, String detail) {
        logTowerAction(action, pillarBase, -1, -1, detail);
    }

    public static void logScaffoldBridge(BlockPos floorPos, Direction dir, BlockPos targetPos, String stepKind, String detail) {
        String key = "BRIDGE_" + floorPos.asLong() + "_" + (dir != null ? dir.name() : "NONE");
        String msg = String.format("Bắc cầu Scaffolding từ (%d, %d, %d) hướng %s -> Đích (%d, %d, %d) | Thao tác: %s | %s",
                floorPos.getX(), floorPos.getY(), floorPos.getZ(),
                dir != null ? dir.getName() : "NONE", targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                stepKind, detail);
        logRateLimited(key, 800, "SCAFFOLD/BRIDGE", msg);
    }

    public static void logScaffoldBridge(String stepKind, BlockPos floorPos, BlockPos targetPos, String detail) {
        String key = "BRIDGE_" + stepKind + "_" + floorPos.asLong();
        String msg = String.format("[%s] Scaffolding từ (%s) -> Đích (%s) | %s",
                stepKind, floorPos.toShortString(), targetPos != null ? targetPos.toShortString() : "NONE", detail);
        logRateLimited(key, 800, "SCAFFOLD/BRIDGE", msg);
    }

    public static void logScaffoldFail(String action, BlockPos pos, String reason) {
        String key = "SCAFFOLD_FAIL_" + action + "_" + pos.asLong();
        String msg = String.format("Thất bại khi [%s] tại (%d, %d, %d) | Lý do: %s",
                action, pos.getX(), pos.getY(), pos.getZ(), reason);
        logRateLimited(key, 1200, "SCAFFOLD/FAIL", msg);
    }

    // ============================================================================================
    // Station & State Logging
    // ============================================================================================

    public static void logStationPlan(BlockPos anchor, BlockState expected, BlockPos station, String details) {
        log("STATION/PLAN", String.format("Cam kết Plan: Mục tiêu (%d, %d, %d) [%s] <--- Đứng tại (%d, %d, %d) | %s",
                anchor.getX(), anchor.getY(), anchor.getZ(),
                expected.getBlock().getName().getString(),
                station.getX(), station.getY(), station.getZ(),
                details));
    }

    public static void logStationFail(BlockPos anchor, String reason) {
        String key = "STATION_FAIL_" + (anchor != null ? anchor.asLong() : 0);
        String msg = String.format("Plan thất bại với Anchor %s | Lý do: %s",
                anchor != null ? anchor.toShortString() : "NONE", reason);
        logRateLimited(key, 1500, "STATION/FAIL", msg);
    }

    public static void logState(String oldState, String newState, String cause) {
        log("STATE", String.format("Chuyển trạng thái: %s -> %s | Nguyên nhân: %s", oldState, newState, cause));
    }

    public static void logState(String transition, String cause) {
        log("STATE", String.format("Trạng thái: %s | %s", transition, cause));
    }
}
