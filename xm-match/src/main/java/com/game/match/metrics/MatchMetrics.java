package com.game.match.metrics;

import com.game.discovery.presence.PlayerPushes;
import com.game.match.dispatch.MatchMethods;
import com.game.match.gather.FingerprintMode;
import com.game.match.gather.GatherOutcome;
import com.game.match.support.MatchModes;
import com.game.match.ticket.TicketStore.DropReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * xm-match 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出；match-spec §11 整张表）。指标名与标签只在这里定义，各业务包调这里的方法，
 * 不自己建计量。下面写的是 Prometheus 里看到的名字（Micrometer 名把 {@code _} 换成 {@code .}、去掉 {@code _total} / {@code _seconds} 后缀）：
 *
 * <table>
 *   <caption>指标一览</caption>
 *   <tr><td>{@code xm_match_requests_seconds{method, result}}</td><td>Timer</td><td>客户端请求从受理到应答（含排队）</td></tr>
 *   <tr><td>{@code xm_match_join_queue_total{mode, outcome}}</td><td>Counter</td><td>157 的出口</td></tr>
 *   <tr><td>{@code xm_match_queue_depth{mode, config}}</td><td>Gauge</td><td>队列长度；只有持锁实例写实值，其余实例置 0（看板按实例求和）</td></tr>
 *   <tr><td>{@code xm_match_starved_anchor_wait_seconds{mode, config}}</td><td>Gauge</td><td>本轮凑不到人的锚点里等得最久的秒数</td></tr>
 *   <tr><td>{@code xm_match_wait_seconds{mode}} / {@code xm_match_group_rating_spread{mode}}</td><td>分布</td><td>成组时锚点已等秒数 / 组内评分极差</td></tr>
 *   <tr><td>{@code xm_match_matcher_rounds_total{result}}</td><td>Counter</td><td>凑单每轮的结局（含三种暂停）</td></tr>
 *   <tr><td>{@code xm_match_requeued_total{reason}} / {@code xm_match_queue_dropped_total{reason}} / {@code xm_match_queue_anomalies_total{reason}}</td>
 *       <td>Counter</td><td>回队首的人数 / 被剔出队列的人数 / 队列数据异常</td></tr>
 *   <tr><td>{@code xm_match_gathers_total{mode, outcome}} / {@code xm_match_gather_seconds{mode, outcome}} / {@code xm_match_gathers_inflight}</td>
 *       <td>Counter / Timer / Gauge</td><td>开局管线</td></tr>
 *   <tr><td>{@code xm_match_gather_zone_mix_total{mode, mix}} / {@code xm_match_table_fingerprint_mismatches_total{fp_mode}}</td><td>Counter</td><td></td></tr>
 *   <tr><td>{@code xm_match_battle_ticket_reissues_total{result}}</td><td>Counter</td><td>179 的出口</td></tr>
 *   <tr><td>{@code xm_match_challenges_total{stage, result}} / {@code xm_match_pushes_total{kind, outcome}}</td><td>Counter</td><td>切磋 / 156、154 的推送结局</td></tr>
 *   <tr><td>{@code xm_match_activity_battles_total{kind, result}} / {@code xm_match_team_calls_total{method, result}}</td><td>Counter</td><td>两个内部接口</td></tr>
 *   <tr><td>{@code xm_match_rating_updates_total{mode, outcome}} / {@code xm_match_rating_round_cap_draws_total{mode}} /
 *       {@code xm_match_rating_consumer_paused}</td><td>Counter / Counter / Gauge</td><td>评分回流</td></tr>
 *   <tr><td>{@code xm_match_battle_nodes{state}} / {@code xm_match_lease_lost}</td><td>Gauge</td><td>battle 目录概况 / 发号租约是否已丢失（1 = 需要重启）</td></tr>
 *   <tr><td>{@code xm_match_admin_requests_total{op, status}}</td><td>Counter</td><td>dev 管理口的 HTTP 审计</td></tr>
 *   <tr><td>{@code xm_match_watch_battle_total{outcome}} / {@code xm_match_list_watchable_total{result}}</td><td>Counter</td>
 *       <td>163 / 164 的出口（批次 6.5，spectate-spec §6）</td></tr>
 *   <tr><td>{@code xm_match_spectate_evictions_total{reason, result}}</td><td>Counter</td><td>把一名观众从他正在看的那一场清退（开局前 / 换场 / 复查命中）</td></tr>
 *   <tr><td>{@code xm_match_watchable_index_evictions_total{reason}} / {@code xm_match_watchable_anomalies_total{reason}} /
 *       {@code xm_match_watchable_battles}</td><td>Counter / Counter / Gauge</td>
 *       <td>可观战索引：摘掉的成员数 / 数据异常 / 索引大小（每个实例各自采样同一个全局 ZSET，看板取 max、不能 sum）</td></tr>
 *   <tr><td>{@code xm_match_observer_rpc_total{method, result}} / {@code xm_match_spectate_inflight}</td><td>Counter / Gauge</td>
 *       <td>发给 battle 的观众 RPC 的结局 / 在途的 163 数（上限 {@code xm.match.spectate.max-inflight}；每实例的真实值，求和才有意义）</td></tr>
 * </table>
 *
 * <p><b>标签</b>：取值全部来自本类的枚举或 {@link MetricLabels} 的净化结果，没有任何客户端能直接控制的值；不以玩家 / 战斗 / 切磋 / 队伍的号或 zone 作标签
 * （AGENTS.md §5）。常见的标签组合启动时预建为 0（「从没出错」与「指标不存在」要分得开），其余在第一次出现时建。
 * 全部方法线程安全、不阻塞、不抛异常，可在任何线程上调。
 */
public final class MatchMetrics {

    static final String REQUESTS = "xm.match.requests";
    static final String JOIN_QUEUE = "xm.match.join.queue";
    static final String QUEUE_DEPTH = "xm.match.queue.depth";
    static final String STARVED_ANCHOR_WAIT = "xm.match.starved.anchor.wait";
    static final String WAIT = "xm.match.wait";
    static final String GROUP_RATING_SPREAD = "xm.match.group.rating.spread";
    static final String MATCHER_ROUNDS = "xm.match.matcher.rounds";
    static final String REQUEUED = "xm.match.requeued";
    static final String QUEUE_DROPPED = "xm.match.queue.dropped";
    static final String QUEUE_ANOMALIES = "xm.match.queue.anomalies";
    static final String GATHERS = "xm.match.gathers";
    static final String GATHER_DURATION = "xm.match.gather";
    static final String GATHERS_INFLIGHT = "xm.match.gathers.inflight";
    static final String GATHER_ZONE_MIX = "xm.match.gather.zone.mix";
    static final String FINGERPRINT_MISMATCHES = "xm.match.table.fingerprint.mismatches";
    static final String REISSUES = "xm.match.battle.ticket.reissues";
    static final String CHALLENGES = "xm.match.challenges";
    static final String PUSHES = "xm.match.pushes";
    static final String ACTIVITY_BATTLES = "xm.match.activity.battles";
    static final String TEAM_CALLS = "xm.match.team.calls";
    static final String RATING_UPDATES = "xm.match.rating.updates";
    static final String RATING_ROUND_CAP_DRAWS = "xm.match.rating.round.cap.draws";
    static final String RATING_CONSUMER_PAUSED = "xm.match.rating.consumer.paused";
    static final String BATTLE_NODES = "xm.match.battle.nodes";
    static final String LEASE_LOST = "xm.match.lease.lost";
    static final String ADMIN_REQUESTS = "xm.match.admin.requests";
    static final String WATCH_BATTLE = "xm.match.watch.battle";
    static final String LIST_WATCHABLE = "xm.match.list.watchable";
    static final String SPECTATE_EVICTIONS = "xm.match.spectate.evictions";
    static final String WATCHABLE_INDEX_EVICTIONS = "xm.match.watchable.index.evictions";
    static final String WATCHABLE_ANOMALIES = "xm.match.watchable.anomalies";
    static final String WATCHABLE_BATTLES = "xm.match.watchable.battles";
    static final String OBSERVER_RPC = "xm.match.observer.rpc";
    static final String SPECTATE_INFLIGHT = "xm.match.spectate.inflight";

    /** 请求耗时：与 gate / login / trade 同一套 SLO 桶（5 ms～10 s）。 */
    static final Duration[] REQUEST_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};
    /** gather 耗时：基线的桶（10 ms～10 s）之上补 30 / 60 / 120 s——Java 的 timer 把补偿也算在内，失败的 gather 可以到 90 s。 */
    static final Duration[] GATHER_BUCKETS = {
            Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120)};
    /** 成组时锚点已等的秒数（同基线 {@code metrics.go:122-127}）。 */
    static final double[] WAIT_BUCKETS = {1, 2, 5, 10, 20, 30, 45, 60, 120, 300};
    /**
     * 组内评分极差，单位评分点（基线 {@code metrics.go:114-119} 的桶去掉最前面的 0：Micrometer 不接受 ≤ 0 的桶边界，极差恰为 0 的样本落进 25 这一桶）。
     */
    static final double[] SPREAD_BUCKETS = {25, 50, 100, 200, 300, 500, 800, 1000, 1600};
    /** 预建的 HTTP 状态；别的状态首次出现时再建（状态码本身有界）。 */
    static final List<String> ADMIN_STATUSES = List.of("200", "400", "401", "403", "500", "503");

    // ================================================================ 标签枚举

    /** 一个客户端请求在派发层的结局（{@code xm_match_requests_seconds{result}}）。业务上的细分看各功能自己的计数。 */
    public enum RequestResult {
        /** 处理器回了应答体（含 in-band 的业务拒绝与 in-band 16004）。 */
        OK,
        /** 处理器回了信封（148 / 153 的依赖故障）。 */
        FAILED,
        /** 工作池满或排队超预算（按该方法的过载应答回）。 */
        OVERLOADED,
        /** 请求体解析失败（信封 1003）。 */
        BAD_REQUEST,
        /** 不认识的号 / 没有处理器（信封 1003）。 */
        UNSUPPORTED,
        /** 处理器抛了未分类的异常（信封 1003）。 */
        ERROR
    }

    /** 157 的出口（{@code xm_match_join_queue_total{outcome}}；同基线，{@code overloaded} 是 Java 新增）。 */
    public enum JoinOutcome { OK, IN_BATTLE, ALREADY_QUEUED, MODE_NOT_OPEN, NO_TEAM_SIZE, NOT_IN_SCENE, INTERNAL, OVERLOADED }

    /** 凑单一轮的结局：三种暂停都在抢锁之前判（battle 池不可分配 / 发号租约无效 / gather 许可已满）。 */
    public enum MatcherRound { OK, PAUSED_NO_BATTLE, PAUSED_NO_LEASE, PAUSED_SATURATED, ERROR }

    /** 回队首的原因：有肇事者时的幸存者 / 无肇事者时的全员。 */
    public enum RequeueReason { GATHER_OFFENDER, GATHER_NO_OFFENDER }

    /** 队列数据异常：评分镜像缺分（按票里的评分用、不回写）/ 弹组被拒后的本轮重挑次数用完。 */
    public enum QueueAnomaly { MISSING_SCORE, REPICK_EXHAUSTED }

    /** 一局的成员是否同区。 */
    public enum ZoneMix { SINGLE, CROSS }

    /**
     * 179 的出口：{@code rejected} = battle 核对名单后拒签（含它回的「房间不存在」）；{@code not_found} = 没有落点记录；
     * {@code instance_changed} = 请求确定没送达、同号节点已换实例、且对原地址的 TCP 建连探测明确连不上，三条都成立（回 1005）；{@code rpc_timeout} / {@code rpc_error} = 其余没调通（回 1003）。
     */
    public enum ReissueResult { OK, REJECTED, NOT_FOUND, NO_SESSION, INTERNAL, RPC_ERROR, RPC_TIMEOUT, INSTANCE_CHANGED }

    /** 切磋的阶段。 */
    public enum ChallengeStage { INVITE, RESPOND }

    /** 切磋各出口（同基线 {@code chl.go} 的 outcome；{@code overloaded} 是 Java 新增）。哪个阶段会出现哪些值见 {@link #CHALLENGE_RESULTS}。 */
    public enum ChallengeResult {
        OK, INTERNAL, SELF, SELF_BUSY, TARGET_BUSY, TARGET_OFFLINE, PENDING, PUSH_FAILED,
        EXPIRED, NOT_TARGET, DECLINED, CHALLENGER_BUSY, RESPONDER_BUSY, ACCEPTED, OVERLOADED
    }

    /** 每个阶段会出现的出口（预建用）。 */
    static final Map<ChallengeStage, List<ChallengeResult>> CHALLENGE_RESULTS = Map.of(
            ChallengeStage.INVITE, List.of(ChallengeResult.OK, ChallengeResult.INTERNAL, ChallengeResult.SELF, ChallengeResult.SELF_BUSY,
                    ChallengeResult.TARGET_BUSY, ChallengeResult.TARGET_OFFLINE, ChallengeResult.PENDING, ChallengeResult.PUSH_FAILED,
                    ChallengeResult.OVERLOADED),
            ChallengeStage.RESPOND, List.of(ChallengeResult.INTERNAL, ChallengeResult.EXPIRED, ChallengeResult.NOT_TARGET, ChallengeResult.DECLINED,
                    ChallengeResult.CHALLENGER_BUSY, ChallengeResult.RESPONDER_BUSY, ChallengeResult.ACCEPTED, ChallengeResult.OVERLOADED));

    /** 推送的消息（标签取消息号）。 */
    public enum PushKind {
        /** 156 切磋邀请。 */
        INVITE("156"),
        /** 154 切磋结果。 */
        RESULT("154");

        private final String label;

        PushKind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** 推送的结局：前三个同 {@code PlayerPushes.Outcome}；{@code error} = 推送的 stage 异常完成（Redis 故障、在线目录条目损坏）。 */
    public enum PushOutcome { SENT, OFFLINE, GATE_UNREACHABLE, ERROR }

    /**
     * 活动开战的结果（同基线 {@code match_activity_battle_total}）：每个请求恰好记一个同步出口（started / invalid / offline / in_battle / not_ready /
     * internal）；started 之后异步 gather 的终态另记 gather_ok / gather_failed。
     */
    public enum ActivityResult { STARTED, INVALID, OFFLINE, IN_BATTLE, NOT_READY, INTERNAL, GATHER_OK, GATHER_FAILED }

    /** {@code MatchTeamService} 的方法（标签取 Java 方法名）。 */
    public enum TeamMethod {
        CHECK_TEAM_MATCH("checkTeamMatch"),
        CREATE_TEAM_TICKETS("createTeamTickets"),
        RELEASE_TEAM_TICKETS("releaseTeamTickets"),
        RUN_TEAM_GATHER("runTeamGather");

        private final String label;

        TeamMethod(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** {@code MatchTeamService} 各方法的结果。哪个方法会出现哪些值见 {@link #TEAM_RESULTS}。 */
    public enum TeamCallResult {
        OK, DUNGEON_NOT_OPEN, SIZE_EXCEEDED, MEMBER_OFFLINE, MEMBER_IN_BATTLE, MEMBER_NOT_READY, INTERNAL,
        CREATED, FAILED, EXPIRED, GATHER_OK, GATHER_FAILED, OVERLOADED, ERROR
    }

    /** 每个方法会出现的结果（预建用）。 */
    static final Map<TeamMethod, List<TeamCallResult>> TEAM_RESULTS = Map.of(
            TeamMethod.CHECK_TEAM_MATCH, List.of(TeamCallResult.OK, TeamCallResult.DUNGEON_NOT_OPEN, TeamCallResult.SIZE_EXCEEDED,
                    TeamCallResult.MEMBER_OFFLINE, TeamCallResult.MEMBER_IN_BATTLE, TeamCallResult.MEMBER_NOT_READY, TeamCallResult.INTERNAL,
                    TeamCallResult.OVERLOADED),
            TeamMethod.CREATE_TEAM_TICKETS, List.of(TeamCallResult.CREATED, TeamCallResult.FAILED, TeamCallResult.EXPIRED, TeamCallResult.OVERLOADED,
                    TeamCallResult.ERROR),
            TeamMethod.RELEASE_TEAM_TICKETS, List.of(TeamCallResult.OK, TeamCallResult.OVERLOADED, TeamCallResult.ERROR),
            TeamMethod.RUN_TEAM_GATHER, List.of(TeamCallResult.GATHER_OK, TeamCallResult.GATHER_FAILED));

    /**
     * 一条对局结果的入账结局（没有基线的 {@code partial}：Java 一局一笔事务）。
     * <ul>
     *   <li>{@code ERROR}：一次入账尝试失败。可恢复故障每重试一次就记一次，库抖动时会成百上千地涨，本身不代表丢了数据；</li>
     *   <li>{@code REJECTED}：这一局被数据库判为数据错误（SQLState 22 / 23），<b>已跳过、位点已提交、永不入账</b>（完整字节在毒丸日志里）。
     *       它与 {@code DECODE_ERROR} 是仅有的两种「一条结果被永久丢弃」，任何增量都该报警；那一次失败同时也记了一次 {@code ERROR}。</li>
     * </ul>
     */
    public enum RatingOutcome { APPLIED, DUPLICATE, IGNORED, ERROR, DECODE_ERROR, REJECTED }

    /** dev 管理口的接口（其余路径一律 {@code other}）。 */
    public enum AdminOp { RATING, ACTIVITY_BATTLE, OTHER }

    // ---------------------------------------------------------------- 观战（批次 6.5，spectate-spec §6）

    /**
     * 163 的出口（{@code xm_match_watch_battle_total{outcome}}；每个请求恰好记一个，对应 spectate-spec §3.1 各行的「指标 outcome」一列）。
     * <ul>
     *   <li>{@code INTERNAL}：16004——身份缺失、依赖故障、<b>剩余预算不够发下一跳</b>；处理器内的未预期异常（信封 1003）也记它；</li>
     *   <li>{@code QUEUED}：16014（入口持票、抢标记时有票、复查命中票据或锁）；{@code IN_BATTLE}：16015；{@code OFFLINE}：16019；</li>
     *   <li>{@code ALREADY_WATCHING}：<b>只计 16016</b>（并发抢占）。入口处对旧标记的懒清退不计它，另计
     *       {@code spectate_evictions{reason="rewatch"}}（W14；基线把两者混在一起）；</li>
     *   <li>{@code NO_BATTLE}：16017；{@code NOT_FOUND}：16018「不存在或已结束」；{@code REJECTED}：16018「当前无法观战」；</li>
     *   <li>{@code OVERLOADED}：在途已满（在处理器的 {@code onOverload()} 里记；这时没有进过处理流程）。Java 新增。</li>
     * </ul>
     */
    public enum WatchOutcome { OK, INTERNAL, QUEUED, IN_BATTLE, ALREADY_WATCHING, OFFLINE, NO_BATTLE, NOT_FOUND, REJECTED, OVERLOADED }

    /**
     * 164 的出口（{@code xm_match_list_watchable_total{result}}）：{@code OK} = 回了列表（含空列表、含批读落点整批失败后变短的列表）；
     * {@code ERROR} = 读索引失败（信封 1003）；{@code OVERLOADED} = 工作池满 / 排队超预算（信封 1003）。
     */
    public enum ListResult { OK, ERROR, OVERLOADED }

    /**
     * 清退的起因（{@code xm_match_spectate_evictions_total{reason}}）；标签值与发给 battle 的 {@code RemoveObserverRequest.reason} 逐字相同
     * （{@code spectate.SpectateRules.REASON_*}）：{@code ENTER_GATHER} = 开局前清退参战者；{@code REWATCH} = 163 入口处清掉旧标记（换场 / 随机）；
     * {@code CONCURRENT_QUEUE} = 163 的自我清退：登记成功后的复查命中票据或战斗锁；或「重看同一场」删掉旧标记之后、抢新标记时发现已有票据
     * （那时观众登记还在、标记已无）。
     */
    public enum EvictReason { ENTER_GATHER, REWATCH, CONCURRENT_QUEUE }

    /**
     * 一次清退的结局（{@code xm_match_spectate_evictions_total{result}}；每处理一条旧标记记一个）：
     * <ul>
     *   <li>{@code REMOVED}：RemoveObserver 调通了（battle 应答了；房间里没有这名观众时 battle 幂等、同样算）；</li>
     *   <li>{@code NO_RECORD}：那一场已经不在了——没有落点记录，或直拨判定所在进程已被别的进程接手（{@code Dead}）：只删标记；</li>
     *   <li>{@code INVALID_MARK}：标记值解析不了：只删标记；</li>
     *   <li>{@code READ_FAILED}：读落点失败或记录损坏：不发 RPC（标记照删；开局清退读全员标记失败是另一回事，记
     *       {@code watchable_anomalies{reason="mark_read_failed"}}）；</li>
     *   <li>{@code RPC_FAILED}：RemoveObserver 没调通（没送达 / 超时 / 断开）：只记日志，名单里的残留随那一场结束清理。</li>
     * </ul>
     * 163「显式重看同一场」只删标记、不发 Remove，不算清退，不计（随后抢标记发现有票而补发的自我清退按 {@code CONCURRENT_QUEUE} 计）。
     */
    public enum EvictResult { REMOVED, NO_RECORD, INVALID_MARK, READ_FAILED, RPC_FAILED }

    /**
     * 可观战索引里摘掉成员的原因（{@code xm_match_watchable_index_evictions_total{reason}}；计的是<b>摘掉的成员数</b>）：
     * {@code ROOM_MISSING} = 163 登记时 battle 回「房间不存在」且已出建房窗口；{@code DEAD_NODE} = 同上但依据是直拨判死；
     * {@code MISSING_RECORD} = 落点记录已不在（163 / 164 读到）；{@code STALE} = 164 读到分数已过期的成员；
     * {@code INVALID_MEMBER} = 成员不是合法的 battle_id；{@code SWEEP} = 定时清扫摘掉的过期成员。
     * 同步的剔除只在存储回报「真的摘了」时计；164 的异步剔除等不到结果，按<b>发出</b>计。
     */
    public enum IndexEviction { ROOM_MISSING, DEAD_NODE, STALE, MISSING_RECORD, INVALID_MEMBER, SWEEP }

    /**
     * 观战数据的异常（{@code xm_match_watchable_anomalies_total{reason}}；长期非 0 需要排查）：{@code CORRUPT_RECORD} = 落点记录在但损坏
     * （163 回 16004、列表跳过，都不剔除，BW9 / W15）；{@code RECORD_READ_FAILED} = 164 批读落点整批失败（回变短的列表）；
     * {@code PUBLISH_FAILED} = 开局成功后登记进索引失败（这一场不进列表）；{@code MARK_READ_FAILED} = 开局清退读全员标记失败（全部跳过）。
     */
    public enum WatchAnomaly { CORRUPT_RECORD, RECORD_READ_FAILED, PUBLISH_FAILED, MARK_READ_FAILED }

    /** 观众 RPC 的方法（{@code xm_match_observer_rpc_total{method}}）。 */
    public enum ObserverMethod { ADD, REMOVE }

    /**
     * 一次观众 RPC 的结局（{@code xm_match_observer_rpc_total{result}}；与 {@code spectate.ObserverDialer.Outcome} 的四种一一对应，由直拨器的实现记，
     * 调用方不重复记）：{@code REPLIED} = 调通了（battle 的业务拒绝也算）；{@code DEAD} = 请求没送达、同号节点已换实例、原地址明确连不上；
     * {@code NOT_DELIVERED} = 请求确定没送达但判不了死；{@code UNKNOWN} = 超时或连上之后失败（结局不明）。
     */
    public enum ObserverResult { REPLIED, DEAD, NOT_DELIVERED, UNKNOWN }

    // ================================================================ 状态

    private final MeterRegistry registry;
    private final MetricLabels labels;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DistributionSummary> summaries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final AtomicInteger ratingConsumerPaused = new AtomicInteger();
    private final AtomicInteger leaseLost = new AtomicInteger();
    private final AtomicInteger nodesAccepting = new AtomicInteger();
    private final AtomicInteger nodesNotAccepting = new AtomicInteger();
    private volatile IntSupplier inflightGathers = () -> 0;
    private final AtomicLong watchableBattles = new AtomicLong();
    private volatile IntSupplier inflightWatches = () -> 0;

    /**
     * @param labels 标签净化（{@code config} 标签要查 Dungeon 表）
     */
    public MatchMetrics(MeterRegistry registry, MetricLabels labels) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.labels = Objects.requireNonNull(labels, "labels");
        preregister();
        Gauge.builder(RATING_CONSUMER_PAUSED, ratingConsumerPaused, AtomicInteger::get)
                .description("评分消费者因可恢复的库故障暂停中（1 = 暂停：评分整体停更，排队照常；持续为 1 要告警）").register(registry);
        Gauge.builder(LEASE_LOST, leaseLost, AtomicInteger::get)
                .description("发号租约是否已真正丢失（1 = 已丢失，不会自愈：开局类入口与排队一律拒，需要重启本进程）").register(registry);
        Gauge.builder(BATTLE_NODES, nodesAccepting, AtomicInteger::get).tag("state", "accepting")
                .description("battle 节点目录概况（凑单每轮刷新）：accepting = 可分配，not_accepting = 在册但关闸中").register(registry);
        Gauge.builder(BATTLE_NODES, nodesNotAccepting, AtomicInteger::get).tag("state", "not_accepting")
                .description("battle 节点目录概况（凑单每轮刷新）：accepting = 可分配，not_accepting = 在册但关闸中").register(registry);
        Gauge.builder(GATHERS_INFLIGHT, this, m -> m.inflightGathers.getAsInt())
                .description("在途的 gather 数（上限 xm.match.gather-max-inflight）").register(registry);
        Gauge.builder(WATCHABLE_BATTLES, watchableBattles, AtomicLong::get)
                .description("可观战索引的大小（清扫时采样 ZCARD；每个实例各自采样同一个全局 ZSET：多实例时看板取 max，不能 sum）").register(registry);
        Gauge.builder(SPECTATE_INFLIGHT, this, m -> m.inflightWatches.getAsInt())
                .description("在途的 163 观战请求数（上限 xm.match.spectate.max-inflight；每实例的真实值）").register(registry);
    }

    private void preregister() {
        for (String method : MatchMethods.ALL) {
            for (RequestResult result : RequestResult.values()) {
                if (result != RequestResult.UNSUPPORTED) {
                    requestTimer(method, result);
                }
            }
        }
        requestTimer(MetricLabels.UNKNOWN, RequestResult.UNSUPPORTED);
        for (int mode : List.of(MatchModes.FIVE_V_FIVE, MatchModes.ONE_V_ONE, MatchModes.PVE_SOLO, MatchModes.PVE_TEAM)) {
            for (JoinOutcome outcome : JoinOutcome.values()) {
                joinCounter(MetricLabels.mode(mode), outcome);
            }
        }
        for (MatcherRound result : MatcherRound.values()) {
            counter(MATCHER_ROUNDS, "凑单每轮的结局（paused_* = 本轮在抢锁之前就暂停，队列原样保留）", "result", tag(result));
        }
        for (RequeueReason reason : RequeueReason.values()) {
            counter(REQUEUED, "gather 失败后回队首的人数", "reason", tag(reason));
        }
        for (DropReason reason : DropReason.values()) {
            counter(QUEUE_DROPPED, "凑单剔出队列的成员数", "reason", tag(reason));
        }
        for (QueueAnomaly reason : QueueAnomaly.values()) {
            counter(QUEUE_ANOMALIES, "队列数据异常（长期非 0 需要排查）", "reason", tag(reason));
        }
        for (int mode : List.of(MatchModes.FIVE_V_FIVE, MatchModes.ONE_V_ONE, MatchModes.PVE_SOLO, MatchModes.PVE_TEAM, MatchModes.PVP_CHALLENGE)) {
            for (GatherOutcome outcome : GatherOutcome.values()) {
                gatherCounter(MetricLabels.mode(mode), outcome);
            }
            for (ZoneMix mix : ZoneMix.values()) {
                counter(GATHER_ZONE_MIX, "一局的成员同区（single）还是跨区（cross）", "mode", MetricLabels.mode(mode), "mix", tag(mix));
            }
        }
        for (FingerprintMode mode : List.of(FingerprintMode.WARN, FingerprintMode.ENFORCE)) {
            fingerprintCounter(mode);
        }
        for (ReissueResult result : ReissueResult.values()) {
            counter(REISSUES, "179 战斗票据补签的出口", "result", tag(result));
        }
        CHALLENGE_RESULTS.forEach((stage, results) -> results.forEach(result -> challengeCounter(stage, result)));
        for (PushKind kind : PushKind.values()) {
            for (PushOutcome outcome : PushOutcome.values()) {
                pushCounter(kind, outcome);
            }
        }
        for (String kind : List.of("guild_trial", "none", MetricLabels.UNKNOWN)) {
            for (ActivityResult result : ActivityResult.values()) {
                activityCounter(kind, result);
            }
        }
        TEAM_RESULTS.forEach((method, results) -> results.forEach(result -> teamCounter(method, result)));
        for (int mode : List.of(MatchModes.FIVE_V_FIVE, MatchModes.ONE_V_ONE)) {
            for (RatingOutcome outcome : RatingOutcome.values()) {
                ratingCounter(MetricLabels.mode(mode), outcome);
            }
            roundCapCounter(MetricLabels.mode(mode));
        }
        ratingCounter(MetricLabels.UNKNOWN, RatingOutcome.DECODE_ERROR);
        for (AdminOp op : AdminOp.values()) {
            for (String status : ADMIN_STATUSES) {
                adminCounter(op, status);
            }
        }
        for (WatchOutcome outcome : WatchOutcome.values()) {
            watchCounter(outcome);
        }
        for (ListResult result : ListResult.values()) {
            listCounter(result);
        }
        for (EvictReason reason : EvictReason.values()) {
            for (EvictResult result : EvictResult.values()) {
                evictionCounter(reason, result);
            }
        }
        for (IndexEviction reason : IndexEviction.values()) {
            indexEvictionCounter(reason);
        }
        for (WatchAnomaly reason : WatchAnomaly.values()) {
            anomalyCounter(reason);
        }
        for (ObserverMethod method : ObserverMethod.values()) {
            for (ObserverResult result : ObserverResult.values()) {
                observerCounter(method, result);
            }
        }
    }

    // ================================================================ 请求

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /** 记一次客户端请求的耗时与结局。{@code method} 传契约方法名（不认识的一律归 {@code unknown}）。 */
    public void requestCompleted(Timer.Sample sample, String method, RequestResult result) {
        sample.stop(requestTimer(MetricLabels.method(method), result));
    }

    /** 157 的一个出口。{@code mode} 传请求里的原始数值（在这里净化）。 */
    public void joinQueue(int mode, JoinOutcome outcome) {
        joinCounter(MetricLabels.mode(mode), outcome).increment();
    }

    // ================================================================ 凑单

    /** 这条队列的长度。持锁实例写实值；抢不到锁、或队列被剔除时写 0。 */
    public void queueDepth(int mode, int configId, long depth) {
        gauge(QUEUE_DEPTH, "排队队列长度（只有持有凑单锁的实例写实值，其余实例置 0）", null, mode, configId).set(Math.max(0, depth));
    }

    /** 这条队列本轮凑不到人的锚点里等得最久的秒数（没有为 0）。抢不到锁的实例写 0。 */
    public void starvedAnchorWait(int mode, int configId, long seconds) {
        gauge(STARVED_ANCHOR_WAIT, "本轮凑不到候选的锚点里等得最久的秒数（0 = 没有；持续上升 = 有人在饿）", "seconds", mode, configId)
                .set(Math.max(0, seconds));
    }

    /** 成组：锚点已等的秒数。 */
    public void matchWait(int mode, long seconds) {
        summary(WAIT, "成组时锚点已等待的秒数", "seconds", WAIT_BUCKETS, MetricLabels.mode(mode)).record(Math.max(0, seconds));
    }

    /**
     * 成组（只对评分模式）：组内最高分与最低分之差。
     *
     * @param spreadCenti 极差 × 100（与票据、评分镜像同单位；这里换算成评分点再入桶）
     */
    public void groupRatingSpread(int mode, long spreadCenti) {
        summary(GROUP_RATING_SPREAD, "成组时组内评分的极差（评分点）", null, SPREAD_BUCKETS, MetricLabels.mode(mode))
                .record(Math.max(0, spreadCenti) / 100.0);
    }

    public void matcherRound(MatcherRound result) {
        counter(MATCHER_ROUNDS, null, "result", tag(result)).increment();
    }

    /** gather 失败后回队首的人数。 */
    public void requeued(RequeueReason reason, int players) {
        if (players > 0) {
            counter(REQUEUED, null, "reason", tag(reason)).increment(players);
        }
    }

    public void queueDropped(DropReason reason) {
        counter(QUEUE_DROPPED, null, "reason", tag(reason)).increment();
    }

    public void queueAnomaly(QueueAnomaly reason) {
        counter(QUEUE_ANOMALIES, null, "reason", tag(reason)).increment();
    }

    /** battle 目录概况（凑单每轮读一次）。读失败时两个数都传 0。 */
    public void battleNodes(int accepting, int notAccepting) {
        nodesAccepting.set(Math.max(0, accepting));
        nodesNotAccepting.set(Math.max(0, notAccepting));
    }

    // ================================================================ gather

    /** 一次 gather 结束（成功、失败并补偿完、或 overloaded）：计数并记耗时。 */
    public void gatherCompleted(int mode, GatherOutcome outcome, Duration elapsed) {
        String modeLabel = MetricLabels.mode(mode);
        gatherCounter(modeLabel, outcome).increment();
        timer(GATHER_DURATION, "gather 从启动到结束的耗时（失败的含补偿）", GATHER_BUCKETS, "mode", modeLabel, "outcome", outcome.label())
                .record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }

    /** 接上在途 gather 数的来源（开局管线启动时调一次）。 */
    public void bindInflightGathers(IntSupplier inflight) {
        this.inflightGathers = Objects.requireNonNull(inflight, "inflight");
    }

    public void gatherZoneMix(int mode, ZoneMix mix) {
        counter(GATHER_ZONE_MIX, null, "mode", MetricLabels.mode(mode), "mix", tag(mix)).increment();
    }

    /** 一组成员的配表指纹不一致（含部分为空）。{@code OFF} 不比对，传进来也不计。 */
    public void fingerprintMismatch(FingerprintMode mode) {
        if (mode != FingerprintMode.OFF) {
            fingerprintCounter(mode).increment();
        }
    }

    // ================================================================ 补签、切磋、内部接口

    public void reissue(ReissueResult result) {
        counter(REISSUES, null, "result", tag(result)).increment();
    }

    public void challenge(ChallengeStage stage, ChallengeResult result) {
        challengeCounter(stage, result).increment();
    }

    /** 一次推送的结局（stage 正常完成）。 */
    public void push(PushKind kind, PlayerPushes.Outcome outcome) {
        pushCounter(kind, switch (outcome) {
            case SENT -> PushOutcome.SENT;
            case OFFLINE -> PushOutcome.OFFLINE;
            case GATE_UNREACHABLE -> PushOutcome.GATE_UNREACHABLE;
        }).increment();
    }

    /** 一次推送的 stage 异常完成（Redis 故障、在线目录条目损坏）。 */
    public void pushError(PushKind kind) {
        pushCounter(kind, PushOutcome.ERROR).increment();
    }

    /** @param kindValue {@code BattleActivityContext.kind} 的数值（{@code getKindValue()}；没有上下文传 0） */
    public void activityBattle(int kindValue, ActivityResult result) {
        activityCounter(MetricLabels.activityKind(kindValue), result).increment();
    }

    public void teamCall(TeamMethod method, TeamCallResult result) {
        teamCounter(method, result).increment();
    }

    // ================================================================ 评分

    /** 一条对局结果的入账结局。解不出消息时模式未知：{@code mode} 传 -1。 */
    public void ratingUpdate(int mode, RatingOutcome outcome) {
        ratingCounter(MetricLabels.mode(mode), outcome).increment();
    }

    /** 一局胜负结果因「回合打满」被改按平局结算。 */
    public void ratingRoundCapDraw(int mode) {
        roundCapCounter(MetricLabels.mode(mode)).increment();
    }

    public void ratingConsumerPaused(boolean paused) {
        ratingConsumerPaused.set(paused ? 1 : 0);
    }

    // ================================================================ 进程

    public void leaseLost(boolean lost) {
        leaseLost.set(lost ? 1 : 0);
    }

    public void adminRequest(AdminOp op, int status) {
        adminCounter(op, Integer.toString(status)).increment();
    }

    // ================================================================ 观战（批次 6.5）

    /** 163 的一个出口（每个请求恰好一次；取值口径见 {@link WatchOutcome}）。 */
    public void watchBattle(WatchOutcome outcome) {
        watchCounter(outcome).increment();
    }

    /** 164 的一个出口（每个请求恰好一次）。 */
    public void listWatchable(ListResult result) {
        listCounter(result).increment();
    }

    /** 处理了一条旧的观战标记（开局前清退 / 163 换场 / 163 复查命中）：起因与结局，口径见 {@link EvictResult}。 */
    public void spectateEviction(EvictReason reason, EvictResult result) {
        evictionCounter(reason, result).increment();
    }

    /** 从可观战索引里摘掉了 {@code members} 个成员（≤ 0 不计；口径见 {@link IndexEviction}）。清扫一轮摘掉多少就传多少。 */
    public void watchableIndexEvicted(IndexEviction reason, long members) {
        if (members > 0) {
            indexEvictionCounter(reason).increment(members);
        }
    }

    /** 一次观战数据异常。 */
    public void watchableAnomaly(WatchAnomaly reason) {
        anomalyCounter(reason).increment();
    }

    /** 可观战索引此刻的大小（清扫器每轮采样一次 ZCARD；读失败时不调，gauge 停在上一次的读数）。 */
    public void watchableBattles(long count) {
        watchableBattles.set(Math.max(0, count));
    }

    /** 一次观众 RPC 的结局（直拨器的实现记；同步的与异步发出的都记）。 */
    public void observerRpc(ObserverMethod method, ObserverResult result) {
        observerCounter(method, result).increment();
    }

    /** 接上在途 163 数的来源（163 的执行器启动时调一次；没接之前读数恒为 0）。 */
    public void bindSpectateInflight(IntSupplier inflight) {
        this.inflightWatches = Objects.requireNonNull(inflight, "inflight");
    }

    // ================================================================ 内部

    private Timer requestTimer(String method, RequestResult result) {
        return timer(REQUESTS, "match 处理客户端请求的耗时与结局（从受理到应答，含工作队列排队）", REQUEST_BUCKETS, "method", method, "result",
                tag(result));
    }

    private Counter joinCounter(String mode, JoinOutcome outcome) {
        return counter(JOIN_QUEUE, "排队 157 的出口", "mode", mode, "outcome", tag(outcome));
    }

    private Counter gatherCounter(String mode, GatherOutcome outcome) {
        return counter(GATHERS, "开局管线的结局", "mode", mode, "outcome", outcome.label());
    }

    private Counter fingerprintCounter(FingerprintMode mode) {
        return counter(FINGERPRINT_MISMATCHES, "成员之间战斗配表指纹不一致的组数（任何非 0 都说明有 scene 节点跑着不同版本的战斗表）", "fp_mode",
                mode.label());
    }

    private Counter challengeCounter(ChallengeStage stage, ChallengeResult result) {
        return counter(CHALLENGES, "切磋各出口", "stage", tag(stage), "result", tag(result));
    }

    private Counter pushCounter(PushKind kind, PushOutcome outcome) {
        return counter(PUSHES, "切磋推送（156 邀请 / 154 结果）的结局；至多一次，sent 不代表客户端收到", "kind", kind.label(), "outcome", tag(outcome));
    }

    private Counter activityCounter(String kind, ActivityResult result) {
        return counter(ACTIVITY_BATTLES, "帮会活动开战（MatchInternalService）的结果", "kind", kind, "result", tag(result));
    }

    private Counter teamCounter(TeamMethod method, TeamCallResult result) {
        return counter(TEAM_CALLS, "整队开战端口（MatchTeamService）各方法的结果", "method", method.label(), "result", tag(result));
    }

    private Counter ratingCounter(String mode, RatingOutcome outcome) {
        return counter(RATING_UPDATES, "对局结果的入账结局", "mode", mode, "outcome", tag(outcome));
    }

    private Counter roundCapCounter(String mode) {
        return counter(RATING_ROUND_CAP_DRAWS, "因回合打满被改按平局结算的计分局数", "mode", mode);
    }

    private Counter adminCounter(AdminOp op, String status) {
        return counter(ADMIN_REQUESTS, "管理端口 /admin/** 的调用（含鉴权失败），按 HTTP 状态计", "op", tag(op), "status", status);
    }

    private Counter watchCounter(WatchOutcome outcome) {
        return counter(WATCH_BATTLE, "观战 163 的出口", "outcome", tag(outcome));
    }

    private Counter listCounter(ListResult result) {
        return counter(LIST_WATCHABLE, "可观战列表 164 的出口", "result", tag(result));
    }

    private Counter evictionCounter(EvictReason reason, EvictResult result) {
        return counter(SPECTATE_EVICTIONS, "清退观众（开局前 / 换场 / 复查命中）的结局；removed = RemoveObserver 调通了", "reason", tag(reason),
                "result", tag(result));
    }

    private Counter indexEvictionCounter(IndexEviction reason) {
        return counter(WATCHABLE_INDEX_EVICTIONS, "从可观战索引里摘掉的成员数", "reason", tag(reason));
    }

    private Counter anomalyCounter(WatchAnomaly reason) {
        return counter(WATCHABLE_ANOMALIES, "观战数据异常（落点损坏 / 批读失败 / 登记索引失败 / 读标记失败；长期非 0 需要排查）", "reason", tag(reason));
    }

    private Counter observerCounter(ObserverMethod method, ObserverResult result) {
        return counter(OBSERVER_RPC, "发给 battle 的观众 RPC（addObserver / removeObserver）的结局；replied 含 battle 的业务拒绝", "method", tag(method),
                "result", tag(result));
    }

    /** 取（或第一次时建）一个计数器。{@code tags} 是「键, 值, 键, 值…」。 */
    private Counter counter(String name, String description, String... tags) {
        String key = key(name, tags);
        Counter counter = counters.get(key);
        if (counter == null) {
            counter = counters.computeIfAbsent(key, k -> Counter.builder(name).description(description).tags(tags).register(registry));
        }
        return counter;
    }

    private Timer timer(String name, String description, Duration[] buckets, String... tags) {
        String key = key(name, tags);
        Timer timer = timers.get(key);
        if (timer == null) {
            timer = timers.computeIfAbsent(key, k -> Timer.builder(name).description(description).tags(tags)
                    .serviceLevelObjectives(buckets).register(registry));
        }
        return timer;
    }

    private DistributionSummary summary(String name, String description, String baseUnit, double[] buckets, String mode) {
        String key = key(name, "mode", mode);
        DistributionSummary summary = summaries.get(key);
        if (summary == null) {
            summary = summaries.computeIfAbsent(key, k -> DistributionSummary.builder(name).description(description).baseUnit(baseUnit)
                    .tag("mode", mode).serviceLevelObjectives(buckets).register(registry));
        }
        return summary;
    }

    private AtomicLong gauge(String name, String description, String baseUnit, int mode, int configId) {
        String modeLabel = MetricLabels.mode(mode);
        String configLabel = labels.config(configId);
        String key = key(name, modeLabel, configLabel);
        AtomicLong value = gauges.get(key);
        if (value == null) {
            value = gauges.computeIfAbsent(key, k -> {
                AtomicLong holder = new AtomicLong();
                Gauge.builder(name, holder, AtomicLong::get).description(description).baseUnit(baseUnit)
                        .tag("mode", modeLabel).tag("config", configLabel).register(registry);
                return holder;
            });
        }
        return value;
    }

    private static String key(String name, String... parts) {
        StringBuilder key = new StringBuilder(name);
        for (String part : parts) {
            key.append('\0').append(part);
        }
        return key.toString();
    }

    static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
