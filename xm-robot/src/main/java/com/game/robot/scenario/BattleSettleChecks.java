package com.game.robot.scenario;

import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.PrepareBattleResponse;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * battle-settle 场景的纯函数件（scene-battle-spec §13.8）：按时间递增的 battle_id、快照核对、结算落地后的期望值（气血 / 宝宝气血 / 背包增量 / 金币）、
 * 大厅 150 与直连 150 的比对。不碰网络，单测覆盖。
 */
final class BattleSettleChecks {

    /** 战斗配表指纹：sha256 前 16 字节的小写 hex（engine-spec §10.1）。 */
    static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{32}");
    /** 单条数量上限（scene 把结算里的数量夹到 uint32，§7.11 h / i 步）。 */
    static final long UINT32_MAX = 0xFFFFFFFFL;
    /** 不可施放的技能类型位（被动 / 持续施法 / 开关，同 {@code BattleRules.isTurnBattleCastableSkill}）。 */
    private static final Set<Integer> NOT_CASTABLE_TYPE_BITS = Set.of(0, 2, 3);

    private BattleSettleChecks() {
    }

    /**
     * robot 自己发的 battle_id：毫秒时间戳 × 1000 + 序号，严格递增、跨次运行不重复（D13 的局序依赖 battle_id 随时间递增，§10.4）。
     * 只在场景线程上用。
     */
    static final class BattleIdSequence {

        private long last;

        long next(long nowMillis) {
            last = Math.max(last + 1, nowMillis * 1000);
            return last;
        }
    }

    /** 技能能否在回合制战斗里施放（技能类型位不含被动 0 / 持续施法 2 / 开关 3）。 */
    static boolean castable(SkillTable row) {
        for (int bit : row.getSkillTypeList()) {
            if (NOT_CASTABLE_TYPE_BITS.contains(bit)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 只备战（PREPARE_ONLY）回来的快照核对（§13.8 第 3 步）；返回全部问题，空 = 通过。
     *
     * @param knownSkills  玩家的技能（77 ListSkills），快照技能必须是它的子集
     * @param castable     技能表里可施放的判据（表里没有的技能按不可施放）
     * @param battleUsable 物品表里 battle_usable 的判据
     * @param petId        出战宝宝（0 = 没有出战，快照不该带宝宝）
     */
    static List<String> snapshotProblems(PrepareBattleResponse response, long playerId, Collection<Integer> knownSkills,
                                         IntPredicate castable, IntPredicate battleUsable, long petId) {
        List<String> problems = new ArrayList<>();
        if (!response.hasSnapshot()) {
            problems.add("没有快照");
            return problems;
        }
        BattlePlayerSnapshot s = response.getSnapshot();
        if (s.getPlayerId() != playerId) {
            problems.add("player_id=" + Long.toUnsignedString(s.getPlayerId()));
        }
        if (s.getLevel() < 1) {
            problems.add("level=" + s.getLevel());
        }
        if (s.getBaseAttributes().getSpeed() <= 0) {
            problems.add("speed=" + s.getBaseAttributes().getSpeed());
        }
        if (s.getMaxHealth() <= 0) {
            problems.add("max_health=" + s.getMaxHealth());
        }
        if (s.getRouting().getSceneNodeId() == 0 || s.getRouting().getSceneInstanceId().isEmpty()) {
            problems.add("routing 的 scene 节点 / 实例为空");
        }
        if (s.getRouting().getGateInstanceId().isEmpty()) {
            problems.add("routing 的 gate 实例为空");
        }
        if (s.getTeamIndex() != 0) {
            problems.add("team_index=" + s.getTeamIndex());
        }
        if (!FINGERPRINT.matcher(response.getTableFingerprint()).matches()
                || !response.getTableFingerprint().equals(s.getTableFingerprint())) {
            problems.add("指纹 response=" + response.getTableFingerprint() + " snapshot=" + s.getTableFingerprint());
        }
        for (BattleItemEntry item : s.getItemsList()) {
            if (!battleUsable.test(item.getItemTableId()) || item.getCount() == 0) {
                problems.add("道具 " + item.getItemTableId() + "×" + item.getCount() + " 不该进快照");
            }
        }
        if (petId == 0) {
            if (s.getPetsCount() != 0) {
                problems.add("没有出战宝宝却带了 " + s.getPetsCount() + " 只");
            }
        } else if (s.getPetsCount() != 1) {
            problems.add("宝宝 " + s.getPetsCount() + " 只（应 1 只）");
        } else {
            BattlePetSnapshot pet = s.getPets(0);
            if (pet.getPetId() != petId || pet.getOwnerPlayerId() != playerId || pet.getMaxHealth() == 0) {
                problems.add("宝宝 pet_id=" + Long.toUnsignedString(pet.getPetId()) + " owner=" + Long.toUnsignedString(pet.getOwnerPlayerId())
                        + " max_health=" + pet.getMaxHealth());
            }
        }
        for (int skill : s.getSkillTableIdsList()) {
            if (!knownSkills.contains(skill) || !castable.test(skill)) {
                problems.add("技能 " + skill + " 不是玩家可施放的技能");
            }
        }
        return problems;
    }

    /** 结算回写后的气血：夹到上限；夹后为 0（阵亡）回满（§7.11 e 步：只在气血 0 时复活，is_dead 但气血 > 0 不复活）。 */
    static long expectedHealth(long settled, long maxHealth) {
        long clamped = Math.min(settled, maxHealth);
        return clamped == 0 ? maxHealth : clamped;
    }

    /** 宝宝的结算回写（§5.3）：夹到上限；is_dead 或夹后为 0 → 回满。 */
    static long expectedPetHealth(BattlePetSettlementData settled, long maxHealth) {
        long clamped = Math.min(settled.getHealth(), maxHealth);
        return settled.getIsDead() || clamped == 0 ? maxHealth : clamped;
    }

    /** 几个包的物品按配置号合计（主包 + 临时格：掉落进主包失败的余量进临时格）。 */
    static Map<Integer, Long> bagTotals(Collection<BagInfo> bags) {
        Map<Integer, Long> totals = new TreeMap<>();
        for (BagInfo bag : bags) {
            for (BagItemInfo item : bag.getItemsList()) {
                totals.merge(item.getConfigId(), Integer.toUnsignedLong(item.getCount()), Long::sum);
            }
        }
        return totals;
    }

    /**
     * 结算应用后背包的期望增量（按配置号）：消耗按应用前的持有夹紧（没有不报错），掉落全部进包（主包满了进临时格）；数量先夹到 uint32。
     * 0 号配置与 0 数量跳过。
     */
    static Map<Integer, Long> expectedBagDelta(BattleSettlementData settlement, Map<Integer, Long> before) {
        Map<Integer, Long> delta = new TreeMap<>();
        Map<Integer, Long> consumed = new TreeMap<>();
        for (BattleItemEntry item : settlement.getItemsConsumedList()) {
            if (item.getItemTableId() != 0 && item.getCount() != 0) {
                consumed.merge(item.getItemTableId(), Math.min(item.getCount(), UINT32_MAX), Long::sum);
            }
        }
        consumed.forEach((config, count) -> delta.merge(config, -Math.min(count, before.getOrDefault(config, 0L)), Long::sum));
        for (BattleItemEntry item : settlement.getItemsGainedList()) {
            if (item.getItemTableId() != 0 && item.getCount() != 0) {
                delta.merge(item.getItemTableId(), Math.min(item.getCount(), UINT32_MAX), Long::sum);
            }
        }
        delta.values().removeIf(v -> v == 0);
        return delta;
    }

    /** 实际增量（after − before，按配置号，0 去掉）。 */
    static Map<Integer, Long> actualBagDelta(Map<Integer, Long> before, Map<Integer, Long> after) {
        Map<Integer, Long> delta = new TreeMap<>();
        after.forEach((config, count) -> delta.merge(config, count, Long::sum));
        before.forEach((config, count) -> delta.merge(config, -count, Long::sum));
        delta.values().removeIf(v -> v == 0);
        return delta;
    }

    /** 这一局击杀了几只某配置号的怪。 */
    static long killed(BattleSettlementData settlement, int monsterConfigId) {
        long count = 0;
        for (BattleMonsterDefeat defeat : settlement.getDefeatedMonstersList()) {
            if (defeat.getMonsterConfigId() == monsterConfigId) {
                count += Integer.toUnsignedLong(defeat.getCount());
            }
        }
        return count;
    }

    /**
     * 大厅上的 150（scene 结算落地后推）与直连上的 150（battle 终局帧）是否一致：battle_id、outcome、settlement 逐字段相同（§6.1：settlement = 引擎原值）。
     * 不一致返回原因。
     */
    static String lobbyEndMismatch(BattleEndS2C lobby, BattleEndS2C direct) {
        if (lobby.getBattleId() != direct.getBattleId()) {
            return "battle_id 大厅 " + Long.toUnsignedString(lobby.getBattleId()) + " / 直连 " + Long.toUnsignedString(direct.getBattleId());
        }
        if (lobby.getOutcome() != direct.getOutcome()) {
            return "outcome 大厅 " + lobby.getOutcome() + " / 直连 " + direct.getOutcome();
        }
        if (!lobby.getSettlement().equals(direct.getSettlement())) {
            return "settlement 不同：大厅 " + oneLine(lobby.getSettlement()) + " / 直连 " + oneLine(direct.getSettlement());
        }
        return null;
    }

    static String oneLine(Object message) {
        return String.valueOf(message).replace('\n', ' ').strip();
    }
}
