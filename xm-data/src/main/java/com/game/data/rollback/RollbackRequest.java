package com.game.data.rollback;

import com.game.data.ops.OpsException;
import com.game.data.ops.OpsRequests;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 一次回档请求（data-ops-spec §4.3），已校验、已规范化（玩家号 / 区号去重升序）。只做不碰库的校验；区服状态与快照归属在
 * {@link RollbackService} 里查库。
 *
 * @param players          scope=players 时的目标（1–100 个，升序去重）
 * @param zones            scope=zones 时的区（升序去重；allZones 时由服务从区服目录展开）
 * @param snapshotId       按号选快照（只允许单个玩家）；与 targetTimeMs 二选一
 * @param targetTimeMs     按时刻选快照（该时刻之前最近的一份，不含安全快照）
 * @param sections         空 = FULL
 */
public record RollbackRequest(Scope scope, List<Long> players, List<Integer> zones, boolean allZones, Long snapshotId,
                              Long targetTimeMs, Set<RollbackSection> sections, IfOnline ifOnline,
                              boolean acceptDivergence, boolean acceptRecallReversal, String reason, boolean dryRun) {

    public static final String PATH = "/admin/rollbacks";
    public static final int MAX_PLAYERS = 100;

    public enum Scope {
        PLAYERS, ZONES
    }

    /** 目标在线时：拒绝（缺省）或走顶号通路踢下线。 */
    public enum IfOnline {
        REJECT, KICK
    }

    /** 请求体（JSON）。玩家号、快照号是十进制字符串（uint64）。 */
    public record Body(String scope, List<String> players, List<Long> zones, Boolean allZones, String snapshotId,
                       Long targetTimeMs, List<String> sections, String ifOnline, Boolean acceptDivergence,
                       Boolean acceptRecallReversal, String reason, Boolean dryRun) {
    }

    /**
     * 校验并规范化。
     *
     * @param nowMs        现在
     * @param minTargetAge 目标时刻至少早于现在多久（快照经 Kafka 落库有延迟）
     */
    public static RollbackRequest parse(Body body, long nowMs, Duration minTargetAge) {
        if (body == null) {
            throw OpsException.badRequest("缺少请求体");
        }
        String reason = OpsRequests.reason(body.reason());
        Scope scope = switch (body.scope() == null ? "" : body.scope().trim().toLowerCase(Locale.ROOT)) {
            case "players" -> Scope.PLAYERS;
            case "zones" -> Scope.ZONES;
            default -> throw OpsException.badRequest("scope 只能是 players / zones");
        };
        IfOnline ifOnline = switch (body.ifOnline() == null ? "reject" : body.ifOnline().trim().toLowerCase(Locale.ROOT)) {
            case "reject" -> IfOnline.REJECT;
            case "kick" -> IfOnline.KICK;
            default -> throw OpsException.badRequest("ifOnline 只能是 reject / kick");
        };
        Long snapshotId = body.snapshotId() == null ? null : OpsRequests.u64("snapshotId", body.snapshotId());
        Long target = body.targetTimeMs();
        if ((snapshotId == null) == (target == null)) {
            throw OpsException.badRequest("snapshotId 与 targetTimeMs 二选一");
        }
        if (snapshotId != null && snapshotId == 0) {
            throw OpsException.badRequest("snapshotId 必须非 0");
        }
        if (target != null) {
            if (target <= 0) {
                throw OpsException.badRequest("targetTimeMs 必须为正");
            }
            if (target > nowMs - minTargetAge.toMillis()) {
                throw OpsException.badRequest("targetTimeMs 至少要早于现在 " + minTargetAge.toSeconds()
                        + " 秒（快照经 Kafka 落库有延迟）");
            }
        }
        Set<RollbackSection> sections = RollbackSection.parse(body.sections());
        List<Long> players = new ArrayList<>();
        List<Integer> zones = new ArrayList<>();
        boolean allZones = Boolean.TRUE.equals(body.allZones());
        if (scope == Scope.PLAYERS) {
            if (body.zones() != null && !body.zones().isEmpty() || allZones) {
                throw OpsException.badRequest("scope=players 不带 zones / allZones");
            }
            TreeSet<Long> distinct = new TreeSet<>(Long::compareUnsigned);
            for (String p : body.players() == null ? List.<String>of() : body.players()) {
                long id = OpsRequests.u64("players", p);
                if (id == 0) {
                    throw OpsException.badRequest("players 不得含 0");
                }
                distinct.add(id);
            }
            if (distinct.isEmpty() || distinct.size() > MAX_PLAYERS) {
                throw OpsException.badRequest("players 必须是 1–" + MAX_PLAYERS + " 个玩家号（去重后）");
            }
            if (snapshotId != null && distinct.size() != 1) {
                throw OpsException.badRequest("snapshotId 只允许单个玩家（多人用 targetTimeMs）");
            }
            players.addAll(distinct);
        } else {
            if (body.players() != null && !body.players().isEmpty()) {
                throw OpsException.badRequest("scope=zones 不带 players");
            }
            if (snapshotId != null) {
                throw OpsException.badRequest("整区回档只能按时刻（targetTimeMs）");
            }
            if (body.ifOnline() != null && ifOnline != IfOnline.KICK) {
                throw OpsException.badRequest("整区回档一律 kick（区服须先进维护态）；不要传 ifOnline=reject");
            }
            ifOnline = IfOnline.KICK;
            TreeSet<Integer> distinct = new TreeSet<>(Integer::compareUnsigned);
            for (Long z : body.zones() == null ? List.<Long>of() : body.zones()) {
                if (z == null || z <= 0 || z > 0xFFFF_FFFFL) {
                    throw OpsException.badRequest("zones 必须是 1–4294967295 的区号");
                }
                distinct.add(z.intValue());
            }
            if (distinct.isEmpty() == !allZones) {
                throw OpsException.badRequest("zones 非空与 allZones=true 二选一");
            }
            zones.addAll(distinct);
        }
        return new RollbackRequest(scope, List.copyOf(players), List.copyOf(zones), allZones, snapshotId, target,
                sections, ifOnline, Boolean.TRUE.equals(body.acceptDivergence()),
                Boolean.TRUE.equals(body.acceptRecallReversal()), reason, Boolean.TRUE.equals(body.dryRun()));
    }

    /** allZones 展开之后的请求（区号来自区服目录）。 */
    public RollbackRequest withZones(List<Integer> expanded) {
        return new RollbackRequest(scope, players, List.copyOf(expanded), allZones, snapshotId, targetTimeMs, sections,
                ifOnline, acceptDivergence, acceptRecallReversal, reason, dryRun);
    }

    public boolean full() {
        return sections.isEmpty();
    }

    public List<String> sectionNames() {
        return sections.stream().map(RollbackSection::wire).toList();
    }

    /** 规范化请求（进指纹；字段顺序固定，同一语义的请求得到同一个串）。 */
    public String canonical() {
        return "scope=" + scope.name().toLowerCase(Locale.ROOT)
                + "\nplayers=" + players.stream().map(Long::toUnsignedString).collect(Collectors.joining(","))
                + "\nzones=" + (allZones ? "all" : zones.stream().map(Integer::toUnsignedString).collect(Collectors.joining(",")))
                + "\nsnapshot=" + (snapshotId == null ? "" : Long.toUnsignedString(snapshotId))
                + "\ntarget=" + (targetTimeMs == null ? "" : targetTimeMs)
                + "\nsections=" + (full() ? "FULL" : String.join(",", sectionNames()))
                + "\nifOnline=" + ifOnline.name().toLowerCase(Locale.ROOT)
                + "\nacceptDivergence=" + acceptDivergence
                + "\nacceptRecallReversal=" + acceptRecallReversal
                + "\nreason=" + reason;
    }

    /** 进 request_json 的视图。 */
    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope.name().toLowerCase(Locale.ROOT));
        if (scope == Scope.PLAYERS) {
            m.put("players", players.stream().map(Long::toUnsignedString).toList());
        } else {
            m.put("zones", zones.stream().map(Integer::toUnsignedLong).toList());
            m.put("allZones", allZones);
        }
        m.put("snapshotId", snapshotId == null ? null : Long.toUnsignedString(snapshotId));
        m.put("targetTimeMs", targetTimeMs);
        m.put("sections", full() ? "FULL" : sectionNames());
        m.put("ifOnline", ifOnline.name().toLowerCase(Locale.ROOT));
        m.put("acceptDivergence", acceptDivergence);
        m.put("acceptRecallReversal", acceptRecallReversal);
        m.put("reason", reason);
        return m;
    }
}
