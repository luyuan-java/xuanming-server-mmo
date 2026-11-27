package com.game.api.rpc;

import org.apache.dubbo.remoting.Constants;

/**
 * 全服务 Dubbo 消费端统一的重连与心跳（半开检测）参数（对齐 PARITY J1 与 2026-11-19 断线重连待确认第 3 条）：
 * <ul>
 *   <li>心跳周期 5 s（与节点链路 LinkPing 同档，3 × 5 s = 15 s 发现半开 TCP 连接，远快于 Dubbo 缺省 3–4 分钟与内核 15 分钟重传）；</li>
 *   <li>最小重连间隔 5 s（后端重启后数秒内自动恢复，替代 Dubbo 缺省 60 s）。</li>
 * </ul>
 */
public final class DubboReconnectParameters {

    public static final String HEARTBEAT_MS = "5000";
    public static final String LEAST_RECONNECT_MS = "5000";

    /** 直接用于 {@code @DubboReference(parameters = {Constants.HEARTBEAT_KEY, DubboReconnectParameters.HEARTBEAT_MS, ...})}。 */
    public static final String[] PARAMETERS = {
            Constants.HEARTBEAT_KEY, HEARTBEAT_MS,
            Constants.LEAST_RECONNECT_DURATION_KEY, LEAST_RECONNECT_MS
    };

    private DubboReconnectParameters() {
    }
}
