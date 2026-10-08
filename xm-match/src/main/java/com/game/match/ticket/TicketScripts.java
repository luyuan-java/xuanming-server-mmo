package com.game.match.ticket;

/**
 * 票据状态机与队列原语的 15 段 Lua（match-spec §9.4 的脚本表；基线 {@code go/match/internal/logic/queue.go:70-181}、{@code matcher.go:24-44}）。
 * 由 {@link RedissonTicketStore} 执行；每段的 KEYS / ARGV / 返回值与<b>重放语义</b>写在各自的注释里，真 Redis 测试
 * （{@code RedissonTicketStoreIntegrationTest}）逐段钉住、每段可变脚本连跑两次。
 *
 * <p><b>通用约定</b>
 * <ul>
 *   <li><b>只碰 KEYS 里声明的键</b>：不在 Lua 里用 ARGV 拼键名、不读票据里存的 {@code queue_key} 去访问别的键——票据的队列键只拿来和传入的
 *       {@code KEYS} <b>比较</b>。全部键同在 {@code {match}} 一个槽（{@code RedisKeys}），将来上 Cluster 这些多键脚本仍同槽。</li>
 *   <li><b>时间只取 Redis {@code TIME}</b>（{@code enqueued_at_ms}、{@code not_before_ms}、退避是否到点），不收调用方的本机时钟（M7）。
 *       {@code TIME} 之后仍可写要求 Redis ≥ 7（按效果复制）。写回的毫秒数一律 {@code string.format('%.0f', x)}。</li>
 *   <li><b>玩家号、票号、队伍号、战斗号只当字符串</b>比较与传递，从不 {@code tonumber}（uint64 超出 double 精度）。</li>
 *   <li><b>全部脚本（含只读的两段）以 READ_WRITE 执行 = 读主库</b>：主从部署下只读脚本缺省会被发到从库，那里的 {@code TIME} 与复制延迟下的票据
 *       都不能和主库上写下的时刻放在一起比较；查状态也要读得到自己刚写的票（同 {@code BattleRedis} 的做法）。</li>
 *   <li><b>票据的写一律带票号 CAS</b>（I4）：{@code HGET ticket} 不等于传入的票号就什么都不写。</li>
 *   <li><b>可重放</b>（M6）：Redisson 在响应超时后会把同一段 {@code EVAL} 原样重发（{@code xm.redis.retry-attempts = 1}），第一次可能已经执行。
 *       每段可变脚本的第二次执行都不产生第二份效果：建票按票号识别「这就是我上次写的」、弹组与回队首按 token 标记（这两段的 CAS 条件
 *       在第一次之后还能重新成立：弹出的人会回队首、回了队首的人会再被弹出）、其余靠 CAS 条件在第一次之后不再成立。
 *       返回值在重放时可能与第一次不同（例如删票第一次回 1、重放回 0），调用方该怎么读写在 {@code TicketStore} 各方法的注释里。</li>
 *   <li><b>中途出错不留孤儿票</b>：Redis 的脚本不是事务，中途命令报错（只可能是键被人为占成别的类型）时已执行的写不回滚。
 *       所以一律「先判定（只读）、后写」，并且入队类脚本<b>先写队列、后写票</b>——出错时最多留下一个没有票的队列项（凑单的校验会剔掉），
 *       不会留下「票是 queued、人不在队列」这种没人收拾的状态。回队首对 {@code LPUSH} 的失败单独兜底（删票，同基线）。</li>
 *   <li>返回数组里不放 nil（Lua 数组遇 nil 截断），缺失值用空串占位；回复是扁平数组（整数与字符串混排），不嵌套。</li>
 * </ul>
 * 票据 HASH 的字段见 {@link TicketCodec}；这里照写成字面量，{@code TicketScriptsTest} 钉住两边一致。
 */
final class TicketScripts {

    /** 取 Redis 时间到局部变量 {@code now}（Unix 毫秒，Lua number）。 */
    private static final String NOW = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            """;

    private TicketScripts() {
    }

    /**
     * S_STATUS（只读）：一把或多把票据键的全量读 + 同一时刻的 Redis 时间。{@code read} / {@code readAll} / {@code status} 共用。
     * <pre>
     * KEYS[i] = 第 i 名玩家的票
     * 返回 {now, n_1, 字段, 值, …（共 n_1 项）, n_2, …}：n_i = 第 i 把键的 HGETALL 项数（0 = 没有票）
     * </pre>
     * 重放：只读，无副作用。
     */
    static final String STATUS = NOW + """
            local out = {now}
            for i = 1, #KEYS do
              local h = redis.call('HGETALL', KEYS[i])
              out[#out + 1] = #h
              for j = 1, #h do
                out[#out + 1] = h[j]
              end
            end
            return out
            """;

    /**
     * S_HEAL：条件删掉一张残留票（排队第 5 步与成员预检的自愈）。
     * <pre>
     * KEYS[1] = 票   KEYS[2] = 该票的队列（只在孤儿模式、且读到的 queue_key 是规范的队列键时给）
     * ARGV[1] = 读到的票号   ARGV[2] = 'ready' | 'orphan'   ARGV[3] = 成员串   ARGV[4] = 读到的 queue_key
     * 返回 1 = 这名玩家此刻没有票（本次删掉，或调用时已不在）；0 = 票还在、条件不满足，什么都没写
     * </pre>
     * <table>
     *   <caption>真值表</caption>
     *   <tr><td>键不存在</td><td>1</td></tr>
     *   <tr><td>票号 ≠ ARGV[1]（含 HASH 损坏、没有票号）</td><td>0</td></tr>
     *   <tr><td>ready 模式：state = ready</td><td>DEL，1；其余 state → 0</td></tr>
     *   <tr><td>orphan 模式：state ≠ queued，或 queue_key ≠ ARGV[4]</td><td>0</td></tr>
     *   <tr><td>orphan 模式、给了 KEYS[2]：LPOS 找得到这个人</td><td>0（在队列里 = 在途）</td></tr>
     *   <tr><td>orphan 模式：找不到，或没给 KEYS[2]（queue_key 不是规范的队列键 → 一律算孤儿）</td><td>DEL，1</td></tr>
     * </table>
     * 重放：第二次键已不在 → 1，与第一次相同。两次之间玩家重排了一张新票 → 0（票号不符），调用方按在途处理。
     */
    static final String HEAL = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 1 end
            if redis.call('HGET', KEYS[1], 'ticket') ~= ARGV[1] then return 0 end
            local state = redis.call('HGET', KEYS[1], 'state')
            if ARGV[2] == 'ready' then
              if state ~= 'ready' then return 0 end
            else
              if state ~= 'queued' then return 0 end
              if (redis.call('HGET', KEYS[1], 'queue_key') or '') ~= ARGV[4] then return 0 end
              if KEYS[2] and redis.call('LPOS', KEYS[2], ARGV[3]) then return 0 end
            end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    /**
     * S_JOIN：单人建票，玩家没有票才建（I3）。两种形态：入队（给 4 把键：queued 票 + 登记注册集 + 入队尾 + 写评分镜像，I1 / I2）与
     * 不入队（只给票键：直接建 matched 票，PVE_SOLO）。
     * <pre>
     * KEYS[1] = 票   [KEYS[2] = 注册集   KEYS[3] = 队列   KEYS[4] = 评分镜像]
     * ARGV[1] = 票号   ARGV[2] = mode   ARGV[3] = config   ARGV[4] = zone   ARGV[5] = rating_centi   ARGV[6] = 票的 TTL 毫秒   ARGV[7] = 成员串
     * 返回 {0, enqueued_at_ms} 本次建成 | {1, ''} 已有同一票号的票（重放）| {2, 现有票号} 已有别的票，什么都没写 | {3, ''} 键存在却没有票号（损坏）
     * </pre>
     * 重放：第二次看到的是自己第一次写的票（票号相同）→ {1}，不再入队、不改 TTL——不会被当成并发的后到者回假的「已在队列中」（修基线 B11）。
     * 两次之间这张票被弹走（matched）也仍是 {1}（票号没变）。写的次序：先队列、后票（见类注释）。
     */
    static final String JOIN = """
            local cur = redis.call('HGET', KEYS[1], 'ticket')
            if cur then
              if cur == ARGV[1] then return {1, ''} end
              return {2, cur}
            end
            if redis.call('EXISTS', KEYS[1]) == 1 then return {3, ''} end
            local t = redis.call('TIME')
            local now = string.format('%.0f', t[1] * 1000 + math.floor(t[2] / 1000))
            local state = 'matched'
            local queue = ''
            if KEYS[3] then
              state = 'queued'
              queue = KEYS[3]
              redis.call('SADD', KEYS[2], queue)
              redis.call('RPUSH', queue, ARGV[7])
              redis.call('ZADD', KEYS[4], ARGV[5], ARGV[7])
            end
            redis.call('HSET', KEYS[1], 'ticket', ARGV[1], 'mode', ARGV[2], 'config', ARGV[3], 'state', state,
                'enqueued_at_ms', now, 'zone_id', ARGV[4], 'queue_key', queue, 'rating_centi', ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            return {0, now}
            """;

    /**
     * S_CREATE_GROUP：原子地给一组人各建一张 matched 票（整队开战、活动开战；不入队）。
     * <pre>
     * KEYS[i] = 第 i 名成员的票
     * ARGV[1] = mode   ARGV[2] = config   ARGV[3] = team_id（'0' = 不写）   ARGV[4] = TTL 毫秒   ARGV[5] = rating_centi
     * ARGV[4 + 2i] = 第 i 名成员的票号   ARGV[5 + 2i] = 第 i 名成员的 zone
     * 返回 0 = 全员建成（或重放）；i ≥ 1 = 名单序第一个冲突者的下标，什么都没写
     * </pre>
     * 逐人：没有票 → 建；已有本次票号的票 → 当作已建，不重写、不续期；已有别的票（或键存在却没有票号）→ 冲突。先整轮判定、后整轮写：
     * 只要有一个冲突就一张都不写。重放：全员都是本次票号 → 0，没有任何写。
     */
    static final String CREATE_GROUP = """
            local n = #KEYS
            for i = 1, n do
              local cur = redis.call('HGET', KEYS[i], 'ticket')
              if cur then
                if cur ~= ARGV[4 + 2 * i] then return i end
              elseif redis.call('EXISTS', KEYS[i]) == 1 then
                return i
              end
            end
            local t = redis.call('TIME')
            local now = string.format('%.0f', t[1] * 1000 + math.floor(t[2] / 1000))
            for i = 1, n do
              if redis.call('EXISTS', KEYS[i]) == 0 then
                redis.call('HSET', KEYS[i], 'ticket', ARGV[4 + 2 * i], 'mode', ARGV[1], 'config', ARGV[2], 'state', 'matched',
                    'enqueued_at_ms', now, 'zone_id', ARGV[5 + 2 * i], 'queue_key', '', 'rating_centi', ARGV[5])
                if ARGV[3] ~= '0' then
                  redis.call('HSET', KEYS[i], 'team_id', ARGV[3])
                end
                redis.call('PEXPIRE', KEYS[i], ARGV[4])
              end
            end
            return 0
            """;

    /**
     * S_CANCEL：取消排队——票号一致、仍是 queued、票的队列键就是 KEYS[2]，才删票并从队列与评分镜像里摘掉（I5：取消太迟由这里保证）。
     * <pre>
     * KEYS[1] = 票   KEYS[2] = 队列   KEYS[3] = 评分镜像
     * ARGV[1] = 票号   ARGV[2] = 成员串
     * 返回 1 = 本次删掉了；0 = 没删（票不在 / 票号不符 / 已被弹走 / 队列不符），什么都没写
     * </pre>
     * 重放：第二次票已不在 → 0；调用方两种结果都按「取消成功」回。
     */
    static final String CANCEL = """
            if redis.call('HGET', KEYS[1], 'ticket') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'queued' then return 0 end
            if (redis.call('HGET', KEYS[1], 'queue_key') or '') ~= KEYS[2] then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('LREM', KEYS[2], 0, ARGV[2])
            redis.call('ZREM', KEYS[3], ARGV[2])
            return 1
            """;

    /**
     * S_SNAPSHOT（只读）：队列前缀 + 各成员的镜像分 + 同一时刻的 Redis 时间（基线 {@code queueSnapshotScript}）。
     * <pre>
     * KEYS[1] = 队列   KEYS[2] = 评分镜像
     * ARGV[1] = 前缀长度（≥ 1）
     * 返回 {now, 成员, 分, 成员, 分, …}：队首在前；镜像里没有这个成员时「分」是空串
     * </pre>
     * 重放：只读，无副作用。
     */
    static final String SNAPSHOT = NOW + """
            local members = redis.call('LRANGE', KEYS[1], 0, tonumber(ARGV[1]) - 1)
            local out = {now}
            for _, m in ipairs(members) do
              out[#out + 1] = m
              out[#out + 1] = redis.call('ZSCORE', KEYS[2], m) or ''
            end
            return out
            """;

    /**
     * S_DROP：凑单有条件地把一个成员剔出队列，不误伤期间重新入队的同一玩家。两种形态：带票键（合法玩家号）与不带票键（非法成员串）。
     * <pre>
     * KEYS[1] = 队列   KEYS[2] = 评分镜像   [KEYS[3] = 这名玩家的票]
     * ARGV[1] = 成员串   ARGV[2] = 'invalid' | 'in_battle' | 'offline'   ARGV[3] = 凑单校验时读到的票号（没读到为空串）
     * 返回 1 = 已从队列里摘掉；0 = 没动
     * </pre>
     * <table>
     *   <caption>真值表</caption>
     *   <tr><td>没给 KEYS[3]（非法成员）</td><td>LREM 全部等于它的项 + ZREM；确实摘掉了至少一项回 1，否则 0。不碰任何票</td></tr>
     *   <tr><td>票不存在 / 不是 queued / queue_key ≠ KEYS[1]</td><td>LREM + ZREM，票不动，1（任何原因都如此）</td></tr>
     *   <tr><td>票是这条队列里有效的 queued 票，原因是 invalid</td><td>0（只是退避没到的人走到这里：不剔）</td></tr>
     *   <tr><td>同上，原因是 in_battle / offline，票号 = ARGV[3]</td><td>DEL 票 + LREM + ZREM，1</td></tr>
     *   <tr><td>同上，票号 ≠ ARGV[3]（他取消后又排了一次）</td><td>0</td></tr>
     * </table>
     * 重放：第二次票已不在 → 走第二行，再 LREM 一遍（没有东西可摘），仍回 1。两次之间他重排了新票 → 0，新票与新队列项不受影响。
     */
    static final String DROP = """
            if not KEYS[3] then
              local n = redis.call('LREM', KEYS[1], 0, ARGV[1])
              redis.call('ZREM', KEYS[2], ARGV[1])
              if n > 0 then return 1 end
              return 0
            end
            local f = redis.call('HMGET', KEYS[3], 'ticket', 'state', 'queue_key')
            if f[1] and f[2] == 'queued' and f[3] == KEYS[1] then
              if ARGV[2] == 'invalid' or f[1] ~= ARGV[3] then return 0 end
              redis.call('DEL', KEYS[3])
            end
            redis.call('LREM', KEYS[1], 0, ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """;

    /**
     * S_POP：原子弹组，全有全无（I5）。逐个核对「票号相等、state = queued、queue_key = KEYS[1]、退避已到点、人在队列里」，全部满足才一次性把这些人
     * 的票置 matched 并设 TTL、摘出队列与评分镜像、写下重放标记；否则不写任何东西。
     * <pre>
     * KEYS[1] = 队列   KEYS[2] = 评分镜像   KEYS[3] = 本次弹组的重放标记   KEYS[3 + i] = 第 i 名成员的票
     * ARGV[1] = matched TTL 毫秒   ARGV[2] = 标记的 TTL 毫秒   ARGV[1 + 2i] = 第 i 名成员的成员串   ARGV[2 + 2i] = 第 i 名成员的票号
     * 返回 {0} 本次弹出 | {1} 标记已存在（同一个 token 弹过了）| {2, i, j, …} 这些下标的成员不满足，什么都没写
     * </pre>
     * 重放：第一次成功后写了标记 → 第二次 {1}，调用方按弹出处理。第一次回的是 {2,…} 时没有写标记，第二次按现状重新核对（结果相同，
     * 除非期间有人变了）。标记过期（60 s）之后才到的重发按现状核对：那些人已是 matched → {2, 全员}，凑单按无效名单处理，不会双弹。
     * {@code LREM count = 0} 把同一玩家在队列里的重复项一并摘掉（基线同）。
     */
    static final String POP = """
            if redis.call('EXISTS', KEYS[3]) == 1 then return {1} end
            """ + NOW + """
            local n = #KEYS - 3
            local out = {2}
            for i = 1, n do
              local f = redis.call('HMGET', KEYS[3 + i], 'ticket', 'state', 'queue_key', 'not_before_ms')
              local ok = f[1] == ARGV[2 + 2 * i] and f[2] == 'queued' and f[3] == KEYS[1]
              if ok and f[4] then
                local nb = tonumber(f[4])
                if nb and nb > now then ok = false end
              end
              if ok and not redis.call('LPOS', KEYS[1], ARGV[1 + 2 * i]) then ok = false end
              if not ok then out[#out + 1] = i end
            end
            if #out > 1 then return out end
            for i = 1, n do
              local key = KEYS[3 + i]
              local member = ARGV[1 + 2 * i]
              redis.call('HSET', key, 'state', 'matched')
              redis.call('PEXPIRE', key, ARGV[1])
              redis.call('LREM', KEYS[1], 0, member)
              redis.call('ZREM', KEYS[2], member)
            end
            redis.call('SET', KEYS[3], '1', 'PX', ARGV[2])
            return {0}
            """;

    /**
     * S_READY：开局成功，票置 ready 并写 battle_id、TTL 改成 ready 窗口。要求票号一致且票是 matched（比基线只比票号更严）。
     * <pre>
     * KEYS[1] = 票
     * ARGV[1] = 票号   ARGV[2] = battle_id（无符号十进制）   ARGV[3] = ready TTL 毫秒
     * 返回 1 = 这张票现在是带这个 battle_id 的 ready；0 = 没写（票不在 / 票号不符 / 不是 matched / 已是别的 battle_id 的 ready）
     * </pre>
     * 重放：第二次看到的是同一个 battle_id 的 ready → 1，不重写、不续期。
     */
    static final String READY = """
            if redis.call('HGET', KEYS[1], 'ticket') ~= ARGV[1] then return 0 end
            local state = redis.call('HGET', KEYS[1], 'state')
            if state == 'ready' then
              if redis.call('HGET', KEYS[1], 'battle_id') == ARGV[2] then return 1 end
              return 0
            end
            if state ~= 'matched' then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'ready', 'battle_id', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    /**
     * S_EXTEND：进补偿之前给幸存者续期——票号一致且仍是 matched 的票，TTL 改成 ARGV[1]（照设，可能比剩余的短）。
     * <pre>
     * KEYS[i] = 第 i 张票
     * ARGV[1] = TTL 毫秒   ARGV[1 + i] = 第 i 张票的票号
     * 返回续上的张数
     * </pre>
     * 重放：再设一遍同一个 TTL（多出重发间隔那几百毫秒），张数不变。
     */
    static final String EXTEND = """
            local n = 0
            for i = 1, #KEYS do
              if redis.call('HGET', KEYS[i], 'ticket') == ARGV[1 + i] and redis.call('HGET', KEYS[i], 'state') == 'matched' then
                redis.call('PEXPIRE', KEYS[i], ARGV[1])
                n = n + 1
              end
            end
            return n
            """;

    /**
     * S_DEL：按票号删票，不看状态、不摘队列项（基线 {@code ticketDelCasScript}）。
     * <pre>
     * KEYS[1] = 票
     * ARGV[1] = 票号
     * 返回 1 = 本次删掉了；0 = 没删（票不在或票号不符）
     * </pre>
     * 重放：第二次票已不在 → 0；调用方两种都按「这张票不再挡路」处理。两次之间玩家重排了新票 → 票号不符，不动。
     */
    static final String DEL = """
            if redis.call('HGET', KEYS[1], 'ticket') ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """;

    /**
     * S_DEL_GROUP：按票号删一组票（一个原子操作，逐张「票号一致才删」）。
     * <pre>
     * KEYS[i] = 第 i 张票
     * ARGV[i] = 第 i 张票的票号
     * 返回本次删掉的张数
     * </pre>
     * 重放：第二次都已不在 → 0（张数只进日志）。
     */
    static final String DEL_GROUP = """
            local n = 0
            for i = 1, #KEYS do
              if redis.call('HGET', KEYS[i], 'ticket') == ARGV[i] then
                n = n + redis.call('DEL', KEYS[i])
              end
            end
            return n
            """;

    /**
     * S_REQUEUE：幸存者回队首，<b>同一段脚本</b>里完成「票回 queued + 恢复长 TTL + 推到队首 + 按票里的评分写回镜像 + 登记注册集」。
     * 基线是「先 CAS 回 queued、再 LPUSH」两步（{@code queue.go:563-622}），两步之间能被别的实例看到，才需要孤儿自愈；这里没有那个窗口。
     * <pre>
     * KEYS[1] = 注册集   KEYS[2] = 队列   KEYS[3] = 评分镜像   KEYS[4] = 本次回队首的重放标记   KEYS[4 + i] = 第 i 名幸存者的票（按原弹出顺序）
     * ARGV[1] = queued TTL 毫秒   ARGV[2] = 退避毫秒（0 = 不退避）   ARGV[3] = 票里没有评分时用的 rating_centi   ARGV[4] = 标记的 TTL 毫秒
     * ARGV[3 + 2i] = 第 i 名幸存者的成员串   ARGV[4 + 2i] = 第 i 名幸存者的票号
     * 返回这一次回队首放回去的人数（标记已存在时是标记里记下的那个数，什么都不写）
     * </pre>
     * 逐人条件：票号一致、state = matched、queue_key = KEYS[2]；不满足的跳过（票已过期 / 已换 / 不属于这条队列 / 已是 queued）。
     * 先整轮判定，有人满足才 SADD 注册集（I1），再<b>从末尾往前</b>逐个 LPUSH——处理完之后这些人在队列里的相对顺序与入参相同、排在原有成员之前。
     * 每人：LPUSH → ZADD 原评分 → 票置 queued、删 {@code battle_id}、退避 &gt; 0 写 {@code not_before_ms = now + 退避} 否则删掉它、PEXPIRE。
     * {@code enqueued_at_ms} 不动（等待时长从第一次入队算）。
     *
     * <p>{@code LPUSH} 失败（队列键被占成别的类型）：删掉这张票让玩家可以立即重排，不留 queued 孤儿（同基线 {@code queue.go:613-617}）；不计入返回值。
     * {@code ZADD} 失败只丢镜像分（凑单按「镜像缺分」用票里的评分）。
     *
     * <p>重放：<b>只靠「票还是不是 matched」认不出重放</b>——回了队首的人可以在重发到达之前又被凑单弹成 matched（票号、queue_key 都没变），
     * 那时条件对旧的入参重新成立，重发会把正在第二次 gather 里的人再推回队首。所以与弹组一样按 token 写标记：<b>每个出口都写</b>
     * （含一个人都没放回去的 0），值 = 这一次放回去的人数；标记存在就原样返回那个数、不碰任何键。标记过期（60 s）之后才到的重发按现状核对。
     */
    static final String REQUEUE = """
            local marked = redis.call('GET', KEYS[4])
            if marked then return tonumber(marked) end
            """ + NOW + """
            local delay = tonumber(ARGV[2])
            local n = #KEYS - 4
            local ratings = {}
            local any = false
            for i = 1, n do
              local f = redis.call('HMGET', KEYS[4 + i], 'ticket', 'state', 'queue_key', 'rating_centi')
              if f[1] == ARGV[4 + 2 * i] and f[2] == 'matched' and f[3] == KEYS[2] then
                ratings[i] = f[4] or ARGV[3]
                any = true
              end
            end
            if not any then
              redis.call('SET', KEYS[4], '0', 'PX', ARGV[4])
              return 0
            end
            redis.call('SADD', KEYS[1], KEYS[2])
            local count = 0
            for i = n, 1, -1 do
              if ratings[i] then
                local key = KEYS[4 + i]
                local member = ARGV[3 + 2 * i]
                local pushed = redis.pcall('LPUSH', KEYS[2], member)
                if type(pushed) == 'table' and pushed.err then
                  redis.call('DEL', key)
                else
                  redis.pcall('ZADD', KEYS[3], ratings[i], member)
                  redis.call('HSET', key, 'state', 'queued')
                  redis.call('HDEL', key, 'battle_id')
                  if delay > 0 then
                    redis.call('HSET', key, 'not_before_ms', string.format('%.0f', now + delay))
                  else
                    redis.call('HDEL', key, 'not_before_ms')
                  end
                  redis.call('PEXPIRE', key, ARGV[1])
                  count = count + 1
                end
              end
            end
            redis.call('SET', KEYS[4], string.format('%d', count), 'PX', ARGV[4])
            return count
            """;

    /**
     * S_PRUNE：空队列的懒剔除（基线 {@code pruneQueueScript}，{@code matcher.go:32-44}）。判定与剔除在同一段脚本里，与并发的入队互斥
     * （入队会重新登记）。
     * <pre>
     * KEYS[1] = 注册集   KEYS[2] = 队列   KEYS[3] = 评分镜像
     * 返回 1 = 队列为空，已不在注册集里（本次拿掉，或本来就不在；空队列下残留的镜像一并删掉）；0 = 队列非空，没动
     * </pre>
     * 重放：幂等，第二次仍回 1。
     */
    static final String PRUNE = """
            if redis.call('LLEN', KEYS[2]) > 0 then return 0 end
            if redis.call('ZCARD', KEYS[3]) > 0 then
              redis.call('DEL', KEYS[3])
            end
            redis.call('SREM', KEYS[1], KEYS[2])
            return 1
            """;

    /**
     * S_LOCK_RELEASE：按持有者释放凑单锁（基线 {@code releaseLockScript}）。
     * <pre>
     * KEYS[1] = 锁
     * ARGV[1] = 本进程实例的标识
     * 返回 1 = 本次释放了；0 = 锁的值不是它（已过期被别人抢走，或本来就没有），不动
     * </pre>
     * 重放：第二次锁已不在（或已是别人的）→ 0，不会误删别人的锁。
     */
    static final String LOCK_RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;
}
