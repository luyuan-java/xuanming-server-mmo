package com.game.scene.audit;

import com.game.audit.proto.SnapshotCause;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.SceneClock;
import java.util.Objects;

/**
 * 玩家快照发往 Kafka（经 {@link AuditPipeline}）。逻辑线程上调用：只盖时刻与 zone、投递不可变草稿，序列化在审计线程上。
 */
public final class KafkaPlayerSnapshots implements PlayerSnapshots {

    private final AuditPipeline pipeline;
    private final SceneClock clock;
    private final int zoneId;
    private final AuditSnapshotPolicy policy;

    public KafkaPlayerSnapshots(AuditPipeline pipeline, SceneClock clock, int zoneId) {
        this(pipeline, clock, zoneId, AuditSnapshotPolicy.DEFAULT);
    }

    public KafkaPlayerSnapshots(AuditPipeline pipeline, SceneClock clock, int zoneId, AuditSnapshotPolicy policy) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneId = zoneId;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public void capture(PlayerSave save, Cause cause) {
        if (!policy.shouldCapture(cause, save.playerId())) {
            return;
        }
        pipeline.submitSnapshot(new AuditPipeline.SnapshotDraft(save, causeOf(cause), clock.epochMillis(), zoneId));
    }

    static SnapshotCause causeOf(Cause cause) {
        return switch (cause) {
            case LOGIN -> SnapshotCause.SNAPSHOT_LOGIN;
            case LOGOUT -> SnapshotCause.SNAPSHOT_LOGOUT;
        };
    }
}
