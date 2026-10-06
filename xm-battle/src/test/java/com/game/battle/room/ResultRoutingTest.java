package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.MONSTER;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.port.BattleResultSink;
import com.game.battle.room.RoomHarness.FakeLink;
import com.game.battle.testing.FakeBattleData;
import com.game.proto.BattleActivityContext;
import com.game.proto.CreateBattleRequest;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleTicketRole;
import org.junit.jupiter.api.Test;

/** 结算与结果事件的出站通道：普通局 / 活动局 / dev 房间（battle-node-spec §4.9、§7.9、§7.12）。 */
class ResultRoutingTest {

    private final RoomHarness h = new RoomHarness();

    /** 单人 PVE，一发必杀打完。 */
    private void finish(long battleId, CreateBattleRequest.Builder request, RoomOrigin origin) {
        assertThat(h.service().createBattle(request.build(), origin).hasErrorMessage()).isFalse();
        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER)).build());
        assertThat(h.service().room(battleId)).as("房间应当已打完").isNull();
    }

    @Test
    void 普通局只发一次普通结果() {
        finish(12001, h.pve(12001, A), RoomOrigin.MATCH);

        assertThat(h.outs(RoomHarness.Kind.RESULT)).hasSize(1);
        assertThat(h.outs(RoomHarness.Kind.ACTIVITY_RESULT)).isEmpty();
        assertThat(h.resultsOut().get(0).hasActivityContext()).isFalse();
        assertThat(h.resultChannels).as("房间只走普通通道；活动通道的发布由活动结果发件箱负责").containsExactly(BattleResultSink.Channel.PLAIN);
    }

    @Test
    void 活动局走活动通道并回显上下文() {
        BattleActivityContext trial = BattleActivityContext.newBuilder()
                .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(66).setActivityId(3).build();

        finish(12002, h.pve(12002, A).setActivityContext(trial), RoomOrigin.MATCH);

        assertThat(h.outs(RoomHarness.Kind.RESULT)).isEmpty();
        BattleResultEvent event = (BattleResultEvent) h.outs(RoomHarness.Kind.ACTIVITY_RESULT).get(0).payload();
        assertThat(event.getActivityContext()).isEqualTo(trial);
    }

    @Test
    void 不认识的kind也按活动局处理() {
        finish(12003, h.pve(12003, A).setActivityContext(BattleActivityContext.newBuilder().setKindValue(7)), RoomOrigin.MATCH);

        assertThat(h.outs(RoomHarness.Kind.ACTIVITY_RESULT)).hasSize(1);
        assertThat(h.outs(RoomHarness.Kind.RESULT)).isEmpty();
    }

    @Test
    void dev房间照常推150_但永不投递结算与结果事件() {
        assertThat(h.service().createBattle(h.pve(12004, A).build(), RoomOrigin.DEV).hasErrorMessage()).isFalse();
        FakeLink a = h.connect(12004, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        h.scheduler.advance(10_000);
        assertThat(h.outs(RoomHarness.Kind.CONFIRM)).as("dev 房间照常补发确认").hasSize(2);

        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(12004)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER)).build());

        assertThat(a.messageIds()).containsSubsequence(139, 150);
        assertThat(h.settlementsOut()).isEmpty();
        assertThat(h.outs(RoomHarness.Kind.RESULT)).isEmpty();
        assertThat(h.outs(RoomHarness.Kind.ACTIVITY_RESULT)).isEmpty();
        assertThat(h.counter("xm.battle.scene.events", "kind", "settlement", "result", "skipped")).isEqualTo(1);
    }

    @Test
    void dev_gather房间照常确认照常结算_但不投递结果事件() {
        assertThat(h.service().createBattle(h.pve(12005, A).build(), RoomOrigin.DEV_GATHER).hasErrorMessage()).isFalse();
        h.connect(12005, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        h.scheduler.advance(10_000);
        assertThat(h.outs(RoomHarness.Kind.CONFIRM)).as("dev gather 房间照常补发确认").hasSize(2);

        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(12005)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER)).build());

        assertThat(h.settlementsOut()).as("快照来自 scene，照常结算（scene-battle-spec §7.18）").hasSize(1);
        assertThat(h.settlementsOut().get(0).getPlayerId()).isEqualTo(A);
        assertThat(h.outs(RoomHarness.Kind.RESULT)).as("没有 match，不投递对局结果").isEmpty();
        assertThat(h.outs(RoomHarness.Kind.ACTIVITY_RESULT)).isEmpty();
    }

    @Test
    void 来源的投递判定() {
        assertThat(RoomOrigin.MATCH.settles()).isTrue();
        assertThat(RoomOrigin.MATCH.publishesResult()).isTrue();
        assertThat(RoomOrigin.DEV.settles()).isFalse();
        assertThat(RoomOrigin.DEV.publishesResult()).isFalse();
        assertThat(RoomOrigin.DEV_GATHER.settles()).isTrue();
        assertThat(RoomOrigin.DEV_GATHER.publishesResult()).isFalse();
    }
}
