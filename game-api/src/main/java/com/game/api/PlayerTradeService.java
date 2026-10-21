package com.game.api;

import com.game.api.proto.PlayerTradeCommand;
import com.game.api.proto.PlayerTradeAuth;
import com.game.api.proto.PlayerTradeReply;
import java.util.concurrent.CompletableFuture;

/** 仅供 trade 的角色 SQL 权威；不占客户端消息号，不接受场景排空或付款自报。 */
public interface PlayerTradeService {
    String GROUP = "player-trade";
    /** future 失败表示结局未知；调用者只能重放原 listing/tx，不分配新身份。 */
    CompletableFuture<PlayerTradeReply> execute(PlayerTradeCommand command, PlayerTradeAuth auth);
}
