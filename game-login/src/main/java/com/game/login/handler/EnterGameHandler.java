package com.game.login.handler;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.EnterScene;
import com.game.api.proto.RedirectToGate;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ZoneRedirect;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.dispatch.Tips;
import com.game.login.metrics.LoginMetrics;
import com.game.login.metrics.LoginMetrics.AssignResult;
import com.game.login.metrics.LoginMetrics.ClaimOutcome;
import com.game.login.metrics.LoginMetrics.EnterRoute;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EnterGame（26）：校验持票者与角色归属 → 按位置记录与票据定去向（{@link EnterRoutes}）→ 落地：向 scene-manager 要落点 →
 * 夺取玩家数据归属（owner_epoch 自增）→ 回 {@code EnterGameResponse{player_id}}，并让 gate 把会话绑定到玩家、送进 (场景节点, 场景)；
 * 或者（角色此刻在别的 zone）不落地，请 gate 把连接重定向过去。
 *
 * <p>客户端可见契约（mmorpg 登录契约 §6；批次 5.4 的顺序见 zone-travel-spec §5.8）：
 * <ol>
 *   <li>无会话 → 2018；</li>
 *   <li><b>持票者不符 → 2011</b>：这条连接握手时出示的重定向票据（{@code SessionContext.ticket_player_id}，普通令牌为 0）属于别的角色。
 *       同基线排在取账号（2028）之前（entergamelogic.go 的持票者校验早于会话检查）：不续设备、不占在途闸门、不读位置、不分配；</li>
 *   <li>会话未登录，或会话上已绑定玩家（不论是不是同一个角色）→ 2028。
 *       与基线一致：基线进游戏成功即删除登录会话，之后的 EnterGame 一律 2028。会话回到「已登录、未进游戏」有两条路：
 *       LeaveGame（gate 解绑玩家），或进场景异步失败（gate 收到 3023 时同样解绑玩家）——所以失败后可在同一连接上重试。
 *       <b>不放行「同一角色再次 EnterGame」</b>：那会在旧实例的最终写回落库之前夺权，旧写回被围栏拒掉、在线进度全部丢失；</li>
 *   <li>设备数续期被拒 → 拒绝码；同一角色的进场在途 → 2005；角色不存在或不属于本账号 → 2011；</li>
 *   <li>去向（{@link EnterRoutes} 的去向表）：本 zone 有位置记录（在线顶号 / 断线 30 s 重连租约内）送回原场景实例，
 *       本 zone 的待落点（跨 zone 传送的第二条腿）按记录的地图落，没有记录（首登 / LeaveGame 后 / 租约过期）落默认主世界；
 *       <b>记录在别的 zone 的节点上、这条连接又没持票钉在本 zone（GO-5）</b>：请 scene-manager 在那个 zone 签重定向票据，
 *       回 {@code EnterGameResponse{player_id}}（不设 {@code error_message}）+ {@code RedirectToGate} 指令，gate 先回应答、再推 124——
 *       不夺权、不分配、不查合服围栏；签票调用失败 / 超时 / 拒绝 / 应答残缺 → 退化为本 zone 首登（X11）；</li>
 *   <li>落地路径：合服围栏检查点 F2（判角色的归属区 {@code player.zone_id}；命中或读不到 → 3023）→ 分配 → 夺权；</li>
 *   <li>scene-manager 回 tip → 原样放进 {@code error_message}；调用失败 / 超时（含时间预算已用完）→ 3023 kEnterSceneFailed；</li>
 *   <li>归属仍被上一个写者持有（旧实例还没写回释放、租约也没过期）：请持有者让出（{@link OwnerTakeovers}，
 *       旧会话被踢下线），在 {@code claimWait} 内（截止不晚于时间预算）退避重试夺权；等不到，或分配回来时预算已用完
 *       → 2005 kLoginInProgress，客户端稍后重试。
 *       跨 zone 传送的第二条腿上源节点已「交出并释放」（{@code owner_released = 1}），夺权一次即成；</li>
 *   <li>成功：{@code player_id} = 请求值，不设置 {@code error_message}，合服相关两个字段保持默认（0 / false）。</li>
 * </ol>
 *
 * <p>线程：持票者 / 归属校验、读位置、合服围栏、夺权在工作线程上同步执行；等 scene-manager（分配、签票）与退避重试都是异步组合，
 * 完成后回到工作线程，不阻塞任何线程。在途闸门一直持有到整条异步链结束（含登录期重定向的签票）。
 *
 * <p>时间预算（{@code enterBudget}，从分发器受理这条请求开始算，含 login 工作队列排队，见
 * {@link ClientMessageHandler#handle(SessionContext, Message, long)}）：gate 调 login 有自己的 Dubbo 超时（game-gate
 * {@code dubbo.consumer.timeout}，5 s），到点就向客户端推 23 {1003}，迟到的应答被 Dubbo 消费端直接丢弃、到不了 gate——
 * 这时夺到的归属没有任何节点持有，也没人代为释放，玩家要等租约（{@link PlayerStore#OWNER_LEASE}）过期才能再进。
 * 所以整条链按剩余预算收紧：等 scene-manager（签票、分配、夺权后的重新分配）的本地兜底取 {@code assignTimeout} 与剩余预算中较小的，
 * 预算用完就不再发起调用（按调用超时处理）；夺权的等待截止取 {@code claimWait} 与预算截止中较早的，分配回来时预算已用完就不夺权（2005），
 * 退避醒来时预算已用完同样不再夺权，下一次重试只能排在预算截止上时不再空等、当场回 2005，
 * 夺权结果回来时预算已用完也不再请持有者让出（见 {@link #claim}）；夺权事务本身按剩余预算设时限
 * （{@link PlayerStore#claimOwnership(long, Duration)}），截止在事务里到点（含行锁等待、语句超时）就回滚、不夺权，回 2005；
 * 夺到之后预算已用完就不再复查位置（沿用原落点）。预算必须小于 gate 的超时，差值留给链路往返、最后一次夺权的 SQL 与回包
 * （排队已算在预算里）。登录期重定向没成时的回落（签票 + 分配 + 夺权）因此也不会超出 gate 的等待。
 *
 * <p>数据一致性：owner_epoch 在分配到场景之后才夺取，分配失败不产生夺权；夺权只在上一个写者已释放（最终写回已落库）
 * 或其租约已过期时成功（{@link PlayerStore#claimOwnership}），新写者加载到的一定是上一个写者写回后的状态。
 * 登录期重定向完全不碰归属：数据仍由别的 zone 的持有者持有，连接到了那边再走一次完整的 EnterGame。
 *
 * <p>指标（{@link LoginMetrics}）：去向（每次选去向的进游戏计一次）、持票者不符；场景分配与签票调用的结果与耗时；
 * 夺权这一步的结局（第一次夺到 / 等持有者让出后夺到 / 等不到 / 角色已不存在 / 故障）与耗时；每次请持有者让出计一次。
 * 耗时都用注入的单调时钟。
 */
public final class EnterGameHandler implements ClientMessageHandler<EnterGameRequest> {

    private static final Logger log = LoggerFactory.getLogger(EnterGameHandler.class);

    /** 夺权撞上仍被持有的归属后，第一次重试前等多久；之后每次翻倍，封顶 {@link #MAX_CLAIM_BACKOFF}。 */
    static final Duration FIRST_CLAIM_BACKOFF = Duration.ofMillis(100);
    static final Duration MAX_CLAIM_BACKOFF = Duration.ofMillis(800);

    /**
     * 一次进游戏的时间预算缺省值（{@code game.login.enter-game-budget} 的缺省，见类注释「时间预算」）：
     * 比 gate 调 login 的 Dubbo 超时（5 s）少 1 s。
     */
    public static final Duration DEFAULT_ENTER_BUDGET = Duration.ofSeconds(4);

    /** 端口合法范围的上界（uint32 线类型，Java 里按无符号比较）。 */
    private static final int MAX_PORT = 65535;

    private final PlayerStore store;
    private final SceneDirectoryService sceneDirectory;
    private final OwnerTakeovers takeovers;
    private final Executor executor;
    private final Function<Duration, Executor> timer;
    private final LongSupplier nanoClock;
    private final int defaultZoneId;
    private final Duration assignTimeout;
    private final Duration claimWait;
    private final Duration enterBudget;
    private final LoginMetrics metrics;
    private final Function<SessionContext, Integer> deviceRenewal;
    private final PlayerLocationLookup locations;
    private final ZoneMergeFence fence;
    private final InFlightKeys<Long> playersInFlight = new InFlightKeys<>();

    /**
     * @param takeovers     归属仍被持有时请持有者让出
     * @param executor      login 工作线程池（查库、夺权在上面执行）
     * @param defaultZoneId 会话没带 zone 时用的 zone（本 login 的 zone）
     * @param assignTimeout 等 scene-manager 结果（分配场景、登录期重定向签票）的兜底上限，再按剩余的时间预算收紧；
     *                      超时按调用失败处理，保证在途闸门一定释放
     * @param claimWait     归属仍被持有时最多等多久（期间退避重试夺权，截止不晚于时间预算），之后回 2005
     * @param metrics       去向、场景分配、签票与夺权的指标
     */
    public EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                            Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                            LoginMetrics metrics) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics,
                session -> null);
    }

    /**
     * @param deviceRenewal 进游戏前续期会话的设备数登记（{@code AccountLogin::renewDevice}）：回拒绝码（2024 / 2023 / 会话已结束 2018）即拒绝，null 放行
     */
    public EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                            Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                            LoginMetrics metrics, Function<SessionContext, Integer> deviceRenewal) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics,
                deviceRenewal, PlayerLocationLookup.NONE, ZoneMergeFence.OPEN, DEFAULT_ENTER_BUDGET);
    }

    /**
     * @param locations   玩家位置记录（在线 / 断线重连租约 / 待落点）：按它与票据定去向（{@link EnterRoutes}）
     * @param fence       合服围栏的读侧（检查点 F2，判角色的归属区；批次 7.3 之前是恒放行的 {@link ZoneMergeFence#OPEN}），
     *                    在 login 工作线程上同步调用
     * @param enterBudget 一次进游戏的时间预算（见类注释「时间预算」），必须小于 gate 调 login 的 Dubbo 超时
     */
    public EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                            Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                            LoginMetrics metrics, Function<SessionContext, Integer> deviceRenewal,
                            PlayerLocationLookup locations, ZoneMergeFence fence, Duration enterBudget) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics,
                delay -> CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS),
                System::nanoTime, deviceRenewal, locations, fence, enterBudget);
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
                nanoClock, session -> null, PlayerLocationLookup.NONE, ZoneMergeFence.OPEN);
    }

    EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                     Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                     LoginMetrics metrics, Function<Duration, Executor> timer, LongSupplier nanoClock,
                     Function<SessionContext, Integer> deviceRenewal, PlayerLocationLookup locations,
                     ZoneMergeFence fence) {
        this(store, sceneDirectory, takeovers, executor, defaultZoneId, assignTimeout, claimWait, metrics, timer,
                nanoClock, deviceRenewal, locations, fence, DEFAULT_ENTER_BUDGET);
    }

    EnterGameHandler(PlayerStore store, SceneDirectoryService sceneDirectory, OwnerTakeovers takeovers,
                     Executor executor, int defaultZoneId, Duration assignTimeout, Duration claimWait,
                     LoginMetrics metrics, Function<Duration, Executor> timer, LongSupplier nanoClock,
                     Function<SessionContext, Integer> deviceRenewal, PlayerLocationLookup locations,
                     ZoneMergeFence fence, Duration enterBudget) {
        if (enterBudget.isNegative() || enterBudget.isZero()) {
            throw new IllegalArgumentException("进游戏的时间预算必须为正: " + enterBudget);
        }
        this.locations = locations;
        this.deviceRenewal = deviceRenewal;
        this.store = store;
        this.sceneDirectory = sceneDirectory;
        this.takeovers = takeovers;
        this.executor = executor;
        this.defaultZoneId = defaultZoneId;
        this.assignTimeout = assignTimeout;
        this.claimWait = claimWait;
        this.enterBudget = enterBudget;
        this.metrics = metrics;
        this.timer = timer;
        this.nanoClock = nanoClock;
        this.fence = Objects.requireNonNull(fence, "fence");
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

    /** 没有经过分发器的调用（测试）：按此刻受理、没有排队。 */
    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, EnterGameRequest request) {
        return handle(session, request, 0);
    }

    /** 截止 = 受理时刻 + 时间预算：排队（{@code queuedNanos}）同样耗着 gate 的等待，从预算里扣掉（见类注释「时间预算」）。 */
    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, EnterGameRequest request, long queuedNanos) {
        long enterDeadline = nanoClock.getAsLong() - queuedNanos + enterBudget.toNanos();
        if (session.getSessionId() == 0) {
            return done(error(LoginErrorTip.login_error.kLoginSessionIdNotFound_VALUE));
        }
        long playerId = request.getPlayerId();
        long ticketHolder = session.getTicketPlayerId();
        if (ticketHolder != 0 && ticketHolder != playerId) {
            // 持票者绑定（同基线 2011）：票据只认证这条连接是替谁开的，换个角色进游戏一律拒绝。排在一切会话状态与存储操作之前：
            // 不续设备、不占在途闸门（不会让持票者本人的进场撞 2005）、不读位置、不分配。
            metrics.ticketHolderRejected();
            log.info("进游戏拒绝：持票者不符 gate={} session={} 持票者={} 请求的角色={} 票据目标zone={}", session.getGateNodeId(),
                    session.getSessionId(), Long.toUnsignedString(ticketHolder), Long.toUnsignedString(playerId),
                    Integer.toUnsignedString(session.getTicketTargetZoneId()));
            return done(error(LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE));
        }
        String account = session.getAccount();
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
            result = enter(session, account, playerId, enterDeadline);
        } catch (RuntimeException e) {
            playersInFlight.release(playerId);
            throw e;
        }
        return result.whenComplete((reply, error) -> playersInFlight.release(playerId));
    }

    /** @param enterDeadline 时间预算的截止时刻（{@link #nanoClock} 的读数） */
    private CompletableFuture<HandlerReply> enter(SessionContext session, String account, long playerId,
                                                  long enterDeadline) {
        Optional<PlayerRow> found = store.findPlayer(playerId);
        if (found.isEmpty() || !account.equals(found.get().getAccount())) {
            log.info("进游戏拒绝：角色不属于本账号 account={} player={} 存在={}", account, playerId, found.isPresent());
            return done(error(LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE));
        }
        PlayerRow row = found.get();
        if (row.getTradeListingId() != 0 || row.getOwnerRevision() <= 0) {
            return done(error(LoginErrorTip.login_error.kLoginInProgress_VALUE));
        }
        int zoneId = session.getZoneId() != 0 ? session.getZoneId() : defaultZoneId;
        EnterRoutes.LocationRead location = readLocation(playerId);
        EnterRoutes.Decision decision = EnterRoutes.resolveEnterRoute(zoneId, session.getTicketPlayerId(),
                session.getTicketTargetZoneId(), location, playerId, row.getSceneConfigId());
        logCrossZone(session, playerId, zoneId, location, decision);
        return switch (decision) {
            case EnterRoutes.Redirect go5 -> redirect(session, playerId, row, zoneId, go5, enterDeadline);
            case EnterRoutes.Land land -> {
                metrics.enterRouted(land.route());
                yield land(session, playerId, row, land.assign(), enterDeadline);
            }
        };
    }

    /** 读位置记录；出错退化成「读失败」这一行（去向表按存档地图选），不让 Redis 抖动挡住进游戏。 */
    private EnterRoutes.LocationRead readLocation(long playerId) {
        Optional<PlayerLocation> location;
        try {
            location = locations.find(playerId);
        } catch (RuntimeException e) {
            log.warn("读玩家位置失败，按存档里的地图选场景 player={}: {}", playerId, e.toString());
            return EnterRoutes.UNREADABLE;
        }
        return location.<EnterRoutes.LocationRead>map(EnterRoutes.Found::new).orElse(EnterRoutes.ABSENT);
    }

    /** 位置记录不在入口 zone 时记一笔去向的理由（同 zone 的首登 / 回原实例 / 待落点是常态，不打日志）。 */
    private static void logCrossZone(SessionContext session, long playerId, int zoneId, EnterRoutes.LocationRead location,
                                     EnterRoutes.Decision decision) {
        if (!(location instanceof EnterRoutes.Found(PlayerLocation at)) || at.getZoneId() == zoneId) {
            return;
        }
        String why = switch (decision) {
            case EnterRoutes.Redirect go5 -> "角色在别的 zone 的节点上，请 scene-manager 签票、把连接送过去";
            case EnterRoutes.Land land when land.route() == EnterRoute.PINNED -> "这条连接持票钉在本 zone，不再弹走，按首登";
            case EnterRoutes.Land land -> "记录是别处的待落点（不牵引）或记录的 zone 无效，按首登";
        };
        log.info("玩家位置在别的 zone：{} player={} session={} 位置zone={} 节点={} 本次zone={}", why, playerId,
                session.getSessionId(), Integer.toUnsignedString(at.getZoneId()), Integer.toUnsignedString(at.getSceneNodeId()),
                Integer.toUnsignedString(zoneId));
    }

    // ================================================================ 登录期重定向（GO-5）

    /**
     * 请 scene-manager 在角色所在的 zone 选 gate、签票（本地兜底 {@code assignTimeout}，再按剩余预算收紧），完成后回到工作线程定结局：
     * 成功只回应答与指令；没成按首登落在入口 zone（回落用的是签票剩下的预算）。
     */
    private CompletableFuture<HandlerReply> redirect(SessionContext session, long playerId, PlayerRow row, int zoneId,
                                                     EnterRoutes.Redirect route, long enterDeadline) {
        RedirectToZoneRequest request = RedirectToZoneRequest.newBuilder()
                .setFromZoneId(zoneId).setToZoneId(route.toZoneId()).setPlayerId(playerId).build();
        long start = nanoClock.getAsLong();
        return callSceneManager(() -> sceneDirectory.redirectToZone(request), enterDeadline)
                .handleAsync((response, failure) -> afterRedirect(session, playerId, row, route, response, failure, start,
                        enterDeadline), executor)
                .thenCompose(Function.identity());
    }

    private CompletableFuture<HandlerReply> afterRedirect(SessionContext session, long playerId, PlayerRow row,
                                                          EnterRoutes.Redirect route, RedirectToZoneResponse response,
                                                          Throwable failure, long start, long enterDeadline) {
        long elapsed = nanoClock.getAsLong() - start;
        AssignResult result;
        String why;
        if (failure != null) {
            result = AssignResult.ERROR;
            why = "调用失败: " + unwrap(failure);
        } else if (response == null) {
            result = AssignResult.ERROR;
            why = "应答为空";
        } else if (response.getTipId() != 0) {
            result = AssignResult.REJECTED;
            why = "scene-manager 拒绝 tip=" + Integer.toUnsignedString(response.getTipId());
        } else {
            why = incompleteRedirect(response, route.toZoneId());
            result = why == null ? AssignResult.OK : AssignResult.ERROR;
        }
        metrics.sceneRedirectCompleted(result, elapsed);
        if (result == AssignResult.OK) {
            metrics.enterRouted(EnterRoute.REDIRECT);
            return done(redirected(session, playerId, response.getRedirect()));
        }
        metrics.enterRouted(EnterRoute.REDIRECT_FALLBACK);
        log.warn("登录期重定向没成，退化为在入口 zone 按首登进游戏 player={} session={} 入口zone={} 角色所在zone={}: {}", playerId,
                session.getSessionId(), Integer.toUnsignedString(route.fallback().getZoneId()),
                Integer.toUnsignedString(route.toZoneId()), why);
        return land(session, playerId, row, route.fallback(), enterDeadline);
    }

    /**
     * {@code tip_id = 0} 的应答是否齐全（契约：此时 {@code redirect} 必填；残缺按调用失败处理）。查的是 gate 推 124 之前同样会查的几项
     * （地址、端口、票据、签名非空），外加目标 zone 与请求一致——不把客户端送到一个连不上或验不了票的地方。
     *
     * @return null = 齐全；否则是残缺的描述（只进日志）
     */
    static String incompleteRedirect(RedirectToZoneResponse response, int toZoneId) {
        if (!response.hasRedirect()) {
            return "应答残缺：tip 为 0 却没有 redirect";
        }
        ZoneRedirect redirect = response.getRedirect();
        if (redirect.getTargetZoneId() != toZoneId) {
            return "应答残缺：redirect 的目标 zone=" + Integer.toUnsignedString(redirect.getTargetZoneId())
                    + " 不是请求的 " + Integer.toUnsignedString(toZoneId);
        }
        if (redirect.getGateHost().isEmpty() || redirect.getGatePort() == 0
                || Integer.compareUnsigned(redirect.getGatePort(), MAX_PORT) > 0) {
            return "应答残缺：gate 地址不合法 host=" + redirect.getGateHost() + " port="
                    + Integer.toUnsignedString(redirect.getGatePort());
        }
        if (redirect.getTokenPayload().isEmpty() || redirect.getTokenSignature().isEmpty()) {
            return "应答残缺：票据或签名为空";
        }
        return null;
    }

    /**
     * GO-5 的成功应答：{@code EnterGameResponse{player_id}}（不设 {@code error_message}，同基线重定向分支是 EnterGame 的成功出口）
     * + {@code RedirectToGate{redirect}}（scene-manager 给的原样转交，票据字节不解析、不重新序列化）。不绑定玩家、不进场景。
     */
    private static HandlerReply redirected(SessionContext session, long playerId, ZoneRedirect redirect) {
        log.info("进游戏重定向（不夺权、不分配）player={} gate={} session={} 目标zone={} 目标gate={} {}:{} 票据到期={}", playerId,
                session.getGateNodeId(), session.getSessionId(), Integer.toUnsignedString(redirect.getTargetZoneId()),
                Integer.toUnsignedString(redirect.getGateNodeId()), redirect.getGateHost(),
                Integer.toUnsignedString(redirect.getGatePort()), redirect.getTokenDeadline());
        EnterGameResponse response = EnterGameResponse.newBuilder().setPlayerId(playerId).build();
        SessionDirective directive = SessionDirective.newBuilder()
                .setRedirectToGate(RedirectToGate.newBuilder().setRedirect(redirect))
                .build();
        return HandlerReply.of(response, directive);
    }

    // ================================================================ 落地：围栏 → 分配 → 夺权

    /** 在入口 zone 落地：合服围栏检查点 F2 → 分配 → 夺权（工作线程上调用）。 */
    private CompletableFuture<HandlerReply> land(SessionContext session, long playerId, PlayerRow row,
                                                 AssignSceneRequest assignRequest, long enterDeadline) {
        if (mergeFenced(session, playerId, row.getZoneId())) {
            return done(error(SceneErrorTip.scene_error.kEnterSceneFailed_VALUE));
        }
        long assignStart = nanoClock.getAsLong();
        long ownerRevision = row.getOwnerRevision();
        return callSceneManager(() -> sceneDirectory.assign(assignRequest), enterDeadline)
                .handleAsync((assigned, failure) -> afterAssign(session, playerId, ownerRevision, assignRequest, assigned, failure,
                        assignStart, enterDeadline), executor)
                .thenCompose(Function.identity());
    }

    /**
     * 合服围栏检查点 F2（zone-travel-spec §3.5）：落地路径上、分配与夺权之前，判角色的<b>归属区</b>（{@code player.zone_id}，
     * 不是入口 zone）。命中，或读不到（按封锁处理，fail-closed）→ 调用方回 3023——同 Java 进场拒绝的同步口径
     * （基线 26 成功后再推 23 {3023}）。重定向路径不查（F3：只送连接）。批次 7.3 之前围栏恒放行。
     *
     * @return true = 拒绝进游戏
     */
    private boolean mergeFenced(SessionContext session, long playerId, int homeZoneId) {
        try {
            if (fence.inProgress(homeZoneId)) {
                log.warn("进游戏拒绝：角色的归属区正在合服 player={} session={} 归属区={}", playerId, session.getSessionId(),
                        Integer.toUnsignedString(homeZoneId));
                return true;
            }
        } catch (Exception e) {
            log.error("进游戏拒绝：合服围栏读不到，按封锁处理 player={} session={} 归属区={}", playerId, session.getSessionId(),
                    Integer.toUnsignedString(homeZoneId), e);
            if (e instanceof InterruptedException) {
                // 工作线程被中断（关停）：照样按封锁拒绝，中断标记留给线程池。
                Thread.currentThread().interrupt();
            }
            return true;
        }
        return false;
    }

    /**
     * 调 scene-manager：把同步抛出、返回 null、超时都统一成一个一定会完成的 future（copy 出来再加超时，不改动 Dubbo 的 future）。
     * 分配与登录期重定向签票共用同一个本地兜底 {@code assignTimeout}，再按剩余的时间预算收紧；预算已用完就不发起调用，
     * 直接按超时失败（调用方照调用失败处理）。
     */
    private <T> CompletableFuture<T> callSceneManager(Supplier<CompletableFuture<T>> call, long enterDeadline) {
        long timeoutNanos = Math.min(assignTimeout.toNanos(), enterDeadline - nanoClock.getAsLong());
        if (timeoutNanos <= 0) {
            return CompletableFuture.failedFuture(new TimeoutException("进游戏的时间预算已用完，没有发起调用"));
        }
        CompletableFuture<T> future;
        try {
            future = call.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (future == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("scene-manager 返回了 null future"));
        }
        return future.copy().orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    private CompletableFuture<HandlerReply> afterAssign(SessionContext session, long playerId, long ownerRevision, AssignSceneRequest routed,
                                                        AssignSceneResponse assigned, Throwable failure, long assignStart,
                                                        long enterDeadline) {
        long assignElapsed = nanoClock.getAsLong() - assignStart;
        if (failure != null) {
            metrics.sceneAssignCompleted(AssignResult.ERROR, assignElapsed);
            log.warn("进游戏失败：场景分配调用失败 player={} session={}: {}", playerId, session.getSessionId(), unwrap(failure).toString());
            return done(error(SceneErrorTip.scene_error.kEnterSceneFailed_VALUE));
        }
        if (assigned.getTipId() != 0) {
            metrics.sceneAssignCompleted(AssignResult.REJECTED, assignElapsed);
            log.info("进游戏失败：场景分配拒绝 player={} tip={}", playerId, assigned.getTipId());
            return done(error(assigned.getTipId()));
        }
        metrics.sceneAssignCompleted(AssignResult.OK, assignElapsed);

        long claimStart = nanoClock.getAsLong();
        if (enterDeadline - claimStart <= 0) {
            // 分配回到工作线程时预算已用完：gate 可能已经按超时放弃了这次调用，这时夺到的归属没人接（迟到的应答被丢弃），
            // 玩家要等租约过期——宁可回 2005 让客户端重试，也不夺权。
            metrics.ownerClaimCompleted(ClaimOutcome.TIMEOUT, 0);
            log.warn("进游戏失败：时间预算 {} 已用完，不再夺权 player={} session={}", enterBudget, playerId,
                    session.getSessionId());
            return done(error(LoginErrorTip.login_error.kLoginInProgress_VALUE));
        }
        long claimDeadline = claimStart + claimWait.toNanos();
        long deadline = claimDeadline - enterDeadline < 0 ? claimDeadline : enterDeadline;
        CompletableFuture<ClaimStep> chain;
        try {
            chain = claim(session, playerId, ownerRevision, routed, assigned, deadline, enterDeadline, FIRST_CLAIM_BACKOFF, false);
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
     * @param deadline      夺权等待的截止（{@code claimWait} 与时间预算截止中较早的）：{@code claimWait} 先到时最后一次重试排在它上面；
     *                      它就是预算截止时，不排那次注定落空的重试（醒来时预算必然已用完），当场回 2005
     * @param enterDeadline 时间预算的截止：过了它不再夺权、不再请持有者让出，夺到之后也不再复查位置
     * @param retried       之前已经撞上过「仍被持有」（夺到时记为 {@link ClaimOutcome#WAITED}）
     */
    private CompletableFuture<ClaimStep> claim(SessionContext session, long playerId, long ownerRevision, AssignSceneRequest routed,
                                               AssignSceneResponse assigned, long deadline, long enterDeadline,
                                               Duration backoff, boolean retried) {
        if (retried && enterDeadline - nanoClock.getAsLong() <= 0) {
            // 退避醒来时时间预算已用完（退避排在预算截止之前，但计时器晚触发、或回工作线程时排了队，醒来已过截止）：不再夺权。
            // 只能排在预算截止上的那次重试，排之前就放弃了（见下文）。
            // 预算之后 gate 随时会按超时放行同一会话的下一条请求，这时夺到的归属没人接，还可能晚于客户端的重试落库
            // （mmorpg client-request-ordering.md §6.1）。第一次夺权之前的同一检查在 afterAssign。
            log.warn("进游戏失败：退避醒来时时间预算 {} 已用完，不再夺权 player={} session={}", enterBudget, playerId,
                    session.getSessionId());
            return CompletableFuture.completedFuture(new ClaimStep(
                    error(LoginErrorTip.login_error.kLoginInProgress_VALUE), ClaimOutcome.TIMEOUT));
        }
        // 夺权事务的时限 = 剩余预算：截止在事务里到点就回滚、不夺权（TimedOut）。已到点（第一次夺权在 afterAssign 刚查过，
        // 只差几条内存操作）也交给事务判：进事务即回滚、一条语句都不发。
        Duration remaining = Duration.ofNanos(Math.max(1, enterDeadline - nanoClock.getAsLong()));
        ClaimResult result = store.claimOwnership(playerId, session.getAccount(), ownerRevision, remaining);
        if (result instanceof ClaimResult.TimedOut) {
            // gate 可能已按超时放行同一会话的下一条请求：夺到的归属没人接，还可能晚于客户端的重试落库。事务已回滚、没有夺权。
            log.warn("进游戏失败：夺权事务没赶在时间预算 {} 的截止前完成，已回滚、没有夺权 player={} session={}", enterBudget,
                    playerId, session.getSessionId());
            return CompletableFuture.completedFuture(new ClaimStep(
                    error(LoginErrorTip.login_error.kLoginInProgress_VALUE), ClaimOutcome.TIMEOUT));
        }
        if (result instanceof ClaimResult.Claimed claimed) {
            ClaimOutcome outcome = retried ? ClaimOutcome.WAITED : ClaimOutcome.CLAIMED;
            return rerouteIfStale(playerId, routed, assigned, enterDeadline).thenApply(target -> new ClaimStep(
                    accepted(session, playerId, target, claimed.ownerEpoch()), outcome));
        }
        if (result instanceof ClaimResult.NotFound) {
            log.warn("进游戏失败：夺权时角色已不存在 player={}", playerId);
            return CompletableFuture.completedFuture(new ClaimStep(
                    error(LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE), ClaimOutcome.NOT_FOUND));
        }
        long heldEpoch = ((ClaimResult.Held) result).ownerEpoch();
        long now = nanoClock.getAsLong();
        if (enterDeadline - now > 0) {
            takeovers.request(playerId, heldEpoch);
            metrics.takeoverRequested();
        } else {
            // 预算用完之后不再请持有者让出：这次不会再重试；而 gate 一旦放行，客户端重试的 EnterGame 可能已在别的 login 实例上
            // 夺到下一代归属，迟到的让出请求会把它刚进场的会话踢下线。客户端收到 2005 后重试时会自己再请一次。
            log.info("夺权结果回来时时间预算 {} 已用完，不再请持有者让出 player={} session={} held_epoch={}", enterBudget,
                    playerId, session.getSessionId(), heldEpoch);
        }
        long remainingNanos = deadline - now;
        if (remainingNanos <= 0) {
            log.warn("进游戏失败：玩家数据归属仍被持有，等待窗口内（{}，截止不晚于时间预算 {}）没等到释放 player={} session={}"
                    + " held_epoch={}", claimWait, enterBudget, playerId, session.getSessionId(), heldEpoch);
            return CompletableFuture.completedFuture(new ClaimStep(
                    error(LoginErrorTip.login_error.kLoginInProgress_VALUE), ClaimOutcome.TIMEOUT));
        }
        if (deadline - enterDeadline >= 0 && backoff.compareTo(Duration.ofNanos(remainingNanos)) >= 0) {
            // 等待截止就是预算截止，下一次退避又会被截到它上面：醒来时预算必然已用完（方法开头的检查），这次重试注定不夺权。
            // 与其白等到截止再回 2005（多占着在途闸门和 gate 上这条会话的队列，回包离 gate 放行也更近），不如现在就回。
            log.warn("进游戏失败：玩家数据归属仍被持有，下一次重试只能排在时间预算 {} 的截止上，不再等 player={} session={}"
                    + " held_epoch={}", enterBudget, playerId, session.getSessionId(), heldEpoch);
            return CompletableFuture.completedFuture(new ClaimStep(
                    error(LoginErrorTip.login_error.kLoginInProgress_VALUE), ClaimOutcome.TIMEOUT));
        }
        log.info("玩家数据归属仍被持有，已请持有者让出，{}ms 后重试夺权 player={} session={} held_epoch={}",
                Math.min(backoff.toMillis(), TimeUnit.NANOSECONDS.toMillis(remainingNanos)), playerId,
                session.getSessionId(), heldEpoch);
        Duration delay = backoff.compareTo(Duration.ofNanos(remainingNanos)) < 0 ? backoff : Duration.ofNanos(remainingNanos);
        Duration nextBackoff = backoff.multipliedBy(2).compareTo(MAX_CLAIM_BACKOFF) < 0
                ? backoff.multipliedBy(2) : MAX_CLAIM_BACKOFF;
        return onWorkerAfter(delay).thenCompose(ignored -> claim(session, playerId, ownerRevision, routed, assigned, deadline,
                enterDeadline, nextBackoff, true));
    }

    /**
     * 夺到归属后再看一眼位置记录：路由时用的记录在夺权期间被上一个持有者改掉了（LeaveGame 之后紧跟着 EnterGame：
     * 读记录时 scene 还没处理离场，登出墓碑在夺权等释放的这段时间才落下）就按新的记录重新分配一次——同基线「LeaveGame 之后按首登」。
     * 只在路由用了原实例（回原实例这一种去向）时才看；读出错或重新分配失败都沿用第一次的结果（只影响落点，不影响归属）。
     * 只认入口 zone 的记录：夺权之后出现的别 zone 记录（含别处的待落点）当作没有——已经持有归属，不再重定向。
     * 时间预算已用完就不复查、沿用原落点：归属已经夺到，应答必须赶在 gate 放弃这次调用之前回去（见类注释「时间预算」）；
     * 重新分配的等待同样按剩余预算收紧。
     */
    private CompletableFuture<AssignSceneResponse> rerouteIfStale(long playerId, AssignSceneRequest routed,
                                                                  AssignSceneResponse assigned, long enterDeadline) {
        if (routed.getPreferredSceneId() == 0) {
            return CompletableFuture.completedFuture(assigned);
        }
        if (enterDeadline - nanoClock.getAsLong() <= 0) {
            log.warn("夺到归属时时间预算 {} 已用完，不再复查玩家位置，沿用原落点 player={}", enterBudget, playerId);
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
        return callSceneManager(() -> sceneDirectory.assign(again.build()), enterDeadline).handle((target, failure) -> {
            long elapsed = nanoClock.getAsLong() - start;
            if (failure != null || target.getTipId() != 0) {
                metrics.sceneAssignCompleted(failure != null ? AssignResult.ERROR : AssignResult.REJECTED, elapsed);
                log.warn("重新分配场景失败，沿用原落点 player={}: {}", playerId,
                        failure != null ? unwrap(failure).toString() : "tip=" + target.getTipId());
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

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
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
