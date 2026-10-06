package com.game.scene.testing;

import com.game.discovery.battle.BattleRedis;
import com.game.discovery.battle.BattleRedis.EnterRead;
import com.game.discovery.battle.BattleRedis.SettlementField;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleSettlementEvent;
import com.game.scene.battle.BattleLocks;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 内存版 {@link BattleLocks}：一个假的 Redis（锁 Hash、待结算记录 Hash、已销账墓碑，带 TTL），每个方法按 {@link BattleRedis} 里对应脚本的
 * <b>真值表</b>执行（逐段照抄 Lua 的判定次序与返回码；真 Redis 上的同一份真值表由 xm-discovery 的 {@code BattleRedisScriptsIntegrationTest} 钉住）。
 *
 * <p><b>两件事分开</b>——这是它存在的理由（scene-battle-spec §1.8：次序不靠连接 FIFO）：
 * <ul>
 *   <li>「Redis 执行这段脚本」：{@link Call#execute()}，对内存模型生效、算出回复；</li>
 *   <li>「回复回到调用方」：{@link Call#reply()}，完成那次调用返回的 future。</li>
 * </ul>
 * 缺省是<b>自动模式</b>：调用到达时立即执行并回复（future 已完成；被测代码的回调此时只是入队到 {@link ManualExecutor}，测试 {@code runAll()} 后才跑）。
 * 对想控制次序的脚本先 {@link #hold}，之后到达的调用挂起成 {@link Call}，测试按任意次序 {@code execute()} / {@code reply()} / {@code fail(...)}：
 * <pre>{@code
 * locks.hold(Op.ENTER_READ, Op.ACK);
 * ...                                   // 被测代码发出恢复读、发出销账
 * Call read = locks.take(Op.ENTER_READ).execute();   // Redis 先执行读（快照里还有记录）
 * locks.take(Op.ACK).complete();                     // 再执行销账并先把销账的回复交回去
 * logic.runAll();
 * read.reply();                                      // 更早拍下的快照现在才回来
 * logic.runAll();
 * }</pre>
 * 重放（Redisson {@code retryAttempts = 1}：首发已执行、回复超时、原样重发）：{@link Call#replay()}（= 执行两遍，回复第二遍的结果）；
 * 「执行了但调用方只看到超时」：{@link Call#executeThenFail}。自动模式下的同类注入：{@link #failNext}、{@link #failNextAfterExecuting}、
 * {@link #failAlways}、{@link #replayNext}；在回调中途制造意外异常：{@link #throwNext}。
 *
 * <p>全部调用按到达次序记在 {@link #calls()}（含参数），断言「调了哪几次、先后如何」用 {@link #ops()} / {@link #calls(Op)} / {@link Call#toString()}。
 * 直接摆状态 / 看状态用下面「模型」一节的方法（它们不算调用、不进记录）。
 *
 * <p>时间取构造时给的毫秒时钟（一般是 {@link ManualClock#epochMillis}）：键到了 TTL 就消失，{@code TTL} 的取整同 Redis（四舍五入到秒）。
 * 只在一个线程上用。
 */
public final class FakeBattleLocks implements BattleLocks {

    /** 端口上的每个方法（= 一段脚本）。 */
    public enum Op {
        PREPARE_LOCK, CONFIRM, CANCEL_OFFLINE, DELETE_PREPARING, DELETE_IF_MATCH, TOUCH, HOLD, ACK, ENTER_READ, READ_SETTLEMENT,
        READ_LOCK, DELETE_FIELD
    }

    /** 一次调用：参数、在假 Redis 上执行了几遍、回复交回去没有。 */
    public final class Call {

        private final Op op;
        private final long playerId;
        private final long battleId;
        private final Map<String, Object> args;
        private final Supplier<Object> script;
        private final CompletableFuture<Object> future = new CompletableFuture<>();
        private int executions;
        private Object lastReply;

        private Call(Op op, long playerId, long battleId, Map<String, Object> args, Supplier<Object> script) {
            this.op = op;
            this.playerId = playerId;
            this.battleId = battleId;
            this.args = Collections.unmodifiableMap(args);
            this.script = script;
        }

        public Op op() {
            return op;
        }

        public long playerId() {
            return playerId;
        }

        /** 这次调用针对的 battle_id；没有这个参数的脚本（{@code ENTER_READ} / {@code READ_LOCK} / {@code DELETE_FIELD}）为 0。 */
        public long battleId() {
            return battleId;
        }

        /** 其余参数（按脚本的 ARGV 次序；名字见各方法）。 */
        public Map<String, Object> args() {
            return args;
        }

        /** 取一个整数参数（如 {@code "ttl"}、{@code "deadline"}、{@code "prepareDeadline"}、{@code "hold"}、{@code "node"}）。 */
        public long longArg(String name) {
            Object value = args.get(name);
            if (!(value instanceof Number n)) {
                throw new IllegalArgumentException(op + " 没有整数参数 " + name + "：" + args);
            }
            return n.longValue();
        }

        /** Redis 执行这段脚本一遍（对模型生效、记下回复），不把回复交回去。再调一次 = 同一段脚本被重发又执行了一遍。 */
        public Call execute() {
            lastReply = script.get();
            executions++;
            return this;
        }

        /** 把最近一次执行的回复交回调用方（还没执行过就先执行一遍）。 */
        public Call reply() {
            if (executions == 0) {
                execute();
            }
            future.complete(lastReply);
            return this;
        }

        /** 执行并回复（自动模式做的就是它）。 */
        public Call complete() {
            return execute().reply();
        }

        /** Redisson 重放：首发已执行、回复丢了、原样重发又执行一遍，调用方拿到的是第二遍的回复。 */
        public Call replay() {
            execute();
            execute();
            return reply();
        }

        /** 不执行脚本，直接用给定的值回复（造真值表之外的回复，如 null）。 */
        public Call replyWith(Object value) {
            future.complete(value);
            return this;
        }

        /** 以异常完成（不执行；之前 {@link #execute()} 过的效果保留）。 */
        public Call fail(Throwable error) {
            future.completeExceptionally(error);
            return this;
        }

        /** 「脚本已执行、只是回复丢了」：执行一遍，调用方看到的是异常（超时）。 */
        public Call executeThenFail(Throwable error) {
            execute();
            return fail(error);
        }

        /** 在假 Redis 上执行过几遍。 */
        public int executions() {
            return executions;
        }

        /** 回复（或异常）已经交回调用方。 */
        public boolean replied() {
            return future.isDone();
        }

        /** 最近一次执行算出的回复（还没执行过为 null）。 */
        public Object lastReply() {
            return lastReply;
        }

        @SuppressWarnings("unchecked")
        private <T> CompletableFuture<T> future() {
            return (CompletableFuture<T>) future;
        }

        /** 形如 {@code ACK(player=1001, battle=7)}、{@code TOUCH(player=1001, battle=7, ttl=360, state=F, deadline=…, prepareDeadline=…)}。 */
        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(op.name()).append("(player=").append(Long.toUnsignedString(playerId));
            if (battleId != 0) {
                out.append(", battle=").append(Long.toUnsignedString(battleId));
            }
            args.forEach((name, value) -> out.append(", ").append(name).append('=')
                    .append(value instanceof byte[] b ? new String(b, StandardCharsets.ISO_8859_1) : String.valueOf(value)));
            return out.append(')').toString();
        }
    }

    /** 自动模式下对某段脚本注入的故障。 */
    private record Fault(Throwable error, boolean executeFirst, boolean replay) {
    }

    /** 一把锁：Hash 的字段 + 过期时刻（-1 = 没有 TTL）。 */
    private static final class LockRow {
        final Map<String, String> fields = new LinkedHashMap<>();
        long expireAtMs = -1;
    }

    private static final long NO_EXPIRY = -1;

    private final LongSupplier nowMs;
    private final Map<Long, LockRow> locks = new HashMap<>();
    /** 玩家 → 字段名（原始字节按 ISO-8859-1 映成串，无损）→ 值；保持写入次序。 */
    private final Map<Long, LinkedHashMap<String, byte[]>> settlements = new HashMap<>();
    /** "pid:bid" → 墓碑的过期时刻。 */
    private final Map<String, Long> tombstones = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final EnumSet<Op> held = EnumSet.noneOf(Op.class);
    private final Map<Op, Deque<Fault>> faults = new EnumMap<>(Op.class);
    private final Map<Op, Throwable> alwaysFailing = new EnumMap<>(Op.class);
    private final Map<Op, Deque<RuntimeException>> throwing = new EnumMap<>(Op.class);

    /** @param nowMs 毫秒时钟（TTL 与过期按它算），一般传 {@code clock::epochMillis} */
    public FakeBattleLocks(LongSupplier nowMs) {
        this.nowMs = nowMs;
    }

    // ================================================================== 端口（每个方法 = 一段脚本的真值表）

    /** 参数名：{@code node, deadline, prepareDeadline, ttl}。 */
    @Override
    public CompletableFuture<String> prepareLock(long playerId, long battleId, int battleNodeId, long deadlineMs, long prepareDeadlineMs,
                                                 long ttlSec) {
        return submit(Op.PREPARE_LOCK, playerId, battleId,
                args("node", battleNodeId, "deadline", deadlineMs, "prepareDeadline", prepareDeadlineMs, "ttl", ttlSec), () -> {
                    LockRow row = lockRow(playerId);
                    String x = unsigned(battleId);
                    String current = row == null ? null : row.fields.get(BattleRedis.FIELD_BATTLE);
                    if (current == null) {
                        // Lua 判的是「键里没有 b」（HGET 回 nil），不是「键不存在」：键在而缺 b（残缺的锁）同样写入——
                        // HSET 五个字段（s 一律写成 P，键上别的字段保留）+ EXPIRE，回 "0"
                        if (row == null) {
                            row = new LockRow();
                            locks.put(playerId, row);
                        }
                        row.fields.put(BattleRedis.FIELD_BATTLE, x);
                        row.fields.put(BattleRedis.FIELD_NODE, Integer.toUnsignedString(battleNodeId));
                        row.fields.put(BattleRedis.FIELD_STATE, BattleRedis.STATE_PREPARING);
                        row.fields.put(BattleRedis.FIELD_DEADLINE, unsigned(deadlineMs));
                        row.fields.put(BattleRedis.FIELD_PREPARE_DEADLINE, unsigned(prepareDeadlineMs));
                        expire(row, ttlSec);
                        return "0";
                    }
                    if (x.equals(current) && BattleRedis.STATE_PREPARING.equals(row.fields.get(BattleRedis.FIELD_STATE))) {
                        // 同局且仍是 P：按重放成功，重写 n d p 并续 TTL
                        row.fields.put(BattleRedis.FIELD_NODE, Integer.toUnsignedString(battleNodeId));
                        row.fields.put(BattleRedis.FIELD_DEADLINE, unsigned(deadlineMs));
                        row.fields.put(BattleRedis.FIELD_PREPARE_DEADLINE, unsigned(prepareDeadlineMs));
                        expire(row, ttlSec);
                        return "0";
                    }
                    return current;
                });
    }

    /** 参数名：{@code deadline, ttl}（0 = 不改）。 */
    @Override
    public CompletableFuture<Map<String, String>> confirm(long playerId, long battleId, long deadlineMs, long ttlSec) {
        return submit(Op.CONFIRM, playerId, battleId, args("deadline", deadlineMs, "ttl", ttlSec), () -> {
            LockRow row = lockRow(playerId);
            if (row == null || !unsigned(battleId).equals(row.fields.get(BattleRedis.FIELD_BATTLE))) {
                return null;
            }
            row.fields.put(BattleRedis.FIELD_STATE, BattleRedis.STATE_FIGHTING);
            if (deadlineMs != 0) {
                row.fields.put(BattleRedis.FIELD_DEADLINE, unsigned(deadlineMs));
            }
            if (ttlSec != 0) {
                expire(row, ttlSec);
            }
            return new LinkedHashMap<>(row.fields);
        });
    }

    @Override
    public CompletableFuture<Long> cancelOffline(long playerId, long battleId) {
        return submit(Op.CANCEL_OFFLINE, playerId, battleId, args(), () -> deletePreparing(playerId, battleId));
    }

    @Override
    public CompletableFuture<Long> deletePreparingIfMatch(long playerId, long battleId) {
        return submit(Op.DELETE_PREPARING, playerId, battleId, args(), () -> deletePreparing(playerId, battleId));
    }

    /** {@code CANCEL_OFFLINE} 与「只删备战锁」共用的那段脚本：b ≠ X → 0；s == F → 2；否则 DEL → 1。 */
    private long deletePreparing(long playerId, long battleId) {
        LockRow row = lockRow(playerId);
        if (row == null || !unsigned(battleId).equals(row.fields.get(BattleRedis.FIELD_BATTLE))) {
            return BattleRedis.PREPARING_DELETE_MISS;
        }
        if (BattleRedis.STATE_FIGHTING.equals(row.fields.get(BattleRedis.FIELD_STATE))) {
            return BattleRedis.PREPARING_DELETE_FIGHTING;
        }
        locks.remove(playerId);
        return BattleRedis.PREPARING_DELETE_DONE;
    }

    @Override
    public CompletableFuture<Long> deleteIfMatch(long playerId, long battleId) {
        return submit(Op.DELETE_IF_MATCH, playerId, battleId, args(), () -> {
            LockRow row = lockRow(playerId);
            if (row != null && unsigned(battleId).equals(row.fields.get(BattleRedis.FIELD_BATTLE))) {
                locks.remove(playerId);
                return 1L;
            }
            return 0L;
        });
    }

    /** 参数名：{@code ttl, state, deadline, prepareDeadline}。 */
    @Override
    public CompletableFuture<Long> touch(long playerId, long battleId, long ttlSec, String state, long deadlineMs, long prepareDeadlineMs) {
        return submit(Op.TOUCH, playerId, battleId,
                args("ttl", ttlSec, "state", state, "deadline", deadlineMs, "prepareDeadline", prepareDeadlineMs), () -> {
                    LockRow row = lockRow(playerId);
                    if (row == null || !unsigned(battleId).equals(row.fields.get(BattleRedis.FIELD_BATTLE))) {
                        return BattleRedis.TOUCH_MISS;
                    }
                    if (BattleRedis.STATE_PREPARING.equals(state)
                            && BattleRedis.STATE_FIGHTING.equals(row.fields.get(BattleRedis.FIELD_STATE))) {
                        // 单调：F 不被 P 降级，d / p / TTL 都不动
                        return BattleRedis.TOUCH_KEPT_FIGHTING;
                    }
                    row.fields.put(BattleRedis.FIELD_STATE, state);
                    row.fields.put(BattleRedis.FIELD_DEADLINE, unsigned(deadlineMs));
                    row.fields.put(BattleRedis.FIELD_PREPARE_DEADLINE, unsigned(prepareDeadlineMs));
                    expire(row, ttlSec);
                    return BattleRedis.TOUCH_HIT;
                });
    }

    /** 参数名：{@code hold}。 */
    @Override
    public CompletableFuture<Long> hold(long playerId, long battleId, long holdSec) {
        return submit(Op.HOLD, playerId, battleId, args("hold", holdSec), () -> {
            LockRow row = lockRow(playerId);
            if (row == null || !unsigned(battleId).equals(row.fields.get(BattleRedis.FIELD_BATTLE))) {
                return 0L;
            }
            long ttl = ttlSec(row);
            if (ttl >= 0 && ttl < holdSec) {
                expire(row, holdSec);
            }
            return 1L;
        });
    }

    @Override
    public CompletableFuture<Long> ack(long playerId, long battleId) {
        return submit(Op.ACK, playerId, battleId, args(), () -> {
            long bits = 0;
            Map<String, byte[]> records = settlements.get(playerId);
            if (records != null && records.remove(unsigned(battleId)) != null) {
                bits = 1;
                if (records.isEmpty()) {
                    settlements.remove(playerId);
                }
            }
            LockRow row = lockRow(playerId);
            if (row != null && unsigned(battleId).equals(row.fields.get(BattleRedis.FIELD_BATTLE))) {
                locks.remove(playerId);
                bits += 2;
            }
            // 无论记录在不在都写墓碑（之后这一局的落库回 -1）
            tombstones.put(tombstoneKey(playerId, battleId), nowMs.getAsLong() + BattleRedis.SETTLED_TOMBSTONE_TTL_SEC * 1000);
            return bits;
        });
    }

    @Override
    public CompletableFuture<EnterRead> enterRead(long playerId) {
        return submit(Op.ENTER_READ, playerId, 0, args(), () -> snapshot(playerId));
    }

    @Override
    public CompletableFuture<byte[]> readSettlement(long playerId, long battleId) {
        return submit(Op.READ_SETTLEMENT, playerId, battleId, args(), () -> {
            Map<String, byte[]> records = settlements.get(playerId);
            return records == null ? null : records.get(unsigned(battleId));
        });
    }

    @Override
    public CompletableFuture<Long> readLockBattleId(long playerId) {
        return submit(Op.READ_LOCK, playerId, 0, args(), () -> lockBattleId(playerId));
    }

    /** 参数名：{@code field}（字段名的原始字节）。 */
    @Override
    public CompletableFuture<Long> deleteSettlementField(long playerId, byte[] rawField) {
        byte[] name = rawField.clone();
        return submit(Op.DELETE_FIELD, playerId, 0, args("field", name), () -> {
            Map<String, byte[]> records = settlements.get(playerId);
            if (records == null || records.remove(latin1(name)) == null) {
                return 0L;
            }
            if (records.isEmpty()) {
                settlements.remove(playerId);
            }
            return 1L;
        });
    }

    // ================================================================== 控制：挂起、故障、重放

    /** 之后到达的这些脚本挂起（不执行、不回复），由测试经 {@link #take} 等取出来手动推进。不给参数 = 全部脚本。 */
    public FakeBattleLocks hold(Op... ops) {
        if (ops.length == 0) {
            held.addAll(EnumSet.allOf(Op.class));
        } else {
            held.addAll(List.of(ops));
        }
        return this;
    }

    /** 这些脚本恢复自动模式（不给参数 = 全部）；已经挂起的调用不受影响，仍要手动推进（或 {@link #completeAll()}）。 */
    public FakeBattleLocks release(Op... ops) {
        if (ops.length == 0) {
            held.clear();
        } else {
            List.of(ops).forEach(held::remove);
        }
        return this;
    }

    /** 自动模式：这段脚本的下一次调用<b>不执行</b>、直接以这个异常完成（连不上 Redis）。可以排多个，按次序消耗。 */
    public FakeBattleLocks failNext(Op op, Throwable error) {
        faults.computeIfAbsent(op, k -> new ArrayDeque<>()).add(new Fault(error, false, false));
        return this;
    }

    /** 自动模式：这段脚本的下一次调用<b>先执行</b>、再以这个异常完成（执行了但回复丢了 / 超时）。 */
    public FakeBattleLocks failNextAfterExecuting(Op op, Throwable error) {
        faults.computeIfAbsent(op, k -> new ArrayDeque<>()).add(new Fault(error, true, false));
        return this;
    }

    /** 自动模式：这段脚本的下一次调用被重放（执行两遍，回复第二遍的结果）。 */
    public FakeBattleLocks replayNext(Op op) {
        faults.computeIfAbsent(op, k -> new ArrayDeque<>()).add(new Fault(null, true, true));
        return this;
    }

    /**
     * 这段脚本的下一次调用<b>同步抛出</b>这个异常（不执行；调用照样记进 {@link #calls()}）。端口约定是「出错以异常完成」，真实现不会这样——
     * 它的用处是在被测回调的<b>指定位置</b>制造一个意外异常（例如让结算收尾里的 {@code HOLD} 抛出），验证回调中途出错时状态不被卡住。
     * 挂起与否都生效；可以排多个。
     */
    public FakeBattleLocks throwNext(Op op, RuntimeException error) {
        throwing.computeIfAbsent(op, k -> new ArrayDeque<>()).add(error);
        return this;
    }

    /** 自动模式：这段脚本之后每次调用都不执行、以这个异常完成，直到 {@link #heal}。 */
    public FakeBattleLocks failAlways(Op op, Throwable error) {
        alwaysFailing.put(op, error);
        return this;
    }

    /** 撤掉 {@link #failAlways} 与还没消耗的一次性故障 / 重放 / 同步抛出（不给参数 = 全部脚本）。 */
    public FakeBattleLocks heal(Op... ops) {
        if (ops.length == 0) {
            alwaysFailing.clear();
            faults.clear();
            throwing.clear();
        } else {
            for (Op op : ops) {
                alwaysFailing.remove(op);
                faults.remove(op);
                throwing.remove(op);
            }
        }
        return this;
    }

    // ================================================================== 调用记录

    /** 全部调用，按到达次序（含已回复与挂起的）。 */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** 某段脚本的全部调用，按到达次序。 */
    public List<Call> calls(Op op) {
        return calls.stream().filter(c -> c.op == op).toList();
    }

    /** 调用序列只看脚本名（断言先后次序用）。 */
    public List<Op> ops() {
        return calls.stream().map(Call::op).toList();
    }

    public int count(Op op) {
        return calls(op).size();
    }

    /** 还没回复的调用，按到达次序。 */
    public List<Call> pending() {
        return calls.stream().filter(c -> !c.replied()).toList();
    }

    public List<Call> pending(Op op) {
        return calls.stream().filter(c -> c.op == op && !c.replied()).toList();
    }

    /** 最早一个还没回复的这段脚本的调用；没有就抛（测试写错了，或被测代码没发）。 */
    public Call take(Op op) {
        return calls.stream().filter(c -> c.op == op && !c.replied()).findFirst()
                .orElseThrow(() -> new IllegalStateException("没有挂起的 " + op + " 调用；全部调用：" + calls));
    }

    /** 这段脚本最近一次调用（不管回复没有）；没有就抛。 */
    public Call last(Op op) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (calls.get(i).op == op) {
                return calls.get(i);
            }
        }
        throw new IllegalStateException("没有 " + op + " 调用；全部调用：" + calls);
    }

    /** 把还没回复的调用按到达次序逐个执行并回复；返回完成了几个。 */
    public int completeAll() {
        List<Call> waiting = pending();
        waiting.forEach(Call::complete);
        return waiting.size();
    }

    /** 清掉<b>已回复</b>的调用记录（模型状态不动）；还挂着没回复的调用留在记录里，之后照样能 {@link #take} 到。 */
    public void clearCalls() {
        calls.removeIf(Call::replied);
    }

    // ================================================================== 模型：直接摆状态 / 看状态（不算调用）

    /** 锁的全部字段（副本）；没有锁（或已过期）为 null。 */
    public Map<String, String> lock(long playerId) {
        LockRow row = lockRow(playerId);
        return row == null ? null : new LinkedHashMap<>(row.fields);
    }

    /** 锁的 b；没有锁为 0。 */
    public long lockBattleId(long playerId) {
        LockRow row = lockRow(playerId);
        return row == null ? 0 : BattleRedis.parseUnsigned(row.fields.get(BattleRedis.FIELD_BATTLE));
    }

    /** 锁的 s（{@code "P"} / {@code "F"}）；没有锁为 null。 */
    public String lockState(long playerId) {
        LockRow row = lockRow(playerId);
        return row == null ? null : row.fields.get(BattleRedis.FIELD_STATE);
    }

    /** 锁的剩余 TTL（秒）：-2 = 没有锁，-1 = 没有 TTL。 */
    public long lockTtlSec(long playerId) {
        LockRow row = lockRow(playerId);
        return row == null ? -2 : ttlSec(row);
    }

    /** 摆一把完整的锁（字段写法同 {@code PREPARE_LOCK} / {@code CONFIRM}）；{@code ttlSec < 0} = 不带 TTL。 */
    public void putLock(long playerId, long battleId, int battleNodeId, String state, long deadlineMs, long prepareDeadlineMs,
                        long ttlSec) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(BattleRedis.FIELD_BATTLE, unsigned(battleId));
        fields.put(BattleRedis.FIELD_NODE, Integer.toUnsignedString(battleNodeId));
        fields.put(BattleRedis.FIELD_STATE, state);
        fields.put(BattleRedis.FIELD_DEADLINE, unsigned(deadlineMs));
        fields.put(BattleRedis.FIELD_PREPARE_DEADLINE, unsigned(prepareDeadlineMs));
        putLockFields(playerId, fields, ttlSec);
    }

    /** 摆一把字段任意的锁（缺字段、怪值都行，测 §1.2 的取值规则用）；{@code ttlSec < 0} = 不带 TTL。 */
    public void putLockFields(long playerId, Map<String, String> fields, long ttlSec) {
        LockRow row = new LockRow();
        row.fields.putAll(fields);
        locks.put(playerId, row);
        if (ttlSec >= 0) {
            expire(row, ttlSec);
        }
    }

    public void removeLock(long playerId) {
        locks.remove(playerId);
    }

    /** 待结算记录里有没有这一局（规范字段名）。 */
    public boolean hasSettlement(long playerId, long battleId) {
        Map<String, byte[]> records = settlements.get(playerId);
        return records != null && records.containsKey(unsigned(battleId));
    }

    /** 待结算记录现有的字段数。 */
    public int settlementCount(long playerId) {
        Map<String, byte[]> records = settlements.get(playerId);
        return records == null ? 0 : records.size();
    }

    /**
     * battle 落库一份结算（{@code STORE_SETTLEMENT} 的真值表，字段名 = battle_id、值 = {@code BattleSettlementEvent} 字节）：
     * 这一局已销账（墓碑还在）→ <b>不写</b>、回 {@link BattleRedis#STORE_ALREADY_SETTLED}；否则写入、回字段数。
     */
    public long storeSettlement(BattleSettlementData settlement) {
        return storeSettlement(settlement.getPlayerId(), settlement.getBattleId(), record(settlement));
    }

    /** 同上，值是任意字节（造坏记录用：解析不了的字节、blob 里的 battle_id / player_id 与字段名或玩家不符）。 */
    public long storeSettlement(long playerId, long battleId, byte[] bytes) {
        Long tombstone = tombstones.get(tombstoneKey(playerId, battleId));
        if (tombstone != null && tombstone > nowMs.getAsLong()) {
            return BattleRedis.STORE_ALREADY_SETTLED;
        }
        Map<String, byte[]> records = settlements.computeIfAbsent(playerId, k -> new LinkedHashMap<>());
        records.put(unsigned(battleId), bytes.clone());
        return records.size();
    }

    /** 直接塞一个字段名任意的字段（不看墓碑；造坏字段名用：非数字、前导零、非法 UTF-8）。 */
    public void putRawSettlementField(long playerId, byte[] rawName, byte[] value) {
        settlements.computeIfAbsent(playerId, k -> new LinkedHashMap<>()).put(latin1(rawName), value.clone());
    }

    /** 这一局的已销账墓碑还在不在（{@code ACK} 写的；在 = 之后的落库回 -1）。 */
    public boolean settled(long playerId, long battleId) {
        Long tombstone = tombstones.get(tombstoneKey(playerId, battleId));
        return tombstone != null && tombstone > nowMs.getAsLong();
    }

    /** {@code ENTER_READ} 此刻会读到的快照（不算调用）。 */
    public EnterRead snapshot(long playerId) {
        LockRow row = lockRow(playerId);
        List<SettlementField> fields = new ArrayList<>();
        Map<String, byte[]> records = settlements.get(playerId);
        if (records != null) {
            records.forEach((name, value) -> fields.add(new SettlementField(name.getBytes(StandardCharsets.ISO_8859_1), value)));
        }
        return new EnterRead(row == null ? Map.of() : row.fields, row == null ? -2 : ttlSec(row), fields);
    }

    /** 一份结算在 Redis 里的字节（{@code BattleSettlementEvent{settlement}}，与 battle 落库、Dubbo 投递的 body 逐字节相同）。 */
    public static byte[] record(BattleSettlementData settlement) {
        return BattleSettlementEvent.newBuilder().setSettlement(settlement).build().toByteArray();
    }

    // ================================================================== 内部

    private <T> CompletableFuture<T> submit(Op op, long playerId, long battleId, Map<String, Object> args, Supplier<Object> script) {
        Call call = new Call(op, playerId, battleId, args, script);
        calls.add(call);
        Deque<RuntimeException> thrown = throwing.get(op);
        if (thrown != null && !thrown.isEmpty()) {
            RuntimeException error = thrown.poll();
            call.fail(error);
            throw error;
        }
        if (held.contains(op)) {
            return call.future();
        }
        Throwable always = alwaysFailing.get(op);
        if (always != null) {
            call.fail(always);
            return call.future();
        }
        Deque<Fault> queued = faults.get(op);
        Fault fault = queued == null ? null : queued.poll();
        if (fault == null) {
            call.complete();
        } else if (fault.replay()) {
            call.replay();
        } else if (fault.executeFirst()) {
            call.executeThenFail(fault.error());
        } else {
            call.fail(fault.error());
        }
        return call.future();
    }

    /** 取锁；到了 TTL 的当场删掉。 */
    private LockRow lockRow(long playerId) {
        LockRow row = locks.get(playerId);
        if (row != null && row.expireAtMs != NO_EXPIRY && row.expireAtMs <= nowMs.getAsLong()) {
            locks.remove(playerId);
            return null;
        }
        return row;
    }

    private void expire(LockRow row, long ttlSec) {
        row.expireAtMs = nowMs.getAsLong() + ttlSec * 1000;
        if (ttlSec <= 0) {
            // EXPIRE 0 / 负数 = 立即删除
            locks.values().remove(row);
        }
    }

    /** Redis 的 TTL：剩余毫秒四舍五入到秒；没有 TTL 为 -1。 */
    private long ttlSec(LockRow row) {
        if (row.expireAtMs == NO_EXPIRY) {
            return -1;
        }
        return (row.expireAtMs - nowMs.getAsLong() + 500) / 1000;
    }

    private static Map<String, Object> args(Object... nameValuePairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            out.put((String) nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return out;
    }

    private static String unsigned(long value) {
        return Long.toUnsignedString(value);
    }

    private static String latin1(byte[] raw) {
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    private static String tombstoneKey(long playerId, long battleId) {
        return Long.toUnsignedString(playerId) + ":" + Long.toUnsignedString(battleId);
    }
}
