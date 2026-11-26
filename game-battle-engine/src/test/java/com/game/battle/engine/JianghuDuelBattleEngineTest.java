package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleOutcome;
import org.junit.jupiter.api.Test;

/** 天墉生死状一期回合战斗引擎回归测试：默认 30 回合上限、打满判平与上下文回显。 */
class JianghuDuelBattleEngineTest {

    private static CreateBattleRequest makeRequest(int roundLimitOverride) {
        CreateBattleRequest.Builder req = CreateBattleRequest.newBuilder()
                .setBattleId(99001L)
                .setBattleConfigId(0)
                .setMatchMode(BattleConstants.MATCH_MODE_JIANGHU_DUEL)
                .setSeed(1234567L)
                .setJianghuDuelContext(JianghuDuelBattleContext.newBuilder()
                        .setDuelInviteId(5001L)
                        .setWritTier(2)
                        .setChallengerCaptainId(1001L)
                        .setChallengerCaptainName("QingFeng")
                        .setTargetCaptainId(2001L)
                        .setTargetCaptainName("MoYuan")
                        .setSideAGuildId(801L)
                        .setSideAGuildName("QingYun")
                        .setSideBGuildId(802L)
                        .setSideBGuildName("CangLan")
                        .setRoundLimitOverride(roundLimitOverride)
                        .setTeamSize(1)
                        .build());

        for (long pid : new long[] {1001L, 2001L}) {
            req.addPlayers(BattlePlayerSnapshot.newBuilder()
                    .setPlayerId(pid)
                    .setTeamIndex(pid == 1001L ? 0 : 1)
                    .setPlayerName(pid == 1001L ? "QingFeng" : "MoYuan")
                    .setLevel(50)
                    .setMaxHealth(5000)
                    .setMaxMana(1000)
                    .setBaseAttributes(BaseAttributesComp.newBuilder()
                            .setHealth(5000)
                            .setMana(1000)
                            .setSpeed(200)
                            .build())
                    .setPhysicalAttack(300)
                    .setMagicAttack(300)
                    .setDefense(100)
                    .build());
        }
        return req.build();
    }

    @Test
    void defaultsToThirtyRoundsAndCompetitivePvp() {
        BattleStart started = TurnBattleEngine.start(makeRequest(0), new MemoryBattleData());
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        TurnBattleEngine engine = ((BattleStart.Started) started).engine();
        assertThat(engine.isJianghuDuelBattle()).isTrue();
        assertThat(engine.isCompetitivePvpBattle()).isTrue();
        assertThat(engine.maxRounds()).isEqualTo(BattleConstants.JIANGHU_DUEL_DEFAULT_MAX_ROUNDS);
    }

    @Test
    void roundLimitForcesDrawAndEchoesJianghuContext() {
        BattleStart started = TurnBattleEngine.start(makeRequest(2), new MemoryBattleData());
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        TurnBattleEngine engine = ((BattleStart.Started) started).engine();
        assertThat(engine.maxRounds()).isEqualTo(2);

        BattleAction defend = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        for (int r = 0; r < 2; r++) {
            assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
            engine.submitAction(1001L, defend);
            engine.submitAction(2001L, defend);
            engine.resolveCurrentRound();
        }

        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        BattleSettlementData settleA = engine.buildSettlement(1001L);
        assertThat(settleA.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        assertThat(settleA.getTotalRounds()).isEqualTo(2);
        assertThat(settleA.hasJianghuDuel()).isTrue();
        assertThat(settleA.getJianghuDuel().getDuelInviteId()).isEqualTo(5001L);
        assertThat(settleA.getJianghuDuel().getWritTier()).isEqualTo(2);
        assertThat(settleA.getJianghuDuel().getSideAGuildId()).isEqualTo(801L);
        assertThat(settleA.getJianghuDuel().getSideBGuildId()).isEqualTo(802L);
    }
}
