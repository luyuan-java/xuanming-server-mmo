package com.game.guild.presence;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.proto.PlayerPresence;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 成员 / 申请人的在线状态（基线 OnlineStatusResolver.BatchResolve，online_status_resolver.go:62-99；guild-spec §4.6，D6）。
 *
 * <p>展示路径，<b>fail-open</b>：读失败或超时一律按全体离线，绝不让 RPC 失败。数据源是 gate 维护的 {@code xm:presence}
 * （{@code PlayerPresenceDirectory.findAllAsync}：一次往返；条目损坏或与键不符按离线）——条目表示「此刻在游戏里」、断线即删，
 * 语义等同基线的 {@code SESSION_STATE_ONLINE}。
 *
 * <p>与基线的差异（D6）：等待有<b>独立上限</b> {@code min(xm.guild.online-lookup-timeout, 剩余预算)}（缺省 800 ms）；基线 MGET 没有独立超时，
 * locator Redis 卡住时会吃光整请求预算（§9.1 第 11 条）。每次批量读计一次 {@code xm_guild_online_lookups_total{outcome}}。
 *
 * <p>只在工作线程上调用（阻塞等待异步结果）。线程安全。
 */
public final class OnlineStatuses {

    private static final Logger log = LoggerFactory.getLogger(OnlineStatuses.class);

    /** 一次批量读的结局（标签 {@code outcome}）。 */
    public enum Outcome {
        OK("ok"),
        /** 超过独立上限（或请求预算），按全体离线。 */
        TIMEOUT("timeout"),
        /** 在线目录读失败，按全体离线。 */
        ERROR("error");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 指标出口（{@code GuildMetrics} 实现；必须便宜、不抛）。 */
    @FunctionalInterface
    public interface Metrics {
        void onlineLookup(Outcome outcome);

        Metrics NONE = outcome -> {
        };
    }

    private final Function<Collection<Long>, CompletionStage<Map<Long, PlayerPresence>>> directory;
    private final long capMillis;
    private final Metrics metrics;

    /**
     * @param directory 宽松批量读（生产 {@code PlayerPresenceDirectory::findAllAsync}：只含在线的玩家，异步、不阻塞调用线程）
     * @param cap       独立等待上限（{@code xm.guild.online-lookup-timeout}）
     */
    public OnlineStatuses(Function<Collection<Long>, CompletionStage<Map<Long, PlayerPresence>>> directory, Duration cap,
                          Metrics metrics) {
        this.directory = directory;
        this.capMillis = cap.toMillis();
        this.metrics = metrics;
    }

    /**
     * 这些玩家里此刻在线的那些（去重、丢 0；空入参不碰 Redis）。永不抛。
     */
    public Set<Long> onlineOf(Collection<Long> playerIds, Deadline deadline) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : playerIds) {
            if (id != null && id != 0) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Long> list = List.copyOf(ids);
        CompletionStage<Map<Long, PlayerPresence>> stage;
        try {
            stage = directory.apply(list);
        } catch (RuntimeException e) {
            stage = CompletableFuture.failedFuture(e);
        }
        Deadline bounded = Deadline.after(Math.min(capMillis, deadline.remainingMillis()));
        try {
            Map<Long, PlayerPresence> online = bounded.await(stage, "批量读在线状态");
            metrics.onlineLookup(Outcome.OK);
            return online == null ? Set.of() : Set.copyOf(online.keySet());
        } catch (DependencyException e) {
            boolean timeout = e.getCause() instanceof TimeoutException;
            metrics.onlineLookup(timeout ? Outcome.TIMEOUT : Outcome.ERROR);
            log.warn("[guild] 读在线状态{}，{} 人按离线显示: {}", timeout ? "超时" : "失败", list.size(),
                    e.getCause() == null ? e.getMessage() : e.getCause().toString());
            return Set.of();
        }
    }
}
