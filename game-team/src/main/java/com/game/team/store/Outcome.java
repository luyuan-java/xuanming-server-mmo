package com.game.team.store;

/** {@link TeamStore#mutate} 的结局（基线 store.go:129-143；服务层的映射见 team-spec §3.1）。 */
public enum Outcome {
    /** 已提交；{@link MutateResult#commit()} 有值。 */
    COMMITTED,
    /** 规则判定成功但无需写（幂等重放 / 无变化）。 */
    UNCHANGED,
    /** 业务拒绝、Lua 拒绝、冲突耗尽、提交前预算已过期；看 code / param。没有写入（{@link MutateResult#repairs()} 除外）。 */
    REJECTED,
    /** {@link Bind.Mode#CALLER} 未绑定：调用者已不在 expected 队伍。没有写入。 */
    NOT_BOUND,
    /** 绑定的队伍记录不存在。调用者索引仍指向它时已尝试 S_HEAL_ORPHAN（看 {@link MutateResult#healedOrphan()}）。 */
    RECORD_MISSING
}
