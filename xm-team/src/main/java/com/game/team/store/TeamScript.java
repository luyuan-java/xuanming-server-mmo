package com.game.team.store;

/**
 * 组队的七段 Lua（基线 go/match/internal/team/scripts.go，team-spec §1.6、§6.5），语义逐字照搬，只换键名（键由 Java 经
 * {@code RedisKeys.teamRecord / teamInfo / teamPlayer / teamInvite} 生成后从 KEYS 传入，统一 hash tag {@code {team}}，D2）。
 *
 * <p>通用约定（scripts.go:3-16）：
 * <ul>
 *   <li>统一模式：S_READ 一致性读 → Java 里跑纯函数规则（{@code TeamRules}）→ S_COMMIT 按 ver CAS 写。</li>
 *   <li>时钟：绝对时间一律取 Redis {@code TIME}（唯一时钟源），要求 Redis 7（effects replication，调 TIME 之后仍可写）。</li>
 *   <li>精度：写回或拼参数一律 {@code string.format("%.0f", x)}；team_id / player_id 在脚本里只当字符串比较，从不 tonumber
 *       （uint64 超出 double 精度）。</li>
 *   <li>返回数组里不放 nil（Lua 数组遇 nil 截断），缺失值用 {@code ""} 或 {@code "0"} 占位。</li>
 *   <li>hash 字段名 {@code ver / pb / tid / epoch} 是与 xm-scene 的进程间契约（{@code TeamRedisFields}），这里照基线写成字面量，
 *       由 {@code TeamScriptTest} 钉住与常量一致。</li>
 * </ul>
 *
 * <p>参数与回复都走 ByteArrayCodec（{@link RedissonTeamRedis}）：pb 是任意字节，数字是 ASCII 十进制；回复里整数是 {@code Long}、
 * 字符串是 {@code byte[]}。{@link #writes()} 为 true 的脚本必须以 READ_WRITE 执行（S_INVITE_LIST 也会写，team-spec §8.1 第 7 条、
 * §8.2 第 7 条）；只读脚本以 READ_ONLY 执行（Redis 7 上 Redisson 发 EVALSHA_RO，脚本里若写会被服务器拒绝）。
 */
public enum TeamScript {

    /**
     * S_COMMIT：唯一的写脚本（scripts.go:18-125）。
     *
     * <pre>
     * KEYS[1]=记录  KEYS[2]=投影
     * 其后依次：nJ 个新加入成员、nK 个保留成员、nL 个移出成员的玩家索引；nIA 个新增 / 刷新邀请、nID 个删除邀请的被邀请人反查 ZSET
     * ARGV[1]=expectedVer（"new" = 建队专用，记录必须不存在；其余为现有记录的十进制 ver）
     * ARGV[2]=recPb（"" = 解散：删记录与投影）  ARGV[3]=projPb  ARGV[4]=ttlSec  ARGV[5]=tid
     * ARGV[6..10]=nJ,nK,nL,nIA,nID  ARGV[11..10+nIA]=各新增邀请的 expire_at_ms  ARGV[11+nIA]=每个被邀请人的待处理邀请上限
     * </pre>
     *
     * 返回 {@code {1, newVer, tidAfter_1, epoch_1, ...}}（按 J、K、L 顺序）/ {@code {0}} 版本冲突或非建队遇到记录已不存在（绝不复活）/
     * {@code {-1,i}} 第 i 个新成员已在别的队 / {@code {-2,i}} 第 i 个保留成员的索引不是本队 / {@code {-3,i}} 第 i 个被邀请人待处理邀请已达上限。
     *
     * <p>判定段只用 HGET / ZSCORE / ZCOUNT，任何拒绝分支都不产生写入。几处必须照搬的细节（team-spec §1.6.1、§8.1）：
     * <ul>
     *   <li>J 判定放行缺失、{@code "0"}、本队；K 判定只放行缺失与本队——{@code "0"} 也算错位（§8.1 第 2 条）。</li>
     *   <li>IA 上限只在本队在该 ZSET 里没有条目（ZSCORE 为 nil）时判，计数按本脚本的 TIME 取 {@code (now, +inf)}；本队有一条已过期未清的
     *       旧项时跳过判定，随后先清过期项再 ZADD，于是对方最多可持有 11 条有效邀请（§8.1 第 1 条，照搬基线，D19 未采纳）。</li>
     *   <li>移出成员：索引缺失或 tid 是本队时置 "0"（缺失时按 TIME 起种），否则原样回报别队的 (tid, epoch)，不写不续期。</li>
     *   <li>ID 先 ZREM，IA 后 ZREMRANGEBYSCORE + ZADD + EXPIRE 3600：集合重叠时的第二道保险。</li>
     *   <li>epoch 只在 tid 变化时变：存在时 +1，缺失或为 0 时取 TIME 毫秒数 +1 起种（严格大于同一毫秒 S_READ 对缺失索引回报的 nowMs）。</li>
     * </ul>
     */
    COMMIT(true, true, """
            local cur = redis.call("HGET", KEYS[1], "ver")
            if ARGV[1] == "new" then
              if cur then return {0} end
            elseif (not cur) or cur ~= ARGV[1] then
              return {0}
            end
            local tid = ARGV[5]
            local ttl = ARGV[4]
            local nJ, nK, nL = tonumber(ARGV[6]), tonumber(ARGV[7]), tonumber(ARGV[8])
            local nIA, nID = tonumber(ARGV[9]), tonumber(ARGV[10])
            local inviteCap = tonumber(ARGV[11 + nIA])
            local t = redis.call("TIME")
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local nowStr = string.format("%.0f", nowms)
            for i = 1, nJ do
              local v = redis.call("HGET", KEYS[2 + i], "tid")
              if v and v ~= "0" and v ~= tid then return {-1, i} end
            end
            for i = 1, nK do
              local v = redis.call("HGET", KEYS[2 + nJ + i], "tid")
              if v and v ~= tid then return {-2, i} end
            end
            local base = 2 + nJ + nK + nL
            for i = 1, nIA do
              local key = KEYS[base + i]
              if not redis.call("ZSCORE", key, tid) then
                if redis.call("ZCOUNT", key, "(" .. nowStr, "+inf") >= inviteCap then
                  return {-3, i}
                end
              end
            end
            local function setIdx(key, v)
              local old = redis.call("HGET", key, "tid")
              local e = tonumber(redis.call("HGET", key, "epoch") or "0")
              if old ~= v then
                if e == 0 then e = nowms + 1 else e = e + 1 end
                redis.call("HSET", key, "tid", v, "epoch", string.format("%.0f", e))
              end
              redis.call("EXPIRE", key, ttl)
              return string.format("%.0f", e)
            end
            local newVer = string.format("%.0f", (tonumber(cur) or 0) + 1)
            local out = {1, newVer}
            if ARGV[2] == "" then
              redis.call("DEL", KEYS[1], KEYS[2])
            else
              redis.call("HSET", KEYS[1], "ver", newVer, "pb", ARGV[2])
              redis.call("EXPIRE", KEYS[1], ttl)
              redis.call("SET", KEYS[2], ARGV[3], "EX", ttl)
            end
            for i = 1, nJ + nK do
              local e = setIdx(KEYS[2 + i], tid)
              out[#out + 1] = tid
              out[#out + 1] = e
            end
            for i = 1, nL do
              local key = KEYS[2 + nJ + nK + i]
              local v = redis.call("HGET", key, "tid")
              if (not v) or v == tid then
                local e = setIdx(key, "0")
                out[#out + 1] = "0"
                out[#out + 1] = e
              else
                out[#out + 1] = v
                out[#out + 1] = redis.call("HGET", key, "epoch") or "0"
              end
            end
            for i = 1, nID do redis.call("ZREM", KEYS[base + nIA + i], tid) end
            for i = 1, nIA do
              local key = KEYS[base + i]
              redis.call("ZREMRANGEBYSCORE", key, "-inf", nowStr)
              redis.call("ZADD", key, ARGV[10 + i], tid)
              redis.call("EXPIRE", key, "3600")
            end
            return out
            """),

    /**
     * S_READ：一致性读（玩家索引 + 某队记录 + Redis 时钟；scripts.go:127-147）。
     *
     * <p>KEYS[1]=玩家索引  KEYS[2]=记录。返回 {@code {tidNow, epoch, ver, pb, recTTL, nowMs}}：tidNow 索引缺失为 ""；
     * epoch 缺失为 nowMs（只读不写：它大于索引消失前发出的任何 epoch、严格小于之后任何起种 nowMs+1，team-spec §1.4）；
     * ver / pb 记录缺失为 ""；recTTL 为整数（-2 不存在 / -1 无 TTL）；nowMs 是字符串。
     */
    READ(false, true, """
            local t = redis.call("TIME")
            local nowStr = string.format("%.0f", tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000))
            local tidNow = redis.call("HGET", KEYS[1], "tid") or ""
            local epoch = redis.call("HGET", KEYS[1], "epoch") or nowStr
            local r = redis.call("HMGET", KEYS[2], "ver", "pb")
            return {tidNow, epoch, r[1] or "", r[2] or "", redis.call("TTL", KEYS[2]), nowStr}
            """),

    /**
     * S_READ_MEMBERS：给其他队员构建不经提交的视图（scripts.go:149-162）。
     *
     * <p>KEYS[1]=记录  KEYS[2..]=按上一次读到的成员表传入的玩家索引。返回 {@code {ver, pb, nowMs, tid_1, epoch_1, ...}}，
     * tid 缺失为 ""、epoch 缺失为 "0"（只给 tid == 本队的成员推送，这些成员的键必然存在）。
     */
    READ_MEMBERS(false, true, """
            local r = redis.call("HMGET", KEYS[1], "ver", "pb")
            local t = redis.call("TIME")
            local out = {r[1] or "", r[2] or "", string.format("%.0f", tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000))}
            for i = 2, #KEYS do
              out[#out + 1] = redis.call("HGET", KEYS[i], "tid") or ""
              out[#out + 1] = redis.call("HGET", KEYS[i], "epoch") or "0"
            end
            return out
            """),

    /**
     * S_INVITE_LIST：ListMyInvites 专用（scripts.go:164-177）。先按 Redis 时钟剔除已过期项（score <= now），再列出剩余项——
     * <b>这个读 RPC 会写</b>，必须 READ_WRITE。
     *
     * <p>KEYS[1]=被邀请人反查 ZSET。返回 {@code {nowMs, tid_1, score_1, ...}}（score 为 Redis 原字符串，供 S_INVITE_PRUNE 做 CAS）。
     */
    INVITE_LIST(true, true, """
            local t = redis.call("TIME")
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call("ZREMRANGEBYSCORE", KEYS[1], "-inf", string.format("%.0f", nowms))
            local out = {string.format("%.0f", nowms)}
            local z = redis.call("ZRANGE", KEYS[1], 0, -1, "WITHSCORES")
            for i = 1, #z do out[#out + 1] = z[i] end
            return out
            """),

    /**
     * S_INVITE_PRUNE：score 没变（期间没被重邀刷新）才删，不会误删队长刚写入的新项（scripts.go:179-191）。
     *
     * <p>KEYS[1]=被邀请人反查 ZSET  ARGV[1]=tid  ARGV[2]=S_INVITE_LIST 看到的 score 原字符串（按字符串比较）。返回 1 删了 / 0 未删。
     */
    INVITE_PRUNE(true, false, """
            local s = redis.call("ZSCORE", KEYS[1], ARGV[1])
            if s and s == ARGV[2] then
              redis.call("ZREM", KEYS[1], ARGV[1])
              return 1
            end
            return 0
            """),

    /**
     * S_TOUCH：续期，不改 ver（scripts.go:193-216）。
     *
     * <p>KEYS[1]=记录  KEYS[2]=投影  KEYS[3..]=成员玩家索引；ARGV[1]=expectedVer  ARGV[2]=ttlSec  ARGV[3]=projPb（由 expectedVer
     * 那一版记录生成）  ARGV[4]=tid。ver 不等（含记录不存在）→ 0；投影缺失时按 ARGV[3] 重写；成员索引只在 tid 仍等于本队时续期。
     * 返回 1 已续期 / 0。
     */
    TOUCH(true, false, """
            local cur = redis.call("HGET", KEYS[1], "ver")
            if (not cur) or cur ~= ARGV[1] then return 0 end
            redis.call("EXPIRE", KEYS[1], ARGV[2])
            if redis.call("EXISTS", KEYS[2]) == 1 then
              redis.call("EXPIRE", KEYS[2], ARGV[2])
            else
              redis.call("SET", KEYS[2], ARGV[3], "EX", ARGV[2])
            end
            for i = 3, #KEYS do
              if redis.call("HGET", KEYS[i], "tid") == ARGV[4] then
                redis.call("EXPIRE", KEYS[i], ARGV[2])
              end
            end
            return 1
            """),

    /**
     * S_HEAL_ORPHAN：孤儿索引自愈（索引指向的记录已不存在；scripts.go:218-236）。
     *
     * <p>KEYS[1]=玩家索引  KEYS[2]=记录；ARGV[1]=tid  ARGV[2]=ttlSec。记录不存在且索引 tid == ARGV[1] 时 tid 置 "0"、epoch+1
     * （为 0 时按 TIME 毫秒数 +1 起种，与 S_COMMIT 同口径）并续期，返回 1；否则 0。
     */
    HEAL_ORPHAN(true, false, """
            if redis.call("EXISTS", KEYS[2]) == 1 then return 0 end
            if redis.call("HGET", KEYS[1], "tid") ~= ARGV[1] then return 0 end
            local e = tonumber(redis.call("HGET", KEYS[1], "epoch") or "0")
            if e == 0 then
              local t = redis.call("TIME")
              e = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) + 1
            else
              e = e + 1
            end
            redis.call("HSET", KEYS[1], "tid", "0", "epoch", string.format("%.0f", e))
            redis.call("EXPIRE", KEYS[1], ARGV[2])
            return 1
            """);

    private final boolean writes;
    private final boolean multi;
    private final String lua;

    TeamScript(boolean writes, boolean multi, String lua) {
        this.writes = writes;
        this.multi = multi;
        this.lua = lua;
    }

    /** 脚本是否会写（true 必须以 READ_WRITE 执行，false 以 READ_ONLY 执行）。 */
    public boolean writes() {
        return writes;
    }

    /** 回复是否是数组（true：MULTI，元素是 {@code Long} / {@code byte[]}；false：单个整数 {@code Long}）。 */
    public boolean multi() {
        return multi;
    }

    /** 脚本正文。 */
    public String lua() {
        return lua;
    }
}
