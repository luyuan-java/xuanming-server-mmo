package com.game.match.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 当场回的四个号（match-spec §8.1 末四行、§8.6）：156 / 154 上行是空操作；163 回 in-band 1006、164 回空列表（6.4 临时，M22）。
 * 应答逐字节钉住；会话有没有绑定玩家都一样；请求体仍按契约的请求类型解析，解析失败把异常交给派发器（→ 信封 1003）。
 */
class InlineHandlersTest {

    private static final SessionContext PLAYER = SessionContext.newBuilder().setGateNodeId(1).setSessionId(7).setPlayerId(1001).setAccount("acc").build();
    private static final SessionContext NOT_IN_GAME = SessionContext.newBuilder().setGateNodeId(1).setSessionId(8).build();
    /** 字段 1 声明成长度前缀、长度却超出剩余字节：任何消息类型都解析不了。 */
    private static final ByteString TRUNCATED = ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01});

    private final InlineHandlers handlers = new InlineHandlers();
    private final MatchMethodHandler invite = handlers.notifyChallengeInviteUplinkHandler();
    private final MatchMethodHandler result = handlers.notifyChallengeResultUplinkHandler();
    private final MatchMethodHandler watch = handlers.watchBattlePlaceholderHandler();
    private final MatchMethodHandler list = handlers.listWatchableBattlesPlaceholderHandler();

    private static ByteString bytes(Reply reply) {
        assertThat(reply).isInstanceOf(Reply.Body.class);
        return ((Reply.Body) reply).bytes();
    }

    @Test
    void 四个处理器各管一个方法_都是inline() {
        assertThat(invite.method()).isEqualTo(MatchMethods.NOTIFY_CHALLENGE_INVITE);
        assertThat(result.method()).isEqualTo(MatchMethods.NOTIFY_CHALLENGE_RESULT);
        assertThat(watch.method()).isEqualTo(MatchMethods.WATCH_BATTLE);
        assertThat(list.method()).isEqualTo(MatchMethods.LIST_WATCHABLE_BATTLES);
        assertThat(List.of(invite, result, watch, list)).allSatisfy(handler -> assertThat(handler.inline()).isTrue());
    }

    @Test
    void 上行的156与154是空操作_应答体0字节() throws Exception {
        ByteString inviteBody = ChallengeInviteS2C.newBuilder().setChallengeId(9).setChallengerId(1002).setChallengerName("x").build().toByteString();
        ByteString resultBody = ChallengeResultS2C.newBuilder().setChallengeId(9).setAccepted(true).setResponderId(1003).build().toByteString();

        assertThat(bytes(invite.handle(PLAYER, inviteBody, Deadline.after(1000))).isEmpty()).isTrue();
        assertThat(bytes(result.handle(PLAYER, resultBody, Deadline.after(1000))).isEmpty()).isTrue();
        assertThat(bytes(invite.handle(PLAYER, ByteString.EMPTY, Deadline.after(1000))).isEmpty()).as("空请求体也是合法的消息").isTrue();
    }

    @Test
    void 观战163回in_band的1006_不带parameters_不带battle_id() throws Exception {
        ByteString reply = bytes(watch.handle(PLAYER, WatchBattleRequest.newBuilder().setBattleId(77).build().toByteString(), Deadline.after(1000)));

        WatchBattleResponse expected = WatchBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1006)).build();
        assertThat(reply).as("逐字节").isEqualTo(expected.toByteString());
        WatchBattleResponse parsed = WatchBattleResponse.parseFrom(reply);
        assertThat(parsed.getErrorMessage().getId()).isEqualTo(1006);
        assertThat(parsed.getErrorMessage().getParametersList()).isEmpty();
        assertThat(parsed.getBattleId()).isZero();
        assertThat(bytes(watch.handle(PLAYER, ByteString.EMPTY, Deadline.after(1000)))).as("battle_id = 0（随机观战）同样回 1006").isEqualTo(reply);
    }

    @Test
    void 可观战列表164回空列表_应答体0字节() throws Exception {
        ByteString reply = bytes(list.handle(PLAYER, ListWatchableBattlesRequest.newBuilder().setLimit(20).build().toByteString(), Deadline.after(1000)));

        assertThat(reply.isEmpty()).isTrue();
        assertThat(ListWatchableBattlesResponse.parseFrom(reply).getBattlesList()).isEmpty();
    }

    @Test
    void 会话没绑定玩家_四个号的应答与绑定了的一样() throws Exception {
        for (MatchMethodHandler handler : List.of(invite, result, watch, list)) {
            assertThat(bytes(handler.handle(NOT_IN_GAME, ByteString.EMPTY, Deadline.after(1000))))
                    .as(handler.method()).isEqualTo(bytes(handler.handle(PLAYER, ByteString.EMPTY, Deadline.after(1000))));
        }
    }

    @Test
    void 请求体解析失败_四个号都把异常交给派发器() {
        for (MatchMethodHandler handler : List.of(invite, result, watch, list)) {
            assertThatThrownBy(() -> handler.handle(PLAYER, TRUNCATED, Deadline.after(1000)))
                    .as(handler.method()).isInstanceOf(InvalidProtocolBufferException.class);
        }
    }

    @Test
    void 过载应答是信封1003_虽然派发器不会对inline的处理器调它() {
        for (MatchMethodHandler handler : List.of(invite, result, watch, list)) {
            assertThat(handler.onOverload()).as(handler.method()).isEqualTo(Reply.envelope(1003));
        }
    }
}
