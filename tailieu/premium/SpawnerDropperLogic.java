package com.nnpg.alice.premium;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SpawnerDropperLogic {
    private SpawnerDropperLogic() {
    }

    static PageKind classify(List<SlotView> slots, Set<?> selectedItems) {
        for (SlotView slot : slots) {
            if (slot.count() > 0 && !selectedItems.contains(slot.item())) {
                return PageKind.Junk;
            }
        }
        return PageKind.SelectedOrEmpty;
    }

    static PendingAccounting beginAccounting(List<SlotView> slots) {
        return new PendingAccounting(slots);
    }

    static float nearestEquivalentYaw(float referenceYaw, float targetYaw) {
        float delta = (targetYaw - referenceYaw) % 360.0f;
        if (delta >= 180.0f) delta -= 360.0f;
        if (delta < -180.0f) delta += 360.0f;
        return referenceYaw + delta;
    }

    static CyclePhase nextPhase(CyclePhase phase) {
        return phase == CyclePhase.DropAll
            ? CyclePhase.SellAll
            : CyclePhase.DropAll;
    }

    static GuiAction actionFor(CyclePhase phase, PageKind pageKind) {
        if (pageKind == PageKind.Junk) return GuiAction.StartJunkDrop;
        return phase == CyclePhase.DropAll
            ? GuiAction.DropAll
            : GuiAction.SellAll;
    }

    enum PageKind {
        SelectedOrEmpty,
        Junk
    }

    enum CyclePhase {
        DropAll,
        SellAll
    }

    enum GuiAction {
        DropAll,
        SellAll,
        StartJunkDrop
    }

    record SlotView(int slot, Object item, int count) {
    }

    record ConfirmedDelta(long items, long stacks) {
        static final ConfirmedDelta NONE = new ConfirmedDelta(0L, 0L);
    }

    static final class PendingAccounting {
        private final Map<Integer, SlotView> beforeBySlot = new HashMap<>();
        private final Map<Integer, Integer> countedDecreaseBySlot = new HashMap<>();
        private final Set<Integer> countedStackSlots = new HashSet<>();

        private PendingAccounting(List<SlotView> before) {
            for (SlotView slot : before) {
                if (slot.count() > 0) beforeBySlot.put(slot.slot(), slot);
            }
        }

        ConfirmedDelta reconcile(List<SlotView> current) {
            Map<Integer, SlotView> currentBySlot = new HashMap<>();
            for (SlotView slot : current) currentBySlot.put(slot.slot(), slot);

            long newlyConfirmedItems = 0L;
            long newlyConfirmedStacks = 0L;
            for (SlotView before : beforeBySlot.values()) {
                SlotView now = currentBySlot.get(before.slot());
                int remaining = now != null && now.item() == before.item()
                    ? Math.max(0, now.count())
                    : 0;
                int confirmedDecrease = Math.max(
                    0,
                    Math.min(before.count(), before.count() - remaining)
                );
                int alreadyCounted = countedDecreaseBySlot.getOrDefault(
                    before.slot(),
                    0
                );
                if (confirmedDecrease <= alreadyCounted) continue;

                newlyConfirmedItems += confirmedDecrease - alreadyCounted;
                countedDecreaseBySlot.put(before.slot(), confirmedDecrease);
                if (countedStackSlots.add(before.slot())) newlyConfirmedStacks++;
            }

            return newlyConfirmedItems == 0L && newlyConfirmedStacks == 0L
                ? ConfirmedDelta.NONE
                : new ConfirmedDelta(newlyConfirmedItems, newlyConfirmedStacks);
        }

        ConfirmedDelta reconcileSlot(SlotView current) {
            SlotView before = beforeBySlot.get(current.slot());
            if (before == null) return ConfirmedDelta.NONE;

            int remaining = current.item() == before.item()
                ? Math.max(0, current.count())
                : 0;
            int confirmedDecrease = Math.max(
                0,
                Math.min(before.count(), before.count() - remaining)
            );
            int alreadyCounted = countedDecreaseBySlot.getOrDefault(
                before.slot(),
                0
            );
            if (confirmedDecrease <= alreadyCounted) {
                return ConfirmedDelta.NONE;
            }

            countedDecreaseBySlot.put(before.slot(), confirmedDecrease);
            long stacks = countedStackSlots.add(before.slot()) ? 1L : 0L;
            return new ConfirmedDelta(
                confirmedDecrease - alreadyCounted,
                stacks
            );
        }
    }
}
