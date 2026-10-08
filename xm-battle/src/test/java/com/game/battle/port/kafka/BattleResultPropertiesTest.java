package com.game.battle.port.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code xm.battle.result.*}（批次 6.4 的四个键；match-spec §5.4，lead 裁决「问题 8」）：缺省值、非法值，以及 {@code application.yaml}
 * 把两个环境变量接到了对的键上——{@code XM_KAFKA_BOOTSTRAP_SERVERS}（与 xm-scene / xm-data / xm-match 共用）与
 * {@code XM_BATTLE_RESULT_TOPIC_GENERATION}（必须与 xm-match 一致，否则两边各写各读一个 topic）。
 */
class BattleResultPropertiesTest {

    private static BattleResultProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("xm.battle.result", BattleResultProperties.class);
    }

    /**
     * 按 application.yaml 绑定；{@code env} 顶在最前面，扮演进程的环境变量。调用方总是把两个变量都给全——{@link StandardEnvironment}
     * 也会读真的环境变量，开发机上若已导出同名变量（本机切片会导出），不显式盖住就会串进来。
     */
    private static BattleResultProperties bindYaml(Map<String, Object> env) throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        yaml.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bindOrCreate("xm.battle.result", BattleResultProperties.class);
    }

    @Test
    void 缺省值_本机Kafka_代次1_副本1_核对上限10秒() {
        BattleResultProperties props = bind(Map.of());

        assertThat(props.bootstrapServers()).isEqualTo("127.0.0.1:9092");
        assertThat(props.topicGeneration()).isEqualTo(1);
        assertThat(props.replicationFactor()).isEqualTo((short) 1);
        assertThat(props.initTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props).isEqualTo(BattleResultProperties.defaults());
        assertThat(props.topic()).isEqualTo("xm-battle-result-g1");
    }

    @Test
    void 四个键都能配_topic名带代次() {
        BattleResultProperties props = bind(Map.of(
                "xm.battle.result.bootstrap-servers", "kafka-0:9092,kafka-1:9092",
                "xm.battle.result.topic-generation", "12",
                "xm.battle.result.replication-factor", "3",
                "xm.battle.result.init-timeout", "2500ms"));

        assertThat(props.bootstrapServers()).isEqualTo("kafka-0:9092,kafka-1:9092");
        assertThat(props.topicGeneration()).isEqualTo(12);
        assertThat(props.replicationFactor()).isEqualTo((short) 3);
        assertThat(props.initTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(props.topic()).isEqualTo("xm-battle-result-g12");
    }

    @Test
    void 配置文件的缺省与代码缺省一致_四个键都写在配置文件里() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));
        PropertySource<?> source = yaml.get(0);
        assertThat(source.getProperty("xm.battle.result.bootstrap-servers")).isEqualTo("${XM_KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:9092}");
        assertThat(source.getProperty("xm.battle.result.topic-generation")).isEqualTo("${XM_BATTLE_RESULT_TOPIC_GENERATION:1}");
        assertThat(source.getProperty("xm.battle.result.replication-factor")).isEqualTo(1);
        assertThat(source.getProperty("xm.battle.result.init-timeout")).isEqualTo("10s");

        // 这两个变量的值本身是合法的时候，绑定结果就是它们；这里给的正是占位符里的缺省值，所以结果应与代码缺省相同
        BattleResultProperties props = bindYaml(Map.of("XM_KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092", "XM_BATTLE_RESULT_TOPIC_GENERATION", "1"));
        assertThat(props).isEqualTo(BattleResultProperties.defaults());
    }

    @Test
    void 配置文件把两个环境变量接到对的键上() throws Exception {
        BattleResultProperties props = bindYaml(Map.of("XM_KAFKA_BOOTSTRAP_SERVERS", "kafka.internal:19092",
                "XM_BATTLE_RESULT_TOPIC_GENERATION", "4"));

        assertThat(props.bootstrapServers()).isEqualTo("kafka.internal:19092");
        assertThat(props.topicGeneration()).isEqualTo(4);
        assertThat(props.topic()).as("与 xm-match 读同一个变量，得到同一个 topic 名").isEqualTo("xm-battle-result-g4");
        assertThat(props.replicationFactor()).isEqualTo((short) 1);
        assertThat(props.initTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 地址为空_代次或副本数小于1_核对上限不为正_拒绝_消息带键名() {
        assertThatThrownBy(() -> new BattleResultProperties(" ", 1, (short) 1, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.bootstrap-servers");
        assertThatThrownBy(() -> new BattleResultProperties(null, 1, (short) 1, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.bootstrap-servers");
        assertThatThrownBy(() -> new BattleResultProperties("127.0.0.1:9092", 0, (short) 1, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.topic-generation");
        assertThatThrownBy(() -> new BattleResultProperties("127.0.0.1:9092", -1, (short) 1, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.topic-generation");
        assertThatThrownBy(() -> new BattleResultProperties("127.0.0.1:9092", 1, (short) 0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.replication-factor");
        assertThatThrownBy(() -> new BattleResultProperties("127.0.0.1:9092", 1, (short) 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.init-timeout");
        assertThatThrownBy(() -> new BattleResultProperties("127.0.0.1:9092", 1, (short) 1, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.init-timeout");
        assertThatThrownBy(() -> new BattleResultProperties("127.0.0.1:9092", 1, (short) 1, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.battle.result.init-timeout");
    }

    @Test
    void 代次写成非数字_绑定失败() {
        assertThatThrownBy(() -> bind(Map.of("xm.battle.result.topic-generation", "g2"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.result.replication-factor", "70000"))).as("超出 short").isInstanceOf(BindException.class);
    }
}
