package com.game.discovery.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class GatePickerTest {

    private static final int ZONE = 1;

    static GateNodeInfo gate(int nodeId, int playerCount, boolean draining) {
        return GateNodeInfo.newBuilder()
                .setZoneId(ZONE)
                .setNodeId(nodeId)
                .setInstanceId("inst-" + nodeId)
                .setClientHost("10.0.0." + nodeId)
                .setClientPort(11000 + nodeId)
                .setPlayerCount(playerCount)
                .setDraining(draining)
                .build();
    }

    /** 按给定序列依次返回随机数，把随机二选一的两次抽样钉死；序列用完或越界即抛错（测试失败）。 */
    private static IntUnaryOperator draws(int... seq) {
        int[] next = {0};
        return bound -> {
            if (next[0] >= seq.length) {
                throw new AssertionError("随机源被调用的次数多于给定序列 " + seq.length);
            }
            int v = seq[next[0]++];
            if (v < 0 || v >= bound) {
                throw new AssertionError("序列值 " + v + " 超出 [0," + bound + ")");
            }
            return v;
        };
    }

    /** 候选只剩一台时不会抽样，不传序列即可；两台及以上必须给出两次抽样。 */
    private static int pickedNode(List<GateNodeInfo> gates, int... seq) {
        return GatePicker.pick(ZONE, gates, draws(seq)).orElseThrow().getNodeId();
    }

    // ---------- 随机二选一（2026-10-18，对齐 mmorpg shared/gatepick.TwoChoices） ----------

    @Test
    void 随机抽两台取人数少的_第二次抽样跳过第一台() {
        List<GateNodeInfo> gates = List.of(gate(1, 30, false), gate(2, 10, false), gate(3, 20, false));
        assertThat(pickedNode(gates, 0, 0)).as("i=0(30)、j=0 顺延到 1(10)").isEqualTo(2);
        assertThat(pickedNode(gates, 0, 1)).as("i=0(30)、j=1 顺延到 2(20)").isEqualTo(3);
        assertThat(pickedNode(gates, 1, 1)).as("i=1(10)、j=1 顺延到 2(20)").isEqualTo(2);
    }

    @Test
    void 严格最忙的那台在任何抽样组合下都选不中() {
        List<GateNodeInfo> gates = List.of(gate(1, 10, false), gate(2, 500, false), gate(3, 20, false), gate(4, 30, false));
        for (int i = 0; i < gates.size(); i++) {
            for (int j = 0; j < gates.size() - 1; j++) {
                assertThat(pickedNode(gates, i, j)).as("抽样 (%d,%d)", i, j).isNotEqualTo(2);
            }
        }
    }

    @Test
    void 同人数取第一次抽中的_不按节点号_全体均匀() {
        // 开新区第一个发布周期里所有 gate 都报 0：按节点号定胜负会让节点号最大的那台一个都分不到。
        List<GateNodeInfo> gates = List.of(gate(9, 5, false), gate(4, 5, false), gate(7, 5, false));
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < gates.size(); i++) {
            for (int j = 0; j < gates.size() - 1; j++) {
                counts.merge(pickedNode(gates, i, j), 1, Integer::sum);
            }
        }
        assertThat(counts).containsEntry(9, 2).containsEntry(4, 2).containsEntry(7, 2);
    }

    @Test
    void 两台时无论怎么抽都是人数少的那台() {
        List<GateNodeInfo> gates = List.of(gate(8, 7, false), gate(3, 2, false));
        assertThat(pickedNode(gates, 0, 0)).isEqualTo(3);
        assertThat(pickedNode(gates, 1, 0)).isEqualTo(3);
    }

    @Test
    void 滞后快照上连挑多次会摊开_最闲那台约占五分之一() {
        // 10 台、只有 1 号人少：旧规则会全部落到 1 号；随机二选一下 1 号只在被抽中时才赢，概率 1 - C(9,2)/C(10,2) = 20%。
        // 随机源用固定种子，结果确定、可重复。
        GateNodeInfo[] arr = new GateNodeInfo[10];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = gate(i + 1, i == 0 ? 0 : 100, false);
        }
        List<GateNodeInfo> gates = List.of(arr);
        SplittableRandom rng = new SplittableRandom(20261018L);
        Map<Integer, Integer> counts = new HashMap<>();
        int picks = 2000;
        for (int k = 0; k < picks; k++) {
            int node = GatePicker.pick(ZONE, gates, rng::nextInt).orElseThrow().getNodeId();
            counts.merge(node, 1, Integer::sum);
        }
        double share = counts.getOrDefault(1, 0) / (double) picks;
        assertThat(share).isBetween(0.15, 0.25);
        assertThat(counts).hasSize(10);
    }

    @Test
    void 默认随机源只在候选里挑() {
        List<GateNodeInfo> gates = List.of(gate(1, 30, false), gate(2, 10, false), gate(3, 20, false));
        for (int k = 0; k < 50; k++) {
            assertThat(GatePicker.pick(ZONE, gates).orElseThrow().getNodeId()).isIn(2, 3);
        }
    }

    // ---------- 候选过滤（规则 1–3） ----------

    @Test
    void 排空中的gate不参与分配_即使人数更少() {
        assertThat(pickedNode(List.of(gate(1, 0, true), gate(2, 50, false)))).isEqualTo(2);
    }

    @Test
    void 全部排空时忽略排空标记() {
        List<GateNodeInfo> gates = List.of(gate(3, 9, true), gate(2, 4, true), gate(1, 4, true));
        assertThat(pickedNode(gates, 0, 0)).as("三台都还是候选：i=0(9)、j=1(4)").isEqualTo(2);
        assertThat(pickedNode(gates, 0, 1)).as("i=0(9)、j=2(4)").isEqualTo(1);
    }

    @Test
    void 人数按无符号比较() {
        // uint32 的 0x80000000 在 Java int 里是负数，有符号比较会误判它最少。
        GateNodeInfo huge = gate(1, Integer.MIN_VALUE, false);
        assertThat(pickedNode(List.of(huge, gate(2, 5, false)), 0, 0)).isEqualTo(2);
        assertThat(pickedNode(List.of(huge, gate(2, 5, false)), 1, 0)).isEqualTo(2);
    }

    @Test
    void 跳过连不上的条目() {
        GateNodeInfo wrongZone = gate(1, 0, false).toBuilder().setZoneId(2).build();
        GateNodeInfo noHost = gate(2, 0, false).toBuilder().setClientHost("").build();
        GateNodeInfo blankHost = gate(3, 0, false).toBuilder().setClientHost("  ").build();
        GateNodeInfo noPort = gate(4, 0, false).toBuilder().setClientPort(0).build();
        GateNodeInfo badPort = gate(5, 0, false).toBuilder().setClientPort(70000).build();
        GateNodeInfo zeroNode = gate(6, 0, false).toBuilder().setNodeId(0).build();
        GateNodeInfo ok = gate(7, 100, false);

        assertThat(pickedNode(List.of(wrongZone, noHost, blankHost, noPort, badPort, zeroNode, ok))).isEqualTo(7);
        assertThat(GatePicker.pick(ZONE, List.of(wrongZone, noHost, noPort, zeroNode))).isEmpty();
    }

    @Test
    void 连不上的条目不算作_未排空的候选() {
        // 唯一未排空的条目没有地址：应退回到「全部排空」规则，而不是返回空。
        GateNodeInfo noHost = gate(1, 0, false).toBuilder().setClientHost("").build();
        assertThat(pickedNode(List.of(noHost, gate(2, 8, true)))).isEqualTo(2);
    }

    @Test
    void 空目录返回空() {
        assertThat(GatePicker.pick(ZONE, List.of())).isEqualTo(Optional.empty());
    }

    // ---------- 同一客户端地址只留最新启动的一条（批次 5.4，zone-travel-spec §5.4 第 4 步、Q10） ----------

    /** 与 {@code base} 同一客户端地址、换了节点号的条目（gate 在同一地址重启后的新进程，或重启前的旧进程）。 */
    private static GateNodeInfo sameAddress(GateNodeInfo base, int nodeId, int playerCount, long startedAtMs) {
        return base.toBuilder().setNodeId(nodeId).setInstanceId("inst-" + nodeId).setPlayerCount(playerCount)
                .setStartedAtMs(startedAtMs).build();
    }

    /**
     * 对照基线 {@code NeverPicksStaleRecordOfCrashedGate}（gate_redirect_client_endpoint_test.go:182）：gate 崩溃后在同一地址重启、
     * 换了节点号，旧条目还没过期、人数停在崩溃时更少——也不选它（签给旧节点号的令牌在新进程上验不过）。
     */
    @Test
    void 同一客户端地址两条_只留启动时刻大者_人数更少的陈旧条目也不选_与输入顺序无关() {
        GateNodeInfo stale = sameAddress(gate(1, 0, false), 1, 0, 1_000L);
        GateNodeInfo fresh = sameAddress(stale, 2, 50, 2_000L);

        assertThat(pickedNode(List.of(stale, fresh))).isEqualTo(2);
        assertThat(pickedNode(List.of(fresh, stale))).isEqualTo(2);
        assertThat(GatePicker.eligible(ZONE, List.of(stale, fresh))).as("放行循环用的候选表同样去重").containsExactly(fresh);
    }

    @Test
    void 缺启动时刻的旧版本条目当0_同地址有新条目时被去掉() {
        GateNodeInfo legacy = gate(1, 0, false); // 不认识 started_at_ms 的旧版本 gate 写的条目
        GateNodeInfo fresh = sameAddress(legacy, 2, 9, 1_700_000_000_000L);

        assertThat(pickedNode(List.of(legacy, fresh))).isEqualTo(2);
    }

    @Test
    void 同地址启动时刻并列_都留下交给随机二选一() {
        GateNodeInfo a = sameAddress(gate(1, 0, false), 5, 7, 0L);
        GateNodeInfo b = sameAddress(a, 3, 7, 0L);
        GateNodeInfo c = sameAddress(a, 4, 2, 0L);

        assertThat(GatePicker.eligible(ZONE, List.of(a, b, c))).containsExactly(a, b, c);
        assertThat(pickedNode(List.of(a, b, c), 0, 1)).as("i=0(7)、j=1 顺延到 2(2)").isEqualTo(4);
        assertThat(pickedNode(List.of(a, b, c), 0, 0)).as("i=0(7)、j=1(7)：同人数取第一次抽中的").isEqualTo(5);
        assertThat(pickedNode(List.of(a, b), 1, 0)).as("i=1(7)、j=0(7)：同人数取第一次抽中的").isEqualTo(3);
    }

    @Test
    void 不同地址的条目互不影响_同主机不同端口不算同地址() {
        GateNodeInfo one = gate(1, 30, false).toBuilder().setClientHost("10.0.0.9").setClientPort(11000)
                .setStartedAtMs(1_000L).build();
        GateNodeInfo otherPort = gate(2, 10, false).toBuilder().setClientHost("10.0.0.9").setClientPort(11001)
                .setStartedAtMs(500L).build();
        GateNodeInfo otherHost = gate(3, 20, false).toBuilder().setClientHost("10.0.0.10").setClientPort(11000)
                .setStartedAtMs(100L).build();

        assertThat(GatePicker.eligible(ZONE, List.of(one, otherPort, otherHost))).containsExactly(one, otherPort, otherHost);
        assertThat(pickedNode(List.of(one, otherPort, otherHost), 0, 0)).isEqualTo(2);
    }

    @Test
    void 去重先于排空_陈旧条目不会因为最新那条在排空而复活() {
        GateNodeInfo stale = sameAddress(gate(1, 0, false), 1, 0, 1_000L);
        GateNodeInfo drainingFresh = sameAddress(stale, 2, 40, 2_000L).toBuilder().setDraining(true).build();
        GateNodeInfo other = gate(3, 90, false);

        assertThat(pickedNode(List.of(stale, drainingFresh, other))).as("剔排空之后只剩另一台").isEqualTo(3);
        assertThat(pickedNode(List.of(stale, drainingFresh)))
                .as("只剩最新那条（在排空）：走「全部排空时忽略标记」，仍不选陈旧条目").isEqualTo(2);
    }

    @Test
    void 连不上的条目不参与去重_不会挤掉同地址的可用条目() {
        GateNodeInfo usable = sameAddress(gate(1, 0, false), 1, 5, 1_000L);
        GateNodeInfo wrongZoneNewer = sameAddress(usable, 2, 0, 9_000L).toBuilder().setZoneId(ZONE + 1).build();
        GateNodeInfo zeroNodeNewer = sameAddress(usable, 0, 0, 9_000L);

        assertThat(pickedNode(List.of(usable, wrongZoneNewer, zeroNodeNewer))).isEqualTo(1);
    }

    @Test
    void 启动时刻按无符号比较() {
        // uint64 的高位为 1 的值在 Java long 里是负数，有符号比较会把它当成最旧。
        GateNodeInfo small = sameAddress(gate(1, 0, false), 1, 0, 5L);
        GateNodeInfo huge = sameAddress(small, 2, 9, Long.MIN_VALUE);

        assertThat(pickedNode(List.of(small, huge))).isEqualTo(2);
    }
}
