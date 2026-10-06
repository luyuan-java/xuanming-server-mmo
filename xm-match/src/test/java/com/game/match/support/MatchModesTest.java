package com.game.match.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 匹配模式的固定规则（match-spec §2.2「人数」、§2.8、§1.4）：排队入口与凑单用同一个口径。
 */
class MatchModesTest {

    @Test
    void 模式数值与契约一致() {
        assertThat(MatchModes.UNSPECIFIED).isZero();
        assertThat(MatchModes.FIVE_V_FIVE).isEqualTo(1);
        assertThat(MatchModes.THREE_V_THREE).isEqualTo(2);
        assertThat(MatchModes.ONE_V_ONE).isEqualTo(3);
        assertThat(MatchModes.PVE_SOLO).isEqualTo(4);
        assertThat(MatchModes.PVE_TEAM).isEqualTo(5);
        assertThat(MatchModes.PVP_CHALLENGE).isEqualTo(6);
    }

    @Test
    void 排队入口只开放四种模式() {
        assertThat(MatchModes.joinable(MatchModes.FIVE_V_FIVE)).isTrue();
        assertThat(MatchModes.joinable(MatchModes.ONE_V_ONE)).isTrue();
        assertThat(MatchModes.joinable(MatchModes.PVE_SOLO)).isTrue();
        assertThat(MatchModes.joinable(MatchModes.PVE_TEAM)).isTrue();
        assertThat(MatchModes.joinable(MatchModes.THREE_V_THREE)).isFalse();
        assertThat(MatchModes.joinable(MatchModes.PVP_CHALLENGE)).as("切磋只能经切磋入口").isFalse();
        assertThat(MatchModes.joinable(MatchModes.UNSPECIFIED)).isFalse();
        assertThat(MatchModes.joinable(7)).isFalse();
        assertThat(MatchModes.joinable(-1)).isFalse();
    }

    @Test
    void 凑满人数_单人1_1V1是2_5V5是10_组队取调用方给的人数() {
        assertThat(MatchModes.requiredPlayers(MatchModes.PVE_SOLO, 0)).isEqualTo(1);
        assertThat(MatchModes.requiredPlayers(MatchModes.PVE_SOLO, 5)).as("单人不看组队人数").isEqualTo(1);
        assertThat(MatchModes.requiredPlayers(MatchModes.ONE_V_ONE, 0)).isEqualTo(2);
        assertThat(MatchModes.requiredPlayers(MatchModes.FIVE_V_FIVE, 0)).isEqualTo(10);
        assertThat(MatchModes.requiredPlayers(MatchModes.PVE_TEAM, 5)).isEqualTo(5);
        assertThat(MatchModes.requiredPlayers(MatchModes.PVE_TEAM, 3)).isEqualTo(3);
        assertThat(MatchModes.requiredPlayers(MatchModes.PVE_TEAM, 0)).as("没配置 = 不能排").isZero();
        assertThat(MatchModes.requiredPlayers(MatchModes.PVE_TEAM, -2)).isZero();
        assertThat(MatchModes.requiredPlayers(MatchModes.THREE_V_THREE, 5)).isZero();
        assertThat(MatchModes.requiredPlayers(MatchModes.PVP_CHALLENGE, 5)).isZero();
        assertThat(MatchModes.requiredPlayers(MatchModes.UNSPECIFIED, 5)).isZero();
        assertThat(MatchModes.requiredPlayers(42, 5)).isZero();
    }

    @Test
    void 只有1V1与5V5计分() {
        assertThat(MatchModes.rated(MatchModes.ONE_V_ONE)).isTrue();
        assertThat(MatchModes.rated(MatchModes.FIVE_V_FIVE)).isTrue();
        assertThat(MatchModes.rated(MatchModes.PVE_SOLO)).isFalse();
        assertThat(MatchModes.rated(MatchModes.PVE_TEAM)).isFalse();
        assertThat(MatchModes.rated(MatchModes.PVP_CHALLENGE)).as("切磋不计分").isFalse();
        assertThat(MatchModes.rated(MatchModes.THREE_V_THREE)).isFalse();
    }

    @Test
    void 走队列的是1V1_5V5_组队_单人副本不入队() {
        assertThat(MatchModes.queued(MatchModes.ONE_V_ONE)).isTrue();
        assertThat(MatchModes.queued(MatchModes.FIVE_V_FIVE)).isTrue();
        assertThat(MatchModes.queued(MatchModes.PVE_TEAM)).isTrue();
        assertThat(MatchModes.queued(MatchModes.PVE_SOLO)).isFalse();
        assertThat(MatchModes.queued(MatchModes.PVP_CHALLENGE)).isFalse();
    }
}
