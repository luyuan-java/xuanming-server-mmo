package com.game.scene.gainblock;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 连真 Redis（{@code -Dxm.it.redis=redis://127.0.0.1:6379}，缺省跳过）：scene 从 xm-data 写的两个名单键读出币种与物品。
 * 键在 DB 13（与其他 Redis 集成测试同一个测试库），名单键是固定名，前后都清掉。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisGainBlockSourceIntegrationTest {

    private RedissonClient redis;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        clear();
    }

    @AfterEach
    void tearDown() {
        clear();
        redis.shutdown();
    }

    private void clear() {
        redis.getKeys().delete(RedisKeys.gainBlocks(RedisKeys.GAIN_BLOCK_CURRENCY),
                RedisKeys.gainBlocks(RedisKeys.GAIN_BLOCK_ITEM));
    }

    @Test
    void 币种与物品两个名单各读各的_非法字段跳过() {
        redis.<String, String>getMap(RedisKeys.gainBlocks(RedisKeys.GAIN_BLOCK_CURRENCY), StringCodec.INSTANCE)
                .fastPut("2", "{}");
        redis.<String, String>getMap(RedisKeys.gainBlocks(RedisKeys.GAIN_BLOCK_ITEM), StringCodec.INSTANCE)
                .fastPut("10", "{}");
        redis.<String, String>getMap(RedisKeys.gainBlocks(RedisKeys.GAIN_BLOCK_ITEM), StringCodec.INSTANCE)
                .fastPut("bad", "{}");

        GlobalGainBlocks blocks = new RedisGainBlockSource(redis).load();

        assertThat(blocks.currencies()).containsExactly(2);
        assertThat(blocks.items()).containsExactly(10);
        assertThat(blocks.blocksItem(2)).isFalse();
    }
}
