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
        Map<String, Integer> expected = new java.util.HashMap<>(Map.of(
                MatchMethods.JOIN_QUEUE, 157,
                MatchMethods.CANCEL_QUEUE, 148,
                MatchMethods.GET_QUEUE_STATUS, 153,
                MatchMethods.CHALLENGE_PLAYER, 152,
                MatchMethods.RESPOND_CHALLENGE, 151,
                MatchMethods.NOTIFY_CHALLENGE_INVITE, 156,
                MatchMethods.NOTIFY_CHALLENGE_RESULT, 154,
                MatchMethods.WATCH_BATTLE, 163,
                MatchMethods.LIST_WATCHABLE_BATTLES, 164,
                MatchMethods.REQUEST_BATTLE_TICKET, 179));
        expected.put(MatchMethods.GET_NETWORK_PROBE_PLAN,REGISTRY.requireId(MatchMethods.SERVICE,MatchMethods.GET_NETWORK_PROBE_PLAN));
        expected.put(MatchMethods.COMMIT_NETWORK_PROBE,REGISTRY.requireId(MatchMethods.SERVICE,MatchMethods.COMMIT_NETWORK_PROBE));
        expected.put(MatchMethods.LIST_BATTLE_REPORTS, 379);
        expected.put(MatchMethods.GET_BATTLE_REPORT, 380);
        expected.put(MatchMethods.LIST_BATTLE_SCRIPTS, 389);
        expected.put(MatchMethods.SUBMIT_BATTLE_SCRIPT, 390);
        expected.put(MatchMethods.DELETE_BATTLE_SCRIPT, 391);
        expected.put(MatchMethods.SET_BATTLE_SCRIPT_LOADOUT, 392);
        expected.put(MatchMethods.GET_RANKED_PROFILE, 448);
        expected.put(MatchMethods.SAVE_RANKED_PRESET, 449);
        expected.put(MatchMethods.UPDATE_RANKED_COSMETIC, 450);
        expected.put(MatchMethods.GET_RANKED_BATTLE_RESULT, 451);
        for (String tournamentMethod : java.util.List.of(
                MatchMethods.GET_TOURNAMENT_OVERVIEW, MatchMethods.CREATE_TOURNAMENT_SQUAD,
                MatchMethods.INVITE_TOURNAMENT_MEMBER, MatchMethods.RESPOND_TOURNAMENT_INVITE,
                MatchMethods.LEAVE_TOURNAMENT_SQUAD, MatchMethods.UPDATE_TOURNAMENT_LINEUP,
                MatchMethods.REGISTER_TOURNAMENT_SQUAD, MatchMethods.LIST_TOURNAMENT_STANDINGS,
                MatchMethods.START_TOURNAMENT_MATCH, MatchMethods.CHEER_TOURNAMENT_SQUAD,
                MatchMethods.CLAIM_TOURNAMENT_REWARD,
                MatchMethods.SEND_TOURNAMENT_SPECTATE_CHEER,
                MatchMethods.GET_TOURNAMENT_SPECTATE_CHEER,
                MatchMethods.UPDATE_TOURNAMENT_SQUAD_EMBLEM, MatchMethods.START_TOURNAMENT_WARMUP,
                MatchMethods.PREDICT_TOURNAMENT_MATCH, MatchMethods.GET_TOURNAMENT_PREDICTION_BOARD,
                MatchMethods.BAN_TOURNAMENT_LEYLINE, MatchMethods.GET_TOURNAMENT_HIGHLIGHTS,
                MatchMethods.CREATE_JIANGHU_DUEL_INVITE, MatchMethods.CONFIRM_JIANGHU_DUEL_MEMBER,
                MatchMethods.RESPOND_JIANGHU_DUEL_INVITE, MatchMethods.LIST_JIANGHU_GRUDGES,
                MatchMethods.DISMISS_JIANGHU_GRUDGE, MatchMethods.NOTIFY_JIANGHU_DUEL_INVITE,
                MatchMethods.NOTIFY_JIANGHU_DUEL_STATUS,
                MatchMethods.CREATE_TRIAL_CHALLENGE_CARD, MatchMethods.GET_TRIAL_CHALLENGE_CARD,
                MatchMethods.GET_SHARED_TRIAL_REPLAY,
                MatchMethods.LIST_TACTICAL_PUZZLES, MatchMethods.GET_TACTICAL_PUZZLE,
                MatchMethods.SAVE_TACTICAL_PUZZLE_DRAFT, MatchMethods.DELETE_TACTICAL_PUZZLE,
                MatchMethods.START_TACTICAL_PUZZLE_BATTLE, MatchMethods.LIKE_TACTICAL_PUZZLE,
                MatchMethods.GET_TACTICAL_PUZZLE_SOLUTION_REPLAY,
                MatchMethods.SEND_BATTLE_SPECTATE_CHAT, MatchMethods.PULL_BATTLE_SPECTATE_CHAT)) {
            expected.put(tournamentMethod, REGISTRY.requireId(MatchMethods.SERVICE, tournamentMethod));
        }

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
            assertThat(empty).as(method).isEqualTo(Set.of(
                    MatchMethods.CANCEL_QUEUE,
                    MatchMethods.NOTIFY_CHALLENGE_INVITE,
                    MatchMethods.NOTIFY_CHALLENGE_RESULT,
                    MatchMethods.NOTIFY_JIANGHU_DUEL_INVITE,
                    MatchMethods.NOTIFY_JIANGHU_DUEL_STATUS).contains(method));
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
    void 活动开战是内部服务_有消息号也不是客户端服务_客户端够不到() {
        // 契约同步到 mmorpg 73b852f3（批次 0.3）后 message_id.txt 给它发了号；客户端够不着靠的是服务没标客户端协议服务 / 玩家服务
        // （gate 白名单只收客户端服务，MessageRoutesTest 钉住它不可路由），不是「没有号」
        MessageMethod method = REGISTRY.byId(REGISTRY.requireId("MatchInternal", "StartActivityBattle")).orElseThrow();
        assertThat(method.clientService()).as(method.key()).isFalse();
        assertThat(method.playerService()).as(method.key()).isFalse();
        var cancel=REGISTRY.byId(REGISTRY.requireId("MatchInternal","CancelActivityBattle")).orElseThrow();
        assertThat(cancel.clientService()).as(cancel.key()).isFalse();assertThat(cancel.playerService()).as(cancel.key()).isFalse();
    }
}
