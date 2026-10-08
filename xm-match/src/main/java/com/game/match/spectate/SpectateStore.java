package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 观战的存储口（spectate-spec §4.2、§4.3）：<b>观战标记</b>（{@code RedisKeys.matchWatching(player)}，STRING，PX 360 s：这名玩家可能正在看哪一场）、
 * <b>可观战索引</b>（{@code RedisKeys.matchWatchable()}，ZSET：成员 = battle_id 无符号十进制，分数 = {@code created_at_ms}），以及对 6.4 的
 * <b>落点记录</b>（{@code RedisKeys.matchBattlePlacement(battle)}，HASH）的原子读与两种有条件的删除。三类键与票据同在 {@code {match}} 槽，
 * 每个方法对应一段 Lua（规格 §4.3 的九段 S_W_*，外加两段只读的批读 S_W_MARKS / S_W_RECORDS），只有 {@link #watchableCount} 是一条普通命令；
 * 脚本一律按读写模式发出（只读的也读主库）。使用者：163（{@code WatchBattleService}）、164（{@code WatchableListService}）、
 * 开局钩子（{@code SpectateGatherHooks}）、清扫器（{@code SpectateSweeper}）。生产实现 {@code RedissonSpectateStore}；
 * 测试替身 {@code testing.InMemorySpectateStore}（与内存版票据 / 落点存储共享状态），两者跑同一套契约测试 {@code SpectateStoreContract}。
 *
 * <p><b>对全部带 {@link Deadline} 参数的方法成立的契约</b>：
 * <ul>
 *   <li><b>阻塞</b>：在调用线程上等 Redis，<b>至多等到 {@code d}</b>。在 163 的虚拟线程、{@code match-worker}、gather 的虚拟线程、清扫线程上调
 *       （实现只在 future 上等，不得在 {@code synchronized} 块里阻塞）；不得在 Dubbo / Netty I/O 线程上调。线程安全。</li>
 *   <li><b>失败一律抛 {@link Deadline.DependencyException}</b>，绝不把故障折成「没有」「没摘到」这类正常返回值：
 *       <ul>
 *         <li>{@code d} 进来时已过：<b>不发命令</b>直接抛——什么都没读、什么都没写；</li>
 *         <li>Redis 出错：抛；</li>
 *         <li>等到 {@code d} 还没有应答：抛。<b>可变操作这时结局不明</b>（命令可能已经执行）——各方法的注释写了调用方该怎么收场。</li>
 *       </ul>
 *       怎么对客户端交代由调用方定：163 回 in-band 16004；164 读索引失败回信封 1003、批读落点失败回变短的列表；钩子与清扫只记日志与指标。</li>
 *   <li><b>可重放</b>：Redis 客户端会在超时后自动重发一次命令，所以每个可变操作连续执行两次，存储里的最终状态都与执行一次相同
 *       （{@link #acquire} 靠值里的 nonce，其余靠「按值 / 按条件」）。返回值是否也稳定见各方法。</li>
 *   <li><b>时间</b>：凡是涉及「现在」的判定（过期分界、TTL）都用 Redis 自己的 {@code TIME}，返回里带的 {@code redisNowMs} 也是它（Unix 毫秒），
 *       不用本机时钟（W13）。</li>
 *   <li><b>id</b>：玩家号、战斗号是无符号 64 位，按位模式放在 {@code long} 里；进 Redis 一律 {@code Long.toUnsignedString}。
 *       索引成员的规范写法、标记值的编解码见 {@link SpectateRules}。</li>
 * </ul>
 * 两个 {@code *Async} 方法是<b>尽力而为</b>的：发出即返回、不等结果、<b>永不抛异常</b>，失败只记日志。
 *
 * <p>本接口不计任何指标：出口计数（163 / 164 / 清退 / 剔除 / 异常）由调用方按 {@code MatchMetrics} 各枚举的口径记。
 */
public interface SpectateStore {

    // ================================================================ 观战标记

    /**
     * 入口检查的一次原子读（S_W_ENTRY）。
     *
     * @param hasTicket 票据键此刻存在（任意状态：queued / matched / ready；只看键在不在，不解析——损坏的票据 HASH 也算「有」）
     * @param mark      观战标记此刻的值（原样；可能是解析不了的脏值）；没有标记为空
     */
    record Entry(boolean hasTicket, Optional<String> mark) {

        public Entry {
            Objects.requireNonNull(mark, "mark");
        }
    }

    /** S_W_ENTRY（只读）：同一时刻的「有没有票据」与「观战标记的值」。163 的第 2、3、6、7 行靠它。 */
    Entry entry(long playerId, Deadline d);

    /** 抢标记的结果。 */
    enum Acquire {
        /** 标记现在是 {@code markValue}：本次写入，或者它本来就等于 {@code markValue}（同一次请求被重放）。TTL 是本次或首次写入时设的 360 s。 */
        OK,
        /** 票据键存在：没有写标记（163 回 16014，不调 AddObserver，W2）。<b>调用方仍要按本次的值 {@link #release} 一次</b>——
         * 命令被重发时首轮可能已经写入标记、两轮之间才建出票据。 */
        QUEUED,
        /** 标记已存在且是别的值：同一玩家的另一条并发 163 抢先了（163 回 16016）。没有写任何东西。 */
        BUSY
    }

    /**
     * S_W_ACQUIRE：原子完成「没有票据 ∧ 没有标记 → {@code SET} 标记，PX {@code MatchBudgets.WATCHING_TTL_SECONDS}」。
     * 判定顺序：票据存在 → {@link Acquire#QUEUED}；标记等于 {@code markValue} → {@link Acquire#OK}（不刷新 TTL）；标记存在 → {@link Acquire#BUSY}；否则写入 → OK。
     *
     * <p>可重放：值里带每次请求随机生成的 nonce，重发命中自己首轮写下的值回 OK，不会误报 BUSY。
     * <b>抛异常（含等到 {@code d}）时结局不明</b>：标记可能已经写下——调用方尽力 {@link #releaseAsync}(本次的值) 再回 16004，
     * <b>这个 {@code markValue} 从此作废</b>（不要拿同一个值再抢）。等到 {@code d} 而命令还在路上的那一种，调用方当场发的释放可能先于客户端
     * 重发的那一遍到达 Redis（次序成了「首发、释放、重发」，重发又把标记写回）：Redis 实现会在在途的命令有了结局之后<b>再按值释放一次</b>。
     * 这仍是尽力而为——最后一遍在客户端判它超时之后才被 Redis 执行的话，标记留到 TTL；它只是提示，下一次 163 的第 7 行或开局清退会清掉
     * （多一次空操作的 RemoveObserver）。
     *
     * @param markValue {@link SpectateRules#encodeMark} 编出来的整串（非空）
     */
    Acquire acquire(long playerId, String markValue, Deadline d);

    /**
     * S_W_RELEASE：标记的值<b>等于</b> {@code markValue} 才删（W3：两条并发 163 不会删掉对方刚抢到的标记）。回滚、复查后的自我清退、
     * 入口处删旧标记、开局清退都用它；删旧标记时传 {@link Entry#mark()} / {@link #marksOf} 读到的原值（脏值也按原样传）。
     *
     * @param markValue 要删的那个值，<b>只拒 null</b>：空串合法（读到空串的脏标记 = 有标记，要能按原串删掉）。必须是读到的原串，
     *                  不要 trim、不要重新编码
     * @return true = 这一次删掉了；false = 标记不在或已是别的值（没动）。幂等；被重发时可能把一次成功的删除报成 false，
     *         所以返回值只用于日志与测试断言，不要拿它做业务判定
     */
    boolean release(long playerId, String markValue, Deadline d);

    /**
     * {@link #release} 的尽力版：发出即返回，不占调用线程，失败只记日志。给「应答已经定了、不值得再等一次 Redis」的地方用。
     * {@code markValue} 同样只拒 null（空串合法）。
     */
    void releaseAsync(long playerId, String markValue);

    /**
     * 一次读出一组玩家的观战标记（一段只读脚本 S_W_MARKS，一次往返，同槽）：开局清退在第一次备战之前调一次。
     *
     * @param playerIds 参战名单（可以为空：返回空表、不发命令）
     * @return 只含<b>有标记</b>的玩家；键是入参里的玩家号，值是标记的原值（可能是脏值，含空串）。任何失败整体抛异常，不返回半份结果
     */
    Map<Long, String> marksOf(List<Long> playerIds, Deadline d);

    // ================================================================ 落点记录 + 索引的原子读

    /** 一条落点记录读出来的样子（三选一，调用方穷举）。 */
    sealed interface Record {

        /**
         * 记录在，是一条好记录。保证 {@code placement.getBattleId()} 等于所问的 battle_id，且 {@code placement.getAttempt()} 等于 HASH 里的
         * attempt 字段——所以可以直接拿它去做 {@link Eviction.Dead} 的守护与 {@link #publish} 的条件。
         */
        record Found(BattlePlacement placement) implements Record {

            public Found {
                Objects.requireNonNull(placement, "placement");
            }
        }

        /** 键不存在（没开过这一局、已删除或 TTL 已过）。 */
        record Absent() implements Record {
        }

        /**
         * 键在，但不是一条好记录：键被占成了别的类型（不是 HASH），或 {@code PlacementRecords.parse(battleId, a, pb)} 判为损坏
         * （缺字段、解析失败、battle_id 与键不符、attempt 字段与消息不一致）。正常写者不会产生。调用方：163 回 16004、列表跳过，<b>都不剔除</b>（BW9 / W15），
         * 计 {@code watchable_anomalies{corrupt_record}}。{@code why} 只进日志。
         */
        record Corrupt(String why) implements Record {
        }
    }

    /**
     * 一场战斗在同一时刻的三样东西（W6）。
     *
     * @param published  读的那一刻这一场在不在可观战索引里（= 开局已成功并公开过）
     * @param record     落点记录
     * @param redisNowMs 读的那一刻的 Redis 时间：建房窗口判定的 {@code checkedAtMs}（{@link SpectateRules#roomMayBeCreating}）
     */
    record Snapshot(boolean published, Record record, long redisNowMs) {

        public Snapshot {
            Objects.requireNonNull(record, "record");
        }
    }

    /** S_W_READ（只读）：原子取「是否已公开 + 落点记录 + Redis 时间」。163 选中一场之后、抢标记之前调。 */
    Snapshot read(long battleId, Deadline d);

    /** 随机选场的结果（二选一）。 */
    sealed interface Pick {

        /**
         * 挑中的索引成员：<b>原样</b>（不保证是合法的 battle_id——调用方用 {@link SpectateRules#parseMember} 解析，
         * 解析不了就 {@link Eviction.Invalid} 之后重挑）。{@code score} 是它的分数（{@code created_at_ms}），保证没有过期。
         */
        record Member(String member, long score, long redisNowMs) implements Pick {

            public Member {
                Objects.requireNonNull(member, "member");
            }
        }

        /** 索引里没有未过期的成员。 */
        record None(long redisNowMs) implements Pick {
        }
    }

    /**
     * S_W_PICK（只读）：在<b>未过期</b>的成员（分数 ≥ {@link SpectateRules#staleCutoff}(Redis 时间)）里按 {@code r} 取一个——设未过期的有 n 个，
     * 按分数升序（同分按成员字典序）取第 {@code min(⌊r·n⌋, n − 1)} 个；n = 0 回 {@link Pick.None}。过期成员永远不会被挑中（W8）。
     *
     * @param r [0, 1) 里的随机数，由调用方生成（生产 {@code ThreadLocalRandom}；测试给定值）；越界是调用方的错
     */
    Pick pickRandom(double r, Deadline d);

    // ================================================================ 剔除与公开

    /**
     * 一次剔除的模式（S_W_EVICT 的四种；规格 §4.3）。条件都在脚本里原子判定——调用方按自己读到的情况选模式，判定之后世界变了也不会删错。
     */
    sealed interface Eviction {

        /** 这一次剔除针对的索引成员。 */
        String member();

        /** 非法成员（不是规范的 battle_id）：无条件 {@code ZREM} 这个成员。不碰任何落点记录。 */
        record Invalid(String member) implements Eviction {

            public Invalid {
                Objects.requireNonNull(member, "member");
            }
        }

        /**
         * 读到落点记录不在：<b>落点此刻仍不存在</b>才 {@code ZREM}。<b>永不删落点</b>——读之后才预写的记录（一场正在建房的战斗）不能动，
         * 这时成员也留着（规格 §7.1 第 2 条）。
         */
        record Missing(long battleId) implements Eviction {

            @Override
            public String member() {
                return SpectateRules.member(battleId);
            }
        }

        /**
         * 房间确认不在（battle 回「房间不存在」或直拨判死，且已出建房窗口）：落点的 attempt 仍等于 {@code attempt}（或落点已经不在）
         * 才「删落点 + {@code ZREM}」；落点被改写过（attempt 变了：换节点重试）就什么都不动（W7）。
         *
         * @param attempt 读到的那条落点记录的 attempt（{@link Record.Found} 的 {@code placement.getAttempt()}；≥ 1）
         */
        record Dead(long battleId, int attempt) implements Eviction {

            public Dead {
                if (attempt == 0) {
                    throw new IllegalArgumentException("attempt 不能为 0");
                }
            }

            @Override
            public String member() {
                return SpectateRules.member(battleId);
            }
        }

        /**
         * 列表里读到分数已过期的成员：成员的分数此刻仍 {@code < cutoffMs} 才「删落点 + {@code ZREM}」（成员已不在、或分数被改大了就不动）。
         *
         * @param cutoffMs 过期分界（{@link SpectateRules#staleCutoff}({@link Listed#redisNowMs()})）
         */
        record Stale(long battleId, long cutoffMs) implements Eviction {

            @Override
            public String member() {
                return SpectateRules.member(battleId);
            }
        }
    }

    /**
     * S_W_EVICT：按 {@code e} 的模式有条件地剔除。
     *
     * @return true = 条件成立并且真的摘掉了东西（索引成员或落点记录至少一样）；false = 条件不成立、或本来就没有可摘的。
     *         幂等；被重发时可能把一次成功的剔除报成 false。只用于计数（{@code watchable_index_evictions}）与日志
     */
    boolean evict(Eviction e, Deadline d);

    /**
     * {@link #evict} 的尽力批量版：发出即返回，不占调用线程，逐条互不影响，失败只记日志。164 在应答组好之后用它
     * （剔除不阻塞应答，规格 §4.5）；一批至多 {@code MatchBudgets.WATCHABLE_LIST_MAX} 条。空表什么都不做。
     */
    void evictAsync(List<Eviction> batch);

    /**
     * S_W_PUBLISH：落点记录的 attempt 等于 {@code placement.getAttempt()} 才把这一场登记进索引
     * （{@code ZADD} 成员 = battle_id，分数 = {@code placement.getCreatedAtMs()}）。开局钩子 {@code onStarted} 调它：
     * 「先有最终落点、再进索引」的顺序不变量靠这个条件保证。
     *
     * @param placement 这一局最终的落点记录（gather 交给 {@code onStarted} 的那一条）
     * @return true = 现在在索引里（本次登记，或重放时已经在）；false = 条件不成立（落点不在、或已被别的 attempt 改写），没有登记。
     *         幂等、可重放（同一分数重复 {@code ZADD}）
     */
    boolean publish(BattlePlacement placement, Deadline d);

    // ================================================================ 列表、清扫

    /** 索引里的一个成员与它的分数。成员原样（可能不是合法的 battle_id）。 */
    record Scored(String member, long score) {

        public Scored {
            Objects.requireNonNull(member, "member");
        }
    }

    /**
     * 列表的一页。
     *
     * @param members    分数降序（同分按成员字符串降序，即 {@code ZREVRANGE} 的次序）；<b>含过期与非法的成员</b>，由调用方逐条判定
     * @param redisNowMs 读的那一刻的 Redis 时间（过期分界由它算）
     */
    record Listed(List<Scored> members, long redisNowMs) {

        public Listed {
            members = List.copyOf(members);
        }
    }

    /**
     * S_W_LIST（只读）：索引里分数最高的至多 {@code limit} 个成员，连同 Redis 时间。
     *
     * @param limit 条数上限（≥ 1；调用方先用 {@link SpectateRules#clampLimit} 收口）
     */
    Listed list(int limit, Deadline d);

    /**
     * 批量读落点记录（一段只读脚本 S_W_RECORDS，一次往返；不要求彼此是同一时刻）。164 用它取这一页成员的记录。
     * 某一把键被占成别的类型只坏它自己（那一条是 {@link Record.Corrupt}），不让整页读不出来。
     *
     * @param battleIds 要读的战斗号（可以为空：返回空表、不发命令）；重复的只读一次
     * @return 入参里<b>每个</b>不同的战斗号各一条（{@link Record.Found} / {@link Record.Absent} / {@link Record.Corrupt}）。
     *         整批失败抛异常（164 把它当「每条都读失败」：回变短的列表，不回 1003）
     */
    Map<Long, Record> readPlacements(List<Long> battleIds, Deadline d);

    /**
     * S_W_SWEEP：摘掉索引里分数 {@code <} {@link SpectateRules#staleCutoff}(Redis 时间) 的成员（{@code ZREMRANGEBYSCORE}）。<b>只摘成员</b>，
     * 落点记录靠自己的 TTL 过期。多实例重复执行幂等。
     *
     * @return 这一次摘掉的成员数（≥ 0；被重发时可能偏小）
     */
    long sweep(Deadline d);

    /** 索引此刻的大小（{@code ZCARD}；含还没被清扫的过期成员）。清扫器每轮采样 {@code xm_match_watchable_battles}。 */
    long watchableCount(Deadline d);
}
