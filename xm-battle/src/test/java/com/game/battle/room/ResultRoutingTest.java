package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.MONSTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.game.battle.port.BattleResultSink;
import com.game.battle.port.kafka.KafkaBattleResultSink;
import com.game.battle.room.RoomHarness.FakeLink;
import com.game.battle.testing.FakeBattleData;
import com.game.battle.testing.FakeResultKafka;
import com.game.battle.testing.ResultFallbackCapture;
import com.game.proto.BattleActivityContext;
import com.game.proto.CreateBattleRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 结算与结果事件的出站通道：普通局 / 活动局 / dev 房间（battle-node-spec §4.9、§7.9、§7.12）。末尾两条把结果端口换成 6.4 的真 Kafka 传输
 * （{@link KafkaBattleResultSink} + 假 Kafka）再走一遍：只有 match 的房间真正打完才有消息落到生产者，dev / dev gather 的房间、
 * 销毁与停机作废的房间一条都没有（match-spec §5.4、§12.1 坑 10：否则 dev 接口会变成刷分的口子）。
 */
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

    // ---------------------------------------------------------------- 6.4：结果端口接上真的 Kafka 传输

    /** 把房间的普通结果端口换成接着假 Kafka 的真传输（必须在第一次用房间服务之前）。 */
    private KafkaBattleResultSink kafkaSink(FakeResultKafka kafka) {
        KafkaBattleResultSink sink = new KafkaBattleResultSink(kafka, 1, (short) 1, Duration.ofSeconds(1), h.metrics);
        sink.start();
        h.results = sink;
        return sink;
    }

    @Test
    void 接上Kafka传输_dev与dev_gather房间打完生产者一条也收不到_match房间打完收到一条_key是battle_id() {
        FakeResultKafka kafka = new FakeResultKafka();
        try (ResultFallbackCapture fallback = ResultFallbackCapture.start(); KafkaBattleResultSink sink = kafkaSink(kafka)) {
            finish(12011, h.pve(12011, A), RoomOrigin.DEV);
            finish(12012, h.pve(12012, A), RoomOrigin.DEV_GATHER);
            finish(12013, h.pve(12013, A), RoomOrigin.MATCH);

            // 发送线程按交来的次序发：dev 房间若调了 sink，它们的消息会排在 12013 之前，下面的「恰好一条」就不成立
            await().atMost(Duration.ofSeconds(5)).until(() -> h.counter("xm.battle.result.events", "result", "sent") == 1);
            assertThat(kafka.sent()).singleElement().satisfies(message -> {
                assertThat(message.topic()).isEqualTo("xm-battle-result-g1");
                assertThat(message.key()).isEqualTo("12013");
                assertThat(message.event().getBattleId()).isEqualTo(12013);
                assertThat(message.event().getMatchMode()).isEqualTo(RoomHarness.PVE_SOLO);
                assertThat(message.event().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
                assertThat(message.event().hasActivityContext()).isFalse();
            });
            assertThat(kafka.sendThreads()).as("房间在逻辑线程上只投递，send 在专用线程上").containsExactly("battle-result-out");
            assertThat(h.counter("xm.battle.results", "channel", "plain", "result", "sent")).isEqualTo(1);
            assertThat(h.counter("xm.battle.results", "channel", "plain", "result", "error")).isZero();
            assertThat(h.counter("xm.battle.result.events", "result", "fallback")).isZero();
            assertThat(h.counter("xm.battle.result.events", "result", "not_verified")).isZero();
            assertThat(fallback.size()).as("dev 房间的结果也不进兜底日志（回灌兜底日志同样会刷分）").isZero();
            assertThat(sink.verified()).isTrue();
        }
    }

    @Test
    void 接上Kafka传输_销毁与停机作废的房间不发_活动局不走普通端口_随后打完的match房间是生产者收到的唯一一条() {
        FakeResultKafka kafka = new FakeResultKafka();
        try (ResultFallbackCapture fallback = ResultFallbackCapture.start(); KafkaBattleResultSink sink = kafkaSink(kafka)) {
            h.create(h.pve(12021, A));
            h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(12021).setReason("gather_rollback").build());
            h.create(h.pve(12022, A));
            h.service().abortAll("node_shutdown");
            assertThat(h.service().roomCount()).isZero();
            finish(12023, h.pve(12023, A).setActivityContext(BattleActivityContext.newBuilder()
                    .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(66).setActivityId(3)), RoomOrigin.MATCH);
            assertThat(h.outs(RoomHarness.Kind.ACTIVITY_RESULT)).as("活动局交给活动结果通道（它落了持久副本之后才经同一个端口按 activity 发）")
                    .hasSize(1);

            finish(12024, h.pve(12024, A), RoomOrigin.MATCH);

            await().atMost(Duration.ofSeconds(5)).until(() -> h.counter("xm.battle.result.events", "result", "sent") == 1);
            assertThat(kafka.sent()).singleElement().satisfies(message -> assertThat(message.key()).isEqualTo("12024"));
            assertThat(fallback.size()).isZero();
            assertThat(sink.topic()).isEqualTo("xm-battle-result-g1");
        }
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
