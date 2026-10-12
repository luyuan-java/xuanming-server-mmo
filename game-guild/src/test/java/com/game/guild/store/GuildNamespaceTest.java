package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.Test;

/** 非缺省部署命名空间下帮会建哨兵行的 MySQL 命名锁：尾段是命名空间，前段不变；两个命名空间在同一个 JVM 里互不影响。 */
class GuildNamespaceTest {

    @Test
    void 命名锁尾段随命名空间() {
        assertThat(GuildStartupChecks.insertGuardInitLockName(GameNamespace.of("nstest"))).isEqualTo("game_guild_insert_guard_init:nstest");
        assertThat(GuildStartupChecks.insertGuardInitLockName(GameNamespace.of("game"))).isEqualTo("game_guild_insert_guard_init:game");
    }

    @Test
    void 没有命名空间就不构造() {
        assertThatThrownBy(() -> GuildStartupChecks.insertGuardInitLockName(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GuildStartupChecks(null, null)).isInstanceOf(NullPointerException.class);
    }
}
