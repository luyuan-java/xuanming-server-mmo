package com.game.scenemanager.world;

import java.util.OptionalLong;

/**
 * 新频道的 scene_id 来源（scene-channels-spec §4.5、D18）：生产为 scene-manager 全服租约（{@code NodeTypes.SCENE_MANAGER}，作用域 0）上的
 * {@code LeaseGatedSnowflake::tryNext}。为空 = 租约无效 / 时钟回拨 / 发出 0 → 规划器停止本拍<b>全部</b>新建（fail-closed，
 * {@code world_ticks_total{result=no_lease}}），其它步骤照做（同基线发号失败即停止铺设，world_init.go:187-194）。
 */
@FunctionalInterface
public interface SceneIdSource {

    OptionalLong tryNext();
}
