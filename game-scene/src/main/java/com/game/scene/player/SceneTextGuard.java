package com.game.scene.player;

import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextScene;

/**
 * 场景服内容安全策略接口（角色改名、宝宝改名共用）。
 * 须在非场景逻辑线程（存储 / I/O 线程池）上同步调用，结果再投递回逻辑线程；
 * 返回 {@code null} 或抛异常均视为内容安全服务不可用（fail-closed）。
 */
@FunctionalInterface
public interface SceneTextGuard {
    CheckTextResponse check(TextScene scene, long playerId, String text);
}
