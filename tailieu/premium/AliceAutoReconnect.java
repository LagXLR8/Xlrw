package com.nnpg.alice.premium;

import java.text.Normalizer;
import java.util.Locale;

import com.nnpg.alice.AliceAddon;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public class AliceAutoReconnect extends AliceModule {

    private static final long MENU_TIMEOUT_MS = 8000L;
    private static final long ANTIBOT_TIMEOUT_MS = 20000L;
    private static final long POST_VERIFY_WAIT_MS = 1500L;
    private static final long SPAWNER_RESTORE_SETTLE_MS = 2000L;
    private static final long GLOBAL_FLOW_TIMEOUT_MS = 3 * 60 * 1000L; // 3 minutes

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<String> loginPassword = sgGeneral.add(new StringSetting.Builder()
        .name("login-password")
        .description("mat khau cho lenh /l")
        .defaultValue("")
        .build());
    private final Setting<Integer> actionDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("click-delay-ticks")
        .description("thoi gian tre giua moi lenh va click")
        .defaultValue(15)
        .min(15)
        .max(40)
        .build());
    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("hien thi thong bao")
        .defaultValue(true)
        .build());


    private State currentState = State.IDLE;
    private boolean verifySuccess = false;
    private boolean restoreSpawnerPending = false;
    private boolean spawnerWasActiveBeforeFlow = false;
    private long stateEnteredAtMs = 0L;
    private long loginSentAtMs = 0L;
    private long lastMenuSentAtMs = 0L;
    private long lastClickAtMs = 0L;
    private long restoreSpawnerAtMs = 0L;
    private long flowStartedAtMs = 0L;


    public AliceAutoReconnect() {
        super(AliceAddon.CATEGORY, "Auto Login & Antibot & Auto Join", "nhan dien /dn, tu dong dang nhap, xu ly antibot va khoi phuc SpawnerOrder");
    }

    /**
     * Returns true if the module is currently handling login/menu flow.
     * SpawnerProtect should NOT detect players while this returns true.
     */
    public boolean isInLoginFlow() {
        return isActive() && currentState != State.IDLE;
    }

    public boolean isIdle() {
        return !isActive() || currentState == State.IDLE;
    }

    public boolean hasLoginPassword() {
        return loginPassword.get() != null && !loginPassword.get().trim().isEmpty();
    }

    public String getStateLabel() {
        if (!isActive()) {
            return "OFF";
        }
        if (currentState == State.WAITING_LOGIN_SUCCESS) {
            return "WAITING LOGIN";
        }
        if (currentState == State.WAITING_TO_RESTORE_SPAWNER && restoreSpawnerPending) {
            long remainingMs = Math.max(0L, restoreSpawnerAtMs - System.currentTimeMillis());
            return "RESTORE " + String.format(Locale.US, "%.1fs", (double) remainingMs / 1000.0);
        }
        return currentState.name().replace("_", " ");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        resetFlowState();
        Module autoReconnect = Modules.get().get(meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect.class);
        if (autoReconnect != null && !autoReconnect.isActive()) {
            autoReconnect.toggle();
        }
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        restoreSpawnerOrder(true);
        resetFlowState();
    }

    @AliceHandler
    public void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive()) {
            return;
        }

        String normalized = normalizeViet(event.getMessage().getString()).toLowerCase(Locale.ROOT);

        if (isMenuTriggerMessage(normalized)) {
            beginMenuFlowFromTrigger(normalized);
            return;
        }

        if (currentState == State.ANTIBOT_VERIFY && normalized.contains("xac minh thanh cong")) {
            verifySuccess = true;
            currentState = State.WAIT_AFTER_VERIFY;
            stateEnteredAtMs = System.currentTimeMillis();
            log("[AutoLogin] Xác minh antibot thành công.");
            return;
        }

        if (currentState == State.WAITING_LOGIN_SUCCESS && normalized.contains("/dn")) {
            if (canUseChat() && System.currentTimeMillis() - loginSentAtMs >= Math.max(1500L, getActionDelayMs())) {
                sendLoginCommand();
                log("[AutoLogin] /dn xuất hiện lại, đã gửi lại /l.");
            }
            return;
        }

        if (currentState != State.IDLE) {
            return;
        }

        // Trigger immediately when chat contains the login prompt
        if (normalized.contains("dang nhap bang lenh") && normalized.contains("/dn")) {
            startLoginFlow();
        }
    }


    @AliceHandler
    public void onTick(TickEvent.Pre event) {
        if (!isActive()) {
            return;
        }


        // Global 3-minute timeout: if flow is stuck, force restore and go IDLE
        if (currentState != State.IDLE && flowStartedAtMs > 0) {
            if (System.currentTimeMillis() - flowStartedAtMs >= GLOBAL_FLOW_TIMEOUT_MS) {
                warn("[AutoLogin] Flow timeout (3 min)! Forcing restore and returning to IDLE.");
                if (mc.currentScreen != null && mc.player != null) {
                    mc.player.closeHandledScreen();
                }
                restoreSpawnerOrder(true);
                resetFlowState();
                return;
            }
        }

        switch (currentState) {
            case IDLE -> restoreSpawnerOrder(false);
            case WAITING_TO_SEND_LOGIN -> handleWaitingToSendLogin();
            case WAITING_LOGIN_SUCCESS -> {
            }
            case SENDING_MENU -> handleSendingMenu();
            case WAIT_FOR_MENU_GUI -> handleWaitForMenuGui();
            case ANTIBOT_VERIFY -> handleAntibotVerify();
            case WAIT_AFTER_VERIFY -> handleWaitAfterVerify();
            case CLICKING_FINAL_SLOT -> handleClickingFinalSlot();
            case WAITING_TO_RESTORE_SPAWNER -> handleWaitingToRestoreSpawner();
        }
    }

    private void startLoginFlow() {
        verifySuccess = false;
        lastClickAtMs = 0L;
        flowStartedAtMs = System.currentTimeMillis();
        stopSpawnerOrder();
        currentState = State.WAITING_TO_SEND_LOGIN;
        stateEnteredAtMs = System.currentTimeMillis();
        log("[AutoLogin] \u00D0\u00E3 ph\u00E1t hi\u1EC7n /dn. \u00D0ang ch\u1EDD g\u1EEDi /l.");
    }

    private void handleWaitingToSendLogin() {
        if (!canUseChat()) {
            return;
        }
        if (System.currentTimeMillis() - stateEnteredAtMs < getActionDelayMs()) {
            return;
        }
        sendLoginCommand();
        currentState = State.WAITING_LOGIN_SUCCESS;
        stateEnteredAtMs = loginSentAtMs;
        log("[AutoLogin] \u00D0\u00E3 g\u1EEDi /l sau khi v\u00E0o game.");
    }

    private void handleSendingMenu() {
        if (!canUseChat()) {
            return;
        }
        // Doi 2-3 giay truoc khi gui /menu
        if (System.currentTimeMillis() - stateEnteredAtMs < 2500) {
            return;
        }

        ChatUtils.sendPlayerMsg("/menu");
        verifySuccess = false;
        lastMenuSentAtMs = System.currentTimeMillis();
        lastClickAtMs = 0L;
        currentState = State.WAIT_FOR_MENU_GUI;
        stateEnteredAtMs = lastMenuSentAtMs;
        log("[AutoLogin] \u00D0\u00E3 g\u1EEDi /menu.");
    }

    private void handleWaitForMenuGui() {
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.GenericContainerScreen screen)) {
            if (System.currentTimeMillis() - lastMenuSentAtMs >= MENU_TIMEOUT_MS) {
                currentState = State.SENDING_MENU;
                stateEnteredAtMs = System.currentTimeMillis();
            }
            return;
        }

        if (hasAntibotSlot(screen)) {
            currentState = State.ANTIBOT_VERIFY;
            stateEnteredAtMs = System.currentTimeMillis();
            lastClickAtMs = 0L;
            log("[AutoLogin] Ph\u00E1t hi\u1EC7n antibot, b\u1EAFt \u0111\u1EA7u x\u00E1c minh.");
        } else {
            currentState = State.CLICKING_FINAL_SLOT;
            stateEnteredAtMs = System.currentTimeMillis();
            lastClickAtMs = 0L;
        }
    }

    private void handleAntibotVerify() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            if (System.currentTimeMillis() - stateEnteredAtMs >= MENU_TIMEOUT_MS) {
                currentState = State.SENDING_MENU;
                stateEnteredAtMs = System.currentTimeMillis();
            }
            return;
        }

        if (verifySuccess) {
            currentState = State.WAIT_AFTER_VERIFY;
            stateEnteredAtMs = System.currentTimeMillis();
            return;
        }

        if (System.currentTimeMillis() - stateEnteredAtMs >= ANTIBOT_TIMEOUT_MS) {
            if (mc.player != null) {
                mc.player.closeHandledScreen();
            }
            currentState = State.SENDING_MENU;
            stateEnteredAtMs = System.currentTimeMillis();
            warn("[AutoLogin] Timeout antibot, thử lại /menu.");
            return;
        }

        if (!canClick()) {
            return;
        }
        if (System.currentTimeMillis() - lastClickAtMs < getClickDelayMs()) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        int slot = findVerifySlot(handler);
        if (slot == -1) {
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        lastClickAtMs = System.currentTimeMillis();
    }

    private void handleWaitAfterVerify() {
        if (System.currentTimeMillis() - stateEnteredAtMs < POST_VERIFY_WAIT_MS) {
            return;
        }
        currentState = State.CLICKING_FINAL_SLOT;
        stateEnteredAtMs = System.currentTimeMillis();
        lastClickAtMs = 0L;
    }

    private void handleClickingFinalSlot() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            if (System.currentTimeMillis() - stateEnteredAtMs >= MENU_TIMEOUT_MS) {
                currentState = State.SENDING_MENU;
                stateEnteredAtMs = System.currentTimeMillis();
            }
            return;
        }

        if (!canClick()) {
            return;
        }
        if (System.currentTimeMillis() - lastClickAtMs < getClickDelayMs()) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (handler.slots.size() <= 24) {
            if (System.currentTimeMillis() - stateEnteredAtMs >= MENU_TIMEOUT_MS) {
                currentState = State.SENDING_MENU;
                stateEnteredAtMs = System.currentTimeMillis();
            }
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, 24, 0, SlotActionType.PICKUP, mc.player);
        lastClickAtMs = System.currentTimeMillis();
        verifySuccess = false;
        currentState = State.WAITING_TO_RESTORE_SPAWNER;
        stateEnteredAtMs = lastClickAtMs;
        restoreSpawnerAtMs = lastClickAtMs + SPAWNER_RESTORE_SETTLE_MS;
        log("[AutoLogin] Đã click slot 24.");
    }

    private void handleWaitingToRestoreSpawner() {
        if (System.currentTimeMillis() < restoreSpawnerAtMs) {
            return;
        }

        if (mc.currentScreen instanceof GenericContainerScreen) {
            if (mc.player != null && System.currentTimeMillis() - stateEnteredAtMs >= MENU_TIMEOUT_MS) {
                mc.player.closeHandledScreen();
            }
            return;
        }

        if (!canUseChat()) {
            return;
        }

        restoreSpawnerOrder(false);
        currentState = State.IDLE;
        stateEnteredAtMs = System.currentTimeMillis();
        log("[AutoLogin] Hoàn tất flow đăng nhập.");
    }

    private void beginMenuFlowFromTrigger(String normalized) {
        if (isMenuFlowState(currentState)) {
            return;
        }
        stopSpawnerOrder();
        verifySuccess = false;
        lastClickAtMs = 0L;
        currentState = State.SENDING_MENU;
        stateEnteredAtMs = System.currentTimeMillis();
        log("[AutoLogin] Phát hiện chat trigger (" + getTriggerLabel(normalized) + "), bắt đầu mở /menu.");
    }

    private void sendLoginCommand() {
        String password = loginPassword.get().trim();
        if (password.isBlank()) {
            warn("[AutoLogin] Chưa nhập mật khẩu! Hãy điền vào setting 'login-password'.");
            // Reset về IDLE để không loop gửi lệnh rỗng
            currentState = State.IDLE;
            stateEnteredAtMs = System.currentTimeMillis();
            return;
        }
        ChatUtils.sendPlayerMsg("/l " + password);
        loginSentAtMs = System.currentTimeMillis();
    }



    private void stopSpawnerOrder() {
        try {
            Module module = Modules.get().get(SpawnerOrder.class);
            boolean moduleWasActive = module != null && module.isActive();
            if (moduleWasActive) {
                if (mc.currentScreen != null && mc.player != null) {
                    mc.player.closeHandledScreen();
                }
                ((SpawnerOrder) module).isPaused = true;
                spawnerWasActiveBeforeFlow = true;
                restoreSpawnerPending = true;
                log("[AutoLogin] Đã dừng SpawnerOrder.");
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreSpawnerOrder(boolean forceNow) {
        if (!restoreSpawnerPending || !spawnerWasActiveBeforeFlow) {
            return;
        }
        if (!forceNow) {
            if (!canUseChat()) {
                return;
            }
            if (mc.currentScreen != null) {
                return;
            }
        }
        try {
            Module module = Modules.get().get(SpawnerOrder.class);
            if (module != null && ((SpawnerOrder) module).isPaused) {
                ((SpawnerOrder) module).isPaused = false;
                log("[AutoLogin] Đã bật lại SpawnerOrder.");
            }
        } catch (Exception ignored) {
        } finally {
            restoreSpawnerPending = false;
            spawnerWasActiveBeforeFlow = false;
            restoreSpawnerAtMs = 0L;
        }
    }

    private boolean hasAntibotSlot(GenericContainerScreen screen) {
        ScreenHandler handler = screen.getScreenHandler();
        for (int i = 0; i < Math.min(handler.slots.size(), 54); ++i) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String name = normalizeViet(stack.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("xac minh") || name.contains("la nguoi") || name.contains("verify")) {
                return true;
            }
        }
        return false;
    }

    private int findVerifySlot(ScreenHandler handler) {
        for (int i = 0; i < Math.min(handler.slots.size(), 54); ++i) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String name = normalizeViet(stack.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("xac minh") || name.contains("la nguoi") || name.contains("verify")) {
                return i;
            }
        }
        return -1;
    }

    private boolean isMenuTriggerMessage(String normalized) {
        return normalized.contains("dang nhap thanh cong, neu chua tao ma pin, hay vao") || normalized.contains("server con moi nen neu co") || normalized.contains("khong duoc phep ban cac vat pham");
    }

    private String getTriggerLabel(String normalized) {
        if (normalized.contains("dang nhap thanh cong, neu chua tao ma pin, hay vao")) {
            return "login-success";
        }
        if (normalized.contains("server con moi nen neu co")) {
            return "server-moi";
        }
        if (normalized.contains("khong duoc phep ban cac vat pham")) {
            return "khong-duoc-phep-ban";
        }
        return "unknown";
    }

    private boolean isMenuFlowState(State state) {
        return state == State.SENDING_MENU || state == State.WAIT_FOR_MENU_GUI || state == State.ANTIBOT_VERIFY || state == State.WAIT_AFTER_VERIFY || state == State.CLICKING_FINAL_SLOT;
    }

    private long getClickDelayMs() {
        return actionDelayTicks.get() * 50L;
    }

    private long getActionDelayMs() {
        return actionDelayTicks.get() * 50L;
    }

    private boolean canUseChat() {
        return mc.player != null && mc.world != null;
    }

    private boolean canClick() {
        return canUseChat() && mc.interactionManager != null;
    }

    private void resetFlowState() {
        currentState = State.IDLE;
        verifySuccess = false;
        stateEnteredAtMs = 0L;
        loginSentAtMs = 0L;
        lastMenuSentAtMs = 0L;
        lastClickAtMs = 0L;
        restoreSpawnerPending = false;
        spawnerWasActiveBeforeFlow = false;
        restoreSpawnerAtMs = 0L;
        flowStartedAtMs = 0L;
    }

    private String normalizeViet(String input) {
        if (input == null) {
            return "";
        }
        return Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").replace("đ", "d").replace("Đ", "D");
    }

    private void log(String msg) {
    }

    private void warn(String msg) {
    }

    @Override
    public String getInfoString() {
        return isActive() ? getStateLabel() : null;
    }

    private enum State {
        IDLE,
        WAITING_TO_SEND_LOGIN,
        WAITING_LOGIN_SUCCESS,
        SENDING_MENU,
        WAIT_FOR_MENU_GUI,
        ANTIBOT_VERIFY,
        WAIT_AFTER_VERIFY,
        CLICKING_FINAL_SLOT,
        WAITING_TO_RESTORE_SPAWNER
    }

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
    }
}


