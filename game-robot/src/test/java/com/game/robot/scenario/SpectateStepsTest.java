package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleActorState;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.WatchBattleResponse;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.MatchSupport.Bot;
import com.game.robot.scenario.MatchSupport.Started;
import com.game.robot.scenario.SpectateSteps.Ended;
import com.game.robot.scenario.SpectateSteps.Stopped;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 观战件的纯判据（spectate-spec §3、§10.7）：163 / 164 应答的形状与文案逐字节、观众票、观众直连开头两帧、收尾与 165 的帧序列。
 * 每条都给出「对的过、错的不过」两面；带网络的几个方法（等观众票、凭票直连、等 166、发 165）由 {@code BattleSmokeScenarioTest} /
 * {@code BattleCrossZoneScenarioTest} 对着本机假服务端整条跑时覆盖。
 */
class SpectateStepsTest {

    private static final BattleIds IDS = BattleIds.resolve(MessageIdRegistry.loadFromClasspath());
    /** uint64 上半区的战斗号 / 玩家号。 */
    private static final long X = 0x9000_0000_0000_0001L;
    private static final long OBSERVER = 0x8000_0000_0000_0007L;
    private static final String SIGNATURE = "0123456789abcdef".repeat(4);
    private static final long NOW = 1_760_000_000_000L;

    // ---------------------------------------------------------------- 码、文案、时限

    @Test
    void 六个观战码取自导表枚举_九条文案里观战的七条逐字节_逗号是半角() {
        assertThat(List.of(SpectateSteps.TIP_QUEUED, SpectateSteps.TIP_IN_BATTLE, SpectateSteps.TIP_ALREADY_WATCHING, SpectateSteps.TIP_NO_BATTLE,
                SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TIP_OFFLINE)).containsExactly(16014, 16015, 16016, 16017, 16018, 16019);
        assertThat(SpectateSteps.TEXT_QUEUED).isEqualTo("匹配中无法观战");
        assertThat(SpectateSteps.TEXT_ALREADY_WATCHING).isEqualTo("已在观战另一场战斗");
        assertThat(SpectateSteps.TEXT_NO_BATTLE).isEqualTo("当前没有可观战的战斗");
        assertThat(SpectateSteps.TEXT_NOT_FOUND).isEqualTo("该战斗不存在或已结束");
        assertThat(SpectateSteps.TEXT_NOT_WATCHABLE).isEqualTo("该战斗当前无法观战");
        for (String text : List.of(SpectateSteps.TEXT_IN_BATTLE, SpectateSteps.TEXT_OFFLINE)) {
            assertThat(text).contains(",").doesNotContain("，");
        }
        // 逐字节：「战斗尚未结束,无法观战」= 6 个汉字 + 1 个 ASCII 逗号 + 4 个汉字；「会话不在线,无法观战」= 5 + 1 + 4
        assertThat(SpectateSteps.TEXT_IN_BATTLE.getBytes(StandardCharsets.UTF_8)).hasSize(6 * 3 + 1 + 4 * 3);
        assertThat(SpectateSteps.TEXT_OFFLINE.getBytes(StandardCharsets.UTF_8)).hasSize(5 * 3 + 1 + 4 * 3);
        assertThat(SpectateSteps.TEXT_NOT_FOUND).as("16018 的这一条与 179 的「1005 该战斗不存在或已结束」同文不同码").isEqualTo(BattleSmokeChecks.TEXT_BATTLE_GONE);
    }

    @Test
    void 标准时限_等观众票与首帧各15秒_等收尾120秒_屏障期30秒_残留窗口62秒_等结算落地25秒_预清理30轮() {
        SpectateSteps.Timing standard = SpectateSteps.Timing.STANDARD;
        assertThat(standard.ticketTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(standard.firstFrameTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(standard.endTimeout()).isEqualTo(Duration.ofSeconds(120)).isEqualTo(MatchSupport.BATTLE_END_TIMEOUT);
        assertThat(standard.publishRetry()).isEqualTo(Duration.ofSeconds(5));
        assertThat(standard.randomRetry()).isEqualTo(Duration.ofSeconds(10));
        assertThat(standard.evictTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(standard.liveBudget()).isEqualTo(Duration.ofSeconds(30));
        assertThat(standard.readyResidue()).isEqualTo(Duration.ofSeconds(62));
        assertThat(standard.settleWait()).as("等大厅 150 的上限与跨区场景的 Z7 同值").isEqualTo(Duration.ofSeconds(25))
                .isEqualTo(BattleCrossZoneScenario.LOBBY_END_TIMEOUT);
        assertThat(standard.precleanRounds()).isEqualTo(30);
        assertThat(standard.stopTimeout()).as("165 的应答与 FIN：battle 1.5 s 内 FIN，留足余量").isGreaterThanOrEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void 屏障期的预算有判别力_比战斗X的最短寿命短一个回合以上_又放得下各步正常的等待() {
        // 评审 R-3：预算 60 s 时，新号单人 PVE 实测 7–10 回合、不开自动只活 42–60 s——脚本真慢的时候先触发的总是「X 提前结束」，
        // 预算那条检查永远报不出来
        long budgetMs = SpectateSteps.Timing.STANDARD.liveBudget().toMillis();
        long shortestLifeMs = SpectateSteps.SOLO_PVE_MIN_ROUNDS * SpectateSteps.ROUND_TIMEOUT_MS;
        assertThat(shortestLifeMs).as("新号单人 PVE 最少 7 回合 × 6 s").isEqualTo(42_000);
        assertThat(budgetMs + SpectateSteps.ROUND_TIMEOUT_MS).as("预算用完时 X 至少还剩一个回合：超预算先于 X 结束报出来")
                .isLessThanOrEqualTo(shortestLifeMs);
        // 另一头：S2 的公开重试（5 s）与 S8 的随机观战重试（10 s）都耗满，加上两个 1 s 的静默窗口，仍在预算内——预算不该因为这些合法的慢而报
        SpectateSteps.Timing standard = SpectateSteps.Timing.STANDARD;
        assertThat(budgetMs).isGreaterThan(standard.publishRetry().plus(standard.randomRetry()).plus(MatchSupport.SILENCE.multipliedBy(2)).toMillis()
                + 6_000);
    }

    // ---------------------------------------------------------------- 163 的应答

    private static WatchBattleResponse rejected(int code, String text) {
        return WatchBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(code).addParameters(text)).build();
    }

    @Test
    void 观战成功_只带battle_id_带了error_message字段哪怕是空的也不算() {
        WatchBattleResponse ok = WatchBattleResponse.newBuilder().setBattleId(X).build();
        assertThat(SpectateSteps.accepted(ok)).isTrue();
        assertThat(SpectateSteps.acceptedProblem(ok, X)).isNull();
        assertThat(SpectateSteps.acceptedProblem(ok, 0)).as("随机观战：任何非 0 的 battle_id").isNull();
        assertThat(SpectateSteps.describe(ok)).isEqualTo("battle_id=10376293541461622785 无 error_message");

        assertThat(SpectateSteps.acceptedProblem(ok, X + 1)).isEqualTo("battle_id=10376293541461622785（期望 10376293541461622786）");
        WatchBattleResponse emptyTip = ok.toBuilder().setErrorMessage(TipInfoMessage.getDefaultInstance()).build();
        assertThat(SpectateSteps.accepted(emptyTip)).isFalse();
        assertThat(SpectateSteps.acceptedProblem(emptyTip, X)).contains("不得带 error_message 字段");
        assertThat(SpectateSteps.acceptedProblem(WatchBattleResponse.getDefaultInstance(), 0)).contains("必须回填 battle_id");
        assertThat(SpectateSteps.accepted(WatchBattleResponse.getDefaultInstance())).isFalse();
        WatchBattleResponse queued = rejected(16014, "匹配中无法观战");
        assertThat(SpectateSteps.accepted(queued)).isFalse();
        assertThat(SpectateSteps.acceptedProblem(queued, X)).isEqualTo("期望成功，实得 battle_id=0 tip=16014 parameters=[匹配中无法观战]");
    }

    @Test
    void 观战被拒_码与文案逐字节_battle_id必须是0() {
        WatchBattleResponse gone = rejected(16018, "该战斗不存在或已结束");
        assertThat(SpectateSteps.rejectedProblem(gone, SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_FOUND)).isNull();
        assertThat(SpectateSteps.isRejection(gone, SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_FOUND)).isTrue();
        // 同一个码的另一种文案：16018 有两条，不能混
        assertThat(SpectateSteps.rejectedProblem(gone, SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_WATCHABLE))
                .contains("期望恰好一项「该战斗当前无法观战」");
        assertThat(SpectateSteps.isRejection(gone, SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_WATCHABLE)).isFalse();
        assertThat(SpectateSteps.rejectedProblem(gone, SpectateSteps.TIP_NO_BATTLE, SpectateSteps.TEXT_NO_BATTLE)).contains("tip=16018（期望 16017）");
        // 全角逗号
        assertThat(SpectateSteps.rejectedProblem(rejected(16015, "战斗尚未结束，无法观战"), SpectateSteps.TIP_IN_BATTLE, SpectateSteps.TEXT_IN_BATTLE))
                .contains("逐字节");
        // 拒绝了却带着 battle_id
        assertThat(SpectateSteps.rejectedProblem(gone.toBuilder().setBattleId(X).build(), SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_FOUND))
                .isEqualTo("拒绝的应答 battle_id 应为 0，实得 10376293541461622785");
        // 成功的应答当然不是拒绝
        assertThat(SpectateSteps.isRejection(WatchBattleResponse.newBuilder().setBattleId(X).build(), SpectateSteps.TIP_NO_BATTLE,
                SpectateSteps.TEXT_NO_BATTLE)).isFalse();
    }

    // ---------------------------------------------------------------- 164 的应答

    private static BattleWatchSummary summary(long battleId, long createdAtMs) {
        return BattleWatchSummary.newBuilder().setBattleId(battleId).setModeValue(4).setBattleConfigId(1).addPlayerNames("侠客一")
                .setCreatedAtMs(createdAtMs).build();
    }

    private static ListWatchableBattlesResponse list(BattleWatchSummary... summaries) {
        return ListWatchableBattlesResponse.newBuilder().addAllBattles(List.of(summaries)).build();
    }

    @Test
    void limit的收口_0是20_超过50是50_其余原样() {
        assertThat(SpectateSteps.listCap(0)).isEqualTo(20);
        assertThat(SpectateSteps.listCap(1)).isEqualTo(1);
        assertThat(SpectateSteps.listCap(6)).as("Unity 面板一次拉 6 条").isEqualTo(6);
        assertThat(SpectateSteps.listCap(50)).isEqualTo(50);
        assertThat(SpectateSteps.listCap(51)).isEqualTo(50);
        assertThat(SpectateSteps.listCap(Integer.MAX_VALUE)).isEqualTo(50);
        assertThatThrownBy(() -> SpectateSteps.listCap(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 列表的形状_空列表与降序的列表都过_同分不算乱序() {
        assertThat(SpectateSteps.listProblem(ListWatchableBattlesResponse.getDefaultInstance(), 0, NOW)).isNull();
        assertThat(SpectateSteps.listProblem(list(summary(X + 2, NOW - 1_000), summary(X + 1, NOW - 1_000), summary(X, NOW - 300_000)), 0, NOW)).isNull();
        assertThat(SpectateSteps.describe(list(summary(X, NOW)))).isEqualTo("1 条 [10376293541461622785]");
        assertThat(SpectateSteps.describe(ListWatchableBattlesResponse.getDefaultInstance())).isEqualTo("0 条");
    }

    @Test
    void 列表的形状_条数超过收口_升序_战斗号为0或重复_都报出来() {
        List<BattleWatchSummary> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(summary(X + i, NOW - i));
        }
        ListWatchableBattlesResponse tooMany = ListWatchableBattlesResponse.newBuilder().addAllBattles(twentyOne).build();
        assertThat(SpectateSteps.listProblem(tooMany, 0, NOW)).isEqualTo("回了 21 条，超过 limit=0 收口后的 20 条");
        assertThat(SpectateSteps.listProblem(tooMany, 50, NOW)).isNull();
        assertThat(SpectateSteps.listProblem(tooMany, 60, NOW)).as("limit 超过 50 按 50 收口，21 条没超").isNull();
        assertThat(SpectateSteps.listProblem(list(summary(X, NOW), summary(X + 1, NOW)), 1, NOW)).contains("回了 2 条，超过 limit=1 收口后的 1 条");

        assertThat(SpectateSteps.listProblem(list(summary(X, NOW - 2_000), summary(X + 1, NOW - 1_000)), 0, NOW))
                .contains("第 2 条", "比前一条的 " + (NOW - 2_000) + " 大（应按 created_at_ms 降序）");
        assertThat(SpectateSteps.listProblem(list(summary(0, NOW)), 0, NOW)).contains("第 1 条（battle_id=0） battle_id 为 0");
        assertThat(SpectateSteps.listProblem(list(summary(X, NOW), summary(X, NOW)), 0, NOW)).contains("第 2 条（battle_id=10376293541461622785） 重复出现");
    }

    @Test
    void 列表的时间窗_过期分界两头各留5秒_恰在边上算过_越过一毫秒就报() {
        // 服务端按 Redis TIME − 360 s 判过期；robot 的墙钟可以比它快或慢几秒
        assertThat(SpectateSteps.listProblem(list(summary(X, NOW - 365_000)), 0, NOW)).isNull();
        assertThat(SpectateSteps.listProblem(list(summary(X, NOW - 365_001)), 0, NOW))
                .contains("不在 [now − 365 s, now + 5 s]", "相对 now -365001 ms", "过期的场次不该出现在列表里");
        assertThat(SpectateSteps.listProblem(list(summary(X, NOW + 5_000)), 0, NOW)).isNull();
        assertThat(SpectateSteps.listProblem(list(summary(X, NOW + 5_001)), 0, NOW)).contains("相对 now 5001 ms");
        // created_at_ms 没填（0）：远在窗外
        assertThat(SpectateSteps.listProblem(list(summary(X, 0)), 0, NOW)).contains("created_at_ms=0 不在");
        assertThat(SpectateSteps.STALE_MS).isEqualTo(360_000);
        assertThat(SpectateSteps.LIST_CLOCK_SLACK_MS).isEqualTo(5_000);
    }

    @Test
    void 在列表里找一场_摘要逐字段_模式按数值比_名字按成员顺序_时刻在发起与收到177之间() {
        ListWatchableBattlesResponse listed = list(summary(X + 1, NOW), summary(X, NOW - 100));
        assertThat(SpectateSteps.find(listed, X)).get().extracting(BattleWatchSummary::getCreatedAtMs).isEqualTo(NOW - 100);
        assertThat(SpectateSteps.find(listed, X + 2)).isEmpty();

        long sent = NOW - 400;
        long assigned = NOW - 50;
        BattleWatchSummary x = summary(X, NOW - 100);
        assertThat(SpectateSteps.summaryProblem(x, 4, 1, List.of("侠客一"), sent, assigned)).isNull();
        assertThat(SpectateSteps.summaryProblem(x, 3, 1, List.of("侠客一"), sent, assigned)).isEqualTo("mode=4（期望 3）");
        assertThat(SpectateSteps.summaryProblem(x, 4, 0, List.of("侠客一"), sent, assigned)).isEqualTo("battle_config_id=1（期望 0）");
        assertThat(SpectateSteps.summaryProblem(x, 4, 1, List.of("robot_java_bmx1_w"), sent, assigned))
                .contains("player_names=[侠客一]（期望 [robot_java_bmx1_w]", "角色名");
        // 两个人的局：顺序也要对
        BattleWatchSummary duel = x.toBuilder().addPlayerNames("侠客二").build();
        assertThat(SpectateSteps.summaryProblem(duel, 4, 1, List.of("侠客一", "侠客二"), sent, assigned)).isNull();
        assertThat(SpectateSteps.summaryProblem(duel, 4, 1, List.of("侠客二", "侠客一"), sent, assigned)).contains("按成员顺序");
        // 契约里没有的模式值也原样下发
        assertThat(SpectateSteps.summaryProblem(x.toBuilder().setModeValue(99).build(), 99, 1, List.of("侠客一"), sent, assigned)).isNull();
        // 时刻：[发 157 − 5 s, 收到 177 + 5 s]
        assertThat(SpectateSteps.summaryProblem(x.toBuilder().setCreatedAtMs(sent - 5_000).build(), 4, 1, List.of("侠客一"), sent, assigned)).isNull();
        assertThat(SpectateSteps.summaryProblem(x.toBuilder().setCreatedAtMs(sent - 5_001).build(), 4, 1, List.of("侠客一"), sent, assigned))
                .contains("created_at_ms", "相对发 157 -5001 ms");
        assertThat(SpectateSteps.summaryProblem(x.toBuilder().setCreatedAtMs(assigned + 5_001).build(), 4, 1, List.of("侠客一"), sent, assigned))
                .contains("created_at_ms");
        // 秒而不是毫秒
        assertThat(SpectateSteps.summaryProblem(x.toBuilder().setCreatedAtMs(NOW / 1000).build(), 4, 1, List.of("侠客一"), sent, assigned))
                .contains("必须是 Unix 毫秒");
    }

    // ---------------------------------------------------------------- 观众票

    private static BattleAssignedS2C ticket(long battleId, long playerId, eBattleTicketRole role, long expireAtMs) {
        return BattleAssignedS2C.newBuilder().setBattleId(battleId).setHost("127.0.0.1").setPort(12000)
                .setTokenPayload(BattleTicketPayload.newBuilder().setBattleId(battleId).setPlayerId(playerId).setBattleNodeId(1)
                        .setBattleInstanceId("i-1").setExpireAtMs(expireAtMs).setRole(role).build().toByteString())
                .setTokenSignature(ByteString.copyFromUtf8(SIGNATURE)).setExpireAtMs(expireAtMs).setRole(role).build();
    }

    private static Received lobby(int index, int messageId, long requestId, Message body) {
        return new Received(index, 0, MessageContent.newBuilder().setMessageId(messageId).setId(requestId).setSerializedMessage(body.toByteString())
                .build());
    }

    @Test
    void 大厅上认观众票_只认推送形状的177且角色是观众_指定了战斗号就还要对上() {
        BattleAssignedS2C observer = ticket(X, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER, NOW + 300_000);
        assertThat(SpectateSteps.observerTicket(lobby(0, IDS.battleAssigned(), 0, observer), IDS, X)).isEqualTo(observer);
        assertThat(SpectateSteps.observerTicket(lobby(0, IDS.battleAssigned(), 0, observer), IDS, 0)).as("随机观战：哪一场的都接").isEqualTo(observer);
        assertThat(SpectateSteps.observerTicket(lobby(0, IDS.battleAssigned(), 0, observer), IDS, X + 1)).isNull();
        // 参战票、应答形状、别的号、battle_id 为 0、解析不了
        assertThat(SpectateSteps.observerTicket(lobby(0, IDS.battleAssigned(), 0,
                ticket(X, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, NOW)), IDS, X)).isNull();
        assertThat(SpectateSteps.observerTicket(lobby(0, IDS.battleAssigned(), 7, observer), IDS, X)).isNull();
        assertThat(SpectateSteps.observerTicket(lobby(0, IDS.battleStart(), 0, observer), IDS, X)).isNull();
        assertThat(SpectateSteps.observerTicket(lobby(0, IDS.battleAssigned(), 0,
                ticket(0, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER, NOW)), IDS, 0)).isNull();
        Received garbage = new Received(0, 0, MessageContent.newBuilder().setMessageId(IDS.battleAssigned())
                .setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xff, (byte) 0xff})).build());
        assertThat(SpectateSteps.observerTicket(garbage, IDS, 0)).isNull();
    }

    @Test
    void 观众票的形状_角色期限签名与payload都对才过_期限要与参战者的票同值() {
        long expire = NOW + 300_000;
        BattleAssignedS2C good = ticket(X, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER, expire);
        assertThat(SpectateSteps.observerTicketProblem(good, X, OBSERVER, expire)).isNull();
        assertThat(SpectateSteps.observerTicketProblem(good, X, OBSERVER, 0)).as("不知道房间期限（看的是别人的战斗）：只要求非 0").isNull();

        assertThat(SpectateSteps.observerTicketProblem(good, X, OBSERVER, expire + 1))
                .isEqualTo("expire_at_ms=" + expire + "（期望房间期限 " + (expire + 1) + "，与参战者的 177 同值）");
        assertThat(SpectateSteps.observerTicketProblem(good, X + 1, OBSERVER, expire)).contains("battle_id=10376293541461622785（期望 10376293541461622786）");
        assertThat(SpectateSteps.observerTicketProblem(good, X, OBSERVER + 1, expire)).contains("没有指向本人（9223372036854775816）本局的观众票");
        assertThat(SpectateSteps.observerTicketProblem(ticket(X, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, expire), X, OBSERVER, expire))
                .contains("role=1（期望 2 OBSERVER）", "payload");
        assertThat(SpectateSteps.observerTicketProblem(good.toBuilder().setTokenSignature(ByteString.copyFromUtf8(SIGNATURE.toUpperCase())).build(),
                X, OBSERVER, expire)).isEqualTo("token_signature 不是 64 位小写 hex");
        assertThat(SpectateSteps.observerTicketProblem(good.toBuilder().clearHost().build(), X, OBSERVER, expire)).contains("通告地址 :12000 不可用");
        assertThat(SpectateSteps.observerTicketProblem(good.toBuilder().setPort(70000).build(), X, OBSERVER, expire)).contains("不可用");
        assertThat(SpectateSteps.observerTicketProblem(good.toBuilder().setExpireAtMs(0).build(), X, OBSERVER, 0)).contains("expire_at_ms=0（期望非 0）");
        // 外层与 payload 的期限不一致
        assertThat(SpectateSteps.observerTicketProblem(good.toBuilder().setExpireAtMs(expire + 5).build(), X, OBSERVER, expire + 5)).contains("payload");
        assertThat(SpectateSteps.observerTicketProblem(good.toBuilder().setTokenPayload(ByteString.copyFrom(new byte[] {(byte) 0xff})).build(),
                X, OBSERVER, expire)).contains("token_payload 解析不了");
    }

    // ---------------------------------------------------------------- 观众直连

    private static BattleFrame verifyOk(int index, long battleId) {
        return new BattleFrame(index, index, BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(battleId).build(), null, null);
    }

    private static BattleFrame push(int index, int messageId, Message body) {
        return new BattleFrame(index, index, null, MessageContent.newBuilder().setMessageId(messageId).setSerializedMessage(body.toByteString()).build(),
                null);
    }

    private static BattleFrame reply(int index, int messageId, long requestId, Message body) {
        return new BattleFrame(index, index, null, MessageContent.newBuilder().setMessageId(messageId).setId(requestId)
                .setSerializedMessage(body.toByteString()).build(), null);
    }

    private static BattleFrame closed(int index, String by) {
        return new BattleFrame(index, index, null, null, by);
    }

    private static BattleStateS2C state(long battleId, boolean cooldowns, boolean items) {
        BattleActorState.Builder actor = BattleActorState.newBuilder().setActorId(1).setName("侠客一");
        if (cooldowns) {
            actor.putSkillCooldownRounds(1001, 2);
        }
        BattleStateS2C.Builder state = BattleStateS2C.newBuilder().setBattleId(battleId).addActors(actor)
                .addActors(BattleActorState.newBuilder().setActorId(2));
        if (items) {
            state.addSelfItems(BattleItemEntry.newBuilder().setItemTableId(10).setCount(1));
        }
        return state.build();
    }

    private static BattleFrame first161(int index, long battleId, int observers) {
        return push(index, IDS.spectateState(), SpectateStateS2C.newBuilder().setState(state(battleId, false, false)).setObserverCount(observers).build());
    }

    private static BattleFrame turn158(int index, long battleId) {
        return push(index, IDS.spectateTurnResult(), TurnResultS2C.newBuilder().setBattleId(battleId).setRoundIndex(index)
                .setState(state(battleId, false, false)).build());
    }

    private static BattleFrame end166(int index, long battleId, eSpectateEndReason reason, eBattleOutcome outcome) {
        return push(index, IDS.spectateEnd(), SpectateEndS2C.newBuilder().setBattleId(battleId).setReason(reason).setOutcome(outcome).build());
    }

    @Test
    void 观众直连的开头_握手应答之后紧跟161_观众数与本局对上_状态是观众版() {
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), first161(1, X, 1)), IDS, X, 1)).isNull();
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), first161(1, X, 2), turn158(2, X)), IDS, X, 2)).isNull();
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), first161(1, X, 7)), IDS, X, -1)).as("看别人的战斗：只要求 ≥ 1").isNull();

        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), first161(1, X, 2)), IDS, X, 1)).isEqualTo("observer_count=2（期望 1）");
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), first161(1, X, 0)), IDS, X, -1)).isEqualTo("observer_count=0（期望 ≥ 1）");
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), first161(1, X + 1, 1)), IDS, X, 1)).contains("161 的 state.battle_id");
        // 161 之前夹了别的帧（哪怕随后就是 161）
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), turn158(1, X), first161(2, X, 1)), IDS, X, 1))
                .startsWith("握手应答之后应紧跟 161，实际 [verify-ok:").contains("push:" + IDS.spectateTurnResult());
        // 只有握手应答、握手失败、第一帧不是握手应答
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X)), IDS, X, 1)).startsWith("直连的第一帧应是成功的握手应答");
        BattleFrame denied = new BattleFrame(0, 0, BattleTokenVerifyResponse.newBuilder().setError("battle not found").build(), null, null);
        assertThat(SpectateSteps.firstFrameProblem(List.of(denied, closed(1, BattleFrame.FIN)), IDS, X, 1)).contains("verify-fail:battle not found");
        assertThat(SpectateSteps.firstFrameProblem(List.of(first161(0, X, 1), verifyOk(1, X)), IDS, X, 1)).startsWith("直连的第一帧应是成功的握手应答");
        // 没裁剪
        BattleFrame leaky = push(1, IDS.spectateState(), SpectateStateS2C.newBuilder().setState(state(X, true, true)).setObserverCount(1).build());
        assertThat(SpectateSteps.firstFrameProblem(List.of(verifyOk(0, X), leaky), IDS, X, 1))
                .isEqualTo("161 带了 1 项 self_items（观众不该看到任何人的道具）；1 个 actor 的技能冷却没有清空");
    }

    @Test
    void 观众版的状态_冷却与道具都空才算() {
        assertThat(SpectateSteps.redactedProblem(state(X, false, false))).isNull();
        assertThat(SpectateSteps.redactedProblem(BattleStateS2C.getDefaultInstance())).isNull();
        assertThat(SpectateSteps.redactedProblem(state(X, true, false))).isEqualTo("1 个 actor 的技能冷却没有清空");
        assertThat(SpectateSteps.redactedProblem(state(X, false, true))).startsWith("带了 1 项 self_items");
    }

    private static Ended ended(List<BattleFrame> frames, long battleId) {
        String closedBy = frames.stream().filter(BattleFrame::isClosed).map(BattleFrame::closedBy).findFirst().orElse(null);
        return new Ended(SpectateSteps.spectateEndOf(frames, IDS, battleId), SpectateSteps.spectateTurns(frames, IDS), closedBy, frames);
    }

    @Test
    void 正常收尾_若干158之后166再FIN_结局与参战者的150同值() {
        List<BattleFrame> frames = List.of(verifyOk(0, X), first161(1, X, 1), turn158(2, X), push(3, IDS.battleAssigned(), BattleAssignedS2C.getDefaultInstance()),
                first161(4, X, 1), turn158(5, X), end166(6, X, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN),
                closed(7, BattleFrame.FIN));
        Ended ended = ended(frames, X);
        assertThat(ended.turns()).isEqualTo(2);
        assertThat(ended.labels()).last().isEqualTo("closed:fin");
        assertThat(SpectateSteps.endProblem(ended, IDS, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 1))
                .as("中途重看同一场重推的 177 / 161 不碍事").isNull();
        // 结局与参战者那条 150 不一致、原因不对
        assertThat(SpectateSteps.endProblem(ended, IDS, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 1))
                .isEqualTo("166 {reason=SPECTATE_END_BATTLE_FINISHED, outcome=BATTLE_OUTCOME_SIDE_A_WIN}（期望 {SPECTATE_END_BATTLE_FINISHED, "
                        + "BATTLE_OUTCOME_SIDE_B_WIN}）");
        assertThat(SpectateSteps.endProblem(ended, IDS, eSpectateEndReason.SPECTATE_END_REMOVED, eBattleOutcome.BATTLE_OUTCOME_ONGOING, 0))
                .contains("期望 {SPECTATE_END_REMOVED, BATTLE_OUTCOME_ONGOING}");
        // 要求的回合数不够
        assertThat(SpectateSteps.endProblem(ended, IDS, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 3))
                .isEqualTo("直连上只有 2 条 158（期望 ≥ 3）");
    }

    @Test
    void 被清退的收尾_一条158都没有也行_166之后必须紧跟FIN() {
        eSpectateEndReason removed = eSpectateEndReason.SPECTATE_END_REMOVED;
        eBattleOutcome ongoing = eBattleOutcome.BATTLE_OUTCOME_ONGOING;
        List<BattleFrame> clean = List.of(verifyOk(0, X), first161(1, X, 1), end166(2, X, removed, ongoing), closed(3, BattleFrame.FIN));
        assertThat(SpectateSteps.endProblem(ended(clean, X), IDS, removed, ongoing, 0)).isNull();

        // RST 而不是 FIN
        List<BattleFrame> reset = List.of(verifyOk(0, X), first161(1, X, 1), end166(2, X, removed, ongoing), closed(3, BattleFrame.RESET));
        assertThat(SpectateSteps.endProblem(ended(reset, X), IDS, removed, ongoing, 0)).contains("166 之后应紧跟 FIN", "closed:reset");
        // 166 之后还有帧
        List<BattleFrame> trailing = List.of(verifyOk(0, X), first161(1, X, 1), end166(2, X, removed, ongoing), turn158(3, X), closed(4, BattleFrame.FIN));
        assertThat(SpectateSteps.endProblem(ended(trailing, X), IDS, removed, ongoing, 0)).contains("166 之后应紧跟 FIN");
        // 没关
        List<BattleFrame> open = List.of(verifyOk(0, X), first161(1, X, 1), end166(2, X, removed, ongoing));
        assertThat(ended(open, X).closed()).isNull();
        assertThat(SpectateSteps.endProblem(ended(open, X), IDS, removed, ongoing, 0)).contains("166 之后应紧跟 FIN");
        // 两条 166
        List<BattleFrame> twice = List.of(verifyOk(0, X), end166(1, X, removed, ongoing), end166(2, X, removed, ongoing), closed(3, BattleFrame.FIN));
        assertThat(SpectateSteps.endProblem(ended(twice, X), IDS, removed, ongoing, 0)).contains("166 应恰好一条，实际 2 条");
    }

    @Test
    void 收尾的判据_158没裁剪或观众的直连上出现参战者的帧_都报() {
        eSpectateEndReason finished = eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED;
        eBattleOutcome win = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
        BattleFrame leaky = push(2, IDS.spectateTurnResult(), TurnResultS2C.newBuilder().setBattleId(X).setState(state(X, true, false)).build());
        List<BattleFrame> unredacted = List.of(verifyOk(0, X), first161(1, X, 1), leaky, end166(3, X, finished, win), closed(4, BattleFrame.FIN));
        assertThat(SpectateSteps.endProblem(ended(unredacted, X), IDS, finished, win, 1)).isEqualTo("158 #2 1 个 actor 的技能冷却没有清空");

        List<BattleFrame> mixed = List.of(verifyOk(0, X), first161(1, X, 1), push(2, IDS.turnResult(), TurnResultS2C.getDefaultInstance()),
                turn158(3, X), push(4, IDS.battleEnd(), BattleEndS2C.getDefaultInstance()), end166(5, X, finished, win), closed(6, BattleFrame.FIN));
        assertThat(SpectateSteps.endProblem(ended(mixed, X), IDS, finished, win, 1)).isEqualTo("观众的直连上出现了 2 条参战者的帧（139 / 150）");
    }

    @Test
    void 找本场的166_别的场的不算_158的条数() {
        List<BattleFrame> frames = List.of(turn158(0, X), end166(1, X + 1, eSpectateEndReason.SPECTATE_END_REMOVED, eBattleOutcome.BATTLE_OUTCOME_ONGOING),
                turn158(2, X), end166(3, X, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, eBattleOutcome.BATTLE_OUTCOME_DRAW));
        assertThat(SpectateSteps.spectateEndOf(frames, IDS, X).getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        assertThat(SpectateSteps.spectateEndOf(frames, IDS, X + 1).getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_REMOVED);
        assertThat(SpectateSteps.spectateEndOf(frames, IDS, X + 2)).isNull();
        assertThat(SpectateSteps.spectateTurns(frames, IDS)).isEqualTo(2);
        assertThat(SpectateSteps.spectateTurns(List.of(), IDS)).isZero();
    }

    // ---------------------------------------------------------------- 165

    @Test
    void 主动退出_成功应答之后紧跟FIN_没有166_应答之前夹着的158不碍事() {
        BattleFrame ok = reply(5, IDS.stopWatch(), 3, StopWatchBattleResponse.getDefaultInstance());
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(ok, closed(6, BattleFrame.FIN))), IDS)).isNull();
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(turn158(4, X), ok, closed(6, BattleFrame.FIN))), IDS)).isNull();

        // 推了 166（基线与 Java 都不推）
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(end166(4, X, eSpectateEndReason.SPECTATE_END_REMOVED,
                eBattleOutcome.BATTLE_OUTCOME_ONGOING), ok, closed(6, BattleFrame.FIN))), IDS)).startsWith("主动退出不该推 166");
        // 没有应答、应答的 id 对不上、RST、应答与 FIN 之间还有帧、应答带错误
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(closed(5, BattleFrame.FIN))), IDS)).startsWith("应依次收到 165 的应答（id=3）与 FIN");
        assertThat(SpectateSteps.stopProblem(new Stopped(4, List.of(ok, closed(6, BattleFrame.FIN))), IDS)).startsWith("应依次收到 165 的应答（id=4）与 FIN");
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(ok, closed(6, BattleFrame.RESET))), IDS)).contains("closed:reset");
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(ok, turn158(6, X), closed(7, BattleFrame.FIN))), IDS))
                .startsWith("165 的应答之后应紧跟 FIN");
        BattleFrame rejected = reply(5, IDS.stopWatch(), 3, StopWatchBattleResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(1005)).build());
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(rejected, closed(6, BattleFrame.FIN))), IDS)).isEqualTo("165 的应答带 error_message 1005[]");
        BattleFrame envelope = new BattleFrame(5, 5, null, MessageContent.newBuilder().setMessageId(IDS.stopWatch()).setId(3)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(1008)).build(), null);
        assertThat(SpectateSteps.stopProblem(new Stopped(3, List.of(envelope, closed(6, BattleFrame.FIN))), IDS)).isEqualTo("165 的应答是信封错误 tip=1008");
    }

    // ---------------------------------------------------------------- 大厅上不该有战斗帧

    @Test
    void 大厅上的战斗帧_只数139_158_161_166_公告与应答不算() {
        List<Received> inbox = List.of(
                lobby(0, IDS.battleAssigned(), 0, BattleAssignedS2C.getDefaultInstance()),
                lobby(1, IDS.battleStart(), 0, BattleStateS2C.getDefaultInstance()),
                lobby(2, IDS.battleEnd(), 0, BattleEndS2C.getDefaultInstance()),
                lobby(3, IDS.sendTip(), 0, TipInfoMessage.getDefaultInstance()));
        assertThat(SpectateSteps.lobbyBattleFrames(inbox, IDS)).as("177 / 143 / 大厅 150 / 23 是大厅公告").isZero();
        List<Received> leaked = new ArrayList<>(inbox);
        leaked.add(lobby(4, IDS.turnResult(), 0, TurnResultS2C.getDefaultInstance()));
        leaked.add(lobby(5, IDS.spectateTurnResult(), 0, TurnResultS2C.getDefaultInstance()));
        leaked.add(lobby(6, IDS.spectateState(), 0, SpectateStateS2C.getDefaultInstance()));
        leaked.add(lobby(7, IDS.spectateEnd(), 0, SpectateEndS2C.getDefaultInstance()));
        assertThat(SpectateSteps.lobbyBattleFrames(leaked, IDS)).isEqualTo(4);
        assertThat(SpectateSteps.lobbyBattleFrames(List.of(), IDS)).isZero();
    }

    // ---------------------------------------------------------------- 带网络的几个方法（对着本机假服务端）

    /** 一名参战者开出一场单人 PVE（不开自动），一名观众进了游戏、还没观战。 */
    private record Stage(FakeMatchWorld world, RobotClient client, MatchSupport.Ids ids, Bot fighter, Bot watcher, Started started) {

        long battle() {
            return started.battleId();
        }
    }

    private static Stage stage(FakeMatchWorld world) throws RobotException {
        RobotClient client = world.newClient();
        PlayerFlow flow = world.flow(client);
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        Bot fighter = new Bot("SA", flow.enter("robot_java_sp_a", new Timings()), FakeMatchWorld.TIMEOUT, FakeMatchWorld.FAST);
        Bot watcher = new Bot("SB", flow.enter("robot_java_sp_b", new Timings()), FakeMatchWorld.TIMEOUT, FakeMatchWorld.FAST);
        int mark = fighter.mark();
        assertThat(BattleSmokeChecks.accepted(MatchSupport.join(fighter, ids, MatchMode.MATCH_MODE_PVE_SOLO, 1))).isTrue();
        Started started = MatchSupport.awaitBattle("SA", fighter.connection(), mark, 0, IDS, Duration.ofSeconds(3));
        return new Stage(world, client, ids, fighter, watcher, started);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void 观战一条龙_163成功后大厅来观众票_凭票直连到161_列表里有这一场_165退出后同一张票再握手被拒() throws Exception {
        try (FakeMatchWorld world = new FakeMatchWorld()) {
            Stage s = stage(world);
            String id = Long.toUnsignedString(s.battle());

            // 还没发 163：等不到观众票，超时的说明里写着等的是哪一场、该去看什么
            assertThatThrownBy(() -> SpectateSteps.awaitObserverTicket("SB", s.watcher().connection(), 0, s.battle(), IDS, Duration.ofMillis(100)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("SB：0 s 内大厅没有收到 battle_id=" + id + " 的观众票 177（role = 2")
                    .hasMessageContaining("AddObserver");

            int mark = s.watcher().mark();
            WatchBattleResponse watched = SpectateSteps.watch(s.watcher(), s.ids(), s.battle());
            assertThat(SpectateSteps.acceptedProblem(watched, s.battle())).isNull();
            Received r177 = SpectateSteps.awaitObserverTicket("SB", s.watcher().connection(), mark, s.battle(), IDS, Duration.ofSeconds(3));
            BattleAssignedS2C ticket = r177.parse(BattleAssignedS2C.parser());
            assertThat(SpectateSteps.observerTicketProblem(ticket, s.battle(), s.watcher().id(), s.started().assigned().getExpireAtMs())).isNull();
            assertThat(SpectateSteps.observerTicketProblem(ticket, s.battle(), s.fighter().id(), 0)).as("票是签给观众本人的").contains("没有指向本人");

            SpectateSteps.Watching watching = SpectateSteps.connectObserver(s.client(), "SB", ticket, IDS, Duration.ofSeconds(3));
            assertThat(watching.firstFrame().index()).as("握手应答是 0 号，161 紧跟其后").isEqualTo(1);
            assertThat(watching.state().getObserverCount()).isEqualTo(1);
            assertThat(watching.state().getState().getBattleId()).isEqualTo(s.battle());
            assertThat(SpectateSteps.firstFrameProblem(watching.direct().since(0), IDS, s.battle(), 1)).isNull();

            ListWatchableBattlesResponse listed = SpectateSteps.list(s.watcher(), s.ids(), 0);
            assertThat(SpectateSteps.listProblem(listed, 0, System.currentTimeMillis())).isNull();
            assertThat(SpectateSteps.find(listed, s.battle())).get().extracting(BattleWatchSummary::getModeValue).isEqualTo(4);

            Stopped stopped = SpectateSteps.stopWatching(watching.direct(), s.battle(), IDS, Duration.ofSeconds(3));
            assertThat(SpectateSteps.stopProblem(stopped, IDS)).isNull();
            assertThat(world.observerCount()).isZero();
            // 主动退出之后连接是被服务端 FIN 的、上面没有 166：再等 166 立刻报「166 之前就被关闭」，不会傻等到超时
            assertThatThrownBy(() -> SpectateSteps.awaitEnd(watching.direct(), s.battle(), IDS, Duration.ofSeconds(20)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("SB：观战直连在 166 之前就被关闭（battle_id=" + id + "）");
            // 名单里已经没有他：同一张票再握手被拒（拒绝串原样带出来），连接随即关闭
            assertThatThrownBy(() -> SpectateSteps.connectObserver(s.client(), "SB", ticket, IDS, Duration.ofSeconds(3)))
                    .isInstanceOf(RobotException.class)
                    .hasMessageContaining("SB：观战直连握手被拒「battle not found or player not in this battle」（battle_id=" + id);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void 战斗打完_观众的直连上158然后166FINISHED再FIN_结局与参战者的150同值_没发165的观众等不到166会超时() throws Exception {
        try (FakeMatchWorld world = new FakeMatchWorld()) {
            Stage s = stage(world);
            BattleSupport.Direct fighterDirect = MatchSupport.connect(s.client(), "SA", s.started().assigned(), IDS, FakeMatchWorld.TIMEOUT);
            int mark = s.watcher().mark();
            assertThat(SpectateSteps.accepted(SpectateSteps.watch(s.watcher(), s.ids(), s.battle()))).isTrue();
            BattleAssignedS2C ticket = SpectateSteps.awaitObserverTicket("SB", s.watcher().connection(), mark, s.battle(), IDS, Duration.ofSeconds(3))
                    .parse(BattleAssignedS2C.parser());
            SpectateSteps.Watching watching = SpectateSteps.connectObserver(s.client(), "SB", ticket, IDS, Duration.ofSeconds(3));

            // 参战者还没开自动：战斗不会结束，等 166 只会超时（说明里写着收到了什么）
            assertThatThrownBy(() -> SpectateSteps.awaitEnd(watching.direct(), s.battle(), IDS, Duration.ofMillis(200)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("SB：0 s 内观战直连上没有 166").hasMessageContaining("push:" + IDS.spectateState());

            MatchSupport.AutoRequest auto = MatchSupport.enableAuto(fighterDirect, s.battle(), IDS);
            MatchSupport.Finished fought = MatchSupport.awaitEnd(fighterDirect, s.battle(), IDS, Duration.ofSeconds(5), auto);
            Ended ended = SpectateSteps.awaitEnd(watching.direct(), s.battle(), IDS, Duration.ofSeconds(5));
            assertThat(ended.turns()).isEqualTo(1);
            assertThat(ended.closed()).isEqualTo(BattleFrame.FIN);
            assertThat(ended.labels()).containsExactly("verify-ok:" + Long.toUnsignedString(s.battle()), "push:" + IDS.spectateState(),
                    "push:" + IDS.spectateTurnResult(), "push:" + IDS.spectateEnd(), "closed:fin");
            assertThat(SpectateSteps.endProblem(ended, IDS, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, fought.end().getOutcome(), 1)).isNull();
            assertThat(SpectateSteps.endProblem(ended, IDS, eSpectateEndReason.SPECTATE_END_REMOVED, eBattleOutcome.BATTLE_OUTCOME_ONGOING, 0))
                    .contains("166 {reason=SPECTATE_END_BATTLE_FINISHED");
            assertThat(SpectateSteps.lobbyBattleFrames(s.watcher().connection().inbox().snapshot(0), IDS)).as("观众的大厅上只有 177").isZero();
            fighterDirect.close();
        }
    }
}
