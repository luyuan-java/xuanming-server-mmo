package com.game.match.reissue;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.ReissueResult;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementDialer.Dial;
import com.game.match.placement.PlacementStore;
import com.game.match.proto.BattlePlacement;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RequestBattleTicketResponse;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 战斗票据补签 179（match-spec §4.1、§4.3；基线 {@code requestbattleticketlogic.go:70-142}）。客户端丢了票（冷启动、换设备、握手被拒）之后，
 * 经大厅会话向 match 要一张新的：match 是客户端协议里唯一同时掌握「会话身份」与「房间在哪个 battle 节点」的服务。battle 核对名单并自签，
 * match 不持票据密钥、不复制名单。
 *
 * <p>判定顺序（客户端可见，逐行照搬；文案在 {@link MatchTip}）：
 * <table>
 *   <caption>179 的六行</caption>
 *   <tr><th>#</th><th>条件</th><th>应答</th><th>指标 result</th></tr>
 *   <tr><td>1</td><td>会话没有绑定玩家（只认会话身份，请求体里没有也不该有 player_id）</td><td>16004「缺少玩家身份」</td><td>no_session</td></tr>
 *   <tr><td>2</td><td>读落点记录出错或记录损坏</td><td>16004「服务器繁忙,请稍后再试」</td><td>internal</td></tr>
 *   <tr><td>3</td><td>落点记录不存在</td><td>1005「该战斗不存在或已结束」</td><td>not_found</td></tr>
 *   <tr><td>4</td><td>按<b>记录里的</b>地址直拨 battle，调通了</td><td>battle 的裁决<b>原样透传</b>（成员：新签的 assignment；
 *       非成员或房间不在：1005；签不出：1003）</td><td>ok / rejected</td></tr>
 *   <tr><td>5</td><td>直拨的请求<b>确定没有送达</b>、目录里同号节点已换实例、再探测一次原地址<b>明确连不上</b></td>
 *       <td>1005「该战斗不存在或已结束」</td><td>instance_changed</td></tr>
 *   <tr><td>6</td><td>其余没调通：超时、连上之后断开、目录里没有该号、还是同一个实例、目录读失败、原地址其实连得上或探测没有结论</td>
 *       <td>1003「战斗服务暂不可用」</td><td>rpc_timeout / rpc_error</td></tr>
 * </table>
 *
 * <p><b>1005 只用于「这局确实没了」</b>：客户端只把 1005 判为战斗已不存在并永久放弃本局，其它失败都退避重试（每局最多补签 3 次）。
 * 所以第 5 行要两条正面证据（原地址连不上 + 节点号已被别的进程接手），<b>超时哪怕同号已换实例也只回 1003</b>——丢了租约的 battle
 * 恰好是「可能很慢」的进程，房间可能还活着。「请求没送达」本身还不等于「原地址连不上」（连接刚断、还没重连上时也是没送达），
 * 所以判死前直拨器对原地址再做一次建连探测。第 4–6 行的判定在 {@link PlacementDialer}（与 6.5 的观众 RPC 共用）。
 *
 * <p>线程：阻塞（一次落点读 + 一次至多 3 s 的直拨），在 {@code match-worker} 上调。<b>不抛异常</b>。无状态、线程安全。
 */
public final class BattleTicketReissue {

    private static final Logger log = LoggerFactory.getLogger(BattleTicketReissue.class);

    /** 直拨的超时按剩余请求预算收短时，留给组应答与回程的余量。 */
    static final long REPLY_RESERVE_MS = 200;

    private final PlacementStore placements;
    private final PlacementDialer dialer;
    private final MatchMetrics metrics;

    public BattleTicketReissue(PlacementStore placements, PlacementDialer dialer, MatchMetrics metrics) {
        this.placements = Objects.requireNonNull(placements, "placements");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * 处理一次补签。
     *
     * @param playerId 会话里的玩家号（0 = 会话没进游戏）
     * @param battleId 请求里的 battle_id（不校验：查不到记录就是「不存在」）
     * @param deadline 本次请求的截止
     */
    public RequestBattleTicketResponse reissue(long playerId, long battleId, Deadline deadline) {
        String battle = Long.toUnsignedString(battleId);
        if (playerId == 0) {
            log.warn("[reissue] 补签缺少会话身份 battle_id={}", battle);
            return reject(MatchTip.NO_IDENTITY, ReissueResult.NO_SESSION);
        }
        String player = Long.toUnsignedString(playerId);
        BattlePlacement placement;
        switch (placements.read(battleId, deadline)) {
            case PlacementStore.Read.Failed failed -> {
                log.error("[reissue] 读落点记录失败 player={} battle_id={}: {}", player, battle, failed.why());
                return reject(MatchTip.BUSY, ReissueResult.INTERNAL);
            }
            case PlacementStore.Read.Absent absent -> {
                log.info("[reissue] 没有落点记录（战斗不存在或已结束） player={} battle_id={}", player, battle);
                return reject(MatchTip.REISSUE_BATTLE_GONE, ReissueResult.NOT_FOUND);
            }
            case PlacementStore.Read.Found found -> placement = found.placement();
        }
        String node = Integer.toUnsignedString(placement.getBattleNodeId()) + "(" + placement.getRpcHost() + ":"
                + Integer.toUnsignedString(placement.getRpcPort()) + "#" + placement.getBattleInstanceId() + ")";
        long timeoutMs = Math.min(MatchBudgets.ISSUE_TICKET_TIMEOUT_MS, deadline.remainingMillis() - REPLY_RESERVE_MS);
        if (timeoutMs <= 0) {
            log.warn("[reissue] 请求预算在直拨之前已用完 player={} battle_id={} node={}", player, battle, node);
            return reject(MatchTip.REISSUE_BATTLE_UNAVAILABLE, ReissueResult.RPC_TIMEOUT);
        }
        IssueBattleTicketRequest request = IssueBattleTicketRequest.newBuilder().setBattleId(battleId).setPlayerId(playerId).build();
        Dial<IssueBattleTicketResponse> dial = dialer.dial(placement, Duration.ofMillis(timeoutMs), service -> service.issueBattleTicket(request));
        switch (dial) {
            case Dial.Replied<IssueBattleTicketResponse> replied -> {
                IssueBattleTicketResponse verdict = replied.reply();
                int tip = verdict.getErrorMessage().getId();
                if (tip != 0) {
                    log.info("[reissue] battle 拒签 player={} battle_id={} node={} tip_id={}", player, battle, node, Integer.toUnsignedString(tip));
                    metrics.reissue(ReissueResult.REJECTED);
                } else {
                    log.info("[reissue] 补签成功 player={} battle_id={} node={} role={}", player, battle, node, verdict.getAssignment().getRole());
                    metrics.reissue(ReissueResult.OK);
                }
                // battle 的裁决原样透传：两个字段有就带、没有就不带，match 不改写
                RequestBattleTicketResponse.Builder response = RequestBattleTicketResponse.newBuilder();
                if (verdict.hasErrorMessage()) {
                    response.setErrorMessage(verdict.getErrorMessage());
                }
                if (verdict.hasAssignment()) {
                    response.setAssignment(verdict.getAssignment());
                }
                return response.build();
            }
            case Dial.RoomGone<IssueBattleTicketResponse> gone -> {
                log.info("[reissue] 原 battle 进程连不上且节点号已被别的进程接手，判战斗已不存在 player={} battle_id={} node={}", player, battle, node);
                return reject(MatchTip.REISSUE_BATTLE_GONE, ReissueResult.INSTANCE_CHANGED);
            }
            case Dial.Unavailable<IssueBattleTicketResponse> unavailable -> {
                log.error("[reissue] 直拨 battle 没调通（{}） player={} battle_id={} node={}: {}", unavailable.kind(), player, battle, node,
                        unavailable.detail());
                return reject(MatchTip.REISSUE_BATTLE_UNAVAILABLE,
                        unavailable.kind() == PlacementDialer.Kind.TIMEOUT ? ReissueResult.RPC_TIMEOUT : ReissueResult.RPC_ERROR);
            }
        }
    }

    private RequestBattleTicketResponse reject(MatchTip tip, ReissueResult result) {
        metrics.reissue(result);
        return MatchTips.reissueRejected(tip);
    }
}
