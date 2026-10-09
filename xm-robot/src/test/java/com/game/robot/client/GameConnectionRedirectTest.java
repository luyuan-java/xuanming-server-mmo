package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.RedirectToGateNotify;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.ByteString;
import io.netty.channel.nio.NioEventLoopGroup;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.4 给连接层加的几样（对着带握手的假 gate）：不握手的裸连接、不因被拒而抛的握手、等「服务端关了连接」、
 * 超时说明里列出 124、连接记得自己连的端点。
 */
class GameConnectionRedirectTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final MessageIds IDS = MessageIds.resolve(REGISTRY);
    private static final int REDIRECT = RedirectTarget.messageId(REGISTRY);
    private static final Duration SHORT = Duration.ofSeconds(3);
    private static final ByteString PAYLOAD = ByteString.copyFrom(new byte[] {0x08, 0x03, 0x10, 0x02, (byte) 0x98, 0x06, 0x2A});
    private static final ByteString SIGNATURE = ByteString.copyFromUtf8("0f".repeat(32));

    private final FakeGate.Events events = new FakeGate.Events();
    private final RobotClient client = new RobotClient("http://127.0.0.1:1", 1, IDS, REDIRECT, SHORT, SHORT);
    private FakeGate gate;

    @AfterEach
    void stop() {
        client.close();
        if (gate != null) {
            gate.close();
        }
    }

    /** 77 照常应答；发 26 时只推一条 124（不回包）；别的不回包。 */
    private static final class PushRedirectOnEnter implements FakeGate.Script {
        @Override
        public void onRequest(FakeGate.Session session, ClientRequest request) {
            if (request.getMessageId() == IDS.listSkills()) {
                session.reply(request, ListSkillsResponse.getDefaultInstance());
            } else if (request.getMessageId() == IDS.enterGame()) {
                session.push(REDIRECT, RedirectToGateNotify.newBuilder().setTargetIp("10.0.0.7").setTargetPort(11010).build());
            }
        }
    }

    @Test
    void 裸连接不握手_连的端点记在连接上_握手帧一个字节不改地到了gate() throws Exception {
        gate = new FakeGate("gate", events, new PushRedirectOnEnter());

        try (GameConnection connection = client.openRaw("127.0.0.1", gate.port())) {
            assertThat(connection.endpoint()).isEqualTo(gate.endpoint());
            assertThat(events.await("gate accept#0", SHORT)).isTrue();
            assertThat(gate.sessions().get(0).verify()).as("openRaw 只建 TCP").isNull();

            ClientTokenVerifyResponse response = connection.tryVerifyToken(PAYLOAD, SIGNATURE, SHORT);

            assertThat(response.getSuccess()).isTrue();
            ClientTokenVerifyRequest seen = gate.sessions().get(0).verify();
            assertThat(seen.getPayload()).as("票据原字节（含本地不认识的字段）").isEqualTo(PAYLOAD);
            assertThat(seen.getSignature()).isEqualTo(SIGNATURE);
            // 握手之后连接照常可用
            assertThat(connection.call(IDS.listSkills(), ListSkillsRequest.getDefaultInstance(), ListSkillsResponse.parser(), SHORT))
                    .isEqualTo(ListSkillsResponse.getDefaultInstance());
            assertThat(connection.awaitClosed(Duration.ofMillis(100))).as("没人关它").isFalse();
            assertThat(connection.isOpen()).isTrue();
        }
    }

    @Test
    void 握手被拒不抛_应答原样交回_随后等到服务端关连接() throws Exception {
        gate = new FakeGate("gate", events, new FakeGate.Script() {
            @Override
            public ClientTokenVerifyResponse onVerify(FakeGate.Session session, ClientTokenVerifyRequest request) {
                return FakeGate.verifyRejected("token not for this zone");
            }

            @Override
            public void onRequest(FakeGate.Session session, ClientRequest request) {
            }
        });

        try (GameConnection connection = client.openRaw("127.0.0.1", gate.port())) {
            ClientTokenVerifyResponse response = connection.tryVerifyToken(PAYLOAD, SIGNATURE, SHORT);

            assertThat(response.getSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("token not for this zone");
            assertThat(connection.awaitClosed(Duration.ofSeconds(5))).as("gate 回完拒绝就关连接").isTrue();
            assertThat(connection.inbox().closedReason()).as("是对端关的，不是本端").isEqualTo("连接已被关闭");
            assertThat(connection.isOpen()).isFalse();
        }
    }

    @Test
    void 旧的verifyToken照旧_被拒即抛_文案不变() throws Exception {
        gate = new FakeGate("gate", events, new FakeGate.Script() {
            @Override
            public ClientTokenVerifyResponse onVerify(FakeGate.Session session, ClientTokenVerifyRequest request) {
                return FakeGate.verifyRejected("token expired");
            }

            @Override
            public void onRequest(FakeGate.Session session, ClientRequest request) {
            }
        });

        try (GameConnection connection = client.openRaw("127.0.0.1", gate.port())) {
            assertThatThrownBy(() -> connection.verifyToken(PAYLOAD.toByteArray(), SIGNATURE.toByteArray(), SHORT))
                    .isInstanceOf(RobotException.class).hasMessage("gate 拒绝令牌：success=false error=token expired");
        }
    }

    @Test
    void 握手没有应答_超时抛出_连接先被关_抛出原因() throws Exception {
        // 压着不回
        gate = new FakeGate("gate", events, new FakeGate.Script() {
            @Override
            public ClientTokenVerifyResponse onVerify(FakeGate.Session session, ClientTokenVerifyRequest request) {
                if (session.index() == 1) {
                    session.close();
                }
                return null;
            }

            @Override
            public void onRequest(FakeGate.Session session, ClientRequest request) {
            }
        });

        try (GameConnection silent = client.openRaw("127.0.0.1", gate.port())) {
            assertThatThrownBy(() -> silent.tryVerifyToken(PAYLOAD, SIGNATURE, Duration.ofMillis(300)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("握手超时").hasMessageContaining("300 ms");
        }
        try (GameConnection dropped = client.openRaw("127.0.0.1", gate.port())) {
            assertThatThrownBy(() -> dropped.tryVerifyToken(PAYLOAD, SIGNATURE, SHORT))
                    .isInstanceOf(RobotException.class).hasMessageContaining("握手完成前连接已被关闭");
        }
    }

    @Test
    void 等应答超时的说明里列出124_在这之前到的124也说() throws Exception {
        gate = new FakeGate("gate", events, new PushRedirectOnEnter());

        try (GameConnection connection = client.openRaw("127.0.0.1", gate.port())) {
            connection.tryVerifyToken(PAYLOAD, SIGNATURE, SHORT);

            // 发 26：gate 只推 124 不回包。先等 124 到了再看说明——不靠它恰好落在某个等待窗口里
            connection.send(IDS.enterGame(), ListSkillsRequest.getDefaultInstance());
            assertThat(connection.await(0, r -> r.messageId() == REDIRECT, SHORT)).isPresent();

            assertThat(connection.describeSince(0)).as("124 在所问的区间里")
                    .isEqualTo("；期间收到了 124 RedirectToGate（目标 10.0.0.7:11010）：服务端让这条连接改连别的 gate，之后本连接上的请求不再有回包");
            int mark = connection.inbox().size();
            assertThat(connection.describeSince(mark)).as("124 在所问的区间之前：照样点出来")
                    .isEqualTo("；这条连接此前已收到了 124 RedirectToGate（目标 10.0.0.7:11010）：它之后发的请求不会有回包");

            // 124 之后再发请求（这里发一个假 gate 不回包的号）：等应答超时的文案带上它
            assertThatThrownBy(() -> connection.call(IDS.createPlayer(), ListSkillsRequest.getDefaultInstance(), ListSkillsResponse.parser(),
                    Duration.ofMillis(300)))
                    .isInstanceOf(RobotException.class)
                    .hasMessageContaining("没有收到 message_id=" + IDS.createPlayer())
                    .hasMessageContaining("此前已收到了 124 RedirectToGate（目标 10.0.0.7:11010）");
        }
    }

    @Test
    void 没给124的消息号时_超时说明只列23_与批次5_4之前相同() throws Exception {
        gate = new FakeGate("gate", events, new FakeGate.Script() {
            @Override
            public void onRequest(FakeGate.Session session, ClientRequest request) {
                session.push(REDIRECT, RedirectToGateNotify.newBuilder().setTargetIp("10.0.0.7").setTargetPort(11010).build());
                session.push(IDS.sendTip(), TipInfoMessage.newBuilder().setId(1003).build());
            }
        });
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        // 包内的五参数入口（GameConnectionTest 用的那个）：不认 124
        try (GameConnection connection = GameConnection.open(group, "127.0.0.1", gate.port(), SHORT, IDS.sendTip())) {
            connection.send(IDS.listSkills(), ListSkillsRequest.getDefaultInstance());
            assertThat(connection.await(0, r -> r.messageId() == IDS.sendTip(), SHORT)).isPresent();

            assertThat(connection.describeSince(0)).isEqualTo("；期间收到 23 tip [1003]");
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 旧的五参数RobotClient也认得124_消息号取自契约注册表() {
        try (RobotClient legacy = new RobotClient("http://127.0.0.1:1", 7, IDS, SHORT, Duration.ofSeconds(4))) {
            assertThat(legacy.redirectToGateId()).isEqualTo(REDIRECT);
            assertThat(legacy.zoneId()).isEqualTo(7);
            assertThat(legacy.handshakeTimeout()).isEqualTo(Duration.ofSeconds(4));
        }
        assertThatThrownBy(() -> new RobotClient("http://127.0.0.1:1", 1, IDS, 0, SHORT, SHORT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("124");
    }
}
