package com.game.discovery.sql;

import com.alibaba.druid.pool.DruidDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/** 权威业务库的每物理连接策略；由各服务静态@Bean在Druid属性绑定后、初始化前安装。 */
public final class StrongTiDbConnectionPolicy implements BeanPostProcessor, Ordered {
    public static final String SESSION_SQL = "SET SESSION tidb_snapshot='',tidb_read_staleness=0,"
            + "tidb_low_resolution_tso=OFF,tidb_read_consistency='strict',tidb_replica_read='leader',"
            + "tidb_txn_mode='pessimistic',transaction_isolation='READ-COMMITTED',"
            + "sql_mode=CONCAT(@@SESSION.sql_mode,',STRICT_TRANS_TABLES')";
    private final boolean requireTiDb;
    private final String domain;
    public StrongTiDbConnectionPolicy(String domain, boolean requireTiDb) {
        this.domain = java.util.Objects.requireNonNull(domain);
        this.requireTiDb = requireTiDb;
    }
    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (!requireTiDb || !(bean instanceof DataSource)) return bean;
        if (!(bean instanceof DruidDataSource pool) || pool.isInited()) {
            throw new IllegalStateException(domain + "权威库必须使用尚未初始化的Druid池，不能保证物理连接策略时拒启");
        }
        var init = pool.getConnectionInitSqls() == null ? new ArrayList<String>() : new ArrayList<>(pool.getConnectionInitSqls());
        init.add(SESSION_SQL);
        pool.setConnectionInitSqls(init);
        return bean;
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    /** 在建表/暴露服务前，用同一借出的物理会话复核；MySQL开发分支不发送TiDB变量。 */
    public static void verify(Connection connection, boolean requireTiDb) throws SQLException {
        String version;
        try (var statement=connection.createStatement();var rows=statement.executeQuery("SELECT VERSION()")) {
            if (!rows.next()) throw new SQLException("无法确认权威库类型");
            version=rows.getString(1);
        }
        boolean tidb=version!=null && version.toLowerCase(Locale.ROOT).contains("tidb");
        if (requireTiDb && !tidb) throw new SQLException("权威库要求TiDB");
        if (!tidb) return;
        try (var statement=connection.createStatement();var rows=statement.executeQuery("SELECT @@SESSION.tidb_snapshot,@@SESSION.tidb_read_staleness,@@SESSION.tidb_low_resolution_tso,@@SESSION.tidb_read_consistency,@@SESSION.tidb_replica_read,@@SESSION.tidb_txn_mode,@@SESSION.transaction_isolation")) {
            if (!rows.next() || !"".equals(rows.getString(1)) || rows.getLong(2)!=0 || rows.getBoolean(3)
                    || !"strict".equalsIgnoreCase(rows.getString(4)) || !"leader".equalsIgnoreCase(rows.getString(5))
                    || !"pessimistic".equalsIgnoreCase(rows.getString(6)) || !"READ-COMMITTED".equalsIgnoreCase(rows.getString(7))) {
                throw new SQLException("权威库会话不是悲观RC强读（snapshot/staleness/lowres/strict/leader）");
            }
        }
    }
}
