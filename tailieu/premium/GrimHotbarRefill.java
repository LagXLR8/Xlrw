package com.nnpg.alice.premium;

import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * Moves one main-inventory stack into a caller-selected hotbar slot using the
 * same neutral-input window and one-packet SWAP contract as AutoTotem.
 */
final class GrimHotbarRefill {
    private static GrimHotbarRefill activeLocalWindow;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Object owner;

    private PendingRefill pending;
    private int completedDestinationHotbarSlot = -1;
    private boolean inventoryClickSafe;
    private ClientPlayerEntity neutralPlayer;
    private RefillInput refillInput;

    GrimHotbarRefill(Object owner) {
        this.owner = owner;
    }

    static void yieldLocalWindowToAutoTotem() {
        GrimHotbarRefill window = activeLocalWindow;
        if (window != null) window.cancel();
    }

    Result request(Item item, int destinationHotbarSlot) {
        return request(item, destinationHotbarSlot, false);
    }

    Result requestExact(Item item, int destinationHotbarSlot) {
        return request(item, destinationHotbarSlot, true);
    }

    private Result request(
        Item item,
        int destinationHotbarSlot,
        boolean exactDestination
    ) {
        if (GrimCombatCoordinator.emergencyOffhandCorrectionPending()) {
            cancel();
            return Result.Waiting;
        }
        if (mc.player == null || item == null) return Result.Waiting;

        int hotbarSlot = exactDestination
            ? findExactHotbarSlot(item, destinationHotbarSlot)
            : findHotbarSlot(item);
        if (hotbarSlot >= 0) {
            cancel();
            return new Result.Ready(hotbarSlot);
        }

        int sourceInventorySlot = findMainInventorySlot(item);
        if (sourceInventorySlot < 0) {
            cancel();
            return Result.Missing;
        }
        if (destinationHotbarSlot < 0 || destinationHotbarSlot > 8) {
            return Result.Waiting;
        }
        if (!canUsePlayerInventory()) {
            cancel();
            return Result.Waiting;
        }

        if (pending == null
            || pending.item != item
            || pending.destinationHotbarSlot != destinationHotbarSlot
            || pending.exactDestination != exactDestination) {
            cancel();
            pending = new PendingRefill(
                item,
                destinationHotbarSlot,
                exactDestination
            );
        }

        EasyPlace.ExternalInventoryWindowState easyPlaceWindow =
            EasyPlace.requestExternalInventoryWindow(owner);
        if (easyPlaceWindow == EasyPlace.ExternalInventoryWindowState.Ready) {
            performSwap();
            return Result.Waiting;
        }
        if (easyPlaceWindow == EasyPlace.ExternalInventoryWindowState.Waiting) {
            return Result.Waiting;
        }

        if (!acquireLocalWindow()) return Result.Waiting;
        if (pending.phase == Phase.NeutralArmed
            || pending.phase == Phase.NeutralApplied) {
            return Result.Waiting;
        }
        if (pending.phase == Phase.NeutralReady) {
            if (pending.neutralWindowReady) performSwap();
            else pending.phase = Phase.NeutralArmed;
            return Result.Waiting;
        }

        // Always complete a neutral movement cycle before ClickSlot. Reusing
        // the same packet order while already stationary avoids a distinct
        // standing-only inventory path.
        pending.phase = Phase.NeutralArmed;
        return Result.Waiting;
    }

    void onPlayerTickMovement() {
        if (pending == null
            || activeLocalWindow != this
            || pending.phase != Phase.NeutralArmed) {
            return;
        }
        ensureRefillInput();
    }

    void onSendMovementPacketsPost() {
        inventoryClickSafe = hasSafeInventoryClickInput();
        if (pending == null || pending.phase != Phase.NeutralApplied) return;

        boolean safe = inventoryClickSafe
            && mc.player == neutralPlayer
            && mc.player != null
            && mc.player.input == refillInput;
        restoreRefillInput();
        neutralPlayer = null;
        pending.neutralWindowReady = safe;
        pending.phase = safe ? Phase.NeutralReady : Phase.NeutralArmed;
    }

    void cancel() {
        completedDestinationHotbarSlot = -1;
        EasyPlace.releaseExternalInventoryWindow(owner);
        restoreRefillInput();
        neutralPlayer = null;
        pending = null;
        releaseLocalWindow();
    }

    int consumeCompletedDestinationHotbarSlot() {
        int slot = completedDestinationHotbarSlot;
        completedDestinationHotbarSlot = -1;
        return slot;
    }

    private boolean acquireLocalWindow() {
        if (activeLocalWindow != null && activeLocalWindow != this) return false;
        activeLocalWindow = this;
        return true;
    }

    private void releaseLocalWindow() {
        if (activeLocalWindow == this) activeLocalWindow = null;
    }

    private void performSwap() {
        PendingRefill refill = pending;
        if (refill == null
            || GrimCombatCoordinator.emergencyOffhandCorrectionPending()
            || !canUsePlayerInventory()) {
            cancel();
            return;
        }

        // A refill can wait across multiple movement epochs for a Grim-safe
        // inventory window. Revalidate the original request at the last
        // possible moment so a server correction or another module cannot
        // make the queued SWAP overwrite an unrelated hotbar item.
        int satisfiedHotbarSlot = refill.exactDestination
            ? findExactHotbarSlot(refill.item, refill.destinationHotbarSlot)
            : findHotbarSlot(refill.item);
        if (satisfiedHotbarSlot >= 0) {
            cancel();
            completedDestinationHotbarSlot = satisfiedHotbarSlot;
            return;
        }

        int sourceInventorySlot = findMainInventorySlot(refill.item);
        int sourceHandlerSlot = SlotUtils.indexToId(sourceInventorySlot);
        if (sourceInventorySlot < 9
            || sourceInventorySlot >= 36
            || sourceHandlerSlot < 0
            || sourceHandlerSlot >= mc.player.playerScreenHandler.slots.size()
            || !mc.player.playerScreenHandler.getSlot(sourceHandlerSlot)
                .getStack().isOf(refill.item)) {
            cancel();
            return;
        }

        int destinationHotbarSlot = refill.destinationHotbarSlot;
        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            sourceHandlerSlot,
            destinationHotbarSlot,
            SlotActionType.SWAP,
            mc.player
        );
        cancel();
        // A matching vanilla prediction normally produces no S2C slot echo.
        // Tell the owner that its guarded one-packet transfer completed; any
        // later authoritative correction can still revoke this destination.
        completedDestinationHotbarSlot = destinationHotbarSlot;
    }

    private boolean canUsePlayerInventory() {
        return mc.player != null
            && mc.interactionManager != null
            && !mc.player.isCreative()
            && !mc.player.isSpectator()
            && mc.currentScreen == null
            && mc.player.currentScreenHandler
                == mc.player.playerScreenHandler;
    }

    private int findHotbarSlot(Item item) {
        if (mc.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(item)) return slot;
        }
        return -1;
    }

    private int findExactHotbarSlot(Item item, int destinationHotbarSlot) {
        if (mc.player == null
            || destinationHotbarSlot < 0
            || destinationHotbarSlot > 8) {
            return -1;
        }
        return mc.player.getInventory().getStack(destinationHotbarSlot).isOf(item)
            ? destinationHotbarSlot
            : -1;
    }

    private int findMainInventorySlot(Item item) {
        if (mc.player == null) return -1;
        for (int slot = 9; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(item)) return slot;
        }
        return -1;
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

    private void ensureRefillInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.hasVehicle()) return;
        if (refillInput != null) return;

        refillInput = new RefillInput(player.input);
        player.input = refillInput;
    }

    private void restoreRefillInput() {
        ClientPlayerEntity player = mc.player;
        if (player != null && refillInput != null
            && player.input == refillInput) {
            player.input = refillInput.delegate;
        }
        refillInput = null;
    }

    sealed interface Result {
        Result Missing = new Missing();
        Result Waiting = new Waiting();

        record Ready(int hotbarSlot) implements Result {
        }

        final class Missing implements Result {
            private Missing() {
            }
        }

        final class Waiting implements Result {
            private Waiting() {
            }
        }
    }

    private final class RefillInput extends Input {
        private final Input delegate;

        private RefillInput(Input delegate) {
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
            if (pending == null
                || pending.phase != Phase.NeutralArmed
                || activeLocalWindow != GrimHotbarRefill.this
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
            pending.phase = Phase.NeutralApplied;
        }

        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }

    private static final class PendingRefill {
        private final Item item;
        private final int destinationHotbarSlot;
        private final boolean exactDestination;
        private Phase phase = Phase.Queued;
        private boolean neutralWindowReady;

        private PendingRefill(
            Item item,
            int destinationHotbarSlot,
            boolean exactDestination
        ) {
            this.item = item;
            this.destinationHotbarSlot = destinationHotbarSlot;
            this.exactDestination = exactDestination;
        }
    }

    private enum Phase {
        Queued,
        NeutralArmed,
        NeutralApplied,
        NeutralReady
    }
}
