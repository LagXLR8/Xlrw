package com.nnpg.alice.premium;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AutoTotemContractTest {
    private static final Path AUTO_TOTEM_SOURCE = Path.of(
        "src/main/java/com/nnpg/alice/premium/AutoTotem.java"
    );
    private static final Path EASY_PLACE_SOURCE = Path.of(
        "src/main/java/com/nnpg/alice/premium/EasyPlace.java"
    );
    private static final Path GRIM_HOTBAR_REFILL_SOURCE = Path.of(
        "src/main/java/com/nnpg/alice/premium/GrimHotbarRefill.java"
    );
    private static final Path GRIM_COORDINATOR_SOURCE = Path.of(
        "src/main/java/com/nnpg/alice/premium/GrimCombatCoordinator.java"
    );

    private AutoTotemContractTest() {
    }

    public static void main(String[] args) throws Exception {
        String autoTotem = Files.readString(AUTO_TOTEM_SOURCE);
        String easyPlace = Files.readString(EASY_PLACE_SOURCE);
        String grimHotbarRefill = Files.readString(GRIM_HOTBAR_REFILL_SOURCE);
        String grimCoordinator = Files.readString(GRIM_COORDINATOR_SOURCE);

        verifyNeutralMovementWindow(autoTotem);
        verifyUniformInventoryPath(autoTotem, easyPlace, grimHotbarRefill);
        verifyOffhandSwap(autoTotem);
        verifyPlusMode(autoTotem, grimHotbarRefill);
        verifyConditionalMioFastPath(autoTotem, grimCoordinator);
        verifyEasyPlaceCoordination(autoTotem, easyPlace);
        verifyEmergencyRefillPriority(autoTotem, grimHotbarRefill);
        System.out.println(
            "[ALICE] AutoTotem conditional Mio/neutral contract passed."
        );
    }

    private static void verifyNeutralMovementWindow(String source) {
        String input = section(
            source,
            "private final class TotemInput extends Input",
            "private enum SwapPhase"
        );
        require(
            source.contains("PlayerTickMovementEvent")
                && source.contains("SendMovementPacketsEvent.Post")
                && source.contains("TickEvent.Pre"),
            "AutoTotem must serialize neutral input, movement completion and ClickSlot across the vanilla tick boundary"
        );
        require(
            input.contains("delegate.tick()")
                && input.contains("new PlayerInput(")
                && input.contains("original.sneak()")
                && input.contains("setState(neutral, Vec2f.ZERO)")
                && input.contains("mc.player.setSprinting(false)")
                && input.contains("SwapPhase.NeutralApplied"),
            "AutoTotem must emit one zero-direction, zero-jump, non-sprinting movement cycle while preserving sneak"
        );

        String movementPost = section(
            source,
            "private void onSendMovementPacketsPost(",
            "private void onPacketReceive("
        );
        require(
            movementPost.contains("hasSafeInventoryClickInput()")
                && movementPost.contains("restoreTotemInput()")
                && movementPost.contains("SwapPhase.NeutralReady"),
            "AutoTotem must validate and restore the neutral wrapper only after vanilla movement packets complete"
        );
        require(
            !source.contains("PlayerInputC2SPacket")
                && !source.contains("ClientCommandC2SPacket")
                && !source.contains("setVelocity("),
            "AutoTotem must rely on vanilla movement emission instead of manual movement packets or velocity cancellation"
        );
    }

    private static void verifyUniformInventoryPath(
        String autoTotem,
        String easyPlace,
        String grimHotbarRefill
    ) {
        String tickPre = section(
            autoTotem,
            "private void onTick(TickEvent.Pre event)",
            "private void armOwnNeutralInventoryTick()"
        );
        String status = section(
            autoTotem,
            "private void handlePlusStatus(",
            "private void queuePlusInventorySettlement("
        );
        require(
            tickPre.contains("swapPhase == SwapPhase.NeutralReady")
                && !tickPre.contains(
                    "EasyPlace.ExternalInventoryWindowState.Ready"
                )
                && !tickPre.contains(
                    "EasyPlace.ExternalInventoryWindowState.Unavailable"
                )
                && !tickPre.contains("EasyPlace.requestExternalInventoryWindow("),
            "AutoTotem now works independently in every GUI without EasyPlace coordination"
        );
        require(
            status.contains("beginPlusPopWindow();")
                && status.contains("plusReserveRefill.cancel();")
                && status.contains("if (tryQueuedMioSwap()) return;")
                && status.contains("armOwnNeutralInventoryTick();"),
            "Status 35 must try the conditional Mio path before arming the neutral fallback"
        );
        require(
            !autoTotem.contains(".name(\"plusplus\")")
                && !autoTotem.contains("tryPlusHotbarSwap")
                && !autoTotem.contains("swapHotbarItemToOffhand")
                && !autoTotem.contains("SWAP_ITEM_WITH_OFFHAND"),
            "AutoTotem must not restore the raw F or held-slot swap paths"
        );

        require(
            grimHotbarRefill.contains("Result requestExact(")
                && grimHotbarRefill.contains("private boolean acquireLocalWindow("),
            "GrimHotbarRefill must retain the requestExact method and local window acquisition"
        );
    }

    private static void verifyOffhandSwap(String source) {
        String swap = section(
            source,
            "private boolean swapTotemToOffhand(int totemSlotIndex)",
            "private boolean isPlayerInventoryContext()"
        );
        require(
            count(source, "clickSlot(") == 1
                && count(source, "swapTotemToOffhand(") == 4
                && swap.contains("resolvePlayerHandlerSlot(totemSlotIndex)")
                && swap.contains("handler.syncId")
                && swap.contains("OFFHAND_SWAP_BUTTON")
                && swap.contains("SlotActionType.SWAP"),
            "Mio and neutral paths must share one exact handler-slot SWAP implementation"
        );
        require(
            source.contains("private static final int OFFHAND_SWAP_BUTTON = 40")
                && source.contains("totemSlotIndex >= 36")
                && source.contains("source.inventory != mc.player.getInventory()")
                && source.contains("source.getIndex() != totemSlotIndex")
                && source.contains("source.getIndex() == playerInventoryIndex")
                && !source.contains("SlotActionType.PICKUP")
                && !source.contains("SlotActionType.QUICK_MOVE"),
            "The single SWAP must source an exact player-owned slot and use vanilla offhand button 40"
        );
        require(
            source.contains("Offhand.isModuleActive()")
                && source.contains("\"auto-totem++\""),
            "Auto Totem++ must remain registered and yield to Meteor's full Offhand module"
        );
    }

    private static void verifyPlusMode(
        String source,
        String grimHotbarRefill
    ) {
        require(
            source.contains(".name(\"plus\")")
                && source.contains(".defaultValue(true)")
                && source.contains("EntityStatusS2CPacket")
                && source.contains("packet.getStatus() == 35")
                && source.contains("beginPlusPopWindow()")
                && source.contains("shouldPrearmPlusNeutral()"),
            "Plus must remain default-on and prioritize an authoritative self status-35 refill"
        );
        require(
            source.contains("ScreenHandlerSlotUpdateS2CPacket")
                && source.contains("InventoryS2CPacket")
                && source.contains("PLAYER_OFFHAND_SLOT = 45")
                && source.contains("queuePlusCorrectionRetry(")
                && source.contains("getRevision() >= revision")
                && source.contains("MAX_PLUS_SWAP_ATTEMPTS = 4"),
            "Plus must retain bounded retries driven by settled authoritative inventory corrections"
        );
        require(
            source.contains(".name(\"plus-slot\")")
                && source.contains(".defaultValue(9)")
                && source.contains("new GrimHotbarRefill(this)")
                && source.contains("plusReserveRefill.requestExact(")
                && source.contains("plusReserveRefill.onPlayerTickMovement()")
                && source.contains("plusReserveRefill.onSendMovementPacketsPost()")
                && grimHotbarRefill.contains("Result requestExact(")
                && grimHotbarRefill.contains(
                    "consumeCompletedDestinationHotbarSlot()"
                ),
            "Plus must retain its exact guarded hotbar reserve through GrimHotbarRefill"
        );
    }

    private static void verifyConditionalMioFastPath(
        String autoTotem,
        String grimCoordinator
    ) {
        String fastPath = section(
            autoTotem,
            "private boolean tryQueuedMioSwap()",
            "private void queuePlusInventorySettlement("
        );
        require(
            fastPath.contains("plusDirectSwapPending")
                && fastPath.contains("plusSwapAttempts != 0")
                && fastPath.contains(
                    "GrimCombatCoordinator.claimCleanExternalInventoryEpoch()"
                )
                && fastPath.contains("swapTotemToOffhand(totemSlotIndex)")
                && fastPath.contains(
                    "GrimCombatCoordinator.releaseCleanExternalInventoryEpoch()"
                ),
            "Only the first pop attempt may claim a clean epoch for Mio's one-packet SWAP"
        );
        require(
            grimCoordinator.contains("instanceof ClientTickEndC2SPacket")
                && grimCoordinator.contains("packetWindowClean = true;")
                && grimCoordinator.contains(
                    "AutoTotem.tryQueuedDirectSwapAfterClientTickEnd();"
                ),
            "A dirty pop must retry immediately after the coordinator observes CLIENT_TICK_END"
        );
        require(
            autoTotem.contains("plusDirectSwapPending = true;")
                && autoTotem.contains("plusDirectSwapPending = false;")
                && !section(
                    autoTotem,
                    "private void retryPlusAfterCorrection(",
                    "private boolean isPlusUrgentRefillPending()"
                ).contains("plusDirectSwapPending = true;"),
            "Correction retries must remain on the neutral path instead of repeating direct swaps"
        );
    }

    private static void verifyEasyPlaceCoordination(
        String autoTotem,
        String easyPlace
    ) {
        require(
            autoTotem.contains("releaseInputForEasyPlace()")
                && easyPlace.contains("AutoTotem.releaseInputForEasyPlace()")
                && easyPlace.contains("PendingExternalInventoryWindow"),
            "AutoTotem and EasyPlace still coordinate on input release, but not on external inventory windows"
        );
    }

    private static void verifyEmergencyRefillPriority(
        String autoTotem,
        String grimHotbarRefill
    ) {
        require(
            autoTotem.contains("isPlayerInventoryContext()")
                && grimHotbarRefill.contains("canUsePlayerInventory()"),
            "AutoTotem and GrimHotbarRefill must validate player inventory context"
        );
        require(
            autoTotem.contains("needsTotemSwap()")
                && autoTotem.contains("!Offhand.isModuleActive()")
                && autoTotem.contains("isPlusUrgentRefillPending()")
                && autoTotem.contains("findTotemSlot() != -1"),
            "AutoTotem priority must apply only when a valid player-owned totem refill is required"
        );
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end < 0 || end <= start) {
            throw new AssertionError(
                "Could not locate source section: " + startMarker
                    + " -> " + endMarker
            );
        }
        return source.substring(start, end);
    }

    private static int count(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
