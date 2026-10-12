package com.game.match.placement;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.placement.RpcFailures.Kind;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.junit.jupiter.api.Test;

/**
 * 传输失败的三分类（match-spec §4.3 第 5–6 行；lead 裁决问题 4）在构造出来的异常上的判定：只有「请求确定没有离开本进程」的客户端侧特征才是
 * {@code NOT_SENT}，分不清的一律落到 {@code TIMEOUT} / {@code OTHER}。真 Dubbo 实际抛出的形态由 {@code RpcFailuresLoopbackTest} 钉。
 */
class RpcFailuresTest {

    private static Throwable status(TriRpcStatus.Code code, String description) {
        return TriRpcStatus.fromCode(code).withDescription(description).asException();
    }

    @Test
    void 发包前连接不在_整句upstream不可用_是NOT_SENT_带各种外壳也认() {
        Throwable upstream = status(TriRpcStatus.Code.UNAVAILABLE, "upstream 127.0.0.1:21200 is unavailable");

        assertThat(RpcFailures.classify(upstream)).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(new ExecutionException(upstream))).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(new CompletionException(new ExecutionException(upstream)))).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNAVAILABLE, "upstream [::1]:21200 is unavailable"))).isEqualTo(Kind.NOT_SENT);
    }

    @Test
    void 对端回的UNAVAILABLE_描述不是整句_不算NOT_SENT() {
        // 对端抛出同样文案时，线上往返后描述带状态前缀（回环测试实测的形态）
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNAVAILABLE, "UNAVAILABLE : upstream 127.0.0.1:1 is unavailable")))
                .isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNAVAILABLE, "battle_not_allocatable"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNAVAILABLE, "upstream is unavailable"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNAVAILABLE, "upstream a b is unavailable"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNAVAILABLE, null))).isEqualTo(Kind.OTHER);
        // 文案对、状态码不对
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNKNOWN, "upstream 127.0.0.1:21200 is unavailable"))).isEqualTo(Kind.OTHER);
    }

    @Test
    void 集群层没有可用的提供方_是NOT_SENT_别的RpcException不是() {
        RpcException noProvider = new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "No provider available for the service");

        assertThat(RpcFailures.classify(noProvider)).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(new ExecutionException(noProvider))).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(new RpcException(RpcException.NETWORK_EXCEPTION, "连接被对端重置"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(new RpcException(RpcException.FORBIDDEN_EXCEPTION, "鉴权失败"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(new RpcException(RpcException.UNKNOWN_EXCEPTION, "不明"))).isEqualTo(Kind.OTHER);
    }

    @Test
    void 建连本身失败_连接被拒_无路由_是NOT_SENT() {
        assertThat(RpcFailures.classify(new ConnectException("Connection refused"))).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(new NoRouteToHostException("No route to host"))).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(new RpcException(RpcException.NETWORK_EXCEPTION, new ConnectException("Connection refused"))))
                .as("包在别的异常里也认").isEqualTo(Kind.NOT_SENT);
    }

    @Test
    void 超时的各种形态_是TIMEOUT() {
        assertThat(RpcFailures.classify(new TimeoutException("这次调用的剩余预算已用完"))).isEqualTo(Kind.TIMEOUT);
        assertThat(RpcFailures.classify(new TimeoutException("建立客户端用完了这次调用的预算")))
                .as("请求其实没发出，但不能证明地址连不上：按超时，不参与判死").isEqualTo(Kind.TIMEOUT);
        assertThat(RpcFailures.classify(new ExecutionException(new TimeoutException()))).isEqualTo(Kind.TIMEOUT);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.DEADLINE_EXCEEDED, "Waiting server-side response timeout by scan timer")))
                .isEqualTo(Kind.TIMEOUT);
        assertThat(RpcFailures.classify(new RpcException(RpcException.TIMEOUT_EXCEPTION, "timeout"))).isEqualTo(Kind.TIMEOUT);
    }

    @Test
    void 其余一律OTHER_连上之后被取消_对端回错_解析不了的主机名_缓存已关闭_null() {
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.CANCELLED, null))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.UNKNOWN, "提供方抛出了异常"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(status(TriRpcStatus.Code.PERMISSION_DENIED, "调用方鉴权失败"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(new SocketException("Connection reset"))).as("连上之后断开：请求可能已送达").isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(new UnknownHostException("no-such-host"))).as("解析失败不是「原进程连不上」的证据").isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(new IllegalStateException("客户端缓存已关闭"))).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(null)).isEqualTo(Kind.OTHER);
    }

    @Test
    void 链里同时有超时与建连失败_以建连失败为准_自引用的cause不会死循环() {
        Throwable connectTimeout = new TimeoutException("外层等待超时");
        connectTimeout.initCause(new ConnectException("connection timed out"));
        assertThat(RpcFailures.classify(connectTimeout)).isEqualTo(Kind.NOT_SENT);

        Throwable chain = new IllegalStateException("第 0 层");
        Throwable current = chain;
        for (int i = 1; i < 40; i++) {
            Throwable next = new IllegalStateException("第 " + i + " 层");
            current.initCause(next);
            current = next;
        }
        current.initCause(new ConnectException("埋得太深"));
        assertThat(RpcFailures.classify(chain)).as("只看有限的层数，看不到就按分不清").isEqualTo(Kind.OTHER);
    }
}
