package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleOutcome;
import com.game.table.DungeonTable;
import com.game.table.MonsterTable;
import com.game.table.Monsterdrop;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

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

    /**
     * 出手序：speed 降序，平手时 actor_id 升序，<strong>两个键都按无符号比较</strong>（{@code BuildTurnOrder}，{@code engine.cpp:697-703}）。
     * 怪物 / 宝宝的 actor_id 在 long 里是负数，有符号比较会让同速的怪物排到玩家前面（规格 §3.3 的 G-TIE）。
     * actor_id 唯一，键构成全序，所以基线的不稳定 {@code std::sort} 与这里的稳定排序结果相同。
     */
    private static final Comparator<BattleUnit> TURN_ORDER = (lhs, rhs) -> lhs.speed() != rhs.speed()
            ? Long.compareUnsigned(rhs.speed(), lhs.speed())
            : Long.compareUnsigned(lhs.actorId(), rhs.actorId());

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
    /** actor_id → 本回合行动；按 actor_id 无符号升序（基线 {@code std::map}）。现在只做查找，用有序容器防止日后有人遍历它。 */
    private final NavigableMap<Long, BattleAction> pendingActions = new TreeMap<>(Long::compareUnsigned);
    /** uint32；回合上限（{@code engine.h:228}）。 */
    private int maxRounds = BattleConstants.DEFAULT_MAX_ROUNDS;
    private eBattleOutcome outcome = eBattleOutcome.BATTLE_OUTCOME_ONGOING;
    /** 最近一回合的出手序（含回合中途被跳过者）；下一次结算前保持不变（{@code engine.h:62-65}）。 */
    private List<Long> lastActionOrder = List.of();

    private TurnBattleEngine(CreateBattleRequest request, BattleData data) {
        this.request = request;
        // 基线 Initialize 第 4 步：拷请求、rng.seed(seed)、roundIndex = 1、outcome = ONGOING（engine.cpp:69-72）
        this.ctx = new BattleContext(data, request.getMatchMode(), request.getSeed());
        this.ledger = new ItemLedger(ctx, request);
        this.checks = new ActionChecks(ctx, ledger);
        this.buffs = new BuffEngine(ctx);
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

        // 回合上限：副本行存在且 time_limit（uint32 秒）> 0 时按毫秒换回合，否则 30（engine.cpp:74-79）
        Optional<DungeonTable> dungeonRow = data.dungeon(request.getBattleConfigId());
        if (dungeonRow.isPresent() && dungeonRow.get().getTimeLimit() != 0) {
            engine.maxRounds = BattleRules.roundsFromMillis(Integer.toUnsignedLong(dungeonRow.get().getTimeLimit()) * 1000L);
        }

        BattleStart.Rejected rejected = engine.initPlayers();
        if (rejected != null) {
            return rejected;
        }
        rejected = engine.initPets();
        if (rejected != null) {
            return rejected;
        }
        if (engine.ctx.isPve()) {
            engine.initMonsters();
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
            // 参战 buff 原样拷贝；实例号也纳入局内自增域，避免与新加 buff 撞号
            for (BattleBuffEntry buff : snapshot.getBuffsList()) {
                actor.addBuffs(buff);
                buffs.reserveSnapshotBuffId(buff.getBuffId());
            }
            // 技能只收回合制可施放的（引擎侧兜底，快照来自另一进程，不能只信它）
            for (int skillTableId : snapshot.getSkillTableIdsList()) {
                Optional<SkillTable> skillRow = ctx.data().skill(skillTableId);
                if (skillRow.isEmpty() || !BattleRules.isTurnBattleCastableSkill(skillRow.get())) {
                    continue;
                }
                actor.addSkillTableIds(skillTableId);
            }
            ctx.units().add(new BattleUnit(actor));

            ctx.settlements().put(playerId, BattleSettlementData.newBuilder()
                    .setBattleId(request.getBattleId())
                    .setPlayerId(playerId));
        }
        return null;
    }

    /**
     * 宝宝入场（{@code InitPets}，{@code engine.cpp:178-238}；规格 §1.6）：局内序号跨所有快照全局递增（先按快照、再按快照内顺序），
     * actor_id = 宝宝号段 + 序号，真实 pet_id 另存。pet_id 为 0 拒；同一只宝宝（按 pet_id）已入场拒；快照自带的 owner_player_id
     * 非 0 且不等于所在快照的玩家拒（归属只认所在快照）。与主人同队、恒为挂机、技能<strong>不过滤</strong>，不建结算条目。
     *
     * @return 拒绝原因；全部成功返回 null
     */
    private BattleStart.Rejected initPets() {
        long petIndex = 0;
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            for (BattlePetSnapshot pet : snapshot.getPetsList()) {
                if (pet.getPetId() == 0) {
                    return new BattleStart.Rejected(InitRejection.PET_ID_ZERO,
                            "宝宝 pet_id 为 0: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " player_id=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                if (petAlreadyJoined(pet.getPetId())) {
                    return new BattleStart.Rejected(InitRejection.DUPLICATE_PET,
                            "CreateBattle 同一只宝宝重复参战: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " pet_id=" + Long.toUnsignedString(pet.getPetId()));
                }
                long actorId = BattleConstants.PET_ACTOR_ID_BASE + petIndex;
                petIndex++;
                if (pet.getOwnerPlayerId() != 0 && pet.getOwnerPlayerId() != snapshot.getPlayerId()) {
                    return new BattleStart.Rejected(InitRejection.PET_OWNER_MISMATCH,
                            "CreateBattle 宝宝归属与所在快照不一致: battle_id=" + Long.toUnsignedString(request.getBattleId())
                                    + " pet_id=" + Long.toUnsignedString(pet.getPetId())
                                    + " owner=" + Long.toUnsignedString(pet.getOwnerPlayerId())
                                    + " snapshot_player=" + Long.toUnsignedString(snapshot.getPlayerId()));
                }
                BaseAttributesComp attributes = pet.getBaseAttributes();
                BattleActorState.Builder actor = BattleActorState.newBuilder()
                        .setActorId(actorId)
                        .setPetId(pet.getPetId())
                        .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PET)
                        .setTeamIndex(snapshot.getTeamIndex())
                        .setOwnerPlayerId(snapshot.getPlayerId())
                        .setPetTableId(pet.getPetTableId())
                        .setName(pet.getPetName())
                        .setLevel(pet.getLevel())
                        .setAttributes(attributes)
                        .setMaxHealth(fallbackMax(pet.getMaxHealth(), attributes.getHealth()))
                        .setMaxMana(fallbackMax(pet.getMaxMana(), attributes.getMana()))
                        .setPhysicalAttack(pet.getPhysicalAttack())
                        .setMagicAttack(pet.getMagicAttack())
                        .setDefense(pet.getDefense())
                        .setFormationSlot(ctx.nextFormationSlot(snapshot.getTeamIndex()))
                        // 宝宝没有客户端行动权：标挂机，由默认行动路径代打，也不卡 allPlayersReady（engine.cpp:228-230）
                        .setIsAuto(true)
                        .addAllSkillTableIds(pet.getSkillTableIdsList());
                ctx.units().add(new BattleUnit(actor));
            }
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
     * uint32）；怪物组取副本配置，为空时改成「每个玩家快照一只兜底怪（id 0）」。
     */
    private void initMonsters() {
        int referenceLevel = 1;
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            // std::max(referenceLevel, level) = referenceLevel < level ? level : referenceLevel（uint32）
            if (Integer.compareUnsigned(referenceLevel, snapshot.getLevel()) < 0) {
                referenceLevel = snapshot.getLevel();
            }
        }
        List<Integer> monsterIds = ctx.data().dungeonMonsterIds(request.getBattleConfigId());
        if (monsterIds.isEmpty()) {
            monsterIds = new ArrayList<>(request.getPlayersCount());
            for (int i = 0; i < request.getPlayersCount(); i++) {
                monsterIds.add(0);
            }
        }
        int monsterIndex = 0;
        for (int monsterTableId : monsterIds) {
            appendMonsterActor(monsterTableId, monsterIndex, referenceLevel);
            monsterIndex++;
        }
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

    /**
     * 零副作用的提交预检（{@code ValidateAction}，{@code engine.cpp:333-346}；规格 §2.2）：已结束 → 1005；单位不存在或不是玩家 → 1005；
     * 已死或已逃 → 1009；其余返回校验链结果。与 {@link #submitAction} 的接受条件逐条同源：{@code == 1000} 等价于这次提交会被收下。
     */
    public int validateAction(long actorId, BattleAction action) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return ActionChecks.INVALID_PARAMETER;
        }
        BattleUnit actor = ctx.findActor(actorId);
        if (actor == null || !actor.isPlayer()) {
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
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            return false;
        }
        BattleUnit actor = ctx.findActor(actorId);
        if (actor != null && actor.isPlayer() && actor.isActive()
                && checks.checkActionPrerequisites(actor, action) == ActionChecks.SUCCESS) {
            pendingActions.put(actorId, action);
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
        BattleUnit actor = ctx.findActor(actorId);
        if (actor == null || !actor.isPlayer()) {
            return ActionChecks.INVALID_PARAMETER;
        }
        if (actor.isDead() || actor.fled()) {
            return ActionChecks.ENTITY_INVALID;
        }
        actor.setAuto(enabled);
        return ActionChecks.SUCCESS;
    }

    /**
     * 每个存活、未逃跑、未挂机的玩家都已有本回合行动（{@code AllPlayersReady}，{@code engine.cpp:366-380}；规格 §2.4）。
     * 没有这样的玩家时为真（空真）。不看胜负。
     */
    public boolean allPlayersReady() {
        for (BattleUnit unit : ctx.units()) {
            if (!unit.isPlayer() || !unit.isActive()) {
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

        fillDefaultActions();

        ctx.events().startRound(result);
        List<Long> order = buildTurnOrder();
        lastActionOrder = order;

        for (long actorId : order) {
            BattleUnit actor = ctx.findActor(actorId);
            if (actor == null || !actor.isActive()) {
                continue;
            }
            BattleAction action = pendingActions.get(actorId);
            if (action == null) {
                continue;
            }
            // 每个行动一组：该行动产生的全部事件（含降级普攻 / 死亡 / buff）共用 group_id；即使最终没有事件也占组号
            ctx.events().beginGroup();
            executeAction(actor, action);
        }

        buffs.tickAtRoundEnd();
        decayCooldowns();
        // 防御只覆盖本回合（含刚结算的回合末周期伤害），此刻统一摘除（engine.cpp:652-655）
        for (BattleUnit unit : ctx.units()) {
            unit.setDefending(false);
        }
        updateOutcome();
        rollDrops();

        pendingActions.clear();
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            ctx.advanceRound();
        }
        ctx.events().endRound();
        return result.setState(buildStateSnapshot()).build();
    }

    /**
     * 按单位插入序，给每个存活、未逃、还没有行动的单位补一条「普攻，目标 0」（{@code FillDefaultActions}，{@code engine.cpp:673-687}）：
     * 覆盖未提交的玩家、挂机玩家、全部怪物与宝宝，也包括被眩晕的单位（执行时被跳过）。不耗随机数。
     */
    private void fillDefaultActions() {
        for (BattleUnit unit : ctx.units()) {
            if (!unit.isActive()) {
                continue;
            }
            pendingActions.putIfAbsent(unit.actorId(), DEFAULT_ACTION);
        }
    }

    /** 回合开始时存活未逃的单位，按 {@link #TURN_ORDER} 排序（{@code BuildTurnOrder}，{@code engine.cpp:689-711}）。 */
    private List<Long> buildTurnOrder() {
        List<BattleUnit> ordered = new ArrayList<>(ctx.units().size());
        for (BattleUnit unit : ctx.units()) {
            if (unit.isActive()) {
                ordered.add(unit);
            }
        }
        ordered.sort(TURN_ORDER);
        List<Long> order = new ArrayList<>(ordered.size());
        for (BattleUnit unit : ordered) {
            order.add(unit.actorId());
        }
        return List.copyOf(order);
    }

    /**
     * 执行一个行动（{@code ExecuteAction}，{@code engine.cpp:713-739}）：身上有眩晕或冰冻 buff 时行动作废、不发事件（组号已占）；
     * 否则按 action_type 的 int 值分派，未知类型什么也不做。
     */
    private void executeAction(BattleUnit actor, BattleAction action) {
        if (ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_STUN)
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_FREEZE)) {
            return;
        }
        switch (action.getActionTypeValue()) {
            case eBattleActionType.BATTLE_ACTION_ATTACK_VALUE -> executeAttack(actor, action.getTargetId());
            case eBattleActionType.BATTLE_ACTION_SKILL_VALUE -> executeSkill(actor, action);
            case eBattleActionType.BATTLE_ACTION_DEFEND_VALUE -> executeDefend(actor);
            case eBattleActionType.BATTLE_ACTION_ITEM_VALUE -> ledger.executeItem(actor, action, checks);
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

        if (!ctx.rollHit(actor, target)) {
            ctx.events().append(eBattleEventType.BATTLE_EVENT_MISS, actor.actorId(), targetId)
                    .setValue(0)
                    .setTargetHealthAfter(target.health())
                    .setTargetManaAfter(target.mana());
            return;
        }

        BattleContext.FinalDamage finalDamage = ctx.calculateFinalDamage(actor, target,
                BattleConstants.BASIC_ATTACK_BASE_DAMAGE, actor.physicalAttack(), 1.0);
        long dealt = target.applyDamage(finalDamage.value());

        ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, actor.actorId(), targetId)
                .setValue(dealt)
                .setIsCritical(finalDamage.critical())
                .setTargetHealthAfter(target.health())
                .setTargetManaAfter(target.mana());

        if (target.health() == 0) {
            ctx.handleDeath(target, actor.actorId());
        }
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

        double baseDamage = ctx.data().skillDamage(action.getSkillTableId(), (double) Integer.toUnsignedLong(actor.level()));

        for (int hitIndex = 0; hitIndex < targetIds.length; hitIndex++) {
            BattleUnit target = ctx.findActor(targetIds[hitIndex]);
            if (target == null) {
                continue;
            }
            ctx.events().setHitIndex(hitIndex);
            applySkillToTarget(actor, action, row, baseDamage, target);
        }
        ctx.events().setHitIndex(0);
    }

    /**
     * 技能对单个目标落地（{@code ApplySkillToTarget}，{@code engine.cpp:858-901}）：命中恒为真（不耗随机数）；基础伤害 &gt; 0 时
     * 按 damage_type 选物攻 / 法攻、过伤害公式（暴击掷骰 R4 逐目标一次）、落血、出 DAMAGE（带 skill_table_id）、气血为 0 结算死亡；
     * 基础伤害为 0、负数或 NaN 时整段跳过（没有 DAMAGE，也不掷暴击）。目标没死才按 effect[] 表序逐个挂 buff（施法者为出手者，深度 0）。
     */
    private void applySkillToTarget(BattleUnit actor, BattleAction action, SkillTable row, double baseDamage,
                                    BattleUnit target) {
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
                    actor.physicalAttack(), actor.magicAttack());
            BattleContext.FinalDamage finalDamage = ctx.calculateFinalDamage(actor, target, baseDamage, attack,
                    row.getAttackMultiplier());
            long dealt = target.applyDamage(finalDamage.value());

            ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, actor.actorId(), target.actorId())
                    .setSkillTableId(action.getSkillTableId())
                    .setValue(dealt)
                    .setIsCritical(finalDamage.critical())
                    .setTargetHealthAfter(target.health())
                    .setTargetManaAfter(target.mana());

            if (target.health() == 0) {
                ctx.handleDeath(target, actor.actorId());
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
     * （uint64）；MANA 事件 source = target = 施法者，带 skill_table_id，value = 实耗，hp_after = 施法者气血，mana_after = after。
     */
    private void consumeSkillMana(BattleUnit actor, SkillTable row, int skillTableId) {
        long manaCost = BattleRules.skillManaCost(row);
        if (manaCost == 0) {
            return;
        }
        long manaBefore = actor.mana();
        long manaAfter = Long.compareUnsigned(manaBefore, manaCost) > 0 ? manaBefore - manaCost : 0;
        actor.setMana(manaAfter);

        ctx.events().append(eBattleEventType.BATTLE_EVENT_MANA, actor.actorId(), actor.actorId())
                .setSkillTableId(skillTableId)
                .setValue(manaBefore - manaAfter)
                .setTargetHealthAfter(actor.health())
                .setTargetManaAfter(manaAfter);
    }

    /**
     * 防御（{@code ExecuteDefend}，{@code engine.cpp:942-945}；规格 §3.8）：<strong>执行这一刻</strong>才置防御位，一直保持到回合末 tick 之后；
     * 速度更快的敌人在此之前打出的伤害不减半，回合末毒 / 灼烧会减半。DEFEND 事件 source = target = 自己。
     */
    private void executeDefend(BattleUnit actor) {
        actor.setDefending(true);
        ctx.events().append(eBattleEventType.BATTLE_EVENT_DEFEND, actor.actorId(), actor.actorId());
    }

    /**
     * 逃跑（{@code ExecuteFlee}，{@code engine.cpp:1014-1031}；规格 §3.9）：只在 PVE 时按速度差算成功率并掷一次 Rand01（R5），
     * 严格小于即成功；FLEE 事件 source = target = 自己，带 success；成功则置逃跑位（保留 buff 与冷却，之后不再 tick、不再被选为目标）。
     * PVP 下提交时就被拒、默认行动是普攻，所以执行期的非 PVE 分支不可达。
     */
    private void executeFlee(BattleUnit actor) {
        boolean success = false;
        if (ctx.isPve()) {
            double chance = BattleRules.fleeChance(actor.speed(), ctx.maxAliveEnemySpeed(actor));
            success = ctx.rng().rand01() < chance;
        }
        ctx.events().append(eBattleEventType.BATTLE_EVENT_FLEE, actor.actorId(), actor.actorId())
                .setSuccess(success);
        if (success) {
            actor.markFled();
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
        boolean sideAWiped = ctx.sideWiped(0);
        boolean sideBWiped = ctx.sideWiped(1);
        if (sideAWiped && sideBWiped) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        } else if (sideAWiped) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        } else if (sideBWiped) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
        } else if (Integer.compareUnsigned(ctx.roundIndex(), maxRounds) >= 0) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
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
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN || ctx.defeatedMonsters().isEmpty()) {
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
                .setFled(actor.fled());

        for (BattleUnit other : ctx.units()) {
            if (!other.isPet() || other.ownerPlayerId() != playerId) {
                continue;
            }
            settlement.addPetsBuilder()
                    .setPetId(other.petId())
                    .setHealth(other.health())
                    .setMana(other.mana())
                    .setIsDead(other.isDead());
        }

        if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && actor.teamIndex() == 0
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
        return settlement.build();
    }

    /** 已完成回合数：进行中为 roundIndex − 1，已结束为 roundIndex（{@code CompletedRounds}，{@code engine.cpp:1767-1770}）。 */
    private int completedRounds() {
        return outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING ? ctx.roundIndex() - 1 : ctx.roundIndex();
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
                .setActionDeadlineMs(0);
        for (BattleUnit unit : ctx.units()) {
            state.addActors(unit.snapshot());
        }
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            for (BattleUnit unit : ctx.units()) {
                if (!unit.isPlayer() || !unit.isActive()) {
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
        return state.build();
    }

    /** 该玩家剩余的战斗道具（规格 §6.3）：余量非 0 的条目按 item_table_id 升序（稳定排序，D6）；不在本局的玩家返回空列表。 */
    public List<BattleItemEntry> selfItems(long playerId) {
        return ledger.selfItems(playerId);
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
}
