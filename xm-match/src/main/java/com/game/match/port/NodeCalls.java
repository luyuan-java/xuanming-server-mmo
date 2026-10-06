package com.game.match.port;

import com.game.api.rpc.NodeRpcClients;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 按节点直连的出站调用口（match-spec §9.7）：{@code NodeRpcClients<S>::call} 的窄接口。match 有两个：
 * {@code NodeCalls<SceneBattleService>}（备战 / 取消，目标取自玩家位置记录 → scene 节点目录）与 {@code NodeCalls<BattleNodeService>}
 * （建房 / 销毁，目标取自 battle 节点目录；补签 / 观众 RPC，目标取自落点记录）。抽成接口只为可测：{@code NodeRpcClients} 是连真 Dubbo 的 final 类，
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
 *   <li>同一地址换了实例（节点号被新进程接手）时旧客户端被销毁重建；{@code target.instanceId()} 还会由对端再核对一次（scene 回 NOT_HERE）。</li>
 * </ul>
 * 线程安全，可在任意线程上调。
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
}
