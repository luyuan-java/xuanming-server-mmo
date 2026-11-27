package com.game.rank;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 排行发奖明确拒收退避（1 分钟起翻倍、1 小时封顶、不晚于发件截止点 expireMs - 24h）与安全上限。 */
class RankRewardBackoffTest {

    @Test
    void 明确拒收退避从1分钟翻倍至1小时且不超过发件截止点() {
        long now = 1_000_000L;
        long farExpire = now + 72L * 3_600_000L;
        assertThat(RankRewardWorker.nextRejectAttemptMs(now, 1, farExpire)).isEqualTo(now + 60_000L);
        assertThat(RankRewardWorker.nextRejectAttemptMs(now, 2, farExpire)).isEqualTo(now + 120_000L);
        assertThat(RankRewardWorker.nextRejectAttemptMs(now, 3, farExpire)).isEqualTo(now + 240_000L);
        assertThat(RankRewardWorker.nextRejectAttemptMs(now, 7, farExpire)).isEqualTo(now + 3_600_000L);
        assertThat(RankRewardWorker.nextRejectAttemptMs(now, 100, farExpire)).isEqualTo(now + 3_600_000L);

        // 距离发件截止点（expireMs - 24h）仅剩 30 秒时，下次尝试夹紧到截止点
        long nearExpire = now + RankRewardWorker.MIN_REWARD_CLAIM_WINDOW_MS + 30_000L;
        assertThat(RankRewardWorker.nextRejectAttemptMs(now, 1, nearExpire)).isEqualTo(now + 30_000L);
        assertThat(RankRewardWorker.MAX_SAFETY_ATTEMPTS).isEqualTo(50_000);
    }
}
