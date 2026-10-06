package com.game.match.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.RespondChallengeResponse;
import com.game.proto.match.WatchBattleResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 匹配回给客户端的 tip：码与 {@code parameters[0]} <b>逐字节</b>钉住（match-spec §2.2、§4.1、§6.1、§8.2、§15.1）。期望值是照规格的表另抄的一份
 * （与基线 {@code go/match/internal/logic/*.go} 的 {@code tipErr(...)} 逐条对过）：谁改了 {@code MatchTip} 里的一个字、一个标点，这里就会失败。
 * 逗号是<b>半角</b>的——那是基线源码的原样，客户端可见。
 */
class MatchTipsTest {

    /** 规格表：发生点 → (码, parameters[0])。 */
    private static final Map<MatchTip, Object[]> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put(MatchTip.NO_IDENTITY, new Object[] {16004, "缺少玩家身份"});
        EXPECTED.put(MatchTip.BUSY, new Object[] {16004, "服务器繁忙,请稍后再试"});
        EXPECTED.put(MatchTip.CHALLENGE_INVITE_PUSH_FAILED, new Object[] {16004, "邀请发送失败,请稍后再试"});
        EXPECTED.put(MatchTip.JOIN_IN_BATTLE, new Object[] {16000, "战斗尚未结束,无法排队"});
        EXPECTED.put(MatchTip.JOIN_ALREADY_QUEUED, new Object[] {16001, "已在匹配队列中"});
        EXPECTED.put(MatchTip.JOIN_MODE_NOT_OPEN, new Object[] {16002, "该匹配模式未开放"});
        EXPECTED.put(MatchTip.JOIN_TEAM_SIZE_NOT_CONFIGURED, new Object[] {16003, "该副本未开放组队"});
        EXPECTED.put(MatchTip.JOIN_NOT_IN_SCENE, new Object[] {16020, "请先进入场景"});
        EXPECTED.put(MatchTip.CHALLENGE_SELF, new Object[] {16007, "不能挑战自己"});
        EXPECTED.put(MatchTip.CHALLENGE_SELF_BUSY, new Object[] {16010, "战斗尚未结束,无法发起切磋"});
        EXPECTED.put(MatchTip.CHALLENGE_TARGET_BUSY, new Object[] {16009, "对方正在战斗中"});
        EXPECTED.put(MatchTip.CHALLENGE_TARGET_OFFLINE, new Object[] {16008, "对方不在线"});
        EXPECTED.put(MatchTip.CHALLENGE_PENDING, new Object[] {16011, "对方已有待处理的切磋邀请"});
        EXPECTED.put(MatchTip.CHALLENGE_EXPIRED, new Object[] {16012, "切磋邀请已过期"});
        EXPECTED.put(MatchTip.CHALLENGE_NOT_TARGET, new Object[] {16013, "该邀请不是发给你的"});
        EXPECTED.put(MatchTip.CHALLENGE_CHALLENGER_BUSY, new Object[] {16012, "发起者已进入其它战斗"});
        EXPECTED.put(MatchTip.CHALLENGE_RESPONDER_BUSY, new Object[] {16010, "战斗尚未结束,无法应战"});
        EXPECTED.put(MatchTip.REISSUE_BATTLE_GONE, new Object[] {1005, "该战斗不存在或已结束"});
        EXPECTED.put(MatchTip.REISSUE_BATTLE_UNAVAILABLE, new Object[] {1003, "战斗服务暂不可用"});
        EXPECTED.put(MatchTip.FEATURE_UNAVAILABLE, new Object[] {1006, null});
    }

    @Test
    void 规格表覆盖全部发生点_不多不少() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(MatchTip.class));
    }

    @Test
    void 每个发生点的码与文案与规格表逐字相同() {
        EXPECTED.forEach((tip, expected) -> {
            assertThat(tip.code()).as(tip + " 的码").isEqualTo(expected[0]);
            assertThat(tip.text()).as(tip + " 的 parameters[0]").isEqualTo(expected[1]);
        });
    }

    @Test
    void 线上的字节逐字节相同_TipInfoMessage只有id与一条parameters() throws Exception {
        for (Map.Entry<MatchTip, Object[]> entry : EXPECTED.entrySet()) {
            MatchTip tip = entry.getKey();
            int code = (Integer) entry.getValue()[0];
            String text = (String) entry.getValue()[1];
            // 手工拼 protobuf：字段 1（varint）= id；字段 2（长度前缀）= UTF-8 文案
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            wire.write(0x08);
            writeVarint(wire, code);
            if (text != null) {
                byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
                wire.write(0x12);
                writeVarint(wire, utf8.length);
                wire.write(utf8);
            }

            assertThat(tip.proto().toByteArray()).as(tip + " 的线上字节").isEqualTo(wire.toByteArray());
            assertThat(tip.proto().getParametersCount()).as(tip + " 的 parameters 条数").isEqualTo(text == null ? 0 : 1);
        }
    }

    @Test
    void 逗号是半角的_没有任何全角标点() {
        int withComma = 0;
        for (MatchTip tip : MatchTip.values()) {
            String text = tip.text();
            if (text == null) {
                continue;
            }
            assertThat(text).as(tip.name()).doesNotContain("，").doesNotContain("。").doesNotContain("、").doesNotContain("！").doesNotContain(" ");
            if (text.indexOf(',') >= 0) {
                withComma++;
                assertThat((int) text.charAt(text.indexOf(','))).isEqualTo(0x2C);
                assertThat(text.chars().filter(c -> c == ',').count()).as(tip + "：恰好一个逗号").isEqualTo(1);
            }
        }
        assertThat(withComma).as("带逗号的文案共 5 条").isEqualTo(5);
    }

    @Test
    void 关键文案的UTF8字节_手抄一份对照() {
        // 「服务器繁忙,请稍后再试」：5 个汉字 + 0x2C + 5 个汉字 = 31 字节
        assertThat(hex(MatchTip.BUSY.text())).isEqualTo("e69c8de58aa1e599a8e7b981e5bf99" + "2c" + "e8afb7e7a88de5908ee5868de8af95");
        // 「战斗尚未结束,无法排队」
        assertThat(hex(MatchTip.JOIN_IN_BATTLE.text())).isEqualTo("e68898e69697e5b09ae69caae7bb93e69d9f" + "2c" + "e697a0e6b395e68e92e9989f");
        // 「该战斗不存在或已结束」（1005：客户端据此判 BattleGone）
        assertThat(hex(MatchTip.REISSUE_BATTLE_GONE.text())).isEqualTo("e8afa5e68898e69697e4b88de5ad98e59ca8e68896e5b7b2e7bb93e69d9f");
        assertThat(MatchTip.BUSY.text().getBytes(StandardCharsets.UTF_8)).hasSize(31);
    }

    @Test
    void 码取自导表生成的枚举_数值与契约一致() {
        assertThat(MatchTips.IN_BATTLE).isEqualTo(16000);
        assertThat(MatchTips.ALREADY_QUEUED).isEqualTo(16001);
        assertThat(MatchTips.MODE_NOT_OPEN).isEqualTo(16002);
        assertThat(MatchTips.TEAM_SIZE_NOT_CONFIGURED).isEqualTo(16003);
        assertThat(MatchTips.INTERNAL).isEqualTo(16004);
        assertThat(MatchTips.CHALLENGE_SELF).isEqualTo(16007);
        assertThat(MatchTips.CHALLENGE_TARGET_OFFLINE).isEqualTo(16008);
        assertThat(MatchTips.CHALLENGE_TARGET_BUSY).isEqualTo(16009);
        assertThat(MatchTips.CHALLENGE_SELF_BUSY).isEqualTo(16010);
        assertThat(MatchTips.CHALLENGE_PENDING).isEqualTo(16011);
        assertThat(MatchTips.CHALLENGE_EXPIRED).isEqualTo(16012);
        assertThat(MatchTips.CHALLENGE_NOT_TARGET).isEqualTo(16013);
        assertThat(MatchTips.NOT_IN_SCENE).isEqualTo(16020);
        assertThat(MatchTips.SERVICE_UNAVAILABLE).isEqualTo(1003);
        assertThat(MatchTips.INVALID_PARAMETER).isEqualTo(1005);
        assertThat(MatchTips.FEATURE_UNAVAILABLE).isEqualTo(1006);
    }

    @Test
    void 永不发出的两个码与观战的码不在发生点里() {
        for (MatchTip tip : MatchTip.values()) {
            assertThat(tip.code()).as(tip.name()).isNotIn(16005, 16006, 16014, 16015, 16016, 16017, 16018, 16019);
        }
    }

    @Test
    void 排队被拒_error_code与error_message同值_票号为空() {
        JoinQueueResponse response = MatchTips.joinRejected(MatchTip.JOIN_MODE_NOT_OPEN);

        assertThat(response.getErrorCode()).isEqualTo(16002).isEqualTo(response.getErrorMessage().getId());
        assertThat(response.getErrorMessage().getParametersList()).containsExactly("该匹配模式未开放");
        assertThat(response.getQueueTicket()).isEmpty();
    }

    @Test
    void 已在队列中_带现有票号_读不到时留空() {
        JoinQueueResponse withTicket = MatchTips.joinAlreadyQueued("6f1c2d3e-0000-4000-8000-000000000001");
        JoinQueueResponse unknown = MatchTips.joinAlreadyQueued(null);

        assertThat(withTicket.getErrorCode()).isEqualTo(16001).isEqualTo(withTicket.getErrorMessage().getId());
        assertThat(withTicket.getErrorMessage().getParametersList()).containsExactly("已在匹配队列中");
        assertThat(withTicket.getQueueTicket()).isEqualTo("6f1c2d3e-0000-4000-8000-000000000001");
        assertThat(unknown.getErrorCode()).isEqualTo(16001);
        assertThat(unknown.getQueueTicket()).isEmpty();
        assertThat(MatchTips.joinAlreadyQueued("").getQueueTicket()).isEmpty();
    }

    @Test
    void 排队受理_error_code为0_不带error_message_只有票号() throws Exception {
        JoinQueueResponse response = MatchTips.joinAccepted("t-1");

        assertThat(response.getErrorCode()).isZero();
        assertThat(response.hasErrorMessage()).as("成功应答体里绝不能出现 error_message").isFalse();
        assertThat(response.getQueueTicket()).isEqualTo("t-1");
        // 线上只有字段 2
        assertThat(response.toByteArray()).isEqualTo(new byte[] {0x12, 0x03, 't', '-', '1'});
        assertThatThrownBy(() -> MatchTips.joinAccepted("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchTips.joinAccepted(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 切磋的应答体() {
        ChallengePlayerResponse rejected = MatchTips.challengeRejected(MatchTip.CHALLENGE_PENDING);
        ChallengePlayerResponse sent = MatchTips.challengeSent(Long.MIN_VALUE + 9);
        RespondChallengeResponse expired = MatchTips.respondRejected(MatchTip.CHALLENGE_CHALLENGER_BUSY);

        assertThat(rejected.getChallengeId()).isZero();
        assertThat(rejected.getErrorMessage()).isEqualTo(tip(16011, "对方已有待处理的切磋邀请"));
        assertThat(sent.getChallengeId()).isEqualTo(Long.MIN_VALUE + 9);
        assertThat(sent.hasErrorMessage()).isFalse();
        assertThat(expired.getErrorMessage()).as("16012 但文案不是「已过期」").isEqualTo(tip(16012, "发起者已进入其它战斗"));
        assertThatThrownBy(() -> MatchTips.challengeSent(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 补签被match拒绝_只有error_message_没有assignment() {
        RequestBattleTicketResponse gone = MatchTips.reissueRejected(MatchTip.REISSUE_BATTLE_GONE);
        RequestBattleTicketResponse unavailable = MatchTips.reissueRejected(MatchTip.REISSUE_BATTLE_UNAVAILABLE);

        assertThat(gone.getErrorMessage()).isEqualTo(tip(1005, "该战斗不存在或已结束"));
        assertThat(gone.hasAssignment()).isFalse();
        assertThat(unavailable.getErrorMessage()).isEqualTo(tip(1003, "战斗服务暂不可用"));
        assertThat(MatchTips.reissueRejected(MatchTip.NO_IDENTITY).getErrorMessage()).isEqualTo(tip(16004, "缺少玩家身份"));
    }

    @Test
    void 观战的临时应答_in_band_1006_不带parameters() {
        WatchBattleResponse response = MatchTips.watchUnavailable();

        assertThat(response.getErrorMessage().getId()).isEqualTo(1006);
        assertThat(response.getErrorMessage().getParametersList()).isEmpty();
        assertThat(response.getBattleId()).isZero();
        assertThat(response.toByteArray()).as("字段 2 = TipInfoMessage{id = 1006}").isEqualTo(new byte[] {0x12, 0x03, 0x08, (byte) 0xEE, 0x07});
    }

    private static TipInfoMessage tip(int id, String text) {
        return TipInfoMessage.newBuilder().setId(id).addParameters(text).build();
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static String hex(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
