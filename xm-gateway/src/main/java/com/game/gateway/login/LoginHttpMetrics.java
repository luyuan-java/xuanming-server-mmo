package com.game.gateway.login;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 登录 / 刷新令牌的结局计数（{@code xm.gateway.login{endpoint, code}}，见 architecture.md §11）：每个进到 {@link LoginHttpService} 的请求
 * 恰好计一次；请求体解析失败 / Content-Type 不对的由 Spring 默认回 400 / 415（同基线），不计。
 * 标签只有端点（login / refresh）与应答体业务码，不带账号 / 令牌 / IP / 上游 tip。已知组合启动时预先注册。线程安全。
 */
public final class LoginHttpMetrics {

    static final String NAME = "xm.gateway.login";
    static final String LOGIN = "login";
    static final String REFRESH = "refresh";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public LoginHttpMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String endpoint : List.of(LOGIN, REFRESH)) {
            for (int code : List.of(HttpLoginResponse.CODE_OK, HttpLoginResponse.CODE_AUTH_REJECTED,
                    HttpLoginResponse.CODE_INTERNAL)) {
                counter(endpoint, code);
            }
        }
        counter(LOGIN, HttpLoginResponse.CODE_QUEUEING);
        counter(LOGIN, HttpLoginResponse.CODE_RATE_LIMITED);
    }

    public void login(int code) {
        counter(LOGIN, code).increment();
    }

    public void refresh(int code) {
        counter(REFRESH, code).increment();
    }

    private Counter counter(String endpoint, int code) {
        return counters.computeIfAbsent(endpoint + "/" + code, k -> Counter.builder(NAME)
                .description("HTTP 登录 / 刷新令牌的结局（HTTP 恒 200，按应答体的业务码分类）")
                .tag("endpoint", endpoint)
                .tag("code", Integer.toString(code))
                .register(registry));
    }
}
