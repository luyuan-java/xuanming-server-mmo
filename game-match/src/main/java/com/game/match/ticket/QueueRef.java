package com.game.match.ticket;

import com.game.discovery.RedisKeys;
import java.util.Optional;

/**
 * 一条排队队列的身份：模式数值 + 副本号（match-spec §9.4）。{@code battle_config_id} 不校验，每个不同的值一条队列（照搬基线，客户端可见）。
 * 三把 Redis 键都由 {@link RedisKeys} 生成，同在 {@code {match}} 槽。
 *
 * @param mode     {@code MatchMode} 的数值（≥ 0）
 * @param configId {@code battle_config_id}（uint32 的位模式）
 */
public record QueueRef(int mode, int configId) {

    public QueueRef {
        if (mode < 0) {
            throw new IllegalArgumentException("匹配模式不能为负: " + mode);
        }
    }

    /** 队列（LIST，等待序）的键；也是票据 {@code queue_key} 字段与注册集成员的取值。 */
    public String queueKey() {
        return RedisKeys.matchQueue(mode, configId);
    }

    /** 评分镜像（ZSET）的键。 */
    public String rankKey() {
        return RedisKeys.matchRank(mode, configId);
    }

    /** 凑单锁的键。 */
    public String lockKey() {
        return RedisKeys.matchQueueLock(mode, configId);
    }

    /** 从队列键全文（票据的 {@code queue_key}、注册集成员）解析；不是规范的队列键为空。 */
    public static Optional<QueueRef> ofQueueKey(String queueKey) {
        return RedisKeys.parseMatchQueue(queueKey).map(id -> new QueueRef(id.mode(), id.config()));
    }

    @Override
    public String toString() {
        return mode + ":" + Integer.toUnsignedString(configId);
    }
}
