package com.game.gate.link;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
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

    /** 链路没能建立（寻址失败 / 连接失败 / 握手被拒 / 队列溢出），这条进场帧没有送到 scene。 */
    void onEnterUndeliverable(int sceneNodeId, long linkGen, PlayerEnter enter);

    /** 一条<b>曾经就绪</b>的链路断开：scene 那一侧已丢失经这条链路进场的全部会话。 */
    void onLinkDown(int sceneNodeId, long linkGen);
}
