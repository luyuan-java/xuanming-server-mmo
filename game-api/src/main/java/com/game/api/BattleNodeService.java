package com.game.api;

import com.game.api.proto.CreateBattleResult;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.CreateBattleRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.RemoveObserverResponse;
import java.util.concurrent.CompletableFuture;

/**
 * battle 节点控制面（基线 gRPC {@code BattleNode}，{@code proto/battle/battle_node.proto:87-93}；battle-node-spec §7.8）。
 *
 * <p><b>按节点直连，不进注册中心</b>：每个 battle 节点一份，导出时 {@code register = false}、group {@link DubboGroups#BATTLE_NODE}、协议 Triple；
 * 调用方从 Redis 节点目录（{@code BattleNodeInfo.rpc_host / rpc_port}，节点类型 {@code battle}、作用域 0）拿地址，只从 {@code accepting = true}
 * 的条目里挑。调用方引用必须 {@code retries = 0}（Dubbo 缺省 failover 会重发 createBattle）。参数与返回值直接用契约生成类（{@code com.game.proto.*}）。
 *
 * <p>契约（调用方必须知道的全部）：
 * <ul>
 *   <li><b>createBattle</b>：结局看 {@link CreateBattleResult#getAdmission()}——
 *     <ul>
 *       <li>{@code ADMITTED}：已进入业务判定，{@code response} 是契约 {@code CreateBattleResponse} 的字节；其 {@code error_message} 非 0 = 没建房
 *           （1005 参数 / 路由缺实例、1006 指纹不符、1002 引擎拒绝、1003 签不出票据），且保证<b>零副作用</b>；error_message 为空 = 已建房
 *           或幂等命中（同 battle_id 的房间已存在，不比较请求内容，不重推任何东西）。</li>
 *       <li>{@code NOT_ALLOCATABLE}：节点级拒绝（准入闸未开 / 已关、在途超限），保证没建房、没推送、没发确认；调用方<b>不发</b> destroyBattle，
 *           换一个没试过的节点重试一次（基线 {@code UNAVAILABLE "battle_not_allocatable"}，§2.8）。</li>
 *       <li>{@code UNSPECIFIED}（字段缺失）：按传输失败处理。</li>
 *     </ul></li>
 *   <li>future <b>异常完成</b> = 传输失败（超时、断连、调用方鉴权失败、节点逻辑线程拒绝投递），结局未知：createBattle 时调用方按「可能已建」处理
 *       （发一次幂等的 destroyBattle 再补偿，基线 {@code gather.go:357-377}）。</li>
 *   <li>其余四个方法：业务错误都在应答的 {@code error_message} 里，Dubbo 层恒成功（同基线 {@code node.cpp:127-209}）。
 *       destroyBattle 对不存在的房间幂等；removeObserver 必须返回原UUID与固定实例的取消证明。</li>
 *   <li>{@code issueBattleTicket} 信任调用方填的 player_id（同基线 {@code pb_node:70-76}）；安全性来自调用方 MAC（{@code GAME_DUBBO_SECRET}）+ 内网隔离。
 *       1005 只表示「这局确实没了 / 不是成员」，调用方不得在别的失败上回 1005（客户端只把 1005 判为 BattleGone，§2.7）。</li>
 * </ul>
 *
 * <p>调用方建议超时：createBattle 5 s；其余 3 s（基线 {@code gather.go:25-30}、{@code rbt.go:22}）。
 */
public interface BattleNodeService {
    /** 仅当前固定实例的观察；ABSENT还须原Remove取得取消证明，UNKNOWN不能当作不存在。 */
    default CompletableFuture<com.game.proto.QueryObserverResponse> queryObserver(com.game.proto.QueryObserverRequest request){return CompletableFuture.completedFuture(com.game.proto.QueryObserverResponse.getDefaultInstance());}
    /** 精确进程与创建操作的权威查询；旧实现只会返回未知，不能据此释放票据。 */
    default CompletableFuture<com.game.proto.QueryBattleCreationResponse> queryBattleCreation(com.game.proto.QueryBattleCreationRequest request) {
        return CompletableFuture.completedFuture(com.game.proto.QueryBattleCreationResponse.getDefaultInstance());
    }

    /**
     * 观战中继（mmorpg battle-spectate-tiered-fanout.md §15.4、§15.7）：校验 {@code target_instance_id} 是本实例（否则 1005）、房间存在（否则 1004）、房间公开（否则 1005）后，
     * 幂等地打开本场的中继开关，回本实例 UUID 与战斗期限。Java 版没有逐 gate 挂接：开关打开后每帧只发布一次到这一场的 Redis 分片频道，
     * 有本机观众的 gate 自己订阅，所以 {@code gate_node_id} / {@code gate_instance_id} 不读、{@code broadcast} 恒为 false。
     */
    default CompletableFuture<com.game.proto.AttachRelayGateResponse> attachRelayGate(com.game.proto.AttachRelayGateRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("观战中继尚未装配"));
    }

    /** 本场升为全服广播：Java 版中继与广播是同一个开关（见 {@link #attachRelayGate}），校验与回包同它。 */
    default CompletableFuture<com.game.proto.PromoteSpectateBroadcastResponse> promoteSpectateBroadcast(com.game.proto.PromoteSpectateBroadcastRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("观战中继尚未装配"));
    }

    /** 建房（含节点级准入判定）。 */
    CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request);

    /** 作废一间房（match 补偿路径）：观众收 166 ABORTED，参战者不收任何帧，不结算、不发结果事件；房间不存在时幂等成功。 */
    CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request);

    /** 补签票据（179 的 battle 侧）：参战者优先，其次观众；都不是或房间不在 → 1005；签不出 → 1003。 */
    CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request);

    /** 登记观众（房间不存在 → 1004；满 20 人 → 1008；参战者 → 1005；签不出 → 1003 且保证不在名单上）。 */
    CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request);

    /** 固定原操作清退；只有cancelled+原UUID+固定实例证明才可让SQL释放watching，Empty或未知不得当成功。 */
    CompletableFuture<RemoveObserverResponse> removeObserver(RemoveObserverRequest request);
}
