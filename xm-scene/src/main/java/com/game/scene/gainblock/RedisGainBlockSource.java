package com.game.scene.gainblock;

import com.game.discovery.RedisKeys;
import java.util.HashSet;
import java.util.Set;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 Redis 读全服产出封禁名单（{@code xm:gain-block:currency} 与 {@code xm:gain-block:item} 的字段）。阻塞调用，
 * 只在 {@link GainBlockSync} 的同步线程（及启动线程）上用。字段不是合法的非负 int（写者是 xm-data 运维接口，会先校验）就跳过并告警。
 */
public final class RedisGainBlockSource implements GainBlockSync.Source {

    public static final String CURRENCY = RedisKeys.GAIN_BLOCK_CURRENCY;
    public static final String ITEM = RedisKeys.GAIN_BLOCK_ITEM;

    private static final Logger log = LoggerFactory.getLogger(RedisGainBlockSource.class);

    private final RedissonClient redis;

    public RedisGainBlockSource(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public GlobalGainBlocks load() {
        return new GlobalGainBlocks(read(CURRENCY), read(ITEM));
    }

    private Set<Integer> read(String category) {
        return parse(redis.<String, String>getMap(RedisKeys.gainBlocks(category), StringCodec.INSTANCE).readAllKeySet());
    }

    static Set<Integer> parse(Set<String> fields) {
        Set<Integer> ids = new HashSet<>();
        for (String field : fields) {
            try {
                int id = Integer.parseInt(field);
                if (id < 0) {
                    throw new NumberFormatException("负数");
                }
                ids.add(id);
            } catch (NumberFormatException e) {
                log.warn("全服产出封禁名单里有非法字段，跳过: '{}'", field);
            }
        }
        return ids;
    }
}
