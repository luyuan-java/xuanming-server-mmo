package com.game.robot.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.robot.client.FakeGate;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RedirectTarget;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.RedirectFollower.CloseOld;
import com.game.robot.flow.RedirectFollower.Followed;
import com.google.protobuf.ByteString;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 跟随 124 的客户端件，对着两台带握手的假 gate（{@code old} = 推出 124 的那台，{@code new} = 124 指向的那台）。
 * 关旧连接的先后一律按事件判：假 gate 的事件账（握手帧先到还是断开先到）、以及假 gate「压着握手应答，直到看见旧连接断开才回」，
 * 不按隔了多少毫秒判。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RedirectFollowerTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final MessageIds IDS = MessageIds.resolve(REGISTRY);
    private static final int REDIRECT = RedirectTarget.messageId(REGISTRY);
    private static final Duration SHORT = Duration.ofSeconds(3);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final long NOW = 1_760_000_000L;
    /** 票据末尾带一个本地 proto 不认识的字段：跟随件要是解析后重新序列化，到 gate 的字节就变了。 */
    private static final ByteString TICKET = GateTokenPayload.newBuilder().setGateNodeId(1).setZoneId(2).setExpireTimestamp(NOW + 300)
            .setPlayerId(77).setTargetZoneId(2).build().toByteString().concat(ByteString.copyFrom(new byte[] {(byte) 0x98, 0x06, 0x2A}));
    private static final ByteString SIGNATURE = ByteString.copyFromUtf8("cd".repeat(32));

    private final FakeGate.Events events = new FakeGate.Events();
    private final AtomicLong clock = new AtomicLong(NOW);
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final RobotClient client = track(new RobotClient("http://127.0.0.1:1", 1, IDS, REDIRECT, SHORT, SHORT));
    private final RedirectFollower follower = new RedirectFollower(client, RedirectFollower.MAX_HOPS, clock::get);

    /** 后建的先关：连接 → 假 gate → 客户端的 I/O 线程。 */
    @AfterEach
    void stop() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            closeables.get(i).close();
        }
    }

    private <T extends AutoCloseable> T track(T closeable) {
        closeables.add(closeable);
        return closeable;
    }

    /** 握手放行，77 照常应答：连接「可用」的判据就是在它上面发一条 77 能收到应答。 */
    private static class Echo implements FakeGate.Script {
        @Override
        public void onRequest(FakeGate.Session session, ClientRequest request) {
            if (request.getMessageId() == IDS.listSkills()) {
                session.reply(request, ListSkillsResponse.getDefaultInstance());
            }
        }
    }

    /** 握手被拒（回完即关连接）。 */
    private static final class Rejecting extends Echo {
        private final String error;

        Rejecting(String error) {
            this.error = error;
        }

        @Override
        public ClientTokenVerifyResponse onVerify(FakeGate.Session session, ClientTokenVerifyRequest request) {
            return FakeGate.verifyRejected(error);
        }
    }

    /**
     * 新 gate 压着握手应答，直到旧 gate 看见旧连接断开才回。跟随件只有「先关旧连接、再等握手」才等得到应答；
     * 等握手成功才关旧连接的实现在这里只会握手超时。
     */
    private static final class HoldUntilOldCloses {
        private boolean oldClosed;
        private FakeGate.Session waiting;

        synchronized ClientTokenVerifyResponse onVerify(FakeGate.Session session) {
            if (oldClosed) {
                return FakeGate.VERIFY_OK;
            }
            waiting = session;
            return null;
        }

        synchronized void onOldClosed() {
            oldClosed = true;
            if (waiting != null) {
                waiting.completeVerify(FakeGate.VERIFY_OK);
                waiting = null;
            }
        }

        FakeGate.Script oldGate() {
            return new Echo() {
                @Override
                public void onClosed(FakeGate.Session session) {
                    onOldClosed();
                }
            };
        }

        FakeGate.Script newGate() {
            return new Echo() {
                @Override
                public ClientTokenVerifyResponse onVerify(FakeGate.Session session, ClientTokenVerifyRequest request) {
                    return HoldUntilOldCloses.this.onVerify(session);
                }
            };
        }
    }

    private FakeGate gate(String name, FakeGate.Script script) {
        return track(new FakeGate(name, events, script));
    }

    private GameConnection open(FakeGate gate) throws RobotException {
        return track(client.openRaw("127.0.0.1", gate.port()));
    }

    private static RedirectTarget targetOf(int port) {
        return new RedirectTarget("127.0.0.1", port, TICKET, SIGNATURE, NOW + 300);
    }

    private static boolean usable(GameConnection connection) {
        try {
            connection.call(IDS.listSkills(), ListSkillsRequest.getDefaultInstance(), ListSkillsResponse.parser(), SHORT);
            return true;
        } catch (RobotException e) {
            return false;
        }
    }

    /** 一个此刻没人监听的本机端口。 */
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    // ---------------------------------------------------------------- AFTER_VERIFY（缺省）

    @Test
    void 缺省次序_握手成功之后才关旧连接_票据原字节到了目标gate_新连接可用() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        FakeGate newGate = gate("new", new Echo());
        GameConnection old = open(oldGate);

        Followed followed = follower.follow(old, targetOf(newGate.port()));
        track(followed.connection());

        assertThat(events.await("old closed#0", WAIT)).as("旧连接被关了").isTrue();
        assertThat(events.indexOf("new verify#0")).as("目标 gate 先收到握手帧，旧 gate 才看到断开")
                .isNotNegative().isLessThan(events.indexOf("old closed#0"));
        assertThat(followed.oldClosed()).isTrue();
        assertThat(old.isOpen()).isFalse();
        assertThat(followed.verifiedNanos()).isLessThanOrEqualTo(followed.oldClosedNanos());

        ClientTokenVerifyRequest seen = newGate.sessions().get(0).verify();
        assertThat(seen.getPayload()).as("票据原字节，没有被重新序列化").isEqualTo(TICKET);
        assertThat(seen.getSignature()).isEqualTo(SIGNATURE);

        assertThat(followed.gate()).isEqualTo(newGate.endpoint());
        assertThat(followed.target()).isEqualTo(targetOf(newGate.port()));
        assertThat(followed.closeOld()).isEqualTo(CloseOld.AFTER_VERIFY);
        assertThat(followed.hop()).isEqualTo(1);
        assertThat(follower.hops()).isEqualTo(1);
        assertThat(usable(followed.connection())).as("新连接已握手，可以接着登录").isTrue();
        assertThat(newGate.received(IDS.login())).as("跟随件只搬 TCP，不替调用方登录").isZero();
    }

    @Test
    void 缺省次序_目标gate拒绝票据_抛出gate的文案_旧连接保持可用_新连接已关() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        FakeGate newGate = gate("new", new Rejecting("token not for this gate"));
        GameConnection old = open(oldGate);

        assertThatThrownBy(() -> follower.follow(old, targetOf(newGate.port())))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("第 1 跳")
                .hasMessageContaining("127.0.0.1:" + newGate.port())
                .hasMessageContaining("拒绝了重定向票据：success=false error=token not for this gate")
                .hasMessageContaining("旧连接保持打开");

        assertThat(events.await("new closed#0", WAIT)).isTrue();
        assertThat(usable(old)).as("握手没成功，旧连接不能动").isTrue();
        assertThat(events.indexOf("old closed#0")).isNegative();
        assertThat(follower.hops()).as("连过去了就算一跳").isEqualTo(1);
    }

    @Test
    void 缺省次序_握手等不到应答_超时抛出_旧连接保持可用() throws Exception {
        // 新 gate 压着握手应答、要等旧连接断开才回；缺省次序在握手成功之前不关旧连接，所以只会超时
        HoldUntilOldCloses hold = new HoldUntilOldCloses();
        FakeGate oldGate = gate("old", hold.oldGate());
        FakeGate newGate = gate("new", hold.newGate());
        RobotClient impatient = track(new RobotClient("http://127.0.0.1:1", 1, IDS, REDIRECT, SHORT, Duration.ofSeconds(1)));
        RedirectFollower impatientFollower = new RedirectFollower(impatient, RedirectFollower.MAX_HOPS, clock::get);
        GameConnection old = track(impatient.openRaw("127.0.0.1", oldGate.port()));

        assertThatThrownBy(() -> impatientFollower.follow(old, targetOf(newGate.port())))
                .isInstanceOf(RobotException.class).hasMessageContaining("握手没有结果").hasMessageContaining("握手超时")
                .hasMessageContaining("旧连接保持打开");

        assertThat(usable(old)).isTrue();
    }

    @Test
    void 握手成功后晚关旧连接_返回时两条连接都开着_调用方可以提前关() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        FakeGate newGate = gate("new", new Echo());
        GameConnection old = open(oldGate);

        // 延迟给得很长：这条用例里它不会自己到点，关旧连接由下面的 closeOldNow 触发
        Followed followed = follower.follow(old, targetOf(newGate.port()), CloseOld.AFTER_VERIFY, Duration.ofMinutes(5));
        track(followed.connection());

        assertThat(followed.oldClosed()).isFalse();
        assertThat(usable(old)).as("旧连接还开着（真服务端这时已把它置为已重定向，请求不会有回包）").isTrue();
        assertThat(usable(followed.connection())).isTrue();
        assertThat(events.indexOf("old closed#0")).isNegative();

        followed.closeOldNow();

        assertThat(followed.oldClosed()).isTrue();
        assertThat(followed.awaitOldClosed(Duration.ofSeconds(1))).isTrue();
        assertThat(old.isOpen()).isFalse();
        assertThat(followed.verifiedNanos()).isLessThanOrEqualTo(followed.oldClosedNanos());
        assertThat(events.await("old closed#0", WAIT)).isTrue();
        followed.closeOldNow();
        assertThat(usable(followed.connection())).as("再关一次是空操作，新连接不受影响").isTrue();
    }

    @Test
    void 握手成功后晚关旧连接_没人提前关时到点自己关() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        FakeGate newGate = gate("new", new Echo());
        GameConnection old = open(oldGate);

        Followed followed = follower.follow(old, targetOf(newGate.port()), CloseOld.AFTER_VERIFY, Duration.ofMillis(200));
        track(followed.connection());

        assertThat(followed.awaitOldClosed(WAIT)).as("只断言结局：到点之后旧连接被关").isTrue();
        assertThat(old.isOpen()).isFalse();
        assertThat(events.await("old closed#0", WAIT)).isTrue();
        assertThat(events.indexOf("new verify#0")).isLessThan(events.indexOf("old closed#0"));
    }

    // ---------------------------------------------------------------- BEFORE_VERIFY（真实客户端的次序）

    @Test
    void 先关旧连接再握手_目标gate压着握手应答直到旧连接断开才回_跟随成功() throws Exception {
        HoldUntilOldCloses hold = new HoldUntilOldCloses();
        FakeGate oldGate = gate("old", hold.oldGate());
        FakeGate newGate = gate("new", hold.newGate());
        GameConnection old = open(oldGate);

        Followed followed = follower.follow(old, targetOf(newGate.port()), CloseOld.BEFORE_VERIFY, Duration.ZERO);
        track(followed.connection());

        assertThat(followed.closeOld()).isEqualTo(CloseOld.BEFORE_VERIFY);
        assertThat(followed.oldClosed()).isTrue();
        assertThat(old.isOpen()).isFalse();
        assertThat(followed.oldClosedNanos()).as("关完旧连接在前，看到握手成功在后").isLessThan(followed.verifiedNanos());
        assertThat(events.indexOf("old closed#0")).as("握手应答是旧 gate 看见断开之后才发的").isNotNegative();
        assertThat(newGate.sessions().get(0).verify().getPayload()).isEqualTo(TICKET);
        assertThat(usable(followed.connection())).isTrue();
    }

    @Test
    void 先关旧连接再握手_票据被拒_旧连接已经没了_文案写明这次会话作废() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        FakeGate newGate = gate("new", new Rejecting("token not for this zone"));
        GameConnection old = open(oldGate);

        assertThatThrownBy(() -> follower.follow(old, targetOf(newGate.port()), CloseOld.BEFORE_VERIFY, Duration.ZERO))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("error=token not for this zone")
                .hasMessageContaining("旧连接已按 BEFORE_VERIFY 关闭，这次会话作废");

        assertThat(old.isOpen()).isFalse();
        assertThat(events.await("old closed#0", WAIT)).isTrue();
        assertThat(events.await("new closed#0", WAIT)).isTrue();
    }

    // ---------------------------------------------------------------- 不碰网络就拒绝的两种

    @Test
    void 每个登录会话至多三跳_第四跳直接拒绝_不去连目标() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        FakeGate newGate = gate("new", new Echo());
        GameConnection current = open(oldGate);
        for (int hop = 1; hop <= RedirectFollower.MAX_HOPS; hop++) {
            Followed followed = follower.follow(current, targetOf(newGate.port()));
            assertThat(followed.hop()).isEqualTo(hop);
            current = track(followed.connection());
        }
        assertThat(follower.hops()).isEqualTo(3);
        GameConnection third = current;

        // 第四跳的目标是一个没人监听的端口：真去连的话报的是「连不上」，这里必须是熔断
        int nowhere = closedPort();
        assertThatThrownBy(() -> follower.follow(third, targetOf(nowhere)))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("已经跟随了 3 次重定向（上限 3）")
                .hasMessageContaining("127.0.0.1:" + nowhere);

        assertThat(follower.hops()).as("被熔断拒掉的不算一跳").isEqualTo(3);
        assertThat(usable(third)).as("旧连接不动").isTrue();
        assertThat(newGate.sessions()).hasSize(3);
    }

    @Test
    void 目标本地校验不过_不跟随_不算一跳_旧连接不动() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        GameConnection old = open(oldGate);
        int nowhere = closedPort();

        clock.set(NOW + 300);
        assertThatThrownBy(() -> follower.follow(old, targetOf(nowhere)))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("124 RedirectToGate 的内容不可用，不跟随")
                .hasMessageContaining("票据已到期")
                .hasMessageNotContaining("cdcd");
        clock.set(NOW);
        assertThatThrownBy(() -> follower.follow(old, new RedirectTarget("", nowhere, TICKET, SIGNATURE, NOW + 300), CloseOld.BEFORE_VERIFY,
                Duration.ZERO)).isInstanceOf(RobotException.class).hasMessageContaining("target_ip 为空");

        assertThat(follower.hops()).isZero();
        assertThat(usable(old)).as("BEFORE_VERIFY 也一样：校验不过时旧连接还没动").isTrue();
    }

    @Test
    void 目标gate连不上_两种次序下旧连接都还在() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        GameConnection old = open(oldGate);
        int nowhere = closedPort();

        assertThatThrownBy(() -> follower.follow(old, targetOf(nowhere)))
                .isInstanceOf(RobotException.class).hasMessageContaining("第 1 跳").hasMessageContaining("目标 gate 127.0.0.1:" + nowhere + " 连不上");
        assertThatThrownBy(() -> follower.follow(old, targetOf(nowhere), CloseOld.BEFORE_VERIFY, Duration.ZERO))
                .isInstanceOf(RobotException.class).hasMessageContaining("第 2 跳").hasMessageContaining("连不上");

        assertThat(usable(old)).as("连都没连上，哪种次序都不该先把旧连接关了").isTrue();
        assertThat(follower.hops()).isEqualTo(2);
    }

    @Test
    void 参数不合法_先关旧连接不带延迟_延迟不能为负() throws Exception {
        FakeGate oldGate = gate("old", new Echo());
        GameConnection old = open(oldGate);
        RedirectTarget target = targetOf(closedPort());

        assertThatThrownBy(() -> follower.follow(old, target, CloseOld.BEFORE_VERIFY, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BEFORE_VERIFY");
        assertThatThrownBy(() -> follower.follow(old, target, CloseOld.AFTER_VERIFY, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(follower.hops()).isZero();
        assertThat(old.isOpen()).isTrue();
    }
}
