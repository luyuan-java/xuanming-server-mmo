package com.game.trade.commerce;

import com.game.api.PlayerTradeService;
import com.game.api.auth.PlayerTradeSignatures;
import com.game.api.proto.PlayerTradeCommand;
import com.game.api.proto.PlayerTradeReply;
import com.game.common.deadline.Deadline;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** SQL 权威调用不重试；超时原 listing/tx 留在持久工作流，由后续轮次重放。 */
public final class PlayerTradeClient {
    private final PlayerTradeService remote;
    private final PlayerTradeSignatures signatures;
    public PlayerTradeClient(PlayerTradeService remote, String secret) {
        this.remote = remote; this.signatures = new PlayerTradeSignatures(secret);
    }
    public PlayerTradeReply execute(PlayerTradeCommand.Builder original, Deadline budget) throws Exception {
        CommerceStore.active(budget);
        long remaining = Math.min(3_000, budget.remainingMillis());
        if (remaining <= 0) throw new java.sql.SQLTimeoutException("角色交易预算结束");
        long now = System.currentTimeMillis();
        var command = original.setExpiresMs(now + remaining).build();
        var auth = signatures.sign(command, now, UUID.randomUUID().toString().replace("-", ""));
        var result = remote.execute(command, auth).get(remaining, TimeUnit.MILLISECONDS);
        if (!signatures.verifyReply(command, auth, result) || result.getPlayerId() != command.getPlayerId()
                || result.getListingId() != command.getListingId() || result.getTxId() != command.getTxId()
                || !result.getSellerAccount().equals(command.getSellerAccount())
                || !result.getBuyerAccount().equals(command.getBuyerAccount()))
            throw new SecurityException("角色交易回复未绑定原请求");
        return result;
    }
}
