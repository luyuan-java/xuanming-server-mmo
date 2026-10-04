package com.game.gate;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.common.token.DubboCallAuth;
import com.game.common.token.GateTokens;
import com.game.common.token.GmRequestAuth;
import com.game.common.token.GmShutdownHandler;
import com.game.common.RunMode;
import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.gate.admin.GmShutdownController;
import com.game.gate.metrics.GateMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.Method;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** gate 的装配。秘密只从环境变量读，缺失即启动失败（fail-closed）。 */
@Configuration(proxyBeanMethods = false)
public class GateConfiguration {

    /** GM 停机受理到开始停机之间留给应答写出的时间。 */
    static final Duration GM_EXIT_DELAY = Duration.ofMillis(300);

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

    /**
     * login 后端（Dubbo group = proto 域 login）。local profile 直连 {@code xm.dubbo.login-url}，nacos profile 该值置空走注册中心。
     * {@code handle} 不重试：客户端消息不幂等（建角、加好友……），Dubbo 缺省的 failover 会在超时后重发同一次调用；
     * 断线通知（sessionClosed）幂等，保留缺省重试。
     */
    @Bean
    @DubboReference(group = DubboGroups.LOGIN, check = false, url = "${xm.dubbo.login-url:}",
            methods = @Method(name = "handle", retries = 0))
    public ReferenceBean<ClientMessageService> loginClientMessageService() {
        return new ReferenceBean<>();
    }

    /**
     * 好友后端（Dubbo group = proto 域 friend，xm-friend 提供）。local profile 直连 {@code xm.dubbo.friend-url}，nacos profile
     * 置空走注册中心。xm-friend 没起来时调用失败，客户端收到「服务不可用」。
     */
    @Bean
    @DubboReference(group = DubboGroups.FRIEND, check = false, url = "${xm.dubbo.friend-url:}",
            methods = @Method(name = "handle", retries = 0))
    public ReferenceBean<ClientMessageService> friendClientMessageService() {
        return new ReferenceBean<>();
    }

    /** 聊天后端（Dubbo group = proto 域 chat，xm-chat 提供）；直连 {@code xm.dubbo.chat-url}，nacos profile 置空走注册中心。 */
    @Bean
    @DubboReference(group = DubboGroups.CHAT, check = false, url = "${xm.dubbo.chat-url:}",
            methods = @Method(name = "handle", retries = 0))
    public ReferenceBean<ClientMessageService> chatClientMessageService() {
        return new ReferenceBean<>();
    }

    /**
     * 组队后端（Dubbo group = proto 域 team，xm-team 提供）；直连 {@code xm.dubbo.team-url}，nacos profile 置空走注册中心。
     * 写路径（建队、同意、踢人……）不幂等，{@code handle} 必须不重试（team-spec §6.2）。
     */
    @Bean
    @DubboReference(group = DubboGroups.TEAM, check = false, url = "${xm.dubbo.team-url:}",
            methods = @Method(name = "handle", retries = 0))
    public ReferenceBean<ClientMessageService> teamClientMessageService() {
        return new ReferenceBean<>();
    }

    /**
     * 帮会后端（Dubbo group = proto 域 guild，xm-guild 提供）；直连 {@code xm.dubbo.guild-url}，nacos profile 置空走注册中心。
     * 写路径（建帮、审批、踢人、解散……）不幂等，{@code handle} 必须不重试（guild-spec §7.2）。
     */
    @Bean
    @DubboReference(group = DubboGroups.GUILD, check = false, url = "${xm.dubbo.guild-url:}",
            methods = @Method(name = "handle", retries = 0))
    public ReferenceBean<ClientMessageService> guildClientMessageService() {
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
                             NodeLinkAuth nodeLinkAuth,
                             @Qualifier("loginClientMessageService") ClientMessageService loginClientMessageService,
                             @Qualifier("friendClientMessageService") ClientMessageService friendClientMessageService,
                             @Qualifier("chatClientMessageService") ClientMessageService chatClientMessageService,
                             @Qualifier("teamClientMessageService") ClientMessageService teamClientMessageService,
                             @Qualifier("guildClientMessageService") ClientMessageService guildClientMessageService,
                             GateProperties properties, GateMetrics gateMetrics, @Value("${xm.zone-id:1}") int zoneId,
                             @Value("${xm.advertise-host:127.0.0.1}") String advertiseHost,
                             @Value("${xm.table-dir:config-data/tables}") String tableDir,
                             @Value("${xm.run-mode:prod}") String runMode) {
        if (!RunMode.isRecognized(runMode)) {
            LoggerFactory.getLogger(GateConfiguration.class)
                    .warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（GM 指令拒绝）: '{}'", runMode);
        }
        return new GateNode(redis, messageIdRegistry, gateTokens, nodeLinkAuth, loginClientMessageService,
                Map.of(DubboGroups.FRIEND, friendClientMessageService,
                        DubboGroups.CHAT, chatClientMessageService,
                        DubboGroups.TEAM, teamClientMessageService,
                        DubboGroups.GUILD, guildClientMessageService), properties, zoneId, advertiseHost, Path.of(tableDir),
                gateMetrics, RunMode.parse(runMode));
    }

    /** GM 签名停机的校验（密钥只从环境变量 XM_GM_ADMIN_SECRET 读，没配一律拒）。 */
    @Bean
    public GmRequestAuth gmRequestAuth(Environment environment) {
        GmRequestAuth auth = GmRequestAuth.fromEnvValues(environment.getProperty(GmRequestAuth.SECRET_ENV),
                environment.getProperty(GmRequestAuth.SKEW_ENV), () -> System.currentTimeMillis() / 1000);
        if (!auth.configured()) {
            LoggerFactory.getLogger(GateConfiguration.class).info("没配 {}：GM 签名停机一律拒绝", GmRequestAuth.SECRET_ENV);
        }
        return auth;
    }

    /**
     * GM 签名停机的受理（只收本机来的请求；确需远程调用时设 XM_GM_ALLOW_REMOTE=true——管理端口为了 Prometheus 跨机抓取
     * 绑到内网地址时，停机接口不该跟着暴露）。
     */
    @Bean
    public GmShutdownHandler gmShutdownHandler(GmRequestAuth gmRequestAuth, GateNode gateNode,
                                               @Value("${XM_GM_ALLOW_REMOTE:false}") boolean allowRemote) {
        return new GmShutdownHandler(GmShutdownController.METHOD, gmRequestAuth,
                () -> new GmShutdownHandler.Identity(gateNode.zoneId(), gateNode.nodeId(), gateNode.instanceId()),
                gateNode::sessionCount, allowRemote);
    }

    /** GM 停机受理后：等应答写出，再按正常流程关 Spring 上下文（触发 {@link GateNode#stop()}）并退出进程。 */
    @Bean
    public GmShutdownController.ProcessExit gmProcessExit(ConfigurableApplicationContext context) {
        return () -> Thread.ofPlatform().name("gm-shutdown").start(() -> {
            LockSupport.parkNanos(GM_EXIT_DELAY.toNanos());
            System.exit(SpringApplication.exit(context, () -> 0));
        });
    }
}
