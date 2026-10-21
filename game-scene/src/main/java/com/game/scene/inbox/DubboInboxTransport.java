package com.game.scene.inbox;

import com.game.api.DubboGroups;
import com.game.api.inbox.InboxCallContract;
import com.game.api.PlayerInboxService;
import com.game.api.rpc.NodeRpcClients;
import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.AckPlayerInboxResponse;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生产用的 {@link InboxTransport}：直连 {@code game.scene.inbox.url}（缺省 {@code tri://127.0.0.1:20918}，game-inbox 的 Dubbo 端口）的编程式引用
 * （game-api 的 {@link NodeRpcClients}：自己的隔离 Dubbo 模型、{@code retries = 0}——Fetch / Ack 都改服务端状态，重发会打乱投递计数与指标；
 * {@code check = false}——game-inbox 不在不影响 scene 启动；建引用不在逻辑线程上做）。连接鉴权由SPI过滤器带上，
 * 业务请求另以scene角色签完整PB；完整准入是否已登记由Inbox同P复核，签名不代替SQL授权。
 * 结果经 {@code logic} 投递回场景逻辑线程，恰好一次（本地兜底超时由 {@link NodeRpcClients#call} 给出）。线程安全。
 */
public final class DubboInboxTransport implements InboxTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DubboInboxTransport.class);

    /** Dubbo 应用名（隔离模型、直连 game-inbox；进对端 URL 便于认出调用方）。 */
    static final String APPLICATION = "game-scene-inbox";
    /** 单次调用的上限：大于 game-inbox 提供方超时 3.5s。 */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(4);

    private final NodeRpcClients<PlayerInboxService> clients;
    private final NodeRpcClients.Target target;
    private final Executor logic;
    private final InboxCallSigner signer;

    DubboInboxTransport(NodeRpcClients<PlayerInboxService> clients, NodeRpcClients.Target target, Executor logic, InboxCallSigner signer) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.target = Objects.requireNonNull(target, "target");
        this.logic = Objects.requireNonNull(logic, "logic");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    /** @param url {@code tri://host:port}（只支持直连，同 scene-manager 的选目标客户端） */
    public static DubboInboxTransport direct(String url, Executor logic) {
        InboxCallSigner signer = new InboxCallSigner(System.getenv(InboxCallContract.SECRET_ENV), System::currentTimeMillis);
        URI uri = URI.create(Objects.requireNonNull(url, "url").trim());
        if (!"tri".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
            throw new IllegalArgumentException("game.scene.inbox.url 必须是 tri://host:port: " + url);
        }
        NodeRpcClients<PlayerInboxService> clients = new NodeRpcClients<>(APPLICATION, PlayerInboxService.class, DubboGroups.INBOX, CALL_TIMEOUT,
                "scene-inbox-connect");
        return new DubboInboxTransport(clients, new NodeRpcClients.Target(uri.getHost(), uri.getPort(), ""), logic, signer);
    }

    @Override
    public void fetch(FetchPlayerInboxRequest request, BiConsumer<FetchPlayerInboxResponse, Throwable> onDone) {
        call(service -> service.fetchItems(signer.sign(request)), onDone, request.getPlayerId());
    }

    @Override
    public void ack(AckPlayerInboxRequest request, BiConsumer<AckPlayerInboxResponse, Throwable> onDone) {
        call(service -> service.ackItems(signer.sign(request)), onDone, request.getPlayerId());
    }

    private <R> void call(Function<PlayerInboxService, CompletableFuture<R>> invocation, BiConsumer<R, Throwable> onDone, long playerId) {
        CompletableFuture<R> future;
        try {
            future = clients.call(target, CALL_TIMEOUT, invocation);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((response, failure) -> {
            try {
                logic.execute(() -> onDone.accept(response, failure));
            } catch (RejectedExecutionException e) {
                log.debug("[PlayerInbox] 场景逻辑线程已停止，丢弃收件箱回包 player={}", Long.toUnsignedString(playerId));
            }
        });
    }

    @Override
    public void close() {
        clients.close();
    }
}
