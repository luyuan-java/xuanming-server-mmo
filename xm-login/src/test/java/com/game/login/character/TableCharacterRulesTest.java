package com.game.login.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 用仓库里同步来的真实配表（config-data/tables，只读文件，不需要外部设施）核对客户端可见的默认值：
 * 默认职业 1、名字 2–12 字、生成名「道友 + 6 位」、撞名最多 5 次。
 * Maven 以模块目录为工作目录跑测试，所以表目录是 ../config-data/tables。
 */
class TableCharacterRulesTest {

    private static final Path TABLE_DIR = Path.of("..", "config-data", "tables");

    @Test
    void 真实配表的默认值与mmorpg契约一致() {
        TableCharacterRules rules = TableCharacterRules.load(TABLE_DIR);

        assertThat(rules.defaultClassId()).isEqualTo(1);
        assertThat(rules.classExists(1)).isTrue();
        assertThat(rules.classExists(0)).isFalse();
        assertThat(rules.roleNameRules()).isEqualTo(new RoleNameRules(2, 12, "道友", 6, 5));
    }

    @Test
    void 目录不存在启动失败() {
        assertThatThrownBy(() -> TableCharacterRules.load(Path.of("no-such-dir-for-test")))
                .isInstanceOf(IllegalStateException.class);
    }
}
