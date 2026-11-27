package com.game.match.report;

import com.game.proto.match.MatchMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 「这局要不要录战报回放」的唯一策略表（与 Go {@code go/match/internal/logic/battle_replay_policy.go} 同口径）：
 * 建房时调用一次 {@link #decide}，结果冻结进 {@code CreateBattleRequest.record_replay}；默认拒绝，只录 PVP。
 *
 * <p>新增 {@link MatchMode} 必须在 {@link #BY_MODE} 里显式表态，{@code BattleReplayPolicyTest} 遍历枚举钉住。
 */
public final class BattleReplayPolicy {

    private static final Map<MatchMode, Boolean> BY_MODE = decisionTable();

    private BattleReplayPolicy() {
    }

    private static Map<MatchMode, Boolean> decisionTable() {
        Map<MatchMode, Boolean> table = new EnumMap<>(MatchMode.class);
        table.put(MatchMode.MATCH_MODE_UNSPECIFIED, Boolean.FALSE);
        table.put(MatchMode.MATCH_MODE_5V5, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_3V3, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_1V1, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_PVE_SOLO, Boolean.FALSE);
        table.put(MatchMode.MATCH_MODE_PVE_TEAM, Boolean.FALSE);
        table.put(MatchMode.MATCH_MODE_PVP_CHALLENGE, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_PVP_TEAM, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_POLARITY_5V5, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_GUILD_WAR_PVP, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_GUILD_WAR_SIEGE, Boolean.FALSE);
        table.put(MatchMode.MATCH_MODE_SEASON_TOURNAMENT, Boolean.TRUE);
        table.put(MatchMode.MATCH_MODE_JIANGHU_DUEL, Boolean.TRUE);
        return Collections.unmodifiableMap(table);
    }

    /** 建房时判定是否录制战报回放（缺省开关打开）。 */
    public static boolean decide(MatchMode mode) {
        return decide(true, mode);
    }

    /** 建房时判定是否录制战报回放；全局开关关闭、{@code null} 或未知枚举值一律 false。 */
    public static boolean decide(boolean enabled, MatchMode mode) {
        return enabled && mode != null && Boolean.TRUE.equals(BY_MODE.get(mode));
    }

    /** 建房期最终录制入口（缺省开关打开）：在既有 PVP 基线之上，仅对五行试炼志有格 PVE 受控开启录制。 */
    public static boolean decideForBattle(MatchMode mode, com.game.proto.TrialBattleContext trialCtx) {
        return decideForBattle(true, mode, trialCtx);
    }

    /** 建房期最终录制入口（对齐 Go {@code recordReplayForBattle}）：普通刷怪/爬塔/历练 PVE 保持关闭。 */
    public static boolean decideForBattle(boolean enabled, MatchMode mode, com.game.proto.TrialBattleContext trialCtx) {
        return decideForBattle(enabled, mode, trialCtx, null);
    }

    /** 建房期最终录制入口（含万象残局坊作者自证验题局）：仅作者自证开启录播，普通挑战者局保持关闭。 */
    public static boolean decideForBattle(MatchMode mode,
                                          com.game.proto.TrialBattleContext trialCtx,
                                          com.game.proto.TacticalPuzzleBattleContext puzzleCtx) {
        return decideForBattle(true, mode, trialCtx, puzzleCtx);
    }

    public static boolean decideForBattle(boolean enabled,
                                          MatchMode mode,
                                          com.game.proto.TrialBattleContext trialCtx,
                                          com.game.proto.TacticalPuzzleBattleContext puzzleCtx) {
        if (!enabled) {
            return false;
        }
        if (decide(true, mode)) {
            return true;
        }
        if ((mode == MatchMode.MATCH_MODE_PVE_SOLO || mode == MatchMode.MATCH_MODE_PVE_TEAM)
                && trialCtx != null && trialCtx.getJournalEntryId() != 0) {
            return true;
        }
        return mode == MatchMode.MATCH_MODE_PVE_SOLO
                && puzzleCtx != null && puzzleCtx.getIsAuthorVerify();
    }

    /** 决策表里显式表态过的模式（只读；测试据此钉住「每个枚举都在表里」）。 */
    static Set<MatchMode> decidedModes() {
        return BY_MODE.keySet();
    }
}
