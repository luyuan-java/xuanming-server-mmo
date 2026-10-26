package com.game.scene.world;

import com.game.proto.SceneChannelDirectory;
import java.util.function.Consumer;

/** 玩家分线的异步读写端口；实现不得阻塞逻辑线程，回调在逻辑线程且恰好一次（停止时丢弃）。 */
public interface PlayerChannels {
    /** 缺失、损坏、读失败或超时均以 null 返回，调用方仍发送不带目录的 31。 */
    void directory(int sceneConfigId, Consumer<SceneChannelDirectory> onDone);

    /** 仅在切换成功后调用；失败或超时也必须回调，不能回滚成功的切换。seconds 为 uint32 原始位。 */
    void switched(long playerId, int seconds, Runnable onDone);

    /**
     * 交接续接：onDone 在逻辑线程；逻辑线程拒绝投递时在完成线程调用 onUndelivered，二者只选其一。
     * onUndelivered 只允许交出非阻塞清理任务，不能访问世界状态或同步执行 SQL。
     */
    default void switched(long playerId, int seconds, Runnable onDone, Runnable onUndelivered) {
        switched(playerId, seconds, onDone);
    }

    /** 纯世界单测的缺省装配：没有共享目录或冷却。 */
    PlayerChannels NONE = new PlayerChannels() {
        @Override public void directory(int configId, Consumer<SceneChannelDirectory> onDone) { onDone.accept(null); }
        @Override public void switched(long playerId, int seconds, Runnable onDone) { onDone.run(); }
    };
}
