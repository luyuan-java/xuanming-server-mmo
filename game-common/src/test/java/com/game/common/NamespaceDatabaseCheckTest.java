package com.game.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 命令行工具的库名核对文案：期望库名缺省 = 命名空间，--expect-database 覆盖；不一致时报出命名空间、DATABASE() 与期望值。 */
class NamespaceDatabaseCheckTest {

    @Test
    void 缺省期望库名等于命名空间() {
        assertThat(NamespaceDatabaseCheck.mismatch("game", null, "game")).isNull();
        assertThat(NamespaceDatabaseCheck.mismatch("t1", null, "t1")).isNull();
        assertThat(NamespaceDatabaseCheck.mismatch("game", null, "t1")).contains("game.namespace=t1").contains("DATABASE()=game")
                .contains("期望 t1").contains("GAME_NAMESPACE").contains("--expect-database");
        assertThat(NamespaceDatabaseCheck.mismatch(null, null, "game")).as("没有选库").contains("DATABASE()=null");
    }

    @Test
    void 显式期望库名覆盖命名空间() {
        assertThat(NamespaceDatabaseCheck.mismatch("legacy_db", "legacy_db", "game")).isNull();
        assertThat(NamespaceDatabaseCheck.mismatch("game", "legacy_db", "game")).contains("期望 legacy_db").contains("（--expect-database）");
    }
}
