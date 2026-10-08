package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateStore.Eviction;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 观战存储的装配。<b>先行件阶段钉的是占位</b>：bean 在、类型对，每个同步方法都按「依赖故障」抛（调用方据此回 16004 / 信封 1003 / 只记日志），
 * 两个尽力方法静默。W1 把占位换成 {@code RedissonSpectateStore} 时，这个文件随之改成对真装配的断言。
 */
class SpectateStoreConfigurationTest {

    private final SpectateStore store = new SpectateStoreConfiguration().spectateStore();

    private static Deadline d() {
        return Deadline.after(1_000);
    }

    @Test
    void 占位的存储_每个同步方法都抛依赖异常_消息里写着是哪个方法() {
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(77).setAttempt(1).build();

        assertThatThrownBy(() -> store.entry(1001, d())).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("SpectateStore.entry");
        assertThatThrownBy(() -> store.acquire(1001, "77:0123456789abcdef", d())).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("acquire");
        assertThatThrownBy(() -> store.release(1001, "77:0123456789abcdef", d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.marksOf(List.of(1001L), d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.read(77, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.pickRandom(0.5, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.evict(new Eviction.Missing(77), d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.publish(placement, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.list(20, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.readPlacements(List.of(77L), d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.sweep(d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.watchableCount(d())).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("watchableCount");
    }

    @Test
    void 占位的存储_两个尽力方法不抛() {
        assertThatCode(() -> store.releaseAsync(1001, "77:0123456789abcdef")).doesNotThrowAnyException();
        assertThatCode(() -> store.evictAsync(List.of(new Eviction.Invalid("junk")))).doesNotThrowAnyException();
    }

    @Test
    void 每次取到的是可用的实例_装配方法不要求任何参数() {
        assertThat(new SpectateStoreConfiguration().spectateStore()).isNotNull().isNotSameAs(store);
    }
}
