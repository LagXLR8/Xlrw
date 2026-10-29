package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

public class BlazeShopDroper extends AliceModule {
    private static final int INVENTORY_SIZE = 36;
    private static final int SELECTED_ITEM_SLOT = 13;
    private static final int PREPARE_BUY_SLOT = 17;
    private static final int BUY_SLOT = 23;
    private static final int RECONNECT_FLOW_GRACE_TICKS = 80;

    private static final int HUD_BACKGROUND = 0xB4141414;
    private static final int HUD_ACCENT = 0xFF55FFAA;
    private static final int HUD_TEXT = 0xFFFFFFFF;
    private static final int HUD_MONEY = 0xFF78FF78;
    private static final int HUD_PROGRESS = 0xFF78DCFF;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("thoi gian tre (tick) khi dieu huong shop va mua lien tuc o slot 23")
        .defaultValue(10)
        .min(1)
        .max(100)
        .sliderMax(40)
        .build());

    private final Setting<Integer> clickBuyDelay = sgGeneral.add(new IntSetting.Builder()
        .name("click-buy-delay")
        .description("thoi gian tre (tick) sau khi click vao nut chuan bi mua o slot 17")
        .defaultValue(4)
        .min(0)
        .max(100)
        .sliderMax(40)
        .build());

    private final Setting<Integer> dropDelay = sgGeneral.add(new IntSetting.Builder()
        .name("drop-delay")
        .description("thoi gian tre 1 lan sau khi tui day roi moi vut het blaze rod")
        .defaultValue(1)
        .min(0)
        .max(100)
        .sliderMax(40)
        .build());

    private final Setting<Boolean> autoReconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-reconnect")
        .description("bat meteor autoreconnect va alice dang nhap, sau do tiep tuc mua")
        .defaultValue(true)
        .onChanged(ignored -> onAutoReconnectChanged())
        .build());

    private final Setting<LimitMode> limitMode = sgGeneral.add(new EnumSetting.Builder<LimitMode>()
        .name("limit-mode")
        .description("chon gioi han so stack roi, gioi han tien, hoac mua khong gioi han")
        .defaultValue(LimitMode.MaxStackAmount)
        .build());

    private final Setting<Integer> maxStackAmount = sgGeneral.add(new IntSetting.Builder()
        .name("max-stack-amount")
        .description("so stack blaze rod roi ra toi da")
        .defaultValue(36)
        .min(1)
        .max(3_600)
        .sliderMax(360)
        .visible(() -> limitMode.get() == LimitMode.MaxStackAmount)
        .build());

    private final Setting<Double> maxMoney = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-money")
        .description("so tien toi da de xai, don vi trieu. vd: 0.5 la 500k")
        .defaultValue(1.0)
        .min(0.001)
        .max(1_000.0)
        .sliderMax(100.0)
        .visible(() -> limitMode.get() == LimitMode.MaxMoney)
        .build());

    private final Setting<Boolean> showHud = sgHud.add(new BoolSetting.Builder()
        .name("show-hud")
        .description("hien thi thong ke cua BlazeShopDroper")
        .defaultValue(true)
        .build());

    private final BlazeShopDroperLogic.TickDelay actionDelay =
        new BlazeShopDroperLogic.TickDelay();
    private final BlazeShopDroperLogic.TickDelay fullInventoryDropDelay =
        new BlazeShopDroperLogic.TickDelay();
    private final Set<Screen> hudRegisteredScreens =
        Collections.newSetFromMap(new WeakHashMap<>());

    private State state = State.Inactive;
    private State resumeAfterDrop = State.OpenShop;
    private long sessionStartedAtMs;
    private long sessionFinishedAtMs;
    private long droppedStacks;
    private long droppedItems;
    private boolean outOfMoney;
    private boolean reconnectFlowObserved;
    private int reconnectFlowWaitTicks;

    public BlazeShopDroper() {
        super(
            AliceAddon.CATEGORY,
            "BlazeShopDroper",
            "Automatically buys Blaze Rods, drops full inventories, and tracks the session."
        );
        registerHandledScreenHud();
    }

    @Override
    public void onActivate() {
        super.onActivate();
        state = State.OpenShop;
        resumeAfterDrop = State.OpenShop;
        sessionStartedAtMs = System.currentTimeMillis();
        sessionFinishedAtMs = 0L;
        droppedStacks = 0L;
        droppedItems = 0L;
        outOfMoney = false;
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        actionDelay.clear();
        fullInventoryDropDelay.clear();
        registerHudForScreen(mc.currentScreen, mc);
        syncReconnectModules();
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        state = State.Inactive;
        actionDelay.clear();
        fullInventoryDropDelay.clear();
    }

    @AliceHandler
    public void onTick(TickEvent.Pre event) {
        if (state == State.Completed || state == State.Error || state == State.Inactive) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (tickReconnectPause()) return;

        ensureSafeHotbar();

        if (state == State.WaitingDrop) {
            handleFullInventoryWait();
            return;
        }

        if (state == State.Dropping) {
            dropBlazeRodBatch();
            return;
        }

        if (isInventoryFull()) {
            resumeAfterDrop = state;
            state = State.WaitingDrop;
            fullInventoryDropDelay.arm(dropDelay.get());
            return;
        }

        if (!actionDelay.tick()) return;

        GenericContainerScreenHandler container = currentContainer();
        if (state != State.OpenShop && container == null) {
            info("Shop GUI closed. BlazeShopDroper stopped.");
            toggle();
            return;
        }

        switch (state) {
            case OpenShop -> openOrEnterNetherCategory(container);
            case SelectBlazeRod -> selectBlazeRod(container);
            case PrepareBuy -> prepareBuy(container);
            case Buying -> buyBlazeRod(container);
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
        actionDelay.clear();
        fullInventoryDropDelay.clear();
    }

    @AliceHandler
    public void onGameJoined(GameJoinedEvent event) {
        if (!isActive() || isTerminal()) return;

        syncReconnectModules();
        resumeAfterDrop = State.OpenShop;
        actionDelay.clear();
        fullInventoryDropDelay.clear();
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        state = autoReconnect.get() ? State.WaitingReconnect : State.OpenShop;
    }

    @AliceHandler
    public void onMessage(ReceiveMessageEvent event) {
        if (!isActive() || outOfMoney || isTerminal()) return;
        if (!BlazeShopDroperLogic.isOutOfMoneyMessage(event.getMessage().getString())) return;

        outOfMoney = true;
        actionDelay.clear();
        fullInventoryDropDelay.clear();
        state = State.Dropping;
        info("Not enough money. Dropping the remaining Blaze Rods.");
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

    private void openOrEnterNetherCategory(GenericContainerScreenHandler container) {
        if (container == null) {
            mc.player.networkHandler.sendChatCommand("shop");
            actionDelay.arm(delay.get());
            return;
        }

        int categorySlot = findShopSlot(container, Items.NETHERRACK);
        if (categorySlot < 0) {
            actionDelay.arm(delay.get());
            return;
        }

        click(container, categorySlot);
        state = State.SelectBlazeRod;
        actionDelay.arm(delay.get());
    }

    private void selectBlazeRod(GenericContainerScreenHandler container) {
        int blazeRodSlot = findShopSlot(container, Items.BLAZE_ROD);
        if (blazeRodSlot < 0) {
            actionDelay.arm(delay.get());
            return;
        }

        click(container, blazeRodSlot);
        state = State.PrepareBuy;
        actionDelay.arm(delay.get());
    }

    private void prepareBuy(GenericContainerScreenHandler container) {
        if (!isSelectedBlazeRod(container)) {
            resetShopFlow();
            return;
        }
        if (container.slots.size() <= PREPARE_BUY_SLOT) {
            failSafely("Shop GUI does not contain slot 17.");
            return;
        }

        click(container, PREPARE_BUY_SLOT);
        state = State.Buying;
        actionDelay.arm(clickBuyDelay.get());
    }

    private void buyBlazeRod(GenericContainerScreenHandler container) {
        if (!isSelectedBlazeRod(container)) {
            resetShopFlow();
            return;
        }
        if (container.slots.size() <= BUY_SLOT) {
            failSafely("Shop GUI does not contain slot 23.");
            return;
        }

        click(container, BUY_SLOT);
        actionDelay.arm(delay.get());
    }

    private void handleFullInventoryWait() {
        if (!isInventoryFull()) {
            state = resumeAfterDrop;
            fullInventoryDropDelay.clear();
            return;
        }
        if (!fullInventoryDropDelay.tick()) return;

        state = State.Dropping;
        dropBlazeRodBatch();
    }

    private boolean tickReconnectPause() {
        if (!autoReconnect.get()) {
            if (state == State.WaitingReconnect) state = State.OpenShop;
            reconnectFlowObserved = false;
            reconnectFlowWaitTicks = 0;
            return false;
        }

        AliceAutoReconnect aliceReconnect = Modules.get().get(AliceAutoReconnect.class);
        if (aliceReconnect != null && !aliceReconnect.isIdle()) {
            state = State.WaitingReconnect;
            reconnectFlowObserved = true;
            reconnectFlowWaitTicks = 0;
            actionDelay.clear();
            fullInventoryDropDelay.clear();
            return true;
        }

        if (state != State.WaitingReconnect) return false;

        reconnectFlowWaitTicks++;
        if (!reconnectFlowObserved && reconnectFlowWaitTicks < RECONNECT_FLOW_GRACE_TICKS) {
            return true;
        }

        state = State.OpenShop;
        resumeAfterDrop = State.OpenShop;
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        actionDelay.arm(delay.get());
        return true;
    }

    private void dropBlazeRodBatch() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        List<Integer> slotIds = new ArrayList<>();
        List<Integer> stackCounts = new ArrayList<>();

        for (int inventoryIndex = 0; inventoryIndex < INVENTORY_SIZE; inventoryIndex++) {
            ItemStack stack = mc.player.getInventory().getStack(inventoryIndex);
            if (!stack.isOf(Items.BLAZE_ROD)) continue;

            int slotId = findInventorySlotId(handler, inventoryIndex);
            if (slotId < 0) continue;
            slotIds.add(slotId);
            stackCounts.add(stack.getCount());
        }

        if (slotIds.isEmpty()) {
            if (outOfMoney) {
                complete("Out of money.");
            } else {
                failSafely("Inventory is full but contains no Blaze Rods to drop.");
            }
            return;
        }

        int[] counts = new int[stackCounts.size()];
        for (int i = 0; i < stackCounts.size(); i++) counts[i] = stackCounts.get(i);
        BlazeShopDroperLogic.BatchTotals batch = BlazeShopDroperLogic.batchTotals(counts);

        for (int slotId : slotIds) {
            mc.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                1,
                SlotActionType.THROW,
                mc.player
            );
        }

        droppedStacks += batch.stacks();
        droppedItems += batch.items();

        if (outOfMoney) {
            complete("Out of money.");
        } else if (limitReached()) {
            complete("Buy limit reached.");
        } else {
            state = resumeAfterDrop;
            actionDelay.arm(delay.get());
        }
    }

    private void resetShopFlow() {
        state = State.OpenShop;
        actionDelay.arm(delay.get());
    }

    private void onAutoReconnectChanged() {
        if (!isActive()) return;
        if (autoReconnect.get()) {
            syncReconnectModules();
            return;
        }

        if (state == State.WaitingReconnect) state = State.OpenShop;
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
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

    private int findShopSlot(GenericContainerScreenHandler container, net.minecraft.item.Item item) {
        int shopSlots = Math.min(container.getRows() * 9, container.slots.size());
        for (int i = 0; i < shopSlots; i++) {
            if (container.getSlot(i).getStack().isOf(item)) return i;
        }
        return -1;
    }

    private int findInventorySlotId(ScreenHandler handler, int inventoryIndex) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == inventoryIndex) {
                return slot.id;
            }
        }
        return -1;
    }

    private boolean isSelectedBlazeRod(GenericContainerScreenHandler container) {
        return container.slots.size() > SELECTED_ITEM_SLOT
            && container.getSlot(SELECTED_ITEM_SLOT).getStack().isOf(Items.BLAZE_ROD);
    }

    private void ensureSafeHotbar() {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getStack(selected).isEmpty()) return;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                mc.player.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    private boolean isInventoryFull() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean limitReached() {
        return switch (limitMode.get()) {
            case MaxStackAmount -> droppedStacks >= maxStackAmount.get();
            case MaxMoney -> moneySpent() >= moneyLimit();
            case Infinity -> false;
        };
    }

    private boolean isTerminal() {
        return state == State.Completed || state == State.Error || state == State.Inactive;
    }

    private void complete(String reason) {
        state = State.Completed;
        sessionFinishedAtMs = System.currentTimeMillis();
        actionDelay.clear();
        fullInventoryDropDelay.clear();
        info(reason + " BlazeShopDroper completed.");
    }

    private void failSafely(String message) {
        state = State.Error;
        sessionFinishedAtMs = System.currentTimeMillis();
        actionDelay.clear();
        fullInventoryDropDelay.clear();
        error(message);
    }

    private long moneySpent() {
        return BlazeShopDroperLogic.moneyForItems(droppedItems);
    }

    private long moneyLimit() {
        return BlazeShopDroperLogic.moneyLimit(maxMoney.get());
    }

    private long sessionElapsedMs() {
        if (sessionStartedAtMs <= 0) return 0L;
        long end = sessionFinishedAtMs > 0 ? sessionFinishedAtMs : System.currentTimeMillis();
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
            "Stacks Dropped: " + droppedStacks,
            "Items Dropped: " + droppedItems,
            "Money Spent: $" + formatMoney(moneySpent()),
            "Uptime: " + formatDuration(sessionElapsedMs()),
            "Progress: " + progressLabel()
        };
        int[] colors = {HUD_TEXT, HUD_TEXT, HUD_MONEY, HUD_TEXT, HUD_PROGRESS};

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
        if (limitMode.get() == LimitMode.Infinity) {
            return "\u221e (N/A%)" + stateSuffix;
        }

        long current;
        long target;
        String units;
        if (limitMode.get() == LimitMode.MaxStackAmount) {
            current = droppedStacks;
            target = maxStackAmount.get();
            units = " stacks";
        } else {
            current = moneySpent();
            target = moneyLimit();
            units = " money";
        }

        double percent = BlazeShopDroperLogic.progressPercent(current, target);
        String prefix = state == State.Completed ? "Completed - " : state == State.Error ? "Error - " : "";
        return String.format(
            Locale.ROOT,
            "%s%.1f%% (%s / %s%s)%s",
            prefix,
            percent,
            formatProgressValue(current),
            formatProgressValue(target),
            units,
            stateSuffix
        );
    }

    private String formatProgressValue(long value) {
        return limitMode.get() == LimitMode.MaxMoney ? "$" + formatMoney(value) : Long.toString(value);
    }

    private String formatMoney(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.2fK", value / 1_000.0);
        return Long.toString(value);
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
            case OpenShop -> "Opening Shop";
            case SelectBlazeRod -> "Selecting Blaze Rod";
            case PrepareBuy -> "Preparing Buy";
            case Buying -> "Buying";
            case WaitingDrop -> "Waiting To Drop";
            case Dropping -> "Dropping";
            case WaitingReconnect -> "Auto Reconnect";
            case Completed -> "Completed";
            case Error -> "Error";
        };
    }

    public enum LimitMode {
        MaxStackAmount,
        MaxMoney,
        Infinity
    }

    private enum State {
        Inactive,
        OpenShop,
        SelectBlazeRod,
        PrepareBuy,
        Buying,
        WaitingDrop,
        Dropping,
        WaitingReconnect,
        Completed,
        Error
    }
}
