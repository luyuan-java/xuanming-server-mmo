package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.RedirectToGateNotify;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

/** 124 RedirectToGate 的解包与本地判据（批次 5.4）：五个字段原样、票据不重新序列化、四种本地可判的毛病、票据不进日志。 */
class RedirectTargetTest {

    private static final long NOW = 1_760_000_000L;

    /** 票据：目标 gate 3 号、目标区 2、持票者 77。末尾多带一个本地 proto 不认识的字段（99 号 varint）：解析后重新序列化就会变样。 */
    private static final ByteString TICKET = GateTokenPayload.newBuilder().setGateNodeId(3).setZoneId(2).setExpireTimestamp(NOW + 300)
            .setPlayerId(77).setTargetZoneId(2).build().toByteString().concat(ByteString.copyFrom(new byte[] {(byte) 0x98, 0x06, 0x2A}));
    private static final ByteString SIGNATURE = ByteString.copyFromUtf8("ab".repeat(32));

    private static Received frame(RedirectToGateNotify notify) {
        return new Received(4, 123L, MessageContent.newBuilder().setMessageId(124).setSerializedMessage(notify.toByteString()).build());
    }

    private static RedirectToGateNotify.Builder redirect124() {
        return RedirectToGateNotify.newBuilder().setTargetIp("10.0.0.7").setTargetPort(11010).setTokenPayload(TICKET)
                .setTokenSignature(SIGNATURE).setTokenDeadline(NOW + 300);
    }

    @Test
    void 解一条124_五个字段原样_票据字节不重新序列化() throws Exception {
        RedirectTarget target = RedirectTarget.parse(frame(redirect124().build()));

        assertThat(target.host()).isEqualTo("10.0.0.7");
        assertThat(target.port()).isEqualTo(11010);
        assertThat(target.tokenPayload()).as("握手要发的是签名时的原字节，含本地不认识的字段").isEqualTo(TICKET);
        assertThat(target.tokenSignature()).isEqualTo(SIGNATURE);
        assertThat(target.tokenDeadline()).isEqualTo(NOW + 300);
        assertThat(target.endpoint()).isEqualTo(new GateEndpoint("10.0.0.7", 11010)).hasToString("10.0.0.7:11010");
        assertThat(target.problem(NOW)).isEmpty();
    }

    @Test
    void 票据的三项绑定可以解出来断言_解出来的只供阅读_原字节不变() throws Exception {
        RedirectTarget target = RedirectTarget.parse(frame(redirect124().build()));

        GateTokenPayload ticket = target.ticket();

        assertThat(ticket.getZoneId()).as("目标 gate 的 zone").isEqualTo(2);
        assertThat(ticket.getPlayerId()).as("持票者").isEqualTo(77);
        assertThat(ticket.getTargetZoneId()).as("目标 zone").isEqualTo(2);
        assertThat(ticket.getGateNodeId()).isEqualTo(3);
        assertThat(ticket.getExpireTimestamp()).isEqualTo(NOW + 300);
        assertThat(ticket.getHmacSessionKey()).as("重定向票据不带会话密钥").isEmpty();
        assertThat(target.tokenPayload()).isEqualTo(TICKET);
    }

    @Test
    void 装成assign_gate的结果形状_字段一一对应_字节相同() throws Exception {
        GateAssignment assignment = RedirectTarget.parse(frame(redirect124().build())).toAssignment();

        assertThat(assignment.gateIp()).isEqualTo("10.0.0.7");
        assertThat(assignment.gatePort()).isEqualTo(11010);
        assertThat(assignment.tokenPayload()).isEqualTo(TICKET.toByteArray());
        assertThat(assignment.tokenSignature()).isEqualTo(SIGNATURE.toByteArray());
        assertThat(assignment.tokenDeadline()).isEqualTo(NOW + 300);
    }

    @Test
    void 本地可判的毛病_地址为空_端口越界_票据为空_已到期() throws Exception {
        assertThat(RedirectTarget.parse(frame(redirect124().setTargetIp("").build())).problem(NOW)).hasValueSatisfying(
                p -> assertThat(p).contains("target_ip 为空"));
        assertThat(RedirectTarget.parse(frame(redirect124().setTargetIp("  ").build())).problem(NOW)).isPresent();

        assertThat(RedirectTarget.parse(frame(redirect124().setTargetPort(0).build())).problem(NOW)).hasValueSatisfying(
                p -> assertThat(p).contains("target_port=0", "1–65535"));
        assertThat(RedirectTarget.parse(frame(redirect124().setTargetPort(65536).build())).problem(NOW)).hasValueSatisfying(
                p -> assertThat(p).contains("target_port=65536"));
        // uint32 的上半区在 Java 里是负的 int：按无符号报出来，不能被「< 1」之外的判断漏掉
        assertThat(RedirectTarget.parse(frame(redirect124().setTargetPort(0xF000_0000).build())).problem(NOW)).hasValueSatisfying(
                p -> assertThat(p).contains("target_port=4026531840"));
        assertThat(RedirectTarget.parse(frame(redirect124().setTargetPort(1).build())).problem(NOW)).isEmpty();
        assertThat(RedirectTarget.parse(frame(redirect124().setTargetPort(65535).build())).problem(NOW)).isEmpty();

        assertThat(RedirectTarget.parse(frame(redirect124().clearTokenPayload().build())).problem(NOW)).hasValueSatisfying(
                p -> assertThat(p).contains("票据为空", "token_payload 0 B"));
        assertThat(RedirectTarget.parse(frame(redirect124().clearTokenSignature().build())).problem(NOW)).hasValueSatisfying(
                p -> assertThat(p).contains("票据为空", "token_signature 0 B"));

        RedirectTarget target = RedirectTarget.parse(frame(redirect124().build()));
        assertThat(target.problem(NOW + 299)).as("到期前一秒还可用").isEmpty();
        assertThat(target.problem(NOW + 300)).as("deadline ≤ now 即到期（gate 判的是 expire ≤ now）").hasValueSatisfying(
                p -> assertThat(p).contains("票据已到期", "token_deadline=" + (NOW + 300), "now=" + (NOW + 300)));
        assertThat(target.problem(NOW + 301)).isPresent();
        assertThat(RedirectTarget.parse(frame(redirect124().clearTokenDeadline().build())).problem(NOW)).as("没带到期时刻也算到期")
                .hasValueSatisfying(p -> assertThat(p).contains("token_deadline=0"));
    }

    @Test
    void 票据解不出来时报错_不返回全零的票据() throws Exception {
        RedirectTarget empty = RedirectTarget.parse(frame(redirect124().clearTokenPayload().build()));
        assertThatThrownBy(empty::ticket).isInstanceOf(RobotException.class).hasMessageContaining("token_payload 为空");

        // 1 号字段声明成长度前缀、长度却超出了剩余字节：不是合法的 protobuf
        RedirectTarget garbage = RedirectTarget.parse(frame(redirect124().setTokenPayload(ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01})).build()));
        assertThatThrownBy(garbage::ticket).isInstanceOf(RobotException.class).hasMessageContaining("不是合法的 GateTokenPayload");
    }

    @Test
    void 包体不是合法的124时解包失败() {
        Received broken = new Received(0, 1L, MessageContent.newBuilder().setMessageId(124)
                .setSerializedMessage(ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01})).build());

        assertThatThrownBy(() -> RedirectTarget.parse(broken)).isInstanceOf(RobotException.class).hasMessageContaining("解析失败");
    }

    @Test
    void 票据是签名凭据_toString只打长度() throws Exception {
        RedirectTarget target = RedirectTarget.parse(frame(redirect124().build()));

        assertThat(target.toString()).isEqualTo("RedirectTarget[10.0.0.7:11010, payload=" + TICKET.size() + "B, signature=64B, deadline="
                + (NOW + 300) + "]").doesNotContain("abab");
    }

    @Test
    void 消息号按服务名加方法名从契约注册表解析_它是客户端可见的玩家服务的推送() {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();

        int id = RedirectTarget.messageId(registry);

        MessageMethod method = registry.byId(id).orElseThrow();
        assertThat(method.clientService()).isTrue();
        assertThat(method.playerService()).isTrue();
        assertThat(method.requestPrototype().getDescriptorForType().getName()).isEqualTo("RedirectToGateNotify");
        assertThat(id).as("与 MessageIds 里的 15 个号都不同（它不在那张表里）").isNotIn(MessageIds.resolve(registry).sendTip(),
                MessageIds.resolve(registry).notifyEnterScene(), MessageIds.resolve(registry).enterGame());
    }
}
