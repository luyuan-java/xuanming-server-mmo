package com.game.login.compliance;

import com.game.api.DubboGroups;
import com.game.api.LoginAdminService;
import com.game.login.dispatch.LoginWorkerPool;
import com.game.login.dispatch.Tips;
import com.game.player.store.AccountLifecycle;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.login.AccountDeletionResponse;
import com.game.proto.login.ResetAccountRealNameRequest;
import com.game.proto.login.ResetAccountRealNameResponse;
import com.game.proto.login.ScheduleAccountDeletionRequest;
import com.game.table.LoginErrorTip;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link LoginAdminService} 的 Dubbo 提供方（group = {@link DubboGroups#LOGIN}）：
 * 客服重置实名（426）、客服代办注销（427）与支付服务实名 {@code pi} 只读查询。
 */
@DubboService(group = DubboGroups.LOGIN)
public class LoginAdminRpcService implements LoginAdminService {

    private static final Logger log = LoggerFactory.getLogger(LoginAdminRpcService.class);

    private final PlayerStore store;
    private final AccountDeletionService deletionService;
    private final AntiAddictionGate antiAddictionGate;
    private final Executor executor;

    @Autowired
    public LoginAdminRpcService(PlayerStore store, AccountDeletionService deletionService,
                                @Autowired(required = false) AntiAddictionGate antiAddictionGate,
                                LoginWorkerPool workers) {
        this(store, deletionService, antiAddictionGate, (Executor) workers);
    }

    LoginAdminRpcService(PlayerStore store, AccountDeletionService deletionService,
                         AntiAddictionGate antiAddictionGate, Executor executor) {
        this.store = store;
        this.deletionService = deletionService;
        this.antiAddictionGate = antiAddictionGate;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<ResetAccountRealNameResponse> resetAccountRealName(ResetAccountRealNameRequest request) {
        return onWorkers(() -> doResetRealName(request));
    }

    @Override
    public CompletableFuture<AccountDeletionResponse> scheduleAccountDeletion(ScheduleAccountDeletionRequest request) {
        return onWorkers(() -> doScheduleDeletion(request));
    }

    @Override
    public CompletableFuture<String> realNamePiOfPlayer(long playerId) {
        return onWorkers(() -> store.findRealNamePiByPlayerId(playerId));
    }

    ResetAccountRealNameResponse doResetRealName(ResetAccountRealNameRequest req) {
        boolean hasPlayer = req.getPlayerId() != 0;
        boolean hasAccount = !req.getAccount().isEmpty();
        if (hasPlayer == hasAccount || !boundedText(req.getOperator(), 64) || !boundedText(req.getReason(), 256)) {
            throw new IllegalArgumentException(
                    "exactly one of player_id / account, plus operator (<=64B) and reason (<=256B), are required");
        }
        String account = req.getAccount();
        if (account.isEmpty()) {
            Optional<PlayerRow> player = store.findPlayer(req.getPlayerId());
            if (player.isEmpty()) {
                return ResetAccountRealNameResponse.newBuilder()
                        .setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE))
                        .build();
            }
            account = player.get().getAccount();
        }
        PlayerStore.ResetRealNameOutcome outcome = store.resetAccountRealName(account);
        if (!outcome.found()) {
            return ResetAccountRealNameResponse.newBuilder()
                    .setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE))
                    .build();
        }
        if (antiAddictionGate != null && antiAddictionGate.realNameService() != null) {
            antiAddictionGate.realNameService().forgetPendingQuietly(account);
        }
        log.info("[real-name] admin reset account={} had_real_name={} operator={} reason={}",
                AccountDeletionService.maskAccount(account), outcome.hadRealName(), req.getOperator(), req.getReason());
        return ResetAccountRealNameResponse.newBuilder().setHadRealName(outcome.hadRealName()).build();
    }

    AccountDeletionResponse doScheduleDeletion(ScheduleAccountDeletionRequest req) {
        if (req.getPlayerId() == 0 || !boundedText(req.getOperator(), 64) || !boundedText(req.getReason(), 256)) {
            throw new IllegalArgumentException("player_id, operator (<=64B) and reason (<=256B) are required");
        }
        Optional<PlayerRow> player = store.findPlayer(req.getPlayerId());
        if (player.isEmpty()) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        }
        String account = player.get().getAccount();
        log.info("[account-deletion] admin schedule player={} account={} operator={} reason={}",
                Long.toUnsignedString(req.getPlayerId()), AccountDeletionService.maskAccount(account),
                req.getOperator(), req.getReason());
        return deletionService.request(account, AccountLifecycle.SOURCE_ADMIN, false, "");
    }

    private static boolean boundedText(String s, int maxBytes) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return s.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    private <T> CompletableFuture<T> onWorkers(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
