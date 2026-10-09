package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.token.GateTokenIssuer.IssuedGateToken;
import com.game.proto.GateTokenPayload;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
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

    // ------------------------------------------------------------------ 重定向票据（批次 5.4，zone-travel-spec §1.4、§11.3）

    /** 持票者：超过 32 位的号，保证 uint64 字段没有被截断。 */
    private static final long HOLDER = 0x0102_0304_0506_0708L;

    @Test
    void 重定向票据的有效期是300秒_不是普通令牌的600秒() {
        assertThat(GateTokenIssuer.REDIRECT_TICKET_TTL).isEqualTo(Duration.ofSeconds(300));
        assertThat(GateTokenIssuer.TOKEN_TTL).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 重定向票据的字段_带持票者与目标zone_不带会话密钥() throws Exception {
        // gate 所在的 zone 与目标 zone 故意取不同的值：两个参数各进各的字段，写反了这里就红
        IssuedGateToken token = ISSUER.issueRedirect(9, 5, HOLDER, 6);
        GateTokenPayload payload = GateTokenPayload.parseFrom(token.payload());

        assertThat(payload.getGateNodeId()).isEqualTo(9);
        assertThat(payload.getZoneId()).isEqualTo(5);
        assertThat(payload.getExpireTimestamp()).isEqualTo(NOW + 300);
        assertThat(payload.getPlayerId()).isEqualTo(HOLDER);
        assertThat(payload.getTargetZoneId()).isEqualTo(6);
        assertThat(payload.getHmacSessionKey().isEmpty()).as("重定向票据不带会话密钥").isTrue();
        assertThat(token.deadlineEpochSec()).as("124 的 token_deadline 取它").isEqualTo(NOW + 300);
        assertThat(token.signature().toString(StandardCharsets.US_ASCII)).matches("[0-9a-f]{64}");
        assertThat(token.signature()).isEqualTo(TOKENS.sign(token.payload()));
    }

    @Test
    void 重定向票据在目标zone的那台gate上验得过_验票结果带出持票者与目标zone() {
        IssuedGateToken token = ISSUER.issueRedirect(9, 2, HOLDER, 2);

        GateTokens.Verdict verdict = TOKENS.verify(token.payload(), token.signature(), 9, 2, NOW);

        assertThat(verdict.failure()).isNull();
        assertThat(verdict.payload().getPlayerId()).isEqualTo(HOLDER);
        assertThat(verdict.payload().getTargetZoneId()).isEqualTo(2);
        assertThat(verdict.payload().getZoneId()).isEqualTo(2);
    }

    @Test
    void 重定向票据_同号gate在别的zone回WRONG_ZONE_别的gate先回WRONG_GATE() {
        IssuedGateToken token = ISSUER.issueRedirect(9, 2, HOLDER, 2);

        // 两个 zone 的 gate 节点号各自从 1 起发，同号是常态：靠 target_zone_id 挡住
        assertThat(TOKENS.verify(token.payload(), token.signature(), 9, 1, NOW).failure())
                .isEqualTo(GateTokens.Failure.WRONG_ZONE);
        // 验票先判本 gate、再判 zone：不同号的 gate 不论在哪个 zone 都是 WRONG_GATE
        assertThat(TOKENS.verify(token.payload(), token.signature(), 8, 2, NOW).failure())
                .isEqualTo(GateTokens.Failure.WRONG_GATE);
        assertThat(TOKENS.verify(token.payload(), token.signature(), 8, 1, NOW).failure())
                .isEqualTo(GateTokens.Failure.WRONG_GATE);
        assertThat(GateTokens.ofUtf8("other").verify(token.payload(), token.signature(), 9, 2, NOW).failure())
                .isEqualTo(GateTokens.Failure.BAD_SIGNATURE);
    }

    @Test
    void 重定向票据_第300秒起过期() {
        IssuedGateToken token = ISSUER.issueRedirect(9, 2, HOLDER, 2);

        assertThat(TOKENS.verify(token.payload(), token.signature(), 9, 2, NOW + 299).ok()).isTrue();
        assertThat(TOKENS.verify(token.payload(), token.signature(), 9, 2, NOW + 300).failure())
                .isEqualTo(GateTokens.Failure.EXPIRED);
    }

    /**
     * 字节金样：固定时钟、固定密钥、固定字段下，票据 payload 与签名的每一个字节。
     *
     * <p>期望值<b>不是</b>从被测代码打印出来的，也不是取自 Go 基线（基线的单测是用同一个 {@code signHMAC} 自比、时间取
     * {@code time.Now()}，没有现成向量；本机也没有 Go 工具链）。它是按 proto3 线格式手工排出来、再用两个与 JDK 无关的 HMAC-SHA256
     * 实现（OpenSSL {@code dgst -sha256 -hmac}、.NET {@code HMACSHA256}）各算一遍得到的：
     * <pre>
     *   08 07                            gate_node_id     = 7                    （tag = 1 << 3 | 0，varint）
     *   10 02                            zone_id          = 2
     *   18 ac a6 a7 da 06                expire_timestamp = 1_800_000_300        （NOW + 300）
     *                                    hmac_session_key 不出现（4 号字段没有设）
     *   28 88 8e 98 a8 c0 e0 80 81 01    player_id        = 0x0102030405060708
     *   30 03                            target_zone_id   = 3
     * </pre>
     * 签名 = HMAC-SHA256(UTF-8("xm-gate-token-golden-secret"), 上面 22 个字节) 的小写 hex。
     *
     * <p>它钉住的是「票据的字节形态不随重构漂移」：字段集合（多填一个字段、漏填一个字段）、字段取值、序列化次序、签名算法与编码。
     * 部署上 Java 的 scene-manager 只给 Java 的 gate 签票，与 Go 逐字节相等不是互通的前提，这里也不这样声称。
     */
    @Test
    void 重定向票据的字节金样_payload与签名逐字节固定() {
        GateTokens goldenTokens = GateTokens.ofUtf8("xm-gate-token-golden-secret");
        GateTokenIssuer issuer = new GateTokenIssuer(
                goldenTokens, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), new SecureRandom());

        IssuedGateToken token = issuer.issueRedirect(7, 2, HOLDER, 3);

        assertThat(HexFormat.of().formatHex(token.payload().toByteArray()))
                .isEqualTo("0807100218aca6a7da0628888e98a8c0e08081013003");
        assertThat(token.signature().toString(StandardCharsets.US_ASCII))
                .isEqualTo("d79e88f6e0720ed0012ef75ab6b3ed9714561cbe2746e3f8510acbc4493c4902");
        assertThat(token.deadlineEpochSec()).isEqualTo(1_800_000_300L);
        // 重定向票据里没有随机成分：同一时刻、同一组参数再签一次，字节完全相同
        assertThat(issuer.issueRedirect(7, 2, HOLDER, 3)).isEqualTo(token);
        // 金样自洽：这 22 个字节加这个签名，在 zone 3 的 7 号 gate 上验得过
        assertThat(goldenTokens.verify(token.payload(), token.signature(), 7, 3, NOW).ok()).isTrue();
    }

    @Test
    void 重定向票据的参数为0一律拒签_不签出失去持票者绑定或zone绑定的票据() {
        assertThatThrownBy(() -> ISSUER.issueRedirect(0, 2, HOLDER, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ISSUER.issueRedirect(9, 0, HOLDER, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ISSUER.issueRedirect(9, 2, 0, 2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("player_id=0");
        assertThatThrownBy(() -> ISSUER.issueRedirect(9, 2, HOLDER, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target_zone_id=0");
    }
}
