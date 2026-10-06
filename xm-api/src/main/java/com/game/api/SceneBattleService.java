package com.game.api;

import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import java.util.concurrent.CompletableFuture;

/**
 * scene 节点的回合制战斗入口（scene-battle-spec §7.3、D1；battle-node-spec Q13）。每个 scene 节点在 {@code xm.scene.asset-rpc-port} 上与
 * {@link SceneAssetOpService} 一并导出，group {@link DubboGroups#SCENE_BATTLE}，{@code register = false}；调用方按 Redis 节点目录直连，
 * 引用 {@code retries = 0}。
 *
 * <p>契约（调用方必须知道的全部）：
 * <ul>
 *   <li>{@code call.target_instance_id} 必填：与提供方实例不符 → {@code NOT_HERE}，不进逻辑线程；</li>
 *   <li>应答的 {@code status = UNSPECIFIED} 一律按传输失败处理；</li>
 *   <li>future <b>异常完成</b> = 传输失败（结局未知）：{@link #prepareBattle} 的调用方按「可能已冻结」补发一次 {@link #cancelBattlePrepare}；</li>
 *   <li>scene 侧最坏约 4.2 s 才给出结论（一条 Redis 脚本的 Redisson 最坏耗时）。调用方超时怎么取分两种（§7.3、§10.4；2026-10-06 裁决）：
 *     <ul>
 *       <li><b>battle → scene 的 {@link #confirmBattle} / {@link #applySettlement}（与 dev gather）</b>：超时必须大于它
 *           （{@code xm.battle.scene-rpc-timeout} 缺省 5 s，battle 启动时校验）——否则 scene 已给出确定结论、调用方却先按「结局未知」处理。</li>
 *       <li><b>match 的 {@link #prepareBattle} / {@link #cancelBattlePrepare}</b>：超时是 3 s
 *           （{@link com.game.api.match.MatchBudgets#PREPARE_BATTLE_TIMEOUT_MS} / {@code CANCEL_PREPARE_TIMEOUT_MS}），<b>小于</b> scene 的最坏耗时，
 *           不受上一条约束：matched 票据 TTL、开战锁与 battle 确认补发窗口都按 3 s 标定（match-spec §3.4、§10.3），改它要连带重算整张表。
 *           备战超时按<b>结局不明</b>处理——该玩家记为肇事者，补偿时对他补发一次取消（match-spec M14）。这样是安全的：scene 在写锁在途时收到的取消
 *           会延后到写锁完成之后再删锁（只删备战锁），超时之后才给出的那份成功应答没人收，冻结与锁由这条取消、scene 的 reaper（备战期限）与锁 TTL 收尾。
 *           取消超时只记日志。</li>
 *     </ul></li>
 * </ul>
 */
public interface SceneBattleService {

    /** 备战冻结：body = 契约 {@code PrepareBattleRequest}；reply.body = 契约 {@code PrepareBattleResponse}。 */
    CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call);

    /** 取消备战：body = 契约 {@code CancelBattlePrepareRequest}；幂等，battle_id 不符时忽略。 */
    CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call);

    /** 开局确认：body = 契约 {@code BattleConfirmedEvent}。 */
    CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call);

    /** 结算投递：body = 契约 {@code BattleSettlementEvent}（与 Redis 待结算记录逐字节相同）。 */
    CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call);
}
