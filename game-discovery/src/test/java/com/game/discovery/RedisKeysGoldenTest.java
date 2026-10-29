package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys.MatchQueueId;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 金样：缺省部署命名空间 {@code game} 的实例下，{@link RedisKeys} 的每一个生成方法与引入命名空间之前<b>逐字节相同</b>（样例见 {@link RedisKeysSamples}）；
 * 另钉住类型层的约束：没有静态入口，只能注入或构造实例。
 */
class RedisKeysGoldenTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    @Test
    void 每个生成方法的整串与金样逐字节相同() {
        for (RedisKeysSamples.Sample sample : RedisKeysSamples.all()) {
            assertThat(sample.key().apply(REDIS_KEYS)).as(sample.method()).isEqualTo(sample.golden());
        }
        assertThat(REDIS_KEYS.prefix()).isEqualTo("game:");
        assertThat(REDIS_KEYS.namespace()).isEqualTo(GameNamespace.of("game"));
        assertThat(REDIS_KEYS).hasToString("RedisKeys[game]");
        assertThat(RedisKeys.GAIN_BLOCK_CURRENCY).isEqualTo("currency");
        assertThat(RedisKeys.GAIN_BLOCK_ITEM).isEqualTo("item");
    }

    @Test
    void 样例覆盖全部生成方法_新增方法必须补金样() {
        assertThat(RedisKeysSamples.publicStringMethods()).as("90 个生成方法 + prefix()").hasSize(91);
        assertThat(RedisKeysSamples.sampledMethods()).isEqualTo(RedisKeysSamples.publicStringMethods());
    }

    @Test
    void 队列键往返解析() {
        assertThat(REDIS_KEYS.parseMatchQueue(REDIS_KEYS.matchQueue(1, -1))).contains(new MatchQueueId(1, -1));
        assertThat(REDIS_KEYS.parseMatchQueue("game:{match}:queue:3:0")).contains(new MatchQueueId(3, 0));
        assertThat(REDIS_KEYS.parseMatchQueue("other:{match}:queue:3:0")).isEqualTo(Optional.empty());
    }

    @Test
    void 类型层_public静态成员只有两个编译期常量_没有可变静态状态() {
        assertThat(Stream.concat(Arrays.stream(RedisKeys.class.getDeclaredFields()), Arrays.stream(RedisKeys.class.getDeclaredMethods()))
                .filter(m -> Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()) && !m.isSynthetic())
                .map(Member::getName))
                .as("静态入口就是绕过注入的全局值").containsExactlyInAnyOrder("GAIN_BLOCK_CURRENCY", "GAIN_BLOCK_ITEM");
        for (Field f : RedisKeys.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                assertThat(Modifier.isFinal(f.getModifiers())).as(f.getName()).isTrue();
                assertThat(f.getType()).as(f.getName()).isEqualTo(String.class);
            } else if (!f.isSynthetic()) {
                assertThat(Modifier.isFinal(f.getModifiers())).as("实例不可变: %s", f.getName()).isTrue();
            }
        }
        assertThat(Arrays.stream(RedisKeys.class.getDeclaredMethods()).filter(m -> Modifier.isStatic(m.getModifiers())).map(Method::getName))
                .as("只剩私有纯函数").containsExactlyInAnyOrder("queueSuffix", "canonicalDecimal");
        assertThatThrownBy(() -> new RedisKeys(null)).isInstanceOf(NullPointerException.class);
    }
}
