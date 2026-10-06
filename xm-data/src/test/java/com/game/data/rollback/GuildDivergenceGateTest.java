package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GuildAssetOpBrief;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.api.proto.ListAppliedResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 帮会资产闸（T-R4 / T-R5，逐条对齐基线 guildcheck 与 {@code TestGuildSinceMs}）：只认 OK；保留期钳位重查；翻页 / 块外玩家 / 上限；
 * 起点钳到 ≥ 1；每人各自的起点过滤。
 */
class GuildDivergenceGateTest {

    private final List<ListAppliedAssetOpsSinceRequest> calls = new ArrayList<>();

    private GuildDivergenceGate gate(Function<ListAppliedAssetOpsSinceRequest, ListAppliedAssetOpsSinceResponse> answer) {
        return new GuildDivergenceGate((req, timeout) -> {
            calls.add(req);
            return CompletableFuture.completedFuture(answer.apply(req));
        }, Duration.ofSeconds(1));
    }

    private static ListAppliedAssetOpsSinceResponse.Builder ok(GuildAssetOpBrief... ops) {
        return ListAppliedAssetOpsSinceResponse.newBuilder().setResult(ListAppliedResult.LIST_APPLIED_RESULT_OK)
                .addAllOps(List.of(ops));
    }

    private static GuildAssetOpBrief op(long opId, long player, long updatedMs) {
        return GuildAssetOpBrief.newBuilder().setOpId(opId).setPlayerId(player).setUpdatedMs(updatedMs).build();
    }

    private static Map<Long, Long> since(long... pairs) {
        Map<Long, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    void 起点_快照时刻减余量_钳到1_不取整到秒() {
        assertThat(GuildDivergenceGate.since(1_700_000_000_123L, Duration.ofSeconds(300))).isEqualTo(1_699_999_700_123L);
        assertThat(GuildDivergenceGate.since(1000, Duration.ofSeconds(300))).isEqualTo(1);
        assertThat(GuildDivergenceGate.since(0, Duration.ofSeconds(5))).isEqualTo(1);
    }

    @Test
    void 干净_空玩家集不发请求() {
        GuildDivergenceGate g = gate(r -> ok().build());
        assertThat(g.check(Map.of(), Duration.ofSeconds(1)).clean()).isTrue();
        assertThat(calls).isEmpty();
        assertThat(g.check(since(7, 100), Duration.ofSeconds(1)).clean()).isTrue();
        assertThat(calls.get(0).getLimit()).isEqualTo(500);
        assertThat(calls.get(0).getZoneId()).isZero();
    }

    @Test
    void 一块用最早起点问_按每人自己的起点过滤() {
        GuildDivergenceGate g = gate(r -> ok(op(1, 7, 150), op(2, 8, 150), op(3, 8, 250)).build());
        GuildDivergenceGate.Result r = g.check(since(7, 100, 8, 200), Duration.ofSeconds(1));
        assertThat(calls.get(0).getSinceMs()).isEqualTo(100);
        assertThat(r.checkFailed()).isFalse();
        assertThat(r.rows()).extracting(GuildAssetOpBrief::getOpId).containsExactly(1L, 3L);
    }

    @Test
    void 非OK一律check_failed_含缺省0_UNAVAILABLE_ERROR_异常完成_未装配() {
        for (ListAppliedResult bad : List.of(ListAppliedResult.LIST_APPLIED_RESULT_UNSPECIFIED,
                ListAppliedResult.LIST_APPLIED_RESULT_UNAVAILABLE, ListAppliedResult.LIST_APPLIED_RESULT_ERROR,
                ListAppliedResult.LIST_APPLIED_RESULT_INVALID_ARGUMENT)) {
            GuildDivergenceGate.Result r = gate(req -> ListAppliedAssetOpsSinceResponse.newBuilder().setResult(bad).build())
                    .check(since(7, 100), Duration.ofSeconds(1));
            assertThat(r.checkFailed()).as(bad.name()).isTrue();
        }
        GuildDivergenceGate failing = new GuildDivergenceGate((req, t) -> CompletableFuture.failedFuture(
                new IllegalStateException("连不上")), Duration.ofSeconds(1));
        assertThat(failing.check(since(7, 100), Duration.ofSeconds(1)).failure()).contains("连不上");
        GuildDivergenceGate hanging = new GuildDivergenceGate((req, t) -> new CompletableFuture<>(), Duration.ofMillis(50));
        assertThat(hanging.check(since(7, 100), Duration.ofMillis(100)).checkFailed()).isTrue();
        assertThat(new GuildDivergenceGate(null, Duration.ofSeconds(1)).check(since(7, 100), Duration.ofSeconds(1))
                .failure()).contains("没有装配");
    }

    @Test
    void 保留期拒绝_用cutoff钳位重查一次_早于cutoff的玩家不可证明() {
        GuildDivergenceGate g = gate(req -> req.getSinceMs() < 500
                ? ListAppliedAssetOpsSinceResponse.newBuilder()
                        .setResult(ListAppliedResult.LIST_APPLIED_RESULT_RETENTION_REJECTED).setCutoffMs(500).build()
                : ok(op(9, 8, 900)).build());
        GuildDivergenceGate.Result r = g.check(since(7, 100, 8, 600), Duration.ofSeconds(1));
        assertThat(r.checkFailed()).isFalse();
        assertThat(r.unprovable()).containsExactly(7L);
        assertThat(r.rows()).extracting(GuildAssetOpBrief::getOpId).containsExactly(9L);
        assertThat(calls).extracting(ListAppliedAssetOpsSinceRequest::getSinceMs).containsExactly(100L, 500L);

        GuildDivergenceGate twice = gate(req -> ListAppliedAssetOpsSinceResponse.newBuilder()
                .setResult(ListAppliedResult.LIST_APPLIED_RESULT_RETENTION_REJECTED).setCutoffMs(req.getSinceMs() + 10).build());
        assertThat(twice.check(since(7, 100), Duration.ofSeconds(1)).checkFailed()).isTrue();
    }

    @Test
    void 翻页_游标前进则续翻_不前进或块外玩家或超上限都check_failed() {
        GuildDivergenceGate paged = gate(req -> req.getAfterOpId() == 0
                ? ok(op(1, 7, 500)).setNextAfterOpId(1).build() : ok(op(2, 7, 500)).build());
        assertThat(paged.check(since(7, 100), Duration.ofSeconds(1)).rows()).hasSize(2);

        GuildDivergenceGate stuck = gate(req -> req.getAfterOpId() == 0
                ? ok(op(5, 7, 500)).setNextAfterOpId(5).build() : ok(op(6, 7, 500)).setNextAfterOpId(5).build());
        assertThat(stuck.check(since(7, 100), Duration.ofSeconds(1)).failure()).contains("没有前进");

        GuildDivergenceGate backwards = gate(req -> ok(op(3, 7, 500)).setNextAfterOpId(9).build());
        assertThat(backwards.check(since(7, 100), Duration.ofSeconds(1)).failure()).contains("没有前进");

        GuildDivergenceGate stranger = gate(req -> ok(op(5, 42, 500)).build());
        assertThat(stranger.check(since(7, 100), Duration.ofSeconds(1)).failure()).contains("块外");

        GuildDivergenceGate huge = gate(req -> {
            ListAppliedAssetOpsSinceResponse.Builder b = ok();
            for (int i = 1; i <= 500; i++) {
                b.addOps(op(req.getAfterOpId() + i, 7, 500));
            }
            return b.setNextAfterOpId(req.getAfterOpId() + 500).build();
        });
        assertThat(huge.check(since(7, 100), Duration.ofSeconds(5)).failure()).contains("10000");
    }

    @Test
    void 超过100人_分块问() {
        GuildDivergenceGate g = gate(req -> ok().build());
        Map<Long, Long> many = new LinkedHashMap<>();
        for (long p = 1; p <= 250; p++) {
            many.put(p, 100L);
        }
        assertThat(g.check(many, Duration.ofSeconds(1)).clean()).isTrue();
        assertThat(calls).extracting(ListAppliedAssetOpsSinceRequest::getPlayerIdsCount).containsExactly(100, 100, 50);
    }
}
