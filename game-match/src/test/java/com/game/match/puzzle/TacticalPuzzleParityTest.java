package com.game.match.puzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.game.match.dispatch.MatchMethods;
import com.game.match.report.BattleReplayPolicy;
import com.game.proto.TacticalPuzzleBattleContext;
import com.game.proto.TacticalPuzzleEnemySpec;
import com.game.proto.TacticalPuzzleSpec;
import com.game.proto.match.MatchMode;
import org.junit.jupiter.api.Test;

class TacticalPuzzleParityTest {

    @Test
    void specTextTagSeedHonorAndReplayPolicyAlignWithGoAuthority() {
        TacticalPuzzleSpec valid = TacticalPuzzleSpec.newBuilder()
                .setRoundLimit(3)
                .setCharacterPresetId(102)
                .setPetPresetId(0)
                .setFormationId(2)
                .setPlayerInitialHpPct(65)
                .setPlayerInitialMpPct(80)
                .setPlayerInitialShieldPct(20)
                .setPlayerInitialDebuffKind(1)
                .addEnemies(TacticalPuzzleEnemySpec.newBuilder()
                        .setMonsterTableId(311)
                        .setInitialHpPct(90)
                        .setInitialShieldPct(40)
                        .setSpeedDeltaPct(-10)
                        .setInitialBuffKind(2)
                        .build())
                .build();
        assertTrue(TacticalPuzzleSpecValidator.isValidSpec(valid));
        assertFalse(TacticalPuzzleSpecValidator.isValidSpec(valid.toBuilder().setRoundLimit(7).build()));
        assertFalse(TacticalPuzzleSpecValidator.isValidSpec(valid.toBuilder().setPetPresetId(205).build()));

        assertTrue(TacticalPuzzleSpecValidator.isValidTagMask((1 << 0) | (1 << 4)));
        assertFalse(TacticalPuzzleSpecValidator.isValidTagMask((1 << 0) | (1 << 1) | (1 << 2)));
        assertFalse(TacticalPuzzleSpecValidator.isValidTagMask(1 << 6));

        var norm = TacticalPuzzleSpecValidator.normalizeAndValidateText("  两回合破盾反杀  ", "  先驱散再点杀 ");
        assertNotNull(norm);
        assertEquals("两回合破盾反杀", norm.title());
        assertEquals("先驱散再点杀", norm.hint());
        assertNull(TacticalPuzzleSpecValidator.normalizeAndValidateText("短名", ""));
        assertNull(TacticalPuzzleSpecValidator.normalizeAndValidateText("非法\n换行", ""));

        long s1 = TacticalPuzzleSpecValidator.deterministicSeed(88001L, 1);
        long s2 = TacticalPuzzleSpecValidator.deterministicSeed(88001L, 1);
        long s3 = TacticalPuzzleSpecValidator.deterministicSeed(88001L, 2);
        assertNotEquals(0L, s1);
        assertEquals(s1, s2);
        assertNotEquals(s1, s3);

        var honor = TacticalPuzzleSpecValidator.deriveHonor(3, 12, 20);
        assertEquals(4, honor.tier());
        assertEquals("奇局宗师", honor.title());

        TacticalPuzzleBattleContext verifyCtx = TacticalPuzzleBattleContext.newBuilder()
                .setPuzzleId(88001L).setSpecRevision(1).setIsAuthorVerify(true).build();
        TacticalPuzzleBattleContext challengeCtx = TacticalPuzzleBattleContext.newBuilder()
                .setPuzzleId(88001L).setSpecRevision(1).setIsAuthorVerify(false).build();
        assertTrue(BattleReplayPolicy.decideForBattle(true, MatchMode.MATCH_MODE_PVE_SOLO, null, verifyCtx));
        assertFalse(BattleReplayPolicy.decideForBattle(true, MatchMode.MATCH_MODE_PVE_SOLO, null, challengeCtx));
        assertFalse(BattleReplayPolicy.decideForBattle(false, MatchMode.MATCH_MODE_PVE_SOLO, null, verifyCtx));

        assertTrue(MatchMethods.ALL.contains(MatchMethods.LIST_TACTICAL_PUZZLES));
        assertTrue(MatchMethods.ALL.contains(MatchMethods.GET_TACTICAL_PUZZLE_SOLUTION_REPLAY));
    }
}
