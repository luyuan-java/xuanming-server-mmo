package com.game.gate.link;

/**
 * 为一条链路建立 TCP 连接：寻址（查节点目录）+ 连接 + 在 pipeline 上装好 {@link SceneLinkHandler}。
 *
 * <p>契约：不阻塞调用线程；成功时由 {@link SceneLinkHandler} 回调 {@link SceneLink#onConnected}，
 * 任何失败都必须以 {@link SceneLink#fail} 结束，不能让链路永远停在「连接中」。
 * 单独成接口是为了隔离网络：单测用 {@code EmbeddedChannel} 代替真实连接。
 */
@FunctionalInterface
public interface LinkConnector {

    void connect(SceneLink link);
}
