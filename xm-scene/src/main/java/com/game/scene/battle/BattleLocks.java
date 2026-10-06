package com.game.scene.battle;

import com.game.discovery.battle.BattleRedis;
import com.game.discovery.battle.BattleRedis.EnterRead;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * scene 侧回合制战斗锁与待结算记录的异步 Redis 端口（scene-battle-spec §7.2；生产 = {@link #redis(BattleRedis)}，测试换成可控完成次序与失败的假实现）。
 * 全部方法不阻塞；future 在任意线程上完成（调用方投递回逻辑线程），出错以异常完成。语义逐条见 {@link BattleRedis}。
 */
public interface BattleLocks {

    /** {@code PREPARE_LOCK}：回 "0" = 写入（或重放）成功；否则回现在的 b（被占）。 */
    CompletableFuture<String> prepareLock(long playerId, long battleId, int battleNodeId, long deadlineMs, long prepareDeadlineMs,
                                          long ttlSec);

    /** {@code CONFIRM}：b == X 时回锁的全部字段（已改成 F），否则 null。 */
    CompletableFuture<Map<String, String>> confirm(long playerId, long battleId, long deadlineMs, long ttlSec);

    /** {@code CANCEL_OFFLINE}：1 删了 / 2 FIGHTING 拒绝 / 0 不是本局。 */
    CompletableFuture<Long> cancelOffline(long playerId, long battleId);

    /** {@code DELETE_IF_MATCH}：1 删了 / 0 不是本局。 */
    CompletableFuture<Long> deleteIfMatch(long playerId, long battleId);

    /** {@code TOUCH}：1 命中 / 0 不是本局。 */
    CompletableFuture<Long> touch(long playerId, long battleId, long ttlSec, String state, long deadlineMs, long prepareDeadlineMs);

    /** {@code HOLD}：1 命中 / 0 不是本局。 */
    CompletableFuture<Long> hold(long playerId, long battleId, long holdSec);

    /** {@code ACK}：位 1 = 删了记录，位 2 = 放了锁。 */
    CompletableFuture<Long> ack(long playerId, long battleId);

    /** {@code ENTER_READ}。 */
    CompletableFuture<EnterRead> enterRead(long playerId);

    /** {@code READ_IF_OURS}：本局记录的字节，没有为 null。 */
    CompletableFuture<byte[]> readSettlement(long playerId, long battleId);

    /** 锁的 b；没有锁为 0。 */
    CompletableFuture<Long> readLockBattleId(long playerId);

    /** 按原样字段名删一个待结算字段（坏字段）。 */
    CompletableFuture<Long> deleteSettlementField(long playerId, String field);

    /** 生产实现：委托 {@link BattleRedis}。 */
    static BattleLocks redis(BattleRedis redis) {
        Objects.requireNonNull(redis, "redis");
        return new BattleLocks() {
            @Override
            public CompletableFuture<String> prepareLock(long playerId, long battleId, int battleNodeId, long deadlineMs,
                                                         long prepareDeadlineMs, long ttlSec) {
                return redis.prepareLock(playerId, battleId, battleNodeId, deadlineMs, prepareDeadlineMs, ttlSec);
            }

            @Override
            public CompletableFuture<Map<String, String>> confirm(long playerId, long battleId, long deadlineMs, long ttlSec) {
                return redis.confirm(playerId, battleId, deadlineMs, ttlSec);
            }

            @Override
            public CompletableFuture<Long> cancelOffline(long playerId, long battleId) {
                return redis.cancelOffline(playerId, battleId);
            }

            @Override
            public CompletableFuture<Long> deleteIfMatch(long playerId, long battleId) {
                return redis.deleteIfMatch(playerId, battleId);
            }

            @Override
            public CompletableFuture<Long> touch(long playerId, long battleId, long ttlSec, String state, long deadlineMs,
                                                 long prepareDeadlineMs) {
                return redis.touch(playerId, battleId, ttlSec, state, deadlineMs, prepareDeadlineMs);
            }

            @Override
            public CompletableFuture<Long> hold(long playerId, long battleId, long holdSec) {
                return redis.hold(playerId, battleId, holdSec);
            }

            @Override
            public CompletableFuture<Long> ack(long playerId, long battleId) {
                return redis.ack(playerId, battleId);
            }

            @Override
            public CompletableFuture<EnterRead> enterRead(long playerId) {
                return redis.enterRead(playerId);
            }

            @Override
            public CompletableFuture<byte[]> readSettlement(long playerId, long battleId) {
                return redis.readSettlement(playerId, battleId);
            }

            @Override
            public CompletableFuture<Long> readLockBattleId(long playerId) {
                return redis.readLockBattleId(playerId);
            }

            @Override
            public CompletableFuture<Long> deleteSettlementField(long playerId, String field) {
                return redis.deleteSettlementField(playerId, field);
            }
        };
    }
}
