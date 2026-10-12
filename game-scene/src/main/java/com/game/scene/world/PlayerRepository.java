package com.game.scene.world;

import com.game.player.store.PlayerStore.HandOffMode;
import java.util.List;
import java.util.Objects;
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
 *   <li>{@link #handOff} 是<b>原子交出</b>（写回冻结快照 + epoch 加一 + 新租约，一笔事务），{@link #probe} 是交出结局不明之后的加锁读探测。
 *       两者的结局回调同样在场景逻辑线程上、不在调用栈内、恰好一次。交出带<b>模式</b>（{@link HandOffMode}，批次 5.4）：
 *       <ul>
 *         <li>{@code HOLD}（批次 5.2 跨节点换图）：新 epoch 保持未释放，等同 zone 的目标节点接手。唯一的例外：逻辑线程已停止、
 *             结局投递被拒时，结局是「已交出」的由实现在存储线程上直接释放新 epoch（交出通知只由逻辑线程发出，逻辑线程收不到结局
 *             就一定没发，新 epoch 没有别的知情者，释放安全）；</li>
 *         <li>{@code RELEASE}（批次 5.4 跨 zone 传送）：新 epoch 在同一笔事务里<b>同时释放</b>，库里没有持有者，目标 zone 的 login
 *             夺权立即成功。所以 RELEASE 下任何路径都<b>不再释放新 epoch</b>（上面那条例外也不做），并多一种结局 {@code Superseded}。</li>
 *       </ul></li>
 *   <li>写对可恢复的瞬时故障（取不到连接、网络闪断、锁等待 / 语句超时）在有界时间内重试；最终失败不抛给调用方，
 *       记日志（写回 / 释放记 ERROR，带足够信息人工修复）。</li>
 * </ul>
 * 同一玩家同一时刻至多一个写在途，由调用方（场景逻辑）保证。例外只有冻结中的三种，都带 (E, 未释放) 围栏、谁先提交都安全
 * （scene-handoff-spec §5.5）：冻结前已在途的在线存盘与交出并发（在线存盘先 → 交出覆盖它；交出先 → 在线存盘被拒）；停服 / 同会话换角色时
 * 冻结中实例的最终写回与交出并发（写回先 → 交出被拒；交出先 → 写回被拒，HOLD 下结局出来时释放新 epoch、RELEASE 下不释放）。
 */
public interface PlayerRepository {

    void load(long playerId, Consumer<LoadResult> onLoaded);

    void save(PlayerSave save);

    void release(long playerId, long ownerEpoch);

    void saveProgress(PlayerSave save, Consumer<ProgressResult> onDone);

    /**
     * 交出归属：带围栏写回冻结快照（{@code frozen.ownerEpoch()} = 本实例持有的 E），同一事务里把 epoch 加一到 E+1、给新租约。
     * 只有仍由 E 持有、未释放、且剩余租约不短于安全边际时才提交。{@code mode} 决定提交时 E+1 的释放标记：
     * <ul>
     *   <li>{@link HandOffMode#HOLD}：E+1 保持未释放（跨节点换图，由目标节点接手）；</li>
     *   <li>{@link HandOffMode#RELEASE}：E+1 同时释放（跨 zone 传送，zone-travel-spec §5.2）。提交之后 E 的一切写都被围栏拒，
     *       对 E+1 的释放是空操作——调用方与实现都<b>不得</b>再释放 E+1。</li>
     * </ul>
     *
     * <p>重试中「其实已提交、只是应答丢了」的那次，靠每次尝试写下的租约值认回来（结局仍是 {@link HandOffOutcome.HandedOff}，
     * 不会被当成 {@link HandOffOutcome.Fenced}）。结局 {@link HandOffOutcome.Failed} 表示结局不明，调用方必须接着 {@link #probe}。
     *
     * @param mode 不能为 null
     */
    void handOff(PlayerSave frozen, HandOffMode mode, Consumer<HandOffOutcome> onDone);

    /** 跨节点换图的交出（批次 5.2 的形式）：等于 {@code handOff(frozen, HandOffMode.HOLD, onDone)}。 */
    default void handOff(PlayerSave frozen, Consumer<HandOffOutcome> onDone) {
        handOff(frozen, HandOffMode.HOLD, onDone);
    }

    /**
     * 交出结局不明（{@link HandOffOutcome.Failed}）之后的探测：加锁读归属（等任何仍持有行锁的在途交出事务结束），
     * 读失败 / 锁等待超时在截止时间（第一次交出尝试起 安全边际 − 2 s）内退避重试，至少读一次。读数按 {@code failed.mode()} 判。
     */
    void probe(HandOffOutcome.Failed failed, Consumer<ProbeOutcome> onDone);

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

    /** {@link #handOff} 的结局。 */
    sealed interface HandOffOutcome {

        /**
         * 已交出：库里 epoch = {@code newEpoch}（= 冻结快照的 epoch + 1）、新租约；冻结快照已落库。旧 epoch 从此写不进去。
         * 释放标记随模式：{@link HandOffMode#HOLD} 未释放（等目标节点接手）；{@link HandOffMode#RELEASE} 已释放（没有持有者，不许再释放它）。
         */
        record HandedOff(long newEpoch) implements HandOffOutcome {
        }

        /** 没提交、什么也没改：仍由本实例的 epoch 持有，但剩余租约不足安全边际（续约近期在失败），交出不安全。 */
        record LeaseTooShort() implements HandOffOutcome {
        }

        /** 没提交、什么也没改：归属已不是本实例的（epoch 已变 / 已释放 / 玩家不存在）。 */
        record Fenced() implements HandOffOutcome {
        }

        /**
         * 只在 {@link HandOffMode#RELEASE} 出现（批次 5.4）：<b>有过一次结局不明的更早尝试</b>，重试时读到库里已是 ≥ E+2——
         * 多半是那次尝试已提交（交出并释放）、随后被别的登录夺走。<b>它不是「本次已提交」的证据</b>（那次尝试也可能根本没到库，
         * E 是租约过期后被别人连夺两次）：调用方对它的处理必须与 {@link Fenced} 完全相同（移除、不写回、踢 2017），
         * 不得据此写待落点、发重定向帧或计「已交出」。第一次尝试就读到 ≥ E+2 是 {@link Fenced}，不是它。
         */
        record Superseded() implements HandOffOutcome {
        }

        /**
         * 结局不明（重试用尽、非瞬时故障、重试等待被中断、线程池拒绝）：可能已提交，也可能没有。必须交给 {@link #probe} 判定。
         *
         * @param playerId  玩家
         * @param fromEpoch 交出方持有的 E
         * @param attempts  这次交出的尝试记录（探测据此认领自己的提交、计算截止时间）
         * @param mode      这次交出的模式：探测按它判读数（HOLD 认 (E+1, 未释放, 自己的租约值)，RELEASE 认 (E+1, 已释放, 自己的租约值)）
         */
        record Failed(long playerId, long fromEpoch, HandOffAttempts attempts, HandOffMode mode) implements HandOffOutcome {

            public Failed {
                Objects.requireNonNull(attempts, "attempts");
                Objects.requireNonNull(mode, "mode");
            }

            /** 跨节点换图（批次 5.2）的形式：模式是 {@link HandOffMode#HOLD}。 */
            public Failed(long playerId, long fromEpoch, HandOffAttempts attempts) {
                this(playerId, fromEpoch, attempts, HandOffMode.HOLD);
            }
        }
    }

    /**
     * 一次交出的尝试记录，只供 {@link #probe} 使用。
     *
     * @param firstAttemptNanos 第一次尝试开始时实现的单调时钟读数（没有任何尝试时无意义）
     * @param leases            每次尝试写下的新租约值（Unix 毫秒），按尝试先后；空 = 一次也没尝试（线程池拒绝），一定没提交
     */
    record HandOffAttempts(long firstAttemptNanos, List<Long> leases) {

        public HandOffAttempts {
            leases = List.copyOf(leases);
        }
    }

    /** {@link #probe} 的结局。 */
    sealed interface ProbeOutcome {

        /** 没提交：仍由 E 持有、未释放；加锁读保证那笔交出已回滚、不会再提交。 */
        record NotCommitted() implements ProbeOutcome {
        }

        /**
         * 其实已提交：库里是 (E+1, 这次交出写下的某个租约值)，释放标记与这次交出的模式相符
         * （{@link HandOffMode#HOLD} 未释放、{@link HandOffMode#RELEASE} 已释放）。
         */
        record HandedOff(long newEpoch) implements ProbeOutcome {
        }

        /**
         * 只在 {@link HandOffMode#RELEASE} 出现（批次 5.4）：读到库里已是 ≥ E+2。含义与约束同 {@link HandOffOutcome.Superseded}——
         * 只是一个标签，后果必须与失去归属相同（移除、不写回、踢 2017），不得当成「本次已提交」。
         */
        record Superseded() implements ProbeOutcome {
        }

        /** 判定不了或已失去：截止前读不到、读到别人的归属、玩家不存在，或 E 已被释放。fail-closed：按失去归属处理。 */
        record Lost() implements ProbeOutcome {
        }
    }
}
