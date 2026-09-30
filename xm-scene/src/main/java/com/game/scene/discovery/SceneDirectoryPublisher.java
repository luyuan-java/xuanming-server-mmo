package com.game.scene.discovery;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定期把本节点写进 Redis 节点目录（{@code SceneNodeInfo}，TTL 15s，每 5s 刷新），scene-manager 按它分配场景、
 * gate 按它连本节点。
 *
 * <p>场景列表的快照由调用方在场景逻辑线程上取（{@code snapshot}），发布（Redis I/O）在调度线程上做，
 * 不占逻辑线程。{@link #stop} 返回后不会再有发布落地，所以「先停再删条目」不会被迟到的发布复活。
 */
public final class SceneDirectoryPublisher {

    private static final Logger log = LoggerFactory.getLogger(SceneDirectoryPublisher.class);

    public static final Duration PERIOD = Duration.ofSeconds(5);
    public static final Duration TTL = Duration.ofSeconds(15);

    private final NodeDirectory<SceneNodeInfo> directory;
    private final SceneNodeInfo identity;
    private final Callable<List<SceneEntry>> snapshot;
    private ScheduledFuture<?> task;
    private boolean stopped;

    /**
     * @param identity 节点身份部分（zone / node_id / instance_id / link_host / link_port），scenes 每次发布时填
     * @param snapshot 取场景列表快照（实现负责切到场景逻辑线程并限时等待）
     */
    public SceneDirectoryPublisher(NodeDirectory<SceneNodeInfo> directory, SceneNodeInfo identity,
                                   Callable<List<SceneEntry>> snapshot) {
        this.directory = directory;
        this.identity = identity;
        this.snapshot = snapshot;
    }

    public synchronized void start(ScheduledExecutorService scheduler) {
        if (task == null && !stopped) {
            task = scheduler.scheduleWithFixedDelay(this::publishOnce, 0, PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void publishOnce() {
        List<SceneEntry> scenes;
        try {
            scenes = snapshot.call();
        } catch (Exception e) {
            log.warn("取场景快照失败，本轮不发布节点目录", e);
            return;
        }
        SceneNodeInfo info = identity.toBuilder().clearScenes().addAllScenes(scenes).build();
        synchronized (this) {
            if (stopped) {
                return;
            }
            try {
                directory.publish(identity.getZoneId(), identity.getNodeId(), info, TTL);
            } catch (RuntimeException e) {
                log.warn("发布场景节点目录失败，下一轮重试", e);
            }
        }
    }

    /**
     * 停止发布。{@code removeEntry=true}（正常停服）同时删除目录条目；节点号租约丢失时传 false：
     * 这个节点号可能已被别的实例占用，删条目会删掉别人的。
     */
    public synchronized void stop(boolean removeEntry) {
        if (stopped) {
            return;
        }
        stopped = true;
        if (task != null) {
            task.cancel(false);
        }
        if (removeEntry) {
            try {
                directory.remove(identity.getZoneId(), identity.getNodeId());
            } catch (RuntimeException e) {
                log.warn("删除场景节点目录条目失败（TTL 到期后自动消失）", e);
            }
        }
    }
}
