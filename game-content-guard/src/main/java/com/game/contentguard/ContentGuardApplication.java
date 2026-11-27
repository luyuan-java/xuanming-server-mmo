package com.game.contentguard;

import com.game.common.startup.ClockCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.AccountLoginService;
import com.game.api.DubboGroups;
import com.game.contentguard.engine.ContentGuardEngine;
import com.game.contentguard.platform.DouyinAdapter;
import com.game.contentguard.platform.PlatformAdapter;
import com.game.contentguard.platform.PlatformReviewer;
import com.game.contentguard.platform.WeChatAdapter;
import com.game.contentguard.service.ContentGuardRpcService;
import com.game.contentguard.wordlist.Lexicon;
import com.game.proto.data_service.GetPlayerChannelIdentityRequest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 内容安全微服务入口（无状态，Dubbo Triple 端口 20919，Actuator 管理端口 18119）。 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.game.contentguard")
@EnableConfigurationProperties(ContentGuardProperties.class)
public class ContentGuardApplication {

    public static void main(String[] args) {
        ClockCheck.mustPass("game-content-guard");
        SpringApplication.run(ContentGuardApplication.class, args);
    }

    @Bean
    ContentGuardEngine contentGuardEngine(ContentGuardProperties props) throws IOException {
        ContentGuardEngine engine = new ContentGuardEngine();
        if (!props.lexiconFile().isBlank()) {
            Path path = Path.of(props.lexiconFile());
            Lexicon lexicon = Lexicon.parse(Files.readAllBytes(path));
            long version = Math.max(1L, Files.getLastModifiedTime(path).toMillis());
            engine.swap(version, lexicon);
        }
        return engine;
    }

    @Bean(destroyMethod = "close")
    HttpClient contentGuardHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Configuration(proxyBeanMethods = false)
    static class IdentityLookupConfiguration {
        @DubboReference(group = DubboGroups.LOGIN, check = false, url = "${game.dubbo.login-url:}", retries = 0)
        private AccountLoginService loginService;

        @Bean
        PlatformReviewer.IdentityLookup platformIdentityLookup(ContentGuardProperties props) {
            long timeoutMs = Math.max(10L, props.platform().lookupTimeoutMs());
            return playerId -> {
                var resp = loginService.getPlayerChannelIdentity(
                                GetPlayerChannelIdentityRequest.newBuilder().setPlayerId(playerId).build())
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
                if (resp == null || !resp.getFound()) {
                    return PlatformReviewer.Identity.EMPTY;
                }
                return new PlatformReviewer.Identity(resp.getChannel(), resp.getOpenId(), resp.getAccount());
            };
        }
    }

    @Bean
    PlatformReviewer platformReviewer(ContentGuardProperties props, ObjectMapper json, HttpClient http,
                                      ObjectProvider<PlatformReviewer.IdentityLookup> lookupProvider) {
        return createPlatformReviewer(props, json, http, lookupProvider == null ? null : lookupProvider.getIfAvailable());
    }

    static PlatformReviewer createPlatformReviewer(ContentGuardProperties props, ObjectMapper json, HttpClient http,
                                                   PlatformReviewer.IdentityLookup identityLookup) {
        ContentGuardProperties.PlatformProperties p = props.platform();
        // 半配凭据（只填 appId 或只填 appSecret）必须 fail-closed 拒启，杜绝生产静默降级跳过平台内容安全审核。
        if (p.wechatAppId().isBlank() != p.wechatAppSecret().isBlank()) {
            throw new IllegalStateException("微信平台内容安全凭据配置不完整：wechat-app-id 与 wechat-app-secret 必须同时配置");
        }
        if (p.douyinAppId().isBlank() != p.douyinAppSecret().isBlank()) {
            throw new IllegalStateException("抖音平台内容安全凭据配置不完整：douyin-app-id 与 douyin-app-secret 必须同时配置");
        }
        Clock clock = Clock.systemUTC();
        List<PlatformAdapter> adapters = new ArrayList<>();
        if (!p.wechatAppId().isBlank()) {
            adapters.add(new WeChatAdapter(p.wechatAppId(), p.wechatAppSecret(), p.wechatBaseUrl(), http, json, clock));
        }
        if (!p.douyinAppId().isBlank()) {
            adapters.add(new DouyinAdapter(p.douyinAppId(), p.douyinAppSecret(), p.douyinBaseUrl(), http, json, clock));
        }
        if (!adapters.isEmpty() && identityLookup == null) {
            throw new IllegalStateException("已启用外部平台内容安全审核，必须装配玩家渠道身份查询（IdentityLookup）");
        }
        return new PlatformReviewer(
                adapters,
                identityLookup,
                Duration.ofMillis(p.syncTimeoutMs()),
                Duration.ofMillis(p.worldTimeoutMs()),
                Duration.ofMillis(p.asyncTimeoutMs()),
                clock);
    }

    @Bean
    HealthIndicator contentGuardHealthIndicator(ContentGuardRpcService service, ContentGuardEngine engine) {
        return () -> {
            long version = engine.current().version();
            return service.isReady()
                    ? Health.up().withDetail("lexiconVersion", version).build()
                    : Health.down().withDetail("reason", "lexicon_not_loaded").withDetail("lexiconVersion", version).build();
        };
    }
}
