package com.game.match.ticket;

import java.util.Objects;

/**
 * 一张排队票据（Redis {@code RedisKeys.matchTicket(pid)} 的内存镜像；match-spec §1.3、§9.4）。每个玩家至多一张；不可变。
 *
 * @param ticketId     票号（UUIDv4 小写带连字符；157 回给客户端的 {@code queue_ticket}）。<b>所有写都带它做 CAS</b>：迟到的写盖不到玩家重排之后的新票上
 * @param mode         {@code MatchMode} 的数值
 * @param configId     {@code battle_config_id}（uint32 的位模式）
 * @param state        状态；Redis 里是不认识的值时为 {@link TicketState#UNKNOWN}
 * @param enqueuedAtMs 建票时刻（Redis {@code TIME}，Unix 毫秒）。回队首<b>不重置</b>它：等待时长（容差曲线、153 的 {@code queued_seconds}）从第一次入队算
 * @param zoneId       建票时玩家所在的 zone（取自位置记录）；只作观测，匹配池全局不分 zone
 * @param queueKey     入队时写下的队列键全文；不入队的票（PVE_SOLO、整队、活动）为空串。取消与孤儿自愈按它找队列（{@link QueueRef#ofQueueKey}）
 * @param ratingCenti  建票时读到的评分 × 100（新号 150000）；回队首按它写回评分镜像
 * @param teamId       整队开战建的票带队伍号（只作观测）；其余为 0
 * @param battleId     {@link TicketState#READY} 时有效；其余为 0
 * @param notBeforeMs  这一刻（Redis {@code TIME}）之前不参与凑单（无肇事者的 gather 失败后的退避，M11）；0 = 没有限制
 */
public record Ticket(String ticketId, int mode, int configId, TicketState state, long enqueuedAtMs, int zoneId,
                     String queueKey, long ratingCenti, long teamId, long battleId, long notBeforeMs) {

    public Ticket {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(state, "state");
        queueKey = queueKey == null ? "" : queueKey;
    }

    /** (玩家, 票号) 引用：对这张票做 CAS 写时用。 */
    public TicketRef ref(long playerId) {
        return new TicketRef(playerId, ticketId);
    }

    /** 这张票此刻是否还在退避期内（{@code redisNowMs} 取同一次读回的 Redis 时间）。 */
    public boolean backingOff(long redisNowMs) {
        return notBeforeMs != 0 && Long.compareUnsigned(redisNowMs, notBeforeMs) < 0;
    }
}
