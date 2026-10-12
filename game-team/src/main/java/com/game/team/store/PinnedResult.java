package com.game.team.store;

import com.game.team.rules.TeamTips;
import java.util.List;

/**
 * 钉版本提交（开战锁 / EndMatch 清锁）的结果（基线 store.go:477-488 PinnedResult）。三种互斥的形态：
 * <ul>
 *   <li>{@code code != 0}：规则拒绝（或提交前预算已过期 → 4029），没有写入；</li>
 *   <li>{@code commit != null}：已提交；</li>
 *   <li>{@code retry}：没有提交——版本已变（{@code {0}}），或保留成员的索引错位（{@code {-2}}）刚做过修复。
 *       开战整轮重来（重新读记录、重排名单、重新预检）；EndMatch 重读。</li>
 * </ul>
 * <b>{@code retry} 不等于没写入</b>：Redisson 对断连 / 应答超时会重发同一段 EVAL，第一次已落盘时第二次回 {@code {0}}——
 * 调用方必须按 token 确认（match-spec §12.1 第 9 条）。
 *
 * @param code    规则拒绝的 tip 码；0 = 没有拒绝
 * @param param   tip 的 parameters[0]（十进制 player_id；0 = 不带）
 * @param commit  已提交时的提交结果；否则为 null
 * @param repairs {@code {-2}} 触发的修复提交（已落盘，服务层照常推 HEALED），按落盘顺序；不可变
 * @param retry   没有提交、需要重来
 */
public record PinnedResult(int code, long param, CommitResult commit, List<CommitResult> repairs, boolean retry) {

    public PinnedResult {
        repairs = repairs == null ? List.of() : List.copyOf(repairs);
    }

    static PinnedResult rejected(int code, long param) {
        return new PinnedResult(code, param, null, List.of(), false);
    }

    static PinnedResult committed(CommitResult commit) {
        return new PinnedResult(TeamTips.OK, 0, commit, List.of(), false);
    }

    static PinnedResult retry(List<CommitResult> repairs) {
        return new PinnedResult(TeamTips.OK, 0, null, repairs, true);
    }
}
