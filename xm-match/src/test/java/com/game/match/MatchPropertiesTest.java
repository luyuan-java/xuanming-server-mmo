package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.gather.FingerprintMode;
import com.game.match.support.MatchModes;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code xm.match.*} 的缺省值、校验与绑定（match-spec §10.1）：缺省值与基线 yaml 相同；不合法的值在启动时拒绝；仓库里的 application.yaml 绑得进来
 * 且与代码缺省值一致（两处各写一份，这里钉住不漂移）。
 */
class MatchPropertiesTest {

    private static MatchProperties defaults() {
        return new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static MatchProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("xm.match", MatchProperties.class).get();
    }

    @Test
    void 全部缺省值与规格表一致() {
        MatchProperties p = defaults();

        assertThat(p.worker().threads()).isEqualTo(16);
        assertThat(p.worker().queue()).isEqualTo(1024);
        assertThat(p.requestBudget()).isEqualTo(Duration.ofMillis(4500));
        assertThat(p.matcher().interval()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.matcher().lockTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.ticketTtl()).isEqualTo(Duration.ofHours(6));
        assertThat(p.readyTicketTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.challengeTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.rating().enabled()).isTrue();
        assertThat(p.rating().tolerance()).isEqualTo(new MatchProperties.Tolerance(100, 5, 100, 1000, 90));
        assertThat(p.rating().drawRoundCap()).isEqualTo(30);
        assertThat(p.rating().drawRoundCapByConfigId()).isEmpty();
        assertThat(p.rating().consumerGroup()).isEqualTo("xm-match-rating");
        assertThat(p.pveTeamSizeByConfigId()).containsExactly(Map.entry(1, 5));
        assertThat(p.tableFingerprintMode()).isEqualTo(FingerprintMode.WARN);
        assertThat(p.gatherMaxInflight()).isEqualTo(256);
        assertThat(p.requeueBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.kafka()).isEqualTo(new MatchProperties.Kafka("127.0.0.1:9092", 1, (short) 1, Duration.ofSeconds(10)));
        assertThat(p.spectate()).as("观战：清扫 10 s 一轮、163 在途上限 128（spectate-spec §5.2）")
                .isEqualTo(new MatchProperties.Spectate(Duration.ofSeconds(10), 128));
    }

    @Test
    void 仓库里的application_yaml绑得进来_且与代码缺省值逐项相同() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }

        MatchProperties fromYaml = Binder.get(environment).bind("xm.match", MatchProperties.class).get();

        // 环境变量可能改了 Kafka 的两个占位符，其余必须与代码缺省值相同
        MatchProperties d = defaults();
        assertThat(fromYaml.worker()).isEqualTo(d.worker());
        assertThat(fromYaml.requestBudget()).isEqualTo(d.requestBudget());
        assertThat(fromYaml.matcher()).isEqualTo(d.matcher());
        assertThat(fromYaml.ticketTtl()).isEqualTo(d.ticketTtl());
        assertThat(fromYaml.readyTicketTtl()).isEqualTo(d.readyTicketTtl());
        assertThat(fromYaml.challengeTtl()).isEqualTo(d.challengeTtl());
        assertThat(fromYaml.rating()).isEqualTo(d.rating());
        assertThat(fromYaml.pveTeamSizeByConfigId()).isEqualTo(d.pveTeamSizeByConfigId());
        assertThat(fromYaml.tableFingerprintMode()).isEqualTo(d.tableFingerprintMode());
        assertThat(fromYaml.gatherMaxInflight()).isEqualTo(d.gatherMaxInflight());
        assertThat(fromYaml.requeueBackoff()).isEqualTo(d.requeueBackoff());
        assertThat(fromYaml.kafka().replicationFactor()).isEqualTo(d.kafka().replicationFactor());
        assertThat(fromYaml.kafka().initTimeout()).isEqualTo(d.kafka().initTimeout());
        assertThat(fromYaml.spectate()).isEqualTo(d.spectate());
        assertThat(environment.getProperty("xm.match.spectate.sweep-interval")).as("yaml 里显式写着这两个键，不是靠代码缺省").isEqualTo("10s");
        assertThat(environment.getProperty("xm.match.spectate.max-inflight")).isEqualTo("128");
        assertThat(environment.getProperty("dubbo.protocol.port")).isEqualTo(System.getenv().getOrDefault("XM_MATCH_RPC_PORT", "20888"));
        assertThat(environment.getProperty("server.port")).isEqualTo(System.getenv().getOrDefault("SERVER_PORT", "18113"));
    }

    @Test
    void 短横线键名与枚举都按Spring的宽松规则绑定() {
        Map<String, String> properties = new HashMap<>();
        properties.put("xm.match.worker.threads", "4");
        properties.put("xm.match.worker.queue", "32");
        properties.put("xm.match.request-budget", "3s");
        properties.put("xm.match.matcher.interval", "200ms");
        properties.put("xm.match.matcher.lock-ttl", "5s");
        properties.put("xm.match.ticket-ttl", "1h");
        properties.put("xm.match.ready-ticket-ttl", "30s");
        properties.put("xm.match.challenge-ttl", "45s");
        properties.put("xm.match.rating.enabled", "false");
        properties.put("xm.match.rating.tolerance.base", "50");
        properties.put("xm.match.rating.tolerance.step-seconds", "10");
        properties.put("xm.match.rating.tolerance.max-wait-seconds", "120");
        properties.put("xm.match.rating.draw-round-cap", "40");
        properties.put("xm.match.rating.draw-round-cap-by-config-id.1", "300");
        properties.put("xm.match.rating.consumer-group", "g2");
        properties.put("xm.match.pve-team-size-by-config-id.1", "3");
        properties.put("xm.match.pve-team-size-by-config-id.2", "8");
        properties.put("xm.match.table-fingerprint-mode", "enforce");
        properties.put("xm.match.gather-max-inflight", "8");
        properties.put("xm.match.requeue-backoff", "0s");
        properties.put("xm.match.kafka.bootstrap-servers", "kafka:19092");
        properties.put("xm.match.kafka.topic-generation", "3");
        properties.put("xm.match.kafka.replication-factor", "2");
        properties.put("xm.match.kafka.init-timeout", "4s");
        properties.put("xm.match.spectate.sweep-interval", "2500ms");
        properties.put("xm.match.spectate.max-inflight", "16");

        MatchProperties p = bind(properties);

        assertThat(p.worker()).isEqualTo(new MatchProperties.Worker(4, 32));
        assertThat(p.requestBudget()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.matcher()).isEqualTo(new MatchProperties.Matcher(Duration.ofMillis(200), Duration.ofSeconds(5)));
        assertThat(p.ticketTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(p.readyTicketTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.challengeTtl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(p.rating().enabled()).isFalse();
        assertThat(p.rating().tolerance()).as("没给的两项取缺省").isEqualTo(new MatchProperties.Tolerance(50, 10, 100, 1000, 120));
        assertThat(p.rating().drawRoundCap()).isEqualTo(40);
        assertThat(p.rating().drawRoundCapByConfigId()).containsExactly(Map.entry(1, 300));
        assertThat(p.rating().consumerGroup()).isEqualTo("g2");
        assertThat(p.pveTeamSizeByConfigId()).containsExactly(Map.entry(1, 3), Map.entry(2, 8));
        assertThat(p.tableFingerprintMode()).isEqualTo(FingerprintMode.ENFORCE);
        assertThat(p.gatherMaxInflight()).isEqualTo(8);
        assertThat(p.requeueBackoff()).as("0 = 关闭退避").isEqualTo(Duration.ZERO);
        assertThat(p.kafka()).isEqualTo(new MatchProperties.Kafka("kafka:19092", 3, (short) 2, Duration.ofSeconds(4)));
        assertThat(p.spectate()).isEqualTo(new MatchProperties.Spectate(Duration.ofMillis(2500), 16));
    }

    @Test
    void 指纹模式写错_绑定失败即拒启() {
        assertThatThrownBy(() -> bind(Map.of("xm.match.table-fingerprint-mode", "reject"))).isInstanceOf(BindException.class);
        assertThat(bind(Map.of("xm.match.table-fingerprint-mode", "OFF")).tableFingerprintMode()).isEqualTo(FingerprintMode.OFF);
    }

    @Test
    void 构造器里的校验经绑定同样生效() {
        assertThatThrownBy(() -> bind(Map.of("xm.match.request-budget", "6s"))).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("request-budget");
    }

    @Test
    void 请求预算必须在500ms到4500ms之间() {
        assertThat(budget(Duration.ofMillis(500)).requestBudget()).isEqualTo(Duration.ofMillis(500));
        assertThat(budget(Duration.ofMillis(4500)).requestBudget()).isEqualTo(Duration.ofMillis(4500));
        assertThatThrownBy(() -> budget(Duration.ofMillis(499))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ofMillis(4501))).as("gate 调 match 的超时是 5 s").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MatchProperties budget(Duration budget) {
        return new MatchProperties(null, budget, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void 工作池与在途上限必须为正() {
        assertThatThrownBy(() -> new MatchProperties.Worker(0, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("worker.threads");
        assertThatThrownBy(() -> new MatchProperties.Worker(null, -1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("worker.queue");
        assertThatThrownBy(() -> new MatchProperties(null, null, null, null, null, null, null, null, null, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gather-max-inflight");
    }

    @Test
    void 凑单间隔为正_锁TTL不短于间隔且不短于1秒() {
        assertThatThrownBy(() -> new MatchProperties.Matcher(Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchProperties.Matcher(null, Duration.ofMillis(900))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-ttl");
        assertThatThrownBy(() -> new MatchProperties.Matcher(Duration.ofSeconds(3), Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MatchProperties.Matcher(Duration.ofSeconds(1), Duration.ofSeconds(1)).lockTtl()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void 排队票据的TTL必须比最长的matched_TTL长() {
        assertThatThrownBy(() -> new MatchProperties(null, null, null, Duration.ofSeconds(96), null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ticket-ttl");
        assertThat(new MatchProperties(null, null, null, Duration.ofSeconds(97), null, null, null, null, null, null, null, null, null).ticketTtl())
                .isEqualTo(Duration.ofSeconds(97));
        assertThatThrownBy(() -> new MatchProperties(null, null, null, null, Duration.ZERO, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ready-ticket-ttl");
        assertThatThrownBy(() -> new MatchProperties(null, null, null, null, null, Duration.ofNanos(1), null, null, null, null, null, null, null))
                .as("不足 1 ms 的 TTL 下发给 Redis 会变成 0").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("challenge-ttl");
    }

    @Test
    void 退避可以为0_不能为负_不能超过60秒() {
        assertThat(backoff(Duration.ZERO).requeueBackoff()).isEqualTo(Duration.ZERO);
        assertThat(backoff(Duration.ofSeconds(60)).requeueBackoff()).isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> backoff(Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requeue-backoff");
        assertThatThrownBy(() -> backoff(Duration.ofSeconds(61))).isInstanceOf(IllegalArgumentException.class);
    }

    private static MatchProperties backoff(Duration backoff) {
        return new MatchProperties(null, null, null, null, null, null, null, null, null, null, backoff, null, null);
    }

    @Test
    void PVE组队人数_按5收口_没配置为0_值必须大于等于1() {
        MatchProperties p = new MatchProperties(null, null, null, null, null, null, null, Map.of(1, 5, 2, 3, 3, 10), null, null, null, null, null);

        assertThat(p.pveTeamSizeFor(1)).isEqualTo(5);
        assertThat(p.pveTeamSizeFor(2)).isEqualTo(3);
        assertThat(p.pveTeamSizeFor(3)).as("配置值 10 按每队上限 5 收口").isEqualTo(5);
        assertThat(p.pveTeamSizeFor(4)).as("没配置 = 该副本未开放组队").isZero();
        assertThat(p.pveTeamSizeFor(0)).isZero();
        assertThat(p.pveTeamSizeFor(-1)).as("≥ 2^31 的副本号不可能配置").isZero();
        assertThat(defaults().pveTeamSizeFor(1)).isEqualTo(5);
        assertThat(defaults().pveTeamSizeFor(2)).as("缺省只开放副本 1：表里 2、3 也有人数，但不能查表").isZero();
        assertThatThrownBy(() -> sizes(Map.of(1, 0))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须 ≥ 1");
        assertThatThrownBy(() -> sizes(Map.of(0, 5))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正的副本 id");
        assertThatThrownBy(() -> sizes(Map.of(-3, 5))).isInstanceOf(IllegalArgumentException.class);
    }

    private static MatchProperties sizes(Map<Integer, Integer> sizes) {
        return new MatchProperties(null, null, null, null, null, null, null, sizes, null, null, null, null, null);
    }

    @Test
    void 人数配置不可变_外面改原表不影响它() {
        Map<Integer, Integer> source = new HashMap<>(Map.of(1, 5));
        MatchProperties p = sizes(source);
        source.put(2, 5);

        assertThat(p.pveTeamSizeFor(2)).isZero();
        assertThatThrownBy(() -> p.pveTeamSizeByConfigId().put(9, 9)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 凑满人数_排队与凑单同一个口径() {
        MatchProperties p = defaults();

        assertThat(p.requiredPlayers(MatchModes.PVE_SOLO, 1)).isEqualTo(1);
        assertThat(p.requiredPlayers(MatchModes.ONE_V_ONE, 0)).isEqualTo(2);
        assertThat(p.requiredPlayers(MatchModes.ONE_V_ONE, 12345)).as("PVP 不看副本号").isEqualTo(2);
        assertThat(p.requiredPlayers(MatchModes.FIVE_V_FIVE, 0)).isEqualTo(10);
        assertThat(p.requiredPlayers(MatchModes.PVE_TEAM, 1)).isEqualTo(5);
        assertThat(p.requiredPlayers(MatchModes.PVE_TEAM, 2)).as("没配置的副本").isZero();
        assertThat(p.requiredPlayers(MatchModes.THREE_V_THREE, 0)).isZero();
        assertThat(p.requiredPlayers(MatchModes.PVP_CHALLENGE, 0)).isZero();
        assertThat(p.requiredPlayers(MatchModes.UNSPECIFIED, 0)).isZero();
        assertThat(p.requiredPlayers(99, 0)).isZero();
    }

    @Test
    void 容差曲线_0或漏配按缺省_负数拒绝() {
        assertThat(new MatchProperties.Tolerance(0, 0, 0, 0, 0)).isEqualTo(new MatchProperties.Tolerance(100, 5, 100, 1000, 90));
        assertThat(new MatchProperties.Tolerance(50, null, 25, 400, 30)).isEqualTo(new MatchProperties.Tolerance(50, 5, 25, 400, 30));
        assertThatThrownBy(() -> new MatchProperties.Tolerance(-1, null, null, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating.tolerance.base");
        assertThatThrownBy(() -> new MatchProperties.Tolerance(null, null, null, null, -90)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-wait-seconds");
    }

    @Test
    void 回合打满阈值_缺省30_0是关闭_覆盖值为0视为没配() {
        MatchProperties.Rating defaults = new MatchProperties.Rating(null, null, null, null, null);
        MatchProperties.Rating overridden = new MatchProperties.Rating(null, null, 30, Map.of(1, 300, 2, 0), null);
        MatchProperties.Rating off = new MatchProperties.Rating(null, null, 0, Map.of(3, 50), null);

        assertThat(defaults.drawRoundCapFor(0)).isEqualTo(30);
        assertThat(defaults.drawRoundCapFor(1)).as("缺省不覆盖：对所有副本都是 30（照搬基线）").isEqualTo(30);
        assertThat(overridden.drawRoundCapFor(1)).isEqualTo(300);
        assertThat(overridden.drawRoundCapFor(2)).as("覆盖值 0 = 没配，回到全局值").isEqualTo(30);
        assertThat(overridden.drawRoundCapFor(7)).isEqualTo(30);
        assertThat(off.drawRoundCapFor(0)).as("全局 0 = 不做这条判定").isZero();
        assertThat(off.drawRoundCapFor(3)).isEqualTo(50);
        assertThatThrownBy(() -> new MatchProperties.Rating(null, null, -1, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchProperties.Rating(null, null, null, Map.of(1, -5), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Kafka连接_空白按缺省_代次与副本数至少为1() {
        assertThat(new MatchProperties.Kafka("  ", null, null, null).bootstrapServers()).isEqualTo("127.0.0.1:9092");
        assertThat(new MatchProperties.Kafka(" kafka:9092 ", 2, (short) 3, Duration.ofSeconds(1)))
                .isEqualTo(new MatchProperties.Kafka("kafka:9092", 2, (short) 3, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> new MatchProperties.Kafka(null, 0, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic-generation");
        assertThatThrownBy(() -> new MatchProperties.Kafka(null, null, (short) 0, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replication-factor");
        assertThatThrownBy(() -> new MatchProperties.Kafka(null, null, null, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("init-timeout");
    }

    @Test
    void 观战_清扫间隔必须为正且不小于1毫秒_在途上限至少为1_不合法即拒启() {
        assertThat(new MatchProperties.Spectate(null, null)).isEqualTo(new MatchProperties.Spectate(Duration.ofSeconds(10), 128));
        assertThat(new MatchProperties.Spectate(Duration.ofMillis(1), 1)).as("下界都合法")
                .satisfies(s -> assertThat(s.sweepInterval()).isEqualTo(Duration.ofMillis(1)))
                .satisfies(s -> assertThat(s.maxInflight()).isEqualTo(1));

        assertThatThrownBy(() -> new MatchProperties.Spectate(Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xm.match.spectate.sweep-interval");
        assertThatThrownBy(() -> new MatchProperties.Spectate(Duration.ofSeconds(-10), null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spectate.sweep-interval");
        assertThatThrownBy(() -> new MatchProperties.Spectate(Duration.ofNanos(999_999), null)).as("不足 1 ms：定时器的间隔会变成 0")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spectate.sweep-interval");
        assertThatThrownBy(() -> new MatchProperties.Spectate(null, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xm.match.spectate.max-inflight");
        assertThatThrownBy(() -> new MatchProperties.Spectate(null, -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spectate.max-inflight");
    }

    @Test
    void 观战的校验经绑定同样生效_只配一项时另一项取缺省() {
        assertThatThrownBy(() -> bind(Map.of("xm.match.spectate.sweep-interval", "0s"))).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("spectate.sweep-interval");
        assertThatThrownBy(() -> bind(Map.of("xm.match.spectate.max-inflight", "0"))).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("spectate.max-inflight");
        assertThat(bind(Map.of("xm.match.spectate.max-inflight", "4")).spectate())
                .isEqualTo(new MatchProperties.Spectate(Duration.ofSeconds(10), 4));
        assertThat(bind(Map.of("xm.match.spectate.sweep-interval", "30s")).spectate())
                .isEqualTo(new MatchProperties.Spectate(Duration.ofSeconds(30), 128));
        assertThat(bind(Map.of("xm.match.gather-max-inflight", "8")).spectate()).as("整段都没配：缺省")
                .isEqualTo(new MatchProperties.Spectate(Duration.ofSeconds(10), 128));
    }

    @Test
    void 消费组空白按缺省() {
        assertThat(new MatchProperties.Rating(null, null, null, null, "  ").consumerGroup()).isEqualTo("xm-match-rating");
        assertThat(new MatchProperties.Rating(null, null, null, null, " g ").consumerGroup()).isEqualTo("g");
    }
}
