package com.game.match.placement;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.proto.BattlePlacement;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PlacementStore} 的生产实现（match-spec §4.3、§9.4 的 S_PLACE）：Redis HASH {@code xm:{match}:battle:<battle_id>}，两个字段
 * {@code a}（attempt 十进制）与 {@code pb}（{@link BattlePlacement} 字节），TTL {@value MatchBudgets#PLACEMENT_TTL_SECONDS} s。
 *
 * <ul>
 *   <li><b>写</b>：一段 Lua、一次 Redis 调用，外层以 {@value MatchBudgets#PLACEMENT_WRITE_WORST_MS} ms 为截止（Redis 客户端自带一次重发，
 *       缺省配置最坏约 4.2 s，在截止之内；这里不再自己重试）。单调：已存的 attempt 更大就不写，所以「超时后迟到落盘的首写」盖不掉换节点之后的改写。
 *       可重放：同一条记录写两次结果相同。</li>
 *   <li><b>读</b>：一段只读 Lua（一次往返拿到「存在与否 + 两个字段」），按读写模式发出——主从部署下固定读主库，客户端收到 177 之后马上补签也读得到。</li>
 *   <li><b>删</b>：无条件 DEL，尽力而为。</li>
 * </ul>
 * 三个方法都只在 future 上等（不持锁），可以在 gather 的虚拟线程与工作线程上调。线程安全。
 */
public final class RedissonPlacementStore implements PlacementStore {

    private static final Logger log = LoggerFactory.getLogger(RedissonPlacementStore.class);

    /** S_PLACE：KEYS[1] = 落点键；ARGV[1] = attempt（十进制）；ARGV[2] = 消息字节；ARGV[3] = TTL 毫秒。回 1 = 本次写入，0 = 已有更新的写（没动）。 */
    static final String PLACE_LUA = """
            local cur = tonumber(redis.call('HGET', KEYS[1], 'a'))
            if cur and cur > tonumber(ARGV[1]) then
              return 0
            end
            redis.call('HSET', KEYS[1], 'a', ARGV[1], 'pb', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    /** 读：键不存在回空表；存在回 {a, pb}（缺的字段回空串——Lua 表遇 nil 会截断，不能直接回 HMGET 的结果）。 */
    static final String READ_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return {}
            end
            return {redis.call('HGET', KEYS[1], 'a') or '', redis.call('HGET', KEYS[1], 'pb') or ''}
            """;

    /** 删除的等待上限（尽力而为；删不掉随 TTL 自清）。 */
    static final long DELETE_WAIT_MS = 3_000;
    static final long TTL_MS = TimeUnit.SECONDS.toMillis(MatchBudgets.PLACEMENT_TTL_SECONDS);

    private final RedissonClient redis;
    private final long writeBudgetMs;

    public RedissonPlacementStore(RedissonClient redis) {
        this(redis, MatchBudgets.PLACEMENT_WRITE_WORST_MS);
    }

    /** @param writeBudgetMs 一次写的外层截止（生产恒为 {@value MatchBudgets#PLACEMENT_WRITE_WORST_MS}；测试收短） */
    RedissonPlacementStore(RedissonClient redis, long writeBudgetMs) {
        this.redis = Objects.requireNonNull(redis, "redis");
        if (writeBudgetMs < 1) {
            throw new IllegalArgumentException("落点写入的截止必须为正: " + writeBudgetMs);
        }
        this.writeBudgetMs = writeBudgetMs;
    }

    @Override
    public boolean write(BattlePlacement placement) {
        Objects.requireNonNull(placement, "placement");
        if (placement.getBattleId() == 0 || placement.getAttempt() < 1) {
            throw new IllegalArgumentException("落点记录必须带 battle_id 与 attempt ≥ 1");
        }
        String battle = Long.toUnsignedString(placement.getBattleId());
        try {
            Long written = this.<Long>eval(PLACE_LUA, RScript.ReturnType.INTEGER, placement.getBattleId(),
                    ascii(Integer.toUnsignedString(placement.getAttempt())), placement.toByteArray(), ascii(Long.toString(TTL_MS)))
                    .get(writeBudgetMs, TimeUnit.MILLISECONDS);
            if (written == null) {
                log.error("写落点记录的回复为空（按写失败处理） battle_id={} attempt={}", battle, placement.getAttempt());
                return false;
            }
            if (written == 0) {
                log.info("落点记录已有更新的写，本次（更早的 attempt）不覆盖 battle_id={} attempt={}", battle, placement.getAttempt());
            }
            return true;
        } catch (TimeoutException e) {
            log.error("写落点记录超过 {} ms，结局不明（按写失败处理） battle_id={} attempt={}", writeBudgetMs, battle, placement.getAttempt());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("写落点记录时被中断（按写失败处理） battle_id={} attempt={}", battle, placement.getAttempt());
            return false;
        } catch (ExecutionException | RuntimeException e) {
            log.error("写落点记录失败 battle_id={} attempt={}: {}", battle, placement.getAttempt(), String.valueOf(cause(e)));
            return false;
        }
    }

    @Override
    public void delete(long battleId) {
        String battle = Long.toUnsignedString(battleId);
        try {
            redis.getKeys().deleteAsync(RedisKeys.matchBattlePlacement(battleId)).toCompletableFuture().get(DELETE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("删落点记录时被中断（随 TTL 自清） battle_id={}", battle);
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            log.warn("删落点记录失败（随 TTL 自清；补签会直拨到 battle、由 battle 回房间不存在） battle_id={}: {}", battle, String.valueOf(cause(e)));
        }
    }

    @Override
    public Read read(long battleId, Deadline d) {
        Objects.requireNonNull(d, "d");
        String battle = Long.toUnsignedString(battleId);
        List<Object> reply;
        try {
            reply = d.await(this.<List<Object>>eval(READ_LUA, RScript.ReturnType.MULTI, battleId), "读落点记录");
        } catch (Deadline.DependencyException e) {
            return new Read.Failed("读落点记录失败 battle_id=" + battle + ": " + cause(e));
        }
        if (reply == null) {
            return new Read.Failed("读落点记录的回复为空 battle_id=" + battle);
        }
        if (reply.isEmpty()) {
            return new Read.Absent();
        }
        if (reply.size() != 2 || !(reply.get(1) instanceof byte[] bytes)) {
            return new Read.Failed("落点记录的回复形状不对 battle_id=" + battle + " size=" + reply.size());
        }
        if (bytes.length == 0) {
            return new Read.Failed("落点记录缺 pb 字段 battle_id=" + battle);
        }
        BattlePlacement placement;
        try {
            placement = BattlePlacement.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            return new Read.Failed("落点记录解析失败 battle_id=" + battle + ": " + e.getMessage());
        }
        if (placement.getBattleId() != battleId) {
            return new Read.Failed("落点记录与键不符 battle_id=" + battle + " recorded=" + Long.toUnsignedString(placement.getBattleId()));
        }
        return new Read.Found(placement);
    }

    /** 一律按读写模式发出（写要写主库；读也读主库，见类注释）。同步抛出的异常（客户端已关闭等）变成失败的 future。 */
    private <R> CompletableFuture<R> eval(String script, RScript.ReturnType type, long battleId, byte[]... args) {
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<R>evalAsync(RScript.Mode.READ_WRITE, script, type,
                    List.of(RedisKeys.matchBattlePlacement(battleId)), (Object[]) args).toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static Throwable cause(Throwable e) {
        return e.getCause() == null ? e : e.getCause();
    }
}
