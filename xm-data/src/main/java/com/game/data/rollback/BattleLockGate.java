package com.game.data.rollback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 回档前的战斗锁闸（data-ops-spec §4.2、§4.12、§13.3；批次 6.3 追加，对应基线回档对战斗中玩家回 1005，
 * {@code player_rollback_handler.cpp:68-80}）。
 *
 * <p><b>为什么夺权挡不住</b>：Java 断线即写回并释放归属，战斗在 xm-battle 继续、结算可以在玩家离线时到达，所以 {@code ifOnline=reject}
 * 也能夺到「战斗锁仍在」的离线玩家；{@code kick} 走的顶号通路不看是否在战斗，在线战斗中的玩家同样被踢下线后夺到。战斗开局时已经拍下
 * 背包与属性，这时换掉存档，结算按账本扣药会「不足按 0」、属性被结算的终值整体覆盖——所以锁在就不写。
 *
 * <p><b>判定</b>（批量读 {@code xm:battle:{pid}:lock} 在不在，接缝是 {@link Reader}，生产实现是 xm-discovery 的
 * {@code BattleLockReader.existsAll}）：
 * <ul>
 *   <li>锁在 → {@code in_battle}；</li>
 *   <li>读不到（读取方抛异常、future 异常完成、超时、应答里没有这个人）→ {@code battle_lock_unknown}。<b>fail-closed</b>：
 *       没问到结论不当成「不在战斗」（AGENTS.md §3），与「确实在战斗」分开记，告警分得出是规则拒绝还是 Redis 故障；</li>
 *   <li>两种都不写。它不是资产分歧检查，不走 {@code acceptDivergence}（基线对战斗中的玩家同样无条件拒绝）。</li>
 * </ul>
 *
 * <p><b>等待</b>：调用线程限时等（作业在 {@code data-ops} 线程上，dry-run 在请求线程上；都不是 Netty I/O 或场景逻辑线程）。
 * 一次 {@link #check} 全程共用一个截止时刻（{@code xm.data.ops.battle-lock-wait}），分块发出（每块 {@value #CHUNK} 人，免得整区上万条
 * 命令一次压给连接池）；任何一块读失败就停——之后的块不再发，没读到的人一律 {@code battle_lock_unknown}。
 * 无状态、线程安全。
 */
public final class BattleLockGate {

    /** 一次批量读的人数（整区回档按 {@code idx_player_zone} 分页也是 500）。 */
    static final int CHUNK = 500;
    /** 摘要里列出的玩家号上限（同 {@code zone_not_quiescent} 的 {@code unclaimedPlayers}）。 */
    static final int SAMPLE = 100;
    /** 摘要里错误文本的长度上限。 */
    static final int ERROR_MAX = 300;

    /**
     * 批量读战斗锁：player_id → 锁在不在。任何一个键读失败整体以异常完成（{@code BattleLockReader.existsAll} 的口径）；
     * 可以同步抛异常（Redis 客户端取不到时）。
     */
    @FunctionalInterface
    public interface Reader {
        CompletableFuture<Map<Long, Boolean>> existsAll(Collection<Long> playerIds);
    }

    /**
     * 一次检查的结论。
     *
     * @param checked  查了多少人
     * @param inBattle 锁在的玩家（入参顺序）
     * @param unknown  读不到的玩家（入参顺序）：按在战处理、不写
     * @param error    读失败的原因（排障用，进摘要）；全部读到为 null
     */
    public record Result(int checked, Set<Long> inBattle, Set<Long> unknown, String error) {

        public Result {
            inBattle = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(inBattle));
            unknown = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(unknown));
        }

        /** 没有人要查。 */
        public static Result none() {
            return new Result(0, Set.of(), Set.of(), null);
        }

        /** 有人不能写（锁在或读不到）。 */
        public boolean blocked() {
            return !inBattle.isEmpty() || !unknown.isEmpty();
        }

        /** 这名玩家的结局：{@code in_battle} / {@code battle_lock_unknown}；{@code null} = 锁不在，可以写。 */
        public String outcome(long playerId) {
            if (inBattle.contains(playerId)) {
                return RollbackJob.IN_BATTLE;
            }
            return unknown.contains(playerId) ? RollbackJob.BATTLE_LOCK_UNKNOWN : null;
        }

        /** 全部被挡时作业的结果码：有确认在战的报 {@code in_battle}，否则（只有读不到的）{@code battle_lock_unknown}。 */
        public String rejectCode() {
            return inBattle.isEmpty() ? RollbackJob.BATTLE_LOCK_UNKNOWN : RollbackJob.IN_BATTLE;
        }

        /** dry-run 的逐人标记：在战 true、不在战 false、读不到 null。 */
        public Boolean inBattleOrNull(long playerId) {
            if (unknown.contains(playerId)) {
                return null;
            }
            return inBattle.contains(playerId);
        }

        /**
         * 进事件 / 摘要 / dry-run 应答的视图：{@code checked}、{@code inBattleCount}、{@code inBattlePlayers}（前 {@value #SAMPLE} 个）、
         * {@code unknownCount}，读失败时另有 {@code error}。
         */
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("checked", checked);
            m.put("inBattleCount", inBattle.size());
            m.put("inBattlePlayers", inBattle.stream().limit(SAMPLE).map(Long::toUnsignedString).toList());
            m.put("unknownCount", unknown.size());
            if (error != null) {
                m.put("error", error);
            }
            return m;
        }
    }

    private final Reader reader;
    private final Duration wait;

    /**
     * @param reader 批量读锁（生产：惰性取 Redis 客户端，装配期不连接）
     * @param wait   一次检查全程最多等多久（{@code xm.data.ops.battle-lock-wait}）
     */
    public BattleLockGate(Reader reader, Duration wait) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.wait = Objects.requireNonNull(wait, "wait");
        if (wait.isNegative() || wait.isZero()) {
            throw new IllegalArgumentException("战斗锁读取等待必须为正：" + wait);
        }
    }

    /**
     * 查一批玩家（重复的只查一次）。在调用线程上限时等待；不抛读失败——读不到的人记进 {@link Result#unknown()}。
     *
     * @param checkpoint 每块之前调一次（作业传 {@code JobContext::checkpoint}：心跳丢失时在这里抛出、不再读）
     */
    public Result check(Collection<Long> playerIds, Runnable checkpoint) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(playerIds));
        Set<Long> inBattle = new LinkedHashSet<>();
        Set<Long> unknown = new LinkedHashSet<>();
        String error = null;
        long deadline = System.nanoTime() + wait.toNanos();
        int from = 0;
        while (from < ids.size()) {
            checkpoint.run();
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            Map<Long, Boolean> answer;
            try {
                answer = read(chunk, deadline);
            } catch (ReadFailedException e) {
                error = e.getMessage();
                break;
            }
            for (Long id : chunk) {
                Boolean locked = answer.get(id);
                if (locked == null) {
                    unknown.add(id); // 读取方没回答这个人：不当成「不在战斗」
                } else if (locked) {
                    inBattle.add(id);
                }
            }
            from += chunk.size();
        }
        if (error != null) {
            unknown.addAll(ids.subList(from, ids.size())); // 失败的这一块与之后没发出的块
        } else if (!unknown.isEmpty()) {
            error = "战斗锁读取的应答里缺 " + unknown.size() + " 名玩家";
        }
        return new Result(ids.size(), inBattle, unknown, error);
    }

    private Map<Long, Boolean> read(List<Long> chunk, long deadlineNanos) {
        CompletableFuture<Map<Long, Boolean>> future;
        try {
            future = reader.existsAll(List.copyOf(chunk));
        } catch (RuntimeException e) {
            throw new ReadFailedException("读战斗锁出错：" + e);
        }
        if (future == null) {
            throw new ReadFailedException("读战斗锁没有返回结果");
        }
        try {
            Map<Long, Boolean> answer = future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (answer == null) {
                throw new ReadFailedException("读战斗锁得到空应答");
            }
            return answer;
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new ReadFailedException("读战斗锁超时（" + wait.toMillis() + " ms 内没有读完）");
        } catch (ExecutionException e) {
            throw new ReadFailedException("读战斗锁失败：" + (e.getCause() == null ? e : e.getCause()));
        } catch (CancellationException e) {
            throw new ReadFailedException("读战斗锁被取消");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReadFailedException("读战斗锁时线程被中断");
        }
    }

    /** 一块没读成（只在本类内部用：{@link #check} 把它折成 {@link Result#unknown()}）。 */
    private static final class ReadFailedException extends RuntimeException {
        ReadFailedException(String message) {
            super(message.length() <= ERROR_MAX ? message : message.substring(0, ERROR_MAX), null, false, false);
        }
    }
}
