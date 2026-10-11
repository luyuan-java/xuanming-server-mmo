package com.game.robot.scenario;

import com.game.proto.GateTokenPayload;
import com.game.proto.TravelToZoneResponse;
import com.game.robot.client.Received;
import com.game.robot.client.RedirectTarget;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;

/**
 * travel 场景的纯判据（批次 5.4，zone-travel-spec §11.11；TravelChecksTest 钉住）：226 应答的拒绝码与受理时的字节形态、
 * 124 票据的各项、没部署的区号、结果行字段。不碰网络。
 */
final class TravelChecks {

    /** 票据有效期上限的余量：SM 签 now + 300 s（{@code REDIRECT_TICKET_TTL}），两边时钟差给 5 s（§11.11 T5「deadline ∈ (now, now + 305]」）。 */
    static final long TICKET_TTL_SECONDS = 300;
    static final long CLOCK_SLACK_SECONDS = 5;
    /** 受理时 226 应答体的字节：{@code error_message} 是空的子消息（§1.3、§4.5：恒存在，编码为 {@code 0a 00}）。 */
    static final ByteString ACCEPTED_BODY = ByteString.copyFrom(new byte[] {0x0a, 0x00});
    /** T4 用的「没有部署的区」：9000 起的一千个号里按运行标签取一个（每轮不同，不会撞上切片的 1、2）。 */
    static final int UNKNOWN_ZONE_BASE = 9000;
    static final int UNKNOWN_ZONE_SPAN = 1000;

    private TravelChecks() {
    }

    /**
     * 226 应答的拒绝码：信封上的传输层错误优先（限频 1008、热关停 1003 ……，说明请求没到 scene），否则应答体的
     * {@code error_message.id}；受理是 0；解不开为 -1。客户端只能用 {@code error_message.id ≠ 0} 判拒绝（§1.3）。
     */
    static int replyTip(Received reply) {
        if (reply.envelopeTipId() != 0) {
            return reply.envelopeTipId();
        }
        TravelToZoneResponse response = reply.parseOrNull(TravelToZoneResponse.parser());
        if (response == null) {
            return -1;
        }
        return response.hasErrorMessage() ? response.getErrorMessage().getId() : 0;
    }

    /** 受理的应答体是否恰好是 {@code 0a 00}（{@code error_message} 存在且为空；没带它、或带了别的字段都不是）。 */
    static boolean acceptedEncoding(Received reply) {
        return reply.envelopeTipId() == 0 && reply.content().getSerializedMessage().equals(ACCEPTED_BODY);
    }

    /** 应答体的十六进制（报告里用；长了截断）。 */
    static String hex(ByteString bytes) {
        StringBuilder out = new StringBuilder();
        int n = Math.min(bytes.size(), 16);
        for (int i = 0; i < n; i++) {
            out.append(i == 0 ? "" : " ").append(String.format("%02x", bytes.byteAt(i) & 0xff));
        }
        return bytes.size() > n ? out + " …（共 " + bytes.size() + " 字节）" : out.toString();
    }

    /**
     * 124 票据的各项（§1.4、§4.5）：持票者、目标区、目标 gate 所在区、到期时刻（∈ (now, now + 305]，且与 124 的 {@code token_deadline}
     * 相同）、不带会话密钥。
     *
     * @param target          124 的内容（{@code token_deadline} 取自它）
     * @param playerId        期望的持票者
     * @param targetZone      期望的目标区（{@code target_zone_id} 与 {@code zone_id} 都应是它）
     * @param nowEpochSeconds 收到 124 时的 Unix 秒
     * @return 不符之处；空 = 全部符合
     */
    static List<String> ticketProblems(GateTokenPayload ticket, RedirectTarget target, long playerId, int targetZone, long nowEpochSeconds) {
        List<String> problems = new ArrayList<>();
        if (ticket.getPlayerId() != playerId) {
            problems.add("player_id=" + Long.toUnsignedString(ticket.getPlayerId()) + "，期望持票者 " + Long.toUnsignedString(playerId));
        }
        if (ticket.getTargetZoneId() != targetZone) {
            problems.add("target_zone_id=" + Integer.toUnsignedString(ticket.getTargetZoneId()) + "，期望 " + targetZone);
        }
        if (ticket.getZoneId() != targetZone) {
            problems.add("zone_id=" + Integer.toUnsignedString(ticket.getZoneId()) + "，期望目标 gate 在区 " + targetZone);
        }
        long expire = ticket.getExpireTimestamp();
        if (expire <= nowEpochSeconds || expire > nowEpochSeconds + TICKET_TTL_SECONDS + CLOCK_SLACK_SECONDS) {
            problems.add("expire_timestamp=" + expire + " 不在 (" + nowEpochSeconds + ", " + (nowEpochSeconds + TICKET_TTL_SECONDS
                    + CLOCK_SLACK_SECONDS) + "]");
        }
        if (target.tokenDeadline() != expire) {
            problems.add("124 的 token_deadline=" + target.tokenDeadline() + " 与票据 expire_timestamp=" + expire + " 不同");
        }
        if (!ticket.getHmacSessionKey().isEmpty()) {
            problems.add("重定向票据带了 hmac_session_key（" + ticket.getHmacSessionKey().size() + " 字节），应不填");
        }
        return problems;
    }

    /** 票据的摘要（报告里用；不打签名）。 */
    static String describe(GateTokenPayload ticket) {
        return "gate_node_id=" + Integer.toUnsignedString(ticket.getGateNodeId()) + " zone_id=" + Integer.toUnsignedString(ticket.getZoneId())
                + " target_zone_id=" + Integer.toUnsignedString(ticket.getTargetZoneId()) + " player_id="
                + Long.toUnsignedString(ticket.getPlayerId()) + " expire_timestamp=" + ticket.getExpireTimestamp();
    }

    /**
     * T4 的目标区：{@value #UNKNOWN_ZONE_BASE} + 运行标签散列 % {@value #UNKNOWN_ZONE_SPAN}，撞上两个真区就顺延。
     * 每轮不同，免得哪次真有人建了这个区、所有运行一起失真。
     */
    static int unknownZone(String runTag, int homeZone, int visitZone) {
        int zone = UNKNOWN_ZONE_BASE + Math.floorMod(runTag.hashCode(), UNKNOWN_ZONE_SPAN);
        while (zone == homeZone || zone == visitZone) {
            zone++;
        }
        return zone;
    }

    /**
     * 结果行 {@code TRAVEL_SMOKE_OK} 之后的字段：与基线 robot 同名同序（{@code travel_smoke_scenario.go:369}），后接 Java 的
     * {@code hops}、{@code reconnect_gate}。号按无符号十进制、gate 是 {@code host:port}、布尔是 {@code true / false}。
     */
    static String okFields(long playerId, int homeZone, int visitZone, String homeGate, String visitGate, String backGate, long goldHome,
                           long goldBack, boolean moveAck, boolean ticketBinding, int rejectTip, int rejectMapTip, int hops,
                           String reconnectGate) {
        return "player_id=" + Long.toUnsignedString(playerId) + " home_zone=" + homeZone + " visit_zone=" + visitZone
                + " home_gate=" + homeGate + " visit_gate=" + visitGate + " back_gate=" + backGate
                + " gold_home=" + Long.toUnsignedString(goldHome) + " gold_back=" + Long.toUnsignedString(goldBack)
                + " move_ack=" + moveAck + " ticket_binding=" + ticketBinding + " reject_tip=" + rejectTip + " reject_map_tip=" + rejectMapTip
                + " hops=" + hops + " reconnect_gate=" + reconnectGate;
    }
}
