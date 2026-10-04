package com.game.team.store;

import com.game.common.deadline.Deadline.DependencyException;

/**
 * {@link TeamStore#readFree} 的结果（基线 store.go:286-313 返回 {@code (snap, healed, err)}）。
 *
 * <p>失败也带着 {@code healed}：前几轮已经治过孤儿索引、后面某轮才失败时，「已治愈」仍要记指标（基线 service.go:745-756 不管 err 都先看 healed）。
 * 服务层的映射：{@link Status#UNSTABLE} → 4029，{@link Status#FAILED} → 4030（team-spec §3.1 {@code readFailureCode}）。
 *
 * @param status   结果
 * @param snapshot {@link Status#OK} 时的快照（索引与记录同源；{@code record == null} 等价于无队）；其余为 null
 * @param healed   本次执行过并成功了 S_HEAL_ORPHAN（任何状态下都有意义）
 * @param error    {@link Status#FAILED} 时的依赖故障（Redis 错误 / 超出预算 / 回复或数据损坏）；其余为 null
 */
public record FreeRead(Status status, Snapshot snapshot, boolean healed, DependencyException error) {

    /** 自由读的结果。 */
    public enum Status {
        /** 读到了稳定的快照。 */
        OK,
        /** 索引在两次读之间持续变化，{@link TeamStore#FREE_READ_RETRIES} 轮用尽（基线 ErrUnstableRead，服务层回 4029）。 */
        UNSTABLE,
        /** 依赖故障（基线其余 error，服务层回 4030）。 */
        FAILED
    }

    static FreeRead ok(Snapshot snapshot, boolean healed) {
        return new FreeRead(Status.OK, snapshot, healed, null);
    }

    static FreeRead unstable(boolean healed) {
        return new FreeRead(Status.UNSTABLE, null, healed, null);
    }

    static FreeRead failed(boolean healed, DependencyException error) {
        return new FreeRead(Status.FAILED, null, healed, error);
    }

    public boolean ok() {
        return status == Status.OK;
    }
}
