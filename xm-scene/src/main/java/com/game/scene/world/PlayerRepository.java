package com.game.scene.world;

import java.util.function.Consumer;

/**
 * 场景逻辑看到的玩家存储（异步）。实现负责把阻塞 I/O 放到存储线程池，并把结果投递回场景逻辑线程。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #load} 的回调<b>一定在场景逻辑线程上、且不在 {@code load} 调用栈内</b>执行，且只执行一次；</li>
 *   <li>{@link #save} 是写者离开时的<b>最终写回并释放归属</b>，带 {@code owner_epoch} 围栏：epoch 已过期（被新的进场夺权）时
 *       写入被丢弃，实现只记日志；调用方不等待结果；</li>
 *   <li>{@link #release} 只释放归属、不写状态（进场失败 / 取消），同样带围栏；</li>
 *   <li>两者对可恢复的瞬时故障（取不到连接、网络闪断）在有界时间内重试；最终失败不抛给调用方，记 ERROR（带足够信息人工修复）。</li>
 * </ul>
 */
public interface PlayerRepository {

    void load(long playerId, Consumer<LoadResult> onLoaded);

    void save(PlayerSave save);

    void release(long playerId, long ownerEpoch);

    /** 加载结果。 */
    sealed interface LoadResult {

        record Found(PlayerData data) implements LoadResult {
        }

        record NotFound() implements LoadResult {
        }

        record Failed(Throwable error) implements LoadResult {
        }
    }
}
