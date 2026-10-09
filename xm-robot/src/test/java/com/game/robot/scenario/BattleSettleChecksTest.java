package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import com.game.proto.SceneInfoComp;
import com.game.proto.eBattleOutcome;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamView;
import com.game.robot.scenario.BattleSettleChecks.StepFailures;
import com.game.table.SkillTable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
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

    // ------------------------------------------------------------------ 第 13 步：逐闸的指标

    /** 一次抓取的 Prometheus 文本：六个本场景会打到的闸各给一个值，外加两个打不到的闸与一条别的指标。 */
    private static String scrape(double enterScene, double attribute, double pet, double bagSort, double skill, double move) {
        return "# HELP xm_scene_battle_gate_rejects_total 回合制战斗在途闸挡掉的操作\n"
                + "# TYPE xm_scene_battle_gate_rejects_total counter\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"enter_scene\"} " + enterScene + "\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"attribute\"} " + attribute + "\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"pet\"} " + pet + "\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"bag_sort\"} " + bagSort + "\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"skill\"} " + skill + "\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"move\"} " + move + "\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"asset\"} 0.0\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"default\"} 4.0\n"
                + "xm_scene_battle_gate_rejects_total{gate=\"zone_travel\"} 0.0\n"
                + "xm_scene_battle_settlements_total{path=\"online\",result=\"applied\"} 2.0\n";
    }

    @Test
    void 逐闸增量_每个闸各算各的() {
        String before = scrape(1, 0, 0, 0, 0, 0);
        String after = scrape(3, 1, 2, 1, 2, 1);

        assertThat(BattleSettleChecks.gateRejectDeltas(before, after, BattleSettleChecks.EXERCISED_GATES))
                .containsExactly(Map.entry("enter_scene", 2.0), Map.entry("attribute", 1.0), Map.entry("pet", 2.0), Map.entry("bag_sort", 1.0),
                        Map.entry("skill", 2.0), Map.entry("move", 1.0));
        assertThat(BattleSettleChecks.gateRejectDeltas(before, after, BattleSettleChecks.UNEXERCISED_GATES))
                .containsExactly(Map.entry("asset", 0.0), Map.entry("default", 0.0), Map.entry("zone_travel", 0.0));
    }

    @Test
    void 逐闸增量_只有一个闸在计数时合计照样增长_但其余闸的增量是0() {
        // OPS-19：原来只断言全标签合计 ≥ 1，某个服务闸漏了计数（这里只有 enter_scene 在涨）也照样通过
        String before = scrape(0, 0, 0, 0, 0, 0);
        String after = scrape(5, 0, 0, 0, 0, 0);

        assertThat(BattleSettleChecks.delta(before, after, BattleSettleChecks.GATE_REJECTS)).as("合计").isEqualTo(5.0);
        Map<String, Double> perGate = BattleSettleChecks.gateRejectDeltas(before, after, BattleSettleChecks.EXERCISED_GATES);
        assertThat(perGate.get("enter_scene")).isEqualTo(5.0);
        assertThat(perGate.entrySet().stream().filter(e -> e.getValue() < 1).map(Map.Entry::getKey))
                .containsExactly("attribute", "pet", "bag_sort", "skill", "move");
    }

    @Test
    void 闸标签串_不会被同前缀的别的标签值蒙混() {
        assertThat(BattleSettleChecks.gateLabel("pet")).isEqualTo("gate=\"pet\"");
        String text = "xm_scene_battle_gate_rejects_total{gate=\"pet_extra\"} 9.0\nxm_scene_battle_gate_rejects_total{gate=\"pet\"} 2.0\n";
        assertThat(BattleSettleChecks.delta("", text, BattleSettleChecks.GATE_REJECTS, BattleSettleChecks.gateLabel("pet"))).isEqualTo(2.0);
    }

    @Test
    void 两个scene节点的抓取文本拼在一起即按节点求和() {
        String before = scrape(1, 0, 0, 0, 0, 0) + "\n" + scrape(0, 0, 0, 0, 0, 0);
        String after = scrape(1, 0, 0, 0, 0, 0) + "\n" + scrape(2, 1, 1, 1, 1, 1);

        Map<String, Double> perGate = BattleSettleChecks.gateRejectDeltas(before, after, BattleSettleChecks.EXERCISED_GATES);

        assertThat(perGate.values()).as("A 在第二个节点上：只看第一个节点会全是 0").containsExactly(2.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        assertThat(BattleSettleChecks.delta(before, after, "xm_scene_battle_settlements_total", "result=\"applied\"")).isZero();
        assertThat(BattleSettleChecks.delta(scrape(0, 0, 0, 0, 0, 0), after, "xm_scene_battle_settlements_total", "result=\"applied\""))
                .as("两个节点各 2").isEqualTo(2.0);
    }

    // ------------------------------------------------------------------ 第 13 步：双 scene 切片只抓了一个节点（评审 R-2）

    private static final String SECOND_NODE = "scene_config_id=1 scene_id=200";
    private static final String APPLIED = "xm_scene_battle_settlements_total{result=\"applied\"}";
    private static final String RELEASED = "xm_scene_battle_acks_total{result=\"released\"}";

    /**
     * 一个 scene 节点的一次抓取：结算应用 / 销账放锁 / 六个会打到的闸（同一个值）。带上真实抓取里会有的别的标签与序列
     * （{@code path}、{@code result="deferred"}、打不到的闸），它们不该被算进去。
     */
    private static String node(double applied, double released, double eachGate) {
        return "xm_scene_battle_settlements_total{path=\"online\",result=\"applied\"} " + applied + "\n"
                + "xm_scene_battle_settlements_total{path=\"online\",result=\"deferred\"} 7.0\n"
                + "xm_scene_battle_acks_total{result=\"released\"} " + released + "\n"
                + "xm_scene_battle_acks_total{result=\"kept\"} 3.0\n"
                + scrape(eachGate, eachGate, eachGate, eachGate, eachGate, eachGate).lines()
                        .filter(line -> line.startsWith(BattleSettleChecks.GATE_REJECTS)).reduce("", (a, b) -> a + b + "\n");
    }

    @Test
    void 第13步scene侧的下限_结算应用与销账各2_六个闸各1_按断言的先后() {
        assertThat(BattleSettleChecks.sceneMetricFloors()).extracting(BattleSettleChecks.MetricFloor::series,
                        BattleSettleChecks.MetricFloor::atLeast)
                .containsExactly(tuple(APPLIED, 2L), tuple(RELEASED, 2L),
                        tuple("xm_scene_battle_gate_rejects_total{gate=\"enter_scene\"}", 1L),
                        tuple("xm_scene_battle_gate_rejects_total{gate=\"attribute\"}", 1L),
                        tuple("xm_scene_battle_gate_rejects_total{gate=\"pet\"}", 1L),
                        tuple("xm_scene_battle_gate_rejects_total{gate=\"bag_sort\"}", 1L),
                        tuple("xm_scene_battle_gate_rejects_total{gate=\"skill\"}", 1L),
                        tuple("xm_scene_battle_gate_rejects_total{gate=\"move\"}", 1L));
    }

    @Test
    void 双scene切片_A中途换了节点_两个节点都抓就达标_只抓一个必然缺一截() {
        // A 原在节点 1：第 4 步的六个闸计在节点 1；第 11 步之后 A 在节点 2，第 6、9 步的两次结算应用与销账计在节点 2
        String n1Before = node(5, 5, 3);
        String n1After = node(5, 5, 4);
        String n2Before = node(1, 1, 0);
        String n2After = node(3, 3, 0);

        assertThat(BattleSettleChecks.sceneMetricShortfalls(n1Before + n2Before, n1After + n2After)).as("两个地址都给了：按节点之和，全部达标").isEmpty();
        assertThat(BattleSettleChecks.sceneMetricShortfalls(n1Before, n1After)).as("只抓 A 原来所在的节点 1：结算与销账看不到")
                .containsExactly(APPLIED + " 5.0 → 5.0（要 ≥ 2）", RELEASED + " 5.0 → 5.0（要 ≥ 2）");
        assertThat(BattleSettleChecks.sceneMetricShortfalls(n2Before, n2After)).as("只抓节点 2（A 原在没抓的节点上）：六个闸看不到")
                .containsExactly("xm_scene_battle_gate_rejects_total{gate=\"enter_scene\"} 0.0 → 0.0（要 ≥ 1）",
                        "xm_scene_battle_gate_rejects_total{gate=\"attribute\"} 0.0 → 0.0（要 ≥ 1）",
                        "xm_scene_battle_gate_rejects_total{gate=\"pet\"} 0.0 → 0.0（要 ≥ 1）",
                        "xm_scene_battle_gate_rejects_total{gate=\"bag_sort\"} 0.0 → 0.0（要 ≥ 1）",
                        "xm_scene_battle_gate_rejects_total{gate=\"skill\"} 0.0 → 0.0（要 ≥ 1）",
                        "xm_scene_battle_gate_rejects_total{gate=\"move\"} 0.0 → 0.0（要 ≥ 1）");
        assertThat(BattleSettleChecks.sceneMetricShortfalls(n2Before, node(2, 3, 0))).as("只涨了 1 也不够 2").contains(APPLIED + " 1.0 → 2.0（要 ≥ 2）")
                .doesNotContain(RELEASED + " 1.0 → 3.0（要 ≥ 2）");
    }

    @Test
    void 只抓了一部分节点的判定_见过第二个节点_地址不足两个_增量又不达标_三样都成立才算() {
        List<String> shortfalls = List.of(APPLIED + " 5.0 → 5.0（要 ≥ 2）", RELEASED + " 5.0 → 5.0（要 ≥ 2）");

        String problem = BattleSettleChecks.partialScrapeProblem(1, SECOND_NODE, shortfalls);
        assertThat(problem).as("原因、差的是哪几条、怎么改，都写在一条里").contains("双 scene 切片", SECOND_NODE, "只给了 1 个地址", "只抓一个节点判不了",
                shortfalls.get(0), shortfalls.get(1), "--scene-metrics-url http://127.0.0.1:18104,http://127.0.0.1:18114");

        assertThat(BattleSettleChecks.partialScrapeProblem(1, null, shortfalls)).as("没见过第二个节点（单 scene 切片）：照常逐条断言").isNull();
        assertThat(BattleSettleChecks.partialScrapeProblem(2, SECOND_NODE, shortfalls)).as("两个地址都给了还不达标：是真问题，逐条断言").isNull();
        assertThat(BattleSettleChecks.partialScrapeProblem(1, SECOND_NODE, List.of()))
                .as("抓到的节点上全都达标（「同图不同频道 = 不同节点」在这个切片上不成立）：没有问题可报").isNull();
    }

    @Test
    void 第13步的scene判定_只抓了一部分节点时记一条写明原因的失败_不再逐条报0到0() {
        CheckReport report = new CheckReport();
        StepFailures failures = new StepFailures();
        long mark = failures.enter(report.failures());

        BattleSettleChecks.judgeSceneMetrics(report, node(5, 5, 3), node(5, 5, 4), 1, SECOND_NODE);
        failures.leave("13", mark, report.failures());

        assertThat(report.items()).as("原来是 2 条「5.0 → 5.0」加 6 条通过，看不出原因").singleElement().satisfies(item -> {
            assertThat(item.passed()).as("仍是失败：这一步没有判成，不能算过").isFalse();
            assertThat(item.name()).isEqualTo(BattleSettleChecks.PARTIAL_SCRAPE_CHECK).contains("--scene-metrics-url");
            assertThat(item.detail()).contains(SECOND_NODE, "只给了 1 个地址", APPLIED + " 5.0 → 5.0（要 ≥ 2）", RELEASED + " 5.0 → 5.0（要 ≥ 2）")
                    .doesNotContain("gate=");
        });
        assertThat(report.notes()).isEmpty();
        assertThat(BattleSettleChecks.summaryLine(report.passed(), failures.steps(), 7, 30, 12, true, true)).isEqualTo("BATTLE_SETTLE_FAIL step=13");
    }

    @Test
    void 第13步的scene判定_两个节点都抓到时逐条断言_名字与细节同原来的写法() {
        CheckReport report = new CheckReport();

        // 与上一条同一组数，只是把节点 2 也抓了：八条全过（声明了双 scene，见没见过第二个节点都一样）
        BattleSettleChecks.judgeSceneMetrics(report, node(5, 5, 3) + node(1, 1, 0), node(5, 5, 4) + node(3, 3, 0), 2, SECOND_NODE);

        assertThat(report.passed()).isTrue();
        assertThat(report.items()).hasSize(8);
        assertThat(report.items().get(0)).isEqualTo(new CheckReport.Item(true, "第 13 步 指标 " + APPLIED + " 增长 ≥ 2", "6.0 → 8.0", "scene-battle-spec §9"));
        assertThat(report.items().get(7)).isEqualTo(new CheckReport.Item(true,
                "第 13 步 指标 xm_scene_battle_gate_rejects_total{gate=\"move\"} 增长 ≥ 1", "3.0 → 4.0", "scene-battle-spec §9"));
        assertThat(report.notes()).isEmpty();
    }

    @Test
    void 第13步的scene判定_没见过第二个节点时照常逐条失败_全都没涨另补一条提示() {
        // 双 scene 切片、A 与 B 登录时恰好同频道（都在没抓的节点 2 上）：robot 见不到第二个节点，抓的节点 1 上一条都没涨
        CheckReport flat = new CheckReport();
        BattleSettleChecks.judgeSceneMetrics(flat, node(5, 5, 3), node(5, 5, 3), 1, null);
        assertThat(flat.items()).hasSize(8).allSatisfy(item -> assertThat(item.passed()).isFalse());
        assertThat(flat.items().get(0).detail()).isEqualTo("5.0 → 5.0");
        assertThat(flat.notes()).singleElement().satisfies(note -> assertThat(note).contains("一条都没有增长", "双 scene 切片", "--scene-metrics-url"));

        // 单 scene 切片上真有一个闸漏了计数：其余都涨了，不给这条提示（不是节点没抓全）
        CheckReport oneGate = new CheckReport();
        String after = node(7, 7, 4).replace("{gate=\"pet\"} 4.0", "{gate=\"pet\"} 3.0");
        BattleSettleChecks.judgeSceneMetrics(oneGate, node(5, 5, 3), after, 1, null);
        assertThat(oneGate.items()).filteredOn(item -> !item.passed()).singleElement()
                .satisfies(item -> assertThat(item.name()).contains("gate=\"pet\""));
        assertThat(oneGate.notes()).isEmpty();
        assertThat(BattleSettleChecks.sceneMetricsFlat(node(5, 5, 3), node(5, 5, 3))).isTrue();
        assertThat(BattleSettleChecks.sceneMetricsFlat(node(5, 5, 3), after)).isFalse();

        // 两个地址都抓了还是全都没涨：是真问题，提示帮不上忙，不给
        CheckReport both = new CheckReport();
        BattleSettleChecks.judgeSceneMetrics(both, node(5, 5, 3) + node(1, 1, 0), node(5, 5, 3) + node(1, 1, 0), 2, null);
        assertThat(both.items()).hasSize(8).allSatisfy(item -> assertThat(item.passed()).isFalse());
        assertThat(both.notes()).isEmpty();
    }

    @Test
    void 闸的取值与xm_scene的BattleGate枚举逐个对上() throws Exception {
        Path source = Path.of("../xm-scene/src/main/java/com/game/scene/metrics/SceneMetrics.java");
        if (!Files.isRegularFile(source)) {
            source = Path.of("xm-scene/src/main/java/com/game/scene/metrics/SceneMetrics.java");
        }
        Assumptions.assumeTrue(Files.isRegularFile(source), "找不到 xm-scene 的源码（单独构建 xm-robot）");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("public enum BattleGate \\{([^}]*)}").matcher(text);
        assertThat(m.find()).as("SceneMetrics 里有 BattleGate 枚举").isTrue();
        List<String> fromScene = new ArrayList<>();
        for (String name : m.group(1).split(",")) {
            if (!name.isBlank()) {
                fromScene.add(name.strip().toLowerCase(Locale.ROOT));
            }
        }
        List<String> fromRobot = new ArrayList<>(BattleSettleChecks.EXERCISED_GATES);
        fromRobot.addAll(BattleSettleChecks.UNEXERCISED_GATES);

        // 标签键是 gate、标签值 = 枚举名小写（SceneMetrics.tagValue）；scene 加了新闸而 robot 没跟上、或改了名，这里就会失败
        assertThat(text).contains("counters(BattleGate.class, BATTLE_GATE_REJECTS, \"gate\"").contains("value.name().toLowerCase(Locale.ROOT)")
                .contains("\"xm.scene.battle.gate.rejects\"");
        assertThat(fromRobot).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(fromScene);
        assertThat(BattleSettleChecks.EXERCISED_GATES).containsExactly("enter_scene", "attribute", "pet", "bag_sort", "skill", "move");
        assertThat(BattleSettleChecks.GATE_REJECTS).isEqualTo("xm_scene_battle_gate_rejects_total");
    }

    @Test
    void 指标地址列表_逗号分隔_去空白去结尾斜杠去重_空项丢掉() {
        assertThat(BattleSettleChecks.metricsUrls("http://127.0.0.1:18104")).containsExactly("http://127.0.0.1:18104");
        assertThat(BattleSettleChecks.metricsUrls(" http://127.0.0.1:18104/ , http://127.0.0.1:18114//,,http://127.0.0.1:18104 "))
                .containsExactly("http://127.0.0.1:18104", "http://127.0.0.1:18114");
        assertThat(BattleSettleChecks.metricsUrls("")).isEmpty();
        assertThat(BattleSettleChecks.metricsUrls(" , ")).isEmpty();
    }

    // ------------------------------------------------------------------ 第 14 步：汇总行与失败步骤

    @Test
    void 失败步骤登记_检查失败不抛异常也记到所在的步骤() {
        StepFailures failures = new StepFailures();
        long failed = 0;

        long mark = failures.enter(failed);
        failures.leave("2", mark, failed);
        mark = failures.enter(failed);
        failed += 2;
        failures.leave("3-4", mark, failed);
        mark = failures.enter(failed);
        failures.leave("5", mark, failed);
        mark = failures.enter(failed);
        failed += 1;
        failures.leave("13", mark, failed);

        assertThat(failures.steps()).as("OPS-20：原来只有抛异常才登记，这种情形汇总行是 step=?").containsExactly("3-4", "13");
    }

    @Test
    void 失败步骤登记_嵌在别的步骤里的检查记到它自己名下_外层步骤不背() {
        StepFailures failures = new StepFailures();
        long failed = 0;

        // 第 12 步前半通过
        long mark = failures.enter(failed);
        failures.leave("12", mark, failed);
        // 第 6–7 步里嵌着队伍视图的采样：采样失败 1 条，第 6–7 步自己的检查全过
        mark = failures.enter(failed);
        failed += 1;
        failures.attribute("12", 1);
        failures.leave("6-7", mark, failed);
        // 第 8 步：先嵌了一条别人的失败，自己又失败 1 条
        mark = failures.enter(failed);
        failed += 1;
        failures.attribute("12", 1);
        failed += 1;
        failures.leave("8", mark, failed);
        // 第 9 步：干净
        mark = failures.enter(failed);
        failures.leave("9", mark, failed);

        assertThat(failures.steps()).containsExactly("12", "8");
        failures.attribute("12", 0);
        failures.add("流程");
        failures.add("8");
        assertThat(failures.steps()).as("重复登记只留一次，按第一次失败的先后").containsExactly("12", "8", "流程");
    }

    @Test
    void 汇总行_全部通过才是OK_失败带步骤号() {
        assertThat(BattleSettleChecks.summaryLine(true, List.of(), 1_760_000_000_000_003L, 30, 12, true, true))
                .isEqualTo("BATTLE_SETTLE_OK battle_id=1760000000000003 gold=30 mission=12 relogin=ok offline=ok");
        assertThat(BattleSettleChecks.summaryLine(true, List.of(), 0x8000_0000_0000_0001L, -1, 0, false, false))
                .as("battle_id 按无符号十进制").isEqualTo("BATTLE_SETTLE_OK battle_id=9223372036854775809 gold=-1 mission=0 relogin=skip offline=skip");
        assertThat(BattleSettleChecks.summaryLine(false, List.of("3-4", "13"), 7, 30, 12, true, true)).isEqualTo("BATTLE_SETTLE_FAIL step=3-4,13");
        assertThat(BattleSettleChecks.summaryLine(true, List.of("流程"), 7, 30, 12, true, true))
                .as("登记了失败步骤就不是 OK，哪怕报告里的检查都过了").isEqualTo("BATTLE_SETTLE_FAIL step=流程");
        assertThat(BattleSettleChecks.summaryLine(false, List.of(), 7, 30, 12, true, true)).isEqualTo("BATTLE_SETTLE_FAIL step=?");
    }

    @Test
    void 汇总行_配合报告_一条普通检查失败就带出步骤号() {
        CheckReport report = new CheckReport();
        StepFailures failures = new StepFailures();
        long mark = failures.enter(report.failures());
        report.pass("第 2 步 大厅上发 149", "tip=1003", "scene-battle-spec §7.19");
        failures.leave("2", mark, report.failures());
        mark = failures.enter(report.failures());
        report.check(false, "第 4 步 168 加点 → 25011", "tip=0，期望 25011", "scene-battle-spec §7.13");
        failures.leave("3-4", mark, report.failures());

        assertThat(BattleSettleChecks.summaryLine(report.passed(), failures.steps(), 7, -1, 0, false, false)).isEqualTo("BATTLE_SETTLE_FAIL step=3-4");
    }

    // ------------------------------------------------------------------ 第 11 步：跨节点目标

    private static SceneInfoComp scene(int config, long id) {
        return SceneInfoComp.newBuilder().setSceneConfigId(config).setSceneId(id).build();
    }

    @Test
    void 跨节点目标_取第一个同图不同频道的候选_没有为null() {
        SceneInfoComp here = scene(1, 100);

        assertThat(BattleSettleChecks.remoteChannel(here, Arrays.asList(scene(1, 100), null, scene(2, 300), scene(1, 200), scene(1, 400))))
                .as("同一个频道、null、别的地图都跳过").isEqualTo(scene(1, 200));
        assertThat(BattleSettleChecks.remoteChannel(here, Arrays.asList(scene(1, 100), null))).as("单 scene 切片：A、B 同频道").isNull();
        assertThat(BattleSettleChecks.remoteChannel(here, List.of(scene(2, 300)))).as("别的地图不算（判据同 cross-node：同图不同 scene_id）").isNull();
        assertThat(BattleSettleChecks.remoteChannel(here, List.of(scene(1, 0)))).as("scene_id 为 0 的不算").isNull();
        assertThat(BattleSettleChecks.remoteChannel(here, List.of())).isNull();
    }

    // ------------------------------------------------------------------ 第 12 步：队伍视图

    @Test
    void 队伍视图_取这名成员的in_battle_不在视图里或出现两次为null() {
        long big = 0x8000_0000_0000_0001L;
        TeamView view = TeamView.newBuilder().setTeamId(9)
                .addMembers(TeamMemberView.newBuilder().setPlayerId(A).setInBattle(true))
                .addMembers(TeamMemberView.newBuilder().setPlayerId(big).setInBattle(false))
                .addMembers(TeamMemberView.newBuilder().setPlayerId(7))
                .addMembers(TeamMemberView.newBuilder().setPlayerId(7).setInBattle(true))
                .build();

        assertThat(BattleSettleChecks.inBattle(view, A)).isTrue();
        assertThat(BattleSettleChecks.inBattle(view, big)).isFalse();
        assertThat(BattleSettleChecks.inBattle(view, 7)).as("出现两次是服务端缺陷，不采信").isNull();
        assertThat(BattleSettleChecks.inBattle(view, 5)).isNull();
        assertThat(BattleSettleChecks.inBattle(TeamView.getDefaultInstance(), A)).as("无队（team_id = 0 的空视图）").isNull();
    }

    @Test
    void 队伍视图的等待上限够覆盖结算后的落盘销账() {
        // 结算应用后锁要等一次落盘才随销账放掉（§7.12），第 7 步给「锁已放」的上限是 2 s；队伍视图读的是同一把锁，等待不能比它短
        assertThat(BattleSettleScenario.TEAM_VIEW_WAIT).isGreaterThanOrEqualTo(BattleSettleScenario.LOCK_RELEASE_WAIT);
        assertThat(BattleSettleScenario.TEAM_VIEW_WAIT).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }
}
