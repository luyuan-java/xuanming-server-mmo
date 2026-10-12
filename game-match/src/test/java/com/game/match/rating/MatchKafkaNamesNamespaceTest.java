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
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 非缺省部署命名空间（{@code nstest}，与 {@code game} 的实例在同一个 JVM 里并存）下 game-match 评分消费的消费组缺省值与 client.id 随命名空间；
 * yaml 不配消费组、JDBC URL 的库名随 {@code game.namespace}。
 */
class MatchKafkaNamesNamespaceTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("game"));
    private static final KafkaNames NSTEST = new KafkaNames(GameNamespace.of("nstest"));

    @Test
    void 消费组缺省与client_id随命名空间_两个命名空间互不影响() {
        MatchProperties.Rating unset = new MatchProperties.Rating(null, null, null, null, null);
        RatingConfiguration.RatingKafkaIds ids = RatingConfiguration.kafkaIds(unset, NSTEST, "i-1");
        assertThat(ids.group()).isEqualTo("nstest-match-rating");
        assertThat(ids.clientId()).isEqualTo("nstest-match-rating-i-1");
        assertThat(ids.adminClientId()).isEqualTo("nstest-match-admin");
        assertThat(RatingConfiguration.kafkaIds(unset, KAFKA_NAMES, "i-1").group()).isEqualTo("game-match-rating");
    }

    @Test
    void yaml在非缺省命名空间下_消费组取代码缺省_库名随命名空间() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("game.namespace", "nstest")));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        MatchProperties fromYaml = Binder.get(environment).bind("game.match", MatchProperties.class).get();
        assertThat(RatingConfiguration.kafkaIds(fromYaml.rating(), NSTEST, "i-1").group()).isEqualTo("nstest-match-rating");
        assertThat(environment.getProperty("spring.datasource.url")).contains("/nstest?").doesNotContain("/game?");
    }

    @Test
    void 显式覆盖的消费组不强制带前缀() {
        MatchProperties explicit = new Binder(new MapConfigurationPropertySource(Map.of("game.match.rating.consumer-group", "g2")))
                .bind("game.match", MatchProperties.class).get();
        assertThat(RatingConfiguration.kafkaIds(explicit.rating(), NSTEST, "i-1").group()).isEqualTo("g2");
    }
}
