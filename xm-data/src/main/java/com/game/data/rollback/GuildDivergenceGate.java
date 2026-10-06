package com.game.data.rollback;

import com.game.api.proto.GuildAssetOpBrief;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.api.proto.ListAppliedResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 回档的帮会资产闸（data-ops-spec §4.6.1，逐条对齐基线 {@code rollback_logic.go:128-436} / {@code guild_divergence.go}）：
 * 问 xm-guild「这些玩家自快照时刻（减余量）以来，有没有已终结为已应用的帮会资产指令」（{@code GuildInternalService.listAppliedAssetOpsSince}）。
 *
 * <ul>
 *   <li><b>起点</b>：{@code since = 快照 time_ms（内容时刻）− 余量}，钳到 ≥ 1，不取整到秒（D9）。每人各自的起点；一块（≤ 100 人）用块内最早的起点问，
 *       再按每人自己的起点过滤（{@code updated_ms > since_p}），结果相同、请求更少。</li>
 *   <li><b>只认 OK</b>：其它结果码（含缺省 0）、future 异常完成、超预算、游标不前进、返回块外玩家、过滤后超过 {@value #MAX_ROWS} 行，一律
 *       {@link Result#checkFailed()}——放行无效（基线 28）。帮会检查把非 OK 当「没有分歧」会让回档复制资产（§9.2 第 8 条）。</li>
 *   <li><b>保留期拒绝</b>：用应答的 {@code cutoff_ms} 钳位重查一次；起点早于 cutoff 的玩家记为不可证明（那一段已无从得知）。</li>
 * </ul>
 * 没有装配客户端（{@code xm.dubbo.guild-url} 为空）= 一律 check_failed（基线 checker 为 nil 就拒绝，D2）。只读、无状态、线程安全。
 */
public final class GuildDivergenceGate {

    /** 每块玩家数、每页行数、过滤后的行数上限、样本条数（同基线 100 / 500 / 10000 / 20）。 */
    public static final int CHUNK = 100;
    public static final int PAGE = 500;
    public static final int MAX_ROWS = 10_000;
    public static final int SAMPLE = 20;

    /** 帮会内部查询的调用方（生产为 Dubbo，见 {@link DubboGuildInternalClient}）。 */
    @FunctionalInterface
    public interface Client {
        /** future 异常完成 = 问不到。 */
        CompletableFuture<ListAppliedAssetOpsSinceResponse> list(ListAppliedAssetOpsSinceRequest request, Duration timeout);
    }

    /**
     * 检查结果。
     *
     * @param checkFailed 问不到（放行无效）；{@code failure} 是原因
     * @param rows        快照之后已应用的帮会指令（按 op_id 升序；各自起点过滤后）
     * @param unprovable  不可证明的玩家（保留期钳位之后起点之前那一段无从得知）
     */
    public record Result(boolean checkFailed, String failure, List<GuildAssetOpBrief> rows, Set<Long> unprovable) {

        public static Result failed(String failure) {
            return new Result(true, failure, List.of(), Set.of());
        }

        public boolean clean() {
            return !checkFailed && rows.isEmpty() && unprovable.isEmpty();
        }

        /** 摘要（进 CHECK / RECHECK 事件与作业摘要；样本至多 {@value #SAMPLE} 条）。 */
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("checkFailed", checkFailed);
            if (checkFailed) {
                m.put("failure", failure);
            }
            m.put("rows", rows.size());
            m.put("unprovablePlayers", unprovable.stream().map(Long::toUnsignedString).limit(SAMPLE).toList());
            m.put("unprovableCount", unprovable.size());
            List<Map<String, Object>> sample = new ArrayList<>();
            for (GuildAssetOpBrief op : rows.subList(0, Math.min(SAMPLE, rows.size()))) {
                sample.add(opView(op));
            }
            m.put("sample", sample);
            return m;
        }
    }

    static Map<String, Object> opView(GuildAssetOpBrief op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opId", Long.toUnsignedString(op.getOpId()));
        m.put("playerId", Long.toUnsignedString(op.getPlayerId()));
        m.put("guildId", Long.toUnsignedString(op.getGuildId()));
        m.put("stream", op.getStream());
        m.put("kind", op.getKind());
        m.put("status", op.getStatus());
        m.put("fundsDelta", Long.toUnsignedString(op.getFundsDelta()));
        m.put("contributionDelta", Long.toUnsignedString(op.getContributionDelta()));
        m.put("updatedMs", Long.toUnsignedString(op.getUpdatedMs()));
        return m;
    }

    private final Client client;
    private final Duration callTimeout;

    /** @param client null = 没有装配（一律 check_failed） */
    public GuildDivergenceGate(Client client, Duration callTimeout) {
        this.client = client;
        this.callTimeout = callTimeout;
    }

    /** 检查起点：快照内容时刻 − 余量，钳到 ≥ 1（同基线 {@code rollback_logic.go:240-249}，但不取整到秒）。 */
    public static long since(long snapshotTimeMs, Duration margin) {
        long since = snapshotTimeMs - margin.toMillis();
        return since < 1 || since > snapshotTimeMs ? 1 : since;
    }

    /**
     * @param sinceByPlayer 每人的起点（{@link #since}）；空 = 干净
     * @param budget        这次检查（含翻页、保留期重查）的总预算
     */
    public Result check(Map<Long, Long> sinceByPlayer, Duration budget) {
        if (sinceByPlayer.isEmpty()) {
            return new Result(false, null, List.of(), Set.of());
        }
        if (client == null) {
            return Result.failed("帮会检查没有装配（xm.dubbo.guild-url 为空）：一律拒绝");
        }
        long deadline = System.nanoTime() + budget.toNanos();
        TreeMap<Long, Long> sorted = new TreeMap<>(Long::compareUnsigned);
        sorted.putAll(sinceByPlayer);
        List<Long> ids = new ArrayList<>(sorted.keySet());
        List<GuildAssetOpBrief> rows = new ArrayList<>();
        Set<Long> unprovable = new TreeSet<>(Long::compareUnsigned);
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            String failure = checkChunk(chunk, sorted, deadline, rows, unprovable);
            if (failure != null) {
                return Result.failed(failure);
            }
        }
        rows.sort((a, b) -> Long.compareUnsigned(a.getOpId(), b.getOpId()));
        return new Result(false, null, List.copyOf(rows), Set.copyOf(unprovable));
    }

    /** 一块玩家；失败返回原因。 */
    private String checkChunk(List<Long> chunk, Map<Long, Long> sinceByPlayer, long deadline,
                              List<GuildAssetOpBrief> rows, Set<Long> unprovable) {
        long since = Long.MAX_VALUE;
        for (long p : chunk) {
            since = Math.min(since, sinceByPlayer.get(p));
        }
        Set<Long> members = Set.copyOf(chunk);
        boolean clamped = false;
        long after = 0;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return "帮会检查预算耗尽";
            }
            Duration timeout = Duration.ofNanos(Math.min(remaining, callTimeout.toNanos()));
            ListAppliedAssetOpsSinceRequest request = ListAppliedAssetOpsSinceRequest.newBuilder()
                    .setZoneId(0)
                    .addAllPlayerIds(chunk)
                    .setSinceMs(since)
                    .setAfterOpId(after)
                    .setLimit(PAGE)
                    .build();
            ListAppliedAssetOpsSinceResponse response;
            try {
                response = client.list(request, timeout).get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "帮会检查被中断";
            } catch (ExecutionException e) {
                return "帮会检查调用失败：" + e.getCause();
            } catch (TimeoutException e) {
                return "帮会检查调用超时（" + timeout.toMillis() + " ms）";
            } catch (RuntimeException e) {
                return "帮会检查调用失败：" + e;
            }
            if (response == null) {
                return "帮会检查应答为空";
            }
            ListAppliedResult result = response.getResult();
            if (result == ListAppliedResult.LIST_APPLIED_RESULT_RETENTION_REJECTED) {
                long cutoff = response.getCutoffMs();
                if (clamped || cutoff <= since || after != 0) {
                    return "帮会保留期拒绝且无法钳位（cutoff_ms=" + Long.toUnsignedString(cutoff) + "，since="
                            + since + "）：" + response.getDetail();
                }
                for (long p : chunk) {
                    if (sinceByPlayer.get(p) < cutoff) {
                        unprovable.add(p);
                    }
                }
                since = cutoff;
                clamped = true;
                continue;
            }
            if (result != ListAppliedResult.LIST_APPLIED_RESULT_OK) {
                return "帮会检查结果码 " + result + "（只认 OK）：" + response.getDetail();
            }
            for (GuildAssetOpBrief op : response.getOpsList()) {
                if (!members.contains(op.getPlayerId())) {
                    return "帮会检查返回了块外的玩家 " + Long.toUnsignedString(op.getPlayerId());
                }
                if (Long.compareUnsigned(op.getOpId(), after) <= 0) {
                    return "帮会检查翻页没有前进（op_id " + Long.toUnsignedString(op.getOpId()) + " ≤ 游标 "
                            + Long.toUnsignedString(after) + "）";
                }
                if (Long.compareUnsigned(op.getUpdatedMs(), sinceByPlayer.get(op.getPlayerId())) > 0) {
                    rows.add(op);
                    if (rows.size() > MAX_ROWS) {
                        return "帮会检查过滤后超过 " + MAX_ROWS + " 行";
                    }
                }
            }
            long next = response.getNextAfterOpId();
            if (next == 0) {
                return null;
            }
            if (Long.compareUnsigned(next, after) <= 0) {
                return "帮会检查游标没有前进（next_after_op_id=" + Long.toUnsignedString(next) + "）";
            }
            after = next;
        }
    }
}
