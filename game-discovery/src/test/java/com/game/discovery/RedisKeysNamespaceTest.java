package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys.MatchQueueId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 非缺省部署命名空间（surefire 的 namespace 执行，{@code -Dgame.namespace=nstest}）下的 Redis 键：每一个生成方法都以 {@code nstest:} 开头、
 * 不含 {@code game:}，去掉前缀后与缺省命名空间的金样形状相同（节点类型、hash tag、字段名不变）。
 */
@Tag("namespace")
class RedisKeysNamespaceTest {

    static final String NS = "nstest";

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo(NS);
    }

    @Test
    void 每个生成方法只换了前缀_其余与金样相同() {
        assertThat(RedisKeys.PREFIX).isEqualTo("nstest:");
        for (RedisKeysSamples.Sample sample : RedisKeysSamples.all()) {
            String key = sample.key().get();
            assertThat(key).as(sample.method()).startsWith(NS + ":").doesNotContain("game:");
            assertThat(key.substring(NS.length() + 1)).as("%s 去掉前缀后与缺省形状相同", sample.method())
                    .isEqualTo(sample.golden().substring(RedisKeysSamples.DEFAULT_PREFIX.length()));
        }
        assertThat(RedisKeysSamples.sampledMethods()).isEqualTo(RedisKeysSamples.publicStringMethods());
    }

    @Test
    void 在线目录前缀与频道随命名空间() {
        assertThat(RedisKeys.presencePrefix()).isEqualTo("nstest:presence:");
        assertThat(RedisKeys.ownerTakeoverTopic()).isEqualTo("nstest:owner-takeover");
        assertThat(RedisKeys.gatePushTopic(1, 2)).isEqualTo("nstest:gate-push:1:2");
        assertThat(RedisKeys.gainBlockChangedTopic()).isEqualTo("nstest:gain-block-changed");
    }

    @Test
    void hash_tag不带命名空间_同一类键仍同槽() {
        assertThat(RedisKeys.matchTicket(5)).contains("{match}");
        assertThat(RedisKeys.teamRecord(1)).contains("{team}");
        assertThat(RedisKeys.guildRankAll()).contains("{rank}");
        assertThat(RedisKeys.worldChannels(1)).contains("{z:1}");
        assertThat(RedisKeys.battleLock(42)).contains("{42}");
    }

    @Test
    void 队列键往返_别的命名空间的队列键解析为空() {
        assertThat(RedisKeys.matchQueue(1, 2)).isEqualTo("nstest:{match}:queue:1:2");
        assertThat(RedisKeys.parseMatchQueue(RedisKeys.matchQueue(1, -1))).contains(new MatchQueueId(1, -1));
        assertThat(RedisKeys.parseMatchQueue("game:{match}:queue:1:2")).as("缺省命名空间的队列键当坏成员").isEmpty();
    }
}
