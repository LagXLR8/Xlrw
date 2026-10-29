package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class AutoOrder extends AliceModule {
    private static final int MAX_STATE_TICKS = 200;
    private static final int MAX_PAGES_PER_PASS = 20;
    private static final int HUD_BACKGROUND = 0xB4141414;
    private static final int HUD_ACCENT = 0xFF55FFAA;
    private static final int HUD_TEXT = 0xFFFFFFFF;
    private static final int HUD_MONEY = 0xFF78FF78;
    private static final int HUD_STATE = 0xFF78DCFF;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Item> item = sgGeneral.add(
        new ItemSetting.Builder()
            .name("item")
            .description("Item to fulfill. AIR waits without sending a command.")
            .defaultValue(Items.AIR)
            .build()
    );

    private final Setting<Boolean> clickCauldron = sgGeneral.add(
        new BoolSetting.Builder()
            .name("click-cauldron")
            .description("mo view tim kiem order bang nut cai vac")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> webhook = sgWebhook.add(
        new BoolSetting.Builder()
            .name("webhook")
            .description("gui cap nhat session dat hang vao discord")
            .defaultValue(false)
            .build()
    );

    private final Setting<String> webhookUrl = sgWebhook.add(
        new StringSetting.Builder()
            .name("webhook-url")
            .description("dia chi webhook discord")
            .defaultValue("")
            .visible(webhook::get)
            .build()
    );

    private final Setting<WebhookMode> webhookMode = sgWebhook.add(
        new EnumSetting.Builder<WebhookMode>()
            .name("webhook-mode")
            .description("gui sau moi don hang hoac sau moi vai phut")
            .defaultValue(WebhookMode.SendOnMinute)
            .visible(webhook::get)
            .build()
    );

    private final Setting<Integer> sendOnMinute = sgWebhook.add(
        new IntSetting.Builder()
            .name("send-on-minute")
            .description("so phut giua cac lan cap nhat webhook")
            .defaultValue(5)
            .min(1)
            .max(1_440)
            .sliderMax(60)
            .visible(
                () -> webhook.get()
                    && webhookMode.get() == WebhookMode.SendOnMinute
            )
            .build()
    );

    private final Setting<Boolean> showHud = sgHud.add(
        new BoolSetting.Builder()
            .name("show-hud")
            .description("hien thi thong ke cua AutoOrder")
            .defaultValue(true)
            .build()
    );

    private final Set<Screen> hudRegisteredScreens =
        Collections.newSetFromMap(new WeakHashMap<>());
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final AutoOrderLogic.StackCounter stackCounter =
        new AutoOrderLogic.StackCounter();
    private final AutoOrderLogic.MinuteSchedule minuteSchedule =
        new AutoOrderLogic.MinuteSchedule();

    private State state = State.SelectItem;
    private int delayTicks;
    private int stateTicks;
    private int pagesVisited;
    private String lastGuiTitle = "";
    private long sessionStartedAtMs;
    private double moneyEarned;
    private PendingTransfer pendingTransfer;

    public AutoOrder() {
        super(
            AliceAddon.CATEGORY,
            "Auto-Order",
            "Repeatedly fulfills the highest-paying valid order for one selected item."
        );
        registerHandledScreenHud();
    }

    @Override
    public void onActivate() {
        super.onActivate();
        sessionStartedAtMs = System.currentTimeMillis();
        moneyEarned = 0D;
        stackCounter.reset();
        pendingTransfer = null;
        pagesVisited = 0;
        lastGuiTitle = "";
        minuteSchedule.reset(sessionStartedAtMs, sendOnMinute.get());
        transition(item.get() == Items.AIR ? State.SelectItem : State.SendCommand, 0);
        registerHudForScreen(mc.currentScreen, mc);
    }

    @Override
    public void onDeactivate() {
        pendingTransfer = null;
        state = State.Inactive;
        super.onDeactivate();
    }

    @Override
    public String getInfoString() {
        return isActive() ? stateLabel() : null;
    }

    @AliceHandler
    private void onTick(TickEvent.Post event) {
        if (
            !isActive()
                || mc.player == null
                || mc.world == null
                || mc.interactionManager == null
        ) {
            return;
        }

        tickWebhook();

        if (item.get() == Items.AIR) {
            if (state != State.SelectItem) {
                closeContainer();
                pendingTransfer = null;
                transition(State.SelectItem, 0);
            }
            return;
        }
        if (state == State.SelectItem) transition(State.SendCommand, 0);

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        stateTicks++;
        if (stateTicks >= MAX_STATE_TICKS) {
            restartLoop();
            return;
        }

        switch (state) {
            case SendCommand -> sendOrderCommand();
            case WaitInitialGui -> waitForInitialGui();
            case WaitSearchGui -> waitForSearchGui();
            case SearchPage -> searchCurrentPage();
            case WaitPage -> waitForNextPage();
            case FillOrder -> fillOrder();
            case WaitConfirmGui -> waitForConfirmGui();
            case ConfirmOrder -> confirmOrder();
            case WaitOrderClose -> waitForOrderClose();
            case CloseOrder -> closeOrder();
            default -> {
            }
        }
    }

    @AliceHandler
    private void onPacket(PacketEvent.Receive event) {
        if (pendingTransfer == null) return;
        int syncId;
        int revision;
        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket packet) {
            syncId = packet.getSyncId();
            revision = packet.getRevision();
            if (
                syncId == pendingTransfer.syncId
                    && revision >= pendingTransfer.baseRevision
            ) {
                if (pendingTransfer.accounting.hasSlot(packet.getSlot())) {
                    int currentCount = packet.getStack().isOf(item.get())
                        ? packet.getStack().getCount()
                        : 0;
                    stackCounter.addConfirmedItems(
                        pendingTransfer.accounting.reconcileSlot(
                            packet.getSlot(),
                            currentCount
                        )
                    );
                }
            }
        } else if (event.packet instanceof InventoryS2CPacket packet) {
            syncId = packet.syncId();
            revision = packet.revision();
            if (
                syncId == pendingTransfer.syncId
                    && revision >= pendingTransfer.baseRevision
            ) {
                Map<Integer, Integer> authoritativeCounts = new HashMap<>();
                for (int slot : pendingTransfer.accounting.slots()) {
                    ItemStack stack = slot >= 0 && slot < packet.contents().size()
                        ? packet.contents().get(slot)
                        : ItemStack.EMPTY;
                    authoritativeCounts.put(
                        slot,
                        stack.isOf(item.get()) ? stack.getCount() : 0
                    );
                }
                stackCounter.addConfirmedItems(
                    pendingTransfer.accounting.reconcileAll(authoritativeCounts)
                );
            }
        } else {
            return;
        }
    }

    @AliceHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;
        AutoOrderLogic.parseDelivery(event.getMessage().getString()).ifPresent(
            delivery -> {
                moneyEarned += delivery.money();
                if (
                    webhook.get()
                        && webhookMode.get() == WebhookMode.SendOnOrder
                ) {
                    sendWebhook();
                }
                restartLoop();
            }
        );
    }

    @AliceHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.currentScreen instanceof HandledScreen<?>) return;
        renderHud(event.drawContext);
    }

    private void sendOrderCommand() {
        closeContainer();
        String commandItem = AutoOrderLogic.commandName(
            Registries.ITEM.getId(item.get()).toString()
        );
        if (commandItem.isBlank() || item.get() == Items.AIR) {
            transition(State.SelectItem, 0);
            return;
        }
        mc.player.networkHandler.sendChatCommand("order " + commandItem);
        pagesVisited = 0;
        transition(State.WaitInitialGui, smartDelay());
    }

    private void waitForInitialGui() {
        GenericContainerScreenHandler handler = currentContainer();
        if (handler == null) return;
        if (!clickCauldron.get()) {
            transition(State.SearchPage, smartDelay());
            return;
        }

        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (!handler.getSlot(slot).getStack().isOf(Items.CAULDRON)) continue;
            click(handler, slot, SlotActionType.PICKUP);
            closeContainer();
            transition(State.WaitSearchGui, smartDelay() * 3);
            return;
        }
        transition(State.SearchPage, smartDelay());
    }

    private void waitForSearchGui() {
        if (currentContainer() != null) transition(State.SearchPage, smartDelay());
    }

    private void searchCurrentPage() {
        GenericContainerScreenHandler handler = currentContainer();
        if (handler == null) {
            restartLoop();
            return;
        }
        int candidate = findBestCandidate(handler);
        if (candidate >= 0) {
            pagesVisited = 0;
            click(handler, candidate, SlotActionType.PICKUP);
            transition(State.FillOrder, smartDelay());
            return;
        }

        if (
            handler.slots.size() > 53
                && !handler.getSlot(53).getStack().isEmpty()
                && pagesVisited++ < MAX_PAGES_PER_PASS
        ) {
            click(handler, 53, SlotActionType.PICKUP);
            lastGuiTitle = currentGuiTitle();
            transition(State.WaitPage, smartDelay());
            return;
        }
        restartLoop();
    }

    private void waitForNextPage() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            restartLoop();
            return;
        }
        String newTitle = cleanTitle(screen.getTitle().getString());
        if (!newTitle.equals(lastGuiTitle)) {
            lastGuiTitle = newTitle;
            transition(State.SearchPage, 1);
        } else {
            delayTicks = 1;
        }
    }

    private void fillOrder() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            restartLoop();
            return;
        }
        String guiTitle = cleanTitle(screen.getTitle().getString());
        if (!isExactFillGuiTitle(guiTitle)) {
            restartLoop();
            return;
        }

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        boolean guiHasEmpty = false;
        int containerLimit = Math.min(54, handler.slots.size());
        for (int slotIndex = 0; slotIndex < containerLimit; slotIndex++) {
            Slot slot = handler.getSlot(slotIndex);
            if (slot.inventory == mc.player.getInventory()) continue;
            if (slot.getStack().isEmpty()) {
                guiHasEmpty = true;
                break;
            }
        }
        if (!guiHasEmpty) {
            closeContainer();
            transition(State.WaitConfirmGui, smartDelay() * 5);
            return;
        }

        Map<Integer, Integer> beforeBySlot = selectedPlayerItemCounts(handler);
        if (beforeBySlot.isEmpty()) {
            closeContainer();
            transition(State.WaitConfirmGui, smartDelay() * 5);
            return;
        }

        pendingTransfer = new PendingTransfer(
            handler.syncId,
            handler.getRevision(),
            new AutoOrderLogic.ConfirmedTransfer(beforeBySlot)
        );
        for (Slot slot : handler.slots) {
            if (
                slot.inventory == mc.player.getInventory()
                    && slot.getStack().isOf(item.get())
            ) {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    slot.id,
                    0,
                    SlotActionType.QUICK_MOVE,
                    mc.player
                );
            }
        }
        transition(State.FillOrder, smartDelay() * 2);
    }

    private void waitForConfirmGui() {
        if (currentContainer() != null) {
            transition(State.ConfirmOrder, smartDelay());
        }
    }

    private void confirmOrder() {
        GenericContainerScreenHandler handler = currentContainer();
        if (handler == null) {
            transition(State.CloseOrder, 0);
            return;
        }
        if (handler.slots.size() > 16) {
            click(handler, 16, SlotActionType.PICKUP);
            transition(State.WaitOrderClose, smartDelay());
            return;
        }
        transition(State.CloseOrder, 0);
    }

    private void waitForOrderClose() {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            if (hasSelectedItem()) {
                String title = cleanTitle(screen.getTitle().getString());
                if (isFillGuiTitle(title)) {
                    transition(State.FillOrder, smartDelay());
                } else if (title.contains("(") && title.contains("/")) {
                    transition(State.SearchPage, smartDelay());
                } else {
                    delayTicks = smartDelay();
                }
            } else {
                closeContainer();
                transition(State.CloseOrder, smartDelay());
            }
        } else if (hasSelectedItem()) {
            transition(State.SendCommand, smartDelay());
        } else {
            delayTicks = smartDelay();
        }
    }

    private void closeOrder() {
        closeContainer();
        pendingTransfer = null;
        pagesVisited = 0;
        transition(
            item.get() == Items.AIR ? State.SelectItem : State.SendCommand,
            smartDelay()
        );
    }

    private int findBestCandidate(GenericContainerScreenHandler handler) {
        List<AutoOrderLogic.Candidate> candidates = new ArrayList<>();
        int limit = Math.min(45, handler.slots.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !stack.isOf(item.get())) continue;
            List<String> lore = loreLines(stack);
            candidates.add(
                new AutoOrderLogic.Candidate(
                    slot,
                    AutoOrderLogic.remaining(lore),
                    AutoOrderLogic.pricePerItem(lore)
                )
            );
        }
        return AutoOrderLogic.bestSlot(candidates);
    }

    private static List<String> loreLines(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        for (var line : lore.lines()) lines.add(line.getString());
        return lines;
    }

    private Map<Integer, Integer> selectedPlayerItemCounts(
        GenericContainerScreenHandler handler
    ) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Slot slot : handler.slots) {
            if (
                slot.inventory == mc.player.getInventory()
                    && slot.getStack().isOf(item.get())
            ) {
                counts.put(slot.id, slot.getStack().getCount());
            }
        }
        return counts;
    }

    private boolean hasSelectedItem() {
        if (mc.player == null || item.get() == Items.AIR) return false;
        for (int slot = 0; slot < mc.player.getInventory().size(); slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(item.get())) return true;
        }
        return false;
    }

    private String currentGuiTitle() {
        return mc.currentScreen instanceof GenericContainerScreen screen
            ? cleanTitle(screen.getTitle().getString())
            : "";
    }

    private static String cleanTitle(String title) {
        return title == null ? "" : title.replaceAll("§.", "");
    }

    private static boolean isExactFillGuiTitle(String title) {
        return title != null
            && title.contains("ʙỏ ᴠậᴛ ᴘʜẩᴍ ᴠàᴏ để ɢɪᴀᴏ");
    }

    private static boolean isFillGuiTitle(String title) {
        return title != null && title.contains("ʙỏ ᴠậᴛ ᴘʜẩᴍ");
    }

    private void restartLoop() {
        pendingTransfer = null;
        closeContainer();
        pagesVisited = 0;
        transition(item.get() == Items.AIR ? State.SelectItem : State.SendCommand,
            smartDelay() * 3);
    }

    private void transition(State next, int delay) {
        state = next;
        delayTicks = Math.max(0, delay);
        stateTicks = 0;
    }

    private int smartDelay() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 5;
        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        int ping = entry == null ? 100 : entry.getLatency();
        if (ping < 50) return 2;
        if (ping < 100) return 3;
        if (ping < 200) return 5;
        if (ping < 300) return 8;
        return 12;
    }

    private GenericContainerScreenHandler currentContainer() {
        return mc.player.currentScreenHandler
            instanceof GenericContainerScreenHandler handler
            ? handler
            : null;
    }

    private void click(
        GenericContainerScreenHandler handler,
        int slot,
        SlotActionType action
    ) {
        if (slot < 0 || slot >= handler.slots.size()) return;
        mc.interactionManager.clickSlot(
            handler.syncId,
            slot,
            0,
            action,
            mc.player
        );
    }

    private void closeContainer() {
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
    }

    private void tickWebhook() {
        if (
            !webhook.get()
                || webhookUrl.get().isBlank()
                || webhookMode.get() != WebhookMode.SendOnMinute
        ) {
            return;
        }
        if (
            minuteSchedule.takeIfDue(
                System.currentTimeMillis(),
                sendOnMinute.get()
            )
        ) {
            sendWebhook();
        }
    }

    private void sendWebhook() {
        String url = webhookUrl.get().trim();
        if (!webhook.get() || url.isBlank()) return;
        String json = "{\"embeds\":[{\"title\":\"Order Session\",\"color\":5635925,"
            + "\"fields\":["
            + field("Uptime", formatDuration(sessionElapsedMs())) + ","
            + field("Stacks Ordered", Long.toString(stackCounter.stacks())) + ","
            + field("Money Earned", formatMoney(moneyEarned))
            + "]}]}";
        Thread.ofVirtual().name("order-session-webhook").start(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            }
        });
    }

    private String stateLabel() {
        return switch (state) {
            case SelectItem -> "Select Item";
            case SendCommand -> "Sending /order";
            case WaitInitialGui, WaitSearchGui -> "Waiting GUI";
            case SearchPage, WaitPage -> "Searching";
            case FillOrder -> "Delivering";
            case WaitConfirmGui, ConfirmOrder, WaitOrderClose, CloseOrder ->
                "Confirming";
            case Inactive -> "Inactive";
        };
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
            "Stacks Ordered: " + stackCounter.stacks(),
            "Money Earned: " + formatMoney(moneyEarned),
            "State: " + stateLabel(),
            "Uptime: " + formatDuration(sessionElapsedMs())
        };
        int[] colors = {HUD_TEXT, HUD_MONEY, HUD_STATE, HUD_TEXT};
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

    private long sessionElapsedMs() {
        return sessionStartedAtMs <= 0L
            ? 0L
            : Math.max(0L, System.currentTimeMillis() - sessionStartedAtMs);
    }

    private static String formatDuration(long milliseconds) {
        long seconds = Math.max(0L, milliseconds / 1_000L);
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            seconds / 3_600L,
            seconds % 3_600L / 60L,
            seconds % 60L
        );
    }

    private static String formatMoney(double value) {
        if (value >= 1_000_000_000D) {
            return String.format(Locale.ROOT, "$%.2fB", value / 1_000_000_000D);
        }
        if (value >= 1_000_000D) {
            return String.format(Locale.ROOT, "$%.2fM", value / 1_000_000D);
        }
        if (value >= 1_000D) {
            return String.format(Locale.ROOT, "$%.2fK", value / 1_000D);
        }
        return String.format(Locale.ROOT, "$%.0f", value);
    }

    private static String field(String name, String value) {
        return "{\"name\":\"" + escapeJson(name) + "\",\"value\":\""
            + escapeJson(value) + "\",\"inline\":true}";
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    public enum WebhookMode {
        SendOnOrder,
        SendOnMinute
    }

    private enum State {
        Inactive,
        SelectItem,
        SendCommand,
        WaitInitialGui,
        WaitSearchGui,
        SearchPage,
        WaitPage,
        FillOrder,
        WaitConfirmGui,
        ConfirmOrder,
        WaitOrderClose,
        CloseOrder
    }

    private static final class PendingTransfer {
        private final int syncId;
        private final int baseRevision;
        private final AutoOrderLogic.ConfirmedTransfer accounting;

        private PendingTransfer(
            int syncId,
            int baseRevision,
            AutoOrderLogic.ConfirmedTransfer accounting
        ) {
            this.syncId = syncId;
            this.baseRevision = baseRevision;
            this.accounting = accounting;
        }
    }
}
