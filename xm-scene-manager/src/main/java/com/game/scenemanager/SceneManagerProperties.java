package com.game.scenemanager;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-scene-manager 配置（{@code xm.scene-manager.*}）。
 *
 * @param defaultWorldConfigId 请求没指定（或指定的不可用）时落哪张世界地图，值是 scene_config_id（BaseScene id）。
 *                             不配 = World 表按表序第一行的 {@code scene_id}（与 mmorpg 一致，当前为 1）。
 *                             配了但不在 World 表里 → 启动失败。
 */
@ConfigurationProperties("xm.scene-manager")
public record SceneManagerProperties(Integer defaultWorldConfigId) {
}
