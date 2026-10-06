package com.game.discovery.battle;

import com.game.discovery.RedisKeys;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RedissonClient;

/**
 * 回合制战斗锁的只读工具（scene-battle-spec §2.4、§7.1）：给 match（JoinQueue / 切磋 / 活动开局 / 整队预检）、team（队伍视图）、
 * scene 的组队跟随与以后的 guild 用。锁存在 = 这名玩家有在途的战斗（备战或战斗中，或已结算待落盘）。
 * 锁是 Hash，不能 MGET（基线 Go 侧的 MGET 写法不移植），批量读逐键 EXISTS 并发发出。
 *
 * <p>读失败时怎么处理由调用方各自定（口径不一：JoinQueue 回内部错误、切磋按「忙」、队伍视图按「不在战斗」，§2.4）——这里只把错误原样带回。
 * 全部方法异步、不阻塞、线程安全。
 */
public final class BattleLockReader {

    private final RedissonClient redis;
    private final BattleRedis scripts;

    public BattleLockReader(RedissonClient redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.scripts = new BattleRedis(redis);
    }

    /**
     * 锁在不在（EXISTS）。咨询性的读（挡排队 / 跟随 / 队伍视图）：走 Redisson 的普通读路由，主从部署下可能读到从库的旧值；
     * 驱动丢弃 / 判废的读不在这里，在 {@link BattleRedis}（一律读主库）。
     */
    public CompletableFuture<Boolean> exists(long playerId) {
        try {
            return redis.getKeys().countExistsAsync(RedisKeys.battleLock(playerId)).toCompletableFuture()
                    .thenApply(n -> n != null && n > 0);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 锁指向的 battle_id；没有锁（或 b 不是无符号十进制）为 0。经 {@link BattleRedis#readLockBattleId}，读主库。 */
    public CompletableFuture<Long> battleId(long playerId) {
        return scripts.readLockBattleId(playerId);
    }

    /**
     * 批量 EXISTS：返回 player_id → 锁在不在（保持入参顺序；重复的 player_id 只读一次）。任何一个读失败整体以异常完成。
     */
    public CompletableFuture<Map<Long, Boolean>> existsAll(Collection<Long> playerIds) {
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(playerIds));
        List<CompletableFuture<Boolean>> reads = new ArrayList<>(ids.size());
        for (long playerId : ids) {
            reads.add(exists(playerId));
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<Long, Boolean> out = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                out.put(ids.get(i), reads.get(i).join());
            }
            return out;
        });
    }
}
