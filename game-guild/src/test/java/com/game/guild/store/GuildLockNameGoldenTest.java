package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 金样：缺省部署命名空间（{@code game}）下，帮会建哨兵行的 MySQL 命名锁名与引入命名空间之前逐字节相同。 */
class GuildLockNameGoldenTest {

    @BeforeAll
    static void 只在缺省命名空间里跑() {
        assertThat(GameNamespace.get())
                .as("金样只在缺省命名空间里成立；不要给 mvn 传 -Dgame.namespace（surefire 会让它覆盖 POM 钉的 game）")
                .isEqualTo("game");
    }

    @Test
    void 命名锁名与金样相同() {
        assertThat(GuildStartupChecks.INSERT_GUARD_INIT_LOCK_NAME).isEqualTo("game_guild_insert_guard_init:game");
    }
}
