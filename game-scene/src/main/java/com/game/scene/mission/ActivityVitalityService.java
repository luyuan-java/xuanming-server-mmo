package com.game.scene.mission;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.ActivityClaimRecordState;
import com.game.player.store.state.ActivityPointSourceState;
import com.game.player.store.state.PlayerState;
import com.game.proto.ClaimActivityVitalityRewardRequest;
import com.game.proto.ClaimActivityVitalityRewardResponse;
import com.game.proto.PlayerActivityRewardItem;
import com.game.proto.PlayerActivityVitalityReward;
import com.game.proto.PlayerActivityVitalitySnapshot;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.mission.MissionTables.ItemDisplay;
import com.game.scene.mission.MissionTables.MissionDef;
import com.game.scene.mission.MissionTables.RewardDef;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerActivityVitality;
import com.game.scene.world.ScenePlayer;
import com.game.table.ActivityScheduleTable;
import com.game.table.ActivityVitalityRewardTable;
import com.game.table.ActivityVitalityRuleTable;
import com.game.table.CommonErrorTip;
import com.game.table.RewardErrorTip;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活动中心活跃度积分与档位领奖（对齐 C++ {@code PlayerActivitySystem}）。
 *
 * <p>核心契约：
 * <ul>
 *   <li>周期由配表 {@code ActivityVitalityRule} 的 UTC 绝对起点与周期长度确定性推导；</li>
 *   <li>一次性活动任务完成同帧置去重位图并记当期来源，裁剪同规则旧周期来源；</li>
 *   <li>领奖先查 {@code request_id} 重放（优先于周期结束、冻结与规则热更），冲突身份拒 {@code kInvalidParameter}；</li>
 *   <li>当前规则且 {@code period_id < currentStart} 且无重放记录时回 {@code kRewardPeriodEnded}（12001）；</li>
 *   <li>发奖前裁剪当前规则已结束周期记录保留最新 64 条，仅在背包原子入包成功后落账本。</li>
 * </ul>
 */
public final class ActivityVitalityService {

    public static final int MAX_LEDGER_RECORDS = 4096;
    public static final int MAX_THRESHOLDS = 64;
    /** 已结束周期保留最新 64 条领取记录供原 request_id 重放（不小于单周期档位上限）。 */
    public static final int KEPT_PAST_CLAIMS = 64;

    static final int OK = 0;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    static final int REWARD_ALREADY_CLAIMED = RewardErrorTip.reward_error.kRewardAlreadyClaimed_VALUE;
    static final int REWARD_PERIOD_ENDED = RewardErrorTip.reward_error.kRewardPeriodEnded_VALUE;

    private final MissionTables tables;
    private final BagService bags;

    public ActivityVitalityService(MissionTables tables, BagService bags) {
        this.tables = tables;
        this.bags = bags;
    }

    public record Period(long startsAtMs, long endsAtMs) {
    }

    /** 纯周期计算：所有时间均取自正式配表，不依赖客户端日期或本地时区。 */
    public static Period resolvePeriod(ActivityVitalityRuleTable rule, long nowMs) {
        if (rule == null || !rule.getEnabled() || rule.getId() == 0 || rule.getConfigurationRevision() == 0
                || rule.getPointsLimit() == 0) {
            return null;
        }
        long first = rule.getFirstPeriodStartsAtMs();
        long duration = rule.getPeriodDurationMs();
        if (first <= 0 || duration <= 0 || Long.compareUnsigned(nowMs, first) < 0) {
            return null;
        }
        long delta = nowMs - first;
        long offset = Long.remainderUnsigned(delta, duration);
        long start = nowMs - offset;
        long end = start + duration;
        if (Long.compareUnsigned(end, start) <= 0) {
            return null;
        }
        return new Period(start, end);
    }

    /** 活动任务完成同帧累计活跃积分并置永久去重位图（不阻断任务完成本身）。 */
    public void onMissionCompleted(ScenePlayer player, MissionDef def, long nowMs) {
        if (player == null || def == null || !def.activity()) {
            return;
        }
        ActivityScheduleTable schedule = def.schedule();
        if (schedule == null || schedule.getVitalityPoints() == 0 || schedule.getDefinitionKey().isEmpty()
                || schedule.getConfigurationRevision() == 0) {
            return;
        }
        ActivityVitalityRuleTable rule = activeRule();
        if (rule == null) {
            return;
        }
        Period period = resolvePeriod(rule, nowMs);
        if (period == null) {
            return;
        }
        int bitIndex = tables.missionBitIndex(def.id());
        if (bitIndex < 0) {
            return;
        }
        PlayerActivityVitality ledger = player.activityVitality();
        if (!validLedger(ledger) || ledger.isMissionCredited(bitIndex)) {
            return;
        }
        ledger.pruneEndedSources(rule.getId(), period.startsAtMs());
        if (ledger.sources().size() >= MAX_LEDGER_RECORDS) {
            return;
        }
        ledger.markMissionCredited(bitIndex);
        ledger.addSource(ActivityPointSourceState.newBuilder()
                .setMissionScope(0)
                .setMissionId(def.id())
                .setRuleId(rule.getId())
                .setConfigurationRevision(rule.getConfigurationRevision())
                .setPeriodId(period.startsAtMs())
                .setPoints(schedule.getVitalityPoints())
                .build());
    }

    /** 构建活动页的活跃度只读快照。 */
    public PlayerActivityVitalitySnapshot buildSnapshot(ScenePlayer player, long nowMs) {
        PlayerActivityVitalitySnapshot.Builder out = PlayerActivityVitalitySnapshot.newBuilder();
        PlayerActivityVitality ledger = player.activityVitality();
        if (!validLedger(ledger)) {
            return out.setUnavailableReason("活跃账本异常").build();
        }
        ActivityVitalityRuleTable rule = activeRule();
        if (rule == null) {
            return out.setUnavailableReason("活跃规则与阈值奖励尚未配置").build();
        }
        List<ActivityVitalityRewardTable> rewards = rewardsForRule(rule.getId());
        Period period = resolvePeriod(rule, nowMs);
        if (period == null || !validThresholds(rule, rewards)) {
            return out.setUnavailableReason("活跃配置异常或尚未开放").build();
        }
        int points = currentPoints(ledger, rule, period.startsAtMs());
        out.setAvailable(true)
                .setRuleId(rule.getId())
                .setConfigurationRevision(rule.getConfigurationRevision())
                .setPeriodId(period.startsAtMs())
                .setPeriodStartsAtMs(period.startsAtMs())
                .setPeriodEndsAtMs(period.endsAtMs())
                .setPoints(points)
                .setPointsLimit(rule.getPointsLimit());
        List<ActivityVitalityRewardTable> sorted = new ArrayList<>(rewards);
        sorted.sort(Comparator.comparingInt(ActivityVitalityRewardTable::getRequiredPoints)
                .thenComparingInt(ActivityVitalityRewardTable::getId));
        boolean mutable = !player.frozen() && !player.inBattle();
        for (ActivityVitalityRewardTable threshold : sorted) {
            PlayerActivityVitalityReward.Builder shown = PlayerActivityVitalityReward.newBuilder()
                    .setThresholdId(threshold.getId())
                    .setRequiredPoints(threshold.getRequiredPoints())
                    .setRewardId(threshold.getRewardId());
            fillRewardItems(threshold.getRewardId(), shown);
            ActivityClaimRecordState claim = findClaim(ledger, rule.getId(), period.startsAtMs(), threshold.getId());
            boolean claimed = claim != null;
            boolean durable = claimed && isDurable(player, claim);
            boolean canClaim = !claimed
                    && Integer.compareUnsigned(points, threshold.getRequiredPoints()) >= 0
                    && ledger.claims().size() < MAX_LEDGER_RECORDS
                    && mutable;
            shown.setClaimed(claimed).setDurable(durable).setCanClaim(canClaim);
            if (claimed) {
                shown.setUnavailableReason(durable ? "已领取" : "奖励保存中，请重查原领取");
            } else if (Integer.compareUnsigned(points, threshold.getRequiredPoints()) < 0) {
                shown.setUnavailableReason("活跃积分尚未达到门槛");
            } else if (!canClaim) {
                shown.setUnavailableReason("当前状态暂不可领取");
            }
            out.addRewards(shown);
        }
        return out.build();
    }

    /** 领取活跃度档位奖励（幂等重放优先于跨日/冻结/战斗闸）。 */
    public ClaimActivityVitalityRewardResponse claim(ScenePlayer player, ClaimActivityVitalityRewardRequest request, long nowMs) {
        ClaimActivityVitalityRewardResponse.Builder resp = ClaimActivityVitalityRewardResponse.newBuilder()
                .setRequestId(request.getRequestId());
        PlayerActivityVitality ledger = player.activityVitality();
        if (!validLedger(ledger)) {
            return resp.setErrorMessage(tip(INVALID_TABLE_DATA)).build();
        }
        if (request.getRequestId().isEmpty() || request.getRequestId().length() > 64
                || request.getRuleId() == 0 || request.getConfigurationRevision() == 0
                || request.getPeriodId() == 0 || request.getThresholdId() == 0) {
            return resp.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        // ① 原请求重放优先于周期结束、冻结与规则变化；同 request_id 冲突身份一律拒绝
        for (ActivityClaimRecordState existing : ledger.claims()) {
            if (!existing.getRequestId().equals(request.getRequestId())) {
                continue;
            }
            if (!sameIdentity(existing, request)) {
                return resp.setErrorMessage(tip(INVALID_PARAMETER)).build();
            }
            return resp.setErrorMessage(tip(OK))
                    .setClaimed(true)
                    .setReplayed(true)
                    .setDurable(isDurable(player, existing))
                    .setSnapshot(buildSnapshot(player, nowMs))
                    .build();
        }
        if (findClaim(ledger, request.getRuleId(), request.getPeriodId(), request.getThresholdId()) != null) {
            return resp.setErrorMessage(tip(REWARD_ALREADY_CLAIMED)).build();
        }
        if (player.frozen() || player.inBattle()) {
            return resp.setErrorMessage(tip(FEATURE_UNAVAILABLE)).build();
        }
        ActivityVitalityRuleTable rule = activeRule();
        if (rule == null) {
            return resp.setErrorMessage(tip(FEATURE_UNAVAILABLE)).build();
        }
        List<ActivityVitalityRewardTable> rewards = rewardsForRule(rule.getId());
        Period period = resolvePeriod(rule, nowMs);
        if (period == null || !validThresholds(rule, rewards)) {
            return resp.setErrorMessage(tip(INVALID_TABLE_DATA)).build();
        }
        // ② 本规则旧周期（含超过 64 条裁剪窗口的原请求）明确回 kRewardPeriodEnded(12001)
        if (request.getRuleId() == rule.getId() && Long.compareUnsigned(request.getPeriodId(), period.startsAtMs()) < 0) {
            return resp.setErrorMessage(tip(REWARD_PERIOD_ENDED)).build();
        }
        if (request.getRuleId() != rule.getId()
                || request.getConfigurationRevision() != rule.getConfigurationRevision()
                || request.getPeriodId() != period.startsAtMs()) {
            return resp.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        ActivityVitalityRewardTable threshold = null;
        for (ActivityVitalityRewardTable row : rewards) {
            if (row.getId() == request.getThresholdId()) {
                threshold = row;
                break;
            }
        }
        int points = currentPoints(ledger, rule, period.startsAtMs());
        if (threshold == null || Integer.compareUnsigned(points, threshold.getRequiredPoints()) < 0) {
            return resp.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        // ③ 先裁已结束周期记录再判总容量，防止长期活跃角色账本攒满永久不可领
        ledger.prunePastClaims(rule.getId(), period.startsAtMs(), KEPT_PAST_CLAIMS);
        if (ledger.claims().size() >= MAX_LEDGER_RECORDS) {
            return resp.setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        RewardDef rewardDef = tables.reward(threshold.getRewardId());
        if (rewardDef.tip() != OK || rewardDef.items().isEmpty()) {
            return resp.setErrorMessage(tip(rewardDef.tip() != OK ? rewardDef.tip() : INVALID_TABLE_DATA)).build();
        }
        if (bags == null) {
            return resp.setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        Bag.AddResult added = bags.addItems(player, BagType.INVENTORY, rewardDef.items(),
                AssetAudit.Reason.QUEST_REWARD, Integer.toUnsignedLong(threshold.getId()), "");
        if (added.tip() != OK) {
            return resp.setErrorMessage(tip(added.tip())).build();
        }
        ActivityClaimRecordState record = ActivityClaimRecordState.newBuilder()
                .setRuleId(rule.getId())
                .setConfigurationRevision(rule.getConfigurationRevision())
                .setPeriodId(period.startsAtMs())
                .setThresholdId(threshold.getId())
                .setRequestId(request.getRequestId())
                .setRewardId(threshold.getRewardId())
                .setClaimedAtMs(nowMs)
                .build();
        ledger.addClaim(record);
        return resp.setErrorMessage(tip(OK))
                .setClaimed(true)
                .setDurable(isDurable(player, record))
                .setSnapshot(buildSnapshot(player, nowMs))
                .build();
    }

    private ActivityVitalityRuleTable activeRule() {
        ActivityVitalityRuleTable found = null;
        for (ActivityVitalityRuleTable row : tables.vitalityRules()) {
            if (!row.getEnabled()) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = row;
        }
        return found;
    }

    private List<ActivityVitalityRewardTable> rewardsForRule(int ruleId) {
        List<ActivityVitalityRewardTable> out = new ArrayList<>();
        for (ActivityVitalityRewardTable row : tables.vitalityRewards()) {
            if (row.getRuleId() == ruleId) {
                out.add(row);
            }
        }
        return out;
    }

    private boolean validThresholds(ActivityVitalityRuleTable rule, List<ActivityVitalityRewardTable> rewards) {
        if (rewards.isEmpty() || rewards.size() > MAX_THRESHOLDS) {
            return false;
        }
        Set<Integer> ids = new HashSet<>();
        for (ActivityVitalityRewardTable row : rewards) {
            if (row.getId() == 0 || row.getRuleId() != rule.getId()
                    || row.getRequiredPoints() == 0
                    || Integer.compareUnsigned(row.getRequiredPoints(), rule.getPointsLimit()) > 0
                    || !ids.add(row.getId())) {
                return false;
            }
            RewardDef reward = tables.reward(row.getRewardId());
            if (reward.tip() != OK || reward.items().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void fillRewardItems(int rewardId, PlayerActivityVitalityReward.Builder out) {
        RewardDef reward = tables.reward(rewardId);
        if (reward.tip() != OK) {
            return;
        }
        for (Map.Entry<Integer, Long> entry : reward.items().entrySet()) {
            ItemDisplay display = tables.itemDisplay(entry.getKey());
            PlayerActivityRewardItem.Builder item = PlayerActivityRewardItem.newBuilder()
                    .setItemConfigId(entry.getKey())
                    .setCount(entry.getValue());
            if (display != null) {
                item.setName(display.name()).setIconKey(display.iconKey());
            }
            out.addItems(item);
        }
    }

    private static boolean validLedger(PlayerActivityVitality ledger) {
        if (ledger.sources().size() > MAX_LEDGER_RECORDS || ledger.claims().size() > MAX_LEDGER_RECORDS) {
            return false;
        }
        Set<Long> sources = new HashSet<>();
        for (ActivityPointSourceState s : ledger.sources()) {
            if (s.getMissionScope() != 0 || s.getMissionId() == 0 || s.getRuleId() == 0
                    || s.getConfigurationRevision() == 0 || s.getPeriodId() == 0 || s.getPoints() == 0) {
                return false;
            }
            long key = (((long) s.getMissionScope()) << 32) | Integer.toUnsignedLong(s.getMissionId());
            if (!sources.add(key)) {
                return false;
            }
        }
        Set<String> requestIds = new HashSet<>();
        Set<String> claimKeys = new HashSet<>();
        for (ActivityClaimRecordState c : ledger.claims()) {
            if (c.getRuleId() == 0 || c.getConfigurationRevision() == 0 || c.getPeriodId() == 0
                    || c.getThresholdId() == 0 || c.getRewardId() == 0 || c.getClaimedAtMs() == 0
                    || c.getRequestId().isEmpty() || c.getRequestId().length() > 64) {
                return false;
            }
            if (!requestIds.add(c.getRequestId())) {
                return false;
            }
            String key = Integer.toUnsignedString(c.getRuleId()) + ":" + Long.toUnsignedString(c.getPeriodId())
                    + ":" + Integer.toUnsignedString(c.getThresholdId());
            if (!claimKeys.add(key)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameIdentity(ActivityClaimRecordState existing, ClaimActivityVitalityRewardRequest req) {
        return existing.getRuleId() == req.getRuleId()
                && existing.getConfigurationRevision() == req.getConfigurationRevision()
                && existing.getPeriodId() == req.getPeriodId()
                && existing.getThresholdId() == req.getThresholdId();
    }

    private static ActivityClaimRecordState findClaim(PlayerActivityVitality ledger, int ruleId, long periodId, int thresholdId) {
        for (ActivityClaimRecordState c : ledger.claims()) {
            if (c.getRuleId() == ruleId && c.getPeriodId() == periodId && c.getThresholdId() == thresholdId) {
                return c;
            }
        }
        return null;
    }

    private static int currentPoints(PlayerActivityVitality ledger, ActivityVitalityRuleTable rule, long periodId) {
        long limit = Integer.toUnsignedLong(rule.getPointsLimit());
        long sum = 0;
        for (ActivityPointSourceState s : ledger.sources()) {
            if (s.getRuleId() == rule.getId() && s.getPeriodId() == periodId) {
                sum = Math.min(limit, sum + Integer.toUnsignedLong(s.getPoints()));
            }
        }
        return (int) sum;
    }

    private static boolean isDurable(ScenePlayer player, ActivityClaimRecordState claim) {
        PlayerState persisted = player.persistedState();
        if (persisted == null || !persisted.hasActivityVitality()) {
            return false;
        }
        for (ActivityClaimRecordState saved : persisted.getActivityVitality().getClaimsList()) {
            if (saved.equals(claim)) {
                return true;
            }
        }
        return false;
    }
}
