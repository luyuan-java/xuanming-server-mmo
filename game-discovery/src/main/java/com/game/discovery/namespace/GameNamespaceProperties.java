package com.game.discovery.namespace;

import com.game.common.GameNamespace;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 部署命名空间的配置绑定（{@code game.namespace}，环境变量 {@code GAME_NAMESPACE} 经宽松绑定生效，缺省 {@code game}）。取值规则见
 * {@link GameNamespace}：校验由 Bean Validation（{@code @Validated} + {@code @Pattern}）在绑定时完成，非法值由 Spring Boot 自带的绑定校验失败报告
 * （BindValidationException，{@code APPLICATION FAILED TO START} 里列出 Property / Value / Origin / Reason）拒启，此时还没有任何 bean 连过中间件。
 *
 * <p>只有「没配」才取缺省 {@code game}：显式给了空串（{@code game.namespace=}、{@code GAME_NAMESPACE=}）绑定成空串、被 {@code @Pattern} 拒绝，
 * 不会被 {@code @DefaultValue} 换回 game。前缀 {@code game} 与 game-scene 的 {@code SceneNodeProperties} 共用（各绑各的字段）。
 *
 * @param namespace 部署命名空间的原值（不 trim、不转小写）
 */
@ConfigurationProperties("game")
@Validated
public record GameNamespaceProperties(
        @DefaultValue(GameNamespace.DEFAULT_VALUE)
        @NotNull
        @Pattern(regexp = GameNamespace.REGEX, message = GameNamespace.RULE)
        String namespace) {
}
