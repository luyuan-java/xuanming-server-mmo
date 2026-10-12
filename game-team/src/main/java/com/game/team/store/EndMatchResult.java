package com.game.team.store;

import com.game.common.deadline.Deadline.DependencyException;
import java.util.List;

/**
 * {@link TeamStore#endMatch} 的完整结果（基线 store.go:526-535 EndMatchResult）。
 *
 * @param stop      结局
 * @param commit    {@link EndMatchStop#RELEASED} 时的清锁提交；否则为 null
 * @param repairs   过程中落盘的 {@code {-2}} 修复提交（服务层照常推送），按落盘顺序；不可变
 * @param conflicts 清锁提交遇到的版本冲突（{@code {0}}）次数
 * @param lastError 最后一次 Redis / 数据故障（循环退避后重试，只在 {@link EndMatchStop#DEADLINE} / {@link EndMatchStop#INTERRUPTED} 时有参考意义）；
 *                  没有出过故障为 null
 */
public record EndMatchResult(EndMatchStop stop, CommitResult commit, List<CommitResult> repairs, int conflicts,
                             DependencyException lastError) {

    public EndMatchResult {
        repairs = repairs == null ? List.of() : List.copyOf(repairs);
    }
}
