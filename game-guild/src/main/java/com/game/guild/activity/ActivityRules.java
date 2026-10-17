package com.game.guild.activity;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetItem;
import com.game.common.time.GameDay;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildTips;
import com.game.proto.guild.GuildActivityView;
import com.game.proto.guild.GuildRewardItem;
import com.game.proto.guild.GuildTrialLobbyView;
import com.game.table.ConfigTables;
import com.game.table.GuildActivityTable;
import com.game.table.GuildRuleTable;
import com.game.table.RewardTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * 帮会活动的纯规则：查询、预检和事务复核共用同一份开放、资格、周期与奖励规则。
 * 时刻与配表均由调用方传入；个人次数按游戏日，灯会和团圆进度按活动档期，历练资金胜场按游戏日。
 */
public final class ActivityRules {
    public static final int TYPE_LANTERN = 1;
    public static final int TYPE_REUNION = 2;
    public static final int TYPE_TRIAL = 3;
    public static final int STATE_DISABLED = 1;
    public static final int STATE_UPCOMING = 2;
    public static final int STATE_OPEN = 3;
    public static final int STATE_ENDED = 4;
    public static final int RULE_ROW_ID = 1;
    public static final int MAX_REWARD_ITEM_KINDS = 16;
    /** scene 单次新发物品硬预算；超过后不能先把可靠奖励写成永远无法送达的待办。 */
    public static final int MAX_REWARD_ITEMS = 4096;
    private static final List<Integer> TYPES = List.of(TYPE_LANTERN, TYPE_REUNION, TYPE_TRIAL);
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MAX_PERSONAL_CONTRIBUTION = 1_000_000_000L;
    private static final long MAX_GUILD_FUNDS = 1_000_000_000_000L;

    private ActivityRules() {}

    /** 档期左闭右开；0/0 是开发常开，坏的非零开始/零结束不会被当成常开。 */
    public static int stateOf(GuildActivityTable row, long nowMs) {
        if (row == null || !row.getEnabled())
            return STATE_DISABLED;
        long start = row.getStartAtMs(), end = row.getEndAtMs();
        if (start == 0 && end == 0)
            return STATE_OPEN;
        if (Long.compareUnsigned(nowMs, start) < 0)
            return STATE_UPCOMING;
        return Long.compareUnsigned(nowMs, end) >= 0 ? STATE_ENDED : STATE_OPEN;
    }

    public static int dayKey(long nowMs) {
        return GameDay.dayKey(nowMs);
    }

    public static long nextResetMs(long nowMs) {
        return GameDay.nextDailyResetMillis(nowMs);
    }

    public static int guildPeriodKey(GuildActivityTable row, long nowMs) {
        if (row == null || row.getType() == TYPE_TRIAL || row.getStartAtMs() == 0 && row.getEndAtMs() == 0) {
            return dayKey(nowMs);
        }
        return dayKey(row.getStartAtMs());
    }

    /** 每类至多一行：开放中最小 id，否则最近将开，否则最小 id；不修改配表快照。 */
    public static List<GuildActivityTable> selectVisible(List<GuildActivityTable> rows, long nowMs) {
        List<GuildActivityTable> out = new ArrayList<>(TYPES.size());
        for (int type : TYPES) {
            GuildActivityTable selected = selectForType(rows, type, nowMs);
            if (selected != null)
                out.add(selected);
        }
        return List.copyOf(out);
    }

    private static GuildActivityTable selectForType(List<GuildActivityTable> rows, int type, long nowMs) {
        GuildActivityTable open = null, upcoming = null, lowest = null;
        for (GuildActivityTable row : rows) {
            if (row == null || row.getType() != type)
                continue;
            int state = stateOf(row, nowMs);
            if (state == STATE_OPEN && (open == null || idBefore(row, open)))
                open = row;
            if (state == STATE_UPCOMING
                    && (upcoming == null || Long.compareUnsigned(row.getStartAtMs(), upcoming.getStartAtMs()) < 0
                            || row.getStartAtMs() == upcoming.getStartAtMs() && idBefore(row, upcoming)))
                upcoming = row;
            if (lowest == null || idBefore(row, lowest))
                lowest = row;
        }
        return open != null ? open : upcoming != null ? upcoming : lowest;
    }

    private static boolean idBefore(GuildActivityTable a, GuildActivityTable b) {
        return Integer.compareUnsigned(a.getId(), b.getId()) < 0;
    }

    /** 只允许该类型当前选中且开放的行，防止伪造旧档期 id 分开累计进度。 */
    public static Optional<GuildActivityTable> pickForWrite(List<GuildActivityTable> rows, int type, int id, long nowMs) {
        GuildActivityTable row = selectForType(rows, type, nowMs);
        return row != null && row.getId() == id && stateOf(row, nowMs) == STATE_OPEN ? Optional.of(row) : Optional.empty();
    }

    public static boolean joinedLongEnough(long joinTimeMs, long nowMs, int minHours) {
        if (minHours == 0)
            return true;
        if (joinTimeMs == 0 || Long.compareUnsigned(joinTimeMs, nowMs) > 0)
            return false;
        return Long.compareUnsigned(nowMs - joinTimeMs, Integer.toUnsignedLong(minHours) * MILLIS_PER_HOUR) >= 0;
    }

    public static int reunionThreshold(GuildActivityTable row, GuildRuleTable rule) {
        int activity = row == null ? 0 : row.getGuildThreshold();
        int minimum = rule == null ? 0 : rule.getReunionMinOnlineMembers();
        return Integer.compareUnsigned(activity, minimum) >= 0 ? activity : minimum;
    }

    public static boolean reunionObserved(int online, int threshold) {
        return threshold != 0 && Integer.compareUnsigned(online, threshold) >= 0;
    }

    public record Progress(int count, long thresholdReachedMs, boolean fundsGranted) {
        public static final Progress EMPTY = new Progress(0, 0, false);
        public boolean latched() {
            return thresholdReachedMs != 0;
        }
    }

    public record GuildOutcome(boolean reachedNow, boolean grantFunds) {}

    /** 传入事务内已加一次点灯人次的进度；资金只认 fundsGranted，支持零资金改表后补发一次。 */
    public static GuildOutcome lanternOutcome(GuildActivityTable row, Progress progress) {
        boolean reached = row.getGuildThreshold() != 0 && Integer.compareUnsigned(progress.count(), row.getGuildThreshold()) >= 0;
        return new GuildOutcome(reached && !progress.latched(), reached && !progress.fundsGranted() && row.getGuildFunds() != 0);
    }

    public static Optional<GuildOutcome> reunionOutcome(GuildActivityTable row, Progress progress, boolean observed) {
        if (!progress.latched() && !observed)
            return Optional.empty();
        return Optional.of(new GuildOutcome(!progress.latched(), !progress.fundsGranted() && row.getGuildFunds() != 0));
    }

    public static boolean trialFundsCounted(GuildActivityTable row, int countedWins) {
        return row.getGuildFunds() != 0 && Integer.compareUnsigned(countedWins, row.getGuildThreshold()) < 0;
    }

    public record BlockInput(int state, int guildLevel, int minLevel, boolean joined, int joinMinHours, int used, int dailyLimit, int type,
            boolean thresholdReached, int progress, int threshold) {}

    public record Blocked(int tipId, List<String> params) {
        public Blocked {
            params = List.copyOf(params);
        }
    }

    /** 固定优先级：未开放、等级、入帮时长、日限、团圆阈值；在线和战斗瞬态由写入口另判。 */
    public static Blocked blocked(BlockInput in) {
        if (in.state() != STATE_OPEN)
            return new Blocked(GuildTips.ACTIVITY_NOT_OPEN, List.of());
        if (Integer.compareUnsigned(in.guildLevel(), in.minLevel()) < 0) {
            return new Blocked(GuildTips.ACTIVITY_LEVEL_TOO_LOW, List.of(Integer.toUnsignedString(in.minLevel())));
        }
        if (!in.joined())
            return new Blocked(GuildTips.ACTIVITY_JOIN_TOO_RECENT, List.of(Integer.toUnsignedString(in.joinMinHours())));
        if (Integer.compareUnsigned(in.used(), in.dailyLimit()) >= 0)
            return new Blocked(GuildTips.ACTIVITY_ALREADY_CLAIMED, List.of());
        if (in.type() == TYPE_REUNION && !in.thresholdReached() && !reunionObserved(in.progress(), in.threshold())) {
            return new Blocked(GuildTips.ACTIVITY_THRESHOLD_NOT_REACHED,
                    List.of(Integer.toUnsignedString(in.progress()), Integer.toUnsignedString(in.threshold())));
        }
        return new Blocked(0, List.of());
    }

    /** Reward 同模板槽合并，按无符号模板 id 排序；不构造任何实例或布局数据。 */
    public static AssetBundle buildRewardBundle(int rewardId, IntFunction<RewardTable> rewardOf) {
        if (rewardId == 0)
            return AssetBundle.getDefaultInstance();
        if (rewardOf == null)
            throw fail("奖励包构建缺少 Reward 表查询");
        RewardTable reward = rewardOf.apply(rewardId);
        if (reward == null)
            throw fail("Reward 表缺少 id=%s 的行", u(rewardId));
        Map<Integer, Long> sums = new TreeMap<>(Integer::compareUnsigned);
        for (int i = 0; i < reward.getRewardCount(); i++) {
            var slot = reward.getReward(i);
            long count = Integer.toUnsignedLong(slot.getRewardCount());
            if (count == 0)
                continue;
            int item = slot.getRewardItem();
            if (item == 0)
                throw fail("Reward[%s] 第 %d 槽 reward_item=0 而 reward_count=%d", u(rewardId), i + 1, count);
            long sum = sums.getOrDefault(item, 0L) + count;
            if (sum > UINT32_MAX)
                throw fail("Reward[%s] 物品 %s 合并后数量 %d 超过 uint32", u(rewardId), u(item), sum);
            sums.put(item, sum);
        }
        if (sums.size() > MAX_REWARD_ITEM_KINDS)
            throw fail("Reward[%s] 合并后物品种类 %d 超过资产通道单次上限 %d", u(rewardId), sums.size(), MAX_REWARD_ITEM_KINDS);
        AssetBundle.Builder bundle = AssetBundle.newBuilder();
        long total = 0;
        for (Map.Entry<Integer, Long> entry : sums.entrySet()) {
            total += entry.getValue();
            if (total > MAX_REWARD_ITEMS)
                throw fail("Reward[%s] 物品总数量 %d 超过资产通道单次新发上限 %d", u(rewardId), total, MAX_REWARD_ITEMS);
            bundle.addItems(AssetItem.newBuilder().setConfigId(entry.getKey()).setCount(entry.getValue().intValue()));
        }
        return bundle.build();
    }

    public static List<GuildRewardItem> rewardItems(AssetBundle bundle) {
        if (bundle == null)
            return List.of();
        return bundle.getItemsList()
                .stream()
                .map(item -> GuildRewardItem.newBuilder().setItemId(item.getConfigId()).setCount(item.getCount()).build())
                .toList();
    }

    public record ViewInput(long nowMs, GuildRuleTable rule, int guildLevel, long joinTimeMs, int myUsedCount, Progress progress,
            int reunionOnline, long myTrialBattleId, int myPendingRewardCount, int myPendingReasonTipId, int myLastRewardRejectTipId,
            GuildTrialLobbyView trialLobby, boolean trialUnavailable) {
        public ViewInput {
            progress = Objects.requireNonNull(progress, "progress");
        }
    }

    public static GuildActivityView buildView(GuildActivityTable row, ViewInput in, IntFunction<RewardTable> rewardOf) {
        if (row == null)
            throw fail("活动视图:配表行为空");
        if (in.rule() == null)
            throw fail("活动视图:GuildRule 表缺少 id=%d 的规则行", RULE_ROW_ID);
        if (in.nowMs() <= 0)
            throw fail("活动视图:ViewInput.nowMs 未设置或超出 Unix 毫秒范围");
        AssetBundle bundle = buildRewardBundle(row.getRewardId(), rewardOf);
        int type = row.getType(), state = stateOf(row, in.nowMs()), threshold = row.getGuildThreshold(), progress = 0;
        boolean reached = false, fundsGranted = false;
        if (type == TYPE_LANTERN || type == TYPE_REUNION) {
            reached = in.progress().latched();
            fundsGranted = in.progress().fundsGranted();
            if (type == TYPE_LANTERN)
                progress = in.progress().count();
            else {
                threshold = reunionThreshold(row, in.rule());
                if (!reached)
                    progress = in.reunionOnline();
            }
        } else if (type == TYPE_TRIAL)
            progress = in.progress().count();
        int joinMinHours = in.rule().getActivityJoinMinHours();
        int blocked = blocked(
                new BlockInput(state, in.guildLevel(), row.getMinGuildLevel(), joinedLongEnough(in.joinTimeMs(), in.nowMs(), joinMinHours),
                        joinMinHours, in.myUsedCount(), row.getDailyLimit(), type, reached, progress, threshold))
                              .tipId();
        if (type == TYPE_TRIAL && in.trialUnavailable()) {
            state = STATE_DISABLED;
            blocked = GuildTips.ACTIVITY_NOT_OPEN;
        }
        GuildActivityView.Builder view = GuildActivityView.newBuilder()
                                                 .setActivityId(row.getId())
                                                 .setTypeValue(type)
                                                 .setName(row.getName())
                                                 .setStateValue(state)
                                                 .setStartAtMs(row.getStartAtMs())
                                                 .setEndAtMs(row.getEndAtMs())
                                                 .setMinGuildLevel(row.getMinGuildLevel())
                                                 .setPeriodKey(dayKey(in.nowMs()))
                                                 .setNextResetMs(nextResetMs(in.nowMs()))
                                                 .setServerTimeMs(in.nowMs())
                                                 .setPersonalContribution(row.getPersonalContribution())
                                                 .setGuildFunds(row.getGuildFunds())
                                                 .setGuildThreshold(threshold)
                                                 .addAllRewardItems(rewardItems(bundle))
                                                 .setDailyLimit(row.getDailyLimit())
                                                 .setMyUsedCount(in.myUsedCount())
                                                 .setProgress(progress)
                                                 .setThresholdReached(reached)
                                                 .setFundsGranted(fundsGranted)
                                                 .setBlockedTipId(blocked)
                                                 .setDungeonId(row.getDungeonId())
                                                 .setTeamSizeMin(row.getTeamSizeMin())
                                                 .setTeamSizeMax(row.getTeamSizeMax())
                                                 .setMyPendingRewardCount(in.myPendingRewardCount())
                                                 .setMyPendingReasonTipId(in.myPendingReasonTipId())
                                                 .setMyLastRewardRejectTipId(in.myLastRewardRejectTipId())
                                                 .setJoinMinHours(joinMinHours)
                                                 .setGuildPeriodKey(guildPeriodKey(row, in.nowMs()));
        if (type == TYPE_TRIAL && !in.trialUnavailable()) {
            view.setMyTrialBattleId(in.myTrialBattleId());
            if (in.trialLobby() != null)
                view.setTrialLobby(in.trialLobby());
        }
        return view.build();
    }

    /** 配表快照在进程启用前整体校验；运行期仍须现查，不缓存行指针。 */
    public static void validate(ConfigTables tables) {
        validate(tables.guildActivity().all(), tables.guildRule().find(RULE_ROW_ID).orElse(null),
                id
                -> tables.guildLevel().find(id).isPresent(),
                id
                -> tables.reward().find(id).orElse(null),
                id -> tables.item().find(id).isPresent(), id -> tables.dungeon().find(id).isPresent());
    }

    public static void validate(List<GuildActivityTable> rows, GuildRuleTable rule, IntPredicate levelExists,
            IntFunction<RewardTable> rewardOf, IntPredicate itemExists, IntPredicate dungeonExists) {
        if (levelExists == null || rewardOf == null || itemExists == null || dungeonExists == null) {
            throw fail("GuildActivity 校验缺少 GuildLevel / Reward / Item / Dungeon 表查询");
        }
        validateRule(rule);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            GuildActivityTable row = rows.get(i);
            if (row == null)
                throw fail("GuildActivity 表第 %d 行为空", i + 1);
            if (row.getId() == 0)
                throw fail("GuildActivity 第 %d 行 id=0:0 是请求缺省值,不能对应真实活动", i + 1);
            if (!seen.add(row.getId()))
                throw fail("GuildActivity.id=%s 重复", u(row.getId()));
            validateRow(row, rule, levelExists, rewardOf, itemExists, dungeonExists);
        }
        List<GuildActivityTable> enabled = rows.stream()
                                                   .filter(GuildActivityTable::getEnabled)
                                                   .sorted((a, b) -> Integer.compareUnsigned(a.getId(), b.getId()))
                                                   .toList();
        for (int i = 0; i < enabled.size(); i++) {
            for (int j = i + 1; j < enabled.size(); j++) {
                GuildActivityTable a = enabled.get(i), b = enabled.get(j);
                if (a.getType() == b.getType() && windowsOverlap(a, b)) {
                    throw fail("GuildActivity[%s] 与 GuildActivity[%s](type=%s)同时启用且时间窗重叠", u(a.getId()), u(b.getId()),
                            u(a.getType()));
                }
            }
        }
    }

    private static void validateRule(GuildRuleTable rule) {
        if (rule == null)
            throw fail("GuildRule 表缺少 id=%d 的规则行", RULE_ROW_ID);
        if (Integer.compareUnsigned(rule.getActivityJoinMinHours(), 720) > 0)
            throw fail("GuildRule[1].activity_join_min_hours=%s 越界,应在 [0,720]", u(rule.getActivityJoinMinHours()));
        long ttl = Integer.toUnsignedLong(rule.getTrialInviteTtlSeconds());
        if (ttl < 10 || ttl > 120)
            throw fail("GuildRule[1].trial_invite_ttl_seconds=%d 越界,应在 [10,120]", ttl);
        if (Integer.compareUnsigned(rule.getTrialInviteCooldownSeconds(), 600) > 0)
            throw fail("GuildRule[1].trial_invite_cooldown_seconds=%s 越界,应在 [0,600]", u(rule.getTrialInviteCooldownSeconds()));
        if (Integer.compareUnsigned(rule.getReunionMinOnlineMembers(), GuildLimits.MAX_GUILD_MEMBERS_CAP) > 0)
            throw fail("GuildRule[1].reunion_min_online_members=%s 超过帮会成员上限 %d", u(rule.getReunionMinOnlineMembers()),
                    GuildLimits.MAX_GUILD_MEMBERS_CAP);
    }

    private static void validateRow(GuildActivityTable row, GuildRuleTable rule, IntPredicate levelExists,
            IntFunction<RewardTable> rewardOf, IntPredicate itemExists, IntPredicate dungeonExists) {
        String id = u(row.getId());
        int type = row.getType();
        if (row.getName().codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85))
            throw fail("GuildActivity[%s].name 为空:界面没有可显示的活动名", id);
        if (!TYPES.contains(type))
            throw fail("GuildActivity[%s].type=%s 非法,只允许 1(元宵灯会)/ 2(中秋团圆)/ 3(同道历练)", id, u(type));
        if (row.getDailyLimit() == 0)
            throw fail("GuildActivity[%s].daily_limit=0:每人每游戏日至少可参与 1 次", id);
        long start = row.getStartAtMs(), end = row.getEndAtMs();
        if (start < 0 || end < 0)
            throw fail("GuildActivity[%s]:start_at_ms=%s / end_at_ms=%s 超过 int64 上限", id, Long.toUnsignedString(start),
                    Long.toUnsignedString(end));
        if ((start != 0 || end != 0) && (start == 0 || end <= start))
            throw fail("GuildActivity[%s]:start_at_ms=%d / end_at_ms=%d 非法,须同为 0(常开)或 0 < start < end", id, start, end);
        if (row.getMinGuildLevel() == 0 || !levelExists.test(row.getMinGuildLevel()))
            throw fail("GuildActivity[%s].min_guild_level=%s 非法:必须 >= 1 且是 GuildLevel 表里存在的等级", id, u(row.getMinGuildLevel()));
        if (Long.compareUnsigned(row.getPersonalContribution(), MAX_PERSONAL_CONTRIBUTION) > 0)
            throw fail("GuildActivity[%s].personal_contribution=%s 超过上限 %d", id, Long.toUnsignedString(row.getPersonalContribution()),
                    MAX_PERSONAL_CONTRIBUTION);
        if (Long.compareUnsigned(row.getGuildFunds(), MAX_GUILD_FUNDS) > 0)
            throw fail("GuildActivity[%s].guild_funds=%s 超过上限 %d", id, Long.toUnsignedString(row.getGuildFunds()), MAX_GUILD_FUNDS);
        if (row.getRewardId() != 0) {
            AssetBundle bundle;
            try {
                bundle = buildRewardBundle(row.getRewardId(), rewardOf);
            } catch (IllegalStateException e) {
                throw fail("GuildActivity[%s].reward_id=%s 非法:%s", id, u(row.getRewardId()), e.getMessage());
            }
            if (bundle.getItemsCount() == 0)
                throw fail("GuildActivity[%s].reward_id=%s 的奖励包为空;不发物品请填 0", id, u(row.getRewardId()));
            if (bundle.getItemsCount() > MAX_REWARD_ITEM_KINDS)
                throw fail("GuildActivity[%s].reward_id=%s 合并后有 %d 种物品,超过资产通道单次发放上限 %d", id, u(row.getRewardId()),
                        bundle.getItemsCount(), MAX_REWARD_ITEM_KINDS);
            for (AssetItem item : bundle.getItemsList()) {
                if (!itemExists.test(item.getConfigId()))
                    throw fail("GuildActivity[%s].reward_id=%s 引用的物品 %s 在 Item 表里不存在(scene 会永久拒收)", id,
                            u(row.getRewardId()), u(item.getConfigId()));
            }
        }
        if (type != TYPE_TRIAL && (row.getDungeonId() != 0 || row.getTeamSizeMin() != 0 || row.getTeamSizeMax() != 0))
            throw fail("GuildActivity[%s]:type=%s 的 dungeon_id / team_size_min / team_size_max 必须为 0", id, u(type));
        if (type == TYPE_LANTERN && row.getGuildThreshold() == 0)
            throw fail("GuildActivity[%s].guild_threshold=0:灯会点灯人次阈值至少为 1", id);
        if (type == TYPE_REUNION) {
            if (row.getGuildThreshold() == 0 && rule.getReunionMinOnlineMembers() == 0)
                throw fail("GuildActivity[%s].guild_threshold 与 GuildRule[1].reunion_min_online_members 不能同时为 0:团圆会变成 0 "
                                + "人在线也能领",
                        id);
            if (Integer.compareUnsigned(row.getGuildThreshold(), GuildLimits.MAX_GUILD_MEMBERS_CAP) > 0)
                throw fail("GuildActivity[%s].guild_threshold=%s 超过帮会成员上限 %d:团圆在线人数永远凑不齐", id,
                        u(row.getGuildThreshold()), GuildLimits.MAX_GUILD_MEMBERS_CAP);
        }
        if (type == TYPE_TRIAL) {
            if (row.getDungeonId() == 0 || !dungeonExists.test(row.getDungeonId()))
                throw fail("GuildActivity[%s].dungeon_id=%s 非法:历练必须填 Dungeon 表里存在的副本", id, u(row.getDungeonId()));
            long lo = Integer.toUnsignedLong(row.getTeamSizeMin()), hi = Integer.toUnsignedLong(row.getTeamSizeMax());
            if (lo < 2 || lo > hi || hi > 5)
                throw fail("GuildActivity[%s]:team_size_min=%d / team_size_max=%d 非法,须满足 2 <= min <= max <= 5", id, lo, hi);
            if (row.getGuildThreshold() == 0)
                throw fail("GuildActivity[%s].guild_threshold=0:历练每游戏日计资金胜场上限至少为 1", id);
        }
    }

    private static boolean windowsOverlap(GuildActivityTable a, GuildActivityTable b) {
        if (a.getStartAtMs() == 0 && a.getEndAtMs() == 0 || b.getStartAtMs() == 0 && b.getEndAtMs() == 0)
            return true;
        return a.getStartAtMs() < b.getEndAtMs() && b.getStartAtMs() < a.getEndAtMs();
    }

    private static IllegalStateException fail(String format, Object... args) {
        return new IllegalStateException(String.format(format, args));
    }

    private static String u(int value) {
        return Integer.toUnsignedString(value);
    }
}
