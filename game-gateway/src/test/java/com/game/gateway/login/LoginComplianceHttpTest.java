package com.game.gateway.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.game.api.AccountLoginService;
import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimitSettings;
import com.game.gateway.ratelimit.RateLimitStore;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.ratelimit.WaveSchedule;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.AccountDeletionBlocker;
import com.game.proto.login.AccountDeletionResponse;
import com.game.proto.login.AntiAddictionStatus;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenResponse;
import com.game.proto.login.SubmitRealNameResponse;
import com.game.table.LoginErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HTTP /api/real-name、/api/account/delete{,/cancel}、/api/login 合规字段与 /api/refresh-token IP 桶限流回归。 */
class LoginComplianceHttpTest {

    private static final ZoneDirectory ZONES = new ZoneDirectory(
            () -> List.of(new ZoneRow(1, "一区", 0, 5000, "", null, true, 1, 0, 0)), System::nanoTime);

    @Test
    void 登录返回注销与防沉迷字段_蛇形JSON与客户端契约一致() throws Exception {
        AccountLoginService login = mock(AccountLoginService.class);
        when(login.login(any())).thenReturn(CompletableFuture.completedFuture(LoginResponse.newBuilder()
                .setAccessToken("tok_a").setRefreshToken("tok_r")
                .setAccessTokenExpire(100).setRefreshTokenExpire(200)
                .setAccountDeletionState(1).setAccountDeletionExecuteAfter(1800000000L)
                .setPreviousAccountDeletedAt(1700000000L)
                .setAntiAddiction(AntiAddictionStatus.newBuilder()
                        .setRealNameRequired(false).setMinor(true).setPlayDeadlineMs(1800003600000L))
                .build()));

        LoginHttpService service = new LoginHttpService(login, ZONES, new LoginHttpMetrics(new SimpleMeterRegistry()));
        HttpLoginResponse resp = service.login(new HttpLoginRequest(1, "dev_1", "p", "password", null, "d"), "1.2.3.4").join();
        assertThat(resp.code()).isZero();
        assertThat(resp.accountDeletionState()).isEqualTo(1);
        assertThat(resp.accountDeletionExecuteAfter()).isEqualTo(1800000000L);
        assertThat(resp.previousAccountDeletedAt()).isEqualTo(1700000000L);
        assertThat(resp.antiAddiction()).isEqualTo(new HttpAntiAddictionStatus(false, true, 1800003600000L));

        String json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).writeValueAsString(resp);
        assertThat(json).contains("\"account_deletion_state\":1")
                .contains("\"account_deletion_execute_after\":1800000000")
                .contains("\"previous_account_deleted_at\":1700000000")
                .contains("\"anti_addiction\":{\"real_name_required\":false,\"minor\":true,\"play_deadline_ms\":1800003600000}");
    }

    @Test
    void 实名提交映射0_401_422_500且请求toString脱敏() {
        AccountLoginService login = mock(AccountLoginService.class);
        LoginHttpService service = new LoginHttpService(login, ZONES, new LoginHttpMetrics(new SimpleMeterRegistry()));

        HttpRealNameRequest req = new HttpRealNameRequest("acc_tok_secret", "张三", "11010519950501001X", 1);
        assertThat(req.toString()).doesNotContain("张三").doesNotContain("110105").doesNotContain("acc_tok_secret");

        // 0 成功
        when(login.submitRealName(any())).thenReturn(CompletableFuture.completedFuture(SubmitRealNameResponse.newBuilder()
                .setAntiAddiction(AntiAddictionStatus.newBuilder().setMinor(false)).build()));
        HttpRealNameResponse ok = service.submitRealName(req, "1.2.3.4").join();
        assertThat(ok.code()).isEqualTo(HttpRealNameResponse.CODE_OK);
        assertThat(ok.tip()).isNull();
        assertThat(ok.antiAddiction()).isNotNull();

        // 未知区号在限流前拒绝，不按任意 zoneId 创建 Redis 区桶键
        RateLimiter limiter = mock(RateLimiter.class);
        LoginHttpService limited = new LoginHttpService(login, ZONES, new LoginHttpMetrics(new SimpleMeterRegistry()), limiter);
        HttpRealNameResponse unknownZone = limited.submitRealName(
                new HttpRealNameRequest("acc_tok_secret", "张三", "11010519950501001X", 999), "1.2.3.4").join();
        assertThat(unknownZone.code()).isEqualTo(HttpLoginResponse.CODE_INTERNAL);
        assertThat(unknownZone.message()).isEqualTo("invalid_zone");
        verify(limiter, never()).check(anyInt(), any(), any(), any());

        // 422 实名信息不合法
        when(login.submitRealName(any())).thenReturn(CompletableFuture.completedFuture(SubmitRealNameResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(LoginErrorTip.login_error.kLoginRealNameInvalid_VALUE))
                .build()));
        HttpRealNameResponse rej = service.submitRealName(req, "1.2.3.4").join();
        assertThat(rej.code()).isEqualTo(HttpRealNameResponse.CODE_REJECTED);
        assertThat(rej.tip()).isEqualTo(LoginErrorTip.login_error.kLoginRealNameInvalid_VALUE);

        // 401 access_token 无效
        when(login.submitRealName(any())).thenReturn(CompletableFuture.completedFuture(SubmitRealNameResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE))
                .build()));
        assertThat(service.submitRealName(req, "1.2.3.4").join().code()).isEqualTo(HttpLoginResponse.CODE_AUTH_REJECTED);

        // 500 服务端故障（fault 列）
        when(login.submitRealName(any())).thenReturn(CompletableFuture.completedFuture(SubmitRealNameResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE))
                .build()));
        assertThat(service.submitRealName(req, "1.2.3.4").join().code()).isEqualTo(HttpLoginResponse.CODE_INTERNAL);
    }

    @Test
    void 注销申请与撤销_边界校验_挂起清单映射_不泄凭据() {
        AccountLoginService login = mock(AccountLoginService.class);
        LoginHttpService service = new LoginHttpService(login, ZONES, new LoginHttpMetrics(new SimpleMeterRegistry()));

        HttpAccountDeletionRequest badZone = new HttpAccountDeletionRequest(0, "u", "secret", "password", null, null);
        assertThat(badZone.toString()).doesNotContain("secret");
        assertThat(service.requestAccountDeletion(badZone, "1.2.3.4").join())
                .isEqualTo(HttpAccountDeletionResponse.error(401, "invalid_zone_id"));
        verify(login, never()).requestAccountDeletion(any());

        when(login.requestAccountDeletion(any())).thenReturn(CompletableFuture.completedFuture(AccountDeletionResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(LoginErrorTip.login_error.kLoginAccountDeletionBlocked_VALUE))
                .setState(0)
                .addBlockers(AccountDeletionBlocker.newBuilder().setPlayerId(77).setReason("guild_leader"))
                .build()));
        HttpAccountDeletionResponse blocked = service.requestAccountDeletion(
                new HttpAccountDeletionRequest(1, "u", "p", "password", null, null), "1.2.3.4").join();
        assertThat(blocked.code()).isEqualTo(401);
        assertThat(blocked.tip()).isEqualTo(LoginErrorTip.login_error.kLoginAccountDeletionBlocked_VALUE);
        assertThat(blocked.blockers()).containsExactly(new HttpAccountDeletionResponse.Blocker(77, "guild_leader"));

        // 撤销缺 access_token -> 401
        assertThat(service.cancelAccountDeletion(new HttpCancelAccountDeletionRequest(1, " "), "1.2.3.4").join())
                .isEqualTo(HttpAccountDeletionResponse.error(401, "empty_access_token"));

        when(login.cancelAccountDeletion(any())).thenReturn(CompletableFuture.completedFuture(AccountDeletionResponse.newBuilder()
                .setState(0).setExecuteAfter(0).build()));
        HttpAccountDeletionResponse cancelled = service.cancelAccountDeletion(
                new HttpCancelAccountDeletionRequest(1, "tok_live"), "1.2.3.4").join();
        assertThat(cancelled.code()).isZero();
        assertThat(cancelled.state()).isZero();
    }

    @Test
    void 刷新令牌仅过共享IP桶_跳过波次区桶与冷却_超限回429IP_RATE_LIMIT() {
        AccountLoginService login = mock(AccountLoginService.class);
        when(login.refreshToken(any())).thenReturn(CompletableFuture.completedFuture(RefreshTokenResponse.newBuilder()
                .setAccessToken("a2").setRefreshToken("r2").setAccessTokenExpire(10).setRefreshTokenExpire(20).build()));

        // 波次全关闭（2099 年才开），但 /api/refresh-token 跳过波次，仅受 IP 桶约束（2 次后拒）
        AtomicInteger ipHits = new AtomicInteger();
        RateLimitStore fakeStore = new RateLimitStore() {
            @Override
            public Admission admit(Bucket ip, Bucket zone) {
                assertThat(zone).as("/api/refresh-token 不碰区桶").isNull();
                return ipHits.incrementAndGet() <= 2
                        ? new Admission(Admission.Kind.OK, 0, 1)
                        : new Admission(Admission.Kind.IP_EMPTY, 0, 0);
            }

            @Override
            public boolean tryCooldown(String key, java.time.Duration ttl) {
                throw new AssertionError("/api/refresh-token 不应触发账号冷却");
            }
        };
        RateLimitSettings settings = new RateLimitSettings(true, 10L, 10L, Map.of(), 10L, 2L, 5000L, List.of(),
                new RateLimitSettings.Wave(true, 4_000_000_000L, List.of()));
        RateLimiter limiter = new RateLimiter(settings, fakeStore, new RedisKeys(new GameNamespace(GameNamespace.DEFAULT_VALUE)),
                System::currentTimeMillis);
        LoginHttpService service = new LoginHttpService(login, ZONES, new LoginHttpMetrics(new SimpleMeterRegistry()), limiter);

        assertThat(service.refresh(new HttpRefreshTokenRequest("r1"), "9.9.9.9").join().code()).isZero();
        assertThat(service.refresh(new HttpRefreshTokenRequest("r1"), "9.9.9.9").join().code()).isZero();
        HttpRefreshTokenResponse limited = service.refresh(new HttpRefreshTokenRequest("r1"), "9.9.9.9").join();
        assertThat(limited.code()).isEqualTo(HttpLoginResponse.CODE_RATE_LIMITED);
        assertThat(limited.message()).isEqualTo(RateLimitDecision.IP_RATE_LIMIT);
    }
}
