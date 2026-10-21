package com.game.login;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.game.api.auth.PlayerTradeSignatures;
import com.game.api.proto.*;
import com.game.player.store.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlayerTradeProviderTest {
    private final PlayerStore players = mock(PlayerStore.class);
    private final PlayerTradeStore trades = mock(PlayerTradeStore.class);
    private final PlayerTradeSignatures signatures = new PlayerTradeSignatures("测试角色交易独立密钥0123456789abcdef");
    private final long now = 1_700_000_000_000L;
    private PlayerTradeCommand command() {
        return PlayerTradeCommand.newBuilder().setOperation(PlayerTradeCommand.Operation.PROFILE).setPlayerId(1)
            .setSellerAccount("seller").setExpiresMs(now + 3000).build();
    }
    @Test void 改正文不进工作线程和SQL() {
        var provider = new PlayerTradeProvider(players, trades, signatures, Runnable::run, () -> now);
        var request = command(); var auth = signatures.sign(request, now, "a".repeat(32));
        assertThrows(java.util.concurrent.CompletionException.class, () -> provider.execute(request.toBuilder().setPlayerId(2).build(), auth).join());
        verifyNoInteractions(players, trades);
    }
    @Test void Profile只能查询原账号角色_拒绝也返回绑定原请求的签名() {
        var row = new PlayerRow(); row.setPlayerId(1); row.setAccount("other"); row.setOwnerRevision(2); row.setZoneId(3);
        when(players.findPlayer(1)).thenReturn(Optional.of(row));
        var request = command(); var auth = signatures.sign(request, now, "b".repeat(32));
        var provider = new PlayerTradeProvider(players, trades, signatures, Runnable::run, () -> now);
        var result = provider.execute(request, auth).join();
        assertEquals("CONFLICT", result.getCode()); assertTrue(signatures.verifyReply(request, auth, result));
        verifyNoInteractions(trades);
    }
}
