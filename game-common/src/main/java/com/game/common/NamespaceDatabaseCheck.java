package com.game.common;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 连上库之后、建表或写库之前的库名核对：{@code SELECT DATABASE()} 必须等于期望库名。规格是<b>库名恒等于部署命名空间</b>（JDBC URL 的库名段写
 * {@code ${game.namespace:game}}，部署只覆盖 host / port，deploy-ci-spec §2.2），期望库名缺省就是命名空间。偏离规格（库名另取）的存量部署
 * 只能经应急开关显式给出期望库名：服务进程 {@code game.mysql.expect-database}，命令行工具 {@code --expect-database} / {@code -expect-database}。
 *
 * <p>用错命名空间时 Redis 键 / Kafka 名字与库会分属两个部署：服务会把 player 行、{@code owner_epoch} 围栏、运维表写进另一个部署的库；回灌会在别的部署的
 * SCENE_GUID 池里占 worker、发出的号可能与目标库里已有的号相撞；assetopfix 会失效错误的缓存键；PasswordAdmin 会给另一个游戏的账号设口令。
 * 使用者：game-discovery 的 {@code NamespaceDatabaseVerifier}（全部连库的服务进程）、AuditFallbackReplay、assetopfix、PasswordAdmin。只依赖 java.sql。
 *
 * <p>报错文本只含命名空间、DATABASE()、期望值与应急开关的写法，不含 JDBC URL（URL 里可能带口令）。
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
     * @param actual       {@code DATABASE()} 的结果
     * @param expected     应急开关给的期望库名；null = 取命名空间（规格）
     * @param namespace    部署命名空间
     * @param overrideHint 调用方自己的应急开关写法（写进报错），如 {@code "--expect-database <库名>"}、{@code "-expect-database <库名>"}、
     *                     {@code "game.mysql.expect-database（环境变量 GAME_MYSQL_EXPECT_DATABASE）"}
     */
    public static String mismatch(String actual, String expected, GameNamespace namespace, String overrideHint) {
        String want = expected == null ? namespace.value() : expected;
        if (want.equals(actual)) {
            return null;
        }
        return "库名核对失败：部署命名空间 " + GameNamespace.PROPERTY + "=" + namespace + "，当前连接的库 DATABASE()=" + actual + "，期望 " + want
                + (expected == null ? "（规格：库名 = 命名空间）" : "（应急开关 " + overrideHint + "）")
                + "。命名空间与库必须属于同一个部署：检查环境变量 " + GameNamespace.ENV + " 与 JDBC URL 的库名（部署只覆盖 host / port，不整串覆盖 URL）；"
                + "库名偏离规格的存量部署应急时用 " + overrideHint + " 给出期望库名";
    }

    /** {@link #currentDatabase} + {@link #mismatch}。 */
    public static String verify(Connection connection, String expected, GameNamespace namespace, String overrideHint) throws SQLException {
        return mismatch(currentDatabase(connection), expected, namespace, overrideHint);
    }
}
