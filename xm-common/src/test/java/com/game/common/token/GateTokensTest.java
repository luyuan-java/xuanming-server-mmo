package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.common.base.GateTokenPayload;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GateTokensTest {

    private static final GateTokens TOKENS = GateTokens.ofUtf8("dev-secret");
    private static final long NOW = 1_800_000_000L;

    private static ByteString payload(int gate, int targetZone, long expire) {
        return GateTokenPayload.newBuilder().setGateNodeId(gate).setZoneId(1).setTargetZoneId(targetZone)
                .setExpireTimestamp(expire).build().toByteString();
    }

    @Test
    void 签名是64字节小写hex_与_HMAC_SHA256_向量一致() {
        // 向量：HMAC-SHA256(key="key", msg="The quick brown fox jumps over the lazy dog")
        GateTokens tokens = GateTokens.ofUtf8("key");
        ByteString sig = tokens.sign(ByteString.copyFromUtf8("The quick brown fox jumps over the lazy dog"));
        assertThat(sig.toString(StandardCharsets.US_ASCII))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void 自签自验通过() {
        ByteString p = payload(3, 0, NOW + 600);
        GateTokens.Verdict v = TOKENS.verify(p, TOKENS.sign(p), 3, 1, NOW);
        assertThat(v.ok()).isTrue();
        assertThat(v.payload().getGateNodeId()).isEqualTo(3);
    }

    @Test
    void 按_C_plus_plus_顺序拒绝() {
        ByteString p = payload(3, 0, NOW + 600);
        ByteString sig = TOKENS.sign(p);
        assertThat(GateTokens.ofUtf8("other").verify(p, sig, 3, 1, NOW).failure()).isEqualTo(GateTokens.Failure.BAD_SIGNATURE);
        assertThat(TOKENS.verify(p, sig, 4, 1, NOW).failure()).isEqualTo(GateTokens.Failure.WRONG_GATE);

        ByteString wrongZone = payload(3, 2, NOW + 600);
        assertThat(TOKENS.verify(wrongZone, TOKENS.sign(wrongZone), 3, 1, NOW).failure()).isEqualTo(GateTokens.Failure.WRONG_ZONE);

        ByteString expired = payload(3, 0, NOW);
        assertThat(TOKENS.verify(expired, TOKENS.sign(expired), 3, 1, NOW).failure()).isEqualTo(GateTokens.Failure.EXPIRED);

        ByteString garbage = ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF});
        assertThat(TOKENS.verify(garbage, TOKENS.sign(garbage), 3, 1, NOW).failure()).isEqualTo(GateTokens.Failure.BAD_PAYLOAD);
    }
}
