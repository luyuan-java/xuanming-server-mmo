package com.game.gateway.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.game.api.AccountLoginService;
import com.game.gateway.zone.Zone;
import com.game.gateway.zone.ZoneCatalog;
import com.game.gateway.zone.ZoneStatus;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoginHttpServiceTest {

    private final AccountLoginService login = mock(AccountLoginService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LoginHttpService service = new LoginHttpService(login,
            new ZoneCatalog(List.of(new Zone(1, "一区", ZoneStatus.OPEN, true, null))), new LoginHttpMetrics(meters));

    private static HttpLoginRequest password(long zone) {
        return new HttpLoginRequest(zone, "robot_0001", "secret", null, null, "dev-1");
    }

    private double count(String endpoint, int code) {
        var c = meters.find(LoginHttpMetrics.NAME).tag("endpoint", endpoint).tag("code", Integer.toString(code)).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void 成功_角色与令牌原样带回_level恒0_请求字段转给login() {
        when(login.login(any())).thenReturn(CompletableFuture.completedFuture(LoginResponse.newBuilder()
                .addPlayers(AccountSimplePlayerWrapper.newBuilder().setPlayer(AccountSimplePlayer.newBuilder()
                        .setPlayerId(11).setName("道友aaaaaa").setClassId(2)))
                .setAccessToken("a").setRefreshToken("r").setAccessTokenExpire(100).setRefreshTokenExpire(200).build()));

        HttpLoginResponse response = service.login(password(1)).join();

        assertThat(response).isEqualTo(HttpLoginResponse.ok(
                List.of(new HttpLoginResponse.PlayerInfo(11, "道友aaaaaa", 0)), "a", "r", 100, 200));
        ArgumentCaptor<LoginRequest> sent = ArgumentCaptor.forClass(LoginRequest.class);
        verify(login).login(sent.capture());
        assertThat(sent.getValue()).isEqualTo(LoginRequest.newBuilder().setAccount("robot_0001").setPassword("secret").build());
        assertThat(count("login", 0)).isEqualTo(1);
    }

    @Test
    void 没签令牌的成功_令牌键不出现_空名字为null() throws Exception {
        when(login.login(any())).thenReturn(CompletableFuture.completedFuture(LoginResponse.newBuilder()
                .addPlayers(AccountSimplePlayerWrapper.newBuilder().setPlayer(AccountSimplePlayer.newBuilder()
                        .setPlayerId(11)))
                .build()));
        HttpLoginResponse response = service.login(password(1)).join();
        assertThat(response.accessToken()).isNull();
        assertThat(response.refreshToken()).isNull();
        String json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .writeValueAsString(response);
        assertThat(json).isEqualTo("{\"code\":0,\"players\":[{\"player_id\":11,\"name\":null,\"level\":0}],"
                + "\"access_token_expire\":0,\"refresh_token_expire\":0}");
    }

    @Test
    void login回业务错误一律401_带上游tip() {
        when(login.login(any())).thenReturn(CompletableFuture.completedFuture(LoginResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(2000)).build()));
        assertThat(service.login(password(1)).join())
                .isEqualTo(HttpLoginResponse.error(401, "upstream_err=2000"));
        assertThat(count("login", 401)).isEqualTo(1);
    }

    @Test
    void 调用失败_超时网络没有提供方是login_unavailable_其余internal_error() {
        when(login.login(any())).thenReturn(CompletableFuture.failedFuture(new RpcException(RpcException.TIMEOUT_EXCEPTION, "t")));
        assertThat(service.login(password(1)).join()).isEqualTo(HttpLoginResponse.error(500, "login_unavailable"));
        when(login.login(any())).thenReturn(CompletableFuture.failedFuture(new RpcException(RpcException.NETWORK_EXCEPTION, "n")));
        assertThat(service.login(password(1)).join().message()).isEqualTo("login_unavailable");
        when(login.login(any())).thenThrow(new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "none"));
        assertThat(service.login(password(1)).join().message()).isEqualTo("login_unavailable");
        AccountLoginService registryEmpty = mock(AccountLoginService.class);
        when(registryEmpty.login(any())).thenThrow(
                new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry"));
        assertThat(new LoginHttpService(registryEmpty, new ZoneCatalog(List.of(new Zone(1, "一区", ZoneStatus.OPEN, true,
                null))), new LoginHttpMetrics(meters)).login(password(1)).join().message())
                .as("注册中心里一个提供方都没有").isEqualTo("login_unavailable");
        AccountLoginService overloaded = mock(AccountLoginService.class);
        when(overloaded.login(any())).thenReturn(CompletableFuture.failedFuture(
                new RpcException(new java.util.concurrent.RejectedExecutionException("login 工作队列满"))));
        assertThat(new LoginHttpService(overloaded, new ZoneCatalog(List.of(new Zone(1, "一区", ZoneStatus.OPEN, true,
                null))), new LoginHttpMetrics(meters)).login(password(1)).join().message())
                .as("login 工作队列满").isEqualTo("login_unavailable");

        AccountLoginService broken = mock(AccountLoginService.class);
        when(broken.login(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));
        LoginHttpService other = new LoginHttpService(broken,
                new ZoneCatalog(List.of(new Zone(1, "一区", ZoneStatus.OPEN, true, null))), new LoginHttpMetrics(meters));
        assertThat(other.login(password(1)).join()).isEqualTo(HttpLoginResponse.error(500, "internal_error"));
        when(broken.login(any())).thenReturn(null);
        assertThat(other.login(password(1)).join().code()).isEqualTo(500);
    }

    @Test
    void 区不在区服列表里_500_不调login() {
        assertThat(service.login(password(2)).join()).isEqualTo(HttpLoginResponse.error(500, "unknown_zone"));
        assertThat(service.login(password(0)).join().message()).isEqualTo("unknown_zone");
        assertThat(service.login(password(1L << 40)).join().message()).isEqualTo("unknown_zone");
        verify(login, never()).login(any());
    }

    @Test
    void 刷新_空refresh是401且不调login_成功带回新的一对_上游错误401() {
        assertThat(service.refresh(new HttpRefreshTokenRequest(" ")).join())
                .isEqualTo(HttpRefreshTokenResponse.error(401, "empty_refresh_token"));
        assertThat(service.refresh(new HttpRefreshTokenRequest(null)).join().code()).isEqualTo(401);
        verify(login, never()).refreshToken(any());

        when(login.refreshToken(RefreshTokenRequest.newBuilder().setRefreshToken("r1").build()))
                .thenReturn(CompletableFuture.completedFuture(RefreshTokenResponse.newBuilder()
                        .setAccessToken("a2").setRefreshToken("r2").setAccessTokenExpire(1).setRefreshTokenExpire(2).build()));
        assertThat(service.refresh(new HttpRefreshTokenRequest("r1")).join())
                .isEqualTo(HttpRefreshTokenResponse.ok("a2", "r2", 1, 2));

        when(login.refreshToken(RefreshTokenRequest.newBuilder().setRefreshToken("used").build()))
                .thenReturn(CompletableFuture.completedFuture(RefreshTokenResponse.newBuilder()
                        .setErrorMessage(TipInfoMessage.newBuilder().setId(2000)).build()));
        assertThat(service.refresh(new HttpRefreshTokenRequest("used")).join())
                .isEqualTo(HttpRefreshTokenResponse.error(401, "upstream_err=2000"));
        assertThat(count("refresh", 401)).isEqualTo(3);
        assertThat(count("refresh", 0)).isEqualTo(1);
    }

    @Test
    void JSON形状_蛇形键_没有值的字段不出现_成功时players即使为空也在() throws Exception {
        ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String ok = json.writeValueAsString(HttpLoginResponse.ok(
                List.of(new HttpLoginResponse.PlayerInfo(11, "n", 0)), "a", "r", 100, 200));
        assertThat(ok).isEqualTo("{\"code\":0,\"players\":[{\"player_id\":11,\"name\":\"n\",\"level\":0}],"
                + "\"access_token\":\"a\",\"refresh_token\":\"r\",\"access_token_expire\":100,\"refresh_token_expire\":200}");
        assertThat(json.writeValueAsString(HttpLoginResponse.ok(List.of(), "", "", 0, 0))).contains("\"players\":[]");
        assertThat(json.writeValueAsString(HttpLoginResponse.error(401, "upstream_err=2000")))
                .isEqualTo("{\"code\":401,\"message\":\"upstream_err=2000\"}");
        assertThat(json.writeValueAsString(HttpRefreshTokenResponse.error(401, "empty_refresh_token")))
                .isEqualTo("{\"code\":401,\"message\":\"empty_refresh_token\"}");
        HttpLoginRequest parsed = json.readValue("{\"zone_id\":1,\"account\":\"robot_0001\",\"password\":\"p\","
                + "\"auth_type\":\"access_token\",\"auth_token\":\"t\",\"device_id\":\"d\"}", HttpLoginRequest.class);
        assertThat(parsed).isEqualTo(new HttpLoginRequest(1, "robot_0001", "p", "access_token", "t", "d"));
        assertThat(json.readValue("{\"refresh_token\":\"x\"}", HttpRefreshTokenRequest.class).refreshToken()).isEqualTo("x");
    }
}
