package com.game.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.common.token.DubboCallAuth;
import com.game.discovery.NodeIdLease;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.dispatch.TeamDispatcher;
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
import javax.sql.DataSource;
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

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TeamConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RedissonClient.class, () -> fake.client)
            .withBean(DataSource.class, TeamConfigurationTest::playerTable)
            .withBean(TeamProperties.class, () -> new TeamProperties(null, null, null, null, 2, null, 1, null));

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
