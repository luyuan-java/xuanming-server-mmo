package com.game.data.snapshot;

import com.game.audit.proto.SnapshotCause;
import java.util.List;
import java.util.Locale;

/**
 * 快照原因（{@code xm.audit.SnapshotCause}）的分类：按时刻选源的白名单、两类保留期（data-ops-spec §3.2、§3.3、§3.7）。
 */
public final class SnapshotCauses {

    public static final int LOGIN = SnapshotCause.SNAPSHOT_LOGIN_VALUE;
    public static final int LOGOUT = SnapshotCause.SNAPSHOT_LOGOUT_VALUE;
    public static final int PERIODIC = SnapshotCause.SNAPSHOT_PERIODIC_VALUE;
    public static final int PRE_TRADE = SnapshotCause.SNAPSHOT_PRE_TRADE_VALUE;
    public static final int PRE_MAINTENANCE = SnapshotCause.SNAPSHOT_PRE_MAINTENANCE_VALUE;
    public static final int GM_MANUAL = SnapshotCause.SNAPSHOT_GM_MANUAL_VALUE;
    public static final int PRE_ROLLBACK = SnapshotCause.SNAPSHOT_PRE_ROLLBACK_VALUE;
    public static final int PRE_GM_EDIT = SnapshotCause.SNAPSHOT_PRE_GM_EDIT_VALUE;

    /**
     * 按时刻选「T 之前最近的状态」时可用的原因。<b>不含</b> PRE_ROLLBACK / PRE_GM_EDIT：安全快照记录的是「被覆盖之前」的状态，
     * 覆盖之后那一刻的真实状态已经变了，拿它当 T 时刻的状态是错的（修正基线 B5）；安全快照只能按号显式选用（撤销上一次编辑）。
     * 也不含 PRE_TRADE（还没有生产方，语义随交易写侧再定）。
     */
    public static final List<Integer> POINT_IN_TIME_SOURCES = List.of(LOGIN, LOGOUT, PERIODIC, PRE_MAINTENANCE, GM_MANUAL);

    /** {@code xm.data.retention.player-snapshot} 清的原因：上下线与周期快照（量大、可再生）。 */
    public static final List<Integer> ROUTINE_RETENTION = List.of(LOGIN, LOGOUT, PERIODIC);

    /**
     * {@code xm.data.retention.gm-snapshot} 清的原因：运维与安全快照（撤销依据，缺省永久）。
     * 不在两个名单里的原因（PRE_TRADE、本版本不认识的值）<b>从不</b>被清理：宁可多留，不误删撤销依据。
     */
    public static final List<Integer> GM_RETENTION = List.of(PRE_MAINTENANCE, GM_MANUAL, PRE_ROLLBACK, PRE_GM_EDIT);

    /** 手工快照接口接受的原因（96 / 103 / 105；整区维护前快照的批量入口随 7.2b）。 */
    public static final List<Integer> MANUAL = List.of(GM_MANUAL, PRE_MAINTENANCE);

    private SnapshotCauses() {
    }

    /** 名字（去掉 {@code SNAPSHOT_} 前缀，如 {@code LOGIN}、{@code GM_MANUAL}）；本版本不认识的值给 {@code UNKNOWN_<数值>}。 */
    public static String name(int cause) {
        SnapshotCause known = SnapshotCause.forNumber(cause);
        if (known == null || known == SnapshotCause.SNAPSHOT_CAUSE_UNSPECIFIED) {
            return "UNKNOWN_" + Integer.toUnsignedString(cause);
        }
        return known.name().substring("SNAPSHOT_".length());
    }

    /** 解析请求里的原因：名字（不区分大小写，可带 {@code SNAPSHOT_} 前缀）或数值；认不出返回 -1。 */
    public static int parse(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String v = value.trim();
        if (!v.isEmpty() && Character.isDigit(v.charAt(0))) {
            try {
                int n = Integer.parseUnsignedInt(v);
                return n == 0 ? -1 : n;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        String upper = v.toUpperCase(Locale.ROOT);
        String full = upper.startsWith("SNAPSHOT_") ? upper : "SNAPSHOT_" + upper;
        try {
            SnapshotCause cause = SnapshotCause.valueOf(full);
            return cause == SnapshotCause.SNAPSHOT_CAUSE_UNSPECIFIED || cause == SnapshotCause.UNRECOGNIZED
                    ? -1 : cause.getNumber();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }
}
