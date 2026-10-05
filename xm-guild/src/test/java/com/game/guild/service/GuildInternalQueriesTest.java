package com.game.guild.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.api.proto.ListAppliedResult;
import com.game.common.deadline.Deadline;
import com.game.guild.asset.InMemoryAssetStore;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * 内部查询 ListAppliedAssetOpsSince（照基线 guild_internal_server_test.go:84-251；guild-economy-spec §3.6、§11.1「内部查询边界」、E11）：入参校验顺序与文案、
 * 不可用、保留期判定（cutoff = now + 1 h − 保留期，下溢取 0）、只回 APPLIED / APPLIED_PARTIAL、按 op_id 翻页、查询失败与工作队列满回 ERROR、指标。
 */
class GuildInternalQueriesTest {

    static final long NOW = 1_700_000_000_000L;
    static final Duration RETENTION = Duration.ofDays(30);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GuildMetrics metrics = new GuildMetrics(meters);
    private final InMemoryAssetStore store = new InMemoryAssetStore();

    private GuildInternalQueries queries(Duration retention) {
        return new GuildInternalQueries(store, retention, Runnable::run, metrics, () -> NOW, 3_500);
    }

    private ListAppliedAssetOpsSinceResponse call(GuildInternalQueries q, ListAppliedAssetOpsSinceRequest.Builder req) {
        return q.listAppliedAssetOpsSince(req.build()).join();
    }

    private static ListAppliedAssetOpsSinceRequest.Builder req(long sinceMs, Long... players) {
        return ListAppliedAssetOpsSinceRequest.newBuilder().setSinceMs(sinceMs).addAllPlayerIds(List.of(players));
    }

    private double count(String result) {
        return meters.get("xm.guild.internal.list.applied").tag("result", result).counter().count();
    }

    @Test
    void 入参校验_顺序与文案同基线() {
        GuildInternalQueries q = queries(RETENTION);
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            many.add(i);
        }
        assertThat(call(q, req(NOW)).getDetail()).isEqualTo("player_ids is required");
        assertThat(call(q, ListAppliedAssetOpsSinceRequest.newBuilder().setSinceMs(NOW).addAllPlayerIds(many)).getDetail())
                .isEqualTo("player_ids has 101 entries, limit 100");
        assertThat(call(q, req(NOW, 1L, 0L)).getDetail()).isEqualTo("player_ids must not contain 0");
        assertThat(call(q, req(NOW, 1L, 1L)).getDetail()).isEqualTo("player_ids must not contain duplicates");
        assertThat(call(q, req(0, 1L)).getDetail()).isEqualTo("since_ms is required");
        ListAppliedAssetOpsSinceResponse r = call(q, req(NOW, 1L).setLimit(501));
        assertThat(r.getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_INVALID_ARGUMENT);
        assertThat(r.getDetail()).isEqualTo("limit 501 exceeds 500");
        assertThat(r.getOpsList()).isEmpty();
        assertThat(count("invalid")).isEqualTo(6);
    }

    @Test
    void 保留期不可用_早于下界回RETENTION_REJECTED带cutoff() {
        assertThat(call(queries(Duration.ZERO), req(NOW, 1L)).getResult())
                .isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_UNAVAILABLE);
        assertThat(call(new GuildInternalQueries(null, RETENTION, Runnable::run, metrics, () -> NOW, 3_500), req(NOW, 1L))
                .getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_UNAVAILABLE);
        assertThat(count("unavailable")).isEqualTo(2);

        long cutoff = NOW + 3_600_000L - RETENTION.toMillis();
        ListAppliedAssetOpsSinceResponse r = call(queries(RETENTION), req(cutoff - 1, 1L));
        assertThat(r.getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_RETENTION_REJECTED);
        assertThat(r.getCutoffMs()).isEqualTo(cutoff);
        assertThat(r.getDetail()).isEqualTo("since_ms older than terminal retention; cutoff_ms=" + cutoff);
        assertThat(call(queries(RETENTION), req(cutoff, 1L)).getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_OK);
        assertThat(count("retention")).isEqualTo(1);
    }

    private void applied(long opId, long player, GuildAssetOpStatus status, long finalizedMs) {
        store.insert(GuildAssetOpRow.newBuilder().setOpId(opId).setPlayerId(player).setGuildId(500).setStream(1).setKindValue(1)
                .setStatus(status).setNextAttemptMs(finalizedMs).setUpdatedMs(finalizedMs).setFundsDelta(10)
                .setContributionDelta(1).build());
    }

    @Test
    void 只回已应用与部分发放_since严格_按op_id翻页() {
        long since = NOW - 60_000;
        applied(1, 7, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, since + 1);
        applied(2, 7, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL, since + 2);
        applied(3, 7, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED, since + 3);
        applied(4, 7, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, since);
        applied(5, 8, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, since + 5);
        applied(6, 9, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, since + 6);
        GuildInternalQueries q = queries(RETENTION);
        ListAppliedAssetOpsSinceResponse page1 = call(q, req(since, 7L, 8L).setLimit(2));
        assertThat(page1.getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_OK);
        assertThat(page1.getOpsList()).extracting(o -> o.getOpId()).containsExactly(1L, 2L);
        assertThat(page1.getNextAfterOpId()).isEqualTo(2);
        assertThat(page1.getOps(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL_VALUE);
        ListAppliedAssetOpsSinceResponse page2 = call(q, req(since, 7L, 8L).setLimit(2).setAfterOpId(2));
        assertThat(page2.getOpsList()).extracting(o -> o.getOpId()).containsExactly(5L);
        assertThat(page2.getNextAfterOpId()).isZero();
        ListAppliedAssetOpsSinceResponse empty = call(q, req(since, 42L));
        assertThat(empty.getOpsList()).isEmpty();
        assertThat(count("ok_rows")).isEqualTo(2);
        assertThat(count("ok_empty")).isEqualTo(1);
        assertThat(meters.get("xm.guild.internal.list.applied.rows").summary().count()).isEqualTo(3);
    }

    @Test
    void 查询失败与工作队列满回ERROR_库错误原文不外发() {
        InMemoryAssetStore failing = new InMemoryAssetStore() {
            @Override
            public AppliedOpsPage listAppliedSince(com.game.guild.asset.AppliedOpsQuery query, Deadline deadline) {
                throw new Deadline.DependencyException("Communications link failure: secret-host");
            }
        };
        ListAppliedAssetOpsSinceResponse r = new GuildInternalQueries(failing, RETENTION, Runnable::run, metrics, () -> NOW, 3_500)
                .listAppliedAssetOpsSince(req(NOW, 1L).build()).join();
        assertThat(r.getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_ERROR);
        assertThat(r.getDetail()).isEqualTo("list applied asset ops failed");
        r = new GuildInternalQueries(store, RETENTION, task -> {
            throw new RejectedExecutionException("full");
        }, metrics, () -> NOW, 3_500).listAppliedAssetOpsSince(req(NOW, 1L).build()).join();
        assertThat(r.getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_ERROR);
        assertThat(count("error")).isEqualTo(2);
    }

    @Test
    void 结果标签启动即预建() {
        for (String result : List.of("ok_empty", "ok_rows", "invalid", "retention", "unavailable", "error")) {
            assertThat(count(result)).isZero();
        }
    }
}
