package com.nnpg.alice.premium;

import java.text.Normalizer;
import java.util.Locale;

final class BlazeShopDroperLogic {
    static final long BLAZE_ROD_PRICE = 150L;
    private static final double ONE_MILLION = 1_000_000.0;
    private static final String OUT_OF_MONEY_MARKER = "de mua vat pham nay";

    private BlazeShopDroperLogic() {
    }

    static boolean isOutOfMoneyMessage(String message) {
        if (message == null || message.isBlank()) return false;

        String normalized = Normalizer.normalize(
            message.toLowerCase(Locale.ROOT),
            Normalizer.Form.NFD
        ).replaceAll("\\p{M}+", "").replace('\u0111', 'd');
        return normalized.contains(OUT_OF_MONEY_MARKER);
    }

    static long moneyLimit(double millions) {
        if (!Double.isFinite(millions) || millions <= 0) return 0L;
        double money = millions * ONE_MILLION;
        if (money >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.round(money);
    }

    static long moneyForItems(long items) {
        if (items <= 0) return 0L;
        if (items > Long.MAX_VALUE / BLAZE_ROD_PRICE) return Long.MAX_VALUE;
        return items * BLAZE_ROD_PRICE;
    }

    static double progressPercent(long current, long target) {
        if (target <= 0) return 100.0;
        return Math.min(100.0, Math.max(0.0, current * 100.0 / target));
    }

    static BatchTotals batchTotals(int... stackCounts) {
        int stacks = 0;
        long items = 0L;
        if (stackCounts != null) {
            for (int count : stackCounts) {
                if (count <= 0) continue;
                stacks++;
                items += count;
            }
        }
        return new BatchTotals(stacks, items, moneyForItems(items));
    }

    record BatchTotals(int stacks, long items, long money) {
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

        int remaining() {
            return remaining;
        }
    }
}
