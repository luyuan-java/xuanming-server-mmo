package com.game.net.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 限频器（gate 与 battle 直连面共用的唯一实现，从 xm-gate 挪来，battle-node-spec §7.2 / Q9）。
 * 含原 gate 侧 {@code MessageRateLimiterTest} 的全部用例；gate 的接入行为（1008 信封、计非法包、到阈值断开）由
 * xm-gate {@code ClientDispatcherTest} 覆盖。
 */
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
    void 每条连接各自一份限频器() {
        MessageLimit one = new MessageLimit(1, Duration.ofSeconds(1));
        MessageRateLimiter other = new MessageRateLimiter();
        assertThat(limiter.tryAcquire(140, one, 0)).isTrue();
        assertThat(limiter.tryAcquire(140, one, 1)).isFalse();
        assertThat(other.tryAcquire(140, one, 1)).as("另一条连接不受影响").isTrue();
    }

    @Test
    void 缺省与C_plus_plus一致_每秒3条() {
        assertThat(MessageLimit.DEFAULT).isEqualTo(new MessageLimit(3, Duration.ofSeconds(1)));
        assertThat(MessageLimits.of(Map.of()).limitOf(84)).isEqualTo(MessageLimit.DEFAULT);
        assertThat(MessageLimits.UNLIMITED.limitOf(84).unlimited()).isTrue();
    }

    @Test
    void 真实配表_表里的按表_其余用缺省() {
        MessageLimits limits = TableMessageLimits.load(Path.of("..", "config-data", "tables"));

        // messagelimiter.pb 与 mmorpg generated/tables/messagelimiter.json 同一份：id 68 → 10 条 / 1 秒。
        assertThat(limits.limitOf(68)).isEqualTo(new MessageLimit(10, Duration.ofSeconds(1)));
        assertThat(limits.limitOf(Integer.MAX_VALUE)).isEqualTo(MessageLimit.DEFAULT);
    }

    @Test
    void 真实配表_四条战斗上行都不在表里_取缺省每秒3条() {
        MessageLimits limits = TableMessageLimits.load(Path.of("..", "config-data", "tables"));

        // 基线 messagelimiter.json 里没有 140 / 149 / 162 / 165（battle-node-spec §3.5）
        for (int battleUpstream : new int[] {140, 149, 162, 165}) {
            assertThat(limits.limitOf(battleUpstream)).as("消息号 %d", battleUpstream).isEqualTo(MessageLimit.DEFAULT);
        }
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
