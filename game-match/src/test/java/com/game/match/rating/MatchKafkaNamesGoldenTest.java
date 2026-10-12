package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.match.MatchProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 金样：缺省部署命名空间（{@code game}）下，game-match 评分消费组（代码缺省与 yaml）与 client.id 与引入命名空间之前逐字节相同。 */
class MatchKafkaNamesGoldenTest {

    @BeforeAll
    static void 只在缺省命名空间里跑() {
        assertThat(GameNamespace.get())
                .as("金样只在缺省命名空间里成立；不要给 mvn 传 -Dgame.namespace（surefire 会让它覆盖 POM 钉的 game）")
                .isEqualTo("game");
    }

    @Test
    void 消费组与client_id与金样相同() throws Exception {
        assertThat(new MatchProperties.Rating(null, null, null, null, null).consumerGroup()).isEqualTo("game-match-rating");
        assertThat(new MatchProperties.Rating(null, null, null, null, " ").consumerGroup()).isEqualTo("game-match-rating");
        MatchProperties bound = new Binder(new MapConfigurationPropertySource(Map.of("game.match.rating.enabled", "true")))
                .bind("game.match", MatchProperties.class).get();
        assertThat(bound.rating().consumerGroup()).isEqualTo("game-match-rating");

        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        assertThat(environment.getProperty("game.match.rating.consumer-group")).isEqualTo("game-match-rating");

        assertThat(RatingConfiguration.ratingClientId("i-1")).isEqualTo("game-match-rating-i-1");
        assertThat(RatingConfiguration.adminClientId()).isEqualTo("game-match-admin");
    }
}
