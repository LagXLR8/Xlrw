package com.xlrw.account;

import com.xlrw.config.BuilderConfig;

/**
 * Effective bot tuning: the normal values, or the stronger Plus values while {@link AccountManager#isPlus()}.
 */
public final class Plus {
    private Plus() {}

    public static boolean on() {
        return AccountManager.get().isPlus();
    }

    /** Reach used for placing / planning. Plus + "REACH+" toggle: 5.0 (vanilla servers accept up to ~5.5). */
    public static double reach() {
        BuilderConfig c = BuilderConfig.getInstance();
        if (on() && c.plusReach) return Math.max(c.reachDistance, 5.0);
        return c.reachDistance;
    }

    /** Shortest pause between two normal placements (ms). */
    public static int minActionDelay() {
        return on() ? 45 : 120;
    }

    /** Lowest value the delay setting may be turned down to (ms). */
    public static int minDelaySetting() {
        return on() ? 20 : 50;
    }

    /** Plain blocks placed per tick in fast mode. */
    public static int fastBlocksPerTick() {
        int n = Math.max(1, BuilderConfig.getInstance().fastBlocksPerTick);
        return on() ? Math.max(n, 8) : n;
    }

    public static float aimSpeedFactor() {
        return on() ? 1.75f : 1f;
    }

    // ---- planner budgets ----
    public static int floodNodes() { return on() ? 24000 : 10000; }
    public static int floodRadius() { return on() ? 64 : 40; }
    public static int anchorsTried() { return on() ? 200 : 80; }
    public static int anchorsCompared() { return on() ? 20 : 8; }
    public static int groundCoverCandidates() { return on() ? 12 : 6; }
    public static int towerAnchors() { return on() ? 20 : 10; }
}
