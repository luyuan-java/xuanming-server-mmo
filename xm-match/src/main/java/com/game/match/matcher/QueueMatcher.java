package com.game.match.matcher;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.gather.BattleNodes;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.MatcherRound;
import com.game.match.metrics.MatchMetrics.QueueAnomaly;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.support.MatchModes;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.DropReason;
import com.game.match.ticket.TicketStore.PopResult;
import com.game.match.ticket.TicketStore.QueueSnapshot;
import com.game.match.ticket.TicketStore.SnapshotEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 凑单（match-spec §2.5–§2.9、§9.5；基线 {@code matcher.go}）：一轮 = 暂停判定 → 读队列注册集 → 逐条队列「抢锁 → 报深度 → 反复挑组、弹组、
 * 交给开局管线」。只经 {@link TicketStore} / {@link GatherLauncher} / {@link BattleNodes} / {@link PlayerStatusReader} 这些接口干活。
 *
 * <p><b>暂停</b>（在抢任何锁之前判；暂停的一轮不碰队列，排队原样保留）：battle 目录读不到或没有可分配的节点、发号租约无效、开局管线的在途许可已满。
 * 后两条是 Java 新增——否则会出现「弹组 → gather 发不出号或拿不到许可 → 回队首 → 500 ms 后再弹」的热循环。暂停期间每 10 s 告警一次。
 * 一轮中途许可用完或租约失效时不再弹组，剩下的队列照常抢锁报深度。
 *
 * <p><b>单条队列</b>（{@link #matchQueueOnce}）：
 * <ol>
 *   <li>凑满人数为 0（配置被摘掉后残留的队列）→ 告警跳过，不动数据。</li>
 *   <li>抢凑单锁；抢不到 → 跳过，本实例对这条队列的两个 gauge 记 0（看板按实例求和，只有持锁实例报实值）。</li>
 *   <li>报深度；不够人数 → 饥饿 gauge 归零，深度为 0 时把空队列从注册集剔除。</li>
 *   <li>持锁期间循环：取队列前 {@value #SCAN_LIMIT} 人的快照与各人票据 → {@link GroupPicker} 挑一组 → {@link TicketStore#pop} 原子弹组 →
 *       {@link GatherLauncher#launch}（不等结果、不占锁）；挑不出组就结束。</li>
 * </ol>
 *
 * <p><b>成员校验</b>（挑组时逐人；{@link GroupPicker.Gate} 的实现在这里）：票据缺失 / 不是 queued / 不属于这条队列 → 摘队列项；退避未到点 → 本轮跳过；
 * 有战斗锁 → 删票出局；位置是重连租约 → 本轮跳过、原位保留，是登出墓碑或没有记录 → 删票出局（M12：Java 断线即移除实体，掉线的人进了组只会在
 * 备战一步失败，还让排在他前面的人白冻结一次）。删票一律带校验时读到的票号，期间重新排队的同一玩家不受影响。
 *
 * <p><b>弹组被拒</b>（校验与弹组之间有人取消 / 票被换掉）：存储什么都没写，其余人原位不动；对被拒的每个人按无效成员摘一次，然后在本轮内重挑，
 * 同一队列至多重挑 {@value #MAX_REPICKS} 次（只是退避没到点的人摘不掉，不设上限会空转）。弹组的应答丢了（结局不明）时用同一个弹组标记重发一次——
 * 存储按标记识别重放；仍然不明就放弃这条队列的本轮，已弹出的票据按 matched TTL 自愈。
 *
 * <p><b>依赖故障</b>：任何一步读写失败都只结束这条队列的本轮（不做出局判定），锁照常释放，下一轮重来；处理某一条队列时冒出的意料之外的
 * 异常同样只结束它自己的本轮，排在后面的队列照常处理。队列锁只是效率手段——
 * 一条队列的全部操作共用一个等于锁 TTL 的截止，锁过期之后不再继续；即便两个实例同时处理同一条队列，弹组脚本也保证不双弹。
 *
 * <p><b>线程</b>：不是线程安全的，只在凑单线程上调（测试里单线程驱动）。除了经接口做的 I/O 之外没有别的阻塞。
 */
public final class QueueMatcher {

    private static final Logger log = LoggerFactory.getLogger(QueueMatcher.class);

    /** 每次挑组读取的队列前缀长度：锚点与候选都只在这一段里找，更深的成员等前面的人被弹走后自然进入前缀（基线 {@code queueScanLimit}）。 */
    public static final int SCAN_LIMIT = 256;
    /** 弹组被拒之后，同一条队列在一轮内最多重挑的次数。 */
    public static final int MAX_REPICKS = 3;
    /** 暂停、饥饿、坏数据这几类告警的限频间隔（基线 10 s）。 */
    static final long WARN_INTERVAL_NANOS = 10_000_000_000L;
    /** 释放凑单锁的独立预算：队列的截止用完了也要试着放锁（放不掉由 TTL 兜底）。 */
    static final long UNLOCK_BUDGET_MS = 3_000;
    /** 限频表的容量：队列键由客户端可控的副本号构成，不能让它无限长。 */
    private static final int WARN_KEYS = 256;

    private final TicketStore store;
    private final PlayerStatusReader players;
    private final GatherLauncher gather;
    private final BattleNodes battleNodes;
    private final MatchIds ids;
    private final MatchMetrics metrics;
    private final MetricLabels labels;
    private final MatchProperties props;
    private final String instanceId;
    private final Tolerance tolerance;
    private final long lockTtlMs;
    private final LongSupplier nanoClock;

    /** 告警限频：键 → 上次告警的单调时刻；按最近使用淘汰。 */
    private final Map<String, Long> lastWarnNanos = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > WARN_KEYS;
        }
    };
    /** 本实例上报过 gauge 的标签组合（标签键 → 代表队列）：整轮结束时，这一轮没再碰到的归零（队列被别的实例弹空剔除后不再出现在注册集里）。 */
    private final Map<String, QueueRef> reportedLabels = new HashMap<>();

    public QueueMatcher(TicketStore store, PlayerStatusReader players, GatherLauncher gather, BattleNodes battleNodes, MatchIds ids,
                        MatchMetrics metrics, MetricLabels labels, MatchProperties props, MatchInstance instance) {
        this(store, players, gather, battleNodes, ids, metrics, labels, props, instance, System::nanoTime);
    }

    /** @param nanoClock 单调时钟（只用于告警限频；测试注入） */
    QueueMatcher(TicketStore store, PlayerStatusReader players, GatherLauncher gather, BattleNodes battleNodes, MatchIds ids,
                 MatchMetrics metrics, MetricLabels labels, MatchProperties props, MatchInstance instance, LongSupplier nanoClock) {
        this.store = Objects.requireNonNull(store, "store");
        this.players = Objects.requireNonNull(players, "players");
        this.gather = Objects.requireNonNull(gather, "gather");
        this.battleNodes = Objects.requireNonNull(battleNodes, "battleNodes");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.labels = Objects.requireNonNull(labels, "labels");
        this.props = Objects.requireNonNull(props, "props");
        this.instanceId = Objects.requireNonNull(instance, "instance").id();
        this.tolerance = Tolerance.of(props.rating().tolerance());
        this.lockTtlMs = props.matcher().lockTtl().toMillis();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    // ================================================================ 一轮

    /**
     * 跑一轮。依赖故障与单条队列里的意外异常都不抛（收敛成 {@link MatcherRound#ERROR}）；其余意料之外的异常原样抛出，由调度方兜住
     * （JDK 的调度器遇到一次异常就永久停转）。
     *
     * @param stopRequested 停机信号：为真时在队列之间、两次弹组之间尽快收手
     * @return 这一轮的结局（调度方据此计 {@code xm_match_matcher_rounds_total}）
     */
    public MatcherRound runRound(BooleanSupplier stopRequested) {
        BattleNodes.Census census = battleNodes.census();
        metrics.battleNodes(census.accepting(), census.notAccepting());
        if (census.nothingAllocatable()) {
            warnLimited("paused:no_battle", () -> log.warn("凑单暂停：{}（排队原样保留，等 battle 节点登记）",
                    census.readFailed() ? "battle 节点目录读不到" : "没有可分配的 battle 节点"));
            return MatcherRound.PAUSED_NO_BATTLE;
        }
        if (!ids.leaseValid()) {
            warnLimited("paused:no_lease", () -> log.warn("凑单暂停：发号租约{}（排队原样保留）",
                    ids.leaseLost() ? "已丢失，不会自愈，需要重启本进程" : "续期滞后，恢复后自动继续"));
            return MatcherRound.PAUSED_NO_LEASE;
        }
        if (gather.availablePermits() <= 0) {
            warnLimited("paused:saturated", () -> log.warn("凑单暂停：开局管线的在途许可已满（排队原样保留）"));
            return MatcherRound.PAUSED_SATURATED;
        }
        Set<String> index;
        try {
            index = store.queueIndex(Deadline.after(lockTtlMs));
        } catch (Deadline.DependencyException e) {
            log.error("凑单读队列注册集失败，本轮结束", e);
            return MatcherRound.ERROR;
        }
        Gauges gauges = new Gauges();
        boolean failed = false;
        boolean stopped = false;
        for (String queueKey : index) {
            if (stopRequested.getAsBoolean()) {
                stopped = true;
                break;
            }
            Optional<QueueRef> queue = QueueRef.ofQueueKey(queueKey);
            if (queue.isEmpty()) {
                // 注册集里混进了不是本服务写的东西：不动它，只告警
                warnLimited("bad-key:" + queueKey, () -> log.error("凑单无法解析注册集里的队列键，跳过: '{}'", queueKey));
                continue;
            }
            failed |= !matchQueue(queue.get(), gauges, stopRequested);
        }
        // 停机打断的一轮没看全注册集，不能据此把「没碰到」的标签归零
        gauges.flush(!stopped);
        return failed ? MatcherRound.ERROR : MatcherRound.OK;
    }

    /**
     * 对一条队列做一次凑单（不做整轮的暂停判定；真依赖的集成测试直接驱动它，不遍历全局注册集）。
     *
     * @return true = 正常结束（含抢不到锁、人数不够、挑不出组）；false = 因依赖故障提前结束
     */
    public boolean matchQueueOnce(QueueRef queue) {
        Gauges gauges = new Gauges();
        boolean ok = matchQueue(queue, gauges, () -> false);
        gauges.flush(false);
        return ok;
    }

    // ================================================================ 单条队列

    private boolean matchQueue(QueueRef queue, Gauges gauges, BooleanSupplier stopRequested) {
        // 只有 1V1 / 5V5 / PVE_TEAM 走队列：PVE_SOLO 虽然有「凑满人数 1」，但它不入队（建票即开局），这种队列键只可能来自人为改数据
        int required = MatchModes.queued(queue.mode()) ? props.requiredPlayers(queue.mode(), queue.configId()) : 0;
        if (required <= 0) {
            warnLimited("no-size:" + queue, () -> log.error("队列 {} 没有凑满人数的配置（模式不走队列，或该副本的组队人数被摘掉了），跳过、不动数据", queue));
            return true;
        }
        // 这条队列的全部读写共用一个截止：锁过期之后不再继续
        Deadline d = Deadline.after(lockTtlMs);
        boolean locked;
        // 这里兜的是 RuntimeException 而不只是依赖异常：某一条队列的数据触发了意料之外的错误时，不能让它每一轮都把排在它后面的队列一起拖死
        try {
            locked = store.tryLockQueue(queue, instanceId, lockTtlMs, d);
        } catch (RuntimeException e) {
            log.error("凑单抢锁失败 queue={}", queue, e);
            return false;
        }
        if (!locked) {
            gauges.notHeld(queue);
            return true;
        }
        try {
            matchLocked(queue, required, d, gauges, stopRequested);
            return true;
        } catch (Deadline.DependencyException e) {
            log.error("凑单依赖故障，结束这条队列的本轮（不做出局判定，下一轮重来） queue={}", queue, e);
            return false;
        } catch (RuntimeException e) {
            log.error("凑单处理这条队列时出了意料之外的错误，结束它的本轮（别的队列照常） queue={}", queue, e);
            return false;
        } finally {
            try {
                store.unlockQueue(queue, instanceId, Deadline.after(UNLOCK_BUDGET_MS));
            } catch (RuntimeException e) {
                log.error("凑单释放锁失败（由锁的 TTL 兜底） queue={}", queue, e);
            }
        }
    }

    private void matchLocked(QueueRef queue, int required, Deadline d, Gauges gauges, BooleanSupplier stopRequested) {
        long depth = store.queueLength(queue, d);
        gauges.depth(queue, depth);
        if (depth < required) {
            // 人数不够谈不上「锚点凑不到候选」：饥饿归零，深度看 queue_depth
            gauges.starved(queue, 0);
            if (depth == 0 && store.pruneIfEmpty(queue, d)) {
                log.info("空队列已从注册集剔除 queue={}", queue);
            }
            return;
        }
        long matchedTtlMs = MatchBudgets.matchedTicketTtlSeconds(required) * 1000L;
        boolean rated = MatchModes.rated(queue.mode());
        int repicks = 0;
        // 持锁期间可以连续凑多组
        while (!stopRequested.getAsBoolean()) {
            if (!ids.leaseValid() || gather.availablePermits() <= 0) {
                log.info("发号租约无效或开局管线的在途许可已满，这条队列本轮不再弹组 queue={}", queue);
                return;
            }
            GroupPicker.Pick pick = pick(queue, required, rated, d);
            if (pick instanceof GroupPicker.None none) {
                gauges.starved(queue, Math.max(0, none.starvedWaitSeconds()));
                GroupPicker.Starved saturated = none.saturated();
                if (saturated != null) {
                    warnLimited("starved:" + queue, () -> log.error("锚点已等 {} s（容差 {} 已到顶）仍凑不到候选，队列在饥饿 queue={} anchor={}",
                            saturated.waitSeconds(), points(saturated.toleranceCenti()), queue, Long.toUnsignedString(saturated.playerId())));
                }
                return;
            }
            GroupPicker.Group group = (GroupPicker.Group) pick;
            // 先把计划建好（构造器校验名单与票号）再弹组：弹出之后就只剩「交给开局管线」这一步，不会留下没人管的 matched 票
            GatherPlan plan = GatherPlan.popped(queue, group.members(), group.tickets());
            PopResult result = pop(queue, plan.ticketRefs(), matchedTtlMs, d);
            if (result instanceof PopResult.Invalid invalid) {
                log.info("弹组被拒：{} 的票据在校验之后变了（取消 / 重排 / 被别的实例弹走），其余人原位不动 queue={} members={}",
                        unsigned(invalid.players()), queue, unsigned(group.members()));
                for (Long playerId : invalid.players()) {
                    if (store.drop(queue, playerId, DropReason.INVALID, null, d)) {
                        metrics.queueDropped(DropReason.INVALID);
                    }
                }
                if (++repicks > MAX_REPICKS) {
                    metrics.queueAnomaly(QueueAnomaly.REPICK_EXHAUSTED);
                    log.warn("同一条队列本轮已重挑 {} 次仍被拒，结束这条队列的本轮 queue={}", MAX_REPICKS, queue);
                    return;
                }
                continue;
            }
            metrics.matchWait(queue.mode(), group.anchorWaitSeconds());
            if (rated) {
                metrics.groupRatingSpread(queue.mode(), group.spreadCenti());
            }
            log.info("成组 queue={} wait={}s tol={} spread={} members={} ratings={} matched_ttl={}s", queue, group.anchorWaitSeconds(),
                    points(group.toleranceCenti()), points(group.spreadCenti()), unsigned(group.members()), group.ratingsCenti(),
                    matchedTtlMs / 1000);
            // gather 是多跳 RPC，跑在它自己的虚拟线程上，不占凑单锁；结果（置 ready / 回队首 / 删票、指标）都由管线自己收尾
            gather.launch(plan);
        }
    }

    /** 取快照与各人票据，挑一组。 */
    private GroupPicker.Pick pick(QueueRef queue, int required, boolean rated, Deadline d) {
        QueueSnapshot snapshot = store.snapshot(queue, SCAN_LIMIT, d);
        Set<Long> playerIds = new LinkedHashSet<>();
        for (SnapshotEntry entry : snapshot.entries()) {
            if (entry.playerId() != 0) {
                playerIds.add(entry.playerId());
            }
        }
        Map<Long, Ticket> tickets = playerIds.isEmpty() ? Map.of() : store.readAll(playerIds, d);
        return new GroupPicker(queue, required, rated, snapshot, tickets, tolerance, new QueueGate(queue, d)).pick();
    }

    /**
     * 原子弹组。应答丢了（结局不明）时用同一个标记重发一次：第一次其实已生效的话，存储按标记回「重放」。
     * 第二次仍失败就把异常抛给上层——这条队列的本轮到此为止，可能已弹出的票据按 matched TTL 自愈（同「实例在 gather 中崩溃」）。
     */
    private PopResult pop(QueueRef queue, List<TicketRef> members, long matchedTtlMs, Deadline d) {
        String popToken = UUID.randomUUID().toString();
        try {
            return store.pop(queue, popToken, members, matchedTtlMs, d);
        } catch (Deadline.DependencyException e) {
            if (d.expired()) {
                throw e;
            }
            log.warn("弹组结局不明，用同一个标记重发一次 queue={} members={}", queue, members.stream().map(TicketRef::playerId).toList(), e);
            return store.pop(queue, popToken, members, matchedTtlMs, d);
        }
    }

    /** 成员校验里要做 I/O 的那一半（见类注释「成员校验」）。 */
    private final class QueueGate implements GroupPicker.Gate {

        private final QueueRef queue;
        private final Deadline d;

        QueueGate(QueueRef queue, Deadline d) {
            this.queue = queue;
            this.d = d;
        }

        @Override
        public boolean admit(long playerId, Ticket ticket) {
            if (players.inBattle(playerId, d)) {
                // 排队期间经切磋等入口进了别的战斗
                log.info("队列成员已在战斗中，出局删票 queue={} player={}", queue, Long.toUnsignedString(playerId));
                drop(playerId, DropReason.IN_BATTLE, ticket.ticketId());
                return false;
            }
            HolderRead location = players.location(playerId, d);
            switch (location.status()) {
                case ONLINE -> {
                    return true;
                }
                case RECONNECT_LEASE -> {
                    // 掉线重连中：本轮不带他，票据与队列位置都保留
                    log.debug("队列成员在重连租约中，本轮跳过 queue={} player={}", queue, Long.toUnsignedString(playerId));
                    return false;
                }
                case LOGGED_OUT, MISSING -> {
                    log.info("队列成员已离线（位置 {}），出局删票 queue={} player={}", location.status(), queue, Long.toUnsignedString(playerId));
                    drop(playerId, DropReason.OFFLINE, ticket.ticketId());
                    return false;
                }
                default -> throw new Deadline.DependencyException("读位置记录失败: " + location.detail());
            }
        }

        @Override
        public void evict(SnapshotEntry entry) {
            if (entry.playerId() == 0) {
                log.error("队列 {} 出现非法成员 '{}'，摘掉", queue, entry.member());
                if (store.dropMalformed(queue, entry.member(), d)) {
                    metrics.queueDropped(DropReason.INVALID);
                }
                return;
            }
            log.info("摘掉无效的队列成员（票据缺失、不是 queued 或不属于这条队列） queue={} player={}", queue, Long.toUnsignedString(entry.playerId()));
            drop(entry.playerId(), DropReason.INVALID, null);
        }

        @Override
        public void missingScore(long playerId) {
            metrics.queueAnomaly(QueueAnomaly.MISSING_SCORE);
            warnLimited("missing-score:" + queue, () -> log.warn("队列成员在评分镜像里没有分，按票里的评分用（不回写） queue={} player={}", queue,
                    Long.toUnsignedString(playerId)));
        }

        private void drop(long playerId, DropReason reason, String seenTicketId) {
            if (store.drop(queue, playerId, reason, seenTicketId, d)) {
                metrics.queueDropped(reason);
            }
        }
    }

    // ================================================================ gauge 与告警

    /**
     * 一轮里要写的两个 gauge。按<b>净化后的标签</b>聚合再写：副本号不在表里的队列共用 {@code config="other"} 一个标签，逐条队列直接写会互相覆盖。
     * 深度求和、饥饿秒数取最大；抢不到锁的队列只占个位（贡献 0）。
     */
    private final class Gauges {

        private final Map<String, QueueRef> touched = new LinkedHashMap<>();
        private final Map<String, Long> depths = new HashMap<>();
        private final Map<String, Long> starved = new HashMap<>();

        void notHeld(QueueRef queue) {
            touch(queue);
        }

        void depth(QueueRef queue, long depth) {
            depths.merge(touch(queue), depth, Long::sum);
        }

        void starved(QueueRef queue, long seconds) {
            starved.merge(touch(queue), seconds, Math::max);
        }

        private String touch(QueueRef queue) {
            String key = MetricLabels.mode(queue.mode()) + '\0' + labels.config(queue.configId());
            touched.putIfAbsent(key, queue);
            return key;
        }

        /** @param fullRound 这是看全了注册集的一整轮：以前报过、这一轮没碰到的标签归零 */
        void flush(boolean fullRound) {
            touched.forEach((key, queue) -> {
                metrics.queueDepth(queue.mode(), queue.configId(), depths.getOrDefault(key, 0L));
                metrics.starvedAnchorWait(queue.mode(), queue.configId(), starved.getOrDefault(key, 0L));
            });
            if (fullRound) {
                List<String> gone = new ArrayList<>();
                reportedLabels.forEach((key, queue) -> {
                    if (!touched.containsKey(key)) {
                        metrics.queueDepth(queue.mode(), queue.configId(), 0);
                        metrics.starvedAnchorWait(queue.mode(), queue.configId(), 0);
                        gone.add(key);
                    }
                });
                gone.forEach(reportedLabels::remove);
            }
            reportedLabels.putAll(touched);
        }
    }

    /** 同一个键每 10 s 至多告警一次。 */
    private void warnLimited(String key, Runnable warn) {
        long now = nanoClock.getAsLong();
        Long last = lastWarnNanos.get(key);
        if (last != null && now - last < WARN_INTERVAL_NANOS) {
            return;
        }
        lastWarnNanos.put(key, now);
        warn.run();
    }

    /** centi → 评分点的日志形态（无穷大写作 inf）。 */
    private static String points(long centi) {
        return centi == Tolerance.UNBOUNDED ? "inf" : Long.toString(centi / 100);
    }

    private static List<String> unsigned(List<Long> playerIds) {
        return playerIds.stream().map(Long::toUnsignedString).toList();
    }
}
