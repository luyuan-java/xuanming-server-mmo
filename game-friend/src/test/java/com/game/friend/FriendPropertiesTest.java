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

    @Test
    void 推荐默认排除预算与所需返回数放得进固定窗口() {
        FriendProperties p = props(null, null, null);
        // 自己、好友、双向申请、自己的拉黑、客户端 exclude、上一级已选最多 limit-1 人，再留够 limit。
        long needed = 1L + p.maxFriends() + p.maxPendingRequests() + p.maxIncomingRequests() + p.maxBlocks()
                + p.recommend().maxExclude() + (p.recommend().maxLimit() - 1) + p.recommend().maxLimit();
        assertThat(needed).isEqualTo(754);
        assertThat(needed).isLessThanOrEqualTo(com.game.friend.store.RecommendStore.ANCHOR_WINDOW);
        // 拉黑我的人数没有上限，因此这不是必然凑满的承诺，不能用继续扫描来补足。
    }
}
