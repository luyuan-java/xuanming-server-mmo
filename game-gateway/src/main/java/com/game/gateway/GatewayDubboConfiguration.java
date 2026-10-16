package com.game.gateway;

import com.game.api.AccountLoginService;
import com.game.api.DubboGroups;
import com.game.common.token.DubboCallAuth;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * game-gateway 调 game-login 的 Dubbo 引用（与 {@link GatewayConfiguration} 分开：只装配业务的测试可以换上替身，不起 Dubbo）。
 */
@Configuration(proxyBeanMethods = false)
public class GatewayDubboConfiguration {

    /**
     * Dubbo 调用鉴权密钥的启动检查（fail-fast，报错信息明确）。真正的签名在 game-api 的 Dubbo 调用方过滤器里，
     * 它自己也从同一个环境变量读密钥、缺失即实例化失败。
     */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    /**
     * game-login 的无会话登录（Dubbo group = login）。默认直连 {@code game.dubbo.login-url}，nacos profile 该值置空走注册中心。
     * 不重试（retries = 0）：刷新令牌是一次性轮换，login 只在重放宽限窗口内（缺省 30s，可配成 0）对同一旧 refresh 回同一对，
     * 窗口为 0 时网关重试会把「login 暂时不可用」变成「认证被拒」、让客户端放弃重试去重登，所以重试留给客户端；
     * 登录重试会让客户端的一次点击变成两次认证。
     * 超时取 {@code dubbo.consumer.timeout}（3000 ms，同基线 login.grpc.timeout-ms；须小于客户端 HTTP 的 5 s，失败才能带着业务码回去）。
     */
    @Bean
    @DubboReference(group = DubboGroups.LOGIN, check = false, url = "${game.dubbo.login-url:}", retries = 0)
    public ReferenceBean<AccountLoginService> accountLoginService() {
        return new ReferenceBean<>();
    }
}
