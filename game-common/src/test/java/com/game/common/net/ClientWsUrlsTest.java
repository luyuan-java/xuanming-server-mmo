package com.game.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClientWsUrlsTest {

    @Test
    void 基地址解析与拼接遵循W7failClosed口径() {
        assertThat(ClientWsUrls.buildAdvertiseUrl("", "example.com", 18000)).isEmpty();
        assertThat(ClientWsUrls.buildAdvertiseUrl("wss://ws.example.com/ws/", "10.244.1.5", 18000))
                .isEqualTo("wss://ws.example.com/ws/10.244.1.5/18000");
        assertThat(ClientWsUrls.buildAdvertiseUrl("ws://127.0.0.1:8443/ws", "127.0.0.1", 20000))
                .isEqualTo("ws://127.0.0.1:8443/ws/127.0.0.1/20000");

        assertThatThrownBy(() -> ClientWsUrls.parseBaseUrl("https://ws.example.com/ws"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientWsUrls.parseBaseUrl("wss://ws.example.com/ws?a=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientWsUrls.parseBaseUrl("wss://ws.example.com/ws#frag"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientWsUrls.buildAdvertiseUrl("wss://ws.example.com/ws", "gate-0.svc", 18000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientWsUrls.buildAdvertiseUrl("wss://ws.example.com/ws", "10.0.0.256", 18000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
