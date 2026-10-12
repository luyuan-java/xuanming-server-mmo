package com.game.discovery.namespace;

import com.game.common.GameNamespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 部署命名空间的第二道防线：在上下文内（bean 创建期）比对 Spring Environment 解析出的值与进程里已定格的值，不一致拒绝启动
 * （{@link InvalidGameNamespaceException}，由 {@link GameNamespaceFailureAnalyzer} 报告）。
 * 兜住不经 SpringApplication 的上下文（ApplicationContextRunner、手工 refresh）不跑 EnvironmentPostProcessor、有人移除或绕开了它的情形。
 * 登记在 game-discovery 的 AutoConfiguration.imports。
 */
@AutoConfiguration
public class GameNamespaceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GameNamespaceAutoConfiguration.class);

    /** 本进程的部署命名空间（已核对与 Spring Environment 一致）。 */
    public record DeploymentNamespace(String value) {
    }

    @Bean
    public DeploymentNamespace gameDeploymentNamespace(Environment environment) {
        return new DeploymentNamespace(verify(environment));
    }

    /** 核对并返回命名空间；Environment 里的值非法或与已定格的值不同时抛 {@link InvalidGameNamespaceException}。 */
    static String verify(Environment environment) {
        GameNamespaceEnvironmentPostProcessor.Resolved resolved = GameNamespaceEnvironmentPostProcessor.resolveWithOrigin(environment);
        try {
            GameNamespace.validate(resolved.value(), resolved.origin());
            String fixed = GameNamespace.get();
            if (!fixed.equals(resolved.value())) {
                throw new IllegalStateException("部署命名空间 " + GameNamespace.PROPERTY + " 在本进程里已定格为 \"" + fixed + "\"（来源 "
                        + GameNamespace.source() + "），而本上下文的 Environment 解析出 \"" + resolved.value() + "\"（来源 " + resolved.origin()
                        + "）：不经 SpringApplication 的上下文不会跑 EnvironmentPostProcessor，两边必须同值");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new InvalidGameNamespaceException(resolved.value(), resolved.origin(), e);
        }
        log.debug("部署命名空间核对通过 {}={}", GameNamespace.PROPERTY, resolved.value());
        return resolved.value();
    }
}
