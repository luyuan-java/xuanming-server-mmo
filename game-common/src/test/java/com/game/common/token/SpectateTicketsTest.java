package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.game.common.RunMode;
import com.game.proto.SpectateTicket;
import com.game.proto.SpectateTicketPayload;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** 观战凭证（mmorpg battle-spectate-tiered-fanout.md §15.2、§15.5）。match 签、gate 验，两边都是这一个实现。 */
class SpectateTicketsTest {

    private static final String SECRET = "spectate-ticket-test-secret-0123456789abcdef";
    private static final long PLAYER = 1001;
    private static final long BATTLE = 0x8000_0000_0000_0077L;
    private static final long NOW = 1_760_000_000_000L;

    private final SpectateTickets tickets = SpectateTickets.ofUtf8(SECRET);

    private static SpectateTicketPayload payload() {
        return SpectateTicketPayload.newBuilder().setBattleId(BATTLE).setPlayerId(PLAYER).setBattleInstanceId("battle-instance-1")
                .setDeadlineMs(NOW + 300_000).setExpireAtMs(NOW + 60_000).build();
    }

    /**
     * 跨语言向量：payload 的字节按 protobuf 线上格式手写（不经任何语言的序列化器），hex 由独立工具算出
     * （Python {@code hmac.new(secret, payload, sha256).hexdigest()}）。Go / C++ 版的测试目前是现算 HMAC 对比，没有固定向量；要加就用这一组值。
     */
    @Test
    void 跨语言向量_同一secret与payload得到相同的小写hex() {
        String secret = "spectate-cross-language-vector-secret-0123456789";
        SpectateTicketPayload vector = SpectateTicketPayload.newBuilder().setBattleId(1).setPlayerId(2).setBattleInstanceId("i").setDeadlineMs(3)
                .setExpireAtMs(4).build();

        ByteString signed = SpectateTickets.ofUtf8(secret).sign(vector);

        assertThat(HexFormat.of().formatHex(vector.toByteArray())).isEqualTo("080110021a016920032804");
        assertThat(ticketOf(signed).getSignature().toString(StandardCharsets.US_ASCII))
                .isEqualTo("81e49d087e684b9df52c0c7bde47b88afce761af48f37b8dda6bdc3f01c2b0d8");
    }

    @Test
    void 签发的凭证验得过_payload原样读回() {
        SpectateTickets.Verdict verdict = tickets.verify(tickets.sign(payload()), PLAYER, BATTLE, NOW);

        assertThat(verdict.ok()).isTrue();
        assertThat(verdict.payload()).isEqualTo(payload());
    }

    @Test
    void 篡改payload或签名都拒_凭证不能在没有密钥时伪造() {
        SpectateTicket original = ticketOf(tickets.sign(payload()));
        ByteString forgedPayload = original.toBuilder()
                .setPayload(payload().toBuilder().setPlayerId(PLAYER + 1).build().toByteString()).build().toByteString();
        ByteString forgedSignature = original.toBuilder()
                .setSignature(ByteString.copyFromUtf8("0".repeat(64))).build().toByteString();
        ByteString otherKey = SpectateTickets.ofUtf8(SECRET + "x").sign(payload());

        assertThat(tickets.verify(forgedPayload, PLAYER + 1, BATTLE, NOW).failure()).isEqualTo(SpectateTickets.Failure.BAD_SIGNATURE);
        assertThat(tickets.verify(forgedSignature, PLAYER, BATTLE, NOW).failure()).isEqualTo(SpectateTickets.Failure.BAD_SIGNATURE);
        assertThat(tickets.verify(otherKey, PLAYER, BATTLE, NOW).failure()).as("换一把密钥签的").isEqualTo(SpectateTickets.Failure.BAD_SIGNATURE);
    }

    @Test
    void 换玩家_换场次_已过期_解析不了_都拒() {
        ByteString ticket = tickets.sign(payload());

        assertThat(tickets.verify(ticket, PLAYER + 1, BATTLE, NOW).failure()).isEqualTo(SpectateTickets.Failure.WRONG_PLAYER);
        assertThat(tickets.verify(ticket, 0, BATTLE, NOW).failure()).as("会话没进游戏").isEqualTo(SpectateTickets.Failure.WRONG_PLAYER);
        assertThat(tickets.verify(ticket, PLAYER, BATTLE + 1, NOW).failure()).isEqualTo(SpectateTickets.Failure.WRONG_BATTLE);
        assertThat(tickets.verify(ticket, PLAYER, BATTLE, NOW + 60_000).failure()).as("恰好到期就算过期").isEqualTo(SpectateTickets.Failure.EXPIRED);
        assertThat(tickets.verify(ticket, PLAYER, BATTLE, NOW + 59_999).ok()).isTrue();
        assertThat(tickets.verify(ByteString.copyFromUtf8("not a protobuf"), PLAYER, BATTLE, NOW).failure()).isEqualTo(SpectateTickets.Failure.BAD_FORMAT);
    }

    @Test
    void 缺帧来源实例的凭证拒绝_收不到任何帧不如当场失败() {
        ByteString ticket = tickets.sign(payload().toBuilder().clearBattleInstanceId().build());

        assertThat(tickets.verify(ticket, PLAYER, BATTLE, NOW).failure()).isEqualTo(SpectateTickets.Failure.BAD_FORMAT);
    }

    @Test
    void 没配密钥_验签一律拒绝_签发抛异常() {
        SpectateTickets disabled = SpectateTickets.ofUtf8("");

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.verify(tickets.sign(payload()), PLAYER, BATTLE, NOW).failure()).isEqualTo(SpectateTickets.Failure.NO_SECRET);
        assertThatIllegalStateException().isThrownBy(() -> disabled.sign(payload()));
        assertThat(SpectateTickets.ofUtf8(null)).isSameAs(SpectateTickets.disabled());
    }

    @Test
    void 密钥门禁_prod拒绝空的太短的与别的信任域相同的_dev只告警() {
        String good = "x".repeat(32);

        assertThat(SpectateSecretPolicy.check(good, RunMode.PROD, "gate-secret", null).problem()).isEqualTo(SpectateSecretPolicy.Problem.NONE);
        assertThat(SpectateSecretPolicy.check(good, RunMode.PROD).refused()).isFalse();
        assertThat(SpectateSecretPolicy.check("", RunMode.PROD).refused()).isTrue();
        assertThat(SpectateSecretPolicy.check(null, RunMode.PROD).problem()).isEqualTo(SpectateSecretPolicy.Problem.MISSING);
        assertThat(SpectateSecretPolicy.check("  \t", RunMode.PROD).problem()).as("纯空白等于没配").isEqualTo(SpectateSecretPolicy.Problem.MISSING);
        assertThat(SpectateSecretPolicy.check("x".repeat(31), RunMode.PROD).problem()).isEqualTo(SpectateSecretPolicy.Problem.TOO_SHORT);
        assertThat(SpectateSecretPolicy.check(" " + good + "\n", RunMode.PROD, good).problem()).as("去首尾空白后比较")
                .isEqualTo(SpectateSecretPolicy.Problem.SAME_AS_OTHER_DOMAIN);
        assertThat(SpectateSecretPolicy.check("", RunMode.DEV).action()).isEqualTo(BattleSecretPolicy.Action.WARN);
        assertThat(SpectateSecretPolicy.check("short", RunMode.TEST).action()).isEqualTo(BattleSecretPolicy.Action.WARN);
    }

    private static SpectateTicket ticketOf(ByteString bytes) {
        try {
            return SpectateTicket.parseFrom(bytes);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }
}
