package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.SpawnerBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.entity.ItemEntity;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpawnerOrder extends AliceModule {
    public static java.lang.invoke.MethodHandles.Lookup getLookup() {
        return java.lang.invoke.MethodHandles.lookup();
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> dispenserClickCount = sgGeneral.add(new IntSetting.Builder()
        .name("drop-click-count").description("so lan click vao dispenser moi spawner").defaultValue(2).min(1).max(10).build());

    private final Setting<Boolean> fastMode = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-mode").description("dung logic che do nhanh cho GUI").defaultValue(true).build());

    private final Setting<Boolean> showHud = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hud").description("hien thi bang thong ke HUD").defaultValue(false).build());

    private final Setting<Boolean> playerTriggerMode = sgGeneral.add(new BoolSetting.Builder()
        .name("player-trigger-mode")
        .description("dung /order <player> thay vi /order <item>. khong check gia, chi giao dung item.")
        .defaultValue(false)
        .build());

    private final Setting<String> triggerPlayerName = sgGeneral.add(new StringSetting.Builder()
        .name("trigger-player-name")
        .description("ten player de gui lenh /order <player>")
        .defaultValue("")
        .visible(playerTriggerMode::get)
        .build());

    private final Queue<Map.Entry<Item, String>> pendingOrders = new LinkedList<>();
    private Item curOrderItem = Items.AIR;
    private String curOrderName = "";
    private boolean hasItemsToSell = false;

    private static final Map<Item, String> ORDER_ITEMS = Map.of(
        Items.LEATHER, "leather",
        Items.IRON_INGOT, "iron",
        Items.BONE, "bone",
        Items.GUNPOWDER, "gunpowder",
        Items.BLAZE_ROD, "blaze rod"
    );



    private enum State {
        SCANNING, MOVING,
        SILENT_ROTATE, SILENT_ROTATE_SMOOTH,
        OPENING, WAIT_CHEST_GUI,
        CLICK_BUNDLE, CLICK_DISPENSER, WAIT_DISPENSER_DROP,
        CLICK_SLOT52,
        CLOSING_SPAWNER, WAIT_CLEANUP,
        SELL_SEND, SELL_WAIT_GUI, SELL_FILL, SELL_CLOSE,
        ORDER_SEND, ORDER_WAIT_GUI, ORDER_CAULDRON, ORDER_WAIT_REOPEN,
        ORDER_FIND_SLOT, ORDER_CLICK_SLOT2, ORDER_FILL,
        ORDER_WAIT_CONFIRM, ORDER_CONFIRM, ORDER_WAIT_CLOSE, ORDER_CLOSE,
        WAITING_CYCLE,
        FAST_DROP_CLICK, FAST_ORDER_WAIT_NEXT_PAGE
    }

    private State currentState = State.WAIT_CLEANUP;
    private State lastState = State.WAIT_CLEANUP;
    private int tickCounter = 0;
    private int cleanupWaitTick = 0;
    private int cycleTickCounter = 0;
    private int currentActionDelay = 3;
    private int globalStateTick = 0;

    private final List<BlockPos> spawners = new ArrayList<>();
    private int spawnerIndex = 0;
    private BlockPos targetSpawner = null;
    private int openingAttempts = 0;
    private int movingTimeoutCounter = 0;
    private static final int MOVING_TIMEOUT = 20;
    private static final int OPEN_TIMEOUT_TICKS = 60;
    private float savedYaw = 0f, savedPitch = 0f;
    private float currentSilentYaw = 0f, currentSilentPitch = 0f;
    private int rotationTicks = 0;
    private static final int ROTATION_TICKS = 3;

    private int orderTimer = 0, orderTimeout = 0;
    private static final int ORDER_MAX_TIMEOUT = 200;
    private int nextPageClickCount = 0;
    private int dispenserWaitTick = 0, dispenserClicksDone = 0;

    private static final int CYCLE_DELAY_TICKS = 1;
    private static final int ACTION_DELAY_TICKS = 1;

    // Tạm dừng khi người dùng ấn ESC (mở menu game) trong lúc bot đang chạy.
    private static final int ESC_PAUSE_TICKS = 40; // 2 giây (20 tick/s)
    private int escPauseTick = 0;
    private boolean escWasDown = false;

    private boolean isFastMode() { return true; }

    private static SpawnerOrder activeInstance;
    public long sessionStartTime = 0;
    public double totalEarned = 0;
    public int totalOrders = 0;
    private String lastGuiTitle = "";
    private static final java.util.Random RNG = new java.util.Random();

    public SpawnerOrder() {
        super(AliceAddon.CATEGORY, "Spawner-Order", "vut do tu spawner roi tu dong dat hang cac item giong nhau trong tui");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        activeInstance = this;
        fastMode.set(true);
        resetToScanning();
        sessionStartTime = System.currentTimeMillis();
        totalEarned = 0;
        totalOrders = 0;
        escPauseTick = 0;
        escWasDown = false;
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        activeInstance = null;
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
    }

    public boolean isPaused = false;

    @AliceHandler
    public void onChat(ReceiveMessageEvent event) {
        if (!isActive() || isPaused) return;

        String msg = event.getMessage().getString();
        
        if (msg.contains("Số lượng giao vượt quá số lượng còn lại")) {
            if (mc.currentScreen instanceof GenericContainerScreen screen) {
                var handler = screen.getScreenHandler();
                boolean hasAnyItem = false;
                for (int i = 0; i < handler.slots.size(); i++) {
                    var stack = handler.slots.get(i).getStack();
                    if (stack.isEmpty()) continue;
                    if (stack.getItem() == curOrderItem) { hasAnyItem = true; break; }
                }
                if (hasAnyItem) {
                    mc.player.closeHandledScreen();
                    currentState = State.ORDER_WAIT_CONFIRM;
                    orderTimer = smartDelay() * 3;
                    orderTimeout = 0;
                } else {
                    mc.player.closeHandledScreen();
                    orderTimer = smartDelay() * 3;
                    orderTimeout = 0;
                    if (nextPendingOrder()) { currentState = State.ORDER_SEND; }
                    else { finishOrderQueue(); }
                }
            }
            return;
        }
        if (msg.contains("Có lỗi xảy ra khi giao hàng") || msg.contains("Co loi xay ra khi giao hang")) {
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            currentState = State.WAITING_CYCLE;
            cycleTickCounter = 0;
            orderTimer = 0;
            orderTimeout = 0;
            return;
        }
        if (!msg.contains("nhận được") && !msg.contains("nhan duoc")) return;
        Matcher m = Pattern.compile("\\+\\$\\s*([0-9]+\\.?[0-9]*[KkMmBb]?)").matcher(msg);
        if (m.find()) totalEarned += parsePrice(m.group(1));
        totalOrders++;
    }

    private boolean hasItemsOnGround() {
        if (mc.world == null || mc.player == null) return false;
        Box box = new Box(mc.player.getBlockPos()).expand(2);
        List<ItemEntity> items = mc.world.getEntitiesByClass(ItemEntity.class, box, e -> true);
        return !items.isEmpty();
    }

    private boolean hasItem(Item item) {
        if (item == Items.AIR) return true;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            var s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) return true;
        }
        return false;
    }

    private void buildPendingOrders() {
        pendingOrders.clear();
        hasItemsToSell = false;

        // Use public API instead of private field 'main' to avoid IllegalAccessError
        // when loaded by custom ClassLoader (cross-classloader private field access is blocked)
        for (int i = 0; i < 36; i++) { // main inventory is slots 0-35
            var stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            if (ORDER_ITEMS.containsKey(item)) {
                if (!pendingOrders.stream().anyMatch(e -> e.getKey() == item)) {
                    pendingOrders.add(new AbstractMap.SimpleEntry<>(item, ORDER_ITEMS.get(item)));
                }
            } else {
                if (item.getMaxCount() > 1) {
                    hasItemsToSell = true;
                }
            }
        }
    }

    private boolean nextPendingOrder() {
        while (!pendingOrders.isEmpty()) {
            var entry = pendingOrders.poll();
            if (hasItem(entry.getKey())) {
                curOrderItem = entry.getKey();
                curOrderName = entry.getValue();
                return true;
            }
        }
        return false;
    }

    private long getPing() {
        if (mc.getNetworkHandler() == null) return 0;
        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    private int smartDelay() {
        long ping = getPing();
        if (ping < 50)  return 2;
        if (ping < 100) return 3;
        if (ping < 200) return 5;
        if (ping < 300) return 8;
        return 12;
    }

    private float calcYawTo(BlockPos pos) {
        double dx = Vec3d.ofCenter(pos).x - mc.player.getX();
        double dz = Vec3d.ofCenter(pos).z - mc.player.getZ();
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
    }

    private float calcPitchTo(BlockPos pos) {
        double dx = Vec3d.ofCenter(pos).x - mc.player.getX();
        double dy = Vec3d.ofCenter(pos).y - mc.player.getEyeY();
        double dz = Vec3d.ofCenter(pos).z - mc.player.getZ();
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx + dz*dz)));
    }

    private void sendSilentRotation(float yaw, float pitch) {
        mc.player.networkHandler.sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), false));
    }

    private void interactWithBlock(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d player = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double dx = player.x - center.x, dy = player.y - center.y, dz = player.z - center.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        Direction face;
        if (ay >= ax && ay >= az) face = dy > 0 ? Direction.UP : Direction.DOWN;
        else if (ax >= az) face = dx > 0 ? Direction.EAST : Direction.WEST;
        else face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(center, face, pos, false));
    }

    private void startOrderQueue() {
        buildPendingOrders();
        orderTimer = isFastMode() ? smartDelay() : (smartDelay() * 3);
        orderTimeout = 0;
        nextPageClickCount = 0;
        if (hasItemsToSell) {
            currentState = State.SELL_SEND;
        } else if (nextPendingOrder()) {
            currentState = State.ORDER_SEND;
        } else {
            finishOrderQueue();
        }
    }

    private void finishOrderQueue() {
        currentState = State.WAIT_CLEANUP;
    }

    private void nextSpawner() {
        openingAttempts = 0;
        rotationTicks = 0;
        dispenserWaitTick = 0;
        dispenserClicksDone = 0;
        pendingOrders.clear();
        spawnerIndex++;
        if (spawnerIndex < spawners.size()) {
            targetSpawner = spawners.get(spawnerIndex);
            currentState = State.MOVING;
            movingTimeoutCounter = 0;
        } else {
            currentState = State.WAITING_CYCLE;
            cycleTickCounter = 0;
        }
    }

    private void resetToScanning() {
        spawners.clear(); spawnerIndex = 0; targetSpawner = null;
        openingAttempts = 0; rotationTicks = 0; movingTimeoutCounter = 0;
        tickCounter = 0; orderTimer = 0; orderTimeout = 0;
        nextPageClickCount = 0; dispenserWaitTick = 0; dispenserClicksDone = 0;
        globalStateTick = 0; pendingOrders.clear();
        curOrderItem = Items.AIR; curOrderName = "";
        hasItemsToSell = false;
        cleanupWaitTick = 0;
        currentState = State.WAIT_CLEANUP;
        lastState = State.WAIT_CLEANUP;
        globalStateTick = 0;
    }

    private double parsePrice(String text) {
        try {
            text = text.trim();
            Matcher m = Pattern.compile("([0-9]+\\.?[0-9]*)([KkMmBb]?)").matcher(text);
            if (!m.find()) return 0;
            double v = Double.parseDouble(m.group(1));
            return switch (m.group(2).toUpperCase()) {
                case "K" -> v * 1_000;
                case "M" -> v * 1_000_000;
                case "B" -> v * 1_000_000_000;
                default  -> v;
            };
        } catch (Exception e) { return 0; }
    }

    private net.minecraft.component.type.LoreComponent getLore(net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (java.lang.reflect.Method m : stack.getClass().getMethods()) {
            if ((m.getName().equals("get") || m.getName().equals("method_57824")) && m.getParameterCount() == 1) {
                Class<?> paramType = m.getParameterTypes()[0];
                if (paramType.isInstance(DataComponentTypes.LORE)) {
                    try {
                        Object res = m.invoke(stack, DataComponentTypes.LORE);
                        if (res instanceof net.minecraft.component.type.LoreComponent lore) {
                            return lore;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private double getPriceFromSlot(GenericContainerScreen screen, int slotIndex) {
        var handler = screen.getScreenHandler();
        if (slotIndex < 0 || slotIndex >= handler.slots.size()) return 0;
        var stack = handler.slots.get(slotIndex).getStack();
        if (stack.isEmpty()) return 0;
        var lore = getLore(stack);
        if (lore != null)
            for (var line : lore.lines()) {
                String s = line.getString();
                if (s.contains("$")) { double p = parsePrice(s.replace("$","").trim()); if (p>0) return p; }
            }
        String name = stack.getName().getString();
        if (name.contains("$")) { double p = parsePrice(name.replace("$","").trim()); if (p>0) return p; }
        return 0;
    }

    private boolean isGuiDecoration(Item item) {
        if (item == null || item == Items.AIR) return true;
        String name = item.getTranslationKey().toLowerCase();
        return name.contains("glass_pane") || name.contains("barrier") || name.contains("structure_void");
    }

    private boolean isNextPageButton(net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item == Items.ARROW || item == Items.FEATHER || item == Items.PAPER) return true;
        String name = stack.getName().getString().toLowerCase();
        return name.contains("next") || name.contains("sau") || name.contains("trang") || name.contains("»");
    }

    private int getRemainingFromSlot(GenericContainerScreen screen, int slotIndex) {
        var handler = screen.getScreenHandler();
        if (slotIndex < 0 || slotIndex >= handler.slots.size()) return 0;
        var stack = handler.slots.get(slotIndex).getStack();
        if (stack.isEmpty()) return 0;
        var lore = getLore(stack);
        if (lore == null) return Integer.MAX_VALUE;
        for (var line : lore.lines()) {
            String text = line.getString();
            Matcher m = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(text);
            if (m.find()) {
                int done = Integer.parseInt(m.group(1));
                int total = Integer.parseInt(m.group(2));
                return Math.max(0, total - done);
            }
        }
        return Integer.MAX_VALUE;
    }

    private double getPricePerItemFromSlot(GenericContainerScreen screen, int slotIndex) {
        var handler = screen.getScreenHandler();
        if (slotIndex < 0 || slotIndex >= handler.slots.size()) return 0;
        var stack = handler.slots.get(slotIndex).getStack();
        if (stack.isEmpty()) return 0;
        var lore = getLore(stack);
        if (lore == null) return 0;
        for (var line : lore.lines()) {
            String text = line.getString();
            if (text.contains("$") || text.toLowerCase().contains("giá") || text.toLowerCase().contains("price")) {
                Matcher m = Pattern.compile("\\$\\s*([0-9]+\\.?[0-9]*)").matcher(text);
                if (m.find()) return Double.parseDouble(m.group(1));
            }
        }
        return 0;
    }

    private int findValidOrderSlot(GenericContainerScreen screen, Item filterItem) {
        var handler = screen.getScreenHandler();
        int bestSlot = -1;
        double bestPrice = -1;
        for (int i = 0; i <= 44; i++) {
            if (i >= handler.slots.size()) break;
            var stack = handler.slots.get(i).getStack();
            if (stack.isEmpty() || isGuiDecoration(stack.getItem())) continue;

            if (filterItem != Items.AIR && stack.getItem() != filterItem) {
                if (!playerTriggerMode.get()) {
                    continue; // Strict item type matching in normal/fast mode
                }
            }

            // Bỏ qua order còn lại dưới 64 (luôn bật).
            int remaining = getRemainingFromSlot(screen, i);
            if (remaining < 64) continue;

            // Player-trigger mode: không quan tâm giá, lấy slot hợp lệ đầu tiên.
            if (playerTriggerMode.get()) {
                return i;
            }

            // Chọn slot có giá mỗi item cao nhất trong các slot đủ điều kiện.
            double price = getPricePerItemFromSlot(screen, i);
            if (price > bestPrice) {
                bestPrice = price;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean isOrderState() {
        return switch (currentState) {
            case ORDER_SEND, ORDER_WAIT_GUI, ORDER_CAULDRON, ORDER_WAIT_REOPEN,
                 ORDER_FIND_SLOT, ORDER_CLICK_SLOT2, ORDER_FILL,
                 ORDER_WAIT_CONFIRM, ORDER_CONFIRM, ORDER_WAIT_CLOSE, ORDER_CLOSE,
                 SELL_SEND, SELL_WAIT_GUI, SELL_FILL, SELL_CLOSE,
                 FAST_ORDER_WAIT_NEXT_PAGE -> true;
            default -> false;
        };
    }

    @AliceHandler
    public void onTick(TickEvent.Post event) {
        if (isPaused) return;
        if (mc.player == null || mc.world == null) return;

        // Người dùng ấn ESC trong lúc bot đang chạy -> tạm dừng 2 giây.
        boolean escDown = mc.getWindow() != null
            && GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (escDown && !escWasDown) escPauseTick = ESC_PAUSE_TICKS;
        escWasDown = escDown;
        if (escPauseTick > 0) {
            escPauseTick--;
            return; // tạm không làm gì
        }

        if (currentState != lastState) {
            lastState = currentState;
            globalStateTick = 0;
        } else {
            globalStateTick++;
        }

        if (currentState != State.WAITING_CYCLE && currentState != State.WAIT_CLEANUP) {
            if (globalStateTick >= 100) {
                globalStateTick = 0;
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                currentState = State.WAIT_CLEANUP;
                return;
            }
        }

        if (currentState == State.WAITING_CYCLE) {
            if (++cycleTickCounter >= CYCLE_DELAY_TICKS) {
                cycleTickCounter = 0;
                resetToScanning();
            }
            return;
        }

        if (currentState == State.WAIT_CLEANUP) {
            buildPendingOrders();
            if (!pendingOrders.isEmpty() || hasItemsToSell) {
                startOrderQueue();
                return;
            }
            if (hasItemsOnGround()) {
                if (++cleanupWaitTick > 100) {
                    cleanupWaitTick = 0;
                } else {
                    return;
                }
            }
            cleanupWaitTick = 0;
            if (spawners.isEmpty()) {
                currentState = State.SCANNING;
            } else {
                nextSpawner();
            }
            return;
        }

        if (currentState == State.WAIT_DISPENSER_DROP) {
            if (++dispenserWaitTick >= smartDelay()) {
                dispenserWaitTick = 0;
                if (isFastMode()) {
                    currentState = State.FAST_DROP_CLICK;
                } else {
                    dispenserClicksDone++;
                    if (dispenserClicksDone >= dispenserClickCount.get()) {
                        dispenserClicksDone = 0;
                        if (mc.currentScreen != null) mc.player.closeHandledScreen();
                        currentState = State.CLOSING_SPAWNER;
                    } else {
                        currentState = State.CLICK_SLOT52;
                    }
                }
            }
            return;
        }

        if (isOrderState()) {
            if (orderTimer > 0) { orderTimer--; return; }
            if (++orderTimeout >= ORDER_MAX_TIMEOUT) {
                orderTimeout = 0;
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                finishOrderQueue();
                return;
            }
            executeCurrentState();
            return;
        }

        currentActionDelay = ACTION_DELAY_TICKS;

        if (++tickCounter >= currentActionDelay) {
            executeCurrentState();
            tickCounter = 0;
        }
    }

    private void executeCurrentState() {
        switch (currentState) {

            case SCANNING -> {
                spawners.clear();
                BlockPos pp = mc.player.getBlockPos();
                int r = 5;
                for (int x=-r; x<=r; x++)
                    for (int y=-r; y<=r; y++)
                        for (int z=-r; z<=r; z++) {
                            BlockPos pos = pp.add(x,y,z);
                            if (mc.world.getBlockState(pos).getBlock() instanceof SpawnerBlock)
                                spawners.add(pos);
                        }
                if (spawners.isEmpty()) { currentState = State.WAITING_CYCLE; cycleTickCounter = 0; return; }
                spawnerIndex = 0;
                targetSpawner = spawners.get(0);
                currentState = State.MOVING;
                movingTimeoutCounter = 0;
            }

            case MOVING -> {
                if (targetSpawner == null) { currentState = State.SCANNING; return; }
                if (new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(Vec3d.ofCenter(targetSpawner)) <= 4.0) {
                    savedYaw = mc.player.getYaw(); savedPitch = mc.player.getPitch();
                    movingTimeoutCounter = 0; rotationTicks = 0;
                    currentSilentYaw = savedYaw; currentSilentPitch = savedPitch;
                    currentState = State.SILENT_ROTATE;
                } else {
                    if (++movingTimeoutCounter >= MOVING_TIMEOUT) { movingTimeoutCounter = 0; nextSpawner(); }
                }
            }

            case SILENT_ROTATE -> { rotationTicks = 0; currentState = State.SILENT_ROTATE_SMOOTH; }

            case SILENT_ROTATE_SMOOTH -> {
                if (targetSpawner == null) { currentState = State.SCANNING; return; }
                float ty = calcYawTo(targetSpawner), tp = calcPitchTo(targetSpawner);
                if (++rotationTicks >= ROTATION_TICKS) {
                    sendSilentRotation(ty, tp);
                    mc.player.setYaw(ty); mc.player.setPitch(tp);
                    openingAttempts = 0; currentState = State.OPENING;
                } else {
                    float p = (float) rotationTicks / ROTATION_TICKS;
                    currentSilentYaw   += (ty - currentSilentYaw)   * p;
                    currentSilentPitch += (tp - currentSilentPitch)  * p;
                    sendSilentRotation(currentSilentYaw, currentSilentPitch);
                    mc.player.setYaw(currentSilentYaw); mc.player.setPitch(currentSilentPitch);
                }
            }

            case OPENING -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    openingAttempts = 0;
                    if (isFastMode()) {
                        String title = screen.getTitle().getString().trim();
                        if (title.matches("\\d+\\s+.*") || screen.getScreenHandler().slots.size() >= 54) {
                            dispenserClicksDone = 0;
                            currentState = State.FAST_DROP_CLICK;
                        }
                    } else {
                        currentState = State.WAIT_CHEST_GUI;
                    }
                    return;
                }
                if (openingAttempts % 20 == 0 && targetSpawner != null) interactWithBlock(targetSpawner);
                if (++openingAttempts >= OPEN_TIMEOUT_TICKS) nextSpawner();
            }

            case WAIT_CHEST_GUI -> {
                currentState = State.CLICK_BUNDLE;
            }

            case CLICK_BUNDLE -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.CLOSING_SPAWNER; return;
                }
                ScreenHandler handler = screen.getScreenHandler();
                if (RNG.nextInt(100) < 5) {
                    for (Slot slot : handler.slots) {
                        if (slot.inventory == mc.player.getInventory()) continue;
                        if (slot.getStack().getItem() == Items.EXPERIENCE_BOTTLE) {
                            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                            break;
                        }
                    }
                } else {
                    for (Slot slot : handler.slots) {
                        if (slot.inventory == mc.player.getInventory()) continue;
                        if (slot.getStack().getItem() == Items.BUNDLE) {
                            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                            break;
                        }
                    }
                }
                currentState = State.CLICK_DISPENSER;
            }

            case CLICK_DISPENSER -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.CLOSING_SPAWNER; return;
                }
                ScreenHandler handler = screen.getScreenHandler();
                for (Slot slot : handler.slots) {
                    if (slot.inventory == mc.player.getInventory()) continue;
                    if (slot.getStack().getItem() == Items.DISPENSER) {
                        mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                        break;
                    }
                }
                dispenserWaitTick = 0;
                currentState = State.WAIT_DISPENSER_DROP;
            }

            case CLICK_SLOT52 -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.CLOSING_SPAWNER; return;
                }
                var handler = screen.getScreenHandler();
                if (handler.slots.size() > 52)
                    mc.interactionManager.clickSlot(handler.syncId, 52, 0, SlotActionType.PICKUP, mc.player);
                currentState = State.CLICK_DISPENSER;
            }

            case FAST_DROP_CLICK -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.WAIT_CLEANUP;
                    return;
                }
                var handler = screen.getScreenHandler();
                int maxClicks = dispenserClickCount.get();
                if (maxClicks == 1) {
                    if (handler.slots.size() > 52) mc.interactionManager.clickSlot(handler.syncId, 52, 0, SlotActionType.PICKUP, mc.player);
                    currentState = State.CLOSING_SPAWNER;
                } else {
                    int targetClicks = maxClicks * 2 - 1;
                    int slotToClick = (dispenserClicksDone % 2 == 0) ? 52 : 53;
                    if (handler.slots.size() > slotToClick) {
                        mc.interactionManager.clickSlot(handler.syncId, slotToClick, 0, SlotActionType.PICKUP, mc.player);
                    }
                    dispenserClicksDone++;
                    if (dispenserClicksDone >= targetClicks) {
                        currentState = State.CLOSING_SPAWNER;
                    } else {
                        dispenserWaitTick = 0;
                        currentState = State.WAIT_DISPENSER_DROP;
                    }
                }
            }

            case CLOSING_SPAWNER -> {
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                tickCounter = 0;
                currentState = State.WAIT_CLEANUP;
            }

            case SELL_SEND -> {
                mc.player.networkHandler.sendChatCommand("sell");
                currentState = State.SELL_WAIT_GUI;
                orderTimer = smartDelay() * 3; orderTimeout = 0;
            }

            case SELL_WAIT_GUI -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    currentState = State.SELL_FILL;
                    orderTimer = smartDelay(); orderTimeout = 0;
                }
            }

            case SELL_FILL -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    var handler = screen.getScreenHandler();
                    boolean filled = false;
                    for (int i = 0; i < handler.slots.size(); i++) {
                        var slot = handler.slots.get(i);
                        if (slot.inventory != mc.player.getInventory() || slot.getStack().isEmpty()) continue;
                        Item item = slot.getStack().getItem();
                        if (!ORDER_ITEMS.containsKey(item) && item.getMaxCount() > 1) {
                            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                            filled = true;
                        }
                    }
                    if (filled) {
                        orderTimer = smartDelay() * 2; orderTimeout = 0;
                        currentState = State.SELL_FILL;
                    } else {
                        mc.player.closeHandledScreen();
                        currentState = State.SELL_CLOSE;
                        orderTimer = smartDelay() * 3; orderTimeout = 0;
                    }
                } else {
                    currentState = State.SELL_CLOSE;
                }
            }

            case SELL_CLOSE -> {
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                orderTimer = smartDelay() * 2; orderTimeout = 0;
                hasItemsToSell = false;
                if (nextPendingOrder()) {
                    currentState = State.ORDER_SEND;
                } else {
                    finishOrderQueue();
                }
            }

            case ORDER_SEND -> {
                if (!hasItem(curOrderItem)) {
                    orderTimer = isFastMode() ? smartDelay() : (smartDelay() * 3); orderTimeout = 0;
                    if (nextPendingOrder()) { currentState = State.ORDER_SEND; }
                    else { finishOrderQueue(); }
                    return;
                }
                if (playerTriggerMode.get()) {
                    String pName = triggerPlayerName.get().trim();
                    if (pName.isEmpty()) {
                        finishOrderQueue();
                        return;
                    }
                    mc.player.networkHandler.sendChatCommand("order " + pName);
                } else {
                    mc.player.networkHandler.sendChatCommand("order " + curOrderName);
                }
                currentState = State.ORDER_WAIT_GUI;
                orderTimer = isFastMode() ? smartDelay() : (smartDelay() * 3); orderTimeout = 0;
            }

            case ORDER_WAIT_GUI -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    if (isFastMode()) {
                        lastGuiTitle = screen.getTitle().getString().replaceAll("§.", "");
                        currentState = State.ORDER_FIND_SLOT;
                        orderTimer = smartDelay(); orderTimeout = 0;
                    } else {
                        currentState = State.ORDER_CAULDRON;
                        orderTimer = smartDelay(); orderTimeout = 0;
                    }
                }
            }

            case ORDER_CAULDRON -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.ORDER_SEND; return;
                }
                var handler = screen.getScreenHandler();
                for (int i = 0; i < handler.slots.size(); i++) {
                    if (handler.slots.get(i).getStack().getItem() == Items.CAULDRON) {
                        mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                        mc.player.closeHandledScreen();
                        currentState = State.ORDER_WAIT_REOPEN;
                        orderTimer = smartDelay() * 3; orderTimeout = 0; return;
                    }
                }
                currentState = State.ORDER_FIND_SLOT; orderTimer = smartDelay();
            }

            case ORDER_WAIT_REOPEN -> {
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    currentState = State.ORDER_FIND_SLOT;
                    orderTimer = smartDelay(); orderTimeout = 0; nextPageClickCount = 0;
                }
            }

            case ORDER_FIND_SLOT -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.ORDER_SEND; return;
                }
                var handler = screen.getScreenHandler();

                if (isFastMode()) {
                    int slot = findValidOrderSlot(screen, curOrderItem);
                    if (slot != -1) {
                        nextPageClickCount = 0;
                        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                        currentState = State.ORDER_FILL;
                        orderTimer = smartDelay(); orderTimeout = 0;
                    } else {
                        if (handler.slots.size() > 53 && isNextPageButton(handler.slots.get(53).getStack())) {
                            if (++nextPageClickCount > 10) {
                                nextPageClickCount = 0;
                                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                                orderTimer = smartDelay() * 3; orderTimeout = 0;
                                if (nextPendingOrder()) { currentState = State.ORDER_SEND; orderTimeout = 0; }
                                else { finishOrderQueue(); }
                                return;
                            }
                            mc.interactionManager.clickSlot(handler.syncId, 53, 0, SlotActionType.PICKUP, mc.player);
                            lastGuiTitle = screen.getTitle().getString().replaceAll("§.", "");
                            currentState = State.FAST_ORDER_WAIT_NEXT_PAGE;
                            orderTimer = smartDelay();
                        } else {
                            if (mc.currentScreen != null) mc.player.closeHandledScreen();
                            orderTimer = smartDelay() * 3; orderTimeout = 0;
                            if (nextPendingOrder()) { currentState = State.ORDER_SEND; orderTimeout = 0; }
                            else { finishOrderQueue(); }
                        }
                    }
                    return;
                }

                int slot = findValidOrderSlot(screen, curOrderItem);
                if (slot != -1) {
                    nextPageClickCount = 0;
                    mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                    currentState = State.ORDER_CLICK_SLOT2;
                    orderTimer = smartDelay(); orderTimeout = 0;
                } else {
                    if (handler.slots.size() > 53 && isNextPageButton(handler.slots.get(53).getStack())) {
                        if (++nextPageClickCount > 10) {
                            nextPageClickCount = 0;
                            if (mc.currentScreen != null) mc.player.closeHandledScreen();
                            orderTimer = smartDelay() * 3; orderTimeout = 0;
                            if (nextPendingOrder()) { currentState = State.ORDER_SEND; orderTimeout = 0; }
                            else { finishOrderQueue(); }
                            return;
                        }
                        mc.interactionManager.clickSlot(handler.syncId, 53, 0, SlotActionType.PICKUP, mc.player);
                        orderTimer = smartDelay() * 6;
                    } else {
                        if (mc.currentScreen != null) mc.player.closeHandledScreen();
                        orderTimer = smartDelay() * 3; orderTimeout = 0;
                        if (nextPendingOrder()) { currentState = State.ORDER_SEND; orderTimeout = 0; }
                        else { finishOrderQueue(); }
                    }
                }
            }

            case ORDER_CLICK_SLOT2 -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.ORDER_SEND; return;
                }
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 1, 0, SlotActionType.PICKUP, mc.player);
                currentState = State.ORDER_FILL; orderTimer = smartDelay(); orderTimeout = 0;
            }

            case FAST_ORDER_WAIT_NEXT_PAGE -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.ORDER_SEND; return;
                }
                String newTitle = screen.getTitle().getString().replaceAll("§.", "");
                if (!newTitle.equals(lastGuiTitle)) {
                    lastGuiTitle = newTitle;
                    currentState = State.ORDER_FIND_SLOT;
                    orderTimer = 1; orderTimeout = 0;
                } else {
                    orderTimer = 1;
                }
            }

            case ORDER_FILL -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.ORDER_SEND; return;
                }
                String guiTitle = screen.getTitle().getString().replaceAll("§.", "");
                if (!guiTitle.contains("ʙỏ ᴠậᴛ ᴘʜẩᴍ ᴠàᴏ để ɢɪᴀᴏ")) {
                    mc.player.closeHandledScreen();
                    orderTimer = smartDelay() * 3; orderTimeout = 0;
                    if (nextPendingOrder()) { currentState = State.ORDER_SEND; }
                    else { finishOrderQueue(); }
                    return;
                }
                var handler = screen.getScreenHandler();
                boolean guiHasEmpty = false;
                for (int i = 0; i < 54 && i < handler.slots.size(); i++) {
                    if (handler.slots.get(i).inventory == mc.player.getInventory()) continue;
                    if (handler.slots.get(i).getStack().isEmpty()) { guiHasEmpty = true; break; }
                }
                if (!guiHasEmpty) {
                    mc.player.closeHandledScreen();
                    currentState = State.ORDER_WAIT_CONFIRM;
                    orderTimer = smartDelay() * 5; orderTimeout = 0;
                    return;
                }
                boolean filled = false;
                for (int i = 0; i < handler.slots.size(); i++) {
                    var slot = handler.slots.get(i);
                    if (slot.inventory != mc.player.getInventory() || slot.getStack().isEmpty()) continue;
                    if (curOrderItem != Items.AIR && slot.getStack().getItem() != curOrderItem) continue;
                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                    filled = true;
                }
                if (filled) {
                    orderTimer = smartDelay() * 2; orderTimeout = 0;
                    currentState = State.ORDER_FILL;
                } else {
                    mc.player.closeHandledScreen();
                    currentState = State.ORDER_WAIT_CONFIRM;
                    orderTimer = smartDelay() * 5; orderTimeout = 0;
                }
            }

            case ORDER_WAIT_CONFIRM -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    var handler = screen.getScreenHandler();
                    if (isFastMode()) {
                        currentState = State.ORDER_CONFIRM; orderTimer = smartDelay(); orderTimeout = 0;
                        return;
                    } else {
                        for (int i = 0; i < handler.slots.size(); i++) {
                            if (handler.slots.get(i).getStack().getItem() == Items.LIME_STAINED_GLASS_PANE) {
                                currentState = State.ORDER_CONFIRM; orderTimer = smartDelay(); orderTimeout = 0;
                                return;
                            }
                        }
                    }
                }
            }

            case ORDER_CONFIRM -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
                    currentState = State.ORDER_CLOSE; return;
                }
                var handler = screen.getScreenHandler();
                if (isFastMode()) {
                    if (handler.slots.size() > 16) {
                        mc.interactionManager.clickSlot(handler.syncId, 16, 0, SlotActionType.PICKUP, mc.player);
                        totalOrders++;
                        orderTimer = smartDelay(); orderTimeout = 0;
                        currentState = State.ORDER_WAIT_CLOSE; return;
                    }
                } else {
                    for (int i = 0; i < handler.slots.size(); i++) {
                        var item = handler.slots.get(i).getStack().getItem();
                        if (item == Items.LIME_STAINED_GLASS_PANE || item == Items.GREEN_STAINED_GLASS_PANE) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                            totalOrders++;
                            double earned = getPriceFromSlot(screen, i);
                            if (earned > 0) totalEarned += earned;
                            orderTimer = smartDelay() * 10; orderTimeout = 0;
                            currentState = State.ORDER_WAIT_CLOSE; return;
                        }
                    }
                }
                currentState = State.ORDER_CLOSE;
            }

            case ORDER_WAIT_CLOSE -> {
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    if (hasItem(curOrderItem)) {
                        if (isFastMode()) {
                            String title = screen.getTitle().getString().replaceAll("§.", "");
                            if (title.contains("ʙỏ ᴠậᴛ ᴘʜẩᴍ")) {
                                currentState = State.ORDER_FILL; orderTimer = smartDelay(); orderTimeout = 0;
                            } else if (title.contains("(") && title.contains("/")) {
                                currentState = State.ORDER_FIND_SLOT; orderTimer = smartDelay(); orderTimeout = 0;
                            } else {
                                orderTimer = smartDelay();
                            }
                        } else {
                            currentState = State.ORDER_FILL; orderTimer = smartDelay(); orderTimeout = 0;
                        }
                    } else {
                        mc.player.closeHandledScreen();
                        currentState = State.ORDER_CLOSE; orderTimer = isFastMode() ? smartDelay() : (smartDelay() * 2);
                    }
                } else {
                    if (hasItem(curOrderItem) && isFastMode()) {
                        currentState = State.ORDER_SEND; orderTimer = smartDelay();
                    } else {
                        orderTimer = smartDelay();
                    }
                }
            }

            case ORDER_CLOSE -> {
                if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
                orderTimer = isFastMode() ? smartDelay() : (smartDelay() * 3); orderTimeout = 0;
                buildPendingOrders();
                if (nextPendingOrder()) {
                    currentState = State.ORDER_SEND;
                } else {
                    finishOrderQueue();
                }
            }
        }
    }

    public static SpawnerOrder getActiveInstance() { return activeInstance; }

    @Override
    public String getInfoString() {
        if (!isActive()) return null;
        if (currentState == State.WAITING_CYCLE)
            return "WAITING | " + (CYCLE_DELAY_TICKS - cycleTickCounter) + "t";
        if (currentState == State.WAIT_DISPENSER_DROP)
            return "DROP | " + dispenserWaitTick + "/" + smartDelay() + "t";
        String item = !curOrderName.isEmpty() ? curOrderName + " | " : "";
        return item + currentState.name().replace("_"," ") + " | " + getPing() + "ms";
    }

    private static final Color HUD_TITLE = new Color(120, 220, 255, 255);
    private static final Color HUD_TEXT  = new Color(255, 255, 255, 255);
    private static final Color HUD_MONEY = new Color(120, 255, 120, 255);

    private String formatMoney(double v) {
        if (v >= 1_000_000_000) return String.format("%.2fB", v / 1_000_000_000);
        if (v >= 1_000_000)     return String.format("%.2fM", v / 1_000_000);
        if (v >= 1_000)         return String.format("%.2fK", v / 1_000);
        return String.format("%.0f", v);
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, sec);
        if (m > 0) return String.format("%dm %ds", m, sec);
        return sec + "s";
    }

    @AliceHandler
    public void onRender2D(Render2DEvent event) {
        if (!isActive() || !showHud.get()) return;
        if (mc.player == null) return;

        TextRenderer text = TextRenderer.get();
        if (text == null) return;

        long elapsed = sessionStartTime > 0 ? System.currentTimeMillis() - sessionStartTime : 0;
        double perHour = elapsed > 0 ? totalEarned / (elapsed / 3_600_000.0) : 0;

        var spModule = meteordevelopment.meteorclient.systems.modules.Modules.get().get(SpawnerProtect.class);
        boolean spActive = spModule.isActive();
        String spInfo = spModule.getInfoString();
        String spStateStr = spInfo != null ? " (" + spInfo + ")" : "";

        var arModule = meteordevelopment.meteorclient.systems.modules.Modules.get().get(AliceAutoReconnect.class);
        boolean arActive = arModule.isActive();
        String arInfo = arModule.getInfoString();
        String arStateStr = arInfo != null ? " (" + arInfo + ")" : "";

        String[] labels = {
            "Spawner-Order",
            "Earned: $" + formatMoney(totalEarned),
            "Orders: " + totalOrders,
            "$/h: " + formatMoney(perHour),
            "Time: " + formatDuration(elapsed),
            "Protect: " + (spActive ? "ON" + spStateStr : "OFF"),
            "AutoRe: " + (arActive ? "ON" + arStateStr : "OFF")
        };
        Color cOn = new Color(85, 255, 85, 255);
        Color cOff = new Color(255, 85, 85, 255);
        Color[] colors = { HUD_TITLE, HUD_MONEY, HUD_TEXT, HUD_MONEY, HUD_TEXT, spActive ? cOn : cOff, arActive ? cOn : cOff };

        double maxW = 0;
        for (String l : labels) maxW = Math.max(maxW, text.getWidth(l, false));
        double lineH = text.getHeight(false);

        double pad = 4, lineGap = 2;
        double bgW = maxW + pad * 2;
        double bgH = labels.length * lineH + (labels.length - 1) * lineGap + pad * 2;

        double screenW = mc.getWindow().getScaledWidth();
        double screenH = mc.getWindow().getScaledHeight();

        double x = (screenW / 2.0) - bgW - 10;
        double y = (screenH / 2.0) - bgH - 10;

        Renderer2D.COLOR.begin();
        // Modern dark background
        Renderer2D.COLOR.quad(x, y, bgW, bgH, new Color(20, 20, 20, 180));
        // Sleek accent line at the top
        Renderer2D.COLOR.quad(x, y, bgW, 2, new Color(85, 255, 170, 255));
        invokeRender(event);

        text.begin(1.0, false, true);
        double ty = y + pad + 3; // Shift down slightly for accent line
        for (int i = 0; i < labels.length; i++) {
            // Render text with shadow (true) for better readability and a more professional look
            text.render(labels[i], x + pad, ty, colors[i], true);
            ty += lineH + lineGap;
        }
        text.end();
    }

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
    }

    private static java.lang.reflect.Method cachedRenderMethod = null;
    private static boolean renderMethodInit = false;

    private void invokeRender(Render2DEvent event) {
        if (!renderMethodInit) {
            renderMethodInit = true;
            for (java.lang.reflect.Method m : Renderer2D.COLOR.getClass().getMethods()) {
                if (m.getName().equals("render")) {
                    if (m.getParameterCount() == 1 || m.getParameterCount() == 0) {
                        cachedRenderMethod = m;
                        break;
                    }
                }
            }
        }
        if (cachedRenderMethod != null) {
            try {
                if (cachedRenderMethod.getParameterCount() == 1) {
                    Class<?> pt = cachedRenderMethod.getParameterTypes()[0];
                    if (pt.getName().contains("DrawContext")) {
                        cachedRenderMethod.invoke(Renderer2D.COLOR, event.drawContext);
                    } else {
                        cachedRenderMethod.invoke(Renderer2D.COLOR, event.drawContext.getMatrices());
                    }
                } else {
                    cachedRenderMethod.invoke(Renderer2D.COLOR);
                }
            } catch (Exception ignored) {}
        }
    }
}

