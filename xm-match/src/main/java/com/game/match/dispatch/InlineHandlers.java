package com.game.match.dispatch;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.support.MatchTips;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.WatchBattleRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 不涉及任何 I/O、在 Dubbo 线程上当场回的四个号（match-spec §8.1 末四行、§8.6、§9.9 第 3 步）：
 * <ul>
 *   <li><b>156 / 154 上行</b>：这两个号本来是 match 推给客户端的（切磋邀请 / 切磋结果）；客户端把它们当请求发上来是空操作，回 Empty、tip 0——
 *       gate 对应答类型是 Empty 的号不回包（基线 {@code chl.go:325-334}）。</li>
 *   <li><b>163 WatchBattle</b>（6.4 临时，M22）：in-band {@code error_message{1006}}「该功能当前不可用」，不带 parameters。</li>
 *   <li><b>164 ListWatchableBattles</b>（6.4 临时）：空列表——合法的「当前没有可观战的战斗」。</li>
 * </ul>
 * 四个号都<b>不看会话有没有绑定玩家</b>（§8.1「会话没绑定玩家」一列是「同左」），但<b>照样解析请求体</b>：解析失败由派发器回信封 1003，
 * 与其余六个号一致。6.5 接观战真语义时删掉这里的 163 / 164 两个 bean、换成自己的处理器（那两个不再是 inline：要读 Redis、调 battle）。
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

    /** 163 的临时应答（6.5 替换）。 */
    @Bean
    public MatchMethodHandler watchBattlePlaceholderHandler() {
        return new Inline(MatchMethods.WATCH_BATTLE, WatchBattleRequest.parser(), MatchMethodHandler.Reply.body(MatchTips.watchUnavailable()));
    }

    /** 164 的临时应答（6.5 替换）：全默认值的应答 = 0 字节 = 空列表；应答类型不是 Empty，gate 照常回包。 */
    @Bean
    public MatchMethodHandler listWatchableBattlesPlaceholderHandler() {
        return new Inline(MatchMethods.LIST_WATCHABLE_BATTLES, ListWatchableBattlesRequest.parser(),
                MatchMethodHandler.Reply.body(ListWatchableBattlesResponse.getDefaultInstance()));
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

        /** inline 的处理器不进工作池，派发器不会调到这里；万一被调到，没有 in-band 错误字段可用的三个号只能回信封，163 也按信封回。 */
        @Override
        public Reply onOverload() {
            return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }
}
