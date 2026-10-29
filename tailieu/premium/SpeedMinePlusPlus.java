package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Single-target packet mining ordered for Grim 2.3.74-d54b49b.
 *
 * <p>The first break uses a full START/STOP cycle. By default, authoritative
 * replacements are rebroken with an aggressive STOP-only packet; users can
 * disable instant rebreak to repeat the full cycle instead. Silent rotations
 * keep the server ray on the target without moving the visible camera.</p>
 */
public final class SpeedMinePlusPlus extends AliceModule
    implements GrimCombatCoordinator.Participant {
    private static final int MINE_PRIORITY = 200;
    private static final int ACK_TIMEOUT_TICKS = 60;
    private static final int FADE_TICKS = 10;
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long START_INTERVAL_NANOS =
        TimeUnit.MILLISECONDS.toNanos(300);
    private static final int MAX_RENDER_PULSES = 8;
    private static final MethodHandle SEQUENCED_PACKET_SENDER =
        findSequencedPacketSender();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("ESP");

    private final Setting<AutoSwapMode> autoSwap = sgGeneral.add(
        new EnumSetting.Builder<AutoSwapMode>()
            .name("auto-swap")
            .description("Silent uses the fastest suitable hotbar tool on the server.")
            .defaultValue(AutoSwapMode.Silent)
            .build()
    );

    private final Setting<Boolean> rebreak = sgGeneral.add(
        new BoolSetting.Builder()
            .name("rebreak")
            .description("Automatically mines the target again after the server replaces it.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> instantRebreak = sgGeneral.add(
        new BoolSetting.Builder()
            .name("instant-rebreak")
            .description("Sends STOP immediately when the server replaces the target. This can flag FastBreak.")
            .defaultValue(true)
            .visible(rebreak::get)
            .build()
    );

    private final Setting<Boolean> esp = sgRender.add(
        new BoolSetting.Builder()
            .name("esp")
            .description("Renders the active mining target.")
            .defaultValue(true)
            .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(
        new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("Controls which parts of the target box are rendered.")
            .defaultValue(ShapeMode.Both)
            .visible(esp::get)
            .build()
    );

    private final Setting<Boolean> fade = sgRender.add(
        new BoolSetting.Builder()
            .name("fade")
            .description("Fades completed target boxes instead of removing them immediately.")
            .defaultValue(true)
            .visible(esp::get)
            .build()
    );

    private final Setting<Integer> fadeTime = sgRender.add(
        new IntSetting.Builder()
            .name("fade-time")
            .description("Fade duration in ticks.")
            .defaultValue(FADE_TICKS)
            .range(1, 40)
            .sliderRange(1, 20)
            .visible(() -> esp.get() && fade.get())
            .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(
        new ColorSetting.Builder()
            .name("side-color")
            .description("Target box side color.")
            .defaultValue(new SettingColor(189, 153, 255, 32))
            .visible(() -> esp.get() && shapeMode.get() != ShapeMode.Lines)
            .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(
        new ColorSetting.Builder()
            .name("line-color")
            .description("Target box outline color.")
            .defaultValue(new SettingColor(189, 153, 255, 193))
            .visible(() -> esp.get() && shapeMode.get() != ShapeMode.Sides)
            .build()
    );

    private final GrimLookGate lookGate = new GrimLookGate();

    private BlockPos targetPos;
    private Direction targetFace;
    private BlockPos abortPos;
    private Phase phase = Phase.Idle;
    private int clientTick;
    private int actionTick;
    private boolean serverAirConfirmed;
    private long mineReadyAtNanos;
    private long nextStartAtNanos;

    private final ArrayDeque<RenderPulse> renderPulses = new ArrayDeque<>();

    private RotationIntent queuedRotation;
    private MiningRotationInput rotationInput;
    private float sentYaw;
    private float sentPitch;
    private boolean sentLookKnown;

    public SpeedMinePlusPlus() {
        super(
            AliceAddon.PVP_CATEGORY,
            "speed-mine++",
            "Automatic Grim-safe packet mining with silent tool and look control."
        );
    }

    @Override
    public void onActivate() {
        resetState();
        GrimCombatCoordinator.activate(this);
        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        restoreRotationInput();
        abortOpenBreak();
        GrimCombatCoordinator.deactivate(this);
        resetState();
        super.onDeactivate();
    }

    @AliceHandler(priority = 500)
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (!canTarget(event.blockPos)) return;
        event.cancel();

        BlockPos requested = event.blockPos.toImmutable();
        Direction requestedFace = event.direction == null
            ? choosePlausibleFace(requested)
            : event.direction;
        if (requested.equals(targetPos)) {
            if (phase == Phase.Armed
                && serverAirConfirmed
                && !mc.world.getBlockState(requested).isAir()) {
                armReplacement(requested);
            }
            return;
        }

        queueAbortIfOpen();
        addFade(targetPos);
        targetPos = requested;
        targetFace = requestedFace == null ? Direction.DOWN : requestedFace;
        phase = Phase.NeedsStart;
        actionTick = clientTick;
        serverAirConfirmed = false;
        mineReadyAtNanos = 0L;
        lookGate.invalidate();
        queuedRotation = null;
    }

    @AliceHandler(priority = 100)
    private void onTickPre(TickEvent.Pre event) {
        clientTick++;
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            resetState();
            return;
        }

        pruneRenderPulses();
        if (targetPos == null) return;
        if (!rebreak.get()
            && (phase == Phase.Armed || phase == Phase.InstantRebreak)) {
            clearTarget(false);
            return;
        }
        if (!instantRebreak.get() && phase == Phase.InstantRebreak) {
            phase = Phase.NeedsStart;
        }
        if (autoSwap.get() == AutoSwapMode.None
            && GrimCombatCoordinator.hasSilentSlot(this)) {
            GrimCombatCoordinator.restoreSilent(this);
        }
        if (!canKeepTarget(targetPos)) {
            clearTarget(true);
            return;
        }
        if (phase == Phase.AwaitingAir
            && clientTick - actionTick > ACK_TIMEOUT_TICKS) {
            clearTarget(false);
        }
    }

    @AliceHandler(priority = 100)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        applyQueuedRotationInput();
    }

    @AliceHandler(priority = -200)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        restoreRotationInput();
        lookGate.onMovementPacketsPost();
    }

    @AliceHandler(priority = -1000)
    private void onPacketSent(PacketEvent.Sent event) {
        if (!(event.packet instanceof PlayerMoveC2SPacket packet)
            || !packet.changesLook()) {
            return;
        }

        sentYaw = packet.getYaw(sentYaw);
        sentPitch = packet.getPitch(sentPitch);
        sentLookKnown = true;
        if (rotationInput != null && rotationInput.applied) {
            GrimCombatCoordinator.observeOwnedLook(this, sentYaw, sentPitch);
        }
    }

    @AliceHandler(priority = -1000)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof BlockUpdateS2CPacket update) {
            observeServerBlock(update.getPos(), update.getState());
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket update) {
            update.visitUpdates(this::observeServerBlock);
        }
    }

    @AliceHandler
    private void onRender3D(Render3DEvent event) {
        if (!esp.get() || mc.world == null) return;

        BlockPos active = targetPos;
        if (active != null && !mc.world.getBlockState(active).isAir()) {
            drawBox(event, active, 1.0);
        }

        if (!fade.get()) return;
        for (RenderPulse pulse : renderPulses) {
            double alpha = fadeAlpha(pulse, event.tickDelta);
            if (alpha <= 0.0) continue;
            double progress = 1.0 - alpha;
            drawBox(
                event,
                new Box(pulse.pos).expand(progress * 0.12),
                alpha
            );
        }
    }

    @Override
    public GrimCombatCoordinator.Intent prepareCombatIntent() {
        restoreRotationInput();
        queuedRotation = null;

        if (mc.player == null
            || mc.world == null
            || mc.interactionManager == null
            || mc.getNetworkHandler() == null) {
            return null;
        }

        if (abortPos != null) {
            BlockPos plannedPos = abortPos;
            return intent(() -> runAbort(plannedPos));
        }
        if (targetPos == null || isPaused()) {
            GrimCombatCoordinator.restoreSilent(this);
            lookGate.invalidate();
            return null;
        }

        BlockPos plannedPos = targetPos;
        BlockState state = mc.world.getBlockState(plannedPos);
        if (state.isAir()) return null;
        if (state.getHardness(mc.world, plannedPos) < 0.0f) {
            clearTarget(false);
            return null;
        }

        Direction plannedFace = choosePlausibleFace(plannedPos);
        if (plannedFace == null) plannedFace = targetFace;
        if (plannedFace == null) plannedFace = Direction.DOWN;

        if (phase == Phase.NeedsStart) {
            if (System.nanoTime() < nextStartAtNanos) return null;
            Direction finalFace = plannedFace;
            return intent(() -> runStart(plannedPos, finalFace));
        }
        if (phase == Phase.InstantRebreak) {
            Direction finalFace = plannedFace;
            return intent(() -> runInstantRebreak(plannedPos, finalFace));
        }
        if (phase == Phase.Mining
            && System.nanoTime() >= mineReadyAtNanos) {
            Direction finalFace = plannedFace;
            return intent(() -> runFinish(plannedPos, finalFace));
        }
        return null;
    }

    @Override
    public void suspendForItemUse() {
        GrimCombatCoordinator.restoreSilent(this);
        restoreRotationInput();
        queuedRotation = null;
        lookGate.invalidate();
    }

    @Override
    public int combatPriority() {
        return MINE_PRIORITY;
    }

    private GrimCombatCoordinator.Intent intent(Runnable action) {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return SpeedMinePlusPlus.this;
            }

            @Override
            public void execute() {
                action.run();
            }
        };
    }

    private void runStart(BlockPos plannedPos, Direction plannedFace) {
        if (phase != Phase.NeedsStart
            || !plannedPos.equals(targetPos)
            || !canKeepTarget(plannedPos)
            || System.nanoTime() < nextStartAtNanos) {
            return;
        }

        BlockState state = mc.world.getBlockState(plannedPos);
        if (state.isAir() || state.getHardness(mc.world, plannedPos) < 0.0f) return;
        if (!holdAndGateBlock(plannedPos, plannedFace)) return;
        if (!GrimCombatCoordinator.claimAction(this)) return;

        int slot = toolSlot(state);
        double delta = BlockUtils.getBreakDelta(slot, state);
        if (delta <= 0.0 || !Double.isFinite(delta)
            || !sendMiningAction(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                plannedPos,
                plannedFace,
                slot,
                autoSwap.get() == AutoSwapMode.Silent && delta < 1.0
            )) {
            return;
        }

        targetFace = plannedFace;
        actionTick = clientTick;
        serverAirConfirmed = false;
        if (delta >= 1.0) {
            mineReadyAtNanos = 0L;
            phase = Phase.AwaitingAir;
        } else {
            mineReadyAtNanos = System.nanoTime() + miningDurationNanos(delta);
            phase = Phase.Mining;
        }
    }

    private void runFinish(BlockPos plannedPos, Direction plannedFace) {
        if (phase != Phase.Mining
            || !plannedPos.equals(targetPos)
            || !canKeepTarget(plannedPos)
            || System.nanoTime() < mineReadyAtNanos) {
            return;
        }

        BlockState state = mc.world.getBlockState(plannedPos);
        if (state.isAir() || state.getHardness(mc.world, plannedPos) < 0.0f) return;
        int slot = toolSlot(state);
        double delta = BlockUtils.getBreakDelta(slot, state);
        if (delta <= 0.0 || !Double.isFinite(delta)) return;
        if (!holdAndGateBlock(plannedPos, plannedFace)) return;
        if (!GrimCombatCoordinator.claimAction(this)) return;

        if (!sendMiningAction(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            plannedPos,
            plannedFace,
            slot,
            false
        )) {
            return;
        }

        long now = System.nanoTime();
        nextStartAtNanos = Math.max(
            nextStartAtNanos,
            now + START_INTERVAL_NANOS
        );
        targetFace = plannedFace;
        serverAirConfirmed = false;
        mineReadyAtNanos = 0L;
        actionTick = clientTick;
        phase = Phase.AwaitingAir;
    }

    private void runInstantRebreak(
        BlockPos plannedPos,
        Direction plannedFace
    ) {
        if (phase != Phase.InstantRebreak
            || !plannedPos.equals(targetPos)
            || !canKeepTarget(plannedPos)) {
            return;
        }

        BlockState state = mc.world.getBlockState(plannedPos);
        if (state.isAir() || state.getHardness(mc.world, plannedPos) < 0.0f) return;
        int slot = toolSlot(state);
        // Select the server-side tool before waiting for the silent look gate.
        // Otherwise the first instant STOP can be scheduled before the pickaxe
        // slot reaches the server, which makes the swap appear several blocks late.
        if (!GrimCombatCoordinator.selectSilent(this, slot)) return;
        if (!holdAndGateBlock(plannedPos, plannedFace)) return;
        if (!GrimCombatCoordinator.claimAction(this)) return;

        if (!sendMiningAction(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            plannedPos,
            plannedFace,
            slot,
            false
        )) {
            return;
        }

        targetFace = plannedFace;
        serverAirConfirmed = false;
        mineReadyAtNanos = 0L;
        actionTick = clientTick;
        phase = Phase.AwaitingAir;
    }

    private void runAbort(BlockPos plannedPos) {
        if (abortPos == null
            || !abortPos.equals(plannedPos)
            || !GrimCombatCoordinator.claimAction(this)) {
            return;
        }
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
            plannedPos,
            Direction.DOWN
        ));
        GrimCombatCoordinator.restoreSilent(this);
        if (plannedPos.equals(abortPos)) abortPos = null;
    }

    private boolean sendMiningAction(
        PlayerActionC2SPacket.Action action,
        BlockPos pos,
        Direction face,
        int slot,
        boolean keepSilentSlot
    ) {
        if (!GrimCombatCoordinator.selectSilent(this, slot)) return false;

        boolean sent = false;
        try {
            mc.player.swingHand(Hand.MAIN_HAND);
            sent = sendSequencedAction(action, pos, face);
            return sent;
        } finally {
            if (!sent || !keepSilentSlot) {
                GrimCombatCoordinator.restoreSilent(this);
            }
        }
    }

    private boolean sendSequencedAction(
        PlayerActionC2SPacket.Action action,
        BlockPos pos,
        Direction face
    ) {
        if (SEQUENCED_PACKET_SENDER == null) return false;
        SequencedPacketCreator creator = sequence -> new PlayerActionC2SPacket(
            action,
            pos,
            face == null ? Direction.DOWN : face,
            sequence
        );
        try {
            SEQUENCED_PACKET_SENDER.invoke(
                mc.interactionManager,
                mc.world,
                creator
            );
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void observeServerBlock(BlockPos pos, BlockState state) {
        if (targetPos == null || pos == null || !targetPos.equals(pos)) return;

        if (state.isAir()) {
            GrimCombatCoordinator.restoreSilent(this);
            serverAirConfirmed = true;
            mineReadyAtNanos = 0L;
            addFade(pos);
            lookGate.invalidate();
            queuedRotation = null;
            if (!rebreak.get()) {
                clearTarget(false);
                return;
            }
            phase = Phase.Armed;
            return;
        }

        if (!serverAirConfirmed || phase != Phase.Armed) return;
        if (state.getHardness(mc.world, pos) < 0.0f) {
            clearTarget(false);
            return;
        }
        armReplacement(pos);
    }

    private void armReplacement(BlockPos pos) {
        serverAirConfirmed = false;
        mineReadyAtNanos = 0L;
        actionTick = clientTick;
        Direction plausible = choosePlausibleFace(pos);
        if (plausible != null) targetFace = plausible;
        phase = instantRebreak.get()
            ? Phase.InstantRebreak
            : Phase.NeedsStart;
        lookGate.invalidate();
        queuedRotation = null;
    }

    private int toolSlot(BlockState state) {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (autoSwap.get() == AutoSwapMode.None) return selected;
        FindItemResult fastest = InvUtils.findFastestTool(state);
        return fastest.found() && fastest.slot() >= 0 && fastest.slot() < 9
            ? fastest.slot()
            : selected;
    }

    private boolean canTarget(BlockPos pos) {
        return mc.player != null
            && mc.world != null
            && mc.interactionManager != null
            && mc.getNetworkHandler() != null
            && !mc.player.isCreative()
            && !mc.player.isSpectator()
            && BlockUtils.canBreak(pos, mc.world.getBlockState(pos))
            && canKeepTarget(pos);
    }

    private boolean canKeepTarget(BlockPos pos) {
        return mc.player != null
            && mc.world != null
            && pos != null
            && mc.player.canInteractWithBlockAt(pos, 0.0);
    }

    private boolean isPaused() {
        return mc.currentScreen != null
            || mc.player.isUsingItem()
            || mc.options.useKey.isPressed();
    }

    private Direction choosePlausibleFace(BlockPos pos) {
        return mc.player == null
            ? targetFace
            : CrystalAuraPlusPlus.choosePlausibleFace(pos, mc.player.getEyePos());
    }

    private boolean holdAndGateBlock(BlockPos pos, Direction face) {
        if (sentLookHitsBlock(pos)) {
            holdSentLook();
            lookGate.invalidate();
            return true;
        }

        queueRotation(pos);
        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(pos),
            face,
            pos,
            false
        );
        if (!lookGate.readyFor(hit)) return false;

        boolean valid = sentLookHitsBlock(pos);
        if (!valid) lookGate.invalidate();
        return valid;
    }

    private boolean sentLookHitsBlock(BlockPos pos) {
        return sentLookKnown
            && canKeepTarget(pos)
            && CrystalAuraPlusPlus.rayIntersectsBox(
                mc.player.getEyePos(),
                sentYaw,
                sentPitch,
                mc.player.getBlockInteractionRange(),
                new Box(pos)
            );
    }

    private void queueRotation(BlockPos pos) {
        Vec3d direction = Vec3d.ofCenter(pos).subtract(mc.player.getEyePos());
        double horizontal = Math.sqrt(
            direction.x * direction.x + direction.z * direction.z
        );
        float yaw = (float) (Math.toDegrees(
            Math.atan2(direction.z, direction.x)
        ) - 90.0);
        float pitch = (float) -Math.toDegrees(
            Math.atan2(direction.y, horizontal)
        );
        queuedRotation = new RotationIntent(yaw, pitch);
    }

    private void holdSentLook() {
        queuedRotation = new RotationIntent(sentYaw, sentPitch);
    }

    private void applyQueuedRotationInput() {
        RotationIntent intent = queuedRotation;
        queuedRotation = null;
        ClientPlayerEntity player = mc.player;
        if (intent == null
            || player == null
            || player.hasVehicle()
            || player.input == null) {
            return;
        }

        restoreRotationInput();
        rotationInput = new MiningRotationInput(player.input, player, intent);
        player.input = rotationInput;
    }

    private void restoreRotationInput() {
        MiningRotationInput input = rotationInput;
        if (input == null) return;

        input.restorePlayerRotation();
        if (mc.player == input.player && mc.player.input == input) {
            mc.player.input = input.delegate;
        }
        rotationInput = null;
    }

    private void queueAbortIfOpen() {
        if (targetPos == null || phase != Phase.Mining) return;
        abortPos = targetPos;
    }

    private void clearTarget(boolean abortOpen) {
        if (abortOpen) queueAbortIfOpen();
        addFade(targetPos);
        GrimCombatCoordinator.restoreSilent(this);
        restoreRotationInput();
        targetPos = null;
        targetFace = null;
        phase = Phase.Idle;
        actionTick = 0;
        serverAirConfirmed = false;
        mineReadyAtNanos = 0L;
        queuedRotation = null;
        lookGate.invalidate();
    }

    private void abortOpenBreak() {
        if (targetPos == null
            || phase != Phase.Mining
            || mc.getNetworkHandler() == null) {
            return;
        }
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
            targetPos,
            Direction.DOWN
        ));
        GrimCombatCoordinator.restoreSilent(this);
    }

    private void drawBox(Render3DEvent event, BlockPos pos, double alpha) {
        drawBox(event, new Box(pos), alpha);
    }

    private void drawBox(Render3DEvent event, Box box, double alpha) {
        event.renderer.box(
            box,
            colorWithAlpha(sideColor.get(), alpha),
            colorWithAlpha(lineColor.get(), alpha),
            shapeMode.get(),
            0
        );
    }

    private void addFade(BlockPos pos) {
        if (!fade.get() || pos == null) return;
        RenderPulse latest = renderPulses.peekLast();
        if (latest != null
            && latest.startedTick == clientTick
            && latest.pos.equals(pos)) {
            return;
        }
        while (renderPulses.size() >= MAX_RENDER_PULSES) {
            renderPulses.removeFirst();
        }
        renderPulses.addLast(new RenderPulse(pos.toImmutable(), clientTick));
    }

    private void pruneRenderPulses() {
        if (!fade.get()) {
            renderPulses.clear();
            return;
        }
        renderPulses.removeIf(
            pulse -> clientTick - pulse.startedTick > fadeTime.get()
        );
    }

    private double fadeAlpha(RenderPulse pulse, float tickDelta) {
        double age = Math.max(0.0, clientTick - pulse.startedTick + tickDelta);
        return MathHelper.clamp(1.0 - age / fadeTime.get(), 0.0, 1.0);
    }

    private static Color colorWithAlpha(Color color, double alpha) {
        if (alpha >= 0.999) return color;
        return new Color(
            color.r,
            color.g,
            color.b,
            MathHelper.clamp((int) Math.round(color.a * alpha), 0, 255)
        );
    }

    static long miningDurationNanos(double breakDelta) {
        if (!(breakDelta > 0.0) || !Double.isFinite(breakDelta)) {
            return Long.MAX_VALUE;
        }
        double ticks = Math.ceil(1.0 / breakDelta) + 1.0;
        if (ticks >= Long.MAX_VALUE / (double) TICK_NANOS) {
            return Long.MAX_VALUE;
        }
        return Math.max(TICK_NANOS, (long) ticks * TICK_NANOS);
    }

    private void resetState() {
        restoreRotationInput();
        targetPos = null;
        targetFace = null;
        abortPos = null;
        phase = Phase.Idle;
        clientTick = 0;
        actionTick = 0;
        serverAirConfirmed = false;
        mineReadyAtNanos = 0L;
        nextStartAtNanos = 0L;
        renderPulses.clear();
        queuedRotation = null;
        sentYaw = 0.0f;
        sentPitch = 0.0f;
        sentLookKnown = false;
        lookGate.invalidate();
    }

    private static MethodHandle findSequencedPacketSender() {
        for (Method method : ClientPlayerInteractionManager.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() != void.class
                || parameters.length != 2
                || parameters[0] != ClientWorld.class
                || parameters[1] != SequencedPacketCreator.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                return MethodHandles.lookup().unreflect(method);
            } catch (IllegalAccessException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Vec2f movementFor(PlayerInput input) {
        float sideways = (input.left() ? 1.0f : 0.0f)
            - (input.right() ? 1.0f : 0.0f);
        float forward = (input.forward() ? 1.0f : 0.0f)
            - (input.backward() ? 1.0f : 0.0f);
        return new Vec2f(sideways, forward).normalize();
    }

    private enum Phase {
        Idle,
        NeedsStart,
        Mining,
        AwaitingAir,
        Armed,
        InstantRebreak
    }

    private enum AutoSwapMode {
        None,
        Silent
    }

    private record RotationIntent(float yaw, float pitch) {
    }

    private record RenderPulse(BlockPos pos, int startedTick) {
    }

    private final class MiningRotationInput extends Input {
        private final Input delegate;
        private final ClientPlayerEntity player;
        private final RotationIntent intent;
        private boolean applied;
        private float restoreYaw;
        private float restorePitch;

        private MiningRotationInput(
            Input delegate,
            ClientPlayerEntity player,
            RotationIntent intent
        ) {
            this.delegate = delegate;
            this.player = player;
            this.intent = intent;
            setState(delegate.playerInput, delegate.getMovementInput());
        }

        @Override
        public void tick() {
            delegate.tick();
            PlayerInput original = delegate.playerInput == null
                ? PlayerInput.DEFAULT
                : delegate.playerInput;
            Vec2f originalMovement = delegate.getMovementInput();
            setState(original, originalMovement);
            if (mc.player != player || mc.player.input != this) return;

            restoreYaw = player.getYaw();
            restorePitch = player.getPitch();
            PlayerInput corrected = CrystalAuraPlusPlus.remapPlayerInput(
                original,
                originalMovement,
                restoreYaw,
                intent.yaw
            );
            setState(corrected, movementFor(corrected));
            player.setYaw(intent.yaw);
            player.setPitch(intent.pitch);
            applied = true;
        }

        private void restorePlayerRotation() {
            if (!applied || mc.player != player) return;
            player.setYaw(restoreYaw);
            player.setPitch(restorePitch);
            applied = false;
        }

        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }
}
