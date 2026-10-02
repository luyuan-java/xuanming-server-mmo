package com.game.gate;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.common.token.DubboCallAuth;
import com.game.common.token.GateTokens;
import com.game.common.RunMode;
import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.gate.metrics.GateMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** gate 的装配。秘密只从环境变量读，缺失即启动失败（fail-closed）。 */
@Configuration(proxyBeanMethods = false)
public class GateConfiguration {

    /** 环境变量名：gateway 用同一密钥签发令牌，gate 验签。 */
    static final String TOKEN_SECRET_ENV = "XM_GATE_TOKEN_SECRET";

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public GateTokens gateTokens(Environment environment) {
        String secret = environment.getProperty(TOKEN_SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + TOKEN_SECRET_ENV + "（gate 令牌密钥，须与 xm-gateway 一致）");
        }
        return GateTokens.ofUtf8(secret);
    }

    /** gate → scene 链路握手鉴权。密钥只从环境变量 {@code XM_NODE_LINK_SECRET} 读，缺失即启动失败。 */
    @Bean
    public NodeLinkAuth nodeLinkAuth(Environment environment) {
        return NodeLinkAuth.requireFromEnvValue(environment.getProperty(NodeLinkAuth.SECRET_ENV));
    }

    /**
     * Dubbo 调用鉴权密钥的启动检查（fail-fast，报错信息明确）。真正的签名在 xm-api 的 Dubbo 调用方过滤器里，
     * 它自己也从同一个环境变量读密钥、缺失即实例化失败。
     */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    /** login 后端（Dubbo group = proto 域 login）。local profile 直连 {@code xm.dubbo.login-url}，nacos profile 该值置空走注册中心。 */
    @Bean
    @DubboReference(group = DubboGroups.LOGIN, check = false, url = "${xm.dubbo.login-url:}")
    public ReferenceBean<ClientMessageService> loginClientMessageService() {
        return new ReferenceBean<>();
    }

    /** gate 指标，注册到 actuator 提供的注册表（Prometheus 导出，见 architecture.md §11）。 */
    @Bean
    public GateMetrics gateMetrics(MeterRegistry meterRegistry) {
        return new GateMetrics(meterRegistry);
    }

    /**
     * @param tableDir 配置表目录（只读 MessageLimiter 表做按消息号限频），相对进程工作目录；从别处启动时用 XM_TABLE_DIR 覆盖
     */
    @Bean
    public GateNode gateNode(RedissonClient redis, MessageIdRegistry messageIdRegistry, GateTokens gateTokens,
                             NodeLinkAuth nodeLinkAuth, ClientMessageService loginClientMessageService,
                             GateProperties properties, GateMetrics gateMetrics, @Value("${xm.zone-id:1}") int zoneId,
                             @Value("${xm.advertise-host:127.0.0.1}") String advertiseHost,
                             @Value("${xm.table-dir:config-data/tables}") String tableDir,
                             @Value("${xm.run-mode:prod}") String runMode) {
        if (!RunMode.isRecognized(runMode)) {
            LoggerFactory.getLogger(GateConfiguration.class)
                    .warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（GM 指令拒绝）: '{}'", runMode);
        }
        return new GateNode(redis, messageIdRegistry, gateTokens, nodeLinkAuth, loginClientMessageService, properties,
                zoneId, advertiseHost, Path.of(tableDir), gateMetrics, RunMode.parse(runMode));
    }
}
