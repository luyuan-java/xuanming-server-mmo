package com.game.net.client;

/** WebSocket 握手与协议关闭观测回调（由 gate / battle 接入各自 Prometheus 指标）。 */
public interface WebSocketObserver {

    WebSocketObserver NOOP = new WebSocketObserver() {
    };

    /** RFC 6455 升级握手成功（已回 101 Switching Protocols）。 */
    default void onHandshakeAccepted() {
    }

    /** 升级握手被拒（400 / 426 / 头部超限）。 */
    default void onHandshakeRejected(String reason) {
    }

    /** 升级后因 RFC 6455 协议违规或超长帧被服务端发关闭帧并断开（1002 / 1003 / 1009）。 */
    default void onProtocolClosed(int closeCode) {
    }
}
