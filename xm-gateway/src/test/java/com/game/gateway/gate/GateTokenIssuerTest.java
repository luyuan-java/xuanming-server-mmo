package com.game.gateway.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GateTokens;
import com.game.gateway.gate.GateTokenIssuer.IssuedGateToken;
import com.game.proto.GateTokenPayload;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GateTokenIssuerTest {

    private static final long NOW = 1_800_000_000L;
    private static final GateTokens TOKENS = GateTokens.ofUtf8("issuer-test-secret");
    private static final GateTokenIssuer ISSUER = new GateTokenIssuer(
            TOKENS, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), new SecureRandom());

    @Test
    void 令牌字段与mmorpg形状一致_目标zone钉死为签发zone() throws Exception {
        IssuedGateToken token = ISSUER.issue(7, 3);
        GateTokenPayload payload = GateTokenPayload.parseFrom(token.payload());

        assertThat(payload.getGateNodeId()).isEqualTo(7);
        assertThat(payload.getZoneId()).isEqualTo(3);
        assertThat(payload.getTargetZoneId()).isEqualTo(3);
        assertThat(payload.getExpireTimestamp()).isEqualTo(NOW + 600);
        assertThat(payload.getHmacSessionKey().size()).isEqualTo(32);
        assertThat(payload.getPlayerId()).isZero();
        assertThat(token.deadlineEpochSec()).isEqualTo(NOW + 600);
    }

    @Test
    void 签名是64字节小写hex_且只对选中的gate与zone有效() {
        IssuedGateToken token = ISSUER.issue(7, 3);

        assertThat(token.signature().toString(StandardCharsets.US_ASCII)).matches("[0-9a-f]{64}");
        assertThat(TOKENS.verify(token.payload(), token.signature(), 7, 3, NOW).ok()).isTrue();
        assertThat(TOKENS.verify(token.payload(), token.signature(), 8, 3, NOW).failure())
                .isEqualTo(GateTokens.Failure.WRONG_GATE);
        assertThat(TOKENS.verify(token.payload(), token.signature(), 7, 4, NOW).failure())
                .isEqualTo(GateTokens.Failure.WRONG_ZONE);
        assertThat(TOKENS.verify(token.payload(), token.signature(), 7, 3, NOW + 600).failure())
                .isEqualTo(GateTokens.Failure.EXPIRED);
        assertThat(GateTokens.ofUtf8("other").verify(token.payload(), token.signature(), 7, 3, NOW).failure())
                .isEqualTo(GateTokens.Failure.BAD_SIGNATURE);
    }

    @Test
    void 每次签发都换新的会话密钥() throws Exception {
        GateTokenPayload a = GateTokenPayload.parseFrom(ISSUER.issue(7, 3).payload());
        GateTokenPayload b = GateTokenPayload.parseFrom(ISSUER.issue(7, 3).payload());

        assertThat(a.getHmacSessionKey()).isNotEqualTo(b.getHmacSessionKey());
    }
}
