package com.game.match.precheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.port.RedisPlayerStatusReader;
import com.game.match.precheck.MemberPrecheck.Reason;
import com.game.match.precheck.MemberPrecheck.Result;
import com.game.match.testing.FakeTicketHealing;
import com.game.match.ticket.TicketHealing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
 * 成员预检连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}；match-spec §15.3「{@code MemberPrecheck} 的四项读」）：
 * 在线目录、战斗锁、位置记录三项都经生产的读法（{@link RedisPlayerStatusReader} 包 xm-discovery 的三个目录）读真 Redis 上按生产写法写下的键——
 * gate 写的在线目录条目、scene 写的位置记录三种状态、scene 的战斗锁 Hash。钉住的是「真数据的形状 → 预检结论」这条链，替身测不到。
 *
 * <p>第四项（排队票据）在这里用 {@link #healing} 的替身：票据存储的 Redis 实现与自愈规则属于票据包，它自己的集成测试连真 Redis 验；
 * 两个包合到一起之后把 {@link #healing} 换成真的实现即可（见该方法的注释）。
 *
 * <p>用 DB 13、随机的玩家号，只删自己写的键（多人共用一台 Redis）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class MemberPrecheckIntegrationTest {

    private static RedissonClient redis;
    private static PlayerPresenceDirectory presences;
    private static PlayerLocationDirectory locations;

    private final List<Long> players = new ArrayList<>();
    private final FakeTicketHealing tickets = new FakeTicketHealing();

    @BeforeAll
    static void connect() {
        redis = Redisson.create(config());
        presences = new PlayerPresenceDirectory(redis);
        locations = new PlayerLocationDirectory(redis);
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    private static Config config() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        return config;
    }

    @AfterEach
    void cleanup() {
        List<String> keys = new ArrayList<>();
        for (long playerId : players) {
            keys.add(RedisKeys.presence(playerId));
            keys.add(RedisKeys.playerLocation(playerId));
            keys.add(RedisKeys.battleLock(playerId));
        }
        if (!keys.isEmpty()) {
            redis.getKeys().delete(keys.toArray(String[]::new));
        }
    }

    /**
     * 第四项的票据判定。票据包的真实现合入之后，换成「真的自愈规则 + Redis 票据存储」（同一个 {@code redis}），
     * 并把下面用 {@code tickets.inFlight} 摆状态的地方改成往票据存储里真的建票。
     */
    private TicketHealing healing() {
        return tickets;
    }

    private DefaultMemberPrecheck precheck(RedissonClient client) {
        return new DefaultMemberPrecheck(new RedisPlayerStatusReader(new BattleLockReader(client), new PlayerPresenceDirectory(client),
                new PlayerLocationDirectory(client)), healing());
    }

    private static Deadline d() {
        return Deadline.after(5_000);
    }

    private long newPlayer() {
        long playerId = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 62);
        players.add(playerId);
        return playerId;
    }

    /** 在游戏里：gate 写在线目录条目，scene 写在线的位置记录。 */
    private long online(int zoneId, int sceneNodeId) throws Exception {
        long playerId = newPlayer();
        presences.putAsync(PlayerPresence.newBuilder().setPlayerId(playerId).setZoneId(zoneId).setGateNodeId(1).setGateInstanceId("gate-it")
                .setSessionId(7).setOnlineSinceMs(1).setOwnerEpoch(3).build()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(locations.putAsync(location(playerId, zoneId, sceneNodeId), 1).toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
        return playerId;
    }

    private static PlayerLocation location(long playerId, int zoneId, int sceneNodeId) {
        return PlayerLocation.newBuilder().setPlayerId(playerId).setZoneId(zoneId).setSceneNodeId(sceneNodeId).setSceneId(5000).setSceneConfigId(1)
                .setOwnerEpoch(3).build();
    }

    /** scene 的战斗锁：一个 Hash（字段 b = battle_id）；预检只看键在不在。 */
    private static void lock(long playerId) {
        redis.<String, String>getMap(RedisKeys.battleLock(playerId), StringCodec.INSTANCE).put("b", "9001");
        redis.getKeys().expire(RedisKeys.battleLock(playerId), 60, TimeUnit.SECONDS);
    }

    @Test
    void 全员在游戏里_通过_zone取自各人的位置记录() throws Exception {
        long a = online(1, 7);
        long b = online(2, 8);

        Result result = precheck(redis).check(List.of(a, b), d());

        assertThat(result.passed()).isTrue();
        assertThat(result.zones()).containsExactly(Map.entry(a, 1), Map.entry(b, 2));
        assertThat(tickets.calls).containsExactly(a, b);
    }

    @Test
    void 在线目录没有条目_OFFLINE_哪怕位置记录还在() throws Exception {
        long a = online(1, 7);
        long gone = newPlayer();
        assertThat(locations.putAsync(location(gone, 1, 7), 1).toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();

        assertThat(precheck(redis).check(List.of(a, gone), d())).isEqualTo(Result.failed(Reason.OFFLINE, gone));
    }

    @Test
    void 在线目录条目损坏_算读失败_不算不在线() throws Exception {
        long a = online(1, 7);
        redis.<byte[]>getBucket(RedisKeys.presence(a), ByteArrayCodec.INSTANCE).set(new byte[] {(byte) 0xFF, (byte) 0xFF, 0x01}, java.time.Duration.ofSeconds(60));

        assertThat(precheck(redis).check(List.of(a), d())).isEqualTo(Result.failed(Reason.PRESENCE_READ_FAILED, a));
    }

    @Test
    void 战斗锁的Hash存在_IN_BATTLE_锁删掉之后通过() throws Exception {
        long a = online(1, 7);
        long b = online(1, 7);
        lock(b);

        assertThat(precheck(redis).check(List.of(a, b), d())).isEqualTo(Result.failed(Reason.IN_BATTLE, b));
        assertThat(tickets.calls).as("有锁的人不碰票据").containsExactly(a);

        redis.getKeys().delete(RedisKeys.battleLock(b));
        assertThat(precheck(redis).check(List.of(a, b), d()).passed()).isTrue();
    }

    @Test
    void 位置记录的三种非在线状态与节点号为0_都是NO_LOCATION() throws Exception {
        long leased = online(1, 7);
        assertThat(locations.leaseAsync(location(leased, 1, 7), 2).toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
        long loggedOut = online(1, 7);
        assertThat(locations.removeAsync(loggedOut, 3, 2).toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
        long missing = online(1, 7);
        redis.getKeys().delete(RedisKeys.playerLocation(missing));
        long noNode = online(1, 0);

        DefaultMemberPrecheck precheck = precheck(redis);
        assertThat(precheck.check(List.of(leased), d())).as("重连租约").isEqualTo(Result.failed(Reason.NO_LOCATION, leased));
        assertThat(precheck.check(List.of(loggedOut), d())).as("登出墓碑").isEqualTo(Result.failed(Reason.NO_LOCATION, loggedOut));
        assertThat(precheck.check(List.of(missing), d())).as("没有记录").isEqualTo(Result.failed(Reason.NO_LOCATION, missing));
        assertThat(precheck.check(List.of(noNode), d())).as("节点号为 0").isEqualTo(Result.failed(Reason.NO_LOCATION, noNode));
        assertThat(tickets.calls).isEmpty();
    }

    @Test
    void 三项都过之后才轮到票据_在途票_TICKET_IN_FLIGHT() throws Exception {
        long a = online(1, 7);
        long b = online(1, 7);
        tickets.inFlight(b, "t-b");

        assertThat(precheck(redis).check(List.of(a, b), d())).isEqualTo(Result.failed(Reason.TICKET_IN_FLIGHT, b));
    }

    @Test
    void Redis不可用_第一项读就失败_PRESENCE_READ_FAILED_在预算内返回() throws Exception {
        long a = online(1, 7);
        RedissonClient closed = Redisson.create(config());
        DefaultMemberPrecheck broken = precheck(closed);
        assertThat(broken.check(List.of(a), d()).passed()).isTrue();
        closed.shutdown();

        long started = System.nanoTime();
        Result result = broken.check(List.of(a), Deadline.after(1_500));

        assertThat(result).as("故障不折成「不在线」").isEqualTo(Result.failed(Reason.PRESENCE_READ_FAILED, a));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
    }
}
