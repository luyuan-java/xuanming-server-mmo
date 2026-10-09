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
}
