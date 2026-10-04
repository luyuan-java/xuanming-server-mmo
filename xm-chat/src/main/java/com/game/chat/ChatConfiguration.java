package com.game.chat;

import com.game.chat.metrics.ChatMetrics;
import com.game.chat.service.ChatService;
import com.game.chat.store.RedissonChatStore;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** xm-chat 的装配：这里是唯一读配置、碰外部系统的地方。 */
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
    public ChatService chatService(RedissonClient redis, ChatMetrics metrics, ChatProperties props) {
        return new ChatService(new RedissonChatStore(redis), metrics, props.maxContentBytes(), props.rateLimitPerSecond(),
                props.historyMaxEntries(), props.historyTtl(), props.historyDefaultLimit(), props.historyMaxLimit(),
                props.requestIdTtl(), System::currentTimeMillis);
    }

    @Bean
    public ChatDispatcher chatDispatcher(MessageIdRegistry registry, ChatService service, ChatMetrics metrics,
                                         ChatProperties props) {
        ChatDispatcher dispatcher = new ChatDispatcher(registry, service, metrics, props.requestBudget().toMillis());
        log.info("chat 接管的消息号={} 内容上限={} 字节 每秒={} 条 历史={} 条 / {}", dispatcher.routedMessageIds(),
                props.maxContentBytes(), props.rateLimitPerSecond(), props.historyMaxEntries(), props.historyTtl());
        return dispatcher;
    }

    /** Dubbo 调用鉴权密钥的启动检查（fail-fast）；签名 / 校验在 xm-api 的 Dubbo 过滤器里。 */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }
}
