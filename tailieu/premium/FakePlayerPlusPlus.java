package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerManager;
import net.minecraft.util.math.Vec3d;

/**
 * Spawns one client-only player for aura and damage testing.
 *
 * <p>The entity is registered with Meteor's FakePlayerManager and is also
 * inserted into ClientWorld by FakePlayerEntity. This lets both Meteor's
 * TargetUtils users and ALICE auras which scan world players select the same
 * entity without any aura-specific target injection.</p>
 */
public final class FakePlayerPlusPlus extends AliceModule {
    private static final String DEFAULT_NAME = "ALICE_Test";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> playerName = sgGeneral.add(new StringSetting.Builder()
        .name("name")
        .description("ten cua fake player")
        .defaultValue(DEFAULT_NAME)
        .build());

    private final Setting<Integer> health = sgGeneral.add(new IntSetting.Builder()
        .name("health")
        .description("mau cua fake player")
        .defaultValue(20)
        .range(1, 100)
        .sliderRange(1, 100)
        .build());

    private final Setting<Boolean> copyInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("copy-inventory")
        .description("copy armor va inventory hien tai sang fake player")
        .defaultValue(true)
        .build());

    private final Setting<Double> spawnDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("spawn-distance")
        .description("khoang cach spawn phia truoc; 0 la dung tai vi tri hien tai")
        .defaultValue(0.0)
        .range(0.0, 8.0)
        .sliderRange(0.0, 8.0)
        .build());

    private FakePlayerEntity fakePlayer;
    private String spawnedName;

    public FakePlayerPlusPlus() {
        super(
            AliceAddon.PVP_CATEGORY,
            "fake-player++",
            "Spawns a client-side player which every player-targeting aura can use for testing."
        );
    }

    @Override
    public void onActivate() {
        super.onActivate();
        spawnOwnedPlayer();
    }

    @Override
    public void onDeactivate() {
        removeOwnedPlayer();
        super.onDeactivate();
    }

    @AliceHandler
    private void onGameLeft(GameLeftEvent event) {
        removeOwnedPlayer();
    }

    @AliceHandler
    private void onGameJoined(GameJoinedEvent event) {
        spawnOwnedPlayer();
    }

    @Override
    public String getInfoString() {
        return fakePlayer == null ? null : spawnedName;
    }

    private void spawnOwnedPlayer() {
        removeOwnedPlayer();
        if (mc.player == null || mc.world == null) return;

        String availableName = availableName(normalizeName(playerName.get()));
        FakePlayerManager.add(availableName, health.get(), copyInventory.get());

        FakePlayerEntity spawned = FakePlayerManager.get(availableName);
        if (spawned == null) return;

        Vec3d position = spawnPosition(
            mc.player.getEntityPos(),
            mc.player.getYaw(),
            spawnDistance.get()
        );
        spawned.setPosition(position.x, position.y, position.z);
        spawned.noHit = false;
        spawned.doNotPush = true;
        spawned.hideWhenInsideCamera = true;

        fakePlayer = spawned;
        spawnedName = availableName;
    }

    private void removeOwnedPlayer() {
        FakePlayerEntity owned = fakePlayer;
        fakePlayer = null;
        spawnedName = null;
        if (owned == null) return;

        FakePlayerManager.getFakePlayers().removeIf(candidate -> candidate == owned);
        if (mc.world != null && !owned.isRemoved()) owned.despawn();
    }

    private static String availableName(String base) {
        if (FakePlayerManager.get(base) == null) return base;

        for (int index = 2; index <= 99; index++) {
            String suffix = "_" + index;
            int prefixLength = Math.min(base.length(), 16 - suffix.length());
            String candidate = base.substring(0, prefixLength) + suffix;
            if (FakePlayerManager.get(candidate) == null) return candidate;
        }

        return normalizeName(
            "ALICE_" + Long.toUnsignedString(System.nanoTime(), 36)
        );
    }

    static String normalizeName(String configured) {
        if (configured == null || configured.isBlank()) return DEFAULT_NAME;

        StringBuilder normalized = new StringBuilder(16);
        for (int index = 0; index < configured.length() && normalized.length() < 16; index++) {
            char character = configured.charAt(index);
            normalized.append(
                character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    ? character
                    : '_'
            );
        }
        return normalized.isEmpty() ? DEFAULT_NAME : normalized.toString();
    }

    static Vec3d spawnPosition(Vec3d origin, float yaw, double distance) {
        double boundedDistance = Math.max(0.0, distance);
        double radians = Math.toRadians(yaw);
        return origin.add(
            -Math.sin(radians) * boundedDistance,
            0.0,
            Math.cos(radians) * boundedDistance
        );
    }
}
