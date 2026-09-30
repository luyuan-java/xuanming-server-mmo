package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NodeLinkAuthTest {

    private static final NodeLinkAuth AUTH = NodeLinkAuth.ofUtf8("link-secret");
    private static final long NOW = 1_800_000_000L;
    private static final String GATE = "gate-uuid";
    private static final long EPOCH = 7;

    @Test
    void 输入串格式固定_数字按无符号十进制() {
        assertThat(NodeLinkAuth.canonical(3, GATE, 1, EPOCH, NOW)).isEqualTo("3|gate-uuid|1|7|1800000000");
        assertThat(NodeLinkAuth.canonical(-1, "x", -1, -1L, -1L))
                .isEqualTo("4294967295|x|4294967295|18446744073709551615|18446744073709551615");
    }

    @Test
    void MAC是64字节小写hex且等于对输入串做HMAC_SHA256() {
        ByteString mac = AUTH.sign(3, GATE, 1, EPOCH, NOW);
        assertThat(mac.size()).isEqualTo(64);
        assertThat(mac.toString(StandardCharsets.US_ASCII)).matches("[0-9a-f]{64}");
        // 与 gate 令牌同一套 HMAC：用同一密钥对同一输入串签名，结果逐字节相同。
        ByteString viaGateTokens = GateTokens.ofUtf8("link-secret")
                .sign(ByteString.copyFromUtf8(NodeLinkAuth.canonical(3, GATE, 1, EPOCH, NOW)));
        assertThat(mac).isEqualTo(viaGateTokens);
    }

    @Test
    void 正确MAC且时间戳在窗口内_通过() {
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, NOW, AUTH.sign(3, GATE, 1, EPOCH, NOW), NOW))
                .isEqualTo(NodeLinkAuth.Verdict.OK);
        long edge = NOW - NodeLinkAuth.MAX_CLOCK_SKEW_SECONDS;
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, edge, AUTH.sign(3, GATE, 1, EPOCH, edge), NOW))
                .isEqualTo(NodeLinkAuth.Verdict.OK);
        long ahead = NOW + NodeLinkAuth.MAX_CLOCK_SKEW_SECONDS;
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, ahead, AUTH.sign(3, GATE, 1, EPOCH, ahead), NOW))
                .isEqualTo(NodeLinkAuth.Verdict.OK);
    }

    @Test
    void 密钥不同或任一字段被改_MAC不对() {
        ByteString mac = AUTH.sign(3, GATE, 1, EPOCH, NOW);
        assertThat(NodeLinkAuth.ofUtf8("other").verify(3, GATE, 1, EPOCH, NOW, mac, NOW))
                .isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(4, GATE, 1, EPOCH, NOW, mac, NOW)).isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(3, "gate-other", 1, EPOCH, NOW, mac, NOW)).isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(3, GATE, 2, EPOCH, NOW, mac, NOW)).isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(3, GATE, 1, EPOCH + 1, NOW, mac, NOW)).as("租约代次改大即失效")
                .isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, NOW + 1, mac, NOW)).isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, NOW, ByteString.EMPTY, NOW)).isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        ByteString upper = ByteString.copyFrom(mac.toString(StandardCharsets.US_ASCII).toUpperCase(), StandardCharsets.US_ASCII);
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, NOW, upper, NOW)).as("只认小写 hex").isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
    }

    @Test
    void 时间戳偏差超过60秒_判过期() {
        long old = NOW - NodeLinkAuth.MAX_CLOCK_SKEW_SECONDS - 1;
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, old, AUTH.sign(3, GATE, 1, EPOCH, old), NOW))
                .isEqualTo(NodeLinkAuth.Verdict.STALE_TIMESTAMP);
        long future = NOW + NodeLinkAuth.MAX_CLOCK_SKEW_SECONDS + 1;
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, future, AUTH.sign(3, GATE, 1, EPOCH, future), NOW))
                .isEqualTo(NodeLinkAuth.Verdict.STALE_TIMESTAMP);
        assertThat(AUTH.verify(3, GATE, 1, EPOCH, -1L, AUTH.sign(3, GATE, 1, EPOCH, -1L), NOW))
                .as("uint64 超过 long 上限").isEqualTo(NodeLinkAuth.Verdict.STALE_TIMESTAMP);
    }

    @Test
    void 空密钥拒绝构造() {
        assertThatThrownBy(() -> NodeLinkAuth.ofUtf8("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodeLinkAuth.ofUtf8(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 环境变量缺失或空白_拒绝启动() {
        assertThatThrownBy(() -> NodeLinkAuth.requireFromEnvValue(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(NodeLinkAuth.SECRET_ENV);
        assertThatThrownBy(() -> NodeLinkAuth.requireFromEnvValue("  "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(NodeLinkAuth.SECRET_ENV);
        NodeLinkAuth auth = NodeLinkAuth.requireFromEnvValue("link-secret");
        assertThat(auth.sign(3, GATE, 1, EPOCH, NOW)).isEqualTo(AUTH.sign(3, GATE, 1, EPOCH, NOW));
    }
}
