package com.game.discovery.team;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 非缺省部署命名空间下，组队投影键前缀（Lua 里经 ARGV 拼键用）由 RedisKeys 推出、随命名空间。 */
@Tag("namespace")
class TeamMembershipReaderNamespaceTest {

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    @Test
    void 投影键前缀随命名空间() {
        assertThat(TeamMembershipReader.INFO_KEY_PREFIX).isEqualTo("nstest:{team}:info:");
    }
}
