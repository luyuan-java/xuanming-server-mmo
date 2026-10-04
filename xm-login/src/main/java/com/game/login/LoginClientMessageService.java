package com.game.login;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.login.account.AccountLogin;
import com.game.login.dispatch.ClientMessageDispatcher;
import com.game.login.dispatch.LoginWorkerPool;
import com.game.login.metrics.LoginMetrics;
import com.game.login.metrics.LoginMetrics.AbandonResult;
import com.game.player.store.PlayerStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ClientMessageService} 的 Dubbo 提供方（group {@code login}，Triple）。只做协议适配：
 * 客户端消息交给 {@link ClientMessageDispatcher}（在 login 工作线程池上执行，不占 Dubbo 线程），
 * 未送达的进场释放归属；会话离开「已绑定、没在游戏里」的窗口（应答里带进场指令、会话结束）时注销设备数登记。
 * 进场指令发出之后 gate / scene 仍可能进场失败（3023）回到大厅：那时会话不计数，直到它再建角 / 进游戏时重新登记
 * （见 {@link AccountLogin#renewDevice}）。
 */
@DubboService(group = DubboGroups.LOGIN)
public class LoginClientMessageService implements ClientMessageService {

    private static final Logger log = LoggerFactory.getLogger(LoginClientMessageService.class);

    private final ClientMessageDispatcher dispatcher;
    private final PlayerStore store;
    private final AccountLogin accountLogin;
    private final Executor executor;
    private final LoginMetrics metrics;

    /**
     * @param workers login 工作线程池（释放归属、注销设备是阻塞调用，不在 Dubbo 线程上做）
     * @param metrics 未送达进场的释放结果计数
     */
    @Autowired
    public LoginClientMessageService(ClientMessageDispatcher dispatcher, PlayerStore store, AccountLogin accountLogin,
                                     LoginWorkerPool workers, LoginMetrics metrics) {
        this(dispatcher, store, accountLogin, (Executor) workers, metrics);
    }

    /** 测试用：阻塞调用放到给定的执行器上。 */
    LoginClientMessageService(ClientMessageDispatcher dispatcher, PlayerStore store, AccountLogin accountLogin,
                              Executor executor, LoginMetrics metrics) {
        this.dispatcher = dispatcher;
        this.store = store;
        this.accountLogin = accountLogin;
        this.executor = executor;
        this.metrics = metrics;
    }

    @Override
    public CompletableFuture<ClientReply> handle(ClientCall call) {
        return dispatcher.dispatch(call).thenApply(reply -> {
            if (reply.getDirectivesList().stream().anyMatch(SessionDirective::hasEnterScene)) {
                leaveDeviceWindow(call.getSession());
            }
            return reply;
        });
    }

    /**
     * 会话结束（断线或主动离开）：注销设备数登记（尽力而为，兜底是登记的有效期）。玩家数据归属由 owner_epoch 围栏 + 释放标记保护，
     * 这里没有别的要清理；天然幂等。断线租约 / 短线重连随路线图 3.3。
     */
    @Override
    public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
        SessionContext session = event.getSession();
        log.info("会话结束 gate={} session={} account={} player={} voluntary={}",
                session.getGateNodeId(), session.getSessionId(), session.getAccount(), session.getPlayerId(),
                event.getVoluntary());
        leaveDeviceWindow(session);
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }

    /**
     * 投递到工作线程注销设备（不阻塞调用方；队列满就放弃，按登记有效期自愈）。gate 记的账号为空也要注销：登录应答可能没送到 gate，
     * 而 login 已经登记了（按会话反查记下的账号）。
     */
    private void leaveDeviceWindow(SessionContext session) {
        try {
            executor.execute(() -> accountLogin.leaveDeviceWindow(session));
        } catch (RejectedExecutionException e) {
            log.warn("login 工作队列已满，设备数登记按有效期自愈 account={} session={}", session.getAccount(),
                    session.getSessionId());
        }
    }

    /**
     * gate 确定这次进场的 {@code PlayerEnter} 从未送到任何 scene：没有写者会释放这份归属，这里代为释放
     * （带 epoch 围栏：已被新的夺权取代或已释放时什么也不做），玩家不必等租约过期就能再进游戏。
     * 失败只记日志（兜底是租约过期），永远正常应答。
     */
    @Override
    public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
        long playerId = event.getPlayerId();
        long epoch = event.getOwnerEpoch();
        SessionContext session = event.getSession();
        if (playerId == 0 || epoch == 0) {
            log.warn("忽略非法的未送达进场通知 gate={} session={} player={} epoch={}",
                    session.getGateNodeId(), session.getSessionId(), playerId, epoch);
            metrics.abandonedEnter(AbandonResult.INVALID);
            return CompletableFuture.completedFuture(Ack.getDefaultInstance());
        }
        CompletableFuture<Ack> done = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    boolean released = store.releaseOwnership(playerId, epoch);
                    metrics.abandonedEnter(released ? AbandonResult.RELEASED : AbandonResult.STALE);
                    log.info("进场未送达，释放归属 gate={} session={} player={} epoch={} 释放={}",
                            session.getGateNodeId(), session.getSessionId(), playerId, epoch, released);
                } catch (RuntimeException e) {
                    metrics.abandonedEnter(AbandonResult.FAILED);
                    log.warn("进场未送达，释放归属失败（等租约过期兜底） player={} epoch={}: {}", playerId, epoch, e.toString());
                } finally {
                    done.complete(Ack.getDefaultInstance());
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.abandonedEnter(AbandonResult.OVERLOADED);
            log.warn("login 工作队列已满，未送达进场的归属等租约过期 player={} epoch={}", playerId, epoch);
            done.complete(Ack.getDefaultInstance());
        }
        return done;
    }
}
