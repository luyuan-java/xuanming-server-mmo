package com.game.scene.world;

import java.time.Duration;
import java.util.Objects;

/**
 * 跨 zone 传送（226，批次 5.4，zone-travel-spec §5.5）在 {@link SceneWorld} 上的装配，仿 {@link CrossNodeSwitch}。
 * 自带选目标期限与墓碑存活时长：226 不依赖跨节点换图（5.2）的装配记录是否启用。
 *
 * @param localZoneId      本节点所属 zone（226 的目标 zone 为 0 或等于它 → 3024）
 * @param targets          选目标（scene-manager）；null = 不启用：226 同步回 3027
 *                         （对应基线「没有可达的 scene-manager」的同步 3027，player_lifecycle.cpp:2870-2874）
 * @param teamChecks       受理前的在队检查；启用时不能为 null
 * @param teamCheckTimeout 在队检查的上限（{@code xm.scene.travel.team-check-timeout}）；延迟应答的槽（PRECHECK）在它之后再过 1 s 作废，
 *                         所以 226 的应答上界是它加 1 s
 * @param resolveTimeout   选目标的本地兜底超时（与 63 共用 {@code xm.scene.switch-resolve-timeout}）；RESOLVING 槽在它之后再过 1 s 作废
 * @param tombstoneTtl     交出墓碑的存活时长（与 63 共用 {@code xm.scene.transfer-tombstone-ttl}）
 */
public record ZoneTravel(int localZoneId, TravelTargets targets, TeamChecks teamChecks, Duration teamCheckTimeout,
                         Duration resolveTimeout, Duration tombstoneTtl) {

    /** 不启用（单测与本地装配的缺省）：226 同步回 3027。时限取各配置键的缺省值（2 s / 4 s / 30 s）。 */
    public static final ZoneTravel DISABLED = new ZoneTravel(0, null, null, Duration.ofSeconds(2), Duration.ofSeconds(4),
            Duration.ofSeconds(30));

    public ZoneTravel {
        Objects.requireNonNull(teamCheckTimeout, "teamCheckTimeout");
        Objects.requireNonNull(resolveTimeout, "resolveTimeout");
        Objects.requireNonNull(tombstoneTtl, "tombstoneTtl");
        if (targets != null) {
            if (localZoneId <= 0) {
                throw new IllegalArgumentException("启用跨 zone 传送时本 zone 号必须为正: " + localZoneId);
            }
            Objects.requireNonNull(teamChecks, "启用跨 zone 传送时必须有在队检查");
        }
        if (teamCheckTimeout.isNegative() || teamCheckTimeout.isZero() || resolveTimeout.isNegative()
                || resolveTimeout.isZero() || tombstoneTtl.isNegative() || tombstoneTtl.isZero()) {
            throw new IllegalArgumentException("在队检查上限、选目标超时与墓碑存活时长必须为正: " + teamCheckTimeout + " / "
                    + resolveTimeout + " / " + tombstoneTtl);
        }
    }

    public boolean enabled() {
        return targets != null;
    }
}
