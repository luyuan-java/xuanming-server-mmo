package com.game.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FriendPropertiesTest {

    private static FriendProperties props(Integer maxFriends, Duration cacheTtl, Duration requestBudget) {
        return new FriendProperties(maxFriends, null, null, null, null, null, cacheTtl, requestBudget, null, null, null, null,
                null, null);
    }

    @Test
    void 缺省值同基线() {
        FriendProperties p = props(null, null, null);
        assertThat(p.limits()).isEqualTo(new com.game.friend.store.FriendLimits(200, 50, 200, 200));
        assertThat(p.requestQuotaPerMinute()).isEqualTo(10);
        assertThat(p.listReadHardLimit()).isEqualTo(1000);
        assertThat(p.cacheTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThat(p.recommend()).isEqualTo(new FriendProperties.Recommend(10, 20, 64));
        assertThat(p.sweep()).isEqualTo(new FriendProperties.Sweep("report_only", Duration.ofMinutes(5), 7, 1000));
    }

    @Test
    void 启动校验() {
        assertThatThrownBy(() -> props(301, null, null)).hasMessageContaining("max-friends");
        assertThatThrownBy(() -> props(0, null, null)).hasMessageContaining("max-friends");
        assertThatThrownBy(() -> props(null, Duration.ZERO, null)).hasMessageContaining("cache-ttl");
        assertThatThrownBy(() -> props(null, null, Duration.ofSeconds(5))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> props(null, null, Duration.ofMillis(100))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> new FriendProperties.Recommend(null, 21, null)).hasMessageContaining("max-limit");
        assertThatThrownBy(() -> new FriendProperties.Recommend(15, 10, null)).hasMessageContaining("default-limit");
        assertThatThrownBy(() -> new FriendProperties.Sweep("", null, null, null)).hasMessageContaining("sweep.mode");
        assertThatThrownBy(() -> new FriendProperties.Sweep(null, null, 36501, null)).hasMessageContaining("retention-days");
    }
}
