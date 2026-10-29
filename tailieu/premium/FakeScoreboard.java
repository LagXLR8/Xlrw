package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayList;
import java.util.List;

public class FakeScoreboard extends AliceModule {
    private static final String SCOREBOARD_NAME = "alice_custom";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hideScoreboard = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-scoreboard")
        .description("an sidebar thay vi hien thi scoreboard gia")
        .defaultValue(false)
        .onChanged(ignored -> safeUpdate())
        .build()
    );

    private final Setting<String> title = textSetting("title", "KingSMP");
    private final Setting<String> money = textSetting("money", "36");
    private final Setting<String> shards = textSetting("shards", "36");
    private final Setting<String> kills = textSetting("kills", "36");
    private final Setting<String> deaths = textSetting("deaths", "36");
    private final Setting<String> playtime = textSetting("playtime", "3d 6h");
    private final Setting<String> team = textSetting("team", "ALICE");

    private final List<String> teamNames = new ArrayList<>();
    private ScoreboardObjective customObjective;
    private ScoreboardObjective originalObjective;
    private long keyStartTime;
    private long lastScoreboardUpdate;

    public FakeScoreboard() {
        super(AliceAddon.ESP_CATEGORY, "fake-scoreboard", "hien thi mot sidebar gia hoac an hoan toan");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        if (mc.world == null || mc.player == null) return;

        originalObjective = mc.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        keyStartTime = System.currentTimeMillis();
        applyMode();
    }

    @Override
    public void onDeactivate() {
        if (mc.world != null) restoreOriginal(mc.world.getScoreboard());
        super.onDeactivate();
    }

    @AliceHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastScoreboardUpdate >= 1_000) {
            applyMode();
            lastScoreboardUpdate = now;
        }
    }

    private Setting<String> textSetting(String name, String defaultValue) {
        return sgGeneral.add(new StringSetting.Builder()
            .name(name)
            .defaultValue(defaultValue)
            .onChanged(ignored -> safeUpdate())
            .build()
        );
    }

    private void safeUpdate() {
        if (isActive() && mc.world != null && mc.player != null) applyMode();
    }

    private void applyMode() {
        if (mc.world == null) return;
        Scoreboard scoreboard = mc.world.getScoreboard();

        if (hideScoreboard.get()) {
            removeCustom(scoreboard);
            scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, null);
        } else {
            updateScoreboard(scoreboard);
        }
    }

    private void updateScoreboard(Scoreboard scoreboard) {
        removeCustom(scoreboard);

        customObjective = scoreboard.addObjective(
            SCOREBOARD_NAME,
            ScoreboardCriterion.DUMMY,
            gradientTitle(title.get()),
            ScoreboardCriterion.RenderType.INTEGER,
            false,
            BlankNumberFormat.INSTANCE
        );
        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, customObjective);

        List<MutableText> entries = generateEntries();
        for (int i = 0; i < entries.size(); i++) {
            String teamName = "alice_fake_" + i;
            teamNames.add(teamName);

            Team existing = scoreboard.getTeam(teamName);
            if (existing != null) scoreboard.removeTeam(existing);

            Team scoreboardTeam = scoreboard.addTeam(teamName);
            scoreboardTeam.setPrefix(entries.get(i));

            String holderName = "\u00a7" + Integer.toHexString(i);
            ScoreHolder holder = ScoreHolder.fromName(holderName);
            scoreboard.removeScore(holder, customObjective);

            ScoreAccess score = scoreboard.getOrCreateScore(holder, customObjective);
            score.setScore(entries.size() - i);
            scoreboard.addScoreHolderToTeam(holderName, scoreboardTeam);
        }
    }

    private void restoreOriginal(Scoreboard scoreboard) {
        removeCustom(scoreboard);
        try {
            scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, originalObjective);
        } catch (RuntimeException ignored) {
            scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, null);
        }
        originalObjective = null;
    }

    private void removeCustom(Scoreboard scoreboard) {
        for (String teamName : teamNames) {
            Team scoreboardTeam = scoreboard.getTeam(teamName);
            if (scoreboardTeam != null) scoreboard.removeTeam(scoreboardTeam);
        }
        teamNames.clear();

        if (customObjective != null) {
            try {
                scoreboard.removeObjective(customObjective);
            } catch (RuntimeException ignored) {
                // The server may already have replaced the objective.
            }
            customObjective = null;
        }
    }

    private List<MutableText> generateEntries() {
        return List.of(
            Text.literal(" "),
            colored("$ ", 0x00FC00).append(colored("Money ", 0xFFFFFF)).append(colored(money.get(), 0x00FC00)),
            colored("★ ", 0xA303F9).append(colored("Shards ", 0xFFFFFF)).append(colored(shards.get(), 0xA303F9)),
            colored("⚔ ", 0xFC0000).append(colored("Kills ", 0xFFFFFF)).append(colored(kills.get(), 0xFC0000)),
            colored("☠ ", 0xF97603).append(colored("Deaths ", 0xFFFFFF)).append(colored(deaths.get(), 0xF97603)),
            colored("⌛ ", 0xFFCC00).append(colored("Key ", 0xFFFFFF)).append(colored(getKeyTimer(), 0xFFCC00)),
            colored("⌚ ", 0xFCE300).append(colored("Played ", 0xFFFFFF)).append(colored(playtime.get(), 0xFCE300)),
            colored("⚑ ", 0xFFFF00).append(colored("Team ", 0xFFFFFF)).append(colored(team.get(), 0xFFFF00)),
            Text.literal(" "),
            bold("KINGMC.VN", 0xFFFF00)
        );
    }

    private String getKeyTimer() {
        long remaining = Math.max(0, 3_599 - (System.currentTimeMillis() - keyStartTime) / 1_000);
        return String.format("%dm %ds", remaining / 60, remaining % 60);
    }

    private MutableText gradientTitle(String value) {
        int[] colors = {0xFFEE00, 0xFFE700, 0xFFDF00, 0xFFD800, 0xFFC900, 0xFFC100, 0xFFBA00};
        MutableText result = Text.empty();
        for (int i = 0; i < value.length(); i++) {
            int color = colors[Math.min(i, colors.length - 1)];
            result.append(Text.literal(String.valueOf(value.charAt(i)))
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)).withBold(true)));
        }
        return result;
    }

    private static MutableText colored(String value, int rgb) {
        return Text.literal(value).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    private static MutableText bold(String value, int rgb) {
        return Text.literal(value).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true));
    }
}
