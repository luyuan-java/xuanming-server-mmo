package com.game.api.match;

/**
 * 匹配开局管线的各跳超时与由它们推出的时限（match-spec §3.4、§9.1、§10.3；基线 {@code go/match/internal/logic/gather.go:26-30}、
 * {@code queue.go:340-386}、{@code spectate.go:33-83}、{@code team_battle.go:60-66}）。纯函数与常量，放在 xm-api：
 * xm-match（gather、票据 TTL、6.5 的观战）、xm-team（开战锁、EndMatch 截止）、xm-battle（确认补发窗口的单测）都引用同一份。
 *
 * <p><b>这些值不开放配置</b>（基线是 yaml 可调的）：它们出现在跨进程不等式里（{@link #MAX_MATCHED_TTL_SECONDS} + scene 锁余量 ≤ battle 确认补发窗口、
 * {@link #teamMatchLockSeconds} ≤ {@link #TEAM_END_MATCH_DEADLINE_SECONDS} 等，§10.3），而且备战期限在 PrepareBattle 时就下发给了 scene、事后改不了——
 * 改任何一跳都要重算整张表并核对那几条不等式（§12.1 第 3 条）。数值与基线逐个相同：1 / 2 / 3 / 4 / 5 / 10 人的 matched TTL 是
 * 42 / 48 / 54 / 60 / 66 / 96 s（客户端在 match 实例崩溃时可见：MATCHED 最长保持这么久）。
 *
 * <p>公式一律用整数毫秒算、最后向上取整到秒，不用浮点（基线的 22.2 s 是 float64）。
 */
public final class MatchBudgets {

    // ---------------------------------------------------------------- 各跳超时（毫秒）

    /**
     * {@code SceneBattleService.prepareBattle} 的单次超时。<b>保持 3 s</b>（2026-10-06 裁决）：它小于 scene 侧一条写锁脚本的最坏耗时（约 4.2 s），
     * 所以超时<b>不等于</b>失败——按「结局不明」处理，补偿时对这名玩家也发取消（match-spec §9.6 末条、M14）。
     */
    public static final long PREPARE_BATTLE_TIMEOUT_MS = 3_000;
    /** {@code SceneBattleService.cancelBattlePrepare} 的单次超时（补偿，每人一次）；超时只记日志，由 scene 的 reaper 与锁 TTL 收尾。 */
    public static final long CANCEL_PREPARE_TIMEOUT_MS = 3_000;
    /** {@code BattleNodeService.createBattle} 的单次超时；超时 = 结局不明，必须先 destroy 成功才能解冻。 */
    public static final long CREATE_BATTLE_TIMEOUT_MS = 5_000;
    /** {@code BattleNodeService.destroyBattle} 的单次超时（建房结局不明时的回滚）。 */
    public static final long DESTROY_BATTLE_TIMEOUT_MS = 3_000;
    /** {@code BattleNodeService.issueBattleTicket} 的单次超时（179 补签）。 */
    public static final long ISSUE_TICKET_TIMEOUT_MS = 3_000;
    /**
     * 清退观众（{@code BattleNodeService.removeObserver}）的单次上限，也是开局前清退<b>每人</b>的总上限——matched TTL 公式里的那一项就是它，
     * <b>不能改大</b>（spectate-spec §4.6、§5.3）：6.5 的开局清退给每名有观战标记的成员一个这么长的截止，读落点与这一次 RPC 都算在里面。
     */
    public static final long REMOVE_OBSERVER_TIMEOUT_MS = 3_000;
    /**
     * 登记观众（{@code BattleNodeService.addObserver}，163）的单次上限：与清退相同（基线 Add / Remove 都是 3 s，{@code spectate.go:32-36}）；
     * 163 再按剩余请求预算收短（见 {@link #WATCH_ADD_RESERVE_MS}）。
     */
    public static final long ADD_OBSERVER_TIMEOUT_MS = 3_000;
    /** 建票 Redis 出错后按 id 逐个回滚票据的独立预算（不受请求截止约束，基线 {@code act.go:45-46}）。 */
    public static final long TICKET_ROLLBACK_BUDGET_MS = 3_000;

    /** 落点记录一次写入的最坏耗时（基线 2 × 3 s + 0.1 s）：Java 是一次 Redisson 调用、外层用它作截止。 */
    public static final long PLACEMENT_WRITE_WORST_MS = 6_100;
    /**
     * 建房阶段的最坏耗时 = 2 ×（落点写入 + 建房）= 22.2 s：首选节点一次、换节点重试一次（只重试一次，§12.1 第 6 条）。
     * 6.5 判断「落点已写、房间可能还在建」的窗口也用它。
     */
    public static final long GATHER_CREATE_STAGE_WORST_MS = 2 * (PLACEMENT_WRITE_WORST_MS + CREATE_BATTLE_TIMEOUT_MS);

    // ---------------------------------------------------------------- 时限（秒）

    /** matched TTL 的下限（基线 {@code MatchedTicketTTLSeconds = 30}）：公式的最小值是 42，这个下限永远不起作用，只为与基线公式逐项对应。 */
    public static final int MATCHED_TTL_FLOOR_SECONDS = 30;
    /** matched TTL 公式里给 Redis 小操作（读位置、推进 ready 等）留的余量。 */
    public static final int MATCHED_TTL_MARGIN_SECONDS = 10;
    /** 补偿续期的余量、开战锁在 matched + 补偿之外的余量（基线都是 10）。 */
    public static final int COMPENSATION_MARGIN_SECONDS = 10;
    public static final int TEAM_LOCK_MARGIN_SECONDS = 10;

    /** 一队的人数上限（基线 {@code kMaxBattleTeamSize}）：PVE 组队人数按它收口，活动开战名单 1..5 人。 */
    public static final int MAX_TEAM_SIZE = 5;
    /** 一次 gather 的人数上限（5V5 = 10 人）：matched TTL 的最大值按它算。 */
    public static final int MAX_GATHER_PLAYERS = 10;

    /** 最长的 matched TTL（10 人）= scene PREPARING 冻结的最晚作废期限。battle 的确认补发窗口必须 ≥ 它 + scene 锁余量 60 s。 */
    public static final int MAX_MATCHED_TTL_SECONDS = 96;

    /** 战斗最长时限：177 的 {@code expire_at_ms} = gather 起点 + 它（客户端可见）。 */
    public static final int BATTLE_MAX_DURATION_SECONDS = 300;
    /** 落点记录的 TTL = 战斗最长时限 + 60 s。 */
    public static final int PLACEMENT_TTL_SECONDS = BATTLE_MAX_DURATION_SECONDS + 60;

    /** xm-team EndMatch 的单调截止（从 gather 结束起算）：必须 ≥ 最长的开战锁（{@link #teamMatchLockSeconds}(5) = 101）。 */
    public static final int TEAM_END_MATCH_DEADLINE_SECONDS = 110;

    /**
     * 整请求预算的缺省值（毫秒）：gate 调 match 的 Dubbo 超时 5 s − 500（基线 zrpc 5000 − 500）。{@code xm-budget-ms} 附件缺失时提供方按它算本地截止。
     */
    public static final long DEFAULT_REQUEST_BUDGET_MS = 4_500;

    // ---------------------------------------------------------------- 观战（批次 6.5，spectate-spec §5.1、§5.3）

    /** 观战标记的 TTL = 落点记录的 TTL：标记到期时那一场必已按期限收尾（360 ≥ 300）。 */
    public static final int WATCHING_TTL_SECONDS = PLACEMENT_TTL_SECONDS;
    /**
     * 可观战索引的过期分界（毫秒）：成员的分数（{@code created_at_ms}）早于「Redis 时间 − 它」即是残留——读路径（随机选场、列表）与清扫同一个口径。
     * 等于落点记录的 TTL。
     */
    public static final long SPECTATE_STALE_MS = PLACEMENT_TTL_SECONDS * 1_000L;
    /** 164 的缺省条数（{@code limit = 0}）与上限（客户端可见，同基线 {@code listwatchablebattleslogic.go:15-18}）。 */
    public static final int WATCHABLE_LIST_DEFAULT = 20;
    public static final int WATCHABLE_LIST_MAX = 50;
    /** 随机观战（163 的 {@code battle_id = 0}）最多选几轮场、每轮最多挑几次（客户端可见，同基线 2 / 3）。指定场只有一轮。 */
    public static final int RANDOM_WATCH_ROUNDS = 2;
    public static final int RANDOM_PICK_TRIES = 3;
    /**
     * 163 换场时，同步的 RemoveObserver 必须在「请求截止 − 它」之前返回（硬截止），给后面的选场与 AddObserver 留出时间。
     * 换场前剩余预算不足「它 + {@link #WATCH_ADD_MIN_BUDGET_MS}」时什么都不做、直接回 16004（旧标记原样保留）。
     */
    public static final long WATCH_REWATCH_RESERVE_MS = 1_200;
    /** 163 的 AddObserver 必须在「请求截止 − 它」之前返回（硬截止）：留给成功之后的复查（并行读票据与战斗锁）与回包。 */
    public static final long WATCH_ADD_RESERVE_MS = 200;
    /** 发 AddObserver 之前剩余预算的下限：不足就回滚标记、回 16004，不去发一个注定超时的调用。 */
    public static final long WATCH_ADD_MIN_BUDGET_MS = 1_000;
    /**
     * 停机时等在途 163 的上限 = 整请求预算 + 500：一次 163 自己至多跑到它的预算到点。与排空 {@code match-worker}（至多 10 s）<b>并行</b>进行，
     * 不加长停机序列。
     */
    public static final long SPECTATE_DRAIN_TIMEOUT_MS = DEFAULT_REQUEST_BUDGET_MS + 500;

    private MatchBudgets() {
    }

    /**
     * {@code n} 人一组的 matched 票据 TTL（秒），也是下发给 scene 的 {@code prepare_deadline_ms} 的相对值
     * （基线 {@code matchedTicketTTLFor}，{@code queue.go:367-380}）：
     * <pre>max(30, ⌈n ×（清退观众 3 s + 备战 3 s）+ 建房阶段 22.2 s + 回滚 3 s⌉ + 10)</pre>
     * 1 / 2 / 3 / 4 / 5 / 10 人 → 42 / 48 / 54 / 60 / 66 / 96。
     *
     * @throws IllegalArgumentException {@code n} 不在 [1, {@value #MAX_GATHER_PLAYERS}] 内
     */
    public static int matchedTicketTtlSeconds(int n) {
        requirePlayers(n, MAX_GATHER_PLAYERS);
        long worstMs = n * (REMOVE_OBSERVER_TIMEOUT_MS + PREPARE_BATTLE_TIMEOUT_MS) + GATHER_CREATE_STAGE_WORST_MS + DESTROY_BATTLE_TIMEOUT_MS;
        long seconds = ceilSeconds(worstMs) + MATCHED_TTL_MARGIN_SECONDS;
        return (int) Math.max(MATCHED_TTL_FLOOR_SECONDS, seconds);
    }

    /**
     * 进补偿之前给幸存者票据的续期（秒）= {@code 要发取消的人数 × 3 + 10}（基线 {@code queue.go:384-386}）。
     * 「要发取消的人数」= 已冻结的人 + 备战结局不明的人（M14），可以为 0。
     *
     * @throws IllegalArgumentException {@code cancels} 不在 [0, {@value #MAX_GATHER_PLAYERS}] 内
     */
    public static int compensationTtlSeconds(int cancels) {
        if (cancels < 0 || cancels > MAX_GATHER_PLAYERS) {
            throw new IllegalArgumentException("要发取消的人数超出范围 [0, " + MAX_GATHER_PLAYERS + "]: " + cancels);
        }
        return (int) (cancels * ceilSeconds(CANCEL_PREPARE_TIMEOUT_MS)) + COMPENSATION_MARGIN_SECONDS;
    }

    /**
     * 整队开战的开战锁时长（秒）= matched TTL(n) + 全员补偿 + 10（基线 {@code tb.go:60-66}）；也是 xm-team 调 {@code runTeamGather} 的调用级超时。
     * 1 / 2 / 3 / 4 / 5 人 → 65 / 74 / 83 / 92 / 101。
     *
     * @throws IllegalArgumentException {@code n} 不在 [1, {@value #MAX_TEAM_SIZE}] 内
     */
    public static int teamMatchLockSeconds(int n) {
        requirePlayers(n, MAX_TEAM_SIZE);
        return gatherWorstSeconds(n) + TEAM_LOCK_MARGIN_SECONDS;
    }

    /**
     * 一次 gather 加全员补偿的最坏耗时（秒）= matched TTL(n) + 补偿续期(n)。5 人 91 s；开战锁与 {@code runTeamGather} 的调用方超时必须 ≥ 它。
     *
     * @throws IllegalArgumentException {@code n} 不在 [1, {@value #MAX_GATHER_PLAYERS}] 内
     */
    public static int gatherWorstSeconds(int n) {
        return matchedTicketTtlSeconds(n) + compensationTtlSeconds(n);
    }

    private static void requirePlayers(int n, int max) {
        if (n < 1 || n > max) {
            throw new IllegalArgumentException("人数超出范围 [1, " + max + "]: " + n);
        }
    }

    private static long ceilSeconds(long millis) {
        return (millis + 999) / 1000;
    }
}
