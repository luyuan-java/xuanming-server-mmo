package com.game.match.port;

import com.game.common.deadline.Deadline;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerPresence;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongFunction;

/**
 * {@link PlayerStatusReader} 的生产实现：三样读各自委托给 xm-discovery 的异步读（{@link BattleLockReader#exists}、
 * {@link PlayerPresenceDirectory#findStrictAsync}、{@link PlayerLocationDirectory#findHolderAsync}），在请求截止内等结果，并把三种来源各异的
 * 「失败」统一成 {@link Deadline.DependencyException}：future 异常完成、等超时、返回 null、以及位置读的 {@code ERROR} 状态。
 * 只在 future 上等，不持锁，可以在虚拟线程上调。
 */
public final class RedisPlayerStatusReader implements PlayerStatusReader {

    private final LongFunction<? extends CompletionStage<Boolean>> lockExists;
    private final LongFunction<? extends CompletionStage<Optional<PlayerPresence>>> presences;
    private final LongFunction<? extends CompletionStage<HolderRead>> holders;

    /** 生产装配。 */
    public RedisPlayerStatusReader(BattleLockReader locks, PlayerPresenceDirectory presence, PlayerLocationDirectory locations) {
        this(locks::exists, presence::findStrictAsync, locations::findHolderAsync);
    }

    /**
     * @param lockExists 战斗锁在不在（异常完成 = 读失败）
     * @param presences  在线目录的严格读（空 = 不在线；异常完成 = 读失败或条目损坏）
     * @param holders    位置记录的严格读（{@code ERROR} 状态或异常完成 = 故障）
     */
    public RedisPlayerStatusReader(LongFunction<? extends CompletionStage<Boolean>> lockExists,
                                   LongFunction<? extends CompletionStage<Optional<PlayerPresence>>> presences,
                                   LongFunction<? extends CompletionStage<HolderRead>> holders) {
        this.lockExists = Objects.requireNonNull(lockExists, "lockExists");
        this.presences = Objects.requireNonNull(presences, "presences");
        this.holders = Objects.requireNonNull(holders, "holders");
    }

    @Override
    public boolean inBattle(long playerId, Deadline d) {
        Boolean locked = d.await(start(lockExists, playerId, "读战斗锁"), "读战斗锁");
        if (locked == null) {
            throw new Deadline.DependencyException("读战斗锁返回了空值");
        }
        return locked;
    }

    @Override
    public Optional<PlayerPresence> presence(long playerId, Deadline d) {
        Optional<PlayerPresence> found = d.await(start(presences, playerId, "读在线目录"), "读在线目录");
        if (found == null) {
            throw new Deadline.DependencyException("读在线目录返回了空值");
        }
        return found;
    }

    @Override
    public HolderRead location(long playerId, Deadline d) {
        HolderRead read = d.await(start(holders, playerId, "读位置记录"), "读位置记录");
        if (read == null) {
            throw new Deadline.DependencyException("读位置记录返回了空值");
        }
        if (read.status() == LocationStatus.ERROR) {
            throw new Deadline.DependencyException("读位置记录失败: " + (read.detail() == null ? "状态未知" : read.detail()));
        }
        if (read.status() == LocationStatus.ONLINE && read.location() == null) {
            throw new Deadline.DependencyException("在线的位置记录没有位置值");
        }
        return read;
    }

    /** 发起一次异步读；同步抛出的异常（客户端已关闭等）也变成失败的 stage，走同一条「失败」路径。 */
    private static <T> CompletionStage<T> start(LongFunction<? extends CompletionStage<T>> read, long playerId, String what) {
        try {
            CompletionStage<T> stage = read.apply(playerId);
            return stage != null ? stage : CompletableFuture.failedFuture(new IllegalStateException(what + " 没有返回 future"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
