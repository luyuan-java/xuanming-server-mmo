package com.game.login;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
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
 * 会话结束通知只记日志并确认，未送达的进场释放归属。
 */
@DubboService(group = DubboGroups.LOGIN)
public class LoginClientMessageService implements ClientMessageService {

    private static final Logger log = LoggerFactory.getLogger(LoginClientMessageService.class);

    private final ClientMessageDispatcher dispatcher;
    private final PlayerStore store;
    private final Executor executor;
    private final LoginMetrics metrics;

    /**
     * @param workers login 工作线程池（释放归属是阻塞 MySQL 调用，不在 Dubbo 线程上做）
     * @param metrics 未送达进场的释放结果计数
     */
    @Autowired
    public LoginClientMessageService(ClientMessageDispatcher dispatcher, PlayerStore store, LoginWorkerPool workers,
                                     LoginMetrics metrics) {
        this(dispatcher, store, (Executor) workers, metrics);
    }

    /** 测试用：阻塞调用放到给定的执行器上。 */
    LoginClientMessageService(ClientMessageDispatcher dispatcher, PlayerStore store, Executor executor, LoginMetrics metrics) {
        this.dispatcher = dispatcher;
        this.store = store;
        this.executor = executor;
        this.metrics = metrics;
    }

    @Override
    public CompletableFuture<ClientReply> handle(ClientCall call) {
        return dispatcher.dispatch(call);
    }

    /**
     * Java 版 login 不持有登录态（会话状态在 gate，玩家数据归属由 owner_epoch 围栏 + 释放标记保护），所以会话结束没有要清理的状态，
     * 天然幂等。断线租约 / 短线重连不在本批。
     */
    @Override
    public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
        SessionContext session = event.getSession();
        log.info("会话结束 gate={} session={} account={} player={} voluntary={}",
                session.getGateNodeId(), session.getSessionId(), session.getAccount(), session.getPlayerId(),
                event.getVoluntary());
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
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
