package com.game.friend.store;

import com.game.friend.support.Deadline;
import java.util.List;

/**
 * 好友关系的权威存储（mmorpg go/friend internal/data friend_repo / block_repo 的 Java 版，规格见 docs/porting/friend-spec.md §1–§2）。
 *
 * <p>业务拒绝用枚举结果返回（事务已回滚，没有副作用——Add 的容量行补建除外，见 {@link #addRequest}）；依赖故障（MySQL 出错、
 * 守卫缺行重试用尽）抛 {@link FriendStoreException}，上层定性为 1003。玩家号一律按无符号 64 位处理，0 与「自己对自己」
 * 由上层挡住（store 收到时抛 {@link IllegalArgumentException}，上层同样定性为 1003 并记 ERROR）。
 *
 * <p>线程安全；全部方法阻塞（JDBC），只在工作线程上调用。每条语句的查询超时取请求预算的剩余（向上取整到秒），预算用完不再发语句、
 * 守卫事务在提交前再查一次（过了预算就回滚）：gate 已经判超时的请求不会在之后落库。
 */
public interface FriendStore {

    /** 发申请的结局。好友数满的两个值按「角色」命名：申请人（sender）满 / 接收方满，由上层分别映射 tip。 */
    enum AddResult { OK, BLOCKED, ALREADY_FRIENDS, ALREADY_SENT, TOO_MANY_PENDING, TARGET_INBOX_FULL, SENDER_FULL, RECEIVER_FULL }

    /** 同意申请的结局。SENDER_FULL = 原申请人好友满，ACCEPTOR_FULL = 我（接受者）好友满。 */
    enum AcceptResult { OK, NO_PENDING, BLOCKED, SENDER_FULL, ACCEPTOR_FULL }

    enum RejectResult { OK, NO_PENDING }

    enum RemoveResult { REMOVED, NOT_FRIENDS }

    enum BlockResult { OK, BLOCK_LIST_FULL }

    /** 好友边（列表读）。 */
    record FriendEdge(long friendPlayerId, long sinceMs) {
    }

    /** 入站待处理申请（列表读）；status 恒为 1。 */
    record PendingRequest(long fromPlayerId, long toPlayerId, long requestTimeMs, int status) {
    }

    record BlockEdge(long blockedPlayerId, long sinceMs) {
    }

    /**
     * 发申请（守卫事务，补双方容量行——补行是自动提交的，被拒时也已补上，同基线）。
     * 检查优先级：拉黑 &gt; 已是好友 &gt; 已申请 &gt; 出站满 &gt; 入站满 &gt; 我满 &gt; 对方满；通过后 upsert 待处理行。
     */
    AddResult addRequest(long from, long to, Deadline deadline);

    /** 同意 {@code from} 发给 {@code me} 的申请：两个方向的待处理都置已同意、双向插边并各自计数 +1。 */
    AcceptResult accept(long from, long me, Deadline deadline);

    /** 拒绝（单条 CAS，不拿守卫）。 */
    RejectResult reject(long from, long me, Deadline deadline);

    /** 删除好友（不是好友 / 删自己都是 {@link RemoveResult#NOT_FRIENDS}，上层回成功）。 */
    RemoveResult remove(long me, long target, Deadline deadline);

    /** 拉黑：写黑名单（幂等）、双向删边并减计数、两个方向的待处理置已拒绝。 */
    BlockResult block(long me, long target, Deadline deadline);

    /** 解除拉黑（单条 DELETE，幂等，不恢复好友）。 */
    void unblock(long me, long target, Deadline deadline);

    /** 好友边（至多 {@code limit} 条，权威读）。 */
    List<FriendEdge> friends(long me, int limit, Deadline deadline);

    /** 入站待处理申请（至多 {@code limit} 条，权威读）。 */
    List<PendingRequest> pendingRequests(long me, int limit, Deadline deadline);

    /** 我拉黑的人（至多 {@code limit} 条，直读、不缓存）。 */
    List<BlockEdge> blocks(long me, int limit, Deadline deadline);
}
