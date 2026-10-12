package com.game.discovery.namespace;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 库名核对的应急开关 {@code game.mysql.expect-database}（{@link NamespaceDatabaseVerifier}）：库名偏离规格（≠ 部署命名空间）的存量部署显式给出期望库名，
 * 放行时打 WARN。与 {@link GameNamespaceProperties} 同一种标准做法：{@code @ConfigurationProperties} 绑定、Bean Validation 校验，非法值由 Spring Boot 自带的
 * 绑定校验报告（{@code APPLICATION FAILED TO START}，列出 Property / Value / Origin / Reason）拒启。
 *
 * <p>没配为 null（规格：期望库名 = 命名空间）；给了就必须是不含空白的库名——显式空值（{@code GAME_MYSQL_EXPECT_DATABASE=}）不当成没配，由 {@code @Pattern}
 * 拒绝（{@code @Pattern} 对 null 放行）。环境变量两种写法都认：{@code GAME_MYSQL_EXPECT_DATABASE}（文档用的写法，Spring Boot 宽松绑定兼容的「横线换下划线」）
 * 与规范写法 {@code GAME_MYSQL_EXPECTDATABASE}（去掉横线）。前缀 {@code game.mysql} 下的其它键（{@code GAME_MYSQL_PASSWORD} 等经宽松绑定也落在这里）不绑定、忽略。
 *
 * @param expectDatabase 期望库名；null = 取部署命名空间
 */
@ConfigurationProperties("game.mysql")
@Validated
public record GameMysqlProperties(
        @Pattern(regexp = "\\S+", message = "不用应急开关就不要设它（空值不等于没设）；设了必须是不含空白的库名，库名规格上等于部署命名空间 game.namespace")
        String expectDatabase) {
}
