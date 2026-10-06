package com.game.match.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.Empty;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@code MatchService} 的方法名常量与契约对得上（match-spec §1.4）：10 个方法都在消息号表里、号与规格的表一致、契约里的 {@code MatchService}
 * 没有本类不认识的方法（契约同步后多了方法，这里先失败，提醒给它登记处理器）。
 */
class MatchMethodsTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();

    @Test
    void 十个方法的消息号与规格的表一致() {
        Map<String, Integer> expected = Map.of(
                MatchMethods.JOIN_QUEUE, 157,
                MatchMethods.CANCEL_QUEUE, 148,
                MatchMethods.GET_QUEUE_STATUS, 153,
                MatchMethods.CHALLENGE_PLAYER, 152,
                MatchMethods.RESPOND_CHALLENGE, 151,
                MatchMethods.NOTIFY_CHALLENGE_INVITE, 156,
                MatchMethods.NOTIFY_CHALLENGE_RESULT, 154,
                MatchMethods.WATCH_BATTLE, 163,
                MatchMethods.LIST_WATCHABLE_BATTLES, 164,
                MatchMethods.REQUEST_BATTLE_TICKET, 179);

        assertThat(MatchMethods.ALL).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((method, id) ->
                assertThat(REGISTRY.requireId(MatchMethods.SERVICE, method)).as(method).isEqualTo(id));
    }

    @Test
    void 契约里的MatchService没有常量表之外的方法_都是客户端服务() {
        Set<String> inContract = new TreeSet<>();
        for (MessageMethod method : REGISTRY.all()) {
            if (MatchMethods.SERVICE.equals(method.serviceName())) {
                inContract.add(method.methodName());
                assertThat(method.clientService()).as(method.key() + " 标了客户端服务").isTrue();
            }
        }
        assertThat(inContract).containsExactlyInAnyOrderElementsOf(MatchMethods.ALL);
    }

    @Test
    void 应答类型是Empty的三个号_成功时gate不回包() {
        for (String method : MatchMethods.ALL) {
            MessageMethod contract = REGISTRY.byId(REGISTRY.requireId(MatchMethods.SERVICE, method)).orElseThrow();
            boolean empty = contract.responsePrototype() instanceof Empty;
            assertThat(empty).as(method).isEqualTo(Set.of(MatchMethods.CANCEL_QUEUE, MatchMethods.NOTIFY_CHALLENGE_INVITE,
                    MatchMethods.NOTIFY_CHALLENGE_RESULT).contains(method));
        }
    }

    @Test
    void 请求与应答类型_补签的两条消息在battle包() {
        MessageMethod join = REGISTRY.byId(157).orElseThrow();
        MessageMethod reissue = REGISTRY.byId(179).orElseThrow();

        assertThat(join.requestPrototype()).isInstanceOf(JoinQueueRequest.class);
        assertThat(join.responsePrototype()).isInstanceOf(JoinQueueResponse.class);
        assertThat(reissue.requestPrototype()).isInstanceOf(RequestBattleTicketRequest.class);
        assertThat(reissue.responsePrototype()).isInstanceOf(RequestBattleTicketResponse.class);
    }

    @Test
    void 活动开战不在消息号表里_客户端够不到() {
        assertThat(REGISTRY.idOf("MatchInternal", "StartActivityBattle")).isEmpty();
    }
}
