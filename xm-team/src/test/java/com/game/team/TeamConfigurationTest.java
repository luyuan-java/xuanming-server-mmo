package com.game.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.DubboGroups;
import com.game.common.deadline.Deadline;
import com.game.common.token.DubboCallAuth;
import com.game.discovery.NodeIdLease;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.dispatch.TeamDispatcher;
import com.game.team.dispatch.TeamWorkerPool;
import com.game.team.match.FakeMatchTeamService;
import com.game.team.match.MatchTeamBattle;
import com.game.team.match.TeamBattlePort;
import com.game.team.presence.TeamDisplay;
import com.game.team.service.TeamService;
import com.game.team.view.MemberDisplay;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RBuckets;
import org.redisson.api.RFuture;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 装配（{@code ApplicationContextRunner}，不连 Redis / MySQL、不开端口）：{@link TeamConfiguration} 起得来，并且队伍视图的 in_battle 真的接到了
 * 战斗锁的批量读上（scene-battle-spec §7.1 模块表 xm-team 行、§7.13 世界内部第 4 条）——Redis 是只应答本测试用到的那几条命令的替身，
 * {@code player} 表在 H2 内存库里。{@code XM_DUBBO_SECRET} 由 surefire 注入（pom.xml，仅测试用的假值）。
 */
class TeamConfigurationTest {

    /** ≥ 2^63：锁键里必须是无符号十进制。 */
    private static final long FIGHTER = Long.MIN_VALUE + 4301;
    private static final long IDLE = 4302;
    private static final long UNKNOWN = Long.MIN_VALUE + 4303;

    private final FakeRedis fake = new FakeRedis();

    private final ApplicationContextRunner base = new ApplicationContextRunner()
            .withUserConfiguration(TeamConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RedissonClient.class, () -> fake.client)
            .withBean(DataSource.class, TeamConfigurationTest::playerTable)
            .withBean(TeamProperties.class, () -> new TeamProperties(null, null, null, null, 2, null, 1, null, 1, null));

    /** 带上整队开战端口的替身（生产里它来自 {@link TeamDubboConfiguration}，那边要起 Dubbo）。 */
    private final ApplicationContextRunner runner = base
            .withBean(TeamBattlePort.class, () -> new MatchTeamBattle(new FakeMatchTeamService()));

    @Test
    void 上下文起得来_队伍视图的in_battle接在战斗锁的逐键EXISTS上_资料与在线各走各的来源() {
        fake.lockedKeys.add("xm:battle:{" + Long.toUnsignedString(FIGHTER) + "}:lock");
        fake.presence.put(RedisKeys.presence(IDLE), PlayerPresence.newBuilder().setPlayerId(IDLE).setGateNodeId(1).build().toByteArray());

        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed()
                    .hasSingleBean(BattleLockReader.class)
                    .hasSingleBean(TeamDisplay.class)
                    .hasSingleBean(TeamService.class)
                    .hasSingleBean(TeamDispatcher.class)
                    .hasSingleBean(NodeIdLease.class)
                    .hasSingleBean(DubboCallAuth.class);

            Map<Long, MemberDisplay> dc = ctx.getBean(TeamDisplay.class).load(List.of(FIGHTER, IDLE, UNKNOWN), Deadline.after(3000));

            assertThat(dc).containsExactly(
                    Map.entry(FIGHTER, new MemberDisplay(false, true, 31, 2, "甲", "ap-1", 1)),
                    Map.entry(IDLE, new MemberDisplay(true, false, 7, 3, "乙", "", 2)),
                    Map.entry(UNKNOWN, MemberDisplay.NONE));
            assertThat(fake.existsAsked).as("每人一条 EXISTS xm:battle:{pid}:lock，键里的 pid 是无符号十进制").containsExactlyInAnyOrder(
                    "xm:battle:{9223372036854780109}:lock", "xm:battle:{4302}:lock", "xm:battle:{9223372036854780111}:lock");
        });
    }

    @Test
    void 战斗锁读不到_上下文里的视图照常返回_in_battle按false() {
        fake.lockedKeys.add(RedisKeys.battleLock(FIGHTER));
        fake.existsError = new IllegalStateException("Unable to send command! Node source: NodeSource [slot=0]");

        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            Map<Long, MemberDisplay> dc = ctx.getBean(TeamDisplay.class).load(List.of(FIGHTER, IDLE), Deadline.after(3000));
            assertThat(dc).containsExactly(
                    Map.entry(FIGHTER, new MemberDisplay(false, false, 31, 2, "甲", "ap-1", 1)),
                    Map.entry(IDLE, new MemberDisplay(false, false, 7, 3, "乙", "", 2)));
        });
    }

    @Test
    void 三个线程池各司其职_整队开战收尾池单独一个() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(TeamWorkerPool.class).keySet())
                    .containsExactlyInAnyOrder("teamWorkerPool", "teamMatchEndPool", "teamPushPool");
            // 线程名就是池名：收尾任务（清开战锁，最坏阻塞 110 s）不在请求工作线程上跑
            AtomicReference<String> thread = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            ctx.getBean("teamMatchEndPool", TeamWorkerPool.class).execute(() -> {
                thread.set(Thread.currentThread().getName());
                ran.countDown();
            });
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("team-match-end-");
        });
    }

    @Test
    void 没有整队开战端口就起不来_不会悄悄退回一个恒拒绝的占位实现() {
        base.run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                .hasMessageContaining(TeamBattlePort.class.getName()));
    }

    @Test
    void 调xm_match的引用_group是match_不重试_不查存活_地址取配置键() throws Exception {
        DubboReference reference = TeamDubboConfiguration.class.getMethod("matchTeamService").getAnnotation(DubboReference.class);

        assertThat(reference.group()).isEqualTo(DubboGroups.MATCH).isEqualTo("match");
        assertThat(reference.retries()).as("建票与 gather 不能被 Dubbo 的 failover 重发").isZero();
        assertThat(reference.check()).as("xm-match 不在时 xm-team 照常启动").isFalse();
        assertThat(reference.url()).as("local 直连、nacos profile 置空走注册中心").isEqualTo("${xm.dubbo.match-url:}");
        assertThat(reference.timeout()).as("引用上的兜底超时 = 每跳上限 3 s").isEqualTo(3000);
        FakeMatchTeamService match = new FakeMatchTeamService();
        TeamBattlePort port = new TeamDubboConfiguration().teamBattlePort(match, "tri://127.0.0.1:20888");
        assertThat(port).isInstanceOf(MatchTeamBattle.class);
        port.checkTeamMatch(1, List.of(FIGHTER), Deadline.after(3000));
        assertThat(match.checks).as("端口调的就是注入的那个引用").hasSize(1);
    }

    /** H2 内存库里的 {@code player} 表（只含 {@code PlayerProfiles} 读的那几列；player_id 是 unsigned 64 位，用 NUMERIC(20)）。 */
    private static DataSource playerTable() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:team-config-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE player (player_id NUMERIC(20) PRIMARY KEY, name VARCHAR(64), level INT, class_id INT,"
                    + " gender INT, appearance_id VARCHAR(64), zone_id INT)");
            s.execute("INSERT INTO player VALUES (" + Long.toUnsignedString(FIGHTER) + ", '甲', 31, 2, 1, 'ap-1', 3)");
            s.execute("INSERT INTO player VALUES (" + IDLE + ", '乙', 7, 3, 2, '', 3)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return ds;
    }

    /**
     * 只应答装配与一次展示缓存读会用到的命令：节点号租约（SET NX / INCR / 释放脚本）、在线目录的 MGET、战斗锁的 EXISTS。
     * 其余调用回 Mockito 的缺省值（null / 0），本测试不会走到。
     */
    private static final class FakeRedis {

        final RedissonClient client = mock(RedissonClient.class);
        final List<String> lockedKeys = new CopyOnWriteArrayList<>();
        final List<String> existsAsked = new CopyOnWriteArrayList<>();
        final Map<String, byte[]> presence = new HashMap<>();
        volatile RuntimeException existsError;

        @SuppressWarnings("unchecked")
        FakeRedis() {
            RBucket<Object> bucket = mock(RBucket.class);
            when(bucket.setIfAbsent(any(), any(Duration.class))).thenReturn(true);
            doReturn(bucket).when(client).getBucket(anyString(), any(Codec.class));
            RAtomicLong epoch = mock(RAtomicLong.class);
            when(epoch.incrementAndGet()).thenReturn(1L);
            when(client.getAtomicLong(anyString())).thenReturn(epoch);
            when(client.getScript(any(Codec.class))).thenReturn(mock(RScript.class));

            RBuckets buckets = mock(RBuckets.class);
            when(buckets.getAsync(any(String[].class))).thenAnswer(inv -> {
                Map<String, byte[]> hit = new HashMap<>();
                for (String key : strings(inv.getArguments())) {
                    byte[] value = presence.get(key);
                    if (value != null) {
                        hit.put(key, value);
                    }
                }
                return future(hit);
            });
            when(client.getBuckets(any(Codec.class))).thenReturn(buckets);

            RKeys keys = mock(RKeys.class);
            when(keys.countExistsAsync(any(String[].class))).thenAnswer(inv -> {
                long n = 0;
                for (String key : strings(inv.getArguments())) {
                    existsAsked.add(key);
                    if (lockedKeys.contains(key)) {
                        n++;
                    }
                }
                RuntimeException error = existsError;
                return error != null ? new CompletableFutureWrapper<Long>(error) : future(n);
            });
            when(client.getKeys()).thenReturn(keys);
        }

        /** 变长参数在不同 Mockito 版本里可能是展开的、也可能是一个数组：两种都摊平成键的列表。 */
        private static List<String> strings(Object[] arguments) {
            List<String> out = new ArrayList<>();
            for (Object argument : arguments) {
                if (argument instanceof String[] array) {
                    out.addAll(List.of(array));
                } else {
                    out.add((String) argument);
                }
            }
            return out;
        }

        private static <T> RFuture<T> future(T value) {
            return new CompletableFutureWrapper<T>(CompletableFuture.completedFuture(value));
        }
    }
}
