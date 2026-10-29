package com.nnpg.alice.premium;

final class SetupModuleLogic {
    private SetupModuleLogic() {
    }

    static int requiredTransfer(
        boolean destinationEmpty,
        boolean destinationMatches,
        int destinationCount,
        int configuredCount,
        int maximumStackSize
    ) {
        if (!destinationEmpty && !destinationMatches) return 0;

        int target = Math.max(1, Math.min(configuredCount, maximumStackSize));
        int current = destinationEmpty ? 0 : Math.max(0, destinationCount);
        return Math.max(0, target - current);
    }

    static boolean crafterSlotEnabled(boolean configuredDisabled) {
        return !configuredDisabled;
    }

    static int patternMask(boolean[] disabledSlots) {
        int mask = 0;
        int limit = Math.min(disabledSlots.length, 9);
        for (int slot = 0; slot < limit; slot++) {
            if (disabledSlots[slot]) mask |= 1 << slot;
        }
        return mask;
    }

    static int limitedRenderCount(int loadedCount, int configuredLimit) {
        return Math.min(Math.max(0, loadedCount), Math.max(1, configuredLimit));
    }

    static int pendingCrafterCount(int renderedCount, int setupCount) {
        return Math.max(0, renderedCount - setupCount);
    }

    static float stepWrappedDegrees(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        return current + clamp(delta, -Math.abs(maxStep), Math.abs(maxStep));
    }

    static float stepDegrees(float current, float target, float maxStep) {
        float delta = target - current;
        return current + clamp(delta, -Math.abs(maxStep), Math.abs(maxStep));
    }

    static float compensatedYaw(
        float realYaw,
        float originalSideways,
        float originalForward,
        float remappedSideways,
        float remappedForward
    ) {
        double originalAngle = Math.toDegrees(Math.atan2(
            originalForward,
            originalSideways
        ));
        double remappedAngle = Math.toDegrees(Math.atan2(
            remappedForward,
            remappedSideways
        ));
        return realYaw + wrapDegrees((float) (originalAngle - remappedAngle));
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
