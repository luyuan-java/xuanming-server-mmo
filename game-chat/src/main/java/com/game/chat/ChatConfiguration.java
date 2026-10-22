package com.game.chat;

import com.game.chat.metrics.ChatMetrics;
import com.game.chat.service.ChatService;
import com.game.chat.store.JdbcChatStore;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.RedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** game-chat 的装配：这里是唯一读配置、碰外部系统的地方。 */
@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChatConfiguration.class);

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public ChatMetrics chatMetrics(MeterRegistry meterRegistry) {
        return new ChatMetrics(meterRegistry);
    }

    @Bean
    @org.springframework.context.annotation.DependsOn("chatSqlReady")
    public ChatService chatService(JdbcChatStore store, RedisKeys keys, ChatMetrics metrics, ChatProperties props) {
        return new ChatService(store, metrics, props, keys);
    }

    @Bean
    public ChatDispatcher chatDispatcher(MessageIdRegistry registry, ChatService service, ChatMetrics metrics,
                                         ChatProperties props) {
        ChatDispatcher dispatcher = new ChatDispatcher(registry, service, metrics, props.requestBudget().toMillis());
        log.info("chat 接管的消息号={} 内容上限={} 字节 每秒={} 条 历史={} 条 / {}", dispatcher.routedMessageIds(),
                props.maxContentBytes(), props.rateLimitPerSecond(), props.historyMaxEntries(), props.historyTtl());
        return dispatcher;
    }

    /** Dubbo 调用鉴权密钥的启动检查（fail-fast）；签名 / 校验在 game-api 的 Dubbo 过滤器里。 */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }
}
