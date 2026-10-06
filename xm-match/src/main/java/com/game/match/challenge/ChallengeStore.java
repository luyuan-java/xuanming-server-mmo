package com.game.match.challenge;

import com.game.common.deadline.Deadline;
import java.util.Optional;

/**
 * 切磋邀请的存储（match-spec §6.3）：一条邀请 = 一条记录（按 challenge_id）+ 目标身上的一个「待应答」占坑（按目标玩家，一个人同时只能挂一条邀请）。
 * 生产实现是 Redis（{@link RedissonChallengeStore}：三段 Lua，三类键都在 {@code {match}} 一个槽里）；组件测试用内存实现，两者跑同一套契约测试。
 *
 * <p>与基线（{@code chl.go:119-155}、{@code :199-228}、{@code :291-302}）的差别都在原子性上：发起是一段脚本（占坑 + 写记录 + 设 TTL），
 * 不再有「占了坑、记录没写成」的中间态；消费是一段脚本（核对目标 + 删记录 + 摘占坑 + 留墓碑），两条并发的接受只有一条拿到记录
 * （修基线 G8「双击接受开两次 gather」，M17）。
 *
 * <p><b>契约</b>（对全部方法成立）：
 * <ul>
 *   <li><b>阻塞</b>：在调用线程（工作线程）上等 Redis，至多等到 {@code d}。线程安全，多实例并发安全。</li>
 *   <li><b>失败</b>：Redis 出错或超出 {@code d} 抛 {@link Deadline.DependencyException}；对写方法这表示<b>结局不明</b>（脚本可能已执行）。</li>
 *   <li><b>可重放</b>：每个写方法对「同样的入参再执行一次」是安全的（Redis 客户端在响应超时后会重发同一段脚本）：发起按 challenge_id 认出自己、
 *       消费按请求 nonce 认出自己，第二次执行返回与第一次相同的结果，不会把自己的第一次误判成别人的。</li>
 *   <li><b>时间</b>：过期时刻与「消费那一刻」都取 Redis {@code TIME}，不用本机时钟（多实例之间墙钟偏差不进入过期判定，M7）。</li>
 * </ul>
 */
public interface ChallengeStore {

    /**
     * 一条切磋邀请。字段解析同基线（{@code chl.go:214-217}）：不是合法无符号十进制的字段按 0。
     *
     * @param challengerId 发起者
     * @param targetId     被挑战者
     * @param configId     {@code battle_config_id}（uint32 的位模式；不校验，原样传给开局管线）
     * @param expiresAtMs  过期时刻（Redis {@code TIME} 的 Unix 毫秒 = 发起时刻 + TTL；也是 156 里的 {@code expires_at_ms}）
     */
    record ChallengeRecord(long challengerId, long targetId, int configId, long expiresAtMs) {
    }

    /** 发起的结局（二选一）。 */
    sealed interface InviteResult {

        /** 本次（或同一个 challenge_id 的上一次执行）已占坑并写下记录。{@code expiresAtMs} 是写进记录的过期时刻。 */
        record Created(long expiresAtMs) implements InviteResult {
        }

        /** 目标已有<b>别的</b>待应答邀请：什么都没写。 */
        record Pending() implements InviteResult {
        }
    }

    /**
     * 原子发起（S_CH_INVITE）：目标的占坑不存在 → 占坑（值 = {@code challengeId}）、写记录、两者都设 {@code ttlMs}；
     * 占坑已是本 {@code challengeId} → 当作重放（返回已写下的过期时刻）；占坑是别的邀请 → {@link InviteResult.Pending}。
     *
     * @param ttlMs 邀请的寿命（{@code xm.match.challenge-ttl}，缺省 60 s），必须 ≥ 1
     */
    InviteResult invite(long challengeId, long challengerId, long targetId, int configId, long ttlMs, Deadline d);

    /**
     * 清理一条邀请（S_CH_DEL；发起后推送失败时用）：删记录；占坑的值<b>等于</b>本 {@code challengeId} 时才摘（不误摘目标后来挂上的另一条邀请）。幂等。
     */
    void delete(long challengeId, long targetId, Deadline d);

    /** 读一条邀请（只读；151 先读一次以便按基线的顺序回码）。不存在（从未有过、已被消费、TTL 已过）为空。 */
    Optional<ChallengeRecord> read(long challengeId, Deadline d);

    /** 消费的结局（三选一，调用方穷举）。 */
    sealed interface ConsumeResult {

        /**
         * 本次（或同一个 nonce 的上一次执行）消费了记录。
         *
         * @param record     被消费的邀请
         * @param redisNowMs 消费那一刻的 Redis 时间：过期判定拿它与 {@link ChallengeRecord#expiresAtMs()} 比
         */
        record Consumed(ChallengeRecord record, long redisNowMs) implements ConsumeResult {
        }

        /** 记录不存在：已过期，或已被别的请求消费（并发的两条应答里后到的那条）。 */
        record Gone() implements ConsumeResult {
        }

        /** 应答者不是这条邀请的目标：记录原样保留。 */
        record NotTarget() implements ConsumeResult {
        }
    }

    /**
     * 一次性消费（S_CH_CONSUME）：记录存在且目标是 {@code responderId} → 删记录、摘占坑（值等于本邀请时）、留下带 {@code nonce} 的墓碑（60 s），
     * 返回记录与此刻的 Redis 时间。同一个 {@code nonce} 再执行一次（重发）→ 从墓碑里原样返回第一次的结果。
     *
     * @param nonce 这一次应答请求的唯一标识（每个 151 请求生成一个随机串，重发时不变），非空
     */
    ConsumeResult consume(long challengeId, long responderId, String nonce, Deadline d);
}
