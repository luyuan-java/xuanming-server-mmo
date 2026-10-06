package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.eBattleOutcome;
import com.game.table.SkillTable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** battle-settle 场景的纯函数件（scene-battle-spec §13.8）。 */
class BattleSettleChecksTest {

    private static final long A = 101;
    private static final long PET = 9001;
    private static final String FP = "0123456789abcdef0123456789abcdef";

    private static PrepareBattleResponse good() {
        return PrepareBattleResponse.newBuilder().setTableFingerprint(FP).setSnapshot(BattlePlayerSnapshot.newBuilder()
                .setPlayerId(A).setLevel(1).setMaxHealth(500).setTableFingerprint(FP)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(500).setSpeed(240))
                .setRouting(BattleRouting.newBuilder().setSceneNodeId(3).setSceneInstanceId("scene").setGateInstanceId("gate").setSessionId(7))
                .addSkillTableIds(1).addSkillTableIds(2)
                .addItems(BattleItemEntry.newBuilder().setItemTableId(10).setCount(2))
                .addPets(BattlePetSnapshot.newBuilder().setPetId(PET).setOwnerPlayerId(A).setMaxHealth(300))).build();
    }

    private static List<String> problems(PrepareBattleResponse response) {
        return BattleSettleChecks.snapshotProblems(response, A, Set.of(1, 2, 13), skill -> skill != 13, item -> item == 10, PET);
    }

    @Test
    void battle_id按时间递增_同一毫秒也严格递增() {
        BattleSettleChecks.BattleIdSequence ids = new BattleSettleChecks.BattleIdSequence();
        long first = ids.next(1_760_000_000_000L);
        long second = ids.next(1_760_000_000_000L);
        long later = ids.next(1_760_000_000_005L);
        long clockBack = ids.next(1_759_999_999_000L);

        assertThat(first).isEqualTo(1_760_000_000_000_000L);
        assertThat(second).isEqualTo(first + 1);
        assertThat(later).isEqualTo(1_760_000_000_005_000L);
        assertThat(clockBack).as("时钟回拨也不倒退").isEqualTo(later + 1);
        assertThat(later).isPositive();
    }

    @Test
    void 技能可施放_类型位不含被动_持续施法_开关() {
        assertThat(BattleSettleChecks.castable(SkillTable.newBuilder().setId(1).build())).isTrue();
        assertThat(BattleSettleChecks.castable(SkillTable.newBuilder().setId(1).addSkillType(1).build())).isTrue();
        assertThat(BattleSettleChecks.castable(SkillTable.newBuilder().setId(1).addSkillType(0).build())).isFalse();
        assertThat(BattleSettleChecks.castable(SkillTable.newBuilder().setId(1).addSkillType(1).addSkillType(2).build())).isFalse();
        assertThat(BattleSettleChecks.castable(SkillTable.newBuilder().setId(1).addSkillType(3).build())).isFalse();
    }

    @Test
    void 快照核对_合格的没有问题() {
        assertThat(problems(good())).isEmpty();
    }

    @Test
    void 快照核对_逐项挑错() {
        PrepareBattleResponse base = good();
        BattlePlayerSnapshot s = base.getSnapshot();

        assertThat(problems(PrepareBattleResponse.getDefaultInstance())).containsExactly("没有快照");
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setPlayerId(7)).build())).anyMatch(p -> p.startsWith("player_id"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setLevel(0)).build())).anyMatch(p -> p.startsWith("level"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setBaseAttributes(s.getBaseAttributes().toBuilder().setSpeed(0)))
                .build())).anyMatch(p -> p.startsWith("speed"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setMaxHealth(0)).build())).anyMatch(p -> p.startsWith("max_health"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setRouting(s.getRouting().toBuilder().setSceneInstanceId("")))
                .build())).anyMatch(p -> p.contains("scene"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setRouting(s.getRouting().toBuilder().setGateInstanceId("")))
                .build())).anyMatch(p -> p.contains("gate"));
        assertThat(problems(base.toBuilder().setTableFingerprint("ABC").build())).anyMatch(p -> p.startsWith("指纹"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setTableFingerprint("0".repeat(32))).build()))
                .as("快照与应答的指纹必须同值").anyMatch(p -> p.startsWith("指纹"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().addItems(BattleItemEntry.newBuilder().setItemTableId(11).setCount(1)))
                .build())).anyMatch(p -> p.startsWith("道具 11"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().clearPets()).build())).anyMatch(p -> p.startsWith("宝宝 0 只"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setPets(0, s.getPets(0).toBuilder().setOwnerPlayerId(5))).build()))
                .anyMatch(p -> p.startsWith("宝宝 pet_id"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().addSkillTableIds(13)).build())).anyMatch(p -> p.startsWith("技能 13"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().addSkillTableIds(99)).build())).anyMatch(p -> p.startsWith("技能 99"));
        assertThat(problems(base.toBuilder().setSnapshot(s.toBuilder().setTeamIndex(1)).build())).anyMatch(p -> p.startsWith("team_index"));
        assertThat(BattleSettleChecks.snapshotProblems(base, A, Set.of(1, 2), x -> true, x -> true, 0))
                .as("没有出战宝宝时快照不该带宝宝").anyMatch(p -> p.startsWith("没有出战宝宝"));
    }

    @Test
    void 气血回写_夹到上限_夹后为0回满() {
        assertThat(BattleSettleChecks.expectedHealth(300, 500)).isEqualTo(300);
        assertThat(BattleSettleChecks.expectedHealth(800, 500)).isEqualTo(500);
        assertThat(BattleSettleChecks.expectedHealth(0, 500)).as("阵亡回满").isEqualTo(500);
    }

    @Test
    void 宝宝回写_阵亡或夹后为0回满() {
        assertThat(BattleSettleChecks.expectedPetHealth(BattlePetSettlementData.newBuilder().setHealth(120).build(), 300)).isEqualTo(120);
        assertThat(BattleSettleChecks.expectedPetHealth(BattlePetSettlementData.newBuilder().setHealth(900).build(), 300)).isEqualTo(300);
        assertThat(BattleSettleChecks.expectedPetHealth(BattlePetSettlementData.newBuilder().setHealth(50).setIsDead(true).build(), 300))
                .isEqualTo(300);
        assertThat(BattleSettleChecks.expectedPetHealth(BattlePetSettlementData.newBuilder().build(), 300)).isEqualTo(300);
    }

    @Test
    void 背包合计_主包与临时格按配置号相加() {
        BagInfo main = BagInfo.newBuilder().addItems(BagItemInfo.newBuilder().setConfigId(10).setCount(3))
                .addItems(BagItemInfo.newBuilder().setConfigId(11).setCount(1)).build();
        BagInfo temp = BagInfo.newBuilder().addItems(BagItemInfo.newBuilder().setConfigId(10).setCount(2)).build();

        assertThat(BattleSettleChecks.bagTotals(List.of(main, temp))).isEqualTo(Map.of(10, 5L, 11, 1L));
    }

    @Test
    void 背包期望增量_消耗按持有夹紧_掉落全进包_数量夹到uint32() {
        BattleSettlementData settlement = BattleSettlementData.newBuilder()
                .addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(10).setCount(5))
                .addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(12).setCount(1))
                .addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(0).setCount(1))
                .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(10).setCount(1))
                .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(11).setCount(1L << 40))
                .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(13).setCount(0))
                .build();

        Map<Integer, Long> delta = BattleSettleChecks.expectedBagDelta(settlement, Map.of(10, 2L));

        // 10：持有 2，扣 5 夹成 2，再掉 1 → −1；12：没有，不扣；11：夹到 0xFFFFFFFF
        assertThat(delta).isEqualTo(Map.of(10, -1L, 11, 0xFFFFFFFFL));
        assertThat(BattleSettleChecks.actualBagDelta(Map.of(10, 2L, 11, 1L), Map.of(10, 1L, 11, 1L, 14, 3L)))
                .isEqualTo(Map.of(10, -1L, 14, 3L));
    }

    @Test
    void 击杀计数与大厅150比对() {
        BattleSettlementData settlement = BattleSettlementData.newBuilder().setBattleId(7).setPlayerId(A)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .addDefeatedMonsters(BattleMonsterDefeat.newBuilder().setMonsterConfigId(1).setCount(1))
                .addDefeatedMonsters(BattleMonsterDefeat.newBuilder().setMonsterConfigId(2).setCount(1))
                .addDefeatedMonsters(BattleMonsterDefeat.newBuilder().setMonsterConfigId(1).setCount(2)).build();
        BattleEndS2C direct = BattleEndS2C.newBuilder().setBattleId(7).setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setSettlement(settlement).build();

        assertThat(BattleSettleChecks.killed(settlement, 1)).isEqualTo(3);
        assertThat(BattleSettleChecks.killed(settlement, 5)).isZero();
        assertThat(BattleSettleChecks.lobbyEndMismatch(direct, direct)).isNull();
        assertThat(BattleSettleChecks.lobbyEndMismatch(direct.toBuilder().setBattleId(8).build(), direct)).contains("battle_id");
        assertThat(BattleSettleChecks.lobbyEndMismatch(direct.toBuilder().setOutcome(eBattleOutcome.BATTLE_OUTCOME_DRAW).build(), direct))
                .contains("outcome");
        assertThat(BattleSettleChecks.lobbyEndMismatch(direct.toBuilder().setSettlement(settlement.toBuilder().setGoldGain(1)).build(), direct))
                .contains("settlement");
    }
}
