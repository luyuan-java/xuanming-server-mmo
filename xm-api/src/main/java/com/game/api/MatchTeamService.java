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
 *   <li><b>预算</b>：前三个方法由调用方把「这一跳肯等多久（毫秒，发出时刻计）」经附件 {@code xm-budget-ms} 带上
 *       （{@link com.game.api.match.MatchRpcAttachments}），提供方以收到时刻 + 预算作本地截止（单调时钟，不跨主机比墙钟）；
 *       缺附件按 {@link com.game.api.match.MatchBudgets#DEFAULT_REQUEST_BUDGET_MS}。调用方每跳的 Dubbo 超时取 min(3 s, 剩余请求预算)，
 *       <b>附件带的就是这个每跳超时</b>，不是整请求的剩余预算：过了每跳超时应答没人收，提供方的截止再晚，迟到的建票就会在调用方
 *       已经判「结果不明」并回滚之后照常写票。</li>
 *   <li><b>{@link #runTeamGather} 是长时间挂起的调用</b>：调用级超时 = {@code TeamMatchCheckReply.lock_ttl_seconds}（5 人 101 s，
 *       大于 gather 加补偿的最坏 91 s），用 Dubbo 的调用级 {@code timeout} 附件设置，引用上的缺省超时不适用于它。</li>
 * </ul>
 */
public interface MatchTeamService {

    /**
     * 副本人数检查 + 逐成员预检（基线第 3、5 步合成一次调用，顺序不变）：先看该副本的组队人数（未配置 → {@code DUNGEON_NOT_OPEN}，
     * 名单超员 → {@code SIZE_EXCEEDED}），再按名单顺序逐人查「在线 → 战斗锁 → 位置 → 排队票据（先自愈）」，第一个不满足的人即为结论。
     * 只读（票据自愈除外：残留的 ready 票 / 孤儿 queued 票会被顺手删掉，同 JoinQueue）。发号租约无效时回 {@code INTERNAL}，不让必败的请求先加锁——
     * 这一条判在人数与成员预检<b>之后</b>：只拦本来会放行的请求，{@code DUNGEON_NOT_OPEN} / {@code SIZE_EXCEEDED} / 三种成员拒绝的可见结果
     * 不因租约而变（比照 JoinQueue 的 PVE_SOLO 前置检查）。工作池过载、排队超预算也回 {@code INTERNAL}。
     * {@code OK} 时附每人的 zone 与开战锁时长；<b>调用方要校验</b> zones 覆盖名单里每个人、{@code lock_ttl_seconds} 在
     * [1, {@link com.game.api.match.MatchBudgets#TEAM_END_MATCH_DEADLINE_SECONDS}]，不满足按传输失败处理。
     */
    CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request);

    /**
     * 按调用方给的每人 ticket id <b>原子</b>建全员的 matched 票（PVE_TEAM，带 team_id，不入队，TTL = matched TTL(n)）：任一人已有别的票据时
     * 什么都不写、回 {@code FAILED} 与名单序第一个冲突者；同一批 id 重发视为重放、回 {@code CREATED}。match 侧 Redis 出错（结果不明）时
     * 先用独立的 3 s 预算按本次 id 逐个删票，再回 {@code FAILED} 与 {@code roster[0]}。
     * {@code EXPIRED} = <b>这次建票没有执行、什么都没写</b>：请求到达时预算已过期、工作池过载 / 排队超预算、发号租约无效、参数不合法
     * （名单空 / 超 5 人 / 含 0 / 重复 / 有人缺票号）——都不是哪名成员的问题，所以不回 {@code FAILED}。
     * <b>调用方在 future 异常完成 / {@code EXPIRED} / {@code UNSPECIFIED} 时一律按结果不明处理并调 {@link #releaseTeamTickets}</b>
     * （传输失败时迟到的建票可能已执行；{@code EXPIRED} 时退票是空操作，统一处理不必区分）。
     */
    CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request);

    /**
     * 建票结果不明时的补偿：按 (player_id, ticket id) 逐个「id 一致才删」，幂等；删不到（已不在、已被别的票替换）不算错。
     * <b>只用于</b> {@link #createTeamTickets} 结果不明、且还没发出 {@link #runTeamGather} 的情形——gather 一旦发出，票据由 gather 自己收尾
     * （成功置 ready、失败删票、对端已死按 matched TTL 自愈），提前删票只会让在打的人查询状态掉成「未排队」。
     * 应答是 Empty，所以这是四个方法里唯一用 <b>future 异常完成</b>表示「没删成」的（传输失败之外，还包括提供方的存储出错、工作池过载）：
     * 调用方只记日志（票据最终按 matched TTL 过期）。玩家号为 0 或票号为空的项被跳过。
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
