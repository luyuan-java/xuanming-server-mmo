package com.game.scene.mission;

import com.game.proto.PlayerMissionStatus;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.mission.MissionTables.MissionDef;
import com.game.scene.mission.MissionTables.RewardDef;
import com.game.scene.mission.MissionTables.Slot;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerMissions;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import com.game.table.MissionErrorTip;
import com.game.table.RewardErrorTip;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务玩法（基线 PlayerMissionSystem + MissionSystem，只在场景逻辑线程上调用）：接取闸与接取、条件事实推进、完成、领奖。
 *
 * <p><b>完成后的连锁同步执行</b>（与基线线上形态不同，见 PARITY）：基线完成任务后把自动领奖、后续任务接取、「完成任务 X」事实
 * 塞进事件队列，但线上从不派发这个队列，于是自动领奖退化成待领、链式任务不接、已在进行中的任务收不到「完成任务 X」。
 * Java 按设计意图同步执行，用一个有界工作队列在本次操作结束前排空：
 * <ol>
 *   <li>一条事实按任务号（无符号）升序推进所有关注它的进行中任务，本条事实完成的任务按任务号升序处理；</li>
 *   <li>完成处理：注销索引（腾出类型）、记已完成；有奖励时置待领，自动领奖的任务立即领（失败保留待领）；
 *       「完成任务 X」事实与后续任务排队；</li>
 *   <li>先排空全部事实，再逐个走完整接取闸接后续任务（接取失败只记日志）——否则后续任务的接取回填与在途的「完成任务 X」事实会重复计数。</li>
 * </ol>
 */
public final class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    static final int OK = MissionTables.OK;
    static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    static final int INVALID_TABLE_DATA = MissionTables.INVALID_TABLE_DATA;
    static final int SERVICE_UNAVAILABLE = MissionTables.SERVICE_UNAVAILABLE;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    static final int TYPE_ALREADY_EXISTS = MissionErrorTip.mission_error.kMissionTypeAlreadyExists_VALUE;
    static final int ALREADY_COMPLETED = MissionErrorTip.mission_error.kMissionAlreadyCompleted_VALUE;
    static final int NOT_IN_REWARD_LIST = MissionErrorTip.mission_error.kMissionIdNotInRewardList_VALUE;
    static final int ALREADY_ACCEPTED = MissionErrorTip.mission_error.kMissionIdRepeated_VALUE;
    static final int REWARD_ALREADY_CLAIMED = RewardErrorTip.reward_error.kRewardAlreadyClaimed_VALUE;
    /** 一次操作里连锁步数的硬上限（事实 + 后续接取）：表有限、已完成的任务不能再接，正常远到不了；到了说明表有环以外的问题，停下记错。 */
    static final int MAX_CASCADE_STEPS = 1 << 16;

    private final MissionTables tables;
    private final BagService bags;
    private final SceneClock clock;

    public MissionService(MissionTables tables, BagService bags, SceneClock clock) {
        this.tables = tables;
        this.bags = bags;
        this.clock = clock;
    }

    public MissionTables tables() {
        return tables;
    }

    /** 一次请求只读一次的时钟（UTC 毫秒）。 */
    long nowMillis() {
        return clock.epochMillis();
    }

    /**
     * 进场景前重建派生索引（基线 RebuildIndexes）：只有表里有、且未完成的进行中任务才关注条件、占用类型。
     * 存档本身不校验（坏的进度格数只是让任务永不推进），不会拒绝进场。
     */
    public void initializeOnLoad(ScenePlayer player) {
        PlayerMissions missions = player.missions();
        missions.clearIndexes();
        for (int id : missions.activeIds()) {
            MissionDef def = tables.mission(id);
            if (def != null && !missions.isComplete(id)) {
                missions.index(id, def.type(), def.subType(), def.categories());
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 列表状态：待领 &gt; 已完成 &gt; 进行中 &gt; 未接（基线 FillMission 的优先级）。 */
    public static PlayerMissionStatus status(PlayerMissions missions, int missionId) {
        if (missions.isClaimable(missionId)) {
            return PlayerMissionStatus.PLAYER_MISSION_CLAIMABLE;
        }
        if (missions.isComplete(missionId)) {
            return PlayerMissionStatus.PLAYER_MISSION_COMPLETED;
        }
        if (missions.isAccepted(missionId)) {
            return PlayerMissionStatus.PLAYER_MISSION_ACTIVE;
        }
        return PlayerMissionStatus.PLAYER_MISSION_NOT_ACCEPTED;
    }

    /**
     * 接取闸（基线 CheckAccept，只读）。先后：scope → 表行 1001 → 无条件 / 顺序非法 1002 → 已接 5004 → 已完成或待领 5001
     * → 类型占用 5000 → 逐格静态闸 1002 / 1003 → 奖励配置 1002 → 活动窗口 1002 / 1006。
     */
    public int checkAccept(ScenePlayer player, int scope, int missionId, long nowMs) {
        if (scope != 0 || !writable(player)) {
            return INVALID_PARAMETER;
        }
        MissionDef def = tables.mission(missionId);
        if (def == null) {
            return INVALID_TABLE_ID;
        }
        if (def.earlyTip() != OK) {
            return def.earlyTip();
        }
        PlayerMissions missions = player.missions();
        if (missions.isAccepted(missionId)) {
            return ALREADY_ACCEPTED;
        }
        if (missions.isComplete(missionId) || missions.isClaimable(missionId)) {
            return ALREADY_COMPLETED;
        }
        if (missions.typeOccupied(def.type(), def.subType())) {
            return TYPE_ALREADY_EXISTS;
        }
        if (def.lateTip() != OK) {
            return def.lateTip();
        }
        int reward = tables.reward(def.rewardId()).tip();
        if (reward != OK) {
            return reward;
        }
        return def.activity() ? ActivitySchedules.checkOpen(def, nowMs) : OK;
    }

    /** 194 接取（当前时刻）。 */
    public int accept(ScenePlayer player, int scope, int missionId) {
        return accept(player, scope, missionId, nowMillis());
    }

    /**
     * 194 接取：过闸后接下、同步回填（等级、已完成的前置任务），回填可能当场完成并连锁。
     *
     * @param nowMs 本次请求的时刻（与应答列表用同一个，一次请求只读一次时钟）
     */
    public int accept(ScenePlayer player, int scope, int missionId, long nowMs) {
        int tip = checkAccept(player, scope, missionId, nowMs);
        if (tip != OK) {
            return tip;
        }
        Cascade cascade = new Cascade(player, nowMs);
        cascade.acceptChecked(tables.mission(missionId));
        cascade.drain();
        return OK;
    }

    /**
     * 领奖闸（基线 CheckClaim，只读；不查背包空间，满包在领取时回 6006）。先后：scope → 不可领（已完成 12000，否则 5002）
     * → 待领却未完成 1002 → 表行缺失或无奖励 1002 → 奖励配置 1002。
     */
    public int checkClaim(ScenePlayer player, int scope, int missionId) {
        if (scope != 0 || !writable(player)) {
            return INVALID_PARAMETER;
        }
        PlayerMissions missions = player.missions();
        if (!missions.isClaimable(missionId)) {
            return missions.isComplete(missionId) ? REWARD_ALREADY_CLAIMED : NOT_IN_REWARD_LIST;
        }
        if (!missions.isComplete(missionId)) {
            return INVALID_TABLE_DATA;
        }
        MissionDef def = tables.mission(missionId);
        if (def == null || def.rewardId() == 0) {
            return INVALID_TABLE_DATA;
        }
        return tables.reward(def.rewardId()).tip();
    }

    /** 195 领奖：物品整批进人物背包（流水原因任务奖励），成功才清待领；失败保留领奖资格。 */
    public int claim(ScenePlayer player, int scope, int missionId) {
        int tip = checkClaim(player, scope, missionId);
        if (tip != OK) {
            return tip;
        }
        RewardDef reward = tables.reward(tables.mission(missionId).rewardId());
        Bag.AddResult result = bags.addItems(player, BagType.INVENTORY, reward.items(), AssetAudit.Reason.QUEST_REWARD, 0,
                "{\"mission_id\":" + Integer.toUnsignedString(missionId) + "}");
        if (!result.ok()) {
            return result.tip();
        }
        player.missions().clearClaimable(missionId);
        return OK;
    }

    // ------------------------------------------------------------------ 事实来源

    /** 等级变了（GM 175 成功后，等级没变也发，同基线）：等级条件按原始等级覆盖进度。 */
    public void onLevelChanged(ScenePlayer player) {
        int level = player.level();
        dispatch(player, MissionFact.of(MissionTables.CATEGORY_LEVEL, level, Integer.toUnsignedLong(level)));
    }

    /** 击杀结算（回合制战斗接入前只有单测调用）：每只一条事实，同基线。 */
    public void onMonsterKilled(ScenePlayer player, int monsterConfigId, int count) {
        if (monsterConfigId == 0) {
            return;
        }
        for (long i = 0; i < Integer.toUnsignedLong(count); i++) {
            dispatch(player, MissionFact.of(MissionTables.CATEGORY_KILL, monsterConfigId, 1));
        }
    }

    void dispatch(ScenePlayer player, MissionFact fact) {
        if (!writable(player)) {
            return;
        }
        Cascade cascade = new Cascade(player, nowMillis());
        cascade.facts.add(fact);
        cascade.drain();
    }

    /** 跨服冻结随跨服（5.x）接入：冻结期间拒绝接取 / 领奖、丢弃事实（基线 1005）。 */
    private static boolean writable(ScenePlayer player) {
        return true;
    }

    // ------------------------------------------------------------------ 连锁

    /** 一次操作的工作队列：先排空事实，再逐个接后续任务。 */
    private final class Cascade {
        private final ScenePlayer player;
        private final PlayerMissions missions;
        private final long nowMs;
        private final ArrayDeque<MissionFact> facts = new ArrayDeque<>();
        private final ArrayDeque<Integer> chained = new ArrayDeque<>();
        private int steps;

        Cascade(ScenePlayer player, long nowMs) {
            this.player = player;
            this.missions = player.missions();
            this.nowMs = nowMs;
        }

        void drain() {
            while (!facts.isEmpty() || !chained.isEmpty()) {
                if (++steps > MAX_CASCADE_STEPS) {
                    log.error("任务连锁步数超上限，丢弃剩余 player={} facts={} chained={}",
                            Long.toUnsignedString(player.playerId()), facts.size(), chained.size());
                    return;
                }
                if (!facts.isEmpty()) {
                    apply(facts.poll());
                    continue;
                }
                int next = chained.poll();
                int tip = checkAccept(player, 0, next, nowMs);
                if (tip != OK) {
                    log.warn("后续任务接取失败 player={} mission={} tip={}", Long.toUnsignedString(player.playerId()),
                            Integer.toUnsignedString(next), tip);
                    continue;
                }
                acceptChecked(tables.mission(next));
            }
        }

        /** 接下已过闸的任务并排入回填事实（只推进这个任务）：等级一条；完成任务类条件涉及的已完成任务每个一条（跨格去重、升序）。 */
        void acceptChecked(MissionDef def) {
            int id = def.id();
            missions.accept(id, def.slots().size(), nowMs, def.type(), def.subType(), def.categories());
            int level = player.level();
            facts.add(new MissionFact(MissionTables.CATEGORY_LEVEL, List.of(level), Integer.toUnsignedLong(level), id));
            TreeSet<Integer> completed = new TreeSet<>(Integer::compareUnsigned);
            for (Slot slot : def.slots()) {
                if (slot.condition() == null || slot.condition().category() != MissionTables.CATEGORY_COMPLETE_MISSION) {
                    continue;
                }
                if (slot.condition().condition1().isEmpty()) {
                    completed.addAll(missions.completedIds());
                } else {
                    for (int prerequisite : slot.condition().condition1()) {
                        if (missions.isComplete(prerequisite)) {
                            completed.add(prerequisite);
                        }
                    }
                }
            }
            for (int done : completed) {
                facts.add(new MissionFact(MissionTables.CATEGORY_COMPLETE_MISSION, List.of(done), 1, id));
            }
        }

        /** 一条事实（基线 HandleConditionEvent）：没有参数的事实什么都不推进。 */
        private void apply(MissionFact fact) {
            if (fact.ids().isEmpty()) {
                return;
            }
            List<MissionDef> justCompleted = new ArrayList<>();
            for (int id : missions.watchers(fact.category())) {
                if (fact.onlyMission() != 0 && id != fact.onlyMission()) {
                    continue;
                }
                PlayerMissions.Active active = missions.active(id);
                MissionDef def = tables.mission(id);
                if (active == null || def == null || missions.isComplete(id)) {
                    continue;
                }
                if (advance(def, active, fact) && allFulfilled(def, active)) {
                    justCompleted.add(def);
                }
            }
            for (MissionDef def : justCompleted) {
                complete(def);
            }
        }

        /** 完成处理（基线 OnMissionCompletion）。 */
        private void complete(MissionDef def) {
            int id = def.id();
            missions.complete(id, def.type(), def.subType(), def.categories());
            if (def.rewardId() != 0) {
                missions.setClaimable(id);
                if (def.autoReward()) {
                    int tip = claim(player, 0, id);
                    if (tip != OK) {
                        log.info("自动领奖失败，保留待领 player={} mission={} tip={}",
                                Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(id), tip);
                    }
                }
            }
            facts.add(MissionFact.of(MissionTables.CATEGORY_COMPLETE_MISSION, id, 1));
            for (int next : def.nextMissionIds()) {
                if (next != 0) {
                    chained.add(next);
                }
            }
        }
    }

    /**
     * 推进一个任务的条件格（基线 UpdateMissionProgress）。格数与表不符 → 不推进。顺序任务只推进第一个未达成的格子
     * （多出的量不顺延）；并行任务推进所有命中的格子。条件行缺失的格子跳过（顺序任务也不在这里停）。
     *
     * @return 有格子的值变了
     */
    static boolean advance(MissionDef def, PlayerMissions.Active active, MissionFact fact) {
        if (active.slots() != def.slots().size()) {
            return false;
        }
        boolean changed = false;
        for (Slot slot : def.slots()) {
            if (slot.condition() == null) {
                continue;
            }
            long current = active.progress(slot.index());
            if (def.ordered() && ConditionRules.isFulfilled(slot.condition(), current, slot.targetOverride())) {
                continue;
            }
            long next = ConditionRules.advance(slot.condition(), current, slot.targetOverride(), fact);
            if (next >= 0) {
                active.progress(slot.index(), next);
                changed = true;
            }
            if (def.ordered()) {
                break;
            }
        }
        return changed;
    }

    /** 全部条件达成（基线 AreAllConditionsFulfilled）：没有条件或格数不符 → 否。 */
    static boolean allFulfilled(MissionDef def, PlayerMissions.Active active) {
        if (def.slots().isEmpty() || active.slots() != def.slots().size()) {
            return false;
        }
        for (Slot slot : def.slots()) {
            if (!ConditionRules.isFulfilled(slot.condition(), active.progress(slot.index()), slot.targetOverride())) {
                return false;
            }
        }
        return true;
    }
}
