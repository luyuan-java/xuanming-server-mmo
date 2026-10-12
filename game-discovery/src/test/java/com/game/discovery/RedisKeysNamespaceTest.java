package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys.MatchQueueId;
import org.junit.jupiter.api.Test;

/**
 * 非缺省部署命名空间 {@code nstest} 的实例下的 Redis 键：每一个生成方法都以 {@code nstest:} 开头、不含 {@code game:}，去掉前缀后与缺省命名空间的金样
 * 形状相同（节点类型、hash tag、字段名不变）。与 {@code game} 的实例在同一个 JVM 里并存。
 */
class RedisKeysNamespaceTest {

    static final String NS = "nstest";
    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of(NS));
    private static final RedisKeys GAME_KEYS = new RedisKeys(GameNamespace.of("game"));

    @Test
    void 每个生成方法只换了前缀_其余与金样相同() {
        assertThat(REDIS_KEYS.prefix()).isEqualTo("nstest:");
        for (RedisKeysSamples.Sample sample : RedisKeysSamples.all()) {
            String key = sample.key().apply(REDIS_KEYS);
            assertThat(key).as(sample.method()).startsWith(NS + ":").doesNotContain("game:");
            assertThat(key.substring(NS.length() + 1)).as("%s 去掉前缀后与缺省形状相同", sample.method())
                    .isEqualTo(sample.golden().substring(RedisKeysSamples.DEFAULT_PREFIX.length()));
            assertThat(sample.key().apply(GAME_KEYS)).as("同一个 JVM 里缺省命名空间的实例不受影响: %s", sample.method())
                    .isEqualTo(sample.golden());
        }
        assertThat(RedisKeysSamples.sampledMethods()).isEqualTo(RedisKeysSamples.publicStringMethods());
    }

    @Test
    void 在线目录前缀与频道随命名空间() {
        assertThat(REDIS_KEYS.presencePrefix()).isEqualTo("nstest:presence:");
        assertThat(REDIS_KEYS.ownerTakeoverTopic()).isEqualTo("nstest:owner-takeover");
        assertThat(REDIS_KEYS.gatePushTopic(1, 2)).isEqualTo("nstest:gate-push:1:2");
        assertThat(REDIS_KEYS.gainBlockChangedTopic()).isEqualTo("nstest:gain-block-changed");
    }

    @Test
    void hash_tag不带命名空间_同一类键仍同槽() {
        assertThat(REDIS_KEYS.matchTicket(5)).contains("{match}");
        assertThat(REDIS_KEYS.teamRecord(1)).contains("{team}");
        assertThat(REDIS_KEYS.guildRankAll()).contains("{rank}");
        assertThat(REDIS_KEYS.worldChannels(1)).contains("{z:1}");
        assertThat(REDIS_KEYS.battleLock(42)).contains("{42}");
    }

    @Test
    void 队列键往返_别的命名空间的队列键解析为空() {
        assertThat(REDIS_KEYS.matchQueue(1, 2)).isEqualTo("nstest:{match}:queue:1:2");
        assertThat(REDIS_KEYS.parseMatchQueue(REDIS_KEYS.matchQueue(1, -1))).contains(new MatchQueueId(1, -1));
        assertThat(REDIS_KEYS.parseMatchQueue("game:{match}:queue:1:2")).as("缺省命名空间的队列键当坏成员").isEmpty();
        assertThat(GAME_KEYS.parseMatchQueue("nstest:{match}:queue:1:2")).as("反过来也一样").isEmpty();
    }
}
