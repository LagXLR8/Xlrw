package com.nnpg.alice.premium;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

final class OrderDropperLogic {
    static final int ITEMS_PER_STACK = 64;
    private static final Pattern OUT_OF_STOCK_PATTERN = Pattern.compile(
        "\\bkhong\\s+co\\s+vat\\s+pham(?:\\s+nao)?\\s+de\\s+nhan\\b"
    );

    private OrderDropperLogic() {
    }

    static boolean isOutOfStockMessage(String message) {
        if (message == null || message.isBlank()) return false;
        String normalized = Normalizer.normalize(
            message.toLowerCase(Locale.ROOT),
            Normalizer.Form.NFD
        ).replaceAll("\\p{M}+", "").replace('\u0111', 'd');
        return OUT_OF_STOCK_PATTERN.matcher(normalized).find();
    }

    static BatchTotals totalsForBatch(boolean fortyFiveStacks) {
        long stacks = fortyFiveStacks ? 45L : 1L;
        return new BatchTotals(stacks, stacks * ITEMS_PER_STACK);
    }

    static boolean isWithinDropRadius(double squaredDistance) {
        return squaredDistance <= 4.0;
    }

    static double progressPercent(long current, long target) {
        if (target <= 0) return 100.0;
        return Math.min(100.0, Math.max(0.0, current * 100.0 / target));
    }

    static int findFirstAvailableSlot(
        int firstSlot,
        int lastSlot,
        Set<Integer> exhaustedSlots,
        IntPredicate matches
    ) {
        for (int slot = firstSlot; slot <= lastSlot; slot++) {
            if (exhaustedSlots.contains(slot)) continue;
            if (matches.test(slot)) return slot;
        }
        return -1;
    }

    static boolean hasEntityDelta(
        Map<Integer, Integer> baseline,
        Map<Integer, Integer> current
    ) {
        for (Map.Entry<Integer, Integer> entry : current.entrySet()) {
            Integer previousCount = baseline.get(entry.getKey());
            if (previousCount == null || entry.getValue() > previousCount) return true;
        }
        return false;
    }

    record BatchTotals(long stacks, long items) {
    }

    static final class TickDelay {
        private int remaining;

        void arm(int ticks) {
            remaining = Math.max(0, ticks);
        }

        void clear() {
            remaining = 0;
        }

        boolean tick() {
            if (remaining <= 0) return true;
            remaining--;
            return false;
        }
    }

    static final class ConfirmationGate {
        private boolean awaiting;

        void open() {
            awaiting = true;
        }

        void clear() {
            awaiting = false;
        }

        boolean confirm(boolean matchingItem, boolean withinRadius) {
            if (!awaiting || !matchingItem || !withinRadius) return false;
            awaiting = false;
            return true;
        }

        boolean isAwaiting() {
            return awaiting;
        }
    }
}
