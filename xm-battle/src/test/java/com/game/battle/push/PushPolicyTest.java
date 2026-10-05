package com.game.battle.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 逐条移植基线 {@code cpp/nodes/battle/tests/battle_push_policy_test.cpp:33-75}（battle-node-spec §13.1）。 */
class PushPolicyTest {

    @Test
    void 有活直连时战斗帧直发() {
        assertThat(PushPolicy.decide(PushCategory.BATTLE_FRAME, true)).isEqualTo(PushRoute.DIRECT);
    }

    @Test
    void 没有活直连时战斗帧丢弃() {
        assertThat(PushPolicy.decide(PushCategory.BATTLE_FRAME, false)).isEqualTo(PushRoute.DROP);
    }

    @Test
    void 有活直连时大厅公告也直发() {
        assertThat(PushPolicy.decide(PushCategory.LOBBY_ANNOUNCEMENT, true)).isEqualTo(PushRoute.DIRECT);
    }

    @Test
    void 没有活直连时大厅公告回落gate() {
        assertThat(PushPolicy.decide(PushCategory.LOBBY_ANNOUNCEMENT, false)).isEqualTo(PushRoute.VIA_GATE);
    }

    @Test
    void 穷举_只有大厅公告加无直连会回落gate() {
        int viaGate = 0;
        for (PushCategory category : PushCategory.values()) {
            for (boolean hasLiveDirect : new boolean[] {false, true}) {
                if (PushPolicy.decide(category, hasLiveDirect) == PushRoute.VIA_GATE) {
                    viaGate++;
                    assertThat(category).isEqualTo(PushCategory.LOBBY_ANNOUNCEMENT);
                    assertThat(hasLiveDirect).isFalse();
                }
            }
        }
        assertThat(viaGate).isEqualTo(1);
    }

    @Test
    void 指标标签取值() {
        assertThat(PushCategory.LOBBY_ANNOUNCEMENT.label()).isEqualTo("lobby");
        assertThat(PushCategory.BATTLE_FRAME.label()).isEqualTo("battle_frame");
        assertThat(PushRoute.DIRECT.label()).isEqualTo("direct");
        assertThat(PushRoute.VIA_GATE.label()).isEqualTo("via_gate");
        assertThat(PushRoute.DROP.label()).isEqualTo("dropped");
    }
}
