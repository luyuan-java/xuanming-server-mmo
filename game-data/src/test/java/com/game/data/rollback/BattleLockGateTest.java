package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.ops.OpsJobRunner.JobAbortedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * 战斗锁闸本身（不碰库）：三态判定、fail-closed 的各种来路、分块与「一块失败就停」、全程共用一个截止时刻、检查点。
 * 作业与 dry-run 里的接线在 {@link RollbackBattleLockSqlTest}。
 */
class BattleLockGateTest {

    private final List<List<Long>> calls = new ArrayList<>();

    private static void noCheckpoint() {
    }

    private static CompletableFuture<Map<Long, Boolean>> answer(Collection<Long> ids, Collection<Long> locked) {
        Map<Long, Boolean> out = new LinkedHashMap<>();
        for (Long id : ids) {
            out.put(id, locked.contains(id));
        }
        return CompletableFuture.completedFuture(out);
    }

    private BattleLockGate gate(BattleLockGate.Reader reader) {
        return new BattleLockGate(ids -> {
            calls.add(List.copyOf(ids));
            return reader.existsAll(ids);
        }, Duration.ofSeconds(2));
    }

    @Test
    void 三态_锁在in_battle_不在null_保持入参顺序_重复的只查一次() {
        BattleLockGate.Result r = gate(ids -> answer(ids, List.of(7L, 3L)))
                .check(List.of(9L, 7L, 3L, 7L, 5L), BattleLockGateTest::noCheckpoint);

        assertThat(calls).containsExactly(List.of(9L, 7L, 3L, 5L));
        assertThat(r.checked()).isEqualTo(4);
        assertThat(r.inBattle()).containsExactly(7L, 3L);
        assertThat(r.unknown()).isEmpty();
        assertThat(r.error()).isNull();
        assertThat(r.blocked()).isTrue();
        assertThat(r.outcome(7)).isEqualTo("in_battle");
        assertThat(r.outcome(9)).isNull();
        assertThat(r.outcome(12345)).as("没问过的人不由它下结论").isNull();
        assertThat(r.rejectCode()).isEqualTo("in_battle");
        assertThat(r.inBattleOrNull(7)).isTrue();
        assertThat(r.inBattleOrNull(9)).isFalse();
        assertThat(r.view()).containsExactly(Map.entry("checked", 4), Map.entry("inBattleCount", 2),
                Map.entry("inBattlePlayers", List.of("7", "3")), Map.entry("unknownCount", 0));
    }

    @Test
    void 没有人要查_不读_不挡() {
        BattleLockGate.Result r = gate(ids -> answer(ids, List.of())).check(List.of(), BattleLockGateTest::noCheckpoint);

        assertThat(calls).isEmpty();
        assertThat(r).isEqualTo(BattleLockGate.Result.none());
        assertThat(r.blocked()).isFalse();
        assertThat(r.view()).containsEntry("checked", 0).doesNotContainKey("error");
    }

    @Test
    void 无符号玩家号_视图按无符号十进制() {
        long big = Long.parseUnsignedLong("18446744073709551615");
        BattleLockGate.Result r = gate(ids -> answer(ids, List.of(big))).check(List.of(big),
                BattleLockGateTest::noCheckpoint);
        assertThat(r.view().get("inBattlePlayers")).isEqualTo(List.of("18446744073709551615"));
    }

    @Test
    void 读失败的五种来路_都是battle_lock_unknown_不是不在战_也不抛给调用方() {
        List<BattleLockGate.Reader> failures = List.of(
                ids -> CompletableFuture.failedFuture(new IllegalStateException("boom")),
                ids -> {
                    throw new IllegalStateException("boom");
                },
                ids -> null,
                ids -> CompletableFuture.completedFuture(null),
                ids -> {
                    CompletableFuture<Map<Long, Boolean>> f = new CompletableFuture<>();
                    f.cancel(false);
                    return f;
                });
        for (BattleLockGate.Reader failure : failures) {
            BattleLockGate.Result r = new BattleLockGate(failure, Duration.ofSeconds(2))
                    .check(List.of(1L, 2L), BattleLockGateTest::noCheckpoint);

            assertThat(r.unknown()).containsExactly(1L, 2L);
            assertThat(r.inBattle()).isEmpty();
            assertThat(r.blocked()).isTrue();
            assertThat(r.outcome(1)).isEqualTo("battle_lock_unknown");
            assertThat(r.rejectCode()).isEqualTo("battle_lock_unknown");
            assertThat(r.inBattleOrNull(1)).isNull();
            assertThat(r.error()).isNotBlank();
            assertThat(r.view()).containsEntry("unknownCount", 2).containsEntry("inBattleCount", 0).containsKey("error");
        }
    }

    @Test
    void 应答里缺人或值为null_缺的人unknown_答了的人照算() {
        BattleLockGate.Result r = gate(ids -> {
            Map<Long, Boolean> out = new LinkedHashMap<>();
            out.put(1L, true);
            out.put(2L, false);
            out.put(3L, null);
            return CompletableFuture.completedFuture(out);
        }).check(List.of(1L, 2L, 3L, 4L), BattleLockGateTest::noCheckpoint);

        assertThat(r.inBattle()).containsExactly(1L);
        assertThat(r.unknown()).containsExactly(3L, 4L);
        assertThat(r.outcome(2)).isNull();
        assertThat(r.rejectCode()).as("有确认在战的就报 in_battle").isEqualTo("in_battle");
        assertThat(r.error()).contains("缺 2 名玩家");
    }

    @Test
    void 超时_到点就停_取消那次读_错误文本过长截断() {
        CompletableFuture<Map<Long, Boolean>> never = new CompletableFuture<>();
        long start = System.nanoTime();
        BattleLockGate.Result r = new BattleLockGate(ids -> never, Duration.ofMillis(200))
                .check(List.of(1L), BattleLockGateTest::noCheckpoint);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isBetween(180L, 3000L);
        assertThat(r.unknown()).containsExactly(1L);
        assertThat(r.error()).contains("超时").contains("200 ms");
        assertThat(never).isCancelled();

        BattleLockGate.Result longError = new BattleLockGate(
                ids -> CompletableFuture.failedFuture(new IllegalStateException("x".repeat(5000))), Duration.ofSeconds(1))
                .check(List.of(1L), BattleLockGateTest::noCheckpoint);
        assertThat(longError.error()).hasSize(BattleLockGate.ERROR_MAX);
    }

    @Test
    void 分块_每块500人_按入参顺序_每块之前过一次检查点() {
        List<Long> ids = LongStream.rangeClosed(1, 1201).boxed().toList();
        AtomicInteger checkpoints = new AtomicInteger();

        BattleLockGate.Result r = gate(chunk -> answer(chunk, List.of(1L, 500L, 501L, 1201L)))
                .check(ids, checkpoints::incrementAndGet);

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).isEqualTo(ids.subList(0, 500));
        assertThat(calls.get(1)).isEqualTo(ids.subList(500, 1000));
        assertThat(calls.get(2)).isEqualTo(ids.subList(1000, 1201));
        assertThat(checkpoints).hasValue(3);
        assertThat(r.checked()).isEqualTo(1201);
        assertThat(r.inBattle()).containsExactly(1L, 500L, 501L, 1201L);
        assertThat(r.unknown()).isEmpty();
    }

    @Test
    void 一块读失败就停_之后的块不再发_已读到的结论保留_其余全部unknown() {
        List<Long> ids = LongStream.rangeClosed(1, 1201).boxed().toList();

        BattleLockGate.Result r = gate(chunk -> chunk.contains(501L)
                ? CompletableFuture.failedFuture(new IllegalStateException("第二块坏了"))
                : answer(chunk, List.of(7L))).check(ids, BattleLockGateTest::noCheckpoint);

        assertThat(calls).as("第三块没有发出").hasSize(2);
        assertThat(r.inBattle()).containsExactly(7L);
        assertThat(r.outcome(8)).as("第一块读到不在战的照常可写").isNull();
        assertThat(r.unknown()).hasSize(701).contains(501L, 1000L, 1001L, 1201L).doesNotContain(500L);
        assertThat(r.error()).contains("第二块坏了");
        assertThat(r.view()).containsEntry("checked", 1201).containsEntry("unknownCount", 701);
    }

    @Test
    void 全程共用一个截止时刻_不是每块各等一遍() {
        List<Long> ids = LongStream.rangeClosed(1, 1500).boxed().toList();
        // 每块都要 600 ms 才答，截止 1 s：第二块等到一半就到点。若每块各等 1 s，三块都读得完、没有人 unknown
        BattleLockGate slow = new BattleLockGate(chunk -> {
            calls.add(List.copyOf(chunk));
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Map<Long, Boolean> out = new LinkedHashMap<>();
                chunk.forEach(id -> out.put(id, false));
                return out;
            });
        }, Duration.ofSeconds(1));

        BattleLockGate.Result r = slow.check(ids, BattleLockGateTest::noCheckpoint);

        assertThat(calls).as("第三块不再发").hasSize(2);
        assertThat(r.outcome(500)).as("第一块在截止之前读完").isNull();
        assertThat(r.unknown()).hasSize(1000).contains(501L, 1500L).doesNotContain(500L);
        assertThat(r.error()).contains("超时");
    }

    @Test
    void 检查点抛出_心跳丢失_原样抛给作业_不再读() {
        BattleLockGate g = gate(ids -> answer(ids, List.of()));

        assertThatThrownBy(() -> g.check(List.of(1L), () -> {
            throw new JobAbortedException("单飞槽已被收走");
        })).isInstanceOf(JobAbortedException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void 等待必须为正() {
        assertThatThrownBy(() -> new BattleLockGate(ids -> answer(ids, List.of()), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BattleLockGate(null, Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    }
}
