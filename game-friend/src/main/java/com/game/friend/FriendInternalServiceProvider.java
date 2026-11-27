package com.game.friend;

import com.game.api.CallerDeadline;
import com.game.api.DubboGroups;
import com.game.api.FriendInternalService;
import com.game.common.deadline.Deadline;
import com.game.friend.dispatch.FriendWorkerPool;
import com.game.friend.service.FriendService;
import com.game.proto.TipInfoMessage;
import com.game.proto.friend.BatchCheckSocialRelationsRequest;
import com.game.proto.friend.BatchCheckSocialRelationsResponse;
import com.game.table.CommonErrorTip;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * {@link FriendInternalService} 的 Dubbo 提供方（group {@code friend}）：
 * 入口在 Dubbo 线程上只读调用方截止并卸载到 {@link FriendWorkerPool}，绝不阻塞 Dubbo I/O 线程。
 */
@DubboService(group = DubboGroups.FRIEND)
public class FriendInternalServiceProvider implements FriendInternalService {

    private final FriendService friendService;
    private final Executor executor;
    private final long budgetMillis;

    public FriendInternalServiceProvider(FriendService friendService, FriendWorkerPool workerPool,
                                         FriendProperties props) {
        this(friendService, workerPool, props.requestBudget().toMillis());
    }

    FriendInternalServiceProvider(FriendService friendService, Executor executor, long budgetMillis) {
        this.friendService = Objects.requireNonNull(friendService, "friendService");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.budgetMillis = Math.max(100L, budgetMillis);
    }

    @Override
    public CompletableFuture<BatchCheckSocialRelationsResponse> batchCheckSocialRelations(
            BatchCheckSocialRelationsRequest request) {
        Optional<Deadline> caller = CallerDeadline.fromRpcContext();
        Deadline deadline = Deadline.within(budgetMillis, caller);
        CompletableFuture<BatchCheckSocialRelationsResponse> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (deadline.expired()) {
                    future.complete(unavailable("friend internal request expired in queue"));
                    return;
                }
                try {
                    future.complete(friendService.batchCheckSocialRelations(request, deadline));
                } catch (RuntimeException e) {
                    future.complete(unavailable("friend internal error"));
                }
            });
        } catch (RejectedExecutionException full) {
            future.complete(unavailable("friend worker pool overloaded"));
        }
        return future;
    }

    private static BatchCheckSocialRelationsResponse unavailable(String reason) {
        return BatchCheckSocialRelationsResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder()
                        .setId(CommonErrorTip.common_error.kServiceUnavailable_VALUE)
                        .addParameters(reason)
                        .build())
                .build();
    }
}
