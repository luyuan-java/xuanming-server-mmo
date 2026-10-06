package com.game.match.admin;

import static com.game.match.admin.DevActivityBattleTestApp.OPERATOR;
import static com.game.match.admin.DevActivityBattleTestApp.post;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.game.match.gather.GatherPlan;
import com.game.match.precheck.MemberPrecheck.Reason;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakeMemberPrecheck;
import com.game.match.testing.InMemoryTicketStore;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * dev 活动开战接口在真的内嵌 Tomcat 上（dev 运行模式；match-spec §7.2 末条、§9.10）：protobuf 字节进出；走与 Dubbo 提供方同一个实现
 * （校验、预检、建票、gather 都真的发生）；业务拒绝是 200 + 应答里的 {@code reject}；请求体不是该消息 / 过大、缺操作人是 400；每次执行记一行运维审计。
 */
@SpringBootTest(classes = DevActivityBattleTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=dev"})
class DevActivityBattleControllerTest {

    private static final long A = 9901;
    private static final long B = 9902;

    @LocalServerPort
    int port;

    @Autowired
    InMemoryTicketStore tickets;

    @Autowired
    FakeMemberPrecheck precheck;

    @Autowired
    FakeGatherLauncher gather;

    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();

    @BeforeEach
    void reset() {
        precheck.pass();
        precheck.rosters.clear();
        gather.plans.clear();
        gather.futures.clear();
        tickets.calls.clear();
        audit.start();
        ((Logger) LoggerFactory.getLogger(DevActivityBattleController.AUDIT_LOGGER)).addAppender(audit);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(DevActivityBattleController.AUDIT_LOGGER)).detachAppender(audit);
    }

    private static StartActivityBattleRequest.Builder request(long initiator, Long... members) {
        return StartActivityBattleRequest.newBuilder().setBattleConfigId(1).addAllMemberPlayerIds(List.of(members))
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                        .setGuildId(424242).setActivityId(7).setPeriodKey(20261006).setGuildPeriodKey(20261006).setInitiatorPlayerId(initiator));
    }

    @Test
    void 合法请求_200带protobuf应答_battle_id非0_票据已建_gather已用同一个号启动_记一行审计() throws Exception {
        StartActivityBattleRequest request = request(A, A, B).build();

        HttpResponse<byte[]> http = post(port, OPERATOR, request.toByteArray());

        assertThat(http.statusCode()).isEqualTo(200);
        assertThat(http.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/x-protobuf"));
        StartActivityBattleResponse response = StartActivityBattleResponse.parseFrom(http.body());
        assertThat(response.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(response.getBattleId()).isNotZero();
        assertThat(precheck.rosters).containsExactly(List.of(A, B));
        assertThat(tickets.ticketOf(A)).isPresent();
        assertThat(tickets.ticketOf(B)).isPresent();
        assertThat(gather.plans).hasSize(1);
        GatherPlan plan = gather.plans.get(0);
        assertThat(plan.presetBattleId()).isEqualTo(response.getBattleId());
        assertThat(plan.members()).containsExactly(A, B);
        assertThat(plan.activityContext()).isEqualTo(request.getActivityContext());

        assertThat(audit.list).hasSize(1);
        assertThat(audit.list.get(0).getFormattedMessage()).contains("operator=xm-robot", "members=[9901, 9902]", "reject=0",
                "battle_id=" + Long.toUnsignedString(response.getBattleId()));
    }

    @Test
    void 业务拒绝_200加应答里的reject_不是HTTP错误() throws Exception {
        HttpResponse<byte[]> invalid = post(port, OPERATOR, request(B, A, B).build().toByteArray());
        precheck.fail(Reason.OFFLINE, B);
        HttpResponse<byte[]> offline = post(port, OPERATOR, request(A, A, B).build().toByteArray());
        precheck.fail(Reason.LOCK_READ_FAILED, B);
        HttpResponse<byte[]> lockError = post(port, OPERATOR, request(A, A, B).build().toByteArray());
        HttpResponse<byte[]> empty = post(port, OPERATOR, new byte[0]);

        assertThat(invalid.statusCode()).isEqualTo(200);
        assertThat(StartActivityBattleResponse.parseFrom(invalid.body()).getReject()).as("发起人不在名单首位")
                .isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT);
        assertThat(offline.statusCode()).isEqualTo(200);
        StartActivityBattleResponse offlineBody = StartActivityBattleResponse.parseFrom(offline.body());
        assertThat(offlineBody.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE);
        assertThat(offlineBody.getOffenderPlayerId()).isEqualTo(B);
        assertThat(StartActivityBattleResponse.parseFrom(lockError.body()).getReject()).as("读战斗锁出错：活动入口是 INTERNAL")
                .isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL);
        assertThat(empty.statusCode()).as("空请求体是合法的空消息：校验不过").isEqualTo(200);
        assertThat(StartActivityBattleResponse.parseFrom(empty.body()).getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT);
        assertThat(gather.plans).isEmpty();
        assertThat(tickets.calls).isEmpty();
        assertThat(audit.list).as("执行了的调用各记一行").hasSize(4);
    }

    @Test
    void 请求体不是该消息或过大_400_缺操作人或操作人非法_400_都不执行() throws Exception {
        byte[] body = request(A, A, B).build().toByteArray();

        assertThat(post(port, OPERATOR, new byte[] {(byte) 0xFF, 0x01}).statusCode()).isEqualTo(400);
        assertThat(post(port, OPERATOR, new byte[DevActivityBattleController.MAX_BODY_BYTES + 1]).statusCode()).isEqualTo(400);
        assertThat(post(port, null, body).statusCode()).as("缺操作人").isEqualTo(400);
        assertThat(post(port, " ", body).statusCode()).as("空白").isEqualTo(400);
        assertThat(post(port, "x".repeat(DevActivityBattleController.MAX_OPERATOR_CHARS + 1), body).statusCode()).as("超长").isEqualTo(400);

        assertThat(precheck.rosters).isEmpty();
        assertThat(tickets.calls).isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(audit.list).isEmpty();
    }

    @Test
    void 操作人按UTF8还原_中文名可以_非法UTF8与控制字符不行() {
        String raw = new String("运维小王".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        assertThat(DevActivityBattleController.operator(raw)).isEqualTo("运维小王");
        assertThat(DevActivityBattleController.operator("xm-robot")).isEqualTo("xm-robot");
        assertThat(DevActivityBattleController.operator(null)).isNull();
        assertThat(DevActivityBattleController.operator("")).isNull();
        assertThat(DevActivityBattleController.operator("a\tb")).as("控制字符会伪造审计日志行").isNull();
        assertThat(DevActivityBattleController.operator(new String(new byte[] {(byte) 0xC3, (byte) 0x28}, StandardCharsets.ISO_8859_1)))
                .as("不是合法的 UTF-8").isNull();
    }
}
