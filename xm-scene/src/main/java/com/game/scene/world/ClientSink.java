package com.game.scene.world;

import com.game.proto.MessageContent;
import java.util.List;

/**
 * 场景逻辑的出站口：下行给客户端的消息，以及给 gate 的进场结果 / 踢出通知。只在场景逻辑线程上调用。
 *
 * <p>链路用 {@code linkId}（每条 gate 链路握手成功时分配的进程内唯一号）标识，而不是 gate 节点号：
 * 链路被顶替后，旧 linkId 上迟到的下行会被丢弃，不会误发到新链路。
 */
public interface ClientSink {

    /**
     * 把同一条 {@code MessageContent} 下发给同一链路上的若干会话。链路已断开时静默丢弃
     * （gate 侧这些会话已随链路失效）。
     */
    void send(long linkId, List<Integer> sessionIds, MessageContent content);

    /**
     * 通知 gate 进场结果；{@code tipId} 为 0 表示成功。{@code ownerEpoch} 回显这次进场的 epoch，
     * gate 据此丢弃同一会话上更早一次进场的迟到结果。
     */
    void enterResult(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId);

    /**
     * 通知 gate：会话上的玩家已被移出场景（归属被别的会话接管或本实例失去归属），该写回的已写回；
     * gate 推 23 {@code TipInfoMessage{tipId}} 后关闭这个会话，不再发 PlayerLeave。
     */
    void playerKicked(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId);

    /**
     * 跨节点换图的改绑指令（{@code PlayerTransfer}，scene-handoff-spec §5.3、§5.7）：交出事务已提交（{@code fromEpoch} → {@code toEpoch}），
     * 实例已从本节点移除；gate 校验绑定后改绑到 {@code targetNodeId} 并向它发 {@code PlayerEnter{targetSceneId, toEpoch, transfer}}。
     * 与发给该会话的其余下行走同一条链路、保持提交顺序。
     *
     * @param onWriteFailed 帧已交给链路、但最终没写出去（异步写失败）时调用；实现负责把它投递回场景逻辑线程执行
     * @return false = 链路已断 / 不可写，帧<b>确定</b>没写出（{@code onWriteFailed} 不会再被调用）
     */
    boolean playerTransfer(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch, int targetNodeId,
                           long targetSceneId, Runnable onWriteFailed);
}
