package com.game.scene.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线托管角色的跨节点搬迁、有界重试与在场心跳/归位元数据维护（对齐主仓
 * {@code docs/design/player-connection-lost-hold.md} §4.3、§4.4、§4.5）：
 * <ul>
 *   <li><b>源端搬迁与有界重试</b>：离线托管角色走托管落点（无 gate 会话、绝不推送任何踢线）；若能证明落点服务未放行
 *       （发送前失败、写前可重试拒绝、或过结算窗结果未知）且复核玩家仍在源处，按 {@code 5s / 20s} 最多重试 2 次；</li>
 *   <li><b>目标端落点有界重试</b>：目标节点收托管落点但实体建不起来（登记/载入瞬时故障）时，按 {@code 5s / 20s / 60s}
 *       最多重试 3 次，总预算 8 分钟；每次重试前先读权威落点核验 {@code (selfNodeId, sceneId, sessionId, loginGeneration, ownerEpoch)}
 *       仍完全一致，任一项变化即结清停止；</li>
 *   <li><b>托管在场心跳与归位信息</b>：托管/搬迁/看护中的角色每 20 秒续写 60 秒 TTL 在场心跳，并写对齐月卡到期的归位元数据。</li>
 * </ul>
 */
public final class HostedPlacementController {

    private static final Logger log = LoggerFactory.getLogger(HostedPlacementController.class);

    /** 源端证明未放行后的重试退避（秒）：5s、20s（至多 2 次重试）。 */
    public static final long[] SOURCE_RETRY_DELAYS_SECONDS = {5L, 20L};

    /** 目标端落点建人失败后的重试退避（秒）：5s、20s、60s（至多 3 次重试）。 */
    public static final long[] TARGET_RETRY_DELAYS_SECONDS = {5L, 20L, 60L};

    /** 目标端有界重试总预算：8 分钟。 */
    public static final Duration TARGET_RETRY_BUDGET = Duration.ofMinutes(8);

    public record PlacementRequest(
            long playerId,
            int targetNodeId,
            long targetSceneId,
            int targetSceneConfigId,
            long ownerEpoch,
            long loginGeneration,
            int lostSessionId,
            long ownerRevision,
            long minorPlayDeadlineMs,
            String account) {
    }

    public enum DispatchOutcome {
        /** 落点服务/目标节点已确认受理。 */
        ACCEPTED,
        /** 明确证明未写任何落点前的可重试拒绝或未发出，可在确认仍在原处后按 5s/20s 重试。 */
        RETRYABLE_PRE_WRITE,
        /** 超过结算窗口的结果未知，需先重读确认仍在原处后再按 5s/20s 重试。 */
        UNKNOWN_AFTER_WINDOW,
        /** 永久失败或已被接管，停止重试且绝不踢旧会话。 */
        PERMANENT_FAILED
    }

    public record AuthoritativePlacement(int nodeId, long sceneId, int sessionId, long loginGeneration, long ownerEpoch) {
        public boolean matches(int expectedNodeId, PlacementRequest req) {
            return nodeId == expectedNodeId
                    && sceneId == req.targetSceneId()
                    && sessionId == req.lostSessionId()
                    && loginGeneration == req.loginGeneration()
                    && ownerEpoch == req.ownerEpoch();
        }
    }

    @FunctionalInterface
    public interface Dispatcher {
        CompletionStage<DispatchOutcome> dispatch(PlacementRequest request);
    }

    @FunctionalInterface
    public interface AuthoritativePlacementReader {
        Optional<AuthoritativePlacement> read(long playerId);
    }

    @FunctionalInterface
    public interface TargetEntityCreator {
        boolean tryCreate(PlacementRequest request);
    }

    /** 托管在场心跳与归位信息写入端口（默认空实现）。 */
    public interface HomingSink {
        HomingSink NONE = new HomingSink() {
            @Override public void renewPresence(long playerId, String holderNode, long ttlSeconds) { }
            @Override public void writeHoming(long playerId, int zoneId, long ownerRevision,
                                              int lostSessionId, long loginGeneration, long expiresAtSec, long ttlSeconds) { }
            @Override public void clearPresence(long playerId) { }
        };

        void renewPresence(long playerId, String holderNode, long ttlSeconds);

        void writeHoming(long playerId, int zoneId, long ownerRevision,
                         int lostSessionId, long loginGeneration, long expiresAtSec, long ttlSeconds);

        void clearPresence(long playerId);
    }

    private static final class SourceRetryTask {
        final PlacementRequest request;
        final LongPredicate stillAtSource;
        int retriesDone;
        long nextRetryAtNanos;

        SourceRetryTask(PlacementRequest request, LongPredicate stillAtSource, long nextRetryAtNanos) {
            this.request = request;
            this.stillAtSource = stillAtSource;
            this.nextRetryAtNanos = nextRetryAtNanos;
        }
    }

    private static final class TargetRetryTask {
        final int selfNodeId;
        final PlacementRequest request;
        final long budgetDeadlineNanos;
        int retriesDone;
        long nextRetryAtNanos;

        TargetRetryTask(int selfNodeId, PlacementRequest request, long budgetDeadlineNanos, long nextRetryAtNanos) {
            this.selfNodeId = selfNodeId;
            this.request = request;
            this.budgetDeadlineNanos = budgetDeadlineNanos;
            this.nextRetryAtNanos = nextRetryAtNanos;
        }
    }

    private final Dispatcher dispatcher;
    private final AuthoritativePlacementReader placementReader;
    private final TargetEntityCreator targetEntityCreator;
    private final HomingSink homingSink;
    private final Map<Long, SourceRetryTask> sourceRetries = new HashMap<>();
    private final Map<Long, TargetRetryTask> targetRetries = new HashMap<>();

    public HostedPlacementController(Dispatcher dispatcher, AuthoritativePlacementReader placementReader,
                                     TargetEntityCreator targetEntityCreator, HomingSink homingSink) {
        this.dispatcher = dispatcher;
        this.placementReader = placementReader;
        this.targetEntityCreator = targetEntityCreator;
        this.homingSink = homingSink == null ? HomingSink.NONE : homingSink;
    }

    public static HostedPlacementController noop() {
        return new HostedPlacementController(null, null, null, HomingSink.NONE);
    }

    public HomingSink homingSink() {
        return homingSink;
    }

    public boolean hasDispatcher() {
        return dispatcher != null;
    }

    /** 源端发出一次托管落点；可重试失败自动登记 5s/20s 有界重试（从不推客户端踢线）。 */
    public CompletionStage<DispatchOutcome> sendFromSource(PlacementRequest request, LongPredicate stillAtSource, long nowNanos) {
        Objects.requireNonNull(request, "request");
        if (dispatcher == null) {
            return CompletableFuture.completedFuture(DispatchOutcome.PERMANENT_FAILED);
        }
        CompletionStage<DispatchOutcome> stage;
        try {
            stage = dispatcher.dispatch(request);
        } catch (RuntimeException ex) {
            log.warn("hosted placement dispatch threw player_id={}", Long.toUnsignedString(request.playerId()), ex);
            stage = CompletableFuture.completedFuture(DispatchOutcome.RETRYABLE_PRE_WRITE);
        }
        return stage.handle((outcome, err) -> {
            DispatchOutcome resolved = err != null || outcome == null ? DispatchOutcome.RETRYABLE_PRE_WRITE : outcome;
            if (resolved == DispatchOutcome.RETRYABLE_PRE_WRITE || resolved == DispatchOutcome.UNKNOWN_AFTER_WINDOW) {
                scheduleSourceRetry(request, stillAtSource, nowNanos);
            } else {
                sourceRetries.remove(request.playerId());
            }
            return resolved;
        });
    }

    private void scheduleSourceRetry(PlacementRequest request, LongPredicate stillAtSource, long nowNanos) {
        SourceRetryTask existing = sourceRetries.get(request.playerId());
        if (existing != null && existing.request.ownerEpoch() == request.ownerEpoch()) {
            if (existing.retriesDone >= SOURCE_RETRY_DELAYS_SECONDS.length) {
                sourceRetries.remove(request.playerId());
                log.warn("hosted placement source retry exhausted player_id={} epoch={}",
                        Long.toUnsignedString(request.playerId()), request.ownerEpoch());
                return;
            }
            long delaySec = SOURCE_RETRY_DELAYS_SECONDS[existing.retriesDone];
            existing.nextRetryAtNanos = nowNanos + TimeUnit.SECONDS.toNanos(delaySec);
            return;
        }
        long delaySec = SOURCE_RETRY_DELAYS_SECONDS[0];
        sourceRetries.put(request.playerId(),
                new SourceRetryTask(request, stillAtSource, nowNanos + TimeUnit.SECONDS.toNanos(delaySec)));
    }

    /** 目标端建人失败后登记 5s / 20s / 60s 有界重试（总预算 8 分钟）。 */
    public void scheduleTargetRetry(int selfNodeId, PlacementRequest request, long nowNanos) {
        Objects.requireNonNull(request, "request");
        long budgetDeadline = nowNanos + TARGET_RETRY_BUDGET.toNanos();
        long nextRetry = nowNanos + TimeUnit.SECONDS.toNanos(TARGET_RETRY_DELAYS_SECONDS[0]);
        targetRetries.put(request.playerId(), new TargetRetryTask(selfNodeId, request, budgetDeadline, nextRetry));
    }

    public void cancelTargetRetry(long playerId) {
        targetRetries.remove(playerId);
    }

    public int pendingSourceRetries() {
        return sourceRetries.size();
    }

    public int pendingTargetRetries() {
        return targetRetries.size();
    }

    /** 逻辑线程周期驱动：推进到期的源端重试与目标端重查重试，同时续写看护中角色的心跳。 */
    public int tick(long nowNanos, String holderNodeLabel) {
        int attempts = 0;
        List<SourceRetryTask> dueSource = new ArrayList<>();
        for (Iterator<SourceRetryTask> it = sourceRetries.values().iterator(); it.hasNext(); ) {
            SourceRetryTask task = it.next();
            if (nowNanos - task.nextRetryAtNanos < 0L) {
                continue;
            }
            if (task.stillAtSource != null && !task.stillAtSource.test(task.request.playerId())) {
                it.remove();
                continue;
            }
            if (task.retriesDone >= SOURCE_RETRY_DELAYS_SECONDS.length) {
                it.remove();
                continue;
            }
            task.retriesDone++;
            // 先占位下一次退避（若仍失败由回调覆盖或耗尽移除）
            if (task.retriesDone < SOURCE_RETRY_DELAYS_SECONDS.length) {
                task.nextRetryAtNanos = nowNanos + TimeUnit.SECONDS.toNanos(SOURCE_RETRY_DELAYS_SECONDS[task.retriesDone]);
            } else {
                task.nextRetryAtNanos = Long.MAX_VALUE;
            }
            dueSource.add(task);
        }
        for (SourceRetryTask task : dueSource) {
            attempts++;
            if (holderNodeLabel != null && !holderNodeLabel.isEmpty()) {
                homingSink.renewPresence(task.request.playerId(), holderNodeLabel,
                        ConnectionLostHold.HOSTED_PRESENCE_TTL_SECONDS);
            }
            sendFromSource(task.request, task.stillAtSource, nowNanos);
        }

        for (Iterator<TargetRetryTask> it = targetRetries.values().iterator(); it.hasNext(); ) {
            TargetRetryTask task = it.next();
            if (nowNanos - task.budgetDeadlineNanos >= 0L) {
                log.warn("hosted placement target retry budget exceeded player_id={} epoch={}",
                        Long.toUnsignedString(task.request.playerId()), task.request.ownerEpoch());
                it.remove();
                continue;
            }
            if (nowNanos - task.nextRetryAtNanos < 0L) {
                continue;
            }
            // 每次重试前必须先核对权威落点：仍是本节点、同场景、同登录身份、同归属代次才重试
            if (placementReader == null || targetEntityCreator == null) {
                it.remove();
                continue;
            }
            Optional<AuthoritativePlacement> auth = placementReader.read(task.request.playerId());
            if (auth.isEmpty() || !auth.get().matches(task.selfNodeId, task.request)) {
                log.info("hosted placement target retry stopped because authoritative placement changed player_id={}",
                        Long.toUnsignedString(task.request.playerId()));
                it.remove();
                continue;
            }
            attempts++;
            if (holderNodeLabel != null && !holderNodeLabel.isEmpty()) {
                homingSink.renewPresence(task.request.playerId(), holderNodeLabel,
                        ConnectionLostHold.HOSTED_PRESENCE_TTL_SECONDS);
            }
            boolean created = false;
            try {
                created = targetEntityCreator.tryCreate(task.request);
            } catch (RuntimeException ex) {
                log.warn("hosted placement target retry create failed player_id={}",
                        Long.toUnsignedString(task.request.playerId()), ex);
            }
            if (created) {
                it.remove();
                continue;
            }
            task.retriesDone++;
            if (task.retriesDone >= TARGET_RETRY_DELAYS_SECONDS.length) {
                log.warn("hosted placement target retry exhausted player_id={} epoch={}",
                        Long.toUnsignedString(task.request.playerId()), task.request.ownerEpoch());
                it.remove();
                continue;
            }
            long delaySec = TARGET_RETRY_DELAYS_SECONDS[task.retriesDone];
            long nextAt = nowNanos + TimeUnit.SECONDS.toNanos(delaySec);
            if (nextAt - task.budgetDeadlineNanos >= 0L) {
                it.remove();
            } else {
                task.nextRetryAtNanos = nextAt;
            }
        }
        return attempts;
    }
}
