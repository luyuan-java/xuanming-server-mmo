package com.game.battle.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.battle.BattleRedis;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 假 Redis 端口自己的行为（发件箱测试的结论建立在它上面）：内存模型与 scene-battle-spec §7.2 三段脚本的真值表一致
 * （真 Redis 上的同一张表见 xm-discovery 的 {@code BattleRedisScriptsIntegrationTest}），以及 {@link Scripted} 的几种编排。
 */
class FakeSettlementStoreTest {

    private static final long P = 9_001;
    private static final long X = 77_001;
    private static final long Y = 77_002;

    private final CallJournal journal = new CallJournal();
    private final FakeSettlementStore store = new FakeSettlementStore(journal);

    @Test
    void 落库_回字段数_同一局重放不变_每局一个字段() {
        assertThat(store.store(P, X, new byte[] {1}).join()).isEqualTo(1L);
        assertThat(store.store(P, X, new byte[] {1}).join()).as("重放幂等").isEqualTo(1L);
        assertThat(store.store(P, Y, new byte[] {2}).join()).isEqualTo(2L);

        assertThat(store.record(P, X)).containsExactly(1);
        assertThat(store.fieldCount(P)).isEqualTo(2);
        assertThat(store.exists(P, X).join()).isTrue();
        assertThat(store.exists(P + 1, X).join()).as("别的玩家").isFalse();
    }

    @Test
    void 销账_位掩码_无条件留墓碑_之后同一局的落库回负1且不写_别的局不受影响() {
        store.store(P, X, new byte[] {1}).join();
        store.lock(P, X);

        assertThat(store.sceneAck(P, X)).as("删了记录 + 放了锁").isEqualTo(3);
        assertThat(store.sceneAck(P, X)).as("重放").isZero();
        assertThat(store.hasTombstone(P, X)).isTrue();

        assertThat(store.store(P, X, new byte[] {9}).join()).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(store.hasRecord(P, X)).isFalse();
        assertThat(store.offered(P, X)).as("传进来的字节照样记下").containsExactly(9);
        assertThat(store.store(P, Y, new byte[] {2}).join()).as("墓碑只挡这一局").isEqualTo(1L);
    }

    /**
     * 只是<b>假件模型</b>的性质（从 {@code SettlementOutboxTest} 挪过来的，评审 R63B-2）：它照着 {@code ACK} / {@code STORE_SETTLEMENT} 两段 Lua 手写，
     * 这里过了不等于脚本对——真脚本的同一场景见 {@code OutboxRedisIntegrationTest} 的
     * {@code 落库结局不明_投一次后scene销账_那条落库这时才落地_被墓碑挡下_没有孤儿记录}（要 {@code -Dxm.it.redis}）。
     */
    @Test
    void 模型_销账时没有记录也没有锁_位掩码0_照样留墓碑_悬着的落库这时才落地_回负1且不写() {
        store.stores.hold();
        CompletableFuture<Long> late = store.store(P, X, new byte[] {1});

        assertThat(store.sceneAck(P, X)).as("没有记录可删、没有锁可放").isZero();
        assertThat(store.hasTombstone(P, X)).as("墓碑是无条件留的").isTrue();

        assertThat(store.stores.last().release()).as("那条落库现在才落地").isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(late.join()).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(store.hasRecord(P, X)).isFalse();
        assertThat(store.fieldCount(P)).isZero();
        assertThat(journal.entries()).containsExactly("store:" + FakeSettlementStore.key(P, X),
                "store-done:" + FakeSettlementStore.key(P, X) + "=-1");
    }

    @Test
    void 已取代判定的四种返回() {
        assertThat(store.ackIfSuperseded(P, X).join()).as("记录不在").isEqualTo(3L);
        store.store(P, X, new byte[] {1}).join();
        assertThat(store.ackIfSuperseded(P, X).join()).as("锁不在").isEqualTo(0L);
        store.lock(P, X);
        assertThat(store.ackIfSuperseded(P, X).join()).as("仍是本局").isEqualTo(1L);
        assertThat(store.hasRecord(P, X)).isTrue();

        store.lock(P, Y);
        assertThat(store.ackIfSuperseded(P, X).join()).as("已被取代：删记录、留墓碑").isEqualTo(2L);
        assertThat(store.hasRecord(P, X)).isFalse();
        assertThat(store.hasTombstone(P, X)).isTrue();
        assertThat(store.ackIfSuperseded(P, X).join()).as("重放").isEqualTo(3L);
    }

    @Test
    void 悬着的调用_放行时才推进模型_强给值与失败不推进() {
        store.stores.hold();
        CompletableFuture<Long> first = store.store(P, X, new byte[] {1});
        CompletableFuture<Long> second = store.store(P, Y, new byte[] {2});
        assertThat(first).isNotDone();
        assertThat(store.hasRecord(P, X)).as("还没落地").isFalse();

        store.stores.pending.get(1).fail(new TimeoutException("超时"));
        assertThat(second).isCompletedExceptionally();
        assertThat(store.hasRecord(P, Y)).isFalse();

        store.stores.pending.get(0).release();
        assertThat(first.join()).isEqualTo(1L);
        assertThat(store.hasRecord(P, X)).isTrue();
        assertThat(journal.entries()).containsExactly("store:" + FakeSettlementStore.key(P, X), "store:" + FakeSettlementStore.key(P, Y),
                "store-done:" + FakeSettlementStore.key(P, Y) + "=error", "store-done:" + FakeSettlementStore.key(P, X) + "=1");
    }

    @Test
    void 其余编排_异常完成_同步抛出_固定值_空future() {
        store.probes.failWith(new IllegalStateException("出错"));
        assertThat(store.exists(P, X)).isCompletedExceptionally();
        store.probes.throwing(new IllegalStateException("抛出"));
        assertThatThrownBy(() -> store.exists(P, X)).isInstanceOf(IllegalStateException.class).hasMessage("抛出");
        store.probes.replyWith(null);
        assertThat(store.exists(P, X).join()).isNull();
        store.probes.returnNullFuture();
        assertThat(store.exists(P, X)).isNull();
        store.probes.auto();
        assertThat(store.exists(P, X).join()).isFalse();
        assertThat(journal.count("exists:")).as("不管怎么编排，调用都记下").isEqualTo(5);
    }
}
