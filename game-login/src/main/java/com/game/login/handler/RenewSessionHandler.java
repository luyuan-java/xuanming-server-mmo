package com.game.login.handler;

import com.game.api.proto.CloseSession;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.login.account.AccountLogin;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.login.hosted.HostedHomingReconciler;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.RenewSessionRequest;
import com.game.proto.login.RenewSessionResponse;
import com.game.table.LoginErrorTip;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RenewSession（394，{@code CLIENT_RPC_SERVER_ONLY}，仅由 gate 定期合成调用）：
 * 续期大厅设备登记与防沉迷会话账本租约；未成年人越过可玩截止时刻时下发 {@link CloseSession}（2049）兜底踢线；
 * 续期成功后按需对离线托管角色执行归位对账（无节点持有时由角色所在 zone 放回世界）。
 */
public final class RenewSessionHandler implements ClientMessageHandler<RenewSessionRequest> {

    private static final Logger log = LoggerFactory.getLogger(RenewSessionHandler.class);

    private final AccountLogin accountLogin;
    private final Duration sessionTtl;
    private volatile HostedHomingReconciler hostedHomingReconciler;

    public RenewSessionHandler(AccountLogin accountLogin, Duration sessionTtl) {
        this.accountLogin = Objects.requireNonNull(accountLogin, "accountLogin");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
    }

    public RenewSessionHandler withHostedHomingReconciler(HostedHomingReconciler reconciler) {
        this.hostedHomingReconciler = reconciler;
        return this;
    }

    @Override
    public String methodName() {
        return "RenewSession";
    }

    @Override
    public Class<RenewSessionRequest> requestType() {
        return RenewSessionRequest.class;
    }

    @Override
    public Class<RenewSessionResponse> responseType() {
        return RenewSessionResponse.class;
    }

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, RenewSessionRequest request) {
        AccountLogin.RenewOutcome outcome = accountLogin.renewSession(session, sessionTtl);
        if (outcome.kickMinor()) {
            RenewSessionResponse resp = RenewSessionResponse.newBuilder().setRenewed(false).build();
            SessionDirective close = SessionDirective.newBuilder()
                    .setCloseSession(CloseSession.newBuilder()
                            .setTipId(LoginErrorTip.login_error.kLoginMinorPlayTimeOver_VALUE))
                    .build();
            return CompletableFuture.completedFuture(HandlerReply.of(resp, close));
        }
        RenewSessionResponse.Builder resp = RenewSessionResponse.newBuilder().setRenewed(outcome.renewed());
        if (outcome.renewed()) {
            resp.setRenewAfterSeconds(outcome.renewAfterSeconds());
            HostedHomingReconciler reconciler = this.hostedHomingReconciler;
            if (reconciler != null && session.getPlayerId() != 0 && session.getOwnerRevision() != 0) {
                try {
                    reconciler.reconcileOnRenew(session.getPlayerId(), session.getSessionId(),
                            session.getOwnerRevision(), Math.floorDiv(System.currentTimeMillis(), 1000L));
                } catch (RuntimeException ex) {
                    log.warn("hosted homing reconcile failed player_id={}",
                            Long.toUnsignedString(session.getPlayerId()), ex);
                }
            }
        }
        return CompletableFuture.completedFuture(HandlerReply.of(resp.build()));
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.of(RenewSessionResponse.newBuilder().setRenewed(false).build());
    }
}
