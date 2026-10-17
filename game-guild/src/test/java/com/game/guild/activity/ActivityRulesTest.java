package com.game.guild.activity;

import static com.game.guild.activity.ActivityRules.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssetBundle;
import com.game.guild.rules.GuildTips;
import com.game.proto.guild.GuildActivityState;
import com.game.proto.guild.GuildActivityType;
import com.game.proto.guild.GuildTrialLobbyView;
import com.game.table.ConfigTables;
import com.game.table.GuildActivityTable;
import com.game.table.GuildRuleTable;
import com.game.table.RewardTable;
import com.game.table.Rewardreward;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** 规则输入全显式；切日、幂等资金锁存、配表拒启与客户端视图逐项对齐 Go 活动规则。 */
class ActivityRulesTest {
    private static final long NOW = Instant.parse("2026-10-16T01:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000;
    private static final IntFunction<RewardTable> REWARDS = id -> id == 1 ? reward(9, 2, 9, 3) : null;

    private static GuildRuleTable rule() {
        return GuildRuleTable.newBuilder()
                .setId(1)
                .setActivityJoinMinHours(2)
                .setReunionMinOnlineMembers(3)
                .setTrialInviteTtlSeconds(30)
                .setTrialInviteCooldownSeconds(10)
                .build();
    }

    private static GuildActivityTable row(int type) {
        GuildActivityTable.Builder b = GuildActivityTable.newBuilder()
                                               .setId(type)
                                               .setName("活动" + type)
                                               .setType(type)
                                               .setEnabled(true)
                                               .setMinGuildLevel(1)
                                               .setPersonalContribution(10)
                                               .setGuildFunds(100)
                                               .setGuildThreshold(3)
                                               .setRewardId(1)
                                               .setDailyLimit(1);
        if (type == TYPE_TRIAL)
            b.setDungeonId(7).setTeamSizeMin(2).setTeamSizeMax(5);
        return b.build();
    }

    private static RewardTable reward(int... pairs) {
        RewardTable.Builder r = RewardTable.newBuilder().setId(1);
        for (int i = 0; i < pairs.length; i += 2) {
            r.addReward(Rewardreward.newBuilder().setRewardItem(pairs[i]).setRewardCount(pairs[i + 1]));
        }
        return r.build();
    }

    private static void validateRows(List<GuildActivityTable> rows, GuildRuleTable rule) {
        validate(rows, rule, id -> id == 1, REWARDS, id -> id == 9, id -> id == 7);
    }

    @Test
    void 类型和开放状态与客户端契约一致() {
        assertThat(TYPE_LANTERN).isEqualTo(GuildActivityType.GUILD_ACTIVITY_TYPE_LANTERN_VALUE);
        assertThat(TYPE_REUNION).isEqualTo(GuildActivityType.GUILD_ACTIVITY_TYPE_REUNION_VALUE);
        assertThat(TYPE_TRIAL).isEqualTo(GuildActivityType.GUILD_ACTIVITY_TYPE_TRIAL_VALUE);
        assertThat(STATE_DISABLED).isEqualTo(GuildActivityState.GUILD_ACTIVITY_STATE_DISABLED_VALUE);
        assertThat(STATE_UPCOMING).isEqualTo(GuildActivityState.GUILD_ACTIVITY_STATE_UPCOMING_VALUE);
        assertThat(STATE_OPEN).isEqualTo(GuildActivityState.GUILD_ACTIVITY_STATE_OPEN_VALUE);
        assertThat(STATE_ENDED).isEqualTo(GuildActivityState.GUILD_ACTIVITY_STATE_ENDED_VALUE);
    }

    @Test
    void 档期左闭右开且缺行或关闭拒绝开放() {
        GuildActivityTable timed = row(TYPE_LANTERN).toBuilder().setStartAtMs(100).setEndAtMs(200).build();
        assertThat(stateOf(timed, 99)).isEqualTo(STATE_UPCOMING);
        assertThat(stateOf(timed, 100)).isEqualTo(STATE_OPEN);
        assertThat(stateOf(timed, 199)).isEqualTo(STATE_OPEN);
        assertThat(stateOf(timed, 200)).isEqualTo(STATE_ENDED);
        assertThat(stateOf(null, NOW)).isEqualTo(STATE_DISABLED);
        assertThat(stateOf(timed.toBuilder().setEnabled(false).build(), 150)).isEqualTo(STATE_DISABLED);
        assertThat(stateOf(row(TYPE_LANTERN), NOW)).isEqualTo(STATE_OPEN);
        assertThat(stateOf(timed.toBuilder().setEndAtMs(0).build(), 150)).isEqualTo(STATE_ENDED);
    }

    @Test
    void 选择按开放最近将开和无符号最小编号且不修改快照() {
        GuildActivityTable upcoming = row(TYPE_LANTERN).toBuilder().setId(5).setStartAtMs(NOW + 1).setEndAtMs(NOW + 10).build();
        GuildActivityTable lowerUpcoming = upcoming.toBuilder().setId(4).build();
        GuildActivityTable later = upcoming.toBuilder().setId(3).setStartAtMs(NOW + 2).build();
        GuildActivityTable high = row(TYPE_LANTERN).toBuilder().setId(-1).build();
        GuildActivityTable low = row(TYPE_LANTERN).toBuilder().setId(2).build();
        List<GuildActivityTable> source = new ArrayList<>(List.of(high, upcoming, row(TYPE_TRIAL), low, row(TYPE_REUNION)));
        List<GuildActivityTable> before = List.copyOf(source);
        assertThat(selectVisible(source, NOW)).extracting(GuildActivityTable::getId).containsExactly(2, 2, 3);
        assertThat(source).isEqualTo(before);
        assertThat(selectVisible(List.of(upcoming, lowerUpcoming, later), NOW)).containsExactly(lowerUpcoming);
        assertThat(pickForWrite(source, TYPE_LANTERN, high.getId(), NOW)).isEmpty();
        assertThat(pickForWrite(source, TYPE_LANTERN, low.getId(), NOW)).contains(low);
        assertThat(pickForWrite(List.of(upcoming), TYPE_LANTERN, 5, NOW)).isEmpty();
    }

    @Test
    void 个人游戏日切换但节日帮会档期保持() {
        long before = Instant.parse("2026-10-15T20:59:59.999Z").toEpochMilli(), after = before + 1;
        GuildActivityTable festival = row(TYPE_LANTERN).toBuilder().setStartAtMs(before).setEndAtMs(after + HOUR).build();
        assertThat(dayKey(before)).isEqualTo(20261015);
        assertThat(dayKey(after)).isEqualTo(20261016);
        assertThat(guildPeriodKey(festival, after)).isEqualTo(20261015);
        assertThat(guildPeriodKey(row(TYPE_TRIAL), after)).isEqualTo(20261016);
        assertThat(guildPeriodKey(row(TYPE_LANTERN), after)).isEqualTo(20261016);
        assertThat(nextResetMs(before)).isEqualTo(after);
        assertThat(nextResetMs(after)).isEqualTo(after + 24 * HOUR);
    }

    @Test
    void 入帮时长含等号且未知未来和无符号极值不会误放行() {
        assertThat(joinedLongEnough(NOW - 2 * HOUR, NOW, 2)).isTrue();
        assertThat(joinedLongEnough(NOW - 2 * HOUR + 1, NOW, 2)).isFalse();
        assertThat(joinedLongEnough(0, NOW, 2)).isFalse();
        assertThat(joinedLongEnough(NOW + 1, NOW, 2)).isFalse();
        assertThat(joinedLongEnough(-2L, -1L, 2)).isFalse();
        assertThat(joinedLongEnough(0, NOW, 0)).isTrue();
    }

    @Test
    void 团圆阈值取规则下限且锁存后无需在线人数() {
        GuildActivityTable reunion = row(TYPE_REUNION).toBuilder().setGuildThreshold(1).build();
        assertThat(reunionThreshold(reunion, rule())).isEqualTo(3);
        assertThat(reunionObserved(0, 0)).isFalse();
        assertThat(reunionOutcome(reunion, Progress.EMPTY, false)).isEmpty();
        assertThat(reunionOutcome(reunion, Progress.EMPTY, true)).contains(new GuildOutcome(true, true));
        assertThat(reunionOutcome(reunion, new Progress(0, NOW, true), false)).contains(new GuildOutcome(false, false));
    }

    @Test
    void 灯会首次达成及改表补资金与历练日上限() {
        GuildActivityTable lantern = row(TYPE_LANTERN);
        assertThat(lanternOutcome(lantern, new Progress(2, 0, false))).isEqualTo(new GuildOutcome(false, false));
        assertThat(lanternOutcome(lantern, new Progress(3, 0, false))).isEqualTo(new GuildOutcome(true, true));
        assertThat(lanternOutcome(lantern, new Progress(4, NOW, true))).isEqualTo(new GuildOutcome(false, false));
        assertThat(lanternOutcome(lantern, new Progress(4, NOW, false))).isEqualTo(new GuildOutcome(false, true));
        assertThat(trialFundsCounted(row(TYPE_TRIAL), 2)).isTrue();
        assertThat(trialFundsCounted(row(TYPE_TRIAL), 3)).isFalse();
        assertThat(trialFundsCounted(row(TYPE_TRIAL).toBuilder().setGuildFunds(0).build(), 0)).isFalse();
    }

    @Test
    void 资格提示按固定优先级并保留参数() {
        assertThat(blocked(new BlockInput(STATE_ENDED, 1, 2, false, 2, 1, 1, TYPE_REUNION, false, 0, 3)).tipId())
                .isEqualTo(GuildTips.ACTIVITY_NOT_OPEN);
        assertThat(blocked(new BlockInput(STATE_OPEN, 1, 2, false, 2, 1, 1, TYPE_REUNION, false, 0, 3)))
                .isEqualTo(new Blocked(GuildTips.ACTIVITY_LEVEL_TOO_LOW, List.of("2")));
        assertThat(blocked(new BlockInput(STATE_OPEN, 2, 2, false, 2, 1, 1, TYPE_REUNION, false, 0, 3)).tipId())
                .isEqualTo(GuildTips.ACTIVITY_JOIN_TOO_RECENT);
        assertThat(blocked(new BlockInput(STATE_OPEN, 2, 2, true, 2, 1, 1, TYPE_REUNION, false, 0, 3)).tipId())
                .isEqualTo(GuildTips.ACTIVITY_ALREADY_CLAIMED);
        assertThat(blocked(new BlockInput(STATE_OPEN, 2, 2, true, 2, 0, 1, TYPE_REUNION, false, 2, 3)))
                .isEqualTo(new Blocked(GuildTips.ACTIVITY_THRESHOLD_NOT_REACHED, List.of("2", "3")));
        assertThat(blocked(new BlockInput(STATE_OPEN, 2, 2, true, 2, 0, 1, TYPE_REUNION, true, 0, 3)).tipId()).isZero();
    }

    @Test
    void 奖励合并同模板跳零槽并无符号排序() {
        AssetBundle bundle = buildRewardBundle(1, id -> reward(-1, 2, 9, 0, 3, 4, -1, 3));
        assertThat(bundle.getItemsList()).extracting(item -> item.getConfigId()).containsExactly(3, -1);
        assertThat(bundle.getItemsList()).extracting(item -> item.getCount()).containsExactly(4, 5);
        assertThat(rewardItems(bundle)).extracting(item -> item.getItemId()).containsExactly(3, -1);
        assertThat(buildRewardBundle(0, null).getItemsCount()).isZero();
        assertThat(buildRewardBundle(1, id -> reward(9, 0)).getItemsCount()).isZero();
    }

    @Test
    void 奖励缺行无模板溢出与总物品硬预算均失败() {
        assertThatThrownBy(() -> buildRewardBundle(1, null)).hasMessageContaining("查询");
        assertThatThrownBy(() -> buildRewardBundle(1, id -> null)).hasMessageContaining("缺少");
        assertThatThrownBy(() -> buildRewardBundle(1, id -> reward(0, 1))).hasMessageContaining("reward_item=0");
        assertThatThrownBy(() -> buildRewardBundle(1, id -> reward(9, -1, 9, 1))).hasMessageContaining("uint32");
        assertThat(buildRewardBundle(1, id -> reward(9, 4096)).getItems(0).getCount()).isEqualTo(4096);
        assertThatThrownBy(() -> buildRewardBundle(1, id -> reward(9, 4096, 8, 1))).hasMessageContaining("4096");
        var many = RewardTable.newBuilder().setId(1);
        for (int item = 1; item <= 17; item++)
            many.addReward(Rewardreward.newBuilder().setRewardItem(item).setRewardCount(1));
        assertThatThrownBy(() -> buildRewardBundle(1, id -> many.build())).hasMessageContaining("16");
    }

    @Test
    void 视图使用服务端时刻周期和锁存并隐藏不可用历练房间() {
        ViewInput input = new ViewInput(NOW, rule(), 1, NOW - 2 * HOUR, 0, new Progress(0, NOW, true), 1, 99, 2, 27001, 27002,
                GuildTrialLobbyView.newBuilder().setLobbyId(88).build(), false);
        var reunion = buildView(row(TYPE_REUNION), input, REWARDS);
        assertThat(reunion.getServerTimeMs()).isEqualTo(NOW);
        assertThat(reunion.getPeriodKey()).isEqualTo(dayKey(NOW));
        assertThat(reunion.getNextResetMs()).isEqualTo(nextResetMs(NOW));
        assertThat(reunion.getThresholdReached()).isTrue();
        assertThat(reunion.getProgress()).isZero();
        assertThat(reunion.getBlockedTipId()).isZero();
        assertThat(reunion.getMyTrialBattleId()).isZero();
        assertThat(reunion.getMyPendingRewardCount()).isEqualTo(2);
        var trial = buildView(row(TYPE_TRIAL), input, REWARDS);
        assertThat(trial.getThresholdReached()).isFalse();
        assertThat(trial.getFundsGranted()).isFalse();
        assertThat(trial.getMyTrialBattleId()).isEqualTo(99);
        assertThat(trial.getTrialLobby().getLobbyId()).isEqualTo(88);
        var unavailable = buildView(row(TYPE_TRIAL),
                new ViewInput(NOW, rule(), 1, NOW - 2 * HOUR, 0, Progress.EMPTY, 1, 99, 0, 0, 0, input.trialLobby(), true), REWARDS);
        assertThat(unavailable.getStateValue()).isEqualTo(STATE_DISABLED);
        assertThat(unavailable.getBlockedTipId()).isEqualTo(GuildTips.ACTIVITY_NOT_OPEN);
        assertThat(unavailable.getMyTrialBattleId()).isZero();
        assertThat(unavailable.hasTrialLobby()).isFalse();
    }

    @Test
    void 三活动合法配置和相接窗口通过() {
        assertThatCode(() -> validateRows(List.of(row(1), row(2), row(3)), rule())).doesNotThrowAnyException();
        GuildActivityTable a = row(1).toBuilder().setStartAtMs(1).setEndAtMs(10).build();
        GuildActivityTable b = a.toBuilder().setId(4).setStartAtMs(10).setEndAtMs(20).build();
        assertThatCode(() -> validateRows(List.of(a, b), rule())).doesNotThrowAnyException();
        assertThatCode(() -> validateRows(List.of(row(1), row(1).toBuilder().setId(4).setEnabled(false).build()), rule()))
                .doesNotThrowAnyException();
    }

    @Test
    void 启动拒绝坏行窗口活动资格和无符号大数() {
        record Bad(String field, Consumer<GuildActivityTable.Builder> mutate) {}
        List<Bad> bad = List.of(new Bad("id=0", b -> b.setId(0)), new Bad("name", b -> b.setName("  ")), new Bad("type", b -> b.setType(4)),
                new Bad("daily_limit", b -> b.setDailyLimit(0)), new Bad("start_at_ms", b -> b.setStartAtMs(0).setEndAtMs(10)),
                new Bad("start_at_ms", b -> b.setStartAtMs(10).setEndAtMs(0)), new Bad("int64", b -> b.setStartAtMs(-1).setEndAtMs(-2)),
                new Bad("min_guild_level", b -> b.setMinGuildLevel(2)),
                new Bad("personal_contribution", b -> b.setPersonalContribution(-1)), new Bad("guild_funds", b -> b.setGuildFunds(-1)),
                new Bad("reward_id", b -> b.setRewardId(2)), new Bad("dungeon_id", b -> b.setDungeonId(7)),
                new Bad("guild_threshold", b -> b.setGuildThreshold(0)));
        for (Bad c : bad) {
            GuildActivityTable.Builder b = row(1).toBuilder();
            c.mutate().accept(b);
            assertThatThrownBy(() -> validateRows(List.of(b.build()), rule())).as(c.field()).hasMessageContaining(c.field());
        }
        assertThatThrownBy(() -> validateRows(List.of(row(1), row(1)), rule())).hasMessageContaining("重复");
        assertThatThrownBy(() -> validateRows(List.of(row(1).toBuilder().setName("\u00a0\u0085").build()), rule()))
                .hasMessageContaining("name");
        assertThatThrownBy(() -> validateRows(List.of(row(1), row(1).toBuilder().setId(4).build()), rule())).hasMessageContaining("重叠");
        assertThatThrownBy(()
                                   -> validateRows(List.of(row(2).toBuilder().setGuildThreshold(0).build()),
                                           rule().toBuilder().setReunionMinOnlineMembers(0).build()))
                .hasMessageContaining("不能同时为 0");
        assertThatThrownBy(() -> validateRows(List.of(row(2).toBuilder().setGuildThreshold(101).build()), rule()))
                .hasMessageContaining("guild_threshold");
        assertThatThrownBy(() -> validateRows(List.of(row(3).toBuilder().setTeamSizeMin(-1).build()), rule()))
                .hasMessageContaining("team_size");
        assertThatThrownBy(() -> validateRows(List.of(row(3).toBuilder().setDungeonId(8).build()), rule()))
                .hasMessageContaining("dungeon_id");
    }

    @Test
    void 启动拒绝坏规则与奖励引用并校验所有来源接缝() {
        assertThatThrownBy(() -> validateRows(List.of(row(1)), null)).hasMessageContaining("GuildRule");
        assertThatThrownBy(() -> validateRows(List.of(row(1)), rule().toBuilder().setActivityJoinMinHours(721).build()))
                .hasMessageContaining("activity_join_min_hours");
        assertThatThrownBy(() -> validateRows(List.of(row(1)), rule().toBuilder().setTrialInviteTtlSeconds(9).build()))
                .hasMessageContaining("trial_invite_ttl_seconds");
        assertThatThrownBy(() -> validateRows(List.of(row(1)), rule().toBuilder().setTrialInviteCooldownSeconds(601).build()))
                .hasMessageContaining("trial_invite_cooldown_seconds");
        assertThatThrownBy(() -> validateRows(List.of(row(1)), rule().toBuilder().setReunionMinOnlineMembers(101).build()))
                .hasMessageContaining("reunion_min_online_members");
        assertThatThrownBy(() -> validate(List.of(row(1)), rule(), null, REWARDS, id -> true, id -> true)).hasMessageContaining("查询");
        assertThatThrownBy(() -> validate(List.of(row(1)), rule(), id -> true, id -> reward(9, 0), id -> true, id -> true))
                .hasMessageContaining("奖励包为空");
        assertThatThrownBy(() -> validate(List.of(row(1)), rule(), id -> true, REWARDS, id -> false, id -> true))
                .hasMessageContaining("Item");
        RewardTable.Builder many = RewardTable.newBuilder().setId(1);
        for (int id = 1; id <= 17; id++) many.addReward(Rewardreward.newBuilder().setRewardItem(id).setRewardCount(1));
        assertThatThrownBy(() -> validate(List.of(row(1)), rule(), id -> true, id -> many.build(), id -> true, id -> true))
                .hasMessageContaining("16");
    }

    @Test
    void 仓库实际配表可启用活动() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("config-data/tables"))) root = root.getParent();
        assertThat(root).as("仓库配置表目录").isNotNull();
        ConfigTables tables = ConfigTables.load(root.resolve("config-data/tables"));
        assertThatCode(() -> validate(tables)).doesNotThrowAnyException();
    }
}
