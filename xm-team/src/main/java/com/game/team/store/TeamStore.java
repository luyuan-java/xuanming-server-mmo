package com.game.team.store;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamRedisFields;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.SessionState;
import com.game.team.rules.TeamLimits;
import com.game.team.rules.TeamRules;
import com.game.team.rules.TeamTips;
import com.game.team.store.TeamReplies.CommitOutcome;
import com.game.team.store.TeamReplies.CommitStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组队存储（基线 go/match/internal/team/store.go，team-spec §1.6-§1.10、§6.4、§6.5）。
 *
 * <p>权威数据全部在 Redis：{@code xm:{team}:rec / info / player / invite} 四类键只经 {@link TeamScript} 的 Lua 写。本类负责
 * 「读 → 调纯函数规则（{@link TeamRules}）→ CAS 提交 → 按返回值重试 / 修复」；不做推送、不打指标——这些副作用由服务层按返回值完成。
 *
 * <p><b>并发</b>：无可变状态，可被多个工作线程共享；正确性全靠 ver CAS 与 Lua 原子判定。
 *
 * <p><b>线程</b>：所有方法都在调用线程上阻塞等待异步 Redis 结果（{@link Deadline#await}，上界是请求预算），只能在工作线程上调用，
 * 不得在 Netty I/O 线程、场景逻辑线程或 Redisson 回调线程上调用（AGENTS.md §3）。
 *
 * <p><b>故障约定</b>（服务层的映射，team-spec §6.4）：
 * <ul>
 *   <li>Redis 错误 / 超出预算 / 回复形状不对 / 记录解不开 / 提交集合违反契约（程序缺陷）一律抛 {@link DependencyException}
 *       （基线 Mutate 返回 error），服务层回 4030。{@link #readFree} 例外：故障装进 {@link FreeRead}（要带出 healed）。</li>
 *   <li>提交前 {@code deadline.expired()} → {@link Outcome#REJECTED}(4029)（基线 {@code ctx.Err()}）。</li>
 *   <li>自由读不稳定 → {@link FreeRead.Status#UNSTABLE}（服务层回 4029）；S_READ_MEMBERS 重试耗尽 → {@link MembersChangedException}。</li>
 * </ul>
 *
 * <p><b>整队开战</b>（基线 store.go:449-689，team-spec §1.7.6）：开战锁用 {@link #commitMatchLock} 钉版本提交（不走 {@link #mutate} 的
 * 「重读重算」）；清锁用 {@link #endMatch}（后台、自带 110 s 单调截止与退避，不受任何请求预算约束）或单轮的 {@link #releaseMatchLockOnce}。
 * 钉版本提交「报错」或「没有提交」都可能已经落盘（Redisson 会重发 EVAL），调用方必须按 token 确认（match-spec §12.1 第 9 条）。
 */
public final class TeamStore {

    private static final Logger log = LoggerFactory.getLogger(TeamStore.class);

    /** 队伍空闲 TTL（24 h），每次提交或触碰续期（store.go:31）。 */
    public static final long IDLE_TTL_SECONDS = 86_400;
    /** 记录剩余 TTL 低于它（12 h）时 GetMyTeam 执行 S_TOUCH（store.go:33）。 */
    public static final long TOUCH_THRESHOLD_SECONDS = 43_200;
    /** 版本冲突（{@code {0}}）的累计上限，含修复提交的冲突；用尽回 4029（store.go:35）。 */
    public static final int COMMIT_RETRIES = 3;
    /** 自由读的轮数上限（store.go:37）。 */
    public static final int FREE_READ_RETRIES = 3;
    /** S_READ_MEMBERS 的轮数上限（store.go:38）。 */
    public static final int READ_MEMBERS_RETRIES = 3;
    /** 一次 mutate 里 {@code {-2}} 修复的次数上限，以及单次修复的级联轮数（store.go:255、:665，= 容量）。 */
    public static final int REPAIR_CAP = TeamLimits.CAPACITY;
    /** 被邀请人反查 ZSET 的 TTL（秒）：每次 IA 写入后 EXPIRE（scripts.go:122，脚本里是字面量 "3600"）。 */
    public static final long INVITE_INDEX_TTL_SECONDS = 3_600;

    /** EndMatch 每一轮（S_READ + 会话读 + S_COMMIT）的独立预算，不继承任何请求预算（store.go:453）。 */
    public static final long END_MATCH_ROUND_TIMEOUT_MS = 2_000;
    /**
     * EndMatch 的进程内单调截止（store.go:454-461）：必须 ≥ 最长的开战锁（5 人 101 s，{@code MatchBudgets.teamMatchLockSeconds}），否则 Redis
     * 持续故障时循环先于锁截止放弃，全队停在 STARTING 直到锁自然过期。EndMatch 在锁提交之后才启动，剩余锁时长不超过 101 s；110 s 另留出
     * 一轮 {@link #END_MATCH_ROUND_TIMEOUT_MS} 加最大退避的余量（{@code TeamBudgetConstraintTest} 钉住这条不等式）。
     */
    public static final long END_MATCH_MAX_DURATION_MS = MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS * 1000L;
    /** EndMatch 冲突 / 故障的退避：50 ms 起翻倍、上限 1 s、±20% 均匀抖动（store.go:462-465）。 */
    public static final long END_MATCH_BACKOFF_INITIAL_MS = 50;
    public static final long END_MATCH_BACKOFF_MAX_MS = 1_000;
    public static final double END_MATCH_BACKOFF_JITTER = 0.2;

    /** 建队专用的 expectedVer 哨兵：记录必须不存在（scripts.go:51-52）。 */
    static final String NEW_TEAM_VERSION = "new";
    /** Lua 参数里的 TTL 用字符串，避免数字格式差异（store.go:39）。 */
    static final byte[] TTL_ARG = Long.toString(IDLE_TTL_SECONDS).getBytes(StandardCharsets.US_ASCII);

    /**
     * 测试缝（生产恒为 {@link #NONE}；基线 store.go:49-56 的包级 afterReadHook / beforeCommitEvalHook 改成实例级）。
     * 用来把「迟到执行」「提交结果未知」这类并发时序做成确定性用例。
     */
    public interface Hooks {

        /** 不做任何事。 */
        Hooks NONE = new Hooks() {
        };

        /** mutate 每轮 S_READ 之后、绑定校验之前调用。 */
        default void afterRead(Bind bind) {
        }

        /**
         * S_COMMIT 发出之前调用（keys / args 不可变）。抛出的异常原样传出、不再发 EVAL：钩子里先自己执行同一段 EVAL 再抛 =
         * 脚本已执行但回复丢失；执行后正常返回 = 同一段 EVAL 被重发了一次。
         */
        default void beforeCommitEval(Decision decision, List<Object> keys, List<byte[]> args) {
        }

        /**
         * 清开战锁的每一轮（{@link #endMatch} / {@link #releaseMatchLockOnce}）S_READ 之后、判定之前调用（基线 afterMatchReadHook）：
         * 用来确定性地制造「锁期间别人提交」的版本冲突。
         */
        default void afterMatchRead(long teamId) {
        }

        /** {@link #endMatch} 的退避等待（基线 endMatchSleepFn；测试换成记录器不真睡）。 */
        default void endMatchSleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }

        /** {@link #endMatch} 截止用的单调时钟（纳秒；测试用来拨快 110 s 的截止）。 */
        default long nanoTime() {
            return System.nanoTime();
        }
    }

    private final TeamRedis redis;
    private final Hooks hooks;

    public TeamStore(TeamRedis redis) {
        this(redis, Hooks.NONE);
    }

    public TeamStore(TeamRedis redis, Hooks hooks) {
        this.redis = redis;
        this.hooks = hooks == null ? Hooks.NONE : hooks;
    }

    // ================================================================ 名册写

    /**
     * 名册写操作的唯一入口（store.go:180-278，team-spec §1.7.2）：
     * <ol>
     *   <li>S_READ(bind.playerId, bind.teamId)；</li>
     *   <li>CALLER 绑定且调用者索引 tid ≠ teamId（含 teamId=0）→ {@link Outcome#NOT_BOUND}；</li>
     *   <li>CREATE 但记录已存在 → REJECTED(4030)（新发的 team_id 已有记录：发号器故障；EVAL 被重发时也走这里，§8.1 第 9 条，照搬基线）；
     *       非建队且记录不存在 → {@link Outcome#RECORD_MISSING}，调用者索引仍指向它时先 S_HEAL_ORPHAN；</li>
     *   <li>读成员会话 → 规则：拒绝 → REJECTED(code, param)；无变化 → UNCHANGED；</li>
     *   <li>预算已过期 → REJECTED(4029)，不提交；</li>
     *   <li>按本轮 ver（建队用 "new"）提交：OK → COMMITTED；{@code {0}} 冲突累计 3 次 → 4029，否则立即重读（不退避）；
     *       {@code {-1,i}} → 4003[joined[i]]；{@code {-3,i}} → 4022[被邀请人]；{@code {-2,i}} → 修复（超过 5 次或预算过期 → 4029），
     *       修复落盘后重读重算原操作。</li>
     * </ol>
     * 每轮都重新 S_READ 并重做绑定校验：重试不会换队，也不会在已删除的记录上重建。
     *
     * @param sessions 成员会话读取（null 视为 {@link SessionLoader#NONE}）；建队时不调用
     * @param cfg      规则配置（null = {@link RuleConfig#DEFAULT}）
     * @throws DependencyException Redis / 数据 / 程序缺陷类故障（此时前几轮已落盘的修复不会回报，同基线，team-spec §8.1 第 8 条）
     */
    public MutateResult mutate(Bind bind, Op op, SessionLoader sessions, RuleConfig cfg, Deadline deadline) {
        Acc res = new Acc();
        int repairs = 0;
        while (true) {
            Snapshot snap = read(bind.playerId(), bind.teamId(), deadline);
            res.snapshot = snap;
            hooks.afterRead(bind);

            if (bind.mode() == Bind.Mode.CALLER && (bind.teamId() == 0 || snap.playerTeamId() != bind.teamId())) {
                return res.finish(Outcome.NOT_BOUND);
            }
            if (bind.mode() == Bind.Mode.CREATE) {
                if (snap.record() != null) {
                    return res.rejected(TeamTips.INTERNAL, 0); // 新发的 team_id 已有记录：发号器故障
                }
            } else if (snap.record() == null) {
                if (bind.teamId() != 0 && snap.playerTeamId() == bind.teamId()) {
                    res.healedOrphan = healOrphan(bind.playerId(), bind.teamId(), deadline);
                }
                return res.finish(Outcome.RECORD_MISSING);
            }

            Map<Long, SessionState> states = loadSessions(sessions, snap.record(), deadline);
            Decision d = TeamRules.apply(op.withCallerTeamId(snap.playerTeamId()), snap.record(), snap.nowMs(), states, cfg);
            res.decision = d;
            if (d.code() != TeamTips.OK) {
                return res.rejected(d.code(), d.param());
            }
            if (!d.changed()) {
                return res.finish(Outcome.UNCHANGED);
            }
            if (deadline.expired()) {
                return res.rejected(TeamTips.STATE_CHANGED, 0);
            }

            String expectedVer = bind.mode() == Bind.Mode.CREATE ? NEW_TEAM_VERSION : Long.toUnsignedString(snap.version());
            CommitOutcome out = commit(bind.teamId(), expectedVer, d, snap.nowMs(), snap.record(), deadline);
            switch (out.status()) {
                case OK -> {
                    res.commit = out.result();
                    return res.finish(Outcome.COMMITTED);
                }
                case CONFLICT -> {
                    if (++res.conflicts >= COMMIT_RETRIES) {
                        return res.rejected(TeamTips.STATE_CHANGED, 0);
                    }
                }
                case MEMBER_IN_TEAM -> {
                    return res.rejected(TeamTips.MEMBER_IN_TEAM, at(d.joined(), out.index(), "{-1} Joined"));
                }
                case INVITE_LIMIT -> {
                    return res.rejected(TeamTips.INVITE_LIMIT, at(d.invitesAdded(), out.index(), "{-3} InvitesAdded").inviteeId());
                }
                case INDEX_MISMATCH -> {
                    repairs++;
                    if (repairs > REPAIR_CAP || deadline.expired()) {
                        return res.rejected(TeamTips.STATE_CHANGED, 0);
                    }
                    CommitOutcome fixed = repairIndexMismatch(snap, expectedVer, d, out.index(), states, deadline);
                    if (fixed == null) {
                        return res.rejected(TeamTips.STATE_CHANGED, 0);
                    } else if (fixed.status() == CommitStatus.OK) {
                        res.repairs.add(fixed.result());
                    } else if (fixed.status() == CommitStatus.CONFLICT) {
                        if (++res.conflicts >= COMMIT_RETRIES) {
                            return res.rejected(TeamTips.STATE_CHANGED, 0);
                        }
                    } else {
                        return res.rejected(TeamTips.STATE_CHANGED, 0);
                    }
                }
            }
            // 回到第 1 步：重新 S_READ 并重做绑定校验
        }
    }

    /**
     * 处理 S_COMMIT 对决策 d 返回的 {@code {-2,index}}（store.go:650-684，team-spec §1.7.4）：把索引已不是本队的保留成员移出本队，
     * 修复提交与原决策钉在同一个 expectedVer。修复提交本身再回 {@code {-2,j}} 时把 {@code fix.kept[j-1]} 并入待移出集合，基于同一份
     * {@code snap.record} 重新生成再提交（被拒的修复没有写入，ver 不变）；每轮至少多移出一人，{@link #REPAIR_CAP} 轮内必然收敛。
     *
     * @return null = 没有可修的（错位者已不在记录里）；否则是最后一次修复提交的结局（OK 已落盘 / CONFLICT ver 已变 / 其余）
     * @throws DependencyException Redis 故障，或下标越界、不收敛这类程序缺陷
     */
    private CommitOutcome repairIndexMismatch(Snapshot snap, String expectedVer, Decision d, int index,
                                              Map<Long, SessionState> states, Deadline deadline) {
        List<Long> kept = d.kept();
        List<Long> bad = new ArrayList<>();
        for (int round = 0; round < REPAIR_CAP; round++) {
            if (index < 1 || index > kept.size()) {
                throw new DependencyException("{-2} 下标 " + index + " 越界（Kept=" + kept.size() + "）");
            }
            bad.add(kept.get(index - 1));
            Decision fix = TeamRules.repairRemoveMembers(snap.record(), bad, snap.nowMs(), states);
            if (!fix.changed()) {
                return null;
            }
            CommitOutcome out = commit(snap.teamId(), expectedVer, fix, snap.nowMs(), snap.record(), deadline);
            if (out.status() != CommitStatus.INDEX_MISMATCH) {
                return out;
            }
            kept = fix.kept();
            index = out.index();
        }
        throw new DependencyException("{-2} 修复 " + REPAIR_CAP + " 轮仍未收敛（待移出 " + unsigned(bad) + "）");
    }

    /**
     * 把 Decision 翻译成 S_COMMIT 的 KEYS / ARGV、执行并解析（store.go:756-810）。提交前先校验集合契约（含 D25）。
     * 通用形状：建队用 {@code "new"}，其余按 ver 钉死；6.4 的开战锁 / EndMatch 钉版本提交直接复用。
     *
     * @param nowMs  本轮 S_READ 的 nowMs（写进 {@link CommitResult#nowMs()}）
     * @param before 本轮规则的输入记录（D25 用；建队为 null）
     */
    CommitOutcome commit(long teamId, String expectedVer, Decision d, long nowMs, TeamRecord before, Deadline deadline) {
        try {
            CommitSets.validate(teamId, d, before);
        } catch (IllegalStateException e) {
            throw new DependencyException("提交集合违反契约（程序缺陷）team=" + Long.toUnsignedString(teamId) + ": "
                    + e.getMessage(), e);
        }
        CommitSets.Call call = CommitSets.build(teamId, expectedVer, d);
        hooks.beforeCommitEval(d, call.keys(), call.args());
        Object raw = eval(TeamScript.COMMIT, call.keys(), call.args(), deadline, "S_COMMIT team=" + Long.toUnsignedString(teamId));
        return TeamReplies.parseCommit(raw, teamId, d, nowMs);
    }

    // ================================================================ 整队开战：钉版本提交与 EndMatch（store.go:449-648）

    /**
     * 开战锁提交入口（store.go:490-508，team-spec §1.7.6）：在 {@code snap}（本轮 S_READ）的记录上加锁，expectedVer 钉死为
     * {@code snap.version}，<b>不</b>走 {@link #mutate} 的「重读并重算规则」循环——那种循环会在新名单上加锁、却按旧名单建票，破坏整队不可拆分。
     *
     * <ul>
     *   <li>规则（{@link TeamRules#lockMatch}）拒绝 → {@code code}（4013 / 4018 / 4023 / 4030 / 4029），不写；</li>
     *   <li>预算已过期 → 4029，不提交；</li>
     *   <li>已提交 → {@code commit}；</li>
     *   <li>{@code {0}} / {@code {-2}} → {@code retry}（{@code {-2}} 时顺手把索引错位的成员修复移出，记入 {@code repairs}）：调用方必须
     *       <b>整轮重来</b>，并先按 token 确认这次的锁确实没落盘（{@link #releaseMatchLockOnce}）。</li>
     * </ul>
     *
     * @param snap       本轮 S_READ 的快照（记录必须存在）
     * @param roster     参战名单（必须与记录成员集合相等，否则 4029）
     * @param expireAtMs 锁截止（Redis 时钟毫秒）= {@code snap.nowMs + 锁时长}
     * @param sessions   {@code {-2}} 修复时读成员会话用（null 视为 {@link SessionLoader#NONE}）
     * @throws DependencyException 快照缺记录（程序缺陷）、Redis / 数据故障、意外的脚本返回——<b>结果未知</b>：EVAL 可能已在 Redis 执行，
     *                             调用方必须按 token 后台清锁
     */
    public PinnedResult commitMatchLock(Snapshot snap, long caller, String token, List<Long> roster, long expireAtMs,
                                        SessionLoader sessions, Deadline deadline) {
        if (snap == null || snap.record() == null || snap.version() == 0) {
            throw new DependencyException("开战锁提交缺少记录快照");
        }
        Decision d = TeamRules.lockMatch(snap.record(), caller, token, roster, expireAtMs, snap.nowMs());
        if (d.code() != TeamTips.OK) {
            return PinnedResult.rejected(d.code(), d.param());
        }
        if (deadline.expired()) {
            return PinnedResult.rejected(TeamTips.STATE_CHANGED, 0);
        }
        return commitPinned(snap, d, sessions, deadline);
    }

    /**
     * 清开战锁的专用循环（store.go:537-585，team-spec §1.7.6）。不复用 {@link #mutate} 的 3 次冲突上限：锁期间队外玩家的申请 / 被邀请
     * 持续让 ver+1，数量不受本队控制，3 次就放弃会让全员停在 STARTING 直到锁过期。
     *
     * <ol>
     *   <li>S_READ 本队记录（nowMs 取 Redis TIME）；</li>
     *   <li>记录不存在 / token 不符 / nowMs ≥ 锁截止 → 停止，不写；</li>
     *   <li>否则按 expectedVer = ver 提交清锁；{@code {0}} → 退避后回第 1 步；{@code {-2}} → 修复后<b>立即</b>回第 1 步；
     *       本轮报错 → 记 {@code lastError}、退避后重试。</li>
     * </ol>
     * 截止：锁自己的截止时间（每轮用 nowMs 判）+ 进程内单调时钟 {@link #END_MATCH_MAX_DURATION_MS} 兜底；每轮一个独立的
     * {@link #END_MATCH_ROUND_TIMEOUT_MS} 预算。
     *
     * <p>同步阻塞直到结局（最坏 110 s），调用方放在后台执行器（{@code team-match-end}）上，<b>不得</b>在请求工作线程上调；
     * 不推送、不打指标（服务层按结果处理）。退避被中断（停机）→ {@link EndMatchStop#INTERRUPTED}，线程的中断标志保持置位。从不抛依赖故障。
     *
     * @param ok       开局是否成功（清锁提交的 Reason：MATCH_ENDED / MATCH_FAILED）
     * @param sessions 清锁决策的惰性转让与 {@code {-2}} 修复用（null 视为 {@link SessionLoader#NONE}）
     */
    public EndMatchResult endMatch(long teamId, String token, boolean ok, SessionLoader sessions) {
        List<CommitResult> repairs = new ArrayList<>();
        int conflicts = 0;
        DependencyException lastError = null;
        long maxNanos = TimeUnit.MILLISECONDS.toNanos(END_MATCH_MAX_DURATION_MS);
        long start = hooks.nanoTime();
        long backoff = END_MATCH_BACKOFF_INITIAL_MS;
        while (true) {
            if (hooks.nanoTime() - start >= maxNanos) {
                return new EndMatchResult(EndMatchStop.DEADLINE, null, repairs, conflicts, lastError);
            }
            LockRelease round = null;
            try {
                round = releaseRound(teamId, token, ok, sessions, Deadline.after(END_MATCH_ROUND_TIMEOUT_MS));
            } catch (DependencyException e) {
                lastError = e;
            }
            if (round != null) {
                if (round.stop() != null) {
                    return new EndMatchResult(round.stop(), null, repairs, conflicts, lastError);
                }
                PinnedResult pinned = round.pinned();
                repairs.addAll(pinned.repairs());
                if (pinned.commit() != null) {
                    return new EndMatchResult(EndMatchStop.RELEASED, pinned.commit(), repairs, conflicts, lastError);
                }
                if (!pinned.repairs().isEmpty()) {
                    continue; // 修复已落盘、ver 已变：立即重读
                }
                conflicts++;
            }
            try {
                hooks.endMatchSleep(jittered(backoff));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new EndMatchResult(EndMatchStop.INTERRUPTED, null, repairs, conflicts, lastError);
            }
            backoff = Math.min(backoff * 2, END_MATCH_BACKOFF_MAX_MS);
        }
    }

    /**
     * 按 token 清开战锁（ok = false）的<b>单轮</b>版本（store.go:587-592）：读 → 判定 → 钉版本提交，不退避、不重试，受调用方预算约束。
     * StartTeamMatch 在开战锁提交没拿到「已提交」时用它确认本 token 的锁不在。
     *
     * @return {@code stop != null}：记录不存在 / token 不符 / 锁已过期，均不写；否则 {@code pinned.commit != null} 表示已清，
     *         为 null 表示没清（冲突或刚做过修复）
     * @throws DependencyException Redis / 数据故障（调用方转后台清锁）
     */
    public LockRelease releaseMatchLockOnce(long teamId, String token, SessionLoader sessions, Deadline deadline) {
        return releaseRound(teamId, token, false, sessions, deadline);
    }

    /** 清锁的一轮（store.go:594-618）：只关心记录，S_READ 的玩家位传 0（{@code xm:{team}:player:0} 恒不存在，脚本只读不写）。 */
    private LockRelease releaseRound(long teamId, String token, boolean ok, SessionLoader sessions, Deadline deadline) {
        Snapshot snap = read(0, teamId, deadline);
        hooks.afterMatchRead(teamId);
        TeamRecord rec = snap.record();
        if (rec == null) {
            return LockRelease.stopped(EndMatchStop.RECORD_MISSING);
        }
        if (token == null || !rec.getMatchLockToken().equals(token)) {
            return LockRelease.stopped(EndMatchStop.TOKEN_MISMATCH);
        }
        if (!TeamRules.matchLockActive(rec, snap.nowMs())) {
            return LockRelease.stopped(EndMatchStop.LOCK_EXPIRED);
        }
        Decision d = TeamRules.releaseMatchLock(rec, token, ok, snap.nowMs(), loadSessions(sessions, rec, deadline));
        if (!d.changed()) {
            return LockRelease.stopped(EndMatchStop.TOKEN_MISMATCH); // 防御：上面已判过；规则层再拒即视为锁已不属于本次
        }
        return LockRelease.attempted(commitPinned(snap, d, sessions, deadline));
    }

    /**
     * 按 {@code snap.version} 钉死提交 d（不重读、不重算规则；store.go:620-648）。{@code {-2,i}}：把索引已指向别队的保留成员移出
     * （{@link #repairIndexMismatch}，修复提交同样钉在 {@code snap.version}，与 {@link #mutate} 同口径），原决策本次不提交、返回 retry。
     * d 不含 joined / invitesAdded，{@code {-1}} / {@code {-3}} 不可能出现，出现即程序缺陷。
     */
    private PinnedResult commitPinned(Snapshot snap, Decision d, SessionLoader sessions, Deadline deadline) {
        String expectedVer = Long.toUnsignedString(snap.version());
        CommitOutcome out = commit(snap.teamId(), expectedVer, d, snap.nowMs(), snap.record(), deadline);
        switch (out.status()) {
            case OK -> {
                return PinnedResult.committed(out.result());
            }
            case CONFLICT -> {
                return PinnedResult.retry(List.of());
            }
            case INDEX_MISMATCH -> {
                CommitOutcome fixed = repairIndexMismatch(snap, expectedVer, d, out.index(),
                        loadSessions(sessions, snap.record(), deadline), deadline);
                return PinnedResult.retry(fixed != null && fixed.status() == CommitStatus.OK
                        ? List.of(fixed.result()) : List.of());
            }
            default -> throw new DependencyException("钉版本提交出现意外返回 status=" + out.status() + " index=" + out.index()
                    + " team=" + Long.toUnsignedString(snap.teamId()));
        }
    }

    /** 给退避加 ±{@link #END_MATCH_BACKOFF_JITTER} 的均匀抖动（store.go:686-689；选时不要求确定性）。 */
    private static long jittered(long millis) {
        double factor = 1 + END_MATCH_BACKOFF_JITTER * (2 * ThreadLocalRandom.current().nextDouble() - 1);
        return Math.max(1, Math.round(millis * factor));
    }

    // ================================================================ 读

    /**
     * 自由读（store.go:286-313，team-spec §1.7.5）：GetMyTeam、各 RPC 的前置检查与失败回包里的调用者视图。
     * 最多 {@link #FREE_READ_RETRIES} 轮：先 {@code HGET tid} 预读，再 S_READ(pid, tid)，回报的 tid 与预读不符就重来（保证 epoch 与
     * 记录 ver 同源）；tid ≠ 0 但记录不存在时执行 S_HEAL_ORPHAN、记下 healed、重来。
     *
     * <p>从不抛 {@link DependencyException}：故障装进结果（{@link FreeRead.Status#FAILED}），以便带出 healed。
     */
    public FreeRead readFree(long playerId, Deadline deadline) {
        boolean healed = false;
        try {
            for (int i = 0; i < FREE_READ_RETRIES; i++) {
                long tid = indexTeamId(playerId, deadline);
                Snapshot snap = read(playerId, tid, deadline);
                if (snap.playerTeamId() != tid) {
                    continue;
                }
                if (tid != 0 && snap.record() == null) {
                    healed = healOrphan(playerId, tid, deadline) || healed;
                    continue;
                }
                return FreeRead.ok(snap, healed);
            }
            return FreeRead.unstable(healed);
        } catch (DependencyException e) {
            return FreeRead.failed(healed, e);
        }
    }

    /**
     * S_READ：playerId 的索引 + teamId 的记录 + Redis 时钟，出自同一次原子读（store.go:693-720）。
     * playerId 传 0 时读 {@code xm:{team}:player:0}（恒不存在，只关心记录）。
     *
     * @throws DependencyException Redis 故障、回复形状不对、记录解不开
     */
    public Snapshot read(long playerId, long teamId, Deadline deadline) {
        Object raw = eval(TeamScript.READ, List.of(RedisKeys.teamPlayer(playerId), RedisKeys.teamRecord(teamId)), List.of(),
                deadline, "S_READ player=" + Long.toUnsignedString(playerId) + " team=" + Long.toUnsignedString(teamId));
        return TeamReplies.parseRead(playerId, teamId, raw);
    }

    /**
     * S_READ_MEMBERS（store.go:366-406）：按 knownMembers 读；记录成员集合（按多重集）与传入的不等时，用新成员表重读，
     * 最多 {@link #READ_MEMBERS_RETRIES} 次。记录不存在返回 {@code record == null}（不是错误）。tid 不等的成员如实回报。
     *
     * @throws MembersChangedException 重试耗尽仍不等（服务层放弃这次推送）
     * @throws DependencyException     Redis 故障、回复形状不对、记录解不开
     */
    public MembersSnapshot readMembers(long teamId, Collection<Long> knownMembers, Deadline deadline) {
        List<Long> members = knownMembers == null ? List.of() : List.copyOf(knownMembers);
        String what = "S_READ_MEMBERS team=" + Long.toUnsignedString(teamId);
        for (int i = 0; i < READ_MEMBERS_RETRIES; i++) {
            List<Object> keys = new ArrayList<>(1 + members.size());
            keys.add(RedisKeys.teamRecord(teamId));
            for (long pid : members) {
                keys.add(RedisKeys.teamPlayer(pid));
            }
            List<Object> arr = TeamReplies.multi(eval(TeamScript.READ_MEMBERS, keys, List.of(), deadline, what), what);
            if (arr.size() != 3 + 2 * members.size()) {
                throw new DependencyException(what + " 返回形态非法: 长度 " + arr.size() + "，期望 " + (3 + 2 * members.size()));
            }
            long nowMs = TeamReplies.parseUint(arr.get(2));
            long version = TeamReplies.parseUint(arr.get(0));
            if (version == 0) {
                return new MembersSnapshot(teamId, 0, null, nowMs, Map.of());
            }
            TeamRecord rec = TeamReplies.unmarshalRecord(arr.get(1));
            List<Long> ids = TeamRules.memberIds(rec);
            if (!TeamRules.sameIdSet(ids, members)) {
                members = ids;
                continue;
            }
            Map<Long, IndexEntry> indexes = new LinkedHashMap<>();
            for (int j = 0; j < members.size(); j++) {
                indexes.put(members.get(j), new IndexEntry(TeamReplies.parseUint(arr.get(3 + 2 * j)),
                        TeamReplies.parseUint(arr.get(4 + 2 * j))));
            }
            return new MembersSnapshot(teamId, version, rec, nowMs, Collections.unmodifiableMap(indexes));
        }
        throw new MembersChangedException("组队成员表持续变化，" + what + " 重试 " + READ_MEMBERS_RETRIES + " 次仍不一致");
    }

    // ================================================================ 续期与自愈

    /** 记录剩余 TTL 不足 12 h（含 -1 没有 TTL）时需要 S_TOUCH（store.go:315-318）。 */
    public static boolean needsTouch(Snapshot snap) {
        return snap != null && snap.record() != null && snap.recordTtlSeconds() < TOUCH_THRESHOLD_SECONDS;
    }

    /**
     * S_TOUCH（store.go:320-342）：ver 仍等于 {@code snap.version} 才续期记录、投影（缺失时按该版记录重写）与仍指向本队的成员索引；
     * 不改 ver。返回 false 表示 ver 已变或记录已不存在（不是错误）。
     *
     * @throws DependencyException Redis 故障
     */
    public boolean touch(Snapshot snap, Deadline deadline) {
        if (snap == null || snap.record() == null) {
            return false;
        }
        TeamRecord rec = snap.record();
        long tid = rec.getTeamId();
        List<Object> keys = new ArrayList<>(2 + rec.getMembersCount());
        keys.add(RedisKeys.teamRecord(tid));
        keys.add(RedisKeys.teamInfo(tid));
        for (long pid : TeamRules.memberIds(rec)) {
            keys.add(RedisKeys.teamPlayer(pid));
        }
        List<byte[]> args = List.of(CommitSets.ascii(Long.toUnsignedString(snap.version())), TTL_ARG.clone(),
                projectionOf(rec).toByteArray(), CommitSets.ascii(Long.toUnsignedString(tid)));
        return isOne(eval(TeamScript.TOUCH, keys, args, deadline, "S_TOUCH team=" + Long.toUnsignedString(tid)));
    }

    /**
     * S_HEAL_ORPHAN（store.go:344-354）：记录不存在且玩家索引仍指向 teamId 时把索引置 0、epoch+1。
     *
     * @return true = 治愈了（服务层记 {@code team_heal_total{orphan_index}}）
     * @throws DependencyException Redis 故障
     */
    public boolean healOrphan(long playerId, long teamId, Deadline deadline) {
        Object raw = eval(TeamScript.HEAL_ORPHAN, List.of(RedisKeys.teamPlayer(playerId), RedisKeys.teamRecord(teamId)),
                List.of(CommitSets.ascii(Long.toUnsignedString(teamId)), TTL_ARG.clone()), deadline,
                "S_HEAL_ORPHAN player=" + Long.toUnsignedString(playerId) + " team=" + Long.toUnsignedString(teamId));
        return isOne(raw);
    }

    // ================================================================ 邀请反查索引

    /**
     * S_INVITE_LIST（store.go:416-436）：按 Redis 时钟剔除过期项并列出剩余项（会写，READ_WRITE）。score 原字符串保留，供
     * {@link #pruneInvite} 做 CAS。
     *
     * @throws DependencyException Redis 故障、回复形状不对、score 不是十进制数
     */
    public InviteList listInvites(long playerId, Deadline deadline) {
        Object raw = eval(TeamScript.INVITE_LIST, List.of(RedisKeys.teamInvite(playerId)), List.of(), deadline,
                "S_INVITE_LIST player=" + Long.toUnsignedString(playerId));
        return TeamReplies.parseInviteList(raw);
    }

    /**
     * S_INVITE_PRUNE（store.go:438-447）：score 仍等于 {@link #listInvites} 看到的原字符串才删（不会误删队长刚重邀写入的新项）。
     *
     * @throws DependencyException Redis 故障
     */
    public boolean pruneInvite(long playerId, long teamId, String score, Deadline deadline) {
        Object raw = eval(TeamScript.INVITE_PRUNE, List.of(RedisKeys.teamInvite(playerId)),
                List.of(CommitSets.ascii(Long.toUnsignedString(teamId)), score.getBytes(StandardCharsets.ISO_8859_1)),
                deadline, "S_INVITE_PRUNE player=" + Long.toUnsignedString(playerId) + " team=" + Long.toUnsignedString(teamId));
        return isOne(raw);
    }

    // ================================================================ 投影

    /** 由记录生成 xm-scene 读的投影（store.go:904-911）：members 是集合语义，这里按 join_seq 升序只为确定性。 */
    public static TeamInfo projectionOf(TeamRecord rec) {
        return TeamInfo.newBuilder()
                .setTeamId(rec.getTeamId())
                .setLeaderId(rec.getLeaderId())
                .addAllMembers(TeamRules.memberIds(rec))
                .build();
    }

    // ================================================================ 内部实现

    /** 玩家索引的 tid（store.go:722-731）：键或字段不存在为 0；值不是无符号十进制是故障。 */
    private long indexTeamId(long playerId, Deadline deadline) {
        byte[] raw = await(redis.hget(RedisKeys.teamPlayer(playerId), TeamRedisFields.TID), deadline,
                "HGET 组队索引 player=" + Long.toUnsignedString(playerId));
        return raw == null ? 0 : TeamReplies.parseIndexTid(raw, playerId);
    }

    /** 读本轮记录成员的会话状态（store.go:733-738）；记录为 null 时不读。实现违约抛异常时按全员 UNKNOWN（fail-closed）。 */
    private static Map<Long, SessionState> loadSessions(SessionLoader sessions, TeamRecord rec, Deadline deadline) {
        if (sessions == null || rec == null) {
            return null;
        }
        try {
            return sessions.load(TeamRules.memberIds(rec), deadline);
        } catch (RuntimeException e) {
            log.warn("[team] 读成员会话状态抛出异常，按全员 UNKNOWN 处理 team={}", Long.toUnsignedString(rec.getTeamId()), e);
            return Map.of();
        }
    }

    private Object eval(TeamScript script, List<Object> keys, List<byte[]> args, Deadline deadline, String what) {
        return await(redis.eval(script, keys, args), deadline, what);
    }

    private static <T> T await(CompletionStage<T> stage, Deadline deadline, String what) {
        try {
            return deadline.await(stage, what);
        } catch (CancellationException e) {
            throw new DependencyException(what + " 被取消", e);
        }
    }

    private static boolean isOne(Object raw) {
        return raw instanceof Long n && n == 1L;
    }

    /** {@code {-1,i}} / {@code {-3,i}} 的第 i 项（i 从 1 起）；越界是程序缺陷（基线会 panic）。 */
    private static <T> T at(List<T> list, int index, String what) {
        if (index < 1 || index > list.size()) {
            throw new DependencyException("S_COMMIT " + what + " 下标 " + index + " 越界（共 " + list.size() + "）");
        }
        return list.get(index - 1);
    }

    private static List<String> unsigned(List<Long> ids) {
        return ids.stream().map(Long::toUnsignedString).toList();
    }

    /** mutate 跨轮累积的结果（基线 res 在多轮之间复用：snapshot / decision 是最后一轮的，conflicts / repairs 累计）。 */
    private static final class Acc {
        private Outcome outcome;
        private int code;
        private long param;
        private Snapshot snapshot;
        private Decision decision = Decision.unchanged();
        private CommitResult commit;
        private final List<CommitResult> repairs = new ArrayList<>();
        private boolean healedOrphan;
        private int conflicts;

        MutateResult finish(Outcome o) {
            outcome = o;
            return new MutateResult(outcome, code, param, snapshot, decision, commit, repairs, healedOrphan, conflicts);
        }

        MutateResult rejected(int c, long p) {
            code = c;
            param = p;
            return finish(Outcome.REJECTED);
        }
    }
}
