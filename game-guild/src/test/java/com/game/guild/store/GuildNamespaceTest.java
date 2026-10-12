package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 非缺省部署命名空间（namespace 执行，{@code -Dgame.namespace=nstest}）下帮会建哨兵行的 MySQL 命名锁：尾段是命名空间，前段不变。 */
@Tag("namespace")
class GuildNamespaceTest {

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    @Test
    void 命名锁尾段随命名空间() {
        assertThat(GuildStartupChecks.INSERT_GUARD_INIT_LOCK_NAME).isEqualTo("game_guild_insert_guard_init:nstest");
    }
}
