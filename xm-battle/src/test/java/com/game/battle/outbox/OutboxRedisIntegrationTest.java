package com.game.battle.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.game.battle.outbox.OutboxMetrics.ActivityEvent;
import com.game.battle.outbox.OutboxMetrics.SettlementEvent;
import com.game.battle.port.BattleResultSink;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.testing.CallJournal;
import com.game.battle.testing.FakeSceneLocator;
import com.game.battle.testing.RecordingSceneTransport;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleSettlementEvent;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/**
 * 两个发件箱接在<b>真 Redis、真脚本</b>（{@code Store.redis(BattleRedis)}）与真的单线程 {@code battle-outbox} 上的整链
 * （scene-battle-spec §13.4 末条「发件箱：落库 → scene 销账 → 探测得到已销账」；审计 OBX-7 的端到端证据）。scene 一侧由测试直接跑它的脚本
 * （{@code ACK}、写锁）；定位与传输仍是假件。脚本逐段的真值表在 xm-discovery 的 {@code BattleRedisScriptsIntegrationTest}。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 14（xm-battle 的测试库）与随机大号 id（≥ 2^63），只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class OutboxRedisIntegrationTest {

    static final int DB = 14;

    private static final long PLAYER_BASE = Long.MIN_VALUE + (1L << 50) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static final long BATTLE_BASE = Long.MIN_VALUE + (1L << 51) + ThreadLocalRandom.current().nextLong(1L << 40);
    /** 本局 X 与下一局 Y。 */
    private static final long X = BATTLE_BASE + 1;
    private static final long Y = BATTLE_BASE + 2;
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private static final AtomicLong sequence = new AtomicLong();
    private static final Set<String> written = ConcurrentHashMap.newKeySet();
    private static RedissonClient redis;
    private static BattleRedis scripts;

    private final DefaultEventLoop loop = new DefaultEventLoop();
    private final EventLoopBattleScheduler scheduler = new EventLoopBattleScheduler(loop);
    private final CallJournal journal = new CallJournal();
    private final FakeSceneLocator locator = new FakeSceneLocator(journal);
    private final RecordingSceneTransport transport = new RecordingSceneTransport(journal);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry);

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        redis = Redisson.create(config);
        scripts = new BattleRedis(redis);
    }

    @AfterAll
    static void cleanup() {
        if (!written.isEmpty()) {
            redis.getKeys().delete(written.toArray(String[]::new));
        }
        redis.shutdown();
    }

    @AfterEach
    void stopLoop() throws InterruptedException {
        loop.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).await(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ 夹具

    /** 一个新玩家号；它的锁、待结算记录、X / Y 两局的墓碑都登记进清理名单。 */
    private static long newPlayer() {
        long player = PLAYER_BASE + sequence.incrementAndGet();
        written.add(RedisKeys.battleLock(player));
        written.add(RedisKeys.battleSettlements(player));
        written.add(RedisKeys.battleSettled(player, X));
        written.add(RedisKeys.battleSettled(player, Y));
        return player;
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static BattleRouting routing() {
        return BattleRouting.newBuilder().setZoneId(1).setSceneNodeId(9).setSceneInstanceId("scene-at-start").build();
    }

    private static BattleSettlementData settlement(long playerId, long battleId) {
        return BattleSettlementData.newBuilder().setBattleId(battleId).setPlayerId(playerId).setGoldGain(100).setExpGain(7).build();
    }

    private static byte[] wire(long playerId, long battleId) {
        return BattleSettlementEvent.newBuilder().setSettlement(settlement(playerId, battleId)).build().toByteArray();
    }

    private SettlementOutbox outbox(SettlementOutbox.Store store) {
        return new SettlementOutbox(scheduler, store, locator::locate, transport::applySettlement, metrics,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    private int count(SettlementEvent event) {
        return (int) registry.get(OutboxMetrics.SETTLEMENT_OUTBOX).tag("event", event.name().toLowerCase(Locale.ROOT)).counter().count();
    }

    private int count(ActivityEvent event) {
        return (int) registry.get(OutboxMetrics.ACTIVITY_OUTBOX).tag("event", event.name().toLowerCase(Locale.ROOT)).counter().count();
    }

    private int sizeOf(SettlementOutbox outbox) throws Exception {
        return loop.submit((Callable<Integer>) outbox::size).get(5, TimeUnit.SECONDS);
    }

    // ================================================================== 结算发件箱

    @Test
    void 落库_首投_scene销账_探测得到已销账_摘除_之后重入的落库被墓碑挡下不再投递() throws Exception {
        long player = newPlayer();
        locator.online(player, 1, 3, "scene-3");
        SettlementOutbox outbox = outbox(SettlementOutbox.Store.redis(scripts));

        outbox.dispatch(routing(), player, settlement(player, X));
        await().atMost(PATIENCE).until(() -> transport.sent.size() == 1 && outbox.inFlight() == 0);

        assertThat(count(SettlementEvent.STORED)).isEqualTo(1);
        assertThat(get(scripts.readSettlement(player, X))).as("Redis 里的记录就是投出去的那份字节").isEqualTo(wire(player, X))
                .isEqualTo(transport.sent.get(0).call().getBody().toByteArray());
        assertThat(redis.getKeys().remainTimeToLive(RedisKeys.battleSettlements(player)))
                .isBetween((BattleRedis.SETTLEMENT_TTL_SEC - 60) * 1000, BattleRedis.SETTLEMENT_TTL_SEC * 1000);
        assertThat(journal.entries()).containsExactly("locate:" + Long.toUnsignedString(player),
                "deliver:" + Long.toUnsignedString(player) + "/" + Long.toUnsignedString(X) + "@scene-3#0");
        assertThat(sizeOf(outbox)).isEqualTo(1);

        // scene 应用、落盘、销账（没有锁：位掩码只有「删了记录」）
        assertThat(get(scripts.ack(player, X))).isEqualTo(1L);
        loop.execute(outbox::tick);
        await().atMost(PATIENCE).until(() -> count(SettlementEvent.ACKED) == 1);
        assertThat(sizeOf(outbox)).isZero();
        assertThat(transport.sent).as("已销账的一轮不重投").hasSize(1);

        // 同一局重入（重复的 dispatch / 晚到的落库）：脚本回 -1
        outbox.dispatch(routing(), player, settlement(player, X));
        await().atMost(PATIENCE).until(() -> count(SettlementEvent.ALREADY_SETTLED) == 1 && outbox.inFlight() == 0);

        assertThat(count(SettlementEvent.STORED)).as("没有第二次落库成功").isEqualTo(1);
        assertThat(transport.sent).as("不再投递").hasSize(1);
        assertThat(get(scripts.settlementExists(player, X))).as("记录不复活").isFalse();
        assertThat(sizeOf(outbox)).isZero();
    }

    @Test
    void 落库结局不明_投一次后scene销账_那条落库这时才落地_被墓碑挡下_没有孤儿记录() throws Exception {
        long player = newPlayer();
        locator.online(player, 1, 3, "scene-3");
        SettlementOutbox.Store real = SettlementOutbox.Store.redis(scripts);
        AtomicReference<Supplier<CompletableFuture<Long>>> stillInTheSocket = new AtomicReference<>();
        // 响应超时：命令已经写出、还没执行——调用方先看到失败，真正的 EVAL 留到后面才落地
        SettlementOutbox outbox = outbox(new SettlementOutbox.Store() {
            @Override
            public CompletableFuture<Long> store(long playerId, long battleId, byte[] payload) {
                stillInTheSocket.set(() -> real.store(playerId, battleId, payload));
                return CompletableFuture.failedFuture(new TimeoutException("Redis 响应超时（测试）"));
            }

            @Override
            public CompletableFuture<Boolean> exists(long playerId, long battleId) {
                return real.exists(playerId, battleId);
            }

            @Override
            public CompletableFuture<Long> ackIfSuperseded(long playerId, long battleId) {
                return real.ackIfSuperseded(playerId, battleId);
            }
        });

        outbox.dispatch(routing(), player, settlement(player, X));
        await().atMost(PATIENCE).until(() -> transport.sent.size() == 1 && outbox.inFlight() == 0);
        assertThat(count(SettlementEvent.NOT_DURABLE)).isEqualTo(1);
        assertThat(sizeOf(outbox)).as("不登记").isZero();

        // scene 在线应用、落盘、销账：没有记录可删（位掩码 0），但留下墓碑
        assertThat(get(scripts.ack(player, X))).isZero();
        // 那条 EVAL 现在才落地
        assertThat(get(stillInTheSocket.get().get())).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);

        assertThat(get(scripts.settlementExists(player, X))).isFalse();
        assertThat(redis.getKeys().countExists(RedisKeys.battleSettlements(player))).as("没有孤儿记录，下次进场不会重复发奖").isZero();
        assertThat(redis.getKeys().remainTimeToLive(RedisKeys.battleSettled(player, X))).as("墓碑带 TTL")
                .isBetween((BattleRedis.SETTLED_TOMBSTONE_TTL_SEC - 60) * 1000, BattleRedis.SETTLED_TOMBSTONE_TTL_SEC * 1000);
    }

    @Test
    void 离线玩家的结算被下一局取代_已取代判定回2_摘除_记录删掉_下一局的锁不动_晚到的落库被挡() throws Exception {
        long player = newPlayer();
        SettlementOutbox outbox = outbox(SettlementOutbox.Store.redis(scripts));

        outbox.dispatch(routing(), player, settlement(player, X));
        await().atMost(PATIENCE).until(() -> count(SettlementEvent.STORED) == 1 && outbox.inFlight() == 0);
        assertThat(transport.sent).as("离线：首投解析不到，不投").isEmpty();
        assertThat(sizeOf(outbox)).isEqualTo(1);

        // 下一局 Y 备战写锁
        long now = System.currentTimeMillis();
        assertThat(get(scripts.prepareLock(player, Y, 7, now + 300_000, now + 60_000, 120))).isEqualTo("0");
        loop.execute(outbox::tick);
        await().atMost(PATIENCE).until(() -> count(SettlementEvent.SUPERSEDED) == 1);

        assertThat(count(SettlementEvent.SKIP_NO_TARGET)).isEqualTo(1);
        assertThat(sizeOf(outbox)).isZero();
        assertThat(get(scripts.settlementExists(player, X))).isFalse();
        assertThat(get(scripts.readLockBattleId(player))).as("下一局的锁原样").isEqualTo(Y);
        assertThat(get(scripts.storeSettlement(player, X, wire(player, X), BattleRedis.SETTLEMENT_TTL_SEC)))
                .as("被取代的那一局再落库也写不进去").isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(get(scripts.storeSettlement(player, Y, wire(player, Y), BattleRedis.SETTLEMENT_TTL_SEC)))
                .as("下一局照常落库").isEqualTo(1L);
    }

    // ================================================================== 活动结果通道

    @Test
    void 活动结果_落库后发布同一份字节_副本在就重发_消费方删掉后摘除() throws Exception {
        long battle = BATTLE_BASE + 100 + sequence.incrementAndGet();
        String key = RedisKeys.battleActivityResult(battle);
        written.add(key);
        BattleResultEvent event = BattleResultEvent.newBuilder().setBattleId(battle).setTotalRounds(4).addFledPlayerIds(11)
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                        .setGuildId(66).setActivityId(3))
                .build();
        List<BattleResultEvent> published = new CopyOnWriteArrayList<>();
        List<BattleResultSink.Channel> channels = new CopyOnWriteArrayList<>();
        ActivityResultOutbox outbox = new ActivityResultOutbox(scheduler, ActivityResultOutbox.Store.redis(scripts), (e, channel) -> {
            channels.add(channel);
            published.add(e);
        }, metrics, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));

        outbox.dispatch(event);
        await().atMost(PATIENCE).until(() -> published.size() == 1);

        byte[] stored = redis.<byte[]>getBucket(key, ByteArrayCodec.INSTANCE).get();
        assertThat(stored).as("持久副本与发布的事件逐字节相同").isEqualTo(event.toByteArray()).isEqualTo(published.get(0).toByteArray());
        assertThat(redis.getKeys().remainTimeToLive(key))
                .isBetween((BattleRedis.ACTIVITY_RESULT_TTL_SEC - 60) * 1000, BattleRedis.ACTIVITY_RESULT_TTL_SEC * 1000);
        assertThat(count(ActivityEvent.STORED)).isEqualTo(1);

        loop.execute(outbox::tick);
        await().atMost(PATIENCE).until(() -> published.size() == 2);
        assertThat(count(ActivityEvent.RESEND)).isEqualTo(1);
        assertThat(channels).containsOnly(BattleResultSink.Channel.ACTIVITY);

        // 消费方（4.6 的帮会同道历练）销账：删掉副本
        assertThat(redis.getKeys().delete(key)).isEqualTo(1);
        loop.execute(outbox::tick);
        await().atMost(PATIENCE).until(() -> count(ActivityEvent.ACKED) == 1);

        assertThat(published).as("已销账的一轮不重发").hasSize(2);
        assertThat(loop.submit((Callable<Integer>) outbox::size).get(5, TimeUnit.SECONDS)).isZero();
    }
}
