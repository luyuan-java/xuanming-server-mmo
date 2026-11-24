package com.game.contentguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.contentguard.engine.ContentGuardEngine;
import com.game.contentguard.platform.DouyinAdapter;
import com.game.contentguard.platform.PlatformAdapter;
import com.game.contentguard.platform.PlatformReviewer;
import com.game.contentguard.platform.WeChatAdapter;
import com.game.contentguard.service.ContentGuardRpcService;
import com.game.contentguard.wordlist.Lexicon;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 内容安全微服务入口（无状态，Dubbo Triple 端口 20919，Actuator 管理端口 18119）。 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.game.contentguard")
@EnableConfigurationProperties(ContentGuardProperties.class)
public class ContentGuardApplication {

    public static void main(String[] args) {
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

    @Bean
    PlatformReviewer platformReviewer(ContentGuardProperties props, ObjectMapper json, HttpClient http) {
        ContentGuardProperties.PlatformProperties p = props.platform();
        Clock clock = Clock.systemUTC();
        List<PlatformAdapter> adapters = new ArrayList<>();
        if (!p.wechatAppId().isBlank() && !p.wechatAppSecret().isBlank()) {
            adapters.add(new WeChatAdapter(p.wechatAppId(), p.wechatAppSecret(), p.wechatBaseUrl(), http, json, clock));
        }
        if (!p.douyinAppId().isBlank() && !p.douyinAppSecret().isBlank()) {
            adapters.add(new DouyinAdapter(p.douyinAppId(), p.douyinAppSecret(), p.douyinBaseUrl(), http, json, clock));
        }
        return new PlatformReviewer(
                adapters,
                null,
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
