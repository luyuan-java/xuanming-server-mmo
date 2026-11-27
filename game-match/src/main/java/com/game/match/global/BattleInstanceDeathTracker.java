package com.game.match.global;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗实例失活取证与按实例复核器（对齐主仓 {@code docs/design/battle-instance-loss-early-abort.md §4}）：
 * <ul>
 *   <li>参战者补签问不到原实例时把该局标为可疑；</li>
 *   <li>单次观察绝不作数，同一实例须至少两次独立观察且相隔不少于 {@value #MIN_CONFIRM_INTERVAL_MS}ms（盖住 15s 排空 + 10s 终局提交期限 + 余量）；</li>
 *   <li>按实例记、各局共用复核记录；只要原实例亲口应答即撤销怀疑与证据；</li>
 *   <li>截止前仅对原实例做只读探查，绝不提前发 {@code DestroyBattle} 立拒收墓碑。</li>
 * </ul>
 */
public final class BattleInstanceDeathTracker {

    /** 两次独立失活观察的最小间隔（30 秒）。 */
    public static final long MIN_CONFIRM_INTERVAL_MS = 30_000L;

    /** 单次可认可的失活观察证据类型。 */
    public enum EvidenceKind {
        /** 原地址上应答的是另一个实例。 */
        DIFFERENT_INSTANCE_ANSWERED,
        /** 原端口当场拒连且注册中心已摘除。 */
        CONNECTION_REFUSED_AND_DEREGISTERED,
        /** 集群调度层已没有任何存活实例持有原地址。 */
        NO_LIVE_ENDPOINT_FOR_IP
    }

    /** 对原实例的只读探查结果（截止前只许只读查询）。 */
    public sealed interface ProbeOutcome permits ProbeOutcome.OriginalAlive, ProbeOutcome.ObservedDead, ProbeOutcome.Inconclusive {
        record OriginalAlive() implements ProbeOutcome {}
        record ObservedDead(EvidenceKind kind) implements ProbeOutcome {
            public ObservedDead {
                Objects.requireNonNull(kind, "kind");
            }
        }
        record Inconclusive() implements ProbeOutcome {}
    }

    private record InstanceEvidence(long firstObservedAtMs, long lastConfirmedAtMs, boolean confirmed, EvidenceKind lastKind) {
    }

    private final long confirmIntervalMs;
    private final Map<String, InstanceEvidence> evidenceByInstance = new ConcurrentHashMap<>();
    private final Map<Long, String> suspectBattleToInstance = new ConcurrentHashMap<>();

    public BattleInstanceDeathTracker() {
        this(MIN_CONFIRM_INTERVAL_MS);
    }

    public BattleInstanceDeathTracker(long confirmIntervalMs) {
        if (confirmIntervalMs < MIN_CONFIRM_INTERVAL_MS) {
            throw new IllegalArgumentException("复核间隔不得小于 30 秒: " + confirmIntervalMs);
        }
        this.confirmIntervalMs = confirmIntervalMs;
    }

    public long confirmIntervalMs() {
        return confirmIntervalMs;
    }

    /** 补签问不到原实例时把对局标为可疑（仅登记，不立即判死）。 */
    public void markSuspect(long battleId, String instanceId) {
        if (battleId == 0 || instanceId == null || instanceId.isEmpty()) {
            return;
        }
        suspectBattleToInstance.putIfAbsent(battleId, instanceId);
    }

    /** 结案或取消跟踪时移除对局嫌疑记录。 */
    public void clearBattle(long battleId) {
        suspectBattleToInstance.remove(battleId);
    }

    /** 原实例亲口应答：立刻撤销该实例的失活证据与挂靠嫌疑。 */
    public void recordAlive(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }
        evidenceByInstance.remove(instanceId);
        suspectBattleToInstance.entrySet().removeIf(e -> instanceId.equals(e.getValue()));
    }

    /**
     * 记一次对 {@code instanceId} 的失活观察。
     * 同一实例首次观察只登记时间返回 {@code false}；第二次及之后若与首次相隔不少于 {@link #confirmIntervalMs()} 则确认失活并返回 {@code true}。
     */
    public boolean recordObservation(String instanceId, EvidenceKind kind, long nowMs) {
        if (instanceId == null || instanceId.isEmpty()) {
            return false;
        }
        Objects.requireNonNull(kind, "kind");
        InstanceEvidence updated = evidenceByInstance.compute(instanceId, (key, existing) -> {
            if (existing == null) {
                return new InstanceEvidence(nowMs, nowMs, false, kind);
            }
            if (existing.confirmed()) {
                return new InstanceEvidence(existing.firstObservedAtMs(), Math.max(existing.lastConfirmedAtMs(), nowMs), true, kind);
            }
            long elapsed = nowMs - existing.firstObservedAtMs();
            if (elapsed >= confirmIntervalMs) {
                return new InstanceEvidence(existing.firstObservedAtMs(), nowMs, true, kind);
            }
            return new InstanceEvidence(existing.firstObservedAtMs(), Math.max(existing.lastConfirmedAtMs(), nowMs), false, kind);
        });
        return updated.confirmed();
    }

    public boolean isConfirmedDead(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return false;
        }
        InstanceEvidence e = evidenceByInstance.get(instanceId);
        return e != null && e.confirmed();
    }

    public Set<Long> suspectBattleIds() {
        return Set.copyOf(suspectBattleToInstance.keySet());
    }
}
