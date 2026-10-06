package com.game.match.lifecycle;

import com.game.api.match.MatchBudgets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * 启动门禁里要「对照别处」才能判的几项（match-spec §9.8 第 3、4 步；只看 {@code xm.match.*} 自身的校验在 {@code MatchProperties} 的构造器里）：
 * <ol>
 *   <li><b>PVE 组队人数表的副本 id 必须在 Dungeon 表里</b>（M21）：写错的 id 不会报任何错，只是那个副本永远「未开放组队」，而运维以为开了。</li>
 *   <li><b>预算断言</b>（§9.6 末、§10.3）：matched 票据 TTL（42 / 48 / 54 / 60 / 66 / 96 s，客户端在 match 崩溃时可见，也是下发给 scene 的备战期限）
 *       是按「备战 / 取消 3 s、建房 5 s、销毁 3 s、清退观众 3 s、落点记录写入最坏 6.1 s」推出来的。Redis 的超时与重试是部署配置
 *       （{@code xm.redis.*}），单条命令的最坏耗时一旦超过 6.1 s，落点写入就可能把 gather 拖出 TTL 预算；各跳超时是代码常量，这里再核一遍
 *       是防有人改了 {@link MatchBudgets} 的某一跳却没有重算整张表（§12.1 第 3 条）。</li>
 *   <li><b>推出来的表仍是基线的值</b>：各跳都没超上限也可能有人把某一跳改小了；matched TTL 表与「开战锁 ≤ EndMatch 截止」再对一遍。</li>
 * </ol>
 * 每项都是纯函数：返回违规清单，空 = 通过；{@link #verify} 把清单拼成一条异常（一次列全，免得改一处重启一次）。
 */
public final class MatchStartupChecks {

    /** 备战 / 取消、销毁、补签、清退观众各跳的上限（毫秒）：matched TTL 公式按它们标定。 */
    static final long MAX_SHORT_HOP_MS = 3_000;
    /** 建房一跳的上限（毫秒）。 */
    static final long MAX_CREATE_HOP_MS = 5_000;

    private MatchStartupChecks() {
    }

    /**
     * 「门禁已过」的凭据：{@code MatchConfiguration} 把它声明成 bean，后面的步骤（申领发号租约等）以它为参数，Spring 就一定先查完再往下走。
     *
     * @param redisWorstCaseMillis 核对时用的 Redis 单条命令最坏耗时（启动日志用）
     * @param pveTeamConfigs       通过核对的 PVE 组队副本个数
     */
    public record Passed(long redisWorstCaseMillis, int pveTeamConfigs) {
    }

    /**
     * gather 各跳的调用超时（毫秒）。生产取 {@link #current()}（{@link MatchBudgets} 的常量）；拆成参数只为了能测这条门禁本身。
     *
     * @param prepareBattle  {@code SceneBattleService.prepareBattle}
     * @param cancelPrepare  {@code SceneBattleService.cancelBattlePrepare}
     * @param createBattle   {@code BattleNodeService.createBattle}
     * @param destroyBattle  {@code BattleNodeService.destroyBattle}
     * @param issueTicket    {@code BattleNodeService.issueBattleTicket}
     * @param removeObserver 开局前清退观众（6.5）
     */
    public record Hops(long prepareBattle, long cancelPrepare, long createBattle, long destroyBattle, long issueTicket, long removeObserver) {

        public static Hops current() {
            return new Hops(MatchBudgets.PREPARE_BATTLE_TIMEOUT_MS, MatchBudgets.CANCEL_PREPARE_TIMEOUT_MS, MatchBudgets.CREATE_BATTLE_TIMEOUT_MS,
                    MatchBudgets.DESTROY_BATTLE_TIMEOUT_MS, MatchBudgets.ISSUE_TICKET_TIMEOUT_MS, MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS);
        }
    }

    /**
     * 全部都查，有任何违规就抛。
     *
     * @param pveTeamSizes         {@code xm.match.pve-team-size-by-config-id}（值 ≥ 1 已由 {@code MatchProperties} 校验）
     * @param dungeonExists        Dungeon 表里有没有这个 id
     * @param redisWorstCaseMillis {@code RedisProperties.worstCaseCommandMillis()}
     * @return 通过的凭据
     * @throws IllegalStateException 有违规：拒绝启动，消息里列出全部违规
     */
    public static Passed verify(Map<Integer, Integer> pveTeamSizes, IntPredicate dungeonExists, long redisWorstCaseMillis) {
        List<String> violations = new ArrayList<>(teamSizeViolations(pveTeamSizes, dungeonExists));
        violations.addAll(budgetViolations(redisWorstCaseMillis, Hops.current()));
        violations.addAll(ttlTableViolations(MatchBudgets::matchedTicketTtlSeconds, MatchBudgets::teamMatchLockSeconds));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("xm-match 启动门禁未通过，拒绝启动：" + String.join("；", violations));
        }
        return new Passed(redisWorstCaseMillis, pveTeamSizes.size());
    }

    /** PVE 组队人数表里不在 Dungeon 表中的副本 id（每个一条违规，按 id 升序）。 */
    public static List<String> teamSizeViolations(Map<Integer, Integer> pveTeamSizes, IntPredicate dungeonExists) {
        List<String> violations = new ArrayList<>();
        pveTeamSizes.keySet().stream().sorted().forEach(configId -> {
            if (!dungeonExists.test(configId)) {
                violations.add("xm.match.pve-team-size-by-config-id 的副本 id " + configId + " 不在 Dungeon 表里（写错的 id 只会让那个副本一直「未开放组队」）");
            }
        });
        return violations;
    }

    /**
     * 预算断言的违规清单。
     *
     * @param redisWorstCaseMillis 一条 Redis 命令最坏阻塞多久（含 Redisson 自己的重试）
     * @param hops                 各跳的调用超时
     */
    public static List<String> budgetViolations(long redisWorstCaseMillis, Hops hops) {
        List<String> violations = new ArrayList<>();
        if (redisWorstCaseMillis > MatchBudgets.PLACEMENT_WRITE_WORST_MS) {
            violations.add("Redis 单条命令的最坏耗时 " + redisWorstCaseMillis + " ms 超过落点记录写入的上限 " + MatchBudgets.PLACEMENT_WRITE_WORST_MS
                    + " ms（(retry-attempts + 1) × timeout-ms + retry-attempts × retry-delay-ms；调小 xm.redis.timeout-ms / retry-attempts，"
                    + "否则 gather 可能被拖出 matched 票据的 TTL）");
        }
        requireAtMost(violations, "备战 prepareBattle", hops.prepareBattle(), MAX_SHORT_HOP_MS);
        requireAtMost(violations, "取消备战 cancelBattlePrepare", hops.cancelPrepare(), MAX_SHORT_HOP_MS);
        requireAtMost(violations, "建房 createBattle", hops.createBattle(), MAX_CREATE_HOP_MS);
        requireAtMost(violations, "销毁 destroyBattle", hops.destroyBattle(), MAX_SHORT_HOP_MS);
        requireAtMost(violations, "补签 issueBattleTicket", hops.issueTicket(), MAX_SHORT_HOP_MS);
        requireAtMost(violations, "清退观众 removeObserver", hops.removeObserver(), MAX_SHORT_HOP_MS);
        return violations;
    }

    /** matched TTL 表的基线值（人数 → 秒，§3.4）：客户端可见，也是 scene 备战冻结的作废期限。 */
    static final int[][] BASELINE_MATCHED_TTL = {{1, 42}, {2, 48}, {3, 54}, {4, 60}, {5, 66}, {10, 96}};

    /**
     * 由各跳超时推出来的两张表仍是基线的值、跨进程不等式仍成立（§10.3）：各跳都没超上限也可能有人把某一跳改<b>小</b>了，表就不再与基线逐字相同。
     *
     * @param matchedTtlSeconds 人数 → matched TTL（生产为 {@link MatchBudgets#matchedTicketTtlSeconds}）
     * @param teamLockSeconds   人数 → 开战锁时长（生产为 {@link MatchBudgets#teamMatchLockSeconds}）
     */
    public static List<String> ttlTableViolations(IntUnaryOperator matchedTtlSeconds, IntUnaryOperator teamLockSeconds) {
        List<String> violations = new ArrayList<>();
        for (int[] row : BASELINE_MATCHED_TTL) {
            int actual = matchedTtlSeconds.applyAsInt(row[0]);
            if (actual != row[1]) {
                violations.add(row[0] + " 人的 matched TTL 是 " + actual + " s，基线是 " + row[1] + " s");
            }
        }
        int longest = matchedTtlSeconds.applyAsInt(MatchBudgets.MAX_GATHER_PLAYERS);
        if (longest != MatchBudgets.MAX_MATCHED_TTL_SECONDS) {
            violations.add("最长的 matched TTL " + longest + " s 与 MAX_MATCHED_TTL_SECONDS = " + MatchBudgets.MAX_MATCHED_TTL_SECONDS
                    + " 不一致（battle 的确认补发窗口按后者核对）");
        }
        int teamLock = teamLockSeconds.applyAsInt(MatchBudgets.MAX_TEAM_SIZE);
        if (teamLock > MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS) {
            violations.add(MatchBudgets.MAX_TEAM_SIZE + " 人的开战锁 " + teamLock + " s 超过 xm-team EndMatch 的截止 "
                    + MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS + " s");
        }
        return violations;
    }

    private static void requireAtMost(List<String> violations, String hop, long actualMs, long maxMs) {
        if (actualMs <= 0 || actualMs > maxMs) {
            violations.add(hop + " 的调用超时 " + actualMs + " ms 不在 (0, " + maxMs + "] 内（改任何一跳都要重算 MatchBudgets 整张表并核对跨进程不等式）");
        }
    }
}
