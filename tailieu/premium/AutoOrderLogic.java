package com.nnpg.alice.premium;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AutoOrderLogic {
    private static final Pattern PROGRESS_PATTERN =
        Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern MONEY_PATTERN = Pattern.compile(
        "(?i)\\$\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMB]?)"
    );
    private static final Pattern DELIVERY_PATTERN = Pattern.compile(
        "(?i)ban da giao\\s+(\\d+)\\b.*?nhan duoc\\s*:?\\s*\\+\\s*\\$\\s*"
            + "([0-9]+(?:\\.[0-9]+)?)\\s*([KMB]?)"
    );

    private AutoOrderLogic() {
    }

    static String commandName(String registryPath) {
        if (registryPath == null) return "";
        int namespaceSeparator = registryPath.indexOf(':');
        String path = namespaceSeparator >= 0
            ? registryPath.substring(namespaceSeparator + 1)
            : registryPath;
        return path.replace('_', ' ').trim();
    }

    static int remaining(List<String> loreLines) {
        if (loreLines == null) return -1;
        for (String line : loreLines) {
            Matcher matcher = PROGRESS_PATTERN.matcher(line);
            if (!matcher.find()) continue;
            try {
                int done = Integer.parseInt(matcher.group(1));
                int total = Integer.parseInt(matcher.group(2));
                return Math.max(0, total - done);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    static double pricePerItem(List<String> loreLines) {
        if (loreLines == null) return 0D;
        for (String line : loreLines) {
            Matcher matcher = MONEY_PATTERN.matcher(line);
            if (matcher.find()) {
                return compactNumber(matcher.group(1), matcher.group(2));
            }
        }
        return 0D;
    }

    static int bestSlot(List<Candidate> candidates) {
        int bestSlot = -1;
        double bestPrice = -1D;
        for (Candidate candidate : candidates) {
            if (candidate.remaining() < 64 || candidate.pricePerItem() <= bestPrice) {
                continue;
            }
            bestSlot = candidate.slot();
            bestPrice = candidate.pricePerItem();
        }
        return bestSlot;
    }

    static Optional<Delivery> parseDelivery(String message) {
        if (message == null || message.isBlank()) return Optional.empty();
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .replace('Đ', 'D')
            .replace('đ', 'd')
            .toLowerCase(Locale.ROOT);
        Matcher matcher = DELIVERY_PATTERN.matcher(normalized);
        if (!matcher.find()) return Optional.empty();
        try {
            int items = Integer.parseInt(matcher.group(1));
            double money = compactNumber(matcher.group(2), matcher.group(3));
            return Optional.of(new Delivery(items, money));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    static double compactNumber(String number, String suffix) {
        double value = Double.parseDouble(number);
        return switch (suffix == null ? "" : suffix.toUpperCase(Locale.ROOT)) {
            case "K" -> value * 1_000D;
            case "M" -> value * 1_000_000D;
            case "B" -> value * 1_000_000_000D;
            default -> value;
        };
    }

    record Candidate(int slot, int remaining, double pricePerItem) {
    }

    record Delivery(int items, double money) {
    }

    static final class StackCounter {
        private long stacks;
        private int remainder;

        void addConfirmedItems(int items) {
            if (items <= 0) return;
            long combined = (long) remainder + items;
            stacks += combined / 64L;
            remainder = (int) (combined % 64L);
        }

        long stacks() {
            return stacks;
        }

        int remainder() {
            return remainder;
        }

        void reset() {
            stacks = 0L;
            remainder = 0;
        }
    }

    static final class MinuteSchedule {
        private long nextAtMs;

        void reset(long nowMs, int minutes) {
            nextAtMs = nowMs + Math.max(1, minutes) * 60_000L;
        }

        boolean takeIfDue(long nowMs, int minutes) {
            if (nextAtMs <= 0L) reset(nowMs, minutes);
            if (nowMs < nextAtMs) return false;
            reset(nowMs, minutes);
            return true;
        }
    }

    static final class ConfirmedTransfer {
        private final Map<Integer, Integer> beforeBySlot;
        private final Map<Integer, Integer> countedBySlot = new HashMap<>();
        private final Set<Integer> observedSlots = new HashSet<>();

        ConfirmedTransfer(Map<Integer, Integer> beforeBySlot) {
            this.beforeBySlot = new HashMap<>(beforeBySlot);
        }

        int reconcileSlot(int slot, int currentCount) {
            Integer before = beforeBySlot.get(slot);
            if (before == null) return 0;
            observedSlots.add(slot);

            int confirmedDecrease = Math.max(
                0,
                Math.min(before, before - Math.max(0, currentCount))
            );
            int counted = countedBySlot.getOrDefault(slot, 0);
            if (confirmedDecrease <= counted) return 0;
            countedBySlot.put(slot, confirmedDecrease);
            return confirmedDecrease - counted;
        }

        int reconcileAll(Map<Integer, Integer> currentBySlot) {
            int confirmed = 0;
            for (int slot : beforeBySlot.keySet()) {
                confirmed += reconcileSlot(
                    slot,
                    currentBySlot.getOrDefault(slot, 0)
                );
            }
            return confirmed;
        }

        boolean hasSlot(int slot) {
            return beforeBySlot.containsKey(slot);
        }

        Set<Integer> slots() {
            return Set.copyOf(beforeBySlot.keySet());
        }

        boolean allObserved() {
            return observedSlots.containsAll(beforeBySlot.keySet());
        }
    }
}
