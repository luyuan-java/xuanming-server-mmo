package com.game.match.reissue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.dispatch.MatchMethods;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.placement.PlacementDialer;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakePlacementDialer;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 票据补签 179（match-spec §4.3 的六行、§8.1 的 179 一行、§15.2）：每一行的应答码与 {@code parameters[0]} 逐字节、指标标签；
 * 身份只取会话；battle 的裁决原样透传；1005 只在「记录不存在」与「建连失败且同号换实例」两处由 match 发出，<b>超时一律 1003</b>。
 * 对照基线 {@code requestbattleticket_test.go:85-165}。直拨的分类规则本身见 {@code DirectPlacementDialerTest}。
 */
class BattleTicketReissueTest {

    private static final long BATTLE = 7_000_000_179L;
    private static final long MEMBER = 1001;
    private static final long OUTSIDER = 2002;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore();
    private final FakeBattleNode battle = new FakeBattleNode();
    private final FakePlacementDialer dialer = new FakePlacementDialer(battle);
    private final BattleTicketReissue reissue = new BattleTicketReissue(placements, dialer, metrics);
    private final ReissueHandler handler = new ReissueHandler(reissue);

    private static final BattlePlacement PLACEMENT = BattlePlacement.newBuilder().setBattleId(BATTLE).setBattleNodeId(7)
            .setBattleInstanceId("inst-a").setRpcHost("10.1.1.1").setRpcPort(21200).setAttempt(1).setMode(3).setDeadlineMs(999_000).build();

    @BeforeEach
    void setUp() {
        placements.put(PLACEMENT);
        battle.room(CreateBattleRequest.newBuilder().setBattleId(BATTLE).setDeadlineMs(999_000)
                .addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(MEMBER)).build());
    }

    private static Deadline d() {
        return Deadline.after(4_500);
    }

    private double count(String result) {
        return meters.get("xm.match.battle.ticket.reissues").tag("result", result).counter().count();
    }

    private static void assertTip(RequestBattleTicketResponse response, int code, String text) {
        assertThat(response.getErrorMessage().getId()).isEqualTo(code);
        assertThat(response.getErrorMessage().getParametersList()).hasSize(1);
        assertThat(response.getErrorMessage().getParameters(0).getBytes(StandardCharsets.UTF_8)).as("parameters[0] 逐字节")
                .isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        assertThat(response.hasAssignment()).isFalse();
    }

    // ================================================================ 六行

    @Test
    void 第1行_会话没有绑定玩家_16004缺少玩家身份_不读记录不拨号() {
        RequestBattleTicketResponse response = reissue.reissue(0, BATTLE, d());

        assertTip(response, 16004, "缺少玩家身份");
        assertThat(dialer.dials).isEmpty();
        assertThat(count("no_session")).isEqualTo(1.0);
    }

    @Test
    void 第2行_读落点记录出错_记录损坏_16004服务器繁忙_半角逗号() {
        placements.readFailed = true;
        RequestBattleTicketResponse readFailed = reissue.reissue(MEMBER, BATTLE, d());
        placements.readFailed = false;
        placements.corrupt(BATTLE);
        RequestBattleTicketResponse corrupt = reissue.reissue(MEMBER, BATTLE, d());

        assertTip(readFailed, 16004, "服务器繁忙,请稍后再试");
        assertTip(corrupt, 16004, "服务器繁忙,请稍后再试");
        assertThat(dialer.dials).as("读不出记录不能当作不存在，也不拨号").isEmpty();
        assertThat(count("internal")).isEqualTo(2.0);
        assertThat(count("not_found")).isZero();
    }

    @Test
    void 第3行_记录不存在_1005该战斗不存在或已结束() {
        RequestBattleTicketResponse response = reissue.reissue(MEMBER, 424_242, d());
        RequestBattleTicketResponse zero = reissue.reissue(MEMBER, 0, d());

        assertTip(response, 1005, "该战斗不存在或已结束");
        assertTip(zero, 1005, "该战斗不存在或已结束");
        assertThat(dialer.dials).isEmpty();
        assertThat(count("not_found")).isEqualTo(2.0);
    }

    @Test
    void 第4行_调通_成员拿到battle新签的票_按记录拨号_带会话的玩家号_超时3秒() {
        RequestBattleTicketResponse response = reissue.reissue(MEMBER, BATTLE, d());

        assertThat(response.hasErrorMessage()).isFalse();
        BattleAssignedS2C assignment = response.getAssignment();
        assertThat(assignment.getBattleId()).isEqualTo(BATTLE);
        assertThat(assignment.getTokenPayload().toStringUtf8()).isEqualTo("payload:" + BATTLE + ":" + MEMBER);
        assertThat(assignment.getExpireAtMs()).isEqualTo(999_000);
        assertThat(dialer.dials).singleElement().satisfies(dialed -> {
            assertThat(dialed.placement()).isEqualTo(PLACEMENT);
            assertThat(dialed.timeout()).isEqualTo(Duration.ofSeconds(3));
        });
        assertThat(battle.issues).singleElement().satisfies(issue -> {
            assertThat(issue.getBattleId()).isEqualTo(BATTLE);
            assertThat(issue.getPlayerId()).isEqualTo(MEMBER);
        });
        assertThat(count("ok")).isEqualTo(1.0);
    }

    @Test
    void 第4行_battle的拒绝原样透传_非成员1005不带parameters_签不出1003() {
        RequestBattleTicketResponse outsider = reissue.reissue(OUTSIDER, BATTLE, d());
        battle.nextIssue(IssueBattleTicketResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1003)).build());
        RequestBattleTicketResponse unsigned = reissue.reissue(MEMBER, BATTLE, d());

        assertThat(outsider.getErrorMessage().getId()).isEqualTo(1005);
        assertThat(outsider.getErrorMessage().getParametersList()).as("battle 的裁决不带 match 的文案").isEmpty();
        assertThat(outsider.hasAssignment()).isFalse();
        assertThat(unsigned.getErrorMessage().getId()).isEqualTo(1003);
        assertThat(unsigned.getErrorMessage().getParametersList()).isEmpty();
        assertThat(count("rejected")).isEqualTo(2.0);
        assertThat(count("ok")).isZero();
    }

    @Test
    void 第4行_透传是逐字段的_battle同时给了tip与票也照搬() {
        IssueBattleTicketResponse both = IssueBattleTicketResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(1008).addParameters("battle 自己的说明"))
                .setAssignment(BattleAssignedS2C.newBuilder().setBattleId(BATTLE).setTokenSignature(ByteString.copyFromUtf8("sig"))).build();
        battle.nextIssue(both);

        RequestBattleTicketResponse response = reissue.reissue(MEMBER, BATTLE, d());

        assertThat(response.getErrorMessage()).isEqualTo(both.getErrorMessage());
        assertThat(response.getAssignment()).isEqualTo(both.getAssignment());
    }

    @Test
    void 第5行_直拨建连失败且同号节点已换实例_1005_客户端据此放弃本局() {
        dialer.roomGone();

        RequestBattleTicketResponse response = reissue.reissue(MEMBER, BATTLE, d());

        assertTip(response, 1005, "该战斗不存在或已结束");
        assertThat(count("instance_changed")).isEqualTo(1.0);
        assertThat(count("not_found")).as("与「没有记录」分开计").isZero();
    }

    @Test
    void 第6行_超时_1003战斗服务暂不可用_计rpc_timeout() {
        dialer.unavailable(PlacementDialer.Kind.TIMEOUT);

        RequestBattleTicketResponse response = reissue.reissue(MEMBER, BATTLE, d());

        assertTip(response, 1003, "战斗服务暂不可用");
        assertThat(count("rpc_timeout")).isEqualTo(1.0);
        assertThat(count("rpc_error")).isZero();
        assertThat(count("instance_changed")).isZero();
    }

    @Test
    void 第6行_建连失败但没有换实例的证据_连上之后断开_都是1003_计rpc_error() {
        dialer.unavailable(PlacementDialer.Kind.NOT_DELIVERED);
        RequestBattleTicketResponse notDelivered = reissue.reissue(MEMBER, BATTLE, d());
        dialer.unavailable(PlacementDialer.Kind.OTHER);
        RequestBattleTicketResponse other = reissue.reissue(MEMBER, BATTLE, d());

        assertTip(notDelivered, 1003, "战斗服务暂不可用");
        assertTip(other, 1003, "战斗服务暂不可用");
        assertThat(count("rpc_error")).isEqualTo(2.0);
        assertThat(count("rpc_timeout")).isZero();
    }

    @Test
    void 请求预算所剩无几时_直拨超时按剩余预算收短_用完了就不拨直接1003() {
        RequestBattleTicketResponse spent = reissue.reissue(MEMBER, BATTLE, Deadline.after(0));
        assertTip(spent, 1003, "战斗服务暂不可用");
        assertThat(dialer.dials).isEmpty();
        assertThat(count("rpc_timeout")).isEqualTo(1.0);

        reissue.reissue(MEMBER, BATTLE, Deadline.after(1_500));
        assertThat(dialer.dials).singleElement().satisfies(dialed -> assertThat(dialed.timeout().toMillis()).as("≤ 剩余预算 − 应答余量")
                .isBetween(1_000L, 1_300L));
    }

    // ================================================================ 入口处理器

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(9).setZoneId(1).setAccount("acc")
                .setPlayerId(playerId).build();
    }

    private static RequestBattleTicketResponse parse(Reply reply) throws InvalidProtocolBufferException {
        assertThat(reply).isInstanceOf(Reply.Body.class);
        return RequestBattleTicketResponse.parseFrom(((Reply.Body) reply).bytes());
    }

    @Test
    void 处理器_登记在RequestBattleTicket上_进工作池_身份只取会话() throws Exception {
        ByteString body = RequestBattleTicketRequest.newBuilder().setBattleId(BATTLE).build().toByteString();

        RequestBattleTicketResponse member = parse(handler.handle(session(MEMBER), body, d()));
        RequestBattleTicketResponse anonymous = parse(handler.handle(session(0), body, d()));

        assertThat(handler.method()).isEqualTo(MatchMethods.REQUEST_BATTLE_TICKET);
        assertThat(handler.inline()).as("要读 Redis、要直拨 battle：不能在 Dubbo 线程上当场回").isFalse();
        assertThat(member.getAssignment().getBattleId()).isEqualTo(BATTLE);
        assertThat(battle.issues).singleElement().satisfies(issue -> assertThat(issue.getPlayerId()).isEqualTo(MEMBER));
        assertTip(anonymous, 16004, "缺少玩家身份");
    }

    @Test
    void 处理器_请求体解析失败抛给派发器回信封1003_过载回inband的16004() throws Exception {
        ByteString garbage = ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});

        assertThatThrownBy(() -> handler.handle(session(MEMBER), garbage, d())).isInstanceOf(InvalidProtocolBufferException.class);
        assertTip(parse(handler.onOverload()), 16004, "服务器繁忙,请稍后再试");
        assertThat(dialer.dials).isEmpty();
    }

    @Test
    void 处理器_空请求体是battle_id为0_按记录不存在回1005() throws Exception {
        RequestBattleTicketResponse response = parse(handler.handle(session(MEMBER), ByteString.EMPTY, d()));

        assertTip(response, 1005, "该战斗不存在或已结束");
    }
}
