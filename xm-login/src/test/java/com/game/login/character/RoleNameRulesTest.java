package com.game.login.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.table.RoleNameRuleTable;
import org.junit.jupiter.api.Test;

class RoleNameRulesTest {

    private static RoleNameRuleTable.Builder validRow() {
        return RoleNameRuleTable.newBuilder()
                .setId(1).setMinChars(2).setMaxChars(12)
                .setGeneratedPrefix("道友").setGeneratedSuffixLen(6).setMaxGenerateAttempts(5);
    }

    @Test
    void 合法行() {
        assertThat(RoleNameRules.fromRow(validRow().build())).isEqualTo(new RoleNameRules(2, 12, "道友", 6, 5));
    }

    @Test
    void 缺行() {
        assertThatThrownBy(() -> RoleNameRules.fromRow(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 长度规则不自洽() {
        assertInvalid(validRow().setMinChars(0));
        assertInvalid(validRow().setMaxChars(33));
        assertInvalid(validRow().setMinChars(10).setMaxChars(9));
        // uint32 超大值在 Java 里是负数，必须按无符号挡住。
        assertInvalid(validRow().setMaxChars(-1));
        assertInvalid(validRow().setMinChars(-1));
    }

    @Test
    void 前缀不合法() {
        assertInvalid(validRow().setGeneratedPrefix(""));
        assertInvalid(validRow().setGeneratedPrefix("道-友"));
        assertInvalid(validRow().setGeneratedPrefix("官方"));
        assertInvalid(validRow().setGeneratedPrefix("GM"));
    }

    @Test
    void 后缀位数与总长度() {
        assertInvalid(validRow().setGeneratedSuffixLen(0));
        assertInvalid(validRow().setGeneratedSuffixLen(17).setMaxChars(32));
        // 2 + 11 = 13 > 12。
        assertInvalid(validRow().setGeneratedSuffixLen(11));
        // 2 + 1 = 3 < 4。
        assertInvalid(validRow().setMinChars(4).setGeneratedSuffixLen(1));
    }

    @Test
    void 尝试次数在1到10() {
        assertInvalid(validRow().setMaxGenerateAttempts(0));
        assertInvalid(validRow().setMaxGenerateAttempts(11));
        assertThat(RoleNameRules.fromRow(validRow().setMaxGenerateAttempts(10).build()).maxGenerateAttempts())
                .isEqualTo(10);
    }

    private static void assertInvalid(RoleNameRuleTable.Builder row) {
        assertThatThrownBy(() -> RoleNameRules.fromRow(row.build())).isInstanceOf(IllegalArgumentException.class);
    }
}
