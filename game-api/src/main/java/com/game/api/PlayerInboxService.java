package com.game.api;

import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.AckPlayerInboxResponse;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家持久收件箱的消费方接口（mmorpg {@code proto/player_inbox/player_inbox.proto} 的 {@code PlayerInbox} 服务，消息直接用同步来的契约 proto），
 * 由 game-inbox 提供（group {@link DubboGroups#INBOX}），调用方是 scene。语义同 mmorpg {@code docs/design/player-inbox-service.md} §5.1、§7：
 * <ul>
 *   <li>{@link #fetchItems}：按 seq 升序拉这名玩家这个域的 PENDING 条目（每批 ≤ 16），并按 owner_epoch 围栏判定；</li>
 *   <li>{@link #ackItems}：报告<b>已落盘</b>的结局（Java 的「已落盘」= 带 owner_epoch 条件的 player_state 提交成功）。</li>
 * </ul>
 * 业务拒绝与服务端故障都在应答的 {@code error_message}（tip）里（只用 tip，设计 §1.4）；future 异常完成 = 传输失败（结局未知，调用方退避重试）。
 * 两个方法都不幂等地改状态（投递计数、终结），调用方引用必须 {@code retries = 0}。
 */
public interface PlayerInboxService {

    CompletableFuture<FetchPlayerInboxResponse> fetchItems(FetchPlayerInboxRequest request);

    CompletableFuture<AckPlayerInboxResponse> ackItems(AckPlayerInboxRequest request);
}
