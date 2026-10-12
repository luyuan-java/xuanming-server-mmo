package com.game.match.challenge;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.ChallengeResult;
import com.game.match.metrics.MatchMetrics.ChallengeStage;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.RespondChallengeRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切磋两个客户端消息号的处理器（match-spec §8.1 的 152 / 151 两行）：只做协议适配——解析请求体、调 {@link ChallengeService}、把应答装进应答体。
 * 都进 {@code match-worker}（要读写 Redis）。应答规则：
 * <ul>
 *   <li>业务拒绝、会话没绑定玩家、依赖故障：in-band {@code error_message}（都在 {@link ChallengeService} 里定）；</li>
 *   <li>请求体解析失败：让 {@link InvalidProtocolBufferException} 抛出，派发器回信封 1003；</li>
 *   <li>过载（工作池满 / 排队超预算）：in-band 16004「服务器繁忙,请稍后再试」（M29）。</li>
 * </ul>
 * 156 / 154 作为上行是空操作，由派发层的 inline 处理器回 Empty，不在这里。
 */
public final class ChallengeHandlers {

    private static final Logger log = LoggerFactory.getLogger(ChallengeHandlers.class);

    private ChallengeHandlers() {
    }

    /** 152 ChallengePlayer。 */
    public static MatchMethodHandler challengePlayer(ChallengeService service, MatchMetrics metrics) {
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return MatchMethods.CHALLENGE_PLAYER;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
                ChallengePlayerRequest request = ChallengePlayerRequest.parseFrom(body);
                try {
                    return Reply.body(service.challenge(session, request, deadline));
                } catch (Deadline.DependencyException e) {
                    // 服务层按约定不会漏出依赖异常；漏了也得回 in-band（信封对 152 是错的应答形状）
                    log.error("[challenge] 152 处理中漏出依赖故障 player={}", Long.toUnsignedString(session.getPlayerId()), e);
                    metrics.challenge(ChallengeStage.INVITE, ChallengeResult.INTERNAL);
                    return Reply.body(MatchTips.challengeRejected(MatchTip.BUSY));
                }
            }

            @Override
            public Reply onOverload() {
                metrics.challenge(ChallengeStage.INVITE, ChallengeResult.OVERLOADED);
                return Reply.body(MatchTips.challengeRejected(MatchTip.BUSY));
            }
        };
    }

    /** 151 RespondChallenge。 */
    public static MatchMethodHandler respondChallenge(ChallengeService service, MatchMetrics metrics) {
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return MatchMethods.RESPOND_CHALLENGE;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
                RespondChallengeRequest request = RespondChallengeRequest.parseFrom(body);
                try {
                    return Reply.body(service.respond(session, request, deadline));
                } catch (Deadline.DependencyException e) {
                    log.error("[challenge] 151 处理中漏出依赖故障 player={}", Long.toUnsignedString(session.getPlayerId()), e);
                    metrics.challenge(ChallengeStage.RESPOND, ChallengeResult.INTERNAL);
                    return Reply.body(MatchTips.respondRejected(MatchTip.BUSY));
                }
            }

            @Override
            public Reply onOverload() {
                metrics.challenge(ChallengeStage.RESPOND, ChallengeResult.OVERLOADED);
                return Reply.body(MatchTips.respondRejected(MatchTip.BUSY));
            }
        };
    }
}
