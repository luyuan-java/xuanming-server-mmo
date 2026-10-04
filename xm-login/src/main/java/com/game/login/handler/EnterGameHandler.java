package com.game.login.handler;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.EnterScene;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.discovery.proto.PlayerLocation;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.dispatch.Tips;
import com.game.login.metrics.LoginMetrics;
import com.game.login.metrics.LoginMetrics.AssignResult;
import com.game.login.metrics.LoginMetrics.ClaimOutcome;
import com.game.login.ownership.OwnerTakeovers;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EnterGame（26）：校验角色归属 → 向 scene-manager 要落点 → 夺取玩家数据归属（owner_epoch 自增）→
 * 回 {@code EnterGameResponse{player_id}}，并让 gate 把会话绑定到玩家、送进 (场景节点, 场景)。
 *
 * <p>客户端可见契约（mmorpg 登录契约 §6）：
 * <ul>
 *   <li>无会话 → 2018；会话未登录，或会话上已绑定玩家（不论是不是同一个角色）→ 2028。
 *       与基线一致：基线进游戏成功即删除登录会话，之后的 EnterGame 一律 2028。会话回到「已登录、未进游戏」有两条路：
 *       LeaveGame（gate 解绑玩家），或进场景异步失败（gate 收到 3023 时同样解绑玩家）——所以失败后可在同一连接上重试。
 *       <b>不放行「同一角色再次 EnterGame」</b>：那会在旧实例的最终写回落库之前夺权，旧写回被围栏拒掉、在线进度全部丢失；</li>
 *   <li>同一角色的进场在途 → 2005；角色不存在或不属于本账号 → 2011；</li>
 *   <li>落点：有玩家位置记录（在线顶号 / 断线 30 s 重连租约内）送回原场景实例，没有（首登 / LeaveGame 后 / 租约过期）落默认主世界；</li>
 *   <li>scene-manager 回 tip → 原样放进 {@code error_message}；调用失败 / 超时 → 3023 kEnterSceneFailed；</li>
 *   <li>归属仍被上一个写者持有（旧实例还没写回释放、租约也没过期）：请持有者让出（{@link OwnerTakeovers}，
 *       旧会话被踢下线），在 {@code claimWait} 内退避重试夺权；等不到 → 2005 kLoginInProgress，客户端稍后重试；</li>
 *   <li>成功：{@code player_id} = 请求值，不设置 {@code error_message}，合服相关两个字段保持默认（0 / false）。</li>
 * </ul>
 *
 * <p>线程：归属校验、夺权在工作线程上同步查库；等 scene-manager 与退避重试都是异步组合，不阻塞任何线程。
 * 在途闸门一直持有到整条异步链结束。
 *
 * <p>数据一致性：owner_epoch 在分配到场景之后才夺取，分配失败不产生夺权；夺权只在上一个写者已释放（最终写回已落库）
 * 或其租约已过期时成功（{@link PlayerStore#claimOwnership}），新写者加载到的一定是上一个写者写回后的状态。
 *
 * <p>指标（{@link LoginMetrics}）：场景分配调用的结果与耗时；夺权这一步的结局（第一次夺到 / 等持有者让出后夺到 / 等不到 /
 * 角色已不存在 / 故障）与耗时；每次请持有者让出计一次。耗时都用注入的单调时钟。
 */
public final class EnterGameHandler implements ClientMessageHandler<EnterGameRequest> {

    private static final Logger log = LoggerFactory.getLogger(EnterGameHandler.class);

    /** 夺权撞上仍被持有的归属后，第一次重试前等多久；之后每次翻倍，封顶 {@link #MAX_CLAIM_BACKOFF}。 */
    static final Duration FIRST_CLAIM_BACKOFF = Duration.ofMillis(100);
    static final Duration MAX_CLAIM_BACKOFF = Duration.ofMillis(800);

    private final PlayerStore store;
    private final SceneDirectoryService sceneDirectory;
    private final OwnerTakeovers takeovers;
    private final Executor executor;
    private final Function<Duration, Executor> timer;
    private final LongSupplier nanoClock;
    private final int defaultZoneId;
    private final Duration assignTimeout;
    private final Duration claimWait;
    private final LoginMetrics metrics;
    private final Function<SessionContext, Integer> deviceRenewal;
    private final PlayerLocationLookup locations;
    private final InFlightKeys<Long> playersInFlight = new InFlightKeys<>();

    /**
     * @param takeovers     归属仍被持有时请持有者让出
     * @param executor      login 工作线程池（查库、夺权在上面执行）
     * @param defaultZoneId 会话没带 zone 时用的 zone（本 login 的 zone）
     * @param assignTimeout 等 scene-manager 结果的兜底上限；超时按调用失败处理，保证在途闸门一定释放
     * @param claimWait     归属仍被持有时最多等多久（期间退避重试夺权），之后回 2005
     * @param metrics       场景分配与夺权的指标
     */
    public EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                            Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                            LoginMetrics metrics) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics,
                session -> null);
    }

    /**
     * @param deviceRenewal 进游戏前续期会话的设备数登记（{@code AccountLogin::renewDevice}）：回拒绝码（2024 / 2023）即拒绝，null 放行
     */
    public EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                            Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                            LoginMetrics metrics, Function<SessionContext, Integer> deviceRenewal) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics,
                deviceRenewal, PlayerLocationLookup.NONE);
    }

    /**
     * @param locations 玩家位置记录（在线 / 断线重连租约内）：有就送回原场景实例，没有按首登落默认主世界
     */
    public EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                            Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                            LoginMetrics metrics, Function<SessionContext, Integer> deviceRenewal,
                            PlayerLocationLookup locations) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics,
                delay -> CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS),
                System::nanoTime, deviceRenewal, locations);
    }

    /**
     * 同上；退避计时与单调时钟可注入（测试用）。
     *
     * @param timer     给定延迟后执行一个很轻的任务（只负责把重试交回工作线程池，不在上面做阻塞 I/O）
     * @param nanoClock 单调时钟（纳秒），用于等待截止时间与指标耗时
     */
    EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                     Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                     LoginMetrics metrics, Function<Duration, Executor> timer, LongSupplier nanoClock) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics, timer,
                nanoClock, session -> null, PlayerLocationLookup.NONE);
    }

    EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                     Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                     LoginMetrics metrics, Function<Duration, Executor> timer, LongSupplier nanoClock,
                     Function<SessionContext, Integer> deviceRenewal, PlayerLocationLookup locations) {
        this.locations = locations;
        this.deviceRenewal = deviceRenewal;
        this.store = store;
        this.sceneDirectory = sceneDirectory;
        this.takeovers = takeovers;
        this.executor = executor;
        this.defaultZoneId = defaultZoneId;
        this.assignTimeout = assignTimeout;
        this.claimWait = claimWait;
        this.metrics = metrics;
        this.timer = timer;
        this.nanoClock = nanoClock;
    }

    @Override
    public String methodName() {
        return "EnterGame";
    }

    @Override
    public Class<EnterGameRequest> requestType() {
        return EnterGameRequest.class;
    }

    @Override
    public Class<EnterGameResponse> responseType() {
        return EnterGameResponse.class;
    }

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, EnterGameRequest request) {
        if (session.getSessionId() == 0) {
            return done(error(LoginErrorTip.login_error.kLoginSessionIdNotFound_VALUE));
        }
        String account = session.getAccount();
        long playerId = request.getPlayerId();
        if (account.isEmpty() || session.getPlayerId() != 0) {
            return done(error(LoginErrorTip.login_error.kLoginSessionNotFound_VALUE));
        }
        Integer refused = deviceRenewal.apply(session);
        if (refused != null) {
            return done(error(refused));
        }
        if (!playersInFlight.tryAcquire(playerId)) {
            log.info("同一角色进场在途，拒绝 player={} session={}", playerId, session.getSessionId());
            return done(error(LoginErrorTip.login_error.kLoginInProgress_VALUE));
        }
        CompletableFuture<HandlerReply> result;
        try {
            result = enter(session, account, playerId);
        } catch (RuntimeException e) {
            playersInFlight.release(playerId);
            throw e;
        }
        return result.whenComplete((reply, error) -> playersInFlight.release(playerId));
    }

    private CompletableFuture<HandlerReply> enter(SessionContext session, String account, long playerId) {
        Optional<PlayerRow> row = store.findPlayer(playerId);
        if (row.isEmpty() || !account.equals(row.get().getAccount())) {
            log.info("进游戏拒绝：角色不属于本账号 account={} player={} 存在={}", account, playerId, row.isPresent());
            return done(error(LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE));
        }
        int zoneId = session.getZoneId() != 0 ? session.getZoneId() : defaultZoneId;
        AssignSceneRequest.Builder assign = AssignSceneRequest.newBuilder().setZoneId(zoneId).setPlayerId(playerId);
        applyLocation(assign, playerId, zoneId, row.get());
        AssignSceneRequest assignRequest = assign.build();
        long assignStart = nanoClock.getAsLong();
        return callAssign(assignRequest)
                .handleAsync((assigned, failure) -> afterAssign(session, playerId, assignRequest, assigned, failure, assignStart),
                        executor)
                .thenCompose(Function.identity());
    }

    /**
     * 落点（对应基线 resolveEnterSceneRoute；基线在 zone 内只按 location 定 zone、落默认主世界，Java 回原实例，见 PARITY）：有本 zone 的位置记录（在线顶号、断线 30 s 重连租约内）
     * 就请 scene-manager 送回原场景实例（不在了按原地图选）；没有（首登、LeaveGame 干净登出、租约过期）不指定，落默认主世界。
     * 读记录出错时退化成按存档里上次所在的地图选（不让 Redis 抖动把老玩家送回主城出生点）。
     */
    private void applyLocation(AssignSceneRequest.Builder assign, long playerId, int zoneId, PlayerRow row) {
        Optional<PlayerLocation> location;
        try {
            location = locations.find(playerId);
        } catch (RuntimeException e) {
            log.warn("读玩家位置失败，按存档里的地图选场景 player={}: {}", playerId, e.toString());
            assign.setPreferredSceneConfigId(row.getSceneConfigId());
            return;
        }
        if (location.isEmpty()) {
            return;
        }
        PlayerLocation at = location.get();
        if (at.getZoneId() != zoneId) {
            log.info("玩家位置在别的 zone，按首登落点 player={} 位置zone={} 本次zone={}", playerId, at.getZoneId(), zoneId);
            return;
        }
        assign.setPreferredSceneConfigId(at.getSceneConfigId())
                .setPreferredSceneNodeId(at.getSceneNodeId())
                .setPreferredSceneId(at.getSceneId());
    }

    /** 把同步抛出、返回 null、超时都统一成一个一定会完成的 future（copy 出来再加超时，不改动 Dubbo 的 future）。 */
    private CompletableFuture<AssignSceneResponse> callAssign(AssignSceneRequest request) {
        CompletableFuture<AssignSceneResponse> future;
        try {
            future = sceneDirectory.assign(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (future == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("scene-manager 返回了 null future"));
        }
        return future.copy().orTimeout(assignTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<HandlerReply> afterAssign(SessionContext session, long playerId, AssignSceneRequest routed,
                                                        AssignSceneResponse assigned, Throwable failure, long assignStart) {
        long assignElapsed = nanoClock.getAsLong() - assignStart;
        if (failure != null) {
            metrics.sceneAssignCompleted(AssignResult.ERROR, assignElapsed);
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            log.warn("进游戏失败：场景分配调用失败 player={} session={}: {}", playerId, session.getSessionId(), cause.toString());
            return done(error(SceneErrorTip.scene_error.kEnterSceneFailed_VALUE));
        }
        if (assigned.getTipId() != 0) {
            metrics.sceneAssignCompleted(AssignResult.REJECTED, assignElapsed);
            log.info("进游戏失败：场景分配拒绝 player={} tip={}", playerId, assigned.getTipId());
            return done(error(assigned.getTipId()));
        }
        metrics.sceneAssignCompleted(AssignResult.OK, assignElapsed);

        long claimStart = nanoClock.getAsLong();
        long deadline = claimStart + claimWait.toNanos();
        CompletableFuture<ClaimStep> chain;
        try {
            chain = claim(session, playerId, routed, assigned, deadline, FIRST_CLAIM_BACKOFF, false);
        } catch (RuntimeException e) {
            metrics.ownerClaimCompleted(ClaimOutcome.ERROR, nanoClock.getAsLong() - claimStart);
            throw e;
        }
        // 结局只在这里记一次：正常结束取步骤给出的结局，异常结束（夺权故障、退避时工作队列满）记 error，异常原样向上传。
        return chain
                .whenComplete((step, error) -> metrics.ownerClaimCompleted(
                        error != null ? ClaimOutcome.ERROR : step.outcome(), nanoClock.getAsLong() - claimStart))
                .thenApply(ClaimStep::reply);
    }

    /**
     * 在工作线程上夺权一次；仍被持有就请持有者让出，退避后在工作线程上再试，直到截止时间。
     *
     * @param retried 之前已经撞上过「仍被持有」（夺到时记为 {@link ClaimOutcome#WAITED}）
     */
    private CompletableFuture<ClaimStep> claim(SessionContext session, long playerId, AssignSceneRequest routed,
                                               AssignSceneResponse assigned, long deadline, Duration backoff,
                                               boolean retried) {
        ClaimResult result = store.claimOwnership(playerId);
        if (result instanceof ClaimResult.Claimed claimed) {
            ClaimOutcome outcome = retried ? ClaimOutcome.WAITED : ClaimOutcome.CLAIMED;
            return rerouteIfStale(playerId, routed, assigned).thenApply(target -> new ClaimStep(
                    accepted(session, playerId, target, claimed.ownerEpoch()), outcome));
        }
        if (result instanceof ClaimResult.NotFound) {
            log.warn("进游戏失败：夺权时角色已不存在 player={}", playerId);
            return CompletableFuture.completedFuture(new ClaimStep(
                    error(LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE), ClaimOutcome.NOT_FOUND));
        }
        long heldEpoch = ((ClaimResult.Held) result).ownerEpoch();
        takeovers.request(playerId, heldEpoch);
        metrics.takeoverRequested();
        long remainingNanos = deadline - nanoClock.getAsLong();
        if (remainingNanos <= 0) {
            log.warn("进游戏失败：玩家数据归属仍被持有，{} 内没等到释放 player={} session={} held_epoch={}",
                    claimWait, playerId, session.getSessionId(), heldEpoch);
            return CompletableFuture.completedFuture(new ClaimStep(
                    error(LoginErrorTip.login_error.kLoginInProgress_VALUE), ClaimOutcome.TIMEOUT));
        }
        log.info("玩家数据归属仍被持有，已请持有者让出，{}ms 后重试夺权 player={} session={} held_epoch={}",
                Math.min(backoff.toMillis(), TimeUnit.NANOSECONDS.toMillis(remainingNanos)), playerId,
                session.getSessionId(), heldEpoch);
        Duration delay = backoff.compareTo(Duration.ofNanos(remainingNanos)) < 0 ? backoff : Duration.ofNanos(remainingNanos);
        Duration nextBackoff = backoff.multipliedBy(2).compareTo(MAX_CLAIM_BACKOFF) < 0
                ? backoff.multipliedBy(2) : MAX_CLAIM_BACKOFF;
        return onWorkerAfter(delay).thenCompose(ignored -> claim(session, playerId, routed, assigned, deadline,
                nextBackoff, true));
    }

    /**
     * 夺到归属后再看一眼位置记录：路由时用的记录在夺权期间被上一个持有者改掉了（LeaveGame 之后紧跟着 EnterGame：
     * 读记录时 scene 还没处理离场，登出墓碑在夺权等释放的这段时间才落下）就按新的记录重新分配一次——同基线「LeaveGame 之后按首登」。
     * 只在路由用了记录时才看；读出错或重新分配失败都沿用第一次的结果（只影响落点，不影响归属）。
     */
    private CompletableFuture<AssignSceneResponse> rerouteIfStale(long playerId, AssignSceneRequest routed,
                                                                  AssignSceneResponse assigned) {
        if (routed.getPreferredSceneId() == 0) {
            return CompletableFuture.completedFuture(assigned);
        }
        Optional<PlayerLocation> now;
        try {
            now = locations.find(playerId);
        } catch (RuntimeException e) {
            log.warn("夺权后复查玩家位置失败，沿用原落点 player={}: {}", playerId, e.toString());
            return CompletableFuture.completedFuture(assigned);
        }
        Optional<PlayerLocation> usable = now.filter(at -> at.getZoneId() == routed.getZoneId());
        if (usable.isPresent() && usable.get().getSceneNodeId() == routed.getPreferredSceneNodeId()
                && usable.get().getSceneId() == routed.getPreferredSceneId()) {
            return CompletableFuture.completedFuture(assigned);
        }
        AssignSceneRequest.Builder again = AssignSceneRequest.newBuilder().setZoneId(routed.getZoneId()).setPlayerId(playerId);
        usable.ifPresent(at -> again.setPreferredSceneConfigId(at.getSceneConfigId())
                .setPreferredSceneNodeId(at.getSceneNodeId())
                .setPreferredSceneId(at.getSceneId()));
        log.info("夺权期间玩家位置变了（如 LeaveGame 的登出墓碑），重新分配场景 player={} 原落点={}/{} 现记录={}", playerId,
                routed.getPreferredSceneNodeId(), routed.getPreferredSceneId(), usable.isPresent() ? "有" : "无");
        long start = nanoClock.getAsLong();
        return callAssign(again.build()).handle((target, failure) -> {
            long elapsed = nanoClock.getAsLong() - start;
            if (failure != null || target.getTipId() != 0) {
                metrics.sceneAssignCompleted(failure != null ? AssignResult.ERROR : AssignResult.REJECTED, elapsed);
                log.warn("重新分配场景失败，沿用原落点 player={}", playerId);
                return assigned;
            }
            metrics.sceneAssignCompleted(AssignResult.OK, elapsed);
            return target;
        });
    }

    /**
     * 延迟后在工作线程上完成的 future。工作队列满（拒绝）时以异常完成——链一定会结束，在途闸门一定会释放
     * （不能直接用 {@code delayedExecutor(…, executor)}：到点后投递被拒只会被吞掉，future 永远不完成）。
     */
    private CompletableFuture<Void> onWorkerAfter(Duration delay) {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        try {
            timer.apply(delay).execute(() -> {
                try {
                    executor.execute(() -> ready.complete(null));
                } catch (RejectedExecutionException e) {
                    ready.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            ready.completeExceptionally(e);
        }
        return ready;
    }

    private static HandlerReply accepted(SessionContext session, long playerId, AssignSceneResponse assigned, long epoch) {
        log.info("进游戏受理 player={} gate={} session={} scene_node={} scene={} scene_config={} owner_epoch={}",
                playerId, session.getGateNodeId(), session.getSessionId(), assigned.getSceneNodeId(),
                assigned.getSceneId(), assigned.getSceneConfigId(), epoch);
        EnterGameResponse response = EnterGameResponse.newBuilder().setPlayerId(playerId).build();
        SessionDirective directive = SessionDirective.newBuilder()
                .setEnterScene(EnterScene.newBuilder()
                        .setPlayerId(playerId)
                        .setSceneNodeId(assigned.getSceneNodeId())
                        .setSceneId(assigned.getSceneId())
                        .setOwnerEpoch(epoch))
                .build();
        return HandlerReply.of(response, directive);
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.of(EnterGameResponse.newBuilder().setErrorMessage(tip).build());
    }

    private static HandlerReply error(int tipId) {
        return HandlerReply.of(EnterGameResponse.newBuilder().setErrorMessage(Tips.of(tipId)).build());
    }

    private static CompletableFuture<HandlerReply> done(HandlerReply reply) {
        return CompletableFuture.completedFuture(reply);
    }

    /** 夺权这一步的结果：给客户端的应答 + 指标用的结局。 */
    private record ClaimStep(HandlerReply reply, ClaimOutcome outcome) {
    }
}
