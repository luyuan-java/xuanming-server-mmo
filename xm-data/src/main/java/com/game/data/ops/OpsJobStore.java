package com.game.data.ops;

import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.RecallSourceRow;
import com.game.pbmysql.PbMysql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * 运维作业表的读写（pbmysql）。每个方法都经 {@link DataSourceUtils#getConnection} 取连接：在 Spring 事务里拿到的是事务绑定的
 * <b>同一条</b>连接，与 MyBatis（快照 / 流水 Mapper）同事务提交或回滚（data-ops-spec §7.5：不另开数据源、不让 pbmysql 自取连接，
 * 否则「快照 + 作业行」不再原子）。SQL 异常经 Spring 的错误码翻译成 {@link DataAccessException}：撞唯一键（幂等键并发）是
 * {@link org.springframework.dao.DuplicateKeyException}。
 */
public final class OpsJobStore {

    /** IN 列表一批的上限。 */
    static final int IN_CHUNK = 500;

    private final DataSource dataSource;
    private final PbMysql db;
    private final SQLExceptionTranslator translator;

    public OpsJobStore(DataSource dataSource, PbMysql db) {
        this.dataSource = dataSource;
        this.db = db;
        this.translator = new SQLErrorCodeSQLExceptionTranslator(dataSource);
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T withConnection(String task, Work<T> work) {
        Connection c = DataSourceUtils.getConnection(dataSource);
        try {
            return work.run(c);
        } catch (SQLException e) {
            DataAccessException translated = translator.translate(task, null, e);
            throw translated != null ? translated
                    : new org.springframework.jdbc.UncategorizedSQLException(task, null, e);
        } finally {
            DataSourceUtils.releaseConnection(c, dataSource);
        }
    }

    public Optional<OpsJobRow> findByIdemKey(String idemKey) {
        return withConnection("ops_job 按幂等键查",
                c -> db.findOne(c, OpsJobRow.class, "`idem_key` = ?", idemKey));
    }

    public Optional<OpsJobRow> findJob(long jobId) {
        return withConnection("ops_job 按号查",
                c -> db.findOneByPk(c, OpsJobRow.newBuilder().setJobId(jobId).build()));
    }

    /** 插入一个作业；幂等键或作业号已存在抛 {@link org.springframework.dao.DuplicateKeyException}。 */
    public void insertJob(OpsJobRow row) {
        withConnection("ops_job 插入", c -> {
            db.insert(c, row);
            return null;
        });
    }

    public void insertEvent(OpsJobEventRow row) {
        withConnection("ops_job_event 插入", c -> {
            db.insert(c, row);
            return null;
        });
    }

    public List<OpsJobEventRow> events(long jobId) {
        return withConnection("ops_job_event 按作业查", c -> db.findAll(c, OpsJobEventRow.class,
                "`job_id` = ? ORDER BY `seq`", PbMysql.uint64(jobId)));
    }

    /** 这些源流水里已经被回收过的（{@code recall_source} 主键命中）。 */
    public Set<Long> recalledAmong(Collection<Long> txIds) {
        Set<Long> out = new HashSet<>();
        List<Object> batch = new ArrayList<>(IN_CHUNK);
        for (long txId : txIds) {
            batch.add(PbMysql.uint64(txId));
            if (batch.size() == IN_CHUNK) {
                out.addAll(recalledBatch(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            out.addAll(recalledBatch(batch));
        }
        return out;
    }

    private Set<Long> recalledBatch(List<Object> txIds) {
        List<RecallSourceRow> rows = withConnection("recall_source 查已回收",
                c -> db.findAllByKvIn(c, RecallSourceRow.class, "tx_id", List.copyOf(txIds)));
        Set<Long> out = new HashSet<>();
        for (RecallSourceRow row : rows) {
            out.add(row.getTxId());
        }
        return out;
    }
}
