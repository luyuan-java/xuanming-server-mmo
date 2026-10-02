package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MessageRateLimiterTest {

    private static final long SEC = 1_000_000_000L;
    private final MessageRateLimiter limiter = new MessageRateLimiter();

    @Test
    void 窗口内满额即拒绝_被拒的不占额度_窗口滑过后恢复() {
        MessageLimit limit = new MessageLimit(3, Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire(84, limit, 0)).isTrue();
        assertThat(limiter.tryAcquire(84, limit, SEC / 4)).isTrue();
        assertThat(limiter.tryAcquire(84, limit, SEC / 2)).isTrue();
        assertThat(limiter.tryAcquire(84, limit, SEC / 2 + 1)).isFalse();
        assertThat(limiter.tryAcquire(84, limit, SEC - 1)).isFalse();

        assertThat(limiter.tryAcquire(84, limit, SEC)).as("第一条滑出窗口").isTrue();
        assertThat(limiter.tryAcquire(84, limit, SEC + 1)).isFalse();
    }

    @Test
    void 各消息号分开计数_不限频的不记录() {
        MessageLimit one = new MessageLimit(1, Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire(84, one, 0)).isTrue();
        assertThat(limiter.tryAcquire(63, one, 0)).isTrue();
        assertThat(limiter.tryAcquire(84, one, 1)).isFalse();

        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire(77, MessageLimit.UNLIMITED, i)).isTrue();
        }
    }

    @Test
    void 缺省与C_plus_plus一致_每秒3条() {
        assertThat(MessageLimit.DEFAULT).isEqualTo(new MessageLimit(3, Duration.ofSeconds(1)));
        assertThat(MessageLimits.of(java.util.Map.of()).limitOf(84)).isEqualTo(MessageLimit.DEFAULT);
    }

    @Test
    void 真实配表_表里的按表_其余用缺省() {
        MessageLimits limits = TableMessageLimits.load(Path.of("..", "config-data", "tables"));

        // messagelimiter.pb 与 mmorpg generated/tables/messagelimiter.json 同一份：id 68 → 10 条 / 1 秒。
        assertThat(limits.limitOf(68)).isEqualTo(new MessageLimit(10, Duration.ofSeconds(1)));
        assertThat(limits.limitOf(Integer.MAX_VALUE)).isEqualTo(MessageLimit.DEFAULT);
    }

    @Test
    void 表文件不存在拒绝启动() {
        assertThatThrownBy(() -> TableMessageLimits.load(Path.of("no-such-dir-for-test")))
                .isInstanceOf(com.game.table.load.TableLoadException.class)
                .hasMessageContaining("配置表目录不存在");
    }

    @Test
    void 非法上限拒绝构造() {
        assertThatThrownBy(() -> new MessageLimit(-1, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageLimit(3, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
