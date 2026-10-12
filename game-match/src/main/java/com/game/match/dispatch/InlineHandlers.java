package com.game.match.dispatch;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.support.MatchTips;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengeResultS2C;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 不涉及任何 I/O、在 Dubbo 线程上当场回的两个号（match-spec §8.1 末几行、§9.9 第 3 步）：
 * <ul>
 *   <li><b>156 / 154 上行</b>：这两个号本来是 match 推给客户端的（切磋邀请 / 切磋结果）；客户端把它们当请求发上来是空操作，回 Empty、tip 0——
 *       gate 对应答类型是 Empty 的号不回包（基线 {@code chl.go:325-334}）。</li>
 * </ul>
 * 两个号都<b>不看会话有没有绑定玩家</b>（§8.1「会话没绑定玩家」一列是「同左」），但<b>照样解析请求体</b>：解析失败由派发器回信封 1003，
 * 与其余八个号一致。
 *
 * <p>163 WatchBattle / 164 ListWatchableBattles 在 6.4 期间也在这里（临时应答，M22）；批次 6.5 起它们的处理器归观战包
 * （{@code spectate.WatchBattleConfiguration} / {@code spectate.WatchableConfiguration}），不再是 inline：要读 Redis、调 battle。
 */
@Configuration(proxyBeanMethods = false)
public class InlineHandlers {

    /** 156 上行：空操作。 */
    @Bean
    public MatchMethodHandler notifyChallengeInviteUplinkHandler() {
        return new Inline(MatchMethods.NOTIFY_CHALLENGE_INVITE, ChallengeInviteS2C.parser(), MatchMethodHandler.Reply.empty());
    }

    /** 154 上行：空操作。 */
    @Bean
    public MatchMethodHandler notifyChallengeResultUplinkHandler() {
        return new Inline(MatchMethods.NOTIFY_CHALLENGE_RESULT, ChallengeResultS2C.parser(), MatchMethodHandler.Reply.empty());
    }

    /** 固定应答的 inline 处理器：只校验请求体能按契约的请求类型解析。不可变、线程安全。 */
    static final class Inline implements MatchMethodHandler {

        private final String method;
        private final Parser<?> requestParser;
        private final Reply reply;

        Inline(String method, Parser<?> requestParser, Reply reply) {
            this.method = method;
            this.requestParser = requestParser;
            this.reply = reply;
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
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            requestParser.parseFrom(body);
            return reply;
        }

        /** inline 的处理器不进执行器，派发器不会调到这里；万一被调到，这两个号没有 in-band 错误字段可用，只能回信封。 */
        @Override
        public Reply onOverload() {
            return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }
}
