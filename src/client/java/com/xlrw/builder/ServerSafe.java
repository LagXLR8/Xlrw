package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.debug.XlrwDebug;
import com.xlrw.mixin.LocalPlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Everything the bot needs so that block placements are actually KEPT by a multiplayer server.
 *
 * <p>Why blocks "appeared and then vanished" on servers:
 * <ol>
 *   <li>{@code useItemOn} places the block on the client immediately (client prediction). The old code read the
 *       client world right after the click and counted the block as "confirmed" - but that was only the prediction.
 *       When the server (or its anti-cheat) refused the click, it sent the old block back ~1 ping later and the block
 *       disappeared, while the bot had already moved on. Now a placement only counts once the server has
 *       acknowledged that exact click ({@code ClientboundBlockChangedAckPacket}) and the block is still there.</li>
 *   <li>The bot sent an extra rotation packet for every click (often several per tick, sometimes without even
 *       turning the client camera). Anti-cheats (Grim, Vulcan, NCP...) see too many movement packets / impossible
 *       rotations, set the player back, and every click sent while the server waits for the setback is ignored.
 *       In server-safe mode the bot only turns the real camera; vanilla sends the rotation in its normal movement
 *       packet, and the click is sent only once the server already knows that rotation.</li>
 *   <li>Through-wall, air-place, rotation-spoof and multi-block-per-tick clicks are accepted by a bare vanilla
 *       server but rejected by anti-cheats. Server-safe mode turns them off (the user's own settings are kept and
 *       come back when server-safe is switched off).</li>
 * </ol>
 */
public final class ServerSafe {
    private ServerSafe() {}

    // ---------------------------------------------------------------- server acknowledgements (filled by mixins)

    private static WeakReference<ClientLevel> ackLevel = new WeakReference<>(null);
    private static volatile int lastAckSeq = -1;
    private static volatile long lastSetbackAt = 0;

    /** Called from {@code ClientLevelMixin} when the server acknowledges block changes up to {@code seq}. */
    public static void onBlockAck(ClientLevel level, int seq) {
        if (ackLevel.get() != level) {
            ackLevel = new WeakReference<>(level);
            lastAckSeq = -1;
        }
        if (seq > lastAckSeq) lastAckSeq = seq;
    }

    /** Called from {@code ClientPacketListenerMixin} whenever the server teleports / sets back the player. */
    public static void onServerSetback() {
        lastSetbackAt = System.currentTimeMillis();
    }

    /** Sequence number of the last block interaction the client sent (0 if none yet in this world). */
    public static int lastSentSequence(Minecraft mc) {
        if (mc.level == null) return -1;
        return ((com.xlrw.mixin.ClientLevelAccessor) mc.level).xlrw$getPredictionHandler().currentSequence();
    }

    /** Has the server answered the click that was sent with {@code seq}? */
    public static boolean isAcked(ClientLevel level, int seq) {
        if (seq <= 0) return true; // nothing was sent with a sequence: nothing to wait for
        return ackLevel.get() == level && lastAckSeq >= seq;
    }

    public static boolean recentSetback(long ms) {
        return System.currentTimeMillis() - lastSetbackAt < ms;
    }

    public static int pingMs(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return 100;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info != null ? Math.max(0, info.getLatency()) : 100;
    }

    /** How long to wait for the server to answer a click before giving up on it. */
    public static long ackTimeoutMs(Minecraft mc) {
        return Mth.clamp(pingMs(mc) * 2L + 450L, 600L, 4000L);
    }

    // ---------------------------------------------------------------- mode

    public static boolean enabled() {
        return BuilderConfig.getInstance().serverSafe;
    }

    // ---------------------------------------------------------------- rotation

    /**
     * Point the player at (yaw, pitch) for a click.
     * <ul>
     *   <li>Server-safe: turns the real camera (no extra packet) and returns true only when the server already knows
     *       this exact rotation, i.e. vanilla sent it in the last movement packet. Callers must simply wait (try again
     *       next tick) while this returns false.</li>
     *   <li>Legacy: old behaviour - turn and send a rotation packet right away, always returns true.</li>
     * </ul>
     */
    public static boolean faceForClick(LocalPlayer p, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90f, 90f);
        if (!enabled()) {
            p.setYRot(yaw);
            p.setXRot(pitch);
            p.connection.send(com.xlrw.compat.MC.rot(yaw, pitch, p.onGround()));
            return true;
        }
        // Keep the yaw continuous with the current one (no 360 jumps), so "synced" compares the real value sent
        float cur = p.getYRot();
        float y = cur + Mth.wrapDegrees(yaw - cur);
        if (Math.abs(y - cur) > 1.0e-4f || Math.abs(pitch - p.getXRot()) > 1.0e-4f) {
            p.setYRot(y);
            p.setXRot(pitch);
            p.yHeadRot = y;
            p.yBodyRot = y;
        }
        return rotationSynced(p, y, pitch);
    }

    /** True when the last movement packet sent to the server carried exactly this rotation. */
    public static boolean rotationSynced(LocalPlayer p, float yaw, float pitch) {
        LocalPlayerAccessor a = (LocalPlayerAccessor) p;
        return Math.abs(Mth.wrapDegrees(a.xlrw$getYRotLast() - yaw)) < 0.01f
                && Math.abs(a.xlrw$getXRotLast() - pitch) < 0.01f
                && Math.abs(Mth.wrapDegrees(p.getYRot() - yaw)) < 0.01f
                && Math.abs(p.getXRot() - pitch) < 0.01f;
    }

    /** Server-safe replacement for "send a rotation packet with the current camera": nothing to do, vanilla sends it. */
    public static void syncCurrentRotation(LocalPlayer p) {
        if (!enabled()) {
            p.connection.send(com.xlrw.compat.MC.rot(p.getYRot(), p.getXRot(), p.onGround()));
        }
    }

    /** True when the server has been told about the current sneak state {@code sneaking}. */
    public static boolean sneakSynced(LocalPlayer p, boolean sneaking) {
        //? if >=1.21.11 {
        /*net.minecraft.world.entity.player.Input sent = ((LocalPlayerAccessor) p).xlrw$getLastSentInput();
        return (sent != null && sent.shift()) == sneaking;
        *///?} else {
        return ((LocalPlayerAccessor) p).xlrw$getWasShiftKeyDown() == sneaking;
        //?}
    }

    /** May the bot click right now (no pending setback, standing still)? */
    public static boolean readyToAct(LocalPlayer p) {
        if (!enabled()) return true;
        if (recentSetback(350)) return false;
        var v = p.getDeltaMovement();
        return v.x * v.x + v.z * v.z < 0.0025; // < 0.05 blocks/tick sideways
    }

    // ---------------------------------------------------------------- adaptive speed

    private static int extraDelayMs = 0;
    private static int rejectStreak = 0;
    private static long pauseUntil = 0;

    public static void onAccepted() {
        rejectStreak = 0;
        extraDelayMs = Math.max(0, extraDelayMs - 15);
    }

    /** A click the server refused (or rolled back later). Slows the bot down until clicks are accepted again. */
    public static void onRejected(BlockPos pos, String what, String why) {
        rejectStreak++;
        extraDelayMs = Math.min(450, extraDelayMs + 70);
        XlrwDebug.log("SERVER/REJECT", "Server từ chối " + what + " tại " + (pos != null ? pos.toShortString() : "-")
                + " (" + why + ") | streak=" + rejectStreak + " | chậm thêm " + extraDelayMs + "ms");
        if (rejectStreak >= 5) {
            pauseUntil = System.currentTimeMillis() + 1500;
            rejectStreak = 0;
            XlrwDebug.log("SERVER/PAUSE", "5 lần liên tiếp bị server từ chối -> nghỉ 1.5s");
        }
    }

    public static int extraDelayMs() {
        return extraDelayMs;
    }

    public static boolean paused() {
        return System.currentTimeMillis() < pauseUntil;
    }

    // ---------------------------------------------------------------- late rollback watch

    private record Watch(BlockPos pos, Block block, long until) {}
    private static final ArrayDeque<Watch> watches = new ArrayDeque<>();

    /** Watch a confirmed placement for a few seconds: some plugins / anti-cheats revert blocks a bit later. */
    public static void watch(BlockPos pos, BlockState expected) {
        if (pos == null || expected == null) return;
        watches.addLast(new Watch(pos.immutable(), expected.getBlock(), System.currentTimeMillis() + 3000));
        while (watches.size() > 256) watches.pollFirst();
    }

    public static void tickWatches(ClientLevel level) {
        if (level == null || watches.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Watch> it = watches.iterator();
        while (it.hasNext()) {
            Watch w = it.next();
            if (!level.isLoaded(w.pos())) { it.remove(); continue; }
            BlockState s = level.getBlockState(w.pos());
            if (s.getBlock() != w.block() && (s.isAir() || s.canBeReplaced())) {
                it.remove();
                onRejected(w.pos(), w.block().getName().getString(), "block bị server xoá lại sau khi đặt");
                // the cell is empty again -> the finder will see it as missing and place it once more
                TaskTracker.getInstance().onSuccess(w.pos());
                continue;
            }
            if (now > w.until()) it.remove();
        }
    }

    public static void reset() {
        watches.clear();
        extraDelayMs = 0;
        rejectStreak = 0;
        pauseUntil = 0;
    }
}
