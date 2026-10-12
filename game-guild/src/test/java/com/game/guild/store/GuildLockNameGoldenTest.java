package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.Test;

/** 金样：缺省部署命名空间（{@code game}）下，帮会建哨兵行的 MySQL 命名锁名与引入命名空间之前逐字节相同。 */
class GuildLockNameGoldenTest {

    @Test
    void 命名锁名与金样相同() {
        assertThat(GuildStartupChecks.insertGuardInitLockName(GameNamespace.of("game"))).isEqualTo("game_guild_insert_guard_init:game");
    }
}
