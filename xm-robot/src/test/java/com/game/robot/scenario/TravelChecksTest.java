package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.TravelToZoneResponse;
import com.game.robot.client.Received;
import com.game.robot.client.RedirectTarget;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

/** travel 的纯判据：226 应答的码与受理时的 {@code 0a 00}、票据各项、没部署的区号、结果行字段。 */
class TravelChecksTest {

    private static final long NOW = 1_800_000_000L;
    private static final long PLAYER = 0x8000_0000_0000_0101L;

    private static Received reply(TravelToZoneResponse body, int envelopeTip) {
        MessageContent.Builder content = MessageContent.newBuilder().setMessageId(226).setId(7).setSerializedMessage(body.toByteString());
        if (envelopeTip != 0) {
            content.setErrorMessage(TipInfoMessage.newBuilder().setId(envelopeTip));
        }
        return new Received(0, 0, content.build());
    }

    private static TravelToZoneResponse tip(int id) {
        return TravelToZoneResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(id)).build();
    }

    private static GateTokenPayload.Builder ticket() {
        return GateTokenPayload.newBuilder().setGateNodeId(1).setZoneId(2).setExpireTimestamp(NOW + 300).setPlayerId(PLAYER).setTargetZoneId(2);
    }

    private static RedirectTarget target(GateTokenPayload ticket, long deadline) {
        return new RedirectTarget("127.0.0.1", 11010, ticket.toByteString(), ByteString.copyFromUtf8("ab".repeat(32)), deadline);
    }

    @Test
    void 应答码_信封优先_受理是0_受理的字节恰好0a00() {
        Received accepted = reply(tip(0), 0);
        assertThat(TravelChecks.replyTip(accepted)).isZero();
        assertThat(TravelChecks.acceptedEncoding(accepted)).isTrue();
        assertThat(TravelChecks.hex(accepted.content().getSerializedMessage())).isEqualTo("0a 00");

        Received noErrorField = reply(TravelToZoneResponse.getDefaultInstance(), 0);
        assertThat(TravelChecks.replyTip(noErrorField)).as("没带 error_message 也按 0 读").isZero();
        assertThat(TravelChecks.acceptedEncoding(noErrorField)).as("但契约要求它恒存在").isFalse();

        assertThat(TravelChecks.replyTip(reply(tip(TravelScenario.TIP_ZONE_INVALID), 0))).isEqualTo(TravelScenario.TIP_ZONE_INVALID);
        Received limited = reply(tip(0), 1008);
        assertThat(TravelChecks.replyTip(limited)).as("信封上的传输层错误优先").isEqualTo(1008);
        assertThat(TravelChecks.acceptedEncoding(limited)).isFalse();

        Received garbage = new Received(0, 0, MessageContent.newBuilder().setMessageId(226)
                .setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xff})).build());
        assertThat(TravelChecks.replyTip(garbage)).isEqualTo(-1);
        assertThat(TravelChecks.hex(ByteString.copyFrom(new byte[20]))).endsWith("…（共 20 字节）");
    }

    @Test
    void 票据各项都对_没有问题() {
        GateTokenPayload ok = ticket().build();
        assertThat(TravelChecks.ticketProblems(ok, target(ok, NOW + 300), PLAYER, 2, NOW)).isEmpty();
        assertThat(TravelChecks.ticketProblems(ok, target(ok, NOW + 300), PLAYER, 2, NOW - 5)).as("时钟差 5 s 之内").isEmpty();
        assertThat(TravelChecks.describe(ok)).isEqualTo("gate_node_id=1 zone_id=2 target_zone_id=2 player_id=" + Long.toUnsignedString(PLAYER)
                + " expire_timestamp=" + (NOW + 300));
    }

    @Test
    void 票据的每一项不对都单独报出() {
        GateTokenPayload otherHolder = ticket().setPlayerId(42).build();
        assertThat(TravelChecks.ticketProblems(otherHolder, target(otherHolder, NOW + 300), PLAYER, 2, NOW))
                .singleElement().asString().startsWith("player_id=42");

        GateTokenPayload homeZone = ticket().setTargetZoneId(1).setZoneId(1).build();
        assertThat(TravelChecks.ticketProblems(homeZone, target(homeZone, NOW + 300), PLAYER, 2, NOW))
                .hasSize(2).anyMatch(p -> p.startsWith("target_zone_id=1")).anyMatch(p -> p.startsWith("zone_id=1"));

        GateTokenPayload expired = ticket().setExpireTimestamp(NOW).build();
        assertThat(TravelChecks.ticketProblems(expired, target(expired, NOW), PLAYER, 2, NOW))
                .singleElement().asString().contains("不在 (" + NOW + ", " + (NOW + 305) + "]");
        GateTokenPayload tooLong = ticket().setExpireTimestamp(NOW + 600).build();
        assertThat(TravelChecks.ticketProblems(tooLong, target(tooLong, NOW + 600), PLAYER, 2, NOW)).as("普通令牌的 600 s 不是重定向票据")
                .singleElement().asString().contains("expire_timestamp=" + (NOW + 600));

        GateTokenPayload ok = ticket().build();
        assertThat(TravelChecks.ticketProblems(ok, target(ok, NOW + 299), PLAYER, 2, NOW))
                .singleElement().asString().contains("token_deadline=" + (NOW + 299));

        GateTokenPayload withKey = ticket().setHmacSessionKey(ByteString.copyFrom(new byte[32])).build();
        assertThat(TravelChecks.ticketProblems(withKey, target(withKey, NOW + 300), PLAYER, 2, NOW))
                .singleElement().asString().contains("hmac_session_key");
    }

    @Test
    void 没部署的区_9000起按标签散列_撞上真区就顺延() {
        int zone = TravelChecks.unknownZone("abc", 1, 2);
        assertThat(zone).isBetween(TravelChecks.UNKNOWN_ZONE_BASE, TravelChecks.UNKNOWN_ZONE_BASE + TravelChecks.UNKNOWN_ZONE_SPAN - 1)
                .isEqualTo(TravelChecks.UNKNOWN_ZONE_BASE + Math.floorMod("abc".hashCode(), TravelChecks.UNKNOWN_ZONE_SPAN));
        assertThat(TravelChecks.unknownZone("abc", zone, zone + 1)).isEqualTo(zone + 2);
        assertThat(TravelChecks.unknownZone("abc", 1, 2)).as("同一个标签每次一样").isEqualTo(zone);
    }

    @Test
    void 结果行字段_与基线同名同序_后接hops与reconnect_gate() {
        assertThat(TravelChecks.okFields(PLAYER, 1, 2, "h:1", "v:2", "b:3", 11, 18, true, true, 3024, 3007, 3, "r:4"))
                .isEqualTo("player_id=" + Long.toUnsignedString(PLAYER) + " home_zone=1 visit_zone=2 home_gate=h:1 visit_gate=v:2 back_gate=b:3"
                        + " gold_home=11 gold_back=18 move_ack=true ticket_binding=true reject_tip=3024 reject_map_tip=3007 hops=3 reconnect_gate=r:4");
    }
}
