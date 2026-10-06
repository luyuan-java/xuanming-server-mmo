package com.game.match.activity;

import com.game.api.match.MatchBudgets;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.StartActivityBattleRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 帮会活动开战请求的参数校验（match-spec §7.1 第 2 步；基线 {@code validateStartActivityBattle}，{@code act.go:170-203}）。纯函数。
 * 任一条不过，入口回 {@code INVALID_ARGUMENT}、offender = 0，不读任何依赖：
 * <ul>
 *   <li>名单 1..{@value MatchBudgets#MAX_TEAM_SIZE} 人、不含 0、不重复；</li>
 *   <li>{@code battle_config_id ≠ 0}；</li>
 *   <li>活动上下文非空；{@code kind} 是契约里已知的值且不是 NONE——proto3 把未知的枚举数值读成 {@code UNRECOGNIZED}，
 *       所以一律按 {@code getKindValue()} 的数值判，未知值同样拒绝；</li>
 *   <li>{@code guild_id} / {@code activity_id} / {@code period_key} / {@code guild_period_key} 都不为 0；</li>
 *   <li>发起人就是名单第一个人（站位顺序即名单顺序，发起人在首位）。</li>
 * </ul>
 * 判定先后同基线（只影响日志里的原因，不影响应答）。不复核帮会归属——名单是 guild 邀请房间里全员已同意的成员，由调用方负责。
 */
public final class ActivityRequestValidator {

    private ActivityRequestValidator() {
    }

    /** @return null = 合法；否则是写进日志的原因 */
    public static String invalidReason(StartActivityBattleRequest request) {
        List<Long> members = request.getMemberPlayerIdsList();
        if (members.isEmpty() || members.size() > MatchBudgets.MAX_TEAM_SIZE) {
            return "人数越界";
        }
        Set<Long> seen = new HashSet<>();
        for (long playerId : members) {
            if (playerId == 0) {
                return "名单含 0";
            }
            if (!seen.add(playerId)) {
                return "名单重复";
            }
        }
        if (request.getBattleConfigId() == 0) {
            return "battle_config_id 为 0";
        }
        if (!request.hasActivityContext()) {
            return "缺少活动上下文";
        }
        BattleActivityContext context = request.getActivityContext();
        eBattleActivityKind kind = eBattleActivityKind.forNumber(context.getKindValue());
        if (kind == null || kind == eBattleActivityKind.BATTLE_ACTIVITY_KIND_NONE) {
            return "活动类型为 NONE 或未知";
        }
        if (context.getGuildId() == 0 || context.getActivityId() == 0 || context.getPeriodKey() == 0 || context.getGuildPeriodKey() == 0) {
            return "活动上下文字段为 0";
        }
        if (context.getInitiatorPlayerId() != members.get(0)) {
            return "发起人不在名单首位";
        }
        return null;
    }
}
