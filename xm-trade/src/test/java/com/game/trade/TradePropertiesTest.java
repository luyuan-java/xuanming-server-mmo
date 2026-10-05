package com.game.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.trade.MarketScope;
import com.game.trade.service.MarketSettings;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code xm.trade.*} 的启动校验（照基线 config_test.go:57-63、:223-229；trade-spec §9.2 TradePropertiesTest）：scope 必填且只认 zone / global、
 * 页长与上限 &gt; 0 且缺省页长 ≤ 上限、预算区间、查询超时不超过预算；并钉住 application.yaml 的取值（zone、20 / 20 / 100 / 100、3500 ms）。
 */
class TradePropertiesTest {

    private static TradeProperties.Market market(String scope) {
        return new TradeProperties.Market(scope, null, null, null, null);
    }

    @Test
    void 缺省值_同基线() {
        TradeProperties props = new TradeProperties(market("zone"), null, null, null, null);

        assertThat(props.requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThat(props.queryTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.queryTimeoutCapSeconds()).isEqualTo(3);
        assertThat(props.workerThreads()).isEqualTo(16);
        assertThat(props.workerQueueCapacity()).isEqualTo(1024);
        MarketSettings settings = props.market().settings();
        assertThat(settings).isEqualTo(new MarketSettings(MarketScope.MARKET_SCOPE_ZONE, 20, 20, 100, 100));
        assertThat(new TradeProperties(market("global"), null, null, null, null).market().settings().scope())
                .isEqualTo(MarketScope.MARKET_SCOPE_GLOBAL);
    }

    @Test
    void scope必填且只认zone与global字面量() {
        assertThatThrownBy(() -> new TradeProperties(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.trade.market.scope");
        for (String bad : new String[] {null, "", "Zone", "GLOBAL", " zone", "all"}) {
            assertThatThrownBy(() -> market(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xm.trade.market.scope");
        }
    }

    @Test
    void 市场参数必须为正且缺省页长不大于上限() {
        assertThatThrownBy(() -> new TradeProperties.Market("zone", 0, null, null, null)).hasMessageContaining("default-page-size");
        assertThatThrownBy(() -> new TradeProperties.Market("zone", null, -1, null, null)).hasMessageContaining("max-page-size");
        assertThatThrownBy(() -> new TradeProperties.Market("zone", null, null, 0, null)).hasMessageContaining("max-page");
        assertThatThrownBy(() -> new TradeProperties.Market("zone", null, null, null, 0)).hasMessageContaining("max-favorites-per-player");
        assertThatThrownBy(() -> new TradeProperties.Market("zone", 21, 20, null, null)).hasMessageContaining("不能大于");
        assertThat(new TradeProperties.Market("zone", 4, 4, 1, 1).settings())
                .isEqualTo(new MarketSettings(MarketScope.MARKET_SCOPE_ZONE, 4, 4, 1, 1));
    }

    @Test
    void 预算区间与查询超时() {
        assertThatThrownBy(() -> new TradeProperties(market("zone"), Duration.ofMillis(499), null, null, null))
                .hasMessageContaining("request-budget");
        assertThatThrownBy(() -> new TradeProperties(market("zone"), Duration.ofMillis(3501), null, null, null))
                .hasMessageContaining("request-budget");
        assertThatThrownBy(() -> new TradeProperties(market("zone"), Duration.ZERO, null, null, null))
                .hasMessageContaining("request-budget");
        assertThat(new TradeProperties(market("zone"), Duration.ofMillis(500), null, null, null).queryTimeout())
                .as("缺省查询超时不超过预算向上取整").isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(() -> new TradeProperties(market("zone"), Duration.ofMillis(1500), Duration.ofSeconds(3), null, null))
                .hasMessageContaining("query-timeout");
        assertThatThrownBy(() -> new TradeProperties(market("zone"), null, null, 0, null)).hasMessageContaining("worker-threads");
        assertThatThrownBy(() -> new TradeProperties(market("zone"), null, null, null, -1)).hasMessageContaining("worker-queue-capacity");
    }

    /** application.yaml 经 Spring 的绑定器（含占位符）得到的配置：钉住键名与基线取值（config_test.go:57-63）。 */
    @Test
    void application_yaml绑定出基线取值() throws IOException {
        TradeProperties props = bindYaml(new MockEnvironment());

        assertThat(props.market().scope()).isEqualTo("zone");
        assertThat(props.market().settings()).isEqualTo(new MarketSettings(MarketScope.MARKET_SCOPE_ZONE, 20, 20, 100, 100));
        assertThat(props.requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThat(props.queryTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.workerThreads()).isEqualTo(16);
        assertThat(props.workerQueueCapacity()).isEqualTo(1024);
    }

    @Test
    void application_yaml的scope可由XM_TRADE_MARKET_SCOPE覆盖_写错拒启() throws IOException {
        MockEnvironment global = new MockEnvironment().withProperty("XM_TRADE_MARKET_SCOPE", "global");
        assertThat(bindYaml(global).market().settings().scope()).isEqualTo(MarketScope.MARKET_SCOPE_GLOBAL);

        MockEnvironment typo = new MockEnvironment().withProperty("XM_TRADE_MARKET_SCOPE", "Global");
        assertThatThrownBy(() -> bindYaml(typo)).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("xm.trade.market.scope");
    }

    private static TradeProperties bindYaml(MockEnvironment overrides) throws IOException {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        // 不让本机的环境变量 / 系统属性（例如设了 XM_TRADE_MARKET_SCOPE）影响断言
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        overrides.getPropertySources().forEach(sources::addFirst);
        yaml.forEach(sources::addLast);
        ConfigurationPropertySources.attach(env);
        return Binder.get(env).bind("xm.trade", TradeProperties.class).get();
    }
}
