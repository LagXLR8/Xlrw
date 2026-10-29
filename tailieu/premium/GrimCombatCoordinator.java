package com.nnpg.alice.premium;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.DoAttackEvent;
import meteordevelopment.meteorclient.events.entity.player.DoItemUseEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.listeners.IListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes ALICE combat modules into the packet order accepted by Grim.
 *
 * <p>The coordinator is payload-lived rather than module-lived. It observes
 * the server-held slot and the last movement look even while both auras are
 * disabled. Active participants submit one immutable
 * intent at the end of {@link TickEvent.Pre}; at most one intent is executed
 * before the next movement packet. This prevents Kill Aura++ and Crystal
 * Aura++ from independently owning rotation, held-slot cleanup or a conflicting
 * interaction in the same server tick.</p>
 */
public final class GrimCombatCoordinator {
    private static final int FIRST_PRIORITY = 1_000_000;
    private static final int LAST_PRIORITY = -1_000_000;

    private static final int EVENT_TICK_PRE_EARLY = 0;
    private static final int EVENT_TICK_PRE_LATE = 1;
    private static final int EVENT_PACKET_SEND = 2;
    private static final int EVENT_PACKET_SENT = 3;
    private static final int EVENT_PACKET_RECEIVE = 4;
    private static final int EVENT_MOVEMENT_POST = 5;
    private static final int EVENT_ATTACK = 6;
    private static final int EVENT_USE = 7;
    private static final int EVENT_BREAK = 8;
    private static final int EVENT_JOIN = 9;
    private static final int EVENT_LEFT = 10;

    private static final GrimCombatCoordinator INSTANCE =
        new GrimCombatCoordinator();

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final List<Participant> activeParticipants = new ArrayList<>(2);
    private final IListener[] listeners = {
        new CoordinatorListener(TickEvent.Pre.class, FIRST_PRIORITY, EVENT_TICK_PRE_EARLY),
        new CoordinatorListener(TickEvent.Pre.class, LAST_PRIORITY, EVENT_TICK_PRE_LATE),
        new CoordinatorListener(PacketEvent.Send.class, FIRST_PRIORITY, EVENT_PACKET_SEND),
        new CoordinatorListener(PacketEvent.Sent.class, LAST_PRIORITY, EVENT_PACKET_SENT),
        new CoordinatorListener(PacketEvent.Receive.class, LAST_PRIORITY, EVENT_PACKET_RECEIVE),
        new CoordinatorListener(SendMovementPacketsEvent.Post.class, LAST_PRIORITY, EVENT_MOVEMENT_POST),
        new CoordinatorListener(DoAttackEvent.class, FIRST_PRIORITY, EVENT_ATTACK),
        new CoordinatorListener(DoItemUseEvent.class, FIRST_PRIORITY, EVENT_USE),
        new CoordinatorListener(StartBreakingBlockEvent.class, FIRST_PRIORITY, EVENT_BREAK),
        new CoordinatorListener(GameJoinedEvent.class, FIRST_PRIORITY, EVENT_JOIN),
        new CoordinatorListener(GameLeftEvent.class, FIRST_PRIORITY, EVENT_LEFT)
    };

    private boolean installed;
    private Participant preferredParticipant;
    private Participant executingParticipant;
    private Participant silentSlotOwner;
    private Participant lookOwner;

    private boolean managedSlotSend;
    private boolean ownsSilentSlot;
    private boolean pendingSlotRestore;
    private boolean pendingLookRestore;
    private boolean allowPendingItemUse;
    private boolean actionTakenThisEpoch;
    private boolean externalEpochReserved;
    private boolean packetWindowClean;
    private volatile boolean emergencyOffhandCorrectionPending;
    private volatile long emergencyOffhandCorrectionToken;
    private PlayerActionC2SPacket managedOffhandSwapPacket;
    private boolean managedOffhandSwapSent;
    private int emergencyRestoreSlot = -1;
    private boolean forceNextAction;

    private int serverSlot = -1;
    private int lastSentSlot = -1;
    private float serverYaw;
    private float serverPitch;
    private boolean serverLookKnown;

    private GrimCombatCoordinator() {
    }

    public static void install() {
        INSTANCE.installListeners();
    }

    static void activate(Participant participant) {
        INSTANCE.activateParticipant(participant);
    }

    static void deactivate(Participant participant) {
        INSTANCE.deactivateParticipant(participant);
    }

    static boolean selectSilent(Participant participant, int slot) {
        return INSTANCE.selectSilentSlot(participant, slot);
    }

    static void restoreSilent(Participant participant) {
        INSTANCE.restoreSilentSlot(participant);
    }

    static boolean hasSilentSlot(Participant participant) {
        return INSTANCE.silentSlotOwner == participant
            && INSTANCE.ownsSilentSlot;
    }

    static void observeOwnedLook(
        Participant participant,
        float yaw,
        float pitch
    ) {
        INSTANCE.observeParticipantLook(participant, yaw, pitch);
    }

    static boolean claimAction(Participant participant) {
        return INSTANCE.executingParticipant == participant
            && INSTANCE.activeParticipants.contains(participant)
            && !INSTANCE.actionTakenThisEpoch
            && !INSTANCE.participantInteractionBlocked(participant);
    }

    static float serverYaw() {
        return INSTANCE.serverYaw;
    }

    static float serverPitch() {
        return INSTANCE.serverPitch;
    }

    static boolean serverLookKnown() {
        return INSTANCE.serverLookKnown;
    }

    static int serverSlot() {
        return INSTANCE.serverSlot;
    }

    static boolean externalActionAvailable() {
        return !INSTANCE.actionTakenThisEpoch
            && !INSTANCE.interactionBlocked()
            && INSTANCE.executingParticipant == null;
    }

    static boolean cleanInventoryWindowAvailable() {
        return INSTANCE.packetWindowClean;
    }

    static void reserveExternalEpoch() {
        if (externalActionAvailable()) INSTANCE.externalEpochReserved = true;
    }

    static boolean claimCleanExternalInventoryEpoch() {
        return INSTANCE.claimCleanExternalInventoryEpochInternal();
    }

    static void releaseCleanExternalInventoryEpoch() {
        INSTANCE.externalEpochReserved = false;
    }

    /**
     * Uses the vanilla swap-hands action with a pre-staged hotbar item. This
     * path deliberately avoids CLICK_WINDOW, so Grim does not require a
     * neutral movement input while the player is moving or sprinting.
     */
    static boolean swapHotbarItemToOffhand(int hotbarSlot, long token) {
        return INSTANCE.swapHotbarItemToOffhandInternal(hotbarSlot, token);
    }

    static void finishEmergencyOffhandCorrection(long token) {
        if (token <= 0L
            || INSTANCE.emergencyOffhandCorrectionToken != token) {
            return;
        }
        INSTANCE.emergencyOffhandCorrectionPending = false;
        INSTANCE.emergencyOffhandCorrectionToken = 0L;
    }

    static boolean emergencyOffhandCorrectionPending() {
        return INSTANCE.emergencyOffhandCorrectionPending
            || INSTANCE.emergencyRestoreSlot >= 0;
    }

    static void forceAllowNextAction() {
        INSTANCE.forceNextAction = true;
    }

    private void installListeners() {
        if (installed) return;
        installed = true;
        initializeConnectionState();
        for (IListener listener : listeners) {
            MeteorClient.EVENT_BUS.subscribe(listener);
        }
    }

    private void activateParticipant(Participant participant) {
        if (participant == null || activeParticipants.contains(participant)) return;
        activeParticipants.add(participant);
        if (preferredParticipant == null
            || participant.combatPriority() > preferredParticipant.combatPriority()) {
            preferredParticipant = participant;
        }
        initializeSlotIfUnknown();
    }

    private void deactivateParticipant(Participant participant) {
        if (participant == null) return;
        activeParticipants.remove(participant);
        participant.suspendForItemUse();

        if (silentSlotOwner == participant && ownsSilentSlot) {
            pendingSlotRestore = true;
        }
        if (lookOwner == participant
            && serverLookKnown
            && mc.player != null
            && !sameLook(
                serverYaw,
                serverPitch,
                mc.player.getYaw(),
                mc.player.getPitch()
            )) {
            pendingLookRestore = true;
        }

        if (preferredParticipant == participant) {
            preferredParticipant = highestPriorityParticipant();
        }
        if (executingParticipant == participant) executingParticipant = null;
        finishCleanupIfReady();
    }

    private void onTickPreEarly() {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            resetConnectionState();
            return;
        }

        initializeSlotIfUnknown();
        if (emergencyRestoreSlot >= 0) {
            int restoreSlot = emergencyRestoreSlot;
            if (serverSlot == restoreSlot || sendManagedSlot(restoreSlot)) {
                emergencyRestoreSlot = -1;
            }
        }
        if (pendingSlotRestore) restoreOwnedSilentSlot();

        if (pendingLookRestore
            && serverLookKnown
            && sameLook(
                serverYaw,
                serverPitch,
                mc.player.getYaw(),
                mc.player.getPitch()
            )) {
            pendingLookRestore = false;
            lookOwner = null;
        }
        finishCleanupIfReady();
    }

    private void onTickPreLate() {
        if (cleanupInteractionBlocked()
            || actionTakenThisEpoch
            || externalEpochReserved
            || mc.player == null
            || mc.world == null
            || activeParticipants.isEmpty()) {
            return;
        }

        List<Intent> intents = new ArrayList<>(activeParticipants.size());
        for (Participant participant : List.copyOf(activeParticipants)) {
            if (participantInteractionBlocked(participant)) continue;
            try {
                Intent intent = participant.prepareCombatIntent();
                if (intent != null && intent.owner() == participant) intents.add(intent);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
        if (intents.isEmpty()) return;

        Intent selected = null;
        if (preferredParticipant != null) {
            for (Intent intent : intents) {
                if (intent.owner() == preferredParticipant) {
                    selected = intent;
                    break;
                }
            }
        }
        if (selected == null) {
            selected = intents.getFirst();
            for (Intent intent : intents) {
                if (intent.owner().combatPriority()
                    > selected.owner().combatPriority()) {
                    selected = intent;
                }
            }
        }

        executingParticipant = selected.owner();
        try {
            selected.execute();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        } finally {
            executingParticipant = null;
        }
    }

    private boolean selectSilentSlot(Participant participant, int slot) {
        if (executingParticipant != participant
            || !activeParticipants.contains(participant)
            || slot < 0
            || slot > 8
            || mc.player == null
            || mc.getNetworkHandler() == null) {
            return false;
        }

        initializeSlotIfUnknown();
        int visibleSlot = mc.player.getInventory().getSelectedSlot();
        if (serverSlot != slot && !sendManagedSlot(slot)) return false;
        silentSlotOwner = participant;
        ownsSilentSlot = slot != visibleSlot;
        return serverSlot == slot;
    }

    private void restoreSilentSlot(Participant participant) {
        if (silentSlotOwner != participant || !ownsSilentSlot) return;
        restoreOwnedSilentSlot();
    }

    private void restoreOwnedSilentSlot() {
        if (!ownsSilentSlot || mc.player == null || mc.getNetworkHandler() == null) {
            return;
        }

        int visibleSlot = mc.player.getInventory().getSelectedSlot();
        if (serverSlot == visibleSlot || sendManagedSlot(visibleSlot)) {
            ownsSilentSlot = false;
            silentSlotOwner = null;
            pendingSlotRestore = false;
            finishCleanupIfReady();
        }
    }

    private void observeParticipantLook(
        Participant participant,
        float yaw,
        float pitch
    ) {
        if (!activeParticipants.contains(participant)) return;
        serverYaw = yaw;
        serverPitch = pitch;
        serverLookKnown = true;
        lookOwner = participant;
    }

    private void onPacketSend(PacketEvent.Send event) {
        if ((emergencyOffhandCorrectionPending || emergencyRestoreSlot >= 0)
            && isEmergencyBlockedPacket(event.packet)
            && !emergencyCombatPacketAllowed()) {
            event.setCancelled(true);
            return;
        }

        if (event.packet instanceof PlayerInteractItemC2SPacket
            && allowPendingItemUse) {
            allowPendingItemUse = false;
            return;
        }

        if (!isTargetedInteraction(event.packet)) return;
        boolean blocked = executingParticipant == null
            ? interactionBlocked()
            : participantInteractionBlocked(executingParticipant);
        if (blocked || actionTakenThisEpoch) {
            if (forceNextAction) {
                forceNextAction = false;
            } else {
                event.setCancelled(true);
            }
        }
    }

    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet == managedOffhandSwapPacket) {
            managedOffhandSwapSent = true;
            // Exact Grim permits a block/entity interaction after this fully
            // ordered F-swap. Do not spend the interaction budget here; the
            // emergency gate below still limits the follow-up to explicitly
            // compatible participants after held-slot restoration.
            return;
        }

        if (event.packet instanceof UpdateSelectedSlotC2SPacket packet) {
            serverSlot = packet.getSelectedSlot();
            lastSentSlot = packet.getSelectedSlot();
            if (!managedSlotSend) {
                ownsSilentSlot = false;
                silentSlotOwner = null;
                pendingSlotRestore = false;
                finishCleanupIfReady();
            }
            return;
        }

        if (event.packet instanceof ClientTickEndC2SPacket) {
            actionTakenThisEpoch = false;
            externalEpochReserved = false;
            packetWindowClean = true;
            AutoTotem.tryQueuedDirectSwapAfterClientTickEnd();
            return;
        }

        if (isPacketOrderConflict(event.packet)) packetWindowClean = false;

        if (event.packet instanceof PlayerInteractEntityC2SPacket) {
            markActionSent();
            return;
        }
        if (event.packet instanceof PlayerInteractBlockC2SPacket
            || event.packet instanceof PlayerInteractItemC2SPacket
            || isTargetedPlayerAction(event.packet)) {
            markActionSent();
            return;
        }
        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;

        packetWindowClean = false;

        if (packet.changesLook()) {
            serverYaw = packet.getYaw(serverYaw);
            serverPitch = packet.getPitch(serverPitch);
            serverLookKnown = true;
        }
    }

    private void markActionSent() {
        actionTakenThisEpoch = true;
        Participant actionOwner = executingParticipant;
        if (actionOwner != null) preferredParticipant = nextParticipant(actionOwner);
    }

    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof UpdateSelectedSlotS2CPacket packet)) return;
        int slot = packet.slot();
        mc.execute(new Runnable() {
            @Override
            public void run() {
                observeServerSlot(slot);
            }
        });
    }

    private void observeServerSlot(int slot) {
        serverSlot = slot;
        if (slot == emergencyRestoreSlot) emergencyRestoreSlot = -1;
        ownsSilentSlot = false;
        silentSlotOwner = null;
        pendingSlotRestore = false;
        finishCleanupIfReady();
    }

    private void onMovementPacketsPost() {
        if (!pendingLookRestore || mc.player == null || !serverLookKnown) return;
        if (!sameLook(
            serverYaw,
            serverPitch,
            mc.player.getYaw(),
            mc.player.getPitch()
        )) {
            return;
        }

        pendingLookRestore = false;
        lookOwner = null;
        allowPendingItemUse = false;
        finishCleanupIfReady();
    }

    private void cancelAttack(DoAttackEvent event) {
        if (interactionBlocked() || actionTakenThisEpoch) event.setCancelled(true);
    }

    private void cancelUse(DoItemUseEvent event) {
        if (interactionBlocked() || actionTakenThisEpoch) {
            event.setCancelled(true);
            return;
        }
        if (activeParticipants.isEmpty()) return;

        boolean lookMismatch = lookDiffersFromCamera();
        if (!ownsSilentSlot && !lookMismatch) return;

        for (Participant participant : List.copyOf(activeParticipants)) {
            participant.suspendForItemUse();
        }
        if (ownsSilentSlot) {
            pendingSlotRestore = true;
            if (lookMismatch) pendingLookRestore = true;
            event.setCancelled(true);
            return;
        }

        // Preserve exactly the user's original USE_ITEM and its visible
        // yaw/pitch. The following visible movement packet completes the pair
        // required by Grim BadPacketsJ for 1.21+ clients.
        pendingLookRestore = true;
        allowPendingItemUse = true;
    }

    private void cancelBreak(StartBreakingBlockEvent event) {
        if (interactionBlocked() || actionTakenThisEpoch) event.setCancelled(true);
    }

    private boolean sendManagedSlot(int slot) {
        if (slot < 0 || slot > 8 || mc.getNetworkHandler() == null) return false;
        if (serverSlot == slot) return true;

        // BadPacketsA compares held-slot packet history. Bridge an externally
        // forced duplicate instead of repeating the previous packet value.
        if (lastSentSlot == slot) {
            int bridge = bridgeSlot(slot);
            if (!sendOneSlot(bridge) || serverSlot != bridge) return false;
        }
        return sendOneSlot(slot) && serverSlot == slot;
    }

    private boolean swapHotbarItemToOffhandInternal(
        int hotbarSlot,
        long token
    ) {
        if (hotbarSlot < 0
            || hotbarSlot > 8
            || token <= 0L
            || mc.player == null
            || mc.getNetworkHandler() == null
            || !packetWindowClean
            || emergencyOffhandCorrectionPending
            || emergencyRestoreSlot >= 0
            || externalEpochReserved
            || !externalActionAvailable()) {
            return false;
        }

        initializeSlotIfUnknown();
        int restoreSlot = serverSlot;
        if (restoreSlot < 0 || restoreSlot > 8) return false;

        // Reserve the complete packet epoch before emitting either held-slot
        // packet. Aura intents therefore cannot enter between the slot change
        // and the vanilla F-key action.
        externalEpochReserved = true;
        if (serverSlot != hotbarSlot && !sendManagedSlot(hotbarSlot)) {
            actionTakenThisEpoch = true;
            if (serverSlot != restoreSlot && !sendManagedSlot(restoreSlot)) {
                emergencyRestoreSlot = restoreSlot;
            }
            return false;
        }

        PlayerActionC2SPacket swap = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        );
        managedOffhandSwapPacket = swap;
        managedOffhandSwapSent = false;
        try {
            mc.getNetworkHandler().sendPacket(swap);
        } finally {
            managedOffhandSwapPacket = null;
        }

        if (!managedOffhandSwapSent) {
            actionTakenThisEpoch = true;
            if (serverSlot != restoreSlot && !sendManagedSlot(restoreSlot)) {
                emergencyRestoreSlot = restoreSlot;
            }
            return false;
        }

        // Raw SWAP_ITEM_WITH_OFFHAND has no client-side inventory prediction.
        // Keep combat/inventory interactions blocked until AutoTotem observes
        // an authoritative offhand correction from the server.
        emergencyOffhandCorrectionPending = true;
        emergencyOffhandCorrectionToken = token;
        if (serverSlot != restoreSlot && !sendManagedSlot(restoreSlot)) {
            emergencyRestoreSlot = restoreSlot;
        } else {
            // The select -> F -> restore sequence is now complete. Release
            // the reservation so one compatible Crystal Aura interaction can
            // follow before movement; the correction gate remains active for
            // every other participant and external action.
            externalEpochReserved = false;
        }
        return true;
    }

    private boolean claimCleanExternalInventoryEpochInternal() {
        if (!packetWindowClean
            || externalEpochReserved
            || !externalActionAvailable()) {
            return false;
        }

        // A 1.21.2+ client may not send CLICK_WINDOW after movement and before
        // CLIENT_TICK_END (Grim PacketOrderO). Claim only the clean interval
        // after the preceding end-tick and keep Aura/user interactions out of
        // this epoch once AutoTotem emits its Mio-style SWAP.
        externalEpochReserved = true;
        return true;
    }

    private boolean sendOneSlot(int slot) {
        if (slot < 0
            || slot > 8
            || slot == lastSentSlot
            || mc.getNetworkHandler() == null) {
            return false;
        }

        managedSlotSend = true;
        try {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        } finally {
            managedSlotSend = false;
        }
        return serverSlot == slot;
    }

    private void initializeConnectionState() {
        resetConnectionState();
        initializeSlotIfUnknown();
        if (mc.player != null) {
            serverYaw = mc.player.getYaw();
            serverPitch = mc.player.getPitch();
            serverLookKnown = true;
        }
    }

    private void initializeSlotIfUnknown() {
        if (serverSlot >= 0 || mc.player == null) return;
        serverSlot = mc.player.getInventory().getSelectedSlot();
        lastSentSlot = serverSlot;
    }

    private void resetConnectionState() {
        preferredParticipant = highestPriorityParticipant();
        executingParticipant = null;
        silentSlotOwner = null;
        lookOwner = null;
        managedSlotSend = false;
        ownsSilentSlot = false;
        pendingSlotRestore = false;
        pendingLookRestore = false;
        allowPendingItemUse = false;
        actionTakenThisEpoch = false;
        externalEpochReserved = false;
        packetWindowClean = false;
        emergencyOffhandCorrectionPending = false;
        emergencyOffhandCorrectionToken = 0L;
        managedOffhandSwapPacket = null;
        managedOffhandSwapSent = false;
        forceNextAction = false;
        emergencyRestoreSlot = -1;
        serverSlot = -1;
        lastSentSlot = -1;
        serverYaw = 0.0f;
        serverPitch = 0.0f;
        serverLookKnown = false;
    }

    private Participant highestPriorityParticipant() {
        Participant best = null;
        for (Participant participant : activeParticipants) {
            if (best == null || participant.combatPriority() > best.combatPriority()) {
                best = participant;
            }
        }
        return best;
    }

    private Participant nextParticipant(Participant owner) {
        Participant best = null;
        for (Participant participant : activeParticipants) {
            if (participant == owner) continue;
            if (best == null || participant.combatPriority() > best.combatPriority()) {
                best = participant;
            }
        }
        return best == null && activeParticipants.contains(owner) ? owner : best;
    }

    private boolean interactionBlocked() {
        return pendingSlotRestore
            || pendingLookRestore
            || emergencyOffhandCorrectionPending
            || emergencyRestoreSlot >= 0;
    }

    private boolean cleanupInteractionBlocked() {
        return pendingSlotRestore
            || pendingLookRestore
            || emergencyRestoreSlot >= 0;
    }

    private boolean participantInteractionBlocked(Participant participant) {
        return cleanupInteractionBlocked()
            || (emergencyOffhandCorrectionPending
                && (participant == null
                    || !participant.canFollowEmergencyOffhandSwap()));
    }

    private boolean emergencyCombatPacketAllowed() {
        return emergencyOffhandCorrectionPending
            && emergencyRestoreSlot < 0
            && executingParticipant != null
            && executingParticipant.canFollowEmergencyOffhandSwap();
    }

    private boolean lookDiffersFromCamera() {
        return mc.player != null
            && serverLookKnown
            && !sameLook(
                serverYaw,
                serverPitch,
                mc.player.getYaw(),
                mc.player.getPitch()
            );
    }

    private void finishCleanupIfReady() {
        if (pendingSlotRestore || pendingLookRestore) return;
        if (!ownsSilentSlot) silentSlotOwner = null;
        if (lookOwner != null && !activeParticipants.contains(lookOwner)) {
            lookOwner = null;
        }
    }

    static int bridgeSlot(int desiredSlot) {
        return (desiredSlot + 1) % 9;
    }

    static boolean sameLook(
        float firstYaw,
        float firstPitch,
        float secondYaw,
        float secondPitch
    ) {
        return Float.compare(firstYaw, secondYaw) == 0
            && Float.compare(firstPitch, secondPitch) == 0;
    }

    private static boolean isTargetedInteraction(Object packet) {
        return packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof PlayerInteractBlockC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket
            || isTargetedPlayerAction(packet);
    }

    private static boolean isTargetedPlayerAction(Object packet) {
        if (!(packet instanceof PlayerActionC2SPacket action)) return false;
        return action.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
            || action.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
            || action.getAction() == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK
            || action.getAction() == PlayerActionC2SPacket.Action.STAB
            || action.getAction()
                == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND;
    }

    private static boolean isEmergencyBlockedPacket(Object packet) {
        return packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof PlayerInteractBlockC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket
            || packet instanceof PlayerActionC2SPacket
            || packet instanceof PickItemFromBlockC2SPacket
            || packet instanceof PickItemFromEntityC2SPacket
            || packet instanceof HandSwingC2SPacket;
    }

    private static boolean isPacketOrderConflict(Object packet) {
        return packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof PlayerInteractBlockC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket
            || packet instanceof PlayerActionC2SPacket
            || packet instanceof ClickSlotC2SPacket
            || packet instanceof PickItemFromBlockC2SPacket
            || packet instanceof PickItemFromEntityC2SPacket
            || packet instanceof ClientCommandC2SPacket
            || packet instanceof ClientStatusC2SPacket;
    }

    private void dispatch(int eventKind, Object event) {
        switch (eventKind) {
            case EVENT_TICK_PRE_EARLY -> onTickPreEarly();
            case EVENT_TICK_PRE_LATE -> onTickPreLate();
            case EVENT_PACKET_SEND -> onPacketSend((PacketEvent.Send) event);
            case EVENT_PACKET_SENT -> onPacketSent((PacketEvent.Sent) event);
            case EVENT_PACKET_RECEIVE -> onPacketReceive((PacketEvent.Receive) event);
            case EVENT_MOVEMENT_POST -> onMovementPacketsPost();
            case EVENT_ATTACK -> cancelAttack((DoAttackEvent) event);
            case EVENT_USE -> cancelUse((DoItemUseEvent) event);
            case EVENT_BREAK -> cancelBreak((StartBreakingBlockEvent) event);
            case EVENT_JOIN -> initializeConnectionState();
            case EVENT_LEFT -> resetConnectionState();
            default -> throw new IllegalStateException(
                "Unknown combat coordinator event " + eventKind
            );
        }
    }

    interface Participant {
        Intent prepareCombatIntent();

        void suspendForItemUse();

        default boolean canFollowEmergencyOffhandSwap() {
            return false;
        }

        default int combatPriority() {
            return 0;
        }
    }

    interface Intent {
        Participant owner();

        void execute();
    }

    private final class CoordinatorListener implements IListener {
        private final Class<?> target;
        private final int priority;
        private final int eventKind;

        private CoordinatorListener(Class<?> target, int priority, int eventKind) {
            this.target = target;
            this.priority = priority;
            this.eventKind = eventKind;
        }

        @Override
        public void call(Object event) {
            dispatch(eventKind, event);
        }

        @Override
        public Class<?> getTarget() {
            return target;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public boolean isStatic() {
            return false;
        }
    }
}
