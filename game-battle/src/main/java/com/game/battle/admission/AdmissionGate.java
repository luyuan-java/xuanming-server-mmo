package com.game.battle.admission;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 建房准入闸（基线 {@code cpp/nodes/battle/battle_admission_gate.h}；battle-node-spec §6.1、§7.8、§7.10、§7.11）：进程自己的
 * 「现在接不接新房间」。只朝一个方向走：{@code NOT_STARTED → OPEN → CLOSED}，{@code CLOSED} 是终态，停机已开始的节点不能再被打开。
 *
 * <p>用法（契约）：
 * <ul>
 *   <li>{@code BattleNode} 启动最后一步在逻辑线程上 {@link #open()}，然后才发布目录（先开闸、后进目录）；</li>
 *   <li>停机与租约丢失都在逻辑线程上 {@link #close()}；停机时「关闸 → 作废全部房间」必须在<b>同一个</b>逻辑任务里，
 *       这样排在它之后的建房任务在逻辑线程上复核时必然看到 CLOSED；</li>
 *   <li>Dubbo 线程先读一次（第一道），投递进逻辑线程后再复核一次（第二道，{@code closed_in_loop}）；任一不是 OPEN 即
 *       {@code NOT_ALLOCATABLE}，发生在任何副作用之前。</li>
 * </ul>
 *
 * <p>线程：无锁，任何线程可读写（{@link AtomicReference} 的 CAS 与 volatile 语义）。不打日志。
 */
public final class AdmissionGate {

    private final AtomicReference<AdmissionPhase> phase = new AtomicReference<>(AdmissionPhase.NOT_STARTED);

    /**
     * {@code NOT_STARTED → OPEN}。返回是否真的打开了：已打开（重复调用）或已关闭（停机先于启动完成）时返回 false，阶段不变。
     */
    public boolean open() {
        return phase.compareAndSet(AdmissionPhase.NOT_STARTED, AdmissionPhase.OPEN);
    }

    /** 任意阶段 → {@code CLOSED}（终态，幂等）。 */
    public void close() {
        phase.set(AdmissionPhase.CLOSED);
    }

    public AdmissionPhase phase() {
        return phase.get();
    }

    public boolean isOpen() {
        return phase.get() == AdmissionPhase.OPEN;
    }
}
