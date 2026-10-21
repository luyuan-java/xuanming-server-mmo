package com.game.api.auth;

import static org.junit.jupiter.api.Assertions.*;
import com.game.api.proto.*;
import org.junit.jupiter.api.Test;

class PlayerTradeSignaturesTest {
    private final PlayerTradeSignatures signatures = new PlayerTradeSignatures("角色交易测试独立密钥0123456789abcdef");
    private final long now = 1_700_000_000_000L;
    private PlayerTradeCommand original() {
        return PlayerTradeCommand.newBuilder().setOperation(PlayerTradeCommand.Operation.BEGIN).setListingId(101)
            .setPlayerId(1).setSellerAccount("原账号:甲").setOwnerRevision(7).setExpiresMs(now + 3000).build();
    }
    @Test void 覆盖全部正文_不同角色账号修订操作均不能借签名() {
        var request = original(); var auth = signatures.sign(request, now, "a".repeat(32));
        assertTrue(signatures.verify(request, auth, now));
        for (var changed : java.util.List.of(request.toBuilder().setPlayerId(2).build(), request.toBuilder().setListingId(102).build(),
                request.toBuilder().setSellerAccount("原账号:乙").build(), request.toBuilder().setOwnerRevision(8).build(),
                request.toBuilder().setOperation(PlayerTradeCommand.Operation.CANCEL).build(), request.toBuilder().setTxId(7).build(),
                request.toBuilder().setBuyerAccount("买家").build(), request.toBuilder().setExpiresMs(now + 3001).build()))
            assertFalse(signatures.verify(changed, auth, now));
    }
    @Test void 到期及未知操作拒绝_回复也绑定原请求与完整正文() {
        var request = original(); var auth = signatures.sign(request, now, "b".repeat(32));
        assertFalse(signatures.verify(request, auth, now + 3000));
        assertFalse(signatures.verify(request.toBuilder().setOperationValue(99).build(), auth, now));
        var reply = signatures.signReply(request, auth, PlayerTradeReply.newBuilder().setCode("OK").setPlayerId(1).setState("FROZEN").build());
        assertTrue(signatures.verifyReply(request, auth, reply));
        assertFalse(signatures.verifyReply(request.toBuilder().setListingId(102).build(), auth, reply));
        assertFalse(signatures.verifyReply(request, auth, reply.toBuilder().setState("TRANSFERRED").build()));
    }
}
