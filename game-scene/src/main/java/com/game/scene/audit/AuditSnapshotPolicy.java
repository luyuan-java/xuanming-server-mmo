package com.game.scene.audit;

import com.game.scene.world.PlayerSnapshots;
import java.util.Objects;

/**
 * 进退场审计快照开关与按 {@code player_id} 稳定抽样策略（L966，对齐 mmorpg {@code audit_snapshot_policy.h}）。
 *
 * <p>缺省全开全量（{@link #DEFAULT}：{@code loginEnabled=true, logoutEnabled=true, sampleRatePermille=1000}）。
 * 抽样使用 SplitMix64 终结哈希，同一玩家在同一采样率下稳定命中或不命中，不随重启或时间抖动。
 */
public record AuditSnapshotPolicy(boolean loginEnabled, boolean logoutEnabled, int sampleRatePermille) {

    public static final int MAX_PERMILLE = 1000;
    public static final AuditSnapshotPolicy DEFAULT = new AuditSnapshotPolicy(true, true, MAX_PERMILLE);

    public AuditSnapshotPolicy {
        if (sampleRatePermille < 0 || sampleRatePermille > MAX_PERMILLE) {
            throw new IllegalArgumentException("sampleRatePermille 必须在 [0, 1000] 内: " + sampleRatePermille);
        }
    }

    public boolean shouldCapture(PlayerSnapshots.Cause cause, long playerId) {
        Objects.requireNonNull(cause, "cause");
        boolean enabled = switch (cause) {
            case LOGIN -> loginEnabled;
            case LOGOUT -> logoutEnabled;
        };
        if (!enabled || sampleRatePermille == 0) {
            return false;
        }
        if (sampleRatePermille >= MAX_PERMILLE) {
            return true;
        }
        return Long.compareUnsigned(Long.remainderUnsigned(mix64(playerId), MAX_PERMILLE), sampleRatePermille) < 0;
    }

    private static long mix64(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        x ^= x >>> 31;
        return x;
    }
}
