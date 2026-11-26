package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.combatai.MonsterUtilityAi;
import com.game.common.math.Unsigned;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleHighlightKind;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattleParticipationFact;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePingKind;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattlePrepPetOption;
import com.game.proto.BattlePrepPlayerState;
import com.game.proto.BattlePrepSealOption;
import com.game.proto.BattlePrepState;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleSurrenderVoteState;
import com.game.proto.BattleTacticalPing;
import com.game.proto.CreateBattleRequest;
import com.game.proto.FestivalBeastBattleContext;
import com.game.proto.SeasonSealEffect;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleHitKind;
import com.game.proto.eBattleOutcome;
import com.game.table.BuffTable;
import com.game.table.DungeonTable;
import com.game.table.MatchErrorTip;
import com.game.table.MonsterTable;
import com.game.table.Monsterdrop;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 回合制战斗确定性引擎：一场战斗一个实例（基线 {@code turn_battle_engine.{h,cpp}}，规格 docs/porting/battle-engine-spec.md）。
 *
 * <p>纯库：没有网络、时钟、计时器与日志；表数据经 {@link BattleData} 注入，随机数只走种子化的 {@link MersenneTwister64}，
 * 相同输入（请求、表、提交序列）产出逐字节相同的事件流与结算。除规格 §12.1 的有意差异 D1–D8 外，行为逐位照搬基线，
 * 包括 §11.1 列出的基线怪行为。
 *
 * <p>线程模型：非线程安全，与基线一样是单线程对象，由 6.2 battle 节点的房间限定在单个串行执行上下文里（规格 §10.6）。
 *
 * <p>生命周期：只能经 {@link #start(CreateBattleRequest, BattleData)} 得到；开局失败不产生实例（D1）。
 *
 * <p>内部分工（规格 §10.2）：本类负责开局、行动收集、回合编排（普攻 / 技能 / 防御 / 逃跑）、冷却、胜负、掉落、结算与快照；
 * {@link ActionChecks}（校验链）、{@link BuffEngine}（buff）、{@link ItemLedger}（道具）只是按职责拆开的文件，
 * 与本类共用同一份 {@link BattleContext}（单位、RNG、事件日志等），调用顺序严格照基线，尤其是规格 §8.4 的每个随机数消耗点。
 */
public final class TurnBattleEngine {

    /** 默认行动：普攻，目标 0（结算时必然重选，{@code FillDefaultActions}，{@code engine.cpp:681-685}）。 */
    private static final BattleAction DEFAULT_ACTION = BattleAction.newBuilder()
            .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
            .setTargetId(0)
            .build();

    /** 怪物的固定名字（{@code engine.cpp:267}）。 */
    private static final String MONSTER_NAME = "野怪";

    /** 开局请求（不可变）；道具余量另由 {@link ItemLedger} 承载，不就地改它（规格 §1.1）。 */
    private final CreateBattleRequest request;
    private final BattleContext ctx;
    private final ItemLedger ledger;
    private final ActionChecks checks;
    private final BuffEngine buffs;
    private final ClassSkillExecution classSkills;
    private final AutoClassSkillSelector autoClassSkills;
    /** actor_id → 本回合行动；按 actor_id 无符号升序（基线 {@code std::map}）。现在只做查找，用有序容器防止日后有人遍历它。 */
    private final NavigableMap<Long, BattleAction> pendingActions = new TreeMap<>(Long::compareUnsigned);
    /** 副本怪物 HFSM + 行为树 + 定点效用 AI 控制器（有序容器保确定性，不消耗 RNG）。 */
    private final NavigableMap<Long, DungeonMonsterAi.Controller> monsterAiControllers =
            new TreeMap<>(Long::compareUnsigned);
    /** uint32；回合上限（{@code engine.h:228}）。 */
    private int maxRounds = BattleConstants.DEFAULT_MAX_ROUNDS;
    private eBattleOutcome outcome = eBattleOutcome.BATTLE_OUTCOME_ONGOING;
    /** 最近一回合的出手序（含回合中途被跳过者）；下一次结算前保持不变（{@code engine.h:62-65}）。 */
    private List<Long> lastActionOrder = List.of();
    /** 论剑排位参与事实（有序容器保确定性，不消耗 RNG）。 */
    private final TreeSet<Long> enteredPlayers = new TreeSet<>(Long::compareUnsigned);
    private final TreeSet<Long> manualActionPlayers = new TreeSet<>(Long::compareUnsigned);
    private final TreeSet<Long> explicitAutoPlayers = new TreeSet<>(Long::compareUnsigned);
    private final TreeSet<Long> hadOperableTurnPlayers = new TreeSet<>(Long::compareUnsigned);
    /** 帮战守旗阵 AI 托管玩家集合：强制自动出手、拒收手动输入、不建个人结算。 */
    private final TreeSet<Long> aiControlledPlayers = new TreeSet<>(Long::compareUnsigned);
    /** PVP 延迟投降状态与连续未手动挂机计数（确定性、零挂钟）。 */
    private int surrenderedTeam = -1;
    private final BattleSurrenderVoteState.Builder[] teamSurrenderVotes = new BattleSurrenderVoteState.Builder[] {
            BattleSurrenderVoteState.newBuilder(),
            BattleSurrenderVoteState.newBuilder()
    };
    private final NavigableMap<Long, Integer> consecutiveIdleRoundsByPlayer = new TreeMap<>(Long::compareUnsigned);
    /** 群仙问道赛 V3 MVP & 论剑 V4 复盘高光累计统计（本人及本人出战宝宝，每回合末从权威事件流确定性汇总）。 */
    private final NavigableMap<Long, Long> totalDamageDealtByPlayer = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Long> totalHealingDoneByPlayer = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Integer> totalControlsLandedByPlayer = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Long> primaryFocusDamageByPlayer = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Long> totalShieldGrantedByPlayer = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Long> damageTakenByPlayer = new TreeMap<>(Long::compareUnsigned);
    private final TreeSet<Long> lowHpExperiencedPlayers = new TreeSet<>(Long::compareUnsigned);
    private int peakEscalationStacks;
    /** 论剑 V4 队内一键战术标点（按阵营隔离，每名队友至多保留 1 条最新意图，回合推进清空）。 */
    @SuppressWarnings("unchecked")
    private final NavigableMap<Long, BattleTacticalPing>[] teamPings = new NavigableMap[] {
            new TreeMap<Long, BattleTacticalPing>(Long::compareUnsigned),
            new TreeMap<Long, BattleTacticalPing>(Long::compareUnsigned)
    };
    private final NavigableMap<Long, Integer> roundPingCountByPlayer = new TreeMap<>(Long::compareUnsigned);
    /** 论剑 V3 战前 15 秒明牌变阵状态（纯确定性，零墙钟）。 */
    private boolean prepActive;
    private long nextPetActorIndex;
    private final NavigableMap<Long, PrepPlayerOptions> prepOptionsByPlayer = new TreeMap<>(Long::compareUnsigned);

    private static final class PrepPlayerOptions {
        int teamIndex;
        int activeSeasonId;
        boolean aiControlled;
        boolean ready;
        int selectedSealId;
        long selectedPetId;
        final List<SeasonSealEffect> availableSeals = new ArrayList<>();
        final List<BattlePetSnapshot> availablePets = new ArrayList<>();
    }

    public record SurrenderVoteResult(int errorTip, BattleSurrenderVoteState voteState) {
    }

    public record UpdatePrepResult(int errorTip, BattlePrepState prepState) {
    }

    public record BattlePingResult(int errorTip, BattleTacticalPing ping) {
    }

    private TurnBattleEngine(CreateBattleRequest request, BattleData data) {
        this.request = request;
        boolean competitivePvp = (request.hasRankedContext() && request.getRankedContext().getSeasonId() != 0)
                || request.getMatchMode() == BattleConstants.MATCH_MODE_POLARITY_5V5
                || request.getMatchMode() == BattleConstants.MATCH_MODE_SEASON_TOURNAMENT;
        // 基线 Initialize 第 4 步：拷请求、rng.seed(seed)、roundIndex = 1、outcome = ONGOING（engine.cpp:69-72）
        this.ctx = new BattleContext(data, request.getMatchMode(), request.getSeed(), competitivePvp);
        this.ledger = new ItemLedger(ctx, request);
        this.checks = new ActionChecks(ctx, ledger);
        this.checks.setCaptureRequest(request);
        this.buffs = ctx.configured.legacy;
        this.classSkills = new ClassSkillExecution(ctx, buffs, checks);
        this.autoClassSkills = new AutoClassSkillSelector(ctx, checks, classSkills, buffs);
    }

    // =====================================================================================================
    // 开局（engine.cpp:42-309）
    // =====================================================================================================

    /**
     * 校验 + 初始化 + 播种（规格 §1.4）；失败返回 {@link BattleStart.Rejected}，不产生实例（D1）。
     *
     * <p>步骤顺序固定：battle_id / 玩家数 → 每队人数（只数 team ≤ 1 的快照，宝宝不计）→ 播种、回合上限 → 玩家 → 宝宝（必须在全部玩家
     * 之后，阵位按插入序）→ PVE 才加怪物 → 两边都要有单位（team 0 记 A 方，其余记 B 方）→ 只对玩家做快照 buff 清洗（必须等全部单位
     * 就位，施法者改写要能查到怪物与宝宝）。各项检查的先后只影响报出哪个原因，不影响「收或拒」的结果集合。
     *
     * @param data 表数据源，整局绑定；不得为 null
     */
    public static BattleStart start(CreateBattleRequest request, BattleData data) {
        return start(request, data, Map.of());
    }

    /**
     * 同 {@link #start(CreateBattleRequest, BattleData)}，可选传入副本怪物 AI 配置（key = {@code monster_table_id}，
     * 0 为缺省兜底配置）。传入空 map 时与两参入口完全一致，保持存量无 AI 对局的逐字节确定性回放。
     */
    public static BattleStart start(CreateBattleRequest request, BattleData data,
                                    Map<Integer, DungeonMonsterAi.Profile> monsterAiProfiles) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(data, "data");
        if (request.getBattleId() == 0) {
            return new BattleStart.Rejected(InitRejection.MISSING_BATTLE_ID, "battle_id 为 0");
        }
        if (request.getPlayersCount() == 0) {
            return new BattleStart.Rejected(InitRejection.NO_PLAYERS,
                    "没有玩家快照: battle_id=" + Long.toUnsignedString(request.getBattleId()));
        }

        // 每队玩家数上限（engine.cpp:53-67）：team_index 越界交给 initPlayers 兜住，这里只数合法队伍
        int[] teamPlayerCounts = new int[2];
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            if (Integer.compareUnsigned(snapshot.getTeamIndex(), 1) <= 0) {
                teamPlayerCounts[snapshot.getTeamIndex()]++;
            }
        }
        for (int teamIndex = 0; teamIndex < 2; teamIndex++) {
            if (teamPlayerCounts[teamIndex] > BattleConstants.MAX_BATTLE_TEAM_SIZE) {
                return new BattleStart.Rejected(InitRejection.TEAM_OVERSIZE,
                        "CreateBattle 队伍人数超限: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                + " team_index=" + teamIndex + " players=" + teamPlayerCounts[teamIndex]
                                + " limit=" + BattleConstants.MAX_BATTLE_TEAM_SIZE);
            }
        }

        TurnBattleEngine engine = new TurnBattleEngine(request, data);

        // 回合上限与玩法配置：优先 DungeonTable.round_limit，其次物法易势默认 16 回合，正式排位缺省收敛到 20 回合，其余沿用 time_limit 换算。
        Optional<DungeonTable> dungeonRow = data.dungeon(request.getBattleConfigId());
        int bossTrialKind = BattleConstants.BOSS_TRIAL_KIND_NONE;
        int battleDifficulty = 0;
        boolean hasExplicitRoundLimit = false;
        if (dungeonRow.isPresent()) {
            DungeonTable d = dungeonRow.get();
            bossTrialKind = d.getBossTrialKind();
            battleDifficulty = d.getDifficulty();
            if (d.getRoundLimit() != 0) {
                engine.maxRounds = d.getRoundLimit();
                hasExplicitRoundLimit = true;
            } else if (d.getTimeLimit() != 0) {
                engine.maxRounds = BattleRules.roundsFromMillis(Integer.toUnsignedLong(d.getTimeLimit()) * 1000L);
            }
        }
        if (engine.isRankedBattle() && !hasExplicitRoundLimit) {
            engine.maxRounds = Math.min(engine.maxRounds, BattleConstants.RANKED_DEFAULT_MAX_ROUNDS);
        }
        if (request.getMatchMode() == BattleConstants.MATCH_MODE_POLARITY_5V5
                && bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_NONE) {
            bossTrialKind = BattleConstants.BOSS_TRIAL_KIND_POLARITY_5V5;
            if (engine.maxRounds == BattleConstants.DEFAULT_MAX_ROUNDS) {
                engine.maxRounds = BattleConstants.POLARITY_DEFAULT_MAX_ROUNDS;
            }
        }
        // 帮会全天帮战 V2 门禁：MATCH_MODE_GUILD_WAR_PVP(9) / SIEGE(10) 与 BATTLE_ACTIVITY_KIND_GUILD_WAR 必须互为充要条件。
        boolean isGuildWarMode = BattleConstants.isGuildWarMatchMode(request.getMatchMode());
        boolean hasGuildWarActivity = request.hasActivityContext()
                && request.getActivityContext().getKind() == eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR;
        if (isGuildWarMode || hasGuildWarActivity) {
            if (!isGuildWarMode || !hasGuildWarActivity || !request.getActivityContext().hasGuildWar()) {
                return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                        "帮会全天帮战 V2 缺少合法上下文或匹配模式不匹配: battle_id=" + Long.toUnsignedString(request.getBattleId()));
            }
            if (!hasExplicitRoundLimit) {
                engine.maxRounds = Math.min(engine.maxRounds, BattleConstants.GUILD_WAR_DEFAULT_MAX_ROUNDS);
            }
        }
        // 瑞兽迎春硬性准入门禁：boss_trial_kind=7 必须且仅能携带合法冻结的 FESTIVAL_BEAST 上下文、单人携单只锁定出战宝宝。
        boolean hasFestivalActivity = request.hasActivityContext()
                && request.getActivityContext().getKind() == eBattleActivityKind.BATTLE_ACTIVITY_KIND_FESTIVAL_BEAST;
        if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_FESTIVAL_BEAST || hasFestivalActivity) {
            if (bossTrialKind != BattleConstants.BOSS_TRIAL_KIND_FESTIVAL_BEAST
                    || !hasFestivalActivity
                    || request.getMatchMode() != BattleConstants.MATCH_MODE_PVE_SOLO
                    || request.getPlayersCount() != 1
                    || request.getPlayers(0).getPetsCount() != 1
                    || !request.getActivityContext().hasFestivalBeast()) {
                return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                        "瑞兽迎春开局缺少合法单人携宠上下文: battle_id=" + Long.toUnsignedString(request.getBattleId()));
            }
            FestivalBeastBattleContext fb = request.getActivityContext().getFestivalBeast();
            if (fb.getActivityInstanceId() == 0
                    || fb.getRewardIdentity().isEmpty()
                    || fb.getRequiredWins() == 0
                    || fb.getLockedPetId() == 0
                    || request.getPlayers(0).getPets(0).getPetId() != fb.getLockedPetId()
                    || fb.getBossHealth() == 0
                    || fb.getRoundLimit() == 0
                    || fb.getCycleRounds() == 0
                    || fb.getFirstHeavyRound() == 0
                    || Integer.compareUnsigned(fb.getFirstHeavyRound(), fb.getCycleRounds()) > 0
                    || fb.getTelegraphLeadRounds() == 0
                    || Integer.compareUnsigned(fb.getTelegraphLeadRounds(), fb.getFirstHeavyRound()) >= 0
                    || fb.getHeavyDamagePercent() == 0) {
                return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                        "瑞兽迎春活动参数或锁定宝宝非法: battle_id=" + Long.toUnsignedString(request.getBattleId()));
            }
            engine.maxRounds = fb.getRoundLimit();
            engine.ctx.encounter.configureFestivalBeast(fb);
        }

        if (!BossTrialWeeklyAffixRules.isValidOverride(request.getBossWeeklyAffixId())) {
            return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                    "首领试炼周常五行词缀覆盖值非法: battle_id=" + Long.toUnsignedString(request.getBattleId())
                            + " affix=" + Integer.toUnsignedString(request.getBossWeeklyAffixId()));
        }

        engine.ctx.setMaxRounds(engine.maxRounds);
        engine.ctx.encounter.configure(bossTrialKind, battleDifficulty);
        int weeklyAffixId = BossTrialWeeklyAffixRules.resolveForBattle(
                BattleConstants.isBossTrialKind(bossTrialKind), false,
                request.getCreatedAtMs(), request.getBossWeeklyAffixId());
        engine.ctx.encounter.configureWeeklyAffix(weeklyAffixId);
        if (request.hasTrialContext()) {
            engine.ctx.encounter.configureTrial(
                    request.getTrialContext().getGuardMonsterTableId(),
                    request.getTrialContext().getGuardStanceBuffId());
        }

        BattleStart.Rejected rejected = engine.initPlayers();
        if (rejected != null) {
            return rejected;
        }
        rejected = engine.initPets();
        if (rejected != null) {
            return rejected;
        }
        rejected = engine.initTacticalPrep();
        if (rejected != null) {
            return rejected;
        }
        if (engine.ctx.isPve()) {
            rejected = engine.initMonsters();
            if (rejected != null) {
                return rejected;
            }
        }

        // 两侧都必须有单位（engine.cpp:96-104）：team 0 记 A 方，其余一律记 B 方
        boolean hasSideA = false;
        boolean hasSideB = false;
        for (BattleUnit unit : engine.ctx.units()) {
            if (unit.teamIndex() == 0) {
                hasSideA = true;
            } else {
                hasSideB = true;
            }
        }
        if (!hasSideA || !hasSideB) {
            return new BattleStart.Rejected(InitRejection.ONE_SIDED,
                    "开局后有一方没有任何单位: battle_id=" + Long.toUnsignedString(request.getBattleId())
                            + " side_a=" + hasSideA + " side_b=" + hasSideB
                            + " match_mode=" + Integer.toUnsignedString(request.getMatchMode()));
        }

        // 快照 buff 清洗：只对玩家，且必须等全部单位就位（engine.cpp:106-111）
        for (BattleUnit unit : engine.ctx.units()) {
            if (unit.isPlayer()) {
                engine.buffs.sanitizeSnapshotBuffs(unit);
            }
        }
        // 战前 15 秒明牌变阵期间延迟到 finalizePrepPhase 锁阵后再初始化开场光环/被动，防止被换掉的首发宠残留旧光环。
        if (!engine.prepActive) {
            engine.ctx.configured.initialize();
        }
        engine.bindDungeonMonsterAis(monsterAiProfiles);
        engine.ctx.encounter.initSpecialState(request.getMatchMode(), engine.maxRounds);
        // 锦标赛 V2 五行灵脉初始化：设置生效灵脉，土厚灵脉开场赋予全体 8% 气血护盾（与 C++ 引擎逐项对齐）。
        if (request.hasTournamentContext()) {
            var leyline = request.getTournamentContext().getLeylineElement();
            engine.ctx.setActiveTournamentLeyline(leyline);
            if (leyline == com.game.proto.TournamentLeylineElement.TOURNAMENT_LEYLINE_EARTH) {
                for (BattleUnit unit : engine.ctx.units()) {
                    if (unit.isActive() && Long.compareUnsigned(unit.maxHealth(), 0L) > 0) {
                        long shield = Math.max(1L, Long.divideUnsigned(unit.maxHealth() * 8L, 100L));
                        unit.addTournamentEarthShield(shield);
                    }
                }
            }
        }
        // 赛季全员对称周环境词缀：未启用锦标赛五行灵脉、非瑞兽迎春/五行幻境爬塔且全体玩家快照一致携带 1..4 时激活，分歧 fail-closed 退回 0。
        boolean hasFestival = request.hasActivityContext()
                && request.getActivityContext().getKind() == eBattleActivityKind.BATTLE_ACTIVITY_KIND_FESTIVAL_BEAST;
        boolean hasTower = request.getPlayersList().stream()
                .anyMatch(p -> p.hasTowerContext() && p.getTowerContext().getFloor() != 0);
        if (engine.ctx.activeTournamentLeyline() == com.game.proto.TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED
                && !hasFestival && !hasTower && !request.hasCustomDuelRules()
                && request.getPlayersCount() > 0) {
            int unanimous = SeasonRules.WEEKLY_AFFIX_NONE;
            boolean consistent = true;
            for (BattlePlayerSnapshot snap : request.getPlayersList()) {
                int candidate = snap.getSeasonWeeklyAffixId();
                if (!SeasonRules.isValidWeeklyAffixId(candidate)) {
                    consistent = false;
                    break;
                }
                if (unanimous == SeasonRules.WEEKLY_AFFIX_NONE) {
                    unanimous = candidate;
                } else if (unanimous != candidate) {
                    consistent = false;
                    break;
                }
            }
            if (consistent && SeasonRules.isValidWeeklyAffixId(unanimous)) {
                engine.ctx.setActiveSeasonWeeklyAffixId(unanimous);
            }
        }
        // 首领试炼周常金周反向考验：敌方全体开局获得 8% 最大气血金甲护盾。
        if (weeklyAffixId == BossTrialWeeklyAffixRules.AFFIX_METAL) {
            for (BattleUnit unit : engine.ctx.units()) {
                if (unit.isActive() && unit.isMonster() && unit.teamIndex() == 1
                        && Long.compareUnsigned(unit.maxHealth(), 0L) > 0) {
                    long shield = Math.max(1L, Long.divideUnsigned(
                            unit.maxHealth() * BossTrialWeeklyAffixRules.METAL_ENEMY_OPENING_SHIELD_PCT, 100L));
                    unit.addTournamentEarthShield(shield);
                }
            }
        }
        return new BattleStart.Started(engine);
    }

    /**
     * 玩家入场（{@code InitPlayers}，{@code engine.cpp:117-176}；规格 §1.5）：按快照顺序逐个，player_id 为 0 或 team &gt; 1（uint32）拒；
     * bit63 置位拒；重复参战拒。字段按规格 §1.3 填；快照 buff 原样追加并推高实例号计数器（在清洗之前）；技能只收表里有行且可施放的
     * （不去重）；建结算条目。不做任何夹紧（health &gt; max_health 也照收）。
     *
     * @return 拒绝原因；全部成功返回 null
     */
    private BattleStart.Rejected initPlayers() {
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            long playerId = snapshot.getPlayerId();
            if (playerId == 0 || Integer.compareUnsigned(snapshot.getTeamIndex(), 1) > 0) {
                return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                        "玩家快照非法: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                + " player_id=" + Long.toUnsignedString(playerId)
                                + " team_index=" + Integer.toUnsignedString(snapshot.getTeamIndex()));
            }
            // bit63 是引擎局内号（怪物 / 宝宝）的保留段：落进来就是编排层把别的东西当成了 player_id
            if ((playerId & BattleConstants.ENGINE_LOCAL_ACTOR_ID_FLAG) != 0) {
                return new BattleStart.Rejected(InitRejection.RESERVED_PLAYER_ID,
                        "CreateBattle player_id 落在引擎局内号保留段: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                + " player_id=" + Long.toUnsignedString(playerId));
            }
            if (ctx.findActor(playerId) != null) {
                return new BattleStart.Rejected(InitRejection.DUPLICATE_PLAYER,
                        "玩家重复参战: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                + " player_id=" + Long.toUnsignedString(playerId));
            }

            BaseAttributesComp attributes = snapshot.getBaseAttributes();
            BattleActorState.Builder actor = BattleActorState.newBuilder()
                    .setActorId(playerId)
                    .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                    .setTeamIndex(snapshot.getTeamIndex())
                    .setName(snapshot.getPlayerName())
                    .setAppearanceId(snapshot.getAppearanceId())
                    .setClassId(snapshot.getClassId())
                    .setGender(snapshot.getGender())
                    .setLevel(snapshot.getLevel())
                    .setAttributes(attributes)
                    .setMaxHealth(fallbackMax(snapshot.getMaxHealth(), attributes.getHealth()))
                    .setMaxMana(fallbackMax(snapshot.getMaxMana(), attributes.getMana()))
                    .setPhysicalAttack(snapshot.getPhysicalAttack())
                    .setMagicAttack(snapshot.getMagicAttack())
                    .setDefense(snapshot.getDefense())
                    .setFormationSlot(ctx.nextFormationSlot(snapshot.getTeamIndex()));
            if (isRankedBattle()) {
                int streak = request.getRankedContext().getMemberWinStreaksOrDefault(playerId, 0);
                if (Integer.compareUnsigned(streak, BattleConstants.MIN_WIN_STREAK_HIGHLIGHT) >= 0) {
                    actor.setWinStreak(streak);
                }
            }
            // 只复制明确提供的玩家加成；缺省子消息不能因读取而产生 presence，宝宝与怪物不继承主人的装备。
            ctx.configured.equipment.inspectLearnedSkills(snapshot);
            if (snapshot.hasEquipmentEffects() && !BattleEquipment.initialize(actor,snapshot.getEquipmentEffects()))
                return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,"装备快照非法");
            if (snapshot.hasCombat()) actor.setCombat(snapshot.getCombat());
            // 参战 buff 原样拷贝；实例号也纳入局内自增域，避免与新加 buff 撞号
            for (BattleBuffEntry buff : snapshot.getBuffsList()) {
                actor.addBuffs(buff);
                buffs.reserveSnapshotBuffId(buff.getBuffId());
            }
            int activeSeasonId = snapshot.getActiveSeasonId();
            // 职业重数按当前职业、等级与当前活跃赛季重新夹取；同 exclusive_group 互斥战法按 skill_table_id 升序确定性保留首条。
            List<Map.Entry<Integer, Integer>> sortedRanks = new ArrayList<>(snapshot.getSkillRanksMap().entrySet());
            sortedRanks.sort(Map.Entry.comparingByKey(Integer::compareUnsigned));
            TreeSet<Integer> occupiedExclusiveGroups = new TreeSet<>(Integer::compareUnsigned);
            for (Map.Entry<Integer, Integer> entry : sortedRanks) {
                int skillId = entry.getKey();
                int storedRank = entry.getValue();
                Optional<SkillTable> skillOpt = ctx.data().skill(skillId);
                if (skillOpt.isEmpty()) continue;
                SkillTable skill = skillOpt.get();
                if (!SeasonRules.isSkillActiveInSeason(skill.getSeasonId(), activeSeasonId)) continue;
                if (skill.getBattleModel() == 1 ? !ConfiguredBattle.subject(actor, skill)
                        : !BattleRules.isClassSkill(skill) || skill.getClassId() != actor.getClassId()) continue;
                int rank = ClassSkillRules.effectiveRank(storedRank, actor.getLevel(), skill.getUnlockLevel(), skill.getMaxRank());
                if (rank == 0) continue;
                if (skill.getExclusiveGroup() != 0 && !occupiedExclusiveGroups.add(skill.getExclusiveGroup())) continue;
                actor.putSkillRanks(skillId, rank);
            }
            // 技能只收回合制可施放、赛季归属合法且已具有有效职业重数的；外来快照不能授予未学或过季技能。
            for (int skillTableId : snapshot.getSkillTableIdsList()) {
                Optional<SkillTable> skillRow = ctx.data().skill(skillTableId);
                if (skillRow.isEmpty() || !BattleRules.isTurnBattleCastableSkill(skillRow.get())
                        || !SeasonRules.isSkillActiveInSeason(skillRow.get().getSeasonId(), activeSeasonId)) {
                    continue;
                }
                if ((skillRow.get().getBattleModel() == 1 || BattleRules.isClassSkill(skillRow.get()))
                        && actor.getSkillRanksOrDefault(skillTableId, 0) == 0) continue;
                actor.addSkillTableIds(skillTableId);
            }
            if (snapshot.getAiControlled()) {
                actor.setIsAuto(true);
                aiControlledPlayers.add(playerId);
            }
            ctx.units().add(new BattleUnit(actor));

            if (!snapshot.getAiControlled()) {
                ctx.settlements().put(playerId, BattleSettlementData.newBuilder()
                        .setBattleId(request.getBattleId())
                        .setPlayerId(playerId));
            }
        }
        return null;
    }

    /**
     * 宝宝入场（{@code InitPets}，{@code engine.cpp:178-238}；规格 §1.6）：局内序号跨所有快照全局递增（先按快照、再按快照内顺序），
     * actor_id = 宝宝号段 + 序号，真实 pet_id 另存。pet_id 为 0 拒；同一只宝宝（按 pet_id）已入场拒；快照自带的 owner_player_id
     * 非 0 且不等于所在快照的玩家拒（归属只认所在快照）。与主人同队、恒为挂机，技能按玩家相同规则过滤，不建结算条目。
     *
     * @return 拒绝原因；全部成功返回 null
     */
    private BattleStart.Rejected initPets() {
        nextPetActorIndex = 0;
        boolean allowTempPet = usesFreePreset();
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            for (BattlePetSnapshot pet : snapshot.getPetsList()) {
                boolean competitiveTemp = pet.getCompetitiveTemporary();
                if (competitiveTemp && !allowTempPet) {
                    return new BattleStart.Rejected(InitRejection.PET_ID_ZERO,
                            "非免费预设对局禁止临时竞技宝宝: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                if (pet.getPetId() == 0 && !(competitiveTemp && allowTempPet)) {
                    return new BattleStart.Rejected(InitRejection.PET_ID_ZERO,
                            "宝宝 pet_id 为 0: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                if (pet.getPetId() != 0 && petAlreadyJoined(pet.getPetId())) {
                    return new BattleStart.Rejected(InitRejection.DUPLICATE_PET,
                            "CreateBattle 同一只宝宝重复参战: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " pet_id=" + Long.toUnsignedString(pet.getPetId()));
                }
                long actorId = BattleConstants.PET_ACTOR_ID_BASE + nextPetActorIndex;
                nextPetActorIndex++;
                if (pet.getOwnerPlayerId() != 0 && pet.getOwnerPlayerId() != snapshot.getPlayerId()) {
                    return new BattleStart.Rejected(InitRejection.PET_OWNER_MISMATCH,
                            "CreateBattle 宝宝归属与所在快照不一致: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " pet_id=" + Long.toUnsignedString(pet.getPetId())
                                    + " owner=" + Long.toUnsignedString(pet.getOwnerPlayerId())
                                    + " snapshot_player=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                BattleActorState.Builder actor = buildPetActor(snapshot, pet, actorId, ctx.nextFormationSlot(snapshot.getTeamIndex()));
                if (actor == null) {
                    return new BattleStart.Rejected(InitRejection.INVALID_PLAYER, "宝宝条目快照非法");
                }
                ctx.units().add(new BattleUnit(actor));
            }
        }
        return null;
    }

    private BattleActorState.Builder buildPetActor(BattlePlayerSnapshot ownerSnap, BattlePetSnapshot pet,
                                                   long actorId, int formationSlot) {
        if (!BattleEquipment.validPet(pet.getPetCultivation())) {
            return null;
        }
        BaseAttributesComp attributes = pet.getBaseAttributes();
        BattleActorState.Builder actor = BattleActorState.newBuilder()
                .setActorId(actorId)
                .setPetId(pet.getPetId())
                .setCompetitiveTemporary(pet.getCompetitiveTemporary())
                .setPetCultivation(pet.getPetCultivation())
                .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PET)
                .setTeamIndex(ownerSnap.getTeamIndex())
                .setOwnerPlayerId(ownerSnap.getPlayerId())
                .setPetTableId(pet.getPetTableId())
                .setName(pet.getPetName())
                .setLevel(pet.getLevel())
                .setAttributes(attributes)
                .setMaxHealth(fallbackMax(pet.getMaxHealth(), attributes.getHealth()))
                .setMaxMana(fallbackMax(pet.getMaxMana(), attributes.getMana()))
                .setPhysicalAttack(pet.getPhysicalAttack())
                .setMagicAttack(pet.getMagicAttack())
                .setDefense(pet.getDefense())
                .setFormationSlot(formationSlot)
                // 宝宝没有客户端行动权：标挂机，由默认行动路径代打，也不卡 allPlayersReady（engine.cpp:228-230）
                .setIsAuto(true);
        pet.getSkillRanksMap().forEach((id, stored) -> ctx.data().skill(id).ifPresent(row -> {
            if (!ConfiguredBattle.subject(actor, row)) return;
            int rank = ClassSkillRules.effectiveRank(stored, actor.getLevel(), row.getUnlockLevel(), row.getMaxRank());
            if (rank != 0) actor.putSkillRanks(id, rank);
        }));
        for (int skillTableId : pet.getSkillTableIdsList()) {
            Optional<SkillTable> skillRow = ctx.data().skill(skillTableId);
            if (skillRow.isEmpty() || !BattleRules.isTurnBattleCastableSkill(skillRow.get()) || BattleRules.isClassSkill(skillRow.get())) continue;
            var row = skillRow.get();
            if (row.getBattleModel() == 1) {
                if (!ConfiguredBattle.subject(actor, row)) continue;
                if (!actor.containsSkillRanks(skillTableId)) {
                    if (pet.containsSkillRanks(skillTableId) || ClassSkillRules.rankLimit(actor.getLevel(), row.getUnlockLevel(), row.getMaxRank()) == 0) continue;
                    actor.putSkillRanks(skillTableId, 1);
                }
            }
            actor.addSkillTableIds(skillTableId);
        }
        if (actor.getSkillRanksCount() > 0) actor.setIsAuto(false);
        return actor;
    }

    /** 论剑 V3 战前 15 秒明牌变阵初始化（对齐 C++ {@code InitTacticalPrep}）。 */
    private BattleStart.Rejected initTacticalPrep() {
        prepActive = false;
        prepOptionsByPlayer.clear();
        // 仅正式自养论剑与赛季战队锦标赛开启战前 15 秒明牌变阵；PVE / 免费预设娱乐场 / 切磋 / 帮战零打扰。
        boolean modeEligible = !ctx.isPve() && !usesFreePreset()
                && (isRankedBattle() || request.getMatchMode() == BattleConstants.MATCH_MODE_SEASON_TOURNAMENT);
        if (!modeEligible) {
            return null;
        }
        TreeSet<Long> allBattlePetIds = new TreeSet<>(Long::compareUnsigned);
        for (BattleUnit unit : ctx.units()) {
            if (unit.isPet() && unit.petId() != 0L) {
                allBattlePetIds.add(unit.petId());
            }
        }
        boolean hasAnyChoice = false;
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            PrepPlayerOptions opt = new PrepPlayerOptions();
            opt.teamIndex = snapshot.getTeamIndex();
            opt.activeSeasonId = snapshot.getActiveSeasonId();
            opt.aiControlled = snapshot.getAiControlled();
            opt.ready = snapshot.getAiControlled();

            TreeSet<Integer> seenSealIds = new TreeSet<>(Integer::compareUnsigned);
            if (snapshot.hasEquipmentEffects() && snapshot.getEquipmentEffects().hasSeasonSeal()) {
                SeasonSealEffect equippedSeal = snapshot.getEquipmentEffects().getSeasonSeal();
                opt.selectedSealId = equippedSeal.getSealId();
                if (seenSealIds.add(equippedSeal.getSealId())) {
                    opt.availableSeals.add(equippedSeal);
                }
            }
            for (SeasonSealEffect seal : snapshot.getAvailableSeasonSealsList()) {
                if (!SeasonRules.validSealEffect(seal.getSeasonId(), seal.getSealId(), seal.getEffectId(),
                        seal.getBonusBps(), seal.getCapBps(), seal.getPerRoundLimit(),
                        seal.getBattleLimit(), seal.getActiveRounds(), seal.getHealthThresholdBps())) {
                    return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                            "战前候选赛季印非法: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId())
                                    + " seal_id=" + Integer.toUnsignedString(seal.getSealId()));
                }
                if (snapshot.getActiveSeasonId() != 0 && seal.getSeasonId() != snapshot.getActiveSeasonId()) {
                    return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                            "战前候选赛季印赛季归属不匹配: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                if (seenSealIds.add(seal.getSealId())) {
                    opt.availableSeals.add(seal);
                }
            }
            opt.availableSeals.sort((a, b) -> Integer.compareUnsigned(a.getSealId(), b.getSealId()));

            if (snapshot.getPetsCount() > 0) {
                opt.availablePets.add(snapshot.getPets(0));
                opt.selectedPetId = snapshot.getPets(0).getPetId();
            }
            for (BattlePetSnapshot standby : snapshot.getStandbyPetsList()) {
                if (standby.getCompetitiveTemporary() || standby.getPetId() == 0L) {
                    return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                            "备选灵宠 pet_id 非法或误带临时标记: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                if (standby.getOwnerPlayerId() != 0L && standby.getOwnerPlayerId() != snapshot.getPlayerId()) {
                    return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                            "备选灵宠归属不一致: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                if (!BattleEquipment.validPet(standby.getPetCultivation())) {
                    return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                            "备选灵宠养成快照非法: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                if (standby.getPetId() == opt.selectedPetId) {
                    continue;
                }
                if (!allBattlePetIds.add(standby.getPetId())) {
                    return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                            "备选灵宠 pet_id 跨场重复: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " pet_id=" + Long.toUnsignedString(standby.getPetId()));
                }
                opt.availablePets.add(standby);
            }

            if (!opt.aiControlled
                    && (opt.availableSeals.size() > 1 || opt.availablePets.size() > 1
                    || (opt.selectedPetId == 0L && !opt.availablePets.isEmpty()))) {
                hasAnyChoice = true;
            }
            prepOptionsByPlayer.put(snapshot.getPlayerId(), opt);
        }

        if (hasAnyChoice) {
            prepActive = true;
        } else {
            prepOptionsByPlayer.clear();
        }
        return null;
    }

    private boolean petAlreadyJoined(long petId) {
        for (BattleUnit unit : ctx.units()) {
            if (unit.isPet() && unit.petId() == petId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 怪物入场（{@code InitMonsters}，{@code engine.cpp:240-259}；规格 §1.7），只在 PVE：参考等级 = max(1, 所有玩家快照等级)（宝宝不参与，
     * uint32）；普通 PVE 按进攻方玩家数循环生成完整怪物组，最多十只，宝宝不计人数。
     * 活动对局保留固定组；空组仍是每个玩家快照一只兜底怪（id 0）。
     */
    private BattleStart.Rejected initMonsters() {
        int referenceLevel = 1;
        int attackerCount = 0;
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            if (snapshot.getTeamIndex() == 0) {
                attackerCount++;
            }
            // std::max(referenceLevel, level) = referenceLevel < level ? level : referenceLevel（uint32）
            if (Integer.compareUnsigned(referenceLevel, snapshot.getLevel()) < 0) {
                referenceLevel = snapshot.getLevel();
            }
        }
        ctx.encounter.setInitialAttackerHumanCount(attackerCount);
        boolean isBossTrialOrRescue = ctx.encounter.isBossTrialOrRescue();
        List<Integer> monsterIds = new ArrayList<>(ctx.data().dungeonMonsterIds(request.getBattleConfigId()));
        if (monsterIds.isEmpty()) {
            monsterIds = new ArrayList<>(request.getPlayersCount());
            for (int i = 0; i < request.getPlayersCount(); i++) {
                monsterIds.add(0);
            }
        } else if (!isBossTrialOrRescue && attackerCount > 0 && request.getActivityContext().getKindValue() == 0) {
            // 未知非零活动 kind 也视为活动；首领试炼改走精简精英阵，不复制白板杂兵。
            long perPlayer = Integer.toUnsignedLong(ctx.data().dungeonMonstersPerPlayer(request.getBattleConfigId()));
            if (perPlayer > 0) {
                int count = (int) Math.min(BattleConstants.MAX_SCALED_PVE_MONSTERS,
                        attackerCount * Math.min(perPlayer, BattleConstants.MAX_SCALED_PVE_MONSTERS));
                List<Integer> scaled = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    scaled.add(monsterIds.get(i % monsterIds.size()));
                }
                monsterIds = scaled;
            }
        }
        // 首领试炼 V2 精简精英阵：单卫 2..3 人加破军卫(109)、4..5 人加鼓噪卫(110)；双卫 3..5 人加破军卫(109)；全场至多 4 怪。
        if (ctx.encounter.isBossTrial()) {
            if (ctx.encounter.bossTrialKind() == BattleConstants.BOSS_TRIAL_KIND_BREAK_DUAL_GUARD) {
                if (attackerCount >= 3 && ctx.data().monster(BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER).isPresent()) {
                    monsterIds.add(BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER);
                }
            } else {
                if (attackerCount >= 2 && ctx.data().monster(BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER).isPresent()) {
                    monsterIds.add(BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER);
                }
                if (attackerCount >= 4 && ctx.data().monster(BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER).isPresent()) {
                    monsterIds.add(BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER);
                }
            }
        }
        long hpScale = isBossTrialOrRescue ? Math.max(1, attackerCount) : 1L;
        if (isFestivalBeastBattle()) {
            if (monsterIds.size() != 1) {
                return new BattleStart.Rejected(InitRejection.INVALID_PLAYER,
                        "瑞兽迎春副本怪物数必须恰好为 1: battle_config_id="
                                + Integer.toUnsignedString(request.getBattleConfigId()));
            }
            appendMonsterActor(monsterIds.getFirst(), 0, referenceLevel);
            BattleUnit spawned = ctx.units().getLast();
            FestivalBeastBattleContext fb = request.getActivityContext().getFestivalBeast();
            spawned.state().setIsBoss(true);
            if (!fb.getBossName().isEmpty()) {
                spawned.state().setName(fb.getBossName());
            }
            spawned.state().setMaxHealth(fb.getBossHealth());
            spawned.state().getAttributesBuilder()
                    .setHealth(fb.getBossHealth())
                    .setStrength(0)
                    .setArmor(0)
                    .setResistance(fb.getBossResistance())
                    .setSpeed(fb.getBossSpeed())
                    .setCritchance(0);
            spawned.state()
                    .setPhysicalAttack(fb.getBossPhysicalAttack())
                    .setMagicAttack(0)
                    .setDefense(fb.getBossDefense())
                    .clearCombat()
                    .clearEquipmentEffects()
                    .clearPetCultivation()
                    .clearSkillTableIds()
                    .clearRuntimeSkills()
                    .setCapturePetTableId(0);
            return null;
        }
        int monsterIndex = 0;
        for (int monsterTableId : monsterIds) {
            appendMonsterActor(monsterTableId, monsterIndex, referenceLevel);
            BattleUnit spawned = ctx.units().getLast();
            boolean isSupportGuard = monsterTableId == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER
                    || monsterTableId == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER;
            long baseHp = spawned.maxHealth();
            if (ctx.encounter.bossTrialKind() == BattleConstants.BOSS_TRIAL_KIND_BREAK_DUAL_GUARD
                    && !spawned.isBoss() && !isSupportGuard) {
                baseHp = Math.max(1L, Long.divideUnsigned(baseHp * BattleConstants.BOSS_DUAL_GUARD_HP_KEEP_PCT, 100L));
            }
            long unitHpScale = isSupportGuard ? Math.max(1L, (attackerCount + 1L) / 2L) : hpScale;
            long scaledHp = baseHp * unitHpScale;
            if (scaledHp != spawned.maxHealth()) {
                spawned.state().setMaxHealth(scaledHp);
                spawned.setHealth(scaledHp);
            }
            // 护卫救援：非首领护卫单位归属玩家侧（team 0）作为受保护目标，死亡即刻判负。
            if (ctx.encounter.bossTrialKind() == BattleConstants.BOSS_TRIAL_KIND_GUARDIAN_RESCUE
                    && !spawned.isBoss() && ctx.encounter.protectedGuardActorId() == 0L) {
                int allySlot = ctx.nextFormationSlot(0);
                spawned.state().setTeamIndex(0).setFormationSlot(allySlot);
                ctx.encounter.setProtectedGuardActorId(spawned.actorId());
            }
            monsterIndex++;
        }
        return null;
    }

    /**
     * 追加一只怪（{@code AppendMonsterActor}，{@code engine.cpp:261-297}）：team 1、名字「野怪」、参考等级、局内号 = 怪物号段 + 序号。
     * 表行存在<strong>且 health &gt; 0</strong> 才取表属性（speed 为 0 时回落 60），否则全用默认值；表行存在但 health 为 0 时
     * monster_table_id 仍是表 id，击杀照样记账。stamina 与 mana 都是 0；不查重，靠号段隔离。
     */
    private void appendMonsterActor(int monsterTableId, int monsterIndex, int referenceLevel) {
        BattleActorState.Builder actor = BattleActorState.newBuilder()
                .setActorId(BattleConstants.MONSTER_ACTOR_ID_BASE + Integer.toUnsignedLong(monsterIndex))
                .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                .setTeamIndex(1)
                .setName(MONSTER_NAME)
                .setLevel(referenceLevel)
                .setMonsterTableId(monsterTableId)
                .setFormationSlot(ctx.nextFormationSlot(1));
        Optional<MonsterTable> monsterRow = ctx.data().monster(monsterTableId);
        if (monsterRow.isPresent() && monsterRow.get().getIsBoss() != 0) {
            actor.setIsBoss(true);
        }
        BaseAttributesComp.Builder attributes = actor.getAttributesBuilder();
        if (monsterRow.isPresent() && monsterRow.get().getHealth() != 0) {
            MonsterTable row = monsterRow.get();
            attributes.setHealth(row.getHealth())
                    .setStrength(row.getStrength())
                    .setArmor(row.getArmor())
                    .setResistance(row.getResistance())
                    .setCritchance(row.getCritchance())
                    .setSpeed(row.getSpeed() != 0 ? row.getSpeed() : BattleConstants.MONSTER_DEFAULT_SPEED);
            actor.setMaxHealth(row.getHealth());
        } else {
            attributes.setHealth(BattleConstants.MONSTER_DEFAULT_HEALTH)
                    .setStrength(BattleConstants.MONSTER_DEFAULT_STRENGTH)
                    .setArmor(BattleConstants.MONSTER_DEFAULT_ARMOR)
                    .setResistance(BattleConstants.MONSTER_DEFAULT_RESISTANCE)
                    .setCritchance(BattleConstants.MONSTER_DEFAULT_CRIT_CHANCE)
                    .setSpeed(BattleConstants.MONSTER_DEFAULT_SPEED);
            actor.setMaxHealth(BattleConstants.MONSTER_DEFAULT_HEALTH);
        }
        var dungeon = ctx.data().dungeon(request.getBattleConfigId());
        var pet = monsterRow.flatMap(row -> ctx.data().pet(row.getCapturePetTableId()));
        if (ctx.isPve() && request.getActivityContext().getKindValue() == 0 && dungeon.isPresent() &&
                dungeon.get().getAllowPetCapture() == 1 && monsterRow.isPresent() && monsterRow.get().getHealth() != 0 &&
                pet.isPresent() && pet.get().getAcquisitionTier() == 0 &&
                Integer.toUnsignedLong(monsterRow.get().getCaptureRate()) > 0 &&
                Integer.toUnsignedLong(monsterRow.get().getCaptureRate()) <= 10000) {
            actor.setCapturePetTableId(pet.get().getId()).setCaptureMinLevel(pet.get().getUnlockLevel()).setName(pet.get().getName());
        }
        ctx.units().add(new BattleUnit(actor));
    }

    /** 快照没带上限时以当前值为上限（可能为 0）：声明值 &gt; 0（uint64）用声明值（{@code FallbackMax}，{@code engine.cpp:27-29}）。 */
    private static long fallbackMax(long declaredMax, long currentValue) {
        return declaredMax != 0 ? declaredMax : currentValue;
    }

    // =====================================================================================================
    // 行动收集（engine.cpp:315-380）
    // =====================================================================================================

    /** 本局 battle_id（uint64）。 */
    public long battleId() {
        return request.getBattleId();
    }

    /** 是否为论剑排位对局（{@code ranked_context.season_id != 0}）。 */
    /** 是否为正式论剑排位局（按 season_id != 0 判定）。 */
    public boolean isRankedBattle() {
        return request.hasRankedContext() && request.getRankedContext().getSeasonId() != 0;
    }

    /** 是否为免费 3×3 预设娱乐场（护卫救援 / 物法易势 5V5）：仅此开关放行临时竞技宠并隔离场外养成。 */
    public boolean usesFreePreset() {
        return request.hasRankedContext() && request.getRankedContext().getUseFreePreset();
    }

    /** 是否为竞技 PVP（人宠会武 / 物法易势 / 战队锦标赛）：启用凝锋主目标与战意渐盛。 */
    public boolean isCompetitivePvpBattle() {
        return ctx.isCompetitivePvp();
    }

    /** 是否为首领试炼对局（boss_trial_kind 1..4 或 8 双卫同阵；用于 18 秒行动窗口与确定退出）。 */
    public boolean isBossTrialBattle() {
        return ctx.encounter.isBossTrial();
    }

    /** 是否为瑞兽迎春单人携宠限时活动对局（boss_trial_kind == 7 且冻结合法 FESTIVAL_BEAST 上下文）。 */
    public boolean isFestivalBeastBattle() {
        return ctx.encounter.isFestivalBeast();
    }

    public int maxRounds() {
        return maxRounds;
    }

    public int escalationStartRound() {
        return ctx.escalationStartRound();
    }

    public int escalationStacks() {
        return ctx.escalationStacks();
    }

    public int surrenderUnlockRound() {
        return ctx.isPve() ? 0 : BattleConstants.PVP_SURRENDER_UNLOCK_ROUND;
    }

    public OptionalInt teamIndexOfPlayer(long playerId) {
        BattleUnit actor = ctx.findActor(playerId);
        if (actor == null || !actor.isPlayer() || Integer.compareUnsigned(actor.teamIndex(), 1) > 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(actor.teamIndex());
    }

    /** 提交 PVP 延迟投降或投票（R5 起解锁，纯确定性不耗 RNG，对齐 C++ SubmitSurrenderVote）。 */
    public SurrenderVoteResult submitSurrenderVote(long playerId, boolean agree) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return new SurrenderVoteResult(ActionChecks.INVALID_PARAMETER, BattleSurrenderVoteState.getDefaultInstance());
        }
        if (prepActive) {
            return new SurrenderVoteResult(ActionChecks.FEATURE_UNAVAILABLE, BattleSurrenderVoteState.getDefaultInstance());
        }
        if (ctx.isPve()) {
            return new SurrenderVoteResult(MatchErrorTip.match_error.kMatchBattleSurrenderNotAllowed_VALUE,
                    BattleSurrenderVoteState.getDefaultInstance());
        }
        OptionalInt teamOpt = teamIndexOfPlayer(playerId);
        if (teamOpt.isEmpty()) {
            return new SurrenderVoteResult(ActionChecks.INVALID_PARAMETER, BattleSurrenderVoteState.getDefaultInstance());
        }
        int roundIdx = ctx.roundIndex();
        if (Integer.compareUnsigned(roundIdx, BattleConstants.PVP_SURRENDER_UNLOCK_ROUND) < 0) {
            return new SurrenderVoteResult(MatchErrorTip.match_error.kMatchBattleSurrenderTooEarly_VALUE,
                    BattleSurrenderVoteState.getDefaultInstance());
        }
        int teamIdx = teamOpt.getAsInt();
        BattleSurrenderVoteState.Builder vote = teamSurrenderVotes[teamIdx];
        if (!vote.getActive()) {
            if (!agree) {
                return new SurrenderVoteResult(ActionChecks.INVALID_PARAMETER, BattleSurrenderVoteState.getDefaultInstance());
            }
            if (vote.getNextAllowedRound() > 0 && Integer.compareUnsigned(roundIdx, vote.getNextAllowedRound()) < 0) {
                return new SurrenderVoteResult(MatchErrorTip.match_error.kMatchBattleSurrenderCooldown_VALUE, vote.build());
            }
            int activeVoters = 0;
            for (BattleUnit member : ctx.units()) {
                if (!member.isPlayer() || member.teamIndex() != teamIdx) {
                    continue;
                }
                int idleRounds = consecutiveIdleRoundsByPlayer.getOrDefault(member.actorId(), 0);
                if (member.actorId() == playerId || idleRounds < BattleConstants.PVP_SURRENDER_IDLE_EXCLUDE_ROUNDS) {
                    activeVoters++;
                }
            }
            vote.clear()
                    .setActive(true)
                    .setTeamIndex(teamIdx)
                    .setInitiatorPlayerId(playerId)
                    .setInitiatedRound(roundIdx)
                    .addAgreedPlayerIds(playerId)
                    .setRequiredAgrees(Math.max(1, activeVoters));
        } else {
            for (long agreedId : vote.getAgreedPlayerIdsList()) {
                if (agreedId == playerId) {
                    return new SurrenderVoteResult(MatchErrorTip.match_error.kMatchBattleSurrenderAlreadyVoted_VALUE,
                            vote.build());
                }
            }
            if (!agree) {
                vote.setActive(false)
                        .setRejected(true)
                        .setPassed(false)
                        .clearAgreedPlayerIds()
                        .setNextAllowedRound(roundIdx + BattleConstants.PVP_SURRENDER_REJECT_COOLDOWN_ROUNDS);
                return new SurrenderVoteResult(0, vote.build());
            }
            List<Long> ids = new ArrayList<>(vote.getAgreedPlayerIdsList());
            ids.add(playerId);
            ids.sort(Long::compareUnsigned);
            vote.clearAgreedPlayerIds().addAllAgreedPlayerIds(ids);
        }

        if (vote.getActive() && vote.getAgreedPlayerIdsCount() >= vote.getRequiredAgrees()) {
            vote.setActive(false).setPassed(true).setRejected(false);
            surrenderedTeam = teamIdx;
            outcome = teamIdx == 0 ? eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN : eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
            ctx.encounter.clearActiveTelegraphs();
            rollDrops();
        }
        return new SurrenderVoteResult(0, vote.build());
    }

    /** 返回收信玩家己方队伍当前应展示的投降投票状态（敌方/观众返回 null）。 */
    public BattleSurrenderVoteState surrenderVoteForViewer(long viewerPlayerId) {
        if (viewerPlayerId == 0L) {
            return null;
        }
        OptionalInt teamOpt = teamIndexOfPlayer(viewerPlayerId);
        if (teamOpt.isEmpty()) {
            return null;
        }
        BattleSurrenderVoteState.Builder vote = teamSurrenderVotes[teamOpt.getAsInt()];
        if (vote.getActive() || vote.getPassed() || vote.getRejected()
                || (vote.getNextAllowedRound() > 0
                && Integer.compareUnsigned(ctx.roundIndex(), vote.getNextAllowedRound()) < 0)) {
            return vote.build();
        }
        return null;
    }

    public void fillSurrenderVoteForViewer(BattleStateS2C.Builder state, long viewerPlayerId) {
        state.clearSurrenderVote();
        BattleSurrenderVoteState vote = surrenderVoteForViewer(viewerPlayerId);
        if (vote != null) {
            state.setSurrenderVote(vote);
        }
    }

    private void expireSurrenderVotesForRoundAdvance() {
        for (int team = 0; team < 2; team++) {
            BattleSurrenderVoteState.Builder vote = teamSurrenderVotes[team];
            if (!vote.getActive()) {
                continue;
            }
            vote.setActive(false)
                    .setRejected(true)
                    .setPassed(false)
                    .clearAgreedPlayerIds()
                    .setNextAllowedRound(ctx.roundIndex() + BattleConstants.PVP_SURRENDER_EXPIRE_COOLDOWN_ROUNDS);
        }
    }

    /** 标记参战者已建立战斗直连或成功拉取房间状态。 */
    public void markPlayerEntered(long playerId) {
        if (playerId != 0L) {
            enteredPlayers.add(playerId);
        }
    }

    /** 组装该玩家的排位有效参与事实（纯读）。 */
    public BattleParticipationFact buildParticipationFact(long playerId) {
        BattleUnit actor = ctx.findActor(playerId);
        boolean entered = enteredPlayers.contains(playerId);
        boolean manual = manualActionPlayers.contains(playerId);
        boolean explicitAuto = explicitAutoPlayers.contains(playerId);
        boolean noOpportunity = actor != null && !actor.fled() && !hadOperableTurnPlayers.contains(playerId);
        return BattleParticipationFact.newBuilder()
                .setPlayerId(playerId)
                .setEnteredRoom(entered)
                .setManualActionAccepted(manual)
                .setExplicitAutoEnabled(explicitAuto)
                .setNoActionOpportunity(noOpportunity)
                .build();
    }

    /** 判定该玩家在排位中是否满足有效参赛口径（与 C++ / Go 逐条一致）。 */
    public boolean didPlayerParticipate(long playerId) {
        BattleUnit actor = ctx.findActor(playerId);
        if (actor == null || !actor.isPlayer() || actor.fled()) {
            return false;
        }
        if (manualActionPlayers.contains(playerId)) {
            return true;
        }
        if (enteredPlayers.contains(playerId) && explicitAutoPlayers.contains(playerId)) {
            return true;
        }
        return enteredPlayers.contains(playerId) && !hadOperableTurnPlayers.contains(playerId);
    }

    private boolean isPlayerHardControlled(BattleUnit actor) {
        return ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_STUN)
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_FREEZE)
                || ctx.configured.hasState(actor, BattleEffect.FREEZE)
                || ctx.configured.hasState(actor, BattleEffect.SLEEP)
                || ctx.configured.hasState(actor, BattleEffect.CONFUSION);
    }

    public static long splitMix64(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    public static long rankedTurnTieKey(long seed, int roundIndex, long actorId) {
        return splitMix64(seed ^ splitMix64(Integer.toUnsignedLong(roundIndex)) ^ splitMix64(actorId));
    }

    /**
     * 零副作用的提交预检（{@code ValidateAction}，{@code engine.cpp:333-346}；规格 §2.2）：已结束 → 1005；单位不存在或不是玩家 → 1005；
     * 已死或已逃 → 1009；其余返回校验链结果。与 {@link #submitAction} 的接受条件逐条同源：{@code == 1000} 等价于这次提交会被收下。
     */
    /** 论剑 V3 是否处于战前 15 秒明牌变阵期。 */
    public boolean isInPrepPhase() {
        return prepActive;
    }

    /** 所有真人参战者是否已锁定战前变阵（AI 托管位默认锁定）。 */
    public boolean allPrepPlayersReady() {
        if (!prepActive) {
            return true;
        }
        for (PrepPlayerOptions opt : prepOptionsByPlayer.values()) {
            if (!opt.aiControlled && !opt.ready) {
                return false;
            }
        }
        return true;
    }

    /** 论剑 V3 战前变阵提交（换赛季印 / 换首发灵宠 / 锁定），对齐 C++ {@code UpdateBattlePrep}。 */
    public UpdatePrepResult updateBattlePrep(long playerId, int selectedSealId, long selectedPetId, boolean ready) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING || !prepActive) {
            return new UpdatePrepResult(ActionChecks.FEATURE_UNAVAILABLE, BattlePrepState.getDefaultInstance());
        }
        PrepPlayerOptions opt = prepOptionsByPlayer.get(playerId);
        if (opt == null || opt.aiControlled) {
            return new UpdatePrepResult(ActionChecks.INVALID_PARAMETER, BattlePrepState.getDefaultInstance());
        }
        if (opt.ready) {
            boolean sealChanged = selectedSealId != 0 && selectedSealId != opt.selectedSealId;
            boolean petChanged = selectedPetId != 0L && selectedPetId != opt.selectedPetId;
            if (sealChanged || petChanged) {
                return new UpdatePrepResult(ActionChecks.FEATURE_UNAVAILABLE, BattlePrepState.getDefaultInstance());
            }
            return new UpdatePrepResult(0, buildPrepStateForViewer(playerId, 0L));
        }
        if (selectedSealId != 0) {
            boolean found = false;
            for (SeasonSealEffect seal : opt.availableSeals) {
                if (seal.getSealId() == selectedSealId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return new UpdatePrepResult(ActionChecks.INVALID_PARAMETER, BattlePrepState.getDefaultInstance());
            }
        }
        if (selectedPetId != 0L) {
            boolean found = false;
            for (BattlePetSnapshot pet : opt.availablePets) {
                if (pet.getPetId() == selectedPetId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return new UpdatePrepResult(ActionChecks.INVALID_PARAMETER, BattlePrepState.getDefaultInstance());
            }
        }
        if (selectedSealId != 0) {
            opt.selectedSealId = selectedSealId;
        }
        if (selectedPetId != 0L) {
            opt.selectedPetId = selectedPetId;
        }
        if (ready) {
            opt.ready = true;
        }
        return new UpdatePrepResult(0, buildPrepStateForViewer(playerId, 0L));
    }

    /** 结束战前变阵期：原子替换选定赛季印与首发灵宠，再一次性初始化全部开场光环/成长被动（对齐 C++ {@code FinalizePrepPhase}）。 */
    public boolean finalizePrepPhase() {
        if (!prepActive) {
            return false;
        }
        for (Map.Entry<Long, PrepPlayerOptions> entry : prepOptionsByPlayer.entrySet()) {
            long playerId = entry.getKey();
            PrepPlayerOptions opt = entry.getValue();

            // 1. 仅替换单槽赛季印机制（轻度气血/防御万分比由赛季进度决定，零面板漂移）。
            if (opt.selectedSealId != 0) {
                BattleUnit actor = ctx.findActor(playerId);
                if (actor != null) {
                    for (SeasonSealEffect seal : opt.availableSeals) {
                        if (seal.getSealId() == opt.selectedSealId) {
                            if (!actor.state().getEquipmentEffects().hasSeasonSeal()
                                    || actor.state().getEquipmentEffects().getSeasonSeal().getSealId() != opt.selectedSealId) {
                                actor.state().getEquipmentEffectsBuilder().setSeasonSeal(seal);
                                actor.state().clearSeasonSealState();
                                actor.state().getSeasonSealStateBuilder()
                                        .setItemId(Integer.toUnsignedLong(seal.getSealId()));
                                if (actor.state().getEntryMaxHealth() == 0L) {
                                    actor.state().setEntryMaxHealth(actor.maxHealth());
                                }
                                if (actor.state().getEntryMaxMana() == 0L) {
                                    actor.state().setEntryMaxMana(actor.maxMana());
                                }
                            }
                            break;
                        }
                    }
                }
            }

            // 2. 替换或补发首发灵宠（原地保留局内 actor_id 与阵位，完整重建属性与技能）。
            if (opt.selectedPetId != 0L) {
                BattlePetSnapshot chosenPet = null;
                for (BattlePetSnapshot pet : opt.availablePets) {
                    if (pet.getPetId() == opt.selectedPetId) {
                        chosenPet = pet;
                        break;
                    }
                }
                BattlePlayerSnapshot ownerSnap = null;
                for (BattlePlayerSnapshot snap : request.getPlayersList()) {
                    if (snap.getPlayerId() == playerId) {
                        ownerSnap = snap;
                        break;
                    }
                }
                if (chosenPet != null && ownerSnap != null) {
                    int existingIdx = -1;
                    for (int i = 0; i < ctx.units().size(); i++) {
                        BattleUnit unit = ctx.units().get(i);
                        if (unit.isPet() && unit.ownerPlayerId() == playerId) {
                            existingIdx = i;
                            break;
                        }
                    }
                    if (existingIdx >= 0) {
                        BattleUnit existing = ctx.units().get(existingIdx);
                        if (existing.petId() != chosenPet.getPetId()) {
                            BattleActorState.Builder rebuilt = buildPetActor(ownerSnap, chosenPet,
                                    existing.actorId(), existing.state().getFormationSlot());
                            if (rebuilt != null) {
                                ctx.units().set(existingIdx, new BattleUnit(rebuilt));
                            }
                        }
                    } else {
                        BattleActorState.Builder added = buildPetActor(ownerSnap, chosenPet,
                                BattleConstants.PET_ACTOR_ID_BASE + nextPetActorIndex,
                                ctx.nextFormationSlot(opt.teamIndex));
                        if (added != null) {
                            nextPetActorIndex++;
                            ctx.units().add(new BattleUnit(added));
                        }
                    }
                }
            }
        }

        prepActive = false;
        roundPingCountByPlayer.clear();
        // 在最终亮牌阵容上一次性初始化全部开场光环/成长被动，杜绝被换掉宠物的残留光环。
        ctx.configured.initialize();
        return true;
    }

    /** 返回收信视角对应的战前变阵状态（非变阵期返回 null；viewerPlayerId == 0 为观众视角，仅暴露倒计时）。 */
    public BattlePrepState prepStateForViewer(long viewerPlayerId, long deadlineMs) {
        if (!prepActive) {
            return null;
        }
        return buildPrepStateForViewer(viewerPlayerId, deadlineMs);
    }

    /** 论剑 V4 队内一键战术标点提交（每名玩家每回合至多 2 次，仅同队可见），对齐 C++ {@code SubmitBattlePing}。 */
    public BattlePingResult submitBattlePing(long playerId, long targetActorId, BattlePingKind kind) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return new BattlePingResult(ActionChecks.FEATURE_UNAVAILABLE, BattleTacticalPing.getDefaultInstance());
        }
        BattleUnit sender = ctx.findActor(playerId);
        if (sender == null || !sender.isPlayer() || sender.fled()) {
            return new BattlePingResult(ActionChecks.INVALID_PARAMETER, BattleTacticalPing.getDefaultInstance());
        }
        int senderTeam = sender.teamIndex();
        if (senderTeam < 0 || senderTeam > 1) {
            return new BattlePingResult(ActionChecks.INVALID_PARAMETER, BattleTacticalPing.getDefaultInstance());
        }
        int teamPlayerCount = 0;
        for (BattleUnit unit : ctx.units()) {
            if (unit.isPlayer() && unit.teamIndex() == senderTeam) {
                teamPlayerCount++;
            }
        }
        if (teamPlayerCount < 2) {
            return new BattlePingResult(ActionChecks.FEATURE_UNAVAILABLE, BattleTacticalPing.getDefaultInstance());
        }
        if (kind == null || (kind != BattlePingKind.BATTLE_PING_FOCUS
                && kind != BattlePingKind.BATTLE_PING_CONTROL
                && kind != BattlePingKind.BATTLE_PING_PROTECT
                && kind != BattlePingKind.BATTLE_PING_DISPEL)) {
            return new BattlePingResult(ActionChecks.INVALID_PARAMETER, BattleTacticalPing.getDefaultInstance());
        }
        BattleUnit target = ctx.findActor(targetActorId);
        if (target == null || !target.isActive()) {
            return new BattlePingResult(ActionChecks.INVALID_PARAMETER, BattleTacticalPing.getDefaultInstance());
        }
        if ((kind == BattlePingKind.BATTLE_PING_FOCUS || kind == BattlePingKind.BATTLE_PING_CONTROL)
                && target.teamIndex() == senderTeam) {
            return new BattlePingResult(ActionChecks.INVALID_PARAMETER, BattleTacticalPing.getDefaultInstance());
        }
        if (kind == BattlePingKind.BATTLE_PING_PROTECT && target.teamIndex() != senderTeam) {
            return new BattlePingResult(ActionChecks.INVALID_PARAMETER, BattleTacticalPing.getDefaultInstance());
        }
        int used = roundPingCountByPlayer.getOrDefault(playerId, 0);
        if (used >= BattleConstants.MAX_BATTLE_PINGS_PER_ROUND) {
            return new BattlePingResult(ActionChecks.RATE_LIMIT_EXCEEDED, BattleTacticalPing.getDefaultInstance());
        }
        roundPingCountByPlayer.put(playerId, used + 1);

        BattleTacticalPing ping = BattleTacticalPing.newBuilder()
                .setSenderPlayerId(playerId)
                .setTargetActorId(targetActorId)
                .setKind(kind)
                .setRoundIndex(prepActive ? 0 : ctx.roundIndex())
                .build();
        teamPings[senderTeam].put(playerId, ping);
        return new BattlePingResult(0, ping);
    }

    /** 仅向同队参战者返回本队当前有效战术标点（对手与观众为空）。 */
    public List<BattleTacticalPing> teamPingsForViewer(long viewerPlayerId) {
        if (viewerPlayerId == 0L || outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return List.of();
        }
        OptionalInt team = teamIndexOfPlayer(viewerPlayerId);
        if (team.isEmpty() || team.getAsInt() < 0 || team.getAsInt() > 1) {
            return List.of();
        }
        return List.copyOf(teamPings[team.getAsInt()].values());
    }

    public void fillPrepStateForViewer(BattleStateS2C.Builder state, long viewerPlayerId, long deadlineMs) {
        state.clearPrepState();
        if (!prepActive) {
            return;
        }
        state.setPrepState(buildPrepStateForViewer(viewerPlayerId, deadlineMs));
    }

    private BattlePrepState buildPrepStateForViewer(long viewerPlayerId, long deadlineMs) {
        BattlePrepState.Builder out = BattlePrepState.newBuilder()
                .setActive(true)
                .setDeadlineMs(deadlineMs)
                .setDurationSeconds(BattleConstants.TACTICAL_PREP_DURATION_SECONDS);
        if (viewerPlayerId == 0L) {
            return out.build();
        }
        PrepPlayerOptions selfOpt = prepOptionsByPlayer.get(viewerPlayerId);
        if (selfOpt == null) {
            return out.build();
        }
        for (SeasonSealEffect seal : selfOpt.availableSeals) {
            out.addSelfAvailableSeals(BattlePrepSealOption.newBuilder()
                    .setSealId(seal.getSealId())
                    .setEffectId(seal.getEffectId())
                    .setName(seal.getName()));
        }
        for (BattlePetSnapshot pet : selfOpt.availablePets) {
            out.addSelfAvailablePets(BattlePrepPetOption.newBuilder()
                    .setPetId(pet.getPetId())
                    .setPetTableId(pet.getPetTableId())
                    .setPetName(pet.getPetName())
                    .setLevel(pet.getLevel())
                    .setMaxHealth(fallbackMax(pet.getMaxHealth(), pet.getBaseAttributes().getHealth()))
                    .setSpeed(pet.getBaseAttributes().getSpeed())
                    .setPhysicalAttack(pet.getPhysicalAttack())
                    .setMagicAttack(pet.getMagicAttack())
                    .setDefense(pet.getDefense())
                    .addAllSkillTableIds(pet.getSkillTableIdsList()));
        }
        // 同队互见、对手严密盲选：仅投影与收信者同 teamIndex 的成员状态。
        for (Map.Entry<Long, PrepPlayerOptions> entry : prepOptionsByPlayer.entrySet()) {
            PrepPlayerOptions opt = entry.getValue();
            if (opt.teamIndex != selfOpt.teamIndex) {
                continue;
            }
            BattlePrepPlayerState.Builder ps = out.addTeamStatesBuilder()
                    .setPlayerId(entry.getKey())
                    .setTeamIndex(opt.teamIndex)
                    .setSelectedSealId(opt.selectedSealId)
                    .setSelectedPetId(opt.selectedPetId)
                    .setReady(opt.ready);
            if (opt.selectedPetId != 0L) {
                for (BattlePetSnapshot pet : opt.availablePets) {
                    if (pet.getPetId() == opt.selectedPetId) {
                        ps.setSelectedPetTableId(pet.getPetTableId())
                                .setSelectedPetName(pet.getPetName());
                        break;
                    }
                }
            }
        }
        return out.build();
    }

    public int currentRoundIndex() { return ctx.roundIndex(); }

    public int validateOwnedAction(long owner, long actorId, BattleAction action) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return ActionChecks.INVALID_PARAMETER;
        }
        if (prepActive) {
            return ActionChecks.FEATURE_UNAVAILABLE;
        }
        BattleUnit unit = ctx.findActor(actorId);
        if (unit == null || aiControlledPlayers.contains(owner)
                || !(unit.isPlayer() && unit.actorId() == owner || unit.isPet() && unit.ownerPlayerId() == owner))
            return ActionChecks.INVALID_PARAMETER;
        return checks.checkActionPrerequisites(unit,action);
    }

    public boolean submitOwnedAction(long owner, long actorId, BattleAction action) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING || prepActive) return false;
        if (validateOwnedAction(owner,actorId,action) != ActionChecks.SUCCESS) return false;
        pendingActions.put(actorId,action);
        manualActionPlayers.add(owner);
        enteredPlayers.add(owner);
        ctx.encounter.recordManualActionRound(owner, ctx.roundIndex());
        return allPlayersReady();
    }

    public int validateAction(long actorId, BattleAction action) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return ActionChecks.INVALID_PARAMETER;
        }
        if (prepActive) {
            return ActionChecks.FEATURE_UNAVAILABLE;
        }
        BattleUnit actor = ctx.findActor(actorId);
        if (actor == null || !actor.isPlayer() || aiControlledPlayers.contains(actorId)) {
            return ActionChecks.INVALID_PARAMETER;
        }
        if (!actor.isActive()) {
            return ActionChecks.ENTITY_INVALID;
        }
        return checks.checkActionPrerequisites(actor, action);
    }

    /**
     * 提交行动（{@code SubmitAction}，{@code engine.cpp:315-331}；规格 §2.1）：已结束返回 false；只收存活未逃的玩家、且校验链通过的行动，
     * 同回合后到的合法提交覆盖先到的（整条 action 原样保存）；不合法的提交<strong>不清掉</strong>此前已收下的行动（基线行为）。
     * 无论收没收都返回 {@link #allPlayersReady()}。对宝宝 / 怪物 / 不存在的 id 提交被静默忽略。挂机玩家也能手动提交，手动优先。
     */
    public boolean submitAction(long actorId, BattleAction action) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING || prepActive) {
            return false;
        }
        BattleUnit actor = ctx.findActor(actorId);
        if (actor != null && actor.isPlayer() && !aiControlledPlayers.contains(actorId) && actor.isActive()
                && checks.checkActionPrerequisites(actor, action) == ActionChecks.SUCCESS) {
            pendingActions.put(actorId, action);
            manualActionPlayers.add(actorId);
            enteredPlayers.add(actorId);
            ctx.encounter.recordManualActionRound(actorId, ctx.roundIndex());
        }
        return allPlayersReady();
    }

    /**
     * 挂机开关（{@code SetActorAuto}，{@code engine.cpp:348-364}；规格 §2.3）：已结束 → 1005；不存在或不是玩家 → 1005；已死或已逃 → 1009；
     * 否则写 is_auto 并返回 1000（基线成功返回 0，Java 统一为 1000，有意差异 D2）。不检查眩晕；不耗随机数、不触发结算。
     */
    public int setActorAuto(long actorId, boolean enabled) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return ActionChecks.INVALID_PARAMETER;
        }
        if (prepActive) {
            return ActionChecks.FEATURE_UNAVAILABLE;
        }
        BattleUnit actor = ctx.findActor(actorId);
        if (actor == null || !actor.isPlayer() || aiControlledPlayers.contains(actorId)) {
            return ActionChecks.INVALID_PARAMETER;
        }
        if (actor.isDead() || actor.fled()) {
            return ActionChecks.ENTITY_INVALID;
        }
        actor.setAuto(enabled);
        if (enabled) {
            explicitAutoPlayers.add(actorId);
        }
        for (BattleUnit pet : ctx.units()) {
            if (pet.isPet() && pet.ownerPlayerId() == actor.actorId() && pet.state().getSkillRanksCount() != 0) pet.setAuto(enabled);
        }
        return ActionChecks.SUCCESS;
    }

    /**
     * 每个存活、未逃跑、未挂机的玩家都已有本回合行动（{@code AllPlayersReady}，{@code engine.cpp:366-380}；规格 §2.4）。
     * 没有这样的玩家时为真（空真）。不看胜负。
     */
    public boolean allPlayersReady() {
        if (prepActive) {
            return false;
        }
        for (BattleUnit unit : ctx.units()) {
            if ((!unit.isPlayer() && !unit.isPet()) || !unit.isActive()) {
                continue;
            }
            if (unit.isAuto()) {
                continue;
            }
            if (!pendingActions.containsKey(unit.actorId())) {
                return false;
            }
        }
        return true;
    }

    // =====================================================================================================
    // 回合结算（engine.cpp:608-1031、:1169-1243）
    // =====================================================================================================

    /**
     * 结算当前回合（{@code ResolveCurrentRound}，{@code engine.cpp:608-671}；规格 §3.1）。
     *
     * <p>已结束：只带 battle_id、round_index、state，无事件、不耗随机数、出手序不变（{@code :617-620}）。否则依次：
     * 补默认行动 → 组号归 0、排出手序 → 按出手序逐个（回合开始后死亡 / 逃跑的跳过、不占组号）先开组再执行 →
     * 回合末 buff tick → 冷却衰减 → 全体撤防御 → 判胜负 → 掉落（只在刚判出 A 方胜时掷）→ 清空本回合行动、未结束则回合号加 1 → 快照。
     *
     * <p>{@code round_index} 是本次结算的回合号；{@code state.round_index} 未结束时是下一回合号、已结束时是同一回合号。
     * {@code action_order} 不填，由节点从 {@link #lastActionOrder()} 透传。
     */
    public TurnResultS2C resolveCurrentRound() {
        TurnResultS2C.Builder result = TurnResultS2C.newBuilder()
                .setBattleId(request.getBattleId())
                .setRoundIndex(ctx.roundIndex());
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return result.setState(buildStateSnapshot()).build();
        }
        if (prepActive) {
            finalizePrepPhase();
        }

        ctx.configured.beginRound();
        // 锦标赛 V2 水润灵脉：每回合初为存活单位额外恢复 5% 最大法力（封顶至 max_mana）。
        if (ctx.activeTournamentLeyline() == com.game.proto.TournamentLeylineElement.TOURNAMENT_LEYLINE_WATER) {
            for (BattleUnit unit : ctx.units()) {
                if (!unit.isActive() || Long.compareUnsigned(unit.maxMana(), 0L) == 0) {
                    continue;
                }
                if (Long.compareUnsigned(unit.mana(), unit.maxMana()) >= 0) {
                    continue;
                }
                long regen = Math.max(1L, Long.divideUnsigned(unit.maxMana() * 5L, 100L));
                unit.restoreMana(regen);
            }
        }
        fillDefaultActions();

        ctx.events().startRound(result);
        List<Long> order = buildTurnOrder();
        lastActionOrder = order;
        ctx.snapshotRoundStartSpeeds();

        for (long actorId : order) {
            if (ctx.encounter.isSlowPhaseActor(actorId)) {
                continue;
            }
            BattleUnit actor = ctx.findActor(actorId);
            if (actor == null || !actor.isActive()) {
                continue;
            }
            BattleAction action = pendingActions.get(actorId);
            if (action == null) {
                continue;
            }
            if (actor.isPlayer() && !isPlayerHardControlled(actor)) {
                hadOperableTurnPlayers.add(actor.actorId());
            }
            // 每个行动一组：该行动产生的全部事件（含降级普攻 / 死亡 / buff）共用 group_id；即使最终没有事件也占组号
            ctx.events().beginGroup();
            executeAction(actor, action);
        }

        // 首领试炼慢速重击与护卫周期机制：排在常规行动之后、回合末 buff tick 与摘除防御之前。
        ctx.encounter.executeSlowAndGuardianActions(checks);

        buffs.tickAtRoundEnd();
        ctx.configured.endRound();
        decayCooldowns();
        // 防御只覆盖本回合（含刚结算的回合末周期伤害），此刻统一摘除（engine.cpp:652-655）
        for (BattleUnit unit : ctx.units()) {
            unit.setDefending(false);
        }
        updateOutcome();
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING && ctx.encounter.isBossTrial()) {
            ctx.encounter.processBossTrialRoundEnd(checks);
            updateOutcome();
        }
        rollDrops();

        int settledRound = ctx.roundIndex();
        for (BattleUnit unit : ctx.units()) {
            if (!unit.isPlayer() || !unit.isActive()) {
                continue;
            }
            if (ctx.encounter.hadManualActionInRound(unit.actorId(), settledRound)) {
                consecutiveIdleRoundsByPlayer.put(unit.actorId(), 0);
            } else {
                consecutiveIdleRoundsByPlayer.put(unit.actorId(),
                        consecutiveIdleRoundsByPlayer.getOrDefault(unit.actorId(), 0) + 1);
            }
        }

        int resolvedEscalation = ctx.escalationStacks();
        pendingActions.clear();
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            ctx.advanceRound();
            ctx.encounter.advanceRoundCleanup();
            expireSurrenderVotesForRoundAdvance();
            teamPings[0].clear();
            teamPings[1].clear();
            roundPingCountByPlayer.clear();
            ctx.encounter.updatePolarityForRound(request.getMatchMode());
            ctx.encounter.refreshTelegraphs(maxRounds);
        } else {
            ctx.encounter.clearActiveTelegraphs();
        }
        ctx.events().endRound();
        accumulateRoundMvpMetrics(result, resolvedEscalation);
        return result.setState(buildStateSnapshot()).build();
    }

    private boolean isHardControlBuff(int buffTableId) {
        if (buffTableId == 0) {
            return false;
        }
        Optional<BuffTable> opt = ctx.data().buff(buffTableId);
        if (opt.isEmpty()) {
            return false;
        }
        BuffTable row = opt.get();
        int bt = row.getBuffType();
        if (bt == BattleConstants.BUFF_TYPE_STUN
                || bt == BattleConstants.BUFF_TYPE_SILENCE
                || bt == BattleConstants.BUFF_TYPE_FREEZE) {
            return true;
        }
        if (row.getBattleOp() == BattleEffect.CONTROL) {
            int sk = row.getStateKind();
            return sk >= BattleEffect.SEAL_ACTIVE && sk <= BattleEffect.FREEZE;
        }
        return false;
    }

    private void accumulateRoundMvpMetrics(TurnResultS2C.Builder result, int resolvedEscalation) {
        if (resolvedEscalation > peakEscalationStacks) {
            peakEscalationStacks = resolvedEscalation;
        }
        int count = result.getEventsCount();
        for (int i = 0; i < count; i++) {
            BattleEventItem ev = result.getEvents(i);
            BattleUnit source = ctx.findActor(ev.getSourceId());
            BattleUnit target = ctx.findActor(ev.getTargetId());
            long ownerId = source == null ? 0L
                    : (source.isPlayer() ? source.actorId()
                    : (source.isPet() ? source.ownerPlayerId() : 0L));
            long targetOwnerId = target == null ? 0L
                    : (target.isPlayer() ? target.actorId()
                    : (target.isPet() ? target.ownerPlayerId() : 0L));
            switch (ev.getEventType()) {
                case BATTLE_EVENT_DAMAGE -> {
                    long dealt = EquipmentCombatRules.addSaturated(ev.getValue(), ev.getAbsorbed());
                    if (Long.compareUnsigned(dealt, 0L) > 0) {
                        if (ownerId != 0L && source != null
                                && (target == null || target.teamIndex() != source.teamIndex())) {
                            long prev = totalDamageDealtByPlayer.getOrDefault(ownerId, 0L);
                            totalDamageDealtByPlayer.put(ownerId, EquipmentCombatRules.addSaturated(prev, dealt));
                            if (ev.getIsPrimaryFocus()) {
                                long prevFocus = primaryFocusDamageByPlayer.getOrDefault(ownerId, 0L);
                                primaryFocusDamageByPlayer.put(ownerId,
                                        EquipmentCombatRules.addSaturated(prevFocus, dealt));
                            }
                        }
                        if (targetOwnerId != 0L && target != null
                                && (source == null || target.teamIndex() != source.teamIndex())) {
                            long prevTaken = damageTakenByPlayer.getOrDefault(targetOwnerId, 0L);
                            damageTakenByPlayer.put(targetOwnerId,
                                    EquipmentCombatRules.addSaturated(prevTaken, dealt));
                        }
                    }
                    noteLowHpExperienced(target, ev.getTargetHealthAfter());
                }
                case BATTLE_EVENT_HEAL -> {
                    if (ownerId != 0L && source != null
                            && Long.compareUnsigned(ev.getValue(), 0L) > 0
                            && (target == null || target.teamIndex() == source.teamIndex())) {
                        long prev = totalHealingDoneByPlayer.getOrDefault(ownerId, 0L);
                        totalHealingDoneByPlayer.put(ownerId, EquipmentCombatRules.addSaturated(prev, ev.getValue()));
                    }
                }
                case BATTLE_EVENT_SHIELD -> {
                    if (ownerId != 0L && source != null
                            && Long.compareUnsigned(ev.getValue(), 0L) > 0
                            && (target == null || target.teamIndex() == source.teamIndex())) {
                        long prev = totalHealingDoneByPlayer.getOrDefault(ownerId, 0L);
                        totalHealingDoneByPlayer.put(ownerId, EquipmentCombatRules.addSaturated(prev, ev.getValue()));
                        long prevShield = totalShieldGrantedByPlayer.getOrDefault(ownerId, 0L);
                        totalShieldGrantedByPlayer.put(ownerId,
                                EquipmentCombatRules.addSaturated(prevShield, ev.getValue()));
                    }
                }
                case BATTLE_EVENT_BUFF_TICK -> {
                    if (Long.compareUnsigned(ev.getValue(), 0L) > 0 && target != null) {
                        if (source == null || target.teamIndex() != source.teamIndex()) {
                            if (ownerId != 0L) {
                                long prev = totalDamageDealtByPlayer.getOrDefault(ownerId, 0L);
                                totalDamageDealtByPlayer.put(ownerId,
                                        EquipmentCombatRules.addSaturated(prev, ev.getValue()));
                            }
                            if (targetOwnerId != 0L) {
                                long prevTaken = damageTakenByPlayer.getOrDefault(targetOwnerId, 0L);
                                damageTakenByPlayer.put(targetOwnerId,
                                        EquipmentCombatRules.addSaturated(prevTaken, ev.getValue()));
                            }
                            noteLowHpExperienced(target, ev.getTargetHealthAfter());
                        } else if (ownerId != 0L) {
                            long prev = totalHealingDoneByPlayer.getOrDefault(ownerId, 0L);
                            totalHealingDoneByPlayer.put(ownerId,
                                    EquipmentCombatRules.addSaturated(prev, ev.getValue()));
                        }
                    }
                }
                case BATTLE_EVENT_BUFF_ADD -> {
                    if (ownerId != 0L && source != null && ev.getBuffTableId() != 0 && target != null
                            && target.teamIndex() != source.teamIndex()
                            && isHardControlBuff(ev.getBuffTableId())) {
                        totalControlsLandedByPlayer.put(ownerId,
                                totalControlsLandedByPlayer.getOrDefault(ownerId, 0) + 1);
                    }
                }
                default -> {
                }
            }
        }
    }

    private void noteLowHpExperienced(BattleUnit target, long hpAfter) {
        if (target == null || !target.isPlayer()) {
            return;
        }
        long maxHp = target.maxHealth();
        if (Long.compareUnsigned(hpAfter, 0L) <= 0 || Long.compareUnsigned(maxHp, 0L) <= 0) {
            return;
        }
        long limit = Long.divideUnsigned(-1L, BattleConstants.CLUTCH_COMEBACK_LOW_HP_DENOMINATOR);
        if (Long.compareUnsigned(hpAfter, limit) <= 0
                && Long.compareUnsigned(hpAfter * BattleConstants.CLUTCH_COMEBACK_LOW_HP_DENOMINATOR, maxHp) < 0) {
            lowHpExperiencedPlayers.add(target.actorId());
        }
    }

    private BattleHighlightKind computeHighlightKind(long playerId, int teamIndex, boolean playerWon, boolean isDead) {
        if (playerWon && (peakEscalationStacks >= BattleConstants.CLUTCH_COMEBACK_MIN_ESCALATION_STACKS
                || (!isDead && lowHpExperiencedPlayers.contains(playerId)))) {
            return BattleHighlightKind.BATTLE_HIGHLIGHT_CLUTCH_COMEBACK;
        }
        long maxFocus = 0L;
        int maxCtrl = 0;
        long maxSupport = 0L;
        long maxTaken = 0L;
        for (BattleUnit member : ctx.units()) {
            if (!member.isPlayer() || member.teamIndex() != teamIndex) {
                continue;
            }
            long pid = member.actorId();
            long focus = primaryFocusDamageByPlayer.getOrDefault(pid, 0L);
            if (Long.compareUnsigned(focus, maxFocus) > 0) maxFocus = focus;
            int ctrl = totalControlsLandedByPlayer.getOrDefault(pid, 0) + ctx.totalDispelsLanded(pid);
            if (ctrl > maxCtrl) maxCtrl = ctrl;
            long support = totalHealingDoneByPlayer.getOrDefault(pid, 0L);
            if (Long.compareUnsigned(support, maxSupport) > 0) maxSupport = support;
            long taken = damageTakenByPlayer.getOrDefault(pid, 0L);
            if (Long.compareUnsigned(taken, maxTaken) > 0) maxTaken = taken;
        }
        long selfFocus = primaryFocusDamageByPlayer.getOrDefault(playerId, 0L);
        if (Long.compareUnsigned(selfFocus, 0L) > 0 && selfFocus == maxFocus) {
            return BattleHighlightKind.BATTLE_HIGHLIGHT_PRIMARY_FOCUS;
        }
        int selfCtrl = totalControlsLandedByPlayer.getOrDefault(playerId, 0) + ctx.totalDispelsLanded(playerId);
        if (selfCtrl >= BattleConstants.HIGHLIGHT_CONTROL_MIN_COUNT && selfCtrl == maxCtrl) {
            return BattleHighlightKind.BATTLE_HIGHLIGHT_TACTICAL_CONTROL;
        }
        long selfSupport = totalHealingDoneByPlayer.getOrDefault(playerId, 0L);
        if (Long.compareUnsigned(selfSupport, 0L) > 0 && selfSupport == maxSupport) {
            return BattleHighlightKind.BATTLE_HIGHLIGHT_SAVIOR_SUPPORT;
        }
        long selfTaken = damageTakenByPlayer.getOrDefault(playerId, 0L);
        if (Long.compareUnsigned(selfTaken, 0L) > 0 && selfTaken == maxTaken) {
            return BattleHighlightKind.BATTLE_HIGHLIGHT_IRON_GUARD;
        }
        return BattleHighlightKind.BATTLE_HIGHLIGHT_NONE;
    }

    /** 只读查询指定怪物当前的 Boss HFSM 阶段（未绑定 AI 时返回空）。 */
    public Optional<DungeonMonsterAi.BossPhaseId> monsterAiPhaseOf(long actorId) {
        DungeonMonsterAi.Controller ctrl = monsterAiControllers.get(actorId);
        return ctrl == null ? Optional.empty() : Optional.of(ctrl.currentPhase());
    }

    private void bindDungeonMonsterAis(Map<Integer, DungeonMonsterAi.Profile> profiles) {
        monsterAiControllers.clear();
        if (profiles == null || profiles.isEmpty()) {
            return;
        }
        DungeonMonsterAi.Profile fallback = profiles.get(0);
        for (BattleUnit unit : ctx.units()) {
            if (!unit.isMonster()) {
                continue;
            }
            DungeonMonsterAi.Profile profile = profiles.get(unit.monsterTableId());
            if (profile == null) {
                profile = fallback;
            }
            if (profile == null) {
                continue;
            }
            // 与 C++ 保持同一闸门：怪物只能携带零耗蓝且回合制可施放的非职业老技能（升序去重）。
            TreeSet<Integer> validSkills = new TreeSet<>(Integer::compareUnsigned);
            for (int skillId : profile.monsterSkillIds()) {
                if (skillId == 0) {
                    continue;
                }
                Optional<SkillTable> row = ctx.data().skill(skillId);
                if (row.isEmpty() || BattleRules.isClassSkill(row.get())
                        || !BattleRules.isTurnBattleCastableSkill(row.get())
                        || BattleRules.skillManaCost(row.get()) != 0L) {
                    continue;
                }
                validSkills.add(skillId);
            }
            for (int skillId : validSkills) {
                if (!unit.state().getSkillTableIdsList().contains(skillId)) {
                    unit.state().addSkillTableIds(skillId);
                }
            }
            monsterAiControllers.put(unit.actorId(), new DungeonMonsterAi.Controller(profile));
        }
    }

    private void fillDungeonMonsterAiActions() {
        if (monsterAiControllers.isEmpty()) {
            return;
        }
        for (BattleUnit actor : ctx.units()) {
            if (!actor.isMonster()) {
                continue;
            }
            DungeonMonsterAi.Controller ctrl = monsterAiControllers.get(actor.actorId());
            if (ctrl == null) {
                continue;
            }
            if (!actor.isActive()) {
                ctrl.notifyDead();
                continue;
            }
            if (pendingActions.containsKey(actor.actorId())) {
                continue;
            }
            if (ctx.encounter.protectedGuardActorId() != 0L && actor.actorId() == ctx.encounter.protectedGuardActorId()) {
                continue;
            }

            int selfHpPermyriad;
            if (actor.maxHealth() != 0L) {
                long clampedHp = Unsigned.minUnsigned(actor.health(), actor.maxHealth());
                selfHpPermyriad = clampedHp == actor.maxHealth()
                        ? MonsterUtilityAi.UTILITY_SCALE
                        : (int) Long.divideUnsigned(clampedHp * MonsterUtilityAi.UTILITY_SCALE, actor.maxHealth());
            } else {
                selfHpPermyriad = 0;
            }

            long rawMaxSpeed = ctx.maxAliveEnemySpeed(actor);
            long maxEnemySpeed = Long.compareUnsigned(rawMaxSpeed, 1L) > 0 ? rawMaxSpeed : 1L;
            List<DungeonMonsterAi.Candidate> candidates = new ArrayList<>();

            for (BattleUnit target : ctx.units()) {
                if (target.teamIndex() == actor.teamIndex() || !target.isActive()) {
                    continue;
                }
                int missingHp = 0;
                if (target.maxHealth() != 0L && Long.compareUnsigned(target.maxHealth(), target.health()) > 0) {
                    long missing = target.maxHealth() - target.health();
                    missingHp = (int) Long.divideUnsigned(missing * MonsterUtilityAi.UTILITY_SCALE, target.maxHealth());
                }
                boolean controlled = ctx.actorHasBuffOfType(target, BattleConstants.BUFF_TYPE_STUN)
                        || ctx.actorHasBuffOfType(target, BattleConstants.BUFF_TYPE_SILENCE)
                        || ctx.actorHasBuffOfType(target, BattleConstants.BUFF_TYPE_FREEZE);
                int uncontrolled = controlled ? 0 : MonsterUtilityAi.UTILITY_SCALE;
                long effSpeed = ctx.effectiveSpeed(target);
                int threat = (int) Unsigned.minUnsigned(
                        MonsterUtilityAi.UTILITY_SCALE,
                        Long.divideUnsigned(effSpeed * MonsterUtilityAi.UTILITY_SCALE, maxEnemySpeed));
                int targetIsPlayer = target.isPlayer() ? MonsterUtilityAi.UTILITY_SCALE : 3000;

                BattleAction attackAction = BattleAction.newBuilder()
                        .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                        .setTargetId(target.actorId())
                        .build();
                pushMonsterCandidateIfLegal(actor, target, attackAction, missingHp, uncontrolled, threat,
                        0, targetIsPlayer, candidates);

                for (int skillId : actor.state().getSkillTableIdsList()) {
                    BattleAction skillAction = BattleAction.newBuilder()
                            .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                            .setSkillTableId(skillId)
                            .setTargetId(target.actorId())
                            .build();
                    pushMonsterCandidateIfLegal(actor, target, skillAction, missingHp, uncontrolled, threat,
                            MonsterUtilityAi.UTILITY_SCALE, targetIsPlayer, candidates);
                }
            }

            DungeonMonsterAi.DecisionInput input = new DungeonMonsterAi.DecisionInput(
                    ctx.roundIndex(), selfHpPermyriad, true, true, candidates);
            ctrl.decideAction(input).ifPresent(decided -> pendingActions.put(actor.actorId(), decided));
        }
    }

    private void pushMonsterCandidateIfLegal(BattleUnit actor, BattleUnit target, BattleAction action,
                                             int missingHp, int uncontrolled, int threat, int isSkillFlag,
                                             int targetIsPlayer, List<DungeonMonsterAi.Candidate> out) {
        if (checks.checkActionPrerequisites(actor, action) != ActionChecks.SUCCESS) {
            return;
        }
        int[] features = new int[DungeonMonsterAi.FEATURE_COUNT];
        features[DungeonMonsterAi.FEATURE_TARGET_MISSING_HP] = missingHp;
        features[DungeonMonsterAi.FEATURE_TARGET_UNCONTROLLED] = uncontrolled;
        features[DungeonMonsterAi.FEATURE_TARGET_THREAT] = threat;
        features[DungeonMonsterAi.FEATURE_IS_SKILL] = isSkillFlag;
        features[DungeonMonsterAi.FEATURE_PHASE_URGENCY] = 0; // 由行为树按当前 HFSM 阶段回填
        features[DungeonMonsterAi.FEATURE_TARGET_IS_PLAYER] = targetIsPlayer;
        out.add(new DungeonMonsterAi.Candidate(action, target.state().getFormationSlot(), features));
    }

    /**
     * 按单位插入序，给每个存活、未逃、还没有行动的单位补一条「普攻，目标 0」（{@code FillDefaultActions}，{@code engine.cpp:673-687}）：
     * 配置了副本 AI 的怪物先走 HFSM + 行为树 + 定点效用打分；
     * 挂机玩家先尝试有净收益的职业技能；非挂机玩家和未配置 AI 的怪物仍用普攻。
     * 宝宝按快照技能顺序尝试首个通过原校验链的技能，目标按单位插入序取存活敌方。
     * 没有可用技能则普攻，也包括被眩晕的单位（执行时被跳过）。选技能与目标不耗随机数。
     */
    private void fillDefaultActions() {
        fillDungeonMonsterAiActions();
        for (BattleUnit unit : ctx.units()) {
            if (!unit.isActive()) {
                continue;
            }
            if (!pendingActions.containsKey(unit.actorId())) {
                if (ctx.encounter.protectedGuardActorId() != 0L && unit.actorId() == ctx.encounter.protectedGuardActorId()) {
                    pendingActions.put(unit.actorId(), BattleAction.newBuilder()
                            .setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
                    continue;
                }
                BattleAction fallback = ctx.configured.isPhysicalForbidden(unit)
                        ? BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build()
                        : DEFAULT_ACTION;
                BattleAction modern = unit.isPet() || (unit.isPlayer() && unit.isAuto()) ? ctx.configured.auto(unit,checks).orElse(null) : null;
                BattleAction action = modern != null ? modern : unit.isPet() ? defaultPetAction(unit)
                        : unit.isPlayer() && unit.isAuto() ? autoClassSkills.select(unit).orElse(fallback)
                        : fallback;
                pendingActions.put(unit.actorId(), action);
            }
        }
    }

    private BattleAction defaultPetAction(BattleUnit pet) {
        long[] targets = ctx.collectAliveEnemyIds(pet);
        if (targets.length == 0) {
            return DEFAULT_ACTION;
        }
        for (int skillTableId : pet.state().getSkillTableIdsList()) {
            BattleAction action = BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                    .setSkillTableId(skillTableId)
                    .setTargetId(targets[0])
                    .build();
            if (checks.checkActionPrerequisites(pet, action) == ActionChecks.SUCCESS) {
                return action;
            }
        }
        return DEFAULT_ACTION;
    }

    /** 回合开始时固定出手序：可施放职业技能的 initiative、有效速度降序，最后按无符号 actor_id 升序。 */
    private List<Long> buildTurnOrder() {
        List<BattleUnit> ordered = new ArrayList<>(ctx.units().size());
        for (BattleUnit unit : ctx.units()) {
            if (unit.isActive()) {
                ordered.add(unit);
            }
        }
        boolean ranked = isRankedBattle();
        long seed = request.getSeed();
        int roundIdx = ctx.roundIndex();
        ordered.sort((left, right) -> {
            int initiative = Integer.compare(actionInitiative(right), actionInitiative(left));
            if (initiative != 0) return initiative;
            int speed = Long.compareUnsigned(ctx.effectiveSpeed(right), ctx.effectiveSpeed(left));
            if (speed != 0) return speed;
            if (ranked) {
                long leftTie = rankedTurnTieKey(seed, roundIdx, left.actorId());
                long rightTie = rankedTurnTieKey(seed, roundIdx, right.actorId());
                int tieCmp = Long.compareUnsigned(leftTie, rightTie);
                if (tieCmp != 0) return tieCmp;
            }
            return Long.compareUnsigned(left.actorId(), right.actorId());
        });
        List<Long> order = new ArrayList<>(ordered.size());
        for (BattleUnit unit : ordered) {
            order.add(unit.actorId());
        }
        return List.copyOf(order);
    }

    private int actionInitiative(BattleUnit actor) {
        BattleAction action = pendingActions.get(actor.actorId());
        if (action == null || action.getActionType() != eBattleActionType.BATTLE_ACTION_SKILL) return 0;
        Optional<SkillTable> skill = ctx.data().skill(action.getSkillTableId());
        if (skill.isEmpty() || (skill.get().getBattleModel() != 1 && !BattleRules.isClassSkill(skill.get())) || skill.get().getInitiative() == 0) return 0;
        return checks.checkActionPrerequisites(actor, action) == ActionChecks.SUCCESS ? skill.get().getInitiative() : 0;
    }

    /**
     * 执行一个行动（{@code ExecuteAction}，{@code engine.cpp:713-739}）：身上有眩晕或冰冻 buff 时行动作废、不发事件（组号已占）；
     * 否则按 action_type 的 int 值分派，未知类型什么也不做。
     */
    private void executeAction(BattleUnit actor, BattleAction action) {
        if (ctx.configured.executePreparedCastIfReady(actor)) {
            return;
        }
        if (ctx.configured.control(actor,action,checks)) return;
        if (ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_STUN)
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_FREEZE)) {
            return;
        }
        switch (action.getActionTypeValue()) {
            case eBattleActionType.BATTLE_ACTION_ATTACK_VALUE -> {
                if (ctx.configured.isPhysicalForbidden(actor)) {
                    break;
                }
                executeAttack(actor, action.getTargetId());
            }
            case eBattleActionType.BATTLE_ACTION_SKILL_VALUE -> executeSkill(actor, action);
            case eBattleActionType.BATTLE_ACTION_DEFEND_VALUE -> executeDefend(actor);
            case eBattleActionType.BATTLE_ACTION_ITEM_VALUE -> ledger.executeItem(actor, action, checks);
            case eBattleActionType.BATTLE_ACTION_CAPTURE_VALUE -> executeCapture(actor, action);
            case eBattleActionType.BATTLE_ACTION_RECALL_PET_VALUE -> {
                if (checks.checkActionPrerequisites(actor,action) != ActionChecks.SUCCESS) return;
                var pet = ctx.findActor(action.getTargetId());
                pet.state().setFled(false).clearWithdrawUntilRound();
                ctx.events().append(eBattleEventType.BATTLE_EVENT_PET_RETREAT,actor.actorId(),pet.actorId())
                        .setValue(0).setSuccess(true).setTargetHealthAfter(pet.health());
            }
            case eBattleActionType.BATTLE_ACTION_FLEE_VALUE -> executeFlee(actor);
            default -> {
                // 未知类型：什么也不做
            }
        }
    }

    /**
     * 普攻（{@code ExecuteAttack}，{@code engine.cpp:741-783}；规格 §3.6）。
     * <ol>
     *   <li>目标不存在、已死、已逃，或<strong>与自己同队</strong>时重选：从存活敌方（插入序）里 RandIndex 选一个——
     *       <strong>只有 1 个候选也消耗一次随机数</strong>（R1）；没有敌方则落空、不发事件。显式指定了存活敌方时不耗随机数；</li>
     *   <li>ATTACK 事件；命中判定恒为真（MISS 分支不可达，骨架保留）；</li>
     *   <li>伤害：基础 10、物攻、倍率 1，暴击掷骰 R4 在这里；落血；DAMAGE 事件（value = 实扣，为 0 也发；不带 skill_table_id）；</li>
     *   <li>目标气血为 0 时结算死亡（快照带进来的「0 血但活着」的单位被打一下也会走到这里判死）。</li>
     * </ol>
     */
    private void executeAttack(BattleUnit actor, long targetId) {
        BattleUnit target = ctx.findActor(targetId);
        if (target == null || !target.isActive() || target.teamIndex() == actor.teamIndex()) {
            long[] enemyIds = ctx.collectAliveEnemyIds(actor);
            if (enemyIds.length == 0) {
                return; // 敌方已清场，本次行动落空
            }
            targetId = enemyIds[(int) ctx.rng().randIndex(enemyIds.length)];
            target = ctx.findActor(targetId);
            if (target == null) {
                return;
            }
        }

        ctx.events().append(eBattleEventType.BATTLE_EVENT_ATTACK, actor.actorId(), targetId);

        if (!resolveBasicAttackHit(actor, target, eBattleHitKind.BATTLE_HIT_NORMAL)) return;
        for (int hit = 1; hit <= BattleConstants.MAX_COMBO_EXTRA_HITS; hit++) {
            if (!actor.isActive() || !target.isActive() || !ctx.rng().rollPercent(actor.combat().getComboRate())) break;
            ctx.events().setHitIndex(hit);
            resolveBasicAttackHit(actor, target, eBattleHitKind.BATTLE_HIT_COMBO);
        }
        ctx.events().setHitIndex(0);
        // 单次物理攻击标记（S01 截锋令 / S09 授锋战印）覆盖整次普攻行动（含连击），行动末一次性消耗。
        if (actor.isActive()) {
            ctx.configured.consumeOneShotAttackModifiers(actor, CombatDamageRules.PHYSICAL_DAMAGE);
        }
        tryCounterAttack(actor, target);
    }

    /** 普攻每段独立命中 / 必杀；仅连击段乘 50%，普通段不额外乘除，保住旧回放浮点末位。 */
    private boolean resolveBasicAttackHit(BattleUnit actor, BattleUnit target, eBattleHitKind kind) {
        if (!ctx.rollHit(actor, target)) {
            ctx.events().append(eBattleEventType.BATTLE_EVENT_MISS, actor.actorId(), target.actorId())
                    .setValue(0)
                    .setTargetHealthAfter(target.health())
                    .setTargetManaAfter(target.mana()).setHitKind(kind);
            return false;
        }

        BattleContext.FinalDamage finalDamage = ctx.calculateFinalDamage(actor, target,
                BattleConstants.BASIC_ATTACK_BASE_DAMAGE, ctx.effectivePhysicalAttack(actor), 1.0, CombatDamageRules.PHYSICAL_DAMAGE);
        double damage = finalDamage.value();
        if (kind == eBattleHitKind.BATTLE_HIT_COMBO) damage *= BattleConstants.COMBO_DAMAGE_PERCENT / 100.0;
        if (ctx.configured.activeFor(actor,target)) {
            var hit = new BattleEffect.Hit(0,1,kind == eBattleHitKind.BATTLE_HIT_COUNTER ? BattleEffect.MIRRORED : BattleEffect.DIRECT,target.actorId());
            hit.critical = finalDamage.critical();
            if (kind == eBattleHitKind.BATTLE_HIT_COMBO) hit.damageOrdinal = 1;
            long landed = ctx.configured.impact.damage(actor,target,damage,CombatDamageRules.PHYSICAL_DAMAGE,hit,kind);
            hit.actualDamage = landed;
            if (landed != 0 && kind == eBattleHitKind.BATTLE_HIT_NORMAL && target.isActive()) {
                ctx.encounter.applyBreakerSunder(actor, target);
            }
            if (landed != 0 && kind == eBattleHitKind.BATTLE_HIT_NORMAL && actor.isActive())
                ctx.configured.trigger(actor,target,BattleEffect.AFTER_BASIC,CombatDamageRules.PHYSICAL_DAMAGE,hit);
            if (kind != eBattleHitKind.BATTLE_HIT_COUNTER) tryReflectDamage(actor,target,landed);
            return landed != 0;
        }
        double legacyDamage = damage;
        if (ctx.escalationStacks() > 0) {
            legacyDamage *= ctx.escalationDamagePct() / 100.0;
        }
        long dealt = ctx.encounter.applyLegacyDamage(actor, target, legacyDamage, CombatDamageRules.PHYSICAL_DAMAGE,
                kind != eBattleHitKind.BATTLE_HIT_COUNTER);
        if (kind != eBattleHitKind.BATTLE_HIT_COUNTER) {
            ctx.encounter.recordTrialDirectDamage(actor, target, CombatDamageRules.PHYSICAL_DAMAGE, dealt);
        }
        if (dealt != 0) ctx.encounter.recordEffectiveContribution(actor);

        ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, actor.actorId(), target.actorId())
                .setValue(dealt)
                .setIsCritical(finalDamage.critical())
                .setTargetHealthAfter(target.health())
                .setTargetManaAfter(target.mana()).setHitKind(kind);

        if (dealt != 0 && kind == eBattleHitKind.BATTLE_HIT_NORMAL && target.health() != 0) {
            ctx.encounter.applyBreakerSunder(actor, target);
        }

        if (target.health() == 0) {
            ctx.handleDeath(target, actor.actorId());
        }
        if (kind != eBattleHitKind.BATTLE_HIT_COUNTER) tryReflectDamage(actor, target, dealt);
        return true;
    }

    private void tryReflectDamage(BattleUnit attacker, BattleUnit defender, long dealt) {
        if (dealt == 0 || !attacker.isActive() || !defender.isActive()
                || !ctx.rng().rollPercent(defender.combat().getReflectRate())) return;
        // 用整数实扣计算，不再过抗性 / 必杀 / 防御姿态；ceil 在 uint64 上也不溢出。
        long reflected = EquipmentCombatRules.percentCeil(dealt, BattleConstants.REFLECT_DAMAGE_PERCENT);
        reflected = Unsigned.minUnsigned(attacker.health(), reflected == 0 ? 1 : reflected);
        attacker.setHealth(attacker.health() - reflected);
        ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, defender.actorId(), attacker.actorId())
                .setValue(reflected).setHitKind(eBattleHitKind.BATTLE_HIT_REFLECT)
                .setTargetHealthAfter(attacker.health()).setTargetManaAfter(attacker.mana());
        if (attacker.health() == 0) ctx.handleDeath(attacker, defender.actorId());
    }

    private void tryCounterAttack(BattleUnit attacker, BattleUnit defender) {
        if (!attacker.isActive() || !defender.isActive()
                || checks.checkState(defender) != ActionChecks.SUCCESS
                || ctx.configured.isPhysicalForbidden(defender)
                || !ctx.rng().rollPercent(defender.combat().getCounterRate())) return;
        ctx.events().beginGroup();
        ctx.events().append(eBattleEventType.BATTLE_EVENT_ATTACK, defender.actorId(), attacker.actorId())
                .setHitKind(eBattleHitKind.BATTLE_HIT_COUNTER);
        resolveBasicAttackHit(defender, attacker, eBattleHitKind.BATTLE_HIT_COUNTER);
    }

    /**
     * 技能（{@code ExecuteSkill}，{@code engine.cpp:785-856}；规格 §4.7）。
     * <ol>
     *   <li>重跑整条校验链（提交后到出手前，冷却 / 沉默 / 目标状态都可能变了），不通过或表行缺失就<strong>降级为普攻</strong>，
     *       事件落在同一组（R3 走 R1）；</li>
     *   <li>目标集：AOE（任一位号是 AOE）取全体存活敌方（插入序，不耗随机数），为空则直接返回——不出 SKILL、不开冷却、不扣蓝；
     *       单体：指定目标存在且存活未逃就用它（<strong>不查阵营，可以打队友或自己</strong>），否则从存活敌方 RandIndex 选一个（R2），
     *       没有敌方直接返回；</li>
     *   <li>开冷却：冷却时长 &gt; 0 时 {@code cooldown[skill_table_id] = 毫秒换回合}，覆盖原值；</li>
     *   <li>SKILL 事件（target = 首目标）→ 耗蓝（MANA 事件）→ 伤害公式只求值一次、所有目标共用（等级按 uint32 换 double）；</li>
     *   <li>逐目标落地：hit_index = 目标序，查不到的目标跳过但下标照常递增；结束后 hit_index 归 0。</li>
     * </ol>
     */
    private void executeSkill(BattleUnit actor, BattleAction action) {
        Optional<SkillTable> classRow = ctx.data().skill(action.getSkillTableId());
        if (classRow.isPresent() && classRow.get().getBattleModel() == 1) {
            if (checks.checkActionPrerequisites(actor,action) == ActionChecks.SUCCESS)
                ctx.configured.cast(actor,classRow.get(),action.getTargetId(),BattleEffect.DIRECT,1);
            return;
        }
        if (classRow.isPresent() && BattleRules.isClassSkill(classRow.get())) {
            if (classSkills.execute(actor, action, classRow.get()) == ClassSkillExecution.Outcome.FALLBACK_ATTACK) {
                executeAttack(actor, action.getTargetId());
            }
            return;
        }
        if (checks.checkActionPrerequisites(actor, action) != ActionChecks.SUCCESS) {
            executeAttack(actor, action.getTargetId());
            return;
        }
        Optional<SkillTable> skillRow = ctx.data().skill(action.getSkillTableId());
        if (skillRow.isEmpty()) {
            executeAttack(actor, action.getTargetId());
            return;
        }
        SkillTable row = skillRow.get();

        long[] targetIds;
        if (BattleRules.isAreaSkill(row)) {
            targetIds = ctx.collectAliveEnemyIds(actor);
            if (targetIds.length == 0) {
                return;
            }
        } else {
            long targetId = action.getTargetId();
            BattleUnit target = ctx.findActor(targetId);
            if (target == null || !target.isActive()) {
                long[] enemyIds = ctx.collectAliveEnemyIds(actor);
                if (enemyIds.length == 0) {
                    return;
                }
                targetId = enemyIds[(int) ctx.rng().randIndex(enemyIds.length)];
                if (ctx.findActor(targetId) == null) {
                    return;
                }
            }
            targetIds = new long[] {targetId};
        }
        long primaryTargetId = targetIds[0];

        long cooldownMs = ctx.data().cooldownDurationMs(row.getCooldownId());
        if (cooldownMs != 0) {
            actor.state().putSkillCooldownRounds(action.getSkillTableId(), BattleRules.roundsFromMillis(cooldownMs));
        }

        ctx.events().append(eBattleEventType.BATTLE_EVENT_SKILL, actor.actorId(), primaryTargetId)
                .setSkillTableId(action.getSkillTableId());

        consumeSkillMana(actor, row, action.getSkillTableId());

        double baseDamage = ctx.data().skillDamage(action.getSkillTableId(), EquipmentCombatRules.skillLevel(actor.level(), actor.combat()));

        for (int hitIndex = 0; hitIndex < targetIds.length; hitIndex++) {
            BattleUnit target = ctx.findActor(targetIds[hitIndex]);
            if (target == null) {
                continue;
            }
            ctx.events().setHitIndex(hitIndex);
            applySkillToTarget(actor, action, row, baseDamage, target, primaryTargetId);
        }
        ctx.events().setHitIndex(0);
    }

    /**
     * 技能对单个目标落地（{@code ApplySkillToTarget}，{@code engine.cpp:858-901}）：命中恒为真（不耗随机数）；基础伤害 &gt; 0 时
     * 按 damage_type 选物攻 / 法攻、过伤害公式（暴击掷骰 R4 逐目标一次）、落血、出 DAMAGE（带 skill_table_id）、气血为 0 结算死亡；
     * 基础伤害为 0、负数或 NaN 时整段跳过（没有 DAMAGE，也不掷暴击）。目标没死才按 effect[] 表序逐个挂 buff（施法者为出手者，深度 0）。
     */
    private void applySkillToTarget(BattleUnit actor, BattleAction action, SkillTable row, double baseDamage,
                                    BattleUnit target, long primaryTargetId) {
        if (!ctx.rollHit(actor, target)) {
            ctx.events().append(eBattleEventType.BATTLE_EVENT_MISS, actor.actorId(), target.actorId())
                    .setSkillTableId(action.getSkillTableId())
                    .setValue(0)
                    .setTargetHealthAfter(target.health())
                    .setTargetManaAfter(target.mana());
            return;
        }

        if (baseDamage > 0) {
            long attack = CombatDamageRules.selectAttack(row.getDamageType(),
                    ctx.effectivePhysicalAttack(actor), actor.magicAttack());
            BattleContext.FinalDamage finalDamage = ctx.calculateFinalDamage(actor, target, baseDamage, attack,
                    row.getAttackMultiplier(), row.getDamageType());
            boolean isAreaSkill = BattleRules.isAreaSkill(row);
            if (ctx.configured.activeFor(actor,target)) {
                var hit = new BattleEffect.Hit(row.getId(),1,BattleEffect.DIRECT,primaryTargetId);
                hit.critical = finalDamage.critical();
                hit.isMultiTargetSkill = isAreaSkill;
                ctx.configured.impact.damage(actor,target,finalDamage.value(),row.getDamageType(),hit,eBattleHitKind.BATTLE_HIT_NORMAL);
            } else {
            double legacySkillDamage = finalDamage.value();
            if (ctx.escalationStacks() > 0) {
                legacySkillDamage *= ctx.escalationDamagePct() / 100.0;
            }
            boolean isPrimaryTarget = primaryTargetId == 0L || target.actorId() == primaryTargetId;
            boolean[] splashReduced = new boolean[1];
            long dealt = ctx.encounter.applyLegacyDamage(actor, target, legacySkillDamage, row.getDamageType(), true,
                    isAreaSkill, isPrimaryTarget, splashReduced);
            ctx.encounter.recordTrialDirectDamage(actor, target, row.getDamageType(), dealt);
            if (dealt != 0) ctx.encounter.recordEffectiveContribution(actor);

            var dmgEv = ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, actor.actorId(), target.actorId())
                    .setSkillTableId(action.getSkillTableId())
                    .setValue(dealt)
                    .setIsCritical(finalDamage.critical())
                    .setTargetHealthAfter(target.health())
                    .setTargetManaAfter(target.mana());
            if (splashReduced[0]) {
                dmgEv.setIsSplashReduced(true);
            }

            if (target.health() == 0) {
                ctx.handleDeath(target, actor.actorId());
            }
        }

        }
        if (!target.isDead()) {
            for (int effectBuffId : row.getEffectList()) {
                buffs.addBuffToActor(target, effectBuffId, actor.actorId(), 0);
            }
        }
    }

    /**
     * 扣蓝（{@code ConsumeSkillMana}，{@code engine.cpp:924-940}；规格 §4.6）：耗蓝为 0 不出事件；{@code after = before > cost ? before - cost : 0}
     * （uint64）；MANA 事件 source = target = 施法者，value = 实耗，mana_delta 为负的实际量，越过 sint64 时饱和显示。
     */
    private void consumeSkillMana(BattleUnit actor, SkillTable row, int skillTableId) {
        long manaCost = ctx.encounter.adjustWeeklySkillManaCost(actor, row, BattleRules.skillManaCost(row));
        if (manaCost == 0) {
            return;
        }
        long manaBefore = actor.mana();
        long manaAfter = Long.compareUnsigned(manaBefore, manaCost) > 0 ? manaBefore - manaCost : 0;
        actor.setMana(manaAfter);

        ctx.events().append(eBattleEventType.BATTLE_EVENT_MANA, actor.actorId(), actor.actorId())
                .setSkillTableId(skillTableId)
                .setValue(manaBefore - manaAfter)
                .setManaDelta(EventLog.signedManaDelta(manaBefore - manaAfter, false))
                .setTargetHealthAfter(actor.health())
                .setTargetManaAfter(manaAfter);
    }

    /**
     * 防御（{@code ExecuteDefend}，{@code engine.cpp:942-945}；规格 §3.8）：<strong>执行这一刻</strong>才置防御位，一直保持到回合末 tick 之后；
     * 速度更快的敌人在此之前打出的伤害不减半，回合末毒 / 灼烧会减半。DEFEND 事件 source = target = 自己。
     */
    private void executeDefend(BattleUnit actor) {
        actor.setDefending(true);
        var ev = ctx.events().append(eBattleEventType.BATTLE_EVENT_DEFEND, actor.actorId(), actor.actorId());
        int manaRegenBps = SeasonRules.defendBonusManaRegenBps(ctx.activeSeasonWeeklyAffixId());
        if (manaRegenBps > 0 && Long.compareUnsigned(actor.maxMana(), 0L) > 0
                && Long.compareUnsigned(actor.mana(), actor.maxMana()) < 0) {
            long curMana = actor.mana();
            long missing = actor.maxMana() - curMana;
            long raw = Long.divideUnsigned(actor.maxMana() * Integer.toUnsignedLong(manaRegenBps), 10000L);
            long gain = Long.compareUnsigned(missing, Math.max(1L, raw)) < 0 ? missing : Math.max(1L, raw);
            actor.setMana(curMana + gain);
            ev.setValue(gain);
        }
        ctx.configured.equipment.defend(actor);
    }

    /** 捕获后离场并登记本人的待结算宠物；目标失效仍消耗行动。 */
    private void executeCapture(BattleUnit actor, BattleAction action) {
        var event = ctx.events().append(eBattleEventType.BATTLE_EVENT_CAPTURE, actor.actorId(), action.getTargetId());
        if (checks.checkCapture(actor, action.getTargetId()) != ActionChecks.SUCCESS) return;
        BattleUnit target = ctx.findActor(action.getTargetId());
        var row = ctx.data().monster(target.state().getMonsterTableId());
        if (row.isEmpty() || target.maxHealth() == 0) return;
        double lostRatio = 1.0 - com.game.common.math.Unsigned.toDouble(target.health()) /
                com.game.common.math.Unsigned.toDouble(target.maxHealth());
        double chance = Math.min(1.0, Integer.toUnsignedLong(row.get().getCaptureRate()) / 10000.0 * (1.0 + 2.0 * lostRatio));
        boolean success = chance >= 1.0 || ctx.rng().rand01() < chance;
        event.setSuccess(success).setValue(Integer.toUnsignedLong(target.state().getCapturePetTableId()))
                .setTargetHealthAfter(target.health());
        if (!success) return;
        target.state().setCapturedByPlayerId(actor.actorId());
        target.markFled();
        ctx.settlements().computeIfAbsent(actor.actorId(), ignored -> BattleSettlementData.newBuilder())
                .addCapturedPetTableIds(target.state().getCapturePetTableId());
    }

    /**
     * 逃跑（{@code ExecuteFlee}，{@code engine.cpp:1014-1031}；规格 §3.9）：只在 PVE 时按速度差算成功率并掷一次 Rand01（R5），
     * 严格小于即成功；FLEE 事件 source = target = 自己，带 success；成功则置逃跑位（保留 buff 与冷却，之后不再 tick、不再被选为目标）。
     * PVP 下提交时就被拒、默认行动是普攻，所以执行期的非 PVE 分支不可达。
     */
    private void executeFlee(BattleUnit actor) {
        boolean success = false;
        if (ctx.encounter.isBossTrial() && actor.isPlayer()) {
            // 首领试炼确定退出（boss-trial-planning-ui.md §6.2）：无随机逃跑失败、不消耗 RNG，本人及宝宝原子离场。
            success = true;
        } else if (ctx.isPve()) {
            double chance = BattleRules.fleeChance(ctx.effectiveSpeed(actor), ctx.maxAliveEnemySpeed(actor));
            success = ctx.rng().rand01() < chance;
        }
        ctx.events().append(eBattleEventType.BATTLE_EVENT_FLEE, actor.actorId(), actor.actorId())
                .setSuccess(success);
        if (success) {
            actor.markFled();
            if ((ctx.encounter.isBossTrial() || ctx.encounter.isFestivalBeast()) && actor.isPlayer()) {
                for (BattleUnit other : ctx.units()) {
                    if (other.isPet() && other.ownerPlayerId() == actor.actorId() && !other.fled()) {
                        boolean wasActive = other.isActive();
                        other.markFled();
                        if (wasActive) {
                            ctx.events().append(eBattleEventType.BATTLE_EVENT_FLEE, other.actorId(), other.actorId())
                                    .setSuccess(true);
                        }
                    }
                }
            }
        }
    }

    /**
     * 冷却衰减（{@code DecayCooldowns}，{@code engine.cpp:1169-1178}；规格 §4.4）：<strong>全部单位</strong>（含已死、已逃），
     * 每项 &gt; 0（uint32）的剩余回合减 1；减到 0 的项<strong>不删除</strong>。先拷出键值对再逐个 put，不在 map 视图上边迭代边改。
     */
    private void decayCooldowns() {
        for (BattleUnit unit : ctx.units()) {
            List<Map.Entry<Integer, Integer>> entries = List.copyOf(unit.state().getSkillCooldownRoundsMap().entrySet());
            for (Map.Entry<Integer, Integer> entry : entries) {
                if (entry.getValue() != 0) {
                    unit.state().putSkillCooldownRounds(entry.getKey(), entry.getValue() - 1);
                }
            }
        }
    }

    /**
     * 胜负（{@code UpdateOutcome}，{@code engine.cpp:1180-1194}；规格 §3.11）：两方都灭 → 平局；A 灭 → B 胜；B 灭 → A 胜；
     * 否则正在结算的回合号 ≥ 回合上限（uint32）→ B 胜（打满回合进攻方判负，PVP 也一样）。
     */
    private void updateOutcome() {
        // 护卫救援：受保护护卫阵亡即刻判负（即便同回合击杀首领仍判负）。
        if (ctx.encounter.bossTrialKind() == BattleConstants.BOSS_TRIAL_KIND_GUARDIAN_RESCUE) {
            BattleUnit guard = ctx.findActor(ctx.encounter.protectedGuardActorId());
            if (guard == null || guard.isDead()) {
                outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
                return;
            }
            boolean anyPlayerActive = false;
            for (BattleUnit unit : ctx.units()) {
                if (unit.isPlayer() && unit.teamIndex() == 0 && unit.isActive()) {
                    anyPlayerActive = true;
                    break;
                }
            }
            if (!anyPlayerActive) {
                outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
                return;
            }
            if (ctx.sideWiped(1)) {
                outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
                return;
            }
            if (Integer.compareUnsigned(ctx.roundIndex(), maxRounds) >= 0) {
                outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
            }
            return;
        }
        boolean sideAWiped = ctx.sideWiped(0);
        boolean sideBWiped = ctx.sideWiped(1);
        if (sideAWiped && sideBWiped) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        } else if (sideAWiped) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        } else if (sideBWiped) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
        } else if (Integer.compareUnsigned(ctx.roundIndex(), maxRounds) >= 0) {
            boolean drawOnCap = isRankedBattle()
                    || BattleConstants.isGuildWarMatchMode(request.getMatchMode())
                    || ctx.encounter.bossTrialKind() == BattleConstants.BOSS_TRIAL_KIND_POLARITY_5V5
                    || request.getMatchMode() == BattleConstants.MATCH_MODE_POLARITY_5V5;
            outcome = drawOnCap ? eBattleOutcome.BATTLE_OUTCOME_DRAW : eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        }
    }

    /**
     * 掉落（{@code RollDrops}，{@code engine.cpp:1196-1243}；规格 §6.5）：只在刚判出 A 方胜且击杀簿非空时执行；战斗结束后结算会提前返回，
     * 所以整局恰好执行一次，且在本回合全部战斗随机数之后。三层遍历顺序都固定：
     * 合格玩家（player_id 无符号升序；actor 存在、team 0、未逃、未死）× 击杀簿（击杀序；查不到怪物表行跳过）×
     * 掉落槽（表序；物品 / 数量 / 概率任一为 0 是空槽，不掷骰）。非空槽<strong>一律掷一次</strong> Rand01（R6，概率 ≥ 10000 也掷），
     * {@code Rand01() × 10000 >= drop_rate} 不掉；掉落时在 items_gained 里找第一条同物品的条目累加，找不到就追加。
     * 组队人人独立掷骰、不分赃；击杀簿 count 恒为 1，不按 count 放大。
     */
    private void rollDrops() {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN || ctx.defeatedMonsters().isEmpty()
                || isFestivalBeastBattle()) {
            return;
        }
        for (Map.Entry<Long, BattleSettlementData.Builder> playerSettlement : ctx.settlements().entrySet()) {
            BattleUnit actor = ctx.findActor(playerSettlement.getKey());
            if (actor == null || actor.teamIndex() != 0 || actor.fled() || actor.isDead()) {
                continue;
            }
            BattleSettlementData.Builder settlement = playerSettlement.getValue();
            for (BattleMonsterDefeat defeat : ctx.defeatedMonsters()) {
                Optional<MonsterTable> monsterRow = ctx.data().monster(defeat.getMonsterConfigId());
                if (monsterRow.isEmpty()) {
                    continue;
                }
                for (Monsterdrop drop : monsterRow.get().getDropList()) {
                    if (drop.getDropItem() == 0 || drop.getDropCount() == 0 || drop.getDropRate() == 0) {
                        continue;
                    }
                    if (ctx.rng().rand01() * (double) BattleConstants.DROP_RATE_DENOMINATOR
                            >= (double) Integer.toUnsignedLong(drop.getDropRate())) {
                        continue;
                    }
                    BattleItemEntry.Builder gained = null;
                    for (BattleItemEntry.Builder entry : settlement.getItemsGainedBuilderList()) {
                        if (entry.getItemTableId() == drop.getDropItem()) {
                            gained = entry;
                            break;
                        }
                    }
                    if (gained == null) {
                        gained = settlement.addItemsGainedBuilder().setItemTableId(drop.getDropItem());
                    }
                    gained.setCount(gained.getCount() + Integer.toUnsignedLong(drop.getDropCount()));
                }
            }
        }
    }

    // =====================================================================================================
    // 输出（engine.cpp:1287-1306、:1570-1656、:1767-1770）
    // =====================================================================================================

    /** 最近一回合的出手序（actor_id，含被跳过者）；不可变。第一次结算之前为空。 */
    public List<Long> lastActionOrder() {
        return lastActionOrder;
    }

    /** 当前胜负。 */
    public eBattleOutcome outcome() {
        return outcome;
    }

    /**
     * 某玩家的结算（{@code BuildSettlement}，{@code engine.cpp:1570-1627}；规格 §6.6）：纯读，可重复调用，每次结果逐字节相同。
     * <ol>
     *   <li>从累积条目拷一份（带消耗账与掉落），没有就从空对象开始；存着的 builder 不动；</li>
     *   <li>写 battle_id、player_id、全局胜负（不换视角）、已完成回合数，清掉 defeated_monsters；</li>
     *   <li>玩家不在本局 → 到此为止；否则写 team、气血、法力、死亡、逃跑，并按插入序追加名下全部宝宝（不论死活）的终值；</li>
     *   <li>只有 A 方胜、本人 team 0、未逃、未死时：拷击杀簿，经验 / 金币是各怪物表行奖励的 uint64 和（查不到行的怪物照样拷、不计数值），
     *       组队人人全额。</li>
     * </ol>
     */
    public BattleSettlementData buildSettlement(long playerId) {
        BattleSettlementData.Builder stored = ctx.settlements().get(playerId);
        BattleSettlementData.Builder settlement = stored != null
                ? stored.build().toBuilder()
                : BattleSettlementData.newBuilder();
        settlement.setBattleId(request.getBattleId())
                .setPlayerId(playerId)
                .setOutcome(outcome)
                .setTotalRounds(completedRounds())
                .clearDefeatedMonsters();

        BattleUnit actor = ctx.findActor(playerId);
        if (actor == null) {
            return settlement.build();
        }
        settlement.setPlayerTeamIndex(actor.teamIndex())
                .setHealth(actor.health())
                .setMana(actor.mana())
                .setIsDead(actor.isDead())
                .setFled(actor.fled())
                .setSurrendered(surrenderedTeam >= 0 && actor.teamIndex() == surrenderedTeam)
                .setTotalDamageDealt(totalDamageDealtByPlayer.getOrDefault(playerId, 0L))
                .setTotalHealingDone(totalHealingDoneByPlayer.getOrDefault(playerId, 0L))
                .setTotalControlsLanded(totalControlsLandedByPlayer.getOrDefault(playerId, 0))
                .setPrimaryFocusDamageDealt(primaryFocusDamageByPlayer.getOrDefault(playerId, 0L))
                .setTotalShieldGranted(totalShieldGrantedByPlayer.getOrDefault(playerId, 0L))
                .setTotalDispelsLanded(ctx.totalDispelsLanded(playerId))
                .setDamageTaken(damageTakenByPlayer.getOrDefault(playerId, 0L));
        boolean playerWon = (actor.teamIndex() == 0 && outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                || (actor.teamIndex() == 1 && outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
        settlement.setHighlightKind(computeHighlightKind(playerId, actor.teamIndex(), playerWon, actor.isDead()));
        if (playerWon) {
            int maxEnemyStreak = 0;
            for (BattleUnit other : ctx.units()) {
                if (other.isPlayer() && other.teamIndex() != actor.teamIndex()) {
                    int st = other.state().getWinStreak();
                    if (st >= BattleConstants.MIN_WIN_STREAK_HIGHLIGHT && st > maxEnemyStreak) {
                        maxEnemyStreak = st;
                    }
                }
            }
            settlement.setEndedOpponentStreak(maxEnemyStreak);
        }

        for (BattleUnit other : ctx.units()) {
            if (!other.isPet() || other.ownerPlayerId() != playerId || other.competitiveTemporary()) {
                continue;
            }
            settlement.addPetsBuilder()
                    .setPetId(other.petId())
                    .setHealth(other.health())
                    .setMana(other.mana())
                    .setIsDead(other.isDead());
        }

        settlement.setUseFreePreset(usesFreePreset())
                .setManualActionRounds(ctx.encounter.manualActionRounds(playerId))
                .setEffectiveCombatContribution(ctx.encounter.hasEffectiveContribution(playerId))
                .setBattleDifficulty(ctx.encounter.battleDifficulty())
                .setBossTrialKind(ctx.encounter.bossTrialKind());
        if (isRankedBattle()) {
            settlement.setCompetitiveRanked(true)
                    .setSeasonId(request.getRankedContext().getSeasonId())
                    .setRankedRuleVersion(request.getRankedContext().getRuleVersion())
                    .setParticipated(didPlayerParticipate(playerId));
        } else if (usesFreePreset()) {
            settlement.setRankedRuleVersion(request.getRankedContext().getRuleVersion())
                    .setParticipated(didPlayerParticipate(playerId));
        }

        if (isFestivalBeastBattle()) {
            settlement.setFestivalBeast(request.getActivityContext().getFestivalBeast());
        }

        if (!isFestivalBeastBattle() && outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && actor.teamIndex() == 0
                && !actor.fled() && !actor.isDead()) {
            long expSum = 0;
            long goldSum = 0;
            for (BattleMonsterDefeat defeat : ctx.defeatedMonsters()) {
                settlement.addDefeatedMonsters(defeat);
                Optional<MonsterTable> row = ctx.data().monster(defeat.getMonsterConfigId());
                if (row.isPresent()) {
                    expSum += row.get().getExpReward();
                    goldSum += row.get().getGoldReward();
                }
            }
            settlement.setExpGain(expSum).setGoldGain(goldSum);
        }
        ctx.encounter.populateTrialFacts(request, actor, playerId, settlement);
        return settlement.build();
    }

    /** 已完成回合数：进行中或收令期投降终局为 roundIndex − 1，其余已结束为 roundIndex（{@code CompletedRounds}，{@code engine.cpp:1767-1770}）。 */
    private int completedRounds() {
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING || surrenderedTeam >= 0) {
            return ctx.roundIndex() > 0 ? ctx.roundIndex() - 1 : 0;
        }
        return ctx.roundIndex();
    }

    /**
     * 全知快照（{@code BuildStateSnapshot}，{@code engine.cpp:1629-1656}；规格 §7.1）：全部单位按插入序整份拷贝（含死亡与逃跑的单位、
     * 全员冷却与 buff），节点下发前必须按收信人裁剪。action_deadline_ms 为 0（引擎无时钟，由节点回填）；self_items 不填。
     * pending_actor_ids 只在进行中填：按插入序取存活未逃、未挂机、还没有行动的玩家。
     */
    public BattleStateS2C buildStateSnapshot() {
        BattleStateS2C.Builder state = BattleStateS2C.newBuilder()
                .setBattleId(request.getBattleId())
                .setRoundIndex(ctx.roundIndex())
                .setOutcome(outcome)
                .setActionDeadlineMs(0)
                .setRoundLimit(maxRounds)
                .setEscalationStacks(escalationStacks())
                .setEscalationStartRound(escalationStartRound())
                .setSurrenderUnlockRound(surrenderUnlockRound());
        if (request.hasTournamentContext() && request.getTournamentContext().getMatchId() != 0L) {
            state.setTournamentContext(request.getTournamentContext());
        }
        if (ctx.activeSeasonWeeklyAffixId() != SeasonRules.WEEKLY_AFFIX_NONE) {
            state.setSeasonWeeklyAffixId(ctx.activeSeasonWeeklyAffixId());
        }
        for (BattleUnit unit : ctx.units()) {
            var visible = state.addActorsBuilder().mergeFrom(unit.snapshot());
            if (unit.isPlayer()) visible.setSupportEffects(ctx.configured.equipment.summary(unit));
        }
        ctx.encounter.populateStateSnapshot(state, outcome, maxRounds);
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            if (prepActive) {
                state.getPrepStateBuilder()
                        .setActive(true)
                        .setDurationSeconds(BattleConstants.TACTICAL_PREP_DURATION_SECONDS);
            } else {
                for (BattleUnit unit : ctx.units()) {
                    if ((!unit.isPlayer() && !unit.isPet()) || !unit.isActive()) {
                        continue;
                    }
                    if (unit.isAuto()) {
                        continue;
                    }
                    if (!pendingActions.containsKey(unit.actorId())) {
                        state.addPendingActorIds(unit.actorId());
                    }
                }
            }
        }
        return state.build();
    }

    /** 下发前清除引擎专用概率；调用方传自己的副本 builder，不分本人、敌方、观众。 */
    public static void stripEngineOnlyState(BattleStateS2C.Builder state) {
        for (BattleActorState.Builder actor : state.getActorsBuilderList()) {
            actor.clearCombat().clearPetCultivation().clearAbilityUseCounts().clearRoundEffectUsage().clearRuntimeSkills()
                    .clearEquipmentEffects().clearArtifactStates().clearSeasonSealState().clearEntryMaxHealth().clearEntryMaxMana();
        }
    }

    /** 该玩家剩余的战斗道具（规格 §6.3）：余量非 0 的条目按 item_table_id 升序（稳定排序，D6）；不在本局的玩家返回空列表。 */
    public List<BattleItemEntry> selfItems(long playerId) {
        return ledger.selfItems(playerId);
    }

    /** 查询指定单位当前剩余的锦标赛 V2 土厚灵脉首回合护盾（供单测核对）。 */
    public long tournamentEarthShieldOf(long actorId) {
        BattleUnit unit = ctx.findActor(actorId);
        return unit == null ? 0L : unit.tournamentEarthShield();
    }

    /** 本局冻结的首领试炼周常五行词缀（0=无/非首领试炼，1金/2木/3水/4火/5土）。 */
    public int bossWeeklyAffixId() {
        return ctx.encounter.bossWeeklyAffixId();
    }

    // =====================================================================================================
    // 测试钩子（包私有）
    // =====================================================================================================

    /**
     * 测试钩子（包私有，对应基线 {@code turn_battle_engine_test.cpp:26-38} 的友元）：直接对单位以深度 0 挂 buff，
     * 产出的事件丢弃。单位不存在时返回 false。不是业务接口（规格 §13.8）。
     */
    boolean addBuffForTest(long targetId, int buffTableId, long sourceId) {
        BattleUnit target = ctx.findActor(targetId);
        if (target == null) {
            return false;
        }
        TurnResultS2C.Builder previous = ctx.events().redirect(TurnResultS2C.newBuilder());
        try {
            buffs.addBuffToActor(target, buffTableId, sourceId, 0);
        } finally {
            ctx.events().redirect(previous);
        }
        return true;
    }

    /** 测试钩子（包私有）：本局 RNG 至今的总抽数（{@link BattleRandom#draws()}），供测试核对规格 §8.4 的消耗账。 */
    long rngDrawsForTest() {
        return ctx.rng().draws();
    }

    /** 测试钩子（包私有）：按 actor_id 查局内单位（供特殊玩法机制单测核对状态）。 */
    BattleUnit findUnitForTest(long actorId) {
        return ctx.findActor(actorId);
    }

    /** 测试钩子（包私有）：读取首领试炼 / 护卫救援 / 物法易势权威机制状态。 */
    EncounterMechanics encounterForTest() {
        return ctx.encounter;
    }
}
