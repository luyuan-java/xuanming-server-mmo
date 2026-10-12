package com.game.data.rollback;

import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerBrief;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回档计划（只读，data-ops-spec §4.4、§4.9）：逐人选快照。计划时选中的快照号会钉进 {@code ops_job_player}（PLANNED），执行时直接用它、
 * 不再重选——比基线 R5「重选不得更早」更强：帮会检查用的起点与实际恢复的内容一定对应同一份快照。
 *
 * <ul>
 *   <li>按号：快照必须属于该玩家（受理时已核对，这里再核一次：执行前被保留期删了 → {@code snapshot_gone}）。</li>
 *   <li>按时刻：{@code findLatestAtOrBefore}，原因白名单 {@link SnapshotCauses#POINT_IN_TIME_SOURCES}（不含安全快照）。</li>
 *   <li>整区目标 = {@code player.zone_id ∈ zones} 的玩家（归属区，权威列，走 {@code idx_player_zone}；D10）。没有快照的玩家只报告、从不删除，
 *       按 {@code player.created_at} 分两类：{@code created_after_target}（建于 T 之后）与 {@code no_snapshot}（建于 T 之前却没有快照）。
 *       快照所在 zone 与归属区不同的玩家在计划摘要里单列（仅提示）。</li>
 * </ul>
 */
public final class RollbackPlanner {

    /** 整区按玩家号分页读目标的页大小。 */
    static final int ZONE_PAGE = 500;

    public static final String PLAYER_NOT_FOUND = "player_not_found";
    public static final String NO_SNAPSHOT = "no_snapshot";
    public static final String CREATED_AFTER_TARGET = "created_after_target";
    public static final String SNAPSHOT_NOT_FOUND = "snapshot_not_found";

    /**
     * 一个目标。
     *
     * @param snapshot 选中的快照（元数据；按号选时含本体）；没有为 null
     * @param outcome  计划阶段就定了结局（没有快照 / 玩家不存在）；null = PLANNED
     */
    public record Target(long playerId, int zoneId, long createdAt, PlayerSnapshotEntry snapshot, String outcome) {

        public boolean planned() {
            return outcome == null;
        }
    }

    private final PersistedPlayerMapper players;
    private final PlayerSnapshotMapper snapshots;

    public RollbackPlanner(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots) {
        this.players = players;
        this.snapshots = snapshots;
    }

    /** 按请求列出全部目标（scope=players 按请求顺序（升序）；整区按区、再按玩家号升序）。 */
    public List<Target> plan(RollbackRequest req) {
        List<Target> out = new ArrayList<>();
        if (req.scope() == RollbackRequest.Scope.PLAYERS) {
            for (long playerId : req.players()) {
                PlayerBrief brief = players.findBrief(playerId);
                if (brief == null) {
                    out.add(new Target(playerId, 0, 0, null, PLAYER_NOT_FOUND));
                    continue;
                }
                out.add(select(brief, req));
            }
            return out;
        }
        for (int zone : req.zones()) {
            long after = 0;
            while (true) {
                List<PlayerBrief> page = players.listInZone(zone, after, ZONE_PAGE);
                for (PlayerBrief brief : page) {
                    out.add(select(brief, req));
                }
                if (page.size() < ZONE_PAGE) {
                    break;
                }
                after = page.get(page.size() - 1).getPlayerId();
            }
        }
        return out;
    }

    private Target select(PlayerBrief brief, RollbackRequest req) {
        long playerId = brief.getPlayerId();
        PlayerSnapshotEntry snapshot;
        if (req.snapshotId() != null) {
            snapshot = snapshots.findById(req.snapshotId());
            if (snapshot == null || snapshot.getPlayerId() != playerId) {
                return new Target(playerId, brief.getZoneId(), brief.getCreatedAt(), null, SNAPSHOT_NOT_FOUND);
            }
        } else {
            snapshot = snapshots.findLatestAtOrBefore(playerId, req.targetTimeMs(), SnapshotCauses.POINT_IN_TIME_SOURCES);
            if (snapshot == null) {
                return new Target(playerId, brief.getZoneId(), brief.getCreatedAt(), null,
                        brief.getCreatedAt() > req.targetTimeMs() ? CREATED_AFTER_TARGET : NO_SNAPSHOT);
            }
        }
        return new Target(playerId, brief.getZoneId(), brief.getCreatedAt(), snapshot, null);
    }

    /** 计划摘要：总数、逐结局计数、快照所在区与归属区不同的玩家（前 100 个）。 */
    public static Map<String, Object> summary(List<Target> targets) {
        Map<String, Integer> byOutcome = new LinkedHashMap<>();
        List<String> zoneMismatch = new ArrayList<>();
        int mismatchCount = 0;
        int planned = 0;
        for (Target t : targets) {
            if (t.planned()) {
                planned++;
                if (t.snapshot().getZoneId() != t.zoneId()) {
                    mismatchCount++;
                    if (zoneMismatch.size() < 100) {
                        zoneMismatch.add(Long.toUnsignedString(t.playerId()));
                    }
                }
            } else {
                byOutcome.merge(t.outcome(), 1, Integer::sum);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targets", targets.size());
        m.put("planned", planned);
        m.put("unplanned", byOutcome);
        m.put("snapshotZoneMismatch", zoneMismatch);
        m.put("snapshotZoneMismatchCount", mismatchCount);
        return m;
    }
}
