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
import com.game.gate.session.MessageRoutes;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * 聚宝斋后端（Dubbo group = proto 域 trade，xm-trade 提供；trade-spec §5.2）；直连 {@code xm.dubbo.trade-url}，nacos profile 置空走注册中心。
     * 4.7 只有只读面与收藏，{@code handle} 照样不重试：读路径重试会在超时时把负载翻倍，Dubbo 内部重投还会打乱 xm-trade 的 3.5 s 请求预算；
     * 收藏虽然幂等，也没有重投的必要。xm-trade 不在或调用失败时客户端收到带请求 id 的信封 1003（T8，聚宝斋按设计不隔离）。
     */
    @Bean
    @DubboReference(group = DubboGroups.TRADE, check = false, url = "${xm.dubbo.trade-url:}",
            methods = @Method(name = "handle", retries = 0))
    public ReferenceBean<ClientMessageService> tradeClientMessageService() {
        return new ReferenceBean<>();
    }

    /**
     * 匹配后端（Dubbo group = proto 域 match，xm-match 提供；match-spec §9.2）；直连 {@code xm.dubbo.match-url}，nacos profile 置空走注册中心。
     * {@code MatchService} 的 10 个号（排队 157 / 148 / 153、切磋 152 / 151 与推送占位 156 / 154、观战 163 / 164、补签 179）都走它。
     * 排队与切磋是写路径，{@code handle} 必须不重试：超时后重投的 157 会撞上第一次已经建好的票、回 16001 而不是受理；Dubbo 内部重投还会打乱
     * xm-match 的 4.5 s 请求预算（它排在 gate 的 5 s 调用超时之内，{@code dubbo.consumer.timeout}）。xm-match 不在或调用失败时客户端收到带
     * 请求 id 的信封 1003（§8.1）。
     *
     * <p><b>后端要先于 gate 启动</b>（本类全部引用同理；{@code BackendReconnectTest} 用真 Triple 钉住）：引用是 {@code check = false} 的直连，
     * gate 启动时后端不在只记日志、照常起来，之后的调用立刻失败（信封 1003）；但 Dubbo 3.3.6 的 Triple 客户端首次建连没连上时，下一次重连
     * 排在 60 s 之后（断线后 1 s 重连一次，那一次落空也是再等 60 s；机制见 xm-api {@code SceneAssetOpClients#RECONNECT_INTERVAL}），晚起来的
     * 后端要等到那一刻才通。所以 {@code tools/local/start-slice.sh} 把 xm-match 排在 xm-team 与 gate 之前（没有照规格草案放在 xm-battle 之后）。
     * 运行中重启某个后端不用重启 gate——同一个引用到点自己连上，只是后端回来之后最长还要等约一个重连周期。这里的引用没有像
     * {@code NodeRpcClients} 那样把重连间隔压到 1 s：那是全部后端一起的运维取舍，不随接入 xm-match 单独改。
     */
    @Bean
    @DubboReference(group = DubboGroups.MATCH, check = false, url = "${xm.dubbo.match-url:}",
            methods = @Method(name = "handle", retries = 0))
    public ReferenceBean<ClientMessageService> matchClientMessageService() {
        return new ReferenceBean<>();
    }

    /**
     * login 以外的客户端消息后端表（消息域 → Dubbo 引用），交给 {@link GateNode}。它必须与 {@link MessageRoutes#SERVICE_BACKENDS} 对得上：
     * 路由表把某个服务指到一个域、这里却没有那个域的引用时，dispatcher 会把它当成「未接入」推 23 {1003}——接入新后端时两处漏改一处
     * 就是这个结果，而且进程照常起来、只有发那个服务的号才看得出。所以装配时当场核对，对不上拒绝启动。
     *
     * @throws IllegalStateException 路由表里有后端域没有引用，或这里多出了路由表不认识的域
     */
    static Map<String, ClientMessageService> backends(ClientMessageService friend, ClientMessageService chat,
                                                      ClientMessageService team, ClientMessageService guild,
                                                      ClientMessageService trade, ClientMessageService match) {
        Map<String, ClientMessageService> backends = Map.of(
                DubboGroups.FRIEND, friend,
                DubboGroups.CHAT, chat,
                DubboGroups.TEAM, team,
                DubboGroups.GUILD, guild,
                DubboGroups.TRADE, trade,
                DubboGroups.MATCH, match);
        Set<String> routed = new TreeSet<>(MessageRoutes.SERVICE_BACKENDS.values());
        routed.remove(DubboGroups.LOGIN); // login 单独传给 GateNode：它的调用占会话唯一的在途位、还带会话指令
        if (!routed.equals(backends.keySet())) {
            throw new IllegalStateException("客户端消息后端表与路由表对不上：MessageRoutes.SERVICE_BACKENDS 指向的域（login 除外）= " + routed
                    + "，GateConfiguration 装配的后端 = " + new TreeSet<>(backends.keySet()) + "；接入新后端要两处一起改");
        }
        return backends;
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
                             @Qualifier("tradeClientMessageService") ClientMessageService tradeClientMessageService,
                             @Qualifier("matchClientMessageService") ClientMessageService matchClientMessageService,
                             GateProperties properties, GateMetrics gateMetrics, @Value("${xm.zone-id:1}") int zoneId,
                             @Value("${xm.advertise-host:127.0.0.1}") String advertiseHost,
                             @Value("${xm.table-dir:config-data/tables}") String tableDir,
                             @Value("${xm.run-mode:prod}") String runMode) {
        if (!RunMode.isRecognized(runMode)) {
            LoggerFactory.getLogger(GateConfiguration.class)
                    .warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（GM 指令拒绝）: '{}'", runMode);
        }
        return new GateNode(redis, messageIdRegistry, gateTokens, nodeLinkAuth, loginClientMessageService,
                backends(friendClientMessageService, chatClientMessageService, teamClientMessageService,
                        guildClientMessageService, tradeClientMessageService, matchClientMessageService),
                properties, zoneId, advertiseHost, Path.of(tableDir), gateMetrics, RunMode.parse(runMode));
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
