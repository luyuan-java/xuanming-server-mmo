package com.game.data.ops;

import com.game.data.ops.pb.AuditReplayLineRow;
import com.game.data.ops.pb.OpsActiveRow;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.RecallSourceRow;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/**
 * 运维作业表的登记与建表（{@code xm/data/ops_tables.proto}，data-ops-spec §7.3）：启动时在一条自动提交的连接上
 * {@link PbMysql#syncAll}（{@code GET_LOCK} 保护、只扩不缩、会补建缺失的普通索引；结构漂移抛异常、拒绝启动）。
 * 写法同 xm-trade 的 TradeTables。
 */
public final class OpsTables {

    public static final String OPS_JOB = "ops_job";
    public static final String OPS_ACTIVE = "ops_active";
    public static final String OPS_JOB_PLAYER = "ops_job_player";
    public static final String OPS_JOB_EVENT = "ops_job_event";
    public static final String RECALL_SOURCE = "recall_source";
    public static final String AUDIT_REPLAY_LINE = "audit_replay_line";

    /** 表名，与 {@link #PROTOTYPES} 一一对应（OpsTablesTest 钉住与 proto 的 table_name 一致）。 */
    public static final List<String> NAMES = List.of(OPS_JOB, OPS_ACTIVE, OPS_JOB_PLAYER, OPS_JOB_EVENT, RECALL_SOURCE,
            AUDIT_REPLAY_LINE);

    public static final List<Message> PROTOTYPES = List.of(OpsJobRow.getDefaultInstance(),
            OpsActiveRow.getDefaultInstance(), OpsJobPlayerRow.getDefaultInstance(), OpsJobEventRow.getDefaultInstance(),
            RecallSourceRow.getDefaultInstance(), AuditReplayLineRow.getDefaultInstance());

    private OpsTables() {
    }

    /** 登记了全部运维表的 pbmysql 实例（不碰库）。 */
    public static PbMysql registry() {
        PbMysql db = new PbMysql();
        for (Message prototype : PROTOTYPES) {
            db.register(prototype);
        }
        return db;
    }

    /**
     * 在一条自动提交的连接上建表 / 只扩不缩地同步。连接串上给请求流量的 socketTimeout 不够等咨询锁加 DDL：
     * 这条连接上临时放宽到 {@code networkTimeout}，用完还原（同 TradeTables.sync）。
     */
    public static PbMysql sync(DataSource dataSource, Duration networkTimeout) throws SQLException {
        PbMysql db = registry();
        try (Connection c = dataSource.getConnection()) {
            sync(db, c, networkTimeout);
        }
        return db;
    }

    /** 同上，在调用方给的连接上（回灌工具用自己的连接）。 */
    public static void sync(PbMysql db, Connection c, Duration networkTimeout) throws SQLException {
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
}
