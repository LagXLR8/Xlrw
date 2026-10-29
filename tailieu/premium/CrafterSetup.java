package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CrafterScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class CrafterSetup extends AliceModule {
    private static final int CRAFTER_SLOT_COUNT = 9;
    private static final int SCREEN_OPEN_TIMEOUT_TICKS = 20;

    private static final Color SETUP_SIDE = new Color(40, 220, 100, 45);
    private static final Color SETUP_LINE = new Color(80, 255, 130, 220);
    private static final Color PENDING_SIDE = new Color(255, 210, 45, 45);
    private static final Color PENDING_LINE = new Color(255, 225, 70, 230);
    private static final int HUD_BACKGROUND = 0xB4141414;
    private static final int HUD_ACCENT = 0xFF55FFAA;
    private static final int HUD_SETUP = 0xFF78FF78;
    private static final int HUD_PENDING = 0xFFFFDC55;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPattern = settings.createGroup("Pattern");
    private final SettingGroup sgHud = settings.createGroup("HUD");
    private final List<Setting<Boolean>> disabledSlots = new ArrayList<>(CRAFTER_SLOT_COUNT);

    private final Setting<Boolean> crafterAura = sgGeneral.add(new BoolSetting.Builder()
        .name("crafter-aura")
        .description("lang le doi huong xoay de dieu chinh crafter ma khong xoay camera")
        .defaultValue(false)
        .build());

    private final Setting<Integer> maxRenderCrafters = sgGeneral.add(new IntSetting.Builder()
        .name("max-render-crafter")
        .description("so luong crafter toi da de hien thi tren ESP, tracer va HUD")
        .defaultValue(64)
        .range(1, 256)
        .sliderRange(1, 128)
        .build());

    private final Setting<Boolean> showHud = sgHud.add(new BoolSetting.Builder()
        .name("show-hud")
        .description("hien thi so luong crafter dang cho")
        .defaultValue(true)
        .build());

    private final Set<Screen> hudRegisteredScreens =
        Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<BlockPos, Long> configuredCrafters = new HashMap<>();
    private final Set<BlockPos> discoveredCrafters = new HashSet<>();

    private List<BlockPos> loadedCrafters = List.of();
    private List<BlockPos> renderedCrafters = List.of();
    private ClientWorld trackedWorld;
    private AuraState auraState = AuraState.Idle;
    private BlockPos auraTarget;
    private boolean pendingPatternApply;
    private boolean closeAfterPatternApply;
    private long clientTick;
    private long screenTimeoutTick = -1L;
    private long closeScreenTick = -1L;
    private Input auraOriginalInput;
    private AuraInput auraInput;
    private AppliedAuraRotation appliedRotation;
    private AuraRotationPlan auraClickRotation;
    private int patternMask;

    public CrafterSetup() {
        super(
            AliceAddon.CATEGORY,
            "CrafterSetup",
            "Applies a saved 3x3 disabled-slot pattern and can configure nearby crafters automatically."
        );

        for (int slot = 0; slot < CRAFTER_SLOT_COUNT; slot++) {
            int displaySlot = slot + 1;
            disabledSlots.add(sgPattern.add(new BoolSetting.Builder()
                .name("disabled-slot-" + displaySlot)
                .description("giu nguyen trang thai vo hieu hoa cho slot " + displaySlot + ".")
                .defaultValue(false)
                .visible(() -> false)
                .build()));
        }

        patternMask = currentPatternMask();
        registerHandledScreenHud();
    }

    @Override
    public void onActivate() {
        super.onActivate();
        resetSession();
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        resetSession();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable root = theme.table();
        root.add(theme.label("Crafter Pattern (checked = disabled)"));
        root.row();

        WTable patternGrid = theme.table();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int slot = row * 3 + column;
                Setting<Boolean> setting = disabledSlots.get(slot);
                WCheckbox checkbox = patternGrid.add(theme.checkbox(setting.get())).widget();
                checkbox.action = () -> setting.set(checkbox.checked);
            }
            patternGrid.row();
        }

        root.add(patternGrid);
        return root;
    }

    @AliceHandler
    public void onOpenScreen(OpenScreenEvent event) {
        if (!isActive() || !(event.screen instanceof CrafterScreen)) return;

        rememberOpenCrafter();
        pendingPatternApply = true;
        closeAfterPatternApply =
            crafterAura.get()
                && auraTarget != null
                && auraState == AuraState.WaitingForScreen;
    }

    @AliceHandler
    public void onTick(TickEvent.Pre event) {
        if (!isActive()) return;
        clientTick++;

        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            resetWorldState();
            return;
        }

        if (trackedWorld != mc.world) {
            resetWorldState();
            trackedWorld = mc.world;
        }

        int nextPatternMask = currentPatternMask();
        if (nextPatternMask != patternMask) {
            patternMask = nextPatternMask;
            configuredCrafters.clear();
            cancelAuraTarget();
        }

        refreshCrafters();

        if (pendingPatternApply
            && mc.player.currentScreenHandler instanceof CrafterScreenHandler handler) {
            applyPattern(handler);
        }

        if (closeScreenTick >= 0L && clientTick >= closeScreenTick) {
            finishAuraScreen();
            return;
        }

        if (!crafterAura.get()) {
            cancelAuraTarget();
            restoreAuraInput();
            return;
        }
        ensureAuraInput();

        if (auraState == AuraState.WaitingForClick) {
            interactAuraTarget();
            return;
        }

        if (auraState == AuraState.WaitingForScreen) {
            if (clientTick >= screenTimeoutTick) cancelAuraTarget();
            return;
        }
        if (auraState == AuraState.WaitingForClose) return;

        if (mc.currentScreen != null) return;

        if (auraTarget == null) {
            auraTarget = findNextReachableCrafter();
            if (auraTarget == null) {
                auraState = AuraState.Idle;
                return;
            }
            auraState = AuraState.Rotating;
        }
    }

    @AliceHandler(priority = 90)
    public void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (!isActive() || !crafterAura.get()) return;
        ensureAuraInput();
    }

    @AliceHandler(priority = -50)
    public void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        AppliedAuraRotation applied = appliedRotation;
        if (applied == null) return;

        restoreAppliedRotation(applied);
        appliedRotation = null;

        if (!isActive()
            || !crafterAura.get()
            || auraState != AuraState.Rotating
            || auraTarget == null
            || !auraTarget.equals(applied.plan.target())) {
            return;
        }

        auraClickRotation = applied.plan;
        auraState = AuraState.WaitingForClick;
    }

    @AliceHandler
    public void onRender3D(Render3DEvent event) {
        if (!isActive() || mc.player == null || mc.world == null) return;

        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Vec3d eye = new Vec3d(
            playerPos.x,
            playerPos.y + mc.player.getEyeHeight(mc.player.getPose()),
            playerPos.z
        );
        if (mc.options.getPerspective().isFirstPerson()) {
            eye = eye.add(mc.player.getRotationVector().multiply(0.45));
        }

        for (BlockPos pos : renderedCrafters) {
            boolean configured = isConfigured(pos);
            event.renderer.box(
                pos,
                configured ? SETUP_SIDE : PENDING_SIDE,
                configured ? SETUP_LINE : PENDING_LINE,
                ShapeMode.Both,
                0
            );

            if (!configured) {
                Vec3d center = Vec3d.ofCenter(pos);
                event.renderer.line(
                    eye.x,
                    eye.y,
                    eye.z,
                    center.x,
                    center.y,
                    center.z,
                    PENDING_LINE
                );
            }
        }
    }

    @AliceHandler
    public void onRender2D(Render2DEvent event) {
        if (mc.currentScreen instanceof HandledScreen<?>) return;
        renderHud(event.drawContext);
    }

    private void applyPattern(CrafterScreenHandler handler) {
        pendingPatternApply = false;

        BlockPos setupPos = resolveOpenCrafterPos(handler);
        for (int slot = 0; slot < CRAFTER_SLOT_COUNT; slot++) {
            boolean disabled = disabledSlots.get(slot).get();
            if (handler.isSlotDisabled(slot) == disabled) continue;

            boolean enabled = SetupModuleLogic.crafterSlotEnabled(disabled);
            handler.setSlotEnabled(slot, enabled);
            mc.interactionManager.slotChangedState(slot, handler.syncId, enabled);
        }

        if (setupPos != null) {
            discoveredCrafters.add(setupPos);
            applyPatternToClientBlockEntity(setupPos);
            configuredCrafters.put(setupPos, clientTick);
        }

        if (closeAfterPatternApply && auraTarget != null) {
            auraState = AuraState.WaitingForClose;
            closeScreenTick = clientTick + 1L;
        }
        closeAfterPatternApply = false;
    }

    private BlockPos resolveOpenCrafterPos(CrafterScreenHandler handler) {
        if (auraTarget != null && auraState == AuraState.WaitingForScreen) {
            return auraTarget;
        }
        if (handler.getInputInventory() instanceof CrafterBlockEntity crafter) {
            return crafter.getPos().toImmutable();
        }
        return null;
    }

    private void applyPatternToClientBlockEntity(BlockPos pos) {
        BlockEntity blockEntity = mc.world.getBlockEntity(pos);
        if (!(blockEntity instanceof CrafterBlockEntity crafter)) return;

        for (int slot = 0; slot < CRAFTER_SLOT_COUNT; slot++) {
            boolean enabled = SetupModuleLogic.crafterSlotEnabled(disabledSlots.get(slot).get());
            crafter.setSlotEnabled(slot, enabled);
        }
    }

    private void finishAuraScreen() {
        closeScreenTick = -1L;
        if (mc.player != null
            && mc.player.currentScreenHandler instanceof CrafterScreenHandler) {
            mc.player.closeHandledScreen();
        }
        cancelAuraTarget();
    }

    private void refreshCrafters() {
        discoverReachableCrafters();

        if (discoveredCrafters.isEmpty() || clientTick % 10L == 1L) {
            discoverLoadedChunkCrafters();
        }

        for (BlockEntity blockEntity : mc.world.getBlockEntities()) {
            if (blockEntity instanceof CrafterBlockEntity) {
                discoveredCrafters.add(blockEntity.getPos().toImmutable());
            }
        }

        discoveredCrafters.removeIf(pos -> !isCrafter(pos));
        configuredCrafters.keySet().removeIf(pos -> !isCrafter(pos));

        List<BlockPos> positions = new ArrayList<>(discoveredCrafters);
        Vec3d eye = mc.player.getEyePos();
        positions.sort(Comparator.comparingDouble(pos ->
            Vec3d.ofCenter(pos).squaredDistanceTo(eye)
        ));
        loadedCrafters = List.copyOf(positions);

        int renderLimit = SetupModuleLogic.limitedRenderCount(
            positions.size(),
            maxRenderCrafters.get()
        );
        renderedCrafters = List.copyOf(positions.subList(0, renderLimit));
    }

    private void discoverLoadedChunkCrafters() {
        int viewDistance = mc.options.getViewDistance().getValue() + 1;
        int centerChunkX = mc.player.getBlockX() >> 4;
        int centerChunkZ = mc.player.getBlockZ() >> 4;

        for (int chunkX = centerChunkX - viewDistance;
             chunkX <= centerChunkX + viewDistance;
             chunkX++) {
            for (int chunkZ = centerChunkZ - viewDistance;
                 chunkZ <= centerChunkZ + viewDistance;
                 chunkZ++) {
                WorldChunk chunk = mc.world.getChunkManager().getChunk(
                    chunkX,
                    chunkZ,
                    ChunkStatus.FULL,
                    false
                );
                if (chunk == null) continue;

                for (Map.Entry<BlockPos, BlockEntity> entry
                    : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (entry.getValue() instanceof CrafterBlockEntity
                        || chunk.getBlockState(pos).isOf(Blocks.CRAFTER)) {
                        discoveredCrafters.add(pos.toImmutable());
                    }
                }
            }
        }
    }

    private void discoverReachableCrafters() {
        Vec3d eye = mc.player.getEyePos();
        int radius = MathHelper.ceil(mc.player.getBlockInteractionRange()) + 1;
        BlockPos center = BlockPos.ofFloored(eye);
        BlockPos min = center.add(-radius, -radius, -radius);
        BlockPos max = center.add(radius, radius, radius);

        for (BlockPos candidate : BlockPos.iterate(min, max)) {
            if (mc.world.getBlockState(candidate).isOf(Blocks.CRAFTER)) {
                discoveredCrafters.add(candidate.toImmutable());
            }
        }
    }

    private void rememberOpenCrafter() {
        if (auraTarget != null && isCrafter(auraTarget)) {
            discoveredCrafters.add(auraTarget.toImmutable());
            return;
        }
        if (mc.crosshairTarget instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK
            && isCrafter(hit.getBlockPos())) {
            discoveredCrafters.add(hit.getBlockPos().toImmutable());
        }
    }

    private void interactAuraTarget() {
        AuraRotationPlan rotation = auraClickRotation;
        auraClickRotation = null;

        if (!isActive()
            || !crafterAura.get()
            || auraState != AuraState.WaitingForClick
            || rotation == null
            || auraTarget == null
            || !auraTarget.equals(rotation.target())
            || mc.player == null
            || mc.interactionManager == null
            || !isCrafter(rotation.target())
            || !withinVanillaReach(rotation.target())) {
            cancelAuraTarget();
            return;
        }

        BlockHitResult hit = raycastAtRotation(
            mc.player.getEyePos(),
            rotation.yaw(),
            rotation.pitch()
        );
        if (hit.getType() != HitResult.Type.BLOCK
            || !hit.getBlockPos().equals(rotation.target())) {
            auraState = AuraState.Rotating;
            return;
        }

        float restoreYaw = mc.player.getYaw();
        float restorePitch = mc.player.getPitch();
        ActionResult result;
        mc.player.setYaw(rotation.yaw());
        mc.player.setPitch(rotation.pitch());
        try {
            result = mc.interactionManager.interactBlock(
                mc.player,
                Hand.MAIN_HAND,
                hit
            );
        } finally {
            mc.player.setYaw(restoreYaw);
            mc.player.setPitch(restorePitch);
        }

        if (!result.isAccepted()) {
            cancelAuraTarget();
            return;
        }

        auraState = AuraState.WaitingForScreen;
        screenTimeoutTick = clientTick + SCREEN_OPEN_TIMEOUT_TICKS;
    }

    private void ensureAuraInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;
        if (auraInput != null && player.input == auraInput) return;

        auraOriginalInput = player.input;
        auraInput = new AuraInput(auraOriginalInput);
        player.input = auraInput;
    }

    private void restoreAuraInput() {
        AppliedAuraRotation applied = appliedRotation;
        if (applied != null) {
            restoreAppliedRotation(applied);
            appliedRotation = null;
        }

        if (mc.player != null
            && auraInput != null
            && mc.player.input == auraInput) {
            mc.player.input = auraOriginalInput;
        }
        auraInput = null;
        auraOriginalInput = null;
    }

    private void applyAuraRotation(
        AuraInput input,
        PlayerInput originalPlayerInput,
        Vec2f originalMovement
    ) {
        ClientPlayerEntity player = mc.player;
        BlockPos target = auraTarget;
        if (player == null
            || target == null
            || appliedRotation != null
            || auraState != AuraState.Rotating
            || !isActive()
            || !crafterAura.get()
            || !isCrafter(target)
            || !withinVanillaReach(target)) {
            return;
        }

        BlockHitResult visibleHit = visibleHit(target);
        if (visibleHit == null) {
            cancelAuraTarget();
            return;
        }

        AuraRotationPlan plan = createAuraRotationPlan(
            target,
            originalPlayerInput,
            originalMovement
        );
        if (plan == null) {
            cancelAuraTarget();
            return;
        }

        boolean sprintingOverridden =
            plan.stopSprinting() && player.isSprinting();
        AppliedAuraRotation applied = new AppliedAuraRotation(
            plan,
            player,
            player.getYaw(),
            player.getPitch(),
            originalPlayerInput,
            originalMovement,
            player.isSprinting(),
            sprintingOverridden
        );
        appliedRotation = applied;

        input.setState(plan.input(), plan.movement());
        if (sprintingOverridden) player.setSprinting(false);
        player.setYaw(plan.yaw());
        player.setPitch(plan.pitch());
    }

    private AuraRotationPlan createAuraRotationPlan(
        BlockPos target,
        PlayerInput originalPlayerInput,
        Vec2f originalMovement
    ) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return null;

        Vec3d eye = player.getEyePos();
        float realYaw = player.getYaw();
        float realPitch = player.getPitch();
        float[] centerAim = aimRotation(eye, Vec3d.ofCenter(target));
        boolean moving = originalMovement != null
            && originalMovement.lengthSquared() > 1.0E-5f;

        AuraRotationPlan best = null;
        double bestScore = Double.MAX_VALUE;
        if (moving) {
            for (InputDirection direction : InputDirection.MOVING) {
                float yaw = SetupModuleLogic.compensatedYaw(
                    realYaw,
                    originalMovement.x,
                    originalMovement.y,
                    direction.sidewaysValue,
                    direction.forwardValue
                );
                float pitch = centerAim[1];
                BlockHitResult hit = raycastAtRotation(eye, yaw, pitch);
                if (hit.getType() != HitResult.Type.BLOCK
                    || !hit.getBlockPos().equals(target)) {
                    continue;
                }
                if ((player.isSprinting() || originalPlayerInput.sprint())
                    && !direction.forward) {
                    continue;
                }

                double score =
                    Math.abs(MathHelper.wrapDegrees(yaw - realYaw))
                        + Math.abs(pitch - realPitch) * 0.2;
                if (direction.matches(originalPlayerInput)) score -= 40.0;
                if (direction.sameMovementClass(originalPlayerInput)) score -= 8.0;
                if (originalPlayerInput.sprint()) {
                    score += direction.forward ? -80.0 : 160.0;
                }

                if (score < bestScore) {
                    bestScore = score;
                    best = new AuraRotationPlan(
                        target,
                        yaw,
                        pitch,
                        direction.apply(originalPlayerInput),
                        direction.movement(),
                        false
                    );
                }
            }
        }
        if (best != null) return best;

        float[] aim = aimRotation(eye, Vec3d.ofCenter(target));
        BlockHitResult aimedHit = raycastAtRotation(eye, aim[0], aim[1]);
        if (aimedHit.getType() != HitResult.Type.BLOCK
            || !aimedHit.getBlockPos().equals(target)) {
            return null;
        }

        return new AuraRotationPlan(
            target,
            aim[0],
            aim[1],
            stationaryInput(originalPlayerInput),
            Vec2f.ZERO,
            true
        );
    }

    private void restoreAppliedRotation(AppliedAuraRotation applied) {
        if (mc.player == applied.player) {
            mc.player.setYaw(applied.restoreYaw);
            mc.player.setPitch(applied.restorePitch);
        }
        if (auraInput != null
            && mc.player != null
            && mc.player.input == auraInput) {
            auraInput.setState(
                applied.restoreInput,
                applied.restoreMovement
            );
        }
        if (applied.sprintingOverridden && mc.player == applied.player) {
            mc.player.setSprinting(applied.restoreSprinting);
        }
    }

    private BlockHitResult raycastAtRotation(
        Vec3d eye,
        float yaw,
        float pitch
    ) {
        Vec3d direction = Vec3d.fromPolar(pitch, yaw);
        Vec3d end = eye.add(
            direction.multiply(mc.player.getBlockInteractionRange())
        );
        return mc.world.raycast(new RaycastContext(
            eye,
            end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
    }

    private static float[] aimRotation(Vec3d eye, Vec3d target) {
        Vec3d delta = target.subtract(eye);
        float yaw = MathHelper.wrapDegrees(
            (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0f
        );
        float pitch = MathHelper.clamp(
            (float) -Math.toDegrees(
                Math.atan2(delta.y, Math.hypot(delta.x, delta.z))
            ),
            -90.0f,
            90.0f
        );
        return new float[]{yaw, pitch};
    }

    private static PlayerInput stationaryInput(PlayerInput input) {
        return new PlayerInput(
            false,
            false,
            false,
            false,
            input.jump(),
            input.sneak(),
            false
        );
    }

    private BlockPos findNextReachableCrafter() {
        for (BlockPos pos : loadedCrafters) {
            if (isConfigured(pos) || !withinVanillaReach(pos)) continue;
            if (visibleHit(pos) != null) return pos;
        }
        return null;
    }

    private boolean withinVanillaReach(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Box box = new Box(pos);
        double nearestX = MathHelper.clamp(eye.x, box.minX, box.maxX);
        double nearestY = MathHelper.clamp(eye.y, box.minY, box.maxY);
        double nearestZ = MathHelper.clamp(eye.z, box.minZ, box.maxZ);
        double reach = mc.player.getBlockInteractionRange();
        return eye.squaredDistanceTo(nearestX, nearestY, nearestZ) <= reach * reach;
    }

    private BlockHitResult visibleHit(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            eye,
            center,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
            return null;
        }
        return hit;
    }

    private boolean isConfigured(BlockPos pos) {
        BlockEntity blockEntity = mc.world == null ? null : mc.world.getBlockEntity(pos);
        if (blockEntity instanceof CrafterBlockEntity crafter && patternMatches(crafter)) {
            configuredCrafters.put(pos, clientTick);
            return true;
        }
        return configuredCrafters.containsKey(pos);
    }

    private boolean patternMatches(CrafterBlockEntity crafter) {
        for (int slot = 0; slot < CRAFTER_SLOT_COUNT; slot++) {
            if (crafter.isSlotDisabled(slot) != disabledSlots.get(slot).get()) return false;
        }
        return true;
    }

    private boolean isCrafter(BlockPos pos) {
        return mc.world != null && mc.world.getBlockState(pos).isOf(Blocks.CRAFTER);
    }

    private int currentPatternMask() {
        boolean[] disabled = new boolean[CRAFTER_SLOT_COUNT];
        for (int slot = 0; slot < CRAFTER_SLOT_COUNT; slot++) {
            disabled[slot] = disabledSlots.get(slot).get();
        }
        return SetupModuleLogic.patternMask(disabled);
    }

    private void cancelAuraTarget() {
        if (appliedRotation != null) {
            restoreAppliedRotation(appliedRotation);
            appliedRotation = null;
        }
        auraState = AuraState.Idle;
        auraTarget = null;
        auraClickRotation = null;
        closeAfterPatternApply = false;
        screenTimeoutTick = -1L;
        closeScreenTick = -1L;
    }

    private void resetSession() {
        restoreAuraInput();
        trackedWorld = mc.world;
        clientTick = 0L;
        patternMask = currentPatternMask();
        configuredCrafters.clear();
        discoveredCrafters.clear();
        loadedCrafters = List.of();
        renderedCrafters = List.of();
        pendingPatternApply = false;
        cancelAuraTarget();
    }

    private void resetWorldState() {
        restoreAuraInput();
        trackedWorld = null;
        configuredCrafters.clear();
        discoveredCrafters.clear();
        loadedCrafters = List.of();
        renderedCrafters = List.of();
        pendingPatternApply = false;
        cancelAuraTarget();
    }

    private void registerHandledScreenHud() {
        ScreenEvents.AFTER_INIT.register(new ScreenEvents.AfterInit() {
            @Override
            public void afterInit(
                MinecraftClient client,
                Screen screen,
                int scaledWidth,
                int scaledHeight
            ) {
                registerHudForScreen(screen, client);
            }
        });
    }

    private void registerHudForScreen(Screen screen, MinecraftClient client) {
        if (!(screen instanceof HandledScreen<?>) || !hudRegisteredScreens.add(screen)) return;

        ScreenEvents.afterRender(screen).register(new ScreenEvents.AfterRender() {
            @Override
            public void afterRender(
                Screen renderedScreen,
                DrawContext context,
                int mouseX,
                int mouseY,
                float tickDelta
            ) {
                if (client.currentScreen == renderedScreen) renderHud(context);
            }
        });
    }

    private void renderHud(DrawContext context) {
        if (!isActive() || !showHud.get() || mc.player == null || context == null) return;

        int setup = 0;
        for (BlockPos pos : renderedCrafters) {
            if (isConfigured(pos)) setup++;
        }
        int pending = SetupModuleLogic.pendingCrafterCount(renderedCrafters.size(), setup);

        String[] lines = {
            "Crafters Setup: " + setup,
            "Crafters Pending: " + pending + " / " + maxRenderCrafters.get()
        };
        int[] colors = {HUD_SETUP, HUD_PENDING};
        int padding = 4;
        int lineGap = 2;
        int lineHeight = mc.textRenderer.fontHeight;
        int width = 0;
        for (String line : lines) width = Math.max(width, mc.textRenderer.getWidth(line));
        width += padding * 2;
        int height = lines.length * lineHeight + lineGap + padding * 2 + 2;
        int x = 10;
        int y = mc.getWindow().getScaledHeight() / 2 - height / 2;

        context.fill(x, y, x + width, y + height, HUD_BACKGROUND);
        context.fill(x, y, x + width, y + 2, HUD_ACCENT);

        int textY = y + padding + 3;
        for (int i = 0; i < lines.length; i++) {
            context.drawTextWithShadow(
                mc.textRenderer,
                lines[i],
                x + padding,
                textY,
                colors[i]
            );
            textY += lineHeight + lineGap;
        }
    }

    private final class AuraInput extends Input {
        private final Input delegate;

        private AuraInput(Input delegate) {
            this.delegate = delegate;
            setState(delegate.playerInput, delegate.getMovementInput());
        }

        @Override
        public void tick() {
            delegate.tick();
            PlayerInput originalPlayerInput = delegate.playerInput;
            Vec2f originalMovement = delegate.getMovementInput();
            setState(originalPlayerInput, originalMovement);
            applyAuraRotation(this, originalPlayerInput, originalMovement);
        }

        @Override
        public void jump() {
            super.jump();
        }

        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }

    private record AuraRotationPlan(
        BlockPos target,
        float yaw,
        float pitch,
        PlayerInput input,
        Vec2f movement,
        boolean stopSprinting
    ) {
    }

    private static final class AppliedAuraRotation {
        private final AuraRotationPlan plan;
        private final ClientPlayerEntity player;
        private final float restoreYaw;
        private final float restorePitch;
        private final PlayerInput restoreInput;
        private final Vec2f restoreMovement;
        private final boolean restoreSprinting;
        private final boolean sprintingOverridden;

        private AppliedAuraRotation(
            AuraRotationPlan plan,
            ClientPlayerEntity player,
            float restoreYaw,
            float restorePitch,
            PlayerInput restoreInput,
            Vec2f restoreMovement,
            boolean restoreSprinting,
            boolean sprintingOverridden
        ) {
            this.plan = plan;
            this.player = player;
            this.restoreYaw = restoreYaw;
            this.restorePitch = restorePitch;
            this.restoreInput = restoreInput;
            this.restoreMovement = restoreMovement;
            this.restoreSprinting = restoreSprinting;
            this.sprintingOverridden = sprintingOverridden;
        }
    }

    private record InputDirection(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        float sidewaysValue,
        float forwardValue
    ) {
        private static final InputDirection[] MOVING = {
            new InputDirection(true, false, false, false, 0.0f, 1.0f),
            new InputDirection(true, false, true, false, 1.0f, 1.0f),
            new InputDirection(false, false, true, false, 1.0f, 0.0f),
            new InputDirection(false, true, true, false, 1.0f, -1.0f),
            new InputDirection(false, true, false, false, 0.0f, -1.0f),
            new InputDirection(false, true, false, true, -1.0f, -1.0f),
            new InputDirection(false, false, false, true, -1.0f, 0.0f),
            new InputDirection(true, false, false, true, -1.0f, 1.0f)
        };

        private Vec2f movement() {
            return new Vec2f(sidewaysValue, forwardValue).normalize();
        }

        private PlayerInput apply(PlayerInput input) {
            return new PlayerInput(
                forward,
                backward,
                left,
                right,
                input.jump(),
                input.sneak(),
                input.sprint()
            );
        }

        private boolean matches(PlayerInput input) {
            return input.forward() == forward
                && input.backward() == backward
                && input.left() == left
                && input.right() == right;
        }

        private boolean sameMovementClass(PlayerInput input) {
            boolean originalForward =
                input.forward() != input.backward();
            boolean originalSideways =
                input.left() != input.right();
            boolean candidateForward = forward != backward;
            boolean candidateSideways = left != right;
            return (originalForward && originalSideways)
                == (candidateForward && candidateSideways);
        }
    }

    private enum AuraState {
        Idle,
        Rotating,
        WaitingForClick,
        WaitingForScreen,
        WaitingForClose
    }
}
