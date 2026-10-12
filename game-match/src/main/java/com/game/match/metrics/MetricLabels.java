package com.game.match.metrics;

import com.game.match.dispatch.MatchMethods;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.MatchMode;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * 指标标签的净化（match-spec §11「标签净化」，修基线 B2）：把客户端能控制的值收敛到有界的集合里，再作标签。
 * <ul>
 *   <li>{@code mode}：只取契约里已知的 {@code MatchMode} 枚举名（与基线 {@code in.Mode.String()} 对已知值的输出相同，如 {@code MATCH_MODE_1V1}），
 *       其余记 {@value #UNKNOWN}——基线对未知值会输出数字串，客户端可以借此造出任意多个标签值；</li>
 *   <li>{@code config}：只取 {@code 0} 或 Dungeon 表里存在的 id，其余记 {@value #OTHER}——{@code battle_config_id} 不校验（照搬基线，客户端可见），
 *       任何值都能开一条队列，但不能让它进标签；</li>
 *   <li>{@code method}：只取 {@link MatchMethods#ALL}，其余 {@value #UNKNOWN}；{@code kind}：活动类型只取 guild_trial / none / unknown。</li>
 * </ul>
 * 玩家号、战斗号、切磋号、队伍号、zone 一律不作标签（AGENTS.md §5）。不可变、线程安全。
 */
public final class MetricLabels {

    /** 契约里没有的模式 / 方法 / 活动类型。 */
    public static final String UNKNOWN = "unknown";
    /** 不是 0 也不在 Dungeon 表里的副本号。 */
    public static final String OTHER = "other";

    private final IntPredicate dungeonExists;

    /** @param dungeonExists Dungeon 表里有没有这个 id（生产为 {@code configTables.dungeon()::contains}） */
    public MetricLabels(IntPredicate dungeonExists) {
        this.dungeonExists = Objects.requireNonNull(dungeonExists, "dungeonExists");
    }

    /** {@code mode} 标签：已知模式的枚举名（含 {@code MATCH_MODE_UNSPECIFIED}），其余 {@value #UNKNOWN}。 */
    public static String mode(int modeValue) {
        MatchMode mode = MatchMode.forNumber(modeValue);
        return mode == null ? UNKNOWN : mode.name();
    }

    /** {@code mode} 标签（枚举形式；{@code UNRECOGNIZED} 与 null 记 {@value #UNKNOWN}）。 */
    public static String mode(MatchMode mode) {
        return mode == null || mode == MatchMode.UNRECOGNIZED ? UNKNOWN : mode.name();
    }

    /**
     * {@code config} 标签：{@code "0"}、Dungeon 表里存在的 id 的十进制、或 {@value #OTHER}。
     *
     * @param configId {@code battle_config_id}（uint32 的位模式；≥ 2^31 的值不可能在表里）
     */
    public String config(int configId) {
        if (configId == 0) {
            return "0";
        }
        return configId > 0 && dungeonExists.test(configId) ? Integer.toString(configId) : OTHER;
    }

    /** {@code method} 标签：{@code MatchService} 的方法名，其余 {@value #UNKNOWN}。 */
    public static String method(String method) {
        return method != null && MatchMethods.ALL.contains(method) ? method : UNKNOWN;
    }

    /**
     * 活动类型标签（基线 {@code activityKindLabel}，{@code act.go:340-348}）：已知值取枚举名去掉 {@code BATTLE_ACTIVITY_KIND_} 前缀后小写
     * （{@code guild_trial} / {@code none}），未知值 {@value #UNKNOWN}。
     *
     * @param kindValue {@code BattleActivityContext.kind} 的数值（用 {@code getKindValue()} 取：proto3 把未知枚举读成 {@code UNRECOGNIZED}）
     */
    public static String activityKind(int kindValue) {
        eBattleActivityKind kind = eBattleActivityKind.forNumber(kindValue);
        if (kind == null) {
            return UNKNOWN;
        }
        String name = kind.name();
        String prefix = "BATTLE_ACTIVITY_KIND_";
        return (name.startsWith(prefix) ? name.substring(prefix.length()) : name).toLowerCase(Locale.ROOT);
    }
}
