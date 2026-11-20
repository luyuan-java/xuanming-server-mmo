package com.game.battle.engine;

import com.game.common.combatai.BehaviorTree;
import com.game.common.combatai.BehaviorTree.Status;
import com.game.common.combatai.BehaviorTree.TreeDef;
import com.game.common.combatai.Hfsm;
import com.game.common.combatai.Hfsm.EventOutcome;
import com.game.common.combatai.Hfsm.StateConfig;
import com.game.common.combatai.MonsterUtilityAi;
import com.game.common.combatai.MonsterUtilityAi.ActionCandidate;
import com.game.common.combatai.MonsterUtilityAi.LinearModel;
import com.game.common.combatai.MonsterUtilityAi.PairwiseSample;
import com.game.common.combatai.MonsterUtilityAi.Selection;
import com.game.common.combatai.MonsterUtilityAi.TrainConfig;
import com.game.common.combatai.MonsterUtilityAi.TrainResult;
import com.game.proto.BattleAction;
import com.game.proto.eBattleActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 副本怪物智能决策模块（纯确定性、零随机数、零挂钟、无日志，与 C++ {@code turnbattle::DungeonMonsterAiController}
 * 及 Go {@code combatai.DungeonMonsterAiController} 逐项对齐）。
 *
 * <ol>
 *   <li>Boss 宏观阶段（{@link Hfsm}）：{@code ROOT -> COMBAT -> { OPENING / ENRAGE / DESPERATE }} 与 {@code DEAD}；
 *       阶段切换走 LCA，共享父态 {@code COMBAT} 不重复退出重入。</li>
 *   <li>战术行为树（{@link BehaviorTree}）：全进程共享一份不可变拓扑 {@link TreeDef}，每只怪仅持轻量 {@link BehaviorTree.State}
 *       （冷却按回合号确定性判定）。</li>
 *   <li>数据训练效用模型（{@link LinearModel}）：与 C++ / Go 使用完全相同的 6 维特征与 8 条基准偏好样本，产出逐位一致的 int32 权重。</li>
 * </ol>
 */
public final class DungeonMonsterAi {

    public static final int FEATURE_COUNT = 6;

    /** 目标已损失气血万分比（0..10000）。 */
    public static final int FEATURE_TARGET_MISSING_HP = 0;
    /** 目标尚未被控（眩晕 / 沉默 / 冰冻）为 10000，否则 0。 */
    public static final int FEATURE_TARGET_UNCONTROLLED = 1;
    /** 目标速度 / 压制威胁度万分比（0..10000）。 */
    public static final int FEATURE_TARGET_THREAT = 2;
    /** 本候选为技能施放为 10000，普攻为 0。 */
    public static final int FEATURE_IS_SKILL = 3;
    /** Boss 阶段紧迫度：Opening=0, Enrage=5000, Desperate=10000。 */
    public static final int FEATURE_PHASE_URGENCY = 4;
    /** 目标是玩家（10000）vs 宠物（3000）。 */
    public static final int FEATURE_TARGET_IS_PLAYER = 5;

    private static final int BB_DEFEND_COOLDOWN_READY_ROUND = 1;
    private static final int EVENT_DIE = 1;

    private DungeonMonsterAi() {
    }

    public enum BossPhaseId {
        ROOT(0),
        COMBAT(1),
        OPENING(2),
        ENRAGE(3),
        DESPERATE(4),
        DEAD(5);

        private final int id;

        BossPhaseId(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        static BossPhaseId fromId(int id) {
            return switch (id) {
                case 0 -> ROOT;
                case 1 -> COMBAT;
                case 2 -> OPENING;
                case 3 -> ENRAGE;
                case 4 -> DESPERATE;
                case 5 -> DEAD;
                default -> OPENING;
            };
        }
    }

    /** 副本怪物 AI 配置（不可变值对象）。 */
    public record Profile(int enrageHpPermyriad, int desperateHpPermyriad,
                          int emergencyDefendHpPermyriad, int defendCooldownRounds,
                          List<Integer> monsterSkillIds, LinearModel utilityModel) {

        public Profile {
            monsterSkillIds = monsterSkillIds == null ? List.of() : List.copyOf(monsterSkillIds);
            utilityModel = utilityModel == null ? new LinearModel(new int[FEATURE_COUNT], 0) : utilityModel;
        }

        public static Profile defaults() {
            return new Profile(6000, 3000, 2500, 3, List.of(), new LinearModel(new int[FEATURE_COUNT], 0));
        }

        public Profile withMonsterSkillIds(List<Integer> skillIds) {
            return new Profile(enrageHpPermyriad, desperateHpPermyriad, emergencyDefendHpPermyriad,
                    defendCooldownRounds, skillIds, utilityModel);
        }

        public Profile withThresholds(int enrageHp, int desperateHp, int emergencyDefendHp, int defendCooldownRounds) {
            return new Profile(enrageHp, desperateHp, emergencyDefendHp, defendCooldownRounds,
                    monsterSkillIds, utilityModel);
        }

        public Profile withUtilityModel(LinearModel model) {
            return new Profile(enrageHpPermyriad, desperateHpPermyriad, emergencyDefendHpPermyriad,
                    defendCooldownRounds, monsterSkillIds, model);
        }
    }

    public record Candidate(BattleAction action, int formationSlot, int[] features) {
        public Candidate {
            Objects.requireNonNull(action, "action");
            features = Objects.requireNonNull(features, "features").clone();
        }
    }

    public record DecisionInput(int roundIndex, int selfHpPermyriad, boolean selfAlive,
                                boolean selfCanDefend, List<Candidate> candidates) {
        public DecisionInput {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** 用对战偏好样本训练副本怪物效用权重（确定性全批量逻辑回归，零随机数）。 */
    public static Profile trainProfile(List<PairwiseSample> samples, Profile baseProfile) {
        Profile base = baseProfile == null ? Profile.defaults() : baseProfile;
        TrainResult trained = MonsterUtilityAi.trainPairwiseLinearModel(FEATURE_COUNT, samples, TrainConfig.defaults());
        return base.withUtilityModel(trained.model());
    }

    /** 内置默认副本 Boss 配置：与 C++ / Go 使用完全相同的 8 条标准副本战术样本。 */
    public static Profile buildTrainedDefaultProfile() {
        List<PairwiseSample> samples = List.of(
                new PairwiseSample(new int[] {8000, 10000, 8000, 10000, 5000, 10000},
                        new int[] {1000, 0, 2000, 0, 0, 3000}, 1.0),
                new PairwiseSample(new int[] {7500, 10000, 6000, 10000, 5000, 10000},
                        new int[] {7500, 10000, 6000, 0, 5000, 10000}, 1.0),
                new PairwiseSample(new int[] {9000, 10000, 5000, 0, 0, 10000},
                        new int[] {1500, 10000, 5000, 0, 0, 10000}, 1.0),
                new PairwiseSample(new int[] {4000, 10000, 9000, 10000, 5000, 10000},
                        new int[] {4000, 0, 3000, 10000, 5000, 10000}, 1.0),
                new PairwiseSample(new int[] {5000, 10000, 7000, 0, 0, 10000},
                        new int[] {5000, 10000, 7000, 0, 0, 3000}, 1.0),
                new PairwiseSample(new int[] {6500, 10000, 7500, 10000, 10000, 10000},
                        new int[] {2000, 0, 4000, 0, 10000, 10000}, 1.0),
                new PairwiseSample(new int[] {3000, 10000, 8500, 10000, 5000, 10000},
                        new int[] {3000, 10000, 2000, 10000, 5000, 10000}, 1.0),
                new PairwiseSample(new int[] {8500, 0, 6000, 10000, 10000, 10000},
                        new int[] {1000, 10000, 2000, 0, 0, 3000}, 1.0)
        );
        return trainProfile(samples, Profile.defaults());
    }

    private static final class BtCtx {
        final DecisionInput input;
        final Profile profile;
        final BossPhaseId currentPhase;
        BattleAction selectedAction;

        BtCtx(DecisionInput input, Profile profile, BossPhaseId currentPhase) {
            this.input = input;
            this.profile = profile;
            this.currentPhase = currentPhase;
        }
    }

    /** 全进程共享的一份不可变副本怪物行为树定义。 */
    private static final TreeDef<BtCtx> SHARED_TREE = buildSharedTree();

    private static TreeDef<BtCtx> buildSharedTree() {
        BehaviorTree.Builder<BtCtx> b = new BehaviorTree.Builder<>();

        int desperateSurvival = b.sequence(
                b.condition((ctx, bb) -> {
                    DecisionInput in = ctx.input;
                    Profile prof = ctx.profile;
                    if (!in.selfCanDefend() || ctx.currentPhase != BossPhaseId.DESPERATE) {
                        return false;
                    }
                    if (Integer.compareUnsigned(in.selfHpPermyriad(), prof.emergencyDefendHpPermyriad()) > 0) {
                        return false;
                    }
                    long readyRound = bb.getLongOr(BB_DEFEND_COOLDOWN_READY_ROUND, 0L);
                    return Long.compareUnsigned(Integer.toUnsignedLong(in.roundIndex()), readyRound) >= 0;
                }),
                b.action((ctx, bb) -> {
                    ctx.selectedAction = BattleAction.newBuilder()
                            .setActionType(eBattleActionType.BATTLE_ACTION_DEFEND)
                            .setTargetId(0)
                            .build();
                    long nextReady = Integer.toUnsignedLong(ctx.input.roundIndex())
                            + Integer.toUnsignedLong(ctx.profile.defendCooldownRounds());
                    bb.setLong(BB_DEFEND_COOLDOWN_READY_ROUND, nextReady);
                    return Status.SUCCESS;
                })
        );

        int utilityDecision = b.action((ctx, bb) -> {
            List<Candidate> cands = ctx.input.candidates();
            if (cands.isEmpty()) {
                return Status.FAILURE;
            }
            int phaseUrgency = switch (ctx.currentPhase) {
                case DESPERATE -> MonsterUtilityAi.UTILITY_SCALE;
                case ENRAGE -> MonsterUtilityAi.UTILITY_SCALE / 2;
                default -> 0;
            };
            List<ActionCandidate> scored = new ArrayList<>(cands.size());
            for (Candidate cand : cands) {
                BattleAction act = cand.action();
                int actionId = act.getActionType() == eBattleActionType.BATTLE_ACTION_SKILL
                        ? act.getSkillTableId()
                        : act.getActionTypeValue();
                int[] feats = cand.features().clone();
                feats[FEATURE_PHASE_URGENCY] = phaseUrgency;
                scored.add(new ActionCandidate(actionId, act.getTargetId(), cand.formationSlot(), feats));
            }
            Optional<Selection> best = MonsterUtilityAi.selectBestCandidate(scored, ctx.profile.utilityModel());
            if (best.isEmpty()) {
                return Status.FAILURE;
            }
            ctx.selectedAction = cands.get(best.get().index()).action();
            return Status.SUCCESS;
        });

        int root = b.reactiveSelector(desperateSurvival, utilityDecision);
        return b.build(root).orElseThrow();
    }

    private static final class PhaseCtx {
        int selfHpPermyriad = MonsterUtilityAi.UTILITY_SCALE;
        final int enrageHpPermyriad;
        final int desperateHpPermyriad;
        int combatEnterCount;
        int combatExitCount;

        PhaseCtx(int enrageHpPermyriad, int desperateHpPermyriad) {
            this.enrageHpPermyriad = enrageHpPermyriad;
            this.desperateHpPermyriad = desperateHpPermyriad;
        }
    }

    /** 单只副本怪的运行期 AI 控制器。 */
    public static final class Controller {
        private final Profile profile;
        private final PhaseCtx phaseCtx;
        private final Hfsm<PhaseCtx, Integer> phaseHfsm = new Hfsm<>();
        private final BehaviorTree.State btState = SHARED_TREE.createState();

        public Controller(Profile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            this.phaseCtx = new PhaseCtx(profile.enrageHpPermyriad(), profile.desperateHpPermyriad());
            initPhaseHfsm();
        }

        private void initPhaseHfsm() {
            phaseHfsm.addState(BossPhaseId.ROOT.id(), StateConfig.<PhaseCtx, Integer>create()
                    .initialChild(BossPhaseId.COMBAT.id())
                    .onEvent((ev, ctx) -> ev == EVENT_DIE
                            ? EventOutcome.transitionTo(BossPhaseId.DEAD.id())
                            : EventOutcome.ignored()));

            phaseHfsm.addState(BossPhaseId.COMBAT.id(), StateConfig.<PhaseCtx, Integer>create()
                    .parent(BossPhaseId.ROOT.id())
                    .initialChild(BossPhaseId.OPENING.id())
                    .onEnter(ctx -> ctx.combatEnterCount++)
                    .onExit(ctx -> ctx.combatExitCount++));

            phaseHfsm.addState(BossPhaseId.OPENING.id(), StateConfig.<PhaseCtx, Integer>create()
                    .parent(BossPhaseId.COMBAT.id())
                    .onUpdate((ctx, dt) -> {
                        if (Integer.compareUnsigned(ctx.selfHpPermyriad, ctx.desperateHpPermyriad) <= 0) {
                            return BossPhaseId.DESPERATE.id();
                        }
                        if (Integer.compareUnsigned(ctx.selfHpPermyriad, ctx.enrageHpPermyriad) <= 0) {
                            return BossPhaseId.ENRAGE.id();
                        }
                        return Hfsm.INVALID_STATE;
                    }));

            phaseHfsm.addState(BossPhaseId.ENRAGE.id(), StateConfig.<PhaseCtx, Integer>create()
                    .parent(BossPhaseId.COMBAT.id())
                    .onUpdate((ctx, dt) -> Integer.compareUnsigned(ctx.selfHpPermyriad, ctx.desperateHpPermyriad) <= 0
                            ? BossPhaseId.DESPERATE.id()
                            : Hfsm.INVALID_STATE));

            phaseHfsm.addState(BossPhaseId.DESPERATE.id(), StateConfig.<PhaseCtx, Integer>create()
                    .parent(BossPhaseId.COMBAT.id()));

            phaseHfsm.addState(BossPhaseId.DEAD.id(), StateConfig.<PhaseCtx, Integer>create()
                    .parent(BossPhaseId.ROOT.id()));

            phaseHfsm.start(BossPhaseId.ROOT.id(), phaseCtx);
        }

        /** 推进本回合 HFSM 阶段并执行共享行为树，返回选定的合法行动。 */
        public Optional<BattleAction> decideAction(DecisionInput input) {
            if (input == null || !input.selfAlive()) {
                notifyDead();
                return Optional.empty();
            }
            phaseCtx.selfHpPermyriad = input.selfHpPermyriad();
            phaseHfsm.update(phaseCtx, 1);

            BtCtx btCtx = new BtCtx(input, profile, currentPhase());
            if (SHARED_TREE.tick(btCtx, btState) != Status.SUCCESS) {
                return Optional.empty();
            }
            return Optional.ofNullable(btCtx.selectedAction);
        }

        /** 单位阵亡时触发 HFSM 死亡事件（冒泡至 ROOT → DEAD，退出 COMBAT 链）。 */
        public void notifyDead() {
            if (phaseHfsm.started() && currentPhase() != BossPhaseId.DEAD) {
                phaseHfsm.dispatch(EVENT_DIE, phaseCtx);
            }
        }

        public BossPhaseId currentPhase() {
            return BossPhaseId.fromId(phaseHfsm.currentLeaf());
        }

        public boolean isInCombat() {
            return phaseHfsm.isInState(BossPhaseId.COMBAT.id());
        }

        public int combatEnterCount() {
            return phaseCtx.combatEnterCount;
        }

        public int combatExitCount() {
            return phaseCtx.combatExitCount;
        }

        public Profile profile() {
            return profile;
        }
    }
}
