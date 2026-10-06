package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.ManualRedisClock;
import com.game.match.testing.RecordingPushes;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 152 / 151 两个处理器接在真的派发器上（match-spec §8.1 的 152 / 151 一行）：按契约的消息号派发、都进工作池；正常与业务拒绝回应答体；
 * 会话没绑定玩家 in-band 16004「缺少玩家身份」；请求体解析失败信封 1003；工作池满 in-band 16004「服务器繁忙,请稍后再试」（M29）。
 */
class ChallengeHandlersTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final int CHALLENGE_PLAYER = REGISTRY.requireId("MatchService", "ChallengePlayer");
    private static final int RESPOND_CHALLENGE = REGISTRY.requireId("MatchService", "RespondChallenge");

    private final FakePlayerStatus players = new FakePlayerStatus().online(A, 1, 7).online(B, 1, 7);
    private final InMemoryChallengeStore store = new InMemoryChallengeStore(new ManualRedisClock());
    private final RecordingPushes pushes = new RecordingPushes();
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final ChallengeService service = new ChallengeService(players, store, new MatchIds(new Snowflake(5), () -> true, () -> false), pushes,
            gather, metrics, Runnable::run, 60_000, 156, 154);
    private final List<MatchMethodHandler> handlers = List.of(ChallengeHandlers.challengePlayer(service, metrics),
            ChallengeHandlers.respondChallenge(service, metrics));

    private MatchDispatcher dispatcher(Executor workers) {
        return new MatchDispatcher(REGISTRY, handlers, workers, metrics, 4500);
    }

    private static ClientCall call(int messageId, long playerId, ByteString body) {
        return ClientCall.newBuilder().setMessageId(messageId).setRequestId(7).setBody(body)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(9).setZoneId(1)
                        .setAccount("acc-" + playerId).setPlayerId(playerId))
                .build();
    }

    private static ClientReply reply(MatchDispatcher dispatcher, ClientCall call) throws Exception {
        return dispatcher.dispatch(call).get(5, TimeUnit.SECONDS);
    }

    @Test
    void 消息号是152与151_都不是inline_要进工作池() {
        assertThat(CHALLENGE_PLAYER).isEqualTo(152);
        assertThat(RESPOND_CHALLENGE).isEqualTo(151);
        assertThat(handlers).extracting(MatchMethodHandler::method).containsExactly("ChallengePlayer", "RespondChallenge");
        assertThat(handlers).allSatisfy(handler -> assertThat(handler.inline()).isFalse());
        assertThat(dispatcher(Runnable::run).handledMessageIds()).containsExactly(151, 152);
    }

    @Test
    void 发起与应答经派发器走通_应答体是契约消息_拒绝邀请的应答体为空() throws Exception {
        MatchDispatcher dispatcher = dispatcher(Runnable::run);

        ClientReply challenged = reply(dispatcher, call(CHALLENGE_PLAYER, A,
                ChallengePlayerRequest.newBuilder().setTargetPlayerId(B).setBattleConfigId(3).build().toByteString()));
        assertThat(challenged.getTipId()).as("in-band：信封不带 tip").isZero();
        long challengeId = ChallengePlayerResponse.parseFrom(challenged.getBody()).getChallengeId();
        assertThat(challengeId).isNotZero();
        assertThat(pushes.sent).hasSize(1);

        ClientReply declined = reply(dispatcher, call(RESPOND_CHALLENGE, B,
                RespondChallengeRequest.newBuilder().setChallengeId(challengeId).setAccept(false).build().toByteString()));
        assertThat(declined.getTipId()).isZero();
        assertThat(declined.getBody().isEmpty()).as("151 成功的应答是空消息").isTrue();
        assertThat(pushes.sentTo(A)).hasSize(1);

        ClientReply again = reply(dispatcher, call(RESPOND_CHALLENGE, B,
                RespondChallengeRequest.newBuilder().setChallengeId(challengeId).setAccept(true).build().toByteString()));
        RespondChallengeResponse rejected = RespondChallengeResponse.parseFrom(again.getBody());
        assertThat(again.getTipId()).as("业务拒绝也是 in-band").isZero();
        assertThat(rejected.getErrorMessage().getId()).isEqualTo(16012);
        assertThat(rejected.getErrorMessage().getParametersList()).containsExactly("切磋邀请已过期");
    }

    @Test
    void 会话没绑定玩家_in_band的16004缺少玩家身份_不是信封() throws Exception {
        MatchDispatcher dispatcher = dispatcher(Runnable::run);

        ClientReply challenged = reply(dispatcher, call(CHALLENGE_PLAYER, 0, ChallengePlayerRequest.newBuilder().setTargetPlayerId(B).build().toByteString()));
        ClientReply responded = reply(dispatcher, call(RESPOND_CHALLENGE, 0, RespondChallengeRequest.newBuilder().setChallengeId(1).build().toByteString()));

        assertThat(challenged.getTipId()).isZero();
        assertThat(ChallengePlayerResponse.parseFrom(challenged.getBody()).getErrorMessage().getParametersList()).containsExactly("缺少玩家身份");
        assertThat(ChallengePlayerResponse.parseFrom(challenged.getBody()).getErrorMessage().getId()).isEqualTo(16004);
        assertThat(responded.getTipId()).isZero();
        assertThat(RespondChallengeResponse.parseFrom(responded.getBody()).getErrorMessage().getParametersList()).containsExactly("缺少玩家身份");
    }

    @Test
    void 请求体解析失败_信封1003_不碰任何依赖() throws Exception {
        MatchDispatcher dispatcher = dispatcher(Runnable::run);
        ByteString garbage = ByteString.copyFrom(new byte[] {(byte) 0xFF, 0x01});

        ClientReply challenged = reply(dispatcher, call(CHALLENGE_PLAYER, A, garbage));
        ClientReply responded = reply(dispatcher, call(RESPOND_CHALLENGE, B, garbage));

        assertThat(challenged.getTipId()).isEqualTo(1003);
        assertThat(challenged.getBody().isEmpty()).isTrue();
        assertThat(responded.getTipId()).isEqualTo(1003);
        assertThat(players.reads).isEmpty();
        assertThat(store.calls).isEmpty();
    }

    @Test
    void 工作池满_in_band的16004服务器繁忙_计入overloaded_不执行业务() throws Exception {
        MatchDispatcher dispatcher = dispatcher(task -> {
            throw new RejectedExecutionException("满了");
        });

        ClientReply challenged = reply(dispatcher, call(CHALLENGE_PLAYER, A, ChallengePlayerRequest.newBuilder().setTargetPlayerId(B).build().toByteString()));
        ClientReply responded = reply(dispatcher, call(RESPOND_CHALLENGE, B, RespondChallengeRequest.newBuilder().setChallengeId(1).build().toByteString()));

        assertThat(challenged.getTipId()).as("152 / 151 有 in-band 错误字段：过载不用信封").isZero();
        ChallengePlayerResponse challengeBody = ChallengePlayerResponse.parseFrom(challenged.getBody());
        assertThat(challengeBody.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(challengeBody.getErrorMessage().getParametersList()).containsExactly("服务器繁忙,请稍后再试");
        RespondChallengeResponse respondBody = RespondChallengeResponse.parseFrom(responded.getBody());
        assertThat(responded.getTipId()).isZero();
        assertThat(respondBody.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(respondBody.getErrorMessage().getParametersList()).containsExactly("服务器繁忙,请稍后再试");
        assertThat(players.reads).isEmpty();
        assertThat(store.calls).isEmpty();
        assertThat(meters.get("xm.match.challenges").tags("stage", "invite", "result", "overloaded").counter().count()).isEqualTo(1);
        assertThat(meters.get("xm.match.challenges").tags("stage", "respond", "result", "overloaded").counter().count()).isEqualTo(1);
    }
}
