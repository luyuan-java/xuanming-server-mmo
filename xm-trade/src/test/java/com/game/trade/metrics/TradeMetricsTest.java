package com.game.trade.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.trade.dispatch.TradeMethods;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 指标的预建与标签基数（trade-spec §6；基线 StartMetrics 预建全部 result 序列，servicecontext.go:377-386）：启动即存在、计数为 0；
 * 标签值只取固定集合，不含 player_id / listing_id / zone。
 */
class TradeMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TradeMetrics metrics = new TradeMetrics(meters);

    @Test
    void 全部序列启动即预建为0() {
        for (String method : TradeMethods.CLIENT_REQUESTS) {
            for (String result : List.of("ok", "business_error", "internal_error", "overloaded", "bad_request", "unauthenticated")) {
                assertThat(meters.get("xm.trade.requests").tag("method", method).tag("result", result).timer().count())
                        .as(method + "/" + result).isZero();
            }
        }
        for (String result : List.of("forbidden", "bad_request", "unauthenticated")) {
            assertThat(meters.get("xm.trade.requests").tag("method", TradeMethods.SEED_LISTING).tag("result", result).timer())
                    .isNotNull();
        }
        assertThat(meters.get("xm.trade.requests").tag("method", TradeMetrics.UNROUTED).tag("result", "unsupported").timer())
                .isNotNull();
        for (String result : List.of("ok", "unmapped", "error")) {
            assertThat(meters.get("xm.trade.home.zone.lookups").tag("result", result).counter().count()).isZero();
        }
        for (String result : List.of("ok", "rejected", "error")) {
            assertThat(meters.get("xm.trade.seed.listings").tag("result", result).counter().count()).isZero();
        }
        assertThat(meters.get("xm.trade.favorite.retries").counter().count()).isZero();
        for (String op : List.of("seed_listing", "other")) {
            for (String status : List.of("200", "400", "401", "403", "500", "503")) {
                assertThat(meters.get("xm.trade.admin.requests").tag("op", op).tag("status", status).counter().count()).isZero();
            }
        }
    }

    @Test
    void 计数与计时() {
        Timer.Sample sample = metrics.startTimer();
        metrics.requestCompleted(sample, TradeMethods.BROWSE_LISTINGS, TradeMetrics.RequestResult.OK);
        metrics.homeZoneLookup(TradeMetrics.LookupResult.UNMAPPED);
        metrics.seedListing(TradeMetrics.SeedResult.ERROR);
        metrics.favoriteRetry();
        metrics.adminRequest(TradeMetrics.ADMIN_OP_SEED_LISTING, 404);
        metrics.adminRequest("/任意路径", 200);

        assertThat(meters.get("xm.trade.requests").tag("method", TradeMethods.BROWSE_LISTINGS).tag("result", "ok").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("xm.trade.home.zone.lookups").tag("result", "unmapped").counter().count()).isEqualTo(1);
        assertThat(meters.get("xm.trade.seed.listings").tag("result", "error").counter().count()).isEqualTo(1);
        assertThat(meters.get("xm.trade.favorite.retries").counter().count()).isEqualTo(1);
        assertThat(meters.get("xm.trade.admin.requests").tag("op", "seed_listing").tag("status", "404").counter().count()).isEqualTo(1);
        assertThat(meters.get("xm.trade.admin.requests").tag("op", "other").tag("status", "200").counter().count())
                .as("未知 op 一律归 other").isEqualTo(1);
    }

    @Test
    void 标签集合有界() {
        Set<String> tagKeys = new HashSet<>();
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag -> tagKeys.add(tag.getKey()));
        }
        tagKeys.remove("le"); // SLO 桶的边界（SimpleMeterRegistry 把直方图桶暴露成带 le 的计量），取值是固定的 11 个桶
        assertThat(tagKeys).containsExactlyInAnyOrder("method", "result", "op", "status");
        Set<String> methods = new HashSet<>();
        meters.get("xm.trade.requests").timers().forEach(t -> methods.add(t.getId().getTag("method")));
        Set<String> allowed = new HashSet<>(TradeMethods.CLIENT_REQUESTS);
        allowed.add(TradeMethods.SEED_LISTING);
        allowed.add(TradeMetrics.UNROUTED);
        assertThat(methods).isEqualTo(allowed);
    }
}
