package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import com.nnpg.alice.bridge.NoPushBridge;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-sell module derived from buddymeteor/meteor-autosell.
 *
 * Original project copyright (c) 2026 Meteor AutoSell and licensed under MIT.
 * The matching license notice is packaged at
 * META-INF/licenses/meteor-autosell.txt.
 */
public class AutoSell extends AliceModule {
    public static java.lang.invoke.MethodHandles.Lookup getLookup() {
        return java.lang.invoke.MethodHandles.lookup();
    }

    private static final long GUI_WAIT_TIMEOUT_MS = 5_000;
    private static final int MAX_GUI_RETRIES = 3;
    private static final long ESC_PAUSE_MS = 4_000L;
    private static final long PASSWORD_WARNING_INTERVAL_MS = 500L;
    private static final int PLAYER_CONFIRM_TICKS = 80;
    private static final int RECONNECT_FLOW_GRACE_TICKS = 80;
    private static final Pattern MONEY_PATTERN = Pattern.compile(
        "(?i)(?:\\+\\s*)?\\$[ \\t]*([0-9]+(?:\\.[0-9]+)?)\\s*([KMB]?)"
    );

    private static final Color HUD_TEXT = new Color(255, 255, 255, 255);
    private static final Color HUD_MONEY = new Color(120, 255, 120, 255);
    private static final Color HUD_STATE = new Color(120, 220, 255, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSelling = settings.createGroup("Selling");
    private final SettingGroup sgReturn = settings.createGroup("Auto Return");
    private final SettingGroup sgPlayer = settings.createGroup("Player Detection");
    private final SettingGroup sgWebhook = settings.createGroup("Discord Webhook");

    private final Setting<Boolean> showHud = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hud")
        .description("hien thi thoi gian on, tien, tong so da ban, trung binh va trang thai")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("hien thi tin nhan trang thai cua auto sell tren chat")
        .defaultValue(true)
        .build());

    private final Setting<FilterMode> filterMode = sgSelling.add(
        new EnumSetting.Builder<FilterMode>()
            .name("filter-mode")
            .description("whitelist chi ban cac mon da chon, blacklist khong ban cac mon da chon")
            .defaultValue(FilterMode.Blacklist)
            .build()
    );

    private final Setting<List<Item>> itemsToSell = sgSelling.add(
        new ItemListSetting.Builder()
            .name("items-to-sell")
            .description("Items included or excluded by Filter Mode.")
            .defaultValue(Items.ELYTRA)
            .build()
    );

    private final Setting<Integer> moveDelayTicks = sgSelling.add(new IntSetting.Builder()
        .name("move-delay")
        .description("tick giua cac dot chuyen item binh thuong")
        .defaultValue(1)
        .min(0)
        .max(100)
        .sliderMax(20)
        .build());

    private final Setting<Integer> sellDelayTicks = sgSelling.add(new IntSetting.Builder()
        .name("sell-delay")
        .description("tick cho sau khi bam slot xac nhan ban")
        .defaultValue(4)
        .min(0)
        .max(100)
        .sliderMax(20)
        .build());

    private final Setting<Boolean> autoReturn = sgReturn.add(new BoolSetting.Builder()
        .name("auto-return")
        .description("quay ve vi tri kich hoat khi gap dieu kien da chon")
        .defaultValue(false)
        .onChanged(ignored -> syncReconnectModules())
        .build());

    private final Setting<Boolean> returnOnPush = sgReturn.add(new BoolSetting.Builder()
        .name("return-on-push")
        .description("quay ve sau khi bi day khoi vi tri kich hoat")
        .defaultValue(true)
        .visible(autoReturn::get)
        .build());

    private final Setting<Boolean> noPush = sgReturn.add(new BoolSetting.Builder()
        .name("no-push")
        .description("chi chan luc day cua nuoc/dong chay khi duoc bat")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> returnOnDisconnect = sgReturn.add(new BoolSetting.Builder()
        .name("return-on-disconnect")
        .description("quay ve sau khi ket noi lai server")
        .defaultValue(true)
        .visible(autoReturn::get)
        .onChanged(ignored -> syncReconnectModules())
        .build());

    private final Setting<Boolean> playerDetection = sgPlayer.add(new BoolSetting.Builder()
        .name("player-detection")
        .description("phat hien bat ky nguoi choi nao duoc load ma khong co trong whitelist")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> disconnectOnPlayer = sgPlayer.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("tu dong thoat khi phat hien nguoi choi o gan")
        .defaultValue(true)
        .visible(playerDetection::get)
        .build());

    private final Setting<List<String>> whitelistPlayers = sgPlayer.add(new StringListSetting.Builder()
        .name("whitelisted-players")
        .description("ten nguoi choi duoc bo qua")
        .defaultValue(new ArrayList<>())
        .visible(playerDetection::get)
        .build());

    private final Setting<Boolean> discordTracking = sgWebhook.add(new BoolSetting.Builder()
        .name("discord-tracking")
        .description("gui thong bao tinh trang va canh bao qua discord")
        .defaultValue(false)
        .build());

    private final Setting<String> discordWebhookUrl = sgWebhook.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("dia chi webhook discord")
        .defaultValue("")
        .visible(discordTracking::get)
        .build());

    private final Setting<Integer> discordUpdateInterval = sgWebhook.add(new IntSetting.Builder()
        .name("status-interval-seconds")
        .description("so giay giua cac lan gui webhook")
        .defaultValue(60)
        .min(10)
        .max(3_600)
        .sliderMax(600)
        .visible(discordTracking::get)
        .build());

    private final Setting<Boolean> discordNotifyOnPlayer = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-on-player")
        .description("gui canh bao webhook khi phat hien nguoi choi")
        .defaultValue(true)
        .visible(discordTracking::get)
        .build());

    public enum FilterMode {
        Whitelist,
        Blacklist
    }

    private enum State {
        IDLE,
        SENDING_COMMAND,
        WAITING_GUI,
        MOVING_ITEMS,
        CLICKING_SELL,
        WAITING_AFTER_SELL,
        COOLDOWN,
        RETURNING
    }

    private State state = State.IDLE;
    private long delayUntilMs;
    private long guiWaitDeadlineMs;
    private int lastSyncId = -1;
    private int guiRetries;
    private int itemsInGui;
    private int sessionSold;
    private double sessionMoney;
    private long sessionStartTime;
    private Vec3d anchorPosition;
    private RegistryKey<World> anchorWorld;
    private String anchorServer = "";
    private String detectedPlayer = "";
    private double detectedPlayerDistance;
    private boolean playerDetectionPending;
    private boolean playerDetectionConfirmed;
    private int playerConfirmationTicks;
    private final Set<UUID> ignoredFalsePositivePlayers = new HashSet<>();
    private boolean returnAfterReconnect;
    private boolean waitingForReconnectFlow;
    private boolean reconnectFlowObserved;
    private int reconnectFlowWaitTicks;
    private boolean safetyReconnectSuppressed;
    private boolean safetyDisconnectInProgress;
    private boolean autoReconnectPaused;
    private boolean escWasDown;
    private long escPauseUntilMs;
    private long nextPasswordWarningMs;
    private long nextReturnMoveMs;
    private boolean returnEpisodeActive;
    private boolean baritonePathing;
    private boolean baritoneWarningShown;
    private boolean waitingForAnchorContext;
    private boolean waterNoPushSuspended;
    private long lastWebhookStatusSeconds;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final BaritoneAdapter baritone = new BaritoneAdapter();

    public AutoSell() {
        super(AliceAddon.CATEGORY, "auto-sell",
            "Automatically fills and confirms a server /sell GUI with optional safety detection.");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        resetSession();
        resetDetectors();
        returnAfterReconnect = false;
        waitingForReconnectFlow = false;
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        safetyReconnectSuppressed = false;
        safetyDisconnectInProgress = false;
        autoReconnectPaused = false;
        escWasDown = false;
        escPauseUntilMs = 0L;
        nextPasswordWarningMs = 0L;
        returnEpisodeActive = false;
        baritonePathing = false;
        baritoneWarningShown = false;
        waitingForAnchorContext = false;
        captureAnchor();
        state = mc.currentScreen instanceof GenericContainerScreen
            ? State.MOVING_ITEMS
            : State.SENDING_COMMAND;
        waterNoPushSuspended = false;
        NoPushBridge.install(
            this,
            () -> isActive() && noPush.get() && !waterNoPushSuspended
        );
        syncReconnectModules();
        notifyPlayer("Auto Sell enabled.");
    }

    @Override
    public void onDeactivate() {
        waterNoPushSuspended = false;
        NoPushBridge.uninstall(this);
        cancelBaritone();
        state = State.IDLE;
        anchorPosition = null;
        anchorWorld = null;
        anchorServer = "";
        itemsInGui = 0;
        closeSellScreen();
        notifyPlayer("Auto Sell disabled.");
        super.onDeactivate();
    }

    @Override
    public String getInfoString() {
        return stateLabel();
    }

    @AliceHandler
    public void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        pollEscapePause();
        syncReconnectModules();
        tickPasswordWarning();
        if (mc.player.isDead()) return;

        AliceAutoReconnect aliceReconnect = aliceAutoReconnect();
        boolean reconnecting = aliceReconnect != null && aliceReconnect.isInLoginFlow();
        if (waitingForReconnectFlow && reconnecting) reconnectFlowObserved = true;

        boolean playerSafetyPaused = tickPlayerDetection(aliceReconnect);
        if (mc.getNetworkHandler() == null) return;

        if (reconnecting && !autoReconnectPaused) {
            autoReconnectPaused = true;
            pauseAutomation();
        } else if (!reconnecting && autoReconnectPaused) {
            autoReconnectPaused = false;
            delayUntilMs = System.currentTimeMillis() + 250L;
        }

        tickWebhookStatus();

        if (playerSafetyPaused || reconnecting || isEscapePaused()) return;
        if (tickReconnectFlowWait(aliceReconnect)) return;
        if (tickPositionControl()) return;
        if (System.currentTimeMillis() < delayUntilMs) return;

        switch (state) {
            case IDLE -> state = State.SENDING_COMMAND;
            case SENDING_COMMAND -> sendSellCommand();
            case WAITING_GUI -> waitForSellGui();
            case MOVING_ITEMS -> moveItems();
            case CLICKING_SELL -> clickSell();
            case WAITING_AFTER_SELL -> afterSell();
            case COOLDOWN -> state = State.SENDING_COMMAND;
            default -> {
            }
        }
    }

    @AliceHandler
    public void onMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;
        parseMoney(event.getMessage().getString());
    }

    @AliceHandler
    public void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        cancelBaritone();
        returnEpisodeActive = false;
        waterNoPushSuspended = false;
        if (safetyDisconnectInProgress) {
            returnAfterReconnect = false;
        } else {
            returnAfterReconnect = reconnectReturnEnabled() && anchorPosition != null;
        }
        safetyDisconnectInProgress = false;
        waitingForReconnectFlow = false;
        state = State.IDLE;
        resetDetectors();
    }

    @AliceHandler
    public void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        boolean manualJoinAfterSafety = safetyReconnectSuppressed;
        safetyReconnectSuppressed = false;
        safetyDisconnectInProgress = false;
        syncReconnectModules();
        resetDetectors();
        itemsInGui = 0;
        guiRetries = 0;
        delayUntilMs = System.currentTimeMillis() + 1_000;
        waitingForReconnectFlow = reconnectReturnEnabled()
            && (anchorPosition == null || returnAfterReconnect || manualJoinAfterSafety);
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        returnAfterReconnect = false;
        state = waitingForReconnectFlow ? State.IDLE : State.SENDING_COMMAND;
    }

    @AliceHandler
    public void onRender2D(Render2DEvent event) {
        if (!isActive() || !showHud.get() || mc.player == null) return;

        TextRenderer text = TextRenderer.get();
        if (text == null) return;

        List<String> labels = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        labels.add("Uptime: " + formatDuration(sessionElapsedMs()));
        colors.add(HUD_TEXT);
        labels.add("Money: " + formatMoney(sessionMoney));
        colors.add(HUD_MONEY);
        labels.add("Total Sell: " + sessionSold);
        colors.add(HUD_TEXT);
        labels.add("Average: " + formatMoney(moneyPerHour()) + "/h");
        colors.add(HUD_MONEY);
        labels.add("State: " + stateLabel());
        colors.add(HUD_STATE);
        labels.add("AutoReconnect: " + autoReconnectHudLabel());
        colors.add(HUD_TEXT);
        if (playerDetection.get()) {
            labels.add("Player Detection: " + playerDetectionHudLabel());
            colors.add(HUD_STATE);
        }

        double maxWidth = 0;
        for (String label : labels) maxWidth = Math.max(maxWidth, text.getWidth(label, false));
        double lineHeight = text.getHeight(false);
        double padding = 4;
        double lineGap = 2;
        double width = maxWidth + padding * 2;
        double height = labels.size() * lineHeight + (labels.size() - 1) * lineGap + padding * 2;
        double x = mc.getWindow().getScaledWidth() / 2.0 - width - 10;
        double y = mc.getWindow().getScaledHeight() / 2.0 - height - 10;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x, y, width, height, new Color(20, 20, 20, 180));
        Renderer2D.COLOR.quad(x, y, width, 2, new Color(85, 255, 170, 255));
        renderBackground(event);

        text.begin(1.0, false, true);
        double textY = y + padding + 3;
        for (int i = 0; i < labels.size(); i++) {
            text.render(labels.get(i), x + padding, textY, colors.get(i), true);
            textY += lineHeight + lineGap;
        }
        text.end();
    }

    private void sendSellCommand() {
        GenericContainerScreen gui = currentGui();
        lastSyncId = gui == null ? -1 : gui.getScreenHandler().syncId;

        mc.player.networkHandler.sendChatCommand("sell");
        guiWaitDeadlineMs = System.currentTimeMillis() + GUI_WAIT_TIMEOUT_MS;
        state = State.WAITING_GUI;
    }

    private void waitForSellGui() {
        GenericContainerScreen gui = currentGui();
        if (gui != null && gui.getScreenHandler().syncId != lastSyncId) {
            guiRetries = 0;
            delayTicks(0);
            state = State.MOVING_ITEMS;
            return;
        }

        if (System.currentTimeMillis() < guiWaitDeadlineMs) return;

        guiRetries++;
        if (guiRetries >= MAX_GUI_RETRIES) {
            guiRetries = 0;
            state = State.COOLDOWN;
            delayUntilMs = System.currentTimeMillis() + 10_000;
            notifyPlayer("Failed to open the sell GUI after 3 retries.");
        } else {
            state = State.SENDING_COMMAND;
        }
    }

    private void moveItems() {
        GenericContainerScreen gui = currentGui();
        if (gui == null) {
            state = State.SENDING_COMMAND;
            return;
        }
        if (mc.interactionManager == null) return;

        ScreenHandler handler = gui.getScreenHandler();
        if (handler.slots.size() < 36) {
            state = State.SENDING_COMMAND;
            return;
        }

        if (countFreeSellSlots(handler) == 0) {
            state = State.CLICKING_SELL;
            return;
        }

        int playerStart = handler.slots.size() - 36;
        int moved = 0;
        int freeSellSlots = countFreeSellSlots(handler);
        for (
            int slot = playerStart;
            slot < handler.slots.size() && moved < freeSellSlots;
            slot++
        ) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || shouldSkip(stack)) continue;

            int count = stack.getCount();
            mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            itemsInGui += count;
            moved++;
        }

        if (moved > 0) {
            delayTicks(moveDelayTicks.get());
            return;
        }

        if (itemsInGui > 0) {
            state = State.CLICKING_SELL;
        } else {
            closeSellScreen();
            state = State.COOLDOWN;
            delayUntilMs = System.currentTimeMillis() + 1_000;
        }
    }

    private void clickSell() {
        GenericContainerScreen gui = currentGui();
        if (gui == null) {
            state = State.SENDING_COMMAND;
            return;
        }
        if (mc.interactionManager == null) return;

        ScreenHandler handler = gui.getScreenHandler();
        if (countFreeSellSlots(handler) > 0 && hasSellablePlayerItems(handler)) {
            state = State.MOVING_ITEMS;
            return;
        }

        if (handler.slots.size() <= 53 || itemsInGui <= 0) {
            closeSellScreen();
            state = State.COOLDOWN;
            delayUntilMs = System.currentTimeMillis() + 1_000;
            return;
        }

        int sold = itemsInGui;
        mc.interactionManager.clickSlot(handler.syncId, 53, 0, SlotActionType.PICKUP, mc.player);
        sessionSold += sold;
        itemsInGui = 0;
        delayTicks(sellDelayTicks.get());
        state = State.WAITING_AFTER_SELL;
    }

    private void afterSell() {
        GenericContainerScreen gui = currentGui();
        if (gui == null || mc.interactionManager == null) {
            state = State.SENDING_COMMAND;
            return;
        }
        mc.interactionManager.clickSlot(
            gui.getScreenHandler().syncId, -999, 0, SlotActionType.PICKUP, mc.player
        );
        state = State.MOVING_ITEMS;
    }

    private boolean tickPositionControl() {
        if (anchorPosition == null || mc.player == null) return false;
        if (!autoReturn.get()) {
            if (state == State.RETURNING || returnEpisodeActive) stopReturnEpisode(false);
            waitingForAnchorContext = false;
            return false;
        }

        boolean returnRequested = state == State.RETURNING || returnEpisodeActive || returnOnPush.get();
        if (returnRequested && !isInAnchorContext()) {
            if (!waitingForAnchorContext) {
                waitingForAnchorContext = true;
                pauseAutomation();
                notifyPlayer("Waiting for the saved server and dimension before returning.");
            }
            return true;
        }
        waitingForAnchorContext = false;

        double distance = mc.player.getEntityPos().distanceTo(anchorPosition);
        if (distance <= 0.5) {
            if (returnEpisodeActive || state == State.RETURNING) {
                stopReturnEpisode(true);
            }
            return false;
        }

        if (state == State.RETURNING
            || returnEpisodeActive
            || (returnOnPush.get() && distance > 1.0D)) {
            if (state != State.RETURNING) beginReturn("Returning to the saved position.");
            moveTowardAnchor(distance);
            return true;
        }
        return false;
    }

    private void beginReturn(String message) {
        waterNoPushSuspended = true;
        closeSellScreen();
        itemsInGui = 0;
        state = State.RETURNING;
        nextReturnMoveMs = 0L;
        if (!returnEpisodeActive) {
            returnEpisodeActive = true;
            baritoneWarningShown = false;
            notifyPlayer(message);
        }
        startBaritonePath();
    }

    private void moveTowardAnchor(double distance) {
        if (!autoReturn.get() || anchorPosition == null || mc.player == null) {
            stopReturnEpisode(false);
            return;
        }
        if (distance <= 0.5) {
            stopReturnEpisode(true);
            return;
        }

        if (baritonePathing) {
            if (baritone.isPathing()) return;
            baritonePathing = baritone.start(BlockPos.ofFloored(anchorPosition));
            if (baritonePathing) return;
        }

        if (!baritoneWarningShown) {
            baritoneWarningShown = true;
            criticalLog("Baritone is unavailable. Please install a compatible Baritone build; using fallback movement.");
        }

        long now = System.currentTimeMillis();
        if (now < nextReturnMoveMs) return;
        nextReturnMoveMs = now + 200L;

        Vec3d current = mc.player.getEntityPos();
        double dx = anchorPosition.x - current.x;
        double dz = anchorPosition.z - current.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.001) return;

        double speed = 0.2873;
        mc.player.setVelocity(
            dx / horizontal * speed,
            mc.player.getVelocity().y,
            dz / horizontal * speed
        );
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private void startBaritonePath() {
        if (anchorPosition == null || baritonePathing) return;
        baritonePathing = baritone.start(BlockPos.ofFloored(anchorPosition));
    }

    private void stopReturnEpisode(boolean completed) {
        cancelBaritone();
        boolean wasReturning = returnEpisodeActive || state == State.RETURNING;
        returnEpisodeActive = false;
        waterNoPushSuspended = false;
        baritoneWarningShown = false;
        nextReturnMoveMs = 0L;
        state = State.SENDING_COMMAND;
        delayUntilMs = System.currentTimeMillis() + 250L;
        if (completed && wasReturning) notifyPlayer("Returned to the saved position.");
    }

    private boolean tickPlayerDetection(AliceAutoReconnect aliceReconnect) {
        if (mc.player == null || mc.world == null) return false;
        if (!playerDetection.get()) {
            clearPlayerDetection();
            ignoredFalsePositivePlayers.clear();
            return false;
        }

        Set<UUID> loadedPlayers = new HashSet<>();
        List<PlayerEntity> strangers = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.getUuid().equals(mc.player.getUuid()) || player.isDead()) continue;
            loadedPlayers.add(player.getUuid());
            String name = player.getName().getString();
            if (name.isBlank() || matchesIgnoreCase(whitelistPlayers.get(), name)) continue;
            if (!ignoredFalsePositivePlayers.contains(player.getUuid())) strangers.add(player);
        }
        ignoredFalsePositivePlayers.retainAll(loadedPlayers);

        if (strangers.isEmpty()) {
            if (playerDetectionPending || playerDetectionConfirmed) {
                criticalLog("Detected player left the loaded world. Auto Sell resumed.");
                clearPlayerDetection();
            }
            return false;
        }

        PlayerEntity first = strangers.getFirst();
        if (!playerDetectionPending && !playerDetectionConfirmed) {
            playerDetectionPending = true;
            playerConfirmationTicks = 0;
            detectedPlayer = first.getName().getString();
            detectedPlayerDistance = mc.player.getEntityPos().distanceTo(first.getEntityPos());
            pauseAutomation();
            criticalLog(String.format(Locale.ROOT,
                "Player detected: %s (%.1f blocks). Confirming for 80 ticks.",
                detectedPlayer, detectedPlayerDistance));
            if (discordTracking.get() && discordNotifyOnPlayer.get()) {
                sendPlayerWebhook(detectedPlayer, detectedPlayerDistance);
            }
        }

        pauseAutomation();
        if (playerDetectionConfirmed) return true;

        playerConfirmationTicks++;
        if (playerConfirmationTicks < PLAYER_CONFIRM_TICKS) return true;

        if (aliceReconnect != null && !aliceReconnect.isIdle()) {
            for (PlayerEntity player : strangers) {
                ignoredFalsePositivePlayers.add(player.getUuid());
            }
            criticalLog("Player alert marked as a reconnect/hub false positive.");
            clearPlayerDetection();
            return true;
        }

        playerDetectionPending = false;
        playerDetectionConfirmed = true;
        if (disconnectOnPlayer.get()) {
            safetyDisconnect("Confirmed player: " + detectedPlayer);
        } else {
            criticalLog("Player confirmed, but Disconnect On Player is disabled.");
        }
        return true;
    }

    private void safetyDisconnect(String reason) {
        if (safetyDisconnectInProgress || mc.getNetworkHandler() == null) return;
        pauseAutomation();
        disableReconnectModules();
        safetyReconnectSuppressed = true;
        safetyDisconnectInProgress = true;
        criticalLog(reason + ". Reconnect modules disabled; disconnecting now.");
        mc.getNetworkHandler().getConnection().disconnect(Text.literal(reason));
    }

    private void clearPlayerDetection() {
        playerDetectionPending = false;
        playerDetectionConfirmed = false;
        playerConfirmationTicks = 0;
        detectedPlayer = "";
        detectedPlayerDistance = 0.0;
    }

    private void pollEscapePause() {
        boolean down = mc.getWindow() != null
            && GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (down && !escWasDown) {
            escPauseUntilMs = System.currentTimeMillis() + ESC_PAUSE_MS;
            pauseAutomation();
            criticalLog("Paused for 4 seconds because ESC was pressed.");
        }
        escWasDown = down;
    }

    private boolean isEscapePaused() {
        return System.currentTimeMillis() < escPauseUntilMs;
    }

    private void pauseAutomation() {
        closeSellScreen();
        itemsInGui = 0;
        cancelBaritone();
        if (state != State.IDLE) state = State.SENDING_COMMAND;
    }

    private void cancelBaritone() {
        if (baritonePathing) baritone.cancel();
        baritonePathing = false;
    }

    private boolean tickReconnectFlowWait(AliceAutoReconnect aliceReconnect) {
        if (!waitingForReconnectFlow) return false;
        if (aliceReconnect != null && !aliceReconnect.isIdle()) {
            reconnectFlowObserved = true;
            return true;
        }
        reconnectFlowWaitTicks++;
        if (!reconnectFlowObserved && reconnectFlowWaitTicks < RECONNECT_FLOW_GRACE_TICKS) return true;

        waitingForReconnectFlow = false;
        reconnectFlowObserved = false;
        reconnectFlowWaitTicks = 0;
        if (anchorPosition == null) {
            captureAnchor();
            state = State.SENDING_COMMAND;
        } else {
            beginReturn("Reconnect flow finished. Returning to the saved position.");
        }
        return false;
    }

    private void captureAnchor() {
        if (mc.player == null || mc.world == null) {
            anchorPosition = null;
            anchorWorld = null;
            anchorServer = "";
            return;
        }
        anchorPosition = mc.player.getEntityPos();
        anchorWorld = mc.world.getRegistryKey();
        anchorServer = currentServerKey();
    }

    private boolean isInAnchorContext() {
        return mc.world != null
            && anchorWorld != null
            && anchorWorld.equals(mc.world.getRegistryKey())
            && anchorServer.equalsIgnoreCase(currentServerKey());
    }

    private String currentServerKey() {
        return mc.getCurrentServerEntry() == null ? "singleplayer" : mc.getCurrentServerEntry().address;
    }

    private boolean reconnectReturnEnabled() {
        return autoReturn.get() && returnOnDisconnect.get();
    }

    private void syncReconnectModules() {
        if (!isActive() || !reconnectReturnEnabled() || safetyReconnectSuppressed) return;

        Module meteorReconnect = Modules.get().get(
            meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect.class
        );
        if (meteorReconnect != null && !meteorReconnect.isActive()) meteorReconnect.toggle();

        AliceAutoReconnect aliceReconnect = aliceAutoReconnect();
        if (aliceReconnect != null && !aliceReconnect.isActive()) aliceReconnect.toggle();
    }

    private void disableReconnectModules() {
        Module meteorReconnect = Modules.get().get(
            meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect.class
        );
        if (meteorReconnect != null && meteorReconnect.isActive()) meteorReconnect.toggle();

        AliceAutoReconnect aliceReconnect = aliceAutoReconnect();
        if (aliceReconnect != null && aliceReconnect.isActive()) aliceReconnect.toggle();
    }

    private void tickPasswordWarning() {
        if (!reconnectReturnEnabled() || safetyReconnectSuppressed || mc.player == null) return;
        AliceAutoReconnect aliceReconnect = aliceAutoReconnect();
        if (aliceReconnect == null || aliceReconnect.hasLoginPassword()) {
            nextPasswordWarningMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextPasswordWarningMs) return;
        nextPasswordWarningMs = now + PASSWORD_WARNING_INTERVAL_MS;
        criticalLog("Please enter the login password in Auto Login & Antibot & Auto Join.");
    }

    private AliceAutoReconnect aliceAutoReconnect() {
        return Modules.get().get(AliceAutoReconnect.class);
    }

    private String autoReconnectHudLabel() {
        Module meteorReconnect = Modules.get().get(
            meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect.class
        );
        String meteorState = meteorReconnect != null && meteorReconnect.isActive() ? "ON" : "OFF";
        AliceAutoReconnect aliceReconnect = aliceAutoReconnect();
        String aliceState = aliceReconnect == null ? "MISSING" : aliceReconnect.getStateLabel();
        return "Meteor " + meteorState + " | ALICE " + aliceState;
    }

    private String playerDetectionHudLabel() {
        if (playerDetectionConfirmed) {
            return detectedPlayer.isBlank() ? "Confirmed" : "Confirmed " + detectedPlayer;
        }
        if (playerDetectionPending) {
            String name = detectedPlayer.isBlank() ? "" : " " + detectedPlayer;
            return "Confirming " + playerConfirmationTicks + "/" + PLAYER_CONFIRM_TICKS + name;
        }
        if (!ignoredFalsePositivePlayers.isEmpty()) {
            return "False Positive (" + ignoredFalsePositivePlayers.size() + ")";
        }
        return "Monitoring";
    }

    private void tickWebhookStatus() {
        if (!discordTracking.get() || discordWebhookUrl.get().isBlank() || mc.player == null) return;

        long now = Instant.now().getEpochSecond();
        if (now - lastWebhookStatusSeconds < discordUpdateInterval.get()) return;
        lastWebhookStatusSeconds = now;

        String json = "{\"embeds\":[{\"title\":\"Session Update\",\"color\":16750848,"
            + "\"fields\":["
            + field("Uptime", formatDuration(sessionElapsedMs())) + ","
            + field("Money", formatMoney(sessionMoney)) + ","
            + field("Total Sell", Integer.toString(sessionSold)) + ","
            + field("Average", formatMoney(moneyPerHour()) + "/h")
            + "],\"footer\":{\"text\":\"Session Monitor\"}}]}";
        postWebhook(json);
    }

    private void sendPlayerWebhook(String name, double distance) {
        String json = "{\"embeds\":[{\"title\":\"Safety Alert\",\"color\":15105570,"
            + "\"fields\":["
            + field("Player", name) + ","
            + field(
                "Distance",
                String.format(Locale.ROOT, "%.1f blocks", distance)
            )
            + "],\"footer\":{\"text\":\"Safety Monitor\"}}]}";
        postWebhook(json);
    }

    private void postWebhook(String json) {
        String url = discordWebhookUrl.get().trim();
        if (!discordTracking.get() || url.isBlank()) return;

        Thread.ofVirtual().name("session-status-webhook").start(() -> {
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

    private void parseMoney(String message) {
        if (message == null || message.isBlank()) return;
        Matcher matcher = MONEY_PATTERN.matcher(message);
        if (!matcher.find()) return;

        try {
            double amount = Double.parseDouble(matcher.group(1));
            switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
                case "K" -> amount *= 1_000;
                case "M" -> amount *= 1_000_000;
                case "B" -> amount *= 1_000_000_000;
                default -> {
                }
            }
            sessionMoney += amount;
        } catch (NumberFormatException ignored) {
        }
    }

    private GenericContainerScreen currentGui() {
        return mc.currentScreen instanceof GenericContainerScreen screen ? screen : null;
    }

    private int countFreeSellSlots(ScreenHandler handler) {
        int limit = Math.min(45, handler.slots.size());
        int free = 0;
        for (int slot = 0; slot < limit; slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) free++;
        }
        return free;
    }

    private boolean hasSellablePlayerItems(ScreenHandler handler) {
        int playerStart = Math.max(0, handler.slots.size() - 36);
        for (int slot = playerStart; slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && !shouldSkip(stack)) return true;
        }
        return false;
    }

    private boolean shouldSkip(ItemStack stack) {
        boolean listed = itemsToSell.get().contains(stack.getItem());
        return !AutoSellLogic.shouldSell(filterMode.get(), listed);
    }

    private void closeSellScreen() {
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
    }

    private void resetSession() {
        sessionSold = 0;
        sessionMoney = 0;
        sessionStartTime = System.currentTimeMillis();
        lastWebhookStatusSeconds = Instant.now().getEpochSecond();
        itemsInGui = 0;
        guiRetries = 0;
        delayUntilMs = 0;
    }

    private void resetDetectors() {
        clearPlayerDetection();
        ignoredFalsePositivePlayers.clear();
    }

    private void notifyPlayer(String message) {
        if (notifications.get() && mc.player != null) {
            mc.player.sendMessage(Text.literal("[ALICE Auto Sell] " + message), false);
        }
    }

    private void criticalLog(String message) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[ALICE Auto Sell] " + message), false);
        }
    }

    private void delayTicks(int ticks) {
        delayUntilMs = System.currentTimeMillis() + ticks * 50L;
    }

    private static boolean matchesIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) return false;
        for (String value : values) {
            if (value != null && value.trim().equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private String stateLabel() {
        if (playerDetectionConfirmed) return "Player Confirmed";
        if (playerDetectionPending) {
            return "Player Check " + playerConfirmationTicks + "/" + PLAYER_CONFIRM_TICKS;
        }
        if (autoReconnectPaused) return "AutoReconnect";
        if (waitingForReconnectFlow) return "Waiting Reconnect";
        if (isEscapePaused()) {
            double remaining = Math.max(0L, escPauseUntilMs - System.currentTimeMillis()) / 1_000.0;
            return String.format(Locale.ROOT, "ESC Paused %.1fs", remaining);
        }
        if (waitingForAnchorContext) return "Waiting Anchor World";
        if (state == State.RETURNING) {
            return baritonePathing ? "Returning (Baritone)" : "Returning (Fallback)";
        }
        return switch (state) {
            case IDLE -> "Idle";
            case SENDING_COMMAND -> "Sending Command";
            case WAITING_GUI -> "Waiting GUI";
            case MOVING_ITEMS -> "Moving Items";
            case CLICKING_SELL -> "Selling";
            case WAITING_AFTER_SELL -> "Waiting";
            case COOLDOWN -> "Cooldown";
            case RETURNING -> "Returning";
        };
    }

    private long sessionElapsedMs() {
        return sessionStartTime == 0 ? 0 : System.currentTimeMillis() - sessionStartTime;
    }

    private double moneyPerHour() {
        double hours = sessionElapsedMs() / 3_600_000.0;
        return hours < 0.001 ? 0 : sessionMoney / hours;
    }

    private static String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1_000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
            seconds / 3_600,
            (seconds % 3_600) / 60,
            seconds % 60);
    }

    private static String formatMoney(double value) {
        if (value >= 1_000_000_000) return String.format(Locale.ROOT, "$%.2fB", value / 1_000_000_000);
        if (value >= 1_000_000) return String.format(Locale.ROOT, "$%.2fM", value / 1_000_000);
        if (value >= 1_000) return String.format(Locale.ROOT, "$%.2fK", value / 1_000);
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

    private static java.lang.reflect.Method cachedRenderMethod;
    private static boolean renderMethodInitialized;

    private void renderBackground(Render2DEvent event) {
        if (!renderMethodInitialized) {
            renderMethodInitialized = true;
            for (java.lang.reflect.Method method : Renderer2D.COLOR.getClass().getMethods()) {
                if (method.getName().equals("render")
                    && (method.getParameterCount() == 0 || method.getParameterCount() == 1)) {
                    cachedRenderMethod = method;
                    break;
                }
            }
        }
        if (cachedRenderMethod == null) return;

        try {
            if (cachedRenderMethod.getParameterCount() == 0) {
                cachedRenderMethod.invoke(Renderer2D.COLOR);
            } else {
                Class<?> type = cachedRenderMethod.getParameterTypes()[0];
                cachedRenderMethod.invoke(Renderer2D.COLOR,
                    type.getName().contains("DrawContext")
                        ? event.drawContext
                        : event.drawContext.getMatrices());
            }
        } catch (Exception ignored) {
        }
    }

    private static final class BaritoneAdapter {
        private boolean initialized;
        private boolean available;
        private Object customGoalProcess;
        private Object pathingBehavior;
        private Constructor<?> goalConstructor;
        private boolean goalUsesBlockPos;
        private Method setGoalAndPath;
        private Method cancelEverything;
        private Method isPathing;

        boolean start(BlockPos target) {
            if (!initialize() || target == null) return false;
            try {
                Object goal = goalUsesBlockPos
                    ? goalConstructor.newInstance(target)
                    : goalConstructor.newInstance(target.getX(), target.getY(), target.getZ());
                setGoalAndPath.invoke(customGoalProcess, goal);
                return true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                available = false;
                return false;
            }
        }

        void cancel() {
            if (!initialized || !available || cancelEverything == null || pathingBehavior == null) return;
            try {
                cancelEverything.invoke(pathingBehavior);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        boolean isPathing() {
            if (!initialized || !available || isPathing == null || pathingBehavior == null) return false;
            try {
                return Boolean.TRUE.equals(isPathing.invoke(pathingBehavior));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return false;
            }
        }

        private boolean initialize() {
            if (initialized) return available;
            initialized = true;
            try {
                ClassLoader loader = AutoSell.class.getClassLoader();
                Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI", false, loader);
                Object provider = apiClass.getMethod("getProvider").invoke(null);
                Object primaryBaritone = findMethod(provider.getClass(), "getPrimaryBaritone", 0).invoke(provider);
                if (primaryBaritone == null) return false;

                customGoalProcess = findMethod(
                    primaryBaritone.getClass(), "getCustomGoalProcess", 0
                ).invoke(primaryBaritone);
                pathingBehavior = findMethod(
                    primaryBaritone.getClass(), "getPathingBehavior", 0
                ).invoke(primaryBaritone);
                if (customGoalProcess == null || pathingBehavior == null) return false;

                Class<?> goalBlockClass = Class.forName(
                    "baritone.api.pathing.goals.GoalBlock", false, loader
                );
                for (Constructor<?> constructor : goalBlockClass.getConstructors()) {
                    Class<?>[] parameters = constructor.getParameterTypes();
                    if (parameters.length == 1 && parameters[0].isAssignableFrom(BlockPos.class)) {
                        goalConstructor = constructor;
                        goalUsesBlockPos = true;
                        break;
                    }
                    if (parameters.length == 3
                        && parameters[0] == int.class
                        && parameters[1] == int.class
                        && parameters[2] == int.class) {
                        goalConstructor = constructor;
                        goalUsesBlockPos = false;
                    }
                }
                if (goalConstructor == null) return false;

                setGoalAndPath = findMethod(customGoalProcess.getClass(), "setGoalAndPath", 1);
                cancelEverything = findOptionalMethod(pathingBehavior.getClass(), "cancelEverything", 0);
                isPathing = findOptionalMethod(pathingBehavior.getClass(), "isPathing", 0);
                available = setGoalAndPath != null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                available = false;
            }
            return available;
        }

        private static Method findMethod(Class<?> type, String name, int parameterCount)
            throws NoSuchMethodException {
            Method method = findOptionalMethod(type, name, parameterCount);
            if (method == null) throw new NoSuchMethodException(type.getName() + "#" + name);
            return method;
        }

        private static Method findOptionalMethod(Class<?> type, String name, int parameterCount) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
            return null;
        }
    }
}
