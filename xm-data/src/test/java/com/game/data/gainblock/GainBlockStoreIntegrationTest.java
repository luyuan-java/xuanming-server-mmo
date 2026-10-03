package com.game.data.gainblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 连真 Redis（{@code -Dxm.it.redis=redis://127.0.0.1:6379}，缺省跳过）。键在 DB 13（与其他 Redis 集成测试同一个测试库；
 * pub/sub 频道不分库），名单键是固定名，前后都清掉。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class GainBlockStoreIntegrationTest {

    private RedissonClient redis;
    private GainBlockStore store;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        redis.getKeys().delete(RedisKeys.gainBlocks(GainBlockStore.CURRENCY));
        store = new GainBlockStore(new StaticListableBeanFactory(Map.of("redis", redis))
                .getBeanProvider(RedissonClient.class), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        redis.getKeys().delete(RedisKeys.gainBlocks(GainBlockStore.CURRENCY));
        redis.shutdown();
    }

    @Test
    void 封禁写进Hash并通知_列表按id升序带元数据_解封幂等() {
        List<String> notices = new CopyOnWriteArrayList<>();
        RTopic topic = redis.getTopic(RedisKeys.gainBlockChangedTopic(), StringCodec.INSTANCE);
        int listener = topic.addListener(String.class, (channel, message) -> notices.add(message));
        try {
            store.block(GainBlockStore.CURRENCY, 2, "运维甲", 100, "刷钻漏洞");
            store.block(GainBlockStore.CURRENCY, 1, "ops", 200, "dupe");
            store.block(GainBlockStore.CURRENCY, 2, "ops", 300, "再确认");

            assertThat(store.list(GainBlockStore.CURRENCY)).containsExactly(
                    new GainBlockStore.Entry(1, "ops", 200, "dupe"),
                    new GainBlockStore.Entry(2, "ops", 300, "再确认"));
            assertThat(redis.<String, String>getMap(RedisKeys.gainBlocks(GainBlockStore.CURRENCY), StringCodec.INSTANCE)
                    .readAllKeySet()).containsExactlyInAnyOrder("1", "2");

            assertThat(store.unblock(GainBlockStore.CURRENCY, 2)).isTrue();
            assertThat(store.unblock(GainBlockStore.CURRENCY, 2)).isFalse();
            assertThat(store.list(GainBlockStore.CURRENCY)).extracting(GainBlockStore.Entry::id).containsExactly(1);

            // pub/sub 频道不分库，同一 Redis 上别的发布者也可能发：至少收到这 5 次
            await().atMost(Duration.ofSeconds(5)).until(() -> notices.size() >= 5);
            assertThat(notices).containsOnly(GainBlockStore.CURRENCY);
        } finally {
            topic.removeListener(listener);
        }
    }

    @Test
    void 元数据坏了照样列出() {
        redis.<String, String>getMap(RedisKeys.gainBlocks(GainBlockStore.CURRENCY), StringCodec.INSTANCE)
                .fastPut("5", "不是 JSON");
        assertThat(store.list(GainBlockStore.CURRENCY)).containsExactly(new GainBlockStore.Entry(5, "", 0, ""));
    }
}
