package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisTimeoutException;

/** 三方认证：微信 / QQ 对着本地假服务（同基线 sandbox_mock 的线上格式），Sa-Token 对着替身 Redis，网易占位。 */
class ExternalAuthProvidersTest {

    /** 本地假服务是 http，测试按 dev 模式放行；unionid 策略取生产缺省（要求 unionid）。 */
    private static final OAuthPolicy LOCAL = new OAuthPolicy(true, true, true);
    /** 显式允许回落 openid。 */
    private static final OAuthPolicy LOCAL_OPENID_FALLBACK = new OAuthPolicy(false, true, true);

    private HttpServer server;
    private volatile int status = 200;
    private String endpoint;
    private final List<Map<String, String>> requests = new ArrayList<>();
    private volatile String wechatBody;
    private volatile String qqBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/oauth2/access_token", exchange -> reply(exchange.getRequestURI(), exchange, wechatBody));
        server.createContext("/oauth2.0/me", exchange -> reply(exchange.getRequestURI(), exchange, qqBody));
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void reply(URI uri, com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        Map<String, String> query = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        synchronized (requests) {
            requests.add(query);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void 微信_用code换unionid_请求参数同基线() {
        WeChatProvider wechat = new WeChatProvider("wx_app", "app-secret", endpoint, LOCAL);
        wechatBody = "{\"access_token\":\"t\",\"openid\":\"o1\",\"unionid\":\"u1\"}";
        assertThat(wechat.authenticate("code&1")).contains("wx_u1");
        assertThat(requests.get(0)).containsEntry("appid", "wx_app").containsEntry("secret", "app-secret")
                .containsEntry("code", "code&1").containsEntry("grant_type", "authorization_code");
    }

    @Test
    void 微信_缺unionid缺省拒绝_显式允许才回落openid() {
        wechatBody = "{\"openid\":\"o2\"}";
        WeChatProvider strict = new WeChatProvider("wx_app", "app-secret", endpoint, LOCAL);
        assertThatThrownBy(() -> strict.authenticate("c")).isInstanceOf(AuthProviderUnavailableException.class);
        WeChatProvider fallback = new WeChatProvider("wx_app", "app-secret", endpoint, LOCAL_OPENID_FALLBACK);
        assertThat(fallback.authenticate("c")).contains("wx_o2");
    }

    @Test
    void 微信_玩家侧errcode与空code返回空_渠道侧errcode坏应答抛渠道故障() {
        WeChatProvider wechat = new WeChatProvider("wx_app", "app-secret", endpoint, LOCAL);
        for (int playerSide : new int[]{40029, 40163, 42003}) {
            wechatBody = "{\"errcode\":" + playerSide + ",\"errmsg\":\"x\"}";
            assertThat(wechat.authenticate("EXPIRED")).as("errcode=" + playerSide).isEmpty();
        }
        assertThat(wechat.authenticate("")).isEmpty();
        for (int providerSide : new int[]{-1, 40001, 40013, 40125, 40164, 41002, 41004, 45009, 45011}) {
            wechatBody = "{\"errcode\":" + providerSide + ",\"errmsg\":\"x\"}";
            assertThatThrownBy(() -> wechat.authenticate("c")).as("errcode=" + providerSide)
                    .isInstanceOf(AuthProviderUnavailableException.class).hasMessageContaining(String.valueOf(providerSide));
        }
        for (String shapeBroken : new String[]{"{\"openid\":\"\"}", "not json", "[1,2]"}) {
            wechatBody = shapeBroken;
            assertThatThrownBy(() -> wechat.authenticate("c")).as(shapeBroken)
                    .isInstanceOf(AuthProviderUnavailableException.class);
        }
        assertThatThrownBy(() -> new WeChatProvider("wx_app", " ", endpoint, LOCAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeChatProvider("wx_app", "app-secret\n", endpoint, LOCAL))
                .as("Secret 带进的换行").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 渠道非2xx抛渠道故障_不读应答体() {
        WeChatProvider wechat = new WeChatProvider("wx_app", "app-secret", endpoint, LOCAL);
        QqProvider qq = new QqProvider("100000001", endpoint, LOCAL);
        status = 502;
        wechatBody = "{\"openid\":\"o1\",\"unionid\":\"u1\"}";
        qqBody = "{\"client_id\":\"100000001\",\"openid\":\"o1\",\"unionid\":\"u1\"}";
        assertThatThrownBy(() -> wechat.authenticate("c")).isInstanceOf(AuthProviderUnavailableException.class)
                .hasMessageContaining("502");
        assertThatThrownBy(() -> qq.authenticate("t")).isInstanceOf(AuthProviderUnavailableException.class);
    }

    @Test
    void QQ_用access_token取unionid_剥JSONP_client_id必须是本应用() {
        QqProvider qq = new QqProvider("100000001", endpoint, LOCAL);
        qqBody = "callback( {\"client_id\":\"100000001\",\"openid\":\"o1\",\"unionid\":\"u1\"} );";
        assertThat(qq.authenticate("tok")).contains("qq_u1");
        assertThat(requests.get(0)).containsEntry("access_token", "tok").containsEntry("unionid", "1")
                .containsEntry("fmt", "json");
        qqBody = "{\"client_id\":\"999\",\"openid\":\"o2\",\"unionid\":\"u2\"}";
        assertThat(qq.authenticate("tok")).as("别的应用签的令牌：凭证无效，不是渠道故障").isEmpty();
        qqBody = "{\"error\":100016,\"error_description\":\"access token check failed\"}";
        assertThat(qq.authenticate("INVALID")).isEmpty();
        qqBody = "{\"client_id\":\"100000001\",\"unionid\":\"u2\"}";
        assertThatThrownBy(() -> qq.authenticate("tok")).as("error=0 却没有 openid")
                .isInstanceOf(AuthProviderUnavailableException.class);
    }

    @Test
    void QQ_缺unionid缺省拒绝_不请求unionid时必须显式允许回落() {
        qqBody = "{\"client_id\":\"100000001\",\"openid\":\"o2\"}";
        QqProvider strict = new QqProvider("100000001", endpoint, LOCAL);
        assertThatThrownBy(() -> strict.authenticate("tok")).isInstanceOf(AuthProviderUnavailableException.class);

        QqProvider noUnionId = new QqProvider("100000001", endpoint, new OAuthPolicy(false, false, true));
        assertThat(noUnionId.authenticate("tok")).contains("qq_o2");
        assertThat(requests.get(requests.size() - 1)).doesNotContainKey("unionid").containsEntry("fmt", "json");
        assertThatThrownBy(() -> new OAuthPolicy(true, false, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 三方服务不可达抛渠道故障_不带查询串() {
        WeChatProvider wechat = new WeChatProvider("wx_app", "app-secret", "http://127.0.0.1:1", LOCAL);
        assertThatThrownBy(() -> wechat.authenticate("code")).isInstanceOf(AuthProviderUnavailableException.class)
                .hasMessageNotContaining("app-secret").hasMessageNotContaining("code=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void SaToken_读键得loginId_不加前缀_非法令牌不碰Redis_出错不带令牌() {
        RedissonClient redis = mock(RedissonClient.class);
        RBucket<Object> hit = mock(RBucket.class);
        RBucket<Object> miss = mock(RBucket.class);
        when(redis.getBucket(any(String.class), any())).thenReturn(miss);
        when(redis.getBucket(org.mockito.ArgumentMatchers.eq("satoken:login:token:abc-123"), any())).thenReturn(hit);
        when(hit.get()).thenReturn("player_42");
        SaTokenProvider satoken = new SaTokenProvider(redis, "satoken", "login");

        assertThat(satoken.authenticate("abc-123")).contains("player_42");
        assertThat(satoken.authenticate("nope")).isEmpty();
        assertThat(satoken.authenticate("a b")).isEmpty();
        assertThat(satoken.authenticate("a:b")).isEmpty();
        assertThat(satoken.authenticate("")).isEmpty();
        verify(redis, never()).getBucket(org.mockito.ArgumentMatchers.contains(" "), any());

        when(hit.get()).thenThrow(new RedisTimeoutException("Command: GET params: [satoken:login:token:abc-123]"));
        assertThatThrownBy(() -> satoken.authenticate("abc-123")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("abc-123");
    }

    @Test
    void 网易占位恒失败() {
        assertThat(new NeteaseProvider().authenticate("anything")).isEmpty();
    }
}
