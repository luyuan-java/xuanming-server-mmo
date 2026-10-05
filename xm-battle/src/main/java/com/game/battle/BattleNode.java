package com.game.battle;

import com.game.api.proto.BattleNodeInfo;
import com.game.battle.BattleInfrastructure.Lease;
import com.game.battle.BattleInfrastructure.RpcExport;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.directory.BattleDirectoryPublisher;
import com.game.battle.edge.DirectEdge;
import com.game.battle.edge.EdgeDependencies;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.BattleClock;
import com.game.battle.room.BattleRoomService;
import com.game.battle.room.BattleScheduler;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.room.RoomDependencies;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.common.RunMode;
import com.game.common.token.BattleTickets;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * battle 节点的组合根与生命周期（基线 {@code main.cpp:260-350}；battle-node-spec §6.5、§6.6、§7.3、§7.10、§7.11）。启动、停机、租约丢失的
 * 顺序都写在这里，别处不隐式创建线程。启动前的门禁（票据密钥策略、{@code XM_DUBBO_SECRET}、prod 下的连接上限、指纹模式、配表与消息号）
 * 在 {@code BattleConfiguration} 建 bean 时已经过完。
 *
 * <p><b>线程</b>（§7.3）：
 * <ul>
 *   <li>{@code battle-logic}（单线程 {@link NioEventLoopGroup}）：直连面 child group 里<b>唯一</b>的 EventLoop，同时独占全部房间、直连会话与房间计时器
 *       （{@link EventLoopBattleScheduler} 包装它）；准入闸的开 / 关与「关闸 + 作废全部房间」都在它上面执行；</li>
 *   <li>{@code battle-sched}（2 条）：节点号续期（租约丢失回调也在这里）、目录发布（Redis I/O，不占逻辑线程）；</li>
 *   <li>{@code battle-rpc-reply}（2 条）：完成控制面的 future（Triple 序列化与写出不占逻辑线程）；</li>
 *   <li>Dubbo 业务线程 / 管理 Tomcat 线程：只经 {@link BattleNodeServiceImpl} 读准入闸、投递任务。</li>
 * </ul>
 *
 * <p><b>启动</b>（任一步失败即逆序释放并拒绝启动）：占节点号租约（作用域 0）+ 每进程实例 UUID → 起逻辑线程、房间服务、回复执行器 →
 * 导出 Dubbo（端口被占即拒启）→ 绑直连端口（被占即拒启）→ <b>在逻辑线程上开准入闸</b> → 首次发布目录（{@code accepting = true}），之后每 5 s
 * → 就绪日志。先开闸、后进目录：match 在开闸之前找不到这个节点，基线「已发布、未开闸」的窗口在 Java 里不存在（§11 N4）。
 *
 * <p><b>停机</b>（{@link #stop()}，§7.11）：停发布并删目录条目 → 在逻辑线程的<b>同一个任务</b>里关准入闸、{@code abortAll("node_shutdown")}
 * （观众收 166 ABORTED + ONGOING，参战者不收帧，房间直连全部优雅关闭）→ 直连面停止接受、有界等待排空后强关剩余连接（修基线 F1，§11 N16）
 * → 反导出 Dubbo（期间进来的 createBattle 在逻辑线程上复核看到 CLOSED → NOT_ALLOCATABLE；补签 → 1005）→ 停逻辑线程与回复执行器 →
 * 最后交还租约。
 *
 * <p><b>租约丢失</b>（§7.10、§11 N17、Q4）：在逻辑线程上关准入闸；停发布并尽力删除（仍属于本实例的）目录条目；打 ERROR、计
 * {@code xm_battle_lease_lost_total}；<b>不作废</b>在打的房间（battle 不持有权威数据，票据带实例 id，同号新进程不收旧票；结算与确认按玩家与
 * battle_id 寻址，与节点号无关），房间按期限或胜负自然结束，房间数归零时打一行「可安全重启」；进程保持存活但不可分配。
 */
public final class BattleNode implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BattleNode.class);

    /** {@code battle-sched} 线程数：续期与目录发布各一条，发布卡在 Redis 上时不拖住续期。 */
    static final int SCHED_THREADS = 2;
    /** {@code battle-rpc-reply} 线程数（只做 future 完成，不做业务）。 */
    static final int REPLY_THREADS = 2;
    /** 别的线程同步等逻辑线程执行一个任务的上限（开准入闸）。 */
    static final Duration LOGIC_CALL_TIMEOUT = Duration.ofSeconds(5);
    /** 停机时等「关闸 + 作废全部房间」执行完的上限（房间多时作废要逐间推 166、关直连）。 */
    static final Duration ABORT_TIMEOUT = Duration.ofSeconds(10);
    /** 租约丢失后检查房间是否已全部结束的间隔。 */
    static final Duration DRAIN_CHECK_PERIOD = Duration.ofSeconds(5);
    /** 停机作废房间时的原因（日志用，同基线）。 */
    static final String SHUTDOWN_REASON = "node_shutdown";

    private final BattleProperties props;
    private final RunMode runMode;
    private final String advertiseHost;
    /** 票据与目录 client_host 里给客户端的直连主机（{@link BattleProperties#effectiveClientAdvertiseHost}，deploy-ci-spec Q9）。 */
    private final String clientAdvertiseHost;
    private final BattleTables tables;
    private final BattleTickets tickets;
    private final OutboundPorts outbound;
    private final AdmissionGate admission;
    private final BattleMetrics metrics;
    private final BattleClock clock;
    private final BattleInfrastructure infra;
    private final String instanceId = UUID.randomUUID().toString();

    // 以下在 start() 里依序创建、release() 里逆序释放；租约丢失回调与目录快照在调度线程上读取，所以都是 volatile。
    private volatile ScheduledExecutorService sched;
    private volatile Lease lease;
    private volatile BattleIdentity identity;
    private volatile NioEventLoopGroup logicGroup;
    private volatile BattleScheduler scheduler;
    private volatile BattleRoomService rooms;
    private volatile ThreadPoolExecutor replyExecutor;
    private volatile BattleNodeServiceImpl provider;
    private volatile RpcExport rpc;
    private volatile DirectEdge edge;
    private volatile BattleDirectoryPublisher publisher;
    private volatile ScheduledFuture<?> drainWatch;
    private volatile boolean running;

    /**
     * @param props         {@code xm.battle.*}（已校验）
     * @param runMode       运行模式（只进就绪日志；与模式有关的门禁已在装配时过完）
     * @param advertiseHost 控制面的通告地址（目录里的 rpc_host、Dubbo URL）；没配 {@code xm.battle.client-advertise-host} 时也是票据里的直连主机
     *                      与目录里的 client_host
     * @param tables        启动时加载的配表、指纹、限频表与消息号
     * @param tickets       票据签名器（房间签票与直连面验签共用）
     * @param outbound      大厅公告回落与出站端口
     * @param admission     建房准入闸（与控制面共用；gauge 由本类绑定）
     * @param clock         墙钟
     * @param infra         要 Redis / 端口 / 节点身份的部件的工厂（生产 {@link BattleInfrastructure#production}）
     */
    public BattleNode(BattleProperties props, RunMode runMode, String advertiseHost, BattleTables tables, BattleTickets tickets,
                      OutboundPorts outbound, AdmissionGate admission, BattleMetrics metrics, BattleClock clock,
                      BattleInfrastructure infra) {
        this.props = Objects.requireNonNull(props, "props");
        this.runMode = Objects.requireNonNull(runMode, "runMode");
        this.advertiseHost = Objects.requireNonNull(advertiseHost, "advertiseHost");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.infra = Objects.requireNonNull(infra, "infra");
        if (advertiseHost.isBlank()) {
            throw new IllegalArgumentException("xm.advertise-host 不能为空（控制面通告地址；没配 client-advertise-host 时也是票据里给客户端的直连地址）");
        }
        this.clientAdvertiseHost = props.effectiveClientAdvertiseHost(advertiseHost);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            doStart();
            running = true;
        } catch (Exception e) {
            release();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("battle 节点启动失败", e);
        }
    }

    private void doStart() throws Exception {
        // 第 4 步：节点号租约（作用域 0）+ 本进程实例 UUID
        sched = Executors.newScheduledThreadPool(SCHED_THREADS, new DefaultThreadFactory("battle-sched", true));
        Lease l = infra.acquireLease(sched, instanceId, this::onLeaseLost);
        lease = l;
        int nodeId = l.nodeId();
        // 票据里的直连地址用客户端通告地址；控制面（Dubbo 导出、目录 rpc_host）用 advertiseHost（deploy-ci-spec Q9）
        BattleIdentity id = new BattleIdentity(nodeId, instanceId, clientAdvertiseHost, props.effectiveAdvertisePort());
        identity = id;

        // 第 5 步：逻辑线程（= 直连面唯一的 I/O EventLoop）、房间服务、回复执行器。状态量回调读 volatile 字段（抓取线程上调用），
        // 组件还没建出来或已释放时报 0。
        NioEventLoopGroup group = new NioEventLoopGroup(1, new DefaultThreadFactory("battle-logic"));
        logicGroup = group;
        EventLoopBattleScheduler logic = new EventLoopBattleScheduler(group.next());
        scheduler = logic;
        metrics.bindAdmissionPhase(admission::phase);
        metrics.bindLogicPendingTasks(() -> {
            BattleScheduler s = scheduler;
            return s == null ? 0 : s.pendingTasks();
        });
        BattleRoomService roomService = infra.rooms(new RoomDependencies(tables.data(), tables.fingerprint(),
                props.tableFingerprintMode(), logic, clock, id, tickets, tables.messageIds(), outbound.lobby(),
                outbound.sceneEvents(), outbound.settlements(), outbound.activityResults(), outbound.results(), metrics));
        rooms = roomService;
        metrics.bindRooms(() -> {
            BattleRoomService r = rooms;
            return r == null ? 0 : r.roomCount();
        });
        // 回复执行器队列无界，但同时排队的至多是在途上限条
        replyExecutor = new ThreadPoolExecutor(REPLY_THREADS, REPLY_THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new DefaultThreadFactory("battle-rpc-reply", true));
        BattleNodeServiceImpl service = new BattleNodeServiceImpl(admission, logic, roomService, replyExecutor,
                props.rpcMaxInflight(), metrics);
        provider = service;

        // 第 6 步：导出控制面（端口被占 / 缺 XM_DUBBO_SECRET 拒绝启动）。准入闸还没开：此刻进来的 createBattle 回 NOT_ALLOCATABLE(not_started)
        RpcExport export = infra.exportRpc(service, advertiseHost, props.rpcPort());
        rpc = export;

        // 第 7 步：绑直连端口（被占即拒启）；child group 就是逻辑线程组
        DirectEdge directEdge = infra.edge(new EdgeDependencies(props, group, roomService, tickets, id, clock,
                tables.messageLimits(), tables.messageIds(), metrics));
        edge = directEdge;
        metrics.bindDirectConnections(() -> {
            DirectEdge e = edge;
            return e == null ? 0 : e.connectionCount();
        });
        directEdge.start();

        // 第 8 步：在逻辑线程上开准入闸（停机 / 租约丢失先于此刻发生时保持关闭）
        boolean opened = callOnLogic(admission::open, LOGIC_CALL_TIMEOUT);
        if (!opened) {
            log.warn("battle 启动完成时准入闸已关闭（停机已开始或租约已丢失），不接新房间 phase={}", admission.phase().wireName());
        }

        // 第 9 步：先开闸、后进目录。首发在本线程上同步做，之后每 5 s 一次
        BattleNodeInfo info = BattleNodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setInstanceId(instanceId)
                .setRpcHost(advertiseHost)
                .setRpcPort(export.port())
                .setClientHost(clientAdvertiseHost)
                .setClientPort(props.effectiveAdvertisePort())
                .setTableFingerprint(tables.fingerprint())
                .build();
        BattleDirectoryPublisher p = new BattleDirectoryPublisher(infra.directory(nodeId), info, this::directorySnapshot);
        publisher = p;
        p.publishNow();
        p.start(sched);

        // 第 10 步：就绪日志
        log.info("battle 节点已就绪 node_id={} instance={} client={}:{} advertise={}:{} rpc={}:{} max_connections={} run_mode={} "
                        + "admission={} table_fingerprint={} fingerprint_mode={}",
                nodeId, instanceId, props.clientBindHost(), props.clientPort(), clientAdvertiseHost, props.effectiveAdvertisePort(),
                advertiseHost, export.port(), props.effectiveMaxConnections(), runMode.name().toLowerCase(Locale.ROOT),
                admission.phase().wireName(), tables.fingerprint(), props.tableFingerprintMode().wireName());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("battle 节点停机中");
        release();
        log.info("battle 节点已停机");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---------------------------------------------------------------- 查询（任意线程）

    /** 控制面的进程内入口（dev 管理接口用，同一条准入与投递路径）；节点没在运行时为空。 */
    public Optional<BattleNodeServiceImpl> controlPlane() {
        BattleNodeServiceImpl p = provider;
        return running && p != null ? Optional.of(p) : Optional.empty();
    }

    /** 本进程身份；节点没启动过为空。 */
    public Optional<BattleIdentity> identity() {
        return Optional.ofNullable(identity);
    }

    /** 本进程实例 UUID（每次进程启动新生成，票据与目录都带它）。 */
    public String instanceId() {
        return instanceId;
    }

    public AdmissionGate admission() {
        return admission;
    }

    // ---------------------------------------------------------------- 租约丢失

    /** 租约丢失回调（{@code battle-sched} 线程上，只调一次；包内可见供测试直接驱动）。 */
    void onLeaseLost() {
        metrics.leaseLost();
        BattleRoomService r = rooms;
        log.error("battle 节点号租约丢失：关准入闸、停发布节点目录；在打的房间不作废、按期限或胜负自然结束，房间数归零后可安全重启 "
                        + "node_id={} instance={} rooms={}", identity == null ? 0 : identity.nodeId(), instanceId,
                r == null ? 0 : r.roomCount());
        // 关闸放在逻辑线程上（与建房的复核串行）；逻辑线程已停 / 还没建出来时直接关（准入闸本身是原子量）
        BattleScheduler s = scheduler;
        if (s == null) {
            admission.close();
        } else {
            try {
                s.execute(admission::close);
            } catch (RejectedExecutionException e) {
                admission.close();
            }
        }
        BattleDirectoryPublisher p = publisher;
        if (p != null) {
            p.stopAfterLeaseLost();
        }
        ScheduledExecutorService sc = sched;
        if (sc != null) {
            try {
                drainWatch = sc.scheduleWithFixedDelay(this::reportDrainedAfterLeaseLoss, 0, DRAIN_CHECK_PERIOD.toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.debug("调度线程已关闭（停机中），不再等房间归零");
            }
        }
    }

    /** 租约丢失之后房间数归零时打一行「可安全重启」，然后不再检查。 */
    private void reportDrainedAfterLeaseLoss() {
        BattleRoomService r = rooms;
        if (r != null && r.roomCount() > 0) {
            return;
        }
        log.warn("battle 节点号租约已丢失且房间已全部结束：可安全重启本节点 instance={}", instanceId);
        ScheduledFuture<?> watch = drainWatch;
        if (watch != null) {
            watch.cancel(false);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 目录发布的可变部分（调度线程上调用：只读原子量）。 */
    private BattleDirectoryPublisher.Snapshot directorySnapshot() {
        Lease l = lease;
        BattleRoomService r = rooms;
        DirectEdge e = edge;
        return new BattleDirectoryPublisher.Snapshot(admission.isOpen(), l != null && l.isValid(), l == null || l.isLost(),
                r == null ? 0 : r.roomCount(), e == null ? 0 : e.connectionCount());
    }

    /**
     * 按启动的逆序释放，每一步都容忍前面没建出来（启动失败时也走这里）：停发布 / 删目录 → 逻辑线程上同一个任务里关闸 + 作废全部房间 →
     * 直连面停止接受并有界排空 → 反导出 Dubbo → 停逻辑线程与回复执行器 → 最后交还租约（停机期间号仍归本实例）。
     */
    private void release() {
        ScheduledFuture<?> watch = drainWatch;
        if (watch != null) {
            watch.cancel(false);
        }
        // 第 1 步：停发布并删条目（读方最多滞后 5 s）。租约已丢失时条目只在仍属于本实例时删（已在丢失时做过，这里幂等）
        BattleDirectoryPublisher p = publisher;
        Lease l = lease;
        if (p != null) {
            if (l != null && l.isLost()) {
                p.stopAfterLeaseLost();
            } else {
                p.stop(true);
            }
        }
        // 第 2 步：同一个逻辑任务里「关闸 → 作废全部房间」：排在它后面的建房在逻辑线程上复核必然看到 CLOSED
        closeAdmissionAndAbortRooms();
        // 第 3 步：直连面停止接受，等优雅关闭的连接把终局包与 FIN 写完（至多 shutdown-flush-timeout），再强关剩余连接
        DirectEdge e = edge;
        if (e != null) {
            try {
                e.stopAccepting();
                e.drainAndClose(props.shutdownFlushTimeout());
            } catch (RuntimeException ex) {
                log.warn("关闭 battle 直连面出错（继续停机）", ex);
            }
        }
        // 第 4 步：反导出控制面（等在途调用至多 1.5 s 的 1/3）
        RpcExport export = rpc;
        if (export != null) {
            export.close();
            rpc = null;
        }
        provider = null;
        // 第 5 步：停逻辑线程，之后才停回复执行器（之后不会再有逻辑线程上完成的结局）
        NioEventLoopGroup group = logicGroup;
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        ThreadPoolExecutor replies = replyExecutor;
        if (replies != null) {
            replies.shutdown();
            try {
                if (!replies.awaitTermination(2, TimeUnit.SECONDS)) {
                    replies.shutdownNow();
                }
            } catch (InterruptedException ex) {
                replies.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // 第 6 步：交还租约，停调度线程
        if (l != null) {
            l.close();
        }
        ScheduledExecutorService sc = sched;
        if (sc != null) {
            sc.shutdownNow();
        }
    }

    private void closeAdmissionAndAbortRooms() {
        BattleScheduler s = scheduler;
        BattleRoomService r = rooms;
        if (s == null || r == null) {
            admission.close();
            return;
        }
        try {
            callOnLogic(() -> {
                admission.close();
                r.abortAll(SHUTDOWN_REASON);
                return null;
            }, ABORT_TIMEOUT);
        } catch (Exception ex) {
            admission.close();
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("停机时作废房间没有在 {} 内完成（准入闸已关；继续停机，剩余房间随进程退出丢失，玩家冻结到期限由 scene 的 reaper 解除）",
                    ABORT_TIMEOUT, ex);
        }
    }

    /** 在逻辑线程上执行一个任务并限时等它完成（调用方不得是逻辑线程）。任务抛出的异常原样抛出。 */
    private <T> T callOnLogic(Callable<T> task, Duration timeout) throws Exception {
        BattleScheduler s = scheduler;
        CompletableFuture<T> done = new CompletableFuture<>();
        s.execute(() -> {
            try {
                done.complete(task.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            return done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        } catch (TimeoutException e) {
            throw new TimeoutException("battle 逻辑线程 " + timeout + " 内没有执行完任务");
        }
    }
}
