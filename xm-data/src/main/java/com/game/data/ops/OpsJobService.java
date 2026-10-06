package com.game.data.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.ops.OpsJobRunner.JobBody;
import com.game.data.ops.pb.OpsActiveRow;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运维作业的受理与查询（data-ops-spec §7.4）。受理在 Tomcat 线程上同步做完：
 * <ol>
 *   <li>{@code Idempotency-Key}：同键同指纹回原作业（{@code replayed=true}），同键不同参数 409 {@code idempotency_conflict}；</li>
 *   <li>发作业号（{@code SCENE_GUID} 全服池；租约无效 503 {@code id_unavailable}）；</li>
 *   <li><b>一个事务</b>：插 {@code ops_active}（重键 = 有作业在跑 → 409 {@code ops_busy}，带在跑的作业号）+ {@code ops_job}（QUEUED）
 *       + STARTED 事件；任一失败 503，零变更（R-A，对应基线 {@code rollback_logic.go:538-550}：STARTED 写不进就什么也不做）；</li>
 *   <li>交给 {@link OpsJobRunner} 异步执行，回 202 {@code {jobId}}。</li>
 * </ol>
 * STARTED 先于任何夺权 / 踢人 / 写数据（不变量 I3；Java 先 STARTED 后夺权，连「因在线被拒」的尝试也留痕，D3）。
 */
public final class OpsJobService {

    private static final Logger log = LoggerFactory.getLogger(OpsJobService.class);

    /** 一次受理的入参。 */
    public record Submission(OpsJobKind kind, String path, String canonical, Map<String, Object> request,
                             String operator, String reason, String idempotencyKey, int playersPlanned, JobBody body) {
    }

    private final OpsJobStore jobs;
    private final OpsIds ids;
    private final OpsJobRunner runner;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public OpsJobService(OpsJobStore jobs, OpsIds ids, OpsJobRunner runner, TransactionTemplate tx, ObjectMapper json,
                         Clock clock) {
        this.jobs = jobs;
        this.ids = ids;
        this.runner = runner;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /** 已有同键作业：同种类同指纹回原作业，否则 409。没有为空。 */
    public Optional<Map<String, Object>> replay(OpsJobKind kind, String idempotencyKey, String hash) {
        Optional<OpsJobRow> existing = jobs.findByIdemKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        OpsJobRow job = existing.get();
        if (job.getKind() != kind || !job.getRequestHash().equals(hash)) {
            throw new OpsException(HttpStatus.CONFLICT, OpsException.IDEMPOTENCY_CONFLICT,
                    "同一个 Idempotency-Key 已用于参数不同的请求", Map.of("jobId", Long.toUnsignedString(job.getJobId())), null);
        }
        Map<String, Object> body = accepted(job);
        body.put("replayed", true);
        return Optional.of(body);
    }

    /** 受理一个作业（见类注释）。返回 202 的应答体。 */
    public Map<String, Object> submit(Submission s) {
        String hash = OpsRequests.requestHash("POST", s.path(), s.canonical());
        try {
            Optional<Map<String, Object>> replay = replay(s.kind(), s.idempotencyKey(), hash);
            if (replay.isPresent()) {
                return replay.get();
            }
            OptionalLong jobId = ids.tryNext();
            if (jobId.isEmpty()) {
                throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.ID_UNAVAILABLE,
                        "全服发号租约（scene-guid）无效：不发号、不受理，稍后重试");
            }
            long now = clock.millis();
            OpsJobRow job = OpsJobRow.newBuilder()
                    .setJobId(jobId.getAsLong())
                    .setIdemKey(s.idempotencyKey())
                    .setKind(s.kind())
                    .setStatus(OpsJobStatus.OPS_JOB_QUEUED)
                    .setRequestJson(toJson(s.request()))
                    .setRequestHash(hash)
                    .setOperator(s.operator())
                    .setReason(s.reason())
                    .setPlayersPlanned(s.playersPlanned())
                    .setRunner(runner.runnerId())
                    .setCreatedMs(now)
                    .build();
            Map<String, Object> started = new LinkedHashMap<>();
            started.put("kind", OpsJobRunner.kindName(job));
            started.put("operator", s.operator());
            started.put("reason", s.reason());
            started.put("request", s.request());
            started.put("runner", runner.runnerId());
            String startedJson = runner.boundedJson(started);
            try {
                tx.executeWithoutResult(status -> {
                    try {
                        jobs.insertActive(OpsActiveRow.newBuilder().setSlot(OpsJobStore.SLOT).setJobId(job.getJobId())
                                .setRunner(runner.runnerId()).setHeartbeatMs(now).build());
                    } catch (DuplicateKeyException e) {
                        throw busy();
                    }
                    jobs.insertJob(job);
                    jobs.insertEvent(OpsJobEventRow.newBuilder().setJobId(job.getJobId()).setSeq(1)
                            .setType(OpsJobEventType.OPS_JOB_EVENT_STARTED).setPayloadJson(startedJson).setAtMs(now).build());
                });
            } catch (DuplicateKeyException e) {
                // 同一幂等键的并发请求先提交了：按它回答
                Optional<Map<String, Object>> raced = replay(s.kind(), s.idempotencyKey(), hash);
                if (raced.isPresent()) {
                    return raced.get();
                }
                throw e;
            }
            OpsJobRunner.audit.info("[OpsJob] STARTED job={} kind={} operator={} reason={}",
                    Long.toUnsignedString(job.getJobId()), OpsJobRunner.kindName(job), OpsJobRunner.printable(s.operator()),
                    OpsJobRunner.printable(s.reason()));
            try {
                runner.submit(job, s.body());
            } catch (RejectedExecutionException e) {
                failUnsubmitted(job);
                throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.SNAPSHOT_DB_ERROR,
                        "xm-data 正在停服，作业没有执行（已标 FAILED）");
            }
            Map<String, Object> body = accepted(job);
            body.put("replayed", false);
            return body;
        } catch (DataAccessException e) {
            log.warn("受理运维作业访问库失败 kind={}：{}", s.kind(), e.toString());
            throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.SNAPSHOT_DB_ERROR,
                    "受理作业失败（STARTED 没写进），什么也没做", Map.of(), e);
        }
    }

    private OpsException busy() {
        String running = jobs.active().map(a -> Long.toUnsignedString(a.getJobId())).orElse("");
        return new OpsException(HttpStatus.CONFLICT, OpsException.OPS_BUSY, "已有运维作业在执行（全集群单飞）",
                Map.of("runningJobId", running), null);
    }

    private void failUnsubmitted(OpsJobRow job) {
        try {
            long now = clock.millis();
            // 按列、带状态条件写（不覆盖取消标志；已被清扫器改成 INTERRUPTED 的不改回）
            jobs.finishJob(job.toBuilder().setStatus(OpsJobStatus.OPS_JOB_FAILED).setResultCode("not_submitted")
                    .setFinishedMs(now).build());
            jobs.deleteActive(job.getJobId());
        } catch (RuntimeException e) {
            log.error("作业 job={} 没交出去、收尾也失败（由清扫器改 INTERRUPTED）", Long.toUnsignedString(job.getJobId()), e);
        }
    }

    private static Map<String, Object> accepted(OpsJobRow job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", Long.toUnsignedString(job.getJobId()));
        body.put("kind", OpsJobRunner.kindName(job));
        body.put("status", OpsJobRunner.outcomeName(job.getStatus()));
        return body;
    }

    // ------------------------------------------------------------------ 查询与取消

    /** 一个作业（含事件）。 */
    public Map<String, Object> view(long jobId) {
        OpsJobRow job = jobs.findJob(jobId).orElseThrow(() -> notFound(jobId));
        Map<String, Object> out = jobView(job);
        List<Map<String, Object>> events = new ArrayList<>();
        for (OpsJobEventRow e : jobs.events(jobId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.getSeq());
            m.put("type", e.getType().name().substring("OPS_JOB_EVENT_".length()));
            m.put("atMs", e.getAtMs());
            m.put("payload", parse(e.getPayloadJson()));
            events.add(m);
        }
        out.put("events", events);
        return out;
    }

    public List<Map<String, Object>> list(OpsJobStatus status, OpsJobKind kind, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OpsJobRow job : jobs.listJobs(status, kind, limit)) {
            out.add(jobView(job));
        }
        return out;
    }

    public List<Map<String, Object>> players(long jobId, long afterPlayer, int limit) {
        if (jobs.findJob(jobId).isEmpty()) {
            throw notFound(jobId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (OpsJobPlayerRow p : jobs.players(jobId, afterPlayer, limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playerId", Long.toUnsignedString(p.getPlayerId()));
            m.put("outcome", p.getOutcome());
            m.put("plannedSnapshotId", Long.toUnsignedString(p.getPlannedSnapshotId()));
            m.put("plannedSnapshotMs", p.getPlannedSnapshotMs());
            m.put("claimedEpoch", Long.toUnsignedString(p.getClaimedEpoch()));
            m.put("preSnapshotId", Long.toUnsignedString(p.getPreSnapshotId()));
            m.put("writtenMs", p.getWrittenMs());
            m.put("detail", parse(p.getDetailJson()));
            out.add(m);
        }
        return out;
    }

    /** 请求取消（幂等；只在第一笔写之前生效：执行线程在各阶段边界检查）。 */
    public Map<String, Object> cancel(long jobId) {
        OpsJobRow job = jobs.findJob(jobId).orElseThrow(() -> notFound(jobId));
        boolean requested = jobs.requestCancel(jobId);
        OpsJobRunner.audit.info("[OpsJob] CANCEL job={} requested={}", Long.toUnsignedString(jobId), requested);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", Long.toUnsignedString(jobId));
        out.put("cancelRequested", requested);
        out.put("status", OpsJobRunner.outcomeName(requested ? job.getStatus()
                : jobs.findJob(jobId).map(OpsJobRow::getStatus).orElse(job.getStatus())));
        out.put("note", requested ? "取消只在第一笔写之前生效；已夺权的玩家全部释放、作业 CANCELLED；已进入写阶段的作业照常做完"
                : "作业已终结，取消无效");
        return out;
    }

    private Map<String, Object> jobView(OpsJobRow job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", Long.toUnsignedString(job.getJobId()));
        m.put("kind", OpsJobRunner.kindName(job));
        m.put("status", OpsJobRunner.outcomeName(job.getStatus()));
        m.put("resultCode", job.getResultCode());
        m.put("operator", job.getOperator());
        m.put("reason", job.getReason());
        m.put("idempotencyKey", job.getIdemKey());
        m.put("playersPlanned", job.getPlayersPlanned());
        m.put("playersAffected", job.getPlayersAffected());
        m.put("playersFailed", job.getPlayersFailed());
        m.put("divergenceRows", job.getDivergenceRows());
        m.put("unprovablePlayers", job.getUnprovablePlayers());
        m.put("acceptedDivergence", job.getAcceptedDivergence());
        m.put("acceptedRecallReversal", job.getAcceptedRecallReversal());
        m.put("cancelRequested", job.getCancelRequested());
        m.put("runner", job.getRunner());
        m.put("createdMs", job.getCreatedMs());
        m.put("startedMs", job.getStartedMs());
        m.put("finishedMs", job.getFinishedMs());
        m.put("request", parse(job.getRequestJson()));
        m.put("summary", parse(job.getSummaryJson()));
        return m;
    }

    private static OpsException notFound(long jobId) {
        return new OpsException(HttpStatus.NOT_FOUND, OpsException.JOB_NOT_FOUND, "作业不存在：" + Long.toUnsignedString(jobId));
    }

    private Object parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return json.readValue(text, new TypeReference<Object>() {
            });
        } catch (JsonProcessingException e) {
            return text;
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化请求失败", e);
        }
    }
}
