package com.game.team.store;

/**
 * {@link TeamStore#endMatch} 的结局（基线 store.go:510-524 EndMatchStop）。只有 {@link #RELEASED} 写了记录。
 */
public enum EndMatchStop {
    /** 已清锁（{@link EndMatchResult#commit()} 有值）。 */
    RELEASED,
    /** 记录已不存在，不写。 */
    RECORD_MISSING,
    /** 锁已被清或已重新加锁（token 不符），不写。 */
    TOKEN_MISMATCH,
    /** 锁按 Redis 时钟已自然过期，不写（token 字段可能还留着，等下次加锁覆盖）。 */
    LOCK_EXPIRED,
    /** 进程内单调截止（110 s）耗尽仍未提交（持续冲突或 Redis 故障），锁靠自然过期。 */
    DEADLINE,
    /** 退避等待被中断（进程正在停机），锁靠自然过期。Java 独有：基线进程退出不等 EndMatch（team-spec §5.2），这里只是把它表达出来。 */
    INTERRUPTED
}
