package com.game.match.placement;

/**
 * 「这个地址此刻到底连不连得上」的一次探测（match-spec §4.3 第 5 行要的那条证据）。补签判「这一局确实没了」之前，直拨器在
 * 「请求没送达 + 同号节点已换实例」之外再问它一次：{@link RpcFailures} 的「没送达」比「连不上」宽——连接刚断、1 s 级的重连还没连上时
 * 同样是没送达，而此刻原进程可能还活着（网络抖动，或进程长停顿被心跳判断线：正是「丢了租约、可能很慢」的那一类）。
 *
 * <p>契约：阻塞至多 {@code timeoutMs}；<b>不抛异常</b>；线程安全，可以在工作线程与虚拟线程上调。
 */
@FunctionalInterface
public interface ConnectProbe {

    /** 探测的结论（三选一）。只有 {@link #REFUSED} 能作为「原进程不在这个地址上了」的证据。 */
    enum Result {
        /** 明确连不上：对端拒绝连接，或地址不可达（没有路由 / 网络不可达）。 */
        REFUSED,
        /** 连上了：这个地址上有进程在听（原进程还活着，或别的进程占了它——都由下一次直拨让对端自己回答）。 */
        CONNECTED,
        /** 没有结论：探测超时、主机名解析不了、预算不够没有探测、别的 I/O 错误。按「不能证明任何事」处理。 */
        INCONCLUSIVE
    }

    /**
     * 探测一次。
     *
     * @param timeoutMs 等待上限；≤ 0 不探测，直接 {@link Result#INCONCLUSIVE}
     */
    Result probe(String host, int port, long timeoutMs);
}
