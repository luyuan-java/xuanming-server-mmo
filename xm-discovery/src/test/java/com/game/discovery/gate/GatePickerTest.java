package com.game.discovery.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import java.util.List;
import java.util.Optional;
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

    private static int pickedNode(List<GateNodeInfo> gates) {
        return GatePicker.pick(ZONE, gates).orElseThrow().getNodeId();
    }

    @Test
    void 取在线人数最少的gate() {
        assertThat(pickedNode(List.of(gate(1, 30, false), gate(2, 10, false), gate(3, 20, false)))).isEqualTo(2);
    }

    @Test
    void 人数相同取节点号小者_与输入顺序无关() {
        assertThat(pickedNode(List.of(gate(9, 5, false), gate(4, 5, false), gate(7, 5, false)))).isEqualTo(4);
        assertThat(pickedNode(List.of(gate(4, 5, false), gate(7, 5, false), gate(9, 5, false)))).isEqualTo(4);
    }

    @Test
    void 排空中的gate不参与分配_即使人数更少() {
        assertThat(pickedNode(List.of(gate(1, 0, true), gate(2, 50, false)))).isEqualTo(2);
    }

    @Test
    void 全部排空时忽略排空标记() {
        assertThat(pickedNode(List.of(gate(3, 9, true), gate(2, 4, true), gate(1, 4, true)))).isEqualTo(1);
    }

    @Test
    void 人数按无符号比较() {
        // uint32 的 0x80000000 在 Java int 里是负数，有符号比较会误判它最少。
        GateNodeInfo huge = gate(1, Integer.MIN_VALUE, false);
        assertThat(pickedNode(List.of(huge, gate(2, 5, false)))).isEqualTo(2);
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
    void 同地址启动时刻并列_都留下按人数与节点号挑() {
        GateNodeInfo a = sameAddress(gate(1, 0, false), 5, 7, 0L);
        GateNodeInfo b = sameAddress(a, 3, 7, 0L);
        GateNodeInfo c = sameAddress(a, 4, 2, 0L);

        assertThat(GatePicker.eligible(ZONE, List.of(a, b, c))).containsExactly(a, b, c);
        assertThat(pickedNode(List.of(a, b, c))).isEqualTo(4);
        assertThat(pickedNode(List.of(a, b))).isEqualTo(3);
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
        assertThat(pickedNode(List.of(one, otherPort, otherHost))).isEqualTo(2);
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
