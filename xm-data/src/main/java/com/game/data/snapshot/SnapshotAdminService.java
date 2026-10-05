package com.game.data.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.OpsRequests;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.query.AuditViews;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运维快照（data-ops-spec §3.4、§3.5；对应 96 / 103 / 105 与 97 / 115 的详情）：
 *
 * <ul>
 *   <li><b>手工快照</b>：同步执行，<b>不夺权、不踢人</b>——快照不改玩家数据，读已提交的状态就够了（同基线「冻结中也允许拍快照」）。
 *       一个事务：一条语句读 player 行 + player_state 原字节 → 插 player_snapshot → 插一条终态作业行（kind SNAPSHOT）。
 *       任一步失败整体回滚、什么也没写（T-P1）。</li>
 *   <li><b>内容时刻</b>：{@code time_ms} = 所读已落盘状态的写入时刻（player_state.updated_at，没有这一行取 player.updated_at），
 *       拍摄时刻记在 {@code ingested_at}；{@code owner_epoch} = player_state.saved_epoch；{@code zone_id} = player.zone_id（归属区）。
 *       按拍摄时刻记会让以后回档的帮会检查起点偏晚、漏掉这段时间里终结的操作（§3.2，9.2 第 4 条）。</li>
 *   <li><b>号</b>：快照号与作业号取自 {@link OpsIds}（SCENE_GUID 全服池）；租约无效 503 {@code id_unavailable}，零变更。</li>
 *   <li><b>幂等</b>：必带 {@code Idempotency-Key}；同键同参数回原结果（{@code replayed=true}），同键不同参数 409
 *       {@code idempotency_conflict}；并发同键时唯一键兜底，输的一方回滚后按已提交的那份回答。</li>
 * </ul>
 */
public final class SnapshotAdminService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotAdminService.class);
    /** 运维作业审计（data-ops-spec §8.4）。 */
    private static final Logger audit = LoggerFactory.getLogger("xm.audit.ops");

    public static final String CREATE_PATH = "/admin/player-snapshots";

    /** 手工快照请求（已解析）。 */
    public record CreateRequest(long playerId, int cause, String note, String reason) {

        /** 规范化请求（进 request_json 与指纹；字段顺序固定）。 */
        String canonical() {
            return "player=" + Long.toUnsignedString(playerId) + "\ncause=" + SnapshotCauses.name(cause) + "\nnote=" + note
                    + "\nreason=" + reason;
        }
    }

    /** 结果：应答体，以及是否是幂等重放。 */
    public record Created(Map<String, Object> body, boolean replayed) {
    }

    private final PersistedPlayerMapper players;
    private final PlayerSnapshotMapper snapshots;
    private final OpsJobStore jobs;
    private final OpsIds ids;
    private final TransactionTemplate tx;
    private final DataMetrics metrics;
    private final ObjectMapper json;
    private final Clock clock;
    private final String runner;

    public SnapshotAdminService(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots, OpsJobStore jobs, OpsIds ids,
                                TransactionTemplate tx, DataMetrics metrics, ObjectMapper json, Clock clock, String runner) {
        this.players = players;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.ids = ids;
        this.tx = tx;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
        this.runner = runner;
    }

    /** 拍一份手工快照（{@code cause} ∈ GM_MANUAL / PRE_MAINTENANCE）。 */
    public Created create(CreateRequest req, String operator, String idempotencyKey) {
        if (!SnapshotCauses.MANUAL.contains(req.cause())) {
            throw OpsException.badRequest("cause 只能是 GM_MANUAL / PRE_MAINTENANCE");
        }
        if (req.playerId() == 0) {
            throw OpsException.badRequest("player 必须非 0");
        }
        String causeName = SnapshotCauses.name(req.cause()).toLowerCase(java.util.Locale.ROOT);
        try {
            return createChecked(req, operator, idempotencyKey, causeName);
        } catch (DataAccessException e) {
            log.warn("手工快照访问库失败 player={}：{}", Long.toUnsignedString(req.playerId()), e.toString());
            metrics.snapshotAdmin(causeName, "db_error");
            throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.SNAPSHOT_DB_ERROR, "快照落库失败，什么也没写",
                    Map.of(), e);
        }
    }

    private Created createChecked(CreateRequest req, String operator, String idempotencyKey, String causeName) {
        String hash = OpsRequests.requestHash("POST", CREATE_PATH, req.canonical());
        Optional<Created> replay = replayOf(idempotencyKey, hash, causeName);
        if (replay.isPresent()) {
            return replay.get();
        }
        OptionalLong jobId = ids.tryNext();
        OptionalLong snapshotId = jobId.isPresent() ? ids.tryNext() : OptionalLong.empty();
        if (snapshotId.isEmpty()) {
            metrics.snapshotAdmin(causeName, "id_unavailable");
            throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.ID_UNAVAILABLE,
                    "全服发号租约（scene-guid）无效：不发号、不写，稍后重试");
        }
        long now = clock.millis();
        Map<String, Object> body;
        try {
            body = tx.execute(status -> write(req, operator, idempotencyKey, hash, jobId.getAsLong(),
                    snapshotId.getAsLong(), now));
        } catch (DuplicateKeyException e) {
            // 同一幂等键的并发请求先提交了：按它回答（同参数回原结果，不同参数 409）
            Optional<Created> raced = replayOf(idempotencyKey, hash, causeName);
            if (raced.isPresent()) {
                return raced.get();
            }
            metrics.snapshotAdmin(causeName, "db_error");
            throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.SNAPSHOT_DB_ERROR, "快照落库撞键，什么也没写",
                    Map.of(), e);
        } catch (OpsException e) {
            metrics.snapshotAdmin(causeName, e.code());
            throw e;
        }
        metrics.snapshotAdmin(causeName, "ok");
        audit.info("[Snapshot] job={} snapshot={} player={} cause={} operator={} reason={} note={}", body.get("jobId"),
                body.get("snapshotId"), body.get("playerId"), body.get("cause"), printable(operator),
                printable(req.reason()), printable(req.note()));
        return new Created(body, false);
    }

    /** 一个事务里的写：读已落盘 → 插快照 → 插终态作业行。任何异常都让 TransactionTemplate 回滚。 */
    private Map<String, Object> write(CreateRequest req, String operator, String idempotencyKey, String hash, long jobId,
                                      long snapshotId, long now) {
        PersistedPlayer p = players.find(req.playerId());
        if (p == null) {
            throw new OpsException(HttpStatus.NOT_FOUND, OpsException.PLAYER_NOT_FOUND,
                    "玩家不存在：" + Long.toUnsignedString(req.playerId()));
        }
        byte[] state = p.stateBytes();
        PlayerSnapshotRow row = new PlayerSnapshotRow(snapshotId, req.playerId(), p.persistedAtMs(), req.cause(),
                p.getZoneId(), p.savedEpoch(), p.getLevel(), p.getSceneConfigId(), p.getPosX(), p.getPosY(), p.getPosZ(),
                state);
        if (snapshots.insertDirect(row, now, operator, req.note()) != 1) {
            throw new IllegalStateException("快照插入没有恰好影响 1 行");
        }
        boolean held = p.ownerHeld(now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", Long.toUnsignedString(jobId));
        body.put("snapshotId", Long.toUnsignedString(snapshotId));
        body.put("playerId", Long.toUnsignedString(req.playerId()));
        body.put("cause", SnapshotCauses.name(req.cause()));
        body.put("timeMs", p.persistedAtMs());
        body.put("ingestedAt", now);
        body.put("savedEpoch", Long.toUnsignedString(p.savedEpoch()));
        body.put("zoneId", Integer.toUnsignedLong(p.getZoneId()));
        body.put("stateBytes", state.length);
        body.put("hasState", p.hasState());
        body.put("ownerReleased", p.getOwnerReleased() != 0);
        body.put("online", held);
        if (held) {
            body.put("onlineCaveat", "玩家的归属此刻被 scene 持有：快照是已落盘状态，可能落后内存至多一个存盘周期；"
                    + "需要此刻的精确状态请先让玩家下线（或以后用 kick）再拍");
        }
        body.put("note", req.note());
        body.put("replayed", false);
        OpsJobRow job = OpsJobRow.newBuilder()
                .setJobId(jobId)
                .setIdemKey(idempotencyKey)
                .setKind(OpsJobKind.OPS_JOB_SNAPSHOT)
                .setStatus(OpsJobStatus.OPS_JOB_SUCCEEDED)
                .setRequestJson(toJson(Map.of("player", Long.toUnsignedString(req.playerId()),
                        "cause", SnapshotCauses.name(req.cause()), "note", req.note(), "reason", req.reason())))
                .setRequestHash(hash)
                .setOperator(operator)
                .setReason(req.reason())
                .setResultCode("ok")
                .setPlayersPlanned(1)
                .setPlayersAffected(1)
                .setRunner(runner)
                .setCreatedMs(now)
                .setStartedMs(now)
                .setFinishedMs(now)
                .setSummaryJson(toJson(body))
                .build();
        jobs.insertJob(job);
        return body;
    }

    /** 已有同键作业：同指纹回原结果，否则 409。没有为空。 */
    private Optional<Created> replayOf(String idempotencyKey, String hash, String causeName) {
        Optional<OpsJobRow> existing = jobs.findByIdemKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        OpsJobRow job = existing.get();
        if (job.getKind() != OpsJobKind.OPS_JOB_SNAPSHOT || !job.getRequestHash().equals(hash)) {
            metrics.snapshotAdmin(causeName, "idempotency_conflict");
            throw new OpsException(HttpStatus.CONFLICT, OpsException.IDEMPOTENCY_CONFLICT,
                    "同一个 Idempotency-Key 已用于参数不同的请求", Map.of("jobId", Long.toUnsignedString(job.getJobId())), null);
        }
        Map<String, Object> body = fromJson(job.getSummaryJson());
        body.put("replayed", true);
        metrics.snapshotAdmin(causeName, "replayed");
        return Optional.of(new Created(body, true));
    }

    /** 快照详情：元数据 + 操作人 / 备注；{@code includeState} 时附 JSON 化的玩法数据（{@link StateJson}）。 */
    public Map<String, Object> detail(long snapshotId, boolean includeState) {
        PlayerSnapshotEntry e = snapshots.findById(snapshotId);
        if (e == null) {
            throw new OpsException(HttpStatus.NOT_FOUND, OpsException.SNAPSHOT_NOT_FOUND,
                    "快照不存在：" + Long.toUnsignedString(snapshotId));
        }
        Map<String, Object> out = AuditViews.snapshotMeta(e);
        out.put("stateBytes", e.getPlayerState() == null ? 0 : e.getPlayerState().length);
        if (includeState) {
            out.put("playerState", StateJson.render(e.getPlayerState() == null ? new byte[0] : e.getPlayerState(), json));
        }
        return out;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化作业摘要失败", e);
        }
    }

    private Map<String, Object> fromJson(String text) {
        try {
            return json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("作业摘要不是合法 JSON", e);
        }
    }

    /** 进日志前把控制字符换成 ?（同 AdminAuthFilter.printable）。 */
    static String printable(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "?");
    }
}
