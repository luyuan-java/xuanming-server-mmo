package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys.MatchQueueId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 金样：缺省部署命名空间（{@code game}）下，{@link RedisKeys} 的每一个生成方法与引入命名空间之前<b>逐字节相同</b>（样例见 {@link RedisKeysSamples}）。
 * 在钉成 {@code game} 的缺省 surefire 执行里跑。
 */
class RedisKeysGoldenTest {

    @BeforeAll
    static void 只在缺省命名空间里跑() {
        assertThat(GameNamespace.get())
                .as("金样只在缺省命名空间里成立；不要给 mvn 传 -Dgame.namespace（surefire 会让它覆盖 POM 钉的 game）")
                .isEqualTo("game");
    }

    @Test
    void 每个生成方法的整串与金样逐字节相同() {
        for (RedisKeysSamples.Sample sample : RedisKeysSamples.all()) {
            assertThat(sample.key().get()).as(sample.method()).isEqualTo(sample.golden());
        }
        assertThat(RedisKeys.PREFIX).isEqualTo("game:");
        assertThat(RedisKeys.GAIN_BLOCK_CURRENCY).isEqualTo("currency");
        assertThat(RedisKeys.GAIN_BLOCK_ITEM).isEqualTo("item");
    }

    @Test
    void 样例覆盖全部生成方法_新增方法必须补金样() {
        assertThat(RedisKeysSamples.publicStringMethods()).hasSize(73);
        assertThat(RedisKeysSamples.sampledMethods()).isEqualTo(RedisKeysSamples.publicStringMethods());
    }

    @Test
    void 队列键往返解析() {
        assertThat(RedisKeys.parseMatchQueue(RedisKeys.matchQueue(1, -1))).contains(new MatchQueueId(1, -1));
        assertThat(RedisKeys.parseMatchQueue("game:{match}:queue:3:0")).contains(new MatchQueueId(3, 0));
        assertThat(RedisKeys.parseMatchQueue("other:{match}:queue:3:0")).isEqualTo(Optional.empty());
    }
}
