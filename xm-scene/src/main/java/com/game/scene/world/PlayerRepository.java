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
 *   <li>{@link #saveProgress} 是在线存盘（周期存盘）：带围栏、<b>不释放</b>归属；归属已释放（最终写回已提交）时同样被拒，
 *       所以迟到的在线存盘盖不过最终写回。结果回调一定在场景逻辑线程上、不在调用栈内、恰好一次；</li>
 *   <li>两者对可恢复的瞬时故障（取不到连接、网络闪断）在有界时间内重试；最终失败不抛给调用方，记 ERROR（带足够信息人工修复）。</li>
 * </ul>
 */
public interface PlayerRepository {

    void load(long playerId, Consumer<LoadResult> onLoaded);

    void save(PlayerSave save);

    void release(long playerId, long ownerEpoch);

    void saveProgress(PlayerSave save, Consumer<ProgressResult> onDone);

    /**
     * 此刻是否适合再提交在线存盘（存储积压时返回 false：在线存盘可以晚一个周期，续约 / 最终写回 / 加载不能等）。
     * 只是建议，非阻塞、可在逻辑线程上调用。
     */
    default boolean acceptsProgress() {
        return true;
    }

    /** 在线存盘的结局。 */
    enum ProgressResult {
        /** 已落库。 */
        SAVED,
        /** 被围栏拒绝：本实例已失去归属（或已释放），什么也没写。 */
        FENCED,
        /** 重试用尽 / 非瞬时故障 / 线程池拒绝：没写成，已记 ERROR；下个周期按最新状态再写。 */
        FAILED
    }

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
