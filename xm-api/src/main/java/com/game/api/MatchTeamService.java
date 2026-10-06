package com.game.api;

import com.game.api.proto.TeamGatherReply;
import com.game.api.proto.TeamGatherRequest;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamTicketsRelease;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.proto.Empty;
import java.util.concurrent.CompletableFuture;

/**
 * 整队开战的跨进程端口（Dubbo Triple，group {@link DubboGroups#MATCH}；xm-match 提供、xm-team 调用；match-spec §7.5、§7.6）。
 * 对应基线同进程的 team → match 端口（{@code go/match/internal/team/service.go:40-60}）。不占消息号、不进客户端白名单；调用方鉴权靠
 * Dubbo 调用方 MAC（{@code XM_DUBBO_SECRET}）。消息见 {@code xm/api/match_control.proto}。
 *
 * <p>xm-team 一次 211（StartTeamMatch）按顺序用到四个方法：{@link #checkTeamMatch} → 提交开战锁 → {@link #createTeamTickets} →
 * 回 STARTING → {@link #runTeamGather}（挂回调）。契约（调用方必须知道的全部）：
 * <ul>
 *   <li><b>不重试</b>：调用方引用必须 {@code retries = 0}（Dubbo 缺省 failover 会重发建票与 gather）。</li>
 *   <li><b>future 异常完成 = 传输失败</b>（超时、断连、鉴权失败、提供方过载或已停机），结局未知；业务结论全部在应答消息里，Dubbo 层恒成功。
 *       应答枚举的 {@code UNSPECIFIED}（字段缺失）一律按传输失败处理，不得读成成功。</li>
 *   <li><b>预算</b>：前三个方法由调用方把「剩余预算（毫秒，发出时刻计）」经附件 {@code xm-budget-ms} 带上
 *       （{@link com.game.api.match.MatchRpcAttachments}），提供方以收到时刻 + 预算作本地截止（单调时钟，不跨主机比墙钟）；
 *       缺附件按 {@link com.game.api.match.MatchBudgets#DEFAULT_REQUEST_BUDGET_MS}。调用方每跳的 Dubbo 超时取 min(3 s, 剩余预算)。</li>
 *   <li><b>{@link #runTeamGather} 是长时间挂起的调用</b>：调用级超时 = {@code TeamMatchCheckReply.lock_ttl_seconds}（5 人 101 s，
 *       大于 gather 加补偿的最坏 91 s），用 Dubbo 的调用级 {@code timeout} 附件设置，引用上的缺省超时不适用于它。</li>
 * </ul>
 */
public interface MatchTeamService {

    /**
     * 副本人数检查 + 逐成员预检（基线第 3、5 步合成一次调用，顺序不变）：先看该副本的组队人数（未配置 → {@code DUNGEON_NOT_OPEN}，
     * 名单超员 → {@code SIZE_EXCEEDED}），再按名单顺序逐人查「在线 → 战斗锁 → 位置 → 排队票据（先自愈）」，第一个不满足的人即为结论。
     * 只读（票据自愈除外：残留的 ready 票 / 孤儿 queued 票会被顺手删掉，同 JoinQueue）。发号租约无效时直接回 {@code INTERNAL}，不让必败的请求先加锁。
     * {@code OK} 时附每人的 zone 与开战锁时长。
     */
    CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request);

    /**
     * 按调用方给的每人 ticket id <b>原子</b>建全员的 matched 票（PVE_TEAM，带 team_id，不入队，TTL = matched TTL(n)）：任一人已有别的票据时
     * 什么都不写、回 {@code FAILED} 与名单序第一个冲突者；同一批 id 重发视为重放、回 {@code CREATED}。match 侧 Redis 出错（结果不明）时
     * 先用独立的 3 s 预算按本次 id 逐个删票，再回 {@code FAILED} 与 {@code roster[0]}；请求到达时预算已过期回 {@code EXPIRED}、不写。
     * <b>调用方在 future 异常完成 / {@code EXPIRED} / {@code UNSPECIFIED} 时必须调 {@link #releaseTeamTickets}</b>（迟到的建票可能已执行）。
     */
    CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request);

    /**
     * 建票结果不明时的补偿：按 (player_id, ticket id) 逐个「id 一致才删」，幂等；删不到（已不在、已被别的票替换）不算错。
     * <b>只用于</b> {@link #createTeamTickets} 结果不明、且还没发出 {@link #runTeamGather} 的情形——gather 一旦发出，票据由 gather 自己收尾
     * （成功置 ready、失败删票、对端已死按 matched TTL 自愈），提前删票只会让在打的人查询状态掉成「未排队」。
     * future 异常完成时调用方只记日志（票据最终按 matched TTL 过期）。
     */
    CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request);

    /**
     * 整队 gather（名单原序、失败全员删票）。提供方收到后立即登记、不占工作线程；<b>future 在 gather 结束时才完成</b>（最长约 91 s）。
     * gather 不受调用方取消 / 超时影响：xm-team 进程退出或这次调用超时，match 照样把 gather 跑完。
     * future 异常完成（match 中途退出、网络分区、调用超时）时调用方按「gather 结果不明」收尾（推 MATCH_FAILED、计 {@code gather_unknown}），
     * 且<b>不得</b>调 {@link #releaseTeamTickets}。
     */
    CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request);
}
