package com.game.match.rating;

import com.game.match.rating.pb.MatchRatingAppliedRow;
import com.game.match.rating.pb.MatchRatingRow;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/**
 * 评分两张表的登记与建表（match-spec §5.2、§9.8 启动第 6 步）。表定义是 Java 自有的 {@code xm/match/match_tables.proto}；建在 {@code xm_java}，
 * 启动期在一条自动提交的连接上 {@link PbMysql#syncAll}（{@code GET_LOCK} 保护、只扩不缩；结构漂移拒启，同 xm-trade / xm-guild）。
 * 存量库不需要手工迁移：表不存在就建，回滚 = 回退代码，表可以留着。
 *
 * <p>{@link RatingStore} 里手写 SQL 的表名 / 列名字面量必须与这里（= proto 的 {@code table_name} 与字段名）一致，{@code MatchRatingTablesTest} 钉住。
 */
public final class MatchRatingTables {

    /** match_rating：玩家评分，每人一行；没有行 = 新号 1500.00。 */
    public static final String RATING = "match_rating";
    /** match_rating_applied：入账标记，每局计分对局一行（主键冲突 = 重复投递）。 */
    public static final String APPLIED = "match_rating_applied";

    /** 表名，与 {@link #PROTOTYPES} 一一对应。 */
    public static final List<String> NAMES = List.of(RATING, APPLIED);

    /** 表消息原型。 */
    public static final List<Message> PROTOTYPES = List.of(MatchRatingRow.getDefaultInstance(), MatchRatingAppliedRow.getDefaultInstance());

    /**
     * 建表这一步。生产恒为 {@link #PBMYSQL}；只有不连 MySQL 的上下文测试换成别的（H2 不认 {@code GET_LOCK} 与 MySQL 的 information_schema，
     * 测试里改用同一份 DDL 直接建）。
     */
    @FunctionalInterface
    public interface SchemaSync {

        /** 让两张表就绪；失败抛异常（进程拒启）。 */
        void sync(DataSource dataSource) throws SQLException;
    }

    /** 结构同步连接的 socket 超时（咨询锁最多等 30 s，再加 DDL）。 */
    static final Duration SCHEMA_SYNC_NETWORK_TIMEOUT = Duration.ofMinutes(2);

    /** 生产的建表：xm-pbmysql 同步。 */
    public static final SchemaSync PBMYSQL = dataSource -> sync(dataSource, SCHEMA_SYNC_NETWORK_TIMEOUT);

    private MatchRatingTables() {
    }

    /** 登记了两张表的 pbmysql 实例（不碰库）。 */
    public static PbMysql registry() {
        PbMysql db = new PbMysql();
        for (Message prototype : PROTOTYPES) {
            db.register(prototype);
        }
        return db;
    }

    /**
     * 在一条自动提交的连接上建表 / 只扩不缩地同步（整轮持 pbmysql 的咨询锁；结构漂移抛异常，启动失败）。
     * 连接串上给请求流量的 socketTimeout 不够等咨询锁加 DDL：这条连接上临时放宽到 {@code networkTimeout}，用完还原（同 {@code TradeTables.sync}）。
     */
    public static PbMysql sync(DataSource dataSource, Duration networkTimeout) throws SQLException {
        PbMysql db = registry();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            int socketTimeout = c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run, (int) networkTimeout.toMillis());
            try {
                db.syncAll(c);
            } finally {
                if (!c.isClosed()) {
                    c.setNetworkTimeout(Runnable::run, socketTimeout);
                }
            }
        }
        return db;
    }
}
