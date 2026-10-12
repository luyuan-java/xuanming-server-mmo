package com.game.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 部署命名空间的校验表与定格状态机。全部用新建的 {@link NamespaceHolder}，不碰进程级的那一个（缺省 JVM 里它钉在 game）。
 */
class NamespaceHolderTest {

    private static Function<String, String> lookup(Map<String, String> values) {
        return values::get;
    }

    private static NamespaceHolder holder(Map<String, String> properties, Map<String, String> env) {
        return new NamespaceHolder(lookup(properties), lookup(env));
    }

    @Test
    void 校验表_合法值() {
        for (String ok : List.of("game", "a", "g1", "t1", "nstest", "a" + "0".repeat(31))) {
            assertThat(GameNamespace.isValid(ok)).as(ok).isTrue();
            assertThat(GameNamespace.validate(ok, "测试")).isEqualTo(ok);
        }
        assertThat(("a" + "0".repeat(31)).length()).isEqualTo(GameNamespace.MAX_LENGTH);
    }

    @Test
    void 校验表_非法值一律拒绝_消息点名配置键与环境变量并带原值() {
        List<String> bad = new ArrayList<>(List.of("", " game", "game ", "game\n", "Game", "GAME", "1a", "a-b", "a_b", "a:b", "a{b", "a*",
                "a?", "a[b", "a.b", "a" + "0".repeat(32), "游戏", "gäme", "mysql", "sys", "public"));
        for (String value : bad) {
            assertThat(GameNamespace.isValid(value)).as("[%s]", value).isFalse();
            assertThatThrownBy(() -> GameNamespace.validate(value, "测试来源")).as("[%s]", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("game.namespace").hasMessageContaining("GAME_NAMESPACE").hasMessageContaining("测试来源")
                    .hasMessageContaining(GameNamespace.quote(value));
        }
        assertThat(GameNamespace.isValid(null)).isFalse();
        assertThatThrownBy(() -> GameNamespace.validate(null, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GameNamespace.validate("mysql", "x")).hasMessageContaining("保留字");
        assertThat(GameNamespace.quote(" a\n")).as("首尾空白与控制字符看得见").isEqualTo("\" a\\n\"");
    }

    @Test
    void 回落顺序_系统属性优先于环境变量_都没有取缺省_回落即定格() {
        NamespaceHolder both = holder(Map.of("game.namespace", "p1"), Map.of("GAME_NAMESPACE", "e1"));
        assertThat(both.get()).isEqualTo("p1");
        assertThat(both.source()).contains("系统属性");

        NamespaceHolder envOnly = holder(Map.of(), Map.of("GAME_NAMESPACE", "e1"));
        assertThat(envOnly.get()).isEqualTo("e1");
        assertThat(envOnly.source()).contains("环境变量");

        NamespaceHolder none = holder(Map.of(), Map.of());
        assertThat(none.source()).as("未定格").isEmpty();
        assertThat(none.get()).isEqualTo("game");
        assertThat(none.source()).contains("缺省");
        assertThatThrownBy(() -> none.set("other", "之后")).as("回落即定格").isInstanceOf(IllegalStateException.class);

        NamespaceHolder relaxed = holder(Map.of("GAME_NAMESPACE", "x1"), Map.of("game.namespace", "x2", "GAME.NAMESPACE", "x3"));
        assertThat(relaxed.get()).as("只认两个精确的名字，不做宽松名").isEqualTo("game");
    }

    @Test
    void 回落值非法_抛异常且不定格() {
        NamespaceHolder bad = holder(Map.of(), Map.of("GAME_NAMESPACE", " game"));
        assertThatThrownBy(bad::get).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GAME_NAMESPACE")
                .hasMessageContaining("\" game\"");
        assertThatThrownBy(bad::get).as("仍未定格：再读仍失败").isInstanceOf(IllegalArgumentException.class);
        bad.set("t1", "显式");
        assertThat(bad.get()).isEqualTo("t1");

        NamespaceHolder empty = holder(Map.of("game.namespace", ""), Map.of());
        assertThatThrownBy(empty::get).as("空串拒绝").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void set_同值幂等_异值失败且消息有两次的值与来源_非法值不定格() {
        NamespaceHolder h = holder(Map.of(), Map.of());
        assertThatThrownBy(() -> h.set("Bad", "第一次")).isInstanceOf(IllegalArgumentException.class);
        assertThat(h.source()).as("非法值不定格").isEmpty();

        h.set("t1", "来源A");
        h.set("t1", "来源B");
        assertThat(h.get()).isEqualTo("t1");
        assertThat(h.source()).as("幂等的第二次不改来源").isEqualTo("来源A");
        assertThatThrownBy(() -> h.set("t2", "来源C")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"t1\"").hasMessageContaining("来源A").hasMessageContaining("\"t2\"").hasMessageContaining("来源C");
        assertThatThrownBy(() -> h.set("T2", "来源D")).as("非法值先于冲突检查").isInstanceOf(IllegalArgumentException.class);
        assertThat(h.get()).isEqualTo("t1");
    }

    @Test
    void 并发设定_只有一个值胜出() throws Exception {
        for (int round = 0; round < 20; round++) {
            NamespaceHolder h = holder(Map.of(), Map.of());
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            Set<String> winners = ConcurrentHashMap.newKeySet();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String value = "v" + (i % 3);
                boolean useGet = i == threads - 1;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (useGet) {
                        winners.add(h.get());
                        return null;
                    }
                    try {
                        h.set(value, "线程");
                        winners.add(value);
                    } catch (IllegalStateException lost) {
                        // 输给了另一个值
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(winners).as("第 %d 轮", round).hasSize(1).containsExactly(h.get());
        }
    }

    @Test
    void 进程级门面在缺省JVM里钉在game() {
        assertThat(GameNamespace.get())
                .as("缺省 surefire 执行把 game.namespace 钉成 game；不要给 mvn 传 -Dgame.namespace（surefire 会让它覆盖 POM 钉的值）")
                .isEqualTo(GameNamespace.DEFAULT);
        GameNamespace.set("game", "测试：同值幂等");
        assertThatThrownBy(() -> GameNamespace.set("other", "测试")).isInstanceOf(IllegalStateException.class);
        assertThat(GameNamespace.get()).isEqualTo("game");
    }
}
