package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import com.nnpg.alice.bridge.NameProtectionBridge;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NameProtectV2 extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hideOwnName = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-own-name")
        .description("thay the ten cua ban o moi noi tren man hinh")
        .defaultValue(true)
        .onChanged(ignored -> rebuildReplacements())
        .build()
    );

    private final Setting<String> ownAlias = sgGeneral.add(new StringSetting.Builder()
        .name("own-alias")
        .description("ten hien thi thay the cho ten cua ban")
        .defaultValue("Protected")
        .visible(hideOwnName::get)
        .onChanged(ignored -> rebuildReplacements())
        .build()
    );

    private final Setting<Boolean> hideAllNames = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-all-names")
        .description("an ten cua tat ca nguoi choi khac tren HUD, chat, tab, scoreboard, ESP va Nametags")
        .defaultValue(false)
        .onChanged(ignored -> rebuildReplacements())
        .build()
    );

    private final Setting<String> otherAlias = sgGeneral.add(new StringSetting.Builder()
        .name("other-alias")
        .description("tien to dung cho ten nguoi choi khac da bi an")
        .defaultValue("Player")
        .visible(hideAllNames::get)
        .onChanged(ignored -> rebuildReplacements())
        .build()
    );

    private volatile List<Map.Entry<String, String>> replacements = List.of();
    private NameSources lastSources;

    public NameProtectV2() {
        super(AliceAddon.CATEGORY, "nameprotectv2", "an ten nguoi choi trong text cua game va meteor");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        rebuildReplacements();
        NameProtectionBridge.install(this, this::protect);
    }

    @Override
    public void onDeactivate() {
        NameProtectionBridge.uninstall(this);
        replacements = List.of();
        lastSources = null;
        super.onDeactivate();
    }

    @AliceHandler
    private void onTick(TickEvent.Post event) {
        rebuildReplacements();
    }

    private void rebuildReplacements() {
        if (!isActive()) return;

        NameSources sources = currentSources();
        if (sources.equals(lastSources)) return;

        Map<String, String> next = new LinkedHashMap<>();
        if (sources.hideOwnName() && sources.ownName() != null && !sources.ownName().isBlank()) {
            next.put(sources.ownName(), sources.ownAlias());
        }

        if (sources.hideAllNames()) {
            for (int i = 0; i < sources.otherNames().size(); i++) {
                next.put(sources.otherNames().get(i), sources.otherAlias() + (i + 1));
            }
        }

        List<Map.Entry<String, String>> snapshot = new ArrayList<>(next.entrySet());
        snapshot.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed());
        replacements = List.copyOf(snapshot);
        lastSources = sources;
    }

    private NameSources currentSources() {
        String ownName = mc.getSession().getUsername();
        List<String> otherNames = List.of();

        if (hideAllNames.get() && mc.getNetworkHandler() != null) {
            otherNames = mc.getNetworkHandler().getPlayerList().stream()
                .map(PlayerListEntry::getProfile)
                .map(profile -> profile.name())
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> !name.equalsIgnoreCase(ownName))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        }

        return new NameSources(
            hideOwnName.get(),
            ownName,
            safeAlias(ownAlias.get(), "Protected"),
            hideAllNames.get(),
            otherNames,
            safeAlias(otherAlias.get(), "Player")
        );
    }

    private String protect(String text) {
        return NameProtectLogic.protect(text, replacements);
    }

    private static String safeAlias(String alias, String fallback) {
        return alias == null || alias.isBlank() ? fallback : alias;
    }

    private record NameSources(
        boolean hideOwnName,
        String ownName,
        String ownAlias,
        boolean hideAllNames,
        List<String> otherNames,
        String otherAlias
    ) {
    }
}
