package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 对照 mmorpg {@code PlayerLevelRulesTest}。 */
class PlayerLevelsTest {

    @Test
    void GM设等级只接受1到85() {
        assertThat(PlayerLevels.isValid(0)).isFalse();
        assertThat(PlayerLevels.isValid(1)).isTrue();
        assertThat(PlayerLevels.isValid(PlayerLevels.MAX_LEVEL)).isTrue();
        assertThat(PlayerLevels.isValid(PlayerLevels.MAX_LEVEL + 1)).isFalse();
        assertThat(PlayerLevels.isValid(200)).isFalse();
        assertThat(PlayerLevels.isValid(-1)).as("uint32 0xFFFFFFFF").isFalse();
    }

    @Test
    void 超限存档压回上限_合法等级与0原样_0参与计算按1() {
        assertThat(PlayerLevels.clampStored(200)).isEqualTo(85);
        assertThat(PlayerLevels.clampStored(86)).isEqualTo(85);
        assertThat(PlayerLevels.clampStored(85)).isEqualTo(85);
        assertThat(PlayerLevels.clampStored(30)).isEqualTo(30);
        assertThat(PlayerLevels.clampStored(0)).isZero();
        assertThat(PlayerLevels.clampStored(Integer.MAX_VALUE)).as("存储层把超出 int 的无符号值饱和到这里").isEqualTo(85);
        assertThat(PlayerLevels.clampStored(-1)).as("防御：负数同样压回").isEqualTo(85);
        assertThat(PlayerLevels.effective(0)).isEqualTo(1);
        assertThat(PlayerLevels.effective(30)).isEqualTo(30);
    }
}
