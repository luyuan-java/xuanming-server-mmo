package com.game.trade.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 代码常量照基线 constants.go:49-79（trade-spec §0.6）与 §5.4 / §5.5 / Q7 钉死：改数值就是改客户端可见行为或预算模型，必须同时改 spec。 */
class TradeLimitsTest {

    @Test
    void 输入上限照基线() {
        assertThat(TradeLimits.MAX_SEARCH_RUNES).isEqualTo(64);
        assertThat(TradeLimits.MAX_TITLE_RUNES).isEqualTo(64);
        assertThat(TradeLimits.MAX_SUMMARY_RUNES).isEqualTo(128);
        assertThat(TradeLimits.MAX_DESCRIPTION_RUNES).isEqualTo(512);
        assertThat(TradeLimits.MAX_ICON_KEY_LEN).isEqualTo(64);
        assertThat(TradeLimits.MAX_LEVEL).isEqualTo(1000);
        assertThat(TradeLimits.MAX_PRICE_FEN).isEqualTo(10_000_000_000L);
        assertThat(TradeLimits.MAX_NOTICE_DURATION_MS).isEqualTo(30L * 24 * 60 * 60 * 1000);
        assertThat(TradeLimits.MAX_SALE_DURATION_MS).isEqualTo(90L * 24 * 60 * 60 * 1000);
    }

    @Test
    void 单次上限与重试照基线() {
        assertThat(TradeLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS).isEqualTo(1_500L);
        assertThat(TradeLimits.STORE_OP_TIMEOUT_MS).isEqualTo(2_000L);
        // 单次上限都必须小于整请求预算 3500 ms（Timeout 4000 − 500），否则「与请求剩余取先到者」形同虚设
        assertThat(TradeLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS).isLessThan(3_500L);
        assertThat(TradeLimits.STORE_OP_TIMEOUT_MS).isLessThan(3_500L);
        assertThat(TradeLimits.FAVORITE_WRITE_ATTEMPTS).isEqualTo(2);
        assertThat(TradeLimits.FAVORITE_RETRY_BACKOFF_MS).isEqualTo(10L);
    }

    /** Q7：锁等待 2 s，不超过单条 SQL 的 2000 ms 上限。 */
    @Test
    void 锁等待对齐单次SQL上限() {
        assertThat(TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS).isEqualTo(2);
        assertThat(TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS * 1000L).isLessThanOrEqualTo(TradeLimits.STORE_OP_TIMEOUT_MS);
    }
}
