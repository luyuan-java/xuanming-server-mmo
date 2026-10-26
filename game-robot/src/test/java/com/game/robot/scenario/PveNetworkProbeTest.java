package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.*;

import com.game.contract.MessageIdRegistry;
import com.game.net.client.ClientFrames;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.*;
import com.game.robot.client.GameConnection;
import com.game.robot.client.RobotException;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 真本地TCP假探测端：验证现有帧协议、三次nonce与完整原授权回执；不需要游戏服务或真实秘密。 */
@Timeout(10)
class PveNetworkProbeTest {
    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final Map<String, Message> REQUESTS = Map.of(NetworkProbeStart.getDescriptor().getFullName(), NetworkProbeStart.getDefaultInstance(),
            NetworkProbeEcho.getDescriptor().getFullName(), NetworkProbeEcho.getDefaultInstance());
    private NetworkProbeGrant grant() {
        return NetworkProbeGrant.newBuilder().setPlanId("plan-1").setPlayerId(7).setSessionBinding("session")
                .setPhysicalRegion(1).setTargetNodeId(1).setTargetInstanceId("instance").setExpiresAtMs(System.currentTimeMillis() + 30_000).build();
    }
    private SignedNetworkProbe signed(Message message) {
        return SignedNetworkProbe.newBuilder().setPayload(message.toByteString()).setSignature(ByteString.copyFromUtf8("fixture-signature")).build();
    }
    private NetworkProbeTarget target(int port, NetworkProbeGrant grant) {
        return NetworkProbeTarget.newBuilder().setHost("127.0.0.1").setPort(port).setPhysicalRegion(1).setGrant(signed(grant)).build();
    }
    private static Message read(DataInputStream input) throws Exception {
        int size = input.readInt();
        assertThat(size).isBetween(ClientFrames.MIN_LEN, ClientFrames.DEFAULT_MAX_LEN);
        var frame = Unpooled.wrappedBuffer(input.readNBytes(size));
        try { return ClientFrames.decodeBody(frame, REQUESTS); }
        finally { frame.release(); }
    }
    private static void write(DataOutputStream output, Message message) throws Exception {
        var frame = ClientFrames.encode(UnpooledByteBufAllocator.DEFAULT, message);
        try {
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            output.write(bytes);
            output.flush();
        } finally { frame.release(); }
    }
    private static final class ProbeCaller implements MatchSupport.Caller {
        private final GetNetworkProbePlanResponse plan;
        private final CommitNetworkProbeResponse confirmed;
        private CommitNetworkProbeRequest committed;
        ProbeCaller(GetNetworkProbePlanResponse plan, CommitNetworkProbeResponse confirmed) {
            this.plan = plan;
            this.confirmed = confirmed;
        }
        @Override public String name() { return "probe"; }
        @Override public long id() { return 7; }
        @Override public GameConnection connection() { throw new UnsupportedOperationException(); }
        @Override public int mark() { throw new UnsupportedOperationException(); }
        @Override public long send(int id, Message body) { throw new UnsupportedOperationException(); }
        @Override public <T extends Message> T call(int id, Message body, Parser<T> parser) throws RobotException {
            Message response;
            if (body instanceof GetNetworkProbePlanRequest) {
                assertThat(id).isEqualTo(REGISTRY.requireId("MatchService", "GetNetworkProbePlan"));
                response = plan;
            } else if (body instanceof CommitNetworkProbeRequest request) {
                assertThat(id).isEqualTo(REGISTRY.requireId("MatchService", "CommitNetworkProbe"));
                assertThat(committed).isNull();
                committed = request;
                response = confirmed;
            } else throw new AssertionError("不允许绕开真实探测发其他RPC");
            try { return parser.parseFrom(response.toByteString()); }
            catch (com.google.protobuf.InvalidProtocolBufferException bad) { throw new RobotException("假服类型不符", bad); }
        }
    }
    private void exchange(Socket socket, NetworkProbeGrant original, boolean wrongReceipt) throws Exception {
        socket.setSoTimeout(2000);
        var input = new DataInputStream(socket.getInputStream());
        var output = new DataOutputStream(socket.getOutputStream());
        assertThat(((NetworkProbeStart) read(input)).getGrant()).isEqualTo(signed(original));
        for (int i = 0; i < 3; i++) {
            var nonce = ByteString.copyFrom(new byte[32]).toByteArray();
            nonce[0] = (byte) (i + 1);
            var challenge = NetworkProbeChallenge.newBuilder().setSampleIndex(i).setNonce(ByteString.copyFrom(nonce)).build();
            write(output, challenge);
            var echo = (NetworkProbeEcho) read(input);
            assertThat(echo.getSampleIndex()).isEqualTo(i);
            assertThat(echo.getNonce()).isEqualTo(challenge.getNonce());
        }
        var receipt = NetworkProbeReceipt.newBuilder().setGrant(wrongReceipt ? original.toBuilder().setPlayerId(8).build() : original)
                .setSampleCount(3).setRttMs(1).setMeasuredAtMs(System.currentTimeMillis()).setExpiresAtMs(original.getExpiresAtMs()).build();
        write(output, NetworkProbeComplete.newBuilder().setReceipt(signed(receipt)).build());
    }

    @Test void 原授权三次真实nonce回显后才接受完整签名回执() throws Exception {
        var original = grant();
        try (var executor = Executors.newSingleThreadExecutor(); var server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            var served = executor.submit(() -> { try (var socket = server.accept()) { exchange(socket, original, false); } return true; });
            var result = PveNetworkProbe.measure(target(server.getLocalPort(), original), 7, "plan-1", Duration.ofSeconds(3));
            var receipt = NetworkProbeReceipt.parseFrom(result.getPayload());
            assertThat(receipt.getGrant()).isEqualTo(original);
            assertThat(receipt.getSampleCount()).isEqualTo(3);
            assertThat(served.get(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void 伪换玩家的回执即使有非空签名也拒绝_不交给排队() throws Exception {
        var original = grant();
        try (var executor = Executors.newSingleThreadExecutor(); var server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            var served = executor.submit(() -> { try (var socket = server.accept()) { exchange(socket, original, true); } return true; });
            assertThatThrownBy(() -> PveNetworkProbe.measure(target(server.getLocalPort(), original), 7, "plan-1", Duration.ofSeconds(3)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("完整原授权");
            assertThat(served.get(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void 跳序挑战或提前complete不能伪装成已完成三次探测() throws Exception {
        var original = grant();
        for (Message invalid : java.util.List.of(NetworkProbeChallenge.newBuilder().setSampleIndex(1).setNonce(ByteString.copyFrom(new byte[32])).build(),
                NetworkProbeComplete.newBuilder().setReceipt(signed(original)).build())) {
            try (var executor = Executors.newSingleThreadExecutor(); var server = new ServerSocket(0)) {
                server.setSoTimeout(3000);
                var served = executor.submit(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        read(new DataInputStream(socket.getInputStream()));
                        write(new DataOutputStream(socket.getOutputStream()), invalid);
                    }
                    return true;
                });
                assertThatThrownBy(() -> PveNetworkProbe.measure(target(server.getLocalPort(), original), 7, "plan-1", Duration.ofSeconds(3)))
                        .isInstanceOf(RobotException.class).hasMessageContaining("三次nonce");
                assertThat(served.get(3, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test void 计划到真实探测再提交原签名回执_提交成功后才返回计划号() throws Exception {
        var original = grant();
        try (var executor = Executors.newSingleThreadExecutor(); var server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            var plan = GetNetworkProbePlanResponse.newBuilder().setRequired(true).setPlanId(original.getPlanId())
                    .addTargets(target(server.getLocalPort(), original)).setExpiresAtMs(original.getExpiresAtMs()).build();
            var caller = new ProbeCaller(plan, CommitNetworkProbeResponse.newBuilder().setExpiresAtMs(original.getExpiresAtMs()).build());
            var served = executor.submit(() -> { try (var socket = server.accept()) { exchange(socket, original, false); } return true; });
            assertThat(PveNetworkProbe.prepare(caller, REGISTRY, Duration.ofSeconds(3))).isEqualTo(original.getPlanId());
            assertThat(caller.committed.getPlanId()).isEqualTo(original.getPlanId());
            assertThat(caller.committed.getReceiptsCount()).isEqualTo(1);
            assertThat(NetworkProbeReceipt.parseFrom(caller.committed.getReceipts(0).getPayload()).getGrant()).isEqualTo(original);
            assertThat(caller.committed.getReceipts(0).getSignature()).isEqualTo(ByteString.copyFromUtf8("fixture-signature"));
            assertThat(served.get(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void 缺计划或提交业务失败均明确失败_不回退无网络原件模式() throws Exception {
        var absent = new ProbeCaller(GetNetworkProbePlanResponse.getDefaultInstance(), CommitNetworkProbeResponse.getDefaultInstance());
        assertThatThrownBy(() -> PveNetworkProbe.prepare(absent, REGISTRY, Duration.ofSeconds(3)))
                .isInstanceOf(RobotException.class).hasMessageContaining("不能退回");
        assertThat(absent.committed).isNull();
        var original = grant();
        try (var executor = Executors.newSingleThreadExecutor(); var server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            var plan = GetNetworkProbePlanResponse.newBuilder().setRequired(true).setPlanId(original.getPlanId())
                    .addTargets(target(server.getLocalPort(), original)).build();
            var caller = new ProbeCaller(plan, CommitNetworkProbeResponse.newBuilder().setExpiresAtMs(original.getExpiresAtMs())
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(1003)).build());
            var served = executor.submit(() -> { try (var socket = server.accept()) { exchange(socket, original, false); } return true; });
            assertThatThrownBy(() -> PveNetworkProbe.prepare(caller, REGISTRY, Duration.ofSeconds(3)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("回执未获确认");
            assertThat(caller.committed.getReceiptsCount()).isEqualTo(1);
            assertThat(served.get(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
