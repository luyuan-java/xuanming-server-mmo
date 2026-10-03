package com.game.scene.currency;

import com.game.discovery.RedisKeys;
import java.util.HashSet;
import java.util.Set;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 Redis 读全服产出封禁名单（{@code xm:gain-block:currency} 的字段）。阻塞调用，只在 {@link GainBlockSync} 的同步线程上用。
 * 字段不是合法的非负 int（写者是 xm-data 运维接口，会先校验）就跳过并告警。
 */
public final class RedisGainBlockSource implements GainBlockSync.Source {

    static final String CURRENCY = "currency";

    private static final Logger log = LoggerFactory.getLogger(RedisGainBlockSource.class);

    private final RedissonClient redis;

    public RedisGainBlockSource(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public Set<Integer> loadCurrencies() {
        Set<String> fields = redis.<String, String>getMap(RedisKeys.gainBlocks(CURRENCY), StringCodec.INSTANCE)
                .readAllKeySet();
        return parse(fields);
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
