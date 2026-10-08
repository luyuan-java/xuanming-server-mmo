package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.IndexEviction;
import com.game.match.metrics.MatchMetrics.ListResult;
import com.game.match.metrics.MatchMetrics.WatchAnomaly;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.spectate.SpectateStore.Record;
import com.game.match.spectate.SpectateStore.Scored;
import com.game.proto.match.ListWatchableBattlesResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 164 ListWatchableBattles 的流程（spectate-spec §3.3、§4.5；基线 {@code listwatchablebattleslogic.go:34-81}）：可观战索引里最新开局的至多
 * {@code limit} 场，逐条读落点记录组成摘要。只读接口：<b>不看身份、不做互斥</b>（BW4：排队中、战斗中的玩家也能浏览，互斥在 163 时才强制）。
 *
 * <p>一次请求的三步，至多三次 Redis 往返（其中第三次不等结果）：
 * <ol>
 *   <li>{@link SpectateStore#list}：取分数（{@code created_at_ms}）最高的 {@code limit} 个成员与 Redis 时间。<b>失败 → 信封 1003</b>
 *       （{@link Result.IndexUnavailable}）：空列表在客户端等同于「当前没有可观战的战斗」，不能拿它冒充故障。</li>
 *   <li>逐条判定（次序即基线的次序）：成员不是合法的 battle_id → 剔除、跳过；分数早于过期分界 → 剔除（连同落点）、跳过；
 *       其余的一次批读落点（{@link SpectateStore#readPlacements}）：记录不在 → 剔除、跳过；记录损坏 → <b>跳过、不剔除</b>、计
 *       {@code anomalies{corrupt_record}}（W15：对一条在场的落点做删除会毁掉 179 的定位）；好记录 → 原样映射成摘要。
 *       <b>批读整批失败</b> → 这一批每条都按「读记录出错」处理（跳过、不剔除），回变短的列表、<b>不回 1003</b>，计
 *       {@code anomalies{record_read_failed}}——对齐的是基线逐条 GET 出错时客户端看到的结果。</li>
 *   <li>应答组好<b>之后</b>把要剔除的成员一批交给 {@link SpectateStore#evictAsync}（发出即返回）：剔除不阻塞应答，它的结果也不影响本次列表。
 *       每条剔除的条件都由存储在脚本里原子复核（{@link Eviction} 的三种模式），判定之后世界变了也不会删错——
 *       尤其「记录不在」只摘成员、<b>永不删落点</b>：批读之后才预写的记录（一场正在建房的战斗）不能动。</li>
 * </ol>
 * <b>不回填</b>（BW5）：剔除 / 跳过之后列表可以短于 {@code limit}，下一次请求自然补齐。列表里会有已结束不足 360 s 的战斗（BW7）、
 * 满员的房间、请求者自己参战的局，同基线。次序 = 索引的次序（分数降序、同分按成员字符串降序），两版都在 Redis 里排。
 *
 * <p>指标：{@code xm_match_list_watchable_total{result}} 每个请求恰好一次（{@code ok} 含空列表与批读失败后变短的列表；{@code error} =
 * 读索引失败，以及流程里未预期的异常；过载由处理器记）。剔除的成员数按<b>发出</b>计进 {@code xm_match_watchable_index_evictions_total}
 * （异步，等不到结果）。
 *
 * <p>线程：{@link #list} 阻塞等 Redis（至多等到请求截止），在 {@code match-worker} 上调。无状态，线程安全。
 */
public final class WatchableListService {

    private static final Logger log = LoggerFactory.getLogger(WatchableListService.class);

    /** 一次 164 的结局（二选一）。 */
    public sealed interface Result {

        /** 回列表（可以为空：这时应答体是 0 字节，gate 照常回包，客户端靠它把面板从「刷新中」收回）。 */
        record Listed(ListWatchableBattlesResponse response) implements Result {

            public Listed {
                Objects.requireNonNull(response, "response");
            }
        }

        /** 读索引失败：164 没有 in-band 错误字段，回信封 1003。 */
        record IndexUnavailable() implements Result {
        }
    }

    private final SpectateStore store;
    private final MatchMetrics metrics;

    public WatchableListService(SpectateStore store, MatchMetrics metrics) {
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * @param requestedLimit 请求里的 {@code limit}（uint32 的位模式；0 → 20，大于 50 → 50，见 {@link SpectateRules#clampLimit}）
     * @param deadline       本次请求的截止：读索引与批读落点共用
     */
    public Result list(int requestedLimit, Deadline deadline) {
        try {
            return listOnce(requestedLimit, deadline);
        } catch (RuntimeException e) { // 存储违反了「只抛依赖异常」的约定，或本类有 bug：照样只记一个出口，再交给派发器回信封 1003
            metrics.listWatchable(ListResult.ERROR);
            throw e;
        }
    }

    private Result listOnce(int requestedLimit, Deadline deadline) {
        int limit = SpectateRules.clampLimit(requestedLimit);
        SpectateStore.Listed page;
        try {
            page = store.list(limit, deadline);
        } catch (Deadline.DependencyException e) {
            log.error("[spectate] 读可观战索引失败（164 回信封 1003） limit={}: {}", limit, e.toString());
            metrics.listWatchable(ListResult.ERROR);
            return new Result.IndexUnavailable();
        }

        // 第一遍：只看成员与分数就能定的两种（非法成员、过期），其余的留给批读
        long nowMs = page.redisNowMs();
        long cutoffMs = SpectateRules.staleCutoff(nowMs);
        List<Eviction> evictions = new ArrayList<>();
        List<Long> candidates = new ArrayList<>(page.members().size());
        int invalid = 0;
        int stale = 0;
        for (Scored member : page.members()) {
            OptionalLong battleId = SpectateRules.parseMember(member.member());
            if (battleId.isEmpty()) {
                log.warn("[spectate] 可观战索引里有非法成员 '{}'，剔除", member.member());
                evictions.add(new Eviction.Invalid(member.member()));
                invalid++;
            } else if (SpectateRules.stale(member.score(), nowMs)) {
                evictions.add(new Eviction.Stale(battleId.getAsLong(), cutoffMs));
                stale++;
            } else {
                candidates.add(battleId.getAsLong());
            }
        }

        // 第二遍：批读落点，按索引的次序组摘要
        Map<Long, Record> records = readRecords(candidates, deadline);
        ListWatchableBattlesResponse.Builder response = ListWatchableBattlesResponse.newBuilder();
        int missing = 0;
        if (records != null) {
            for (long battleId : candidates) {
                switch (records.get(battleId)) {
                    case Record.Found found -> response.addBattles(SpectateRules.summaryOf(found.placement()));
                    case Record.Absent absent -> {
                        evictions.add(new Eviction.Missing(battleId));
                        missing++;
                    }
                    case Record.Corrupt corrupt -> {
                        log.warn("[spectate] 落点记录损坏，列表跳过这一场（不剔除） battle_id={}: {}", Long.toUnsignedString(battleId), corrupt.why());
                        metrics.watchableAnomaly(WatchAnomaly.CORRUPT_RECORD);
                    }
                    // 存储没按约定给出这一条：当作这一条读失败，跳过、不剔除
                    case null -> log.error("[spectate] 批读落点的结果里缺了 battle_id={}（按读失败跳过）", Long.toUnsignedString(battleId));
                }
            }
        }
        ListWatchableBattlesResponse built = response.build();

        // 应答已经组好：剔除发出即返回，不等结果
        if (!evictions.isEmpty()) {
            store.evictAsync(evictions);
            metrics.watchableIndexEvicted(IndexEviction.INVALID_MEMBER, invalid);
            metrics.watchableIndexEvicted(IndexEviction.STALE, stale);
            metrics.watchableIndexEvicted(IndexEviction.MISSING_RECORD, missing);
            log.debug("[spectate] 列表里懒剔除 {} 个成员（非法 {} / 过期 {} / 落点不在 {}）", evictions.size(), invalid, stale, missing);
        }
        metrics.listWatchable(ListResult.OK);
        return new Result.Listed(built);
    }

    /** 批读这一页候选的落点记录；整批失败回 null（调用方把每一条都当读失败：跳过、不剔除）。没有候选时不发命令。 */
    private Map<Long, Record> readRecords(List<Long> candidates, Deadline deadline) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        try {
            return Objects.requireNonNull(store.readPlacements(candidates, deadline), "readPlacements 返回了 null");
        } catch (Deadline.DependencyException e) {
            log.error("[spectate] 批读落点记录失败：这一页 {} 场都跳过（不剔除），照常回变短的列表: {}", candidates.size(), e.toString());
            metrics.watchableAnomaly(WatchAnomaly.RECORD_READ_FAILED);
            return null;
        }
    }
}
