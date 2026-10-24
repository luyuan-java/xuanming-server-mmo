package com.game.api;

import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.AckPlayerInboxResponse;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家持久收件箱的消费方接口（mmorpg {@code proto/player_inbox/player_inbox.proto} 的 {@code PlayerInbox} 服务，消息直接用同步来的契约 proto），
 * 由 game-inbox 提供（group {@link DubboGroups#INBOX}）。Scene只调用Fetch/Ack；Match以独立方法签名调用Battle入箱/查询。
 * 语义同 mmorpg {@code docs/design/player-inbox-service.md} §5.1、§7：
 * <ul>
 *   <li>{@link #fetchItems}：按 seq 升序拉这名玩家这个域的 PENDING 条目（每批 ≤ 16），并按 owner_epoch 围栏判定；</li>
 *   <li>{@link #ackItems}：报告<b>已落盘</b>的结局（Java 的「已落盘」= 带 owner_epoch 条件的 player_state 提交成功）。</li>
 * </ul>
 * 业务拒绝与服务端故障都在应答的 {@code error_message}（tip）里（只用 tip，设计 §1.4）；future 异常完成 = 传输失败（结局未知，调用方退避重试）。
 * Fetch/Ack会改投递计数或终结，所有调用方引用均须{@code retries = 0}。
 * Battle两方法固定原归属、签发时间、完整正文；永久幂等键不随重试改变，只有原SQL收据APPLIED代表到账，
 * 不以入箱成功、消息送达、UNKNOWN或通用运维VOID代替。
 */
public interface PlayerInboxService {

    String BATTLE_SECRET_ENV = "MMORPG_PLAYER_INBOX_SECRET_MATCH";
    String BATTLE_ENQUEUE_METHOD = "/player_inbox.PlayerInbox/EnqueueBattleSettlement";
    String BATTLE_LOOKUP_METHOD = "/player_inbox.PlayerInbox/LookupBattleSettlement";

    /** 仅Match永久终局来源；固定原owner、issuedAt与正文，重试不另分配seq。 */
    default CompletableFuture<com.game.proto.player_inbox.BattleSettlementInboxResponse> enqueueBattleSettlement(
            com.game.proto.player_inbox.BattleSettlementInboxRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("战斗永久来源入箱尚未装配"));
    }

    /** 只读取相同原件的永久SQL收据；仅APPLIED是到账，不以seq或入箱成功代替。 */
    default CompletableFuture<com.game.proto.player_inbox.BattleSettlementInboxResponse> lookupBattleSettlement(
            com.game.proto.player_inbox.BattleSettlementInboxRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("战斗永久收据读取尚未装配"));
    }

    CompletableFuture<FetchPlayerInboxResponse> fetchItems(FetchPlayerInboxRequest request);

    CompletableFuture<AckPlayerInboxResponse> ackItems(AckPlayerInboxRequest request);
}
