package com.game.team.store;

import com.game.team.rules.Decision;
import java.util.List;

/**
 * {@link TeamStore#mutate} 的完整结果（基线 store.go:159-178；服务层的映射见 team-spec §3.1 {@code runMutate}）。
 *
 * @param outcome      结局
 * @param code         {@link Outcome#REJECTED} 时的 tip 码（规则拒绝、4003 / 4022 Lua 拒绝、4029 冲突耗尽或预算过期、4030 新 tid 已有记录）；
 *                     其余结局为 0
 * @param param        tip 的 parameters[0]（十进制 player_id；0 = 不带）
 * @param snapshot     最后一轮 S_READ（调用者 tid / epoch + 绑定队伍的记录）
 * @param decision     最后一轮规则输出（从未跑到规则时是零值 {@link Decision#unchanged()}）。{@code decision.code() != 0} 表示
 *                     规则拒绝——此时视图可与 snapshot 同源；为 0 的 REJECTED（Lua 拒绝 / 冲突耗尽 / 预算过期 / 建队撞号）改用自由读
 * @param commit       {@link Outcome#COMMITTED} 时的主提交；其余为 null
 * @param repairs      {@code {-2}} 触发的修复提交（把索引已指向别队的成员移出本队，Reason=HEALED），按落盘顺序。即使结局不是 COMMITTED
 *                     也已落盘，服务层照常推送
 * @param healedOrphan 调用者的孤儿索引已被 S_HEAL_ORPHAN 置 0
 * @param conflicts    本次遇到的 S_COMMIT 版本冲突（{@code {0}}）次数，含修复提交的冲突（服务层记 {@code team_commit_retry_total}）
 */
public record MutateResult(Outcome outcome, int code, long param, Snapshot snapshot, Decision decision,
                           CommitResult commit, List<CommitResult> repairs, boolean healedOrphan, int conflicts) {

    public MutateResult {
        repairs = repairs == null ? List.of() : List.copyOf(repairs);
        decision = decision == null ? Decision.unchanged() : decision;
    }
}
