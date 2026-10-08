package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.BattleNodeInfo;
import com.game.common.deadline.Deadline;
import com.game.match.gather.BattleNodes.Census;
import com.game.match.gather.BattleNodes.Lookup;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * battle 节点目录的读口（match-spec §9.6 第 2 步、§9.5 的暂停判定、§4.3 的判死）：只从可分配的条目里等概率选、按 (节点号, 实例) 排除；
 * 目录概况与指标；按节点号比对实例。读失败时三个方法各自回保守的那一支、不抛。
 */
class RedisBattleNodesTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final MemoryDirectory directory = new MemoryDirectory();

    /** 内存里的目录：可以让两种读分别失败。 */
    private static final class MemoryDirectory implements RedisBattleNodes.Directory {
        final List<BattleNodeInfo> entries = new ArrayList<>();
        final Map<Integer, CompletableFuture<Optional<BattleNodeInfo>>> scriptedFinds = new HashMap<>();
        boolean listFails;
        boolean findThrows;

        @Override
        public List<BattleNodeInfo> list() {
            if (listFails) {
                throw new IllegalStateException("注入的故障: 读目录");
            }
            return List.copyOf(entries);
        }

        @Override
        public CompletableFuture<Optional<BattleNodeInfo>> find(int nodeId) {
            if (findThrows) {
                throw new IllegalStateException("注入的故障: 读条目");
            }
            CompletableFuture<Optional<BattleNodeInfo>> scripted = scriptedFinds.get(nodeId);
            if (scripted != null) {
                return scripted;
            }
            return CompletableFuture.completedFuture(entries.stream().filter(e -> e.getNodeId() == nodeId).findFirst());
        }
    }

    private static BattleNodeInfo node(int nodeId, String instanceId, int port) {
        return BattleNodeInfo.newBuilder().setNodeId(nodeId).setInstanceId(instanceId).setRpcHost("10.0.0." + nodeId).setRpcPort(port)
                .setAccepting(true).build();
    }

    private RedisBattleNodes nodes() {
        return new RedisBattleNodes(directory, metrics, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    private double gauge(String state) {
        return meters.get("xm.match.battle.nodes").tag("state", state).gauge().value();
    }

    // ================================================================ pickRandom

    @Test
    void 只从可分配的条目里选_关闸的_没导出控制面的_端口越界的都不选() {
        directory.entries.add(node(1, "a", 21200).toBuilder().setAccepting(false).build());
        directory.entries.add(node(2, "b", 0));
        directory.entries.add(node(3, "c", 21202).toBuilder().setRpcHost(" ").build());
        directory.entries.add(node(4, "d", 70_000));
        directory.entries.add(node(5, "e", 21204));

        for (int i = 0; i < 50; i++) {
            assertThat(nodes().pickRandom(Set.of())).map(BattleNodeInfo::getNodeId).contains(5);
        }
    }

    @Test
    void 可分配的条目等概率_三个节点各约三分之一() {
        directory.entries.add(node(1, "a", 21200));
        directory.entries.add(node(2, "b", 21201));
        directory.entries.add(node(3, "c", 21202));
        RedisBattleNodes nodes = nodes();
        int[] hits = new int[4];

        for (int i = 0; i < 6_000; i++) {
            hits[nodes.pickRandom(Set.of()).orElseThrow().getNodeId()]++;
        }

        // 期望各 2000，标准差约 36.5：±300 是 8 个标准差以外，不会误报
        assertThat(hits[1]).isBetween(1_700, 2_300);
        assertThat(hits[2]).isBetween(1_700, 2_300);
        assertThat(hits[3]).isBetween(1_700, 2_300);
    }

    @Test
    void 随机下标直接映射到候选_上界是候选数而不是目录条目数() {
        directory.entries.add(node(1, "a", 21200).toBuilder().setAccepting(false).build());
        directory.entries.add(node(2, "b", 21201));
        directory.entries.add(node(3, "c", 21202));
        List<Integer> bounds = new ArrayList<>();
        RedisBattleNodes lastOfCandidates = new RedisBattleNodes(directory, metrics, bound -> {
            bounds.add(bound);
            return bound - 1;
        });

        assertThat(lastOfCandidates.pickRandom(Set.of())).map(BattleNodeInfo::getNodeId).contains(3);
        assertThat(bounds).as("3 条里只有 2 条可分配").containsExactly(2);
    }

    @Test
    void 按节点号加实例排除_同号新实例是另一个可以尝试的节点() {
        directory.entries.add(node(1, "inst-new", 21200));
        directory.entries.add(node(2, "inst-b", 21201));

        Optional<BattleNodeInfo> excludingB = nodes().pickRandom(Set.of(BattleNodes.key(2, "inst-b")));
        Optional<BattleNodeInfo> excludingOldInstanceOf1AndB = nodes().pickRandom(Set.of(BattleNodes.key(1, "inst-old"), BattleNodes.key(2, "inst-b")));
        Optional<BattleNodeInfo> excludingBoth = nodes().pickRandom(Set.of(BattleNodes.key(1, "inst-new"), BattleNodes.key(2, "inst-b")));

        assertThat(excludingB).map(BattleNodeInfo::getNodeId).contains(1);
        assertThat(excludingOldInstanceOf1AndB).as("1 号已换了实例：旧实例的排除不挡新实例").map(BattleNodeInfo::getInstanceId).contains("inst-new");
        assertThat(excludingBoth).isEmpty();
    }

    @Test
    void 目录为空_全都不可分配_读失败_都选不出节点_不抛() {
        assertThat(nodes().pickRandom(Set.of())).isEmpty();

        directory.entries.add(node(1, "a", 21200).toBuilder().setAccepting(false).build());
        assertThat(nodes().pickRandom(Set.of())).isEmpty();

        directory.entries.add(node(2, "b", 21201));
        directory.listFails = true;
        assertThat(nodes().pickRandom(Set.of())).isEmpty();
    }

    // ================================================================ census

    @Test
    void 目录概况_可分配与其余分开数_并刷新指标() {
        directory.entries.add(node(1, "a", 21200));
        directory.entries.add(node(2, "b", 21201));
        directory.entries.add(node(3, "c", 21202).toBuilder().setAccepting(false).build());
        directory.entries.add(node(4, "d", 0));

        Census census = nodes().census();

        assertThat(census).isEqualTo(new Census(2, 2, false));
        assertThat(census.nothingAllocatable()).isFalse();
        assertThat(gauge("accepting")).isEqualTo(2.0);
        assertThat(gauge("not_accepting")).isEqualTo(2.0);
    }

    @Test
    void 目录读失败_概况带readFailed_凑单据此暂停_指标清零() {
        directory.entries.add(node(1, "a", 21200));
        nodes().census();
        assertThat(gauge("accepting")).isEqualTo(1.0);
        directory.listFails = true;

        Census census = nodes().census();

        assertThat(census).isEqualTo(new Census(0, 0, true));
        assertThat(census.nothingAllocatable()).isTrue();
        assertThat(gauge("accepting")).isZero();
        assertThat(gauge("not_accepting")).isZero();
    }

    @Test
    void 没有可分配的节点_概况据此暂停凑单() {
        directory.entries.add(node(1, "a", 21200).toBuilder().setAccepting(false).build());

        assertThat(nodes().census().nothingAllocatable()).isTrue();
    }

    // ================================================================ lookup

    @Test
    void 按节点号比对实例_同实例_换了实例_不在目录里() {
        directory.entries.add(node(1, "inst-a", 21200));

        assertThat(nodes().lookup(1, "inst-a")).isEqualTo(Lookup.SAME_INSTANCE);
        assertThat(nodes().lookup(1, "inst-old")).isEqualTo(Lookup.OTHER_INSTANCE);
        assertThat(nodes().lookup(9, "inst-a")).isEqualTo(Lookup.ABSENT);
    }

    @Test
    void 关闸中的节点仍在册_判死看的是实例不是能不能分配() {
        directory.entries.add(node(1, "inst-a", 21200).toBuilder().setAccepting(false).build());

        assertThat(nodes().lookup(1, "inst-a")).isEqualTo(Lookup.SAME_INSTANCE);
        assertThat(nodes().lookup(1, "inst-b")).isEqualTo(Lookup.OTHER_INSTANCE);
    }

    @Test
    void 读条目失败_异常完成_同步抛出_条目损坏_都是ERROR_不能证明任何事() {
        directory.scriptedFinds.put(1, CompletableFuture.failedFuture(new IllegalStateException("节点目录条目解析失败")));
        assertThat(nodes().lookup(1, "inst-a")).isEqualTo(Lookup.ERROR);

        // 条目与键不符、条目没有实例号
        directory.scriptedFinds.put(2, CompletableFuture.completedFuture(Optional.of(node(3, "inst-c", 21202))));
        directory.scriptedFinds.put(4, CompletableFuture.completedFuture(Optional.of(node(4, "", 21204))));
        assertThat(nodes().lookup(2, "inst-a")).isEqualTo(Lookup.ERROR);
        assertThat(nodes().lookup(4, "inst-a")).as("没有实例号的条目不能当成「换了实例」").isEqualTo(Lookup.ERROR);

        directory.findThrows = true;
        assertThat(nodes().lookup(1, "inst-a")).isEqualTo(Lookup.ERROR);
    }

    @Test
    void 读条目迟迟不回_一秒后按ERROR_不让补签为它耗掉整个请求预算() {
        directory.scriptedFinds.put(1, new CompletableFuture<>());

        long started = System.nanoTime();
        Lookup lookup = nodes().lookup(1, "inst-a");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(lookup).isEqualTo(Lookup.ERROR);
        assertThat(elapsedMs).isBetween(900L, 3_000L);
    }

    // ================================================================ 带截止的 lookup（6.5 的观众 RPC；lead 裁决 3）

    @Test
    void 带截止的lookup_截止充裕时结果与不带截止的相同() {
        directory.entries.add(node(1, "inst-a", 21200));

        assertThat(nodes().lookup(1, "inst-a", Deadline.after(5_000))).isEqualTo(Lookup.SAME_INSTANCE);
        assertThat(nodes().lookup(1, "inst-old", Deadline.after(5_000))).isEqualTo(Lookup.OTHER_INSTANCE);
        assertThat(nodes().lookup(9, "inst-a", Deadline.after(5_000))).isEqualTo(Lookup.ABSENT);
        directory.scriptedFinds.put(2, CompletableFuture.failedFuture(new IllegalStateException("节点目录条目解析失败")));
        assertThat(nodes().lookup(2, "inst-a", Deadline.after(5_000))).isEqualTo(Lookup.ERROR);
        directory.findThrows = true;
        assertThat(nodes().lookup(1, "inst-a", Deadline.after(5_000))).as("同步抛出也不漏出来").isEqualTo(Lookup.ERROR);
    }

    @Test
    void 带截止的lookup_截止已过_不读目录_直接ERROR() {
        directory.entries.add(node(1, "inst-successor", 21200));
        int[] finds = {0};
        RedisBattleNodes nodes = new RedisBattleNodes(new RedisBattleNodes.Directory() {
            @Override
            public List<BattleNodeInfo> list() {
                return directory.list();
            }

            @Override
            public CompletableFuture<Optional<BattleNodeInfo>> find(int nodeId) {
                finds[0]++;
                return directory.find(nodeId);
            }
        }, metrics, bound -> 0);

        assertThat(nodes.lookup(1, "inst-a", Deadline.after(0))).as("本来读得到「已换实例」，但没有时间了：不能证明任何事").isEqualTo(Lookup.ERROR);
        assertThat(finds[0]).as("截止已过就不发读").isZero();
        assertThat(nodes.lookup(1, "inst-a", Deadline.after(5_000))).isEqualTo(Lookup.OTHER_INSTANCE);
        assertThat(finds[0]).isEqualTo(1);
    }

    /**
     * 一次迟迟不回的读：记下调用方肯等它多久（纳秒），随即按超时收场。不真的等，「等多久」就不必靠墙钟的上下界去量——
     * 被测代码正是在 {@code Directory.find} 返回的这个对象上调带超时的 {@code get}。
     */
    private static final class NeverAnswering extends CompletableFuture<Optional<BattleNodeInfo>> {
        final List<Long> waitsNanos = new CopyOnWriteArrayList<>();

        @Override
        public Optional<BattleNodeInfo> get(long timeout, TimeUnit unit) throws TimeoutException {
            waitsNanos.add(unit.toNanos(timeout));
            throw new TimeoutException("测试：读条目迟迟不回");
        }
    }

    @Test
    void 带截止的lookup_读条目迟迟不回_只等到截止_按ERROR_不等满固定的一秒() {
        NeverAnswering read = new NeverAnswering();
        directory.scriptedFinds.put(1, read);

        // 截止 800 ms 早于固定上限 1 s
        Lookup lookup = nodes().lookup(1, "inst-a", Deadline.after(800));

        assertThat(lookup).isEqualTo(Lookup.ERROR);
        assertThat(read.waitsNanos).as("等这次读的上限 = min(固定上限 1 s, 截止的剩余)：取的是截止的剩余，不是固定的 1 s").singleElement()
                .satisfies(waitNanos -> assertThat(waitNanos).isBetween(1L, TimeUnit.MILLISECONDS.toNanos(800)));
    }

    @Test
    void 带截止的lookup_截止比固定上限长时_仍只等一秒() {
        NeverAnswering read = new NeverAnswering();
        directory.scriptedFinds.put(1, read);

        Lookup lookup = nodes().lookup(1, "inst-a", Deadline.after(30_000));

        assertThat(lookup).isEqualTo(Lookup.ERROR);
        assertThat(RedisBattleNodes.LOOKUP_WAIT_MS).isEqualTo(1_000);
        assertThat(read.waitsNanos).as("min(固定上限 1 s, 截止的剩余)：恰好是固定上限")
                .containsExactly(TimeUnit.MILLISECONDS.toNanos(RedisBattleNodes.LOOKUP_WAIT_MS));
    }
}
