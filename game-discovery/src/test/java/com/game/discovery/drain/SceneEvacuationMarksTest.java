package com.game.discovery.drain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;

class SceneEvacuationMarksTest {
    private final RedissonClient redis = mock(RedissonClient.class);
    private final SceneEvacuationMarks marks = new SceneEvacuationMarks(redis, new RedisKeys(GameNamespace.of("game")));

    @Test
    void 标记绑定实例_空目录不访问Redis() {
        var mark = SceneEvacuationMarks.Mark.parse("1800000000:instance-a");
        assertThat(mark.markedAtSec()).isEqualTo(1_800_000_000L);
        assertThat(mark.raw()).isEqualTo("1800000000:instance-a");
        assertThat(mark.appliesTo("instance-a")).isTrue();
        assertThat(mark.appliesTo("instance-b")).isFalse();
        assertThat(marks.evacuating(1, List.of())).isEmpty();
        verifyNoInteractions(redis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "x:i", "0:i", "-1:i", "123:", "123:i:other", "123: "})
    void 损坏值必须是读失败不能解释成撤销(String raw) {
        assertThatThrownBy(() -> SceneEvacuationMarks.Mark.parse(raw)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 无效实例TTL及自身后备不能调用Lua() {
        assertThatThrownBy(() -> marks.mark(1, 1, "", Duration.ofSeconds(1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> marks.mark(1, 1, "i", Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> marks.mark(1, 1, "i", Duration.ofSeconds(1), Map.of(1, "i")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> marks.mark(1, 1, "i", Duration.ofSeconds(1), Map.of(2, "")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis);
    }
}
