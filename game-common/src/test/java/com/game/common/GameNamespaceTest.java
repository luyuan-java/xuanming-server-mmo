package com.game.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 部署命名空间值类型：校验表、构造即校验、{@link GameNamespace#fromEnv} 的取值规则，以及「没有进程级可变状态」。 */
class GameNamespaceTest {

    @Test
    void 校验表_合法值() {
        for (String ok : List.of("game", "a", "g1", "t1", "nstest", "a" + "0".repeat(27), "mysql1", "system", "publics")) {
            assertThat(GameNamespace.isValid(ok)).as(ok).isTrue();
            assertThat(GameNamespace.of(ok).value()).isEqualTo(ok);
            assertThat(new GameNamespace(ok).toString()).isEqualTo(ok);
        }
        assertThat(("a" + "0".repeat(27)).length()).isEqualTo(GameNamespace.MAX_LENGTH).isEqualTo(28);
    }

    @Test
    void 校验表_非法值一律拒绝_消息点名配置键与环境变量并带原值与来源() {
        List<String> bad = new ArrayList<>(List.of("", " ", " game", "game ", "game\n", "\tgame", "Game", "GAME", "1a", "1", "a-b", "a_b",
                "a:b", "a{b", "a*", "a?", "a[b", "a.b", "a$b", "a" + "0".repeat(28), "游戏", "gäme", "mysql", "sys", "public"));
        for (String value : bad) {
            assertThat(GameNamespace.isValid(value)).as("[%s]", value).isFalse();
            assertThatThrownBy(() -> GameNamespace.of(value, "测试来源")).as("[%s]", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("game.namespace").hasMessageContaining("GAME_NAMESPACE").hasMessageContaining("测试来源")
                    .hasMessageContaining(GameNamespace.quote(value));
            assertThatThrownBy(() -> new GameNamespace(value)).as("构造即校验 [%s]", value)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("构造参数");
        }
        assertThat(GameNamespace.isValid(null)).isFalse();
        assertThatThrownBy(() -> GameNamespace.of(null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null")
                .hasMessageContaining("调用方");
        assertThatThrownBy(() -> GameNamespace.of("mysql", "x")).hasMessageContaining("保留字");
        assertThatThrownBy(() -> GameNamespace.of("Bad", "x")).hasMessageContaining(GameNamespace.RULE);
        assertThat(GameNamespace.quote(" a\n")).as("首尾空白与控制字符看得见").isEqualTo("\" a\\n\"");
        assertThat(GameNamespace.quote(null)).isEqualTo("null");
    }

    @Test
    void 正则与规则文案_长度上限一致_文案不含BeanValidation的插值字符() {
        Pattern pattern = Pattern.compile(GameNamespace.REGEX);
        assertThat(pattern.matcher("a" + "b".repeat(GameNamespace.MAX_LENGTH - 1)).matches()).isTrue();
        assertThat(pattern.matcher("a" + "b".repeat(GameNamespace.MAX_LENGTH)).matches()).isFalse();
        assertThat(GameNamespace.RULE).contains(String.valueOf(GameNamespace.MAX_LENGTH)).doesNotContain("{").doesNotContain("}").doesNotContain("$");
        assertThat(GameNamespace.DEFAULT_VALUE).isEqualTo("game");
        assertThat(GameNamespace.isValid(GameNamespace.DEFAULT_VALUE)).isTrue();
    }

    @Test
    void 值语义_同值相等_不同命名空间可在同一个JVM并存() {
        assertThat(GameNamespace.of("game")).isEqualTo(new GameNamespace("game")).hasSameHashCodeAs(GameNamespace.of("game"));
        assertThat(GameNamespace.of("game")).isNotEqualTo(GameNamespace.of("nstest"));
    }

    @Test
    void fromEnv_有变量按原样校验_没有取缺省_不读系统属性() {
        assertThat(GameNamespace.fromEnv(Map.of())).isEqualTo(new GameNamespace.Sourced(GameNamespace.of("game"), "缺省"));
        GameNamespace.Sourced t1 = GameNamespace.fromEnv(Map.of("GAME_NAMESPACE", "t1"));
        assertThat(t1.namespace().value()).isEqualTo("t1");
        assertThat(t1.source()).isEqualTo("环境变量 GAME_NAMESPACE");

        assertThatThrownBy(() -> GameNamespace.fromEnv(Map.of("GAME_NAMESPACE", ""))).as("空串照样拒，不当成没设")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("\"\"").hasMessageContaining("环境变量 GAME_NAMESPACE");
        assertThatThrownBy(() -> GameNamespace.fromEnv(Map.of("GAME_NAMESPACE", " game"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GameNamespace.fromEnv(Map.of("GAME_NAMESPACE", "mysql"))).isInstanceOf(IllegalArgumentException.class);

        Map<String, String> relaxed = new HashMap<>();
        relaxed.put("game.namespace", "x1");
        relaxed.put("GAME.NAMESPACE", "x2");
        assertThat(GameNamespace.fromEnv(relaxed).namespace().value()).as("只认精确的 GAME_NAMESPACE").isEqualTo("game");

        String old = System.getProperty("game.namespace");
        System.setProperty("game.namespace", "fromprop");
        try {
            assertThat(GameNamespace.fromEnv(Map.of()).namespace().value()).as("不读系统属性").isEqualTo("game");
        } finally {
            if (old == null) {
                System.clearProperty("game.namespace");
            } else {
                System.setProperty("game.namespace", old);
            }
        }
    }

    @Test
    void 没有进程级可变状态_静态字段全是常量() {
        for (Field f : GameNamespace.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                assertThat(Modifier.isFinal(f.getModifiers())).as("静态字段必须 final: %s", f.getName()).isTrue();
                assertThat(f.getType()).as("静态字段只许常量类型: %s", f.getName())
                        .isIn(String.class, int.class, Pattern.class, java.util.Set.class);
            }
        }
        assertThat(Arrays.stream(GameNamespace.class.getDeclaredMethods()).map(m -> m.getName()))
                .as("没有全局取值 / 设值入口").doesNotContain("get", "set", "source");
    }
}
