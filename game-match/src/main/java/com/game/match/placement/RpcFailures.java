package com.game.match.placement;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.StatusRpcException;
import org.apache.dubbo.rpc.TriRpcStatus;

/**
 * 把一次按节点直连的调用（{@code NodeCalls.call} / {@code NodeRpcClients.call}）的<b>传输失败</b>分成三类（match-spec §4.3 第 5–6 行；
 * lead 裁决问题 4）。两处用它：补签 179 与 6.5 的观众 RPC 的判死；gather 的<b>建房</b>——建房请求确定没有送达时这个节点上不可能有这间房，
 * 直接按「没建房」补偿，不必先销毁（lead 裁决，2026-10-08）。gather 的其余各跳对任何传输失败一律按「结局不明」处理，不看类别。
 *
 * <p><b>判定原则：分不清就不判 {@link Kind#NOT_SENT}</b>。{@code NOT_SENT} 会参与「这局确实没了」的判定（客户端据此永久放弃本局），
 * 也会让 gather 不经销毁就解冻全员（判错 = 房间活着、玩家已解冻），所以只认「请求<b>确定</b>没有离开本进程」的客户端侧特征；
 * 别的一律落到 {@link Kind#TIMEOUT} / {@link Kind#OTHER}（补签的客户端退避后重试；建房走「先销毁成功才解冻」的原路径）。
 *
 * <p>{@link Kind#NOT_SENT} 的特征（都在本进程产生，异常链里出现任意一个即是；Dubbo 3.3.6 的真 Triple 回环测试
 * {@code RpcFailuresLoopbackTest} 逐条钉住，升级 Dubbo 时这条测试先红）：
 * <ol>
 *   <li>{@code StatusRpcException}，状态 {@code UNAVAILABLE}，描述<b>整句</b>是 {@code upstream <地址> is unavailable}：Triple 调用器发包之前
 *       发现连接不在（{@code TripleInvoker.doInvoke}）。对端从未监听、对端进程已退出、连接刚断还没重连上，都是它。
 *       对端<b>回</b>的 UNAVAILABLE 经过线上往返后描述带状态前缀（如 {@code UNAVAILABLE : …}）或是别的文案，不会整句相等。
 *       调用方知道这一次发往哪个地址时用 {@link #classify(Throwable, String)}：句中的地址还必须<b>就是</b>这一次的目标——
 *       对端转述它自己某个上游不可用（地址是别人的）在文案层面就被排除，不依赖「线上往返会加前缀」这个实现细节。</li>
 *   <li>不是 {@code StatusRpcException} 的 {@code RpcException}，错误码是「没有可用的提供方」：连接断开一段时间后，集群层把这个直连地址
 *       标成不可用，调用在选路阶段就失败了。对端的任何应答都以 {@code StatusRpcException} 到达，不会是这种形态。</li>
 *   <li>{@link ConnectException}（含 Netty 的连接超时）/ {@link NoRouteToHostException}：建连本身失败。</li>
 * </ol>
 * {@link Kind#TIMEOUT}：Dubbo 的调用超时（{@code DEADLINE_EXCEEDED}）、{@code java.util.concurrent.TimeoutException}（{@code NodeRpcClients}
 * 的本地兜底，以及「建客户端用完了这次调用的预算」——后者请求其实也没发出，但它不能证明地址连不上（也可能只是建连线程在排队），按超时算；
 * 首次直拨一个连不上的地址时，建连要等到 Dubbo 的连接超时，往往就是这一种，下一次直拨才会得到 {@code NOT_SENT}）。
 * 其余（连上之后被对端取消 / 断开、对端回错、鉴权失败、客户端缓存已关闭……）都是 {@link Kind#OTHER}。
 */
public final class RpcFailures {

    /** 传输失败的类别。 */
    public enum Kind {
        /** 请求确定没有送达：建连失败，或发包之前连接就不在。 */
        NOT_SENT,
        /** 调用超时：请求可能已经送达并生效。 */
        TIMEOUT,
        /** 其它：请求可能已送达（连上之后断开、对端回错），或分不清。 */
        OTHER
    }

    /** {@code TripleInvoker.doInvoke} 在连接不可用时给的描述（整句匹配；地址段不含空白）。 */
    private static final Pattern UPSTREAM_UNAVAILABLE = Pattern.compile("upstream (\\S+) is unavailable");
    private static final int MAX_DEPTH = 16;

    private RpcFailures() {
    }

    /**
     * 给一次失败分类。
     *
     * @param error future 异常完成时带的异常（可以是 {@code ExecutionException} / {@code CompletionException} 的外壳，会沿 cause 链往里看）；
     *              null 按 {@link Kind#OTHER}
     */
    public static Kind classify(Throwable error) {
        return classify(error, null);
    }

    /**
     * 给一次失败分类，并要求「发包之前连接不在」那一种特征里的地址就是这一次调用的目标。
     *
     * @param error         同 {@link #classify(Throwable)}
     * @param targetAddress 这一次调用发往的 {@code host:port}（{@code NodeRpcClients.Target.address()}）；null = 不核对地址。
     *                      地址对不上时那个特征不算数（落到 {@link Kind#OTHER}，方向安全）
     */
    public static Kind classify(Throwable error, String targetAddress) {
        boolean timeout = false;
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (notSent(current, targetAddress)) {
                return Kind.NOT_SENT;
            }
            timeout |= timedOut(current);
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return timeout ? Kind.TIMEOUT : Kind.OTHER;
    }

    private static boolean notSent(Throwable t, String targetAddress) {
        if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
            return true;
        }
        if (t instanceof StatusRpcException status) {
            TriRpcStatus rpcStatus = status.getStatus();
            if (rpcStatus == null || rpcStatus.code != TriRpcStatus.Code.UNAVAILABLE || rpcStatus.description == null) {
                return false;
            }
            Matcher upstream = UPSTREAM_UNAVAILABLE.matcher(rpcStatus.description);
            return upstream.matches() && (targetAddress == null || sameAddress(upstream.group(1), targetAddress));
        }
        return t instanceof RpcException rpc && rpc.isNoInvokerAvailableAfterFilter();
    }

    /** 两个 {@code host:port} 是否同一个地址：逐字比较，只放过 IPv6 字面量带不带方括号这一种写法差异。 */
    private static boolean sameAddress(String a, String b) {
        return a.equals(b) || a.replace("[", "").replace("]", "").equals(b.replace("[", "").replace("]", ""));
    }

    private static boolean timedOut(Throwable t) {
        if (t instanceof java.util.concurrent.TimeoutException || t instanceof org.apache.dubbo.remoting.TimeoutException) {
            return true;
        }
        return t instanceof RpcException rpc && rpc.isTimeout();
    }
}
