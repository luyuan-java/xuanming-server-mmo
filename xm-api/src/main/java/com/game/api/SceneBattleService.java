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
 *   <li>scene 侧最坏约 4.2 s 才给出结论（一条 Redis 脚本的 Redisson 最坏耗时），调用方超时必须大于它（§7.3、§10.4）。</li>
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
