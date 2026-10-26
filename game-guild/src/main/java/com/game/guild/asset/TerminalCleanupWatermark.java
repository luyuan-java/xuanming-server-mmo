package com.game.guild.asset;

import com.game.common.deadline.Deadline;

/**
 * 终态指令清理水位：截止之前的记录可能已被删，调大保留期也不能重新声明那段历史可证明（批次 7.2d）。
 * 全服共享、无过期、只增。推进必须先于删除；读取不准必须抛异常，不能回退成 0。
 */
public interface TerminalCleanupWatermark {

    void advance(long cutoffMs, Deadline deadline);

    /** 0 只表示键确实不存在（尚未清理），不表示依赖不可用。 */
    long read(Deadline deadline);

    /** 不参与清理的离线工具可不装配；误调用清理或回档查询时仍然关闭放行。 */
    TerminalCleanupWatermark UNAVAILABLE = new TerminalCleanupWatermark() {
        @Override public void advance(long cutoffMs, Deadline deadline) { throw unavailable(); }
        @Override public long read(Deadline deadline) { throw unavailable(); }
        private Deadline.DependencyException unavailable() {
            return new Deadline.DependencyException("终态指令清理水位没有装配");
        }
    };
}
