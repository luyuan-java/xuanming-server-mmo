package com.game.match.gather;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.PlacementStore;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gather 失败后的补偿（match-spec §3.3 补偿矩阵、§9.6 的 fail 行；基线 {@code fail} 闭包与 {@code failDropRecord}，{@code gather.go:168-197}、
 * {@code :302-306}）。固定顺序，不可调换：
 *
 * <ol>
 *   <li><b>续期</b>（只有凑单入口 {@link FailPolicy#REQUEUE_SURVIVORS}）：把本组还是 matched 的票续成「要发取消的人数 × 3 + 10」秒。matched TTL 只覆盖到
 *       这里为止的链路；逐人取消每人最长 3 s，不续期的话 10 人组会在回队首之前全部过期、整组从队列里消失。续的是全员的票（同基线；
 *       肇事者的票随后就删）。</li>
 *   <li><b>逐人取消备战</b>：已冻结的人，加上备战<b>结局不明</b>的那一位（M14），每人至多 3 s，发回各自备战时用的 scene 端点；
 *       失败只记日志，由 scene 的 reaper 与锁 TTL 收尾。</li>
 *   <li><b>票据</b>，按入口的策略：凑单——肇事者删票，其余幸存者按原相对顺序回队首（没有肇事者时全员回队首，并带 {@code requeue-backoff}
 *       的退避，免得 500 ms 后同一组又被弹出、白冻结一轮，M11）；PVE_SOLO / 整队 / 活动——全员按票号删；切磋——没有票。一律带票号做 CAS。</li>
 *   <li><b>删落点记录</b>（只有已经预写过的出口）：放在最后，不占票据的关键路径；删不掉也无害。</li>
 * </ol>
 *
 * <p><b>不走这里的唯一出口</b>是 {@link GatherOutcome#CREATE_FAILED_ROOM_ALIVE}：房间可能活着，不解冻、不动票据、保留落点记录。
 *
 * <p>每一步的失败都只记日志、继续下一步：票据写失败时票留在 matched（已续期），到期自灭，玩家可以重排；不会串局。
 * 线程：阻塞，在 gather 的虚拟线程上调；不持锁。<b>不抛异常</b>。无状态、线程安全。
 */
public final class Compensation {

    private static final Logger log = LoggerFactory.getLogger(Compensation.class);

    /**
     * 补偿里每个票据写的等待上限：不短于 Redis 客户端一条命令的最坏耗时（缺省约 4.2 s），免得它还在重发、这里先当成失败。
     * 删肇事者票 + 回队首两步合计落在续期余量（10 s）之内。
     */
    static final long TICKET_OP_BUDGET_MS = 4_500;

    /**
     * 要补发取消的一名成员。
     *
     * @param endpoint 他备战时用的 scene 端点
     */
    public record Cancel(long playerId, ScenePreparer.Endpoint endpoint) {

        public Cancel {
            Objects.requireNonNull(endpoint, "endpoint");
        }
    }

    private final TicketStore tickets;
    private final ScenePreparer scenes;
    private final PlacementStore placements;
    private final MatchMetrics metrics;
    private final long queuedTtlMs;
    private final long requeueBackoffMs;

    /**
     * @param queuedTtlMs      回队首的票恢复成的 TTL（{@code xm.match.ticket-ttl}）
     * @param requeueBackoffMs 无肇事者失败时回队首的票的退避（{@code xm.match.requeue-backoff}；0 = 关闭）
     */
    public Compensation(TicketStore tickets, ScenePreparer scenes, PlacementStore placements, MatchMetrics metrics, long queuedTtlMs,
                        long requeueBackoffMs) {
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (queuedTtlMs < 1 || requeueBackoffMs < 0) {
            throw new IllegalArgumentException("票据 TTL 必须为正、退避不能为负: ttl=" + queuedTtlMs + " backoff=" + requeueBackoffMs);
        }
        this.queuedTtlMs = queuedTtlMs;
        this.requeueBackoffMs = requeueBackoffMs;
    }

    /**
     * 跑一次补偿。
     *
     * @param offender   肇事者的玩家号；没有为 0
     * @param cancels    要补发取消的人（名单顺序）：已冻结的 + 备战结局不明的
     * @param battleId   这一局的 battle_id（取消与删记录用；还没发号为 0，此时 {@code cancels} 必为空）
     * @param dropRecord 是否已经预写过落点记录、需要在最后删掉
     */
    public void run(GatherPlan plan, long offender, List<Cancel> cancels, long battleId, boolean dropRecord) {
        String battle = Long.toUnsignedString(battleId);
        List<TicketRef> refs = plan.ticketRefs();
        if (plan.onFail() == FailPolicy.REQUEUE_SURVIVORS) {
            long ttlMs = TimeUnit.SECONDS.toMillis(MatchBudgets.compensationTtlSeconds(cancels.size()));
            try {
                tickets.extendMatched(refs, ttlMs, Deadline.after(TICKET_OP_BUDGET_MS));
            } catch (RuntimeException e) {
                log.error("补偿前给票据续期失败（票据可能先于回队首过期） battle_id={} members={}: {}", battle, ids(plan.members()), e.toString());
            }
        }
        for (Cancel cancel : cancels) {
            scenes.cancel(cancel.playerId(), battleId, cancel.endpoint());
        }
        switch (plan.onFail()) {
            case REQUEUE_SURVIVORS -> requeueSurvivors(plan, offender, refs, battle);
            case DELETE_ALL -> {
                try {
                    tickets.deleteGroup(refs, Deadline.after(TICKET_OP_BUDGET_MS));
                } catch (RuntimeException e) {
                    log.error("gather 失败后删票失败（票据留在 matched，到期自灭） battle_id={} members={}: {}", battle, ids(plan.members()), e.toString());
                }
            }
            case NO_TICKETS -> {
                // 切磋：参战者没有票据
            }
        }
        if (dropRecord) {
            placements.delete(battleId);
        }
    }

    private void requeueSurvivors(GatherPlan plan, long offender, List<TicketRef> refs, String battle) {
        List<TicketRef> survivors = new ArrayList<>(refs.size());
        for (TicketRef ref : refs) {
            if (offender != 0 && ref.playerId() == offender) {
                try {
                    tickets.delete(ref, Deadline.after(TICKET_OP_BUDGET_MS));
                } catch (RuntimeException e) {
                    log.error("删肇事者票据失败（票据留在 matched，到期自灭） battle_id={} player={}: {}", battle, Long.toUnsignedString(offender), e.toString());
                }
            } else {
                survivors.add(ref);
            }
        }
        if (survivors.isEmpty()) {
            return;
        }
        boolean noOffender = offender == 0;
        // 每次补偿一个新标记：Redis 客户端重发这一段脚本时入参不变，存储凭它认出重放——回了队首的人可能在重发到达之前又被弹成 matched，
        // 没有标记的话重发会把正在下一次 gather 里的人再推回队首
        String requeueToken = UUID.randomUUID().toString();
        try {
            int requeued = tickets.requeueFront(plan.queue(), requeueToken, survivors, queuedTtlMs, noOffender ? requeueBackoffMs : 0,
                    Deadline.after(TICKET_OP_BUDGET_MS));
            metrics.requeued(noOffender ? MatchMetrics.RequeueReason.GATHER_NO_OFFENDER : MatchMetrics.RequeueReason.GATHER_OFFENDER, requeued);
            if (requeued != survivors.size()) {
                log.warn("回队首的人数少于幸存者（其余人的票已过期 / 已换 / 已取消） battle_id={} queue={} survivors={} requeued={}", battle, plan.queue(),
                        survivors.size(), requeued);
            }
        } catch (RuntimeException e) {
            log.error("幸存者回队首失败（票据留在 matched，到期自灭） battle_id={} queue={} survivors={}: {}", battle, plan.queue(),
                    ids(survivors.stream().map(TicketRef::playerId).toList()), e.toString());
        }
    }

    static List<String> ids(List<Long> playerIds) {
        return playerIds.stream().map(Long::toUnsignedString).toList();
    }
}
