package com.game.data.tools.playerbundle;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实例身份：导出时记下来源实例是谁，导入时比对写目标，<b>目标就是导出来源时拒绝</b>（闸门二）。port-forward 之后线上实例也在
 * 127.0.0.1，只看地址拦不住。
 *
 * <ul>
 *   <li>MySQL：{@code server_uuid:<@@server_uuid>}（每个 mysqld 数据目录唯一，重启不变）；</li>
 *   <li>TiDB（{@code VERSION()} 含 TiDB）：{@code tidb-pd:<按地址排序的 PD 实例地址>}（{@code INFORMATION_SCHEMA.CLUSTER_INFO}）。
 *       PD 地址是集群内部地址，port-forward 出来的线上集群报的仍是线上 PD 地址；本机 playground 报回环地址。两个集群恰好用同一组
 *       内部地址时会被误判为同一实例——那是拒绝方向的误判（fail-closed），可以接受；</li>
 *   <li>Redis：{@code run_id:<INFO server 的 run_id>}。<b>run_id 随 Redis 重启变化</b>：导出之后线上 Redis 重启过，比对就认不出来——
 *       这是已知残余，靠闸门一（本机地址）与导入只删本玩家的少数几把键收窄影响面（使用手册写明）。</li>
 * </ul>
 * 取不到身份返回 null；导入端对 null 一律拒绝（无法证明「不是导出来源」就不写）。
 */
final class InstanceIdentity {

    /** 本地 debug 导入目标 Redis 必须显式置好的身份标记键与值（对齐主仓 debug_import）。 */
    static final String LOCAL_TARGET_MARKER_KEY = "debug_import:local_target";
    static final String LOCAL_TARGET_MARKER_VALUE = "local-debug-import-target";

    private InstanceIdentity() {
    }

    /** 读 MySQL / TiDB 的实例身份；取不到返回 null（H2 等没有这些变量的库）。 */
    static String mysql(Connection connection) {
        String version;
        try {
            version = single(connection, "SELECT VERSION()");
        } catch (SQLException e) {
            return null;
        }
        if (version != null && version.toLowerCase(Locale.ROOT).contains("tidb")) {
            return tidb(connection);
        }
        try {
            String uuid = single(connection, "SELECT @@server_uuid");
            return uuid == null || uuid.isBlank() ? null : "server_uuid:" + uuid.strip();
        } catch (SQLException e) {
            return null;
        }
    }

    private static String tidb(Connection connection) {
        List<String> pd = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT INSTANCE FROM INFORMATION_SCHEMA.CLUSTER_INFO WHERE TYPE = 'pd' ORDER BY INSTANCE")) {
            while (rs.next()) {
                String instance = rs.getString(1);
                if (instance != null && !instance.isBlank()) {
                    pd.add(instance.strip());
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return pd.isEmpty() ? null : "tidb-pd:" + String.join(",", pd);
    }

    /** 从 {@code INFO server} 的结果里取 run_id；取不到返回 null。 */
    static String redis(Map<String, String> infoServer) {
        String runId = infoServer == null ? null : infoServer.get("run_id");
        return runId == null || runId.isBlank() ? null : "run_id:" + runId.strip();
    }

    /**
     * 闸门二：目标身份与导出包记录的<b>同类</b>来源身份（库比库、Redis 比 Redis）都必须取得到，且两者不相同；通过返回 null，否则返回报错文本。
     * 任一侧取不到都拒绝：无法证明「不是导出来源」就不写。
     *
     * @param what     写目标的名字（MySQL / Redis）
     * @param target   目标实例身份；null = 取不到
     * @param exported 导出包记录的同类来源身份；null = 导出时没取到（或 {@code --skip-redis} 没读 Redis）
     */
    static String checkNotExportSource(String what, String target, String exported) {
        if (target == null) {
            return what + " 取不到实例身份（MySQL 需要 @@server_uuid，TiDB 需要 INFORMATION_SCHEMA.CLUSTER_INFO，Redis 需要 INFO server 的 run_id）："
                    + "无法证明它不是导出来源，拒绝写入";
        }
        if (exported == null || exported.isBlank()) {
            return "导出包没有记录 " + what + " 的来源实例身份（导出时没取到，或用了 --skip-redis）：无法证明本地 " + what
                    + " 不是导出来源，拒绝写入；重新导出（不带 --skip-redis）";
        }
        if (exported.equals(target)) {
            return what + " 与导出来源是同一个实例（身份 " + target + "）：导入只写隔离环境，检查是不是把线上 port-forward 当成了本地";
        }
        return null;
    }

    /**
     * 闸门二（Redis 加强版，2026-10-22 评审修正 L948）：
     * <ol>
     *   <li>{@code run_id} 相同仍硬拒（即使带本地标记或 {@code --allow-unverified-target-identity}）；</li>
     *   <li>{@code run_id} 不同不足以证明不是导出来源（线上 Redis HA 切主/重启会换 run_id），必须读取目标 Redis 的
     *       {@code GET debug_import:local_target == "local-debug-import-target"}，缺失则拒绝，或显式传
     *       {@code --allow-unverified-target-identity} 并打印 WARNING。</li>
     * </ol>
     */
    static String checkRedisTargetIdentity(PlayerRedis redis, String exportedRedis,
                                           boolean allowUnverifiedTargetIdentity, PrintStream warnOut) {
        String targetRunId = redis(redis.infoServer());
        String runIdProblem = checkNotExportSource("Redis", targetRunId, exportedRedis);
        if (runIdProblem != null) {
            return runIdProblem;
        }
        List<PlayerRedis.RawEntry> entries = redis.read(List.of(LOCAL_TARGET_MARKER_KEY));
        PlayerRedis.RawEntry marker = entries.isEmpty() ? null : entries.getFirst();
        boolean markerMatched = marker != null && marker.error() == null && "string".equals(marker.type())
                && marker.string() != null
                && LOCAL_TARGET_MARKER_VALUE.equals(new String(marker.string(), StandardCharsets.UTF_8).strip());
        if (markerMatched) {
            return null;
        }
        if (allowUnverifiedTargetIdentity) {
            if (warnOut != null) {
                warnOut.println("[WARNING] 目标 Redis 缺少本地标记键 " + LOCAL_TARGET_MARKER_KEY + "=" + LOCAL_TARGET_MARKER_VALUE
                        + "，已按 --allow-unverified-target-identity 显式放行");
            }
            return null;
        }
        return "目标 Redis 缺少本地标记键 " + LOCAL_TARGET_MARKER_KEY + "=" + LOCAL_TARGET_MARKER_VALUE
                + "（run_id 不同不能证明不是重启/切主后的线上实例）：请在本地 Redis 写入该标记，或确认无误后显式加 --allow-unverified-target-identity";
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
