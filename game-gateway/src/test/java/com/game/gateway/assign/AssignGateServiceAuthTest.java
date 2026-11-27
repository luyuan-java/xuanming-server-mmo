package com.game.gateway.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.api.proto.GateNodeInfo;
import com.game.common.token.GateTokenIssuer;
import com.game.common.token.GateTokens;
import com.game.discovery.gate.GateInflight;
import com.game.discovery.gate.GateSource;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** assign-gate 凭 access_token 鉴权、忽略自报 account、401/500 分类、匿名开关、摘要冷却键与 5 类鉴权指标单测。 */
class AssignGateServiceAuthTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    private static ZoneDirectory openZone() {
        return new ZoneDirectory(() -> List.of(new ZoneRow(1, "一区", 0, 100, "", null, true, 1, 0, 0)),
                System::nanoTime);
    }

    private static GateSource singleGate() {
        return zoneId -> List.of(GateNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(1).setInstanceId("g-1")
                .setClientHost("10.0.0.1").setClientPort(11001).build());
    }

    private static GateTokenIssuer issuer() {
        return new GateTokenIssuer(GateTokens.ofUtf8("unit-test-secret"), CLOCK, new SecureRandom());
    }

    private static double authCount(SimpleMeterRegistry meters, AssignGateMetrics.AuthOutcome outcome) {
        return meters.counter("game.gateway.assign.gate.auth", "result", outcome.tag()).count();
    }

    @Test
    void 合法令牌按摘要冷却键放行_无效令牌回401_存储故障回500_匿名受旧路径开关控制且不信自报账号() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AssignGateMetrics metrics = new AssignGateMetrics(meters);
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.check(eq(1), any(), any(), eq(RateLimiter.Scope.ASSIGN))).thenReturn(RateLimitDecision.pass());

        AssignGateService.AccessTokenAuthenticator auth = token -> switch (token) {
            case "good-secret-token" -> Optional.of("real_account");
            case "expired-or-revoked" -> Optional.empty();
            default -> throw new IllegalStateException("redis unavailable");
        };

        // 1. legacyGateLoginEnabled = true：合法令牌走 authenticated 且冷却键为 tok:<32 hex>
        AssignGateService legacyOn = new AssignGateService(openZone(), singleGate(), issuer(), null, limiter, CLOCK,
                new GateInflight(), auth, true, metrics::recordAuth);

        String expectedKey = AssignGateService.tokenCooldownKey("good-secret-token");
        assertThat(expectedKey).startsWith("tok:").hasSize(4 + 32).doesNotContain("good-secret-token");

        AssignGateResponse ok = legacyOn.assign(1, null, "1.2.3.4", "forged_account", "good-secret-token");
        assertThat(ok.code()).isZero();
        verify(limiter).check(eq(1), eq("1.2.3.4"), eq(expectedKey), eq(RateLimiter.Scope.ASSIGN));
        assertThat(authCount(meters, AssignGateMetrics.AuthOutcome.AUTHENTICATED)).isEqualTo(1);

        // 2. 无令牌匿名分配：legacy 开启时放行，但绝对忽略自报 account（冷却键为 null）
        AssignGateResponse anonOk = legacyOn.assign(1, null, "1.2.3.4", "forged_account", null);
        assertThat(anonOk.code()).isZero();
        verify(limiter).check(eq(1), eq("1.2.3.4"), isNull(), eq(RateLimiter.Scope.ASSIGN));
        assertThat(authCount(meters, AssignGateMetrics.AuthOutcome.ANONYMOUS)).isEqualTo(1);

        // 3. 令牌被拒（过期/吊销/未知）-> 401 auth_rejected，不碰限流与 gate 目录
        RateLimiter untouched = mock(RateLimiter.class);
        AssignGateService strict = new AssignGateService(openZone(), singleGate(), issuer(), null, untouched, CLOCK,
                new GateInflight(), auth, false, metrics::recordAuth);

        AssignGateResponse rejected = strict.assign(1, null, "1.2.3.4", null, "expired-or-revoked");
        assertThat(rejected.code()).isEqualTo(AssignGateResponse.CODE_AUTH_REJECTED);
        assertThat(rejected.error()).isEqualTo(AssignGateResponse.ERR_AUTH_REJECTED);
        assertThat(authCount(meters, AssignGateMetrics.AuthOutcome.TOKEN_REJECTED)).isEqualTo(1);

        // 4. 令牌存储/RPC 故障 -> 500 auth_unavailable（可重试）
        AssignGateResponse backendErr = strict.assign(1, null, "1.2.3.4", null, "boom");
        assertThat(backendErr.code()).isEqualTo(AssignGateResponse.CODE_INTERNAL);
        assertThat(backendErr.error()).isEqualTo(AssignGateResponse.ERR_AUTH_UNAVAILABLE);
        assertThat(authCount(meters, AssignGateMetrics.AuthOutcome.ERROR)).isEqualTo(1);

        // 5. 关闭 legacy 后匿名分配 -> 401 auth_required
        AssignGateResponse anonDenied = strict.assign(1, null, "1.2.3.4", "forged_account", "");
        assertThat(anonDenied.code()).isEqualTo(AssignGateResponse.CODE_AUTH_REJECTED);
        assertThat(anonDenied.error()).isEqualTo(AssignGateResponse.ERR_AUTH_REQUIRED);
        assertThat(authCount(meters, AssignGateMetrics.AuthOutcome.ANONYMOUS_DENIED)).isEqualTo(1);
        verifyNoInteractions(untouched);
    }
}
