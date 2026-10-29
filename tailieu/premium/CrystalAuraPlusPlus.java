package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.joml.Vector3d;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * A client-paced crystal scheduler tuned to Grim 2.3.74-d54b49b.
 *
 * <p>No client-side entity removal, reach expansion or same-tick
 * place/attack chain is used. A look direction is sent for a full
 * movement cycle before every interaction. Grim mode permits a direct ray to
 * the real target through an intervening block because that Grim revision's
 * RotationPlace and Reach checks do not raycast the intervening world.</p>
 */
public final class CrystalAuraPlusPlus extends AliceModule
    implements GrimCombatCoordinator.Participant {
    private static final int CLIENT_PACED_RENDER_TICKS = 1;
    private static final double RANGE_EPSILON = 0.0;
    private static final double FACE_TOLERANCE = 0.03;
    private static final double[] MULTI_POINT_FIRST = {
        0.0, -0.30, 0.30, 0.0, 0.0, -0.30, -0.30, 0.30, 0.30
    };
    private static final double[] MULTI_POINT_SECOND = {
        0.0, 0.0, 0.0, -0.30, 0.30, -0.30, 0.30, -0.30, 0.30
    };
    private static final MethodHandle SEQUENCED_PACKET_SENDER = findSequencedPacketSender();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgPause = settings.createGroup("Pause");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("tam toi da toi muc tieu")
        .defaultValue(10.0)
        .range(1.0, 16.0)
        .sliderRange(1.0, 16.0)
        .build());

    private final Setting<Integer> maxTargets = sgGeneral.add(new IntSetting.Builder()
        .name("max-targets")
        .description("so luong muc tieu danh cung luc")
        .defaultValue(2)
        .range(1, 8)
        .sliderRange(1, 8)
        .build());

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("damage toi thieu de danh muc tieu")
        .defaultValue(4.0)
        .range(0.0, 36.0)
        .sliderRange(0.0, 20.0)
        .build());

    private final Setting<Double> facePlaceHealth = sgGeneral.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("HP thap nhat cua ke dich de crystal lien tuc")
        .defaultValue(9.0)
        .range(0.0, 36.0)
        .sliderRange(0.0, 20.0)
        .build());

    private final Setting<Double> maxSelfDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("sat thuong toi da ban than co the nhan khi danh muc tieu")
        .defaultValue(5.0)
        .range(0.0, 36.0)
        .sliderRange(0.0, 20.0)
        .build());

    private final Setting<Double> minAdvantage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-advantage")
        .description("loi the = sat thuong len muc tieu - sat thuong ban than")
        .defaultValue(1.0)
        .range(-20.0, 36.0)
        .sliderRange(-5.0, 12.0)
        .build());

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("bao ve ban than khoi viec danh chinh minh")
        .defaultValue(false)
        .build());

    private final Setting<Double> safetyReserve = sgGeneral.add(new DoubleSetting.Builder()
        .name("safety-reserve")
        .description("luong HP muon giu lai")
        .defaultValue(1.0)
        .range(0.0, 20.0)
        .sliderRange(0.0, 10.0)
        .visible(antiSuicide::get)
        .build());

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("du doan chuyen dong cua dich")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> speedMode = sgGeneral.add(new BoolSetting.Builder()
        .name("speed-mode")
        .description("Aggressive scheduler: skip look-gate delay, event-driven break, and entity ID tracking. May flag on strict Grim servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> dangerMode = sgGeneral.add(new BoolSetting.Builder()
        .name("danger-mode")
        .description("Bypass GrimCombatCoordinator's 1-action-per-epoch limit for same-tick break-after-place. Extreme flag risk.")
        .defaultValue(false)
        .visible(speedMode::get)
        .build()
    );

    private final Setting<Boolean> sync = sgGeneral.add(new BoolSetting.Builder()
        .name("sync")
        .description("cho server xac nhan truoc khi dat/pha")
        .defaultValue(true)
        .build());

    private final Setting<PickItemMode> pickItem = sgGeneral.add(new EnumSetting.Builder<PickItemMode>()
        .name("pick-item")
        .description("chon item theo mode")
        .defaultValue(PickItemMode.Silent)
        .build());

    private final Setting<Boolean> doPlace = sgPlace.add(new BoolSetting.Builder()
        .name("place")
        .description("dat crystal")
        .defaultValue(true)
        .build());

    private final Setting<RaycastMode> raycastMode = sgGeneral.add(new EnumSetting.Builder<RaycastMode>()
        .name("raycast-mode")
        .description("chon mode raycast")
        .defaultValue(RaycastMode.Grim)
        .build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("toi da tam dat crystal")
        .defaultValue(4.5)
        .range(1.0, 6.0)
        .sliderRange(1.0, 6.0)
        .build());

    private final Setting<Integer> minPlaceDelayMs = sgPlace.add(new IntSetting.Builder()
        .name("min-place-delay-ms")
        .description("so mili-giay toi thieu giua cac goi place va support-place")
        .defaultValue(0)
        .range(0, 1000)
        .sliderRange(0, 500)
        .build());

    private final Setting<Integer> maxPlaceDelayMs = sgPlace.add(new IntSetting.Builder()
        .name("max-place-delay-ms")
        .description("so mili-giay toi da giua cac goi place va support-place")
        .defaultValue(0)
        .range(0, 1000)
        .sliderRange(0, 500)
        .build());

    private final Setting<Boolean> multiPointPlace = sgPlace.add(new BoolSetting.Builder()
        .name("multi-point-place")
        .description("phan tan place qua nhieu base gan tuong duong thay vi lap lien tuc mot diem")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> support = sgPlace.add(new BoolSetting.Builder()
        .name("support")
        .description("dat obsidian")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> doBreak = sgBreak.add(new BoolSetting.Builder()
        .name("break")
        .description("tan cong crystal")
        .defaultValue(true)
        .build());

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("tam tan cong crystal")
        .defaultValue(3.0)
        .range(1.0, 6.0)
        .sliderRange(1.0, 6.0)
        .build());

    private final Setting<Integer> minBreakDelayMs = sgBreak.add(new IntSetting.Builder()
        .name("min-break-delay-ms")
        .description("so mili-giay toi thieu giua cac goi dap crystal")
        .defaultValue(0)
        .range(0, 1000)
        .sliderRange(0, 500)
        .build());

    private final Setting<Integer> maxBreakDelayMs = sgBreak.add(new IntSetting.Builder()
        .name("max-break-delay-ms")
        .description("so mili-giay toi da giua cac goi dap crystal")
        .defaultValue(75)
        .range(0, 1000)
        .sliderRange(0, 500)
        .build());

    private final Setting<Integer> retryDelay = sgBreak.add(new IntSetting.Builder()
        .name("retry-delay")
        .description("khoang nghi giua cac lan thu lai tan cong crystal")
        .defaultValue(1)
        .range(1, 10)
        .sliderRange(1, 10)
        .build());

    private final Setting<Integer> maxAttempts = sgBreak.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("deo biet")
        .defaultValue(1)
        .range(1, 5)
        .sliderRange(1, 5)
        .build());

    private final Setting<Boolean> antiWeakness = sgBreak.add(new BoolSetting.Builder()
        .name("anti-weakness")
        .description("dung vu khi trong hotbar co the pha crystal khi dang bi weakness")
        .defaultValue(true)
        .build());

    private final Setting<Double> yawStep = sgRotation.add(new DoubleSetting.Builder()
        .name("yaw-step")
        .description("goc yaw thay doi toi da tren server moi chu ky")
        .defaultValue(180.0)
        .range(1.0, 180.0)
        .sliderRange(1.0, 180.0)
        .build());

    private final Setting<Double> pitchStep = sgRotation.add(new DoubleSetting.Builder()
        .name("pitch-step")
        .description("goc pitch thay doi toi da tren server moi chu ky")
        .defaultValue(90.0)
        .range(1.0, 90.0)
        .sliderRange(1.0, 90.0)
        .build());

    private final Setting<Boolean> pauseOnUse = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("tam dung khi ban dang dung item de tranh xung dot")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pauseOnMine = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-mine")
        .description("tam dung khi giu nut danh de tranh xung dot")
        .defaultValue(true)
        .build());

    private final Setting<Double> pauseHealth = sgPause.add(new DoubleSetting.Builder()
        .name("pause-health")
        .description("tam dung khi mau thap hon muc nay")
        .defaultValue(0.0)
        .range(0.0, 36.0)
        .sliderRange(0.0, 20.0)
        .build());

    private final Setting<Boolean> renderEnabled = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("cong tac chinh cho moi overlay va nhan sat thuong cua Crystal Aura++")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> crystalChams = sgRender.add(new BoolSetting.Builder()
        .name("crystal-chams")
        .description("hien thi model crystal that xuyen tuong ma khong gay anh huong den server")
        .defaultValue(true)
        .visible(renderEnabled::get)
        .build());

    private final Setting<Double> chamsRange = sgRender.add(new DoubleSetting.Builder()
        .name("chams-range")
        .description("tam toi da de render crystal xuyen tuong")
        .defaultValue(16.0)
        .range(4.0, 64.0)
        .sliderRange(4.0, 32.0)
        .visible(() -> renderEnabled.get() && crystalChams.get())
        .build());

    private final Setting<Double> chamsScale = sgRender.add(new DoubleSetting.Builder()
        .name("chams-scale")
        .description("ty le cua model crystal xuyen tuong")
        .defaultValue(1.0)
        .range(0.25, 2.0)
        .sliderRange(0.5, 1.5)
        .visible(() -> renderEnabled.get() && crystalChams.get())
        .build());

    private final Setting<ShapeMode> chamsShapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("chams-shape-mode")
        .description("ve cac mat, canh hoac ca hai cua model crystal xuyen tuong")
        .defaultValue(ShapeMode.Both)
        .visible(() -> renderEnabled.get() && crystalChams.get())
        .build());

    private final Setting<SettingColor> chamsSideColor = sgRender.add(new ColorSetting.Builder()
        .name("chams-side-color")
        .description("mau nen cua model crystal xuyen tuong")
        .defaultValue(new SettingColor(190, 90, 255, 45))
        .visible(() -> renderEnabled.get() && crystalChams.get() && chamsShapeMode.get().sides())
        .build());

    private final Setting<SettingColor> chamsLineColor = sgRender.add(new ColorSetting.Builder()
        .name("chams-line-color")
        .description("mau vien cua model crystal xuyen tuong")
        .defaultValue(new SettingColor(220, 150, 255, 220))
        .visible(() -> renderEnabled.get() && crystalChams.get() && chamsShapeMode.get().lines())
        .build());

    private final Setting<Boolean> renderPlace = sgRender.add(new BoolSetting.Builder()
        .name("render-place")
        .description("hien thi khoi be do da duoc xac nhan")
        .defaultValue(true)
        .visible(renderEnabled::get)
        .build());

    private final Setting<Boolean> renderBreak = sgRender.add(new BoolSetting.Builder()
        .name("render-break")
        .description("hien thi hitbox cua crystal muc tieu da duoc xac nhan")
        .defaultValue(true)
        .visible(renderEnabled::get)
        .build());

    private final Setting<Boolean> renderPending = sgRender.add(new BoolSetting.Builder()
        .name("render-pending")
        .description("hien thi cac hanh dong dang cho server cap nhat")
        .defaultValue(true)
        .visible(renderEnabled::get)
        .build());

    private final Setting<Boolean> renderDamage = sgRender.add(new BoolSetting.Builder()
        .name("render-damage")
        .description("hien thi sat thuong muc tieu va ban than")
        .defaultValue(true)
        .visible(renderEnabled::get)
        .build());

    private final Setting<Boolean> renderFade = sgRender.add(new BoolSetting.Builder()
        .name("render-fade")
        .description("Keeps completed place and break overlays visible while smoothly fading them out.")
        .defaultValue(true)
        .visible(renderEnabled::get)
        .build());

    private final Setting<Integer> fadeTime = sgRender.add(new IntSetting.Builder()
        .name("fade-time")
        .description("thoi gian (tick) de mo dan hanh dong da hoan thanh")
        .defaultValue(8)
        .range(1, 40)
        .sliderRange(1, 20)
        .visible(() -> renderEnabled.get() && renderFade.get())
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("ve cac mat, vien hoac ca hai cua overlay")
        .defaultValue(ShapeMode.Both)
        .visible(renderEnabled::get)
        .build());

    private final Setting<SettingColor> placeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("place-side-color")
        .description("mau nen cua overlay hien thi vi tri dat")
        .defaultValue(new SettingColor(70, 255, 130, 45))
        .visible(() -> renderEnabled.get() && shapeMode.get().sides())
        .build());

    private final Setting<SettingColor> placeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("place-line-color")
        .description("mau vien cho lop phu dat")
        .defaultValue(new SettingColor(70, 255, 130, 220))
        .visible(() -> renderEnabled.get() && shapeMode.get().lines())
        .build());

    private final Setting<SettingColor> breakSideColor = sgRender.add(new ColorSetting.Builder()
        .name("break-side-color")
        .description("mau nen cua overlay hien thi vi tri dap")
        .defaultValue(new SettingColor(255, 70, 90, 45))
        .visible(() -> renderEnabled.get() && shapeMode.get().sides())
        .build());

    private final Setting<SettingColor> breakLineColor = sgRender.add(new ColorSetting.Builder()
        .name("break-line-color")
        .description("mau vien cho lop phu dap")
        .defaultValue(new SettingColor(255, 70, 90, 220))
        .visible(() -> renderEnabled.get() && shapeMode.get().lines())
        .build());

    private final Setting<SettingColor> pendingSideColor = sgRender.add(new ColorSetting.Builder()
        .name("pending-side-color")
        .description("mau nen cho cac hanh dong dang cho server xac nhan")
        .defaultValue(new SettingColor(255, 185, 45, 35))
        .visible(() -> renderEnabled.get() && renderPending.get() && shapeMode.get().sides())
        .build());

    private final Setting<SettingColor> pendingLineColor = sgRender.add(new ColorSetting.Builder()
        .name("pending-line-color")
        .description("mau vien cho cac thao tac dang cho server")
        .defaultValue(new SettingColor(255, 185, 45, 220))
        .visible(() -> renderEnabled.get() && renderPending.get() && shapeMode.get().lines())
        .build());

    private final Setting<SettingColor> damageColor = sgRender.add(new ColorSetting.Builder()
        .name("damage-color")
        .description("mau cua nhan hien thi sat thuong len muc tieu/ban than")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> renderEnabled.get() && renderDamage.get())
        .build());

    private final Setting<Double> damageScale = sgRender.add(new DoubleSetting.Builder()
        .name("damage-scale")
        .description("ty le scale cho nhan hien thi sat thuong the gioi")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 2.0)
        .visible(() -> renderEnabled.get() && renderDamage.get())
        .build());

    private final GrimLookGate lookGate = new GrimLookGate();
    private final GrimHotbarRefill refill = new GrimHotbarRefill(this);
    private int lastCrystalHotbarSlot = -1;
    private final Map<Integer, AttackRecord> attacks = new HashMap<>();

    private int highestEntityId = -1;
    private PlacePlan fastPlaceCandidate; 
    private BlockPos fastBreakLocation;

    private int clientTick;
    private long nextPlaceActionAtNanos;
    private long nextBreakActionAtNanos;
    private BlockPos lastPlaceDelayBase;
    private BlockPos lastBreakDelayBase;
    private long placePointSequence;
    private PendingSupport pendingSupport;
    private PendingCrystal pendingCrystal;
    private RenderSnapshot selectedRender;
    private final List<RenderPulse> renderHistory = new ArrayList<>();
    private final Vector3d renderTextPos = new Vector3d();
    private float sentYaw;
    private float sentPitch;
    private boolean sentLookKnown;
    private RotationIntent queuedRotation;
    private AuraRotationInput rotationInput;

    private final Map<BlockPos, SupportState> supportStates = new HashMap<>();
    private final Map<Integer, BlockPos> crystalToSupport = new HashMap<>();
    private BlockPos lastSupportPos = null;
    private long lastSupportCleanup = 0;

    public CrystalAuraPlusPlus() {
        super(
            AliceAddon.PVP_CATEGORY,
            "crystal-aura++",
            "Server-authoritative Crystal Aura tuned to Grim 2.3.74 with exact damage exposure and stable rotations."
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

    private void resetState() {
        attacks.clear();
        highestEntityId = -1;
        fastPlaceCandidate = null;
        fastBreakLocation = null;
        pendingSupport = null;
        pendingCrystal = null;
        lookGate.invalidate();
        refill.cancel();
        lastCrystalHotbarSlot = -1;
        
        supportStates.clear();
        crystalToSupport.clear();
        lastSupportPos = null;
        lastSupportCleanup = 0;
        
        restoreRotationInput();
        clientTick = 0;
        nextPlaceActionAtNanos = 0L;
        nextBreakActionAtNanos = 0L;
        lastPlaceDelayBase = null;
        lastBreakDelayBase = null;
        placePointSequence = 0L;
        selectedRender = null;
        renderHistory.clear();
        sentYaw = 0.0f;
        sentPitch = 0.0f;
        sentLookKnown = false;
        queuedRotation = null;
    }



    @AliceHandler(priority = 100)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        refill.onPlayerTickMovement();
        applyQueuedRotationInput();
    }

    @AliceHandler(priority = -200)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        restoreRotationInput();
        lookGate.onMovementPacketsPost();
        refill.onSendMovementPacketsPost();
    }

    @AliceHandler(priority = meteordevelopment.orbit.EventPriority.HIGHEST)
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity crystal)) return;

        if (crystal.getId() > highestEntityId) {
            highestEntityId = crystal.getId();
        }

        BlockPos support = crystal.getBlockPos().down();
        crystalToSupport.put(crystal.getId(), support);

        if (!speedMode.get()) return;

        PendingCrystal pending = pendingCrystal;
        if (pending != null && crystalMatchesBase(crystal, pending.base)) {
            pendingCrystal = null; 

            if (doBreak.get()
                && actionCooldownPassed(CrystalAction.Break, support)
                && canAttackCrystal(crystal)
            ) {
                List<PlayerEntity> targets = collectTargets();
                if (!targets.isEmpty()) {
                    BreakPlan plan = scoreBreakCandidate(crystal, targets);
                    if (plan != null) {
                        dangerBreakFromEvent(plan);
                    }
                }
            }
        }
    }

    @AliceHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (event.entity instanceof EndCrystalEntity) {
            attacks.remove(event.entity.getId());
            crystalToSupport.remove(event.entity.getId());
        }
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
        selectedRender = null;
        clientTick++;
        pruneState();
        pruneRenderHistory();

        if (pickItem.get() != PickItemMode.Silent
            && GrimCombatCoordinator.hasSilentSlot(this)) {
            return cleanupIntent();
        }

        if (!canRun()) {
            lookGate.invalidate();
            refill.cancel();
            return GrimCombatCoordinator.hasSilentSlot(this)
                ? cleanupIntent()
                : null;
        }

        List<PlayerEntity> targets = collectTargets();
        if (targets.isEmpty()) {
            lookGate.invalidate();
            refill.cancel();
            return GrimCombatCoordinator.hasSilentSlot(this)
                ? cleanupIntent()
                : null;
        }

        if (doBreak.get()) {
            BreakPlan breakPlan = findBestBreak(targets);
            if (breakPlan != null
                && actionCooldownPassed(CrystalAction.Break, breakPlan.base)
                && weaknessWeaponSlot(breakPlan.crystal) != Integer.MIN_VALUE) {

                if (speedMode.get() && doPlace.get()) {
                    fastPlaceCandidate = findBestPlace(targets, false);
                    if (fastPlaceCandidate == null && support.get()) {
                        fastPlaceCandidate = findBestPlace(targets, true);
                    }
                }

                return breakIntent(breakPlan);
            }
        }

        if (!doPlace.get()) {
            lookGate.invalidate();
            refill.cancel();
            return GrimCombatCoordinator.hasSilentSlot(this)
                ? cleanupIntent()
                : null;
        }

        PendingSupport pending = pendingSupport;
        if (!sync.get() && pending != null) {
            if (clientTick > pending.sentTick) {
                PlacePlan plan = makeClientPacedSupportPlan(pending.base, targets);
                if (plan != null && hasItemAvailable(Items.END_CRYSTAL)) {
                    if (actionCooldownPassed(CrystalAction.Place, plan.base)) {
                        return placeIntent(plan, true);
                    }
                    lookGate.invalidate();
                    refill.cancel();
                    return GrimCombatCoordinator.hasSilentSlot(this)
                        ? cleanupIntent()
                        : null;
                }
            }
            pendingSupport = null;
        }

        PlacePlan plan = findBestPlace(targets, false);
        if (speedMode.get() && fastPlaceCandidate != null) {
            plan = fastPlaceCandidate;
            fastPlaceCandidate = null;
        } else if (plan == null && support.get()) {
            plan = findBestPlace(targets, true);
        }
        if (plan == null || !hasPlacementItems(plan)) {
            lookGate.invalidate();
            refill.cancel();
            return GrimCombatCoordinator.hasSilentSlot(this)
                ? cleanupIntent()
                : null;
        }
        if (!actionCooldownPassed(CrystalAction.Place, plan.base)) {
            lookGate.invalidate();
            refill.cancel();
            return GrimCombatCoordinator.hasSilentSlot(this)
                ? cleanupIntent()
                : null;
        }
        if (speedMode.get()) {
            fastBreakLocation = plan.base;
        }
        return placeIntent(plan, false);
    }

    @Override
    public int combatPriority() {
        return 100;
    }

    @Override
    public boolean canFollowEmergencyOffhandSwap() {
        return true;
    }

    private GrimCombatCoordinator.Intent breakIntent(BreakPlan plan) {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return CrystalAuraPlusPlus.this;
            }

            @Override
            public void execute() {
                runBreak(plan);
            }
        };
    }

    private GrimCombatCoordinator.Intent placeIntent(
        PlacePlan plan,
        boolean pendingFollowup
    ) {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return CrystalAuraPlusPlus.this;
            }

            @Override
            public void execute() {
                runPlace(plan, pendingFollowup);
            }
        };
    }

    private GrimCombatCoordinator.Intent cleanupIntent() {
        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return CrystalAuraPlusPlus.this;
            }

            @Override
            public void execute() {
                restoreSilentSlot();
            }
        };
    }

    @AliceHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderEnabled.get() || mc.player == null || mc.world == null) return;

        if (crystalChams.get()) renderCrystalChams(event);

        if (renderFade.get()) {
            for (RenderPulse pulse : renderHistory) {
                if (isPendingSnapshot(pulse.snapshot)
                    || !shouldRender(pulse.snapshot.kind)) {
                    continue;
                }
                double alpha = fadeAlpha(pulse, event.tickDelta);
                if (alpha > 0.0) drawSnapshot3D(event, pulse.snapshot, false, alpha);
            }
        }

        RenderSnapshot selected = selectedRender;
        if (selected != null
            && !isPendingSnapshot(selected)
            && shouldRender(selected.kind)) {
            drawSnapshot3D(event, selected, false, 1.0);
        }

        if (!renderPending.get()) return;

        PendingSupport support = pendingSupport;
        if (support != null) drawSnapshot3D(event, support.render, true, 1.0);

        PendingCrystal crystal = pendingCrystal;
        if (crystal != null) drawSnapshot3D(event, crystal.render, true, 1.0);

        for (AttackRecord record : attacks.values()) {
            if (record.render != null) {
                drawSnapshot3D(event, record.render, true, 1.0);
            }
        }
    }

    private void renderCrystalChams(Render3DEvent event) {
        double rangeSquared = chamsRange.get() * chamsRange.get();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)
                || !crystal.isAlive()
                || mc.player.squaredDistanceTo(crystal) > rangeSquared) {
                continue;
            }

            WireframeEntityRenderer.render(
                event,
                crystal,
                chamsScale.get(),
                chamsSideColor.get(),
                chamsLineColor.get(),
                chamsShapeMode.get()
            );
        }
    }

    @AliceHandler
    private void onRender2D(Render2DEvent event) {
        if (!renderEnabled.get()
            || !renderDamage.get()
            || mc.player == null
            || mc.world == null) {
            return;
        }

        if (renderFade.get()) {
            for (RenderPulse pulse : renderHistory) {
                if (isPendingSnapshot(pulse.snapshot)
                    || !shouldRender(pulse.snapshot.kind)) {
                    continue;
                }
                double alpha = fadeAlpha(pulse, event.tickDelta);
                if (alpha > 0.0) drawDamageLabel(pulse.snapshot, alpha);
            }
        }

        RenderSnapshot selected = selectedRender;
        if (selected != null
            && !isPendingSnapshot(selected)
            && shouldRender(selected.kind)) {
            drawDamageLabel(selected, 1.0);
        }

        if (!renderPending.get()) return;

        PendingSupport support = pendingSupport;
        if (support != null) drawDamageLabel(support.render, 1.0);

        PendingCrystal crystal = pendingCrystal;
        if (crystal != null) drawDamageLabel(crystal.render, 1.0);

        for (AttackRecord record : attacks.values()) {
            if (record.render != null) drawDamageLabel(record.render, 1.0);
        }
    }

    private void drawSnapshot3D(
        Render3DEvent event,
        RenderSnapshot snapshot,
        boolean pending,
        double alpha
    ) {
        SettingColor side = pending
            ? pendingSideColor.get()
            : snapshot.kind == RenderKind.Place
                ? placeSideColor.get()
                : breakSideColor.get();
        SettingColor line = pending
            ? pendingLineColor.get()
            : snapshot.kind == RenderKind.Place
                ? placeLineColor.get()
                : breakLineColor.get();

        event.renderer.box(
            snapshot.box,
            colorWithAlpha(side, alpha),
            colorWithAlpha(line, alpha),
            shapeMode.get(),
            0
        );
    }

    private void drawDamageLabel(RenderSnapshot snapshot, double alpha) {
        renderTextPos.set(
            snapshot.label.x,
            snapshot.label.y,
            snapshot.label.z
        );
        if (!NametagUtils.to2D(renderTextPos, damageScale.get())) return;

        String label = snapshot.damageLabel;
        TextRenderer text = TextRenderer.get();
        if (text == null) return;

        NametagUtils.begin(renderTextPos);
        text.begin(1.0, false, true);
        double width = text.getWidth(label, true);
        double height = text.getHeight(true);
        text.render(
            label,
            -width / 2.0,
            -height / 2.0,
            colorWithAlpha(damageColor.get(), alpha),
            true
        );
        text.end();
        NametagUtils.end();
    }

    private boolean shouldRender(RenderKind kind) {
        return kind == RenderKind.Place ? renderPlace.get() : renderBreak.get();
    }

    private boolean isPendingSnapshot(RenderSnapshot snapshot) {
        if (snapshot == null) return false;

        PendingSupport support = pendingSupport;
        if (support != null && support.render == snapshot) return true;

        PendingCrystal crystal = pendingCrystal;
        if (crystal != null && crystal.render == snapshot) return true;

        for (AttackRecord record : attacks.values()) {
            if (record.render == snapshot) return true;
        }
        return false;
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
        if (totalHealth(mc.player) < pauseHealth.get()) return false;
        if (pauseOnUse.get()
            && (mc.player.isUsingItem()
                || mc.currentScreen == null && mc.options.useKey.isPressed())) {
            return false;
        }
        return !pauseOnMine.get()
            || mc.currentScreen != null
            || !mc.options.attackKey.isPressed();
    }

    private List<PlayerEntity> collectTargets() {
        double rangeSquared = targetRange.get() * targetRange.get();
        List<PlayerEntity> targets = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player
                || !player.isAlive()
                || player.isCreative()
                || player.isSpectator()
                || !Friends.get().shouldAttack(player)
                || mc.player.squaredDistanceTo(player) > rangeSquared) {
                continue;
            }
            targets.add(player);
        }
        targets.sort(Comparator
            .comparingDouble((PlayerEntity player) -> mc.player.squaredDistanceTo(player))
            .thenComparingInt(Entity::getId));
        if (targets.size() > maxTargets.get()) {
            return new ArrayList<>(targets.subList(0, maxTargets.get()));
        }
        return targets;
    }

    private BreakPlan findBestBreak(List<PlayerEntity> targets) {
        BreakPlan best = null;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            BreakPlan plan = scoreBreakCandidate(crystal, targets);
            if (plan == null) continue;

            if (best == null) {
                best = plan;
                continue;
            }

            double rawDiff = plan.damage.score() - best.damage.score();
            if (Math.abs(rawDiff) > 0.5) {
                if (rawDiff > 0) best = plan;
            } else if (plan.adaptiveScore > best.adaptiveScore
                || (sameScore(plan.adaptiveScore, best.adaptiveScore)
                    && plan.crystal.getId() < best.crystal.getId())) {
                best = plan;
            }
        }
        return best;
    }

    private BreakPlan scoreBreakCandidate(EndCrystalEntity crystal, List<PlayerEntity> targets) {
        if (!crystal.isAlive() || !canAttackCrystal(crystal)) return null;

        AttackRecord record = attacks.get(crystal.getId());
        if (record != null) {
            if (record.attempts >= maxAttempts.get()) return null;
            if (clientTick - record.lastTick < retryDelay.get()) return null;
        }

        Vec3d aim = findBreakAim(crystal);
        if (aim == null) return null;

        DamageProfile damage = scoreExplosion(crystal.getEntityPos(), null, targets);
        if (damage == null) return null;

        BlockPos support = crystalToSupport.get(crystal.getId());
        if (support == null) support = crystal.getBlockPos().down();
        double adaptiveScore = damage.score();

        return new BreakPlan(crystal, support, aim, damage, adaptiveScore);
    }

    private void runBreak(BreakPlan plan) {
        int weaponSlot = weaknessWeaponSlot(plan.crystal);
        if (weaponSlot == Integer.MIN_VALUE) {
            lookGate.invalidate();
            refill.cancel();
            restoreSilentSlot();
            return;
        }
        if (!prepareAttackSlot(weaponSlot)) return;

        if (!canAttackCrystal(plan.crystal)
            || !scoreStillSafe(plan.crystal.getEntityPos(), null, plan.damage.target)) {
            lookGate.invalidate();
            return;
        }

        selectedRender = breakRenderSnapshot(plan);

        double[] rotation = rotationTo(plan.aim);
        if (!holdAndGateEntity(plan.crystal, rotation[0], rotation[1])) return;
        if (!actionWindowOpen(CrystalAction.Break, plan.base)) return;

        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(
            plan.crystal,
            mc.player.isSneaking()
        ));
        mc.player.swingHand(Hand.MAIN_HAND);
        armActionCooldown(CrystalAction.Break, plan.base);

        recordSupportBreak(plan.base);

        AttackRecord record = attacks.computeIfAbsent(
            plan.crystal.getId(),
            ignored -> new AttackRecord()
        );
        record.render = selectedRender;
        record.attempts++;
        record.lastTick = clientTick;
        recordRenderAction(selectedRender);
    }

    private void runPlace(PlacePlan plan, boolean pendingFollowup) {
        if (plan.support) placeSupport(plan);
        else if (placeCrystal(plan, pendingFollowup) && pendingFollowup) pendingSupport = null;
    }

    private boolean hasPlacementItems(PlacePlan plan) {
        return plan.support
            ? hasItemAvailable(Items.OBSIDIAN)
                && hasItemAvailable(Items.END_CRYSTAL)
            : hasItemAvailable(Items.END_CRYSTAL);
    }

    private boolean hasItemAvailable(Item item) {
        if (mc.player.getOffHandStack().isOf(item)) return true;
        int selected = mc.player.getInventory().getSelectedSlot();
        if (pickItem.get() == PickItemMode.None) {
            return mc.player.getInventory().getStack(selected).isOf(item);
        }
        return InvUtils.find(item).found();
    }

    private void recordSupportPlace(BlockPos pos) {
        if (!multiPointPlace.get()) return;
        SupportState state = supportStates.computeIfAbsent(pos, SupportState::new);
        state.recordPlace(System.currentTimeMillis());
        if (lastSupportPos != null && lastSupportPos.equals(pos)) {
            state.consecutiveUses++;
        } else {
            if (lastSupportPos != null) {
                SupportState prev = supportStates.get(lastSupportPos);
                if (prev != null) prev.consecutiveUses = 0;
            }
            state.consecutiveUses = 1;
            lastSupportPos = pos;
        }
    }

    private void recordSupportBreak(BlockPos pos) {
        if (!multiPointPlace.get() || pos == null) return;
        SupportState state = supportStates.computeIfAbsent(pos, SupportState::new);
        state.recordBreak(System.currentTimeMillis());
    }

    private double applyAdaptivePenalty(double rawScore, BlockPos support) {
        if (!multiPointPlace.get()) return rawScore;
        SupportState state = supportStates.get(support);
        if (state == null) return rawScore;

        long now = System.currentTimeMillis();
        int places = state.recentPlaces(now, 500);
        int breaks = state.recentBreaks(now, 500);

        double penalty = (places * 0.1) + (breaks * 0.1) + (state.consecutiveUses * 0.05);
        return rawScore - Math.min(0.5, penalty);
    }
    
    private void cleanupSupportStates() {
        if (!multiPointPlace.get()) {
            supportStates.clear();
            lastSupportPos = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSupportCleanup > 1000) {
            supportStates.values().removeIf(s -> now - s.lastActivity() > 2000);
            lastSupportCleanup = now;
        }
    }

    private PlacePlan findBestPlace(List<PlayerEntity> targets, boolean supportCandidate) {
        cleanupSupportStates();
        int radius = MathHelper.ceil(placeRange.get()) + 1;
        PlacePlan best = null;
        for (BlockPos iterated : BlockPos.iterateOutwards(
            mc.player.getBlockPos(),
            radius,
            radius,
            radius
        )) {
            BlockPos base = iterated.toImmutable();
            PlacePlan candidate = makePlacePlan(base, supportCandidate, targets);
            if (candidate == null) continue;
            
            if (best == null) {
                best = candidate;
                continue;
            }

            double rawDiff = candidate.damage.score - best.damage.score;
            if (Math.abs(rawDiff) > 0.5) {
                if (rawDiff > 0) best = candidate;
            } else if (candidate.adaptiveScore > best.adaptiveScore
                || (sameScore(candidate.adaptiveScore, best.adaptiveScore)
                    && comparePos(candidate.base, best.base) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private PlacePlan makePlacePlan(
        BlockPos base,
        boolean supportCandidate,
        List<PlayerEntity> targets
    ) {
        if (supportCandidate) {
            if (!canBecomeSupport(base)) return null;
        } else if (!isExistingBaseValid(base)) {
            return null;
        }

        Vec3d explosion = crystalSpawn(base);
        DamageProfile damage = scoreExplosion(
            explosion,
            supportCandidate ? base : null,
            targets
        );
        if (damage == null) return null;

        BlockHitResult hit = supportCandidate
            ? findSupportPlacementHit(base)
            : findCrystalBaseHit(base);
        if (hit == null) return null;
        
        double adaptiveScore = applyAdaptivePenalty(damage.score, base);
        return new PlacePlan(base, hit, supportCandidate, damage, adaptiveScore);
    }

    private PlacePlan makeClientPacedSupportPlan(
        BlockPos base,
        List<PlayerEntity> targets
    ) {
        if (!canUseClientPacedSupport(base)) return null;
        DamageProfile damage = scoreExplosion(crystalSpawn(base), base, targets);
        if (damage == null) return null;
        BlockHitResult hit = findTargetHit(base, Direction.UP);
        if (hit == null) return null;
        
        double adaptiveScore = applyAdaptivePenalty(damage.score, base);
        return new PlacePlan(base, hit, false, damage, adaptiveScore);
    }

    private boolean placeCrystal(PlacePlan plan, boolean assumedSupport) {
        HeldItem crystal = ensureHeld(Items.END_CRYSTAL, chooseDestination());
        if (crystal == null) return false;
        boolean baseValid = assumedSupport
            ? canUseClientPacedSupport(plan.base)
            : isExistingBaseValid(plan.base);
        if (!baseValid
            || !scoreStillSafe(
                crystalSpawn(plan.base),
                assumedSupport ? plan.base : null,
                plan.damage.target
            )) {
            lookGate.invalidate();
            return false;
        }

        BlockHitResult hit = findTargetHit(plan.base, plan.hit.getSide());
        if (hit == null) {
            lookGate.invalidate();
            return false;
        }
        selectedRender = placeRenderSnapshot(plan);
        double[] rotation = rotationTo(hit.getPos());
        if (!holdAndGateBlock(hit, rotation[0], rotation[1])) return false;
        if (!actionWindowOpen(CrystalAction.Place, plan.base)) return false;

        if (!interactBlock(crystal, hit)) return false;
        mc.player.swingHand(crystal.hand);
        armActionCooldown(CrystalAction.Place, plan.base);
        placePointSequence++;

        pendingCrystal = new PendingCrystal(
            plan.base,
            clientTick,
            selectedRender,
            null
        );
        recordSupportPlace(plan.base);
        recordRenderAction(selectedRender);
        return true;
    }

    private void placeSupport(PlacePlan plan) {
        HeldItem obsidian = ensureHeld(Items.OBSIDIAN, chooseDestination());
        if (obsidian == null) return;
        if (!canBecomeSupport(plan.base)
            || !scoreStillSafe(crystalSpawn(plan.base), plan.base, plan.damage.target)) {
            lookGate.invalidate();
            return;
        }

        BlockHitResult hit = findSupportPlacementHit(plan.base);
        if (hit == null) {
            lookGate.invalidate();
            return;
        }
        selectedRender = placeRenderSnapshot(plan);
        double[] rotation = rotationTo(hit.getPos());
        if (!holdAndGateBlock(hit, rotation[0], rotation[1])) return;
        if (!actionWindowOpen(CrystalAction.Place, plan.base)) return;

        if (!interactBlock(obsidian, hit)) return;
        mc.player.swingHand(obsidian.hand);
        armActionCooldown(CrystalAction.Place, plan.base);
        placePointSequence++;
        pendingSupport = new PendingSupport(
            plan.base,
            clientTick,
            selectedRender
        );
        recordSupportPlace(plan.base);
        recordRenderAction(selectedRender);
    }

    private HeldItem ensureHeld(Item item, int destination) {
        if (mc.player.getOffHandStack().isOf(item)) {
            refill.cancel();
            restoreSilentSlot();
            return new HeldItem(Hand.OFF_HAND, -1);
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        if (pickItem.get() == PickItemMode.None) {
            refill.cancel();
            restoreSilentSlot();
            if (!mc.player.getInventory().getStack(selected).isOf(item)) return null;
            rememberCrystalSlot(item, selected);
            return new HeldItem(Hand.MAIN_HAND, selected);
        }

        int hotbar = findHotbarSlot(item);
        if (hotbar < 0) {
            int refillDestination = item == Items.END_CRYSTAL
                ? lastCrystalHotbarSlot
                : destination;
            if (refillDestination < 0 || refillDestination > 8) {
                refill.cancel();
                lookGate.invalidate();
                return null;
            }

            GrimHotbarRefill.Result result = refill.request(item, refillDestination);
            if (!(result instanceof GrimHotbarRefill.Result.Ready ready)) {
                lookGate.invalidate();
                return null;
            }
            hotbar = ready.hotbarSlot();
        } else {
            refill.cancel();
        }

        rememberCrystalSlot(item, hotbar);
        if (!selectSlotForAction(hotbar)) return null;
        return new HeldItem(Hand.MAIN_HAND, hotbar);
    }

    private void rememberCrystalSlot(Item item, int slot) {
        if (item == Items.END_CRYSTAL && slot >= 0 && slot <= 8) {
            lastCrystalHotbarSlot = slot;
        }
    }

    private boolean prepareAttackSlot(int weaponSlot) {
        if (weaponSlot < 0) {
            if (pickItem.get() == PickItemMode.Silent) restoreSilentSlot();
            return true;
        }

        if (pickItem.get() == PickItemMode.None) {
            restoreSilentSlot();
            return mc.player.getInventory().getSelectedSlot() == weaponSlot;
        }
        return selectSlotForAction(weaponSlot);
    }

    private boolean selectSlotForAction(int slot) {
        if (slot < 0 || slot > 8) return false;

        if (pickItem.get() == PickItemMode.Silent) {
            return GrimCombatCoordinator.selectSilent(this, slot);
        }

        restoreSilentSlot();
        return mc.player.getInventory().getSelectedSlot() == slot
            || InvUtils.swap(slot, false);
    }

    private void restoreSilentSlot() {
        GrimCombatCoordinator.restoreSilent(this);
    }

    private boolean interactBlock(HeldItem heldItem, BlockHitResult hit) {
        if (SEQUENCED_PACKET_SENDER == null) return false;

        SequencedPacketCreator creator = sequence ->
            new PlayerInteractBlockC2SPacket(heldItem.hand, hit, sequence);
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

    static boolean sequencedPacketSenderAvailable() {
        return SEQUENCED_PACKET_SENDER != null;
    }

    private int weaknessWeaponSlot(EndCrystalEntity crystal) {
        if (!antiWeakness.get() || !mc.player.hasStatusEffect(StatusEffects.WEAKNESS)) {
            return -1;
        }

        if (pickItem.get() == PickItemMode.None) {
            int selected = mc.player.getInventory().getSelectedSlot();
            ItemStack held = mc.player.getInventory().getStack(selected);
            return DamageUtils.getAttackDamage(mc.player, crystal, held) > 0
                ? selected
                : Integer.MIN_VALUE;
        }

        int bestSlot = -1;
        float bestDamage = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            float damage = DamageUtils.getAttackDamage(mc.player, crystal, stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        return bestSlot < 0 ? Integer.MIN_VALUE : bestSlot;
    }

    private DamageProfile scoreExplosion(
        Vec3d explosion,
        BlockPos obsidianOverride,
        List<PlayerEntity> targets
    ) {
        float selfDamage = conservativeDamage(mc.player, explosion, obsidianOverride, false);
        if (selfDamage > maxSelfDamage.get()) return null;
        if (antiSuicide.get()
            && totalHealth(mc.player) - selfDamage <= safetyReserve.get()) {
            return null;
        }

        DamageProfile best = null;
        for (PlayerEntity target : targets) {
            float targetDamage = conservativeDamage(target, explosion, obsidianOverride, true);
            double required = totalHealth(target) <= facePlaceHealth.get()
                ? Math.min(minDamage.get(), 1.0)
                : minDamage.get();
            if (targetDamage < required
                || targetDamage - selfDamage < minAdvantage.get()) {
                continue;
            }
            double score = targetDamage * 10.0 - selfDamage;
            if (targetDamage >= totalHealth(target)) score += 1000.0;
            DamageProfile candidate = new DamageProfile(
                target,
                targetDamage,
                selfDamage,
                score
            );
            if (best == null || better(candidate, best)) best = candidate;
        }
        return best;
    }

    private boolean scoreStillSafe(
        Vec3d explosion,
        BlockPos obsidianOverride,
        PlayerEntity target
    ) {
        if (target == null || !target.isAlive() || !Friends.get().shouldAttack(target)) {
            return false;
        }
        if (target.isCreative()
            || target.isSpectator()
            || mc.player.squaredDistanceTo(target)
                > targetRange.get() * targetRange.get()) {
            return false;
        }
        return scoreExplosion(explosion, obsidianOverride, List.of(target)) != null;
    }

    private float conservativeDamage(
        PlayerEntity entity,
        Vec3d explosion,
        BlockPos obsidianOverride,
        boolean target
    ) {
        DamageUtils.RaycastFactory exactFactory = (context, pos) -> {
            BlockState state = obsidianOverride != null && pos.equals(obsidianOverride)
                ? Blocks.OBSIDIAN.getDefaultState()
                : mc.world.getBlockState(pos);
            return state.getCollisionShape(mc.world, pos).raycast(
                context.start(),
                context.end(),
                pos
            );
        };

        float current = DamageUtils.crystalDamage(
            entity,
            entity.getEntityPos(),
            entity.getBoundingBox(),
            explosion,
            exactFactory
        );
        if (!predictMovement.get()) return current;

        Vec3d velocity = entity.getVelocity();
        float predicted = DamageUtils.crystalDamage(
            entity,
            entity.getEntityPos().add(velocity),
            entity.getBoundingBox().offset(velocity),
            explosion,
            exactFactory
        );
        return target ? Math.min(current, predicted) : Math.max(current, predicted);
    }

    private boolean canAttackCrystal(EndCrystalEntity crystal) {
        if (!mc.player.canAttackEntityIn(
            crystal.getBoundingBox(),
            0.0
        )) {
            return false;
        }
        double range = effectiveBreakRange();
        return squaredDistanceToBox(mc.player.getEyePos(), crystal.getBoundingBox())
            <= range * range;
    }

    private double effectiveBreakRange() {
        return breakRange.get();
    }

    private boolean isExistingBaseValid(BlockPos base) {
        BlockState state = mc.world.getBlockState(base);
        if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) return false;
        if (!mc.world.isAir(base.up())) return false;
        if (!mc.player.canInteractWithBlockAt(base, RANGE_EPSILON)) return false;
        if (squaredDistanceToBox(mc.player.getEyePos(), new Box(base))
            > placeRange.get() * placeRange.get()) {
            return false;
        }
        return placementSpaceClear(base);
    }

    private boolean canBecomeSupport(BlockPos base) {
        if (!mc.world.getBlockState(base).isReplaceable()
            || !mc.world.isAir(base.up())
            || !Blocks.OBSIDIAN.getDefaultState().canPlaceAt(mc.world, base)
            || !placementSpaceClear(base)) {
            return false;
        }
        return findSupportPlacementHit(base) != null;
    }

    private boolean canUseClientPacedSupport(BlockPos base) {
        if (!mc.player.canInteractWithBlockAt(base, RANGE_EPSILON)) return false;
        if (squaredDistanceToBox(mc.player.getEyePos(), new Box(base))
            > placeRange.get() * placeRange.get()) {
            return false;
        }
        return mc.world.isAir(base.up()) && placementSpaceClear(base);
    }

    private boolean placementSpaceClear(BlockPos base) {
        for (Entity entity : mc.world.getOtherEntities(null, crystalPlacementBox(base))) {
            if (entity instanceof EndCrystalEntity crystal) {
                if (!sync.get()) {
                    AttackRecord record = attacks.get(crystal.getId());
                    if (record != null && record.attempts >= maxAttempts.get()) continue;
                }
            }
            return false;
        }
        return true;
    }

    private BlockHitResult findSupportPlacementHit(BlockPos target) {
        for (Direction face : Direction.values()) {
            BlockPos clicked = target.offset(face.getOpposite());
            BlockState clickedState = mc.world.getBlockState(clicked);
            if (clickedState.isAir() || clickedState.isReplaceable()) continue;
            if (!mc.player.canInteractWithBlockAt(clicked, RANGE_EPSILON)) continue;
            if (squaredDistanceToBox(mc.player.getEyePos(), new Box(clicked))
                > placeRange.get() * placeRange.get()) {
                continue;
            }
            BlockHitResult hit = findTargetHit(clicked, face);
            if (hit != null) return hit;
        }
        return null;
    }

    private BlockHitResult findTargetHit(BlockPos clicked, Direction requiredFace) {
        BlockHitResult visible = visibleHit(clicked, requiredFace);
        if (visible != null) return visible;
        if (raycastMode.get() == RaycastMode.Visible) return null;

        Direction face = requiredFace == null
            ? choosePlausibleFace(clicked, mc.player.getEyePos())
            : requiredFace;
        if (face == null || !isPlausibleFace(clicked, face, mc.player.getEyePos())) {
            return null;
        }
        Vec3d point = placementPoint(clicked, face, 0);
        return new BlockHitResult(point, face, clicked, false);
    }

    private BlockHitResult findCrystalBaseHit(BlockPos base) {
        BlockHitResult top = findTargetHit(base, Direction.UP);
        return top != null ? top : findTargetHit(base, null);
    }

    private BlockHitResult visibleHit(BlockPos clicked, Direction requiredFace) {
        Vec3d eye = mc.player.getEyePos();
        Direction[] faces = requiredFace == null
            ? Direction.values()
            : new Direction[] {requiredFace};
        for (Direction face : faces) {
            for (int attempt = 0; attempt < MULTI_POINT_FIRST.length; attempt++) {
                Vec3d point = placementPoint(clicked, face, attempt);
                Vec3d inside = point.subtract(
                    face.getOffsetX() * 0.001,
                    face.getOffsetY() * 0.001,
                    face.getOffsetZ() * 0.001
                );
                BlockHitResult result = mc.world.raycast(new RaycastContext(
                    eye,
                    inside,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    mc.player
                ));
                if (result.getType() == HitResult.Type.BLOCK
                    && result.getBlockPos().equals(clicked)
                    && (requiredFace == null || result.getSide() == requiredFace)) {
                    return result;
                }
            }
        }
        return null;
    }

    private Vec3d placementPoint(BlockPos pos, Direction side, int attempt) {
        int ordinal = multiPointOrdinal(pos, side, placePointSequence, attempt);
        return facePoint(
            pos,
            side,
            MULTI_POINT_FIRST[ordinal],
            MULTI_POINT_SECOND[ordinal]
        );
    }

    static int multiPointOrdinal(
        BlockPos pos,
        Direction side,
        long sequence,
        int attempt
    ) {
        long mixed = (pos.asLong()
            ^ ((long) side.ordinal() * 0xC2B2AE3D27D4EB4FL))
            + sequence;
        int start = Math.floorMod(mixed, MULTI_POINT_FIRST.length);
        return Math.floorMod(start + attempt, MULTI_POINT_FIRST.length);
    }

    private Vec3d findBreakAim(EndCrystalEntity crystal) {
        Box box = crystal.getBoundingBox();
        if (raycastMode.get() == RaycastMode.Grim) return box.getCenter();

        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = (box.minY + box.maxY) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double xInset = Math.min(0.35, (box.maxX - box.minX) * 0.35);
        double yInset = Math.min(0.65, (box.maxY - box.minY) * 0.35);
        double zInset = Math.min(0.35, (box.maxZ - box.minZ) * 0.35);
        for (double y : new double[] {centerY, centerY - yInset, centerY + yInset}) {
            for (double x : new double[] {centerX, centerX - xInset, centerX + xInset}) {
                for (double z : new double[] {centerZ, centerZ - zInset, centerZ + zInset}) {
                    Vec3d aim = new Vec3d(x, y, z);
                    if (hasClearBlockRay(aim)) return aim;
                }
            }
        }
        return null;
    }

    private boolean hasClearBlockRay(Vec3d target) {
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(),
            target,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
        if (hit.getType() == HitResult.Type.MISS) return true;
        double targetDistance = mc.player.getEyePos().squaredDistanceTo(target);
        return mc.player.getEyePos().squaredDistanceTo(hit.getPos()) + 1.0E-4
            >= targetDistance;
    }

    private boolean holdAndGateBlock(BlockHitResult hit, double yaw, double pitch) {
        if (sentLookHitsBlock(hit)) {
            holdSentLook();
            lookGate.invalidate();
            return true;
        }
        if (!queueSteppedRotation(yaw, pitch)) {
            lookGate.invalidate();
            return false;
        }

        if (speedMode.get()) {
            lookGate.invalidate();
            return true;
        }

        if (!lookGate.readyFor(hit)) return false;

        boolean valid = sentLookHitsBlock(hit);
        if (!valid) lookGate.invalidate();
        return valid;
    }

    private boolean holdAndGateEntity(
        EndCrystalEntity crystal,
        double yaw,
        double pitch
    ) {
        if (sentLookHitsEntity(crystal)) {
            holdSentLook();
            lookGate.invalidate();
            return true;
        }
        if (!queueSteppedRotation(yaw, pitch)) {
            lookGate.invalidate();
            return false;
        }

        if (speedMode.get()) {
            lookGate.invalidate();
            return true;
        }

        if (!lookGate.readyForEntity(crystal.getId())) return false;

        boolean valid = sentLookHitsEntity(crystal);
        if (!valid) lookGate.invalidate();
        return valid;
    }

    private void dangerBreakFromEvent(BreakPlan plan) {
        if (mc.player == null || mc.world == null) return;
        if (!actionCooldownPassed(CrystalAction.Break, plan.base)) return;

        if (dangerMode.get()) {
            GrimCombatCoordinator.forceAllowNextAction();
        } else {
            if (!GrimCombatCoordinator.claimAction(this)) return;
        }

        int weaponSlot = weaknessWeaponSlot(plan.crystal);
        if (weaponSlot == Integer.MIN_VALUE) return;
        if (!prepareAttackSlot(weaponSlot)) return;

        Vec3d aim = findBreakAim(plan.crystal);
        if (aim == null) return;
        double[] rotation = rotationTo(aim);
        queueSteppedRotation(rotation[0], rotation[1]);

        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(
            plan.crystal, mc.player.isSneaking()
        ));
        mc.player.swingHand(Hand.MAIN_HAND);
        armActionCooldown(CrystalAction.Break, plan.base);

        AttackRecord record = attacks.computeIfAbsent(
            plan.crystal.getId(), ignored -> new AttackRecord()
        );
        record.attempts++;
        record.lastTick = clientTick;
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
        double nextYaw = steppedAngle(currentYaw, targetYaw, yawStep.get());
        double nextPitch = MathHelper.clamp(
            currentPitch + MathHelper.clamp(
                pitchDelta,
                -pitchStep.get(),
                pitchStep.get()
            ),
            -90.0,
            90.0
        );
        boolean finalRotation = Math.abs(yawDelta) <= yawStep.get() + 1.0E-4
            && Math.abs(pitchDelta) <= pitchStep.get() + 1.0E-4;
        queuedRotation = new RotationIntent((float) nextYaw, (float) nextPitch);
        return finalRotation;
    }

    private void holdSentLook() {
        queuedRotation = new RotationIntent(sentYaw, sentPitch);
    }

    /**
     * Installs a one-movement-cycle wrapper after every other input owner has
     * armed itself. Its tick delegates the real keyboard read first, remaps
     * that discrete input to the silent yaw, and only then lets vanilla run
     * movement physics. Grim therefore receives the same input/yaw pair that
     * produced the client position instead of a camera-yaw trajectory paired
     * with a spoofed movement packet.
     */
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
        rotationInput = new AuraRotationInput(
            player.input,
            player,
            intent
        );
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

    @Override
    public void suspendForItemUse() {
        restoreRotationInput();
        queuedRotation = null;
        lookGate.invalidate();
    }

    static PlayerInput remapPlayerInput(
        PlayerInput original,
        Vec2f movement,
        float cameraYaw,
        float silentYaw
    ) {
        PlayerInput source = original == null ? PlayerInput.DEFAULT : original;
        Vec2f sourceMovement = movement == null ? Vec2f.ZERO : movement;
        if (sourceMovement.lengthSquared() <= 1.0E-5f) {
            return new PlayerInput(
                false,
                false,
                false,
                false,
                source.jump(),
                source.sneak(),
                false
            );
        }

        float delta = (cameraYaw - silentYaw) * MathHelper.RADIANS_PER_DEGREE;
        float cos = MathHelper.cos(delta);
        float sin = MathHelper.sin(delta);
        int sideways = MathHelper.clamp(Math.round(
            sourceMovement.x * cos - sourceMovement.y * sin
        ), -1, 1);
        int forward = MathHelper.clamp(Math.round(
            sourceMovement.y * cos + sourceMovement.x * sin
        ), -1, 1);

        return new PlayerInput(
            forward > 0,
            forward < 0,
            sideways > 0,
            sideways < 0,
            source.jump(),
            source.sneak(),
            source.sprint() && forward > 0
        );
    }

    private static Vec2f movementFor(PlayerInput input) {
        float sideways = (input.left() ? 1.0f : 0.0f)
            - (input.right() ? 1.0f : 0.0f);
        float forward = (input.forward() ? 1.0f : 0.0f)
            - (input.backward() ? 1.0f : 0.0f);
        return new Vec2f(sideways, forward).normalize();
    }

    private boolean sentLookHitsBlock(BlockHitResult target) {
        if (!sentLookKnown || target == null || mc.player == null) return false;

        Vec3d eye = mc.player.getEyePos();
        double rayLength = placeRange.get();
        if (!rayIntersectsBox(
            eye,
            sentYaw,
            sentPitch,
            rayLength,
            new Box(target.getBlockPos())
        )) {
            return false;
        }
        if (raycastMode.get() == RaycastMode.Grim) return true;

        Vec3d end = eye.add(
            Vec3d.fromPolar(sentPitch, sentYaw).multiply(rayLength)
        );
        BlockHitResult visible = mc.world.raycast(new RaycastContext(
            eye,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
        return visible.getType() == HitResult.Type.BLOCK
            && visible.getBlockPos().equals(target.getBlockPos())
            && visible.getSide() == target.getSide();
    }

    private boolean sentLookHitsEntity(EndCrystalEntity crystal) {
        return sentLookKnown
            && crystal != null
            && mc.player != null
            && rayIntersectsBox(
                mc.player.getEyePos(),
                sentYaw,
                sentPitch,
                effectiveBreakRange(),
                crystal.getBoundingBox()
            );
    }

    private boolean actionWindowOpen(CrystalAction action, BlockPos base) {
        return actionCooldownPassed(action, base)
            && GrimCombatCoordinator.claimAction(this)
            && (!pauseOnUse.get()
                || (!mc.player.isUsingItem()
                    && (mc.currentScreen != null || !mc.options.useKey.isPressed())))
            && (!pauseOnMine.get()
                || mc.currentScreen != null
                || !mc.options.attackKey.isPressed());
    }

    private void pruneState() {
        attacks.entrySet().removeIf(entry -> {
            Entity entity = mc.world == null ? null : mc.world.getEntityById(entry.getKey());
            return !(entity instanceof EndCrystalEntity) || !entity.isAlive();
        });
        if (pendingCrystal != null
            && clientTick - pendingCrystal.sentTick > CLIENT_PACED_RENDER_TICKS) {
            pendingCrystal = null;
        }
    }

    private void pruneRenderHistory() {
        if (!renderEnabled.get() || !renderFade.get()) {
            renderHistory.clear();
            return;
        }
        renderHistory.removeIf(
            pulse -> clientTick - pulse.startedTick > fadeTime.get()
        );
    }

    private void recordRenderAction(RenderSnapshot snapshot) {
        if (snapshot == null || !renderEnabled.get() || !renderFade.get()) return;

        renderHistory.removeIf(pulse -> sameRenderTarget(pulse.snapshot, snapshot));
        if (renderHistory.size() >= 64) renderHistory.remove(0);
        renderHistory.add(new RenderPulse(snapshot, clientTick));
    }

    private static boolean sameRenderTarget(
        RenderSnapshot first,
        RenderSnapshot second
    ) {
        if (first.kind != second.kind) return false;
        Vec3d firstCenter = center(first.box);
        Vec3d secondCenter = center(second.box);
        return firstCenter.squaredDistanceTo(secondCenter) < 1.0E-4;
    }

    private static RenderSnapshot placeRenderSnapshot(PlacePlan plan) {
        BlockPos base = plan.base;
        return new RenderSnapshot(
            RenderKind.Place,
            new Box(base),
            new Vec3d(base.getX() + 0.5, base.getY() + 1.25, base.getZ() + 0.5),
            damageLabel(plan.damage)
        );
    }

    private static RenderSnapshot breakRenderSnapshot(BreakPlan plan) {
        Box box = plan.crystal.getBoundingBox();
        Box snapshot = new Box(
            box.minX,
            box.minY,
            box.minZ,
            box.maxX,
            box.maxY,
            box.maxZ
        );
        Vec3d center = center(snapshot);
        return new RenderSnapshot(
            RenderKind.Break,
            snapshot,
            new Vec3d(center.x, snapshot.maxY + 0.2, center.z),
            damageLabel(plan.damage)
        );
    }

    private static String damageLabel(DamageProfile damage) {
        return String.format(
            Locale.ROOT,
            "T %.1f | S %.1f",
            damage.targetDamage,
            damage.selfDamage
        );
    }

    private static Vec3d center(Box box) {
        return new Vec3d(
            (box.minX + box.maxX) * 0.5,
            (box.minY + box.maxY) * 0.5,
            (box.minZ + box.maxZ) * 0.5
        );
    }



    private int findHotbarSlot(Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(item)) return slot;
        }
        return -1;
    }

    private int chooseDestination() {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getStack(selected).isEmpty()) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return slot;
        }
        return selected;
    }

    private static boolean better(DamageProfile candidate, DamageProfile current) {
        if (Math.abs(candidate.score - current.score) > 1.0E-4) {
            return candidate.score > current.score;
        }
        if (Math.abs(candidate.targetDamage - current.targetDamage) > 1.0E-4) {
            return candidate.targetDamage > current.targetDamage;
        }
        if (Math.abs(candidate.selfDamage - current.selfDamage) > 1.0E-4) {
            return candidate.selfDamage < current.selfDamage;
        }
        return candidate.target.getId() < current.target.getId();
    }

    private static boolean sameScore(DamageProfile first, DamageProfile second) {
        return Math.abs(first.score - second.score) <= 1.0E-4;
    }

    private static boolean sameScore(double first, double second) {
        return Math.abs(first - second) <= 1.0E-4;
    }

    private static int comparePos(BlockPos first, BlockPos second) {
        int y = Integer.compare(first.getY(), second.getY());
        if (y != 0) return y;
        int x = Integer.compare(first.getX(), second.getX());
        return x != 0 ? x : Integer.compare(first.getZ(), second.getZ());
    }

    private boolean actionCooldownPassed(CrystalAction action, BlockPos base) {
        long now = System.nanoTime();
        long actionDeadline = action == CrystalAction.Place
            ? nextPlaceActionAtNanos
            : nextBreakActionAtNanos;
        BlockPos lastBase = action == CrystalAction.Place
            ? lastPlaceDelayBase
            : lastBreakDelayBase;
        return baseDelayPassed(lastBase, base, actionDeadline, now);
    }

    private void armActionCooldown(CrystalAction action, BlockPos base) {
        long now = System.nanoTime();
        if (action == CrystalAction.Place) {
            lastPlaceDelayBase = base.toImmutable();
            nextPlaceActionAtNanos = now
                + randomDelayNanos(minPlaceDelayMs.get(), maxPlaceDelayMs.get());
        } else {
            lastBreakDelayBase = base.toImmutable();
            nextBreakActionAtNanos = now
                + randomDelayNanos(minBreakDelayMs.get(), maxBreakDelayMs.get());
        }
    }

    static boolean baseDelayPassed(
        BlockPos lastBase,
        BlockPos candidateBase,
        long deadline,
        long now
    ) {
        return candidateBase != null
            && (lastBase == null
                || !lastBase.equals(candidateBase)
                || deadlinePassed(deadline, now));
    }

    private static boolean deadlinePassed(long deadline, long now) {
        return deadline == 0L || now - deadline >= 0L;
    }

    static long randomDelayNanos(int minimumMs, int maximumMs) {
        int lower = Math.max(0, Math.min(minimumMs, maximumMs));
        int upper = Math.max(0, Math.max(minimumMs, maximumMs));
        long delayMs = lower == upper
            ? lower
            : ThreadLocalRandom.current().nextLong(lower, (long) upper + 1L);
        return delayMs * 1_000_000L;
    }

    private static Vec3d crystalSpawn(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    private static Box crystalPlacementBox(BlockPos base) {
        BlockPos up = base.up();
        return new Box(
            up.getX(),
            up.getY(),
            up.getZ(),
            up.getX() + 1.0,
            up.getY() + 2.0,
            up.getZ() + 1.0
        );
    }

    private static boolean crystalMatchesBase(EndCrystalEntity crystal, BlockPos base) {
        Vec3d expected = crystalSpawn(base);
        return crystal.getEntityPos().squaredDistanceTo(expected) < 0.26;
    }

    private static Vec3d faceCenter(BlockPos pos, Direction side) {
        return new Vec3d(
            pos.getX() + 0.5 + side.getOffsetX() * 0.5,
            pos.getY() + 0.5 + side.getOffsetY() * 0.5,
            pos.getZ() + 0.5 + side.getOffsetZ() * 0.5
        );
    }

    private static Vec3d facePoint(
        BlockPos pos,
        Direction side,
        double first,
        double second
    ) {
        Vec3d center = faceCenter(pos, side);
        return switch (side.getAxis()) {
            case Y -> center.add(first, 0.0, second);
            case Z -> center.add(first, second, 0.0);
            case X -> center.add(0.0, second, first);
        };
    }

    static Direction choosePlausibleFace(BlockPos pos, Vec3d eye) {
        Direction best = null;
        double bestSeparation = Double.NEGATIVE_INFINITY;
        for (Direction side : Direction.values()) {
            if (!isPlausibleFace(pos, side, eye)) continue;
            double separation = switch (side) {
                case DOWN -> pos.getY() - eye.y;
                case UP -> eye.y - (pos.getY() + 1.0);
                case NORTH -> pos.getZ() - eye.z;
                case SOUTH -> eye.z - (pos.getZ() + 1.0);
                case WEST -> pos.getX() - eye.x;
                case EAST -> eye.x - (pos.getX() + 1.0);
            };
            if (separation > bestSeparation) {
                bestSeparation = separation;
                best = side;
            }
        }
        return best;
    }

    static boolean isPlausibleFace(BlockPos pos, Direction side, Vec3d eye) {
        return switch (side) {
            case DOWN -> eye.y <= pos.getY() + FACE_TOLERANCE;
            case UP -> eye.y >= pos.getY() + 1.0 - FACE_TOLERANCE;
            case NORTH -> eye.z <= pos.getZ() + FACE_TOLERANCE;
            case SOUTH -> eye.z >= pos.getZ() + 1.0 - FACE_TOLERANCE;
            case WEST -> eye.x <= pos.getX() + FACE_TOLERANCE;
            case EAST -> eye.x >= pos.getX() + 1.0 - FACE_TOLERANCE;
        };
    }

    static double steppedAngle(double current, double target, double maxStep) {
        return current + MathHelper.clamp(
            MathHelper.wrapDegrees(target - current),
            -maxStep,
            maxStep
        );
    }

    static boolean rayIntersectsBox(
        Vec3d eye,
        float yaw,
        float pitch,
        double distance,
        Box box
    ) {
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(distance));
        return box.contains(eye) || box.raycast(eye, end).isPresent();
    }

    private static double[] rotationTo(Vec3d target) {
        Vec3d eye = net.minecraft.client.MinecraftClient.getInstance().player.getEyePos();
        double x = target.x - eye.x;
        double y = target.y - eye.y;
        double z = target.z - eye.z;
        double horizontal = Math.sqrt(x * x + z * z);
        return new double[] {
            Math.toDegrees(Math.atan2(z, x)) - 90.0,
            -Math.toDegrees(Math.atan2(y, horizontal))
        };
    }

    private static double squaredDistanceToBox(Vec3d point, Box box) {
        double x = Math.max(box.minX - point.x, Math.max(0.0, point.x - box.maxX));
        double y = Math.max(box.minY - point.y, Math.max(0.0, point.y - box.maxY));
        double z = Math.max(box.minZ - point.z, Math.max(0.0, point.z - box.maxZ));
        return x * x + y * y + z * z;
    }

    private static double totalHealth(PlayerEntity player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    public enum RaycastMode {
        Visible,
        Grim
    }

    public enum PickItemMode {
        Silent,
        Pick,
        None
    }

    private enum CrystalAction {
        Place,
        Break
    }

    private record HeldItem(Hand hand, int slot) {
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
            PlayerInput corrected = remapPlayerInput(
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

    private record DamageProfile(
        PlayerEntity target,
        float targetDamage,
        float selfDamage,
        double score
    ) {
    }

    private record BreakPlan(
        EndCrystalEntity crystal,
        BlockPos base,
        Vec3d aim,
        DamageProfile damage,
        double adaptiveScore
    ) {
    }

    private record PlacePlan(
        BlockPos base,
        BlockHitResult hit,
        boolean support,
        DamageProfile damage,
        double adaptiveScore
    ) {
    }

    private enum RenderKind {
        Place,
        Break
    }

    private record RenderSnapshot(
        RenderKind kind,
        Box box,
        Vec3d label,
        String damageLabel
    ) {
    }

    private record RenderPulse(RenderSnapshot snapshot, int startedTick) {
    }

    private static final class AttackRecord {
        private int attempts;
        private int lastTick;
        private RenderSnapshot render;
    }

    private static final class PendingSupport {
        private final BlockPos base;
        private final int sentTick;
        private final RenderSnapshot render;

        private PendingSupport(
            BlockPos base,
            int sentTick,
            RenderSnapshot render
        ) {
            this.base = base;
            this.sentTick = sentTick;
            this.render = render;
        }
    }

    private record PendingCrystal(
        BlockPos base,
        int sentTick,
        RenderSnapshot render,
        EndCrystalEntity dummyEntity
    ) {
    }

    private static final class SupportState {
        public final BlockPos pos;
        public final LongArrayList placeTimes = new LongArrayList();
        public final LongArrayList breakTimes = new LongArrayList();
        public int consecutiveUses;
        
        public SupportState(BlockPos pos) {
            this.pos = pos;
        }

        public void recordPlace(long now) {
            placeTimes.add(now);
        }

        public void recordBreak(long now) {
            breakTimes.add(now);
        }

        public int recentPlaces(long now, long windowMs) {
            long threshold = now - windowMs;
            int count = 0;
            for (int i = placeTimes.size() - 1; i >= 0; i--) {
                if (placeTimes.getLong(i) >= threshold) count++;
                else break;
            }
            return count;
        }

        public int recentBreaks(long now, long windowMs) {
            long threshold = now - windowMs;
            int count = 0;
            for (int i = breakTimes.size() - 1; i >= 0; i--) {
                if (breakTimes.getLong(i) >= threshold) count++;
                else break;
            }
            return count;
        }

        public long lastActivity() {
            long lastPlace = placeTimes.isEmpty() ? 0 : placeTimes.getLong(placeTimes.size() - 1);
            long lastBreak = breakTimes.isEmpty() ? 0 : breakTimes.getLong(breakTimes.size() - 1);
            return Math.max(lastPlace, lastBreak);
        }
    }
}
