package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.dispatch.MatchMethods;
import com.game.match.lifecycle.InflightWatches;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.proto.AddObserverRequest;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 163、观众 RPC 与在途口的装配。<b>先行件阶段钉的是三个占位</b>：163 仍是 6.4 的临时应答（in-band 1006，逐字节；M22 还开着）、观众 RPC 一律
 * 「没送达」、在途口恒为空闲。W2 把占位换成真实现时，这个文件随之改成对真装配的断言（163 的真语义在 {@code WatchBattleServiceTest} 等）。
 */
class WatchBattleConfigurationTest {

    private static final SessionContext PLAYER = SessionContext.newBuilder().setGateNodeId(1).setSessionId(7).setPlayerId(1001).setAccount("acc").build();
    private static final SessionContext NOT_IN_GAME = SessionContext.newBuilder().setGateNodeId(1).setSessionId(8).build();
    private static final BattlePlacement PLACEMENT = BattlePlacement.newBuilder().setBattleId(77).setRpcHost("127.0.0.1").setRpcPort(21200)
            .setAttempt(1).build();

    private final WatchBattleConfiguration configuration = new WatchBattleConfiguration();
    private final MatchMethodHandler watch = configuration.watchBattleHandler();
    private final ObserverDialer dialer = configuration.observerDialer();
    private final InflightWatches inflight = configuration.inflightWatches();

    private static ByteString bytes(Reply reply) {
        assertThat(reply).isInstanceOf(Reply.Body.class);
        return ((Reply.Body) reply).bytes();
    }

    @Test
    void 占位的163_管的是WatchBattle_当场回_不带自己的执行器() {
        assertThat(watch.method()).isEqualTo(MatchMethods.WATCH_BATTLE);
        assertThat(watch.inline()).isTrue();
        assertThat(watch.executor()).isNull();
        assertThat(watch.onOverload()).isEqualTo(Reply.envelope(1003));
    }

    @Test
    void 占位的163_回in_band的1006_不带parameters_不带battle_id_与6_4逐字节相同() throws Exception {
        ByteString reply = bytes(watch.handle(PLAYER, WatchBattleRequest.newBuilder().setBattleId(77).build().toByteString(), Deadline.after(1000)));

        WatchBattleResponse expected = WatchBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1006)).build();
        assertThat(reply).as("逐字节").isEqualTo(expected.toByteString());
        assertThat(reply.toByteArray()).as("字段 2 = TipInfoMessage{id = 1006}").isEqualTo(new byte[] {0x12, 0x03, 0x08, (byte) 0xEE, 0x07});
        WatchBattleResponse parsed = WatchBattleResponse.parseFrom(reply);
        assertThat(parsed.getErrorMessage().getParametersList()).isEmpty();
        assertThat(parsed.getBattleId()).isZero();
        assertThat(bytes(watch.handle(PLAYER, ByteString.EMPTY, Deadline.after(1000)))).as("battle_id = 0（随机观战）同样回 1006").isEqualTo(reply);
        assertThat(bytes(watch.handle(NOT_IN_GAME, ByteString.EMPTY, Deadline.after(1000)))).as("不看会话有没有绑定玩家").isEqualTo(reply);
    }

    @Test
    void 占位的163_照样解析请求体_解析失败把异常交给派发器() {
        ByteString truncated = ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01});

        assertThatThrownBy(() -> watch.handle(PLAYER, truncated, Deadline.after(1000))).isInstanceOf(InvalidProtocolBufferException.class);
    }

    @Test
    void 占位的观众RPC_不发任何调用_一律没送达_异步的那个也立刻完成() throws Exception {
        AddObserverRequest request = AddObserverRequest.newBuilder().setBattleId(77).setObserverPlayerId(1001).build();

        Outcome added = dialer.add(PLACEMENT, request, Duration.ofSeconds(3), Deadline.after(3_000));
        Outcome removed = dialer.remove(PLACEMENT, 1001, SpectateRules.REASON_ENTER_GATHER, Duration.ofSeconds(3), Deadline.after(3_000));
        Outcome async = dialer.removeAsync(PLACEMENT, 1001, SpectateRules.REASON_CONCURRENT_QUEUE).get(1, TimeUnit.SECONDS);

        assertThat(added).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(removed).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(async).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(((Outcome.NotDelivered) added).detail()).contains("施工中");
    }

    @Test
    void 占位的在途口_恒为空闲_不等() {
        long startedNanos = System.nanoTime();

        assertThat(inflight.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)).as("没有在途的 163：立即返回").isLessThan(5_000);
    }
}
