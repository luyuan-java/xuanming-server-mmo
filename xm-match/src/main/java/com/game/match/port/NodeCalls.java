package com.game.match.port;

import com.game.api.rpc.NodeRpcClients;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 按节点直连的出站调用口（match-spec §9.7）：{@code NodeRpcClients<S>::call} 的窄接口。容器里有两个：
 * {@code NodeCalls<SceneBattleService>}（备战 / 取消，目标取自玩家位置记录 → scene 节点目录）与 {@code NodeCalls<BattleNodeService>}
 * （gather 的建房 / 销毁，目标取自 battle 节点目录）。补签 / 观众 RPC 按落点记录直拨 battle 用的是<b>另一份</b>客户端缓存的出站口
 * （{@code placement.PlacementClients#calls()}，不登记成 bean——两份共用会在 battle 原地重启后互相销毁对方的引用）。
 * 抽成接口只为可测：{@code NodeRpcClients} 是连真 Dubbo 的 final 类，
 * 组件测试用内存替身（{@code FakeNodeCalls} + {@code FakeSceneBattle} / {@code FakeBattleNode}）按目标把调用路由到假服务上，
 * 并能脚本化「连不上 / 超时 / 对端回错」。
 *
 * <p><b>契约</b>（与 {@code NodeRpcClients.call} 相同）：
 * <ul>
 *   <li><b>不阻塞</b>调用线程：建连在客户端缓存自己的线程上做；调用方要同步结果就在返回的 future 上按自己的预算等。</li>
 *   <li><b>不重试</b>（引用 {@code retries = 0}）：重投与补偿由调用方负责。</li>
 *   <li>future <b>异常完成 = 传输失败</b>，结局未知：连不上、超时（{@code timeout} 到点，本地另有约 200 ms 的兜底）、对端过载 / 未就绪 / 鉴权失败、
 *       客户端缓存已关闭。{@code timeout ≤ 0} 时不发包、直接异常完成。失败属于哪一类（建连失败 / 超时 / 其它）由需要区分的调用方自己从异常里分类
 *       （只有补签判死需要），其余调用方一律按「结局不明」处理。</li>
 *   <li>{@code invocation} 在客户端就绪后被调用恰好一次（就绪失败则不调），调用线程不确定；它必须只发起一次 Dubbo 调用并返回其 future。</li>
 *   <li>同一地址换了实例（节点号被新进程接手）时旧客户端被销毁重建；{@code target.instanceId()} 还会由对端再核对一次（scene 回 NOT_HERE）。
 *       所以 {@link #call} 只给<b>刚从目录读出来</b>的目标用；带着先前记下的目标发调用（取消、销毁）用 {@link #callRemembered}。</li>
 * </ul>
 * 生产实现是 {@link NodeClientCache#calls()}（包着 {@code NodeRpcClients}，另管空闲清扫）。线程安全，可在任意线程上调。
 *
 * @param <S> 服务接口（{@code SceneBattleService} / {@code BattleNodeService}）
 */
public interface NodeCalls<S> {

    /**
     * 向 {@code target} 发一次调用。
     *
     * @param target     目标节点的直连地址与实例号（来自节点目录或落点记录）
     * @param timeout    这一次调用的上限（{@code MatchBudgets} 的各跳常量，或按剩余预算收短）
     * @param invocation 在直连客户端上发起调用
     */
    <R> CompletableFuture<R> call(NodeRpcClients.Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation);

    /**
     * 向一个<b>先前记下来的</b>目标发一次调用（补偿的取消发回备战时的 scene 端点、回滚的销毁发回建房时选的 battle 节点）。
     * 与 {@link #call} 的区别只在客户端缓存：这个地址上已经有客户端时直接用它，<b>不因 {@code target.instanceId()} 与它不同而销毁重建</b>——
     * 记下来的实例号可能已经过时（节点原地重启），拿它去重建会把别的调用正在用的新客户端顶掉，其上在途的调用全部按传输失败收场。
     * 请求照样到达现在占着这个地址的进程；要不要认这个请求由对端决定（scene 核对请求里的实例号，不符回 NOT_HERE）。
     * 这个地址上还没有客户端时与 {@link #call} 相同。其余契约同 {@link #call}。
     *
     * <p>缺省实现就是 {@link #call}：没有客户端缓存的实现（测试替身）不需要区分。
     */
    default <R> CompletableFuture<R> callRemembered(NodeRpcClients.Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation) {
        return call(target, timeout, invocation);
    }
}
