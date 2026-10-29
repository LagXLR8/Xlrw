package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class OrderDropper extends AliceModule {
    private static final int OPEN_ORDERS_SLOT = 51;
    private static final int FIRST_ORDER_SLOT = 0;
    private static final int LAST_ORDER_SLOT = 25;
    private static final int DROP_45_SLOT = 15;
    private static final int DROP_1_SLOT = 16;
    private static final long GUI_TIMEOUT_MS = 5_000L;
    private static final int RECONNECT_FLOW_GRACE_TICKS = 80;

    private static final int HUD_BACKGROUND = 0xB4141414;
    private static final int HUD_ACCENT = 0xFF55FFAA;
    private static final int HUD_TEXT = 0xFFFFFFFF;
    private static final int HUD_COUNT = 0xFF78FF78;
    private static final int HUD_PROGRESS = 0xFF78DCFF;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("thoi gian tre (tick) ap dung doc lap sau moi trang thai dieu huong")
        .defaultValue(10)
        .min(0)
        .max(100)
        .sliderMax(40)
        .build());

    private final Setting<Integer> dropDelay = sgGeneral.add(new IntSetting.Builder()
        .name("drop-delay")
        .description("thoi gian tre toi thieu (ms) giua cac lan bam vut do tren GUI")
        .defaultValue(1_000)
        .min(0)
        .max(60_000)
        .sliderMax(5_000)
        .build());

    private final Setting<Boolean> autoReconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-reconnect")
        .description("bat meteor autoreconnect va alice dang nhap, sau do tiep tuc vut do")
        .defaultValue(true)
        .onChanged(ignored -> onAutoReconnectChanged())
        .build());

    private final Setting<DropMode> dropMode = sgGeneral.add(new EnumSetting.Builder<DropMode>()
        .name("drop-mode")
        .description("chon xem moi lan xac nhan tu server la vut 45 stack hay 1 stack")
        .defaultValue(DropMode.FortyFiveStacks)
        .build());

    private final Setting<CountMode> countMode = sgGeneral.add(new EnumSetting.Builder<CountMode>()
        .name("drop-count-mode")
        .description("vut cho den khi het do hoac dung lai sau mot so luong nhat dinh")
        .defaultValue(CountMode.Infinity)
        .build());

    private final Setting<Integer> count = sgGeneral.add(new IntSetting.Builder()
        .name("count")
        .description("so luong lo hang duoc xac nhan truoc khi hoan thanh")
        .defaultValue(10)
        .min(1)
        .max(100_000)
        .sliderMax(100)
        .visible(() -> countMode.get() == CountMode.MaxAttempt)
        .build());

    private final Setting<Item> selectedItem = sgGeneral.add(new ItemSetting.Builder()
        .name("select-item")
        .description("chon item o slot tu 0 den 25 de order")
        .defaultValue(Items.BONE)
        .build());

    private final Setting<Boolean> showHud = sgHud.add(new BoolSetting.Builder()
        .name("show-hud")
        .description("hien thi thong ke Order Dropper")
        .defaultValue(true)
        .build());

    private final OrderDropperLogic.TickDelay stateDelay =
        new OrderDropperLogic.TickDelay();
    private final OrderDropperLogic.ConfirmationGate dropConfirmation =
        new OrderDropperLogic.ConfirmationGate();
    private final Map<Integer, Integer> nearbyItemBaseline = new HashMap<>();
    private final Set<Integer> exhaustedListingSlots = new HashSet<>();
    private final Set<Screen> hudRegisteredScreens =
        Collections.newSetFromMap(new WeakHashMap<>());

    private State state = State.Inactive;
    private int currentListingSlot = -1;
    private long stateEnteredAtMs;
    private long lastDropClickAtMs;
    private long sessionStartedAtMs;
    private long sessionFinishedAtMs;
    private long confirmedAttempts;
    private long droppedStacks;
    private long droppedItems;
    private DropMode pendingDropMode = DropMode.FortyFiveStacks;
    private boolean reconnectFlowObserved;
    private int reconnectFlowWaitTicks;

    public OrderDropper() {
        super(
            AliceAddon.CATEGORY,
            "Order Dropper",
            "Receives selected order items through the server GUI and tracks confirmed drops."
        );
        registerHandledScreenHud();
    }

    @Override
    public void onActivate() {
        super.onActivate();
        exhaustedListingSlots.clear();
        currentListingSlot = -1;
        lastDropClickAtMs = 0L;
        sessionStartedAtMs = System.currentTimeMillis();
        sessionFinishedAtMs = 0L;
        confirmedAttempts = 0L;
        droppedStacks = 0L;
        droppedItems = 0L;
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        clearDropConfirmation();
        pendingDropMode = dropMode.get();
        stateDelay.clear();
        registerHudForScreen(mc.currentScreen, mc);
        syncReconnectModules();
        beginOrderFlow();
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        state = State.Inactive;
        clearDropConfirmation();
        stateDelay.clear();
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
    }

    @AliceHandler
    public void onTick(TickEvent.Pre event) {
        if (state == State.Completed || state == State.Error || state == State.Inactive) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (tickReconnectPause()) return;

        if (state == State.Dropping && pollDropConfirmation() && isTerminal()) return;
        if (countMode.get() == CountMode.MaxAttempt && confirmedAttempts >= count.get()) {
            complete("Maximum confirmed attempts reached.");
            return;
        }
        if (!stateDelay.tick()) return;

        switch (state) {
            case SendOrder -> beginOrderFlow();
            case WaitOrderGui -> waitForOrderGui();
            case FindItem -> findAndSelectItem();
            case Dropping -> clickDropWhenReady();
            default -> {
            }
        }
    }

    @AliceHandler
    public void onGameLeft(GameLeftEvent event) {
        if (!isActive() || isTerminal() || !autoReconnect.get()) return;

        state = State.WaitingReconnect;
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        clearDropConfirmation();
        stateDelay.clear();
    }

    @AliceHandler
    public void onGameJoined(GameJoinedEvent event) {
        if (!isActive() || isTerminal()) return;

        syncReconnectModules();
        currentListingSlot = -1;
        lastDropClickAtMs = 0L;
        clearDropConfirmation();
        stateDelay.clear();
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;

        if (autoReconnect.get()) {
            state = State.WaitingReconnect;
        } else {
            state = State.SendOrder;
            stateEnteredAtMs = System.currentTimeMillis();
            stateDelay.arm(delay.get());
        }
    }

    @AliceHandler
    public void onEntityAdded(EntityAddedEvent event) {
        if (!isActive() || state != State.Dropping) return;
        if (mc.player == null || !(event.entity instanceof ItemEntity itemEntity)) return;

        ItemStack stack = itemEntity.getStack();
        if (!dropConfirmation.confirm(
            stack.isOf(selectedItem.get()),
            OrderDropperLogic.isWithinDropRadius(itemEntity.squaredDistanceTo(mc.player))
        )) return;
        recordConfirmedBatch();
    }

    private void recordConfirmedBatch() {
        nearbyItemBaseline.clear();
        OrderDropperLogic.BatchTotals totals = OrderDropperLogic.totalsForBatch(
            pendingDropMode == DropMode.FortyFiveStacks
        );
        confirmedAttempts++;
        droppedStacks += totals.stacks();
        droppedItems += totals.items();

        if (countMode.get() == CountMode.MaxAttempt && confirmedAttempts >= count.get()) {
            complete("Maximum confirmed attempts reached.");
        }
    }

    @AliceHandler
    public void onMessage(ReceiveMessageEvent event) {
        if (!isActive() || isTerminal()) return;
        if (!OrderDropperLogic.isOutOfStockMessage(event.getMessage().getString())) return;
        if (currentListingSlot < FIRST_ORDER_SLOT || currentListingSlot > LAST_ORDER_SLOT) return;

        exhaustedListingSlots.add(currentListingSlot);
        currentListingSlot = -1;
        clearDropConfirmation();
        beginOrderFlow();
    }

    @AliceHandler
    public void onRender2D(Render2DEvent event) {
        if (mc.currentScreen instanceof HandledScreen<?>) return;
        renderHud(event.drawContext);
    }

    @Override
    public String getInfoString() {
        return isActive() ? stateLabel() : null;
    }

    private void beginOrderFlow() {
        if (mc.player == null || mc.player.networkHandler == null) {
            state = State.SendOrder;
            stateEnteredAtMs = System.currentTimeMillis();
            stateDelay.arm(delay.get());
            return;
        }

        closeAllGui();
        currentListingSlot = -1;
        clearDropConfirmation();
        mc.player.networkHandler.sendChatCommand("order");
        state = State.WaitOrderGui;
        stateEnteredAtMs = System.currentTimeMillis();
        stateDelay.arm(delay.get());
    }

    private void waitForOrderGui() {
        GenericContainerScreenHandler container = currentContainer();
        if (container != null && container.slots.size() > OPEN_ORDERS_SLOT) {
            click(container, OPEN_ORDERS_SLOT);
            state = State.FindItem;
            stateEnteredAtMs = System.currentTimeMillis();
            stateDelay.arm(delay.get());
            return;
        }

        if (timedOut()) beginOrderFlow();
    }

    private void findAndSelectItem() {
        GenericContainerScreenHandler container = currentContainer();
        if (container == null) {
            beginOrderFlow();
            return;
        }

        int lastSlot = Math.min(LAST_ORDER_SLOT, container.slots.size() - 1);
        int itemSlot = OrderDropperLogic.findFirstAvailableSlot(
            FIRST_ORDER_SLOT,
            lastSlot,
            exhaustedListingSlots,
            slot -> container.getSlot(slot).getStack().isOf(selectedItem.get())
        );
        if (itemSlot < 0) {
            complete("No unexhausted order listing remains for " + selectedItem.get().getName().getString() + ".");
            return;
        }

        currentListingSlot = itemSlot;
        click(container, itemSlot);
        state = State.Dropping;
        stateEnteredAtMs = System.currentTimeMillis();
        lastDropClickAtMs = 0L;
        clearDropConfirmation();
        stateDelay.arm(delay.get());
    }

    private void clickDropWhenReady() {
        GenericContainerScreenHandler container = currentContainer();
        if (container == null) {
            beginOrderFlow();
            return;
        }

        DropMode mode = dropMode.get();
        int dropSlot = mode == DropMode.FortyFiveStacks ? DROP_45_SLOT : DROP_1_SLOT;
        if (container.slots.size() <= dropSlot) {
            if (timedOut()) beginOrderFlow();
            return;
        }

        long now = System.currentTimeMillis();
        if (lastDropClickAtMs > 0 && now - lastDropClickAtMs < dropDelay.get()) return;

        pendingDropMode = mode;
        captureNearbyItemBaseline();
        dropConfirmation.open();
        click(container, dropSlot);
        lastDropClickAtMs = now;
        stateEnteredAtMs = now;
    }

    private boolean tickReconnectPause() {
        if (!autoReconnect.get()) {
            if (state == State.WaitingReconnect) {
                reconnectFlowObserved = false;
                reconnectFlowWaitTicks = 0;
                beginOrderFlow();
                return true;
            }
            reconnectFlowObserved = false;
            reconnectFlowWaitTicks = 0;
            return false;
        }

        AliceAutoReconnect aliceReconnect = Modules.get().get(AliceAutoReconnect.class);
        if (aliceReconnect != null && !aliceReconnect.isIdle()) {
            if (state != State.WaitingReconnect) {
                closeAllGui();
                clearDropConfirmation();
                stateDelay.clear();
            }
            state = State.WaitingReconnect;
            reconnectFlowObserved = true;
            reconnectFlowWaitTicks = 0;
            return true;
        }

        if (state != State.WaitingReconnect) return false;

        reconnectFlowWaitTicks++;
        if (!reconnectFlowObserved && reconnectFlowWaitTicks < RECONNECT_FLOW_GRACE_TICKS) {
            return true;
        }

        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        beginOrderFlow();
        return true;
    }

    private void onAutoReconnectChanged() {
        if (!isActive()) return;
        if (autoReconnect.get()) {
            syncReconnectModules();
            return;
        }

        if (state == State.WaitingReconnect) {
            reconnectFlowObserved = false;
            reconnectFlowWaitTicks = 0;
            state = State.SendOrder;
            stateEnteredAtMs = System.currentTimeMillis();
            stateDelay.clear();
        }
    }

    private void syncReconnectModules() {
        if (!isActive() || !autoReconnect.get()) return;

        Module meteorReconnect = Modules.get().get(
            meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect.class
        );
        if (meteorReconnect != null && !meteorReconnect.isActive()) meteorReconnect.toggle();

        AliceAutoReconnect aliceReconnect = Modules.get().get(AliceAutoReconnect.class);
        if (aliceReconnect != null && !aliceReconnect.isActive()) aliceReconnect.toggle();
    }

    private void click(GenericContainerScreenHandler container, int slot) {
        mc.interactionManager.clickSlot(
            container.syncId,
            slot,
            0,
            SlotActionType.PICKUP,
            mc.player
        );
    }

    private GenericContainerScreenHandler currentContainer() {
        return mc.player.currentScreenHandler instanceof GenericContainerScreenHandler container
            ? container
            : null;
    }

    private boolean timedOut() {
        return System.currentTimeMillis() - stateEnteredAtMs >= GUI_TIMEOUT_MS;
    }

    private boolean isTerminal() {
        return state == State.Completed || state == State.Error || state == State.Inactive;
    }

    private void complete(String reason) {
        state = State.Completed;
        sessionFinishedAtMs = System.currentTimeMillis();
        clearDropConfirmation();
        stateDelay.clear();
        closeAllGui();
        info(reason + " Order Dropper completed.");
    }

    private void closeAllGui() {
        if (mc.currentScreen == null) return;
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            mc.player.closeHandledScreen();
        } else {
            mc.setScreen(null);
        }
    }

    private void captureNearbyItemBaseline() {
        nearbyItemBaseline.clear();
        nearbyItemBaseline.putAll(nearbySelectedItems());
    }

    private boolean pollDropConfirmation() {
        if (!dropConfirmation.isAwaiting()) return false;
        Map<Integer, Integer> current = nearbySelectedItems();
        if (!OrderDropperLogic.hasEntityDelta(nearbyItemBaseline, current)) return false;
        if (!dropConfirmation.confirm(true, true)) return false;
        recordConfirmedBatch();
        return true;
    }

    private Map<Integer, Integer> nearbySelectedItems() {
        Map<Integer, Integer> nearby = new HashMap<>();
        if (mc.player == null || mc.world == null) return nearby;

        Item target = selectedItem.get();
        for (net.minecraft.entity.Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!itemEntity.getStack().isOf(target)) continue;
            if (!OrderDropperLogic.isWithinDropRadius(itemEntity.squaredDistanceTo(mc.player))) continue;
            nearby.put(itemEntity.getId(), itemEntity.getStack().getCount());
        }
        return nearby;
    }

    private void clearDropConfirmation() {
        dropConfirmation.clear();
        nearbyItemBaseline.clear();
    }

    private long sessionElapsedMs() {
        if (sessionStartedAtMs <= 0L) return 0L;
        long end = sessionFinishedAtMs > 0L ? sessionFinishedAtMs : System.currentTimeMillis();
        return Math.max(0L, end - sessionStartedAtMs);
    }

    private void registerHandledScreenHud() {
        ScreenEvents.AFTER_INIT.register(new ScreenEvents.AfterInit() {
            @Override
            public void afterInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
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

        String[] lines = {
            "Uptime: " + formatDuration(sessionElapsedMs()),
            "Stacks Dropped: " + droppedStacks,
            "Items Dropped: " + droppedItems,
            "Progress: " + progressLabel()
        };
        int[] colors = {HUD_TEXT, HUD_COUNT, HUD_COUNT, HUD_PROGRESS};

        int padding = 4;
        int lineGap = 2;
        int lineHeight = mc.textRenderer.fontHeight;
        int width = 0;
        for (String line : lines) width = Math.max(width, mc.textRenderer.getWidth(line));
        width += padding * 2;
        int height = lines.length * lineHeight + (lines.length - 1) * lineGap + padding * 2 + 2;
        int x = mc.getWindow().getScaledWidth() / 2 - width - 10;
        int y = mc.getWindow().getScaledHeight() / 2 - height - 10;

        context.fill(x, y, x + width, y + height, HUD_BACKGROUND);
        context.fill(x, y, x + width, y + 2, HUD_ACCENT);

        int textY = y + padding + 3;
        for (int i = 0; i < lines.length; i++) {
            context.drawTextWithShadow(mc.textRenderer, lines[i], x + padding, textY, colors[i]);
            textY += lineHeight + lineGap;
        }
    }

    private String progressLabel() {
        String stateSuffix = " - " + stateLabel();
        if (countMode.get() == CountMode.Infinity) {
            String prefix = state == State.Completed ? "Completed - " : "";
            return prefix + confirmedAttempts + " / \u221e (N/A%)" + stateSuffix;
        }

        long target = count.get();
        double percent = OrderDropperLogic.progressPercent(confirmedAttempts, target);
        String prefix = state == State.Completed ? "Completed - " : "";
        return String.format(
            Locale.ROOT,
            "%s%d / %d (%.1f%%)%s",
            prefix,
            confirmedAttempts,
            target,
            percent,
            stateSuffix
        );
    }

    private String formatDuration(long milliseconds) {
        long totalSeconds = milliseconds / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(Locale.ROOT, "%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format(Locale.ROOT, "%dm %ds", minutes, seconds);
        return seconds + "s";
    }

    private String stateLabel() {
        return switch (state) {
            case Inactive -> "Inactive";
            case SendOrder -> "Sending /order";
            case WaitOrderGui -> "Opening Orders";
            case FindItem -> "Selecting Item";
            case Dropping -> dropConfirmation.isAwaiting() ? "Waiting Confirmation" : "Dropping";
            case WaitingReconnect -> "Auto Reconnect";
            case Completed -> "Completed";
            case Error -> "Error";
        };
    }

    public enum DropMode {
        FortyFiveStacks("45 Stacks"),
        OneStack("1 Stack");

        private final String title;

        DropMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum CountMode {
        Infinity("Infinity"),
        MaxAttempt("Max Attempt");

        private final String title;

        CountMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private enum State {
        Inactive,
        SendOrder,
        WaitOrderGui,
        FindItem,
        Dropping,
        WaitingReconnect,
        Completed,
        Error
    }
}
