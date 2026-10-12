package com.game.scene.world;

/**
 * 玩家快照出口（回档素材）：进场时与离场写回时各拍一份（场景逻辑线程上调用，不得阻塞——实现只投递不可变的数据）。
 * 内容就是写回用的 {@link PlayerSave}（player 行的可变字段 + 玩法数据），日后回档可以原样写回。
 * 失去归属时的移除不拍（那份状态已不权威）。
 */
@FunctionalInterface
public interface PlayerSnapshots {

    /** 触发原因。 */
    enum Cause {
        /** 进入场景（每次进场一份，含接管旧实例）。 */
        LOGIN,
        /** 离开场景（主动离开、断线、停服）。 */
        LOGOUT
    }

    /** 不拍快照（测试 / 关闭审计管线时）。 */
    PlayerSnapshots NONE = (save, cause) -> { };

    void capture(PlayerSave save, Cause cause);
}
