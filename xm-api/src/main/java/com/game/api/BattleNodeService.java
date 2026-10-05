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
 *       destroyBattle / removeObserver 对不存在的房间 / 观众幂等成功。</li>
 *   <li>{@code issueBattleTicket} 信任调用方填的 player_id（同基线 {@code pb_node:70-76}）；安全性来自调用方 MAC（{@code XM_DUBBO_SECRET}）+ 内网隔离。
 *       1005 只表示「这局确实没了 / 不是成员」，调用方不得在别的失败上回 1005（客户端只把 1005 判为 BattleGone，§2.7）。</li>
 * </ul>
 *
 * <p>调用方建议超时：createBattle 5 s；其余 3 s（基线 {@code gather.go:25-30}、{@code rbt.go:22}）。
 */
public interface BattleNodeService {

    /** 建房（含节点级准入判定）。 */
    CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request);

    /** 作废一间房（match 补偿路径）：观众收 166 ABORTED，参战者不收任何帧，不结算、不发结果事件；房间不存在时幂等成功。 */
    CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request);

    /** 补签票据（179 的 battle 侧）：参战者优先，其次观众；都不是或房间不在 → 1005；签不出 → 1003。 */
    CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request);

    /** 登记观众（房间不存在 → 1004；满 20 人 → 1008；参战者 → 1005；签不出 → 1003 且保证不在名单上）。 */
    CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request);

    /** 清退观众（推 166 REMOVED 并关其直连）；房间或观众不在时幂等成功。 */
    CompletableFuture<Empty> removeObserver(RemoveObserverRequest request);
}
