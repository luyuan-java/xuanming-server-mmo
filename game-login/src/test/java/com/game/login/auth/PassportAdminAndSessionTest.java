package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.discovery.presence.PlayerPushes;
import com.game.login.LoginAccountService;
import com.game.login.account.AccountLogin;
import com.game.login.compliance.AccountDeletionService;
import com.game.login.handler.RefreshTokenHandler;
import com.game.login.ownership.OwnerTakeovers;
import com.game.login.session.LoginDevices;
import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.login.token.TokenPair;
import com.game.player.store.AccountLifecycle;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.RoleDeletionPrecheck;
import com.game.proto.login.AccountDeletionRequest;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.RefreshTokenRequest;
import com.game.table.LoginErrorTip;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 覆盖官方通行证管理接口（查状态/三步删用户）、会话状态核对（登录/刷新/注销重认证/webhook）以及账号注销带原因踢线与跨域清理。
 */
class PassportAdminAndSessionTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String startServer(Map<String, com.sun.net.httpserver.HttpHandler> routes) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routes.forEach((p, h) -> server.createContext(p, h));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void 通行证管理接口三步删用户与查状态_软删残留和Unaffected触发重试() throws Exception {
        AtomicInteger getCalls = new AtomicInteger();
        AtomicBoolean softDeleteMode = new AtomicBoolean(false);
        AtomicBoolean unaffectedMode = new AtomicBoolean(false);
        AtomicBoolean deleted = new AtomicBoolean(false);
        String base = startServer(Map.of(
                "/api/get-user", ex -> {
                    getCalls.incrementAndGet();
                    String q = ex.getRequestURI().getRawQuery();
                    if (q.contains("userId=sub-1")) {
                        if (deleted.get() && !softDeleteMode.get()) {
                            reply(ex, 200, "{\"status\":\"ok\",\"data\":null}");
                        } else {
                            reply(ex, 200, "{\"status\":\"ok\",\"data\":{\"owner\":\"game\",\"name\":\"alice\",\"id\":\"sub-1\","
                                    + "\"isForbidden\":false,\"isDeleted\":false,\"lastChangePasswordTime\":\"2026-11-27T10:00:00Z\"}}");
                        }
                    } else if (q.contains("id=game%2Falice")) {
                        reply(ex, 200, "{\"status\":\"ok\",\"data\":{\"owner\":\"game\",\"name\":\"alice\",\"id\":\"sub-1\",\"isForbidden\":true}}");
                    } else {
                        reply(ex, 200, "{\"status\":\"ok\",\"data\":null}");
                    }
                },
                "/api/delete-user", ex -> {
                    if (unaffectedMode.get()) {
                        reply(ex, 200, "{\"status\":\"ok\",\"data\":\"Unaffected\"}");
                    } else {
                        deleted.set(true);
                        reply(ex, 200, "{\"status\":\"ok\",\"data\":\"Affected\"}");
                    }
                }
        ));

        try (PassportAdminClient admin = new PassportAdminClient(base, null, "adm-id", "adm-sec", true)) {
            var st = admin.lookupUser("sub-1");
            assertThat(st.gone()).isFalse();
            assertThat(st.forbidden()).isFalse();
            long expectedChanged = java.time.Instant.parse("2026-11-27T10:00:00Z").getEpochSecond();
            assertThat(st.passwordChangedAtSeconds()).isEqualTo(expectedChanged);
            assertThat(st.sessionVerdict(expectedChanged)).isEqualTo(PassportAdminClient.VERDICT_PASSWORD_CHANGED);
            assertThat(st.sessionVerdict(expectedChanged + 1L)).isEmpty();

            var byName = admin.lookupUserByName("game", "alice");
            assertThat(byName.sub()).isEqualTo("sub-1");
            assertThat(byName.forbidden()).isTrue();

            // Unaffected 拒认删完
            unaffectedMode.set(true);
            assertThatThrownBy(() -> admin.deleteUser("sub-1")).isInstanceOf(AuthProviderUnavailableException.class);
            unaffectedMode.set(false);

            // 软删（删后仍查得到）拒认删完
            softDeleteMode.set(true);
            assertThatThrownBy(() -> admin.deleteUser("sub-1")).isInstanceOf(AuthProviderUnavailableException.class);
            softDeleteMode.set(false);
            deleted.set(false);

            // 正常三步删完并复查消失，重复调用幂等
            admin.deleteUser("sub-1");
            admin.deleteUser("sub-1");
        }
    }

    @Test
    void 通行证会话核对_登录刷新注销重认证与Webhook过滤builtIn和应用伪用户() {
        LoginTokens tokens = mock(LoginTokens.class);
        List<String> kicked = new ArrayList<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        long changedAt = 1_700_000_500L;

        PassportSessionEnforcer.UserLookup lookup = new PassportSessionEnforcer.UserLookup() {
            @Override public PassportAdminClient.UserStatus lookupBySub(String sub) {
                return switch (sub) {
                    case "u_pwd" -> new PassportAdminClient.UserStatus("u_pwd", false, false, changedAt);
                    case "u_ban" -> new PassportAdminClient.UserStatus("u_ban", false, true, 0L);
                    case "u_down" -> throw new AuthProviderUnavailableException("casdoor down");
                    default -> new PassportAdminClient.UserStatus(sub, false, false, 0L);
                };
            }
            @Override public PassportAdminClient.UserStatus lookupByName(String owner, String name) {
                if ("game".equals(owner) && "bob".equals(name)) {
                    return new PassportAdminClient.UserStatus("u_ban", false, true, 0L);
                }
                return new PassportAdminClient.UserStatus("", true, false, 0L);
            }
        };

        when(tokens.revokeIssuedBefore("passport_u_pwd", changedAt)).thenReturn(2);
        PassportSessionEnforcer enforcer = new PassportSessionEnforcer(
                lookup, tokens, (acc, tip) -> kicked.add(acc + ":" + tip), meters, Runnable::run);

        // 1. 改密前的令牌登录 -> 拒回 2059，同时吊销旧家族并踢线
        assertThat(enforcer.checkLogin("passport_u_pwd", changedAt - 10))
                .isEqualTo(LoginErrorTip.login_error.kLoginPassportCredentialChanged_VALUE);
        verify(tokens).revokeIssuedBefore("passport_u_pwd", changedAt);
        assertThat(kicked).contains("passport_u_pwd:2059");

        // 2. 改密后的新令牌登录 -> 放行（返回 0），且仍吊销旧会话
        assertThat(enforcer.checkLogin("passport_u_pwd", changedAt + 10)).isZero();

        // 3. 通行证故障 -> fail-open 放行并计数
        assertThat(enforcer.checkLogin("passport_u_down", changedAt)).isZero();
        assertThat(meters.counter("login.passport.session.check", "phase", "login", "result", "error").count())
                .isEqualTo(1.0);

        // 4. RefreshTokenHandler 轮换前核对：改密早于 changedAt 拒绝续期且绝不调 tokens.refresh
        RefreshTokenHandler refreshHandler = new RefreshTokenHandler(tokens).withPassportEnforcer(enforcer);
        when(tokens.inspectRefresh("rt-old")).thenReturn(Optional.of(
                new TokenData("passport_u_pwd", "passport", "", changedAt, "fam1", changedAt - 5)));
        var refreshResp = refreshHandler.refresh(RefreshTokenRequest.newBuilder().setRefreshToken("rt-old").build());
        assertThat(refreshResp.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        verify(tokens, never()).refresh(eq("rt-old"), any());

        // 5. 申请注销重认证也走同一道通行证核对
        AccountLogin accountLogin = mock(AccountLogin.class);
        AccountDeletionService deletion = mock(AccountDeletionService.class);
        when(accountLogin.authenticate(any(LoginRequest.class))).thenReturn(Optional.of(
                new LoginAuthenticator.Authenticated("passport_u_ban", "passport", "passport", "", changedAt)));
        LoginAccountService rpc = new LoginAccountService(accountLogin, refreshHandler, tokens, mock(PlayerStore.class),
                null, deletion, Runnable::run).withPassportEnforcer(enforcer);
        var delResp = rpc.requestAccountDeletion(AccountDeletionRequest.newBuilder()
                .setAuthType("passport").setAuthToken("tok").build()).join();
        assertThat(delResp.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginPassportCredentialChanged_VALUE);
        verify(deletion, never()).request(any(), anyInt(), eq(false), any());

        // 6. Webhook 提取用户：跳过 built-in 和含 '/' 的应用伪用户，坏 JSON 回 -1
        assertThat(enforcer.handleEvent("not-json".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PassportSessionEnforcer.RESULT_BAD_EVENT);
        String eventJson = "{\"organization\":\"built-in\",\"user\":\"admin\",\"extendedUser\":{\"owner\":\"game\",\"name\":\"app/delete-bot\"},"
                + "\"object\":\"{\\\"owner\\\":\\\"game\\\",\\\"name\\\":\\\"bob\\\"}\"}";
        assertThat(enforcer.handleEvent(eventJson.getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
        verify(tokens, org.mockito.Mockito.times(2)).revokeAll("passport_u_ban");
    }

    @Test
    void 注销申请与执行带原因踢线_参与者清理失败延后finish_通行证删用户失败阻断finish() {
        PlayerStore store = mock(PlayerStore.class);
        LoginTokens tokens = mock(LoginTokens.class);
        OwnerTakeovers takeovers = mock(OwnerTakeovers.class);
        PlayerPushes pushes = mock(PlayerPushes.class);
        when(pushes.kick(anyLong(), anyInt())).thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.SENT));

        PlayerRow role = new PlayerRow();
        role.setPlayerId(501L);
        role.setOwnerEpoch(9L);
        when(store.listPlayers("passport_u1")).thenReturn(List.of(role));
        when(store.requestAccountDeletion(eq("passport_u1"), eq(AccountLifecycle.SOURCE_USER), any(), any()))
                .thenReturn(new PlayerStore.AccountDeletionOutcome(PlayerStore.AccountDeletionStatus.OK,
                        new AccountLifecycle("passport_u1", "", AccountLifecycle.DELETION_PENDING, 1000L, 2000L, 0L, 1, 0L),
                        List.of()));

        AtomicBoolean guildReady = new AtomicBoolean(false);
        AtomicBoolean passportReady = new AtomicBoolean(false);
        AccountDeletionService svc = new AccountDeletionService(
                store, tokens, takeovers, RoleDeletionPrecheck.NONE, Duration.ofDays(7), null, false, new SimpleMeterRegistry())
                .withPlayerPushes(pushes)
                .withRolePurgeCleanups(List.of(pid -> guildReady.get()))
                .withPassportUserDeleter(sub -> {
                    if (!passportReady.get()) {
                        throw new AuthProviderUnavailableException("casdoor busy");
                    }
                });

        // 1. 申请注销：推 2038 踢线 + owner takeover
        var reqResp = svc.request("passport_u1", AccountLifecycle.SOURCE_USER, false, "");
        assertThat(reqResp.hasErrorMessage()).isFalse();
        verify(pushes).kick(501L, LoginErrorTip.login_error.kLoginAccountDeletionPending_VALUE);
        verify(takeovers).request(501L, 9L);

        // 2. 执行第 1 轮：角色删完但帮会参与者清理返回 false -> WAITING，不调 finishAccountDeletion
        when(store.claimDueAccountDeletions(anyInt(), any())).thenReturn(List.of("passport_u1"));
        when(store.readAccountLifecycle("passport_u1", false)).thenReturn(Optional.of(
                new AccountLifecycle("passport_u1", "", AccountLifecycle.DELETION_DELETING, 1000L, 2000L, 9000L, 1, 0L)));
        when(store.removeRoleForDeletion(eq("passport_u1"), eq(501L), any())).thenReturn(PlayerStore.RoleRemovalStatus.REMOVED);
        var r1 = svc.runExecutorOnce();
        assertThat(r1.waiting()).isEqualTo(1);
        verify(pushes).kick(501L, LoginErrorTip.login_error.kLoginAccountDeletionInProgress_VALUE);
        verify(store, never()).finishAccountDeletion("passport_u1");

        // 3. 执行第 2 轮：角色已删完（listPlayers 空），帮会清理重试成功，但通行证删用户失败 -> FAILED，不删游戏账号行
        when(store.listPlayers("passport_u1")).thenReturn(List.of());
        guildReady.set(true);
        var r2 = svc.runExecutorOnce();
        assertThat(r2.failed()).isEqualTo(1);
        verify(store, never()).finishAccountDeletion("passport_u1");

        // 4. 执行第 3 轮：通行证删用户成功 -> finishAccountDeletion 成功收尾
        passportReady.set(true);
        when(store.finishAccountDeletion("passport_u1")).thenReturn(new PlayerStore.FinishDeletionOutcome(true, 0));
        var r3 = svc.runExecutorOnce();
        assertThat(r3.finished()).isEqualTo(1);
        verify(store).finishAccountDeletion("passport_u1");
    }
}
