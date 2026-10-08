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
import com.game.match.support.MatchModes;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketHealing;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 四项读全部走生产的读法、读真 Redis 上按生产写法写下的键——在线目录、战斗锁、位置记录三项经 {@link RedisPlayerStatusReader}
 * （包 xm-discovery 的三个目录；gate 写的在线目录条目、scene 写的位置记录三种状态、scene 的战斗锁 Hash），第四项排队票据经真的自愈规则
 * {@link DefaultTicketHealing} + Redis 票据存储 {@link RedissonTicketStore}（票是用存储自己的脚本建的）。钉住的是「真数据的形状 → 预检结论」
 * 这条链，替身测不到。
 *
 * <p>用 DB 13、随机的玩家号，只删自己写的键（多人共用一台 Redis）；票据只建不入队的 matched / ready 票，不碰全局的队列注册集。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class MemberPrecheckIntegrationTest {

    private static RedissonClient redis;
    private static PlayerPresenceDirectory presences;
    private static PlayerLocationDirectory locations;

    private final List<Long> players = new ArrayList<>();
    /** 预检对谁做过第四项（票据）的判定，按次序：断言「前三项不过的人不碰票据」。 */
    private final List<Long> ticketChecks = new CopyOnWriteArrayList<>();

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
            keys.add(RedisKeys.matchTicket(playerId));
        }
        if (!keys.isEmpty()) {
            redis.getKeys().delete(keys.toArray(String[]::new));
        }
    }

    /** 第四项的票据判定：真的自愈规则 + Redis 票据存储（同一个客户端）；外面包一层只为记下「对谁判过」。 */
    private TicketHealing healing(RedissonClient client) {
        TicketHealing real = new DefaultTicketHealing(new RedissonTicketStore(client));
        return (playerId, d) -> {
            ticketChecks.add(playerId);
            return real.healOrBlock(playerId, d);
        };
    }

    private DefaultMemberPrecheck precheck(RedissonClient client) {
        return new DefaultMemberPrecheck(new RedisPlayerStatusReader(new BattleLockReader(client), new PlayerPresenceDirectory(client),
                new PlayerLocationDirectory(client)), healing(client));
    }

    /** 用存储自己的脚本给玩家建一张不入队的 matched 票（PVE_SOLO 的建法），TTL 60 s。 */
    private static String matchedTicket(long playerId) {
        String ticketId = UUID.randomUUID().toString();
        TicketStore.JoinResult created = new RedissonTicketStore(redis).createMatched(playerId, ticketId, MatchModes.PVE_SOLO, 1, 1, 150_000,
                60_000, d());
        assertThat(created).isInstanceOf(TicketStore.JoinResult.Created.class);
        return ticketId;
    }

    private static Optional<Ticket> ticketOf(long playerId) {
        return new RedissonTicketStore(redis).read(playerId, d());
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
        assertThat(ticketChecks).as("两人都走到了第四项，按名单顺序").containsExactly(a, b);
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
        assertThat(ticketChecks).as("有锁的人不碰票据").containsExactly(a);

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
        assertThat(ticketChecks).isEmpty();
    }

    @Test
    void 三项都过之后才轮到票据_真的matched票是在途票_TICKET_IN_FLIGHT_票原样留着() throws Exception {
        long a = online(1, 7);
        long b = online(1, 7);
        String ticketId = matchedTicket(b);

        assertThat(precheck(redis).check(List.of(a, b), d())).isEqualTo(Result.failed(Reason.TICKET_IN_FLIGHT, b));
        assertThat(ticketChecks).containsExactly(a, b);
        Ticket still = ticketOf(b).orElseThrow();
        assertThat(still.ticketId()).as("在途票不被预检动").isEqualTo(ticketId);
        assertThat(still.state()).isEqualTo(TicketState.MATCHED);
    }

    @Test
    void 上一场留下的ready票_没有战斗锁时被自愈清掉_预检通过_有战斗锁时轮不到票据所以票还在() throws Exception {
        long a = online(1, 7);
        long b = online(1, 7);
        RedissonTicketStore store = new RedissonTicketStore(redis);
        for (long playerId : List.of(a, b)) {
            String ticketId = matchedTicket(playerId);
            assertThat(store.markReady(new TicketRef(playerId, ticketId), 9001, 60_000, d())).isTrue();
            assertThat(ticketOf(playerId).orElseThrow().state()).isEqualTo(TicketState.READY);
        }
        lock(b);

        Result blocked = precheck(redis).check(List.of(a, b), d());

        assertThat(blocked).as("b 还在战斗里：先报战斗锁").isEqualTo(Result.failed(Reason.IN_BATTLE, b));
        assertThat(ticketOf(a)).as("a 没有战斗锁：ready 票是残留，已被条件删清掉").isEmpty();
        assertThat(ticketOf(b).orElseThrow().state()).as("b 有战斗锁：没走到票据这一项，ready 票原样").isEqualTo(TicketState.READY);

        redis.getKeys().delete(RedisKeys.battleLock(b));
        Result passed = precheck(redis).check(List.of(a, b), d());

        assertThat(passed.passed()).isTrue();
        assertThat(ticketOf(b)).as("战斗结束后 b 的残留票同样被清掉").isEmpty();
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
