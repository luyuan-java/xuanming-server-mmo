package com.game.api;

import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 帮会活动开战（基线 gRPC {@code MatchInternal.StartActivityBattle}，{@code proto/match/match_internal.proto:52-54}；match-spec §7.1、§7.2）。
 * Dubbo Triple，group {@link DubboGroups#MATCH}，由 xm-match 提供；唯一合法调用方是 xm-guild（随批次 4.6 接入）。参数与返回值直接用同步来的
 * 契约类（{@code com.game.proto.match.*}，先例 {@link AccountLoginService}）。
 *
 * <p><b>隔离</b>：{@code match_internal.proto} 没有标客户端服务、不在消息号白名单，gate 只调 {@link ClientMessageService#handle}，客户端够不到这里；
 * 调用方鉴权靠 Dubbo 调用方 MAC（{@code XM_DUBBO_SECRET}）。所以没有基线「带会话 metadata 调用回 PermissionDenied」那条分支。
 *
 * <p>契约（调用方必须知道的全部）：
 * <ul>
 *   <li><b>业务拒绝放在 {@code reject} 里</b>，Dubbo 层恒成功：{@code INVALID_ARGUMENT}（名单 1..5 人、不含 0、不重复；{@code battle_config_id ≠ 0}；
 *       上下文非空、kind 是已知值且不是 NONE、四个 id / 期键都不为 0、{@code initiator == members[0]}）；{@code MEMBER_OFFLINE} /
 *       {@code MEMBER_IN_BATTLE} / {@code MEMBER_NOT_READY} 带 {@code offender_player_id}（名单序第一个不满足的人）；其余故障（读 Redis 出错——
 *       <b>含读战斗锁出错</b>、发号失败、预算过期、过载）一律 {@code INTERNAL}、offender = 0。</li>
 *   <li><b>成功</b>（{@code reject = NONE}）：{@code battle_id ≠ 0}，全员的 matched 票已原子建好，gather 已异步启动；gather 之后失败时全员删票、
 *       不回队列，这个 battle_id <b>不会产生结果事件</b>，调用方必须有过期兜底。</li>
 *   <li><b>future 异常完成 = 传输失败</b>：调用方按 {@code INTERNAL} 映射，并负责「战斗可能已开始」的补登记（归 4.6）。引用必须 {@code retries = 0}。</li>
 *   <li><b>预算</b>：调用方把剩余预算（毫秒）经附件 {@code xm-budget-ms} 带上（{@link com.game.api.match.MatchRpcAttachments}）；提供方在读票据之前、
 *       建票之后各检查一次，已过期 → 回滚票据、回 {@code INTERNAL}。缺附件按 {@link com.game.api.match.MatchBudgets#DEFAULT_REQUEST_BUDGET_MS}。</li>
 * </ul>
 */
public interface MatchInternalService {

    CompletableFuture<StartActivityBattleResponse> startActivityBattle(StartActivityBattleRequest request);
}
