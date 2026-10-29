package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.gate.link.EnterFailure;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PendingSceneEntryTest {
    @Test void 单调时钟回绕仍将目录缺席收紧至三秒且不重新延长() {
        long started = Long.MAX_VALUE - Duration.ofSeconds(10).toNanos();
        var entry = new PendingSceneEntry(1, PlayerEnter.getDefaultInstance(), started);
        entry.attempts = 1;
        assertThat(entry.retryDelay(EnterFailure.NODE_MISSING, started)).isPositive();
        assertThat(entry.deadline - started).isEqualTo(Duration.ofSeconds(3).toNanos());
        assertThat(entry.retryDelay(EnterFailure.NODE_MISSING, started + Duration.ofSeconds(3).toNanos())).isEqualTo(-1);
    }
}
