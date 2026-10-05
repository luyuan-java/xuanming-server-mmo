package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.token.BattleTickets.Verdict;
import com.game.proto.BattleTicketPayload;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 逐条移植基线 {@code cpp/nodes/battle/tests/battle_ticket_test.cpp:44-217} 的签名与字段判定用例（battle-node-spec §13.1），
 * 另加两条 Java 侧要求：大写 hex 签名必须拒绝；payload 带未知字段仍能验过（验签用原字节）。
 */
class BattleTicketsTest {

    private static final String SECRET = "unit-test-battle-token-secret-0123456789abcdef";
    /** 「序列化后的 BattleTicketPayload」：签名是字节级的，任何字节串都行（同基线 kPayload）。 */
    private static final ByteString PAYLOAD = ByteString.copyFrom(new byte[] {0x08, (byte) 0xd2, 0x09, 0x10, 0x01});

    private final BattleTickets tickets = BattleTickets.ofUtf8(SECRET);

    private static ByteString ascii(String s) {
        return ByteString.copyFrom(s, StandardCharsets.US_ASCII);
    }

    // ---- 签名 ----

    @Test
    void 签名往返_64位小写hex() {
        ByteString sig = tickets.sign(PAYLOAD);
        assertThat(sig.size()).isEqualTo(BattleTickets.SIGNATURE_LENGTH);
        assertThat(sig.toString(StandardCharsets.US_ASCII)).matches("^[0-9a-f]{64}$");
        assertThat(tickets.signatureMatches(PAYLOAD, sig)).isTrue();
    }

    @Test
    void 同输入签名确定() {
        assertThat(tickets.sign(PAYLOAD)).isEqualTo(tickets.sign(PAYLOAD));
        assertThat(BattleTickets.ofUtf8(SECRET).sign(PAYLOAD)).isEqualTo(tickets.sign(PAYLOAD));
    }

    @Test
    void 改payload验签失败() {
        ByteString sig = tickets.sign(PAYLOAD);
        byte[] tampered = PAYLOAD.toByteArray();
        tampered[0] ^= 0x01;
        assertThat(tickets.signatureMatches(ByteString.copyFrom(tampered), sig)).isFalse();
    }

    @Test
    void 换密钥验签失败() {
        ByteString sig = tickets.sign(PAYLOAD);
        assertThat(BattleTickets.ofUtf8("another-secret-that-is-long-enough-000000").signatureMatches(PAYLOAD, sig)).isFalse();
    }

    @Test
    void 空签名_截短到63位_多1位都失败() {
        String sig = tickets.sign(PAYLOAD).toString(StandardCharsets.US_ASCII);
        assertThat(tickets.signatureMatches(PAYLOAD, ByteString.EMPTY)).isFalse();
        assertThat(tickets.signatureMatches(PAYLOAD, ascii(sig.substring(0, 63)))).isFalse();
        assertThat(tickets.signatureMatches(PAYLOAD, ascii(sig + "0"))).isFalse();
    }

    @Test
    void gate密钥签的过不了battle验签() {
        ByteString gateSigned = BattleTickets.ofUtf8("gate-secret-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").sign(PAYLOAD);
        assertThat(tickets.signatureMatches(PAYLOAD, gateSigned)).isFalse();
    }

    @Test
    void 大写hex签名必须拒绝_比较大小写敏感() {
        String sig = tickets.sign(PAYLOAD).toString(StandardCharsets.US_ASCII);
        assertThat(sig).isNotEqualTo(sig.toUpperCase(Locale.ROOT)).as("HMAC 输出里必然有 a-f");
        assertThat(tickets.signatureMatches(PAYLOAD, ascii(sig.toUpperCase(Locale.ROOT)))).isFalse();
    }

    @Test
    void payload带未知字段仍能验过_验签用原字节() throws Exception {
        BattleTicketPayload payload = validTicket().build();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(buf);
        payload.writeTo(out);
        out.writeString(99, "将来版本加的字段");
        out.flush();
        ByteString raw = ByteString.copyFrom(buf.toByteArray());
        ByteString sig = tickets.sign(raw);

        assertThat(tickets.signatureMatches(raw, sig)).isTrue();
        BattleTicketPayload parsed = BattleTicketPayload.parseFrom(raw);
        assertThat(parsed.getBattleId()).isEqualTo(payload.getBattleId());
        assertThat(tickets.signatureMatches(payload.toByteString(), sig))
                .as("按已知字段重新序列化的字节（丢了未知字段）与签名不符：验签只能用收到的原字节").isFalse();
    }

    @Test
    void 空密钥构造即拒绝() {
        assertThatThrownBy(() -> new BattleTickets(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BattleTickets(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BattleTickets.ofUtf8(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BattleTickets.ofUtf8("")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 字段判定 ----

    private static final int SELF_NODE = 7;
    private static final String SELF_INSTANCE = "battle-uuid-1";
    private static final long NOW = 999_999;

    private static BattleTicketPayload.Builder validTicket() {
        return BattleTicketPayload.newBuilder()
                .setBattleId(65047318352658432L)
                .setPlayerId(9001)
                .setBattleNodeId(7)
                .setBattleInstanceId("battle-uuid-1")
                .setExpireAtMs(1_000_000)
                .setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
    }

    private static Verdict classify(BattleTicketPayload.Builder t) {
        return BattleTickets.classify(t.build(), SELF_NODE, SELF_INSTANCE, NOW);
    }

    @Test
    void 参战者与观众字段合法() {
        assertThat(classify(validTicket())).isEqualTo(Verdict.OK);
        assertThat(classify(validTicket().setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER))).isEqualTo(Verdict.OK);
    }

    @Test
    void 身份为空拒绝() {
        assertThat(classify(validTicket().setBattleId(0))).isEqualTo(Verdict.EMPTY_IDENTITY);
        assertThat(classify(validTicket().setPlayerId(0))).isEqualTo(Verdict.EMPTY_IDENTITY);
    }

    @Test
    void 签给别的节点拒绝() {
        assertThat(classify(validTicket().setBattleNodeId(8))).isEqualTo(Verdict.NODE_MISMATCH);
    }

    @Test
    void 同节点号不同实例拒绝() {
        // 节点重启后节点号被复用：旧票的节点号对得上，实例 UUID 对不上，必须拒
        assertThat(classify(validTicket().setBattleInstanceId("battle-uuid-0-restarted"))).isEqualTo(Verdict.INSTANCE_MISMATCH);
    }

    @Test
    void 实例任一侧为空拒绝() {
        assertThat(classify(validTicket().setBattleInstanceId(""))).isEqualTo(Verdict.INSTANCE_MISMATCH);
        assertThat(BattleTickets.classify(validTicket().build(), SELF_NODE, "", NOW)).isEqualTo(Verdict.INSTANCE_MISMATCH);
        assertThat(BattleTickets.classify(validTicket().build(), SELF_NODE, null, NOW)).isEqualTo(Verdict.INSTANCE_MISMATCH);
    }

    @Test
    void 期限右端不放行_等于也算过期() {
        BattleTicketPayload t = validTicket().build();
        long expire = t.getExpireAtMs();
        assertThat(BattleTickets.classify(t, SELF_NODE, SELF_INSTANCE, expire - 1)).isEqualTo(Verdict.OK);
        assertThat(BattleTickets.classify(t, SELF_NODE, SELF_INSTANCE, expire)).isEqualTo(Verdict.EXPIRED);
        assertThat(BattleTickets.classify(t, SELF_NODE, SELF_INSTANCE, expire + 1)).isEqualTo(Verdict.EXPIRED);
    }

    @Test
    void 期限按uint64比较_超过long上限的期限不算过期() {
        assertThat(classify(validTicket().setExpireAtMs(-1L))).as("uint64 最大值是遥远的将来").isEqualTo(Verdict.OK);
        assertThat(classify(validTicket().setExpireAtMs(0))).isEqualTo(Verdict.EXPIRED);
    }

    @Test
    void 未知角色拒绝_按整数值判断() {
        assertThat(classify(validTicket().setRoleValue(0))).isEqualTo(Verdict.ROLE_INVALID);
        assertThat(classify(validTicket().setRoleValue(3))).isEqualTo(Verdict.ROLE_INVALID);
        assertThat(classify(validTicket().setRoleValue(42))).isEqualTo(Verdict.ROLE_INVALID);
    }

    @Test
    void 多个字段都坏时最便宜的那个赢() {
        // 判定顺序契约：身份 → 节点 → 实例 → 期限 → 角色
        assertThat(classify(validTicket().setBattleNodeId(99).setExpireAtMs(0).setRoleValue(0))).isEqualTo(Verdict.NODE_MISMATCH);
        assertThat(classify(validTicket().setPlayerId(0).setBattleNodeId(99))).isEqualTo(Verdict.EMPTY_IDENTITY);
        assertThat(classify(validTicket().setBattleInstanceId("x").setExpireAtMs(0))).isEqualTo(Verdict.INSTANCE_MISMATCH);
        assertThat(classify(validTicket().setExpireAtMs(0).setRoleValue(42))).isEqualTo(Verdict.EXPIRED);
    }

    @Test
    void 判定名与拒绝串同基线() {
        assertThat(Verdict.OK.wireName()).isEqualTo("ok");
        assertThat(Verdict.EMPTY_IDENTITY.wireName()).isEqualTo("empty_identity");
        assertThat(Verdict.NODE_MISMATCH.wireName()).isEqualTo("node_mismatch");
        assertThat(Verdict.INSTANCE_MISMATCH.wireName()).isEqualTo("instance_mismatch");
        assertThat(Verdict.EXPIRED.wireName()).isEqualTo("expired");
        assertThat(Verdict.ROLE_INVALID.wireName()).isEqualTo("role_invalid");
        assertThat(Verdict.EXPIRED.clientError()).isEqualTo("ticket rejected: expired");
        assertThatThrownBy(Verdict.OK::clientError).isInstanceOf(IllegalStateException.class);
    }
}
