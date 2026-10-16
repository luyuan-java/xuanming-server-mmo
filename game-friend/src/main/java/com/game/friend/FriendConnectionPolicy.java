package com.game.friend;

import com.alibaba.druid.pool.DruidDataSource;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/** 在连接池初始化之前固定生产好友的会话策略，每个新物理连接与断线重建都执行。 */
final class FriendConnectionPolicy implements BeanPostProcessor, Ordered {
    static final String SESSION_SQL = "SET SESSION tidb_snapshot='',tidb_read_staleness=0,"
            + "tidb_low_resolution_tso=OFF,tidb_read_consistency='strict',tidb_replica_read='leader',"
            + "tidb_txn_mode='pessimistic',transaction_isolation='READ-COMMITTED',"
            + "sql_mode=CONCAT(@@SESSION.sql_mode,',STRICT_TRANS_TABLES'),innodb_lock_wait_timeout=3";

    private final boolean requireTiDb;

    FriendConnectionPolicy(boolean requireTiDb) {
        this.requireTiDb = requireTiDb;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!requireTiDb || !(bean instanceof DataSource)) {
            return bean;
        }
        if (!(bean instanceof DruidDataSource pool)) {
            throw new IllegalStateException("生产好友强读策略只支持初始化前的 Druid 连接池");
        }
        if (pool.isInited()) {
            throw new IllegalStateException("好友连接池已提前初始化，无法保证全部物理连接的强读策略");
        }
        // 属性绑定先于本钩子；把强制策略放最后，覆盖外部 URL 或自定义初始化语句中的弱读设置。
        var statements = pool.getConnectionInitSqls() == null
                ? new ArrayList<String>() : new ArrayList<>(pool.getConnectionInitSqls());
        statements.add(SESSION_SQL);
        pool.setConnectionInitSqls(statements);
        return bean;
    }

    @Override
    public int getOrder() {
        // ConfigurationPropertiesBindingPostProcessor 是 PriorityOrdered，必须先完成 Druid 属性绑定。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
