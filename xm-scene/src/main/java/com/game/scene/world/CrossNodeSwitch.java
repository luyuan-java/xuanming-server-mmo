package com.game.scene.world;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 跨节点换图（批次 5.2，scene-handoff-spec §5）在 {@link SceneWorld} 上的装配。
 *
 * @param localNodeId    本场景节点号（选目标的结果指向本节点却不在本节点 = 目录过时，按拒绝处理，绝不交给自己）
 * @param targets        选目标（scene-manager）；null = 不启用跨节点换图：63 的远端去向照 5.1 回 3023
 * @param renewSoon      交出进场成功后立即单独续约一次新 epoch（{@code OwnerLeaseRenewer.renewSoon}）；不阻塞、可在逻辑线程上调
 * @param resolveTimeout 选目标的本地兜底超时（{@code xm.scene.switch-resolve-timeout}）；RESOLVING 槽在它之后再过 1 s 作废（兜底，正常由结果回调结束）
 * @param tombstoneTtl   交出墓碑的存活时长（{@code xm.scene.transfer-tombstone-ttl}）：与 PlayerTransfer 在链路上交叉的 PlayerLeave 靠它补写位置
 */
public record CrossNodeSwitch(int localNodeId, RemoteSwitchTargets targets, Consumer<OwnedPlayer> renewSoon,
                              Duration resolveTimeout, Duration tombstoneTtl) {

    /** 不启用（单测与本地装配的缺省）：63 的远端去向回 3023，与 5.1 相同。 */
    public static final CrossNodeSwitch DISABLED = new CrossNodeSwitch(0, null, owned -> { },
            Duration.ofSeconds(4), Duration.ofSeconds(30));

    public CrossNodeSwitch {
        Objects.requireNonNull(renewSoon, "renewSoon");
        Objects.requireNonNull(resolveTimeout, "resolveTimeout");
        Objects.requireNonNull(tombstoneTtl, "tombstoneTtl");
        if (targets != null && localNodeId <= 0) {
            throw new IllegalArgumentException("启用跨节点换图时本节点号必须为正: " + localNodeId);
        }
        if (resolveTimeout.isNegative() || resolveTimeout.isZero() || tombstoneTtl.isNegative() || tombstoneTtl.isZero()) {
            throw new IllegalArgumentException("选目标超时与墓碑存活时长必须为正: " + resolveTimeout + " / " + tombstoneTtl);
        }
    }

    public boolean enabled() {
        return targets != null;
    }
}
