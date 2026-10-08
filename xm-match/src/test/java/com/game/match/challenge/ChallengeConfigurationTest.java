package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.match.testing.ManualRedisClock;
import com.game.match.testing.RecordingPushes;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 切磋的 Spring 装配（{@link ChallengeConfiguration}）：给齐它向别的包要的接口之后装得出服务与 152 / 151 两个处理器，并且
 * <b>配置值与消息号传对了地方</b>——{@code challenge-ttl}（156 的 {@code expires_at_ms}，客户端可见的时限，match-spec §8.4）与 156 / 154 两个号
 * 在 {@code ChallengeService} 的构造器里是相邻的同类型参数（一个 long、两个 int），对调了编译照过；组件测试都是自己拿常量构造服务，
 * 只有经装配类跑一次才看得出来。存储换成内存实现（{@code @Primary}），其余是 testing 包的替身；推送回调跑在真的 {@code match-push} 上。
 */
class ChallengeConfigurationTest {

    private static final long A = 1001;
    private static final long B = 1002;

    @TestConfiguration(proxyBeanMethods = false)
    static class Collaborators {

        /** 非缺省（缺省 60 s）：装配要是没把它传下去，或者传成了别的时限，过期时刻就对不上。 */
        static final Duration CHALLENGE_TTL = Duration.ofSeconds(45);

        @Bean
        MatchProperties matchProperties() {
            return new MatchProperties(null, null, null, null, null, CHALLENGE_TTL, null, null, null, null, null, null);
        }

        @Bean
        ManualRedisClock clock() {
            return new ManualRedisClock();
        }

        @Bean
        FakePlayerStatus players() {
            return new FakePlayerStatus().online(A, 1, 7).online(B, 2, 8);
        }

        @Bean
        MatchIds matchIds() {
            return new MatchIds(new Snowflake(5), () -> true, () -> false);
        }

        @Bean
        RecordingPushes pushes() {
            return new RecordingPushes();
        }

        @Bean
        FakeGatherLauncher gather() {
            return new FakeGatherLauncher();
        }

        @Bean
        MatchMetrics matchMetrics() {
            return new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));
        }

        @Bean
        MessageIdRegistry messageIdRegistry() {
            return MessageIdRegistry.loadFromClasspath();
        }

        /** 只为满足 {@code ChallengeConfiguration.challengeStore} 的入参；服务实际用的是下面那个 {@code @Primary}。 */
        @Bean
        RedissonClient redissonClient() {
            return new LeaseOnlyRedis().client;
        }

        @Bean
        @Primary
        InMemoryChallengeStore inMemoryChallengeStore(ManualRedisClock clock) {
            return new InMemoryChallengeStore(clock);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class, ChallengeConfiguration.class);

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(9).setZoneId(1)
                .setAccount("acc-" + playerId).setPlayerId(playerId).build();
    }

    private static Map<String, MatchMethodHandler> handlers(AssertableApplicationContext context) {
        return context.getBeansOfType(MatchMethodHandler.class).values().stream()
                .collect(Collectors.toMap(MatchMethodHandler::method, Function.identity()));
    }

    private static ByteString body(MatchMethodHandler.Reply reply) {
        assertThat(reply).isInstanceOf(MatchMethodHandler.Reply.Body.class);
        return ((MatchMethodHandler.Reply.Body) reply).bytes();
    }

    @Test
    void 装出服务与两个处理器_各管152与151() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ChallengeService.class).hasSingleBean(MatchPushExecutor.class);
            assertThat(context.getBean(ChallengeStore.class)).as("测试里用 @Primary 换掉了 Redis 实现").isInstanceOf(InMemoryChallengeStore.class);
            assertThat(context.getBeansOfType(ChallengeStore.class).values()).hasAtLeastOneElementOfType(RedissonChallengeStore.class);
            assertThat(handlers(context)).containsOnlyKeys(MatchMethods.CHALLENGE_PLAYER, MatchMethods.RESPOND_CHALLENGE);
            assertThat(handlers(context).values()).allSatisfy(handler -> assertThat(handler.inline()).as("要读写 Redis：进工作池").isFalse());
        });
    }

    @Test
    void 经装配发起再拒绝_邀请用156且过期时刻取challenge_ttl_结果用154() {
        runner.run(context -> {
            Map<String, MatchMethodHandler> handlers = handlers(context);
            RecordingPushes pushes = context.getBean(RecordingPushes.class);
            ManualRedisClock clock = context.getBean(ManualRedisClock.class);

            ChallengePlayerResponse sent = ChallengePlayerResponse.parseFrom(body(handlers.get(MatchMethods.CHALLENGE_PLAYER).handle(session(A),
                    ChallengePlayerRequest.newBuilder().setTargetPlayerId(B).setBattleConfigId(3).build().toByteString(), Deadline.after(5_000))));

            long challengeId = sent.getChallengeId();
            assertThat(challengeId).as("发起成功").isNotZero();
            assertThat(sent.hasErrorMessage()).isFalse();
            assertThat(pushes.sent).hasSize(1);
            RecordingPushes.Pushed invite = pushes.sent.get(0);
            assertThat(invite.playerId()).isEqualTo(B);
            assertThat(invite.messageId()).as("邀请推送是 156 NotifyChallengeInvite（不是结果的 154）").isEqualTo(156);
            ChallengeInviteS2C inviteBody = ChallengeInviteS2C.parseFrom(invite.body());
            assertThat(inviteBody.getChallengeId()).isEqualTo(challengeId);
            assertThat(inviteBody.getChallengerId()).isEqualTo(A);
            assertThat(inviteBody.getBattleConfigId()).isEqualTo(3);
            assertThat(inviteBody.getExpiresAtMs()).as("xm.match.challenge-ttl = 45 s（缺省是 60 s）").isEqualTo(clock.peekMs() + 45_000);

            RespondChallengeResponse declined = RespondChallengeResponse.parseFrom(body(handlers.get(MatchMethods.RESPOND_CHALLENGE).handle(session(B),
                    RespondChallengeRequest.newBuilder().setChallengeId(challengeId).setAccept(false).build().toByteString(), Deadline.after(5_000))));

            assertThat(declined.hasErrorMessage()).isFalse();
            assertThat(pushes.sent).hasSize(2);
            RecordingPushes.Pushed result = pushes.sent.get(1);
            assertThat(result.playerId()).as("拒绝只通知发起者").isEqualTo(A);
            assertThat(result.messageId()).as("结果推送是 154 NotifyChallengeResult（不是邀请的 156）").isEqualTo(154);
            assertThat(ChallengeResultS2C.parseFrom(result.body()))
                    .isEqualTo(ChallengeResultS2C.newBuilder().setChallengeId(challengeId).setAccepted(false).setResponderId(B).build());
            assertThat(context.getBean(FakeGatherLauncher.class).plans).isEmpty();
        });
    }

    @Test
    void 邀请的寿命取challenge_ttl_45秒整过期_差一毫秒还能应答() {
        runner.run(context -> {
            Map<String, MatchMethodHandler> handlers = handlers(context);
            ManualRedisClock clock = context.getBean(ManualRedisClock.class);
            long challengeId = ChallengePlayerResponse.parseFrom(body(handlers.get(MatchMethods.CHALLENGE_PLAYER).handle(session(A),
                    ChallengePlayerRequest.newBuilder().setTargetPlayerId(B).build().toByteString(), Deadline.after(5_000)))).getChallengeId();
            assertThat(challengeId).isNotZero();
            InMemoryChallengeStore store = context.getBean(InMemoryChallengeStore.class);

            clock.advanceMs(44_999);
            assertThat(store.recordOf(challengeId)).as("第 44.999 秒：记录还在").isPresent();
            clock.advanceMs(1);

            RespondChallengeResponse late = RespondChallengeResponse.parseFrom(body(handlers.get(MatchMethods.RESPOND_CHALLENGE).handle(session(B),
                    RespondChallengeRequest.newBuilder().setChallengeId(challengeId).setAccept(true).build().toByteString(), Deadline.after(5_000))));

            assertThat(late.getErrorMessage().getId()).as("第 45 秒整：切磋邀请已过期").isEqualTo(16012);
            assertThat(context.getBean(FakeGatherLauncher.class).plans).isEmpty();
        });
    }
}
