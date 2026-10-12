package com.game.discovery.namespace;

import com.game.common.GameNamespace;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Spring 进程里定格部署命名空间（{@code game.namespace}，见 {@link GameNamespace}）：在建任何 bean（Redisson、DataSource、Kafka、Dubbo）之前，
 * 从 Spring Environment 按宽松绑定读出取值（{@code --game.namespace}、{@code -Dgame.namespace}、环境变量 {@code GAME_NAMESPACE}、
 * {@code SPRING_APPLICATION_JSON}、application*.yaml 与 profile 都认），校验后 {@link GameNamespace#set}。登记在 game-discovery 的
 * {@code META-INF/spring.factories}（Spring Boot 3.x 的键 {@code org.springframework.boot.env.EnvironmentPostProcessor}，升 Boot 4 时改名）；
 * 13 个 Spring 进程都直接依赖 game-discovery，自动生效。
 *
 * <p><b>顺序硬约束</b>：先 {@link GameNamespace#set}，之后才允许触碰任何名字类（RedisKeys、KafkaNames、AuditTopics、BattleResultTopics……），
 * 打日志也不先于 set。order 取 {@link Ordered#LOWEST_PRECEDENCE}，排在 ConfigData（application*.yaml、profile）之后。
 * 值非法或与已定格的值冲突抛 {@link InvalidGameNamespaceException}：此时还在 prepareEnvironment、日志系统尚未初始化，
 * 信息放在异常与 {@link GameNamespaceFailureAnalyzer} 里（延迟日志冲不出去）。
 *
 * <p>Nacos：命名空间不是 {@code game}、注册中心地址是 {@code nacos://}、且没有显式配置 Nacos namespace（{@code dubbo.registry.parameters.namespace}
 * 及其宽松名，或地址查询串里的 {@code namespace=}）时，追加一个最低优先级的属性源令 {@code dubbo.registry.parameters.namespace=<ns>}；
 * 缺省 {@code game} 什么都不加（nacos profile 照旧落在 public，现状不变）。显式配置（例如 Nacos 控制台生成的 UUID）优先。
 */
public class GameNamespaceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 追加的 Nacos 属性源的名字。 */
    static final String NACOS_PROPERTY_SOURCE = "gameNamespaceNacos";
    /** Dubbo 注册中心的 Nacos namespace 参数（RegistryConfig 没有 namespace 字段，Dubbo 文档的写法就是 parameters.namespace）。 */
    static final String NACOS_NAMESPACE_PROPERTY = "dubbo.registry.parameters.namespace";
    static final String REGISTRY_ADDRESS_PROPERTY = "dubbo.registry.address";

    private final Log log;

    public GameNamespaceEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(GameNamespaceEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Resolved resolved = resolveWithOrigin(environment);
        try {
            GameNamespace.set(resolved.value(), "Spring Environment（" + resolved.origin() + "）");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new InvalidGameNamespaceException(resolved.value(), resolved.origin(), e);
        }
        log.info("部署命名空间 " + GameNamespace.PROPERTY + "=" + resolved.value() + "（来源 " + resolved.origin() + "）");
        nacosNamespace(environment, resolved.value()).ifPresent(ns -> {
            environment.getPropertySources().addLast(new MapPropertySource(NACOS_PROPERTY_SOURCE, Map.of(NACOS_NAMESPACE_PROPERTY, ns)));
            log.info("Nacos 注册隔离：" + NACOS_NAMESPACE_PROPERTY + "=" + ns + "（由部署命名空间派生；显式配置可覆盖）");
        });
    }

    /** 解析出的命名空间与来源。 */
    record Resolved(String value, String origin) {
    }

    /**
     * Spring Environment 里的部署命名空间（Boot 推荐的宽松绑定入口 {@link Binder}）；没配时为 {@value GameNamespace#DEFAULT}。只解析、不校验、不定格：
     * 供命令行工具（assetopfix）与单测复用。
     */
    public static String resolve(Environment environment) {
        return resolveWithOrigin(environment).value();
    }

    static Resolved resolveWithOrigin(Environment environment) {
        BindResult<String> bound = Binder.get(environment).bind(GameNamespace.PROPERTY, String.class);
        if (!bound.isBound()) {
            return new Resolved(GameNamespace.DEFAULT, "缺省");
        }
        return new Resolved(bound.get(), origin(environment));
    }

    /** 给出取值的属性源名（按优先级第一个含有它的源）；找不到时为「Spring Environment」。 */
    private static String origin(Environment environment) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(GameNamespace.PROPERTY);
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            ConfigurationProperty property = source.getConfigurationProperty(name);
            if (property != null) {
                Object underlying = source.getUnderlyingSource();
                return underlying instanceof PropertySource<?> ps ? ps.getName() : String.valueOf(underlying);
            }
        }
        return "Spring Environment";
    }

    /**
     * Nacos 规则（纯函数）：要追加的 Nacos namespace；不追加为空。四条同时满足才追加：命名空间不是缺省 {@code game}；注册中心地址以 {@code nacos://} 开头；
     * Environment 里没有 {@value #NACOS_NAMESPACE_PROPERTY}（含 {@code DUBBO_REGISTRY_PARAMETERS_NAMESPACE} 等宽松名）；地址查询串里没有 {@code namespace=}。
     * 多注册中心写法（{@code dubbo.registries.*}）不处理，用时必须显式配置。
     */
    static Optional<String> nacosNamespace(Environment environment, String namespace) {
        if (GameNamespace.DEFAULT.equals(namespace)) {
            return Optional.empty();
        }
        Binder binder = Binder.get(environment);
        String address = binder.bind(REGISTRY_ADDRESS_PROPERTY, String.class).orElse(null);
        if (address == null || !address.strip().startsWith("nacos://")) {
            return Optional.empty();
        }
        if (binder.bind(NACOS_NAMESPACE_PROPERTY, String.class).isBound() || queryHasNamespace(address)) {
            return Optional.empty();
        }
        return Optional.of(namespace);
    }

    /** 地址的查询串里有没有 {@code namespace} 参数（如 {@code nacos://h:8848?namespace=xxx}，常见于 GAME_NACOS_ADDR 里自带）。 */
    static boolean queryHasNamespace(String address) {
        int q = address.indexOf('?');
        if (q < 0) {
            return false;
        }
        for (String pair : address.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = (eq < 0 ? pair : pair.substring(0, eq)).strip();
            if (key.equals("namespace")) {
                return true;
            }
        }
        return false;
    }
}
