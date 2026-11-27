package com.game.match.spectate;

import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.eSpectateChatPreference;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.MatchMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「本场战斗是否开放局外人观战交流」的唯一权威决策表（与 Go 版 {@code spectate_chat_policy.go} 逐条同构）。
 *
 * <p>核心原则：
 * <ul>
 *   <li>严禁与 {@link SpectateVisibilityPolicy} 混淆：关闭本开关仅禁止观战期间交流，绝不影响可观战性；</li>
 *   <li>建房时按所有真人参战者的个人设置一次性冻结进 {@code CreateBattleRequest.spectate_chat_enabled}，局中不可切换；</li>
 *   <li>私密/不可观战局或总开关关闭一律返回 {@code false}；PVP 默认采用「全员一票否决」（任意真人选禁交流则整局禁交流，AI 忽略）；</li>
 *   <li>新增 {@link MatchMode} 必须在 {@link #BY_MODE} 显式表态，由单测穷举枚举守门。</li>
 * </ul>
 */
public final class SpectateChatPolicy {

    /** 建房期冻结策略开关（缺省全 false = 生产默认：总开关启用、未设置默认允许、PVP 全员一票否决、锦标赛按队伍规则）。 */
    public record Config(
            boolean disabled,
            boolean defaultForbidWhenUnset,
            boolean teamPvpCaptainOnly,
            boolean tournamentDisabled) {
        public static final Config DEFAULT = new Config(false, false, false, false);
    }

    enum Rule {
        DISABLED,
        SOLO_PLAYER,
        PVE_CAPTAIN,
        PVP_VETO,
        PVP_TEAM,
        TOURNAMENT
    }

    private static final Map<MatchMode, Rule> BY_MODE = decisionTable();

    private SpectateChatPolicy() {
    }

    private static Map<MatchMode, Rule> decisionTable() {
        Map<MatchMode, Rule> table = new EnumMap<>(MatchMode.class);
        table.put(MatchMode.MATCH_MODE_UNSPECIFIED, Rule.DISABLED);
        table.put(MatchMode.MATCH_MODE_PVE_SOLO, Rule.SOLO_PLAYER);
        table.put(MatchMode.MATCH_MODE_PVE_TEAM, Rule.PVE_CAPTAIN);
        table.put(MatchMode.MATCH_MODE_1V1, Rule.PVP_VETO);
        table.put(MatchMode.MATCH_MODE_PVP_CHALLENGE, Rule.PVP_VETO);
        table.put(MatchMode.MATCH_MODE_JIANGHU_DUEL, Rule.PVP_VETO);
        table.put(MatchMode.MATCH_MODE_3V3, Rule.PVP_TEAM);
        table.put(MatchMode.MATCH_MODE_5V5, Rule.PVP_TEAM);
        table.put(MatchMode.MATCH_MODE_PVP_TEAM, Rule.PVP_TEAM);
        table.put(MatchMode.MATCH_MODE_POLARITY_5V5, Rule.PVP_TEAM);
        table.put(MatchMode.MATCH_MODE_GUILD_WAR_PVP, Rule.PVP_TEAM);
        table.put(MatchMode.MATCH_MODE_GUILD_WAR_SIEGE, Rule.PVP_TEAM);
        table.put(MatchMode.MATCH_MODE_SEASON_TOURNAMENT, Rule.TOURNAMENT);
        return Collections.unmodifiableMap(table);
    }

    /** 显式表态过的 MatchMode 集合（测试穷举守门用）。 */
    static Set<MatchMode> decidedModes() {
        return BY_MODE.keySet();
    }

    /** 解析单个真人快照的偏好：明确 ALLOW/FORBID 按本人，UNSPECIFIED/未知按默认配置。 */
    public static boolean preferenceAllows(eSpectateChatPreference pref, Config cfg) {
        Config effective = cfg == null ? Config.DEFAULT : cfg;
        if (pref == eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW) {
            return true;
        }
        if (pref == eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID) {
            return false;
        }
        return !effective.defaultForbidWhenUnset();
    }

    /** 建房期冻结决策入口：纯函数、无状态、线程安全。 */
    public static boolean decide(MatchMode mode, eSpectateVisibility visibility,
                                 List<BattlePlayerSnapshot> players, Config cfg) {
        Config effective = cfg == null ? Config.DEFAULT : cfg;
        if (effective.disabled() || !SpectateVisibilityPolicy.isPublic(visibility) || mode == null) {
            return false;
        }
        Rule rule = BY_MODE.getOrDefault(mode, Rule.DISABLED);
        if (rule == Rule.DISABLED) {
            return false;
        }
        if (rule == Rule.TOURNAMENT && effective.tournamentDisabled()) {
            return false;
        }
        List<BattlePlayerSnapshot> humans = filterHumans(players);
        if (humans.isEmpty()) {
            return false;
        }
        return switch (rule) {
            case SOLO_PLAYER -> preferenceAllows(humans.get(0).getSpectateChatPref(), effective);
            case PVE_CAPTAIN -> {
                BattlePlayerSnapshot captain = findCaptainOrFirst(humans);
                yield preferenceAllows(captain.getSpectateChatPref(), effective);
            }
            case PVP_VETO -> allAllow(humans, effective);
            case PVP_TEAM, TOURNAMENT -> effective.teamPvpCaptainOnly()
                    ? allTeamCaptainsAllow(humans, effective)
                    : allAllow(humans, effective);
            case DISABLED -> false;
        };
    }

    private static List<BattlePlayerSnapshot> filterHumans(List<BattlePlayerSnapshot> players) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        return players.stream()
                .filter(p -> p != null && p.getPlayerId() != 0L && !p.getAiControlled())
                .toList();
    }

    private static BattlePlayerSnapshot findCaptainOrFirst(List<BattlePlayerSnapshot> humans) {
        for (BattlePlayerSnapshot p : humans) {
            if (p.getIsTeamCaptain()) {
                return p;
            }
        }
        return humans.get(0);
    }

    private static boolean allAllow(List<BattlePlayerSnapshot> humans, Config cfg) {
        for (BattlePlayerSnapshot p : humans) {
            if (!preferenceAllows(p.getSpectateChatPref(), cfg)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allTeamCaptainsAllow(List<BattlePlayerSnapshot> humans, Config cfg) {
        Map<Integer, BattlePlayerSnapshot> representative = new LinkedHashMap<>();
        for (BattlePlayerSnapshot p : humans) {
            int team = p.getTeamIndex();
            BattlePlayerSnapshot existing = representative.get(team);
            if (existing == null || (!existing.getIsTeamCaptain() && p.getIsTeamCaptain())) {
                representative.put(team, p);
            }
        }
        for (BattlePlayerSnapshot rep : representative.values()) {
            if (!preferenceAllows(rep.getSpectateChatPref(), cfg)) {
                return false;
            }
        }
        return !representative.isEmpty();
    }
}
