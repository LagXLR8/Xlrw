package com.xlrw.builder;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Remembers recent actions per position so the printer never spams the same click:
 * - pending: an action was just sent, wait for the server to confirm the block update
 * - attempts: repeated failures lead to a temporary blacklist
 * - permanent skip: positions that failed over and over are skipped for this session
 */
public class TaskTracker {
    private static final TaskTracker INSTANCE = new TaskTracker();

    private static final int MAX_ATTEMPTS = 6;
    private static final int MAX_BLACKLISTS = 4;
    private static final long BLACKLIST_DURATION_MS = 8_000;

    private final Map<BlockPos, Long> pendingUntil = new HashMap<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Long> blacklistUntil = new HashMap<>();
    private final Map<BlockPos, Integer> blacklistCount = new HashMap<>();
    private final Set<BlockPos> permanentSkip = new HashSet<>();

    public static TaskTracker getInstance() {
        return INSTANCE;
    }

    /** True if the position must not be acted on right now. */
    public boolean isBlocked(BlockPos pos) {
        if (permanentSkip.contains(pos)) return true;
        long now = System.currentTimeMillis();
        Long p = pendingUntil.get(pos);
        if (p != null) {
            if (now < p) return true;
            pendingUntil.remove(pos);
        }
        Long b = blacklistUntil.get(pos);
        if (b != null) {
            if (now < b) return true;
            blacklistUntil.remove(pos);
        }
        return false;
    }

    public boolean isPermanentlySkipped(BlockPos pos) {
        return permanentSkip.contains(pos);
    }

    /** Registers an action; the position is locked for {@code waitMs} while the server responds. */
    public void onAction(BlockPos pos, long waitMs) {
        BlockPos key = pos.immutable();
        pendingUntil.put(key, System.currentTimeMillis() + Math.max(400, waitMs));
        int n = attempts.merge(key, 1, Integer::sum);
        if (n > 3) {
            attempts.remove(key);
            blacklist(key, BLACKLIST_DURATION_MS);
        }
    }

    public void onSuccess(BlockPos pos) {
        if (pos == null) return;
        BlockPos key = pos.immutable();
        attempts.remove(key);
        pendingUntil.remove(key);
    }

    public void blacklist(BlockPos pos, long ms) {
        BlockPos key = pos.immutable();
        int c = blacklistCount.merge(key, 1, Integer::sum);
        // Exponential backoff duration up to 45 seconds max instead of permanent blocking
        long backoff = Math.min(45_000, ms * (1L << Math.min(c, 3)));
        blacklistUntil.put(key, System.currentTimeMillis() + backoff);
        if (c >= 8) {
            permanentSkip.add(key);
        }
    }

    public void unskip(BlockPos pos) {
        if (pos == null) return;
        BlockPos key = pos.immutable();
        permanentSkip.remove(key);
        blacklistUntil.remove(key);
        blacklistCount.remove(key);
        attempts.remove(key);
        pendingUntil.remove(key);
    }

    public void clearPermanentSkips() {
        permanentSkip.clear();
        blacklistCount.clear();
    }

    public int getAttempts(BlockPos pos) {
        if (pos == null) return 0;
        return attempts.getOrDefault(pos.immutable(), 0);
    }

    public Map<BlockPos, Integer> getAttempts() {
        return attempts;
    }

    public int getPermanentSkipCount() {
        return permanentSkip.size();
    }

    public void clearPending() {
        pendingUntil.clear();
    }

    public void clear() {
        pendingUntil.clear();
        attempts.clear();
        blacklistUntil.clear();
        blacklistCount.clear();
        permanentSkip.clear();
    }
}
