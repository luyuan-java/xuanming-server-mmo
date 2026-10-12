package com.game.data.gainblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 全服产出封禁名单读写的键与通知频道取自注入的 {@link RedisKeys}：缺省命名空间下与金样逐字节相同，非缺省命名空间整体换前缀（Redis 是替身；
 * 真 Redis 上的往返在 {@link GainBlockStoreIntegrationTest}）。
 */
class GainBlockStoreKeysTest {

    @SuppressWarnings("unchecked")
    private static void assertKeys(String namespace, String expectedMap, String expectedTopic) {
        RedissonClient redis = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        RTopic topic = mock(RTopic.class);
        when(redis.<String, String>getMap(expectedMap, StringCodec.INSTANCE)).thenReturn(map);
        when(redis.getTopic(expectedTopic, StringCodec.INSTANCE)).thenReturn(topic);
        when(map.readAllEntrySet()).thenReturn(Map.of("7", "{}").entrySet());
        GainBlockStore store = new GainBlockStore(new StaticListableBeanFactory(Map.of("redis", redis))
                .getBeanProvider(RedissonClient.class), new ObjectMapper(), new RedisKeys(GameNamespace.of(namespace)));

        store.block(GainBlockStore.CURRENCY, 7, "ops", 1, "测试");
        assertThat(store.list(GainBlockStore.CURRENCY)).extracting(GainBlockStore.Entry::id).containsExactly(7);

        verify(map).fastPut(eq("7"), anyString());
        verify(topic).publish(GainBlockStore.CURRENCY);
    }

    @Test
    void 缺省命名空间的键与频道与金样相同() {
        assertKeys("game", "game:gain-block:currency", "game:gain-block-changed");
    }

    @Test
    void 非缺省命名空间的键与频道整体换前缀() {
        assertKeys("nstest", "nstest:gain-block:currency", "nstest:gain-block-changed");
    }
}
