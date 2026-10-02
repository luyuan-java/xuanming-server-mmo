package com.game.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GatePropertiesTest {

    private static GateProperties withPorts(Integer clientPort, Integer advertisePort) {
        return new GateProperties(clientPort, advertisePort, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void advertisePortDefaultsToClientPort() {
        assertThat(withPorts(12000, null).advertisePort()).isEqualTo(12000);
        assertThat(withPorts(12000, 0).advertisePort()).isEqualTo(12000);
        assertThat(withPorts(null, null).advertisePort()).isEqualTo(11000);
    }

    @Test
    void advertisePortCanDifferFromListenPort() {
        GateProperties props = withPorts(11000, 30011);
        assertThat(props.clientPort()).isEqualTo(11000);
        assertThat(props.advertisePort()).isEqualTo(30011);
    }

    @Test
    void rejectsOutOfRangeAdvertisePort() {
        assertThatThrownBy(() -> withPorts(11000, 65536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withPorts(11000, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
