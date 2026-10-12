package com.game.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 库名核对文案：期望库名缺省 = 命名空间（规格），应急开关覆盖；不一致时报出命名空间、DATABASE()、期望值与调用方自己的应急开关写法。 */
class NamespaceDatabaseCheckTest {

    private static final GameNamespace GAME = GameNamespace.of("game");
    private static final GameNamespace T1 = GameNamespace.of("t1");
    private static final String HINT = "--expect-database <库名>";

    @Test
    void 缺省期望库名等于命名空间() {
        assertThat(NamespaceDatabaseCheck.mismatch("game", null, GAME, HINT)).isNull();
        assertThat(NamespaceDatabaseCheck.mismatch("t1", null, T1, HINT)).isNull();
        assertThat(NamespaceDatabaseCheck.mismatch("game", null, T1, HINT)).contains("game.namespace=t1").contains("DATABASE()=game")
                .contains("期望 t1").contains("规格：库名 = 命名空间").contains("GAME_NAMESPACE").contains("应急时用 --expect-database <库名>");
        assertThat(NamespaceDatabaseCheck.mismatch(null, null, GAME, HINT)).as("没有选库").contains("DATABASE()=null");
    }

    @Test
    void 显式期望库名覆盖命名空间_报错写调用方自己的开关() {
        assertThat(NamespaceDatabaseCheck.mismatch("legacy_db", "legacy_db", GAME, "-expect-database <库名>")).isNull();
        assertThat(NamespaceDatabaseCheck.mismatch("game", "legacy_db", GAME, "-expect-database <库名>")).contains("期望 legacy_db")
                .contains("（应急开关 -expect-database <库名>）").doesNotContain("--expect-database");
        assertThat(NamespaceDatabaseCheck.mismatch("other", null, GAME, "game.mysql.expect-database（环境变量 GAME_MYSQL_EXPECT_DATABASE）"))
                .contains("应急时用 game.mysql.expect-database（环境变量 GAME_MYSQL_EXPECT_DATABASE）");
    }

    @Test
    void 报错不带URL() {
        assertThat(NamespaceDatabaseCheck.mismatch("game", null, T1, HINT)).doesNotContain("jdbc:");
    }
}
