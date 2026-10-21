package com.game.login;

import com.game.api.PlayerTradeService;
import com.game.api.auth.PlayerTradeSignatures;
import com.game.api.proto.PlayerTradeAuth;
import com.game.api.proto.PlayerTradeCommand;
import com.game.api.proto.PlayerTradeReply;
import com.game.login.dispatch.LoginWorkerPool;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerTradeStore;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 阻塞 SQL 只在 login-worker 上跑；RPC 接受原件，不接受调用者自报排空、付款或归属证明。 */
@DubboService(group = PlayerTradeService.GROUP, timeout = 5000, retries = 0)
@ConditionalOnProperty(name = "game.login.role-trade.enabled", havingValue = "true")
public final class PlayerTradeProvider implements PlayerTradeService {
    private final PlayerStore players;
    private final PlayerTradeStore trades;
    private final PlayerTradeSignatures signatures;
    private final Executor worker;
    private final LongSupplier clock;
    @org.springframework.beans.factory.annotation.Autowired
    public PlayerTradeProvider(PlayerStore players, PlayerTradeStore trades, LoginWorkerPool worker) {
        this(players, trades, new PlayerTradeSignatures(System.getenv("GAME_PLAYER_TRADE_SECRET")), worker,
            System::currentTimeMillis);
    }
    PlayerTradeProvider(PlayerStore players, PlayerTradeStore trades, PlayerTradeSignatures signatures,
                        Executor worker, LongSupplier clock) {
        this.players = players; this.trades = trades; this.signatures = signatures; this.worker = worker; this.clock = clock;
    }
    @Override public CompletableFuture<PlayerTradeReply> execute(PlayerTradeCommand command, PlayerTradeAuth auth) {
        if (!signatures.verify(command, auth, clock.getAsLong()))
            return CompletableFuture.failedFuture(new SecurityException("角色交易正文鉴权失败"));
        return CompletableFuture.supplyAsync(() -> {
            if (!signatures.verify(command, auth, clock.getAsLong()))
                throw new SecurityException("角色交易在队列中到期");
            var out = PlayerTradeReply.newBuilder().setListingId(command.getListingId()).setTxId(command.getTxId())
                .setPlayerId(command.getPlayerId()).setSellerAccount(command.getSellerAccount())
                .setBuyerAccount(command.getBuyerAccount());
            try {
                long remaining = command.getExpiresMs() - clock.getAsLong();
                if (remaining <= 0) throw new SecurityException("角色交易期限已到");
                Duration budget = Duration.ofMillis(Math.min(remaining, 5_000));
                if (command.getOperation() == PlayerTradeCommand.Operation.PROFILE) {
                    var player = players.findPlayer(command.getPlayerId()).orElseThrow(() ->
                        new PlayerTradeStore.Failure(PlayerTradeStore.Code.NOT_FOUND, "角色不存在"));
                    if (!player.getAccount().equals(command.getSellerAccount()) || player.getOwnerRevision() <= 0)
                        throw new PlayerTradeStore.Failure(PlayerTradeStore.Code.CONFLICT, "角色账号归属不符");
                    out.setOwnerRevision(player.getOwnerRevision()).setOwnerEpoch(player.getOwnerEpoch())
                        .setHomeZone(player.getZoneId()).setTitle(player.getName())
                        .setState(player.getTradeListingId() == 0 ? "AVAILABLE" : "LOCKED");
                } else {
                    var freeze = new PlayerTradeStore.FreezeRequest(command.getListingId(), command.getPlayerId(),
                        command.getSellerAccount(), command.getOwnerRevision());
                    if (command.getOperation() == PlayerTradeCommand.Operation.TRANSFER) {
                        var receipt = trades.transfer(new PlayerTradeStore.TransferRequest(command.getTxId(),
                            command.getListingId(), command.getPlayerId(), command.getSellerAccount(),
                            command.getBuyerAccount(), command.getOwnerRevision()), budget);
                        out.setOwnerRevision(receipt.newRevision()).setState("TRANSFERRED");
                    } else {
                        var receipt = switch (command.getOperation()) {
                            case BEGIN -> trades.beginFreeze(freeze, budget);
                            case CONFIRM -> trades.confirmFreeze(freeze, budget);
                            case CANCEL -> trades.cancelFreeze(freeze, budget);
                            default -> throw new PlayerTradeStore.Failure(PlayerTradeStore.Code.INVALID, "角色交易操作非法");
                        };
                        out.setOwnerRevision(receipt.ownerRevision()).setOwnerEpoch(receipt.ownerEpoch())
                            .setState(receipt.status().name());
                    }
                }
                out.setCode("OK");
            } catch (PlayerTradeStore.Failure denied) {
                out.setCode(denied.code().name());
            }
            return signatures.signReply(command, auth, out.build());
        }, worker);
    }
}
