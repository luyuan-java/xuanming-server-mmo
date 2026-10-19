package com.game.inbox.rpc;

import com.game.api.DubboGroups;
import com.game.api.PlayerInboxService;
import com.game.common.deadline.Deadline;
import com.game.inbox.InboxProperties;
import com.game.inbox.rules.InboxTips;
import com.game.inbox.store.InboxStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.AckPlayerInboxResponse;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxResponse;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PlayerInboxService} 的提供方（group {@link DubboGroups#INBOX}；调用方是 scene）。阻塞 JDBC 只在本类自己的有界工作池上执行，
 * 不占 Dubbo 线程；池满直接回 kPlayerInboxStoreUnavailable（scene 按服务端故障退避，不计协议异常）。库 / 玩家表故障同样回它（只用 tip，设计 §1.4），
 * 回包照样回显 player_id / domain / correlation_id，scene 据此关联。调用方鉴权（{@code GAME_DUBBO_SECRET}）由 game-api 的 SPI 过滤器完成。
 */
@DubboService(group = DubboGroups.INBOX, interfaceClass = PlayerInboxService.class)
public final class PlayerInboxProvider implements PlayerInboxService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlayerInboxProvider.class);

    private final InboxStore store;
    private final long budgetMs;
    private final ThreadPoolExecutor workers;

    public PlayerInboxProvider(InboxStore store, InboxProperties properties) {
        this.store = store;
        this.budgetMs = properties.requestBudget().toMillis();
        this.workers = new ThreadPoolExecutor(properties.workerThreads(), properties.workerThreads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.workerQueueCapacity()), Thread.ofPlatform().name("inbox-rpc-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public CompletableFuture<FetchPlayerInboxResponse> fetchItems(FetchPlayerInboxRequest request) {
        String peer = peer();
        CompletableFuture<FetchPlayerInboxResponse> response = new CompletableFuture<>();
        submit(response, () -> {
            try {
                response.complete(store.fetch(Deadline.after(budgetMs), request, peer));
            } catch (Exception e) {
                log.error("[PlayerInbox] Fetch 失败 player={} domain={} scene_node_id={} peer={}", Long.toUnsignedString(request.getPlayerId()),
                        request.getDomainValue(), Integer.toUnsignedString(request.getSceneNodeId()), peer, e);
                response.complete(fetchFault(request));
            }
        }, () -> fetchFault(request));
        return response;
    }

    @Override
    public CompletableFuture<AckPlayerInboxResponse> ackItems(AckPlayerInboxRequest request) {
        String peer = peer();
        CompletableFuture<AckPlayerInboxResponse> response = new CompletableFuture<>();
        submit(response, () -> {
            try {
                response.complete(store.ack(Deadline.after(budgetMs), request, peer));
            } catch (Exception e) {
                log.error("[PlayerInbox] Ack 失败 player={} domain={} scene_node_id={} peer={}", Long.toUnsignedString(request.getPlayerId()),
                        request.getDomainValue(), Integer.toUnsignedString(request.getSceneNodeId()), peer, e);
                response.complete(ackFault(request));
            }
        }, () -> ackFault(request));
        return response;
    }

    private <T> void submit(CompletableFuture<T> response, Runnable work, java.util.function.Supplier<T> overloaded) {
        try {
            workers.execute(work);
        } catch (RejectedExecutionException e) {
            log.warn("[PlayerInbox] 工作池已满，回 StoreUnavailable");
            response.complete(overloaded.get());
        }
    }

    private static FetchPlayerInboxResponse fetchFault(FetchPlayerInboxRequest request) {
        return FetchPlayerInboxResponse.newBuilder().setPlayerId(request.getPlayerId()).setDomainValue(request.getDomainValue())
                .setCorrelationId(request.getCorrelationId()).setErrorMessage(TipInfoMessage.newBuilder().setId(InboxTips.STORE_UNAVAILABLE))
                .build();
    }

    private static AckPlayerInboxResponse ackFault(AckPlayerInboxRequest request) {
        return AckPlayerInboxResponse.newBuilder().setPlayerId(request.getPlayerId()).setDomainValue(request.getDomainValue())
                .setCorrelationId(request.getCorrelationId()).setErrorMessage(TipInfoMessage.newBuilder().setId(InboxTips.STORE_UNAVAILABLE))
                .build();
    }

    /** 调用方地址（只进日志；内网可信模型下唯一不可伪造的部分）。 */
    private static String peer() {
        try {
            String remote = RpcContext.getServiceContext().getRemoteAddressString();
            return remote == null || remote.isBlank() ? "unknown" : remote;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
