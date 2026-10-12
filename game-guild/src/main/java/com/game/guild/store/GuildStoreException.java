package com.game.guild.store;

/**
 * 帮会存储的内部错误（基线里那些「原错误照回」的非哨兵错误，guild-spec §2.7 最后一行）：哨兵行 / 状态行缺失、写入自检失败、
 * 双存储矛盾。都不是忙错误——重试多少次结局都一样——上层一律定性为信封 1003 并记 ERROR，<b>绝不</b>映射成 14021。
 * SQL 本身的故障走 {@link com.game.common.deadline.Deadline.DependencyException}。
 */
public final class GuildStoreException extends RuntimeException {

    /** 错误类别（只用于日志与测试断言；客户端所见都是信封 1003）。 */
    public enum Kind {
        /** 全局插入守卫哨兵行 guild_player_state(0) 缺失：启动步骤漏了（errGlobalInsertGuardMissing，guild_manage_repo.go:836-839）。 */
        GLOBAL_INSERT_GUARD_MISSING,
        /** 事务内点锁玩家状态行时行不存在：事务外建行那步被跳过了（errPlayerStateRowMissing，:811-827）。 */
        PLAYER_STATE_ROW_MISSING,
        /** 写入自检失败：期望恰好改动 1 行（execExactlyOneRow，:1154-1170），或删帮会删到多于 1 行（:2341-2342）。 */
        ROW_COUNT_MISMATCH,
        /** 不变量被破坏：guild 行锁下成员集合变了（:2379-2381）、转让后帮主人数不是 1（:1570-1576）。 */
        INVARIANT_BROKEN,
        /**
         * 4.5：分配 seq 时 guild_player_op_seq 行不存在（assetop ErrSeqRowMissing，seq.go:181-185）。预留事务刚在同一事务里按需建过行，
         * 走到这里只可能是有人在事务外删了它。
         */
        SEQ_ROW_MISSING,
        /** 4.5：seq 行的纪元为 0（ErrSeqRowCorrupt，seq.go:187-189）：只可能来自 bug 或人工改库；继续分配会让 scene 分不清纪元。 */
        SEQ_ROW_CORRUPT
    }

    private final Kind kind;

    public GuildStoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
