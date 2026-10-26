package com.game.guild.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.misc.CompletableFutureWrapper;

class RedissonTerminalCleanupWatermarkTest {

    @Test
    void 只有缺键返回零_坏值不能当作从未清理() {
        assertThat(RedissonTerminalCleanupWatermark.parse(null)).isZero();
        assertThat(RedissonTerminalCleanupWatermark.parse("0")).isZero();
        assertThat(RedissonTerminalCleanupWatermark.parse("1700000000000")).isEqualTo(1_700_000_000_000L);
        assertThat(RedissonTerminalCleanupWatermark.parse(Long.toString(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
        for (String raw : List.of("", "-1", "+1", "01", "1.5", "1e3", "NaN", "9223372036854775808")) {
            assertThatThrownBy(() -> RedissonTerminalCleanupWatermark.parse(raw))
                    .as("损坏值 %s", raw).isInstanceOf(Deadline.DependencyException.class);
        }
    }

    @Test
    void 读写都走主节点_使用注入命名空间_失败和超时不降级() {
        RedisKeys keys = new RedisKeys(GameNamespace.of("watermarktest"));
        RedissonClient redis = mock(RedissonClient.class);
        List<String> commands = new ArrayList<>();
        RScript script = mock(RScript.class, invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("evalAsync");
            assertThat((RScript.Mode) invocation.getArgument(0)).isEqualTo(RScript.Mode.READ_WRITE);
            assertThat(invocation.<List<String>>getArgument(3)).containsExactly(keys.guildTerminalCleanupWatermark());
            String lua = invocation.getArgument(1);
            commands.add(lua);
            return lua.equals(RedissonTerminalCleanupWatermark.READ)
                    ? new CompletableFutureWrapper<>("1700000000000") : new CompletableFutureWrapper<>(1L);
        });
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
        var watermark = new RedissonTerminalCleanupWatermark(redis, keys);
        watermark.advance(1_700_000_000_000L, Deadline.after(1000));
        assertThat(watermark.read(Deadline.after(1000))).isEqualTo(1_700_000_000_000L);
        assertThat(commands).containsExactly(RedissonTerminalCleanupWatermark.ADVANCE, RedissonTerminalCleanupWatermark.READ);
        assertThatThrownBy(() -> watermark.read(Deadline.after(0))).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> watermark.advance(1, Deadline.after(0))).isInstanceOf(Deadline.DependencyException.class);
        assertThat(commands).hasSize(2);

        RScript failing = mock(RScript.class, invocation -> new CompletableFutureWrapper<>(
                CompletableFuture.failedFuture(new IllegalStateException("redis unavailable"))));
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(failing);
        assertThatThrownBy(() -> watermark.read(Deadline.after(1000))).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> watermark.advance(1, Deadline.after(1000))).isInstanceOf(Deadline.DependencyException.class);
    }
}
