package com.game.scene.world;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * 玩家位置记录的写口（scene 是持有归属期间的唯一写者，见 {@code PlayerLocationDirectory}）。全部在场景逻辑线程上调用，
 * 实现必须不阻塞（异步发出、失败只记日志 / 指标：位置只是 login 选场景的提示，写不上退化成按首登落点）。
 * 除 {@link #awaitingPlacement} 外都是「发出即忘」，没有完成回调。
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

    /**
     * 跨 zone 传送交出之后（批次 5.4，zone-travel-spec §5.9）：写一条<b>待落点</b>记录——状态 {@code l}、节点号 0、场景号 0，
     * {@code zone = targetZoneId}、{@code scene_config_id = sceneConfigId}，{@code (epoch, seq) = (removed.ownerEpoch(), removed.nextLocationSeq())}，
     * 存活 {@code ttl}。目标 zone 的 login 在第二条腿按它把玩家分配到目标地图；位置读者对它一律当「没有持有者」。
     *
     * <p>这是位置写口里唯一带完成回调的：调用方要等它写完（或写失败）才发重定向帧。{@code onDone} <b>恰好一次、在场景逻辑线程上</b>
     * （true = 写上了；false = 没写上：被更新的写盖过、Redis 故障）；逻辑线程已停止时不回调。回调<b>可能就在本次调用栈内</b>
     * （缺省实现与测试替身如此），调用方必须先放好墓碑与计数再调。
     *
     * @param removed       刚被移出世界的实例（只读它的玩家号、epoch 与位置序号）
     * @param targetZoneId  目标 zone（非 0）
     * @param sceneConfigId 选目标解析出来的目标地图
     * @param ttl           记录的存活时长：调用方按「票据过期时刻 − 现在」算好（{@code SceneClock.epochMillis()}），实现只把它截到 [1 s, 300 s]
     */
    default void awaitingPlacement(ScenePlayer removed, int targetZoneId, int sceneConfigId, Duration ttl,
                                   Consumer<Boolean> onDone) {
        // 缺省：不写（没接位置目录的装配与只关心别的写口的替身），当场回「没写上」
        onDone.accept(false);
    }

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
