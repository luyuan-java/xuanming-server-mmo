package com.game.battle.admission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 逐条移植基线 {@code cpp/nodes/battle/tests/battle_admission_gate_test.cpp:33-88}（7 条，battle-node-spec §13.1）。 */
class AdmissionGateTest {

    private final AdmissionGate gate = new AdmissionGate();

    @Test
    void 初始是NOT_STARTED() {
        assertThat(gate.phase()).isEqualTo(AdmissionPhase.NOT_STARTED);
        assertThat(gate.isOpen()).isFalse();
    }

    @Test
    void open从NOT_STARTED进入OPEN() {
        assertThat(gate.open()).isTrue();
        assertThat(gate.phase()).isEqualTo(AdmissionPhase.OPEN);
        assertThat(gate.isOpen()).isTrue();
    }

    @Test
    void 重复open返回false且阶段不变() {
        assertThat(gate.open()).isTrue();
        assertThat(gate.open()).isFalse();
        assertThat(gate.phase()).isEqualTo(AdmissionPhase.OPEN);
    }

    @Test
    void close从OPEN进入CLOSED() {
        gate.open();
        gate.close();
        assertThat(gate.phase()).isEqualTo(AdmissionPhase.CLOSED);
        assertThat(gate.isOpen()).isFalse();
    }

    @Test
    void 没open就close是终态_之后open失败() {
        gate.close();
        assertThat(gate.phase()).isEqualTo(AdmissionPhase.CLOSED);
        assertThat(gate.open()).as("停机先于启动完成：不能再打开").isFalse();
        assertThat(gate.phase()).isEqualTo(AdmissionPhase.CLOSED);
    }

    @Test
    void close幂等() {
        gate.open();
        gate.close();
        gate.close();
        assertThat(gate.phase()).isEqualTo(AdmissionPhase.CLOSED);
        assertThat(gate.open()).isFalse();
    }

    @Test
    void 阶段名与指标取值() {
        assertThat(AdmissionPhase.NOT_STARTED.wireName()).isEqualTo("not_started");
        assertThat(AdmissionPhase.OPEN.wireName()).isEqualTo("open");
        assertThat(AdmissionPhase.CLOSED.wireName()).isEqualTo("closed");
        assertThat(AdmissionPhase.NOT_STARTED.gaugeValue()).isZero();
        assertThat(AdmissionPhase.OPEN.gaugeValue()).isEqualTo(1);
        assertThat(AdmissionPhase.CLOSED.gaugeValue()).isEqualTo(2);
    }
}
