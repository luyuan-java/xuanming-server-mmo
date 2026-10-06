package com.game.data.rollback;

import com.game.data.DataProperties;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsJobService;
import com.game.data.ops.OpsRequests;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.query.AuditViews;
import com.game.data.rollback.RollbackPlanner.Target;
import com.game.data.snapshot.LedgerDiff;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;
import com.game.player.store.state.PlayerState;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * 回档的受理（data-ops-spec §4.3；对应 99 / 100 / 101 / 112）：校验 → 写操作开关 → 幂等键 → 查库校验（快照归属、区服维护态、规模上限）
 * → dry-run 同步返回计划，否则交给作业框架（202 {@code {jobId}}）。
 *
 * <ul>
 *   <li>{@code xm.data.ops.enabled=false}：非 dry-run 回 503 {@code ops_disabled}；dry-run 照常（只读）。</li>
 *   <li>单人按号：快照必须属于该玩家，否则 404 {@code snapshot_not_found}（同基线 resolveSnapshot）。</li>
 *   <li>整区：每个区 {@code zone_config.manual_status} 必须是 MAINTENANCE 或 CLOSED，否则 409 {@code zone_open}（Q7）；
 *       玩家总数超过 {@code max-players-per-job} 回 422 {@code plan_too_large}（不自动拆批，拆批会破坏「整份计划过闸才写第一个玩家」；
 *       dry-run 同样先数人再计划，不在请求线程上对超限的范围逐人查快照）。</li>
 * </ul>
 */
public final class RollbackService {

    /** dry-run 里逐人列出的上限。 */
    static final int DRY_RUN_LIST = 200;

    private final RollbackJob.Deps deps;
    private final OpsJobService jobs;
    private final GatewayStore zones;
    private final DataProperties props;
    private final Clock clock;

    public RollbackService(RollbackJob.Deps deps, OpsJobService jobs, GatewayStore zones, DataProperties props, Clock clock) {
        this.deps = deps;
        this.jobs = jobs;
        this.zones = zones;
        this.props = props;
        this.clock = clock;
    }

    /** dry-run 返回 200 的应答体；否则 202 的受理应答体（{@code accepted=true}）。 */
    public Map<String, Object> handle(RollbackRequest.Body body, String operator, String idempotencyKey) {
        RollbackRequest req = RollbackRequest.parse(body, clock.millis(), props.ops().minTargetAge());
        if (req.dryRun()) {
            return dryRun(expandZones(req, false));
        }
        if (!props.ops().enabled()) {
            throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.OPS_DISABLED,
                    "改玩家数据的运维写操作未开启（xm.data.ops.enabled=false）；dry-run 照常可用");
        }
        String key = OpsRequests.idempotencyKey(idempotencyKey);
        String hash = OpsRequests.requestHash("POST", RollbackRequest.PATH, req.canonical());
        Optional<Map<String, Object>> replay = jobs.replay(OpsJobKind.OPS_JOB_ROLLBACK, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        RollbackRequest expanded = expandZones(req, true);
        int planned;
        if (expanded.scope() == RollbackRequest.Scope.PLAYERS) {
            checkSnapshotOwner(expanded);
            planned = expanded.players().size();
        } else {
            planned = (int) requireWithinCap(expanded);
        }
        Map<String, Object> request = expanded.view();
        if (expanded.allZones()) {
            request.put("expandedZones", expanded.zones().stream().map(Integer::toUnsignedLong).toList());
        }
        return jobs.submit(new OpsJobService.Submission(OpsJobKind.OPS_JOB_ROLLBACK, RollbackRequest.PATH,
                req.canonical(), request, operator, req.reason(), key, planned, new RollbackJob(deps, expanded)));
    }

    /** allZones → 区服目录全部区（升序）；执行时（{@code requireMaintenance}）每个区必须是维护 / 关闭态。 */
    private RollbackRequest expandZones(RollbackRequest req, boolean requireMaintenance) {
        if (req.scope() != RollbackRequest.Scope.ZONES) {
            return req;
        }
        List<Integer> ids = new ArrayList<>(req.zones());
        Map<Integer, ZoneRow> rows = new LinkedHashMap<>();
        for (ZoneRow row : zones.zones()) {
            rows.put(row.zoneId(), row);
        }
        if (req.allZones()) {
            ids = rows.keySet().stream().sorted(Integer::compareUnsigned).toList();
            if (ids.isEmpty()) {
                throw new OpsException(HttpStatus.NOT_FOUND, OpsException.ZONE_NOT_FOUND, "区服目录是空的");
            }
        }
        if (requireMaintenance) {
            List<Long> open = new ArrayList<>();
            for (int zone : ids) {
                ZoneRow row = rows.get(zone);
                if (row == null) {
                    throw new OpsException(HttpStatus.NOT_FOUND, OpsException.ZONE_NOT_FOUND,
                            "区服目录里没有区 " + Integer.toUnsignedString(zone));
                }
                ZoneManualStatus status = row.status();
                if (status != ZoneManualStatus.MAINTENANCE && status != ZoneManualStatus.CLOSED) {
                    open.add(Integer.toUnsignedLong(zone));
                }
            }
            if (!open.isEmpty()) {
                throw new OpsException(HttpStatus.CONFLICT, OpsException.ZONE_OPEN,
                        "整区回档要求区服先进维护态（MAINTENANCE / CLOSED）", Map.of("openZones", open), null);
            }
        }
        return req.withZones(ids);
    }

    /**
     * 整区：先数人（{@code idx_player_zone} 上的一次 COUNT），超过 {@code max-players-per-job} 回 422 {@code plan_too_large}。
     * 执行与 dry-run 共用：dry-run 也在 Tomcat 线程上逐人查快照，不先卡规模会对整个服做 N 次查询、建 N 个元素的计划，只为了报「太大」。
     */
    private long requireWithinCap(RollbackRequest req) {
        long count = deps.players().countInZones(req.zones());
        if (count > props.ops().maxPlayersPerJob()) {
            throw new OpsException(HttpStatus.UNPROCESSABLE_ENTITY, OpsException.PLAN_TOO_LARGE,
                    "这些区共 " + count + " 名玩家，超过一个作业的上限 " + props.ops().maxPlayersPerJob()
                            + "（不自动拆批：拆批会让整区回档不再「全有或全无」）",
                    Map.of("players", count, "max", props.ops().maxPlayersPerJob()), null);
        }
        return count;
    }

    private void checkSnapshotOwner(RollbackRequest req) {
        if (req.snapshotId() == null) {
            return;
        }
        PlayerSnapshotEntry e = deps.snapshots().findById(req.snapshotId());
        if (e == null || e.getPlayerId() != req.players().get(0)) {
            throw new OpsException(HttpStatus.NOT_FOUND, OpsException.SNAPSHOT_NOT_FOUND,
                    "该玩家没有快照 " + Long.toUnsignedString(req.snapshotId()));
        }
    }

    /**
     * dry-run（同步、只读、不夺权）：逐人选中的快照（{@code timeMs} / {@code ingestedAt} / {@code cause}，由运维判断是不是想要的那份）、
     * 是否在线、按现档预演的恢复内容与账本差集。帮会检查要沉降，只在执行时做。
     */
    Map<String, Object> dryRun(RollbackRequest req) {
        if (req.scope() == RollbackRequest.Scope.PLAYERS) {
            checkSnapshotOwner(req);
        } else {
            requireWithinCap(req);
        }
        List<Target> targets = deps.planner().plan(req);
        long now = clock.millis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", true);
        out.put("request", req.view());
        out.put("plan", RollbackPlanner.summary(targets));
        out.put("tooLarge", targets.size() > props.ops().maxPlayersPerJob());
        if (req.scope() == RollbackRequest.Scope.ZONES) {
            List<Map<String, Object>> zoneViews = new ArrayList<>();
            for (ZoneRow row : zones.zones()) {
                if (req.zones().contains(row.zoneId())) {
                    zoneViews.add(Map.of("zoneId", Integer.toUnsignedLong(row.zoneId()), "status", row.status().name()));
                }
            }
            out.put("zones", zoneViews);
        }
        List<Map<String, Object>> players = new ArrayList<>();
        for (Target t : targets.subList(0, Math.min(DRY_RUN_LIST, targets.size()))) {
            players.add(playerPreview(req, t, now));
        }
        out.put("players", players);
        out.put("playersTruncated", targets.size() > DRY_RUN_LIST);
        out.put("caveat", "dry-run 不夺权、以已落盘状态预演；执行时以夺权（kick 时 scene 已写回）之后的现档为准，"
                + "帮会检查在沉降之后才做");
        return out;
    }

    private Map<String, Object> playerPreview(RollbackRequest req, Target t, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerId", Long.toUnsignedString(t.playerId()));
        m.put("zoneId", Integer.toUnsignedLong(t.zoneId()));
        if (!t.planned()) {
            m.put("outcome", t.outcome());
            return m;
        }
        PlayerSnapshotEntry meta = t.snapshot();
        m.put("snapshot", AuditViews.snapshotMeta(meta));
        if (req.scope() == RollbackRequest.Scope.ZONES) {
            return m;
        }
        PersistedPlayer current = deps.players().find(t.playerId());
        PlayerSnapshotEntry full = deps.snapshots().findById(meta.getSnapshotId());
        if (current == null || full == null) {
            m.put("outcome", current == null ? RollbackPlanner.PLAYER_NOT_FOUND : RollbackWriter.SNAPSHOT_GONE);
            return m;
        }
        m.put("online", current.ownerHeld(now));
        m.put("persistedAtMs", current.persistedAtMs());
        try {
            RestoreBuilder.Restored r = RestoreBuilder.build(full, current, req.sections());
            m.put("restore", r.detail());
            if (RollbackSection.restoresAssets(req.sections()) && r.currentValid()) {
                PlayerState s = RestoreBuilder.parse(full.getPlayerState(), "snapshot");
                PlayerState c = RestoreBuilder.parse(current.stateBytes(), "current");
                LedgerDiff.Result diff = LedgerDiff.compare(s, c);
                Map<String, Object> ledger = new LinkedHashMap<>();
                ledger.put("clean", diff.clean());
                ledger.put("rows", diff.rows().size());
                ledger.put("unprovable", diff.unprovable().stream().map(u -> Map.of("stream", u.stream(), "reason",
                        u.reason())).toList());
                m.put("ledger", ledger);
            }
        } catch (RestoreBuilder.UnknownSectionsException e) {
            m.put("outcome", RollbackWriter.UNKNOWN_SECTIONS);
            m.put("unknownFields", e.fields());
            m.put("message", e.getMessage());
        } catch (RestoreBuilder.StateInvalidException e) {
            m.put("outcome", RollbackWriter.STATE_INVALID);
            m.put("message", e.getMessage());
        }
        return m;
    }
}
