package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class PistonPush extends AliceModule implements GrimCombatCoordinator.Participant {
    public enum ChargeType { Block, Torch, All }
    public enum PistonType { Sticky, Normal, All }
    private enum Step { Support, Piston, Charge }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder().name("target-range").defaultValue(5.0).min(1.5).sliderMax(7.0).build());
    public final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder().name("blocks-per-tick").defaultValue(2).min(1).max(2).build());
    public final Setting<Integer> delayPerPlace = sgGeneral.add(new IntSetting.Builder().name("delay-per-place").defaultValue(0).min(0).max(5).build());
    public final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder().name("rotate").defaultValue(false).build());
    public final Setting<ChargeType> chargeType = sgGeneral.add(new EnumSetting.Builder<ChargeType>().name("charge-type").defaultValue(ChargeType.All).build());
    public final Setting<PistonType> pistonType = sgGeneral.add(new EnumSetting.Builder<PistonType>().name("piston-type").defaultValue(PistonType.All).build());
    public final Setting<Boolean> autoSwap = sgGeneral.add(new BoolSetting.Builder().name("auto-swap").defaultValue(true).build());
    public final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder().name("swing").defaultValue(true).build());

    private PlayerEntity target;
    private Direction pushDirection;
    private Direction chargeFace;
    private BlockPos supportPos;
    private BlockPos pistonPos;
    private BlockPos chargePos;
    private Step step;
    private int delay;
    private boolean completedForTarget;
    private boolean rotationPending;
    private float pendingYaw;
    private float pendingPitch;
    private RotationIntent queuedRotation;
    private PistonRotationInput appliedRotation;
    private boolean placingPacket;
    private boolean placePacketSent;

    public PistonPush() {
        super(AliceAddon.PVP_CATEGORY, "piston-push", "Pushes block-phased enemies out using an obsidian support, piston and redstone.");
    }

    @Override
    public void onActivate() {
        resetState();
        GrimCombatCoordinator.activate(this);
        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        GrimCombatCoordinator.deactivate(this);
        resetState();
        super.onDeactivate();
    }

    @AliceHandler(priority = 100)
    private void onTick(TickEvent.Pre event) {
        if (!hasWorld()) {
            resetState();
            return;
        }

        if (!isPlayerTargetCorrect(target)) {
            clearRotationState();
            clearPlan();
            completedForTarget = false;
            findTarget();
            if (target != null) findPlacementPlan();
            return;
        }

        if (completedForTarget) {
            clearRotationState();
            return;
        }

        if (rotationPending || queuedRotation != null) {
            GrimCombatCoordinator.reserveExternalEpoch();
            return;
        }

        if (step == null) {
            findPlacementPlan();
            return;
        }

        if (step == Step.Charge
            && (chargeFace == null
                || chargePos == null
                || !mc.world.getBlockState(chargePos).isAir())) {
            clearRotationState();
            findChargePlacement();
            if (chargePos == null) return;
        }

        if (delay > 0) {
            delay--;
            return;
        }
    }

    @AliceHandler(priority = 90)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (!rotationPending || queuedRotation == null || appliedRotation != null) {
            return;
        }

        ClientPlayerEntity player = mc.player;
        if (player == null || player.input == null || player.hasVehicle()) {
            clearRotationState();
            return;
        }

        RotationIntent intent = queuedRotation;
        queuedRotation = null;
        appliedRotation = new PistonRotationInput(
            player.input,
            player,
            intent,
            rotate.get()
        );
        player.input = appliedRotation;
    }

    @AliceHandler(priority = -50)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        PistonRotationInput input = appliedRotation;
        if (input == null) return;

        input.restorePlayerRotation(false);
        if (mc.player == input.player && mc.player.input == input) {
            mc.player.input = input.delegate;
        }
        appliedRotation = null;

        // Vanilla may omit look fields when the next step uses the same
        // yaw/pitch already held by the server. The wrapper still kept that
        // look through this movement cycle, so do not wait for a packet that
        // vanilla correctly did not need to send.
        if (rotationPending
            && GrimCombatCoordinator.sameLook(
                pendingYaw,
                pendingPitch,
                input.intent.yaw,
                input.intent.pitch
            )) {
            rotationPending = false;
        }
    }

    /** Serializes support, piston and charge into separate Grim-safe epochs. */
    @Override
    public GrimCombatCoordinator.Intent prepareCombatIntent() {
        if (!hasWorld()
            || mc.currentScreen != null
            || target == null
            || pushDirection == null
            || supportPos == null
            || pistonPos == null
            || step == null
            || delay > 0
            || !isPlayerTargetCorrect(target)) {
            return null;
        }

        BlockPos actionPos = actionPosition();
        if (!isActionPositionReady(actionPos)) {
            clearRotationState();
            return null;
        }

        FindItemResult item = actionItem();
        if (!item.found() || !autoSwap.get() && !item.isMainHand()) {
            clearRotationState();
            return null;
        }

        float[] rotation = actionRotation();
        if (rotation == null) return null;
        if (!serverLookMatches(rotation)) {
            if (rotationPending || queuedRotation != null || appliedRotation != null) {
                return intent(() -> { });
            }
            return intent(() -> queueRotation(rotation));
        }

        return switch (step) {
            case Support -> intent(() -> placeSupport(rotation));
            case Piston -> intent(() -> placePiston(rotation));
            case Charge -> intent(() -> placeCharge(rotation));
        };
    }

    @AliceHandler(priority = -1000)
    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof PlayerInteractBlockC2SPacket) {
            if (placingPacket) placePacketSent = true;
            return;
        }

        if (!(event.packet instanceof PlayerMoveC2SPacket packet)
            || !packet.changesLook()) {
            return;
        }

        if (!rotationPending) return;

        float sentYaw = packet.getYaw(pendingYaw);
        float sentPitch = packet.getPitch(pendingPitch);

        // A user or another rotation owner may have won this movement
        // packet. Either way, the next clean epoch can queue the current
        // action look again if it is still needed.
        if (!GrimCombatCoordinator.sameLook(
            sentYaw,
            sentPitch,
            pendingYaw,
            pendingPitch
        )) {
            queuedRotation = null;
        }
        rotationPending = false;
    }

    @Override
    public void suspendForItemUse() {
        // Keep the current step, but never let a queued silent look survive a
        // visible user action.
        clearRotationState();
    }

    @Override
    public int combatPriority() {
        return 75;
    }

    private GrimCombatCoordinator.Intent intent(Runnable action) {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return PistonPush.this;
            }

            @Override
            public void execute() {
                action.run();
            }
        };
    }

    private void placeSupport(float[] rotation) {
        if (!GrimCombatCoordinator.claimAction(this)
            || step != Step.Support
            || supportPos == null
            || !mc.world.getBlockState(supportPos).isReplaceable()) {
            return;
        }

        BlockPos targetPos = target.getBlockPos();
        BlockHitResult hit = faceHit(targetPos, pushDirection);
        if (place(getSupportSlot(), hit, rotation)) {
            advanceStep(Step.Piston);
            queueNextStepRotation();
        }
    }

    private void placePiston(float[] rotation) {
        if (!GrimCombatCoordinator.claimAction(this)
            || step != Step.Piston
            || pistonPos == null
            || !mc.world.getBlockState(pistonPos).isReplaceable()) {
            return;
        }

        BlockHitResult hit = faceHit(supportPos, Direction.UP);
        // Piston placement faces opposite the player's look direction. Look
        // away from the target so the piston face points inward at the player.
        if (place(getPistonSlot(), hit, rotation)) {
            advanceStep(Step.Charge);
            findChargePlacement();
            queueNextStepRotation();
        }
    }

    private void placeCharge(float[] rotation) {
        if (!GrimCombatCoordinator.claimAction(this)
            || step != Step.Charge
            || chargeFace == null
            || chargePos == null
            || !mc.world.getBlockState(chargePos).isAir()) {
            return;
        }

        BlockHitResult hit = faceHit(pistonPos, chargeFace);
        if (!place(getChargeSlot(), hit, rotation)) {
            return;
        }

        clearPlan();
        completedForTarget = true;
        delay = delayPerPlace.get();
    }

    private boolean place(FindItemResult item, BlockHitResult hit, float[] rotation) {
        if (!selectItem(item)) return false;
        placingPacket = true;
        placePacketSent = false;
        try {
            if (!serverLookMatches(rotation)) return false;
            mc.interactionManager.interactBlock(
                mc.player,
                Hand.MAIN_HAND,
                hit
            );
            if (!placePacketSent) return false;
            if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        } finally {
            placingPacket = false;
            GrimCombatCoordinator.restoreSilent(this);
        }
    }

    private boolean selectItem(FindItemResult item) {
        if (!item.found()) return false;
        if (!autoSwap.get()) return item.isMainHand();
        return GrimCombatCoordinator.selectSilent(this, item.slot());
    }

    private BlockHitResult faceHit(BlockPos blockPos, Direction face) {
        Vec3d center = blockPos.toCenterPos();
        Vec3d hitPos = center.add(
            face.getOffsetX() * 0.5,
            face.getOffsetY() * 0.5,
            face.getOffsetZ() * 0.5
        );
        return new BlockHitResult(hitPos, face, blockPos, false);
    }

    private float[] rotationTo(Vec3d point) {
        Vec3d eye = mc.player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new float[] {
            (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
            (float) (-Math.toDegrees(Math.atan2(dy, horizontal)))
        };
    }

    private float yawFor(Direction direction) {
        return (float) (Math.toDegrees(
            Math.atan2(-direction.getOffsetX(), direction.getOffsetZ())
        ));
    }

    private float[] actionRotation() {
        return switch (step) {
            case Support -> rotationTo(faceHit(target.getBlockPos(), pushDirection).getPos());
            case Piston -> {
                BlockHitResult hit = faceHit(supportPos, Direction.UP);
                float[] aim = rotationTo(hit.getPos());
                yield new float[] {yawFor(pushDirection), aim[1]};
            }
            case Charge -> chargeFace == null
                ? null
                : rotationTo(faceHit(pistonPos, chargeFace).getPos());
        };
    }

    private boolean serverLookMatches(float[] rotation) {
        return rotation != null
            && GrimCombatCoordinator.serverLookKnown()
            && GrimCombatCoordinator.sameLook(
                GrimCombatCoordinator.serverYaw(),
                GrimCombatCoordinator.serverPitch(),
                rotation[0],
                rotation[1]
            );
    }

    private void queueRotation(float[] rotation) {
        if (rotationPending
            || rotation == null
            || !GrimCombatCoordinator.claimAction(this)) {
            return;
        }

        armRotation(rotation);
    }

    private void queueNextStepRotation() {
        if (delay > 0 || step == null) return;
        float[] rotation = actionRotation();
        if (rotation != null) armRotation(rotation);
    }

    private void armRotation(float[] rotation) {
        if (rotationPending || queuedRotation != null || rotation == null) return;

        pendingYaw = rotation[0];
        pendingPitch = rotation[1];
        rotationPending = true;
        queuedRotation = new RotationIntent(pendingYaw, pendingPitch);
    }

    private void clearRotationState() {
        queuedRotation = null;
        rotationPending = false;
        PistonRotationInput input = appliedRotation;
        if (input != null) {
            input.restorePlayerRotation(true);
            if (mc.player == input.player && mc.player.input == input) {
                mc.player.input = input.delegate;
            }
            appliedRotation = null;
        }
    }

    private BlockPos actionPosition() {
        return switch (step) {
            case Support -> supportPos;
            case Piston -> pistonPos;
            case Charge -> chargePos;
        };
    }

    private boolean isActionPositionReady(BlockPos pos) {
        if (pos == null) return false;
        return step == Step.Charge
            ? mc.world.getBlockState(pos).isAir()
            : mc.world.getBlockState(pos).isReplaceable();
    }

    private FindItemResult actionItem() {
        return switch (step) {
            case Support -> getSupportSlot();
            case Piston -> getPistonSlot();
            case Charge -> getChargeSlot();
        };
    }

    private void advanceStep(Step next) {
        step = next;
        if (blocksPerTick.get() == 1) delay = 1;
    }

    private FindItemResult getSupportSlot() {
        return InvUtils.findInHotbar(Items.OBSIDIAN);
    }

    private FindItemResult getPistonSlot() {
        FindItemResult sticky = InvUtils.findInHotbar(Items.STICKY_PISTON);
        FindItemResult normal = InvUtils.findInHotbar(Items.PISTON);
        return switch (pistonType.get()) {
            case Normal -> normal;
            case Sticky -> sticky;
            case All -> normal.found() ? normal : sticky;
        };
    }

    private FindItemResult getChargeSlot() {
        // Redstone block is preferred; torch is the fallback for low-resource setups.
        FindItemResult block = InvUtils.findInHotbar(Items.REDSTONE_BLOCK);
        FindItemResult torch = InvUtils.findInHotbar(Items.REDSTONE_TORCH);
        return switch (chargeType.get()) {
            case Block -> block;
            case Torch -> torch;
            case All -> block.found() ? block : torch;
        };
    }

    private void findPlacementPlan() {
        clearPlan();
        BlockPos targetPos = target.getBlockPos();
        boolean horizontalAirAvailable = false;

        for (Direction direction : new Direction[] {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST
        }) {
            if (mc.world.getBlockState(targetPos.offset(direction)).isAir()) {
                horizontalAirAvailable = true;
                break;
            }
        }
        if (!horizontalAirAvailable) return;

        Direction fallbackDirection = null;
        BlockPos fallbackSupport = null;
        BlockPos fallbackPiston = null;
        Step fallbackStep = null;

        for (Direction direction : new Direction[] {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST
        }) {
            BlockPos candidateSupport = targetPos.offset(direction);
            BlockPos candidatePiston = candidateSupport.up();
            boolean needsSupport = mc.world.getBlockState(candidateSupport).isAir();

            // Use an existing obsidian/bedrock support when present. If the
            // side is air, place the support first. A separate air side is
            // still required so a fully sealed four-sided phase is ignored.
            if ((!needsSupport && !isSupportBlock(candidateSupport))
                || !mc.world.getBlockState(candidatePiston).isReplaceable()) {
                continue;
            }

            if (!withinReach(faceHit(candidateSupport, Direction.UP))) {
                continue;
            }

            Step candidateStep = needsSupport ? Step.Support : Step.Piston;
            if (isVisibleFace(faceHit(candidateSupport, Direction.UP))) {
                setPlacementPlan(
                    direction,
                    candidateSupport,
                    candidatePiston,
                    candidateStep
                );
                return;
            }

            if (fallbackDirection == null) {
                fallbackDirection = direction;
                fallbackSupport = candidateSupport;
                fallbackPiston = candidatePiston;
                fallbackStep = candidateStep;
            }
        }

        if (fallbackDirection != null) {
            setPlacementPlan(
                fallbackDirection,
                fallbackSupport,
                fallbackPiston,
                fallbackStep
            );
        }
    }

    private void setPlacementPlan(
        Direction direction,
        BlockPos support,
        BlockPos piston,
        Step initialStep
    ) {
        pushDirection = direction;
        supportPos = support;
        pistonPos = piston;
        step = initialStep;
    }

    private void findChargePlacement() {
        chargeFace = null;
        chargePos = null;
        if (pistonPos == null || pushDirection == null || !hasWorld()) return;

        Direction faceTowardTarget = pushDirection.getOpposite();
        Direction fallbackFace = null;
        BlockPos fallbackPos = null;

        for (Direction face : new Direction[] {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST
        }) {
            if (face == faceTowardTarget) continue;

            BlockPos candidate = pistonPos.offset(face);
            if (!mc.world.getBlockState(candidate).isAir()) continue;

            BlockHitResult hit = faceHit(pistonPos, face);
            if (!withinReach(hit)) continue;
            if (isVisibleFace(hit)) {
                chargeFace = face;
                chargePos = candidate;
                return;
            }
            if (fallbackPos == null) {
                fallbackFace = face;
                fallbackPos = candidate;
            }
        }

        if (fallbackPos != null) {
            chargeFace = fallbackFace;
            chargePos = fallbackPos;
            return;
        }

        BlockPos top = pistonPos.up();
        if (mc.world.getBlockState(top).isAir()
            && withinReach(faceHit(pistonPos, Direction.UP))) {
            chargeFace = Direction.UP;
            chargePos = top;
        }
    }

    private boolean withinReach(BlockHitResult hit) {
        double reach = mc.player.getBlockInteractionRange();
        return mc.player.getEyePos().squaredDistanceTo(hit.getPos())
            <= reach * reach;
    }

    private boolean isVisibleFace(BlockHitResult hit) {
        Vec3d eye = mc.player.getEyePos();
        if (!withinReach(hit)) return false;

        BlockHitResult raycast = mc.world.raycast(new RaycastContext(
            eye,
            hit.getPos(),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
        return raycast.getType() == HitResult.Type.MISS
            || raycast.getBlockPos().equals(hit.getBlockPos())
                && raycast.getSide() == hit.getSide();
    }

    private boolean isSupportBlock(BlockPos pos) {
        return mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)
            || mc.world.getBlockState(pos).isOf(Blocks.BEDROCK);
    }

    private boolean isPlayerTargetCorrect(PlayerEntity player) {
        if (!hasWorld()
            || player == null
            || player == mc.player
            || player instanceof FakePlayerEntity
            || !player.isAlive()
            || player.isRemoved()
            || player.isCreative()
            || player.isSpectator()
            || !Friends.get().shouldAttack(player)) {
            return false;
        }
        if (mc.player.distanceTo(player) > range.get()) return false;

        BlockPos playerPos = player.getBlockPos();
        return mc.world.getBlockState(playerPos).isOf(Blocks.OBSIDIAN)
            || mc.world.getBlockState(playerPos).isOf(Blocks.BEDROCK);
    }

    private static Vec2f movementFor(PlayerInput input) {
        float sideways = (input.left() ? 1.0f : 0.0f)
            - (input.right() ? 1.0f : 0.0f);
        float forward = (input.forward() ? 1.0f : 0.0f)
            - (input.backward() ? 1.0f : 0.0f);
        return new Vec2f(sideways, forward).normalize();
    }

    private record RotationIntent(float yaw, float pitch) {
    }

    /** Applies one silent look through vanilla movement, then restores input/camera. */
    private final class PistonRotationInput extends Input {
        private final Input delegate;
        private final ClientPlayerEntity player;
        private final RotationIntent intent;
        private final boolean keepCamera;
        private boolean applied;
        private float restoreYaw;
        private float restorePitch;

        private PistonRotationInput(
            Input delegate,
            ClientPlayerEntity player,
            RotationIntent intent,
            boolean keepCamera
        ) {
            this.delegate = delegate;
            this.player = player;
            this.intent = intent;
            this.keepCamera = keepCamera;
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

        private void restorePlayerRotation(boolean force) {
            if (!applied || mc.player != player) return;
            if (force || !keepCamera) {
                player.setYaw(restoreYaw);
                player.setPitch(restorePitch);
            }
            applied = false;
        }

        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }

    private void findTarget() {
        target = null;
        if (!hasWorld()) return;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (isPlayerTargetCorrect(player)) {
                target = player;
                return;
            }
        }
    }

    private boolean hasWorld() {
        return mc.player != null
            && mc.world != null
            && mc.interactionManager != null
            && mc.getNetworkHandler() != null;
    }

    private void clearPlan() {
        pushDirection = null;
        chargeFace = null;
        supportPos = null;
        pistonPos = null;
        chargePos = null;
        step = null;
    }

    private void resetState() {
        target = null;
        clearPlan();
        delay = 0;
        completedForTarget = false;
        clearRotationState();
        pendingYaw = 0.0f;
        pendingPitch = 0.0f;
        placingPacket = false;
        placePacketSent = false;
    }
}
