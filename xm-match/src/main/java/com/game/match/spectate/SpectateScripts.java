package com.game.match.spectate;

/**
 * 观战存储的 Lua（spectate-spec §4.3 的九段 S_W_*，外加两段只读的批读）。由 {@link RedissonSpectateStore} 执行；每段的 KEYS / ARGV / 返回值编码 /
 * 真值表 / <b>重放语义</b>写在各自的注释里，真 Redis 测试（{@code RedissonSpectateStoreIntegrationTest}）逐段钉住、每段可变脚本连跑两次。
 *
 * <p><b>通用约定</b>（与 {@code ticket.TicketScripts} 同一套）
 * <ul>
 *   <li><b>只碰 KEYS 里声明的键</b>：不在 Lua 里拼键名。三类键——观战标记、可观战索引、落点记录——与票据同在 {@code {match}} 一个槽（{@code RedisKeys}），
 *       多键脚本上 Cluster 仍同槽。KEYS 的次序：票据在标记之前；索引在落点之前（落点键是可选的那一把，放最后）。</li>
 *   <li><b>时间只取 Redis {@code TIME}</b>（过期分界、返回给调用方的「现在」），不收调用方的本机时钟（W13）。{@code TIME} 之后仍可写要求 Redis ≥ 7
 *       （按效果复制）。脚本里拼进命令的毫秒数一律 {@code string.format('%.0f', x)}。</li>
 *   <li><b>玩家号、战斗号、索引成员、标记值只当字符串</b>比较与传递，从不 {@code tonumber}（uint64 超出 double 精度）。转成数字的只有：
 *       ZSET 的分数与过期分界（毫秒，&lt; 2^53）、随机数 {@code r}、条数上限、过期时长。</li>
 *   <li><b>全部脚本（含只读的）以 READ_WRITE 执行 = 读主库</b>：主从部署下只读脚本缺省会被发到从库，那里的 {@code TIME} 与复制延迟下的标记 / 落点
 *       都不能和主库上写下的时刻、刚写下的标记放在一起判（163 要读得到自己上一条请求写的标记，开局清退要读得到 163 刚写的标记）。</li>
 *   <li><b>可重放</b>：Redisson 在响应超时后会把同一段 {@code EVAL} 原样重发（{@code xm.redis.retry-attempts = 1}），第一次可能已经执行。
 *       每段可变脚本执行两次，存储里的最终状态都与执行一次相同：抢标记靠值里的 nonce 认出「这就是我上次写的」，其余靠「按值 / 按条件」——
 *       条件在第一次之后不再成立。返回值在重放时可能与第一次不同（删、摘第一次回 1、重放回 0），调用方该怎么读写在 {@link SpectateStore} 各方法的注释里。</li>
 *   <li><b>先判定（只读）、后写</b>：Redis 的脚本不是事务，中途命令报错时已执行的写不回滚（只可能是键被人为占成别的类型）。每段脚本的写都排在
 *       全部读之后；有两条写的（「删落点 + 摘成员」）先把两把键都读过一次——类型不对的键要么在第一条写之前就让脚本报错，要么让唯一的那条写报错，
 *       都不会留下写了一半的状态。</li>
 *   <li>返回数组里不放 nil（Lua 数组遇 nil 截断）：缺失值用空串占位、另带 0 / 1 的「在不在」标志；回复是扁平数组（整数与字符串混排），不嵌套。</li>
 * </ul>
 * 落点 HASH 的字段名（{@code a} / {@code pb}）与 {@code placement.PlacementRecords} 的常量一致、四种剔除模式与 {@code MODE_*} 一致，
 * 由 {@code SpectateScriptsTest} 钉住。
 */
final class SpectateScripts {

    // ---------------------------------------------------------------- 返回值编码（Java 侧按这些常量解）

    /** S_W_ACQUIRE 的返回值：标记现在是本次的值（本次写入，或重放命中）。 */
    static final long ACQUIRE_OK = 0;
    /** S_W_ACQUIRE 的返回值：票据键存在，没有写。 */
    static final long ACQUIRE_QUEUED = 1;
    /** S_W_ACQUIRE 的返回值：标记已存在且是别的值，没有写。 */
    static final long ACQUIRE_BUSY = 2;

    /** 落点记录的状态（S_W_READ / S_W_RECORDS）：键不存在。 */
    static final long RECORD_ABSENT = 0;
    /** 落点记录的状态：键在（两个字段原样返回，缺的是空串；是不是好记录由 Java 侧的 {@code PlacementRecords.parse} 判）。 */
    static final long RECORD_PRESENT = 1;
    /** 落点记录的状态：键在，但不是 HASH（被人为占成了别的类型）。 */
    static final long RECORD_WRONG_TYPE = 2;

    /** S_W_EVICT 的模式：非法成员，无条件摘。 */
    static final String MODE_INVALID = "invalid";
    /** S_W_EVICT 的模式：缺记录，落点此刻仍不存在才摘成员；永不删落点。 */
    static final String MODE_MISSING = "missing";
    /** S_W_EVICT 的模式：房间已死，落点的 attempt 没变（或落点已不在）才删落点 + 摘成员。 */
    static final String MODE_DEAD = "dead";
    /** S_W_EVICT 的模式：分数过期，成员的分数仍小于分界才删落点 + 摘成员。 */
    static final String MODE_STALE = "stale";

    // ---------------------------------------------------------------- 共用片段

    /** 取 Redis 时间到局部变量 {@code now}（Unix 毫秒，Lua number）。 */
    static final String NOW = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            """;

    /**
     * 读一条落点 HASH：{@code record(key)} 返回三个值——状态（{@link #RECORD_ABSENT} / {@link #RECORD_PRESENT} / {@link #RECORD_WRONG_TYPE}）、
     * {@code a} 字段、{@code pb} 字段（缺的字段是空串）。用 {@code pcall}：键被占成别的类型时这一条回「类型不对」，不让整段脚本失败——
     * 批读里一条坏键不该让整页列表都读不出来。
     */
    private static final String RECORD = """
            local function record(key)
              local f = redis.pcall('HMGET', key, 'a', 'pb')
              if f.err then return 2, '', '' end
              if not f[1] and not f[2] and redis.call('EXISTS', key) == 0 then return 0, '', '' end
              return 1, f[1] or '', f[2] or ''
            end
            """;

    private SpectateScripts() {
    }

    // ================================================================ 观战标记

    /**
     * S_W_ENTRY（只读）：163 入口检查的一次原子读——同一时刻的「有没有票据」与「观战标记的值」。
     * <pre>
     * KEYS[1] = 票据   KEYS[2] = 观战标记
     * 返回 {有票, 有标记, 标记的值}：前两项是 0 / 1；没有标记时第三项是空串
     * </pre>
     * 票据只看键在不在（{@code EXISTS}），不解析：任意状态（queued / matched / ready）、损坏的 HASH、被占成别的类型的键都算「有」。
     * 标记原样返回（可能是解析不了的脏值，含空串——所以另带「有标记」标志，不用空串表示没有）。
     * 重放：只读，无副作用。
     */
    static final String ENTRY = """
            local has_ticket = redis.call('EXISTS', KEYS[1])
            local mark = redis.call('GET', KEYS[2])
            if mark then
              return {has_ticket, 1, mark}
            end
            return {has_ticket, 0, ''}
            """;

    /**
     * S_W_ACQUIRE：原子完成「没有票据 ∧ 没有标记 → 写标记」（W2：入口检查之后才建出的票据在这里拦住）。
     * <pre>
     * KEYS[1] = 票据   KEYS[2] = 观战标记
     * ARGV[1] = 本次的标记值（"&lt;battle_id&gt;:&lt;nonce&gt;"）   ARGV[2] = 标记的 TTL 毫秒
     * 返回 0 = 标记现在是 ARGV[1]   1 = 有票据，没写   2 = 标记是别的值，没写
     * </pre>
     * <table>
     *   <caption>真值表（自上而下，先命中先返回）</caption>
     *   <tr><td>票据键存在</td><td>1（哪怕标记就是自己的值：标记不动，由调用方按值释放）</td></tr>
     *   <tr><td>标记 = ARGV[1]</td><td>0（重放命中自己写下的值；<b>不刷新 TTL</b>）</td></tr>
     *   <tr><td>标记存在（别的值）</td><td>2</td></tr>
     *   <tr><td>没有标记</td><td>{@code SET … PX ARGV[2]}，0</td></tr>
     * </table>
     * 重放：值里带每次请求随机的 nonce，第二次看到的是自己第一次写的值 → 0，不重写、不续期，不会误报「已在观战」。
     * 两次之间建出了票据 → 1，首轮写下的标记还在——所以调用方拿到 1 也要按本次的值释放一次（S_W_RELEASE）。
     *
     * <p><b>这一段挡不住「迟到的重发」</b>（其余可变脚本都有条件可比，这里标记一旦被回滚就没有东西可比）：调用方等到截止、放弃并释放之后，
     * 重发的那一遍才到——看到「无票、无标记」又把标记写回。脚本自己不设防（那需要一把按 nonce 的墓碑键）；由 {@link RedissonSpectateStore#acquire}
     * 在在途的命令有了结局之后再按值释放一次来收窄，漏掉的留到 TTL（标记只是提示）。
     */
    static final String ACQUIRE = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return 1 end
            local cur = redis.call('GET', KEYS[2])
            if cur then
              if cur == ARGV[1] then return 0 end
              return 2
            end
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            return 0
            """;

    /**
     * S_W_RELEASE：按值删标记（W3）。回滚、复查后的自我清退、入口处删旧标记、开局清退都用它。
     * <pre>
     * KEYS[1] = 观战标记
     * ARGV[1] = 要删的值（整串比较；删脏标记时传读到的原值）
     * 返回 1 = 本次删掉了；0 = 标记不在或已是别的值，没动
     * </pre>
     * 重放：第二次标记已不在 → 0；两次之间别的请求抢到了新标记（别的 nonce）→ 值不等，不动。
     */
    static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /**
     * S_W_MARKS（只读）：一次读出一组玩家的观战标记（规格 §4.3；原稿写的是「一条 MGET」，写成脚本是为了读主库，并且只回有标记的人）。
     * <pre>
     * KEYS[i] = 第 i 名玩家的观战标记
     * 返回 {i, 值, j, 值, …}：只含有标记的玩家，i 是 KEYS 里的下标（从 1 起），按下标升序
     * </pre>
     * 重放：只读，无副作用。
     */
    static final String MARKS = """
            local out = {}
            for i = 1, #KEYS do
              local mark = redis.call('GET', KEYS[i])
              if mark then
                out[#out + 1] = i
                out[#out + 1] = mark
              end
            end
            return out
            """;

    // ================================================================ 落点记录 + 索引的原子读

    /**
     * S_W_READ（只读）：一场战斗在同一时刻的「是否已公开 + 落点记录 + Redis 时间」（W6：建房窗口判定的三个输入必须同源）。
     * <pre>
     * KEYS[1] = 可观战索引   KEYS[2] = 落点记录
     * ARGV[1] = 索引成员（battle_id 的无符号十进制）
     * 返回 {now, 已公开, 状态, a, pb}：已公开 = 0 / 1（成员在不在索引里）；状态见 RECORD_*；a / pb 缺失或状态不是「在」时是空串
     * </pre>
     * 不修任何东西：成员在而落点不在、落点损坏，都照实返回，由调用方决定剔不剔。重放：只读，无副作用。
     */
    static final String READ = NOW + RECORD + """
            local published = 0
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) then published = 1 end
            local state, a, pb = record(KEYS[2])
            return {now, published, state, a, pb}
            """;

    /**
     * S_W_RECORDS（只读）：批量读落点记录（规格 §4.5；原稿写的是「RBatch 取这批落点」，写成一段脚本：一次往返、读主库、分得清「键不在」与「键在但缺字段」，
     * 一条被占成别的类型的键只坏它自己）。各键不要求是同一时刻——恰好是，但调用方不依赖。
     * <pre>
     * KEYS[i] = 第 i 条落点记录
     * 返回 {状态_1, a_1, pb_1, 状态_2, a_2, pb_2, …}：每把键恰好三项，次序同 KEYS
     * </pre>
     * 重放：只读，无副作用。
     */
    static final String RECORDS = RECORD + """
            local out = {}
            for i = 1, #KEYS do
              local state, a, pb = record(KEYS[i])
              out[#out + 1] = state
              out[#out + 1] = a
              out[#out + 1] = pb
            end
            return out
            """;

    /**
     * S_W_PICK（只读）：在<b>未过期</b>的成员里按调用方给的随机数取一个（W8：过期成员不可能被挑中）。
     * <pre>
     * KEYS[1] = 可观战索引
     * ARGV[1] = r ∈ [0, 1)（十进制小数，Java 侧生成）   ARGV[2] = 过期时长毫秒（分数 &lt; now − 它 的成员算过期）
     * 返回 {now} = 没有未过期的成员；{now, 成员, 分数} = 挑中的那一个（成员原样，不保证是合法的 battle_id）
     * </pre>
     * 做法：{@code cutoff = now − ARGV[2]}；{@code n = ZCOUNT [cutoff, +inf)}；n = 0 → 空；否则按分数升序（同分按成员字典序）取第
     * {@code min(⌊r·n⌋, n − 1)} 个（{@code ZRANGEBYSCORE cutoff +inf WITHSCORES LIMIT idx 1}）。恰在分界上的成员（分数 = cutoff）还算活着。
     * 重放：只读，无副作用（重发时 {@code now} 变了，挑中的可能是另一个——无妨，r 本来就是随机的）。
     */
    static final String PICK = NOW + """
            local cutoff = string.format('%.0f', now - tonumber(ARGV[2]))
            local n = redis.call('ZCOUNT', KEYS[1], cutoff, '+inf')
            if n == 0 then return {now} end
            local idx = math.floor(tonumber(ARGV[1]) * n)
            if idx > n - 1 then idx = n - 1 end
            local hit = redis.call('ZRANGEBYSCORE', KEYS[1], cutoff, '+inf', 'WITHSCORES', 'LIMIT', idx, 1)
            if #hit < 2 then return {now} end
            return {now, hit[1], hit[2]}
            """;

    // ================================================================ 剔除与公开

    /**
     * S_W_EVICT：有条件的剔除，四种模式。条件在脚本里原子判定——调用方按自己读到的情况选模式，判定之后世界变了也不会删错。
     * <pre>
     * KEYS[1] = 可观战索引   [KEYS[2] = 落点记录（invalid 模式不给：非法成员没有对应的落点键）]
     * ARGV[1] = 'invalid' | 'missing' | 'dead' | 'stale'   ARGV[2] = 索引成员
     * ARGV[3] = dead：读到的那条落点的 attempt（无符号十进制，同 a 字段的写法）；stale：过期分界毫秒
     * 返回 1 = 真的摘掉了东西（索引成员或落点记录至少一样）；0 = 条件不成立，或本来就没有可摘的
     * </pre>
     * <table>
     *   <caption>真值表</caption>
     *   <tr><td>invalid</td><td>{@code ZREM} 成员；摘到回 1。不碰任何落点</td></tr>
     *   <tr><td>missing：落点键存在（读之后才预写出来的、或一直都在）</td><td>0，什么都不动</td></tr>
     *   <tr><td>missing：落点键不存在</td><td>{@code ZREM} 成员；摘到回 1。<b>这个模式永不删落点</b></td></tr>
     *   <tr><td>dead：落点键存在且 a ≠ ARGV[3]（被换节点改写过；或没有 a 字段，无从比对）</td><td>0，什么都不动（W7）</td></tr>
     *   <tr><td>dead：落点键存在且 a = ARGV[3]</td><td>{@code DEL} 落点 + {@code ZREM} 成员，1</td></tr>
     *   <tr><td>dead：落点键不存在</td><td>{@code ZREM} 成员；摘到回 1</td></tr>
     *   <tr><td>stale：成员不在索引里，或分数 ≥ ARGV[3]（恰在分界上不算过期）</td><td>0，什么都不动（不动落点）</td></tr>
     *   <tr><td>stale：成员的分数 &lt; ARGV[3]</td><td>{@code DEL} 落点 + {@code ZREM} 成员，1</td></tr>
     * </table>
     * 重放：幂等。第二次要么条件不再成立、要么已经没有可摘的 → 0；两次之间 gather 重新预写了同一场的落点（attempt 更大）→ dead 的条件不成立，不动。
     *
     * <p>每个模式都是「读完再写」：dead 先 {@code ZSCORE} 再判落点，哪一把键被占成了别的类型都在第一条写之前报错——不会出现「落点删了、成员没摘成」。
     * dead 模式落点键不是 HASH 时 {@code HGET} 报错、整段失败、什么都没写（163 只在读到好记录之后才用这个模式，正常走不到）。
     */
    static final String EVICT = """
            local mode = ARGV[1]
            if mode == 'invalid' then
              return redis.call('ZREM', KEYS[1], ARGV[2])
            end
            if mode == 'missing' then
              if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
              return redis.call('ZREM', KEYS[1], ARGV[2])
            end
            if mode == 'dead' then
              local listed = redis.call('ZSCORE', KEYS[1], ARGV[2])
              local exists = redis.call('EXISTS', KEYS[2]) == 1
              if exists and redis.call('HGET', KEYS[2], 'a') ~= ARGV[3] then return 0 end
              if not exists and not listed then return 0 end
              redis.call('DEL', KEYS[2])
              redis.call('ZREM', KEYS[1], ARGV[2])
              return 1
            end
            if mode == 'stale' then
              local score = redis.call('ZSCORE', KEYS[1], ARGV[2])
              if not score or tonumber(score) >= tonumber(ARGV[3]) then return 0 end
              redis.call('DEL', KEYS[2])
              redis.call('ZREM', KEYS[1], ARGV[2])
              return 1
            end
            return redis.error_reply('S_W_EVICT: unknown mode')
            """;

    /**
     * S_W_PUBLISH：开局成功后把这一场登记进可观战索引——落点的 attempt 等于这次开局的 attempt 才登记（「先有最终落点、再进索引」的顺序不变量：
     * 索引里出现的成员，其落点一定是最终那一次写的）。
     * <pre>
     * KEYS[1] = 可观战索引   KEYS[2] = 落点记录
     * ARGV[1] = 这次开局的 attempt（无符号十进制）   ARGV[2] = 索引成员   ARGV[3] = 分数（created_at_ms，十进制）
     * 返回 1 = 现在在索引里（本次登记，或重放时已经在）；0 = 条件不成立（落点不在、没有 a 字段、或 a 是别的 attempt），没有登记
     * </pre>
     * 重放：同一成员同一分数再 {@code ZADD} 一遍，状态不变，仍回 1。
     */
    static final String PUBLISH = """
            if redis.call('HGET', KEYS[2], 'a') ~= ARGV[1] then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
            return 1
            """;

    // ================================================================ 清扫、列表

    /**
     * S_W_SWEEP：摘掉索引里分数过期的成员（{@code ZREMRANGEBYSCORE -inf (now − ARGV[1])}：严格小于分界，恰在分界上的留着）。
     * <b>只摘成员</b>，不碰落点记录（落点靠自己的 TTL 过期）。
     * <pre>
     * KEYS[1] = 可观战索引
     * ARGV[1] = 过期时长毫秒
     * 返回这一次摘掉的成员数
     * </pre>
     * 重放：幂等；第二次没有可摘的（或只摘到两次之间新过期的）→ 返回值偏小，只进日志。多实例重复执行同样无害。
     */
    static final String SWEEP = NOW + """
            return redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', string.format('(%.0f', now - tonumber(ARGV[1])))
            """;

    /**
     * S_W_LIST（只读）：索引里分数最高的至多 ARGV[1] 个成员，连同 Redis 时间（过期分界由调用方拿它算）。
     * <pre>
     * KEYS[1] = 可观战索引
     * ARGV[1] = 条数上限（≥ 1）
     * 返回 {now, 成员, 分数, 成员, 分数, …}：分数降序、同分按成员字符串降序（{@code ZREVRANGE} 的次序）；<b>含过期与非法的成员</b>
     * </pre>
     * 重放：只读，无副作用。
     */
    static final String LIST = NOW + """
            local hits = redis.call('ZREVRANGE', KEYS[1], 0, tonumber(ARGV[1]) - 1, 'WITHSCORES')
            local out = {now}
            for i = 1, #hits do
              out[#out + 1] = hits[i]
            end
            return out
            """;
}
