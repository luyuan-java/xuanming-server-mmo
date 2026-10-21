package com.game.match.activity;

import com.game.api.DubboGroups;
import com.game.api.MatchInternalService;
import com.game.api.match.MatchRpcAttachments;
import com.game.common.deadline.Deadline;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchWorkers;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.proto.match.CancelActivityBattleRequest;
import com.game.proto.match.CancelActivityBattleResponse;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MatchInternalService} 的 Dubbo 提供方（group {@code match}，与 {@code ClientMessageService} 同一端口；match-spec §7.2）。只做协议适配：
 * 在 Dubbo 线程上取截止（调用方的剩余预算在附件 {@code game-budget-ms} 里，必须在这条线程上同步读；缺附件按本进程的请求预算），
 * 把请求投到 {@code match-worker}，判定全部在 {@link ActivityBattleService}。
 *
 * <p>不占消息号、不进客户端白名单：gate 只调 {@code ClientMessageService.handle}，客户端够不到这里；调用方鉴权靠 Dubbo 调用方 MAC
 * （{@code GAME_DUBBO_SECRET}，game-api 的过滤器）。所以没有基线「带会话的调用回 PermissionDenied」那条分支（M2）。
 *
 * <p>Start返回的 future <b>永不异常完成</b>：业务拒绝在 {@code reject} 里；工作池拒收、轮到执行时预算已用完、处理中出了未分类的异常，一律回
 * {@code INTERNAL}（offender = 0），不让调用悬着。
 * Cancel是安全消歧协议：完整正文签guild，回包签match且绑定原摘要；配置/鉴权/SQL/预算未知均异常完成，不伪造已取消证明。
 */
@DubboService(group = DubboGroups.MATCH)
public class MatchInternalServiceImpl implements MatchInternalService {

    private static final Logger log = LoggerFactory.getLogger(MatchInternalServiceImpl.class);

    private final ActivityBattleService service;
    private final MatchWorkers workers;
    private final long budgetMs;
    public static final String CANCEL_METHOD = "/match.MatchInternal/CancelActivityBattle";
    private final String cancellationSecret;
    private final ServiceCallSignatures cancellations;

    @org.springframework.beans.factory.annotation.Autowired
    public MatchInternalServiceImpl(ActivityBattleService service, MatchWorkers workers, MatchProperties props) {
        this(service,workers,props,System.getenv("MMORPG_MATCH_INTERNAL_SECRET_GUILD"));
    }
    MatchInternalServiceImpl(ActivityBattleService service, MatchWorkers workers, MatchProperties props, String cancellationSecret) {
        this.service = Objects.requireNonNull(service, "service");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.budgetMs = props.requestBudget().toMillis();
        this.cancellationSecret = cancellationSecret;
        this.cancellations = cancellationSecret == null || cancellationSecret.isBlank() ? null
            : new ServiceCallSignatures(Map.of("guild",cancellationSecret),System::currentTimeMillis);
    }

    @Override public CompletableFuture<CancelActivityBattleResponse> cancelActivityBattle(CancelActivityBattleRequest request) {
        Deadline deadline=MatchRpcAttachments.deadlineFromCall(budgetMs);
        CompletableFuture<CancelActivityBattleResponse> reply=new CompletableFuture<>();
        try {
            authorizeCancellation(request);
            workers.execute(() -> {
                try {
                    authorizeCancellation(request);
                    if(deadline.expired())throw new IllegalStateException("活动取消在队列中到期，原件仍待恢复");
                    var decision=service.cancel(request,deadline);
                    reply.complete(ServiceCallSignatures.sign(CANCEL_METHOD+"/reply",decision,"match",cancellationSecret,System.currentTimeMillis()));
                } catch(RuntimeException failure) { reply.completeExceptionally(failure); }
                catch(Error failure) { reply.completeExceptionally(failure); throw failure; }
            });
        } catch(RuntimeException failure) { reply.completeExceptionally(failure); }
        return reply;
    }
    private void authorizeCancellation(CancelActivityBattleRequest request) {
        if(cancellations==null||request==null||!"guild".equals(cancellations.verify(CANCEL_METHOD,request)))
            throw new SecurityException("活动取消调用方未配置或正文认证失败");
    }

    @Override
    public CompletableFuture<StartActivityBattleResponse> startActivityBattle(StartActivityBattleRequest request) {
        Deadline d = MatchRpcAttachments.deadlineFromCall(budgetMs);
        CompletableFuture<StartActivityBattleResponse> reply = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try {
                    if (d.expired()) {
                        log.warn("[match] 活动开战请求在工作队列里等过了预算（或到达时已过期），回 INTERNAL");
                        reply.complete(service.unavailable(request));
                    } else {
                        reply.complete(service.start(request, d));
                    }
                } catch (RuntimeException e) {
                    log.error("[match] 活动开战处理失败，回 INTERNAL", e);
                    reply.complete(service.unavailable(request));
                } catch (Error e) {
                    reply.completeExceptionally(e);
                    throw e;
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[match] 活动开战：工作队列已满，回 INTERNAL");
            reply.complete(service.unavailable(request));
        }
        return reply;
    }
}
