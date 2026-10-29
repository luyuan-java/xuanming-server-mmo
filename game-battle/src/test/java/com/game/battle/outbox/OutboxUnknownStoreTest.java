package com.game.battle.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.port.BattleResultSink;
import com.game.battle.testing.CallJournal;
import com.game.battle.testing.FakeActivityStore;
import com.game.battle.testing.FakeSceneLocator;
import com.game.battle.testing.FakeSettlementStore;
import com.game.battle.testing.ManualBattleScheduler;
import com.game.battle.testing.RecordingSceneTransport;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleSettlementEvent;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** 写入已执行但丢回包、原件证据不足、同 ID 重入的故障边界；不把内存模型当作 Redis 脚本证据。 */
class OutboxUnknownStoreTest {

    private static final long PLAYER = 91;
    private static final long BATTLE = 802;
    private static final long ROUND = BattleRedis.SETTLEMENT_RETRY_INTERVAL.toMillis();
    private static final BattleRouting ROUTING = BattleRouting.newBuilder().setSceneNodeId(9).build();

    private final ManualBattleScheduler scheduler = new ManualBattleScheduler(1_800_000_000_000L);
    private final CallJournal journal = new CallJournal();
    private final FakeSettlementStore settlements = new FakeSettlementStore(journal);
    private final FakeActivityStore activities = new FakeActivityStore(journal);
    private final FakeSceneLocator locator = new FakeSceneLocator(journal);
    private final RecordingSceneTransport transport = new RecordingSceneTransport(journal);
    private final List<BattleResultEvent> published = new ArrayList<>();
    private boolean failNextPublish;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry);
    private final SettlementOutbox settlementOutbox = new SettlementOutbox(scheduler, settlements, locator::locate,
            transport::applySettlement, metrics, scheduler::nowMs);
    private final ActivityResultOutbox activityOutbox = new ActivityResultOutbox(scheduler, activities, (event, channel) -> {
        assertThat(channel).isEqualTo(BattleResultSink.Channel.ACTIVITY);
        published.add(event);
        if (failNextPublish) {
            failNextPublish = false;
            throw new IllegalStateException("首次发布失败");
        }
    }, metrics, scheduler::nowMs);

    private static BattleSettlementData settlement() {
        return BattleSettlementData.newBuilder().setPlayerId(PLAYER).setBattleId(BATTLE).setGoldGain(100).build();
    }

    private static byte[] settlementBytes() {
        return BattleSettlementEvent.newBuilder().setSettlement(settlement()).build().toByteArray();
    }

    private static BattleResultEvent activity() {
        return BattleResultEvent.newBuilder().setBattleId(BATTLE).setTotalRounds(4).build();
    }

    private void dispatchSettlement() {
        settlementOutbox.dispatch(ROUTING, PLAYER, settlement());
        scheduler.runPending();
    }

    private void dispatchActivity() {
        activityOutbox.dispatch(activity());
        scheduler.runPending();
    }

    private double settlementCount(String event) {
        return registry.get(OutboxMetrics.SETTLEMENT_OUTBOX).tag("event", event).counter().count();
    }

    private double activityCount(String event) {
        return registry.get(OutboxMetrics.ACTIVITY_OUTBOX).tag("event", event).counter().count();
    }

    private void unknownSettlementWithOriginal() {
        locator.online(PLAYER, 1, 9, "scene-9");
        settlements.stores.hold();
        dispatchSettlement();
        settlements.applyStore(PLAYER, BATTLE, settlementBytes());
        settlements.stores.last().fail(new TimeoutException("写入已执行，回包丢失"));
        scheduler.runPending();
    }

    private void unknownActivityWithOriginal() {
        activities.stores.hold();
        dispatchActivity();
        activities.applyStore(BATTLE, activity().toByteArray());
        activities.stores.last().fail(new TimeoutException("写入已执行，回包丢失"));
        scheduler.runPending();
    }

    @Test
    void 两种store已执行丢回包_核对原字节后恢复重投_不重新写入() {
        transport.replies.failWith(new TimeoutException("首投传输丢失"));
        failNextPublish = true;
        unknownSettlementWithOriginal();
        unknownActivityWithOriginal();

        assertThat(transport.sent).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(settlementOutbox.inFlight()).isZero();
        assertThat(registry.get(OutboxMetrics.SETTLEMENT_DELIVERY).tag("result", "transport_error").counter().count())
                .isEqualTo(1);
        transport.replies.auto();
        scheduler.advance(ROUND * 2);

        assertThat(transport.sent).hasSize(3).allSatisfy(sent ->
                assertThat(sent.call().getBody()).isEqualTo(ByteString.copyFrom(settlementBytes())));
        assertThat(transport.sent).extracting(sent -> sent.call().getAttempt()).containsExactly(0, 1, 2);
        assertThat(published).hasSize(3).allSatisfy(event -> assertThat(event.toByteArray()).isEqualTo(activity().toByteArray()));
        assertThat(journal.count("store:")).isEqualTo(1);
        assertThat(journal.count("activity-store:")).isEqualTo(1);
        assertThat(journal.count("original:")).isEqualTo(2);
        assertThat(journal.count("activity-original:")).isEqualTo(2);
        assertThat(settlementCount("stored")).isZero();
        assertThat(activityCount("stored")).isZero();
        assertThat(settlementCount("not_durable")).isEqualTo(1);
        assertThat(activityCount("not_durable")).isEqualTo(1);
        assertThat(settlementCount("resend")).isEqualTo(2);
        assertThat(activityCount("resend")).isEqualTo(2);
    }

    @Test
    void exists只证明同ID存在_异字节不能触发原负载重投或销账() {
        unknownSettlementWithOriginal();
        unknownActivityWithOriginal();
        byte[] foreign = {9, 8, 7};
        settlements.applyStore(PLAYER, BATTLE, foreign);
        activities.applyStore(BATTLE, foreign);

        scheduler.advance(ROUND * 2);

        assertThat(transport.sent).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(settlementOutbox.attemptsByBattle()).containsEntry(BATTLE, 0);
        assertThat(settlementOutbox.size()).isEqualTo(1);
        assertThat(activityOutbox.size()).isEqualTo(1);
        assertThat(settlements.record(PLAYER, BATTLE)).isEqualTo(foreign);
        assertThat(activities.copy(BATTLE)).isEqualTo(foreign);
        assertThat(settlementCount("acked")).isZero();
        assertThat(activityCount("acked")).isZero();
        assertThat(settlementCount("probe_error")).isEqualTo(2);
        assertThat(activityCount("probe_error")).isEqualTo(2);
    }

    @Test
    void 原件读失败或空值_保留UNKNOWN_下一轮读成功才恢复() {
        unknownSettlementWithOriginal();
        unknownActivityWithOriginal();
        settlements.originals.failWith(new TimeoutException("读回包丢失"));
        activities.originals.replyWith(null);
        scheduler.advance(ROUND);
        assertThat(transport.sent).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(settlementCount("resend")).isZero();
        assertThat(activityCount("resend")).isZero();
        assertThat(settlementOutbox.attemptsByBattle()).containsEntry(BATTLE, 0);

        settlements.originals.auto();
        activities.originals.auto();
        scheduler.advance(ROUND);
        assertThat(transport.sent).hasSize(2);
        assertThat(published).hasSize(2);
        assertThat(settlementCount("resend")).isEqualTo(1);
        assertThat(activityCount("resend")).isEqualTo(1);
    }

    @Test
    void 曾读到原件也不能将后续缺失升级为销账_只在年龄上限停止() {
        unknownSettlementWithOriginal();
        unknownActivityWithOriginal();
        scheduler.advance(ROUND);
        settlements.sceneAck(PLAYER, BATTLE);
        activities.consume(BATTLE);
        scheduler.advance(ROUND);
        assertThat(settlementOutbox.size()).isEqualTo(1);
        assertThat(activityOutbox.size()).isEqualTo(1);
        assertThat(settlementCount("acked")).isZero();
        assertThat(activityCount("acked")).isZero();

        scheduler.advance(BattleRedis.OUTBOX_MAX_AGE.toMillis());
        assertThat(settlementOutbox.size()).isZero();
        assertThat(activityOutbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(settlementCount("expired")).isEqualTo(1);
        assertThat(activityCount("expired")).isEqualTo(1);
        assertThat(settlementCount("acked")).isZero();
        assertThat(activityCount("acked")).isZero();
        assertThat(journal.count("store:")).isEqualTo(1);
        assertThat(journal.count("activity-store:")).isEqualTo(1);
        assertThat(transport.sent).hasSize(2);
        assertThat(published).hasSize(2);
    }

    @Test
    void 未知结算离线且已有下一局锁_不调用取代销账也不删原件() {
        unknownSettlementWithOriginal();
        locator.offline(PLAYER);
        settlements.lock(PLAYER, BATTLE + 1);
        scheduler.advance(ROUND);

        assertThat(journal.count("superseded?:")).isZero();
        assertThat(settlements.hasTombstone(PLAYER, BATTLE)).isFalse();
        assertThat(settlements.record(PLAYER, BATTLE)).isEqualTo(settlementBytes());
        assertThat(settlementOutbox.size()).isEqualTo(1);
        assertThat(settlementCount("skip_no_target")).isEqualTo(1);
        assertThat(settlementCount("superseded")).isZero();
    }

    @Test
    void 旧store回包晚于同原件新登记_不得重置重投次数或重复首投() {
        locator.online(PLAYER, 1, 9, "scene-9");
        settlements.stores.hold();
        activities.stores.hold();
        dispatchSettlement();
        dispatchActivity();
        dispatchSettlement();
        dispatchActivity();
        settlements.stores.pending.get(1).release();
        activities.stores.pending.get(1).release();
        scheduler.runPending();
        scheduler.advance(ROUND);
        assertThat(settlementOutbox.attemptsByBattle()).containsEntry(BATTLE, 1);

        // 旧命令也真的执行；同字节写入不会改负载，但迟到回包不得另建登记。
        settlements.stores.pending.get(0).release();
        activities.stores.pending.get(0).release();
        scheduler.runPending();
        assertThat(transport.sent).hasSize(2);
        assertThat(published).hasSize(2);
        assertThat(settlementCount("stored")).isEqualTo(1);
        assertThat(activityCount("stored")).isEqualTo(1);
        assertThat(settlementOutbox.attemptsByBattle()).containsEntry(BATTLE, 1);
        assertThat(settlementOutbox.inFlight()).isZero();

        scheduler.advance(ROUND);
        assertThat(transport.sent).extracting(sent -> sent.call().getAttempt()).containsExactly(0, 1, 2);
        assertThat(published).hasSize(3);
        assertThat(settlements.record(PLAYER, BATTLE)).isEqualTo(settlementBytes());
        assertThat(activities.copy(BATTLE)).isEqualTo(activity().toByteArray());
    }

    @Test
    void 结算新登记已过龄但旧store尚未执行_异字节仍拒绝直到全部写调用结束() {
        locator.online(PLAYER, 1, 9, "scene-9");
        settlements.stores.hold();
        dispatchSettlement();
        dispatchSettlement();
        settlements.stores.pending.get(1).release();
        scheduler.runPending();
        settlements.probes.hold();
        scheduler.advance(BattleRedis.OUTBOX_MAX_AGE.toMillis() + ROUND);
        assertThat(settlementOutbox.size()).isZero();
        assertThat(settlementCount("expired")).isEqualTo(1);
        assertThat(settlementOutbox.inFlight()).isEqualTo(1);

        BattleSettlementData changed = settlement().toBuilder().setGoldGain(999).build();
        settlementOutbox.dispatch(ROUTING, PLAYER, changed);
        scheduler.runPending();
        assertThat(settlementCount("payload_conflict")).isEqualTo(1);
        assertThat(journal.count("store:")).isEqualTo(2);
        assertThat(transport.sent).hasSize(1);
        assertThat(settlementOutbox.size()).isZero();

        // A1 到此才执行模型的写入，不是只补一个早已执行完的回包。
        settlements.stores.pending.get(0).release();
        scheduler.runPending();
        assertThat(settlements.record(PLAYER, BATTLE)).isEqualTo(settlementBytes());
        assertThat(settlementCount("stored")).isEqualTo(1);
        assertThat(settlementOutbox.size()).isZero();
        assertThat(settlementOutbox.inFlight()).isZero();
        assertThat(transport.sent).hasSize(1);

        // 全部 store 结局已归还才释放约束；新调用可以登记，旧 probe 不能摘除它。
        settlements.stores.auto();
        settlementOutbox.dispatch(ROUTING, PLAYER, changed);
        scheduler.runPending();
        byte[] changedBytes = BattleSettlementEvent.newBuilder().setSettlement(changed).build().toByteArray();
        assertThat(settlements.record(PLAYER, BATTLE)).isEqualTo(changedBytes);
        assertThat(journal.count("store:")).isEqualTo(3);
        assertThat(transport.sent).hasSize(2);
        settlements.probes.last().complete(false);
        scheduler.runPending();
        assertThat(settlementOutbox.size()).isEqualTo(1);
        assertThat(settlementCount("acked")).isZero();
    }

    @Test
    void 活动新登记已销账但旧store尚未执行_异字节仍拒绝直到全部写调用结束() {
        activities.stores.hold();
        dispatchActivity();
        dispatchActivity();
        activities.stores.pending.get(1).release();
        scheduler.runPending();
        activities.consume(BATTLE);
        scheduler.advance(ROUND);
        assertThat(activityOutbox.size()).isZero();
        assertThat(activityCount("acked")).isEqualTo(1);

        BattleResultEvent changed = activity().toBuilder().setTotalRounds(999).build();
        activityOutbox.dispatch(changed);
        scheduler.runPending();
        assertThat(activityCount("payload_conflict")).isEqualTo(1);
        assertThat(journal.count("activity-store:")).isEqualTo(2);
        assertThat(published).containsExactly(activity());
        assertThat(activities.copy(BATTLE)).isNull();

        // 旧 SET 现在才真正落地。没有活动墓碑，旧原件会复现，但不得覆盖已接受的异字节新记录。
        activities.stores.pending.get(0).release();
        scheduler.runPending();
        assertThat(activities.copy(BATTLE)).isEqualTo(activity().toByteArray());
        assertThat(activityCount("stored")).isEqualTo(1);
        assertThat(activityOutbox.size()).isZero();
        assertThat(published).containsExactly(activity());

        activities.stores.auto();
        activityOutbox.dispatch(changed);
        scheduler.runPending();
        assertThat(activities.copy(BATTLE)).isEqualTo(changed.toByteArray());
        assertThat(journal.count("activity-store:")).isEqualTo(3);
        assertThat(activityOutbox.size()).isEqualTo(1);
        assertThat(published).containsExactly(activity(), changed);
    }

    @Test
    void 同ID不同字节在pending或已登记期间都拒绝_不写不首投() {
        locator.online(PLAYER, 1, 9, "scene-9");
        settlements.stores.hold();
        activities.stores.hold();
        dispatchSettlement();
        dispatchActivity();
        BattleSettlementData conflictSettlement = settlement().toBuilder().setGoldGain(999).build();
        BattleResultEvent conflictActivity = activity().toBuilder().setTotalRounds(999).build();
        for (int phase = 0; phase < 2; phase++) {
            settlementOutbox.dispatch(ROUTING, PLAYER, conflictSettlement);
            activityOutbox.dispatch(conflictActivity);
            scheduler.runPending();
            if (phase == 0) {
                settlements.stores.last().release();
                activities.stores.last().release();
                scheduler.runPending();
            }
        }

        assertThat(journal.count("store:")).isEqualTo(1);
        assertThat(journal.count("activity-store:")).isEqualTo(1);
        assertThat(settlementCount("payload_conflict")).isEqualTo(2);
        assertThat(activityCount("payload_conflict")).isEqualTo(2);
        assertThat(settlements.record(PLAYER, BATTLE)).isEqualTo(settlementBytes());
        assertThat(activities.copy(BATTLE)).isEqualTo(activity().toByteArray());
        assertThat(transport.sent).hasSize(1);
        assertThat(published).containsExactly(activity());
        assertThat(settlementOutbox.inFlight()).isZero();
    }

    @Test
    void 旧原件读取晚于新登记_不得重投或改写新登记() {
        unknownSettlementWithOriginal();
        unknownActivityWithOriginal();
        settlements.originals.hold();
        activities.originals.hold();
        scheduler.advance(ROUND);
        settlements.stores.auto();
        activities.stores.auto();
        dispatchSettlement();
        dispatchActivity();
        assertThat(transport.sent).hasSize(2);
        assertThat(published).hasSize(2);

        settlements.originals.last().release();
        activities.originals.last().release();
        scheduler.runPending();
        assertThat(transport.sent).hasSize(2);
        assertThat(published).hasSize(2);
        assertThat(settlementOutbox.attemptsByBattle()).containsEntry(BATTLE, 0);
        assertThat(settlementCount("resend")).isZero();
        assertThat(activityCount("resend")).isZero();
        scheduler.advance(ROUND);
        assertThat(transport.last().call().getAttempt()).isEqualTo(1);
        assertThat(published).hasSize(3);
    }

    @Test
    void 原件读取一直悬挂仍受年龄限制_过龄后的回包不能再投递() {
        unknownSettlementWithOriginal();
        unknownActivityWithOriginal();
        settlements.originals.hold();
        activities.originals.hold();
        scheduler.advance(ROUND);
        scheduler.advance(BattleRedis.OUTBOX_MAX_AGE.toMillis());
        assertThat(settlementOutbox.size()).isZero();
        assertThat(activityOutbox.size()).isZero();
        assertThat(settlementCount("expired")).isEqualTo(1);
        assertThat(activityCount("expired")).isEqualTo(1);

        settlements.originals.last().release();
        activities.originals.last().release();
        scheduler.runPending();
        assertThat(transport.sent).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(settlementCount("acked")).isZero();
        assertThat(activityCount("acked")).isZero();
    }

    @Test
    void 无效store返回码不能算作落库成功_仍保留未知登记() {
        settlements.stores.replyWith(0L);
        activities.stores.replyWith(-2L);
        dispatchSettlement();
        dispatchActivity();
        scheduler.advance(ROUND);
        assertThat(settlementCount("stored")).isZero();
        assertThat(activityCount("stored")).isZero();
        assertThat(settlementCount("not_durable")).isEqualTo(1);
        assertThat(activityCount("not_durable")).isEqualTo(1);
        assertThat(settlementOutbox.size()).isEqualTo(1);
        assertThat(activityOutbox.size()).isEqualTo(1);
        assertThat(settlementCount("acked")).isZero();
        assertThat(activityCount("acked")).isZero();
    }

    @Test
    void 已提交broker的活动store未知_不重复首发_原件核对后仍可有界重发() {
        activities.stores.hold();
        activityOutbox.dispatchCommitted(activity());
        scheduler.runPending();
        activities.applyStore(BATTLE, activity().toByteArray());
        activities.stores.last().fail(new TimeoutException("Redis 响应丢失"));
        scheduler.runPending();
        assertThat(published).isEmpty();
        assertThat(activityOutbox.size()).isEqualTo(1);

        scheduler.advance(ROUND);
        assertThat(published).containsExactly(activity());
        assertThat(activityCount("resend")).isEqualTo(1);
        assertThat(journal.count("activity-store:")).isEqualTo(1);
    }

    @Test
    void 未知写入查到相同原件_仍遵守12次和30次上限_耗尽不删原件不记ACK() {
        unknownSettlementWithOriginal();
        unknownActivityWithOriginal();
        scheduler.advance(ROUND * (BattleRedis.ACTIVITY_RETRY_MAX + 1));

        assertThat(transport.sent).hasSize(BattleRedis.SETTLEMENT_RETRY_MAX + 1);
        assertThat(published).hasSize(BattleRedis.ACTIVITY_RETRY_MAX + 1);
        assertThat(settlementCount("exhausted")).isEqualTo(1);
        assertThat(activityCount("exhausted")).isEqualTo(1);
        assertThat(settlementCount("acked")).isZero();
        assertThat(activityCount("acked")).isZero();
        assertThat(settlementOutbox.size()).isZero();
        assertThat(activityOutbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(settlements.record(PLAYER, BATTLE)).isEqualTo(settlementBytes());
        assertThat(activities.copy(BATTLE)).isEqualTo(activity().toByteArray());
        assertThat(journal.count("store:")).isEqualTo(1);
        assertThat(journal.count("activity-store:")).isEqualTo(1);
    }

    @Test
    void 未实现原件读取的旧端口_exists为真也不能重投UNKNOWN() {
        SettlementOutbox.Store unreadableSettlement = new SettlementOutbox.Store() {
            @Override
            public CompletableFuture<Long> store(long playerId, long battleId, byte[] payload) {
                return CompletableFuture.failedFuture(new TimeoutException("写入结局未知"));
            }

            @Override
            public CompletableFuture<Boolean> exists(long playerId, long battleId) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Long> ackIfSuperseded(long playerId, long battleId) {
                throw new AssertionError("UNKNOWN 不得调用销账");
            }
        };
        ActivityResultOutbox.Store unreadableActivity = new ActivityResultOutbox.Store() {
            @Override
            public CompletableFuture<Long> store(long battleId, byte[] payload) {
                return CompletableFuture.failedFuture(new TimeoutException("写入结局未知"));
            }

            @Override
            public CompletableFuture<Boolean> exists(long battleId) {
                return CompletableFuture.completedFuture(true);
            }
        };
        locator.online(PLAYER, 1, 9, "scene-9");
        SettlementOutbox legacySettlement = new SettlementOutbox(scheduler, unreadableSettlement, locator::locate,
                transport::applySettlement, metrics, scheduler::nowMs);
        ActivityResultOutbox legacyActivity = new ActivityResultOutbox(scheduler, unreadableActivity,
                (event, channel) -> published.add(event), metrics, scheduler::nowMs);
        legacySettlement.dispatch(ROUTING, PLAYER, settlement());
        legacyActivity.dispatch(activity());
        scheduler.runPending();
        scheduler.advance(ROUND);

        assertThat(transport.sent).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(legacySettlement.size()).isEqualTo(1);
        assertThat(legacyActivity.size()).isEqualTo(1);
        assertThat(settlementCount("probe_error")).isEqualTo(1);
        assertThat(activityCount("probe_error")).isEqualTo(1);
        assertThat(settlementCount("resend")).isZero();
        assertThat(activityCount("resend")).isZero();
    }
}
