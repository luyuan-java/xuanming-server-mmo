package com.game.discovery.team;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import org.junit.jupiter.api.Test;

/** 组队投影键前缀（Lua 里经 ARGV 拼键用）由注入的 RedisKeys 推出、随命名空间；两个命名空间的实例在同一个 JVM 里并存。 */
class TeamMembershipReaderNamespaceTest {

    @Test
    void 投影键前缀随命名空间() {
        assertThat(TeamMembershipReader.infoKeyPrefix(new RedisKeys(GameNamespace.of("nstest")))).isEqualTo("nstest:{team}:info:");
        assertThat(TeamMembershipReader.infoKeyPrefix(new RedisKeys(GameNamespace.of("game")))).isEqualTo("game:{team}:info:");
    }
}
