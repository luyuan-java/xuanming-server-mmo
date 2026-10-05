package com.game.gate.link;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.ToClient;

/**
 * 节点链路事件。在链路所在的 I/O 线程（或建链线程）上回调，实现方不得阻塞，
 * 要动会话状态必须投递回会话所属线程。
 */
public interface SceneLinkListener {

    /** scene 下行给一个或多个会话的消息。 */
    void onToClient(int sceneNodeId, long linkGen, ToClient message);

    /** scene 回报进场结果。 */
    void onPlayerEnterResult(int sceneNodeId, long linkGen, PlayerEnterResult result);

    /** scene 已把会话上的玩家移出场景（归属被接管 / 失去归属），要求 gate 推 tip 后关闭这个会话。 */
    void onPlayerKicked(int sceneNodeId, long linkGen, PlayerKicked kicked);

    /**
     * 源 scene 已把会话上的玩家交出到别的节点（归属已推进到 {@code to_epoch}、实例已移除），要求 gate 改绑会话并向目标节点发交出进场。
     * 与该 scene 发给会话的其余下行同链路、按到达顺序回调。实现方找不到会话时也必须善后（代为放弃 {@code to_epoch}）：
     * 这份归属只为这一帧铸出，丢了它就只能等租约过期。
     */
    void onPlayerTransfer(int sceneNodeId, long linkGen, PlayerTransfer transfer);

    /**
     * 链路没能建立（寻址失败 / 连接失败 / 握手被拒 / 队列溢出），这条进场帧没有送到 scene。
     * scene 从未见过它，实现方必须代为放弃这次进场的归属（会话已关闭、已释放也一样）。
     */
    void onEnterUndeliverable(int sceneNodeId, long linkGen, PlayerEnter enter);

    /** 一条<b>曾经就绪</b>的链路断开：scene 那一侧已丢失经这条链路进场的全部会话。 */
    void onLinkDown(int sceneNodeId, long linkGen);
}
