package com.game.match.reissue;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.RequestBattleTicketRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;

/**
 * 179 {@code MatchService.RequestBattleTicket} 的入口处理器（match-spec §8.1 的 179 一行）。在 {@code match-worker} 上执行
 * （要读 Redis、要直拨 battle）。应答规则：
 * <ul>
 *   <li>请求体解析失败 → 信封 1003（让异常抛给派发器）；</li>
 *   <li>会话没绑定玩家、依赖故障、战斗不存在、battle 暂不可用 → 都是 in-band（应答体自己的 {@code error_message}），见 {@link BattleTicketReissue}；</li>
 *   <li>工作池满 / 排队超预算 → in-band 16004「服务器繁忙,请稍后再试」（M29）。</li>
 * </ul>
 * 身份只取 {@code session.player_id}（请求体只有 battle_id）。无状态、线程安全。
 */
public final class ReissueHandler implements MatchMethodHandler {

    private static final Reply OVERLOADED = Reply.body(MatchTips.reissueRejected(MatchTip.BUSY));

    private final BattleTicketReissue reissue;

    public ReissueHandler(BattleTicketReissue reissue) {
        this.reissue = Objects.requireNonNull(reissue, "reissue");
    }

    @Override
    public String method() {
        return MatchMethods.REQUEST_BATTLE_TICKET;
    }

    @Override
    public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
        RequestBattleTicketRequest request = RequestBattleTicketRequest.parseFrom(body);
        return Reply.body(reissue.reissue(session.getPlayerId(), request.getBattleId(), deadline));
    }

    @Override
    public Reply onOverload() {
        return OVERLOADED;
    }
}
