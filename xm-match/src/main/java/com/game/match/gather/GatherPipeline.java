package com.game.match.gather;

import com.game.api.BattleNodeService;
import com.game.api.match.MatchBudgets;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.CreateBattleResult;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.PlacementStore;
import com.game.match.port.NodeCalls;
import com.game.match.port.RedisClock;
import com.game.match.proto.BattlePlacement;
import com.game.match.rating.RatingReader;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketStore;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.match.MatchMode;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开局管线本体（match-spec §3.2、§3.3、§9.6；基线 {@code runGatherWithOptions}，{@code gather.go:162-394}）。五个入口汇入这一条直线式的流程，
 * 步骤与基线逐段对号：
 *
 * <pre>
 * 1    battle_id：活动入口用预发的号，其余由本进程发号；发不出 → internal（不许拿 0 顶替）
 * 2    选 battle 节点（只从可分配的条目里等概率选）；没有 → no_battle_node。读一次 Redis 时间：
 *      deadline = 时间 + 300 s（= 177 的 expire_at_ms），prepare_deadline = 时间 + matched TTL(n)；两者同一次读数、在任何备战之前
 * 2.5  钩子 beforePrepare（6.5 的观战清退；6.4 为空）
 * 2.6  分队（5V5 重读评分后蛇形；1V1 / 切磋按下标；PVE 全员 0 队）
 * 3    逐人<b>串行</b>备战（定位 scene → prepareBattle 3 s）；任何一人失败 → 该人是肇事者，no_location / prepare_failed
 * 3.5  组内 zone 组成（只作观测）
 * 3.6  配表指纹比对（off / warn / enforce）
 * 4    种子（SecureRandom）；再读一次 Redis 时间作 created_at_ms；组建房请求
 * 4.1  <b>先写落点记录</b>（attempt = 1），写不进去就不建房 → index_failed
 * 4.2  createBattle（5 s）；节点级拒绝 → 排除它再选一个节点、把记录改写到新节点（attempt = 2）、<b>只重试一次</b>
 * 4.3  最后一次的结局：仍是节点级拒绝 → not_allocatable；已受理但明确拒绝 → create_rejected（都不发 destroy）；
 *      结局不明（超时 / 传输失败 / 准入字段缺失）→ 对最后尝试的节点发 destroy（3 s）：成功 → create_failed；
 *      失败 → create_failed_room_alive：<b>不解冻、不动票据、保留记录</b>
 * 5    成功：逐人票据置 ready → 同值补写落点记录 → 钩子 onStarted
 * </pre>
 * 除 {@code create_failed_room_alive} 外，每个失败出口都先做完 {@link Compensation}（续期 → 逐人取消 → 票据 → 删记录）再返回。
 *
 * <p><b>必须守住的几条</b>（§12.1）：
 * <ul>
 *   <li><b>建房超时 ≠ 建房失败</b>：结局不明时必须先 destroy 成功才能解冻；destroy 也失败就一律不解冻、不回队——房间可能活着且已向 scene 发了确认，
 *       此时再取消会出现「房间活着、玩家已解冻」。</li>
 *   <li><b>落点记录先于建房</b>：battle 在建房应答之前就向 scene 发确认并推 177，客户端收到 177 之后马上补签也要定位得到房间；
 *       建房之后才发现写不进去，解冻基本无效。</li>
 *   <li><b>判断「不可分配」只看准入枚举</b>；字段缺失按「可能已建房」。</li>
 *   <li><b>换节点只重试一次</b>：整池都不可分配时多试只会把 gather 拖出 matched TTL 的预算（公式按两次建房标定）。</li>
 *   <li><b>备战期限在备战时就下发了</b>，事后改不了：各跳超时是代码常量（{@link MatchBudgets}），改任何一个都要重算整张表。</li>
 *   <li>备战结局不明的人也发取消（M14）；battle 明确拒绝建房时不发 destroy（M15）。</li>
 * </ul>
 *
 * <p><b>线程</b>：{@link #run} 阻塞到 gather 结束（最坏约 matched TTL + 补偿），由 {@link VirtualThreadGatherLauncher} 放在虚拟线程上跑；
 * 每跳都是「异步 API + 在 future 上限时等」，全程不持锁、不在 {@code synchronized} 块里阻塞、不直接跑 JDBC（评分读取经 {@link RatingReader}，
 * 它自己切到平台线程池）。<b>{@link #run} 与 {@link #overloaded} 不抛异常</b>：意外的运行时异常按当时房间的状态收敛
 * （还没发建房 → 补偿后 internal；建房结局未定 → 按房间可能活着处理；已建成 → 成功）。无共享可变状态，多个 gather 并发安全。
 */
public final class GatherPipeline {

    private static final Logger log = LoggerFactory.getLogger(GatherPipeline.class);

    /** 回滚 destroy 的原因串（同基线；只进 battle 的日志）。 */
    static final String ROLLBACK_REASON = "gather_rollback";
    /** 读 Redis 时间的等待上限（算在 matched TTL 公式给 Redis 小操作留的余量里）。 */
    static final long CLOCK_BUDGET_MS = 4_500;
    /** 本地等待比调用超时多给的余量：出站口自己会先以超时失败，这里只防 future 永不完成。 */
    static final long LOCAL_WAIT_GRACE_MS = 250;

    /**
     * 对 battle 的两跳超时。生产恒为 {@link #PRODUCTION}（{@link MatchBudgets} 的常量，出现在 matched TTL 公式里，不开放配置）；测试可以收短。
     */
    record Timeouts(Duration create, Duration destroy) {

        static final Timeouts PRODUCTION = new Timeouts(Duration.ofMillis(MatchBudgets.CREATE_BATTLE_TIMEOUT_MS),
                Duration.ofMillis(MatchBudgets.DESTROY_BATTLE_TIMEOUT_MS));

        Timeouts {
            Objects.requireNonNull(create, "create");
            Objects.requireNonNull(destroy, "destroy");
        }
    }

    /** 管线要用的全部协作者（都是接口或无状态的小件；装配见 {@code GatherConfiguration}）。 */
    public record Parts(MatchIds ids, BattleNodes battleNodes, RedisClock clock, GatherHooks hooks, RatingReader ratings, ScenePreparer scenes,
                        NodeCalls<BattleNodeService> battleCalls, PlacementStore placements, TicketStore tickets, Compensation compensation,
                        MatchMetrics metrics) {

        public Parts {
            Objects.requireNonNull(ids, "ids");
            Objects.requireNonNull(battleNodes, "battleNodes");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(hooks, "hooks");
            Objects.requireNonNull(ratings, "ratings");
            Objects.requireNonNull(scenes, "scenes");
            Objects.requireNonNull(battleCalls, "battleCalls");
            Objects.requireNonNull(placements, "placements");
            Objects.requireNonNull(tickets, "tickets");
            Objects.requireNonNull(compensation, "compensation");
            Objects.requireNonNull(metrics, "metrics");
        }
    }

    private final Parts parts;
    private final FingerprintMode fingerprintMode;
    private final long readyTicketTtlMs;
    private final LongSupplier seeds;
    private final Timeouts timeouts;

    /**
     * 生产装配。
     *
     * @param fingerprintMode  配表指纹的比对策略（{@code xm.match.table-fingerprint-mode}）
     * @param readyTicketTtlMs ready 票据的 TTL（{@code xm.match.ready-ticket-ttl}）
     * @param seeds            战斗种子的来源（生产是 {@code SecureRandom::nextLong}；抛异常 → internal）
     */
    public GatherPipeline(Parts parts, FingerprintMode fingerprintMode, long readyTicketTtlMs, LongSupplier seeds) {
        this(parts, fingerprintMode, readyTicketTtlMs, seeds, Timeouts.PRODUCTION);
    }

    GatherPipeline(Parts parts, FingerprintMode fingerprintMode, long readyTicketTtlMs, LongSupplier seeds, Timeouts timeouts) {
        this.parts = Objects.requireNonNull(parts, "parts");
        this.fingerprintMode = Objects.requireNonNull(fingerprintMode, "fingerprintMode");
        if (readyTicketTtlMs < 1) {
            throw new IllegalArgumentException("ready 票据的 TTL 必须为正: " + readyTicketTtlMs);
        }
        this.readyTicketTtlMs = readyTicketTtlMs;
        this.seeds = Objects.requireNonNull(seeds, "seeds");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    }

    /** 跑一次 gather，阻塞到结束（成功，或失败并补偿完）。不抛异常。 */
    public GatherResult run(GatherPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Run run = new Run(plan);
        try {
            return run.execute();
        } catch (RuntimeException e) {
            return run.unexpected(e);
        }
    }

    /**
     * 拿不到在途许可时的收尾：没发号、没选节点、没冻结任何人，只按入口的策略处置票据（凑单：全员回队首并带退避；PVE_SOLO / 整队 / 活动：删票；
     * 切磋：无事可做）。阻塞（至多几次票据写）；不抛异常。
     */
    public GatherResult overloaded(GatherPlan plan) {
        Objects.requireNonNull(plan, "plan");
        log.warn("[gather] 在途 gather 已到上限，本次不开局 mode={} config={} members={}", plan.mode(), Integer.toUnsignedString(plan.battleConfigId()),
                Compensation.ids(plan.members()));
        try {
            parts.compensation().run(plan, 0, List.of(), 0, false);
        } catch (RuntimeException e) {
            log.error("[gather] 过载收尾时处置票据出错（票据到期自灭） members={}", Compensation.ids(plan.members()), e);
        }
        return GatherResult.failed(GatherOutcome.OVERLOADED, 0);
    }

    // ================================================================ 一次 gather 的状态与流程

    /** 这间房此刻的确定程度：决定意外异常时能不能解冻。 */
    private enum Room {
        /** 确定不存在：还没发过建房，或每一次建房都确认没建成 / 已销毁。 */
        ABSENT,
        /** 建房已发出、结局未定：可能已建成。 */
        UNKNOWN,
        /** 已建成。 */
        BUILT
    }

    /** 选中的 battle 节点：目录条目 + 由它得出的直连目标。 */
    private record Node(BattleNodeInfo info, NodeRpcClients.Target target) {

        String describe() {
            return Integer.toUnsignedString(info.getNodeId()) + "(" + target.address() + "#" + target.instanceId() + ")";
        }
    }

    /** 一次建房调用的结局。 */
    private enum Created { BUILT, NOT_ALLOCATABLE, REJECTED, UNKNOWN }

    /** 一名已冻结的成员。 */
    private record Prepared(long playerId, BattlePlayerSnapshot snapshot, String fingerprint) {
    }

    private final class Run {

        private final GatherPlan plan;
        private final String members;
        /** 要补发取消的人（已冻结的 + 备战结局不明的），名单顺序。 */
        private final List<Compensation.Cancel> cancels = new ArrayList<>();
        private long battleId;
        private boolean placementWritten;
        private Room room = Room.ABSENT;

        Run(GatherPlan plan) {
            this.plan = plan;
            this.members = Compensation.ids(plan.members()).toString();
        }

        GatherResult execute() {
            long startedNanos = System.nanoTime();
            MatchMode mode = plan.mode();
            int n = plan.members().size();

            // 1. battle_id
            battleId = plan.presetBattleId();
            if (battleId == 0) {
                OptionalLong issued = parts.ids().nextBattleId();
                if (issued.isEmpty() || issued.getAsLong() == 0) {
                    log.error("[gather] battle_id 发号失败（租约无效或时钟回拨） mode={} members={}", mode, members);
                    return fail(GatherOutcome.INTERNAL, 0);
                }
                battleId = issued.getAsLong();
            }
            String battle = Long.toUnsignedString(battleId);

            // 2. 选 battle 节点；两个期限取同一次 Redis 时间
            Node node = pick(Set.of());
            if (node == null) {
                log.error("[gather] 没有可分配的 battle 节点 battle_id={} mode={} members={}", battle, mode, members);
                return fail(GatherOutcome.NO_BATTLE_NODE, 0);
            }
            long nowMs;
            try {
                nowMs = parts.clock().nowMs(Deadline.after(CLOCK_BUDGET_MS));
            } catch (Deadline.DependencyException e) {
                log.error("[gather] 读 Redis 时间失败，无法确定期限 battle_id={} mode={} members={}: {}", battle, mode, members, e.toString());
                return fail(GatherOutcome.INTERNAL, 0);
            }
            long deadlineMs = nowMs + TimeUnit.SECONDS.toMillis(MatchBudgets.BATTLE_MAX_DURATION_SECONDS);
            long prepareDeadlineMs = nowMs + TimeUnit.SECONDS.toMillis(MatchBudgets.matchedTicketTtlSeconds(n));

            // 2.5 观战清退的钩子：失败不阻断开局
            try {
                parts.hooks().beforePrepare(plan.members());
            } catch (RuntimeException e) {
                log.error("[gather] beforePrepare 钩子抛出异常（忽略） battle_id={}", battle, e);
            }

            // 2.6 分队
            int[] teams = TeamAssignment.assign(mode, plan.members(), mode == MatchMode.MATCH_MODE_5V5 ? loadRatings(battle) : Map.of());

            // 3. 逐人串行备战
            List<Prepared> prepared = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long playerId = plan.members().get(i);
                PrepareBattleRequest request = PrepareBattleRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId)
                        .setBattleNodeId(node.info().getNodeId()).setDeadlineMs(deadlineMs).setPrepareDeadlineMs(prepareDeadlineMs).build();
                switch (parts.scenes().prepare(request)) {
                    case ScenePreparer.Prepare.Ok ok -> {
                        cancels.add(new Compensation.Cancel(playerId, ok.endpoint()));
                        prepared.add(new Prepared(playerId, ok.response().getSnapshot().toBuilder().setTeamIndex(teams[i]).build(),
                                ok.response().getTableFingerprint()));
                    }
                    case ScenePreparer.Prepare.Failed failed -> {
                        log.error("[gather] 备战失败 battle_id={} player={}（第 {}/{} 人） outcome={} scene={} 补发取消={}: {}", battle,
                                Long.toUnsignedString(playerId), i + 1, n, failed.outcome().label(), failed.endpoint(), failed.cancelNeeded(),
                                failed.detail());
                        if (failed.cancelNeeded() && failed.endpoint() != null) {
                            cancels.add(new Compensation.Cancel(playerId, failed.endpoint()));
                        }
                        return fail(failed.outcome(), playerId);
                    }
                }
            }

            // 3.5 组内 zone 组成（只作观测）
            observeZoneMix(battle, prepared);

            // 3.6 配表指纹
            FingerprintCheck.Result fingerprint = FingerprintCheck.check(fingerprintMode,
                    prepared.stream().map(p -> new FingerprintCheck.Member(p.playerId(), p.fingerprint())).toList());
            if (fingerprint.mismatch()) {
                parts.metrics().fingerprintMismatch(fingerprintMode);
                log.error("[gather] 配表指纹不一致（{}） battle_id={} mode={} majority='{}' suspect={} members={}",
                        fingerprint.proceed() ? "warn：照常开局、不透传指纹" : "enforce：拒绝开局", battle, mode, fingerprint.majority(),
                        Long.toUnsignedString(fingerprint.suspect()),
                        prepared.stream().map(p -> Long.toUnsignedString(p.playerId()) + "='" + p.fingerprint() + "'").toList());
            }
            if (!fingerprint.proceed()) {
                return fail(GatherOutcome.FINGERPRINT_MISMATCH, fingerprint.offender());
            }

            // 4. 种子、created_at_ms、建房请求
            long seed;
            long createdAtMs;
            try {
                seed = seeds.getAsLong();
                createdAtMs = parts.clock().nowMs(Deadline.after(CLOCK_BUDGET_MS));
            } catch (RuntimeException e) {
                log.error("[gather] 生成种子或读 Redis 时间失败 battle_id={} members={}: {}", battle, members, e.toString());
                return fail(GatherOutcome.INTERNAL, 0);
            }
            CreateBattleRequest.Builder create = CreateBattleRequest.newBuilder().setBattleId(battleId).setBattleConfigId(plan.battleConfigId())
                    .setSeed(seed).setMatchMode(mode.getNumber()).setCreatedAtMs(createdAtMs).setDeadlineMs(deadlineMs)
                    .setTableFingerprint(fingerprint.passThrough());
            BattlePlacement.Builder record = BattlePlacement.newBuilder().setBattleId(battleId).setMode(mode.getNumber())
                    .setBattleConfigId(plan.battleConfigId()).setCreatedAtMs(createdAtMs).setDeadlineMs(deadlineMs);
            for (Prepared member : prepared) {
                create.addPlayers(member.snapshot());
                record.addPlayerNames(member.snapshot().getPlayerName());
            }
            if (plan.activityContext() != null) {
                create.setActivityContext(plan.activityContext());
            }
            CreateBattleRequest createRequest = create.build();

            // 4.1 先写落点记录，再建房
            BattlePlacement placement = pointAt(record, node, 1);
            placementWritten = true;
            if (!parts.placements().write(placement)) {
                log.error("[gather] 落点记录写不进去，不建房 battle_id={} node={} members={}", battle, node.describe(), members);
                return fail(GatherOutcome.INDEX_FAILED, 0);
            }

            // 4.2 建房；节点级拒绝时换一个节点重试一次
            room = Room.UNKNOWN;
            Created created = create(node, createRequest);
            if (created == Created.NOT_ALLOCATABLE) {
                room = Room.ABSENT;
                Node next = pick(Set.of(BattleNodes.key(node.info())));
                if (next == null) {
                    log.error("[gather] battle 节点暂不可分配，且没有别的节点可换 battle_id={} node={}", battle, node.describe());
                } else {
                    log.info("[gather] battle 节点暂不可分配，换节点重试一次 battle_id={} from={} to={}", battle, node.describe(), next.describe());
                    placement = pointAt(record, next, 2);
                    if (!parts.placements().write(placement)) {
                        log.error("[gather] 换节点前改写落点记录失败，不再建房 battle_id={} to={} members={}", battle, next.describe(), members);
                        return fail(GatherOutcome.INDEX_FAILED, 0);
                    }
                    node = next;
                    room = Room.UNKNOWN;
                    created = create(node, createRequest);
                }
            }

            // 4.3 最后一次建房的结局
            switch (created) {
                case BUILT -> room = Room.BUILT;
                case NOT_ALLOCATABLE -> {
                    room = Room.ABSENT;
                    return fail(GatherOutcome.NOT_ALLOCATABLE, 0);
                }
                case REJECTED -> {
                    room = Room.ABSENT;
                    return fail(GatherOutcome.CREATE_REJECTED, 0);
                }
                case UNKNOWN -> {
                    if (!destroy(node)) {
                        log.error("[gather] 建房结局不明且销毁也失败：房间可能活着，不解冻、不动票据、保留落点记录，交给 scene / battle 的期限收尾 "
                                + "battle_id={} node={} members={}", battle, node.describe(), members);
                        return GatherResult.failed(GatherOutcome.CREATE_FAILED_ROOM_ALIVE, battleId);
                    }
                    room = Room.ABSENT;
                    return fail(GatherOutcome.CREATE_FAILED, 0);
                }
            }

            // 5. 成功收尾：票据置 ready → 补写落点 → 钩子
            for (TicketRef ticket : plan.ticketRefs()) {
                try {
                    if (!parts.tickets().markReady(ticket, battleId, readyTicketTtlMs, Deadline.after(Compensation.TICKET_OP_BUDGET_MS))) {
                        log.warn("[gather] 票据没有置成 ready（已过期或已被新票替换；不影响开局） battle_id={} player={}", battle,
                                Long.toUnsignedString(ticket.playerId()));
                    }
                } catch (RuntimeException e) {
                    log.error("[gather] 票据置 ready 失败（不影响开局；票据到期自灭） battle_id={} player={}: {}", battle,
                            Long.toUnsignedString(ticket.playerId()), e.toString());
                }
            }
            if (!parts.placements().write(placement)) {
                log.warn("[gather] 开局后补写落点记录失败（建房前已写过，不影响开局） battle_id={}", battle);
            }
            try {
                parts.hooks().onStarted(placement);
            } catch (RuntimeException e) {
                log.error("[gather] onStarted 钩子抛出异常（忽略） battle_id={}", battle, e);
            }
            log.info("[gather] 开局成功 battle_id={} mode={} config={} node={} members={} 耗时={} ms", battle, mode,
                    Integer.toUnsignedString(plan.battleConfigId()), node.describe(), members,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            return GatherResult.success(battleId);
        }

        /** 意外的运行时异常（协作者违反了「不抛」的约定，或本类有 bug）：按房间此刻的确定程度收敛，宁可多冻结一会儿也不解冻一间活着的房。 */
        GatherResult unexpected(RuntimeException e) {
            String battle = Long.toUnsignedString(battleId);
            switch (room) {
                case BUILT -> {
                    log.error("[gather] 房间已建成，收尾时出现意外异常（按开局成功处理） battle_id={} members={}", battle, members, e);
                    return GatherResult.success(battleId);
                }
                case UNKNOWN -> {
                    log.error("[gather] 建房结局未定时出现意外异常：房间可能活着，不解冻、不动票据、保留落点记录 battle_id={} members={}", battle, members, e);
                    return GatherResult.failed(GatherOutcome.CREATE_FAILED_ROOM_ALIVE, battleId);
                }
                default -> {
                    log.error("[gather] 意外异常，按内部错误补偿 battle_id={} members={}", battle, members, e);
                    return fail(GatherOutcome.INTERNAL, 0);
                }
            }
        }

        /** 失败出口：做完补偿再返回。只在「房间确定不存在」时调用。 */
        private GatherResult fail(GatherOutcome outcome, long offender) {
            try {
                parts.compensation().run(plan, offender, cancels, battleId, placementWritten);
            } catch (RuntimeException e) {
                log.error("[gather] 补偿过程出现意外异常（冻结由 scene 的备战期限收尾，票据到期自灭） battle_id={} members={}",
                        Long.toUnsignedString(battleId), members, e);
            }
            log.warn("[gather] 开局失败 outcome={} battle_id={} mode={} offender={} 已发取消={} members={}", outcome.label(),
                    Long.toUnsignedString(battleId), plan.mode(), Long.toUnsignedString(offender),
                    cancels.stream().map(c -> Long.toUnsignedString(c.playerId())).toList(), members);
            return GatherResult.failed(outcome, battleId);
        }

        /** 选一个可分配的节点并得出直连目标；没有、或条目的地址不合法（按没有处理）为 null。 */
        private Node pick(Set<String> exclude) {
            Set<String> tried = new HashSet<>(exclude);
            // 地址不合法的条目跳过再选：实现保证只回可分配的条目，这里只是兜底，至多多选几次
            for (int attempt = 0; attempt < 4; attempt++) {
                Optional<BattleNodeInfo> picked = parts.battleNodes().pickRandom(Set.copyOf(tried));
                if (picked == null || picked.isEmpty()) {
                    return null;
                }
                BattleNodeInfo info = picked.get();
                try {
                    return new Node(info, new NodeRpcClients.Target(info.getRpcHost(), info.getRpcPort(), info.getInstanceId()));
                } catch (IllegalArgumentException e) {
                    log.error("[gather] battle 节点目录条目的直连地址不合法，跳过 node={} host='{}' port={}", Integer.toUnsignedString(info.getNodeId()),
                            info.getRpcHost(), Integer.toUnsignedString(info.getRpcPort()));
                    tried.add(BattleNodes.key(info));
                }
            }
            return null;
        }

        private Map<Long, Long> loadRatings(String battle) {
            try {
                Map<Long, Long> ratings = parts.ratings().loadAllCentiOrDefault(plan.members());
                if (ratings != null) {
                    return ratings;
                }
            } catch (RuntimeException e) {
                log.error("[gather] 5V5 分队读评分出错，全员按缺省分 battle_id={}: {}", battle, e.toString());
            }
            return Map.of();
        }

        private void observeZoneMix(String battle, List<Prepared> prepared) {
            Set<Integer> zones = new HashSet<>();
            List<String> perMember = new ArrayList<>(prepared.size());
            for (Prepared member : prepared) {
                int zone = member.snapshot().getRouting().getZoneId();
                zones.add(zone);
                perMember.add(Long.toUnsignedString(member.playerId()) + "@z" + Integer.toUnsignedString(zone) + "/t" + member.snapshot().getTeamIndex());
            }
            MatchMetrics.ZoneMix mix = zones.size() > 1 ? MatchMetrics.ZoneMix.CROSS : MatchMetrics.ZoneMix.SINGLE;
            parts.metrics().gatherZoneMix(plan.mode().getNumber(), mix);
            log.info("[gather] 全员已冻结 battle_id={} mode={} zone_mix={} members={}", battle, plan.mode(), mix, perMember);
        }

        private BattlePlacement pointAt(BattlePlacement.Builder record, Node node, int attempt) {
            return record.setBattleNodeId(node.info().getNodeId()).setBattleInstanceId(node.info().getInstanceId())
                    .setRpcHost(node.info().getRpcHost()).setRpcPort(node.info().getRpcPort()).setAttempt(attempt).build();
        }

        /** 发一次建房并归类结局。只看准入枚举判断「不可分配」；分不清的一律是 {@link Created#UNKNOWN}（可能已建房）。 */
        private Created create(Node node, CreateBattleRequest request) {
            String battle = Long.toUnsignedString(battleId);
            CreateBattleResult result;
            try {
                result = await(parts.battleCalls().call(node.target(), timeouts.create(), service -> service.createBattle(request)), timeouts.create());
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.error("[gather] 建房调用失败（结局不明） battle_id={} node={}: {}", battle, node.describe(), String.valueOf(cause(e)));
                return Created.UNKNOWN;
            }
            if (result == null) {
                log.error("[gather] 建房应答为空（结局不明） battle_id={} node={}", battle, node.describe());
                return Created.UNKNOWN;
            }
            switch (result.getAdmission()) {
                case BATTLE_ADMISSION_NOT_ALLOCATABLE -> {
                    log.warn("[gather] battle 节点级拒绝建房（保证没建房） battle_id={} node={} reason={}", battle, node.describe(), result.getReason());
                    return Created.NOT_ALLOCATABLE;
                }
                case BATTLE_ADMISSION_ADMITTED -> {
                    CreateBattleResponse response;
                    try {
                        response = CreateBattleResponse.parseFrom(result.getResponse());
                    } catch (InvalidProtocolBufferException e) {
                        log.error("[gather] 建房应答体解析失败（结局不明） battle_id={} node={}", battle, node.describe());
                        return Created.UNKNOWN;
                    }
                    int tip = response.getErrorMessage().getId();
                    if (tip != 0) {
                        log.error("[gather] battle 明确拒绝建房（零副作用，不发 destroy） battle_id={} node={} tip_id={}", battle, node.describe(),
                                Integer.toUnsignedString(tip));
                        return Created.REJECTED;
                    }
                    return Created.BUILT;
                }
                default -> {
                    log.error("[gather] 建房应答的准入字段缺失或不认识（结局不明） battle_id={} node={} admission={}", battle, node.describe(),
                            result.getAdmissionValue());
                    return Created.UNKNOWN;
                }
            }
        }

        /** 回滚：销毁可能已建成的房间。true = battle 确认已销毁（或本来就不存在）。 */
        private boolean destroy(Node node) {
            DestroyBattleRequest request = DestroyBattleRequest.newBuilder().setBattleId(battleId).setReason(ROLLBACK_REASON).build();
            try {
                await(parts.battleCalls().call(node.target(), timeouts.destroy(), service -> service.destroyBattle(request)), timeouts.destroy());
                return true;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.error("[gather] 回滚销毁失败 battle_id={} node={}: {}", Long.toUnsignedString(battleId), node.describe(), String.valueOf(cause(e)));
                return false;
            }
        }
    }

    /** 在这一跳的超时内等结果；被中断按超时处理（恢复中断标志）。 */
    private static <R> R await(CompletableFuture<R> future, Duration timeout) throws ExecutionException, TimeoutException {
        if (future == null) {
            throw new IllegalStateException("出站口没有返回 future");
        }
        try {
            return future.get(Math.max(0, timeout.toMillis()) + LOCAL_WAIT_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException("等待应答时被中断");
        }
    }

    private static Throwable cause(Throwable e) {
        return e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    }
}
