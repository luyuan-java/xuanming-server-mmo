package com.game.match.queue;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.match.MatchProperties;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.JoinOutcome;
import com.game.match.port.PlayerStatusReader;
import com.game.match.rating.RatingReader;
import com.game.match.support.MatchModes;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketHealing;
import com.game.match.ticket.TicketReader;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.HealMode;
import com.game.match.ticket.TicketStore.JoinResult;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.QueueState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 排队的三个客户端入口：排队 157、取消 148、查状态 153（match-spec §2.1–§2.4；基线 {@code joinqueuelogic.go} / {@code cancelqueuelogic.go} /
 * {@code getqueuestatuslogic.go}）。<b>客户端契约逐字节照搬</b>：判定顺序、tip 码与 {@code parameters[0]} 的中文串、五态映射、恒为 0 的
 * {@code estimated_wait_seconds}。
 *
 * <p><b>身份只取会话里的 player_id</b>（调用方传进来的 {@code playerId}），请求体里的 player_id 一律忽略（M3，修基线 B1）。
 *
 * <p><b>157 的判定顺序</b>（顺序本身就是语义，§2.2 的表；每个出口记一笔 {@code xm_match_join_queue_total{mode, outcome}}）：
 * <ol>
 *   <li>身份为 0 → 16004「缺少玩家身份」（internal）；</li>
 *   <li>PVE_TEAM 且该副本没配组队人数 → 16003（no_team_size）；3V3 / 切磋 / 未指定 / 契约里没有的值 → 16002（mode_not_open）；</li>
 *   <li>读战斗锁出错 → 16004「服务器繁忙,请稍后再试」（internal，fail-closed）；</li>
 *   <li>有战斗锁 → 16000（in_battle）；</li>
 *   <li>读票 / 自愈出错 → 16004（internal）；</li>
 *   <li>自愈之后仍有在途的票 → 16001，带<b>现有</b>票号（already_queued）；</li>
 *   <li>读位置出错 → 16004（internal）；</li>
 *   <li>位置不是在线（重连租约 / 登出墓碑 / 没有记录）→ 16020（not_in_scene）；</li>
 *   <li>建票出错 → 16004（internal）；结局不明，用剩余的请求预算按本次票号尽力回滚；</li>
 *   <li>建票时发现已有别的票（并发的重复排队，后到的一方）→ 16001，带赢家的票号（already_queued）。</li>
 * </ol>
 * 自愈（第 5 步）先于读位置（第 7 步）：即使随后回 16020，残留的 ready / 孤儿票也已经被清掉。
 *
 * <p><b>Java 加在第 8 步之后、建票之前的三道闸</b>（不改变 1–8 步的先后）：
 * <ul>
 *   <li>发号租约<b>已丢失</b>（不会自愈，只能重启）→ 任何模式都回 16004「服务器繁忙,请稍后再试」（internal），不建票：凑单已停，票入队后永不成局
 *       （lead 裁决 2）；</li>
 *   <li>PVE_SOLO 且租约此刻不能发号（含续期滞后）→ 16004（internal）；PVE_SOLO 且 gather 在途许可已满 → 16004（overloaded）：不然会
 *       「回 0、建票、gather 第 1 步秒败、静默删票」（M28、M13）。</li>
 * </ul>
 *
 * <p><b>建票</b>：PVE_SOLO 直接建 matched 票（TTL 按 1 人的公式，42 s）、不入队，然后把 gather 交出去、不等结果——gather 失败只删票，不推送任何东西。
 * 其余模式先读评分（读不到按 1500，不拒绝），再一段脚本完成「建 queued 票 + 登记注册集 + 写评分镜像 + 入队尾」；zone 取自位置记录。
 * {@code battle_config_id} 不校验（照搬基线，客户端可见）：每个不同的值一条队列。
 *
 * <p><b>148 / 153 没有 in-band 错误字段</b>：读票或删票出错时本类把 {@link Deadline.DependencyException} 原样抛出，由处理器翻成信封 1003。
 * 16005 / 16006 基线从不发出，这里同样不发：取消太迟、票号不符都是静默成功。
 *
 * <p>阻塞（等 Redis，评分读还要等库），在 {@code match-worker} 上调；线程安全，无本地状态。
 */
public final class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    /** 取消排队的结局。客户端一律看到「成功」（不回包），区别只进日志与测试。 */
    public enum CancelOutcome {
        /** 会话没有绑定玩家。 */
        NO_IDENTITY,
        /** 没有票（从未排队、已取消、已过期、已被开局收尾）。 */
        NO_TICKET,
        /** 请求带了票号，但不是当前这张：客户端拿着旧票，不动现在的排队。 */
        TICKET_MISMATCH,
        /** 读到的票已是 matched / ready（或状态不认识）：取消太迟，凑单或开局已在途。 */
        TOO_LATE,
        /** 读到时还是 queued，删的时候已经被弹走或被替换：同样是太迟。 */
        RACED,
        /** 票已删、队列项已摘。 */
        CANCELLED
    }

    private final MatchProperties props;
    private final PlayerStatusReader players;
    private final TicketStore tickets;
    private final TicketHealing healing;
    private final RatingReader ratings;
    private final GatherLauncher gather;
    private final MatchIds ids;
    private final MatchMetrics metrics;
    private final Supplier<String> ticketIds;

    /** 生产装配：票号是 UUIDv4（小写带连字符，同基线 {@code uuid.New().String()}）。 */
    public QueueService(MatchProperties props, PlayerStatusReader players, TicketStore tickets, TicketHealing healing, RatingReader ratings,
                        GatherLauncher gather, MatchIds ids, MatchMetrics metrics) {
        this(props, players, tickets, healing, ratings, gather, ids, metrics, () -> UUID.randomUUID().toString());
    }

    /** @param ticketIds 票号来源（测试给确定的序列） */
    public QueueService(MatchProperties props, PlayerStatusReader players, TicketStore tickets, TicketHealing healing, RatingReader ratings,
                        GatherLauncher gather, MatchIds ids, MatchMetrics metrics, Supplier<String> ticketIds) {
        this.props = Objects.requireNonNull(props, "props");
        this.players = Objects.requireNonNull(players, "players");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.healing = Objects.requireNonNull(healing, "healing");
        this.ratings = Objects.requireNonNull(ratings, "ratings");
        this.gather = Objects.requireNonNull(gather, "gather");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ticketIds = Objects.requireNonNull(ticketIds, "ticketIds");
    }

    // ================================================================ 157 JoinQueue

    /**
     * 排队。永不抛依赖异常：一切失败都在应答体里（in-band）。
     *
     * @param playerId 会话里的玩家号（0 = 会话没进游戏）
     * @param request  只用 {@code mode} 与 {@code battle_config_id}；{@code player_id} / {@code map_config_id} / {@code zone_id} 忽略，
     *                 {@code party_member_ids} 已废弃（多于 1 人只记一条日志）
     */
    public JoinQueueResponse join(long playerId, JoinQueueRequest request, Deadline d) {
        int mode = request.getModeValue();
        int configId = request.getBattleConfigId();
        // 1 身份
        if (playerId == 0) {
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.NO_IDENTITY);
        }
        if (request.getPartyMemberIdsCount() > 1) {
            log.info("[match] JoinQueue 携带预组队成员 {} 人，忽略，仅本人入队 player={}", request.getPartyMemberIdsCount(), id(playerId));
        }
        // 2 模式与凑满人数（PVE_TEAM 未配置人数的判定先于「模式未开放」）
        if (mode == MatchModes.PVE_TEAM) {
            if (props.pveTeamSizeFor(configId) == 0) {
                return rejected(mode, JoinOutcome.NO_TEAM_SIZE, MatchTip.JOIN_TEAM_SIZE_NOT_CONFIGURED);
            }
        } else if (!MatchModes.joinable(mode)) {
            return rejected(mode, JoinOutcome.MODE_NOT_OPEN, MatchTip.JOIN_MODE_NOT_OPEN);
        }
        // 3–4 战斗锁（咨询性；读失败按内部错误，不放行）
        boolean locked;
        try {
            locked = players.inBattle(playerId, d);
        } catch (Deadline.DependencyException e) {
            log.error("[match] JoinQueue 查战斗锁失败 player={}: {}", id(playerId), why(e));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        if (locked) {
            return rejected(mode, JoinOutcome.IN_BATTLE, MatchTip.JOIN_IN_BATTLE);
        }
        // 5–6 每人至多一张在途的票；残留的 ready 票与孤儿票在这里清掉（先于读位置）
        TicketHealing.Verdict verdict;
        try {
            verdict = healing.healOrBlock(playerId, d);
        } catch (Deadline.DependencyException e) {
            log.error("[match] JoinQueue 读票 / 自愈失败 player={}: {}", id(playerId), why(e));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        if (verdict instanceof TicketHealing.InFlight inFlight) {
            metrics.joinQueue(mode, JoinOutcome.ALREADY_QUEUED);
            return MatchTips.joinAlreadyQueued(inFlight.ticketId());
        }
        // 7–8 位置 → zone
        HolderRead location;
        try {
            location = players.location(playerId, d);
        } catch (Deadline.DependencyException e) {
            log.error("[match] JoinQueue 读玩家位置失败 player={}: {}", id(playerId), why(e));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        if (location.status() != LocationStatus.ONLINE || location.location() == null) {
            return rejected(mode, JoinOutcome.NOT_IN_SCENE, MatchTip.JOIN_NOT_IN_SCENE);
        }
        int zoneId = location.location().getZoneId();
        // Java 的闸：租约已丢失时凑单永久停摆，任何模式都不再收票
        if (ids.leaseLost()) {
            log.error("[match] JoinQueue 被拒：发号租约已丢失，本进程需要重启 player={}", id(playerId));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        String ticketId = ticketIds.get();
        if (mode == MatchModes.PVE_SOLO) {
            return joinSoloPve(playerId, mode, configId, zoneId, ticketId, d);
        }
        return joinQueued(playerId, mode, configId, zoneId, ticketId, d);
    }

    /** PVE_SOLO：不入队，直接建 matched 票，gather 交出去不等。 */
    private JoinQueueResponse joinSoloPve(long playerId, int mode, int configId, int zoneId, String ticketId, Deadline d) {
        if (!ids.leaseValid()) {
            log.warn("[match] PVE_SOLO 被拒：发号租约此刻无效（续期滞后），不建票 player={}", id(playerId));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        if (gather.availablePermits() <= 0) {
            log.warn("[match] PVE_SOLO 被拒：gather 在途许可已满，不建票 player={}", id(playerId));
            return rejected(mode, JoinOutcome.OVERLOADED, MatchTip.BUSY);
        }
        TicketRef ref = new TicketRef(playerId, ticketId);
        long ttlMs = MatchBudgets.matchedTicketTtlSeconds(1) * 1000L;
        JoinResult result;
        try {
            result = tickets.createMatched(playerId, ticketId, mode, configId, zoneId, RatingReader.DEFAULT_CENTI, ttlMs, d);
        } catch (Deadline.DependencyException e) {
            log.error("[match] JoinQueue 建票失败（结局不明，按本次票号尽力回滚）player={} ticket={}: {}", id(playerId), ticketId, why(e));
            rollback(playerId, ticketId, () -> tickets.delete(ref, d));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        if (result instanceof JoinResult.Exists exists) {
            return duplicate(playerId, mode, exists);
        }
        try {
            gather.launch(GatherPlan.soloPve(configId, playerId, ticketId));
        } catch (RuntimeException e) { // 约定里 launch 不抛；真抛了也不能留下一张没人收尾的 matched 票
            log.error("[match] PVE_SOLO 交出 gather 时出错，删票 player={} ticket={}", id(playerId), ticketId, e);
            rollback(playerId, ticketId, () -> tickets.delete(ref, d));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        log.info("[match] PVE solo 即时开战 player={} zone={} config={} ticket={}", id(playerId), zoneId, Integer.toUnsignedString(configId),
                ticketId);
        metrics.joinQueue(mode, JoinOutcome.OK);
        return MatchTips.joinAccepted(ticketId);
    }

    /** 1V1 / 5V5 / PVE_TEAM：读评分，一段脚本建票并入队。 */
    private JoinQueueResponse joinQueued(long playerId, int mode, int configId, int zoneId, String ticketId, Deadline d) {
        QueueRef queue = new QueueRef(mode, configId);
        long ratingCenti = ratings.loadCentiOrDefault(playerId);
        JoinResult result;
        try {
            result = tickets.enqueue(playerId, ticketId, queue, zoneId, ratingCenti, props.ticketTtl().toMillis(), d);
        } catch (Deadline.DependencyException e) {
            log.error("[match] JoinQueue 入队失败（结局不明，按本次票号尽力回滚）player={} queue={} ticket={}: {}", id(playerId), queue, ticketId,
                    why(e));
            rollback(playerId, ticketId, () -> tickets.cancel(playerId, ticketId, queue, d));
            return rejected(mode, JoinOutcome.INTERNAL, MatchTip.BUSY);
        }
        if (result instanceof JoinResult.Exists exists) {
            return duplicate(playerId, mode, exists);
        }
        log.info("[match] 入队成功 player={} zone={} mode={} config={} required={} rating_centi={} ticket={}", id(playerId), zoneId, mode,
                Integer.toUnsignedString(configId), props.requiredPlayers(mode, configId), ratingCenti, ticketId);
        metrics.joinQueue(mode, JoinOutcome.OK);
        return MatchTips.joinAccepted(ticketId);
    }

    /** 第 10 行：建票时发现已有别的票（同一玩家并发的两条排队里后到的一方）。 */
    private JoinQueueResponse duplicate(long playerId, int mode, JoinResult.Exists exists) {
        log.info("[match] JoinQueue 并发重复入队，后来者拒绝 player={} existing={}", id(playerId), exists.ticketId());
        metrics.joinQueue(mode, JoinOutcome.ALREADY_QUEUED);
        return MatchTips.joinAlreadyQueued(exists.ticketId());
    }

    private JoinQueueResponse rejected(int mode, JoinOutcome outcome, MatchTip tip) {
        metrics.joinQueue(mode, outcome);
        return MatchTips.joinRejected(tip);
    }

    /** 建票结局不明之后的尽力回滚：按本次票号条件删（没写成就什么都不删）；回滚自己失败只记日志，票靠 TTL 与下一次排队的自愈收尾。 */
    private static void rollback(long playerId, String ticketId, Runnable undo) {
        try {
            undo.run();
        } catch (Deadline.DependencyException e) {
            log.error("[match] JoinQueue 回滚本次票据失败（靠 TTL / 下次排队自愈）player={} ticket={}: {}", id(playerId), ticketId, why(e));
        }
    }

    /** 依赖故障的一行描述：带上底层原因（Redis 的报错 / 超时），不打整个栈——Redis 故障期间每个请求都会走到这里。 */
    static String why(Deadline.DependencyException e) {
        Throwable cause = e.getCause();
        return cause == null ? e.getMessage() : e.getMessage() + " <- " + cause;
    }

    // ================================================================ 148 CancelQueue

    /**
     * 取消排队（基线 {@code cancel.go:38-89}）。客户端对每一种结局看到的都是「成功」（应答 Empty，不回包）。
     *
     * @param requestedTicket 请求里的 {@code queue_ticket}；<b>空串 = 取消我当前那张票</b>
     * @throws Deadline.DependencyException 读票或删票出错（处理器回信封 1003）
     */
    public CancelOutcome cancel(long playerId, String requestedTicket, Deadline d) {
        if (playerId == 0) {
            return CancelOutcome.NO_IDENTITY;
        }
        Optional<Ticket> existing = tickets.read(playerId, d);
        if (existing.isEmpty()) {
            return CancelOutcome.NO_TICKET;
        }
        Ticket ticket = existing.get();
        if (requestedTicket != null && !requestedTicket.isEmpty() && !requestedTicket.equals(ticket.ticketId())) {
            log.info("[match] CancelQueue 票据不匹配，忽略 player={} req={} cur={}", id(playerId), requestedTicket, ticket.ticketId());
            return CancelOutcome.TICKET_MISMATCH;
        }
        if (ticket.state() != TicketState.QUEUED) {
            log.info("[match] CancelQueue 太迟，状态={} player={}", ticket.state(), id(playerId));
            return CancelOutcome.TOO_LATE;
        }
        // 出队用票里记下的队列键，不按 (mode, config) 重算。队列键不是规范形的 queued 票只可能来自人为改数据：它进不了任何队列，
        // 按孤儿票的条件删（票号一致、仍是 queued、队列键没变）。
        Optional<QueueRef> queue = QueueRef.ofQueueKey(ticket.queueKey());
        boolean deleted = queue.isPresent() ? tickets.cancel(playerId, ticket.ticketId(), queue.get(), d)
                : tickets.heal(playerId, ticket, HealMode.ORPHAN, d);
        if (!deleted) {
            log.info("[match] CancelQueue 太迟，票据已在读后被弹出或替换 player={} ticket={}", id(playerId), ticket.ticketId());
            return CancelOutcome.RACED;
        }
        log.info("[match] 取消排队成功 player={} mode={} config={} queue={}", id(playerId), ticket.mode(),
                Integer.toUnsignedString(ticket.configId()), ticket.queueKey());
        return CancelOutcome.CANCELLED;
    }

    // ================================================================ 153 GetQueueStatus

    /**
     * 查排队状态（基线 {@code status.go:35-79}）：五态映射里只会回 QUEUED / MATCHED / READY / NOT_QUEUED，{@code ENTERING} 与
     * {@code UNSPECIFIED} 永不回给客户端；{@code estimated_wait_seconds} 恒为 0；{@code queued_seconds} 用读票那一刻的 Redis 时间减入队时刻、
     * 夹到 ≥ 0（M7，修基线 B5 的下溢），回队首不重置。
     *
     * @throws Deadline.DependencyException 读票出错（处理器回信封 1003）
     */
    public GetQueueStatusResponse status(long playerId, Deadline d) {
        if (playerId == 0) {
            return notQueued();
        }
        TicketReader.Status read = tickets.status(playerId, d);
        if (read.ticket().isEmpty()) {
            return notQueued();
        }
        Ticket ticket = read.ticket().get();
        QueueState state = switch (ticket.state()) {
            case QUEUED -> QueueState.QUEUE_STATE_QUEUED;
            case MATCHED -> QueueState.QUEUE_STATE_MATCHED;
            case READY -> QueueState.QUEUE_STATE_READY;
            case UNKNOWN -> {
                log.error("[match] 未知 ticket 状态 player={} ticket={}，按 NOT_QUEUED 返回", id(playerId), ticket.ticketId());
                yield QueueState.QUEUE_STATE_NOT_QUEUED;
            }
        };
        long queuedSeconds = 0;
        if (ticket.enqueuedAtMs() > 0) {
            long elapsedMs = read.redisNowMs() - ticket.enqueuedAtMs();
            if (elapsedMs > 0) {
                queuedSeconds = Math.min(elapsedMs / 1000, 0xFFFF_FFFFL);
            }
        }
        // estimated_wait_seconds 不设（恒为 0）：等 mmorpg backlog D-11 定了两版同批改
        return GetQueueStatusResponse.newBuilder().setState(state).setQueuedSeconds((int) queuedSeconds).build();
    }

    private static GetQueueStatusResponse notQueued() {
        return GetQueueStatusResponse.newBuilder().setState(QueueState.QUEUE_STATE_NOT_QUEUED).build();
    }

    private static String id(long playerId) {
        return Long.toUnsignedString(playerId);
    }
}
