package com.game.scene.inbox;

import com.game.api.SceneInboxService;
import com.game.api.proto.SceneInboxNotify;
import com.game.api.proto.SceneInboxNotifyReply;
import com.game.api.proto.SceneInboxNotifyStatus;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SceneInboxService} 的跨进程提供方（与资产通道同一个端口、group {@code scene-inbox}、{@code register = false}）：game-inbox 的在线提醒。
 * 在 Dubbo 线程上做：实例核对（{@code target_instance_id} 与本进程不符 → NOT_HERE，等价基线 Kafka 的 target_instance_id 过滤）→ 排队名额
 * （超出回 OVERLOADED，零副作用）→ 把「提前检查」投进场景逻辑线程后立即回 ACCEPTED（不等逻辑线程执行：提醒只是提醒）。逻辑线程已停 → 异常完成
 * （调用方按传输失败退避）。线程安全。
 */
public final class SceneInboxProvider implements SceneInboxService {

    private com.game.scene.admin.SceneMaintenanceController.Gate maintenance=new com.game.scene.admin.SceneMaintenanceController.Gate();
    public SceneInboxProvider withMaintenance(com.game.scene.admin.SceneMaintenanceController.Gate gate){this.maintenance=Objects.requireNonNull(gate);return this;}

    private static final Logger log = LoggerFactory.getLogger(SceneInboxProvider.class);

    /** 排进逻辑线程、还没执行的提醒上限（提醒量 = 入箱量，远小于它；防的是异常洪峰占满逻辑线程的队列）。 */
    static final int MAX_QUEUED = 1024;

    private final Supplier<InboxService> service;
    private final String instanceId;
    private final Executor logic;
    private final Semaphore queued = new Semaphore(MAX_QUEUED);

    /**
     * @param service    逻辑线程上的消费端（节点没启动好时为 null）
     * @param instanceId 本进程实例 id（节点目录里的那个）
     * @param logic      投递到场景逻辑线程
     */
    public SceneInboxProvider(Supplier<InboxService> service, String instanceId, Executor logic) {
        this.service = Objects.requireNonNull(service, "service");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    @Override
    public CompletableFuture<SceneInboxNotifyReply> notifyInbox(SceneInboxNotify request) {
        InboxService svc = service.get();
        if (svc == null || request == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(svc == null ? "scene inbox not ready" : "empty request"));
        }
        if (!instanceId.equals(request.getTargetInstanceId())) {
            return CompletableFuture.completedFuture(reply(SceneInboxNotifyStatus.SCENE_INBOX_NOTIFY_NOT_HERE));
        }
        if (!queued.tryAcquire()) {
            log.warn("[PlayerInbox] 在线提醒排队已满，回过载 player={}", Long.toUnsignedString(request.getPlayerId()));
            return CompletableFuture.completedFuture(reply(SceneInboxNotifyStatus.SCENE_INBOX_NOTIFY_OVERLOADED));
        }
        final com.game.scene.admin.SceneMaintenanceController.Lease admitted;
        try{admitted=maintenance.admit(request.toByteArray());}catch(RejectedExecutionException closed){queued.release();return CompletableFuture.failedFuture(closed);}
        long playerId = request.getPlayerId();
        int domain = request.getDomain();
        try {
            logic.execute(() -> {
                try{svc.onNotify(playerId,domain);admitted.complete(null);}
                catch(RuntimeException failure){admitted.complete(failure);throw failure;}
                finally{queued.release();}
            });
        } catch (RejectedExecutionException e) {
            admitted.complete(e);queued.release();
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(reply(SceneInboxNotifyStatus.SCENE_INBOX_NOTIFY_ACCEPTED));
    }

    private static SceneInboxNotifyReply reply(SceneInboxNotifyStatus status) {
        return SceneInboxNotifyReply.newBuilder().setStatus(status).build();
    }
}
