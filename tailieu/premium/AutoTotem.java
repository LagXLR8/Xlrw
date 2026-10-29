package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * Auto Totem++ for ALICE: keeps a Totem of Undying in the offhand at all times.
 *
 * <p>Grim MultiActionsC rejects CLICK_WINDOW when the player is sprinting or
 * has non-zero movement input. This module emits one neutral movement cycle
 * (zero direction, zero jump, no sprint, preserve sneak) before each swap.</p>
 *
 * <p>Mio fast-path: when the server confirms a pop via EntityStatus 35 the
 * module tries a 0ms clean-epoch SWAP immediately after CLIENT_TICK_END
 * (inside the same tick's neutral window). If the window is dirty it falls
 * back to the normal 1-tick neutral path — still Grim-safe.</p>
 */
public class AutoTotem extends AliceModule {
    private static final int OFFHAND_SWAP_BUTTON = 40;
    private static AutoTotem activeInstance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("so tick cho giua moi lan swap sang offhand")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> yieldMeteorOffhand = sgGeneral.add(
        new BoolSetting.Builder()
            .name("yield-meteor-offhand")
            .description("tu dong tat khi Meteor's Offhand module dang active")
            .defaultValue(true)
            .build()
    );

    private int cooldown;
    private SwapPhase swapPhase = SwapPhase.Idle;
    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;

    /** Set when a pop (status 35) is observed — next swap is urgent (0ms path). */
    private boolean mioPending;
    /** True only for the first swap attempt after a pop — that's the one allowed to use the clean epoch. */
    private boolean mioDirectAllowed;

    public AutoTotem() {
        super(
            AliceAddon.PVP_CATEGORY,
            "auto-totem++",
            "Tu dong swap totem tu inventory sang offhand."
        );
    }

    // Called by GrimCombatCoordinator right after CLIENT_TICK_END when
    // packetWindowClean == true (i.e. no Move/Interact/ClickSlot in this epoch).
    static void tryQueuedDirectSwapAfterClientTickEnd() {
        AutoTotem inst = activeInstance;
        if (inst != null) inst.tryQueuedMioSwap();
    }

    @Override
    public void onActivate() {
        activeInstance = this;
        cooldown = 0;
        swapPhase = SwapPhase.Idle;
        neutralPlayer = null;
        mioPending = false;
        mioDirectAllowed = false;
        restoreNeutralInput();
        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        if (activeInstance == this) activeInstance = null;
        cooldown = 0;
        swapPhase = SwapPhase.Idle;
        neutralPlayer = null;
        mioPending = false;
        mioDirectAllowed = false;
        restoreNeutralInput();
        super.onDeactivate();
    }

    @AliceHandler(priority = -10000)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (swapPhase == SwapPhase.NeutralArmed) {
            ensureNeutralInput();
        }
    }

    @AliceHandler(priority = 10000)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (swapPhase != SwapPhase.NeutralApplied) return;

        boolean safe = hasSafeInventoryClickInput()
            && mc.player == neutralPlayer
            && mc.player != null
            && mc.player.input == neutralInput;
        restoreNeutralInput();
        neutralPlayer = null;
        swapPhase = safe ? SwapPhase.NeutralReady : SwapPhase.Idle;
    }

    @AliceHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getStatus() != 35) return;
        if (mc.world == null || mc.player == null) return;
        if (packet.getEntity(mc.world) != mc.player) return;
        // Only treat as urgent if offhand totem was consumed — pop.
        // If offhand still has totem the status is for another entity effect.
        // We check offhand has totem (ghost) — decrement will happen at swap boundary.
        if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        // Main-hand totem pop is handled elsewhere; offhand pop is our concern.
        if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        if (!isActive() || (yieldMeteorOffhand.get() && isMeteorOffhandActive())) return;

        // Arm urgent refill — try 0ms path, fallback to neutral.
        mioPending = true;
        mioDirectAllowed = true;
        // If we are idle, arm neutral immediately so this tick's movement is neutral.
        if (swapPhase == SwapPhase.Idle) {
            swapPhase = SwapPhase.NeutralArmed;
        }
        // If already neutral-ready and window is still clean, try immediately
        // (packet may have arrived after movement — then we wait for next tick's
        // CLIENT_TICK_END via the coordinator callback).
        if (swapPhase == SwapPhase.NeutralReady) {
            tryQueuedMioSwap();
        }
    }

    @AliceHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null
            || mc.world == null
            || mc.interactionManager == null
            || mc.player.isCreative()
            || mc.player.isSpectator()) {
            cooldown = 0;
            swapPhase = SwapPhase.Idle;
            mioPending = false;
            mioDirectAllowed = false;
            restoreNeutralInput();
            return;
        }

        if (yieldMeteorOffhand.get() && isMeteorOffhandActive()) return;

        // If a pop just happened, ignore normal cooldown — totem must be back ASAP.
        boolean urgent = mioPending;

        if (cooldown > 0 && !urgent) {
            cooldown--;
            return;
        }
        if (urgent) cooldown = 0;

        boolean offhandHasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        if (offhandHasTotem) {
            // Totem already back — clear urgent and idle.
            if (mioPending) {
                mioPending = false;
                mioDirectAllowed = false;
            }
            swapPhase = SwapPhase.Idle;
            return;
        }

        int totemSlot = findTotemSlot();
        if (totemSlot < 0) {
            // No totem anywhere — nothing to do.
            return;
        }

        if (swapPhase == SwapPhase.NeutralArmed
            || swapPhase == SwapPhase.NeutralApplied) {
            return;
        }

        if (swapPhase == SwapPhase.NeutralReady) {
            swapPhase = SwapPhase.Idle;
            // If this was the urgent pop refill, the direct path already had its
            // chance at CLIENT_TICK_END — now just do the normal neutral swap.
            mioDirectAllowed = false;
            if (swapTotemToOffhand(totemSlot)) {
                cooldown = delay.get();
                mioPending = false;
            }
            return;
        }

        // Need a totem — arm neutral window.
        swapPhase = SwapPhase.NeutralArmed;
        // Also mark as pending so the coordinator callback can fire at end of this tick.
        if (!mioPending) {
            // Non-urgent refill also benefits from clean-epoch try, but only the
            // pop refill is allowed to claim the epoch — keep it simple: only
            // urgent (mioPending) claims clean epoch. Non-urgent waits for next tick.
        }
    }

    /**
     * Try to send the SWAP immediately after CLIENT_TICK_END while the epoch
     * is still clean. This is the 0ms Mio path — succeeds only if
     * GrimCombatCoordinator.claimCleanExternalInventoryEpoch() returns true.
     */
    private boolean tryQueuedMioSwap() {
        if (!mioPending || !mioDirectAllowed) return false;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (mc.player.isCreative() || mc.player.isSpectator()) return false;
        if (swapPhase != SwapPhase.NeutralReady) return false;
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            mioPending = false;
            mioDirectAllowed = false;
            swapPhase = SwapPhase.Idle;
            return false;
        }
        int totemSlot = findTotemSlot();
        if (totemSlot < 0) return false;

        if (!GrimCombatCoordinator.claimCleanExternalInventoryEpoch()) return false;

        boolean swapped = false;
        try {
            swapPhase = SwapPhase.Idle;
            mioDirectAllowed = false;
            swapped = swapTotemToOffhand(totemSlot);
            if (swapped) {
                cooldown = delay.get();
                mioPending = false;
            }
            return swapped;
        } finally {
            if (!swapped) {
                // Release epoch so other modules are not blocked.
                GrimCombatCoordinator.releaseCleanExternalInventoryEpoch();
            }
            // If swapped, the epoch is consumed — coordinator keeps externalEpochReserved
            // until next CLIENT_TICK_END; no need to release.
        }
    }

    private int findTotemSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    private boolean swapTotemToOffhand(int totemSlotIndex) {
        if (mc.player == null
            || mc.interactionManager == null
            || totemSlotIndex < 0
            || totemSlotIndex >= 36
            || !mc.player.getInventory().getStack(totemSlotIndex)
                .isOf(Items.TOTEM_OF_UNDYING)) {
            return false;
        }

        // Status 35 is authoritative proof the offhand totem was consumed, but
        // its slot-clear packet can arrive after we swap. Apply one local
        // decrement at the safe click boundary so the SWAP prediction does not
        // exchange with a ghost totem.
        if (mioPending && mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            mc.player.getOffHandStack().decrement(1);
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, totemSlotIndex);
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory()
            || source.getIndex() != totemSlotIndex
            || !source.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return false;
        }

        mc.interactionManager.clickSlot(
            handler.syncId,
            handlerSlot,
            OFFHAND_SWAP_BUTTON,
            SlotActionType.SWAP,
            mc.player
        );
        return true;
    }

    private void ensureNeutralInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.hasVehicle()) return;
        if (neutralInput != null && player.input == neutralInput) return;

        neutralInput = new NeutralInput(player.input);
        player.input = neutralInput;
    }

    private void restoreNeutralInput() {
        ClientPlayerEntity player = mc.player;
        if (player != null && neutralInput != null
            && player.input == neutralInput) {
            player.input = neutralInput.delegate;
        }
        neutralInput = null;
    }

    private boolean hasSafeInventoryClickInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.isSprinting()) return false;

        Input input = player.input;
        if (input == null) return true;

        Vec2f movement = input.getMovementInput();
        if (movement != null && movement.lengthSquared() > 1.0E-5f) return false;

        PlayerInput playerInput = input.playerInput;
        return playerInput == null
            || !(playerInput.forward()
                || playerInput.backward()
                || playerInput.left()
                || playerInput.right());
    }

    private static int resolvePlayerHandlerSlot(
        ScreenHandler handler,
        int playerInventoryIndex
    ) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory
                && slot.getIndex() == playerInventoryIndex) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMeteorOffhandActive() {
        meteordevelopment.meteorclient.systems.modules.Module offhand =
            meteordevelopment.meteorclient.systems.modules.Modules.get().get(
                meteordevelopment.meteorclient.systems.modules.combat.Offhand.class
            );
        return offhand != null && offhand.isActive();
    }

    private enum SwapPhase {
        Idle,
        NeutralArmed,
        NeutralApplied,
        NeutralReady
    }

    private final class NeutralInput extends Input {
        final Input delegate;

        NeutralInput(Input delegate) {
            this.delegate = delegate;
            setState(delegate.playerInput, delegate.getMovementInput());
        }

        @Override
        public void tick() {
            delegate.tick();
            PlayerInput original = delegate.playerInput == null
                ? PlayerInput.DEFAULT
                : delegate.playerInput;
            setState(original, delegate.getMovementInput());

            if (swapPhase != SwapPhase.NeutralArmed
                || mc.player == null
                || mc.player.input != this) {
                return;
            }

            PlayerInput neutral = new PlayerInput(
                false,
                false,
                false,
                false,
                false,
                original.sneak(),
                false
            );
            setState(neutral, Vec2f.ZERO);
            mc.player.setSprinting(false);
            neutralPlayer = mc.player;
            swapPhase = SwapPhase.NeutralApplied;
        }

        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }
}
