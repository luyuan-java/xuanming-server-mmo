package com.game.scene.world;

import java.time.Duration;
import java.util.Objects;

/**
 * 镜像 / 副本实例（批次 5.3，dungeon-mirror-spec §6）在 {@link SceneWorld} 上的装配与参数（{@code xm.scene.instance.*}，§7.2）。
 *
 * @param localNodeId        本场景节点号（取号结果的节点不是本节点 = 5.3 不该出现的跨节点放置，按拒绝处理，R11）
 * @param ids                实例取号（scene-manager {@code createInstance}）；null = 没装配：63 镜像分支受理后推 23 {1003}（同「没有 scene-manager 可用」，D9），
 *                           dev 管理口建副本回 1003
 * @param mirrorIdleTimeout  镜像空置多久进入回收宽限（缺省 30 s）；0 = 用 {@code idleTimeout}（同基线 MirrorIdleTimeoutSeconds ≤ 0 的回落）
 * @param idleTimeout        副本（及回落到它的镜像）空置多久进入回收宽限（缺省 300 s）；0 = 不自动回收
 * @param reclaimGrace       回收宽限（缺省 30 s，≥ 10 s）：宽限内不接本地新进入、在途进场到达即复活；宽限满且仍空、无在途才销毁
 * @param maxPerNode         本节点实例数上限（缺省 200）：63 镜像分支超限回 3005，建实例超限拒建（D19）
 * @param maxPerCreator      本节点上同一创建者的镜像数上限（缺省 3）：超限回 3005（D19）
 * @param resolveTimeout     取号在途（RESOLVING）槽的寿命基准（同 5.2 {@code xm.scene.switch-resolve-timeout}，槽在它之后再过 1 s 作废）
 * @param directoryChanged   实例建立 / 进入回收 / 复活 / 级联 / 销毁之后立即补发节点目录（{@code SceneDirectoryPublisher.requestPublishNow}；
 *                           逻辑线程上调用，不得阻塞：实现只标脏，连发合并成至多一次调度发布，可以每处变化各调一次）
 */
public record SceneInstances(int localNodeId, InstanceIds ids, Duration mirrorIdleTimeout, Duration idleTimeout,
                             Duration reclaimGrace, int maxPerNode, int maxPerCreator, Duration resolveTimeout,
                             Runnable directoryChanged) {

    public static final Duration DEFAULT_MIRROR_IDLE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(300);
    public static final Duration DEFAULT_RECLAIM_GRACE = Duration.ofSeconds(30);
    /** 回收宽限下限：不短于 5.1 软预占 TTL / login 归属等待的口径（§6.10 第 6 条）。 */
    public static final Duration MIN_RECLAIM_GRACE = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_PER_NODE = 200;
    public static final int MAX_MAX_PER_NODE = 10_000;
    public static final int DEFAULT_MAX_PER_CREATOR = 3;
    public static final int MAX_MAX_PER_CREATOR = 100;

    /** 不接实例取号（单测与本地装配的缺省）；回收、级联等节点本地规则照常按缺省参数。 */
    public static final SceneInstances DISABLED = new SceneInstances(0, null, DEFAULT_MIRROR_IDLE_TIMEOUT,
            DEFAULT_IDLE_TIMEOUT, DEFAULT_RECLAIM_GRACE, DEFAULT_MAX_PER_NODE, DEFAULT_MAX_PER_CREATOR,
            Duration.ofSeconds(4), () -> { });

    public SceneInstances {
        Objects.requireNonNull(mirrorIdleTimeout, "mirrorIdleTimeout");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(reclaimGrace, "reclaimGrace");
        Objects.requireNonNull(resolveTimeout, "resolveTimeout");
        Objects.requireNonNull(directoryChanged, "directoryChanged");
        validate(mirrorIdleTimeout, idleTimeout, reclaimGrace, maxPerNode, maxPerCreator);
        if (resolveTimeout.isNegative() || resolveTimeout.isZero()) {
            throw new IllegalArgumentException("取号超时必须为正: " + resolveTimeout);
        }
        if (ids != null && localNodeId <= 0) {
            throw new IllegalArgumentException("启用实例取号时本节点号必须为正: " + localNodeId);
        }
    }

    /** 配置校验（不满足即拒启，§7.2；{@code SceneNodeProperties} 绑定时同样调用）。 */
    public static void validate(Duration mirrorIdleTimeout, Duration idleTimeout, Duration reclaimGrace, int maxPerNode,
                                int maxPerCreator) {
        if (mirrorIdleTimeout.isNegative() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("xm.scene.instance.mirror-idle-timeout / idle-timeout 不能为负: "
                    + mirrorIdleTimeout + " / " + idleTimeout);
        }
        if (reclaimGrace.compareTo(MIN_RECLAIM_GRACE) < 0) {
            throw new IllegalArgumentException("xm.scene.instance.reclaim-grace 不能短于 " + MIN_RECLAIM_GRACE
                    + "（不短于软预占 TTL / login 归属等待）: " + reclaimGrace);
        }
        if (maxPerNode < 1 || maxPerNode > MAX_MAX_PER_NODE) {
            throw new IllegalArgumentException("xm.scene.instance.max-per-node 必须在 1.." + MAX_MAX_PER_NODE + " 之间: "
                    + maxPerNode);
        }
        if (maxPerCreator < 1 || maxPerCreator > MAX_MAX_PER_CREATOR) {
            throw new IllegalArgumentException("xm.scene.instance.max-per-creator 必须在 1.." + MAX_MAX_PER_CREATOR
                    + " 之间: " + maxPerCreator);
        }
    }

    public boolean enabled() {
        return ids != null;
    }

    /**
     * 这类实例空置多久进入回收宽限（纳秒）；0 = 不自动回收。镜像：{@code mirrorIdleTimeout > 0} 用它，否则回落 {@code idleTimeout}
     * （同基线 instance_lifecycle.go:58-63）；副本：{@code idleTimeout}。
     */
    long idleTimeoutNanos(SceneKind kind) {
        Duration timeout = kind == SceneKind.MIRROR && !mirrorIdleTimeout.isZero() ? mirrorIdleTimeout : idleTimeout;
        return timeout.toNanos();
    }

    /** 同样的参数换一个取号实现（测试用）。 */
    public SceneInstances withIds(int nodeId, InstanceIds instanceIds) {
        return new SceneInstances(nodeId, instanceIds, mirrorIdleTimeout, idleTimeout, reclaimGrace, maxPerNode,
                maxPerCreator, resolveTimeout, directoryChanged);
    }
}
