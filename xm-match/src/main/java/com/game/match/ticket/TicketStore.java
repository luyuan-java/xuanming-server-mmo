package com.game.match.ticket;

import com.game.common.deadline.Deadline;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 票据状态机与队列原语（match-spec §1.3、§9.4 的脚本表）：排队、凑单、gather、整队、活动共用这一个存储。生产实现是 Redis（每个方法一段 Lua，
 * 全部键在 {@code {match}} 一个槽里）；测试用内存实现（{@code InMemoryTicketStore}），两者跑同一套契约测试。
 *
 * <p><b>不变量</b>（任何实现都必须守住；调用方可以依赖）：
 * <ul>
 *   <li>(I1) 非空队列一定在注册集里：入队、回队首与登记注册集在同一个原子操作里。</li>
 *   <li>(I2) 队列的成员集合恒等于评分镜像的成员集合：改队列的操作同时改镜像。</li>
 *   <li>(I3) 每个玩家至多一张票：建票是「不存在才写」。</li>
 *   <li>(I4) <b>票据的写一律带票号做 CAS</b>：玩家此刻的票不是调用方给的那一张时，什么都不写——迟到的 gather 写不脏玩家重排之后的新票。</li>
 *   <li>(I5) 取消太迟由存储保证：取消要求票仍是 queued；弹组要求每个人仍是 queued 且在队列里，<b>全有全无</b>。</li>
 * </ul>
 *
 * <p><b>契约</b>（对全部方法成立，除非方法注释另说）：
 * <ul>
 *   <li><b>阻塞</b>：在调用线程上等 Redis，至多等到 {@code d}。可以在工作线程、凑单线程与 gather 的虚拟线程上调（实现不得在 {@code synchronized}
 *       块里阻塞）；不得在 Dubbo / Netty I/O 线程上调。线程安全，多实例并发安全。</li>
 *   <li><b>失败</b>：Redis 出错或超出 {@code d} 抛 {@link Deadline.DependencyException}。对写方法这表示<b>结局不明</b>（脚本可能已经执行）：
 *       调用方不得假定「没写」。各入口怎么收尾见各自的规格条目（例：整队建票结局不明 → 用独立预算按本次的票号逐个 {@link #delete}）。
 *       <b>{@code d} 在发出之前就已经过期时，实现不发命令、直接抛</b>（这一种确定什么都没写）：所以补偿路径上「必须尽量做成」的调用
 *       （放凑单锁、续期、回队首、删票）要给新的截止，不要沿用可能已经用完的那个。</li>
 *   <li><b>可重放</b>：每个写方法对「同样的入参再执行一次」是安全的（Redis 客户端在响应超时后会重发同一段脚本）——第二次执行不得产生第二份效果，
 *       也不得把自己第一次的效果误判成别人的（不出现假的「已在队列中」）。返回值在重放时可能与第一次不同，方法注释写明了调用方该怎么读。</li>
 *   <li><b>时间</b>：{@code enqueued_at_ms}、{@code not_before_ms} 与一切「是否到点」的判定都用 Redis {@code TIME}，不用本机时钟。
 *       TTL 入参都是毫秒、必须 ≥ 1。</li>
 *   <li>入参里的玩家号非 0、票号非空、列表不含 null；违反是调用方的 bug（{@link IllegalArgumentException} / {@link NullPointerException}）。</li>
 * </ul>
 */
public interface TicketStore extends TicketReader {

    // ================================================================ 自愈

    /** 自愈的两种模式（match-spec §2.2「自愈」）。 */
    enum HealMode {
        /** 残留的 ready 票：票号一致且仍是 ready 才删。<b>前提：调用方已确认这名玩家没有战斗锁。</b> */
        READY,
        /** 孤儿 queued 票：票号一致、仍是 queued、且它的队列里找不到这个人才删（队列键不是规范形的 queued 票一律算孤儿）。 */
        ORPHAN
    }

    /**
     * 条件删掉一张残留票（S_HEAL）。{@code seen} 是调用方刚读到的票（按它的票号、状态与队列键核对）。
     *
     * @return true = <b>这名玩家此刻没有票了</b>（本次删掉了，或调用时已经不在——含自己上一次执行已删的重放）；
     *         false = 票还在、条件不满足（票号已换 / 状态已变 / 它其实在队列里）：按「在途」处理，什么都没写
     */
    boolean heal(long playerId, Ticket seen, HealMode mode, Deadline d);

    // ================================================================ 建票

    /** 单人建票的结局（三选一，调用方穷举）。 */
    sealed interface JoinResult {

        /** 本次建成。{@code enqueuedAtMs} = 写进票里的 Redis 时间。 */
        record Created(long enqueuedAtMs) implements JoinResult {
        }

        /** 玩家已有一张<b>同一票号</b>的票：自己上一次执行的重放，按成功处理（没有再入一次队）。 */
        record Replayed() implements JoinResult {
        }

        /** 玩家已有一张<b>别的</b>票（{@code ticketId} 是它的票号）：什么都没写。排队入口据此回 16001 并带上这个票号。 */
        record Exists(String ticketId) implements JoinResult {
        }
    }

    /**
     * 排队（S_JOIN 的入队形态；1V1 / 5V5 / PVE_TEAM）：玩家没有票才建一张 queued 票（{@code mode} / {@code config} / {@code queue_key} 取自
     * {@code queue}），并在同一个原子操作里登记注册集、写评分镜像、入队尾。
     *
     * @param ticketId    调用方生成的票号（UUIDv4 小写带连字符）
     * @param zoneId      位置记录里的 zone（只作观测）
     * @param ratingCenti 入队时的评分 × 100（读不到按 150000）
     * @param ttlMs       queued 票的 TTL（{@code xm.match.ticket-ttl}，缺省 6 h）
     */
    JoinResult enqueue(long playerId, String ticketId, QueueRef queue, int zoneId, long ratingCenti, long ttlMs, Deadline d);

    /**
     * 不入队、直接建一张 matched 票（S_JOIN 的不入队形态；PVE_SOLO）：玩家没有票才建，{@code queue_key} 为空、{@code team_id} 为 0。
     *
     * @param ttlMs matched TTL（{@code MatchBudgets.matchedTicketTtlSeconds(1)} 秒）
     */
    JoinResult createMatched(long playerId, String ticketId, int mode, int configId, int zoneId, long ratingCenti, long ttlMs, Deadline d);

    /**
     * 整组建票的一个成员。
     *
     * @param ticketId 这名成员的票号（整队开战由 xm-team 每人生成一个；活动开战由 match 生成）
     * @param zoneId   预检读到的 zone（没有按 0）
     */
    record GroupMember(long playerId, String ticketId, int zoneId) {

        public GroupMember {
            new TicketRef(playerId, ticketId);
        }
    }

    /**
     * 原子地给一组人各建一张 matched 票（S_CREATE_GROUP；整队开战与活动开战）：不入队，{@code queue_key} 为空，评分写缺省 150000。
     * 逐人看：没有票 → 建；已有<b>本次票号</b>的票 → 当作已建（不重写、不续期）；已有<b>别的</b>票 → 冲突。
     * <b>只要有一个冲突，就什么都不写</b>，返回名单序第一个冲突者；否则返回空（全员建成，或重放）。
     *
     * @param members 名单顺序（决定「第一个冲突者」是谁）；玩家号不得重复
     * @param teamId  整队开战写队伍号（只作观测）；活动开战传 0
     * @param ttlMs   matched TTL（按名单人数）
     * @return 空 = 全员建成；否则是第一个冲突的玩家号
     */
    OptionalLong createGroup(List<GroupMember> members, int mode, int configId, long teamId, long ttlMs, Deadline d);

    // ================================================================ 取消与凑单

    /**
     * 取消排队（S_CANCEL）：票号一致、仍是 queued、且票的队列键就是 {@code queue} 才删票并从队列与评分镜像里摘掉（一个原子操作）。
     *
     * @return true = 本次删掉了；false = 没删（票已不在、票号不符、已被弹走、或是自己上一次执行已删的重放）。两种结果调用方都按「取消成功」回——
     *         取消太迟是静默的（客户端靠查状态收敛）
     */
    boolean cancel(long playerId, String ticketId, QueueRef queue, Deadline d);

    /**
     * 队列快照里的一个成员（等待序）。
     *
     * @param member      队列里的原始成员串
     * @param playerId    解析出的玩家号；成员不是合法的非 0 无符号十进制时为 0（人为改数据才会出现，用 {@link #dropMalformed} 摘掉）
     * @param ratingCenti 评分镜像里的分 × 100；镜像里没有这个成员时为空（同样只可能来自人为改数据：按票里的评分用，不回写）
     */
    record SnapshotEntry(String member, long playerId, OptionalLong ratingCenti) {
    }

    /**
     * 队列前缀的快照。
     *
     * @param entries    队首在前（等得最久的在前）；同一玩家出现多次时原样保留，由凑单按玩家号去重
     * @param redisNowMs 取快照那一刻的 Redis 时间：算锚点已等多久、判 {@link Ticket#backingOff} 都用它
     */
    record QueueSnapshot(List<SnapshotEntry> entries, long redisNowMs) {

        public QueueSnapshot {
            entries = List.copyOf(entries);
        }
    }

    /** 读队列的前 {@code limit} 个成员与各自的镜像分（S_SNAPSHOT，只读）。队列不存在回空快照。 */
    QueueSnapshot snapshot(QueueRef queue, int limit, Deadline d);

    /** 凑单把一个成员剔出队列的原因。 */
    enum DropReason {
        /** 票据缺失 / 不是 queued / 不属于这条队列：只摘队列项，不碰票。 */
        INVALID,
        /** 排队期间进了别的战斗（有战斗锁）：摘队列项并删票。 */
        IN_BATTLE,
        /** 位置记录是登出墓碑或不存在（M12）：摘队列项并删票。 */
        OFFLINE
    }

    /**
     * 有条件地把一个成员剔出队列（S_DROP），<b>不误伤期间重新入队的同一玩家</b>：
     * <ul>
     *   <li>玩家此刻的票不存在、不是 queued、或不属于 {@code queue} → 从队列与评分镜像里摘掉他（任何 {@code reason} 都如此；票不动）；</li>
     *   <li>票是 {@code queue} 里一张有效的 queued 票：{@code reason} 是 {@link DropReason#IN_BATTLE} / {@link DropReason#OFFLINE} 且票号等于
     *       {@code seenTicketId} → 删票并摘掉；否则（{@link DropReason#INVALID}，或票号已换 = 他重新排了一次）→ 什么都不做。</li>
     * </ul>
     * 只是「退避时间没到」的人不会被它剔除。
     *
     * @param seenTicketId 凑单校验时读到的票号；没读到票（{@link DropReason#INVALID}）传 null
     * @return true = 这个成员已从队列里摘掉；false = 没动（他现在是一张有效的 queued 票）
     */
    boolean drop(QueueRef queue, long playerId, DropReason reason, String seenTicketId, Deadline d);

    /**
     * 摘掉快照里不是合法玩家号的成员（{@link SnapshotEntry#playerId()} 为 0 的那种）：从队列里删掉全部等于 {@code member} 的项，顺带摘评分镜像；
     * 不碰任何票据。
     *
     * @return true = 队列里确实摘掉了至少一项
     */
    boolean dropMalformed(QueueRef queue, String member, Deadline d);

    /** 弹组的结局（三选一，调用方穷举）。 */
    sealed interface PopResult {

        /** 本次弹出：全员已离开队列、票已置 matched。 */
        record Popped() implements PopResult {
        }

        /** 同一个 {@code popToken} 已经弹过（自己上一次执行的重放）：按 {@link Popped} 处理。 */
        record Replayed() implements PopResult {
        }

        /**
         * 有人不满足条件：<b>什么都没写</b>，队列原样。{@code players} 是不满足的人（按入参顺序）——凑单对其中每人执行 {@link TicketStore#drop}
         * （按重新读到的情况给原因），然后在本轮内重挑（同一队列至多 3 次）。
         */
        record Invalid(List<Long> players) implements PopResult {

            public Invalid {
                players = List.copyOf(players);
            }
        }
    }

    /**
     * 原子弹组（S_POP）：逐个核对「在 {@code queue} 里、票是 queued、票号相等、票的队列键相符、退避时间已过」，<b>全部满足</b>才一次性把这些人
     * 摘出队列与评分镜像、票置 matched 并设 TTL、写下重放标记（60 s）；否则不写任何东西。队列锁过期、两个实例同时处理同一队列时靠它保证不双弹。
     *
     * @param popToken     这次弹组的唯一标识（调用方每次弹组生成一个随机串，重发时不变）
     * @param members      要弹的人及其票号（锚点在前，其余按选中顺序）；至少一人，玩家号不得重复
     * @param matchedTtlMs matched TTL（按组的人数）
     */
    PopResult pop(QueueRef queue, String popToken, List<TicketRef> members, long matchedTtlMs, Deadline d);

    // ================================================================ gather 的票据写（全部带票号 CAS）

    /**
     * 开局成功：票置 ready 并写 {@code battle_id}、TTL 改成 {@code readyTtlMs}（S_READY）。要求票号一致且票是 matched（或已经是同一个
     * {@code battle_id} 的 ready——重放）。
     *
     * @return true = 这张票现在是带这个 {@code battle_id} 的 ready；false = 没写（票已过期 / 已被新票替换 / 不是 matched）——只记日志，不影响开局
     */
    boolean markReady(TicketRef ticket, long battleId, long readyTtlMs, Deadline d);

    /**
     * 进补偿之前给幸存者续期（S_EXTEND）：票号一致且仍是 matched 的票，TTL 改成 {@code ttlMs}（可能比剩余的短也可能长，照设）。
     *
     * @return 续上的张数（只用于日志；重放时不变）
     */
    int extendMatched(List<TicketRef> tickets, long ttlMs, Deadline d);

    /**
     * 按票号删票（S_DEL）：票号一致就删，不看状态。不摘队列项——删的是 queued 票时，队列里的残留项由凑单的校验剔除。
     *
     * @return true = 本次删掉了；false = 没删（票已不在或票号不符，含重放）。调用方两种都按「这张票不再挡路」处理
     */
    boolean delete(TicketRef ticket, Deadline d);

    /**
     * 按票号删一组票（S_DEL_GROUP；一个原子操作，逐张「票号一致才删」）。
     *
     * @return 本次删掉的张数（只用于日志）
     */
    int deleteGroup(List<TicketRef> tickets, Deadline d);

    /**
     * 幸存者回队首（S_REQUEUE）：在<b>同一个</b>原子操作里，从名单末尾往前逐个处理「票号一致、仍是 matched、票的队列键就是 {@code queue}」的人——
     * 票回 queued、TTL 恢复成 {@code queuedTtlMs}、推到队首、按票里的评分写回镜像；最后登记注册集。处理完之后这些人在队列里的相对顺序
     * 与 {@code survivorsInOrder} 相同，且排在原有成员之前。{@code enqueued_at_ms} 不变。不满足条件的人跳过（票已过期 / 已换 / 已是 queued），
     * 不会留下「在队列里却没有 queued 票」或反过来的孤儿。某个人推进队列这一步失败时（只可能是队列键被人为占成别的类型），他的票被<b>删掉</b>
     * （让玩家可以立即重排，同基线），不计入返回值。
     *
     * <p><b>重放按 {@code requeueToken} 识别</b>（标记 60 s，同弹组）：同一个 token 再调一次不写任何东西、原样返回第一次的人数。
     * 不能只靠「票已经不是 matched」来认重放——回了队首的人可以在重发到达之前又被凑单弹成 matched（票号与队列都没变），那时旧的入参会重新满足条件，
     * 把正在下一次 gather 里的人再推回队首。标记在每个出口都写（含一个人都没放回去的 0）；名单为空时什么都不做、也不写标记。
     *
     * @param requeueToken     这一次回队首的唯一标识（调用方每次补偿生成一个随机串，重发时不变）；不得为空
     * @param survivorsInOrder 原弹出顺序里的幸存者；可以为空（什么都不做）；<b>玩家号不得重复</b>（重复是调用方的 bug，抛
     *                         {@link IllegalArgumentException}）
     * @param notBeforeDelayMs &gt; 0：这些票的 {@code not_before_ms} 置为 Redis 时间 + 它（无肇事者的失败，M11）；0：清掉 {@code not_before_ms}
     * @return 这一次回队首放回去的人数（只用于指标与日志；同一个 token 的重放返回第一次的那个数）
     */
    int requeueFront(QueueRef queue, String requeueToken, List<TicketRef> survivorsInOrder, long queuedTtlMs, long notBeforeDelayMs, Deadline d);

    // ================================================================ 注册集、深度、凑单锁

    /** 注册集的全部成员（队列键全文；用 {@link QueueRef#ofQueueKey} 解析，解析不了的由凑单告警跳过）。 */
    Set<String> queueIndex(Deadline d);

    /** 队列长度（LLEN；不存在为 0）。 */
    long queueLength(QueueRef queue, Deadline d);

    /**
     * 剔除空队列（S_PRUNE）：队列此刻为空才把它从注册集里拿掉（并清掉空的评分镜像）；与并发的入队互斥（入队会重新登记）。
     *
     * @return true = 已不在注册集里（本次拿掉，或本来就不在）；false = 队列非空，没动
     */
    boolean pruneIfEmpty(QueueRef queue, Deadline d);

    /**
     * 抢凑单锁（SET NX PX）。锁只是效率手段（避免多个实例同时扫同一条队列），正确性靠 {@link #pop}。
     *
     * @param instanceId 本进程实例的标识（释放时按它核对）
     * @return true = 抢到了；false = 别人持有（跳过本轮）
     */
    boolean tryLockQueue(QueueRef queue, String instanceId, long ttlMs, Deadline d);

    /** 按持有者释放凑单锁（S_LOCK_RELEASE）：锁的值不是 {@code instanceId}（已过期被别人抢走）时什么都不做。幂等。 */
    void unlockQueue(QueueRef queue, String instanceId, Deadline d);
}
