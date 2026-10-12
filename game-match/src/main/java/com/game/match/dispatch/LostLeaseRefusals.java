package com.game.match.dispatch;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.ChallengeResult;
import com.game.match.metrics.MatchMetrics.ChallengeStage;
import com.game.match.metrics.MatchMetrics.JoinOutcome;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.JoinQueueRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Map;
import java.util.Optional;

/**
 * 发号租约<b>真正丢失</b>（不会自愈，只能重启）期间，客户端的两个「会产生新对局」的入口的拒收应答（lead 裁决 2，match-spec §9.8）：
 * <ul>
 *   <li><b>157 JoinQueue</b>：不拦的话 1V1 / 5V5 / PVE 组队的票照常入队、却永远不会成局（凑单已暂停、发不出 battle_id）；</li>
 *   <li><b>152 ChallengePlayer</b>：发不出 challenge_id，必败。</li>
 * </ul>
 * 一律按各自的「内部错误」口径回（§8.1「依赖故障」一列）：in-band 16004「服务器繁忙,请稍后再试」，指标记 {@code internal}。只保留两条比它更靠前的判定，
 * 与正常路径的可见结果一致：请求体解析失败 → 信封 1003（由派发器翻译）；会话没绑定玩家 → 16004「缺少玩家身份」。
 *
 * <p><b>不受影响的号</b>：148 取消排队、153 查状态、179 补签（玩家手里的票与在打的局要能收尾）；151 应答切磋（拒绝照常；接受之后的开局在 gather
 * 第 1 步发号失败，双方再各收一次 154 false，同 §9.8）；156 / 154 / 163 / 164。整队开战与活动开战走另外两个 Dubbo 接口，各自在入口按
 * {@code MatchIds.leaseValid()}（租约丢失时恒为假）拒，不经这里。
 *
 * <p>放在派发层而不是各处理器里：这是一条全进程的开关，不该靠每个处理器各自记得去查。处理器自己再查一遍也无妨（走不到）。无状态、线程安全。
 */
final class LostLeaseRefusals {

    private final Map<String, MatchMethodHandler> refusals;

    LostLeaseRefusals(MatchMetrics metrics) {
        this.refusals = Map.of(
                MatchMethods.JOIN_QUEUE, new Refusal(MatchMethods.JOIN_QUEUE) {
                    @Override
                    public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
                        JoinQueueRequest request = JoinQueueRequest.parseFrom(body);
                        metrics.joinQueue(request.getModeValue(), JoinOutcome.INTERNAL);
                        return Reply.body(MatchTips.joinRejected(tipFor(session)));
                    }
                },
                MatchMethods.CHALLENGE_PLAYER, new Refusal(MatchMethods.CHALLENGE_PLAYER) {
                    @Override
                    public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
                        ChallengePlayerRequest.parseFrom(body);
                        metrics.challenge(ChallengeStage.INVITE, ChallengeResult.INTERNAL);
                        return Reply.body(MatchTips.challengeRejected(tipFor(session)));
                    }
                });
    }

    /** 这个方法在租约丢失期间的拒收处理器；空 = 不受影响，照常交给它自己的处理器。 */
    Optional<MatchMethodHandler> refusalFor(String method) {
        return Optional.ofNullable(refusals.get(method));
    }

    private static MatchTip tipFor(SessionContext session) {
        return session.getPlayerId() == 0 ? MatchTip.NO_IDENTITY : MatchTip.BUSY;
    }

    /** 拒收处理器的公共部分：当场回（不涉及 I/O），所以不存在过载。 */
    private abstract static class Refusal implements MatchMethodHandler {

        private final String method;

        Refusal(String method) {
            this.method = method;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public boolean inline() {
            return true;
        }

        @Override
        public Reply onOverload() {
            return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }
}
