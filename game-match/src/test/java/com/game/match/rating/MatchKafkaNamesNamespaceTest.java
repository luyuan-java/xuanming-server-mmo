package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.match.MatchProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 非缺省部署命名空间（namespace 执行，{@code -Dgame.namespace=nstest}）下 game-match 的评分消费组（代码缺省与 yaml 解析出的值相同）与 client.id；
 * yaml 里的 JDBC URL 库名随命名空间。
 */
@Tag("namespace")
class MatchKafkaNamesNamespaceTest {

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    @Test
    void 消费组_代码缺省与yaml相同且随命名空间_client_id随命名空间() throws Exception {
        String codeDefault = new MatchProperties.Rating(null, null, null, null, null).consumerGroup();
        assertThat(codeDefault).isEqualTo("nstest-match-rating");

        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        MatchProperties fromYaml = Binder.get(environment).bind("game.match", MatchProperties.class).get();
        assertThat(fromYaml.rating().consumerGroup()).isEqualTo(codeDefault);
        assertThat(environment.getProperty("spring.datasource.url")).contains("/nstest?").doesNotContain("/game?");

        MatchProperties explicit = new Binder(new MapConfigurationPropertySource(Map.of("game.match.rating.consumer-group", "g2")))
                .bind("game.match", MatchProperties.class).get();
        assertThat(explicit.rating().consumerGroup()).as("显式覆盖不强制带前缀").isEqualTo("g2");

        assertThat(RatingConfiguration.ratingClientId("i-1")).isEqualTo("nstest-match-rating-i-1");
        assertThat(RatingConfiguration.adminClientId()).isEqualTo("nstest-match-admin");
    }
}
