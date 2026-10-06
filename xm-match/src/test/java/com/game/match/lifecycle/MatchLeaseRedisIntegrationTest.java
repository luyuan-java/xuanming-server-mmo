package com.game.match.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.match.MatchConfiguration;
import com.game.match.MatchInstance;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.proto.match.JoinQueueResponse;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.boot.actuate.health.Status;

/**
 * 发号租约连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}；DB 13）：{@code MatchConfiguration} 的装配 + 真的 {@code NodeIdLease}
 * 与它的续期 / 释放脚本。钉住 lead 裁决 2 的事实基础——号被别的实例占走、或租约键过期之后，下一次续期就判丢失且不再恢复；丢失后健康检查 DOWN、
 * gauge 置 1、派发器拒收排队；丢失的租约在关闭时不去删别人的号。续期由测试手动触发（换掉调度器，脚本仍是真的），不必干等 5 s 的续期周期。
 *
 * <p>只碰自己占到的号的租约键（{@code xm:node-id:match:0:<n>}，值是本用例随机生成的实例 id），用完即删；防护代次计数器按设计永不删除。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class MatchLeaseRedisIntegrationTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static RedissonClient redis;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final MatchConfiguration configuration = new MatchConfiguration();
    /** 本用例写过的键：键 → 写进去的值；收尾时只在值仍是自己写的那个时才删。 */
    private final List<String[]> written = new ArrayList<>();

    /** 一个进程实例的发号装配：真的租约，续期由 {@link #renew()} 手动触发。 */
    private final class Instance {
        final String id = "it-" + UUID.randomUUID();
        final ArgumentCaptor<Runnable> renewal = ArgumentCaptor.forClass(Runnable.class);
        final NodeIdLease lease;
        final MatchIds ids;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Instance() {
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            when(scheduler.scheduleAtFixedRate(renewal.capture(), anyLong(), anyLong(), any(TimeUnit.class))).thenReturn((ScheduledFuture) mock(ScheduledFuture.class));
            lease = configuration.matchIdLease(redis, scheduler, new MatchInstance(id), metrics, new MatchStartupChecks.Passed(4200, 1));
            ids = configuration.matchIds(lease);
            written.add(new String[] {key(), id});
        }

        String key() {
            return RedisKeys.nodeId(NodeTypes.MATCH, 0, lease.nodeId());
        }

        void renew() {
            renewal.getValue().run();
        }
    }

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @AfterEach
    void deleteOwnKeys() {
        for (String[] entry : written) {
            RBucket<String> bucket = bucket(entry[0]);
            if (entry[1].equals(bucket.get())) {
                bucket.delete();
            }
        }
    }

    private static RBucket<String> bucket(String key) {
        return redis.getBucket(key, StringCodec.INSTANCE);
    }

    private static ClientCall join() {
        return ClientCall.newBuilder().setMessageId(REGISTRY.requireId(MatchMethods.SERVICE, MatchMethods.JOIN_QUEUE)).setBody(ByteString.EMPTY)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(2).setPlayerId(1001)).build();
    }

    @Test
    void 占号_续期保持有效_关闭时交还() {
        Instance instance = new Instance();

        assertThat(bucket(instance.key()).get()).as("租约键的值是本实例 id").isEqualTo(instance.id);
        assertThat(bucket(instance.key()).remainTimeToLive()).as("TTL 15 s").isBetween(10_000L, 15_000L);
        assertThat(instance.lease.nodeId()).isBetween(0, Snowflake.MAX_WORKER);

        instance.renew();
        instance.renew();

        assertThat(instance.ids.leaseLost()).isFalse();
        assertThat(instance.ids.leaseValid()).isTrue();
        long battleId = instance.ids.nextBattleId().orElseThrow();
        assertThat(Snowflake.workerOf(battleId)).as("battle_id 里的 worker 就是占到的号").isEqualTo(instance.lease.nodeId());
        assertThat(meters.get("xm.match.lease.lost").gauge().value()).isZero();

        instance.lease.close();
        assertThat(bucket(instance.key()).isExists()).as("交还：租约键已删").isFalse();
    }

    @Test
    void 两个实例占到不同的号_发出的号不撞() {
        Instance first = new Instance();
        Instance second = new Instance();

        assertThat(second.lease.nodeId()).isNotEqualTo(first.lease.nodeId());
        long a = first.ids.nextBattleId().orElseThrow();
        long b = second.ids.nextBattleId().orElseThrow();
        assertThat(a).isNotEqualTo(b);
        assertThat(Snowflake.workerOf(a)).isNotEqualTo(Snowflake.workerOf(b));

        first.lease.close();
        second.lease.close();
    }

    @Test
    void 号被别的实例占走_下一次续期判丢失_不再恢复_健康DOWN_gauge为1_拒收排队_关闭时不删别人的号() throws Exception {
        Instance instance = new Instance();
        AtomicInteger joined = new AtomicInteger();
        MatchMethodHandler joinHandler = new MatchMethodHandler() {
            @Override
            public String method() {
                return MatchMethods.JOIN_QUEUE;
            }

            @Override
            public boolean inline() {
                return true;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline) {
                joined.incrementAndGet();
                return Reply.empty();
            }

            @Override
            public Reply onOverload() {
                return Reply.envelope(1003);
            }
        };
        MatchDispatcher dispatcher = new MatchDispatcher(REGISTRY, List.of(joinHandler), Runnable::run, metrics, 4500, instance.ids::leaseLost);
        MatchLeaseHealthIndicator health = configuration.matchLeaseHealthIndicator(instance.ids);
        dispatcher.dispatch(join()).get(5, TimeUnit.SECONDS);
        assertThat(joined).hasValue(1);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        // 别的实例占走了这个号（Redis 中断超过 TTL、键过期之后被人重新占到就是这个局面）
        String usurper = "usurper-" + UUID.randomUUID();
        bucket(instance.key()).set(usurper, Duration.ofSeconds(15));
        written.add(new String[] {instance.key(), usurper});
        instance.renew();

        assertThat(instance.ids.leaseLost()).isTrue();
        assertThat(instance.ids.nextBattleId()).isEmpty();
        assertThat(meters.get("xm.match.lease.lost").gauge().value()).isEqualTo(1.0);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        ClientReply refused = dispatcher.dispatch(join()).get(5, TimeUnit.SECONDS);
        assertThat(JoinQueueResponse.parseFrom(refused.getBody()).getErrorCode()).isEqualTo(16004);
        assertThat(joined).as("丢失之后排队不再进处理器").hasValue(1);

        // 号「还回来」也不恢复：丢失是单向的
        bucket(instance.key()).set(instance.id, Duration.ofSeconds(15));
        instance.renew();
        assertThat(instance.ids.leaseLost()).isTrue();
        assertThat(instance.ids.leaseValid()).isFalse();

        bucket(instance.key()).set(usurper, Duration.ofSeconds(15));
        instance.lease.close();
        assertThat(bucket(instance.key()).get()).as("已丢失的租约关闭时不执行释放：别人的号原样留着").isEqualTo(usurper);
    }

    @Test
    void 租约键过期_下一次续期同样判丢失() {
        Instance instance = new Instance();

        bucket(instance.key()).delete(); // 等价于 TTL 到期
        instance.renew();

        assertThat(instance.ids.leaseLost()).isTrue();
        assertThat(meters.get("xm.match.lease.lost").gauge().value()).isEqualTo(1.0);
        assertThat(bucket(instance.key()).isExists()).as("丢失后不会把号重新占回来").isFalse();
    }
}
