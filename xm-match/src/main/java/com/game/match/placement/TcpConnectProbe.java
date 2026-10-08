package com.game.match.placement;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * {@link ConnectProbe} 的生产实现：对目标地址做一次 TCP 建连，连上立即关掉（不发任何字节）。
 *
 * <ul>
 *   <li>{@link ConnectException}（连接被拒绝、网络不可达）/ {@link NoRouteToHostException} → {@link Result#REFUSED}；</li>
 *   <li>连上 → {@link Result#CONNECTED}；</li>
 *   <li>超时与其它一切 → {@link Result#INCONCLUSIVE}。<b>超时不算连不上</b>：丢包、对端所在的主机被隔离时也是超时，而那台主机上的进程可能还活着。</li>
 * </ul>
 * 对一个没人监听的端口建连，被拒绝通常是立即的（Linux；本机 Windows 11 上实测回环 0–20 ms）。有的环境要等内核重试几次才报拒绝
 * （旧版 Windows 约 2 s）：那时缺省上限内的探测只会超时 = 没有结论，补签不因「连不上」判死、只回暂不可用，方向安全。
 *
 * <p>用阻塞的 {@link Socket}：JDK 21 的套接字在虚拟线程上阻塞时会让出载体线程，不钉住。主机名解析不受 {@code timeoutMs} 约束
 * （落点记录里的地址是 battle 节点自己通告的，正常是 IP 或内网可解析的名字）。无状态、线程安全。
 */
public final class TcpConnectProbe implements ConnectProbe {

    /**
     * 探测等待的硬上限。操作系统自己的建连超时（几十秒）也是以 {@link ConnectException} 报上来的，与「被拒绝」分不开；
     * 把等待压在它之前，到点的只会是 {@link SocketTimeoutException}——这样 {@link ConnectException} 就只剩「被拒绝 / 不可达」。
     */
    static final long MAX_TIMEOUT_MS = 5_000;

    @Override
    public Result probe(String host, int port, long timeoutMs) {
        if (timeoutMs <= 0 || host == null || host.isBlank() || port < 1 || port > 65535) {
            return Result.INCONCLUSIVE;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) Math.min(timeoutMs, MAX_TIMEOUT_MS));
            return Result.CONNECTED;
        } catch (SocketTimeoutException e) {
            return Result.INCONCLUSIVE;
        } catch (ConnectException | NoRouteToHostException e) {
            return Result.REFUSED;
        } catch (IOException | RuntimeException e) {
            return Result.INCONCLUSIVE;
        }
    }
}
