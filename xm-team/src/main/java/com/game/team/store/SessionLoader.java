package com.game.team.store;

import com.game.common.deadline.Deadline;
import com.game.team.rules.SessionState;
import java.util.Collection;
import java.util.Map;

/**
 * 批量读成员会话状态，供规则层判定惰性转让队长与转让目标在线（基线 store.go:58-60 SessionLoader）。
 *
 * <p>实现（服务层，team-spec §6.6）必须<b>逐成员 fail-closed、从不抛出</b>：读失败、条目损坏、身份不符的成员给
 * {@link SessionState#UNKNOWN} 或干脆不放进 map（缺项即 UNKNOWN）。万一实现抛了 RuntimeException，{@link TeamStore} 按全员 UNKNOWN
 * 处理（不转让、转让目标一律视为离线），不让 mutate 失败。在调用 {@code mutate} 的工作线程上同步执行，可以阻塞（上界是 deadline）。
 */
@FunctionalInterface
public interface SessionLoader {

    /** 全员 UNKNOWN（基线 sessions 为 nil）。 */
    SessionLoader NONE = (members, deadline) -> Map.of();

    /**
     * @param members  本轮记录里的成员（按 join_seq 升序）
     * @param deadline 本次请求的预算
     * @return 成员 → 会话状态；缺项即 UNKNOWN
     */
    Map<Long, SessionState> load(Collection<Long> members, Deadline deadline);
}
