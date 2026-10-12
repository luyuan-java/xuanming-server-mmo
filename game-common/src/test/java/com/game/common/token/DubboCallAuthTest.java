package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

class DubboCallAuthTest {

    private static final DubboCallAuth AUTH = DubboCallAuth.ofUtf8("dubbo-secret");
    private static final String SERVICE = "com.game.api.ClientMessageService";
    private static final long NOW = 1_800_000_000L;

    @Test
    void 输入串格式固定() {
        assertThat(DubboCallAuth.canonical(SERVICE, "handle", NOW))
                .isEqualTo("com.game.api.ClientMessageService|handle|1800000000");
    }

    @Test
    void MAC是64字节小写hex且与其他HMAC用途同一算法() {
        String mac = AUTH.sign(SERVICE, "handle", NOW);
        assertThat(mac).matches("[0-9a-f]{64}");
        ByteString viaGateTokens = GateTokens.ofUtf8("dubbo-secret")
                .sign(ByteString.copyFromUtf8(DubboCallAuth.canonical(SERVICE, "handle", NOW)));
        assertThat(mac).isEqualTo(viaGateTokens.toStringUtf8());
    }

    @Test
    void 正确附件在时间窗内通过() {
        assertThat(AUTH.verify(SERVICE, "handle", Long.toString(NOW), AUTH.sign(SERVICE, "handle", NOW), NOW))
                .isEqualTo(DubboCallAuth.Verdict.OK);
        long edge = NOW - DubboCallAuth.MAX_CLOCK_SKEW_SECONDS;
        assertThat(AUTH.verify(SERVICE, "handle", Long.toString(edge), AUTH.sign(SERVICE, "handle", edge), NOW))
                .isEqualTo(DubboCallAuth.Verdict.OK);
    }

    @Test
    void 没带附件_拒绝() {
        assertThat(AUTH.verify(SERVICE, "handle", null, null, NOW)).isEqualTo(DubboCallAuth.Verdict.MISSING);
        assertThat(AUTH.verify(SERVICE, "handle", Long.toString(NOW), null, NOW)).isEqualTo(DubboCallAuth.Verdict.MISSING);
    }

    @Test
    void 密钥不同_方法或服务或时间戳被改_拒绝() {
        String mac = AUTH.sign(SERVICE, "handle", NOW);
        String ts = Long.toString(NOW);
        assertThat(DubboCallAuth.ofUtf8("other").verify(SERVICE, "handle", ts, mac, NOW))
                .isEqualTo(DubboCallAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(SERVICE, "sessionClosed", ts, mac, NOW)).isEqualTo(DubboCallAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify("com.game.api.SceneDirectoryService", "handle", ts, mac, NOW))
                .isEqualTo(DubboCallAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(SERVICE, "handle", Long.toString(NOW + 1), mac, NOW)).isEqualTo(DubboCallAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(SERVICE, "handle", "not-a-number", mac, NOW)).isEqualTo(DubboCallAuth.Verdict.BAD_MAC);
        assertThat(AUTH.verify(SERVICE, "handle", ts, mac.toUpperCase(), NOW)).as("只认小写 hex")
                .isEqualTo(DubboCallAuth.Verdict.BAD_MAC);
    }

    @Test
    void 时间戳超出窗口_拒绝() {
        long old = NOW - DubboCallAuth.MAX_CLOCK_SKEW_SECONDS - 1;
        assertThat(AUTH.verify(SERVICE, "handle", Long.toString(old), AUTH.sign(SERVICE, "handle", old), NOW))
                .isEqualTo(DubboCallAuth.Verdict.STALE_TIMESTAMP);
        long future = NOW + DubboCallAuth.MAX_CLOCK_SKEW_SECONDS + 1;
        assertThat(AUTH.verify(SERVICE, "handle", Long.toString(future), AUTH.sign(SERVICE, "handle", future), NOW))
                .isEqualTo(DubboCallAuth.Verdict.STALE_TIMESTAMP);
        String huge = Long.toUnsignedString(-1L);
        assertThat(AUTH.verify(SERVICE, "handle", huge, AUTH.sign(SERVICE, "handle", -1L), NOW))
                .as("uint64 超过 long 上限").isEqualTo(DubboCallAuth.Verdict.STALE_TIMESTAMP);
    }

    @Test
    void 环境变量缺失或空白_拒绝启动() {
        assertThatThrownBy(() -> DubboCallAuth.requireFromEnvValue(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(DubboCallAuth.SECRET_ENV);
        assertThatThrownBy(() -> DubboCallAuth.requireFromEnvValue(" "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(DubboCallAuth.SECRET_ENV);
        assertThat(DubboCallAuth.requireFromEnvValue("dubbo-secret").sign(SERVICE, "handle", NOW))
                .isEqualTo(AUTH.sign(SERVICE, "handle", NOW));
    }
}
