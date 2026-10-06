package com.game.scene.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.battle.BattleRedis.EnterRead;
import com.game.discovery.battle.BattleRedis.SettlementField;
import com.game.scene.battle.BattleLocks;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * {@link FakeBattleLocks} 自己的测试——十几个战斗测试类都建在它上面，所以它必须与真脚本同一份真值表：
 * <ul>
 *   <li>同一段调用脚本（{@link #script}）在假实现上跑出的逐步结果与字面期望一致（缺省就跑）；</li>
 *   <li>开了 {@code -Dxm.it.redis=redis://127.0.0.1:6379} 时，同一段脚本在<b>真 Redis</b>（{@code BattleLocks.redis(new BattleRedis(...))}）上再跑一遍，
 *       逐步结果必须与假实现完全相同（DB 15、随机大号玩家，只删自己写的键）；</li>
 *   <li>挂起 / 乱序完成 / 注入失败 / 重放 / 调用记录这些测试控制面的行为。</li>
 * </ul>
 */
class FakeBattleLocksTest {

    private static final long X = 0x8000_0000_0000_0007L;
    private static final long Y = 9;
    private static final long Z = 11;
    private static final String XS = Long.toUnsignedString(X);

    /** 两种实现的共同面：端口 + battle 侧落库 + 直接塞一个原始字段名（造坏字段）+ 直接摆一把字段任意的锁（造残缺的锁）。 */
    private interface Port {
        BattleLocks locks();

        long store(long battleId, byte[] bytes);

        void putRaw(byte[] name, byte[] value);

        /** 整把换成这些字段（不经脚本），带 {@code ttlSec} 秒的 TTL。 */
        void putRawLock(Map<String, String> fields, long ttlSec);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String show(Object value) {
        if (value == null) {
            return "nil";
        }
        if (value instanceof byte[] b) {
            return "bytes:" + new String(b, StandardCharsets.ISO_8859_1);
        }
        if (value instanceof Map<?, ?> m) {
            return new TreeMap<>(m).toString();
        }
        if (value instanceof EnterRead read) {
            List<String> fields = new ArrayList<>();
            for (SettlementField f : read.settlements()) {
                fields.add(f.name() + "=" + new String(f.value(), StandardCharsets.ISO_8859_1));
            }
            return "lock" + new TreeMap<>(read.lock()) + " ttl=" + read.lockTtlSec() + " records" + fields;
        }
        return String.valueOf(value);
    }

    /**
     * 一段把每个脚本的每个分支都走到的调用序列；返回逐步结果（已规整成文本）。期限用远期常量，TTL 都是整秒且远大于脚本耗时，
     * 所以真 Redis 上读回的 TTL 与假实现一致。
     */
    private static List<String> script(long playerId, Port port) {
        BattleLocks locks = port.locks();
        List<String> out = new ArrayList<>();
        long d = 4_000_000_000_000L;
        long p = 3_999_999_000_000L;
        // —— 空状态
        out.add("enterRead0 " + show(locks.enterRead(playerId).join()));
        out.add("readLock0 " + show(locks.readLockBattleId(playerId).join()));
        out.add("readSettlement0 " + show(locks.readSettlement(playerId, X).join()));
        out.add("touchNoLock " + show(locks.touch(playerId, X, 100, "F", d, p).join()));
        out.add("holdNoLock " + show(locks.hold(playerId, X, 180).join()));
        out.add("confirmNoLock " + show(locks.confirm(playerId, X, d, 360).join()));
        out.add("cancelNoLock " + show(locks.cancelOffline(playerId, X).join()));
        out.add("deleteNoLock " + show(locks.deleteIfMatch(playerId, X).join()));
        // —— 备战锁
        out.add("prepare " + show(locks.prepareLock(playerId, X, 21, d, p, 120).join()));
        out.add("prepareReplay " + show(locks.prepareLock(playerId, X, 22, d + 1, p + 1, 130).join()));
        out.add("afterReplay " + show(locks.enterRead(playerId).join()));
        out.add("prepareOther " + show(locks.prepareLock(playerId, Y, 21, d, p, 120).join()));
        out.add("touchP " + show(locks.touch(playerId, X, 100, "P", d + 2, p + 2).join()));
        out.add("touchOther " + show(locks.touch(playerId, Y, 100, "P", d, p).join()));
        out.add("deletePreparingOther " + show(locks.deletePreparingIfMatch(playerId, Y).join()));
        out.add("confirmOther " + show(locks.confirm(playerId, Y, d, 360).join()));
        // —— 确认：标 F、写 d、续 TTL
        out.add("confirm " + show(locks.confirm(playerId, X, d + 3, 360).join()));
        out.add("prepareAfterF " + show(locks.prepareLock(playerId, X, 21, d, p, 120).join()));
        out.add("touchPonF " + show(locks.touch(playerId, X, 90, "P", d + 4, p + 4).join()));
        out.add("afterTouchPonF " + show(locks.enterRead(playerId).join()));
        out.add("confirmNoChange " + show(locks.confirm(playerId, X, 0, 0).join()));
        out.add("touchF " + show(locks.touch(playerId, X, 400, "F", d + 5, p + 5).join()));
        out.add("cancelOnF " + show(locks.cancelOffline(playerId, X).join()));
        out.add("deletePreparingOnF " + show(locks.deletePreparingIfMatch(playerId, X).join()));
        // —— HOLD 只延不缩
        out.add("holdShorter " + show(locks.hold(playerId, X, 180).join()));
        out.add("afterHoldShorter " + show(locks.enterRead(playerId).join()));
        out.add("holdLonger " + show(locks.hold(playerId, X, 500).join()));
        out.add("afterHoldLonger " + show(locks.enterRead(playerId).join()));
        out.add("holdOther " + show(locks.hold(playerId, Y, 100).join()));
        out.add("readLock " + show(locks.readLockBattleId(playerId).join()));
        // —— 条件删锁（不看 s）
        out.add("deleteOther " + show(locks.deleteIfMatch(playerId, Y).join()));
        out.add("delete " + show(locks.deleteIfMatch(playerId, X).join()));
        out.add("deleteAgain " + show(locks.deleteIfMatch(playerId, X).join()));
        // —— 只删备战锁：P 删、重放回 0
        out.add("prepareZ " + show(locks.prepareLock(playerId, Z, 21, d, 0, 60).join()));
        out.add("cancelP " + show(locks.cancelOffline(playerId, Z).join()));
        out.add("cancelPAgain " + show(locks.cancelOffline(playerId, Z).join()));
        out.add("prepareZ2 " + show(locks.prepareLock(playerId, Z, 21, d, 0, 60).join()));
        out.add("deletePreparingP " + show(locks.deletePreparingIfMatch(playerId, Z).join()));
        // —— 残缺的锁（键在、没有 b）：每段脚本比的都是 HGET b，所以除了备战写锁全部落空；PREPARE_LOCK 判的是「没有 b」而不是「键不存在」，
        //    照样写入——五个字段都写（s 改回 P）、键上别的字段保留、TTL 换成新的
        port.putRawLock(Map.of("n", "5", "s", "F", "zz", "keep"), 40);
        out.add("noB " + show(locks.enterRead(playerId).join()));
        out.add("readLockNoB " + show(locks.readLockBattleId(playerId).join()));
        out.add("touchNoB " + show(locks.touch(playerId, Z, 100, "F", d, p).join()));
        out.add("holdNoB " + show(locks.hold(playerId, Z, 180).join()));
        out.add("confirmNoB " + show(locks.confirm(playerId, Z, d, 360).join()));
        out.add("cancelNoB " + show(locks.cancelOffline(playerId, Z).join()));
        out.add("deletePreparingNoB " + show(locks.deletePreparingIfMatch(playerId, Z).join()));
        out.add("deleteNoB " + show(locks.deleteIfMatch(playerId, Z).join()));
        out.add("ackNoB " + show(locks.ack(playerId, 66).join()));
        out.add("stillNoB " + show(locks.enterRead(playerId).join()));
        out.add("prepareNoB " + show(locks.prepareLock(playerId, Z, 23, d + 6, p + 6, 70).join()));
        out.add("afterPrepareNoB " + show(locks.enterRead(playerId).join()));
        out.add("deleteAfterNoB " + show(locks.deleteIfMatch(playerId, Z).join()));
        // —— 待结算记录
        out.add("store1 " + port.store(X, bytes("first")));
        out.add("store2 " + port.store(Y, bytes("second")));
        out.add("storeOverwrite " + port.store(X, bytes("first-v2")));
        out.add("readSettlement " + show(locks.readSettlement(playerId, X).join()));
        out.add("readSettlementMissing " + show(locks.readSettlement(playerId, Z).join()));
        port.putRaw(new byte[] {'0', '5'}, bytes("raw"));
        port.putRaw(new byte[] {(byte) 0xff, (byte) 0xfe}, bytes("bad-utf8"));
        out.add("withRecords " + show(locks.enterRead(playerId).join()));
        out.add("deleteField " + show(locks.deleteSettlementField(playerId, new byte[] {(byte) 0xff, (byte) 0xfe}).join()));
        out.add("deleteFieldAgain " + show(locks.deleteSettlementField(playerId, new byte[] {(byte) 0xff, (byte) 0xfe}).join()));
        out.add("deleteField05 " + show(locks.deleteSettlementField(playerId, new byte[] {'0', '5'}).join()));
        // —— 销账：位掩码、墓碑挡住之后的落库
        out.add("prepareX " + show(locks.prepareLock(playerId, X, 21, d, p, 120).join()));
        out.add("ackRecordAndLock " + show(locks.ack(playerId, X).join()));
        out.add("ackReplay " + show(locks.ack(playerId, X).join()));
        out.add("storeAfterAck " + port.store(X, bytes("late")));
        out.add("ackRecordOnly " + show(locks.ack(playerId, Y).join()));
        out.add("afterAcks " + show(locks.enterRead(playerId).join()));
        out.add("ackNothing " + show(locks.ack(playerId, Z).join()));
        out.add("storeAfterBareAck " + port.store(Z, bytes("never-existed")));
        out.add("storeUntouched " + port.store(77, bytes("fresh")));
        out.add("prepareWhileRecord " + show(locks.prepareLock(playerId, 88, 21, d, p, 120).join()));
        out.add("ackLockOnly " + show(locks.ack(playerId, 88).join()));
        out.add("final " + show(locks.enterRead(playerId).join()));
        return out;
    }

    private static Port fakePort(long playerId, FakeBattleLocks fake) {
        return new Port() {
            @Override
            public BattleLocks locks() {
                return fake;
            }

            @Override
            public long store(long battleId, byte[] bytes) {
                return fake.storeSettlement(playerId, battleId, bytes);
            }

            @Override
            public void putRaw(byte[] name, byte[] value) {
                fake.putRawSettlementField(playerId, name, value);
            }

            @Override
            public void putRawLock(Map<String, String> fields, long ttlSec) {
                fake.putLockFields(playerId, fields, ttlSec);
            }
        };
    }

    // ------------------------------------------------------------------ 真值表

    @Test
    void 每段脚本每个分支的返回与副作用_与BattleRedis的真值表逐步一致() {
        ManualClock clock = new ManualClock();
        FakeBattleLocks fake = new FakeBattleLocks(clock::epochMillis);

        List<String> steps = script(1001, fakePort(1001, fake));

        long d = 4_000_000_000_000L;
        long p = 3_999_999_000_000L;
        assertThat(steps).containsExactly(
                "enterRead0 lock{} ttl=-2 records[]",
                "readLock0 0",
                "readSettlement0 nil",
                "touchNoLock 0",
                "holdNoLock 0",
                "confirmNoLock nil",
                "cancelNoLock 0",
                "deleteNoLock 0",
                "prepare 0",
                "prepareReplay 0",
                "afterReplay lock{b=" + XS + ", d=" + (d + 1) + ", n=22, p=" + (p + 1) + ", s=P} ttl=130 records[]",
                "prepareOther " + XS,
                "touchP 1",
                "touchOther 0",
                "deletePreparingOther 0",
                "confirmOther nil",
                "confirm {b=" + XS + ", d=" + (d + 3) + ", n=22, p=" + (p + 2) + ", s=F}",
                "prepareAfterF " + XS,
                "touchPonF 2",
                "afterTouchPonF lock{b=" + XS + ", d=" + (d + 3) + ", n=22, p=" + (p + 2) + ", s=F} ttl=360 records[]",
                "confirmNoChange {b=" + XS + ", d=" + (d + 3) + ", n=22, p=" + (p + 2) + ", s=F}",
                "touchF 1",
                "cancelOnF 2",
                "deletePreparingOnF 2",
                "holdShorter 1",
                "afterHoldShorter lock{b=" + XS + ", d=" + (d + 5) + ", n=22, p=" + (p + 5) + ", s=F} ttl=400 records[]",
                "holdLonger 1",
                "afterHoldLonger lock{b=" + XS + ", d=" + (d + 5) + ", n=22, p=" + (p + 5) + ", s=F} ttl=500 records[]",
                "holdOther 0",
                "readLock " + X,
                "deleteOther 0",
                "delete 1",
                "deleteAgain 0",
                "prepareZ 0",
                "cancelP 1",
                "cancelPAgain 0",
                "prepareZ2 0",
                "deletePreparingP 1",
                "noB lock{n=5, s=F, zz=keep} ttl=40 records[]",
                "readLockNoB 0",
                "touchNoB 0",
                "holdNoB 0",
                "confirmNoB nil",
                "cancelNoB 0",
                "deletePreparingNoB 0",
                "deleteNoB 0",
                "ackNoB 0",
                "stillNoB lock{n=5, s=F, zz=keep} ttl=40 records[]",
                "prepareNoB 0",
                "afterPrepareNoB lock{b=11, d=" + (d + 6) + ", n=23, p=" + (p + 6) + ", s=P, zz=keep} ttl=70 records[]",
                "deleteAfterNoB 1",
                "store1 1",
                "store2 2",
                "storeOverwrite 2",
                "readSettlement bytes:first-v2",
                "readSettlementMissing nil",
                "withRecords lock{} ttl=-2 records[" + XS + "=first-v2, 9=second, 05=raw, hex:fffe=bad-utf8]",
                "deleteField 1",
                "deleteFieldAgain 0",
                "deleteField05 1",
                "prepareX 0",
                "ackRecordAndLock 3",
                "ackReplay 0",
                "storeAfterAck -1",
                "ackRecordOnly 1",
                "afterAcks lock{} ttl=-2 records[]",
                "ackNothing 0",
                "storeAfterBareAck -1",
                "storeUntouched 1",
                "prepareWhileRecord 0",
                "ackLockOnly 2",
                "final lock{} ttl=-2 records[77=fresh]");
    }

    /** 同一段脚本在真 Redis 上逐步结果相同：假实现没有偏离 {@code BattleRedis} 里的 Lua。 */
    @Test
    @EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
    void 真Redis上同一段脚本的逐步结果_与假实现完全相同() {
        long playerId = Long.MIN_VALUE | ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(15);
        RedissonClient client = Redisson.create(config);
        String lockKey = RedisKeys.battleLock(playerId);
        String recordsKey = RedisKeys.battleSettlements(playerId);
        try {
            BattleRedis redis = new BattleRedis(client);
            BattleLocks real = BattleLocks.redis(redis);
            Port realPort = new Port() {
                @Override
                public BattleLocks locks() {
                    return real;
                }

                @Override
                public long store(long battleId, byte[] bytes) {
                    return redis.storeSettlement(playerId, battleId, bytes, BattleRedis.SETTLEMENT_TTL_SEC).join();
                }

                @Override
                public void putRaw(byte[] name, byte[] value) {
                    client.<byte[], byte[]>getMap(recordsKey, ByteArrayCodec.INSTANCE).fastPut(name, value);
                }

                @Override
                public void putRawLock(Map<String, String> fields, long ttlSec) {
                    RMap<String, String> lock = client.getMap(lockKey, StringCodec.INSTANCE);
                    lock.delete();
                    lock.putAll(fields);
                    lock.expire(Duration.ofSeconds(ttlSec));
                }
            };
            FakeBattleLocks fake = new FakeBattleLocks(System::currentTimeMillis);

            List<String> onRedis = script(playerId, realPort);
            List<String> onFake = script(playerId, fakePort(playerId, fake));

            assertThat(onFake).containsExactlyElementsOf(onRedis);
        } finally {
            client.getKeys().delete(lockKey, recordsKey, RedisKeys.battleSettled(playerId, X), RedisKeys.battleSettled(playerId, Y),
                    RedisKeys.battleSettled(playerId, Z), RedisKeys.battleSettled(playerId, 66), RedisKeys.battleSettled(playerId, 77),
                    RedisKeys.battleSettled(playerId, 88));
            client.shutdown();
        }
    }

    // ------------------------------------------------------------------ TTL 与过期

    @Test
    void 锁到了TTL就消失_墓碑十分钟后过期() {
        ManualClock clock = new ManualClock();
        FakeBattleLocks fake = new FakeBattleLocks(clock::epochMillis);
        fake.putLock(1001, 7, 21, "P", 1, 1, 120);
        fake.ack(1001, 8);
        assertThat(fake.settled(1001, 8)).isTrue();

        clock.advanceMillis(119_999);
        assertThat(fake.lockBattleId(1001)).isEqualTo(7);
        assertThat(fake.lockTtlSec(1001)).as("剩 1 ms，四舍五入为 0").isZero();
        clock.advanceMillis(1);
        assertThat(fake.lock(1001)).isNull();
        assertThat(fake.lockTtlSec(1001)).isEqualTo(-2);
        assertThat(fake.readLockBattleId(1001).join()).isZero();

        clock.advanceMillis(BattleRedis.SETTLED_TOMBSTONE_TTL_SEC * 1000 - 120_001);
        assertThat(fake.settled(1001, 8)).isTrue();
        assertThat(fake.storeSettlement(1001, 8, bytes("x"))).isEqualTo(-1);
        clock.advanceMillis(1);
        assertThat(fake.settled(1001, 8)).isFalse();
        assertThat(fake.storeSettlement(1001, 8, bytes("x"))).isEqualTo(1);
    }

    @Test
    void 不带TTL的锁_TTL为负1_HOLD不给它加TTL() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);
        fake.putLock(1001, 7, 21, "F", 1, 0, -1);

        assertThat(fake.lockTtlSec(1001)).isEqualTo(-1);
        assertThat(fake.hold(1001, 7, 180).join()).isEqualTo(1);
        assertThat(fake.lockTtlSec(1001)).isEqualTo(-1);
        assertThat(fake.enterRead(1001).join().lockTtlSec()).isEqualTo(-1);
    }

    // ------------------------------------------------------------------ 控制面：挂起、次序、故障、重放、记录

    @Test
    void 自动模式_调用到达即执行并回复_按到达次序记下调用与参数() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);

        CompletableFuture<String> prepared = fake.prepareLock(1001, 7, 21, 500, 400, 120);
        CompletableFuture<Long> held = fake.hold(1001, 7, 180);

        assertThat(prepared).isCompletedWithValue("0");
        assertThat(held).isCompletedWithValue(1L);
        assertThat(fake.ops()).containsExactly(Op.PREPARE_LOCK, Op.HOLD);
        assertThat(fake.calls()).extracting(Call::toString).containsExactly(
                "PREPARE_LOCK(player=1001, battle=7, node=21, deadline=500, prepareDeadline=400, ttl=120)",
                "HOLD(player=1001, battle=7, hold=180)");
        assertThat(fake.last(Op.PREPARE_LOCK).longArg("ttl")).isEqualTo(120);
        assertThat(fake.pending()).isEmpty();
        fake.clearCalls();
        assertThat(fake.calls()).isEmpty();
        assertThat(fake.lockBattleId(1001)).as("清记录不动模型").isEqualTo(7);
    }

    @Test
    void 挂起的调用不执行也不回复_执行与回复可以分开_次序由测试定() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);
        fake.putLock(1001, 7, 21, "F", 1, 0, 360);
        fake.storeSettlement(1001, 7, bytes("record"));
        fake.hold(Op.ENTER_READ, Op.ACK);

        CompletableFuture<EnterRead> read = fake.enterRead(1001);
        CompletableFuture<Long> ack = fake.ack(1001, 7);

        assertThat(read).isNotDone();
        assertThat(ack).isNotDone();
        assertThat(fake.lockBattleId(1001)).as("挂起 = 还没在 Redis 上执行").isEqualTo(7);
        assertThat(fake.pending()).hasSize(2);

        // Redis 先执行读（快照里记录与锁都在），再执行销账并先把销账的回复交回去
        Call readCall = fake.take(Op.ENTER_READ).execute();
        assertThat(read).as("执行了，回复还没交回").isNotDone();
        fake.take(Op.ACK).complete();
        assertThat(ack).isCompletedWithValue(3L);
        assertThat(fake.lock(1001)).isNull();

        readCall.reply();
        assertThat(read.join().lockBattleId()).as("回来的是销账之前拍的快照").isEqualTo(7);
        assertThat(read.join().settlements()).hasSize(1);
        assertThat(readCall.executions()).isEqualTo(1);
        assertThat(fake.pending()).isEmpty();
    }

    @Test
    void 只挂起指定的脚本_其余照常自动_release之后恢复自动_completeAll按到达次序补完() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);
        fake.hold(Op.TOUCH);

        assertThat(fake.prepareLock(1001, 7, 21, 500, 400, 120)).isCompleted();
        CompletableFuture<Long> first = fake.touch(1001, 7, 100, "P", 500, 400);
        CompletableFuture<Long> second = fake.touch(1001, 8, 100, "P", 500, 400);
        assertThat(first).isNotDone();
        assertThat(fake.take(Op.TOUCH).battleId()).as("take 取最早一个还没回复的").isEqualTo(7);

        fake.release(Op.TOUCH);
        assertThat(fake.touch(1001, 7, 100, "P", 500, 400)).as("之后到达的自动完成").isCompletedWithValue(1L);
        assertThat(first).as("已经挂起的不会自己完成").isNotDone();

        assertThat(fake.completeAll()).isEqualTo(2);
        assertThat(first).isCompletedWithValue(1L);
        assertThat(second).isCompletedWithValue(0L);
        assertThatThrownBy(() -> fake.take(Op.TOUCH)).isInstanceOf(IllegalStateException.class).hasMessageContaining("没有挂起的 TOUCH");
    }

    @Test
    void 注入失败_不执行直接失败_执行后失败_一直失败直到heal() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);
        RuntimeException timeout = new RuntimeException("超时");

        fake.failNext(Op.PREPARE_LOCK, timeout);
        CompletableFuture<String> notExecuted = fake.prepareLock(1001, 7, 21, 500, 400, 120);
        assertThatThrownBy(notExecuted::join).isInstanceOf(CompletionException.class).hasCause(timeout);
        assertThat(fake.lock(1001)).as("没有执行").isNull();
        assertThat(fake.last(Op.PREPARE_LOCK).executions()).isZero();

        fake.failNextAfterExecuting(Op.PREPARE_LOCK, timeout);
        CompletableFuture<String> executed = fake.prepareLock(1001, 7, 21, 500, 400, 120);
        assertThat(executed).isCompletedExceptionally();
        assertThat(fake.lockBattleId(1001)).as("执行了，只是回复丢了").isEqualTo(7);

        assertThat(fake.prepareLock(1001, 7, 21, 500, 400, 120)).as("一次性故障只管一次").isCompletedWithValue("0");

        fake.failAlways(Op.ACK, timeout);
        assertThat(fake.ack(1001, 7)).isCompletedExceptionally();
        assertThat(fake.ack(1001, 7)).isCompletedExceptionally();
        assertThat(fake.lockBattleId(1001)).isEqualTo(7);
        fake.heal(Op.ACK);
        assertThat(fake.ack(1001, 7)).isCompletedWithValue(2L);
    }

    @Test
    void 重放_脚本执行两遍_调用方拿到第二遍的回复() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);
        fake.putLock(1001, 7, 21, "P", 1, 1, 120);
        fake.storeSettlement(1001, 7, bytes("record"));

        fake.replayNext(Op.ACK);
        CompletableFuture<Long> ack = fake.ack(1001, 7);

        assertThat(fake.last(Op.ACK).executions()).isEqualTo(2);
        assertThat(ack).as("第一遍回 3（删了记录、放了锁），重放回 0").isCompletedWithValue(0L);
        assertThat(fake.lock(1001)).isNull();

        fake.hold(Op.CANCEL_OFFLINE);
        fake.putLock(1001, 8, 21, "P", 1, 1, 120);
        CompletableFuture<Long> cancel = fake.cancelOffline(1001, 8);
        fake.take(Op.CANCEL_OFFLINE).replay();
        assertThat(cancel).as("第一遍删了回 1，重放回 0").isCompletedWithValue(0L);
    }

    @Test
    void 同步抛出_用来在回调的指定位置制造意外异常_调用照样记下() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);
        RuntimeException boom = new IllegalStateException("意外");
        fake.throwNext(Op.HOLD, boom);

        assertThatThrownBy(() -> fake.hold(1001, 7, 180)).isSameAs(boom);
        assertThat(fake.ops()).containsExactly(Op.HOLD);
        assertThat(fake.hold(1001, 7, 180)).as("只管一次").isCompletedWithValue(0L);
    }

    @Test
    void 手动给回复_造真值表之外的值() {
        FakeBattleLocks fake = new FakeBattleLocks(new ManualClock()::epochMillis);
        fake.hold(Op.TOUCH);
        CompletableFuture<Long> touch = fake.touch(1001, 7, 100, "P", 1, 1);

        fake.take(Op.TOUCH).replyWith(null);

        assertThat(touch).isCompletedWithValue(null);
        assertThat(fake.last(Op.TOUCH).executions()).isZero();
    }
}
