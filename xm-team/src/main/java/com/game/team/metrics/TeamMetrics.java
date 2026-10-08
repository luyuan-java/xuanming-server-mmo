package com.game.team.metrics;

import com.game.team.rules.TeamTips;
import com.game.team.service.TeamMethods;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * xm-team 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出；team-spec §7.2）。指标名与标签只在这里定义。
 *
 * <p>对应基线 go/match/internal/metrics/metrics.go:158-211：
 * <ul>
 *   <li>{@code xm_team_requests_seconds{method, result}} ← {@code team_rpc_total{method, outcome}}（Java 改成 Timer，结果分类照 friend）；</li>
 *   <li>{@code xm_team_commit_retries_total{op}} ← {@code team_commit_retry_total}；</li>
 *   <li>{@code xm_team_heals_total{kind}} ← {@code team_heal_total}；</li>
 *   <li>{@code xm_team_pushes_total{kind, outcome}} ← {@code team_push_total}；</li>
 *   <li>{@code xm_team_matches_total{outcome}} ← {@code team_match_total}（多一个 {@code gather_unknown}：跨进程才有的故障面）；</li>
 *   <li>{@code xm_team_cross_zone_allowed} ← {@code team_cross_zone_allowed}（启动即上报本实例生效的开关）。</li>
 * </ul>
 * {@code team_scene_refresh_total} 不移植：Java 不发 scene 刷新信号（D7）。
 *
 * <p>全部可能出现的标签组合启动时预建为 0（基线不预建；不预建的话「从未发生」的序列不存在，{@code rate(...) > 0} 的告警既不报警也不报错）。
 * <b>标签基数</b>（AGENTS.md §5）：不以 player_id / team_id 作标签；{@code method} 只取 {@link TeamMethods} 的方法名或 {@link #UNROUTED}。
 * 线程安全。
 */
public final class TeamMetrics {

    static final String REQUESTS = "xm.team.requests";
    static final String COMMIT_RETRIES = "xm.team.commit.retries";
    static final String HEALS = "xm.team.heals";
    static final String PUSHES = "xm.team.pushes";
    static final String MATCHES = "xm.team.matches";
    static final String CROSS_ZONE_ALLOWED = "xm.team.cross.zone.allowed";

    /** 不认识的消息号（不归 team 或契约里没有）。 */
    public static final String UNROUTED = "unrouted";

    /** 与 gate / login / friend 同一套 SLO 桶（5ms～10s）。 */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 一个客户端请求的结果（{@code xm.team.requests{result}}，team-spec §6.3 / §7.2）。 */
    public enum RequestResult {
        /** 应答体没有 error_message（成功）。 */
        OK,
        /** 应答体是非 4030 的 tip（业务拒绝，含 4029）。 */
        BUSINESS_ERROR,
        /** 应答体是 4030（team 段唯一的故障码）：依赖故障或处理器异常。 */
        INTERNAL_ERROR,
        /** 工作队列满或请求在队列里等过了预算（in-band 4030，D12）。 */
        OVERLOADED,
        /** 请求体解析失败（信封 1003）。 */
        BAD_REQUEST,
        /** 会话没有绑定玩家（player_id = 0）：in-band 4001、不带视图（基线 outcome=no_session）。 */
        UNAUTHENTICATED,
        /** 客户端上行了服务端推送的消息号 203 / 213 / 215（空操作，D13；基线不计）。 */
        FORBIDDEN,
        /** 不认识的消息号。 */
        UNSUPPORTED
    }

    /** 自愈类型（基线 notify.go:55-56）。 */
    public enum HealKind {
        /** S_HEAL_ORPHAN：索引指向已不存在的记录。 */
        ORPHAN_INDEX,
        /** {@code {-2}} 修复提交：保留成员的索引已指向别队。 */
        INDEX_MISMATCH
    }

    /** 推送种类（基线 notify.go:39-42）。 */
    public enum PushKind {
        /** 213 NotifyTeamSnapshot。 */
        SNAPSHOT,
        /** 215 NotifyTeamInvite。 */
        INVITE,
        /** 203 NotifyTeamEvent。 */
        EVENT,
        /** MEMBER_ONLINE / 开战结果推送读成员时成员表持续变化，整批放弃（只配 {@link PushOutcome#SKIPPED}）。 */
        MEMBERS_CHANGED
    }

    /** 推送结局（基线 notify.go:44-47；PlayerPushes 的映射见 team-spec §6.8）。 */
    public enum PushOutcome {
        /** SENT：已发布到在订阅的 gate。 */
        OK,
        /** OFFLINE：玩家不在线，没推（不算错误）。 */
        OFFLINE,
        /** GATE_UNREACHABLE / 异常 / 超出批预算 / 执行器拒绝。 */
        ERROR,
        /** 放弃（只配 {@link PushKind#MEMBERS_CHANGED}）。 */
        SKIPPED
    }

    /**
     * 整队开战的终态（基线 service.go:70-74、team-spec §5.2；match-spec §11 末行）。一次 211 恰好计一次：同步拒绝计前三个之一；
     * 建票失败计 {@link #TICKET_FAILED}；已受理（回了 STARTING）的在 gather 收尾时计后三个之一。
     */
    public enum MatchOutcome {
        /** 同步拒绝：业务码（4013 / 4018 / 4023–4029）。 */
        REJECTED,
        /** 同步拒绝：故障码 4030（含 xm-match 调不通、应答缺字段、建票结果不明）。 */
        INTERNAL,
        /** 同步拒绝：码不在 team 段内（码表漂移）。 */
        UNKNOWN_CODE,
        /** 开局成功：gather 回 ok。 */
        SUCCESS,
        /** gather 明确失败（xm-match 已全员删票）。 */
        GATHER_FAILED,
        /** 建票失败：成员已有别的票据，或 xm-match 自己的 Redis 出错（都回 4026[pid]）。 */
        TICKET_FAILED,
        /**
         * gather 结果不明：{@code runTeamGather} 传输失败（xm-match 中途退出、网络分区、调用超时）。Java 独有（基线 match 与 team 同进程，
         * 没有这个故障面，match-spec M20）；照样推 MATCH_FAILED，但 match 可能仍在跑这局。
         */
        GATHER_UNKNOWN
    }

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> requests = new ConcurrentHashMap<>();
    private final Map<String, Counter> commitRetries = new HashMap<>();
    private final Map<HealKind, Counter> heals = new EnumMap<>(HealKind.class);
    private final Map<PushKind, Map<PushOutcome, Counter>> pushes = new EnumMap<>(PushKind.class);
    private final Map<MatchOutcome, Counter> matches = new EnumMap<>(MatchOutcome.class);
    private final AtomicInteger crossZoneAllowed = new AtomicInteger();

    /**
     * @param allowCrossZone 本实例生效的 {@code xm.team.allow-cross-zone}（{@code xm_team_cross_zone_allowed} 的值：1 允许 / 0 禁止）
     */
    public TeamMetrics(MeterRegistry registry, boolean allowCrossZone) {
        this.registry = registry;
        for (String method : TeamMethods.REQUESTS) {
            for (RequestResult result : List.of(RequestResult.OK, RequestResult.BUSINESS_ERROR, RequestResult.INTERNAL_ERROR,
                    RequestResult.OVERLOADED, RequestResult.BAD_REQUEST, RequestResult.UNAUTHENTICATED)) {
                timer(method, result);
            }
        }
        for (String method : TeamMethods.PUSHES) {
            timer(method, RequestResult.FORBIDDEN);
            timer(method, RequestResult.BAD_REQUEST);
        }
        timer(UNROUTED, RequestResult.UNSUPPORTED);
        for (String op : TeamMethods.REQUESTS) {
            commitRetries.put(op, Counter.builder(COMMIT_RETRIES)
                    .description("S_COMMIT 版本冲突（{0}）的次数，含修复提交的冲突；持续升高说明同队并发写多")
                    .tag("op", op).register(registry));
        }
        for (HealKind kind : HealKind.values()) {
            heals.put(kind, Counter.builder(HEALS)
                    .description("组队存储自愈：orphan_index = 孤儿索引置 0，index_mismatch = 修复提交移出索引已在别队的成员")
                    .tag("kind", tagValue(kind)).register(registry));
        }
        for (PushKind kind : PushKind.values()) {
            Map<PushOutcome, Counter> byOutcome = new EnumMap<>(PushOutcome.class);
            List<PushOutcome> outcomes = kind == PushKind.MEMBERS_CHANGED
                    ? List.of(PushOutcome.SKIPPED) : List.of(PushOutcome.OK, PushOutcome.OFFLINE, PushOutcome.ERROR);
            for (PushOutcome outcome : outcomes) {
                byOutcome.put(outcome, Counter.builder(PUSHES)
                        .description("组队 S2C 推送的结局：ok = 已发布到在订阅的 gate，offline = 不在线，error = 依赖故障 / 没有订阅者 / "
                                + "超出批预算，skipped = 成员表持续变化放弃整批")
                        .tag("kind", tagValue(kind)).tag("outcome", tagValue(outcome)).register(registry));
            }
            pushes.put(kind, byOutcome);
        }
        for (MatchOutcome outcome : MatchOutcome.values()) {
            matches.put(outcome, Counter.builder(MATCHES)
                    .description("StartTeamMatch 的终态：rejected / internal / unknown_code = 同步拒绝，ticket_failed = 建票失败，"
                            + "success / gather_failed = gather 的结论，gather_unknown = 调 xm-match 的 gather 传输失败（结果不明）")
                    .tag("outcome", tagValue(outcome)).register(registry));
        }
        crossZoneAllowed.set(allowCrossZone ? 1 : 0);
        Gauge.builder(CROSS_ZONE_ALLOWED, crossZoneAllowed, AtomicInteger::get)
                .description("本实例生效的 xm.team.allow-cross-zone（1 允许 / 0 禁止）；多实例取值不一致即开关切换的混跑窗口")
                .register(registry);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void requestCompleted(Timer.Sample sample, String method, RequestResult result) {
        sample.stop(timer(method, result));
    }

    /** 一次 S_COMMIT 版本冲突（op = RPC 名；未登记的名字不计，防止 label 失控）。 */
    public void commitRetry(String op) {
        Counter counter = commitRetries.get(op);
        if (counter != null) {
            counter.increment();
        }
    }

    public void heal(HealKind kind) {
        heals.get(kind).increment();
    }

    /** 一次推送结局；kind 与 outcome 的非法组合（如 members_changed / ok）不计。 */
    public void push(PushKind kind, PushOutcome outcome) {
        Counter counter = pushes.get(kind).get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }

    public void match(MatchOutcome outcome) {
        matches.get(outcome).increment();
    }

    /**
     * 同步拒绝的定性（基线 server.go:73-87 rpcOutcome：按 tip 码表定性；Java 没有 Tip 表的 fault 列，故障集合写死为 {4030}）：
     * 故障 → internal；team 段内的其余码 → rejected；段外 → unknown_code。
     */
    public static MatchOutcome rejectOutcome(int code) {
        if (TeamTips.isFault(code)) {
            return MatchOutcome.INTERNAL;
        }
        return TeamTips.isTeamCode(code) ? MatchOutcome.REJECTED : MatchOutcome.UNKNOWN_CODE;
    }

    private Timer timer(String method, RequestResult result) {
        String key = method + '\0' + result.name();
        Timer timer = requests.get(key);
        if (timer == null) {
            timer = requests.computeIfAbsent(key, k -> Timer.builder(REQUESTS)
                    .description("team 处理客户端请求的耗时与结果（从受理到应答，含工作队列排队）")
                    .tag("method", method).tag("result", tagValue(result))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        return timer;
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
