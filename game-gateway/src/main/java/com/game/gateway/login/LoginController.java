package com.game.gateway.login;

import com.game.gateway.ratelimit.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 登录两步走的第一步（客户端契约，同 mmorpg Java Gateway）：{@code POST /api/login} 完成认证、拿 access / refresh 令牌与
 * 角色列表，然后 {@code POST /api/assign-gate} 拿 gate，连上 gate 后用 {@code Login{auth_type:"access_token"}} 完成会话绑定。
 * {@code POST /api/refresh-token} 轮换令牌（不限流：本身很轻，限流会挡掉游戏内正常续期）。异步应答，不占 Servlet 线程等 Dubbo。
 * 登录过开服限流（100 / 429，见 {@link LoginHttpService}）。
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    private final LoginHttpService service;
    private final ClientIpResolver ipResolver;

    public LoginController(LoginHttpService service, ClientIpResolver ipResolver) {
        this.service = service;
        this.ipResolver = ipResolver;
    }

    @PostMapping("/login")
    public CompletableFuture<HttpLoginResponse> login(@RequestBody HttpLoginRequest request, HttpServletRequest http) {
        return service.login(request, ipResolver.resolve(http));
    }

    @PostMapping("/refresh-token")
    public CompletableFuture<HttpRefreshTokenResponse> refresh(@RequestBody HttpRefreshTokenRequest request) {
        return service.refresh(request);
    }
}
