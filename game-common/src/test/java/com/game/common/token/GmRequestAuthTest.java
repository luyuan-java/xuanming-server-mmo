package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GmRequestAuth.Envelope;
import com.game.common.token.GmRequestAuth.Result;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** GM 运维指令签名：没配密钥拒、信封格式、时间窗、签名绑方法名与节点号、nonce 去重与表满即拒、时间窗配置钳位。 */
class GmRequestAuthTest {

    private static final byte[] SECRET = "gm-secret".getBytes(StandardCharsets.UTF_8);
    private static final String METHOD = "Gate.GmGracefulShutdown";
    private final AtomicLong now = new AtomicLong(1_800_000_000L);
    private final GmRequestAuth auth = new GmRequestAuth(SECRET, 300, now::get);

    private Envelope envelope(String method, String node, String nonce, long ts, String reason) {
        String canonical = GmRequestAuth.canonical(method, node, "ops", Long.toString(ts), nonce, reason);
        return new Envelope("ops", Long.toString(ts), nonce, GmRequestAuth.sign(SECRET, canonical));
    }

    @Test
    void 签名对_时间窗内_通过_同一nonce重放拒() {
        Envelope e = envelope(METHOD, "3", "n1", now.get(), "维护");
        assertThat(auth.verify(METHOD, "3", e, "维护")).isEqualTo(Result.OK);
        assertThat(auth.verify(METHOD, "3", e, "维护")).isEqualTo(Result.REPLAYED_NONCE);
        now.addAndGet(601);
        assertThat(auth.verify(METHOD, "3", envelope(METHOD, "3", "n1", now.get(), "维护"), "维护"))
                .as("过了 2 × 窗口 nonce 可再用").isEqualTo(Result.OK);
    }

    @Test
    void 签名绑方法名_节点号_原因() {
        Envelope e = envelope(METHOD, "3", "n2", now.get(), "维护");
        assertThat(auth.verify("Scene.GmGracefulShutdown", "3", e, "维护")).isEqualTo(Result.SIGNATURE_MISMATCH);
        assertThat(auth.verify(METHOD, "4", e, "维护")).isEqualTo(Result.SIGNATURE_MISMATCH);
        assertThat(auth.verify(METHOD, "3", e, "别的原因")).isEqualTo(Result.SIGNATURE_MISMATCH);
        assertThat(auth.verify(METHOD, "3", e, "维护")).as("验签失败不登记 nonce").isEqualTo(Result.OK);
    }

    @Test
    void 时间窗外拒_没配密钥拒() {
        assertThat(auth.verify(METHOD, "3", envelope(METHOD, "3", "n3", now.get() - 301, ""), ""))
                .isEqualTo(Result.TIMESTAMP_OUT_OF_WINDOW);
        assertThat(auth.verify(METHOD, "3", envelope(METHOD, "3", "n4", now.get() + 301, ""), ""))
                .isEqualTo(Result.TIMESTAMP_OUT_OF_WINDOW);
        GmRequestAuth none = GmRequestAuth.fromEnvValues(null, null, now::get);
        assertThat(none.configured()).isFalse();
        assertThat(none.verify(METHOD, "3", envelope(METHOD, "3", "n5", now.get(), ""), ""))
                .isEqualTo(Result.SECRET_NOT_CONFIGURED);
    }

    @Test
    void 信封格式不对拒() {
        Envelope ok = envelope(METHOD, "3", "n6", now.get(), "");
        assertThat(auth.verify(METHOD, "3", new Envelope("", ok.timestamp(), ok.nonce(), ok.signature()), ""))
                .isEqualTo(Result.MALFORMED_ENVELOPE);
        assertThat(auth.verify(METHOD, "3", new Envelope("o p", ok.timestamp(), ok.nonce(), ok.signature()), ""))
                .isEqualTo(Result.MALFORMED_ENVELOPE);
        assertThat(auth.verify(METHOD, "3", new Envelope("ops", "-1", ok.nonce(), ok.signature()), ""))
                .isEqualTo(Result.MALFORMED_ENVELOPE);
        assertThat(auth.verify(METHOD, "3", new Envelope("ops", ok.timestamp(), ok.nonce(), ok.signature().toUpperCase()),
                "")).isEqualTo(Result.MALFORMED_ENVELOPE);
        assertThat(auth.verify(METHOD, "3", new Envelope("ops", ok.timestamp(), "n".repeat(129), ok.signature()), ""))
                .isEqualTo(Result.MALFORMED_ENVELOPE);
        assertThat(auth.verify(METHOD, "3", null, "")).isEqualTo(Result.MALFORMED_ENVELOPE);
    }

    @Test
    void 原因超长拒() {
        String reason = "x".repeat(GmRequestAuth.MAX_REASON_LENGTH + 1);
        assertThat(auth.verify(METHOD, "3", envelope(METHOD, "3", "long", now.get(), reason), reason))
                .isEqualTo(Result.MALFORMED_ENVELOPE);
    }

    @Test
    void nonce表满即拒_不淘汰旧项() {
        for (int i = 0; i < GmRequestAuth.MAX_NONCES; i++) {
            assertThat(auth.verify(METHOD, "3", envelope(METHOD, "3", "f" + i, now.get(), ""), "")).isEqualTo(Result.OK);
        }
        assertThat(auth.verify(METHOD, "3", envelope(METHOD, "3", "full", now.get(), ""), ""))
                .isEqualTo(Result.REPLAYED_NONCE);
    }

    @Test
    void 时间窗配置_缺省300_钳到30到900() {
        assertThat(GmRequestAuth.resolveSkew(null)).isEqualTo(300);
        assertThat(GmRequestAuth.resolveSkew("abc")).isEqualTo(300);
        assertThat(GmRequestAuth.resolveSkew("5")).isEqualTo(30);
        assertThat(GmRequestAuth.resolveSkew("100000")).isEqualTo(900);
        assertThat(GmRequestAuth.resolveSkew(" 120 ")).isEqualTo(120);
    }
}
