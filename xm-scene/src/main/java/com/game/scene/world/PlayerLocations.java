package com.game.scene.world;

import java.util.Collection;

/**
 * 玩家位置记录的写口（scene 是持有归属期间的唯一写者，见 {@code PlayerLocationDirectory}）。全部在场景逻辑线程上调用，
 * 实现必须不阻塞（异步发出、失败只记日志 / 指标：位置只是 login 选场景的提示，写不上退化成按首登落点）。
 */
public interface PlayerLocations {

    /** 进场或换场景之后：记下玩家此刻所在的场景实例。 */
    void entered(ScenePlayer player);

    /** 断线（连接断开、gate 链路断开）离场之后：把在线记录转成重连租约。 */
    void disconnected(ScenePlayer player);

    /** 主动离开（LeaveGame）之后：换成登出墓碑（读者当没有），下次进游戏按首登落点。 */
    void loggedOut(ScenePlayer player);

    /**
     * 进场还在加载时就主动离开（LeaveGame）：这次进场没写过记录，用它的 epoch 写登出墓碑，盖掉更早那次进场留下的在线 / 租约记录。
     */
    void loggedOutWhileLoading(long playerId, long ownerEpoch);

    /** 在线续期一批（每秒一个槽）。 */
    void refresh(Collection<ScenePlayer> players);

    PlayerLocations NONE = new PlayerLocations() {
        @Override
        public void entered(ScenePlayer player) {
        }

        @Override
        public void disconnected(ScenePlayer player) {
        }

        @Override
        public void loggedOut(ScenePlayer player) {
        }

        @Override
        public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
        }

        @Override
        public void refresh(Collection<ScenePlayer> players) {
        }
    };
}
