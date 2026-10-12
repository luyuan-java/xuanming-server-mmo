package com.game.table.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** 表达式列的编译与求值：现有数据用到的写法、运算优先级、白名单函数、注入随机源，以及加载期就拒绝的写坏公式。 */
class TableExpressionTest {

    private static final RandomGenerator HALF = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public double nextDouble() {
            return 0.5;
        }
    };

    private static double eval(String source, List<String> params, double... args) {
        return TableExpression.compile("T", "c", 1, source, params).evaluate(args, HALF);
    }

    @Test
    void 现有数据的写法() {
        assertThat(eval("100*level", List.of("level"), 3)).isEqualTo(300);
        assertThat(eval("0.013*level*health", List.of("level", "health"), 2, 1000)).isCloseTo(26, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(eval("66", List.of())).isEqualTo(66);
        assertThat(eval("  ", List.of("level"), 1)).as("空串按 0").isZero();
    }

    @Test
    void 优先级与结合性() {
        assertThat(eval("1+2*3", List.of())).isEqualTo(7);
        assertThat(eval("(1+2)*3", List.of())).isEqualTo(9);
        assertThat(eval("2^3^2", List.of())).as("乘方右结合").isEqualTo(512);
        assertThat(eval("-2^2", List.of())).as("乘方高于一元负号").isEqualTo(-4);
        assertThat(eval("2^-1", List.of())).isEqualTo(0.5);
        assertThat(eval("10-4-3", List.of())).as("减法左结合").isEqualTo(3);
        assertThat(eval("7 % 3", List.of())).isEqualTo(1);
        assertThat(eval("-7 % 3", List.of())).as("浮点取余同 C fmod").isEqualTo(-1);
        assertThat(eval("1.5e2 + .5", List.of())).isEqualTo(150.5);
        assertThat(eval("--3", List.of())).isEqualTo(3);
        assertThat(eval("1/0", List.of())).isInfinite();
    }

    @Test
    void 函数() {
        assertThat(eval("min(3, level, 5)", List.of("level"), 1)).isEqualTo(1);
        assertThat(eval("max(3, level)", List.of("level"), 9)).isEqualTo(9);
        assertThat(eval("abs(-2) + floor(2.7) + ceil(2.1)", List.of())).isEqualTo(7);
        assertThat(eval("100*random()", List.of())).as("random() 取注入的随机源").isEqualTo(50);
        assertThat(eval("MAX(1,2)", List.of())).as("函数名不区分大小写").isEqualTo(2);
    }

    @Test
    void 写坏的公式在编译期拒绝() {
        for (String bad : List.of("100*lvl", "1+", "(1+2", "1 2", "foo(1)", "abs()", "abs(1,2)", "random(1)", "min()", "1+*2", "#", "1e999")) {
            assertThatThrownBy(() -> TableExpression.compile("Skill", "damage", 7, bad, List.of("level"))).as(bad)
                    .isInstanceOf(TableLoadException.class)
                    .hasMessageContaining("Skill")
                    .hasMessageContaining("damage")
                    .hasMessageContaining("行 7");
        }
    }

    @Test
    void 参数个数不对是调用方错误() {
        TableExpression e = TableExpression.compile("T", "c", 1, "level", List.of("level"));
        assertThatThrownBy(() -> e.evaluate(new double[0], HALF)).isInstanceOf(IllegalArgumentException.class);
    }
}
