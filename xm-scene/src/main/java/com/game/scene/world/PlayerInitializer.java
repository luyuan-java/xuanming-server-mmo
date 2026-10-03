package com.game.scene.world;

/**
 * 玩家实例建好、进入场景之前调用一次（场景逻辑线程上，进场与接管旧实例都会走到）：玩法按配表把恢复出来的状态规整
 * （例如属性系统清掉表里已删除的维度、收敛超量分配、算出二级属性）。不得阻塞；抛异常按进场失败处理。
 */
@FunctionalInterface
public interface PlayerInitializer {

    /** 不做任何事（只有场景核心功能的世界，测试用）。 */
    PlayerInitializer NONE = player -> { };

    void initialize(ScenePlayer player);
}
