package com.game.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.store.ListingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** {@link TradeConfiguration} 的装配断言（不连库、不起 Spring 上下文）。 */
class TradeConfigurationTest {

    /**
     * 客户端请求按会话保序（mmorpg client-request-ordering.md §6.1）：收藏写入的写入预留只在 listingStore 这一处装配传入（缺省预算 3500 ms →
     * 预留 1000 ms）。改回不带预留的构造，存储层单测与公式测试照样通过，只有这里会失败：只剩 900 ms 的请求会去取连接、发语句。
     */
    @Test
    void listingStore装配带写入预留_请求剩余不足预留时收藏写入不碰数据源() {
        List<String> touched = new ArrayList<>();
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    touched.add(method.getName());
                    throw new UnsupportedOperationException("DataSource." + method.getName());
                });
        TradeProperties props = new TradeProperties(new TradeProperties.Market("zone", null, null, null, null), null, null, null, null);
        assertThat(props.requestBudget().toMillis()).as("缺省预算").isEqualTo(3_500L);
        ListingStore store = new TradeConfiguration().listingStore(dataSource, props, new TradeMetrics(new SimpleMeterRegistry()),
                new TradeConfiguration.SchemaReady(null));

        assertThatThrownBy(() -> store.insertFavorite(1L, 2L, 3L, Deadline.after(900)))
                .isInstanceOf(DependencyException.class).hasMessageContaining("写入预留");
        assertThatThrownBy(() -> store.deleteFavorite(1L, 2L, Deadline.after(900)))
                .isInstanceOf(DependencyException.class).hasMessageContaining("写入预留");
        assertThat(touched).as("不取连接、不发语句").isEmpty();
    }
}
