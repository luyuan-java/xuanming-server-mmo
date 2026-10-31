package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.DUNGEON_TANK;
import static com.game.battle.room.RoomHarness.INSTANCE;
import static com.game.battle.room.RoomHarness.observerRouting;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.metrics.BattleMetrics.RoundTrigger;
import com.game.proto.AddObserverRequest;
import com.game.proto.AttachRelayGateRequest;
import com.game.proto.AttachRelayGateResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.MessageContent;
import com.game.proto.PromoteSpectateBroadcastRequest;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.TurnResultS2C;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import com.game.proto.contracts.kafka.eSpectateFrameKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.eSpectateEndReason;
import com.game.proto.eSpectateVisibility;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 观战中继开关（mmorpg battle-spectate-tiered-fanout.md §15.4、§15.7 的 Java 口径：中继与广播是同一个开关，帧只发布一次）。
 * 出站端口是记录型的（{@link RoomHarness#relayFrames}）；真 Redis 见 game-discovery 的 {@code SpectateFrameChannelIntegrationTest}。
 */
class SpectateRelayRoomTest {

    private static final long BATTLE = 8801;

    private final RoomHarness h = new RoomHarness();

    private AttachRelayGateResponse attach(long battle, String instance) {
        return h.service().attachRelayGate(AttachRelayGateRequest.newBuilder().setBattleId(battle).setTargetInstanceId(instance).build());
    }

    private void createPve() {
        h.create(h.pveTeam(BATTLE, A).setBattleConfigId(DUNGEON_TANK));
    }

    private void resolve() {
        h.service().resolveRound(h.service().room(BATTLE), RoundTrigger.TIMER);
    }

    private static MessageContent content(SpectateFrameEvent frame) {
        try {
            return MessageContent.parseFrom(frame.getClientFrame());
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    // ================================================================ 校验

    @Test
    void 校验顺序_实例不符1005_房间不存在1004_私密局1005_都不发布() {
        h.create(h.pvp(BATTLE, A, B).setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE));

        assertThat(attach(404, "other-instance").getErrorMessage().getId()).as("实例先于房间").isEqualTo(BattleRoomServiceImpl.INVALID_PARAMETER);
        assertThat(attach(404, INSTANCE).getErrorMessage().getId()).as("1004 是 match 懒剔除的信号").isEqualTo(BattleRoomServiceImpl.ENTITY_IS_NULL);
        assertThat(attach(BATTLE, INSTANCE).getErrorMessage().getId()).isEqualTo(BattleRoomServiceImpl.INVALID_PARAMETER);
        assertThat(h.service().promoteSpectateBroadcast(PromoteSpectateBroadcastRequest.newBuilder().setBattleId(BATTLE).setTargetInstanceId(INSTANCE).build())
                .getErrorMessage().getId()).isEqualTo(BattleRoomServiceImpl.INVALID_PARAMETER);
        assertThat(h.service().room(BATTLE).spectateRelayEnabled).isFalse();
        assertThat(h.relayFrames).isEmpty();
    }

    @Test
    void 漏填可见性同私密() {
        h.create(h.pvp(BATTLE, A, B).setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_UNSPECIFIED));

        assertThat(attach(BATTLE, INSTANCE).getErrorMessage().getId()).isEqualTo(BattleRoomServiceImpl.INVALID_PARAMETER);
    }

    // ================================================================ 开关

    @Test
    void 打开回实例与期限_第一次发布一帧STATE_重复调用幂等不再发布_广播入口是同一个开关() throws Exception {
        createPve();
        long deadline = h.service().room(BATTLE).deadlineMs;

        AttachRelayGateResponse first = attach(BATTLE, INSTANCE);

        assertThat(first.hasErrorMessage()).isFalse();
        assertThat(first.getBattleInstanceId()).isEqualTo(INSTANCE);
        assertThat(first.getDeadlineMs()).isEqualTo(deadline);
        assertThat(first.getBroadcast()).as("Java 没有逐 gate 挂接，也没有单独的广播档").isFalse();
        assertThat(h.relayFrames).singleElement().satisfies(frame -> {
            assertThat(frame.getBattleId()).isEqualTo(BATTLE);
            assertThat(frame.getBattleInstanceId()).isEqualTo(INSTANCE);
            assertThat(frame.getKind()).isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_STATE);
            assertThat(frame.getRoundIndex()).isEqualTo(1);
            assertThat(frame.getDeadlineMs()).isEqualTo(deadline);
        });
        MessageContent state = content(h.relayFrames.get(0));
        assertThat(state.getMessageId()).isEqualTo(h.ids.id(com.game.battle.protocol.BattleMessageIds.Notify.SPECTATE_STATE));
        assertThat(state.getId()).as("推送形状").isZero();
        SpectateStateS2C parsed = SpectateStateS2C.parseFrom(state.getSerializedMessage());
        assertThat(parsed.getObserverCount()).as("大厅档人数未知").isZero();
        assertThat(parsed.getState().getSelfItemsCount()).as("观众版：没有 self_items").isZero();
        assertThat(parsed.getState().getActorsList()).allSatisfy(actor -> assertThat(actor.getSkillCooldownRoundsMap()).as("观众版：冷却清空").isEmpty());

        assertThat(attach(BATTLE, INSTANCE).getBattleInstanceId()).isEqualTo(INSTANCE);
        var promoted = h.service().promoteSpectateBroadcast(PromoteSpectateBroadcastRequest.newBuilder().setBattleId(BATTLE).setTargetInstanceId(INSTANCE).build());
        assertThat(promoted.getBattleInstanceId()).isEqualTo(INSTANCE);
        assertThat(promoted.getDeadlineMs()).isEqualTo(deadline);
        assertThat(h.relayFrames).as("开关只升不降，只发布一次 STATE").hasSize(1);
    }

    @Test
    void 没打开中继_回合与收尾都不发布_直连观众照常() {
        createPve();
        h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(BATTLE).setObserverPlayerId(C).setObserverName("观众")
                .setRouting(observerRouting(C, 900)).setSpectatorOperationId(UUID.randomUUID().toString()).setAdmitBeforeMs(h.now() + 25_000)
                .setTargetInstanceId(INSTANCE).build());

        resolve();
        h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(BATTLE).setReason("test").build());

        assertThat(h.relayFrames).isEmpty();
    }

    // ================================================================ 每回合与收尾

    @Test
    void 每回合发布一次观众版158_回合号是刚结算的回合_客户端帧与直连同一份() throws Exception {
        createPve();
        attach(BATTLE, INSTANCE);
        h.relayFrames.clear();

        resolve();
        resolve();

        assertThat(h.relayFrames).extracting(SpectateFrameEvent::getKind).containsExactly(eSpectateFrameKind.SPECTATE_FRAME_TURN, eSpectateFrameKind.SPECTATE_FRAME_TURN);
        assertThat(h.relayFrames).extracting(SpectateFrameEvent::getRoundIndex).containsExactly(1, 2);
        TurnResultS2C turn = TurnResultS2C.parseFrom(content(h.relayFrames.get(1)).getSerializedMessage());
        assertThat(content(h.relayFrames.get(1)).getMessageId()).isEqualTo(h.ids.id(com.game.battle.protocol.BattleMessageIds.Notify.SPECTATE_TURN_RESULT));
        assertThat(turn.getRoundIndex()).isEqualTo(2);
        assertThat(turn.getState().getRoundIndex()).as("state 是下一回合的快照").isEqualTo(3);
        assertThat(turn.getState().getActorsList()).allSatisfy(actor -> assertThat(actor.getSkillCooldownRoundsMap()).as("观众版").isEmpty());
    }

    @Test
    void 作废发布一帧END_原因与直连同值_回合号取最后一帧() throws Exception {
        createPve();
        attach(BATTLE, INSTANCE);
        resolve();
        h.relayFrames.clear();

        h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(BATTLE).setReason("test").build());

        assertThat(h.relayFrames).singleElement().satisfies(frame -> {
            assertThat(frame.getKind()).isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_END);
            assertThat(frame.getRoundIndex()).isEqualTo(1);
        });
        SpectateEndS2C end = SpectateEndS2C.parseFrom(content(h.relayFrames.get(0)).getSerializedMessage());
        assertThat(end.getBattleId()).isEqualTo(BATTLE);
        assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
    }

    @Test
    void 分出胜负_先发终局TURN再发END_END原因是FINISHED() throws Exception {
        h.create(h.pveTeam(BATTLE, A).setBattleConfigId(RoomHarness.DUNGEON_BRUTE));
        attach(BATTLE, INSTANCE);
        h.relayFrames.clear();

        for (int i = 0; i < 50 && h.service().room(BATTLE) != null; i++) {
            resolve();
        }

        assertThat(h.service().room(BATTLE)).as("一击必杀的怪：房间已收尾").isNull();
        List<SpectateFrameEvent> frames = h.relayFrames;
        assertThat(frames.get(frames.size() - 1).getKind()).isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_END);
        assertThat(frames.get(frames.size() - 2).getKind()).isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_TURN);
        assertThat(frames.stream().filter(f -> f.getKind() == eSpectateFrameKind.SPECTATE_FRAME_END)).as("END 只发布一次").hasSize(1);
        SpectateEndS2C end = SpectateEndS2C.parseFrom(content(frames.get(frames.size() - 1)).getSerializedMessage());
        assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED);
    }

    @Test
    void 端口抛异常不打断回合推进_也不影响开关() {
        h.spectateFrames = frame -> {
            throw new IllegalStateException("redis client closed");
        };
        createPve();

        assertThat(attach(BATTLE, INSTANCE).hasErrorMessage()).isFalse();
        resolve();

        assertThat(h.service().room(BATTLE)).isNotNull();
        assertThat(h.service().room(BATTLE).spectateRelayEnabled).isTrue();
        assertThat(h.service().room(BATTLE).lastSpectateRound).isEqualTo(1);
    }

    // ================================================================ 按场串行（Redisson 紧挨着发出的命令没有先后，END 不能越过最后一回合的 TURN）

    /** 记录型端口，但每一帧的结局由测试决定：按发布顺序取一个 future，取完后的帧当场完成。 */
    private final List<CompletableFuture<Void>> held = new ArrayList<>();

    private void holdFrames(int count) {
        for (int i = 0; i < count; i++) {
            held.add(new CompletableFuture<>());
        }
        h.spectateFrames = frame -> {
            h.relayFrames.add(frame);
            int index = h.relayFrames.size() - 1;
            return index < held.size() ? held.get(index) : CompletableFuture.completedFuture(null);
        };
    }

    @Test
    void 上一帧没有结局时下一帧不发布_END等到最后一回合的TURN完成才发_逻辑线程不等() {
        holdFrames(2);
        h.create(h.pveTeam(BATTLE, A).setBattleConfigId(RoomHarness.DUNGEON_BRUTE));
        attach(BATTLE, INSTANCE);
        assertThat(h.relayFrames).as("STATE 已发出、还没有结局").hasSize(1);

        for (int i = 0; i < 50 && h.service().room(BATTLE) != null; i++) {
            resolve();
        }

        assertThat(h.service().room(BATTLE)).as("回合照常推进到收尾：逻辑线程没有等 Redis").isNull();
        assertThat(h.relayFrames).as("后面的帧都排在 STATE 之后").hasSize(1);

        held.get(0).complete(null);
        assertThat(h.relayFrames).as("STATE 完成：只放出下一帧").hasSize(2);
        assertThat(h.relayFrames.get(1).getKind()).isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_TURN);

        held.get(1).complete(null);
        List<SpectateFrameEvent> frames = h.relayFrames;
        assertThat(frames.get(frames.size() - 2).getKind()).isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_TURN);
        assertThat(frames.get(frames.size() - 1).getKind()).as("END 排在最后").isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_END);
        assertThat(frames).as("房间已回收，排着的帧照常发完").extracting(SpectateFrameEvent::getKind).contains(eSpectateFrameKind.SPECTATE_FRAME_END);
    }

    @Test
    void 上一帧失败也算有结局_下一帧照发() {
        holdFrames(2);
        createPve();
        attach(BATTLE, INSTANCE);
        resolve();
        h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(BATTLE).setReason("test").build());
        assertThat(h.relayFrames).hasSize(1);

        held.get(0).completeExceptionally(new IllegalStateException("redis timeout"));
        assertThat(h.relayFrames).as("STATE 失败：TURN 照发").hasSize(2);
        held.get(1).completeExceptionally(new IllegalStateException("redis timeout"));

        assertThat(h.relayFrames).extracting(SpectateFrameEvent::getKind).containsExactly(eSpectateFrameKind.SPECTATE_FRAME_STATE,
                eSpectateFrameKind.SPECTATE_FRAME_TURN, eSpectateFrameKind.SPECTATE_FRAME_END);
    }

    @Test
    void 端口抛异常也不断了后面的帧() {
        h.spectateFrames = frame -> {
            h.relayFrames.add(frame);
            if (frame.getKind() == eSpectateFrameKind.SPECTATE_FRAME_TURN) {
                throw new IllegalStateException("redis client closed");
            }
            return CompletableFuture.completedFuture(null);
        };
        createPve();
        attach(BATTLE, INSTANCE);

        resolve();
        h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(BATTLE).setReason("test").build());

        assertThat(h.relayFrames).extracting(SpectateFrameEvent::getKind).containsExactly(eSpectateFrameKind.SPECTATE_FRAME_STATE,
                eSpectateFrameKind.SPECTATE_FRAME_TURN, eSpectateFrameKind.SPECTATE_FRAME_END);
    }

    @Test
    void 观众与中继并存_直连观众照常收帧_中继同样一帧() {
        createPve();
        String operation = UUID.randomUUID().toString();
        h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(BATTLE).setObserverPlayerId(C).setObserverName("观众")
                .setRouting(observerRouting(C, 900)).setSpectatorOperationId(operation).setAdmitBeforeMs(h.now() + 25_000)
                .setTargetInstanceId(INSTANCE).build());
        RoomHarness.FakeLink link = h.connect(BATTLE, C, com.game.proto.eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        attach(BATTLE, INSTANCE);
        h.relayFrames.clear();
        link.frames.clear();

        resolve();

        assertThat(link.frames).hasSize(1);
        assertThat(h.relayFrames).hasSize(1);
        assertThat(h.relayFrames.get(0).getClientFrame()).as("同一份观众帧").isEqualTo(link.frames.get(0).toByteString());
    }
}
