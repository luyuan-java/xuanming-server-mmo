package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.engine.BattleConstants;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.proto.AddObserverRequest;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattlePrepUpdateS2C;
import com.game.proto.BattleReplay;
import com.game.proto.BattleStartS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.EquipmentEffects;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.RankedMatchContext;
import com.game.proto.SeasonSealEffect;
import com.game.proto.SpectateStateS2C;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.UpdateBattlePrepRequest;
import com.game.proto.UpdateBattlePrepResponse;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.MatchMode;
import com.game.table.CommonErrorTip.common_error;
import org.junit.jupiter.api.Test;

/** 论剑 V3 战前 15 秒明牌变阵（504/505）、同队隐私推送、观众仅看倒计时与亮牌后延迟录播测试。 */
class BattleTacticalPrepRoomTest {

    private static SeasonSealEffect seal(int sealId, int effectId, String name) {
        return SeasonSealEffect.newBuilder()
                .setSeasonId(1)
                .setSealId(sealId)
                .setEffectId(effectId)
                .setName(name)
                .setBonusBps(800)
                .setPerRoundLimit(1)
                .setBattleLimit(2)
                .build();
    }

    private static BattlePetSnapshot pet(long ownerId, long petId, int petTableId, String name, long speed) {
        return BattlePetSnapshot.newBuilder()
                .setPetId(petId)
                .setOwnerPlayerId(ownerId)
                .setPetTableId(petTableId)
                .setPetName(name)
                .setLevel(30)
                .setMaxHealth(800)
                .setMaxMana(300)
                .setPhysicalAttack(160)
                .setMagicAttack(160)
                .setDefense(90)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(800).setMana(300).setSpeed(speed))
                .build();
    }

    private CreateBattleRequest.Builder ranked2v2(RoomHarness h, long battleId, boolean recordReplay) {
        SeasonSealEffect sealA = seal(101, 7, "玄清回灵印");
        SeasonSealEffect sealB = seal(102, 8, "定心明神印");

        BattlePlayerSnapshot.Builder pA = RoomHarness.player(RoomHarness.A, 0, 50_000, 200)
                .setActiveSeasonId(1)
                .setEquipmentEffects(EquipmentEffects.newBuilder().setSeasonSeal(sealA))
                .addAvailableSeasonSeals(sealA)
                .addAvailableSeasonSeals(sealB)
                .addPets(pet(RoomHarness.A, 70001L, 1, "默认灵猫", 180))
                .addStandbyPets(pet(RoomHarness.A, 70002L, 2, "备选白虎", 250));
        BattlePlayerSnapshot.Builder pC = RoomHarness.player(RoomHarness.C, 0, 50_000, 190)
                .setActiveSeasonId(1)
                .setEquipmentEffects(EquipmentEffects.newBuilder().setSeasonSeal(sealA))
                .addAvailableSeasonSeals(sealA)
                .addAvailableSeasonSeals(sealB)
                .addPets(pet(RoomHarness.C, 70003L, 1, "队友灵宠", 170));
        BattlePlayerSnapshot.Builder pB = RoomHarness.player(RoomHarness.B, 1, 50_000, 195)
                .setActiveSeasonId(1)
                .setEquipmentEffects(EquipmentEffects.newBuilder().setSeasonSeal(sealB))
                .addAvailableSeasonSeals(sealA)
                .addAvailableSeasonSeals(sealB)
                .addPets(pet(RoomHarness.B, 70004L, 1, "对手灵宠", 175));

        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(0)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(303L)
                .setDeadlineMs(h.now() + 300_000)
                .setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC)
                .setRecordReplay(recordReplay)
                .setRankedContext(RankedMatchContext.newBuilder()
                        .setSeasonId(1).setRuleVersion(3).setTeamSize(2).setUseFreePreset(false))
                .addPlayers(pA)
                .addPlayers(pC)
                .addPlayers(pB);
    }

    @Test
    void 变阵期同队私密同步_观众仅看倒计时_全员锁定立即亮牌并延迟录播() throws Exception {
        RoomHarness h = new RoomHarness();
        long battleId = 9701L;
        h.create(ranked2v2(h, battleId, true));

        BattleRoom room = h.service().room(battleId);
        assertThat(room.engine.isInPrepPhase()).isTrue();
        assertThat(room.actionDeadlineMs).isZero();
        assertThat(room.prepDeadlineMs).isEqualTo(RoomHarness.T0 + BattleConstants.TACTICAL_PREP_TIMEOUT_MS);
        // 变阵期间绝不提前创建回放录制器，避免录下未换阵前的初始阵容
        assertThat(room.replay).isNull();

        // 参战者与观众直连入场
        RoomHarness.FakeLink linkA = h.connect(battleId, RoomHarness.A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        RoomHarness.FakeLink linkC = h.connect(battleId, RoomHarness.C, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        RoomHarness.FakeLink linkB = h.connect(battleId, RoomHarness.B, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        long obsId = 8001L;
        assertThat(h.addObserver(AddObserverRequest.newBuilder()
                .setBattleId(battleId).setObserverPlayerId(obsId).setObserverName("观战者")
                .setRouting(RoomHarness.observerRouting(obsId, 901)).build()).hasErrorMessage()).isFalse();
        RoomHarness.FakeLink linkObs = h.connect(battleId, obsId, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);

        // 观众首帧与补拉均只看到倒计时，绝不泄露双方可选或已选
        SpectateStateS2C obsFirst = linkObs.parsed(h.ids.id(Notify.SPECTATE_STATE), SpectateStateS2C.parser()).getFirst();
        assertThat(obsFirst.getState().getPrepState().getActive()).isTrue();
        assertThat(obsFirst.getState().getPrepState().getDeadlineMs()).isEqualTo(room.prepDeadlineMs);
        assertThat(obsFirst.getState().getPrepState().getSelfAvailableSealsList()).isEmpty();
        assertThat(obsFirst.getState().getPrepState().getTeamStatesList()).isEmpty();

        BattleStateS2C obsPolled = h.service().getState(obsId, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
        assertThat(obsPolled.getPrepState().getActive()).isTrue();
        assertThat(obsPolled.getPrepState().getTeamStatesList()).isEmpty();

        // 变阵期内提交回合行动返回 FEATURE_UNAVAILABLE
        var earlySubmit = h.service().submit(RoomHarness.A, SubmitBattleActionRequest.newBuilder()
                .setBattleId(battleId).setRoundIndex(1)
                .setAction(RoomHarness.action(eBattleActionType.BATTLE_ACTION_DEFEND, 0)).build());
        assertThat(earlySubmit.getErrorMessage().getId()).isEqualTo(common_error.kFeatureUnavailable_VALUE);

        // A 换印(102) + 换备选白虎(70002) 并锁定：仅向同队 A、C 推送 505，对手 B 与观众绝不收 505
        UpdateBattlePrepResponse respA = h.service().updateBattlePrep(RoomHarness.A, UpdateBattlePrepRequest.newBuilder()
                .setBattleId(battleId).setSelectedSealId(102).setSelectedPetId(70002L).setReady(true).build());
        assertThat(respA.hasErrorMessage()).isFalse();
        assertThat(respA.getPrepState().getDeadlineMs()).isEqualTo(room.prepDeadlineMs);
        assertThat(respA.getPrepState().getTeamStatesList()).hasSize(2);

        int prepNotifyId = h.ids.id(Notify.BATTLE_PREP_UPDATE);
        assertThat(linkA.parsed(prepNotifyId, BattlePrepUpdateS2C.parser())).hasSize(1);
        assertThat(linkC.parsed(prepNotifyId, BattlePrepUpdateS2C.parser())).hasSize(1);
        BattlePrepUpdateS2C pushedToC = linkC.parsed(prepNotifyId, BattlePrepUpdateS2C.parser()).getFirst();
        // C 收到的 505 带的是 C 自己的可选宠物(1只)，同时看到队友 A 已换白虎且已锁定
        assertThat(pushedToC.getPrepState().getSelfAvailablePetsList()).hasSize(1);
        assertThat(pushedToC.getPrepState().getTeamStatesList())
                .anySatisfy(ts -> {
                    assertThat(ts.getPlayerId()).isEqualTo(RoomHarness.A);
                    assertThat(ts.getSelectedPetId()).isEqualTo(70002L);
                    assertThat(ts.getReady()).isTrue();
                });
        assertThat(linkB.parsed(prepNotifyId, BattlePrepUpdateS2C.parser())).isEmpty();
        assertThat(linkObs.parsed(prepNotifyId, BattlePrepUpdateS2C.parser())).isEmpty();

        // C 和 B 全部锁定 -> 立刻亮牌开打
        assertThat(h.service().updateBattlePrep(RoomHarness.C, UpdateBattlePrepRequest.newBuilder()
                .setBattleId(battleId).setReady(true).build()).hasErrorMessage()).isFalse();
        assertThat(room.engine.isInPrepPhase()).isTrue();

        assertThat(h.service().updateBattlePrep(RoomHarness.B, UpdateBattlePrepRequest.newBuilder()
                .setBattleId(battleId).setSelectedSealId(101).setReady(true).build()).hasErrorMessage()).isFalse();

        assertThat(room.engine.isInPrepPhase()).isFalse();
        assertThat(room.prepDeadlineMs).isZero();
        assertThat(room.actionDeadlineMs).isEqualTo(RoomHarness.T0 + BattleConstants.ROUND_DURATION_MS);

        // 全员收到亮牌后的 143 BattleStartS2C（prep_state 已清，A 的灵宠已是 70002 备选白虎）
        int startId = h.ids.id(Notify.BATTLE_START);
        BattleStartS2C revealedA = linkA.parsed(startId, BattleStartS2C.parser()).getLast();
        assertThat(revealedA.getState().hasPrepState()).isFalse();
        assertThat(revealedA.getState().getActionDeadlineMs()).isEqualTo(room.actionDeadlineMs);
        assertThat(revealedA.getState().getActorsList())
                .anyMatch(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET
                        && a.getOwnerPlayerId() == RoomHarness.A && a.getPetId() == 70002L);

        // 观众收到亮牌后的 161 SpectateStateS2C（同样看到换上来的 70002 白虎且 prep_state 已清）
        SpectateStateS2C obsRevealed = linkObs.parsed(h.ids.id(Notify.SPECTATE_STATE), SpectateStateS2C.parser()).getLast();
        assertThat(obsRevealed.getState().hasPrepState()).isFalse();
        assertThat(obsRevealed.getState().getActorsList())
                .anyMatch(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET
                        && a.getOwnerPlayerId() == RoomHarness.A && a.getPetId() == 70002L);

        // 延迟创建的回放录制器记录的起点正是亮牌后的 70002 白虎，不含变阵期隐私态
        assertThat(room.replay).isNotNull();
        var envelope = room.replay.freeze(com.game.proto.eBattleOutcome.BATTLE_OUTCOME_DRAW, 0, h.now()).orElseThrow();
        BattleReplay replay = BattleReplayRecorder.decode(envelope);
        assertThat(replay.getStartState().hasPrepState()).isFalse();
        assertThat(replay.getStartState().getActorsList())
                .anyMatch(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET
                        && a.getOwnerPlayerId() == RoomHarness.A && a.getPetId() == 70002L);
    }

    @Test
    void 战前15秒倒计时到期自动锁阵亮牌开打() {
        RoomHarness h = new RoomHarness();
        long battleId = 9702L;
        h.create(ranked2v2(h, battleId, true));
        BattleRoom room = h.service().room(battleId);
        RoomHarness.FakeLink linkA = h.connect(battleId, RoomHarness.A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        // A 只改选不点锁定，等待 15 秒倒计时自动触发
        assertThat(h.service().updateBattlePrep(RoomHarness.A, UpdateBattlePrepRequest.newBuilder()
                .setBattleId(battleId).setSelectedPetId(70002L).setReady(false).build()).hasErrorMessage()).isFalse();

        h.scheduler.advance(BattleConstants.TACTICAL_PREP_TIMEOUT_MS - 1);
        assertThat(room.engine.isInPrepPhase()).isTrue();
        assertThat(room.replay).isNull();

        h.scheduler.advance(1);
        assertThat(room.engine.isInPrepPhase()).isFalse();
        assertThat(room.replay).isNotNull();
        assertThat(room.actionDeadlineMs).isEqualTo(RoomHarness.T0 + BattleConstants.TACTICAL_PREP_TIMEOUT_MS + BattleConstants.ROUND_DURATION_MS);

        BattleStartS2C revealed = linkA.parsed(h.ids.id(Notify.BATTLE_START), BattleStartS2C.parser()).getLast();
        assertThat(revealed.getState().getActorsList())
                .anyMatch(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET
                        && a.getOwnerPlayerId() == RoomHarness.A && a.getPetId() == 70002L);
    }
}
