package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.MONSTER;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.testing.FakeBattleData;
import com.game.proto.SubmitBattleActionRequest;
import org.junit.jupiter.api.Test;

/** 确认事件首发 + 17 次补发（基线 {@code room.cpp:1107-1128}；计次实现 §11 N19；battle-node-spec §4.8、§13.2）。 */
class ConfirmResendTest {

    private final RoomHarness h = new RoomHarness();

    private long confirms(long playerId) {
        return h.outs(RoomHarness.Kind.CONFIRM).stream().filter(o -> o.playerId() == playerId).count();
    }

    @Test
    void 每人18条_t0首发_10到170秒补17次_180秒停表_每条带房间期限() {
        h.create(h.pveTeam(4001, A, B).setBattleConfigId(RoomHarness.DUNGEON_TANK));
        long deadline = h.service().room(4001).deadlineMs;
        assertThat(confirms(A)).isEqualTo(1);

        h.scheduler.advance(9_999);
        assertThat(confirms(A)).isEqualTo(1);
        h.scheduler.advance(1);
        assertThat(confirms(A)).isEqualTo(2);
        h.scheduler.advanceTo(RoomHarness.T0 + 170_000);
        assertThat(confirms(A)).isEqualTo(18);
        assertThat(confirms(B)).isEqualTo(18);
        h.scheduler.advanceTo(RoomHarness.T0 + 299_999);

        assertThat(h.service().room(4001)).as("房间还在打").isNotNull();
        assertThat(confirms(A)).isEqualTo(18);
        assertThat(confirms(B)).isEqualTo(18);
        assertThat(h.outs(RoomHarness.Kind.CONFIRM)).allSatisfy(o -> assertThat(o.payload()).isEqualTo(deadline));
    }

    @Test
    void 战斗在35秒结束后不再补发() {
        h.create(h.pve(4002, A).setBattleConfigId(0));
        h.scheduler.advanceTo(RoomHarness.T0 + 35_000);
        assertThat(confirms(A)).isEqualTo(4);

        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(4002)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER)).build());
        assertThat(h.service().roomCount()).isZero();
        h.scheduler.advanceTo(RoomHarness.T0 + 400_000);

        assertThat(confirms(A)).isEqualTo(4);
    }
}
