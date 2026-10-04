package com.game.team.presence;

import com.game.common.deadline.Deadline;
import com.game.team.view.MemberDisplay;
import java.util.Collection;
import java.util.Map;

/**
 * 批量加载视图的展示缓存（基线 presence.go:84-105 loadDisplay）。生产实现 {@link TeamDisplay}。
 *
 * <p>契约：去掉 0、去重；<b>从不抛出</b>——任何读失败只让对应字段按「离线 / 不在战斗 / 零值」处理并记日志，不让 RPC 失败
 * （基线 presence.go:25-27）。会阻塞（MySQL），只在工作线程 / 推送线程上调用。
 */
@FunctionalInterface
public interface DisplayLoader {

    /**
     * @param playerIds 视图里会出现的玩家（可含 0 与重复）
     * @param deadline  本次请求（或推送批）的预算
     * @return 玩家 → 展示信息；缺项按 {@link MemberDisplay#NONE}
     */
    Map<Long, MemberDisplay> load(Collection<Long> playerIds, Deadline deadline);
}
