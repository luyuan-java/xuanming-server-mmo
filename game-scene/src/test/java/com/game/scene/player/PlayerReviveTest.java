package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.player.PlayerRevive.Result;
import org.junit.jupiter.api.Test;

/**
 * 移植基线 {@code cpp/tests/turn_battle_engine_test/player_revive_rule_test.cpp} 里与气血 / 法力有关的用例。
 * 成长属性那几条（新号写职业初值、阵亡不动成长属性、按三项全 0 判新号）在 Java 不适用：Java 不存成长属性（PARITY「死亡 / 复活」行）；
 * 「上限传 0 退回职业初值」那条 Java 改为「上限为 0 的那一项不动」（气血上限恒 ≥ 1，只有法力会遇到）。
 */
class PlayerReviveTest {

    @Test
    void 阵亡按传入的上限回满() {
        assertThat(PlayerRevive.reviveIfDead(0, 13, 1100, 400)).isEqualTo(new Result(1100, 400, true));
    }

    @Test
    void 活着给了上限也不动_残血保住() {
        assertThat(PlayerRevive.reviveIfDead(123, 7, 1100, 400)).isEqualTo(new Result(123, 7, false));
        assertThat(PlayerRevive.reviveIfDead(1, 0, 1100, 400)).as("活着且法力 0").isEqualTo(new Result(1, 0, false));
    }

    @Test
    void 幂等_复活后再套一次不再改动() {
        Result first = PlayerRevive.reviveIfDead(0, 0, 640, 260);
        assertThat(first.revived()).isTrue();
        assertThat(PlayerRevive.reviveIfDead(first.health(), first.mana(), 640, 260))
                .isEqualTo(new Result(640, 260, false));
    }

    @Test
    void 法力上限为0的那一项不动() {
        assertThat(PlayerRevive.reviveIfDead(0, 5, 550, 0)).as("法力那一项原样保留").isEqualTo(new Result(550, 5, true));
        assertThat(PlayerRevive.reviveIfDead(0, 0, 550, 0)).as("不退回职业初值 800").isEqualTo(new Result(550, 0, true));
    }
}
