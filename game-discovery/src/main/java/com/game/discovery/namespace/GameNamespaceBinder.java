package com.game.discovery.namespace;

import com.game.common.GameNamespace;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 不起 Spring 上下文的命令行工具（AuditFallbackReplay、PasswordAdmin、assetopfix）取部署命名空间：与服务进程<b>同一个绑定类型</b>
 * {@link GameNamespaceProperties}、同一套宽松绑定，再按 {@link GameNamespace} 的规则校验（不 trim、显式空串照样拒绝，没配才取 {@code game}）。
 * 服务进程认的 {@code -Dgame.namespace} 与环境变量 {@code GAME_NAMESPACE} 工具都认、优先级相同（系统属性在前），所以运维约定只有一条：工具与服务进程
 * 拿到同一个 {@code GAME_NAMESPACE}（或 {@code -Dgame.namespace}）。assetopfix 另把它 {@code -f} 读的 yaml 放在最后（{@link #bind(ConfigurableEnvironment)}）。
 *
 * <p>只取值：不注册 bean、不留任何状态。返回值带来源（Spring Boot 的 Origin，如 {@code "GAME_NAMESPACE" from property source "systemEnvironment"}；没配时为「缺省」），
 * 供工具打印；非法值抛 {@link IllegalArgumentException}，消息里点名配置键、环境变量、原值与来源。
 */
public final class GameNamespaceBinder {

    /** 没配时的来源说明。 */
    public static final String DEFAULT_ORIGIN = "缺省";

    private static final ConfigurationPropertyName NAME = ConfigurationPropertyName.of(GameNamespace.PROPERTY);

    private GameNamespaceBinder() {
    }

    /**
     * 绑定结果。
     *
     * @param namespace 校验过的部署命名空间
     * @param origin    取值来源（Spring Boot 的 Origin 文本；没配为 {@value #DEFAULT_ORIGIN}）
     */
    public record Bound(GameNamespace namespace, String origin) {

        public Bound {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /**
     * 从系统属性与环境变量绑定：与 {@link StandardEnvironment} 同样的两个属性源、同样的名字与次序（系统属性优先），环境变量按 Spring Boot 的宽松规则映射。
     * 生产传 {@code System.getProperties()} 与 {@code System.getenv()}，测试传任意映射。
     *
     * @throws IllegalArgumentException 取值非法
     */
    public static Bound bind(Map<?, ?> systemProperties, Map<String, String> systemEnvironment) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> properties = new HashMap<>();
        systemProperties.forEach((key, value) -> properties.put(String.valueOf(key), value));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, properties));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(systemEnvironment)));
        return bind(environment);
    }

    /**
     * 从给定的 Environment 绑定（按它的属性源次序；assetopfix 用：系统属性、环境变量，最后是它 {@code -f} 读的 yaml）。
     *
     * @throws IllegalArgumentException 取值非法
     */
    public static Bound bind(ConfigurableEnvironment environment) {
        AtomicReference<ConfigurationProperty> source = new AtomicReference<>();
        GameNamespaceProperties properties;
        try {
            properties = Binder.get(environment).bindOrCreate(NAME.getParent(), Bindable.of(GameNamespaceProperties.class), new AbstractBindHandler() {
                @Override
                public Object onSuccess(ConfigurationPropertyName name, Bindable<?> target, BindContext context, Object result) {
                    if (NAME.equals(name) && context.getConfigurationProperty() != null) {
                        source.set(context.getConfigurationProperty());
                    }
                    return super.onSuccess(name, target, context, result);
                }
            });
        } catch (BindException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            throw new IllegalArgumentException("部署命名空间 " + GameNamespace.PROPERTY + "（环境变量 " + GameNamespace.ENV + "）绑定失败: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
        }
        ConfigurationProperty property = source.get();
        String origin = property == null ? DEFAULT_ORIGIN
                : property.getOrigin() != null ? property.getOrigin().toString() : property.getSource().toString();
        return new Bound(GameNamespace.of(properties.namespace(), origin), origin);
    }
}
