package com.game.team.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.rules.SessionState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 会话四态在真 Redis 上的映射：真的 {@code xm:presence} 条目（完好 / 损坏 / 与键不符 / 缺失）× 真的 {@code xm:location} 记录
 * （{@code s} = o / l / x / 不认识的值 / 缺失 / 键类型不对）。读失败（整路失败、超时）在 {@link TeamSessionsTest} 用假实现覆盖。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}（DB 12，随机 ≥ 2^63 的 id，只删自己写的键）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamSessionsRedisIntegrationTest {

    private static final int DB = 12;

    private static RedissonClient redis;
    private final List<String> written = new ArrayList<>();
    private final long base = Long.MIN_VALUE + (1L << 54) + ThreadLocalRandom.current().nextLong(1L << 38) * 64;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void shutdown() {
        redis.shutdown();
    }

    @AfterEach
    void cleanup() {
        if (!written.isEmpty()) {
            redis.getKeys().delete(written.toArray(String[]::new));
        }
    }

    private void presence(long pid, byte[] value) {
        String key = RedisKeys.presence(pid);
        written.add(key);
        redis.<byte[]>getBucket(key, ByteArrayCodec.INSTANCE).set(value);
    }

    private void location(long pid, String state) {
        String key = RedisKeys.playerLocation(pid);
        written.add(key);
        redis.<String, String>getMap(key, StringCodec.INSTANCE).putAll(Map.of("e", "1", "q", "1", "s", state));
    }

    private void locationWrongType(long pid) {
        String key = RedisKeys.playerLocation(pid);
        written.add(key);
        redis.<String>getBucket(key, StringCodec.INSTANCE).set("not-a-hash");
    }

    @Test
    void 真实条目的状态矩阵() {
        PlayerPresenceDirectory presenceDir = new PlayerPresenceDirectory(redis);
        PlayerLocationDirectory locationDir = new PlayerLocationDirectory(redis);
        TeamSessions sessions = new TeamSessions(presenceDir::findEachStrictAsync, locationDir::statusesAsync,
                presenceDir::findStrictAsync);

        Map<Long, SessionState> want = new LinkedHashMap<>();
        int n = 0;
        // presence 完好：不看 location
        long online = base + (++n);
        presence(online, PlayerPresence.newBuilder().setPlayerId(online).setGateNodeId(1).build().toByteArray());
        location(online, "x");
        want.put(online, SessionState.ONLINE);
        // presence 损坏 / 与键不符：未知（不看 location）
        long corrupt = base + (++n);
        presence(corrupt, new byte[] {(byte) 0xFF, (byte) 0xFF, 0x01});
        location(corrupt, "o");
        want.put(corrupt, SessionState.UNKNOWN);
        long mismatch = base + (++n);
        presence(mismatch, PlayerPresence.newBuilder().setPlayerId(mismatch + 1).build().toByteArray());
        want.put(mismatch, SessionState.UNKNOWN);
        // presence 缺失：看 location
        long sceneOnline = base + (++n);
        location(sceneOnline, "o");
        want.put(sceneOnline, SessionState.PRESENT);
        long lease = base + (++n);
        location(lease, "l");
        want.put(lease, SessionState.PRESENT);
        long loggedOut = base + (++n);
        location(loggedOut, "x");
        want.put(loggedOut, SessionState.ABSENT);
        long missing = base + (++n);
        want.put(missing, SessionState.ABSENT);
        long badState = base + (++n);
        location(badState, "?");
        want.put(badState, SessionState.UNKNOWN);
        long wrongType = base + (++n);
        locationWrongType(wrongType);
        want.put(wrongType, SessionState.UNKNOWN);

        Map<Long, SessionState> got = sessions.load(new ArrayList<>(want.keySet()), Deadline.after(5000));
        assertThat(got).isEqualTo(want);

        assertThat(sessions.isOnline(online, Deadline.after(5000))).isTrue();
        assertThat(sessions.isOnline(sceneOnline, Deadline.after(5000))).as("presence 缺失 = 不在线（邀请回 4017）").isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessions.isOnline(corrupt, Deadline.after(5000)))
                .as("条目损坏是故障（邀请回 4030），不是离线").isInstanceOf(Deadline.DependencyException.class);
    }
}
