package com.game.friend.store;

import com.game.friend.store.RecommendStore.Candidate;
import com.game.common.deadline.Deadline;
import java.sql.SQLException;
import java.util.List;

/** 推荐的两级候选来源（实现见 {@link RecommendStore}）。 */
public interface RecommendSource {

    /** 原会话请求视图；缺少实现时拒绝，不能把推荐的轻微陈旧策略用于账号权限。 */
    default RecommendSource forActor(long playerId, String originalAccount, long originalRevision) {
        throw new SecurityException("好友推荐未实现原会话身份验证");
    }

    /** 在线目录没有关系 SQL，仍须先验证调用者；生产实现必须锁住 P 验证原件。 */
    default void authorize(Deadline deadline) throws SQLException {
        throw new SecurityException("好友推荐未实现原会话身份验证");
    }

    /** 好友的好友：按共同好友数降序、同数随机，至多 {@code limit} 个。 */
    List<Candidate> mutual(long me, List<Long> exclude, int limit, Deadline deadline) throws SQLException;

    /** 随机锚点兜底：窗口内按玩家号升序的前 {@code limit} 个合格者（可能少于 limit）。 */
    List<Candidate> random(long me, List<Long> exclude, int limit, Deadline deadline) throws SQLException;
}
