package com.game.battle.engine;

import static com.game.battle.engine.EngineTestSupport.deterministicBytes;
import static com.game.battle.engine.EngineTestSupport.hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePetSettlementData;
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
import com.game.table.BuffTable;
import com.game.table.CommonErrorTip;
import com.game.table.MonsterTable;
import com.game.table.Monsterdrop;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import com.game.table.Skillcost_resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 不变量测试（规格 §13.7）：2000 个随机局，JUnit 参数化（不引入 jqwik，规格 §10.1）。
 *
 * <p><strong>场景生成</strong>只在测试侧用 {@link SplittableRandom}（引擎内照样禁用）：随机的技能 / buff 表（群攻、瞬时、无限、叠层、驱散、免疫、
 * 子 buff、target_sub_buff、毒、灼烧、回血、眩晕、沉默、冰冻、缺行引用）、道具、怪物与掉落、副本时限；PVE 单人 / 组队与 PVP 1V1 / 5V5 / 切磋；
 * 随机属性、宝宝、快照 buff 与道具副本。每回合每个玩家的决策只由（场景种子, 回合号, 玩家下标）决定，与引擎状态无关，所以同一场景
 * 跑几遍得到的提交序列完全相同（不合法的提交照样交给引擎，正好覆盖校验链）。
 *
 * <p>每个场景跑三遍：主跑（带全部检查）、原样重跑、把「开挂机」换成「关挂机 + 手动提交 ATTACK 0」的变体。不变量（编号同规格）：
 * <ol>
 *   <li>同一输入跑两遍：每回合事件字节、确定性序列化后的快照字节、出手序、结算字节、剩余道具都相同；</li>
 *   <li>挂机与手动提交 ATTACK 0 等价：事件流、出手序、结算、剩余道具逐字节相同；</li>
 *   <li>气血 ≤ 上限、法力 ≤ 上限（无符号；生成器保证快照里当前值不超过上限，超过上限的回绕见 {@link TurnBattleBuffTest}）；</li>
 *   <li>已阵亡的单位没有 buff、不在防御中且气血为 0；快照里没有单位处于防御中；已阵亡或已逃的单位不再作为任何行动组的出手者出现；</li>
 *   <li>每回合 group_id ≥ 1 且单调不减；非技能组的 hit_index 为 0，技能组的 hit_index 小于目标数上界；
 *       同一行动组只有一个出手者，出手者的先后是出手序的子序列；</li>
 *   <li>结束后再结算不产生事件、不耗随机数、快照不变，提交 / 校验 / 挂机分别为 false / 1005 / 1005；total_rounds 等于实际结算过的回合数；</li>
 *   <li>击杀簿 = 由事件流推出的「满足记账条件的怪物 DEATH」（凶手取紧挨着 DEATH 的那条 DAMAGE / BUFF_TICK 的 source）；</li>
 *   <li>只有 A 方胜才有掉落、只发给合格玩家；每种物品的掉落数介于「掉率 ≥ 10000 的槽」与「全部非空槽」的 drop_count 之和之间；</li>
 *   <li>结算重复读取字节相同，字段与终局快照一致（含宝宝终值、经验金币）；</li>
 *   <li>提交收集完后，待行动名单 = 存活、未逃、非挂机且没有任何一次 validateAction == 1000 的玩家；submitAction 的返回值恒等于 allPlayersReady()；</li>
 *   <li>{@code rngDrawsForTest()} 等于按 §8.4 的账从事件流逐项累计的次数：R1 普攻目标 ≠ 行动目标、R2 单体技能目标 ≠ 行动目标、
 *       R4 出手者暴击率非 0 的每条 DAMAGE、R5 PVE 的每条 FLEE、R6 判出 A 方胜那一回合的「合格人数 × Σ 击杀簿里每只怪的非空掉落槽数」；</li>
 *   <li>（Java 追加）出手序 = 回合开始时存活未逃的单位按速度降序、actor_id 升序（都按无符号）排列；胜负与各方存活情况、回合上限一致。</li>
 * </ol>
 * 道具账另核对「消耗 + 剩余 = 快照初始数量」、ITEM 事件数 = 消耗数、PVP 每人消耗 ≤ 5。
 */
class TurnBattleInvariantTest {

    private static final int SCENARIOS = 2000;

    private static final int SUCCESS = CommonErrorTip.common_error.kSuccess_VALUE;
    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int ENTITY_INVALID = CommonErrorTip.common_error.kThisEntityIsInvalid_VALUE;

    private static final int BUFF_BASE = 1001;
    private static final int BUFF_COUNT = 12;
    private static final int MISSING_BUFF = 1999;
    private static final int SKILL_BASE = 2001;
    private static final int SKILL_COUNT = 8;
    private static final int MISSING_SKILL = 2999;
    private static final int ITEM_BASE = 3001;
    private static final int ITEM_COUNT = 4;
    private static final int MISSING_ITEM = 3999;
    private static final int MONSTER_BASE = 4001;
    private static final int MONSTER_COUNT = 6;
    private static final int MISSING_MONSTER = 4999;
    private static final int DUNGEON = TestTables.DUNGEON_CONFIG;
    private static final long ABSENT_PLAYER = Long.MAX_VALUE;

    private static final int[] BUFF_TYPES = {
            0, 13, 36,
            BattleConstants.BUFF_TYPE_STUN, BattleConstants.BUFF_TYPE_SILENCE, BattleConstants.BUFF_TYPE_DISPEL,
            BattleConstants.BUFF_TYPE_HEALTH_REGENERATION, BattleConstants.BUFF_TYPE_HEALTH_REGENERATION_BASED_ON_LOST_HEALTH,
            BattleConstants.BUFF_TYPE_POISON, BattleConstants.BUFF_TYPE_POISON, BattleConstants.BUFF_TYPE_BURN,
            BattleConstants.BUFF_TYPE_FREEZE, BattleConstants.BUFF_TYPE_INVINCIBILITY, BattleConstants.BUFF_TYPE_MANA_REGENERATION,
    };
    private static final String[] TAGS = {"a", "b", "c"};
    private static final List<List<Integer>> TARGETING_SHAPES = List.of(
            List.of(), List.of(0), List.of(1), List.of(1), List.of(2), List.of(1, 2), List.of(2, 2), List.of(3), List.of(0, 1));
    private static final List<List<Integer>> SKILL_TYPE_SHAPES = List.of(
            List.of(1), List.of(1), List.of(1, 1), List.of(4), List.of(5), List.of(0), List.of(2), List.of(3), List.of(6), List.of(1, 4));
    private static final int[] MATCH_MODES = {
            BattleConstants.MATCH_MODE_PVE_SOLO, BattleConstants.MATCH_MODE_PVE_TEAM, 3, 1, 6,
    };
    private static final Set<eBattleEventType> ACTION_EVENTS = EnumSet.of(
            eBattleEventType.BATTLE_EVENT_ATTACK, eBattleEventType.BATTLE_EVENT_SKILL, eBattleEventType.BATTLE_EVENT_DEFEND,
            eBattleEventType.BATTLE_EVENT_ITEM, eBattleEventType.BATTLE_EVENT_FLEE, eBattleEventType.BATTLE_EVENT_MANA);
    private static final BattleAction DEFAULT_ATTACK = BattleAction.newBuilder()
            .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(0).build();

    static LongStream 场景种子() {
        return LongStream.rangeClosed(1, SCENARIOS);
    }

    @ParameterizedTest(name = "场景 {0}")
    @MethodSource("场景种子")
    void 随机局满足全部不变量(long seed) {
        Scenario scenario = Scenario.generate(seed);
        Run primary = run(scenario, false, true);

        // 不变量 1：同一输入跑两遍逐字节相同
        Run replay = run(scenario, false, false);
        assertThat(replay.events).as("事件字节").isEqualTo(primary.events);
        assertThat(replay.snapshots).as("快照字节").isEqualTo(primary.snapshots);
        assertThat(replay.orders).as("出手序").isEqualTo(primary.orders);
        assertThat(replay.settlements).as("结算字节").isEqualTo(primary.settlements);
        assertThat(replay.selfItems).as("剩余道具").isEqualTo(primary.selfItems);

        // 不变量 2：挂机 ≡ 手动提交 ATTACK 0
        Run manual = run(scenario, true, false);
        assertThat(manual.events).as("挂机换手动后的事件字节").isEqualTo(primary.events);
        assertThat(manual.orders).as("挂机换手动后的出手序").isEqualTo(primary.orders);
        assertThat(manual.settlements).as("挂机换手动后的结算字节").isEqualTo(primary.settlements);
        assertThat(manual.selfItems).as("挂机换手动后的剩余道具").isEqualTo(primary.selfItems);
    }

    // ---------------------------------------------------------------------------------------------
    // 场景生成
    // ---------------------------------------------------------------------------------------------

    private record Scenario(long seed, MemoryBattleData data, CreateBattleRequest request, boolean pve, boolean hasPermission) {

        static Scenario generate(long seed) {
            SplittableRandom r = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + 17);
            MemoryBattleData data = new MemoryBattleData();
            generateBuffs(r, data);
            generateSkills(r, data);
            boolean hasPermission = r.nextInt(10) != 0;
            if (hasPermission) {
                SkillPermissionTable.Builder permission = data.addSkillPermission(BattleConstants.COMBAT_STATE_SILENCE);
                for (int column = 0; column < 6; column++) {
                    permission.addSkillType(pick(r, 1000, 1000, 7005, 0));
                }
            }
            generateItems(r, data);
            generateMonsters(r, data);
            generateDungeon(r, data);

            int matchMode = pick(r, MATCH_MODES);
            boolean pve = matchMode == BattleConstants.MATCH_MODE_PVE_SOLO || matchMode == BattleConstants.MATCH_MODE_PVE_TEAM;
            int teamA;
            int teamB;
            if (matchMode == BattleConstants.MATCH_MODE_PVE_SOLO) {
                teamA = 1;
                teamB = 0;
            } else if (matchMode == BattleConstants.MATCH_MODE_PVE_TEAM) {
                teamA = 1 + r.nextInt(BattleConstants.MAX_BATTLE_TEAM_SIZE);
                teamB = 0;
            } else if (matchMode == 1) {
                teamA = 1 + r.nextInt(BattleConstants.MAX_BATTLE_TEAM_SIZE);
                teamB = 1 + r.nextInt(BattleConstants.MAX_BATTLE_TEAM_SIZE);
            } else {
                teamA = 1;
                teamB = 1;
            }
            List<Integer> teams = new ArrayList<>();
            for (int i = 0; i < teamA; i++) {
                teams.add(0);
            }
            for (int i = 0; i < teamB; i++) {
                teams.add(1);
            }
            for (int i = teams.size() - 1; i > 0; i--) { // 打乱快照里的队伍顺序
                int j = r.nextInt(i + 1);
                teams.set(i, teams.set(j, teams.get(i)));
            }

            CreateBattleRequest.Builder request = CreateBattleRequest.newBuilder()
                    .setBattleId(seed)
                    .setBattleConfigId(DUNGEON)
                    .setMatchMode(matchMode)
                    .setSeed(r.nextLong());
            List<Long> playerIds = new ArrayList<>();
            long nextPlayerId = 1 + r.nextInt(1_000_000);
            long nextPetId = 700_000 + r.nextInt(1000);
            for (int team : teams) {
                long playerId = nextPlayerId;
                nextPlayerId += 1 + r.nextInt(1000);
                playerIds.add(playerId);
                BattlePlayerSnapshot.Builder player = request.addPlayersBuilder()
                        .setPlayerId(playerId)
                        .setPlayerName("随机玩家" + playerId)
                        .setLevel(pick(r, 0, 1, 10, 20, 85, 100))
                        .setTeamIndex(team)
                        .setAppearanceId("look-" + r.nextInt(5))
                        .setClassId(r.nextInt(4))
                        .setGender(r.nextInt(3));
                long health = r.nextInt(20) == 0 ? 0 : 1 + r.nextInt(400);
                long mana = r.nextInt(100);
                player.setMaxHealth(r.nextInt(4) == 0 ? 0 : health + r.nextInt(200));
                player.setMaxMana(r.nextInt(4) == 0 ? 0 : mana + r.nextInt(100));
                player.getBaseAttributesBuilder()
                        .setHealth(health)
                        .setMana(mana)
                        .setStrength(r.nextInt(31))
                        .setArmor(r.nextInt(101))
                        .setResistance(r.nextInt(21))
                        .setCritchance(r.nextBoolean() ? 0 : r.nextInt(61))
                        .setSpeed(pick(r, 0, 12, 60, 60, 120, 240, 600, 37))
                        .setStamina(r.nextInt(10));
                player.setPhysicalAttack(r.nextInt(51)).setMagicAttack(r.nextInt(51)).setDefense(r.nextInt(1601));
                for (int i = 0; i < SKILL_COUNT; i++) {
                    if (r.nextInt(10) < 6) {
                        player.addSkillTableIds(SKILL_BASE + i);
                    }
                }
                if (r.nextInt(5) == 0) {
                    player.addSkillTableIds(MISSING_SKILL);
                }
                int items = r.nextInt(4);
                for (int i = 0; i < items; i++) {
                    int itemId = r.nextInt(8) == 0 ? MISSING_ITEM : ITEM_BASE + r.nextInt(ITEM_COUNT);
                    player.addItems(BattleItemEntry.newBuilder().setItemTableId(itemId).setCount(r.nextInt(4)));
                }
                int buffs = r.nextInt(3);
                for (int i = 0; i < buffs; i++) {
                    long caster = switch (r.nextInt(5)) {
                        case 0 -> 0;
                        case 1 -> playerId;
                        case 2 -> playerIds.get(r.nextInt(playerIds.size()));
                        case 3 -> BattleConstants.MONSTER_ACTOR_ID_BASE;
                        default -> 424242;
                    };
                    player.addBuffs(BattleBuffEntry.newBuilder()
                            .setBuffId(pick(r, 0, 1, 2, 5, 100 + r.nextInt(100)))
                            .setBuffTableId(pickBuffRef(r))
                            .setLayer(r.nextInt(4))
                            .setRemainRounds(r.nextInt(6))
                            .setCasterId(caster));
                }
                if (r.nextInt(4) == 0) {
                    long petHealth = 1 + r.nextInt(300);
                    BattlePetSnapshot.Builder pet = player.addPetsBuilder()
                            .setPetId(nextPetId)
                            .setOwnerPlayerId(r.nextBoolean() ? playerId : 0)
                            .setPetName("随机宝宝")
                            .setPetTableId(1)
                            .setLevel(pick(r, 1, 10, 30))
                            .setMaxHealth(r.nextInt(3) == 0 ? 0 : petHealth + r.nextInt(100))
                            .setPhysicalAttack(r.nextInt(40))
                            .setMagicAttack(r.nextInt(40))
                            .setDefense(r.nextInt(500));
                    nextPetId += 1 + r.nextInt(10);
                    pet.getBaseAttributesBuilder()
                            .setHealth(petHealth)
                            .setStrength(r.nextInt(31))
                            .setArmor(r.nextInt(60))
                            .setCritchance(r.nextBoolean() ? 0 : r.nextInt(40))
                            .setSpeed(pick(r, 0, 60, 120, 360, 59));
                    if (r.nextBoolean()) {
                        pet.addSkillTableIds(SKILL_BASE + r.nextInt(SKILL_COUNT));
                    }
                }
            }
            return new Scenario(seed, data, request.build(), pve, hasPermission);
        }

        private static void generateBuffs(SplittableRandom r, MemoryBattleData data) {
            for (int i = 0; i < BUFF_COUNT; i++) {
                int id = BUFF_BASE + i;
                BuffTable.Builder buff = data.addBuff(id);
                buff.setBuffType(pick(r, BUFF_TYPES));
                if (r.nextInt(100) < 15) {
                    buff.setInfiniteDuration(1);
                }
                buff.setDuration(pickDouble(r, 0.0, 2.0, 6.0, 12.0, 18.0, 30.0));
                buff.setInterval(pickDouble(r, 0.0, 0.0, 6.0, 12.0));
                buff.setIntervalCount(pick(r, 0, 0, 1, 2));
                if (r.nextInt(5) != 0) {
                    buff.addIntervalEffect(1 + r.nextInt(40));
                }
                buff.setMaxLayer(pick(r, 0, 1, 2, 3, 5));
                buff.setNoCaster(r.nextInt(4) == 0 ? 1 : 0);
                for (String tag : TAGS) {
                    if (r.nextInt(3) == 0) {
                        buff.putTag(tag, true);
                    }
                }
                if (r.nextInt(6) == 0) {
                    buff.putImmuneTag(TAGS[r.nextInt(TAGS.length)], true);
                }
                if (r.nextInt(4) == 0) {
                    buff.putDispelTag(TAGS[r.nextInt(TAGS.length)], true);
                }
                // 每个 buff 至多一条子 buff、一条 target_sub_buff：深度上限 9 层时最坏也只有 2^10 次挂载
                if (r.nextInt(5) == 0) {
                    buff.addSubBuff(pickBuffRef(r));
                }
                if (r.nextInt(5) == 0) {
                    buff.addTargetSubBuff(pickBuffRef(r));
                }
                if (r.nextInt(4) != 0) {
                    data.setBuffRegen(id, r.nextInt(60));
                }
            }
        }

        private static void generateSkills(SplittableRandom r, MemoryBattleData data) {
            data.setCooldownMs(1, 12000);
            data.setCooldownMs(2, 6000);
            data.setCooldownMs(3, 500);
            for (int i = 0; i < SKILL_COUNT; i++) {
                int id = SKILL_BASE + i;
                SkillTable.Builder skill = data.addSkill(id);
                skill.addAllTargetingMode(TARGETING_SHAPES.get(r.nextInt(TARGETING_SHAPES.size())));
                skill.addAllSkillType(SKILL_TYPE_SHAPES.get(r.nextInt(SKILL_TYPE_SHAPES.size())));
                skill.setCooldownId(r.nextInt(4));
                skill.setDamageType(r.nextInt(3));
                skill.setAttackMultiplier(pickDouble(r, 0.0, 1.0, 2.0, 0.5));
                data.setSkillDamage(id, pickDouble(r, 0.0, 0.0, 20.0, 50.0, 100.0, 300.0, -5.0, Double.NaN));
                int effects = r.nextInt(4);
                for (int e = 0; e < effects; e++) {
                    skill.addEffect(pickBuffRef(r));
                }
                if (r.nextInt(3) == 0) {
                    skill.addCostResource(Skillcost_resource.newBuilder()
                            .setCostResourceId(BattleConstants.SKILL_COST_RESOURCE_MANA).setCostResourceCost(pick(r, 5, 10, 30)));
                }
                if (r.nextInt(5) == 0) {
                    skill.addCostResource(Skillcost_resource.newBuilder().setCostResourceId(2).setCostResourceCost(20));
                }
            }
        }

        private static void generateItems(SplittableRandom r, MemoryBattleData data) {
            data.addItem(ITEM_BASE).setBattleUsable(1).setBattleHealHp(50 + r.nextInt(100));
            data.addItem(ITEM_BASE + 1).setBattleUsable(1).setBattleHealMp(20 + r.nextInt(40));
            data.addItem(ITEM_BASE + 2).setBattleUsable(1).setBattleHealHp(1 + r.nextInt(80)).setBattleHealMp(1 + r.nextInt(40));
            data.addItem(ITEM_BASE + 3).setBattleUsable(r.nextInt(2)); // 两列效果都是 0
        }

        private static void generateMonsters(SplittableRandom r, MemoryBattleData data) {
            for (int i = 0; i < MONSTER_COUNT; i++) {
                MonsterTable.Builder monster = data.addMonster(MONSTER_BASE + i);
                monster.setHealth(r.nextInt(5) == 0 ? 0 : 20 + r.nextInt(280));
                monster.setStrength(r.nextInt(31));
                monster.setArmor(r.nextInt(101));
                monster.setResistance(r.nextInt(31));
                monster.setCritchance(r.nextBoolean() ? 0 : r.nextInt(31));
                monster.setSpeed(pick(r, 0, 12, 60, 120, 200, 360));
                monster.setExpReward(r.nextInt(51));
                monster.setGoldReward(r.nextInt(51));
                int slots = r.nextInt(3);
                for (int s = 0; s < slots; s++) {
                    monster.addDrop(Monsterdrop.newBuilder()
                            .setDropItem(pick(r, ITEM_BASE, ITEM_BASE + 1, ITEM_BASE + 2, 0))
                            .setDropCount(r.nextInt(4))
                            .setDropRate(pick(r, 0, 3000, 5000, 10000, 12000)));
                }
            }
        }

        private static void generateDungeon(SplittableRandom r, MemoryBattleData data) {
            int kind = r.nextInt(3);
            if (kind == 1) {
                data.addDungeon(DUNGEON).setTimeLimit(0);
            } else if (kind == 2) {
                data.addDungeon(DUNGEON).setTimeLimit(pick(r, 12, 30, 60, 180));
            }
            if (r.nextInt(4) != 0) {
                int count = 1 + r.nextInt(4);
                List<Integer> monsters = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    monsters.add(r.nextInt(8) == 0 ? (r.nextBoolean() ? 0 : MISSING_MONSTER) : MONSTER_BASE + r.nextInt(MONSTER_COUNT));
                }
                data.setDungeonMonsters(DUNGEON, monsters); // 测试数据源不过滤 0：0 号是兜底怪
            }
        }

        int maxRounds() {
            return data.dungeon(DUNGEON)
                    .filter(row -> row.getTimeLimit() != 0)
                    .map(row -> BattleRules.roundsFromMillis(Integer.toUnsignedLong(row.getTimeLimit()) * 1000L))
                    .orElse(BattleConstants.DEFAULT_MAX_ROUNDS);
        }
    }

    private static int pickBuffRef(SplittableRandom r) {
        if (r.nextInt(10) == 0) {
            return r.nextBoolean() ? 0 : MISSING_BUFF;
        }
        return BUFF_BASE + r.nextInt(BUFF_COUNT);
    }

    private static int pick(SplittableRandom r, int... values) {
        return values[r.nextInt(values.length)];
    }

    private static double pickDouble(SplittableRandom r, double... values) {
        return values[r.nextInt(values.length)];
    }

    // ---------------------------------------------------------------------------------------------
    // 每回合的决策：只由（场景种子, 回合号, 玩家下标）决定
    // ---------------------------------------------------------------------------------------------

    private record Decision(boolean auto, BattleAction first, BattleAction second) {
    }

    private static Decision decide(Scenario scenario, List<Long> candidates, long playerId, int round, int playerIndex) {
        SplittableRandom r = new SplittableRandom(scenario.seed() * 1_000_003L + round * 7_919L + playerIndex * 104_729L);
        if (r.nextInt(100) < 15) {
            return new Decision(true, null, null);
        }
        BattleAction first = randomAction(r, candidates, playerId);
        BattleAction second = r.nextInt(10) == 0 ? randomAction(r, candidates, playerId) : null;
        return new Decision(false, first, second);
    }

    private static BattleAction randomAction(SplittableRandom r, List<Long> candidates, long playerId) {
        int roll = r.nextInt(100);
        int type;
        if (roll < 30) {
            type = eBattleActionType.BATTLE_ACTION_ATTACK_VALUE;
        } else if (roll < 65) {
            type = eBattleActionType.BATTLE_ACTION_SKILL_VALUE;
        } else if (roll < 75) {
            type = eBattleActionType.BATTLE_ACTION_DEFEND_VALUE;
        } else if (roll < 87) {
            type = eBattleActionType.BATTLE_ACTION_ITEM_VALUE;
        } else if (roll < 95) {
            type = eBattleActionType.BATTLE_ACTION_FLEE_VALUE;
        } else if (roll < 98) {
            type = eBattleActionType.BATTLE_ACTION_NONE_VALUE;
        } else {
            type = 99; // 未知行动类型
        }
        long target = r.nextInt(6) == 0 ? playerId : candidates.get(r.nextInt(candidates.size()));
        int skill = r.nextInt(10) == 0 ? (r.nextBoolean() ? 0 : MISSING_SKILL) : SKILL_BASE + r.nextInt(SKILL_COUNT);
        int item = r.nextInt(10) == 0 ? MISSING_ITEM : ITEM_BASE + r.nextInt(ITEM_COUNT);
        return BattleAction.newBuilder()
                .setActionTypeValue(type)
                .setTargetId(target)
                .setSkillTableId(skill)
                .setItemTableId(item)
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // 跑一局
    // ---------------------------------------------------------------------------------------------

    /** 一局的可比较输出（字节都转成 hex，失败信息可读）。 */
    private static final class Run {
        final List<List<String>> events = new ArrayList<>();
        final List<String> snapshots = new ArrayList<>();
        final List<List<Long>> orders = new ArrayList<>();
        final Map<Long, String> settlements = new LinkedHashMap<>();
        final Map<Long, List<BattleItemEntry>> selfItems = new LinkedHashMap<>();
    }

    /**
     * @param autoAsManual 把「开挂机」决策换成「关挂机 + 手动提交 ATTACK 0」（不变量 2 的变体）
     * @param check        是否做逐回合检查（只在主跑里做）
     */
    private static Run run(Scenario scenario, boolean autoAsManual, boolean check) {
        BattleStart start = TurnBattleEngine.start(scenario.request(), scenario.data());
        assertThat(start).as("生成器只产出合法的开局请求").isInstanceOf(BattleStart.Started.class);
        TurnBattleEngine engine = ((BattleStart.Started) start).engine();

        BattleStateS2C initial = engine.buildStateSnapshot();
        List<Long> candidates = new ArrayList<>();
        for (BattleActorState actor : initial.getActorsList()) {
            candidates.add(actor.getActorId());
        }
        candidates.add(0L);
        candidates.add(999_999L);
        List<Long> players = scenario.request().getPlayersList().stream().map(BattlePlayerSnapshot::getPlayerId).toList();
        Checker checker = check ? new Checker(scenario, engine, initial) : null;

        Run out = new Run();
        int resolved = 0;
        for (int round = 1; engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; round++) {
            assertThat(round).as("回合上限保证收场").isLessThanOrEqualTo(40);
            BattleStateS2C before = engine.buildStateSnapshot();
            if (checker != null) {
                checker.beginRound(before);
            }
            Map<Long, BattleAction> accepted = new HashMap<>();
            for (int i = 0; i < players.size(); i++) {
                long playerId = players.get(i);
                Decision decision = decide(scenario, candidates, playerId, round, i);
                if (decision.auto()) {
                    if (autoAsManual) {
                        engine.setActorAuto(playerId, false);
                        submit(engine, playerId, DEFAULT_ATTACK, accepted, checker);
                    } else {
                        int code = engine.setActorAuto(playerId, true);
                        if (checker != null) {
                            checker.autoToggled(before, playerId, true, code);
                        }
                    }
                } else {
                    int code = engine.setActorAuto(playerId, false);
                    if (checker != null) {
                        checker.autoToggled(before, playerId, false, code);
                    }
                    submit(engine, playerId, decision.first(), accepted, checker);
                    if (decision.second() != null) {
                        submit(engine, playerId, decision.second(), accepted, checker);
                    }
                }
            }
            if (checker != null) {
                checker.afterCollection(accepted);
            }
            long drawsBefore = engine.rngDrawsForTest();
            TurnResultS2C result = engine.resolveCurrentRound();
            resolved++;
            List<String> eventHex = new ArrayList<>(result.getEventsCount());
            for (BattleEventItem event : result.getEventsList()) {
                eventHex.add(hex(event.toByteArray()));
            }
            out.events.add(eventHex);
            out.snapshots.add(hex(deterministicBytes(result.getState())));
            out.orders.add(List.copyOf(engine.lastActionOrder()));
            if (checker != null) {
                checker.afterRound(round, before, accepted, result, drawsBefore);
            }
        }
        if (checker != null) {
            checker.afterBattle(resolved);
        }
        for (long playerId : players) {
            out.settlements.put(playerId, hex(engine.buildSettlement(playerId).toByteArray()));
            out.selfItems.put(playerId, engine.selfItems(playerId));
        }
        return out;
    }

    private static void submit(TurnBattleEngine engine, long playerId, BattleAction action, Map<Long, BattleAction> accepted,
                               Checker checker) {
        int verdict = engine.validateAction(playerId, action);
        boolean ready = engine.submitAction(playerId, action);
        if (checker != null) {
            checker.submitted(playerId, verdict, ready);
        }
        if (verdict == SUCCESS) {
            accepted.put(playerId, action); // 最后一次合法提交生效
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 逐回合检查（只在主跑里做）
    // ---------------------------------------------------------------------------------------------

    private static final class Checker {
        private final Scenario scenario;
        private final TurnBattleEngine engine;
        private final Map<Long, BattleActorState> initialById = new LinkedHashMap<>();
        private final List<Long> initialOrder = new ArrayList<>();
        private final Map<Long, Boolean> autoState = new HashMap<>();
        private final List<Integer> creditedKills = new ArrayList<>();
        private final Set<Long> diedEver = new HashSet<>();
        private final Map<Long, Integer> itemEvents = new HashMap<>();
        private long expectedDraws;
        /** 本回合收集提交之前的快照：收集期间生死与逃跑状态不会变。 */
        private BattleStateS2C roundStart;

        Checker(Scenario scenario, TurnBattleEngine engine, BattleStateS2C initial) {
            this.scenario = scenario;
            this.engine = engine;
            for (BattleActorState actor : initial.getActorsList()) {
                initialById.put(actor.getActorId(), actor);
                initialOrder.add(actor.getActorId());
                autoState.put(actor.getActorId(), actor.getIsAuto());
            }
            assertThat(engine.rngDrawsForTest()).as("开局不耗随机数").isZero();
            checkState(initial);
        }

        private static boolean active(BattleActorState actor) {
            return !actor.getIsDead() && !actor.getFled();
        }

        private BattleActorState stateOf(BattleStateS2C state, long actorId) {
            BattleActorState actor = TestBattles.stateActor(state, actorId);
            assertThat(actor).as("快照里应有单位 %s", EngineTestSupport.actorName(actorId)).isNotNull();
            return actor;
        }

        void autoToggled(BattleStateS2C before, long playerId, boolean enabled, int code) {
            if (active(stateOf(before, playerId))) {
                assertThat(code).as("挂机开关").isEqualTo(SUCCESS);
                autoState.put(playerId, enabled);
            } else {
                assertThat(code).as("已死或已逃的玩家开关挂机").isEqualTo(ENTITY_INVALID);
            }
        }

        void beginRound(BattleStateS2C before) {
            roundStart = before;
        }

        void submitted(long playerId, int verdict, boolean ready) {
            assertThat(ready).as("submitAction 的返回值恒等于 allPlayersReady()").isEqualTo(engine.allPlayersReady());
            BattleActorState actor = stateOf(roundStart, playerId);
            if (!active(actor)) {
                assertThat(verdict).as("已死或已逃的玩家提交").isEqualTo(ENTITY_INVALID);
            }
        }

        /** 不变量 10：收集完提交后，待行动名单 = 存活、未逃、非挂机、且没有被收下行动的玩家（按插入序）。 */
        void afterCollection(Map<Long, BattleAction> accepted) {
            BattleStateS2C collected = engine.buildStateSnapshot();
            List<Long> expectedPending = new ArrayList<>();
            for (BattleActorState actor : collected.getActorsList()) {
                assertThat(actor.getIsAuto()).as("%s 的挂机标记", EngineTestSupport.actorName(actor.getActorId()))
                        .isEqualTo(autoState.get(actor.getActorId()));
                if (actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER && active(actor) && !actor.getIsAuto()
                        && !accepted.containsKey(actor.getActorId())) {
                    expectedPending.add(actor.getActorId());
                }
            }
            assertThat(collected.getPendingActorIdsList()).as("待行动名单").containsExactlyElementsOf(expectedPending);
            assertThat(engine.allPlayersReady()).isEqualTo(expectedPending.isEmpty());
        }

        void afterRound(int round, BattleStateS2C before, Map<Long, BattleAction> accepted, TurnResultS2C result, long drawsBefore) {
            assertThat(result.getBattleId()).isEqualTo(scenario.request().getBattleId());
            assertThat(result.getRoundIndex()).as("本次结算的回合号").isEqualTo(round);
            assertThat(before.getRoundIndex()).isEqualTo(round);
            assertThat(result.getActionOrderList()).as("action_order 由节点填").isEmpty();

            // 不变量 12：出手序
            List<Long> expectedOrder = before.getActorsList().stream()
                    .filter(Checker::active)
                    .sorted(Comparator.comparing((BattleActorState a) -> a.getAttributes().getSpeed(),
                                    (l, r) -> Long.compareUnsigned(r, l))
                            .thenComparing(BattleActorState::getActorId, Long::compareUnsigned))
                    .map(BattleActorState::getActorId)
                    .toList();
            List<Long> order = engine.lastActionOrder();
            assertThat(order).as("出手序").containsExactlyElementsOf(expectedOrder);

            Map<Long, BattleActorState> startById = new HashMap<>();
            Set<Long> inactive = new HashSet<>();
            for (BattleActorState actor : before.getActorsList()) {
                startById.put(actor.getActorId(), actor);
                if (!active(actor)) {
                    inactive.add(actor.getActorId());
                }
            }

            List<BattleEventItem> events = result.getEventsList();
            Map<Integer, List<BattleEventItem>> groups = new LinkedHashMap<>();
            List<Long> actingSources = new ArrayList<>();
            int previousGroup = 0;
            int lastActionGroup = -1;
            long draws = drawsBefore;
            for (int i = 0; i < events.size(); i++) {
                BattleEventItem event = events.get(i);
                String where = "第 " + round + " 回合第 " + i + " 条事件 " + EngineTestSupport.describe(event);
                int group = event.getGroupId();
                // 不变量 5：组号 ≥ 1、单调不减
                assertThat(group).as(where).isGreaterThanOrEqualTo(1).isGreaterThanOrEqualTo(previousGroup);
                previousGroup = group;
                groups.computeIfAbsent(group, g -> new ArrayList<>()).add(event);

                eBattleEventType type = event.getEventType();
                long source = event.getSourceId();
                if (ACTION_EVENTS.contains(type)) {
                    // 不变量 4：已阵亡或已逃的单位不再作为行动组的出手者
                    assertThat(inactive).as(where + "：出手者已阵亡或已逃").doesNotContain(source);
                    if (group != lastActionGroup) {
                        actingSources.add(source);
                        lastActionGroup = group;
                    } else {
                        assertThat(source).as(where + "：同一行动组只有一个出手者").isEqualTo(actingSources.get(actingSources.size() - 1));
                    }
                }
                BattleAction action = accepted.get(source);
                switch (type) {
                    case BATTLE_EVENT_ATTACK -> {
                        if (action != null) {
                            assertThat(action.getActionType()).as(where).isIn(
                                    eBattleActionType.BATTLE_ACTION_ATTACK, eBattleActionType.BATTLE_ACTION_SKILL);
                        }
                        long actionTarget = action == null ? 0 : action.getTargetId();
                        if (event.getTargetId() != actionTarget) {
                            draws++; // R1 / R3
                        }
                        BattleActorState target = initialById.get(event.getTargetId());
                        assertThat(target).as(where).isNotNull();
                        assertThat(inactive).as(where + "：普攻目标存活").doesNotContain(event.getTargetId());
                        assertThat(target.getTeamIndex()).as(where + "：普攻只打异队").isNotEqualTo(initialById.get(source).getTeamIndex());
                    }
                    case BATTLE_EVENT_SKILL -> {
                        assertThat(action).as(where).isNotNull();
                        assertThat(action.getActionType()).as(where).isEqualTo(eBattleActionType.BATTLE_ACTION_SKILL);
                        assertThat(event.getSkillTableId()).as(where).isEqualTo(action.getSkillTableId());
                        SkillTable row = scenario.data().skill(event.getSkillTableId()).orElseThrow();
                        if (!BattleRules.isAreaSkill(row) && event.getTargetId() != action.getTargetId()) {
                            draws++; // R2
                        }
                    }
                    case BATTLE_EVENT_DAMAGE -> {
                        if (initialById.get(source).getAttributes().getCritchance() != 0) {
                            draws++; // R4
                        }
                    }
                    case BATTLE_EVENT_FLEE -> {
                        assertThat(action).as(where).isNotNull();
                        assertThat(action.getActionType()).as(where).isEqualTo(eBattleActionType.BATTLE_ACTION_FLEE);
                        assertThat(scenario.pve()).as(where + "：PVP 不能逃跑").isTrue();
                        draws++; // R5
                        if (event.getSuccess()) {
                            inactive.add(source);
                        }
                    }
                    case BATTLE_EVENT_DEFEND -> {
                        assertThat(action).as(where).isNotNull();
                        assertThat(action.getActionType()).as(where).isEqualTo(eBattleActionType.BATTLE_ACTION_DEFEND);
                    }
                    case BATTLE_EVENT_ITEM -> {
                        assertThat(action).as(where).isNotNull();
                        assertThat(action.getActionType()).as(where).isEqualTo(eBattleActionType.BATTLE_ACTION_ITEM);
                        assertThat(event.getItemTableId()).as(where).isEqualTo(action.getItemTableId());
                        assertThat(event.getTargetId()).as(where).isIn(source, action.getTargetId());
                        itemEvents.merge(source, 1, Integer::sum);
                    }
                    case BATTLE_EVENT_DEATH -> {
                        long victimId = event.getTargetId();
                        assertThat(source).as(where + "：DEATH 的 source = target = 死者").isEqualTo(victimId);
                        assertThat(diedEver.add(victimId)).as(where + "：每个单位只死一次").isTrue();
                        inactive.add(victimId);
                        creditKill(events, i, where);
                    }
                    case BATTLE_EVENT_MISS, BATTLE_EVENT_HEAL, BATTLE_EVENT_BLOCK -> fail(where + "：不该产出的事件类型");
                    default -> {
                    }
                }
            }

            // 不变量 5：hit_index
            for (Map.Entry<Integer, List<BattleEventItem>> entry : groups.entrySet()) {
                BattleEventItem skillEvent = entry.getValue().stream()
                        .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_SKILL).findFirst().orElse(null);
                int bound = 1;
                if (skillEvent != null) {
                    SkillTable row = scenario.data().skill(skillEvent.getSkillTableId()).orElseThrow();
                    if (BattleRules.isAreaSkill(row)) {
                        int casterTeam = initialById.get(skillEvent.getSourceId()).getTeamIndex();
                        bound = (int) before.getActorsList().stream()
                                .filter(a -> active(a) && a.getTeamIndex() != casterTeam).count();
                    }
                }
                for (BattleEventItem event : entry.getValue()) {
                    assertThat(Integer.toUnsignedLong(event.getHitIndex()))
                            .as("第 %d 回合 %s 的 hit_index", round, EngineTestSupport.describe(event)).isLessThan(bound);
                }
            }

            // 不变量 5：出手者的先后是出手序的子序列
            int cursor = 0;
            for (long acting : actingSources) {
                while (cursor < order.size() && order.get(cursor) != acting) {
                    cursor++;
                }
                assertThat(cursor).as("第 %d 回合出手者 %s 不在出手序剩余部分里", round, EngineTestSupport.actorName(acting))
                        .isLessThan(order.size());
                cursor++;
            }

            // 快照与胜负
            BattleStateS2C state = result.getState();
            checkState(state);
            eBattleOutcome outcome = engine.outcome();
            assertThat(state.getOutcome()).isEqualTo(outcome);
            assertThat(before.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
            boolean sideAAlive = state.getActorsList().stream().anyMatch(a -> a.getTeamIndex() == 0 && active(a));
            boolean sideBAlive = state.getActorsList().stream().anyMatch(a -> a.getTeamIndex() == 1 && active(a));
            eBattleOutcome expectedOutcome;
            if (!sideAAlive && !sideBAlive) {
                expectedOutcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
            } else if (!sideAAlive) {
                expectedOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
            } else if (!sideBAlive) {
                expectedOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
            } else if (round >= scenario.maxRounds()) {
                expectedOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
            } else {
                expectedOutcome = eBattleOutcome.BATTLE_OUTCOME_ONGOING;
            }
            assertThat(outcome).as("第 %d 回合的胜负", round).isEqualTo(expectedOutcome);
            if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
                assertThat(state.getRoundIndex()).isEqualTo(round + 1);
                List<Long> expectedPending = state.getActorsList().stream()
                        .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER && active(a) && !a.getIsAuto())
                        .map(BattleActorState::getActorId)
                        .toList();
                assertThat(state.getPendingActorIdsList()).as("结算后的待行动名单").containsExactlyElementsOf(expectedPending);
            } else {
                assertThat(state.getRoundIndex()).isEqualTo(round);
                assertThat(state.getPendingActorIdsList()).isEmpty();
            }

            // 不变量 11：R6 掉落掷点，只在刚判出 A 方胜的这一回合
            if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN) {
                long qualifying = qualifyingPlayers(state).size();
                long slots = 0;
                for (int monsterId : creditedKills) {
                    slots += scenario.data().monster(monsterId).map(Checker::nonEmptySlots).orElse(0);
                }
                draws += qualifying * slots;
            }
            expectedDraws = draws;
            assertThat(engine.rngDrawsForTest()).as("第 %d 回合后的随机数总抽数", round).isEqualTo(expectedDraws);
        }

        /** 不变量 7：紧挨着 DEATH 的 DAMAGE / BUFF_TICK 的 source 就是凶手；按 HandleDeath 的条件记账。 */
        private void creditKill(List<BattleEventItem> events, int deathIndex, String where) {
            BattleActorState victim = initialById.get(events.get(deathIndex).getTargetId());
            if (victim.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER || victim.getTeamIndex() != 1
                    || victim.getMonsterTableId() == 0) {
                return;
            }
            assertThat(deathIndex).as(where + "：怪物死亡前必有致死事件").isGreaterThan(0);
            BattleEventItem lethal = events.get(deathIndex - 1);
            assertThat(lethal.getEventType()).as(where).isIn(eBattleEventType.BATTLE_EVENT_DAMAGE, eBattleEventType.BATTLE_EVENT_BUFF_TICK);
            assertThat(lethal.getTargetId()).as(where).isEqualTo(victim.getActorId());
            BattleActorState killer = initialById.get(lethal.getSourceId());
            BattleActorState owner = killer != null && killer.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET
                    ? initialById.get(killer.getOwnerPlayerId())
                    : killer;
            if (killer != null && killer.getTeamIndex() == 0 && owner != null
                    && owner.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER && owner.getTeamIndex() == 0) {
                creditedKills.add(victim.getMonsterTableId());
            }
        }

        private static int nonEmptySlots(MonsterTable row) {
            int slots = 0;
            for (Monsterdrop drop : row.getDropList()) {
                if (drop.getDropItem() != 0 && drop.getDropCount() != 0 && drop.getDropRate() != 0) {
                    slots++;
                }
            }
            return slots;
        }

        private static List<Long> qualifyingPlayers(BattleStateS2C state) {
            return state.getActorsList().stream()
                    .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER && a.getTeamIndex() == 0 && active(a))
                    .map(BattleActorState::getActorId)
                    .toList();
        }

        /** 不变量 3、4：每份快照里的单位状态。 */
        private void checkState(BattleStateS2C state) {
            assertThat(state.getBattleId()).isEqualTo(scenario.request().getBattleId());
            assertThat(state.getActionDeadlineMs()).isZero();
            assertThat(state.getSelfItemsList()).isEmpty();
            assertThat(state.getActorsList()).extracting(BattleActorState::getActorId).containsExactlyElementsOf(initialOrder);
            for (BattleActorState actor : state.getActorsList()) {
                String name = EngineTestSupport.actorName(actor.getActorId());
                BattleActorState initial = initialById.get(actor.getActorId());
                assertThat(actor.getActorType()).as(name).isEqualTo(initial.getActorType());
                assertThat(actor.getTeamIndex()).as(name).isEqualTo(initial.getTeamIndex());
                assertThat(actor.getAttributes().getSpeed()).as(name).isEqualTo(initial.getAttributes().getSpeed());
                assertThat(actor.getMaxHealth()).as(name).isEqualTo(initial.getMaxHealth());
                assertThat(actor.getIsDefending()).as(name + " 快照里不在防御中").isFalse();
                assertThat(Long.compareUnsigned(actor.getAttributes().getHealth(), actor.getMaxHealth()))
                        .as(name + " 气血 ≤ 上限").isLessThanOrEqualTo(0);
                assertThat(Long.compareUnsigned(actor.getAttributes().getMana(), actor.getMaxMana()))
                        .as(name + " 法力 ≤ 上限").isLessThanOrEqualTo(0);
                if (actor.getIsDead()) {
                    assertThat(actor.getBuffsList()).as(name + " 已阵亡不留 buff").isEmpty();
                    assertThat(actor.getAttributes().getHealth()).as(name + " 已阵亡气血为 0").isZero();
                }
            }
        }

        /** 不变量 6、7、8、9 与道具账。 */
        void afterBattle(int resolved) {
            eBattleOutcome outcome = engine.outcome();
            assertThat(outcome).isNotEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
            BattleStateS2C finalState = engine.buildStateSnapshot();
            List<Long> lastOrder = engine.lastActionOrder();

            // 不变量 6：结束后再结算没有事件、不耗随机数、快照不变
            TurnResultS2C again = engine.resolveCurrentRound();
            assertThat(again.getEventsList()).isEmpty();
            assertThat(again.getRoundIndex()).isEqualTo(finalState.getRoundIndex());
            assertThat(again.getBattleId()).isEqualTo(scenario.request().getBattleId());
            assertThat(deterministicBytes(again.getState())).isEqualTo(deterministicBytes(finalState));
            assertThat(engine.rngDrawsForTest()).isEqualTo(expectedDraws);
            assertThat(engine.lastActionOrder()).isEqualTo(lastOrder);

            List<Long> qualifying = qualifyingPlayers(finalState);
            boolean sideAWin = outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
            long expectedExp = 0;
            long expectedGold = 0;
            Map<Integer, Long> dropLower = new TreeMap<>();
            Map<Integer, Long> dropUpper = new TreeMap<>();
            for (int monsterId : creditedKills) {
                MonsterTable row = scenario.data().monster(monsterId).orElse(null);
                if (row == null) {
                    continue;
                }
                expectedExp += row.getExpReward();
                expectedGold += row.getGoldReward();
                for (Monsterdrop drop : row.getDropList()) {
                    if (drop.getDropItem() == 0 || drop.getDropCount() == 0 || drop.getDropRate() == 0) {
                        continue;
                    }
                    dropUpper.merge(drop.getDropItem(), Integer.toUnsignedLong(drop.getDropCount()), Long::sum);
                    if (Integer.compareUnsigned(drop.getDropRate(), BattleConstants.DROP_RATE_DENOMINATOR) >= 0) {
                        dropLower.merge(drop.getDropItem(), Integer.toUnsignedLong(drop.getDropCount()), Long::sum);
                    }
                }
            }

            for (BattlePlayerSnapshot snapshot : scenario.request().getPlayersList()) {
                long playerId = snapshot.getPlayerId();
                String name = EngineTestSupport.actorName(playerId);
                assertThat(engine.submitAction(playerId, DEFAULT_ATTACK)).isFalse();
                assertThat(engine.validateAction(playerId, DEFAULT_ATTACK)).isEqualTo(INVALID_PARAMETER);
                assertThat(engine.setActorAuto(playerId, true)).isEqualTo(INVALID_PARAMETER);

                // 不变量 9：重复读取字节相同，字段与终局快照一致
                BattleSettlementData settlement = engine.buildSettlement(playerId);
                assertThat(engine.buildSettlement(playerId).toByteArray()).as(name + " 结算重复读取").isEqualTo(settlement.toByteArray());
                BattleActorState actor = stateOf(finalState, playerId);
                assertThat(settlement.getBattleId()).isEqualTo(scenario.request().getBattleId());
                assertThat(settlement.getPlayerId()).isEqualTo(playerId);
                assertThat(settlement.getOutcome()).isEqualTo(outcome);
                assertThat(settlement.getTotalRounds()).as(name + " total_rounds").isEqualTo(resolved);
                assertThat(settlement.getPlayerTeamIndex()).isEqualTo(actor.getTeamIndex());
                assertThat(settlement.getHealth()).isEqualTo(actor.getAttributes().getHealth());
                assertThat(settlement.getMana()).isEqualTo(actor.getAttributes().getMana());
                assertThat(settlement.getIsDead()).isEqualTo(actor.getIsDead());
                assertThat(settlement.getFled()).isEqualTo(actor.getFled());
                List<BattlePetSettlementData> expectedPets = new ArrayList<>();
                for (BattleActorState other : finalState.getActorsList()) {
                    if (other.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET && other.getOwnerPlayerId() == playerId) {
                        expectedPets.add(BattlePetSettlementData.newBuilder()
                                .setPetId(other.getPetId())
                                .setHealth(other.getAttributes().getHealth())
                                .setMana(other.getAttributes().getMana())
                                .setIsDead(other.getIsDead())
                                .build());
                    }
                }
                assertThat(settlement.getPetsList()).as(name + " 宝宝终值").containsExactlyElementsOf(expectedPets);

                // 不变量 7、8：击杀簿、经验金币、掉落只给 A 方胜时的合格玩家
                if (sideAWin && qualifying.contains(playerId)) {
                    assertThat(settlement.getDefeatedMonstersList()).as(name + " 击杀簿")
                            .extracting(BattleMonsterDefeat::getMonsterConfigId).containsExactlyElementsOf(creditedKills);
                    assertThat(settlement.getDefeatedMonstersList()).allMatch(d -> d.getCount() == 1);
                    assertThat(settlement.getExpGain()).isEqualTo(expectedExp);
                    assertThat(settlement.getGoldGain()).isEqualTo(expectedGold);
                    Set<Integer> seen = new HashSet<>();
                    for (BattleItemEntry gained : settlement.getItemsGainedList()) {
                        assertThat(seen.add(gained.getItemTableId())).as(name + " 掉落按物品合并").isTrue();
                        assertThat(gained.getCount()).as(name + " 物品 %d 的掉落数", gained.getItemTableId())
                                .isBetween(dropLower.getOrDefault(gained.getItemTableId(), 0L),
                                        dropUpper.getOrDefault(gained.getItemTableId(), 0L));
                        assertThat(gained.getCount()).isPositive();
                    }
                    for (Map.Entry<Integer, Long> lower : dropLower.entrySet()) {
                        assertThat(seen).as(name + " 必掉的物品 %d", lower.getKey()).contains(lower.getKey());
                    }
                } else {
                    assertThat(settlement.getDefeatedMonstersList()).as(name).isEmpty();
                    assertThat(settlement.getExpGain()).as(name).isZero();
                    assertThat(settlement.getGoldGain()).as(name).isZero();
                    assertThat(settlement.getItemsGainedList()).as(name + " 不合格者没有掉落").isEmpty();
                }

                // 道具账：消耗 + 剩余 = 快照初始数量；ITEM 事件数 = 消耗数；PVP 每人 ≤ 5
                Map<Integer, Long> initialCounts = new TreeMap<>();
                for (BattleItemEntry entry : snapshot.getItemsList()) {
                    initialCounts.merge(entry.getItemTableId(), entry.getCount(), Long::sum);
                }
                Map<Integer, Long> remaining = new TreeMap<>();
                List<BattleItemEntry> selfItems = engine.selfItems(playerId);
                for (int i = 0; i < selfItems.size(); i++) {
                    BattleItemEntry entry = selfItems.get(i);
                    assertThat(entry.getCount()).isPositive();
                    if (i > 0) {
                        assertThat(Integer.compareUnsigned(selfItems.get(i - 1).getItemTableId(), entry.getItemTableId()))
                                .as(name + " 剩余道具按 id 升序").isLessThanOrEqualTo(0);
                    }
                    remaining.merge(entry.getItemTableId(), entry.getCount(), Long::sum);
                }
                long consumedTotal = 0;
                Set<Integer> consumedIds = new HashSet<>();
                for (BattleItemEntry consumed : settlement.getItemsConsumedList()) {
                    assertThat(consumedIds.add(consumed.getItemTableId())).as(name + " 消耗按物品合并").isTrue();
                    remaining.merge(consumed.getItemTableId(), consumed.getCount(), Long::sum);
                    consumedTotal += consumed.getCount();
                }
                assertThat(remaining).as(name + " 消耗 + 剩余 = 初始").isEqualTo(withoutZeros(initialCounts));
                assertThat(consumedTotal).as(name + " ITEM 事件数 = 消耗数").isEqualTo(itemEvents.getOrDefault(playerId, 0).longValue());
                if (!scenario.pve()) {
                    assertThat(consumedTotal).as(name + " PVP 用药上限").isLessThanOrEqualTo(BattleConstants.MAX_ITEM_USES_PER_BATTLE_PVP);
                }
            }

            BattleSettlementData absent = engine.buildSettlement(ABSENT_PLAYER);
            assertThat(absent).isEqualTo(BattleSettlementData.newBuilder()
                    .setBattleId(scenario.request().getBattleId())
                    .setPlayerId(ABSENT_PLAYER)
                    .setOutcome(outcome)
                    .setTotalRounds(resolved)
                    .build());
            assertThat(engine.selfItems(ABSENT_PLAYER)).isEmpty();
        }

        private static Map<Integer, Long> withoutZeros(Map<Integer, Long> counts) {
            Map<Integer, Long> result = new TreeMap<>();
            counts.forEach((id, count) -> {
                if (count != 0) {
                    result.put(id, count);
                }
            });
            return result;
        }
    }
}
