package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * InnoDB 自己记下的「最近一次死锁」（SHOW ENGINE INNODB STATUS 的 LATEST DETECTED DEADLOCK 一段；照 mmorpg economy_repo_test.go:559-638
 * econDeadlockWatch）：它不经过任何一层重试，是唯一看得见被吸收掉的 1213（含夹具里裸 JDBC 占锁事务那一侧）的地方。
 *
 * <p>与基线的差异：本机 MySQL 实例上还有别的模块的测试在并发跑，「最近一次死锁」可能被别库覆盖——基线在这种情况下判红（宁红勿绿），这里只在
 * 新现场<b>出现本测试库名</b>时判红，否则只记一行日志；判据的主干仍是事务基座的重跑记录器（GuildTx / BackgroundTx 的 Listener）。
 * 读不到（缺 PROCESS 权限）时直接红，不许退化成跳过。
 */
public final class InnoDbDeadlockWatch {

    private static final String HEADER = "LATEST DETECTED DEADLOCK";

    private final GuildMysqlFixture db;
    private final String before;

    private InnoDbDeadlockWatch(GuildMysqlFixture db, String before) {
        this.db = db;
        this.before = before;
    }

    public static InnoDbDeadlockWatch start(GuildMysqlFixture db) throws SQLException {
        return new InnoDbDeadlockWatch(db, latest(db));
    }

    /** 全部并发结束之后在主线程调用。 */
    public void assertNone(String what) throws SQLException {
        String after = latest(db);
        if (after.equals(before)) {
            return;
        }
        assertThat(after).as("%s：本测试库 %s 出现了新的 InnoDB 死锁（1213，已被重试吸收，返回值看不出来）。现场：%n%s", what,
                db.database, after).doesNotContain("`" + db.database + "`");
        System.out.println("[InnoDbDeadlockWatch] " + what + "：期间同一实例记录了别库的死锁（不判红）");
    }

    private static String latest(GuildMysqlFixture db) throws SQLException {
        try (Connection c = db.dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW ENGINE INNODB STATUS")) {
            assertThat(rs.next()).as("SHOW ENGINE INNODB STATUS 没有返回行：测试账号需要 PROCESS 权限").isTrue();
            String status = rs.getString(3);
            int start = status == null ? -1 : status.indexOf(HEADER);
            if (start < 0) {
                return "";
            }
            String section = status.substring(start);
            int end = section.indexOf("\nTRANSACTIONS\n");
            return end >= 0 ? section.substring(0, end) : section;
        }
    }
}
