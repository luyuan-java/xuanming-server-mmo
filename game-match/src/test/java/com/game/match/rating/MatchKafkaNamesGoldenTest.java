package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.match.MatchProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 金样：缺省部署命名空间（{@code game}）下，game-match 评分消费的消费组（没配时的缺省、空白、绑定、仓库 yaml）与两个 client.id
 * 与引入命名空间之前逐字节相同。名字由注入的 {@link KafkaNames} 经 {@link RatingConfiguration#kafkaIds} 算出。
 */
class MatchKafkaNamesGoldenTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("game"));

    @Test
    void 消费组与client_id与金样相同() throws Exception {
        RatingConfiguration.RatingKafkaIds ids = RatingConfiguration.kafkaIds(new MatchProperties.Rating(null, null, null, null, null), KAFKA_NAMES, "i-1");
        assertThat(ids.group()).isEqualTo("game-match-rating");
        assertThat(ids.clientId()).isEqualTo("game-match-rating-i-1");
        assertThat(ids.adminClientId()).isEqualTo("game-match-admin");

        assertThat(group(new MatchProperties.Rating(null, null, null, null, " "))).isEqualTo("game-match-rating");
        MatchProperties bound = new Binder(new MapConfigurationPropertySource(Map.of("game.match.rating.enabled", "true")))
                .bind("game.match", MatchProperties.class).get();
        assertThat(group(bound.rating())).isEqualTo("game-match-rating");

        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        assertThat(environment.getProperty("game.match.rating.consumer-group")).as("yaml 不配消费组：缺省只有代码一个出处").isNull();
        MatchProperties fromYaml = Binder.get(environment).bind("game.match", MatchProperties.class).get();
        assertThat(group(fromYaml.rating())).isEqualTo("game-match-rating");
    }

    @Test
    void 显式配置的消费组原样使用_不强制带前缀() {
        MatchProperties explicit = new Binder(new MapConfigurationPropertySource(Map.of("game.match.rating.consumer-group", " g2 ")))
                .bind("game.match", MatchProperties.class).get();
        assertThat(group(explicit.rating())).isEqualTo("g2");
    }

    private static String group(MatchProperties.Rating rating) {
        return RatingConfiguration.kafkaIds(rating, KAFKA_NAMES, "i-1").group();
    }
}
