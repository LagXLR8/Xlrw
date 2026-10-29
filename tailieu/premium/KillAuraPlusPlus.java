package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-cooldown player aura designed against Grim 2.3.74-d54b49b.
 *
 * <p>This is an independent Meteor/Minecraft implementation. ThunderHack's
 * archived 1.21 Aura was reviewed for feature vocabulary only; its packet
 * switching, same-tick restore and resolver paths are deliberately not used.
 * Reach is checked against the exact outgoing look and the current entity
 * hitbox. Grim mode omits world occlusion because the audited Reach/Hitboxes
 * checks do not raycast blocks, but it never expands the numerical reach.
 * The user explicitly selected Thunder-like parallel scheduling: raw Crystal
 * Aura attacks and crystal swing packets do not reset this module's client
 * cooldown view, even though the server may consequently apply reduced melee
 * damage. Interaction packets remain serialized by GrimCombatCoordinator.</p>
 */
public final class KillAuraPlusPlus extends AliceModule
    implements GrimCombatCoordinator.Participant {
    private static final double RAY_EPSILON = 1.0E-4;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgPause = settings.createGroup("Pause");

    private final Setting<WeaponSwapMode> weaponSwap = sgGeneral.add(
        new EnumSetting.Builder<WeaponSwapMode>()
            .name("weapon-swap")
            .description("none: danh bang do dang cam, pick: cam do ngon nhat, silent: chi doi slot tren server")
            .defaultValue(WeaponSwapMode.None)
            .build()
    );

    private final Setting<RaycastMode> raycastMode = sgGeneral.add(
        new EnumSetting.Builder<RaycastMode>()
            .name("raycast-mode")
            .description("grim chi yeu cau tia chieu vao hitbox cua thuc: the. visible con yeu cau tia khong bi chan boi the gioi")
            .defaultValue(RaycastMode.Grim)
            .build()
    );

    private final Setting<Double> attackRange = sgTargeting.add(
        new DoubleSetting.Builder()
            .name("attack-range")
            .description("gioi han tam danh cuc bo ap dung cung voi he thong cua minecraft 1.21.11, khong bu tru")
            .defaultValue(3.0)
            .range(1.0, 6.0)
            .sliderRange(1.0, 6.0)
            .build()
    );

    private final Setting<TargetMode> targetMode = sgTargeting.add(
        new EnumSetting.Builder<TargetMode>()
            .name("target-mode")
            .description("chon mot che do uu tien danh muc tieu nhat dinh")
            .defaultValue(TargetMode.LowestHealth)
            .build()
    );

    private final Setting<Boolean> lockTarget = sgTargeting.add(
        new BoolSetting.Builder()
            .name("lock-target")
            .description("Keeps the selected player until it is no longer valid.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Double> yawStep = sgRotation.add(
        new DoubleSetting.Builder()
            .name("yaw-step")
            .description("goc yaw thay doi toi da tren server moi chu ky")
            .defaultValue(180.0)
            .range(1.0, 180.0)
            .sliderRange(1.0, 180.0)
            .build()
    );

    private final Setting<Double> pitchStep = sgRotation.add(
        new DoubleSetting.Builder()
            .name("pitch-step")
            .description("goc pitch thay doi toi da tren server moi chu ky")
            .defaultValue(90.0)
            .range(1.0, 90.0)
            .sliderRange(1.0, 90.0)
            .build()
    );

    private final Setting<Boolean> pauseOnUse = sgPause.add(
        new BoolSetting.Builder()
            .name("pause-on-use")
            .description("tam dung khi dang dung item de tranh xung dot packet")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> pauseOnMine = sgPause.add(
        new BoolSetting.Builder()
            .name("pause-on-mine")
            .description("tam dung khi giu nut danh de tranh xung dot")
            .defaultValue(true)
            .build()
    );

    private final GrimLookGate lookGate = new GrimLookGate();

    private PlayerEntity target;
    private float sentYaw;
    private float sentPitch;
    private boolean sentLookKnown;
    private RotationIntent queuedRotation;
    private AuraRotationInput rotationInput;

    public KillAuraPlusPlus() {
        super(
            AliceAddon.PVP_CATEGORY,
            "kill-aura++",
            "Thunder-like client-cooldown aura with Grim-safe reach, rotation and Crystal Aura++ coordination."
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
        GrimCombatCoordinator.deactivate(this);
        resetState();
        super.onDeactivate();
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

    @Override
    public GrimCombatCoordinator.Intent prepareCombatIntent() {
        restoreRotationInput();
        queuedRotation = null;

        if (weaponSwap.get() != WeaponSwapMode.Silent
            && GrimCombatCoordinator.hasSilentSlot(this)) {
            return cleanupIntent();
        }
        if (!canRun()) {
            clearTarget();
            return GrimCombatCoordinator.hasSilentSlot(this)
                ? cleanupIntent()
                : null;
        }

        target = selectTarget();
        if (target == null) {
            lookGate.invalidate();
            return GrimCombatCoordinator.hasSilentSlot(this)
                ? cleanupIntent()
                : null;
        }

        int desiredSlot = desiredWeaponSlot(target);
        if (needsSlotPreparation(desiredSlot)) return slotIntent(desiredSlot);

        // ThunderHack Aura reads the client's own attack timer while its
        // AutoCrystal sends raw entity-attack packets. Keep that deliberate
        // divergence here: Crystal Aura can run in parallel without resetting
        // this visible cooldown, although the server may deal reduced damage.
        if (thunderCooldownProgress() + 1.0E-4f < 1.0f) {
            lookGate.invalidate();
            return null;
        }

        Vec3d aim = target.getBoundingBox().getCenter();
        double[] rotation = rotationTo(aim);
        return attackIntent(target, desiredSlot, rotation[0], rotation[1]);
    }

    @Override
    public void suspendForItemUse() {
        restoreRotationInput();
        queuedRotation = null;
        lookGate.invalidate();
    }

    private GrimCombatCoordinator.Intent slotIntent(int slot) {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return KillAuraPlusPlus.this;
            }

            @Override
            public void execute() {
                prepareSlot(slot);
            }
        };
    }

    private GrimCombatCoordinator.Intent attackIntent(
        PlayerEntity plannedTarget,
        int slot,
        double yaw,
        double pitch
    ) {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return KillAuraPlusPlus.this;
            }

            @Override
            public void execute() {
                runAttack(plannedTarget, slot, yaw, pitch);
            }
        };
    }

    private GrimCombatCoordinator.Intent cleanupIntent() {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return KillAuraPlusPlus.this;
            }

            @Override
            public void execute() {
                GrimCombatCoordinator.restoreSilent(KillAuraPlusPlus.this);
            }
        };
    }

    private void runAttack(
        PlayerEntity plannedTarget,
        int slot,
        double yaw,
        double pitch
    ) {
        if (target != plannedTarget
            || !isValidTarget(plannedTarget)
            || needsSlotPreparation(slot)
            || thunderCooldownProgress() + 1.0E-4f < 1.0f) {
            lookGate.invalidate();
            return;
        }
        if (!holdAndGateTarget(plannedTarget, yaw, pitch)) return;
        if (!GrimCombatCoordinator.claimAction(this)) return;

        mc.interactionManager.attackEntity(mc.player, plannedTarget);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean canRun() {
        if (mc.player == null
            || mc.world == null
            || mc.interactionManager == null
            || mc.getNetworkHandler() == null
            || !mc.player.isAlive()
            || mc.player.isCreative()
            || mc.player.isSpectator()
            || mc.player.hasVehicle()
            || mc.player.isGliding()
            || mc.player.getAbilities().flying) {
            return false;
        }
        if (pauseOnUse.get()
            && (mc.player.isUsingItem()
                || mc.currentScreen == null && mc.options.useKey.isPressed())) {
            return false;
        }
        return !pauseOnMine.get()
            || mc.currentScreen != null
            || !mc.options.attackKey.isPressed();
    }

    private PlayerEntity selectTarget() {
        if (lockTarget.get() && isValidTarget(target)) return target;

        List<PlayerEntity> candidates = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (isValidTarget(player)) candidates.add(player);
        }
        candidates.sort(this::compareTargets);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private boolean isValidTarget(PlayerEntity player) {
        if (player == null
            || player == mc.player
            || !player.isAlive()
            || player.isRemoved()
            || player.isCreative()
            || player.isSpectator()
            || !Friends.get().shouldAttack(player)) {
            return false;
        }
        if (player instanceof FakePlayerEntity fakePlayer && fakePlayer.noHit) {
            return false;
        }

        Box box = player.getBoundingBox();
        if (!mc.player.canAttackEntityIn(box, 0.0)) {
            return false;
        }
        double range = effectiveRange();
        return squaredDistanceToBox(mc.player.getEyePos(), box) <= range * range;
    }

    private int compareTargets(PlayerEntity first, PlayerEntity second) {
        int comparison = switch (targetMode.get()) {
            case LowestHealth -> Float.compare(totalHealth(first), totalHealth(second));
            case ClosestDistance -> Double.compare(targetDistance(first), targetDistance(second));
            case ClosestAngle -> Double.compare(targetAngle(first), targetAngle(second));
            case LowestArmor -> Integer.compare(first.getArmor(), second.getArmor());
            case HighestArmor -> Integer.compare(second.getArmor(), first.getArmor());
        };
        if (comparison != 0) return comparison;

        comparison = Double.compare(targetDistance(first), targetDistance(second));
        return comparison != 0 ? comparison : Integer.compare(first.getId(), second.getId());
    }

    private double targetDistance(PlayerEntity player) {
        return squaredDistanceToBox(mc.player.getEyePos(), player.getBoundingBox());
    }

    private double targetAngle(PlayerEntity player) {
        double[] rotation = rotationTo(player.getBoundingBox().getCenter());
        float baseYaw = sentLookKnown
            ? sentYaw
            : GrimCombatCoordinator.serverLookKnown()
                ? GrimCombatCoordinator.serverYaw()
                : mc.player.getYaw();
        float basePitch = sentLookKnown
            ? sentPitch
            : GrimCombatCoordinator.serverLookKnown()
                ? GrimCombatCoordinator.serverPitch()
                : mc.player.getPitch();
        double yaw = MathHelper.wrapDegrees(rotation[0] - baseYaw);
        double pitch = rotation[1] - basePitch;
        return yaw * yaw + pitch * pitch;
    }

    private int desiredWeaponSlot(PlayerEntity plannedTarget) {
        if (weaponSwap.get() == WeaponSwapMode.None) {
            return mc.player.getInventory().getSelectedSlot();
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        int bestSlot = selected;
        float bestDamage = DamageUtils.getAttackDamage(
            mc.player,
            plannedTarget,
            mc.player.getInventory().getStack(selected)
        );
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            float damage = DamageUtils.getAttackDamage(mc.player, plannedTarget, stack);
            if (damage > bestDamage + 1.0E-4f) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private boolean needsSlotPreparation(int slot) {
        if (slot < 0 || slot > 8) return true;
        int selected = mc.player.getInventory().getSelectedSlot();
        return switch (weaponSwap.get()) {
            case None -> GrimCombatCoordinator.serverSlot() != selected;
            case Pick -> selected != slot || GrimCombatCoordinator.serverSlot() != slot;
            case Silent -> GrimCombatCoordinator.serverSlot() != slot;
        };
    }

    private void prepareSlot(int slot) {
        if (slot < 0 || slot > 8 || mc.player == null) return;
        int selected = mc.player.getInventory().getSelectedSlot();
        switch (weaponSwap.get()) {
            case None -> GrimCombatCoordinator.selectSilent(this, selected);
            case Pick -> {
                if (selected != slot) InvUtils.swap(slot, false);
                else GrimCombatCoordinator.selectSilent(this, slot);
            }
            case Silent -> GrimCombatCoordinator.selectSilent(this, slot);
        }
        lookGate.invalidate();
    }

    private boolean holdAndGateTarget(
        PlayerEntity plannedTarget,
        double yaw,
        double pitch
    ) {
        if (sentLookHitsTarget(plannedTarget)) {
            holdSentLook();
            lookGate.invalidate();
            return true;
        }
        if (!queueSteppedRotation(yaw, pitch)) {
            lookGate.invalidate();
            return false;
        }
        if (!lookGate.readyForEntity(plannedTarget.getId())) return false;

        boolean valid = sentLookHitsTarget(plannedTarget);
        if (!valid) lookGate.invalidate();
        return valid;
    }

    private boolean queueSteppedRotation(double targetYaw, double targetPitch) {
        double currentYaw = sentLookKnown
            ? sentYaw
            : GrimCombatCoordinator.serverLookKnown()
                ? GrimCombatCoordinator.serverYaw()
                : Rotations.serverYaw;
        double currentPitch = sentLookKnown
            ? sentPitch
            : GrimCombatCoordinator.serverLookKnown()
                ? GrimCombatCoordinator.serverPitch()
                : Rotations.serverPitch;
        double yawDelta = MathHelper.wrapDegrees(targetYaw - currentYaw);
        double pitchDelta = targetPitch - currentPitch;
        double nextYaw = CrystalAuraPlusPlus.steppedAngle(
            currentYaw,
            targetYaw,
            yawStep.get()
        );
        double nextPitch = MathHelper.clamp(
            currentPitch + MathHelper.clamp(
                pitchDelta,
                -pitchStep.get(),
                pitchStep.get()
            ),
            -90.0,
            90.0
        );
        boolean finalRotation = Math.abs(yawDelta) <= yawStep.get() + RAY_EPSILON
            && Math.abs(pitchDelta) <= pitchStep.get() + RAY_EPSILON;
        queuedRotation = new RotationIntent((float) nextYaw, (float) nextPitch);
        return finalRotation;
    }

    private void holdSentLook() {
        queuedRotation = new RotationIntent(sentYaw, sentPitch);
    }

    private boolean sentLookHitsTarget(PlayerEntity plannedTarget) {
        if (!sentLookKnown
            || plannedTarget == null
            || mc.player == null
            || !isValidTarget(plannedTarget)) {
            return false;
        }

        Vec3d eye = mc.player.getEyePos();
        double rayLength = effectiveRange();
        Box box = plannedTarget.getBoundingBox();
        Vec3d end = eye.add(Vec3d.fromPolar(sentPitch, sentYaw).multiply(rayLength));
        Vec3d entityHit = box.contains(eye)
            ? eye
            : box.raycast(eye, end).orElse(null);
        if (entityHit == null) return false;
        if (raycastMode.get() == RaycastMode.Grim) return true;

        BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
            eye,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
        return blockHit.getType() == HitResult.Type.MISS
            || eye.squaredDistanceTo(blockHit.getPos()) + RAY_EPSILON
                >= eye.squaredDistanceTo(entityHit);
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
        rotationInput = new AuraRotationInput(player.input, player, intent);
        player.input = rotationInput;
    }

    private void restoreRotationInput() {
        AuraRotationInput input = rotationInput;
        if (input == null) return;

        input.restorePlayerRotation();
        if (mc.player == input.player && mc.player.input == input) {
            mc.player.input = input.delegate;
        }
        rotationInput = null;
    }

    private void clearTarget() {
        target = null;
        lookGate.invalidate();
        queuedRotation = null;
    }

    private void resetState() {
        restoreRotationInput();
        clearTarget();
        sentYaw = 0.0f;
        sentPitch = 0.0f;
        sentLookKnown = false;
    }

    private double effectiveRange() {
        return attackRange.get();
    }

    private float thunderCooldownProgress() {
        return mc.player == null
            ? 0.0f
            : mc.player.getAttackCooldownProgress(0.5f);
    }

    static int compareTargetValues(
        TargetMode mode,
        float firstHealth,
        float secondHealth,
        double firstDistance,
        double secondDistance,
        double firstAngle,
        double secondAngle,
        int firstArmor,
        int secondArmor
    ) {
        return switch (mode) {
            case LowestHealth -> Float.compare(firstHealth, secondHealth);
            case ClosestDistance -> Double.compare(firstDistance, secondDistance);
            case ClosestAngle -> Double.compare(firstAngle, secondAngle);
            case LowestArmor -> Integer.compare(firstArmor, secondArmor);
            case HighestArmor -> Integer.compare(secondArmor, firstArmor);
        };
    }

    static double squaredDistanceToBox(Vec3d point, Box box) {
        double x = Math.max(box.minX - point.x, Math.max(0.0, point.x - box.maxX));
        double y = Math.max(box.minY - point.y, Math.max(0.0, point.y - box.maxY));
        double z = Math.max(box.minZ - point.z, Math.max(0.0, point.z - box.maxZ));
        return x * x + y * y + z * z;
    }

    private static double[] rotationTo(Vec3d target) {
        ClientPlayerEntity player = net.minecraft.client.MinecraftClient
            .getInstance()
            .player;
        Vec3d eye = player.getEyePos();
        double x = target.x - eye.x;
        double y = target.y - eye.y;
        double z = target.z - eye.z;
        double horizontal = Math.sqrt(x * x + z * z);
        return new double[] {
            Math.toDegrees(Math.atan2(z, x)) - 90.0,
            -Math.toDegrees(Math.atan2(y, horizontal))
        };
    }

    private static float totalHealth(PlayerEntity player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    private static Vec2f movementFor(PlayerInput input) {
        float sideways = (input.left() ? 1.0f : 0.0f)
            - (input.right() ? 1.0f : 0.0f);
        float forward = (input.forward() ? 1.0f : 0.0f)
            - (input.backward() ? 1.0f : 0.0f);
        return new Vec2f(sideways, forward).normalize();
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    public enum WeaponSwapMode {
        None,
        Pick,
        Silent
    }

    public enum RaycastMode {
        Grim,
        Visible
    }

    public enum TargetMode {
        LowestHealth,
        ClosestDistance,
        ClosestAngle,
        LowestArmor,
        HighestArmor
    }

    private record RotationIntent(float yaw, float pitch) {
    }

    private final class AuraRotationInput extends Input {
        private final Input delegate;
        private final ClientPlayerEntity player;
        private final RotationIntent intent;
        private boolean applied;
        private float restoreYaw;
        private float restorePitch;

        private AuraRotationInput(
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
