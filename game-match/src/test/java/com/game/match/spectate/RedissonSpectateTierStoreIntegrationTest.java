package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.proto.eSpectateTier;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 观战档位与赛事局索引连真 Redis（缺省跳过：{@code -Dgame.it.redis=redis://127.0.0.1:6379}）：几段 Lua 只有在这里才真的跑。
 * 用 DB 13、每个用例一个随机命名空间，只删自己写的键（多人共用一台 Redis）。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class RedissonSpectateTierStoreIntegrationTest {

    private static final long BATTLE = Long.MIN_VALUE + 4301;
    private static final long OTHER = Long.MIN_VALUE + 4302;

    private static RedissonClient redis;

    private final RedisKeys keys = new RedisKeys(GameNamespace.of("tier" + Integer.toUnsignedString(ThreadLocalRandom.current().nextInt(), 36)));
    private final RedissonSpectateTierStore store = new RedissonSpectateTierStore(redis, keys);

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @AfterEach
    void cleanup() {
        redis.getKeys().delete(keys.matchSpectateTier(BATTLE), keys.matchSpectateFeatured());
    }

    private static Deadline d() {
        return Deadline.after(2_000);
    }

    @Test
    void 档位只升不降_没有记录是DIRECT_写入带TTL() {
        assertThat(store.tier(BATTLE, d())).isEqualTo(eSpectateTier.SPECTATE_TIER_DIRECT);

        assertThat(store.raise(BATTLE, eSpectateTier.SPECTATE_TIER_RELAY, d())).isEqualTo(eSpectateTier.SPECTATE_TIER_RELAY);
        assertThat(store.raise(BATTLE, eSpectateTier.SPECTATE_TIER_DIRECT, d())).as("不降").isEqualTo(eSpectateTier.SPECTATE_TIER_RELAY);
        assertThat(store.tier(BATTLE, d())).isEqualTo(eSpectateTier.SPECTATE_TIER_RELAY);
        assertThat(redis.getBucket(keys.matchSpectateTier(BATTLE), StringCodec.INSTANCE).remainTimeToLive()).as("TTL 同落点记录").isPositive();

        assertThat(store.raise(BATTLE, eSpectateTier.SPECTATE_TIER_BROADCAST, d())).isEqualTo(eSpectateTier.SPECTATE_TIER_BROADCAST);
        assertThat(store.raise(BATTLE, eSpectateTier.SPECTATE_TIER_RELAY, d())).isEqualTo(eSpectateTier.SPECTATE_TIER_BROADCAST);
    }

    @Test
    void 赛事局索引_后标记的在前_条数受限_重复标记不重复_取消只摘自己_无符号id原样回来() throws Exception {
        assertThat(store.activeFeatured(10, d())).isEmpty();

        store.setFeatured(BATTLE, true, d());
        Thread.sleep(2); // 分数是毫秒：两次标记落在同一毫秒时次序按成员字符串，这里要的是「后标记的在前」
        store.setFeatured(OTHER, true, d());

        assertThat(store.activeFeatured(10, d())).containsExactly(OTHER, BATTLE);
        assertThat(store.activeFeatured(1, d())).containsExactly(OTHER);

        Thread.sleep(2);
        store.setFeatured(BATTLE, true, d());
        assertThat(store.activeFeatured(10, d())).containsExactly(BATTLE, OTHER);

        store.setFeatured(OTHER, false, d());
        store.setFeatured(OTHER, false, d());
        assertThat(store.activeFeatured(10, d())).as("取消幂等").containsExactly(BATTLE);
    }

    @Test
    void 到期的成员读不到_下一次标记顺手清掉() {
        var index = redis.<String>getScoredSortedSet(keys.matchSpectateFeatured(), StringCodec.INSTANCE);
        index.add(1.0, SpectateRules.member(OTHER));

        assertThat(store.activeFeatured(10, d())).isEmpty();

        store.setFeatured(BATTLE, true, d());

        assertThat(store.activeFeatured(10, d())).containsExactly(BATTLE);
        assertThat(index.size()).isEqualTo(1);
    }
}
