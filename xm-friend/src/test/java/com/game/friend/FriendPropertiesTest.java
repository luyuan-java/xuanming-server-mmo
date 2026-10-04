package com.game.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FriendPropertiesTest {

    @Test
    void 缺省值同基线() {
        FriendProperties p = new FriendProperties(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(p.limits()).isEqualTo(new com.game.friend.store.FriendLimits(200, 50, 200, 200));
        assertThat(p.requestQuotaPerMinute()).isEqualTo(10);
        assertThat(p.listReadHardLimit()).isEqualTo(1000);
        assertThat(p.cacheTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.requestBudget()).isEqualTo(Duration.ofMillis(3500));
    }

    @Test
    void 启动校验() {
        assertThatThrownBy(() -> new FriendProperties(301, null, null, null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("max-friends");
        assertThatThrownBy(() -> new FriendProperties(0, null, null, null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("max-friends");
        assertThatThrownBy(() -> new FriendProperties(null, null, null, null, null, null, Duration.ZERO, null, null, null,
                null, null)).hasMessageContaining("cache-ttl");
        assertThatThrownBy(() -> new FriendProperties(null, null, null, null, null, null, null, Duration.ofSeconds(5), null,
                null, null, null)).hasMessageContaining("request-budget");
    }
}
