package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class SpawnerDropper extends AliceModule {
    private static final double REACH = 4.5;
    private static final int CONTENT_LAST_SLOT = 44;
    private static final int DROP_ALL_SLOT = 52;
    private static final int SELL_ALL_SLOT = 50;
    private static final int OPEN_TIMEOUT_TICKS = 100;
    private static final int HUD_BACKGROUND = 0xB4141414;
    private static final int HUD_ACCENT = 0xFF55FFAA;
    private static final int HUD_TEXT = 0xFFFFFFFF;
    private static final int HUD_STATE = 0xFF78DCFF;
    private static final Vec3d[] AIM_OFFSETS = {
        new Vec3d(0.5, 0.5, 0.5),
        new Vec3d(0.5, 0.25, 0.5),
        new Vec3d(0.5, 0.75, 0.5),
        new Vec3d(0.25, 0.5, 0.5),
        new Vec3d(0.75, 0.5, 0.5),
        new Vec3d(0.5, 0.5, 0.25),
        new Vec3d(0.5, 0.5, 0.75)
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<List<Item>> itemsToDrop = sgGeneral.add(
        new ItemListSetting.Builder()
            .name("items-to-drop")
            .description("Items that may be dropped from the spawner GUI.")
            .defaultValue(Items.BLAZE_ROD, Items.LEATHER, Items.BONE)
            .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(
        new IntSetting.Builder()
            .name("delay")
            .description("tick giua luc mo lai GUI va thao tac ranh")
            .defaultValue(4)
            .min(1)
            .max(200)
            .sliderMax(40)
            .build()
    );

    private final Setting<Integer> sessionWaitingSeconds = sgGeneral.add(
        new IntSetting.Builder()
            .name("session-waiting-seconds")
            .description("so giay phai cho sau khi ban tat ca truoc khi bat dau dot khac")
            .defaultValue(60)
            .min(1)
            .max(3_600)
            .sliderMax(300)
            .build()
    );

    private final Setting<Boolean> showHud = sgHud.add(
        new BoolSetting.Builder()
            .name("show-hud")
            .description("hien thi thong ke cua SpawnerDropper")
            .defaultValue(true)
            .build()
    );

    private final Set<Screen> hudRegisteredScreens =
        Collections.newSetFromMap(new WeakHashMap<>());

    private State state = State.Scanning;
    private long clientTick;
    private long actionAtTick;
    private long sessionStartedAtMs;
    private long stacksDropped;
    private long itemsDropped;
    private BlockPos targetSpawner;
    private int activeSyncId = -1;
    private SpawnerDropperLogic.CyclePhase phase =
        SpawnerDropperLogic.CyclePhase.DropAll;
    private PendingDrop pendingDrop;

    public SpawnerDropper() {
        super(
            AliceAddon.CATEGORY,
            "SpawnerDropper",
            "Alternates Drop All and Sell All across spawner GUI sessions."
        );
        registerHandledScreenHud();
    }

    @Override
    public void onActivate() {
        super.onActivate();
        clientTick = 0L;
        sessionStartedAtMs = System.currentTimeMillis();
        stacksDropped = 0L;
        itemsDropped = 0L;
        targetSpawner = null;
        activeSyncId = -1;
        phase = SpawnerDropperLogic.CyclePhase.DropAll;
        pendingDrop = null;
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        transition(State.Scanning, 0);
        registerHudForScreen(mc.currentScreen, mc);
    }

    @Override
    public void onDeactivate() {
        state = State.Inactive;
        pendingDrop = null;
        targetSpawner = null;
        activeSyncId = -1;
        phase = SpawnerDropperLogic.CyclePhase.DropAll;
        super.onDeactivate();
    }

    @Override
    public String getInfoString() {
        return isActive() ? stateLabel() : null;
    }

    @AliceHandler
    private void onTick(TickEvent.Post event) {
        clientTick++;
        if (
            !isActive()
                || mc.player == null
                || mc.world == null
                || mc.interactionManager == null
        ) {
            return;
        }

        switch (state) {
            case Scanning -> openNearestSpawner();
            case Opening -> waitForOpenedGui();
            case Inspecting -> inspectOpenedGui();
            case WaitingReopen -> reopenWhenReady();
            case WaitingJunkThrow -> throwJunkItemsWhenReady();
            case WaitingJunkSellAll -> sellJunkBatchWhenReady();
            case SessionWaiting -> finishSessionWaitWhenReady();
            default -> {
            }
        }
    }

    @AliceHandler
    private void onPacket(PacketEvent.Receive event) {
        int syncId;
        int revision;
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket packet) {
            syncId = packet.getSyncId();
            revision = packet.getRevision();
            if (
                packet.getSlot() < 0
                    || packet.getSlot() > CONTENT_LAST_SLOT
            ) {
                return;
            }
            if (
                pendingDrop != null
                    && pendingDrop.syncId == syncId
                    && revision >= pendingDrop.baseRevision
            ) {
                applyConfirmedDelta(
                    pendingDrop.accounting.reconcileSlot(
                        slotView(packet.getSlot(), packet.getStack())
                    )
                );
            }
        } else if (event.packet instanceof InventoryS2CPacket packet) {
            syncId = packet.syncId();
            revision = packet.revision();
            if (
                pendingDrop != null
                    && pendingDrop.syncId == syncId
                    && revision >= pendingDrop.baseRevision
            ) {
                applyConfirmedDelta(
                    pendingDrop.accounting.reconcile(
                        packetContentSnapshot(packet.contents())
                    )
                );
            }
        } else {
            return;
        }
    }

    @AliceHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.currentScreen instanceof HandledScreen<?>) return;
        renderHud(event.drawContext);
    }

    private void openNearestSpawner() {
        targetSpawner = findNearestReachableSpawner();
        if (targetSpawner == null) return;

        if (!silentInteract(targetSpawner)) {
            targetSpawner = null;
            return;
        }
        transition(State.Opening, OPEN_TIMEOUT_TICKS);
    }

    private void waitForOpenedGui() {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            GenericContainerScreenHandler handler = screen.getScreenHandler();
            activeSyncId = handler.syncId;
            pendingDrop = null;
            transition(State.Inspecting, 0);
            return;
        }
        if (ready()) resetForScan();
    }

    private void inspectOpenedGui() {
        GenericContainerScreenHandler handler = activeContainer();
        if (handler == null) {
            resetForScan();
            return;
        }

        List<SpawnerDropperLogic.SlotView> all = contentSnapshot(handler, false);
        Set<Item> selected = new HashSet<>(itemsToDrop.get());
        SpawnerDropperLogic.GuiAction action = SpawnerDropperLogic.actionFor(
            phase,
            SpawnerDropperLogic.classify(all, selected)
        );
        if (action == SpawnerDropperLogic.GuiAction.StartJunkDrop) {
            transition(State.WaitingJunkThrow, delay.get());
            return;
        }

        if (action == SpawnerDropperLogic.GuiAction.DropAll) {
            List<SpawnerDropperLogic.SlotView> selectedSnapshot =
                contentSnapshot(handler, true);
            beginDropAccounting(handler, selectedSnapshot);
            if (!click(handler, DROP_ALL_SLOT, 0, SlotActionType.PICKUP)) {
                pendingDrop = null;
                resetForScan();
                return;
            }
            phase = SpawnerDropperLogic.nextPhase(phase);
            closeActiveGui();
            transition(State.WaitingReopen, delay.get());
            return;
        }

        pendingDrop = null;
        if (!click(handler, SELL_ALL_SLOT, 0, SlotActionType.PICKUP)) {
            resetForScan();
            return;
        }
        phase = SpawnerDropperLogic.nextPhase(phase);
        closeActiveGui();
        transition(State.SessionWaiting, sessionWaitingSeconds.get() * 20);
    }

    private void reopenWhenReady() {
        if (!ready()) return;
        targetSpawner = findNearestReachableSpawner();
        if (targetSpawner == null) {
            transition(State.Scanning, 0);
            return;
        }
        if (!silentInteract(targetSpawner)) {
            transition(State.Scanning, 0);
            return;
        }
        transition(State.Opening, OPEN_TIMEOUT_TICKS);
    }

    private void throwJunkItemsWhenReady() {
        if (!ready()) return;
        GenericContainerScreenHandler handler = activeContainer();
        if (handler == null) {
            resetForScan();
            return;
        }
        Set<Item> selected = new HashSet<>(itemsToDrop.get());
        throwSelectedStacksSameTick(handler, selected);
        transition(State.WaitingJunkSellAll, delay.get());
    }

    private void sellJunkBatchWhenReady() {
        if (!ready()) return;
        GenericContainerScreenHandler handler = activeContainer();
        if (handler == null) {
            resetForScan();
            return;
        }
        pendingDrop = null;
        if (!click(handler, SELL_ALL_SLOT, 0, SlotActionType.PICKUP)) {
            resetForScan();
            return;
        }
        phase = SpawnerDropperLogic.CyclePhase.DropAll;
        closeActiveGui();
        transition(State.SessionWaiting, sessionWaitingSeconds.get() * 20);
    }

    private void finishSessionWaitWhenReady() {
        if (!ready()) return;
        if (mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        resetForScan();
    }

    private void throwSelectedStacksSameTick(
        GenericContainerScreenHandler handler,
        Set<Item> selected
    ) {
        List<SpawnerDropperLogic.SlotView> selectedSnapshot =
            contentSnapshot(handler, true);
        beginDropAccounting(handler, selectedSnapshot);
        for (SpawnerDropperLogic.SlotView slot : selectedSnapshot) {
            if (!selected.contains((Item) slot.item())) continue;
            click(handler, slot.slot(), 1, SlotActionType.THROW);
        }
    }

    private void beginDropAccounting(
        GenericContainerScreenHandler handler,
        List<SpawnerDropperLogic.SlotView> selectedSnapshot
    ) {
        pendingDrop = new PendingDrop(
            handler.syncId,
            handler.getRevision(),
            SpawnerDropperLogic.beginAccounting(selectedSnapshot)
        );
    }

    private void applyConfirmedDelta(
        SpawnerDropperLogic.ConfirmedDelta delta
    ) {
        itemsDropped += delta.items();
        stacksDropped += delta.stacks();
    }

    private SpawnerDropperLogic.SlotView slotView(int slot, ItemStack stack) {
        return new SpawnerDropperLogic.SlotView(
            slot,
            stack.isEmpty() ? Items.AIR : stack.getItem(),
            stack.isEmpty() ? 0 : stack.getCount()
        );
    }

    private List<SpawnerDropperLogic.SlotView> packetContentSnapshot(
        List<ItemStack> contents
    ) {
        int limit = Math.min(CONTENT_LAST_SLOT + 1, contents.size());
        List<SpawnerDropperLogic.SlotView> snapshot = new ArrayList<>(limit);
        for (int slot = 0; slot < limit; slot++) {
            snapshot.add(slotView(slot, contents.get(slot)));
        }
        return snapshot;
    }

    private List<SpawnerDropperLogic.SlotView> contentSnapshot(
        GenericContainerScreenHandler handler,
        boolean selectedOnly
    ) {
        Set<Item> selected = selectedOnly
            ? new HashSet<>(itemsToDrop.get())
            : Collections.emptySet();
        int limit = Math.min(CONTENT_LAST_SLOT + 1, handler.slots.size());
        List<SpawnerDropperLogic.SlotView> snapshot = new ArrayList<>();
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            if (selectedOnly && !selected.contains(stack.getItem())) continue;
            snapshot.add(
                new SpawnerDropperLogic.SlotView(
                    slot,
                    stack.getItem(),
                    stack.getCount()
                )
            );
        }
        return snapshot;
    }

    private boolean click(
        GenericContainerScreenHandler handler,
        int slot,
        int button,
        SlotActionType action
    ) {
        if (slot < 0 || slot >= handler.slots.size()) return false;
        mc.interactionManager.clickSlot(
            handler.syncId,
            slot,
            button,
            action,
            mc.player
        );
        return true;
    }

    private GenericContainerScreenHandler currentContainer() {
        return mc.player.currentScreenHandler
            instanceof GenericContainerScreenHandler handler
            ? handler
            : null;
    }

    private GenericContainerScreenHandler activeContainer() {
        GenericContainerScreenHandler handler = currentContainer();
        return handler != null && handler.syncId == activeSyncId ? handler : null;
    }

    private void closeActiveGui() {
        if (activeContainer() != null) mc.player.closeHandledScreen();
        activeSyncId = -1;
    }

    private void resetForScan() {
        targetSpawner = null;
        activeSyncId = -1;
        pendingDrop = null;
        transition(State.Scanning, 0);
    }

    private void transition(State next, int delayTicks) {
        state = next;
        actionAtTick = clientTick + Math.max(0, delayTicks);
    }

    private boolean ready() {
        return clientTick >= actionAtTick;
    }

    private BlockPos findNearestReachableSpawner() {
        BlockPos origin = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!mc.world.getBlockState(pos).isOf(Blocks.SPAWNER)) continue;
                    double distance = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distance > REACH * REACH || distance >= nearestDistance) continue;
                    if (findHit(pos) == null) continue;
                    nearest = pos;
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    private boolean silentInteract(BlockPos pos) {
        BlockHitResult hit = findHit(pos);
        if (hit == null || mc.player.squaredDistanceTo(hit.getPos()) > REACH * REACH) {
            return false;
        }

        float originalYaw = mc.player.getYaw();
        float originalPitch = mc.player.getPitch();
        float[] rotation = rotationTo(hit.getPos());
        float silentYaw = SpawnerDropperLogic.nearestEquivalentYaw(
            originalYaw,
            rotation[0]
        );
        float restoreYaw = SpawnerDropperLogic.nearestEquivalentYaw(
            silentYaw,
            originalYaw
        );
        sendLook(silentYaw, rotation[1]);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        sendLook(restoreYaw, originalPitch);
        return true;
    }

    private void sendLook(float yaw, float pitch) {
        mc.player.networkHandler.sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(
                yaw,
                pitch,
                mc.player.isOnGround(),
                mc.player.horizontalCollision
            )
        );
    }

    private float[] rotationTo(Vec3d target) {
        Vec3d eyes = mc.player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-dx, dz)),
            (float) -Math.toDegrees(Math.atan2(dy, horizontal))
        };
    }

    private BlockHitResult findHit(BlockPos pos) {
        Vec3d eyes = mc.player.getEyePos();
        for (Vec3d offset : AIM_OFFSETS) {
            Vec3d aim = new Vec3d(
                pos.getX() + offset.x,
                pos.getY() + offset.y,
                pos.getZ() + offset.z
            );
            BlockHitResult result = mc.world.raycast(
                new RaycastContext(
                    eyes,
                    aim,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,
                    mc.player
                )
            );
            if (
                result.getType() == HitResult.Type.BLOCK
                    && result.getBlockPos().equals(pos)
            ) {
                return result;
            }
        }
        return null;
    }

    private String stateLabel() {
        if (state == State.SessionWaiting) {
            long ticks = Math.max(0L, actionAtTick - clientTick);
            long seconds = (ticks + 19L) / 20L;
            return "Session Waiting (" + seconds + "s)";
        }
        return switch (state) {
            case Scanning -> "Scanning (" + phaseLabel() + ")";
            case Opening -> "Opening (" + phaseLabel() + ")";
            case Inspecting -> "Inspecting (" + phaseLabel() + ")";
            case WaitingReopen -> "Reopening (" + phaseLabel() + ")";
            case WaitingJunkThrow -> "Junk Drop";
            case WaitingJunkSellAll -> "Junk Sell All";
            case SessionWaiting -> "Session Waiting";
            case Inactive -> "Inactive";
        };
    }

    private String phaseLabel() {
        return phase == SpawnerDropperLogic.CyclePhase.DropAll
            ? "Drop All"
            : "Sell All";
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
        if (!(screen instanceof HandledScreen<?>) || !hudRegisteredScreens.add(screen)) {
            return;
        }
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
        if (!isActive() || !showHud.get() || mc.player == null || context == null) {
            return;
        }
        String[] lines = {
            "Stacks Dropped: " + stacksDropped,
            "Items Dropped: " + itemsDropped,
            "State: " + stateLabel(),
            "Uptime: " + formatDuration(System.currentTimeMillis() - sessionStartedAtMs)
        };
        int[] colors = {HUD_TEXT, HUD_TEXT, HUD_STATE, HUD_TEXT};
        int padding = 4;
        int lineGap = 2;
        int lineHeight = mc.textRenderer.fontHeight;
        int width = 0;
        for (String line : lines) width = Math.max(width, mc.textRenderer.getWidth(line));
        width += padding * 2;
        int height = lines.length * lineHeight
            + (lines.length - 1) * lineGap
            + padding * 2
            + 2;
        int x = mc.getWindow().getScaledWidth() / 2 - width - 10;
        int y = mc.getWindow().getScaledHeight() / 2 - height - 10;

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

    private static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1_000L);
        long hours = seconds / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        long remaining = seconds % 60L;
        if (hours > 0L) return hours + "h " + minutes + "m " + remaining + "s";
        if (minutes > 0L) return minutes + "m " + remaining + "s";
        return remaining + "s";
    }

    private enum State {
        Inactive,
        Scanning,
        Opening,
        Inspecting,
        WaitingReopen,
        WaitingJunkThrow,
        WaitingJunkSellAll,
        SessionWaiting
    }

    private static final class PendingDrop {
        private final int syncId;
        private final int baseRevision;
        private final SpawnerDropperLogic.PendingAccounting accounting;
        private PendingDrop(
            int syncId,
            int baseRevision,
            SpawnerDropperLogic.PendingAccounting accounting
        ) {
            this.syncId = syncId;
            this.baseRevision = baseRevision;
            this.accounting = accounting;
        }
    }
}
