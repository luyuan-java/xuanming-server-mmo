package com.game.guild.rank;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * {@link GuildRanks} 需要的 Redis 操作（生产实现 {@link RedissonGuildRankRedis}；单测可换成内存假实现制造故障）。
 *
 * <p>键全部由调用方经 {@code RedisKeys.guildRank*} 生成并显式传入（脚本里不拼键名：Cluster 下键必须出现在 KEYS 里；
 * 排行的键共用 hash tag {@code {rank}}，多键脚本同槽）。成员是帮会号的无符号十进制，分数是 int64 的十进制
 * （Redis 按 strtod 取最近的 double，与基线 {@code float64(score)} 相同）。
 *
 * <p>实现必须：异步返回、不阻塞调用线程，失败以异常完成的 stage 表达；带锁校验的写脚本（{@link #add}、{@link #remove}、{@link #swap}）
 * 先比较维护锁令牌，不符就什么都不写——锁过期被别人接管后，迟到的写不会覆盖重建的结果。
 */
public interface GuildRankRedis {

    /**
     * 抢维护锁：键缺失 → 写入令牌（PX）返回 true；已是本令牌 → 续上 PX 返回 true（Redisson 重发同一段脚本时不会误判没抢到）；
     * 别人持有 → false。
     */
    CompletionStage<Boolean> tryLock(String lockKey, String token, long ttlMillis);

    /** 续期：令牌相符才 PEXPIRE，返回是否仍持有。 */
    CompletionStage<Boolean> renewLock(String lockKey, String token, long ttlMillis);

    /** 释放：令牌相符才 DEL，返回是否删了。 */
    CompletionStage<Boolean> unlock(String lockKey, String token);

    /**
     * 持锁入榜 / 改分：令牌相符才 {@code ZADD allKey score member}；{@code zoneKey} 非 null 时再 {@code ZADD zoneKey} 与
     * {@code SADD zonesKey zoneMember}。
     *
     * @return false = 锁已不是本令牌（什么都没写）
     */
    CompletionStage<Boolean> add(String lockKey, String token, String allKey, String zonesKey, String zoneKey,
                                 String zoneMember, String score, String member);

    /** {@code SMEMBERS}。 */
    CompletionStage<Set<String>> members(String setKey);

    /**
     * 持锁清榜：令牌相符才对 {@code rankKeys} 逐个 {@code ZREM member}。
     *
     * @return false = 锁已不是本令牌（什么都没删）
     */
    CompletionStage<Boolean> remove(String lockKey, String token, List<String> rankKeys, String member);

    /**
     * 重建：往临时键批量 {@code ZADD}（{@code scoreMemberPairs} = 分数, 成员, 分数, 成员…）并 {@code PEXPIRE ttlMillis}（同一段脚本，
     * 进程中途死掉也不泄漏）。
     *
     * @return 本批新加入的成员数（ZADD 的返回值）
     */
    CompletionStage<Long> addTemp(String tmpKey, List<String> scoreMemberPairs, long ttlMillis);

    /**
     * 重建：一段脚本原子换榜（见 {@link SwapPlan}）。
     *
     * @return 1 = 已换；0 = 锁已不是本令牌（什么都没动）；-1 = 临时键的成员数与预期不符（过期 / 被淘汰，什么都没动）
     */
    CompletionStage<Long> swap(SwapPlan plan);

    /** {@code DEL}（清理临时键；不存在的键忽略）。 */
    CompletionStage<Long> delete(List<String> keys);

    /**
     * 一页排行（只读，一段脚本）：{@code ZCARD key}；{@code size > 0} 且 {@code start < total} 时再
     * {@code ZREVRANGE key start min(start+size−1, total−1) WITHSCORES}。
     *
     * @param start 起始下标（无符号十进制，可以远大于榜长）
     */
    CompletionStage<RawPage> page(String key, String start, long size);

    /** 单帮名次（只读，一段脚本同时取 {@code ZREVRANK} 与 {@code ZSCORE}）；不在榜上为 null。 */
    CompletionStage<RawRank> rank(String key, String member);

    /**
     * 换榜计划。脚本内顺序：比较锁令牌 → 核对每个临时键的 ZCARD → {@code DEL all} → {@code DEL} 每个旧区榜 →
     * {@code RENAME tmpAll → all} 并 {@code PERSIST}（临时键为空则不存在、跳过）→ 每个新区 {@code DEL} 正式键、{@code RENAME tmp → 正式}、
     * {@code PERSIST} → {@code DEL zones} → {@code SADD zones} 全部新区。
     *
     * @param oldZoneKeys 现有区榜键（来自区索引）
     * @param zones       新区榜（临时键 → 正式键）
     */
    record SwapPlan(String lockKey, String token, String allKey, String zonesKey, String tmpAllKey, long expectedAll,
                    List<String> oldZoneKeys, List<ZoneSwap> zones) {

        public SwapPlan {
            oldZoneKeys = List.copyOf(oldZoneKeys);
            zones = List.copyOf(zones);
        }
    }

    /** 一个新区榜：临时键、正式键、区索引里的成员（zone_id 无符号十进制）、预期成员数。 */
    record ZoneSwap(String tmpKey, String formalKey, String zoneMember, long expected) {
    }

    /** 一页的原始结果：{@code members.get(i)} 与 {@code scores.get(i)} 一一对应（Redis 回的原串）。 */
    record RawPage(long total, List<String> members, List<String> scores) {

        public RawPage {
            members = List.copyOf(members);
            scores = List.copyOf(scores);
            if (members.size() != scores.size()) {
                throw new IllegalArgumentException("成员与分数个数不符: " + members.size() + " / " + scores.size());
            }
        }
    }

    /** 单帮名次的原始结果：{@code index} 是 ZREVRANK（0 起），{@code score} 是 ZSCORE 的原串。 */
    record RawRank(long index, String score) {
    }
}
