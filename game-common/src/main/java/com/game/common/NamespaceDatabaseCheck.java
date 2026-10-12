package com.game.common;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 命令行工具（AuditFallbackReplay、assetopfix）连上库之后、建表或写库之前的库名核对：{@code SELECT DATABASE()} 必须等于期望库名
 * （缺省 = 部署命名空间，库名另取的部署用 {@code --expect-database} 给出）。用错命名空间时 Redis 键与库会分属两个部署：回灌会在别的部署的
 * SCENE_GUID 池里占 worker、发出的号可能与目标库里已有的号相撞而被当成重复静默跳过；assetopfix 会失效错误的缓存键。只依赖 java.sql。
 *
 * <p>报错文本只含命名空间、DATABASE() 与期望值，不含 JDBC URL（URL 里可能带口令）。
 */
public final class NamespaceDatabaseCheck {

    private NamespaceDatabaseCheck() {
    }

    /** 当前连接的库名（{@code SELECT DATABASE()}；没有选库时为 null）。 */
    public static String currentDatabase(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * 核对库名；一致返回 null，不一致返回报错文案。
     *
     * @param actual         {@code DATABASE()} 的结果
     * @param expected       期望库名（{@code --expect-database}；null = 取命名空间）
     * @param namespace      部署命名空间
     */
    public static String mismatch(String actual, String expected, String namespace) {
        String want = expected == null ? namespace : expected;
        if (want.equals(actual)) {
            return null;
        }
        return "库名核对失败：部署命名空间 " + GameNamespace.PROPERTY + "=" + namespace + "，当前连接的库 DATABASE()=" + actual + "，期望 " + want
                + (expected == null ? "（缺省等于命名空间）" : "（--expect-database）")
                + "。命名空间与库必须属于同一个部署：检查环境变量 " + GameNamespace.ENV + " 与 JDBC URL 的库名；库名另取的部署用 --expect-database <库名> 给出期望值";
    }

    /** {@link #currentDatabase} + {@link #mismatch}。 */
    public static String verify(Connection connection, String expected, String namespace) throws SQLException {
        return mismatch(currentDatabase(connection), expected, namespace);
    }
}
