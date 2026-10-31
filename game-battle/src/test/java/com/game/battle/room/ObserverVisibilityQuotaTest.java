package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.D;
import static com.game.battle.room.RoomHarness.INSTANCE;
import static com.game.battle.room.RoomHarness.observerRouting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.game.proto.AddObserverRequest;
import com.game.proto.ObserverAdmissionState;
import com.game.proto.QueryObserverRequest;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.eSpectateVisibility;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * AddObserver 的可见性第二道闸与观众席节点配额（mmorpg battle-spectate-tiered-fanout.md §10 第 4、6 条）。原件由测试显式构造
 * （同 {@link ObserverOperationRoomTest}），不在被测服务里补齐。
 */
class ObserverVisibilityQuotaTest {

    private final RoomHarness h = new RoomHarness();

    private AddObserverRequest original(long battle, long player) {
        return AddObserverRequest.newBuilder().setBattleId(battle).setObserverPlayerId(player).setObserverName("观众" + player)
                .setRouting(observerRouting(player, 900)).setSpectatorOperationId(UUID.randomUUID().toString())
                .setAdmitBeforeMs(h.now() + 25_000).setTargetInstanceId(INSTANCE).build();
    }

    private static RemoveObserverRequest remove(AddObserverRequest add) {
        return RemoveObserverRequest.newBuilder().setBattleId(add.getBattleId()).setObserverPlayerId(add.getObserverPlayerId())
                .setSpectatorOperationId(add.getSpectatorOperationId()).setAdmitBeforeMs(add.getAdmitBeforeMs())
                .setTargetInstanceId(add.getTargetInstanceId()).setReason("rewatch").build();
    }

    private static QueryObserverRequest query(AddObserverRequest add) {
        return QueryObserverRequest.newBuilder().setBattleId(add.getBattleId()).setObserverPlayerId(add.getObserverPlayerId())
                .setSpectatorOperationId(add.getSpectatorOperationId()).setTargetInstanceId(add.getTargetInstanceId()).build();
    }

    // ================================================================ 可见性第二道闸

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = eSpectateVisibility.class, names = {"SPECTATE_VISIBILITY_UNSPECIFIED", "SPECTATE_VISIBILITY_PRIVATE"})
    void 非公开房间_AddObserver回参数错误而不是1004_不登记不签票_Query为ABSENT_同op重放仍拒(eSpectateVisibility visibility) {
        h.create(h.pvp(9931, A, B).setSpectateVisibility(visibility));
        AddObserverRequest add = original(9931, C);
        h.clearLog();

        int tip = h.service().addObserver(add).getErrorMessage().getId();

        assertThat(tip).as("参数错误；1004 是 match 懒剔除可观战索引的信号，不能借用").isEqualTo(BattleRoomServiceImpl.INVALID_PARAMETER)
                .isNotEqualTo(BattleRoomServiceImpl.ENTITY_IS_NULL);
        assertThat(h.service().room(9931).routingByObserver).isEmpty();
        assertThat(h.service().room(9931).observerOperations).isEmpty();
        assertThat(h.trace()).as("不签票、不推 177").isEmpty();
        assertThat(h.service().queryObserver(query(add)).getState()).isEqualTo(ObserverAdmissionState.OBSERVER_ADMISSION_ABSENT);
        assertThat(h.service().addObserver(add).hasErrorMessage()).as("拒绝即终态：墓碑保留，同 op 迟到的 Add 也翻不成准入").isTrue();
        assertThat(h.service().addObserver(original(9931, C)).getErrorMessage().getId()).as("换新 op 也一样：可见性随建房原件冻结")
                .isEqualTo(BattleRoomServiceImpl.INVALID_PARAMETER);
        assertThat(h.service().removeObserver(remove(add)).getCancelled()).as("match 补发的原 Remove 照样拿到取消正证明").isTrue();
        assertThat(h.service().room(9931).routingByObserver).isEmpty();
    }

    @Test
    void 公开房间照常准入_同一建房夹具只差可见性() {
        h.create(h.pvp(9932, A, B));

        assertThat(h.service().addObserver(original(9932, C)).hasErrorMessage()).isFalse();
        assertThat(h.service().room(9932).isObserver(C)).isTrue();
    }

    @Test
    void 第二道闸排在参战者与参数校验之后_参战者仍回参数错误_房间不在仍回1004() {
        h.create(h.pvp(9933, A, B).setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE));

        assertThat(h.service().addObserver(original(404, C)).getErrorMessage().getId()).isEqualTo(BattleRoomServiceImpl.ENTITY_IS_NULL);
        assertThat(h.service().addObserver(original(9933, A)).getErrorMessage().getId()).isEqualTo(BattleRoomServiceImpl.INVALID_PARAMETER);
    }

    // ================================================================ 观众席节点配额

    @Test
    void 节点配额_跨房间按已登记观众席求和_含没建直连的_到额回满员码_回收一席后新操作可进() {
        h.observerSeatQuota = 3;
        h.create(h.pvp(9941, A, B));
        h.create(h.pve(9942, D));
        AddObserverRequest first = original(9941, 6001);
        assertThat(h.service().addObserver(first).hasErrorMessage()).isFalse();
        assertThat(h.service().addObserver(original(9941, 6002)).hasErrorMessage()).isFalse();
        assertThat(h.service().addObserver(original(9942, 6003)).hasErrorMessage()).as("另一间房：仍在节点配额内").isFalse();

        AddObserverRequest fourth = original(9942, 6004);
        assertThat(h.service().addObserver(fourth).getErrorMessage().getId()).as("每房远没满 20，节点已满：与房间满员同一个专用码")
                .isEqualTo(BattleRoomServiceImpl.SPECTATE_FULL);
        assertThat(h.service().room(9942).isObserver(6004)).isFalse();

        assertThat(h.service().removeObserver(remove(first)).getCancelled()).isTrue();
        assertThat(h.service().addObserver(fourth).hasErrorMessage()).as("拒绝即终态：同 op 有空位之后也不准入").isTrue();
        AddObserverRequest fresh = original(9942, 6004);
        assertThat(h.service().addObserver(fresh).hasErrorMessage()).as("回收一席后新操作照常准入").isFalse();
        assertThat(h.service().room(9942).isObserver(6004)).isTrue();
    }

    @Test
    void 节点配额只封新观众_已登记观众的幂等重试不受影响() {
        h.observerSeatQuota = 1;
        h.create(h.pvp(9943, A, B));
        AddObserverRequest only = original(9943, C);
        assertThat(h.service().addObserver(only).hasErrorMessage()).isFalse();

        assertThat(h.service().addObserver(only).hasErrorMessage()).as("幂等路径排在配额之前").isFalse();
        assertThat(h.service().addObserver(original(9943, 6005)).getErrorMessage().getId()).isEqualTo(BattleRoomServiceImpl.SPECTATE_FULL);
    }

    @Test
    void 生产口径的节点配额是连接上限的四分之一_不低于每房上限() {
        assertThat(RoomDependencies.observerSeatQuotaFor(4096)).isEqualTo(1024);
        assertThat(RoomDependencies.observerSeatQuotaFor(65535)).isEqualTo(16383);
        assertThat(RoomDependencies.observerSeatQuotaFor(40)).as("连接上限很小时也至少容得下一间满员房").isEqualTo(RoomConstants.MAX_OBSERVERS_PER_ROOM);
        assertThatIllegalArgumentException().isThrownBy(() -> RoomDependencies.observerSeatQuotaFor(0));
    }
}
