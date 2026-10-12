package com.game.discovery.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.proto.PlayerPresence;
import com.game.proto.BattleRouting;
import org.junit.jupiter.api.Test;

/**
 * 在线目录条目 → 观众路由（spectate-spec §10.2；对照基线 {@code spectate_test.go:294} TestWatchBattleBindsObserverWithGateOnlyRouting）：
 * 四个 gate 字段原样取自在线目录，scene 两个字段恒为 0 / 空。
 */
class BattleRoutingsTest {

    private static PlayerPresence presence() {
        return PlayerPresence.newBuilder().setPlayerId(1001).setZoneId(2).setGateNodeId(1).setGateInstanceId("gate-z2-inst")
                .setSessionId(0x0100_0007).setOnlineSinceMs(1_800_000_000_000L).setOwnerEpoch(9).build();
    }

    @Test
    void 会话_gate节点_gate实例_zone四个字段都取自在线目录() {
        BattleRouting routing = BattleRoutings.gatePart(presence());

        assertThat(routing.getSessionId()).isEqualTo(0x0100_0007);
        assertThat(routing.getGateNodeId()).isEqualTo(1);
        assertThat(routing.getGateInstanceId()).isEqualTo("gate-z2-inst");
        assertThat(routing.getZoneId()).as("zone 取在线目录的（玩家挂在哪个 zone 的 gate 上），两个 zone 的 1 号 gate 靠它分开").isEqualTo(2);
    }

    @Test
    void scene两个字段恒为0和空_观众没有scene侧的状态() {
        BattleRouting routing = BattleRoutings.gatePart(presence());

        assertThat(routing.getSceneNodeId()).isZero();
        assertThat(routing.getSceneInstanceId()).isEmpty();
        assertThat(routing).as("除四个 gate 字段外什么都不填").isEqualTo(BattleRouting.newBuilder().setSessionId(0x0100_0007).setGateNodeId(1)
                .setGateInstanceId("gate-z2-inst").setZoneId(2).build());
    }

    @Test
    void 无符号的大值原样保留_不做任何换算() {
        PlayerPresence big = presence().toBuilder().setSessionId(-1).setGateNodeId(-2).setZoneId(-3).build();

        BattleRouting routing = BattleRoutings.gatePart(big);

        assertThat(Integer.toUnsignedString(routing.getSessionId())).isEqualTo("4294967295");
        assertThat(Integer.toUnsignedString(routing.getGateNodeId())).isEqualTo("4294967294");
        assertThat(Integer.toUnsignedString(routing.getZoneId())).isEqualTo("4294967293");
    }

    @Test
    void 条目缺gate实例时照样换算_可不可用由调用方判() {
        BattleRouting routing = BattleRoutings.gatePart(presence().toBuilder().clearGateInstanceId().build());

        assertThat(routing.getGateInstanceId()).as("163 据此回 16004、dev 管理口回 422；这里不抛").isEmpty();
        assertThat(routing.getGateNodeId()).isEqualTo(1);
    }

    @Test
    void 同一个条目换算两次结果相等_空条目是调用方的错() {
        assertThat(BattleRoutings.gatePart(presence())).isEqualTo(BattleRoutings.gatePart(presence()));
        assertThatThrownBy(() -> BattleRoutings.gatePart(null)).isInstanceOf(NullPointerException.class);
    }
}
