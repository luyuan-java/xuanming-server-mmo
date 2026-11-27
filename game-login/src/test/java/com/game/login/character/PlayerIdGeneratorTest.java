package com.game.login.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.id.Snowflake;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PlayerIdGeneratorTest {

    @Test
    void 发号带上租约给的worker且不重复() {
        long[] clock = {Snowflake.DEFAULT_EPOCH_MS + 1_000};
        PlayerIdGenerator ids = new PlayerIdGenerator(new Snowflake(37, Snowflake.DEFAULT_EPOCH_MS, () -> clock[0]++),
                () -> true);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long id = ids.nextId();
            assertThat(Snowflake.workerOf(id)).isEqualTo(37);
            assertThat(seen.add(id)).isTrue();
        }
    }

    @Test
    void 租约无效时拒绝发号_恢复有效后继续发号() {
        // 生产中 valid 来自 NodeIdLease::isValid：丢失，或续期滞后超过 2/3 TTL（未丢失）都为假。
        AtomicBoolean valid = new AtomicBoolean(true);
        PlayerIdGenerator ids = new PlayerIdGenerator(new Snowflake(1), valid::get);
        assertThat(ids.nextId()).isPositive();

        valid.set(false);
        assertThatThrownBy(ids::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("租约无效");

        valid.set(true);
        assertThat(ids.nextId()).isPositive();
    }

    @Test
    void 权威三记录发号器在旧机时钟快且重启换实例后绝不重号且故障不回退雪花() {
        var primary = new com.game.common.id.AuthoritativeIdIssuer.InMemoryReservationStore("p");
        var secondary = new com.game.common.id.AuthoritativeIdIssuer.InMemoryReservationStore("s");
        var registry = new com.game.common.id.GlobalRegionRegistry.InMemory("reg");
        registry.initialize(5, "k8s-prod-1", "p", "s", 0L);

        // 旧机时钟偏快（+10s）发号，进程退出后新机（时钟慢 10s）启动复用相同配置：绝不重号
        var oldIssuer = new com.game.common.id.AuthoritativeIdIssuer(5, true, "k8s-prod-1", primary, secondary, registry, 50L,
                () -> 1_700_000_010_000L);
        var oldGen = new PlayerIdGenerator(oldIssuer);
        Set<Long> issued = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            assertThat(issued.add(oldGen.nextId())).isTrue();
        }

        var newIssuer = new com.game.common.id.AuthoritativeIdIssuer(5, true, "k8s-prod-1", primary, secondary, registry, 50L,
                () -> 1_700_000_000_000L);
        var newGen = new PlayerIdGenerator(newIssuer);
        for (int i = 0; i < 60; i++) {
            assertThat(issued.add(newGen.nextId())).isTrue();
        }

        // 任一预留存储写失败时明确拒发，不回退本地雪花
        primary.setFailWrites(true);
        for (int i = 0; i < 40; i++) {
            newGen.nextId(); // 用完当前已预留块 [150, 200)
        }
        assertThatThrownBy(newGen::nextId).isInstanceOf(IllegalStateException.class);
    }
}
