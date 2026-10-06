package com.game.data.snapshot;

import com.game.data.ops.JobContext;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobRunner.JobBody;
import com.game.data.ops.OpsJobRunner.JobResult;
import com.game.data.ops.OpsJobService;
import com.game.data.ops.OpsRequests;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerBrief;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneRow;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 整区维护前快照（data-ops-spec §3.4，批次 7.2b；{@code POST /admin/zone-snapshots}）：按 {@code player.zone_id} 分批（每批 500）读已落盘状态、
 * 批量插入 PRE_MAINTENANCE 快照。不需要栅栏（快照不改玩家数据），也不需要 {@code ops.enabled}；但它是作业（统一审计、统一单飞，
 * 免得与回档并发）。每批一个事务；号源失效时停下（已拍的快照留着，无害），作业 FAILED {@code id_unavailable}。
 * 快照语义同手工快照：{@code time_ms} = 内容时刻（{@code player_state.updated_at}），{@code owner_epoch} = 写入这份内容的 epoch。
 */
public final class ZoneSnapshotService {

    public static final String PATH = "/admin/zone-snapshots";
    static final int BATCH = 500;

    /** 请求体：{@code {"zones":[1,2] | "allZones":true, "note":"...", "reason":"..."}}。 */
    public record Body(List<Long> zones, Boolean allZones, String note, String reason) {
    }

    private final PersistedPlayerMapper players;
    private final PlayerSnapshotMapper snapshots;
    private final OpsIds ids;
    private final OpsJobService jobs;
    private final GatewayStore zones;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ZoneSnapshotService(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots, OpsIds ids,
                               OpsJobService jobs, GatewayStore zones, TransactionTemplate tx, Clock clock) {
        this.players = players;
        this.snapshots = snapshots;
        this.ids = ids;
        this.jobs = jobs;
        this.zones = zones;
        this.tx = tx;
        this.clock = clock;
    }

    public Map<String, Object> submit(Body body, String operator, String idempotencyKey) {
        String key = OpsRequests.idempotencyKey(idempotencyKey);
        if (body == null) {
            throw OpsException.badRequest("缺少请求体");
        }
        String reason = OpsRequests.reason(body.reason());
        String note = OpsRequests.text("note", body.note());
        boolean all = Boolean.TRUE.equals(body.allZones());
        TreeSet<Integer> requested = new TreeSet<>(Integer::compareUnsigned);
        for (Long z : body.zones() == null ? List.<Long>of() : body.zones()) {
            if (z == null || z <= 0 || z > 0xFFFF_FFFFL) {
                throw OpsException.badRequest("zones 必须是 1–4294967295 的区号");
            }
            requested.add(z.intValue());
        }
        if (requested.isEmpty() == !all) {
            throw OpsException.badRequest("zones 非空与 allZones=true 二选一");
        }
        String canonical = "zones=" + (all ? "all" : requested.stream().map(Integer::toUnsignedString)
                .collect(Collectors.joining(","))) + "\nnote=" + note + "\nreason=" + reason;
        String hash = OpsRequests.requestHash("POST", PATH, canonical);
        var replay = jobs.replay(OpsJobKind.OPS_JOB_ZONE_SNAPSHOT, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<Integer> target = new ArrayList<>(requested);
        if (all) {
            target = zones.zones().stream().map(ZoneRow::zoneId).sorted(Integer::compareUnsigned).toList();
            if (target.isEmpty()) {
                throw new OpsException(HttpStatus.NOT_FOUND, OpsException.ZONE_NOT_FOUND, "区服目录是空的");
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("zones", target.stream().map(Integer::toUnsignedLong).toList());
        request.put("allZones", all);
        request.put("note", note);
        request.put("reason", reason);
        long planned = players.countInZones(target);
        List<Integer> zoneList = List.copyOf(target);
        return jobs.submit(new OpsJobService.Submission(OpsJobKind.OPS_JOB_ZONE_SNAPSHOT, PATH, canonical, request,
                operator, reason, key, (int) Math.min(Integer.MAX_VALUE, planned), new Job(zoneList, note)));
    }

    /** 作业体。 */
    final class Job implements JobBody {
        private final List<Integer> zoneIds;
        private final String note;

        Job(List<Integer> zoneIds, String note) {
            this.zoneIds = zoneIds;
            this.note = note;
        }

        @Override
        public JobResult run(JobContext ctx) {
            Map<String, Object> byZone = new LinkedHashMap<>();
            int total = 0;
            String noteText = note.isEmpty() ? "job:" + ctx.jobIdText() : note + " job:" + ctx.jobIdText();
            if (noteText.length() > 256) {
                noteText = noteText.substring(0, 256);
            }
            String finalNote = noteText;
            for (int zone : zoneIds) {
                int count = 0;
                long after = 0;
                while (true) {
                    ctx.checkpoint();
                    if (ctx.cancelRequested() || ctx.timedOut()) {
                        byZone.put(Integer.toUnsignedString(zone), count);
                        Map<String, Object> s = Map.of("byZone", byZone, "total", total + count,
                                "stoppedAt", Integer.toUnsignedLong(zone));
                        return new JobResult(ctx.timedOut() ? OpsJobStatus.OPS_JOB_FAILED : OpsJobStatus.OPS_JOB_CANCELLED,
                                ctx.timedOut() ? "job_timeout" : "cancelled", total + count, total + count, 0, 0, 0, false,
                                false, s);
                    }
                    List<PlayerBrief> page = players.listInZone(zone, after, BATCH);
                    if (page.isEmpty()) {
                        break;
                    }
                    Integer written = tx.execute(status -> {
                        int n = 0;
                        long now = clock.millis();
                        for (PlayerBrief brief : page) {
                            PersistedPlayer p = players.find(brief.getPlayerId());
                            if (p == null) {
                                continue;
                            }
                            OptionalLong id = ids.tryNext();
                            if (id.isEmpty()) {
                                status.setRollbackOnly();
                                return -1;
                            }
                            PlayerSnapshotRow row = new PlayerSnapshotRow(id.getAsLong(), p.getPlayerId(), p.persistedAtMs(),
                                    SnapshotCauses.PRE_MAINTENANCE, p.getZoneId(), p.savedEpoch(), p.getLevel(),
                                    p.getSceneConfigId(), p.getPosX(), p.getPosY(), p.getPosZ(), p.stateBytes());
                            if (snapshots.insertDirect(row, now, ctx.operator(), finalNote) != 1) {
                                throw new IllegalStateException("维护前快照插入没有恰好影响 1 行");
                            }
                            n++;
                        }
                        return n;
                    });
                    if (written == null || written < 0) {
                        byZone.put(Integer.toUnsignedString(zone), count);
                        return new JobResult(OpsJobStatus.OPS_JOB_FAILED, OpsException.ID_UNAVAILABLE, total + count,
                                total + count, 0, 0, 0, false, false,
                                Map.of("byZone", byZone, "total", total + count, "stoppedAt", Integer.toUnsignedLong(zone)));
                    }
                    count += written;
                    after = page.get(page.size() - 1).getPlayerId();
                    if (page.size() < BATCH) {
                        break;
                    }
                }
                byZone.put(Integer.toUnsignedString(zone), count);
                total += count;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("byZone", byZone);
            summary.put("total", total);
            return new JobResult(OpsJobStatus.OPS_JOB_SUCCEEDED, total == 0 ? "zone_empty" : "ok", total, total, 0, 0, 0,
                    false, false, summary);
        }
    }
}
