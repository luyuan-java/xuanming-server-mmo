package com.game.api;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;

/** 仅供 trade 的角色 SQL 权威；不占客户端消息号，不接受场景排空或付款自报。 */
public interface PlayerTradeService {
    String GROUP = "player-trade";
    enum Operation { PROFILE, BEGIN, CONFIRM, CANCEL, TRANSFER }
    record Command(Operation operation, long listingId, long txId, long playerId, String sellerAccount,
                   String buyerAccount, long ownerRevision, long expiresMs) implements Serializable {}
    record Auth(String caller, long timestampMs, String nonce, String signature) implements Serializable {}
    record Reply(String code, long listingId, long txId, long playerId, String sellerAccount,
                 String buyerAccount, long ownerRevision, long ownerEpoch, String state,
                 int homeZone, String title, String signature) implements Serializable {}
    /** future 失败表示结局未知；调用者只能重放原 listing/tx，不分配新身份。 */
    CompletableFuture<Reply> execute(Command command, Auth auth);
}
