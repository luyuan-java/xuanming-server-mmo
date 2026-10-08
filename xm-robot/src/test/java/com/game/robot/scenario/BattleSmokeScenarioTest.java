package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.eBattleOutcome;
import com.game.robot.RobotOptions;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.RobotClient;
import com.game.robot.scenario.FakeMatchWorld.Fault;
import com.game.robot.scenario.SpectateSteps.Timing;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * battle-smoke 场景对着本机假服务端（{@link FakeMatchWorld}）整条跑：编排本身（先后次序、mark、等待条件、过渡态重试、收尾）走得通；
 * 假服务端故意做错一处时，对应那一步的检查失败、结果行指向那一步。假服务端只钉 robot 这一侧——真服务端的行为要到本机切片上才验证得了。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class BattleSmokeScenarioTest {

    private static final Pattern OK_LINE = Pattern.compile(
            "BATTLE_SMOKE_OK battle_id=(\\d+) a_turns=(\\d+) a_direct_turns=(\\d+) pvp_battle_id=(\\d+) challenge_battle_id=(\\d+)"
                    + " spectate_battle_id=(\\d+) b_spectate_turns=(\\d+) b_direct_spectate_turns=(\\d+) removed_ok=([01]) s12_ready_residue=([01])");

    private FakeMatchWorld world;

    @BeforeEach
    void start() throws IOException {
        world = new FakeMatchWorld();
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private BattleSmokeScenario scenario(String runTag, MatchAdminClient admin) {
        return scenario(runTag, admin, FakeMatchWorld.FAST_SPECTATE);
    }

    private BattleSmokeScenario scenario(String runTag, MatchAdminClient admin, Timing timing) {
        RobotClient client = world.newClient();
        return new BattleSmokeScenario(client, world.flow(client), world.registry(), admin, "robot_java_", runTag, FakeMatchWorld.TIMEOUT,
                FakeMatchWorld.FAST, timing);
    }

    private static List<String> failed(CheckReport report) {
        return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
    }

    private static List<String> names(CheckReport report) {
        return report.items().stream().map(CheckReport.Item::name).toList();
    }

    /** 把 {@link FakeMatchWorld#FAST_SPECTATE} 的某几项换掉。 */
    private static Timing timing(Duration liveBudget, Duration readyResidue) {
        Timing fast = FakeMatchWorld.FAST_SPECTATE;
        return new Timing(fast.ticketTimeout(), fast.firstFrameTimeout(), fast.endTimeout(), fast.stopTimeout(), fast.publishRetry(),
                fast.randomRetry(), fast.evictTimeout(), liveBudget, readyResidue, fast.settleWait(), fast.precleanRounds());
    }

    /** {@link FakeMatchWorld#FAST_SPECTATE}，只换随机观战的重试上限、等结算落地的上限与预清理的轮数。 */
    private static Timing timing(Duration randomRetry, Duration settleWait, int precleanRounds) {
        Timing fast = FakeMatchWorld.FAST_SPECTATE;
        return new Timing(fast.ticketTimeout(), fast.firstFrameTimeout(), fast.endTimeout(), fast.stopTimeout(), fast.publishRetry(),
                randomRetry, fast.evictTimeout(), fast.liveBudget(), fast.readyResidue(), settleWait, precleanRounds);
    }

    @Test
    void 观战段与匹配段整条跑通_结果行带各局的battle_id与观战字段_收尾后没有残留的锁排队票与观众() {
        BattleSmokeScenario scenario = scenario("ok", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.passed()).isTrue();
        // 每一步都真的跑到了（不是一条都没查就「通过」）；原第 10 步（6.4 的临时应答）已经随 M22 关闭删掉
        List<String> names = names(report);
        for (int step : List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 11)) {
            String prefix = "第 " + step + " 步";
            assertThat(names).as(prefix).anyMatch(n -> n.startsWith(prefix));
        }
        assertThat(names).noneMatch(n -> n.startsWith("第 10 步"));
        for (int step = 0; step <= 13; step++) {
            String prefix = "S" + step + " ";
            assertThat(names).as(prefix).anyMatch(n -> n.startsWith(prefix));
        }
        // 观战段在匹配段之前：S0–S11 的检查都排在「第 1 步 新号发 153」之前；S12 在第 9 步里，S13 在最后
        int firstMatchStep = names.indexOf(names.stream().filter(n -> n.startsWith("第 1 步")).findFirst().orElseThrow());
        assertThat(names.subList(0, firstMatchStep)).allMatch(n -> n.matches("S(\\d|1[01]) .*") || n.startsWith("S1 收到 177 到 S8"))
                .anyMatch(n -> n.startsWith("S11 "));
        assertThat(names.get(names.size() - 1)).startsWith("S13 指标 xm_match_spectate_evictions_total");
        // S12 分两半：等 ready 残留在切磋开局之前（第一条邀请收完尾、第二条邀请发起之前），163 在切磋局里、开自动之前
        assertThat(names.stream().filter(n -> n.startsWith("S12 "))).hasSize(2);
        int residue = names.indexOf(names.stream().filter(n -> n.startsWith("S12 切磋开局之前 A 发 153 → NOT_QUEUED")).findFirst().orElseThrow());
        assertThat(names.get(residue - 1)).startsWith("第 9 步 B 再应答同一条邀请 → 16012");
        assertThat(names.get(residue + 1)).startsWith("第 9 步 A 再次挑战 B → 受理");
        int s12 = names.indexOf(names.stream().filter(n -> n.startsWith("S12 切磋局里（开自动之前）A 发 163(0)")).findFirst().orElseThrow());
        assertThat(names.get(s12 - 1)).startsWith("第 9 步 A 在战斗中（开自动之前）C 挑战 A");
        assertThat(names.get(s12 + 1)).startsWith("第 9 步 都开自动");
        assertThat(report.items().get(residue).detail()).as("假服务端的 ready 残留缺省让 153 先回一次 READY").endsWith("（此前 1 次 READY：ready 残留）");

        Matcher line = OK_LINE.matcher(scenario.resultLine());
        assertThat(line.matches()).as(scenario.resultLine()).isTrue();
        long solo = Long.parseUnsignedLong(line.group(1));
        long duel = Long.parseUnsignedLong(line.group(4));
        long challenge = Long.parseUnsignedLong(line.group(5));
        long watched = Long.parseUnsignedLong(line.group(6));
        assertThat(List.of(solo, duel, challenge, watched)).as("四局各不相同、都非 0，按无符号十进制输出（假服务端的号在 uint64 上半区）")
                .doesNotHaveDuplicates().doesNotContain(0L).allMatch(id -> id < 0);
        assertThat(Long.compareUnsigned(watched, solo)).as("观战的那一局开得比匹配段的都早").isNegative();
        assertThat(line.group(2)).as("PVE 第一局在直连上收到的 139 条数").isEqualTo("1").isEqualTo(line.group(3));
        assertThat(line.group(7)).as("SB 在直连上收到的 158 条数（两个字段同值：观战帧只走直连）").isEqualTo("1").isEqualTo(line.group(8));
        assertThat(line.group(9)).as("开局清退成立").isEqualTo("1");
        assertThat(line.group(10)).as("假服务端的 ready 残留缺省让切磋开局之前的 153 先回一次 READY").isEqualTo("1");
        assertThat(scenario.resultLine()).doesNotContain("-");

        // 请求确实发了、没有多发
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.watchBattle())).as("163：S1、S3、S4、S6、S7、S8、S10、S11、S12 各一条（S12 在切磋局里只发一次，不重试）")
                .isEqualTo(9);
        assertThat(world.receivedCount(ids.queueStatus())).as("153：第 1 步、S6、第 3 步三条、第 4 步，S12 前半两条（先 READY 后 NOT_QUEUED）").isEqualTo(8);
        assertThat(world.receivedCount(ids.listWatchable())).as("164：S0、预清理（列表为空，一轮即停）、S2 两条、S10").isEqualTo(5);
        // 收尾的两条 148 发出后连接随即关闭，服务端稍后才处理到；另三条是第 3 步两条与 S6 一条
        assertThat(world.eventually(() -> world.receivedCount(ids.cancelQueue()) == 5)).as("148 共 " + world.receivedCount(ids.cancelQueue()) + " 条")
                .isTrue();
        assertThat(world.receivedCount(ids.requestTicket())).as("179：S5 的 SB；A、C、C（不存在的局）、A（旧局）").isEqualTo(5);
        assertThat(world.lockedPlayers()).as("每一局都打完并结算落地（含观战段的 X、Z、W）").isZero();
        assertThat(world.queuedTickets()).isZero();
        assertThat(world.observerCount()).as("观众都摘干净了：收尾、165、开局清退").isZero();
        assertThat(report.notes()).anyMatch(n -> n.equals("预清理：可观战列表已空（清了 0 轮）"));
        // 观战段的 SD、SB 刚打完 Z、W 就该下线了：先等到各自的大厅 150（结算落地）再发 LeaveGame，不带着战斗锁离场
        assertThat(world.leftWhileLocked()).as("发 LeaveGame 时还持着战斗锁（结算没落地）的次数").isZero();
        assertThat(report.notes()).noneMatch(n -> n.startsWith("观战段下线之前"));
    }

    // ---------------------------------------------------------------- 评审 R-1：同一个号的第二局 PVE 不断言胜负

    @Test
    void 同一个号的第二局PVE阵亡_第7步只要求打完_整条照样通过() {
        // 单人 PVE 打完的先后：观战段的 X、Z、W，然后是 A 的第一局、第二局
        world.soloPveLostAt = 5;
        BattleSmokeScenario scenario = scenario("r1a", world.admin());

        CheckReport report = scenario.run();

        // 真服务端上血量随第一局的结算带进第二局、种子每局随机，第二局阵亡是合法结果（2026-10-08 切片：第一局剩 261 血，第二局第 9 回合阵亡）
        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("第 7 步 第二局同样挂机（162 被受理）打到 150（胜 / 负 / 平都算打完");
            assertThat(item.detail()).startsWith("outcome=BATTLE_OUTCOME_SIDE_B_WIN rounds=" + FakeMatchWorld.PVE_ROUNDS);
        });
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_OK ");
    }

    @Test
    void 新号的第一局PVE阵亡_第6步仍然要求打赢_失败() {
        world.soloPveLostAt = 4;
        BattleSmokeScenario scenario = scenario("r1b", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 6 步 挂机打到 150：SIDE_A_WIN").contains("终局不是 SIDE_A_WIN",
                "外层 BATTLE_OUTCOME_SIDE_B_WIN");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=6-direct-fight ");
        assertThat(report.items()).as("第二局照常打赢").anyMatch(i -> i.passed() && i.name().startsWith("第 7 步 第二局同样挂机"));
    }

    @Test
    void 平局的1V1_评分不动但局数加1_照样通过() {
        world.pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        BattleSmokeScenario scenario = scenario("draw", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.items()).anySatisfy(item -> assertThat(item.name()).startsWith("第 8 步").contains("Δ 都是 0"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_OK ");
    }

    @Test
    void B队胜的1V1_A减16_B加16_照样通过() {
        world.pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        BattleSmokeScenario scenario = scenario("bwin", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("第 8 步").contains("|Δ| = 16");
            assertThat(item.detail()).contains("rating=1500.00 games=0 → rating=1484.00 games=1", "rating=1500.00 games=0 → rating=1516.00 games=1");
        });
    }

    @Test
    void 取消排队居然回了包_第3步失败_其余步骤照常跑完() {
        world.faults.add(Fault.REPLY_TO_CANCEL);
        BattleSmokeScenario scenario = scenario("f1", world.admin());

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(failed(report)).hasSize(2).allMatch(f -> f.startsWith("第 3 步 148(") && f.contains("收到了 148 的回包"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=3-queue-cancel reason=第 3 步 148(\"stale\")");
        assertThat(report.items()).as("后面的阶段没有被这一步拖垮").anyMatch(i -> i.passed() && i.name().startsWith("第 11 步"));
    }

    @Test
    void 大厅上先143后177_第4步失败() {
        world.faults.add(Fault.START_BEFORE_ASSIGNED);
        BattleSmokeScenario scenario = scenario("f2", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 4 步 大厅上先 177 后 143");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=4-pve-solo ");
    }

    @Test
    void 补签的票与177不同_第5步失败_随后退回177的票继续打() {
        world.faults.add(Fault.REISSUE_DIFFERENT_TICKET);
        BattleSmokeScenario scenario = scenario("f3", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 5 步 A 发 179").contains("逐字节相同");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=5-reissue ");
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 6 步 挂机打到 150"));
    }

    @Test
    void 文案用了全角逗号_第6步的逐字节核对失败() {
        world.faults.add(Fault.FULL_WIDTH_COMMA);
        BattleSmokeScenario scenario = scenario("f4", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 6 步 战斗中").contains("逐字节", "战斗尚未结束，无法排队");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=6-direct-fight ");
    }

    @Test
    void 上一局的ready票没被清掉_再排回16001_第7步失败() {
        world.faults.add(Fault.KEEP_READY_TICKET);
        BattleSmokeScenario scenario = scenario("f5", world.admin());

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(failed(report).get(0)).startsWith("第 7 步 打完立即再排：不得出现 16001").contains("error_code=16001");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=7-requeue ");
        // 这一阶段中断，后面的阶段各自记账（这里 A 的票还挡着 1V1；切磋不看票据，照常通过）
        assertThat(report.items()).anyMatch(i -> !i.passed() && i.name().startsWith("流程中断：PVE_SOLO"));
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 C 下线后 A 挑战 C"));
        assertThat(report.items()).anyMatch(i -> !i.passed() && i.name().contains("xm_match_gathers_total"));
    }

    @Test
    void 一对一把后入队的人放进0队_第8步的分队与评分都对不上() {
        world.faults.add(Fault.SWAP_DUEL_SIDES);
        BattleSmokeScenario scenario = scenario("f6", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("第 8 步 143 的 actors 里 A（锚点）在 0 队").contains("[1, 0]");
        assertThat(failed(report).get(1)).startsWith("第 8 步 收到 150 后").contains("期望 +16.00");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=8-pvp-rating ");
    }

    @Test
    void 拒绝切磋时应答者也收到了154_第9步失败() {
        world.faults.add(Fault.DECLINE_PUSH_TO_RESPONDER);
        BattleSmokeScenario scenario = scenario("f7", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 9 步 拒绝只推发起者").contains("B 也收到了");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=9-challenge ");
    }

    @Test
    void 切磋接受后开局失败_第9步中断_失败原因里写明又收到了154false() {
        world.faults.add(Fault.CHALLENGE_GATHER_FAILS);
        BattleSmokeScenario scenario = scenario("f9", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("流程中断：切磋（第 9 步，含 S12）").contains("没有收到参战票 177", "又收到了 154 false", "gather 失败");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=9-challenge reason=流程中断：切磋");
        // 接受之前的那些检查照常通过（含 S12 的前半：等 ready 残留在发起之前）；少了一局，指标那一步也对不上；
        // S12 的后半（切磋局里的 163）没有机会跑，S13 的 in_battle 随之对不上
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 接受后 A、B 都收到 154"));
        assertThat(report.items()).anyMatch(i -> !i.passed() && i.name().contains("xm_match_gathers_total"));
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("S12 切磋开局之前 A 发 153 → NOT_QUEUED"));
        assertThat(names(report)).noneMatch(n -> n.startsWith("S12 切磋局里"));
        assertThat(world.receivedCount(MatchSupport.Ids.resolve(world.registry()).watchBattle())).as("切磋局没开出来：S12 的 163 没有发").isEqualTo(8);
        assertThat(failed(report)).anyMatch(f -> f.startsWith("S13 指标 xm_match_watch_battle_total{outcome=\"in_battle\"} 本轮至少 + 1：0.0 → 0.0"));
        assertThat(scenario.resultLine()).doesNotContain("\n");
    }

    // ---------------------------------------------------------------- 评审 ROBOT-2 / ROBOT-3

    @Test
    void 开挂机的162被拒而这一局照样打到150_第6步单独一条检查失败_写明是靠超时打完的() {
        world.faults.add(Fault.FIRST_AUTO_REJECTED);
        // 162 的先后：观战段 S9 的 SA、S11 的 SD 与 SB，然后才是第 6 步的 A
        world.rejectAutoAt = 4;
        BattleSmokeScenario scenario = scenario("f10", world.admin());

        CheckReport report = scenario.run();

        // 150 本身没有任何毛病（SIDE_A_WIN、有回合、FIN）：只看终局包发现不了挂机没开成
        assertThat(failed(report)).singleElement().asString().startsWith("第 6 步 162 开挂机被受理")
                .contains("162 的应答带 error_message 1005", "挂机没有开成", "回合超时的默认行动");
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 6 步 挂机打到 150"));
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 6 步 150 之后服务端 FIN"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=6-direct-fight reason=第 6 步 162 开挂机被受理");
        // 只有那一条 162 被拒：别的局的挂机照常被受理
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("S9 放行屏障"));
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 7 步 第二局同样挂机（162 被受理）"));
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 8 步 都开自动（162 被受理）"));
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 都开自动（162 被受理）"));
    }

    @Test
    void 终局包里的settlement是别的局的_第6步与第7步的终局判据都咬住() {
        world.faults.add(Fault.SETTLEMENT_OF_OTHER_BATTLE);
        BattleSmokeScenario scenario = scenario("f11", world.admin());

        CheckReport report = scenario.run();

        // 外层的 outcome、settlement.player_id、回合数都对：基线多看的 settlement.battle_id 才咬得住
        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("第 6 步 挂机打到 150").contains("battle_id 对不上本局", "settlement ");
        // 第 7 步不断言胜负之后（评审 R-1），其余几项与第 6 步是同一套判据：同一个号的第二局也看 settlement 指向哪一局
        assertThat(failed(report).get(1)).startsWith("第 7 步 第二局同样挂机（162 被受理）打到 150（胜 / 负 / 平都算打完")
                .contains("battle_id 对不上本局", "settlement ").doesNotContain("终局不是");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=6-direct-fight ");
    }

    @Test
    void 切磋只走到挑战自己被拒_发起应答接受都没发生_第11步按出口断言的切磋指标不放过() {
        world.faults.add(Fault.CHALLENGE_TARGET_ALWAYS_OFFLINE);
        BattleSmokeScenario scenario = scenario("f12", world.admin());

        CheckReport report = scenario.run();

        List<String> failures = failed(report);
        assertThat(failures.get(0)).startsWith("第 9 步 A 挑战 B → 受理").contains("16008");
        assertThat(report.items()).as("「挑战自己 → 16007」照常通过：服务端为它记了一次 invite").anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 A 挑战自己"));
        // 不带标签求和时这一次 16007 就够「有增长」了；按 (stage, result) 看，三条都对不上
        assertThat(failures).anyMatch(f -> f.startsWith("第 11 步 指标 xm_match_challenges_total{stage=\"invite\",result=\"ok\"} 本轮至少 + 2") && f.contains("0.0 → 0.0"));
        assertThat(failures).anyMatch(f -> f.startsWith("第 11 步 指标 xm_match_challenges_total{stage=\"respond\",result=\"declined\"}"));
        assertThat(failures).anyMatch(f -> f.startsWith("第 11 步 指标 xm_match_challenges_total{stage=\"respond\",result=\"accepted\"}"));
        // 开局按模式拆开：少的是切磋那一局，PVE 与 1V1 各自照常通过
        assertThat(failures).anyMatch(f -> f.startsWith("第 11 步 指标 xm_match_gathers_total{mode=\"MATCH_MODE_PVP_CHALLENGE\",outcome=\"success\"}"));
        assertThat(report.items()).anyMatch(i -> i.passed()
                && i.name().startsWith("第 11 步 指标 xm_match_gathers_total{mode=\"MATCH_MODE_PVE_SOLO\",outcome=\"success\"} 本轮至少 + 2"));
        assertThat(report.items()).anyMatch(i -> i.passed()
                && i.name().startsWith("第 11 步 指标 xm_match_gathers_total{mode=\"MATCH_MODE_1V1\",outcome=\"success\"}"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=9-challenge ");
    }

    @Test
    void 没有运维令牌_登录之前就中止_不向服务端发任何请求() {
        BattleSmokeScenario scenario = scenario("f8", new MatchAdminClient(world.baseUrl(), null, FakeMatchWorld.TIMEOUT));

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(failed(report)).singleElement().asString().startsWith("流程中断").contains("XM_ADMIN_TOKEN");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=1-login reason=流程中断：没有运维令牌");
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.queueStatus())).isZero();
        assertThat(world.receivedCount(ids.listWatchable())).as("观战段也没有开始").isZero();
    }

    @Test
    void 管理端口通但进不了游戏_观战段在登录那一步中断_结果行是s0_login_匹配段的登录随后也失败() {
        // 一区不给分 gate：指标抓得到（第 1 步前半通过），四个观战号一个都进不去
        world.closeZone(1);
        BattleSmokeScenario scenario = scenario("f13", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("流程中断：观战（S0–S11）：assign-gate 未准入 code=404");
        assertThat(failed(report).get(1)).startsWith("流程中断：assign-gate 未准入");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s0-login reason=流程中断：观战（S0–S11）");
        assertThat(world.receivedCount(MatchSupport.Ids.resolve(world.registry()).listWatchable())).isZero();
    }

    @Test
    void 账号带bm标签_七个账号等长_不与battle_settle的bs撞() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("battle-smoke", "--run-tag", "x1"), Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.BATTLE_SMOKE);
        assertThat(BattleSmokeScenario.accountName(options.accountPrefix(), options.runTag(), "a")).isEqualTo("robot_java_bmx1_a");
        // 观战段的四个号用 w / x / y / z：与 a / b / c 等长，账号长度上限（RobotOptions 按 _a 算）对它们同样成立
        for (String suffix : List.of("b", "c", "w", "x", "y", "z")) {
            assertThat(BattleSmokeScenario.accountName("p_", "t", suffix)).hasSameSizeAs(BattleSmokeScenario.accountName("p_", "t", "a"));
        }
        assertThat(BattleSmokeScenario.accountName("p_", "t", "a")).isNotEqualTo(BattleSettleScenario.accountName("p_", "t", "a"));
        assertThat(BattleSmokeScenario.EXPECTED_SOLO_GATHERS).as("PVE 两局（第 4、7 步）").isEqualTo(2);
        assertThat(BattleSmokeScenario.EXPECTED_INVITES).as("被受理的切磋邀请：一条被拒绝、一条被接受").isEqualTo(2);
        assertThat(BattleSmokeScenario.EXPECTED_WATCH_OK).as("成功的 163：S3、S4、S8、S11").isEqualTo(4);
        assertThat(BattleSmokeScenario.EXPECTED_WATCH_NOT_FOUND).as("16018「不存在或已结束」：S7、S10").isEqualTo(2);
        assertThat(BattleSmokeScenario.EXPECTED_WATCH_QUEUED).as("16014：S1、S6").isEqualTo(2);
    }

    // ---------------------------------------------------------------- 观战段（批次 6.5）

    @Test
    void 索引里有五场已结束的残留_预清理三轮把它们剔干净_随机观战挑中的就是X() {
        world.seedFinishedBattles(5);
        BattleSmokeScenario scenario = scenario("s1", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        // 每条 163(0) 连着扑空两场、各剔一场：5 → 3 → 1 → 0
        assertThat(report.notes()).contains("预清理：可观战列表已空（清了 3 轮）");
        assertThat(names(report)).as("没有残留干扰：S8 挑中的是 X，观众数连 SB 在内是 2")
                .anyMatch(n -> n.equals("S8 SC 直连后握手应答紧跟 161 {observer_count = 2}（SB 与 SC）"));
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.watchBattle())).as("比干净的世界多三条预清理的 163(0)").isEqualTo(12);
        assertThat(world.receivedCount(ids.listWatchable())).as("预清理四条（5、3、1、0）").isEqualTo(8);
    }

    @Test
    void 残留太多清不完_预清理到轮数上限就停_只记观察不判失败() {
        world.seedFinishedBattles(5);
        Timing fast = FakeMatchWorld.FAST_SPECTATE;
        Timing oneRound = timing(fast.randomRetry(), fast.settleWait(), 1);
        BattleSmokeScenario scenario = scenario("s2", world.admin(), oneRound);

        CheckReport report = scenario.run();

        // 剩下三场残留：S8 的随机观战第一次扑空（16017），重试一次剔掉最后一场之后才轮到 X
        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.notes()).contains("预清理：1 轮之后列表仍不为空，残留场次太多；S8 的随机观战可能要多试几次");
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("S8 SC 发 163(0)");
            assertThat(item.detail()).contains("（此前 1 次 16017）");
        });
    }

    @Test
    void 列表里的残留剔不掉_预清理发现列表没变短就停_S8重试到上限后失败() {
        // 两场已结束的残留，而且服务端不剔除它们：随机观战每次都连着扑空这两场
        world.seedFinishedBattles(2);
        world.faults.add(Fault.ENDED_BATTLE_STAYS_LISTED);
        Timing fast = FakeMatchWorld.FAST_SPECTATE;
        Timing shortRetry = timing(Duration.ofMillis(300), fast.settleWait(), fast.precleanRounds());
        BattleSmokeScenario scenario = scenario("s20", world.admin(), shortRetry);

        CheckReport report = scenario.run();

        assertThat(report.notes()).contains("预清理：第 1 轮的 163(0) 没有让列表变短（还有 2 条），停止清理；S8 的随机观战可能要多试几次");
        assertThat(failed(report).get(0)).startsWith("S8 SC 发 163(0)（随机；16017 每 1 s 重试、上限 0 s）→ 成功，battle_id ≠ 0")
                .contains("tip=16017", "当前没有可观战的战斗");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s8-random ");
        assertThat(report.items()).as("预清理停了之后前面的步骤照常").anyMatch(i -> i.passed() && i.name().startsWith("S7 SC 发 163"));
        // 预清理没有空转 30 轮：164 只有 S0 一条、预清理两条（第二条发现没变短）、S2 两条
        assertThat(world.receivedCount(MatchSupport.Ids.resolve(world.registry()).listWatchable())).isEqualTo(5);
    }

    @Test
    void 切片上有别人的活战斗_预清理退出观战并停止清理_S8挑中的不是X也照常通过() {
        long stranger = world.seedLiveBattle();
        BattleSmokeScenario scenario = scenario("s3", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        String id = Long.toUnsignedString(stranger);
        assertThat(report.notes()).anyMatch(n -> n.startsWith("预清理第 1 轮：163(0) 挑中了一场活着的战斗 battle_id=" + id))
                .anyMatch(n -> n.startsWith("S8 随机观战挑中的不是 X 而是 battle_id=" + id))
                .noneMatch(n -> n.contains("退出 battle_id=" + id + " 的观战没有走完"));
        assertThat(names(report)).as("看的是别人的战斗：不断言 observer_count 的具体值").contains("S8 SC 直连后握手应答紧跟 161");
        assertThat(world.observerCount()).as("两次都经 165 退出了那场别人的战斗").isZero();
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_OK ").doesNotContain("spectate_battle_id=" + id + " ");
    }

    @Test
    void 开局公告先于登记进索引_S2重试两次之后在列表里看到X() {
        // 开局之后的头两条 164 还看不到这一局，第三条才看得到
        world.publishAfterLists = 2;
        BattleSmokeScenario scenario = scenario("s4", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.listWatchable())).as("比开局即公开时多两条：S2 的 164 {limit = 50} 重试了两次").isEqualTo(7);
    }

    @Test
    void 重看同一场时重推的177走了大厅_S4的三条检查都咬住() {
        world.faults.add(Fault.REWATCH_TICKET_TO_LOBBY);
        BattleSmokeScenario scenario = scenario("s5", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(3);
        assertThat(failed(report).get(0)).startsWith("S4 重推的 177 到达 SB 的直连").contains("s 内直连上没有 177");
        assertThat(failed(report).get(1)).startsWith("S4 随后 SB 的直连上再收到一条 161");
        assertThat(failed(report).get(2)).startsWith("S4 SB 的大厅连接上").contains("大厅上又来了一条 177");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s4-rewatch reason=S4 重推的 177");
    }

    @Test
    void 重看同一场时重推的177与第一条差了一个字段_S4的逐字节比对咬住() {
        world.faults.add(Fault.REWATCH_TICKET_DIFFERS);
        BattleSmokeScenario scenario = scenario("s21", world.admin());

        CheckReport report = scenario.run();

        // 177 确实到了直连、随后也有 161、大厅上没有多余的 177：只有「逐字节相同」这一条不成立
        assertThat(failed(report)).singleElement().isEqualTo("S4 重推的 177 到达 SB 的直连（它是活的，大厅公告直写），与第一条逐字节相同"
                + "（同一份 payload，HMAC 是确定性的）：两条 177 的字节不同");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s4-rewatch ");
    }

    @Test
    void 观众补签的票与177不同_S5失败_参战者的补签照常通过() {
        world.faults.add(Fault.OBSERVER_REISSUE_DIFFERS);
        BattleSmokeScenario scenario = scenario("s22", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("S5 SB 发 179(X) → 无错误，assignment 与 177 逐字节相同（role = 2）")
                .endsWith("tip=0 assignment=true role=2");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s5-reissue ");
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 5 步 A 发 179"));
    }

    @Test
    void 观众一去排队就被清退_S6失败_结果行指向S6() {
        world.faults.add(Fault.QUEUE_EVICTS_OBSERVER);
        BattleSmokeScenario scenario = scenario("s6", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("S6 只排队不清退（进 gather 才清退）").contains("push:");
        // 人已经被清出去了：S9 等到的是那条 REMOVED，不是 FINISHED
        assertThat(failed(report)).anyMatch(f -> f.startsWith("S9 SB 的直连上 ≥ 1 条 158") && f.contains("SPECTATE_END_REMOVED"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s6-queue-watching ");
    }

    @Test
    void 开局前没有清退观众_S11失败_指标也对不上() {
        world.faults.add(Fault.NO_EVICT_ON_GATHER);
        BattleSmokeScenario scenario = scenario("s7", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("S11 开局清退：SB 在 Z 的直连上").contains("s 内观战直连上没有 166");
        assertThat(failed(report).get(1)).startsWith("S13 指标 xm_match_spectate_evictions_total{reason=\"enter_gather\",result=\"removed\"}");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s11-evict ");
        assertThat(report.items()).as("清退没成，两局照样打完").anyMatch(i -> i.passed() && i.name().startsWith("S11 之后 SD、SB 都开自动"));
        assertThat(world.eventually(() -> world.lockedPlayers() == 0)).isTrue();
    }

    @Test
    void 已结束的战斗没有从索引里剔除_S10的列表里还有X() {
        world.faults.add(Fault.ENDED_BATTLE_STAYS_LISTED);
        BattleSmokeScenario scenario = scenario("s8", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("S10 SC 发 164：列表里没有 X");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s10-ended ");
    }

    @Test
    void 观众看到了技能冷却_S3的首帧检查先咬住() {
        world.faults.add(Fault.OBSERVER_SEES_COOLDOWNS);
        BattleSmokeScenario scenario = scenario("s9", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("S3 SB 的直连上第一帧是握手应答，然后才是 161").contains("技能冷却没有清空");
        assertThat(failed(report)).anyMatch(f -> f.startsWith("S9 SB 的直连上 ≥ 1 条 158") && f.contains("158 #"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s3-watch ");
    }

    @Test
    void 主动退出时也推了166_S8的165检查失败() {
        world.faults.add(Fault.STOP_WATCH_PUSHES_END);
        BattleSmokeScenario scenario = scenario("s10", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("S8 SC 在直连上发 165").contains("主动退出不该推 166");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s8-random ");
    }

    @Test
    void 观战帧回落到了大厅_S9数出一条() {
        world.faults.add(Fault.SPECTATE_TURN_TO_LOBBY);
        BattleSmokeScenario scenario = scenario("s11", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("S9 SB 的大厅连接上 139 / 158 / 161 / 166 的条数都是 0").endsWith("：1 条");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s9-finish ");
    }

    @Test
    void 列表摘要用了账号名_S2逐字段核对失败() {
        world.faults.add(Fault.SUMMARY_NAMES_ARE_ACCOUNTS);
        BattleSmokeScenario scenario = scenario("s12", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("S2 SC 发 164 {limit = 50}").contains("player_names=[robot_java_bms12_w]", "角色名");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s2-list ");
    }

    @Test
    void 观战成功的应答带了空的error_message_S3中止观战段_匹配段照常跑() {
        world.faults.add(Fault.WATCH_OK_WITH_EMPTY_TIP);
        BattleSmokeScenario scenario = scenario("s13", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("S3 SB 发 163(X) → 应答 battle_id = X、没有 error_message").contains("不得带 error_message 字段");
        assertThat(failed(report).get(1)).startsWith("流程中断：观战（S0–S11）");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s3-watch ");
        assertThat(report.items()).as("匹配段各自记账").anyMatch(i -> i.passed() && i.name().startsWith("第 8 步 收到 150 后"));
        assertThat(names(report)).noneMatch(n -> n.startsWith("S9 "));
    }

    @Test
    void 屏障期的战斗X提前结束_结果行是x_ended_early_写明当时的回合数() {
        world.faults.add(Fault.BARRIER_BATTLE_ENDS_EARLY);
        BattleSmokeScenario scenario = scenario("s14", world.admin());

        CheckReport report = scenario.run();

        // X 在 SA 直连补拉时就已经打完（150 排在 140 的应答之前）：S1 的 163 照常回 16014（票还在），进 S2 之前发现
        assertThat(failed(report).get(0)).startsWith("S2 之前战斗 X 就结束了（屏障期间 SA 的直连上出现了 150）").contains("X 打了 1 回合", "距 S1 收到 177");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s2-x-ended-early reason=S2 之前战斗 X 就结束了");
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("S1 SA 持着这一局的票"));
        assertThat(failed(report)).anyMatch(f -> f.startsWith("流程中断：观战（S0–S11）") && f.contains("战斗 X 在 S9 放行之前就结束了"));
        assertThat(names(report)).noneMatch(n -> n.startsWith("S9 ") || n.startsWith("S11 "));
        assertThat(report.items()).as("匹配段不受影响").anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 C 下线后 A 挑战 C"));
    }

    @Test
    void 屏障期用时超出预算_只在第一次超出的那一步记一条budget_后面照常跑完() {
        // 预算取负数：不管 S1 用了多少毫秒（哪怕不足 1 ms）都算超出，用例不看墙钟
        BattleSmokeScenario scenario = scenario("s15", world.admin(), timing(Duration.ofMillis(-1), FakeMatchWorld.FAST_SPECTATE.readyResidue()));

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("S1 收到 177 到 S1 结束的合计用时超出预算").contains("超出预算 -1 ms");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s1-budget ");
        assertThat(names(report)).as("超预算不中止：X 多半还活着").anyMatch(n -> n.startsWith("S11 之后 SD、SB 都开自动"))
                .noneMatch(n -> n.startsWith("S1 收到 177 到 S8 结束的合计用时在"));
    }

    @Test
    void 上一局的ready票在切磋之前就过期了_153头一次就是NOT_QUEUED_s12_ready_residue是0() {
        world.readyResiduePolls = 0;
        BattleSmokeScenario scenario = scenario("s16", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(scenario.resultLine()).endsWith(" removed_ok=1 s12_ready_residue=0");
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.queueStatus())).as("S12 前半只问了一次").isEqualTo(7);
        assertThat(world.receivedCount(ids.watchBattle())).as("S12 后半只发了一条").isEqualTo(9);
    }

    @Test
    void ready残留过了窗口还在_S12前半失败_切磋局里的163只发一次_回16014再记一条() {
        world.readyResiduePolls = Integer.MAX_VALUE;
        // 窗口极短：第 8 步收到 177 之后 1 ms 就「必然过期」，此后 153 再回 READY 即判错
        BattleSmokeScenario scenario = scenario("s17", world.admin(), timing(FakeMatchWorld.FAST_SPECTATE.liveBudget(), Duration.ofMillis(1)));

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("S12 切磋开局之前 A 发 153 → NOT_QUEUED")
                .contains("A 的票还是 READY", "早该过期了", "实得 state=QUEUE_STATE_READY", "（此前 1 次 READY：ready 残留）");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s12-ready-residue ").doesNotContain(" s12_ready_residue=");
        // 票还在：切磋局里那一条 163 回 16014。不再按过渡态重试——只发一次，直接判失败
        assertThat(failed(report).get(1)).startsWith("S12 切磋局里（开自动之前）A 发 163(0) → {16015, 战斗尚未结束,无法观战}")
                .contains("tip=16014", "匹配中无法观战", "回的是 16014：A 还持着票");
        assertThat(world.receivedCount(MatchSupport.Ids.resolve(world.registry()).watchBattle())).as("S12 的 163 没有重试").isEqualTo(9);
        assertThat(report.items()).as("S12 不通过也不拖垮切磋：两人照常开自动打完").anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 都开自动"));
        assertThat(failed(report)).anyMatch(f -> f.startsWith("S13 指标 xm_match_watch_battle_total{outcome=\"in_battle\"}"));
    }

    @Test
    void ready残留还在而切磋局只有一个回合_残留在开局之前就等掉了_整条照样通过() {
        // 真服务端上的情形（评审 R-2）：切磋双方带着上一局 1V1 的结算血量进场，不开自动的切磋局可以 6 s 就结束——
        // 在切磋局里按 16014 重试等 ready 票据过期（最长几十秒）是等不起的
        world.readyResiduePolls = 3;
        world.faults.add(Fault.CHALLENGE_ENDS_AFTER_FIRST_WATCH);
        BattleSmokeScenario scenario = scenario("s18", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_OK ").endsWith(" removed_ok=1 s12_ready_residue=1");
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("S12 切磋开局之前 A 发 153 → NOT_QUEUED");
            assertThat(item.detail()).isEqualTo("state=QUEUE_STATE_NOT_QUEUED estimated_wait_seconds=0 queued_seconds=0（此前 3 次 READY：ready 残留）");
        });
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.queueStatus())).as("比缺省的世界多问了两次 153").isEqualTo(10);
        assertThat(world.receivedCount(ids.watchBattle())).as("切磋局里只来得及发一条 163，它就是 16015").isEqualTo(9);
        // 这一局是自己打完的：开自动的 162 回的是拒绝，但排在 150 之后——不算挂机没开成；150 与 FIN 照常核对
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 都开自动"));
        assertThat(world.eventually(() -> world.lockedPlayers() == 0)).isTrue();
    }

    // ---------------------------------------------------------------- 观战段下线之前等结算落地（评审 R-4）

    @Test
    void S11两局的结算一直没落到大厅_观战段等到上限后照常下线_只记观察不判失败() {
        world.faults.add(Fault.NO_LOBBY_END);
        Timing fast = FakeMatchWorld.FAST_SPECTATE;
        BattleSmokeScenario scenario = scenario("s23", world.admin(), timing(fast.randomRetry(), Duration.ofMillis(300), fast.precleanRounds()));

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_OK ");
        // 等的是 S11 里真正打完的两局：SD 的 Z、SB 的 W（X 在 S9 就结束了，不等）
        assertThat(report.notes().stream().filter(n -> n.startsWith("观战段下线之前没有等到 "))).hasSize(2)
                .anyMatch(n -> n.startsWith("观战段下线之前没有等到 SD 的 battle_id="))
                .anyMatch(n -> n.startsWith("观战段下线之前没有等到 SB 的 battle_id="))
                .allMatch(n -> n.contains("的大厅 150") && n.contains("照常下线"));
    }

    @Test
    void 第一条开挂机的162被拒_它是观战段S9的那一条_S9的检查咬住() {
        world.faults.add(Fault.FIRST_AUTO_REJECTED);
        BattleSmokeScenario scenario = scenario("s19", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("S9 放行屏障：SA 开自动（162 被受理）打到 150 后 FIN")
                .contains("挂机没有开成");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=s9-finish ");
    }
}
