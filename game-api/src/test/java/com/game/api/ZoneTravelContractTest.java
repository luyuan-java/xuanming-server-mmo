package com.game.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.BindAccount;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.RedirectToGate;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ZoneRedirect;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.4（跨 zone 传送 226、重定向 124）的内部契约（zone-travel-spec §5.3）：{@code ZoneRedirect} 的字段号与线类型、
 * {@code PlayerTransfer.redirect}（7 号，带存在性）、{@code SessionContext} 的 8 / 9 号、{@code SessionDirective} 的 5 号分支、
 * scene-manager 两对请求 / 应答、{@code GateNodeInfo.started_at_ms}，以及 {@link SceneDirectoryService} 追加的两个方法的签名。
 * 这些是先行件冻结的面：scene、gate、login、scene-manager 四个进程各自按它编码，号对不上只会在多进程切片上才暴露。
 */
class ZoneTravelContractTest {

    /** 一段故意不是合法 proto 的字节：票据在这一层就是不透明的 bytes。 */
    private static final ByteString TICKET = ByteString.copyFrom(new byte[] {(byte) 0xff, 0x00, 0x08, 0x07, (byte) 0x80});
    private static final ByteString SIGNATURE = ByteString.copyFromUtf8("ab".repeat(32));

    private static ZoneRedirect redirect() {
        return ZoneRedirect.newBuilder().setTargetZoneId(2).setGateNodeId(7).setGateHost("10.0.0.9").setGatePort(7001)
                .setTokenPayload(TICKET).setTokenSignature(SIGNATURE).setTokenDeadline(1_800_000_300L).build();
    }

    @Test
    void ZoneRedirect的七个字段_号与线类型() throws IOException {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(expected);
        out.writeUInt32(1, 2);
        out.writeUInt32(2, 7);
        out.writeString(3, "10.0.0.9");
        out.writeUInt32(4, 7001);
        out.writeBytes(5, TICKET);
        out.writeBytes(6, SIGNATURE);
        out.writeInt64(7, 1_800_000_300L);
        out.flush();

        assertThat(redirect().toByteArray()).isEqualTo(expected.toByteArray());
        assertFields(ZoneRedirect.getDescriptor(), "target_zone_id", 1, "gate_node_id", 2, "gate_host", 3, "gate_port", 4,
                "token_payload", 5, "token_signature", 6, "token_deadline", 7);
    }

    @Test
    void 改绑帧的7号字段是redirect_带存在性_不带它的旧帧读出来没有() throws Exception {
        // 5.2 的改绑帧只有 1..6 号字段
        PlayerTransfer rebind = PlayerTransfer.parseFrom(PlayerTransfer.newBuilder().setSessionId(11).setPlayerId(1001)
                .setFromEpoch(4).setToEpoch(5).setTargetSceneNodeId(3).setTargetSceneId(900_001).build().toByteArray());
        assertThat(rebind.hasRedirect()).isFalse();
        assertThat(rebind.getRedirect()).isEqualTo(ZoneRedirect.getDefaultInstance());

        PlayerTransfer travel = PlayerTransfer.newBuilder().setSessionId(11).setPlayerId(1001).setFromEpoch(4).setToEpoch(5)
                .setRedirect(redirect()).build();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(expected);
        out.writeUInt32(1, 11);
        out.writeUInt64(2, 1001);
        out.writeUInt64(3, 4);
        out.writeUInt64(4, 5);
        out.writeMessage(7, redirect());
        out.flush();
        assertThat(travel.toByteArray()).isEqualTo(expected.toByteArray());

        PlayerTransfer parsed = PlayerTransfer.parseFrom(travel.toByteArray());
        assertThat(parsed.hasRedirect()).isTrue();
        assertThat(parsed.getTargetSceneNodeId()).isZero();
        assertThat(parsed.getTargetSceneId()).isZero();
        assertThat(parsed.getRedirect().getTokenPayload()).as("票据字节经链路帧往返原样").isEqualTo(TICKET);
        assertThat(parsed.getRedirect().getTokenSignature()).isEqualTo(SIGNATURE);

        // 判别只看存在性：一个全默认值的 redirect 也算「带了」（gate 据此判非法帧，而不是把它当成改绑指令）
        PlayerTransfer empty = PlayerTransfer.parseFrom(
                PlayerTransfer.newBuilder().setRedirect(ZoneRedirect.getDefaultInstance()).build().toByteArray());
        assertThat(empty.hasRedirect()).isTrue();
    }

    @Test
    void 会话上下文的8号9号是票据的持票者与目标zone_不填时为0() throws IOException {
        SessionContext context = SessionContext.newBuilder().setTicketPlayerId(0x0102_0304_0506_0708L).setTicketTargetZoneId(2)
                .build();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(expected);
        out.writeUInt64(8, 0x0102_0304_0506_0708L);
        out.writeUInt32(9, 2);
        out.flush();
        assertThat(context.toByteArray()).isEqualTo(expected.toByteArray());

        // 不认识这两个字段的旧 gate 发来的上下文
        SessionContext old = SessionContext.parseFrom(SessionContext.newBuilder().setGateNodeId(1).setSessionId(9).setZoneId(1)
                .setAccount("a").setPlayerId(5).build().toByteArray());
        assertThat(old.getTicketPlayerId()).isZero();
        assertThat(old.getTicketTargetZoneId()).isZero();
        assertThat(SessionContext.getDescriptor().getFields()).hasSize(9);
    }

    @Test
    void 会话指令的5号分支是RedirectToGate_原有四个分支的号不变() throws IOException {
        assertThat(SessionDirective.KindCase.BIND_ACCOUNT.getNumber()).isEqualTo(1);
        assertThat(SessionDirective.KindCase.ENTER_SCENE.getNumber()).isEqualTo(2);
        assertThat(SessionDirective.KindCase.CLOSE_SESSION.getNumber()).isEqualTo(3);
        assertThat(SessionDirective.KindCase.UNBIND_PLAYER.getNumber()).isEqualTo(4);
        assertThat(SessionDirective.KindCase.REDIRECT_TO_GATE.getNumber()).isEqualTo(5);

        RedirectToGate body = RedirectToGate.newBuilder().setRedirect(redirect()).build();
        SessionDirective directive = SessionDirective.newBuilder().setRedirectToGate(body).build();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(expected);
        out.writeMessage(5, body);
        out.flush();
        assertThat(directive.toByteArray()).isEqualTo(expected.toByteArray());

        SessionDirective parsed = SessionDirective.parseFrom(directive.toByteArray());
        assertThat(parsed.getKindCase()).isEqualTo(SessionDirective.KindCase.REDIRECT_TO_GATE);
        assertThat(parsed.getRedirectToGate().getRedirect().getTokenPayload()).as("票据字节经会话指令往返原样").isEqualTo(TICKET);
        assertFields(RedirectToGate.getDescriptor(), "redirect", 1);
        // 同一个 oneof：设了别的分支，重定向分支就不在了
        assertThat(directive.toBuilder().setBindAccount(BindAccount.newBuilder().setAccount("a")).build().hasRedirectToGate())
                .isFalse();
    }

    @Test
    void 跨zone传送选目标的请求与应答_5号留给归属区() {
        assertFields(SelectTravelTargetRequest.getDescriptor(), "from_zone_id", 1, "to_zone_id", 2, "player_id", 3,
                "want_scene_config_id", 4);
        assertThat(SelectTravelTargetRequest.getDescriptor().isReservedNumber(5)).as("7.3 的 home_zone_id").isTrue();
        assertFields(SelectTravelTargetResponse.getDescriptor(), "tip_id", 1, "scene_config_id", 2, "redirect", 3);
        assertThat(SelectTravelTargetResponse.getDescriptor().findFieldByNumber(3).getMessageType())
                .isEqualTo(ZoneRedirect.getDescriptor());
        assertThat(SelectTravelTargetRequest.getDescriptor().findFieldByNumber(3).getType())
                .as("player_id 是 uint64").isEqualTo(FieldDescriptor.Type.UINT64);

        // 拒绝应答不带 redirect；选中的应答带（调用方按存在性判残缺）
        assertThat(SelectTravelTargetResponse.newBuilder().setTipId(3000).build().hasRedirect()).isFalse();
        assertThat(SelectTravelTargetResponse.newBuilder().setSceneConfigId(2).setRedirect(redirect()).build().hasRedirect())
                .isTrue();
    }

    @Test
    void 登录期重定向选目标的请求与应答() {
        assertFields(RedirectToZoneRequest.getDescriptor(), "from_zone_id", 1, "to_zone_id", 2, "player_id", 3);
        assertFields(RedirectToZoneResponse.getDescriptor(), "tip_id", 1, "redirect", 2);
        assertThat(RedirectToZoneResponse.getDescriptor().findFieldByNumber(2).getMessageType())
                .isEqualTo(ZoneRedirect.getDescriptor());
        assertThat(RedirectToZoneRequest.getDescriptor().findFieldByNumber(3).getType()).isEqualTo(FieldDescriptor.Type.UINT64);
        assertThat(RedirectToZoneResponse.getDefaultInstance().hasRedirect()).isFalse();
    }

    @Test
    void gate目录条目的8号是启动时刻_旧条目读出来是0() throws Exception {
        GateNodeInfo entry = GateNodeInfo.newBuilder().setStartedAtMs(1_800_000_000_123L).build();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(expected);
        out.writeUInt64(8, 1_800_000_000_123L);
        out.flush();
        assertThat(entry.toByteArray()).isEqualTo(expected.toByteArray());

        GateNodeInfo old = GateNodeInfo.parseFrom(GateNodeInfo.newBuilder().setZoneId(1).setNodeId(2).setInstanceId("i")
                .setClientHost("h").setClientPort(7001).setPlayerCount(3).setDraining(true).build().toByteArray());
        assertThat(old.getStartedAtMs()).as("不认识这个字段的旧 gate 写的条目 = 最旧").isZero();
    }

    @Test
    void 场景目录服务追加的两个方法_异步_请求应答类型各自成对() throws Exception {
        Method travel = SceneDirectoryService.class.getDeclaredMethod("selectTravelTarget", SelectTravelTargetRequest.class);
        Method redirect = SceneDirectoryService.class.getDeclaredMethod("redirectToZone", RedirectToZoneRequest.class);

        assertThat(travel.getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(travel.getGenericReturnType().getTypeName()).contains(SelectTravelTargetResponse.class.getName());
        assertThat(redirect.getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(redirect.getGenericReturnType().getTypeName()).contains(RedirectToZoneResponse.class.getName());
        assertThat(SceneDirectoryService.class.getDeclaredMethods()).extracting(Method::getName)
                .containsExactlyInAnyOrder("assign", "selectSwitchTarget", "createInstance", "selectTravelTarget", "redirectToZone");
    }

    private static void assertFields(Descriptor descriptor, Object... nameThenNumber) {
        assertThat(descriptor.getFields()).as(descriptor.getName() + " 的字段数").hasSize(nameThenNumber.length / 2);
        for (int i = 0; i < nameThenNumber.length; i += 2) {
            String name = (String) nameThenNumber[i];
            assertThat(descriptor.findFieldByName(name)).as(descriptor.getName() + "." + name).isNotNull();
            assertThat(descriptor.findFieldByName(name).getNumber()).as(descriptor.getName() + "." + name)
                    .isEqualTo(nameThenNumber[i + 1]);
        }
    }
}
