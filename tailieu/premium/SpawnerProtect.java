package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import com.nnpg.alice.premium.AliceAutoReconnect;
import com.nnpg.alice.premium.SpawnerOrder;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.InvUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

public class SpawnerProtect extends AliceModule {
    private static void dummyLog(Object... args) {}
    public static java.lang.invoke.MethodHandles.Lookup getLookup() {
        return java.lang.invoke.MethodHandles.lookup();
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    private final Setting<Boolean> webhook = sgWebhook.add(new BoolSetting.Builder()
            .name("webhook")
            .description("bat thong bao qua webhook")
            .defaultValue(false)
            .build());

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
            .name("webhook-url")
            .description("dia chi webhook discord de gui thong bao")
            .defaultValue("")
            .visible(webhook::get)
            .build());

    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
            .name("self-ping")
            .description("tag ten ban vao tin nhan webhook")
            .defaultValue(false)
            .visible(webhook::get)
            .build());

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
            .name("discord-id")
            .description("ID nguoi dung discord cua ban de ping")
            .defaultValue("")
            .visible(() -> webhook.get() && selfPing.get())
            .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
            .name("notifications")
            .description("hien thi tin nhan phan hoi")
            .defaultValue(true)
            .build());

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-range")
            .description("tam quet cac spawner con lai")
            .defaultValue(16)
            .min(1)
            .max(50)
            .sliderMax(50)
            .build());

    private final Setting<Integer> emergencyDistance = sgGeneral.add(new IntSetting.Builder()
            .name("emergency-distance")
            .description("khoang cach (block) de kich hoat tu dong thoat (0 de tat)")
            .defaultValue(7)
            .min(0)
            .max(20)
            .sliderMax(20)
            .build());

    private final Setting<Integer> minDetectionRange = sgGeneral.add(new IntSetting.Builder()
            .name("min-detection-range")
            .description("khoang cach toi thieu de phat hien nguoi choi")
            .defaultValue(0)
            .min(0)
            .max(50)
            .sliderMax(50)
            .build());

    private final Setting<Integer> maxDetectionRange = sgGeneral.add(new IntSetting.Builder()
            .name("max-detection-range")
            .description("khoang cach toi da de phat hien nguoi choi")
            .defaultValue(50)
            .min(1)
            .max(100)
            .sliderMax(100)
            .build());

    private final Setting<Integer> spawnerCheckDelay = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-check-delay-ms")
            .description("thoi gian tre (ms) truoc khi xac nhan da het spawner")
            .defaultValue(3000)
            .min(1000)
            .max(10000)
            .sliderMax(10000)
            .build());

    private final Setting<Integer> spawnerTimeout = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-timeout-ms")
            .description("thoi gian (ms) truoc khi bo qua spawner khong the dao duoc")
            .defaultValue(4000)
            .min(4000)
            .max(30000)
            .sliderMax(30000)
            .build());

    private final Setting<Boolean> depositToEChest = sgGeneral.add(new BoolSetting.Builder()
            .name("deposit-to-echest")
            .description("cat spawner vao ender chest sau khi dao")
            .defaultValue(true)
            .build());

    private final Setting<Integer> maxSellRetries = sgGeneral.add(new IntSetting.Builder()
            .name("sell-retries")
            .description("so lan toi da thu ban do rac truoc khi bo qua")
            .defaultValue(2)
            .min(1)
            .max(10)
            .sliderMax(5)
            .build());

    private final Setting<Boolean> enableWhitelist = sgWhitelist.add(new BoolSetting.Builder()
            .name("enable-whitelist")
            .description("bat danh sach trang (nguoi choi trong danh sach se khong kich hoat bao ve)")
            .defaultValue(false)
            .build());

    private final Setting<List<String>> whitelistPlayers = sgWhitelist.add(new StringListSetting.Builder()
            .name("whitelisted-players")
            .description("danh sach nguoi choi de bo qua")
            .defaultValue(new ArrayList<>())
            .visible(enableWhitelist::get)
            .build());

    private enum State {
        IDLE,
        DISABLE_MODULES,
        SELLING_JUNK,
        WAITING_SELL_GUI,
        DEPOSITING_SELL_ITEMS,
        CHECK_PICKAXE,
        GETTING_PICKAXE,
        OPENING_ECHEST_PICKUP,
        TAKING_PICKAXE,
        WAITING_PICKAXE_TRANSFER,
        GOING_TO_SPAWNERS,
        PICKING_UP_ITEMS,
        GOING_TO_CHEST,
        OPENING_CHEST,
        DEPOSITING_ITEMS,
        DISCONNECTING,
        WORLD_CHANGED_ONCE,
        WORLD_CHANGED_TWICE
    }

    private State currentState = State.IDLE;
    private String detectedPlayer = "";
    private long detectionTime = 0;
    private boolean spawnersMinedSuccessfully = false;
    private boolean itemsDepositedSuccessfully = false;
    private int tickCounter = 0;
    private int transferDelayCounter = 0;
    private int lastProcessedSlot = -1;

    private BlockPos currentTarget = null;
    private long noSpawnerStartTime = -1;
    private long currentTargetStartTime = -1;
    private long currentMiningStartTime = -1;

    private BlockPos targetChest = null;
    private int chestOpenAttempts = 0;
    private boolean emergencyDisconnect = false;
    private String emergencyReason = "";

    private World trackedWorld = null;
    private int worldChangeCount = 0;
    // If there are this many or more other players online, do not activate
    // protection
    private final int PLAYER_COUNT_THRESHOLD = 3;
    private float targetYaw, targetPitch;
    private boolean rotating = false;
    private final float ROTATION_SPEED = 8.0f;
    private long respawnWaitStart = -1;
    private final Set<BlockPos> invalidSpawners = new HashSet<>();
    private long pickupStartTime = -1;
    private volatile boolean serverConfirmedTargetMined = false;
    private BlockPos silentMiningTarget = null;
    private Direction silentMiningSide = null;
    private float currentSilentMiningYaw = 0f;
    private float currentSilentMiningPitch = 0f;
    private boolean silentMiningRotationReady = false;
    private boolean silentMiningRotationActive = false;
    private int pickaxeTransferBaseRevision = -1;
    private int pickaxeTransferHotbarSlot = -1;
    private int pickaxeTransferExpectedStrength = -1;
    private int pickaxeTransferTicks = 0;
    private int sellRetryCount = 0;
    private int echestPickupAttempts = 0;
    private State savedStateBeforeSell = null;
    private boolean sellExhausted = false;
    private long lastDebugLogTime = 0;
    private long lastTickCrashLogTime = 0;

    public SpawnerProtect() {
        super(AliceAddon.CATEGORY, "spawner-protect",
                "Breaks spawners and puts them in your inv when a player is detected");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        resetState();
        configureLegitMining();

        if (mc.world != null) {
            trackedWorld = mc.world;
            worldChangeCount = 0;
            if (notifications.get())
                info("SpawnerProtect activated - Monitoring world: " + mc.world.getRegistryKey().getValue());
            if (notifications.get())
                info("Monitoring for players...");
        }

        if (notifications.get())
            dummyLog(
                    "Make sure to have an empty inventory with only a pickaxe and an ender chest nearby!");
    }

    private void resetState() {
        currentState = State.IDLE;
        detectedPlayer = "";
        detectionTime = 0;
        spawnersMinedSuccessfully = false;
        itemsDepositedSuccessfully = false;
        tickCounter = 0;
        transferDelayCounter = 0;
        lastProcessedSlot = -1;
        currentTarget = null;
        noSpawnerStartTime = -1;
        currentTargetStartTime = -1;
        currentMiningStartTime = -1;
        targetChest = null;
        chestOpenAttempts = 0;
        emergencyDisconnect = false;
        emergencyReason = "";
        invalidSpawners.clear();
        rotating = false;
        pickupStartTime = -1;
        serverConfirmedTargetMined = false;
        silentMiningTarget = null;
        silentMiningSide = null;
        silentMiningRotationReady = false;
        silentMiningRotationActive = false;
        pickaxeTransferBaseRevision = -1;
        pickaxeTransferHotbarSlot = -1;
        pickaxeTransferExpectedStrength = -1;
        pickaxeTransferTicks = 0;
        sellRetryCount = 0;
        echestPickupAttempts = 0;
        savedStateBeforeSell = null;
        sellExhausted = false;
        webhookSent = false;
    }

    private void configureLegitMining() {
        if (notifications.get())
            info("Manual mining mode activated");
    }

    private void disableAllAutoReconnect() {
        Module autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            if (notifications.get())
                info("Meteor AutoReconnect disabled");
        }
        try {
            Module aliceReconnect = Modules.get().get(AliceAutoReconnect.class);
            if (aliceReconnect != null && aliceReconnect.isActive()) {
                aliceReconnect.toggle();
                if (notifications.get())
                    info("AliceAutoReconnect disabled");
            }
        } catch (Exception ignored) {}
    }

    @AliceHandler(priority = -100)
    public void onTick(TickEvent.Pre event) {
        try {
            tickLogic(event);
        } catch (Throwable t) {
            // Surface the real crash to chat (rate-limited to once/second) so we can diagnose.
            long now = System.currentTimeMillis();
            if (now - lastTickCrashLogTime > 1000) {
                lastTickCrashLogTime = now;
                StackTraceElement top = t.getStackTrace().length > 0 ? t.getStackTrace()[0] : null;
                String where = top != null ? (top.getClassName() + "." + top.getMethodName() + ":" + top.getLineNumber()) : "unknown";
                dummyLog("[SpawnerProtect] CRASH in tick: " + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " - " + t.getMessage() : "") + " @ " + where);
            }
            System.err.println("[SpawnerProtect] Exception in onTick:");
            t.printStackTrace();
        }
    }

    private void tickLogic(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null)
            return;
        // Spawner mining uses an outgoing-only rotation. Never let an old
        // chest/item look rotation move the real camera during this state.
        if (currentState == State.GOING_TO_SPAWNERS) {
            rotating = false;
        }
        if (rotating) {
            float yaw = mc.player.getYaw();
            float pitch = mc.player.getPitch();

            float yawDiff = wrapDegrees(targetYaw - yaw);
            float pitchDiff = targetPitch - pitch;

            float newYaw = yaw + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED);
            float newPitch = pitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);

            mc.player.setYaw(newYaw);
            mc.player.setPitch(newPitch);

            if (Math.abs(yawDiff) < 1f && Math.abs(pitchDiff) < 1f) {
                rotating = false;
            }
        }

        if (currentState == State.GOING_TO_SPAWNERS) {
            mc.options.sneakKey.setPressed(true);
        }

        tickCounter++;

        if (trackedWorld == null) {
            trackedWorld = mc.world;
            worldChangeCount = 0;
            currentState = State.IDLE;
            if (notifications.get()) {
                info("SpawnerProtect world initialized - monitoring resumed");
            }
        } else if (mc.world != trackedWorld) {
            handleWorldChange();
            return;
        }

        if (currentState == State.WORLD_CHANGED_ONCE) {
            return;
        }

        if (currentState == State.WORLD_CHANGED_TWICE) {
            currentState = State.IDLE;
            if (notifications.get())
                info("Returned to spawner world - resuming player monitoring");
        }

        // Skip ALL player detection while AliceAutoReconnect is handling login/menu
        if (currentState == State.IDLE && isLoginFlowActive()) {
            debug("IDLE skipped: AliceAutoReconnect login flow active");
            return;
        }

        if (!isLoginFlowActive() && checkEmergencyDisconnect()) {
            return;
        }

        if (transferDelayCounter > 0) {
            transferDelayCounter--;
            return;
        }

        switch (currentState) {
            case IDLE:
                checkForPlayers();
                break;
            case DISABLE_MODULES:
                handleDisableModules();
                break;
            case SELLING_JUNK:
                handleSellingJunk();
                break;
            case WAITING_SELL_GUI:
                handleWaitingSellGui();
                break;
            case DEPOSITING_SELL_ITEMS:
                handleDepositingSellItems();
                break;
            case CHECK_PICKAXE:
                handleCheckPickaxe();
                break;
            case GETTING_PICKAXE:
                handleGettingPickaxe();
                break;
            case OPENING_ECHEST_PICKUP:
                handleOpeningEchestPickup();
                break;
            case TAKING_PICKAXE:
                handleTakingPickaxe();
                break;
            case WAITING_PICKAXE_TRANSFER:
                handleWaitingPickaxeTransfer();
                break;
            case GOING_TO_SPAWNERS:
                handleGoingToSpawners();
                break;
            case PICKING_UP_ITEMS:
                handlePickingUpItems();
                break;
            case GOING_TO_CHEST:
                handleGoingToChest();
                break;
            case OPENING_CHEST:
                handleOpeningChest();
                break;
            case DEPOSITING_ITEMS:
                handleDepositingItems();
                break;
            case DISCONNECTING:
                handleDisconnecting();
                break;
            case WORLD_CHANGED_ONCE:
            case WORLD_CHANGED_TWICE:
                break;
        }

        // ── Global junk interrupt ─────────────────────────────────────────
        // After executing the current state, check for junk items.
        // If found and not already selling, interrupt and sell first.
        if (currentState != State.IDLE
                && currentState != State.SELLING_JUNK
                && currentState != State.WAITING_SELL_GUI
                && currentState != State.DEPOSITING_SELL_ITEMS
                && currentState != State.DISCONNECTING
                && currentState != State.WORLD_CHANGED_ONCE
                && currentState != State.WORLD_CHANGED_TWICE
                && currentState != State.DISABLE_MODULES
                && !sellExhausted
                && hasJunkItems()) {
            // Save current state so we can resume after selling
            savedStateBeforeSell = currentState;
            currentState = State.SELLING_JUNK;
            // Stop any movement/mining
            stopBreaking();
            mc.options.sneakKey.setPressed(false);
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
            if (notifications.get())
                info("Junk detected in inventory! Pausing to sell... (attempt " + (sellRetryCount + 1) + "/" + maxSellRetries.get() + ")");
        }
    }

    @AliceHandler
    public void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof BlockUpdateS2CPacket update) {
            observeServerSpawnerUpdate(update.getPos(), update.getState());
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket update) {
            update.visitUpdates(this::observeServerSpawnerUpdate);
        }
    }

    private void observeServerSpawnerUpdate(BlockPos pos, net.minecraft.block.BlockState state) {
        BlockPos target = currentTarget;
        if (target == null || pos == null || !target.equals(pos)) return;

        // PacketEvent.Receive is authoritative server state. Client-side block
        // prediction never reaches this path.
        serverConfirmedTargetMined = state.getBlock() != Blocks.SPAWNER;
    }

    private void handleWorldChange() {
        stopBreaking();
        mc.options.sneakKey.setPressed(false);
        worldChangeCount++;
        trackedWorld = mc.world;

        if (worldChangeCount == 1) {
            currentState = State.WORLD_CHANGED_ONCE;
            if (notifications.get())
                info("World changed (TP to spawn) - pausing player detection until return");
        } else if (worldChangeCount == 2) {
            currentState = State.WORLD_CHANGED_TWICE;
            worldChangeCount = 0;
            if (notifications.get())
                info("World changed (back to spawners) - will resume monitoring");
        }
    }

    private float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f)
            value -= 360.0f;
        if (value < -180.0f)
            value += 360.0f;
        return value;
    }

    private boolean checkEmergencyDisconnect() {
        if (emergencyDistance.get() <= 0)
            return false;

        long otherPlayers = mc.world.getPlayers().stream().filter(p -> p != mc.player).count();
        if (otherPlayers >= PLAYER_COUNT_THRESHOLD)
            return false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player == null || !(player instanceof AbstractClientPlayerEntity))
                continue;

            String playerName = player.getName().getString();

            if (isPlayerWhitelisted(playerName)) {
                continue;
            }

            double distance = mc.player.distanceTo(player);
            if (distance <= emergencyDistance.get()) {
                if (!hasNearbySpawners()) {
                    continue; // False alarm, no spawners around
                }

                if (notifications.get())
                    info("EMERGENCY: Player " + playerName + " came too close (" + String.format("%.1f", distance)
                            + " blocks)!");

                emergencyDisconnect = true;
                emergencyReason = "User " + playerName + " came too close";
                detectedPlayer = playerName;
                detectionTime = System.currentTimeMillis();

                disableAllAutoReconnect();

                // Send webhook notification before disconnecting
                if (!webhookSent) {
                    sendWebhookNotification();
                    webhookSent = true;
                }

                doDisconnect();

                if (notifications.get())
                    info("Disconnected due to emergency player detection.");

                toggle();
                return true;
            }
        }
        return false;
    }

    private void checkForPlayers() {
        long otherPlayers = mc.world.getPlayers().stream().filter(p -> p != mc.player).count();
        boolean nearbySpawners = hasNearbySpawners();
        debug("IDLE scan: otherPlayers=" + otherPlayers + ", nearbySpawners=" + nearbySpawners
                + ", range=" + minDetectionRange.get() + "-" + maxDetectionRange.get()
                + ", emergency=" + emergencyDistance.get());

        if (otherPlayers >= PLAYER_COUNT_THRESHOLD) {
            debug("IDLE skipped: too many players (" + otherPlayers + "/" + PLAYER_COUNT_THRESHOLD + ")");
            return;
        }

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player == null || !(player instanceof AbstractClientPlayerEntity))
                continue;

            String playerName = player.getName().getString();
            double distance = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(new Vec3d(player.getX(), player.getY(), player.getZ()));
            debug("Candidate: " + playerName + " @ " + String.format("%.1f", distance) + " blocks");

            if (distance < minDetectionRange.get() || distance > maxDetectionRange.get()) {
                debug("Skipped " + playerName + ": out of range");
                continue;
            }

            if (isPlayerWhitelisted(playerName)) {
                debug("Skipped " + playerName + ": whitelisted");
                continue;
            }

            detectedPlayer = playerName;
            detectionTime = System.currentTimeMillis();

            if (!hasNearbySpawners()) {
                debug("Skipped " + playerName + ": no spawners nearby");
                continue;
            }

            if (notifications.get())
                info("SpawnerProtect: Player detected at " + String.format("%.1f", distance) + " blocks - "
                        + detectedPlayer);

            disableAllAutoReconnect();

            currentState = State.DISABLE_MODULES;
            if (notifications.get())
                info("Player detected! Starting protection sequence...");

            break;
        }
    }

    private boolean isLoginFlowActive() {
        try {
            AliceAutoReconnect reconnect = (AliceAutoReconnect) Modules.get().get(AliceAutoReconnect.class);
            return reconnect != null && reconnect.isInLoginFlow();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasNearbySpawners() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos playerPos = mc.player.getBlockPos();
        int range = spawnerRange.get();
        for (BlockPos pos : BlockPos.iterate(
                playerPos.add(-range, -range, -range),
                playerPos.add(range, range, range))) {
            if (mc.world.getBlockState(pos).getBlock() == net.minecraft.block.Blocks.SPAWNER) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerWhitelisted(String playerName) {
        if (!enableWhitelist.get() || whitelistPlayers.get().isEmpty()) {
            return false;
        }

        return whitelistPlayers.get().stream()
                .anyMatch(whitelistedName -> whitelistedName.equalsIgnoreCase(playerName));
    }

    // ── Protection item helpers ──────────────────────────────────────────

    private boolean isPickaxe(Item item) {
        return item == Items.NETHERITE_PICKAXE
                || item == Items.DIAMOND_PICKAXE
                || item == Items.IRON_PICKAXE
                || item == Items.GOLDEN_PICKAXE
                || item == Items.STONE_PICKAXE
                || item == Items.WOODEN_PICKAXE;
    }

    /**
     * Material-tier strength. Silk Touch is deliberately irrelevant: the
     * protection flow needs the strongest available pickaxe, not a particular
     * enchantment.
     */
    private int pickaxeStrength(Item item) {
        if (item == Items.NETHERITE_PICKAXE) return 6;
        if (item == Items.DIAMOND_PICKAXE) return 5;
        if (item == Items.IRON_PICKAXE) return 4;
        if (item == Items.GOLDEN_PICKAXE) return 3;
        if (item == Items.STONE_PICKAXE) return 2;
        if (item == Items.WOODEN_PICKAXE) return 1;
        return -1;
    }

    private int remainingDurability(ItemStack stack) {
        return stack.isDamageable()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamage())
                : Integer.MAX_VALUE;
    }

    private boolean isBetterPickaxe(
            ItemStack candidate,
            int bestStrength,
            int bestDurability) {
        int strength = pickaxeStrength(candidate.getItem());
        if (strength != bestStrength) return strength > bestStrength;
        return strength >= 0 && remainingDurability(candidate) > bestDurability;
    }

    private int findStrongestPickaxeSlot() {
        if (mc.player == null) return -1;

        int bestSlot = -1;
        int bestStrength = -1;
        int bestDurability = -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !isPickaxe(stack.getItem())) continue;
            if (isBetterPickaxe(stack, bestStrength, bestDurability)) {
                bestSlot = i;
                bestStrength = pickaxeStrength(stack.getItem());
                bestDurability = remainingDurability(stack);
            }
        }
        return bestSlot;
    }

    private boolean equipStrongestPickaxe() {
        if (mc.player == null) return false;

        int slot = findStrongestPickaxeSlot();
        if (slot < 0) return false;

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        if (slot == selectedSlot) return true;

        if (slot <= 8) {
            return InvUtils.swap(slot, false);
        }

        // InvUtils.swap only accepts hotbar slots. A main-inventory pickaxe
        // needs one SWAP transaction into the selected hotbar slot. quickSwap
        // requires converted slot IDs on both sides; selectedSlot is only an
        // inventory index, so fromId(selectedSlot) targets the wrong handler
        // slot (crafting slots 0-8 in PlayerScreenHandler).
        if (slot < 36) {
            InvUtils.quickSwap()
                    .fromHotbar(selectedSlot)
                    .to(slot);
            return true;
        }

        return false;
    }

    private boolean isProtectedItem(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (isPickaxe(stack.getItem())) return true;
        if (stack.getItem() == Items.SPAWNER) return true;
        return false;
    }

    private boolean hasJunkItems() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isProtectedItem(stack)) return true;
        }
        return false;
    }

    private boolean hasPickaxeInInventory() {
        return findStrongestPickaxeSlot() != -1;
    }

    // ── New protection flow handlers ─────────────────────────────────────

    private void handleDisableModules() {
        // Close any open screen first
        if (mc.currentScreen != null && mc.player != null) {
            mc.player.closeHandledScreen();
        }

        // Disable SpawnerOrder
        try {
            Module spawnerOrder = Modules.get().get(SpawnerOrder.class);
            if (spawnerOrder != null && spawnerOrder.isActive()) {
                spawnerOrder.toggle();
                if (notifications.get()) info("Disabled SpawnerOrder");
            }
        } catch (Exception ignored) {}

        currentState = State.CHECK_PICKAXE;
        if (notifications.get()) info("Modules disabled. Checking for pickaxe...");
    }

    private void handleSellingJunk() {
        if (!hasJunkItems()) {
            // Selling done - return to saved state or disconnect
            if (savedStateBeforeSell != null) {
                if (notifications.get()) info("Junk sold. Resuming " + savedStateBeforeSell.name() + "...");
                currentState = savedStateBeforeSell;
                savedStateBeforeSell = null;
            } else {
                if (notifications.get()) info("Inventory clean. Disconnecting...");
                currentState = State.DISCONNECTING;
            }
            return;
        }

        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            return;
        }

        if (sellRetryCount >= maxSellRetries.get()) {
            // Sell failed too many times, mark as exhausted so global interrupt won't re-trigger
            sellExhausted = true;
            if (notifications.get()) info("Sell attempt limit reached (" + maxSellRetries.get() + "/" + maxSellRetries.get() + "). Skipping unsellable items.");
            if (savedStateBeforeSell != null) {
                currentState = savedStateBeforeSell;
                savedStateBeforeSell = null;
            } else {
                currentState = State.CHECK_PICKAXE;
            }
            return;
        }

        ChatUtils.sendPlayerMsg("/sell");
        sellRetryCount++;
        currentState = State.WAITING_SELL_GUI;
        tickCounter = 0;
        if (notifications.get()) info("Sent /sell command (attempt " + sellRetryCount + ")");
    }

    private void handleWaitingSellGui() {
        if (mc.currentScreen instanceof GenericContainerScreen) {
            currentState = State.DEPOSITING_SELL_ITEMS;
            tickCounter = 0;
            return;
        }
        // Timeout after 60 ticks (3 seconds)
        if (tickCounter > 60) {
            currentState = State.SELLING_JUNK;
        }
    }

    private void handleDepositingSellItems() {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            currentState = State.SELLING_JUNK;
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();

        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            ItemStack stack = slot.getStack();
            if (slot.inventory != mc.player.getInventory() || stack.isEmpty()) continue;
            if (isProtectedItem(stack)) continue;

            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
        }

        mc.player.closeHandledScreen();

        // Don't check hasJunkItems() here — items are in transit to the server.
        // Add a delay to let the server process, then resume previous state.
        // If unsellable items come back, the global junk interrupt will re-trigger
        // (with sellRetryCount preserved) until sellExhausted is set.
        transferDelayCounter = 10; // wait 10 ticks for server to process
        if (savedStateBeforeSell != null) {
            if (notifications.get()) info("Sell attempt " + sellRetryCount + "/" + maxSellRetries.get() + " done. Resuming " + savedStateBeforeSell.name() + "...");
            currentState = savedStateBeforeSell;
            savedStateBeforeSell = null;
        } else {
            if (notifications.get()) info("Sell attempt " + sellRetryCount + "/" + maxSellRetries.get() + " done. Continuing...");
            currentState = State.CHECK_PICKAXE;
        }
    }

    private void handleCheckPickaxe() {
        if (hasPickaxeInInventory()) {
            if (notifications.get()) info("Pickaxe found. Moving to spawners...");
            currentState = State.GOING_TO_SPAWNERS;
            currentTarget = null;
            noSpawnerStartTime = -1;
            currentTargetStartTime = -1;
            currentMiningStartTime = -1;
            serverConfirmedTargetMined = false;
            rotating = false;
            clearSilentMiningRotation(false);
            invalidSpawners.clear();
            return;
        }

        // Need to get pickaxe from ender chest
        if (notifications.get()) info("No pickaxe found. Getting from ender chest...");
        targetChest = null;
        currentState = State.GETTING_PICKAXE;
    }

    private void handleGettingPickaxe() {
        if (targetChest == null) {
            targetChest = findNearestEnderChest();
            if (targetChest == null) {
                if (notifications.get()) info("No ender chest found! Trying to mine without pickaxe...");
                currentState = State.GOING_TO_SPAWNERS;
                return;
            }
        }

        lookAtBlock(targetChest);
        currentState = State.OPENING_ECHEST_PICKUP;
        echestPickupAttempts = 0;
        tickCounter = 0;
    }

    private void handleOpeningEchestPickup() {
        if (targetChest == null) {
            currentState = State.GETTING_PICKAXE;
            return;
        }

        mc.options.sneakKey.setPressed(false);
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);

        if (echestPickupAttempts < 20) {
            lookAtBlock(targetChest);
        }

        if (echestPickupAttempts % 5 == 0) {
            if (mc.interactionManager != null && mc.player != null) {
                mc.interactionManager.interactBlock(
                        mc.player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3d.ofCenter(targetChest),
                                Direction.UP,
                                targetChest,
                                false));
            }
        }

        echestPickupAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            currentState = State.TAKING_PICKAXE;
            tickCounter = 0;
            if (notifications.get()) info("Ender chest opened. Looking for pickaxe...");
        }

        if (echestPickupAttempts > 200) {
            if (notifications.get()) dummyLog("Failed to open ender chest for pickaxe!");
            currentState = State.GOING_TO_SPAWNERS;
        }
    }

    private void handleTakingPickaxe() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            currentState = State.GETTING_PICKAXE;
            return;
        }

        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;

        // Select the strongest material tier. Enchantments, including Silk
        // Touch, are intentionally not required.
        int bestSlot = -1;
        int bestStrength = -1;
        int bestDurability = -1;

        for (int i = 0; i < chestSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || !isPickaxe(stack.getItem())) continue;

            if (isBetterPickaxe(stack, bestStrength, bestDurability)) {
                bestSlot = i;
                bestStrength = pickaxeStrength(stack.getItem());
                bestDurability = remainingDurability(stack);
            }
        }

        if (bestSlot != -1) {
            int hotbarSlot = mc.player.getInventory().getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) {
                    hotbarSlot = i;
                    break;
                }
            }

            pickaxeTransferBaseRevision = handler.getRevision();
            pickaxeTransferHotbarSlot = hotbarSlot;
            pickaxeTransferExpectedStrength = bestStrength;
            pickaxeTransferTicks = 0;

            // SWAP places the pickaxe directly in a known hotbar slot. The old
            // QUICK_MOVE could put it in main inventory, where InvUtils.swap
            // silently refuses to select it.
            mc.interactionManager.clickSlot(
                    handler.syncId,
                    bestSlot,
                    hotbarSlot,
                    SlotActionType.SWAP,
                    mc.player);
            currentState = State.WAITING_PICKAXE_TRANSFER;
            if (notifications.get()) {
                info("Taking strongest pickaxe from ender chest slot " + bestSlot + "...");
            }
            return;
        }

        if (notifications.get()) info("No pickaxe found in ender chest!");
        finishPickaxePickupFlow();
    }

    private void handleWaitingPickaxeTransfer() {
        pickaxeTransferTicks++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            boolean serverAcknowledged = handler.getRevision() != pickaxeTransferBaseRevision;
            boolean receivedExpectedPickaxe = pickaxeTransferHotbarSlot >= 0
                    && pickaxeTransferHotbarSlot < 9
                    && pickaxeStrength(mc.player.getInventory()
                            .getStack(pickaxeTransferHotbarSlot).getItem())
                            >= pickaxeTransferExpectedStrength;

            if (serverAcknowledged && receivedExpectedPickaxe) {
                if (notifications.get()) info("Strongest pickaxe moved to hotbar.");
                finishPickaxePickupFlow();
                return;
            }

            if (pickaxeTransferTicks <= 60) return;
            if (notifications.get()) {
                info("Pickaxe transfer was not acknowledged; reopening ender chest...");
            }
            mc.player.closeHandledScreen();
            targetChest = null;
            currentState = State.GETTING_PICKAXE;
            return;
        }

        if (hasPickaxeInInventory()) {
            finishPickaxePickupFlow();
        } else {
            targetChest = null;
            currentState = State.GETTING_PICKAXE;
        }
    }

    private void finishPickaxePickupFlow() {
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        transferDelayCounter = 5;
        currentState = State.GOING_TO_SPAWNERS;
        currentTarget = null;
        noSpawnerStartTime = -1;
        currentTargetStartTime = -1;
        currentMiningStartTime = -1;
        serverConfirmedTargetMined = false;
        rotating = false;
        pickaxeTransferBaseRevision = -1;
        pickaxeTransferHotbarSlot = -1;
        pickaxeTransferExpectedStrength = -1;
        pickaxeTransferTicks = 0;
        invalidSpawners.clear();
    }

    private void handlePickingUpItems() {
        // Wait for dropped items to be picked up
        if (pickupStartTime == -1) {
            pickupStartTime = System.currentTimeMillis();
        }

        long elapsed = System.currentTimeMillis() - pickupStartTime;

        // Check for spawner item entities nearby
        boolean foundSpawnerEntity = false;
        for (net.minecraft.entity.Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity itemEntity) {
                if (itemEntity.getStack().getItem() == Items.SPAWNER) {
                    double dist = mc.player.distanceTo(itemEntity);
                    if (dist < 16) {
                        foundSpawnerEntity = true;
                        if (dist > 2.0) {
                            // Walk towards the spawner item
                            lookAtBlock(itemEntity.getBlockPos());
                            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
                        } else {
                            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
                        }
                        break;
                    }
                }
            }
        }

        // Timeout or no more spawner entities
        if (!foundSpawnerEntity || elapsed > 5000) {
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
            pickupStartTime = -1;

            // Check if there are more spawner blocks to mine
            BlockPos nextSpawner = findNearestSpawner();
            if (nextSpawner != null) {
                currentTarget = null;
                currentState = State.GOING_TO_SPAWNERS;
                if (notifications.get()) info("More spawners found, continuing mining...");
            } else {
                spawnersMinedSuccessfully = true;
                currentState = State.GOING_TO_CHEST;
                targetChest = null;
                tickCounter = 0;
                if (notifications.get()) info("All spawners mined! Moving to ender chest...");
            }
        }
    }

    private void handleGoingToSpawners() {
        mc.options.sneakKey.setPressed(true);

        if (currentTarget != null) {
            if (serverConfirmedTargetMined) {
                if (notifications.get()) {
                    info("Spawner at " + currentTarget
                            + " server-confirmed mined! Looking for next spawner...");
                }
                stopBreaking();
                currentTarget = null;
                currentTargetStartTime = -1;
                currentMiningStartTime = -1;
                serverConfirmedTargetMined = false;
                noSpawnerStartTime = -1;
                return;
            }

            long timeoutStartedAt = currentMiningStartTime >= 0
                    ? currentMiningStartTime
                    : currentTargetStartTime;
            if (timeoutStartedAt != -1
                    && System.currentTimeMillis() - timeoutStartedAt
                            > spawnerTimeout.get()) {
                if (notifications.get()) {
                    info("Timeout mining spawner at " + currentTarget
                            + " after " + spawnerTimeout.get() + "ms, skipping...");
                }
                invalidSpawners.add(currentTarget);
                currentTarget = null;
                currentTargetStartTime = -1;
                currentMiningStartTime = -1;
                serverConfirmedTargetMined = false;
                stopBreaking();
                return;
            }

            // ClientPlayerInteractionManager predicts AIR before the server
            // answers. Never advance from that local state; wait for an
            // authoritative BlockUpdate/ChunkDelta packet instead.
            if (mc.world.getBlockState(currentTarget).getBlock() != Blocks.SPAWNER) {
                pauseBreakingInput();
                return;
            }
        }

        if (currentTarget == null) {
            currentTarget = findNearestSpawner();

            if (currentTarget == null) {
                // Start the delay timer when no spawners are found
                if (noSpawnerStartTime == -1) {
                    noSpawnerStartTime = System.currentTimeMillis();
                    if (notifications.get())
                        info("No spawners found, waiting " + spawnerCheckDelay.get() + "ms to confirm...");
                    return;
                }
                
                // Check if the delay has passed
                long elapsed = System.currentTimeMillis() - noSpawnerStartTime;
                if (elapsed < spawnerCheckDelay.get()) {
                    // Still waiting, keep checking for new spawners
                    return;
                }
                
                // Delay passed, confirm no spawners - now pick up dropped items
                invalidSpawners.clear();
                stopBreaking();
                mc.options.sneakKey.setPressed(false);
                pickupStartTime = -1;
                currentState = State.PICKING_UP_ITEMS;
                noSpawnerStartTime = -1;
                if (notifications.get())
                    info("All spawners mined! Picking up dropped items...");
                return;
            }
            
            // Found a new spawner. Targeting and mining have separate timeout
            // baselines so a legitimate silent rotation does not consume the
            // pickaxe's whole mining budget.
            currentTargetStartTime = System.currentTimeMillis();
            currentMiningStartTime = -1;
            serverConfirmedTargetMined = false;
            clearSilentMiningRotation(false);
            if (notifications.get())
                info("Found spawner at " + currentTarget + ", distance: " + 
                    String.format("%.1f", Math.sqrt(currentTarget.getSquaredDistance(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())))));
        }
        
        // Reset the no-spawner timer when we find a spawner
        noSpawnerStartTime = -1;

        if (!equipStrongestPickaxe()) {
            stopBreaking();
            currentState = State.GOING_TO_CHEST;
            if (notifications.get())
                info("No usable pickaxe found, moving to ender chest...");
            return;
        }

        BlockHitResult miningHit = findSpawnerMiningHit(currentTarget);
        if (miningHit == null) {
            pauseBreakingInput();
            return;
        }

        if (!advanceSilentMiningRotation(miningHit)) {
            pauseBreakingInput();
            return;
        }

        if (currentMiningStartTime < 0) {
            currentMiningStartTime = System.currentTimeMillis();
        }

        // Advance through the vanilla interaction manager exactly once here.
        // The previous input-key path depended on MinecraftClient's cursor and
        // screen state, so it could skip handleBlockBreaking completely. Do
        // not also hold attackKey: that would make vanilla advance a second
        // time later in the same client tick.
        mc.options.attackKey.setPressed(false);
        if (mc.interactionManager.updateBlockBreakingProgress(
                currentTarget,
                miningHit.getSide())) {
            mc.world.spawnBlockBreakingParticle(currentTarget, miningHit.getSide());
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private BlockHitResult findSpawnerMiningHit(BlockPos pos) {
        if (mc.player == null || mc.world == null || pos == null) return null;

        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double reach = mc.player.getBlockInteractionRange();
        if (eye.squaredDistanceTo(center) > (reach + 0.75) * (reach + 0.75)) {
            return null;
        }

        HitResult raycast = mc.world.raycast(new RaycastContext(
                eye,
                center,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        if (raycast instanceof BlockHitResult hit
                && hit.getBlockPos().equals(pos)) {
            return hit;
        }
        return null;
    }

    /**
     * Gradually rotates only the server-facing yaw/pitch. The real player
     * camera is never changed. Mining begins on the tick after the final look
     * packet, preserving packet order for server-side rotation checks.
     */
    private boolean advanceSilentMiningRotation(BlockHitResult hit) {
        BlockPos target = hit.getBlockPos().toImmutable();
        Direction side = hit.getSide();

        if (!target.equals(silentMiningTarget) || side != silentMiningSide) {
            silentMiningTarget = target;
            silentMiningSide = side;
            currentSilentMiningYaw = mc.player.getYaw();
            currentSilentMiningPitch = mc.player.getPitch();
            silentMiningRotationReady = false;
            silentMiningRotationActive = true;
        }

        if (silentMiningRotationReady) return true;

        Vec3d direction = hit.getPos().subtract(mc.player.getEyePos());
        double horizontal = Math.sqrt(
                direction.x * direction.x + direction.z * direction.z);
        float wantedYaw = (float) Math.toDegrees(
                Math.atan2(-direction.x, direction.z));
        float wantedPitch = (float) -Math.toDegrees(
                Math.atan2(direction.y, horizontal));

        float yawDiff = wrapDegrees(wantedYaw - currentSilentMiningYaw);
        float pitchDiff = wantedPitch - currentSilentMiningPitch;
        currentSilentMiningYaw += Math.signum(yawDiff)
                * Math.min(Math.abs(yawDiff), ROTATION_SPEED);
        currentSilentMiningPitch += Math.signum(pitchDiff)
                * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);

        if (Math.abs(yawDiff) <= ROTATION_SPEED
                && Math.abs(pitchDiff) <= ROTATION_SPEED) {
            currentSilentMiningYaw = wantedYaw;
            currentSilentMiningPitch = wantedPitch;
            silentMiningRotationReady = true;
        }

        sendSilentMiningRotation(
                currentSilentMiningYaw,
                currentSilentMiningPitch);
        return false;
    }

    private void sendSilentMiningRotation(float yaw, float pitch) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        mc.player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(
                        yaw,
                        pitch,
                        mc.player.isOnGround(),
                        false));
    }

    private void pauseBreakingInput() {
        mc.options.attackKey.setPressed(false);
    }

    private void clearSilentMiningRotation(boolean restoreServerRotation) {
        if (restoreServerRotation
                && silentMiningRotationActive
                && mc.player != null
                && mc.player.networkHandler != null) {
            sendSilentMiningRotation(mc.player.getYaw(), mc.player.getPitch());
        }
        silentMiningTarget = null;
        silentMiningSide = null;
        silentMiningRotationReady = false;
        silentMiningRotationActive = false;
    }

    private BlockPos findNearestSpawner() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        // Use spawnerRange setting for distance check (squared for efficiency)
        double maxDistanceSq = spawnerRange.get() * spawnerRange.get();

        for (BlockPos pos : BlockPos.iterate(
                playerPos.add(-spawnerRange.get(), -spawnerRange.get(), -spawnerRange.get()),
                playerPos.add(spawnerRange.get(), spawnerRange.get(), spawnerRange.get()))) {

            if (mc.world.getBlockState(pos).getBlock() != Blocks.SPAWNER) continue;
            if (invalidSpawners.contains(pos)) continue;

            double distanceSq = pos.getSquaredDistance(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            if (distanceSq > maxDistanceSq) continue;

            if (distanceSq < nearestDistance) {
                nearestDistance = distanceSq;
                nearest = pos.toImmutable();
            }
        }

        return nearest;
    }
    private void lookAtBlock(BlockPos pos) {
        lookAtBlock(pos, Direction.UP);
    }

    private void lookAtBlock(BlockPos pos, Direction side) {
        Vec3d targetPos = Vec3d.ofCenter(pos).add(Vec3d.of(side.getVector()).multiply(0.5));
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d dir = targetPos.subtract(playerPos).normalize();

        targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        targetPitch = (float) Math.toDegrees(-Math.asin(dir.y));
        rotating = true;
    }

    private void stopBreaking() {
        pauseBreakingInput();
        if (mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        clearSilentMiningRotation(true);
    }

    private void handleGoingToChest() {
        if (!depositToEChest.get()) {
            currentState = State.DISCONNECTING;
            if (notifications.get())
                info("Deposit to ender chest disabled, disconnecting...");
            return;
        }

        if (targetChest == null) {
            targetChest = findNearestEnderChest();
            if (targetChest == null) {
                if (notifications.get())
                    info("No ender chest found nearby!");
                currentState = State.DISCONNECTING;
                return;
            }
            if (notifications.get())
                info("Found ender chest at " + targetChest);
        }

        moveTowardsBlock(targetChest);

        if (mc.player.getBlockPos().getSquaredDistance(targetChest) <= 9) {
            currentState = State.OPENING_CHEST;
            chestOpenAttempts = 0;
            if (notifications.get())
                info("Reached ender chest. Attempting to open...");
        }

        if (tickCounter > 600) {
            if (notifications.get())
                dummyLog("Timed out trying to reach ender chest!");
            currentState = State.DISCONNECTING;
        }
    }

    private BlockPos findNearestEnderChest() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearestChest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
                playerPos.add(-16, -8, -16),
                playerPos.add(16, 8, 16))) {

            if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                double distance = pos.getSquaredDistance(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestChest = pos.toImmutable();
                }
            }
        }

        return nearestChest;
    }

    private void moveTowardsBlock(BlockPos target) {
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = Vec3d.ofCenter(target);
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        mc.player.setYaw((float) yaw);

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
    }

    private void handleOpeningChest() {
        if (targetChest == null) {
            currentState = State.GOING_TO_CHEST;
            return;
        }

        mc.options.sneakKey.setPressed(false);

        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), true);

        if (chestOpenAttempts < 20) {
            lookAtBlock(targetChest);
        }

        if (chestOpenAttempts % 5 == 0) {
            if (mc.interactionManager != null && mc.player != null) {
                mc.interactionManager.interactBlock(
                        mc.player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3d.ofCenter(targetChest),
                                Direction.UP,
                                targetChest,
                                false));
                if (notifications.get())
                    info("Right-clicking ender chest... (attempt " + (chestOpenAttempts / 5 + 1) + ")");
            }
        }

        chestOpenAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
            currentState = State.DEPOSITING_ITEMS;
            lastProcessedSlot = -1;
            tickCounter = 0;
            if (notifications.get())
                info("Ender chest opened successfully! Made by GLZD ");
        }

        if (chestOpenAttempts > 200) {
            KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
            if (notifications.get())
                dummyLog("Failed to open ender chest after multiple attempts!");
            currentState = State.DISCONNECTING;
        }
    }

    private void handleDepositingItems() {
        if (!depositToEChest.get()) {
            currentState = State.DISCONNECTING;
            if (notifications.get())
                info("Deposit to ender chest disabled, skipping deposit.");
            return;
        }

        mc.options.sneakKey.setPressed(false);

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler handler = (GenericContainerScreenHandler) mc.player.currentScreenHandler;

            if (!hasItemsToDeposit()) {
                itemsDepositedSuccessfully = true;
                if (notifications.get())
                    info("All spawners deposited!");
                mc.player.closeHandledScreen();
                transferDelayCounter = 10;
                currentState = State.DISCONNECTING;
                return;
            }

            transferItemsToChest(handler);

        } else {
            currentState = State.OPENING_CHEST;
            chestOpenAttempts = 0;
        }

        if (tickCounter > 900) {
            if (notifications.get())
                dummyLog("Timed out depositing items!");
            currentState = State.DISCONNECTING;
        }
    }

    private boolean isVitalItem(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() == Items.AIR)
            return true;

        // Only spawners should be deposited into ender chest
        if (stack.getItem() == Items.SPAWNER)
            return false;

        // Everything else (pickaxe, ender chest, etc.) is vital - keep it
        return true;
    }

    private boolean hasItemsToDeposit() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isVitalItem(stack)) {
                return true;
            }
        }
        return false;
    }

    private void transferItemsToChest(GenericContainerScreenHandler handler) {
        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;
        int playerInventoryStart = chestSlots;

        // Check if chest is full before depositing
        if (isChestFull(handler, chestSlots)) {
            if (notifications.get())
                error("Ender chest is full! Disconnecting for safety.");
            currentState = State.DISCONNECTING;
            return;
        }

        // Only deposit spawners
        for (int i = 0; i < 36; i++) {
            int slotId = playerInventoryStart + i;
            ItemStack stack = handler.getSlot(slotId).getStack();

            if (!stack.isEmpty() && stack.getItem() == Items.SPAWNER) {
                depositSlot(handler, slotId, stack);
                return;
            }
        }

        if (lastProcessedSlot >= playerInventoryStart) {
            lastProcessedSlot = playerInventoryStart - 1;
            transferDelayCounter = 3;
        }
    }

    private void depositSlot(GenericContainerScreenHandler handler, int slotId, ItemStack stack) {
        if (notifications.get())
            info("Transferring item from slot " + slotId + ": " + stack.getItem().toString());

        if (mc.interactionManager != null) {
            mc.interactionManager.clickSlot(
                    handler.syncId,
                    slotId,
                    0,
                    SlotActionType.QUICK_MOVE,
                    mc.player);
        }

        lastProcessedSlot = slotId;
        transferDelayCounter = 2;
    }

    private boolean isChestFull(GenericContainerScreenHandler handler, int chestSlots) {
        // If there's any empty slot, it's not full
        for (int i = 0; i < chestSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty())
                return false;
        }
        // If all slots are non-empty, we consider it possibly full.
        // Note: It might still have stackable space, but requirement says "deconnect if
        // full".
        // A more robust check would check if any stackable item can fit, but this is
        // usually what users mean.
        return true;
    }

    private boolean webhookSent = false;

    private void handleDisconnecting() {
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
        mc.options.sneakKey.setPressed(false);

        // Disable both auto-reconnect modules
        disableAllAutoReconnect();

        // Send webhook only once
        if (!webhookSent) {
            sendWebhookNotification();
            webhookSent = true;
        }

        if (emergencyDisconnect) {
            if (notifications.get())
                info("SpawnerProtect: " + emergencyReason + ". Successfully disconnected.");
        } else {
            if (notifications.get())
                info("SpawnerProtect: " + detectedPlayer + " detected. Successfully disconnected.");
        }

        doDisconnect();

        if (notifications.get())
            info("Disconnected due to player detection.");
        toggle();
    }

    private void sendWebhookNotification() {
        if (!webhook.get() || webhookUrl.get() == null || webhookUrl.get().trim().isEmpty()) {
            if (notifications.get())
                info("Webhook disabled or URL not configured.");
            return;
        }

        String webhookUrlValue = webhookUrl.get().trim();

        long discordTimestamp = detectionTime / 1000L;

        String messageContent = "";
        if (selfPing.get() && discordId.get() != null && !discordId.get().trim().isEmpty()) {
            messageContent = String.format("<@%s>", discordId.get().trim());
        }

        String embedJson = createWebhookPayload(messageContent, discordTimestamp);

        new Thread(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrlValue))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(embedJson))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    if (notifications.get())
                        info("Webhook notification sent successfully!");
                } else {
                    if (notifications.get())
                        dummyLog("Failed to send webhook notification. Status: " + response.statusCode());
                }
            } catch (Exception e) {
                if (notifications.get())
                    dummyLog("Failed to send webhook notification: " + e.getMessage());
            }
        }).start();
    }

    private String createWebhookPayload(String messageContent, long discordTimestamp) {
        String title = emergencyDisconnect ? "SpawnerProtect Emergency Alert" : "SpawnerProtect Alert";
        String description;

        if (emergencyDisconnect) {
            description = String.format(
                    "**Player Detected:** %s\\n**Detection Time:** <t:%d:R>\\n**Reason:** %s\\n**Disconnected:** Yes",
                    escapeJson(detectedPlayer), discordTimestamp, escapeJson(emergencyReason));
        } else {
            description = String.format(
                    "**Player Detected:** %s\\n**Detection Time:** <t:%d:R>\\n**Spawners Mined:** %s\\n**Items Deposited:** %s\\n**Disconnected:** Yes",
                    escapeJson(detectedPlayer), discordTimestamp,
                    spawnersMinedSuccessfully ? "✅ Success" : "❌ Failed",
                    itemsDepositedSuccessfully ? "✅ Success" : "❌ Failed");
        }

        int color = emergencyDisconnect ? 16711680 : 16766720;

        return String.format("""
                {
                    "username": "ALICE Webhook",
                    "avatar_url": "https://i.imgur.com/OL2y1cr.png",
                    "content": "%s",
                    "embeds": [{
                        "title": "%s",
                        "description": "%s",
                        "color": %d,
                        "timestamp": "%s",
                        "footer": {
                            "text": "Sent by ALICE"
                        }
                    }]
                }""",
                escapeJson(messageContent),
                title,
                description,
                color,
                Instant.now().toString());
    }

    private String escapeJson(String input) {
        if (input == null)
            return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void info(String msg) {
        if (notifications.get()) {
            dummyLog(msg);
        }
    }

    private void error(String msg) {
        if (notifications.get()) {
            dummyLog(msg);
        }
    }

    // Disconnect via the network connection instead of ClientWorld.disconnect(),
    // which no longer exists as a no-arg method in the runtime mappings (class_638.method_8525).
    private void doDisconnect() {
        try {
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("SpawnerProtect"));
            }
        } catch (Throwable t) {
            System.err.println("[SpawnerProtect] doDisconnect failed: " + t);
        }
    }

    // Temporary diagnostic logging, rate-limited to once per second to avoid chat spam.
    private void debug(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastDebugLogTime > 1000) {
            lastDebugLogTime = now;
            dummyLog("[SP-DEBUG] " + msg);
        }
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        stopBreaking();
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
    }

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
    }
}

