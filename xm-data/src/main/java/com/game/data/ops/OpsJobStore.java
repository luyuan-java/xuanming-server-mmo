package com.game.data.ops;

import com.game.data.ops.pb.OpsActiveRow;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.ops.pb.RecallSourceRow;
import com.game.pbmysql.PbMysql;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    // ================================================================ 作业框架（批次 7.2b，data-ops-spec §7.4）

    /** 插入单飞槽（slot 恒 1）；已有作业在跑抛 {@link org.springframework.dao.DuplicateKeyException}。 */
    public void insertActive(OpsActiveRow row) {
        withConnection("ops_active 插入", c -> {
            db.insert(c, row);
            return null;
        });
    }

    /** 当前占着单飞槽的作业；空闲为空。 */
    public Optional<OpsActiveRow> active() {
        return withConnection("ops_active 查", c -> db.findOneByPk(c, OpsActiveRow.newBuilder().setSlot(SLOT).build()));
    }

    /**
     * 心跳：只在槽仍属于这个作业时更新。返回 false = 槽已被清扫器收走（执行线程必须自停）。
     *
     * <p>新值取 {@code GREATEST(heartbeat_ms + 1, now)}：心跳值严格递增，保证命中时一定「改到」行——连接串带 {@code useAffectedRows=true}
     * （§7.5），同一毫秒内的第二次心跳（例如受理与开始执行落在同一毫秒）写入相同的值会数出 0 行，被误判为槽已被收走。
     */
    public boolean heartbeat(long jobId, long nowMs) {
        return update("ops_active 心跳", "UPDATE `ops_active` SET `heartbeat_ms` = GREATEST(`heartbeat_ms` + 1, ?) "
                + "WHERE `slot` = ? AND `job_id` = ?", nowMs, SLOT, PbMysql.uint64(jobId)) == 1;
    }

    /** 作业终结时让出单飞槽（只删属于它的那一行）。 */
    public boolean deleteActive(long jobId) {
        return update("ops_active 删除", "DELETE FROM `ops_active` WHERE `slot` = ? AND `job_id` = ?", SLOT,
                PbMysql.uint64(jobId)) == 1;
    }

    /** 清扫：按心跳值 CAS 删除（期间心跳又更新过就不删）。 */
    public boolean sweepActive(long jobId, long heartbeatMs) {
        return update("ops_active 清扫", "DELETE FROM `ops_active` WHERE `slot` = ? AND `job_id` = ? AND `heartbeat_ms` = ?",
                SLOT, PbMysql.uint64(jobId), heartbeatMs) == 1;
    }

    /*
     * 作业行的状态迁移一律按列、带状态条件更新，不整行覆盖：执行线程手里的行是受理时的副本，而 cancel_requested 由受理线程（可能在别的副本上）
     * 改、INTERRUPTED 由清扫器改——整行覆盖会抹掉取消标志、把已中断的作业改回 RUNNING。状态列每次都变，useAffectedRows=true 下
     * 「命中」与「改到」一致。
     */

    /** QUEUED → RUNNING（开始执行）。返回是否改到（false = 作业已不是 QUEUED，例如已被清扫器改成 INTERRUPTED）。 */
    public boolean markRunning(long jobId, long startedMs, String runner) {
        return update("ops_job 开始执行", "UPDATE `ops_job` SET `status` = ?, `started_ms` = ?, `runner` = ? "
                        + "WHERE `job_id` = ? AND `status` = ?",
                OpsJobStatus.OPS_JOB_RUNNING_VALUE, startedMs, runner, PbMysql.uint64(jobId),
                OpsJobStatus.OPS_JOB_QUEUED_VALUE) == 1;
    }

    /**
     * 未终结（QUEUED / RUNNING）的作业写成终态：只写结局列（状态、结果码、人数、分歧、放行标志、结束时刻、摘要），不碰取消标志等别人改的列。
     * 返回是否改到（false = 作业已终结，例如已被清扫器改成 INTERRUPTED；或不存在）。
     */
    public boolean finishJob(OpsJobRow row) {
        return update("ops_job 终结", "UPDATE `ops_job` SET `status` = ?, `result_code` = ?, `players_planned` = ?, "
                        + "`players_affected` = ?, `players_failed` = ?, `divergence_rows` = ?, `unprovable_players` = ?, "
                        + "`accepted_divergence` = ?, `accepted_recall_reversal` = ?, `finished_ms` = ?, `summary_json` = ? "
                        + "WHERE `job_id` = ? AND `status` IN (?, ?)",
                row.getStatusValue(), row.getResultCode(), Integer.toUnsignedLong(row.getPlayersPlanned()),
                Integer.toUnsignedLong(row.getPlayersAffected()), Integer.toUnsignedLong(row.getPlayersFailed()),
                Integer.toUnsignedLong(row.getDivergenceRows()), Integer.toUnsignedLong(row.getUnprovablePlayers()),
                row.getAcceptedDivergence() ? 1 : 0, row.getAcceptedRecallReversal() ? 1 : 0, row.getFinishedMs(),
                row.getSummaryJson(), PbMysql.uint64(row.getJobId()), OpsJobStatus.OPS_JOB_QUEUED_VALUE,
                OpsJobStatus.OPS_JOB_RUNNING_VALUE) == 1;
    }

    /** 未终结（QUEUED / RUNNING）的作业改成 INTERRUPTED（清扫器用；执行线程心跳丢失时兜底）。返回是否改到。 */
    public boolean markInterrupted(long jobId, long nowMs, String summaryJson) {
        return update("ops_job 标记中断", "UPDATE `ops_job` SET `status` = ?, `result_code` = 'interrupted', `finished_ms` = ?, "
                        + "`summary_json` = ? WHERE `job_id` = ? AND `status` IN (?, ?)",
                OpsJobStatus.OPS_JOB_INTERRUPTED_VALUE, nowMs, summaryJson, PbMysql.uint64(jobId),
                OpsJobStatus.OPS_JOB_QUEUED_VALUE, OpsJobStatus.OPS_JOB_RUNNING_VALUE) == 1;
    }

    /**
     * 请求取消未终结的作业（幂等）。返回 true = 作业未终结且取消标志已置上（含此前已请求过）；false = 已终结 / 不存在。
     * 连接串带 {@code useAffectedRows=true}：标志本来就是 1 时 UPDATE 数出 0 行，所以 0 行时回读一次按状态判，而不是按影响行数判。
     */
    public boolean requestCancel(long jobId) {
        if (update("ops_job 请求取消", "UPDATE `ops_job` SET `cancel_requested` = 1 WHERE `job_id` = ? AND `status` IN (?, ?)",
                PbMysql.uint64(jobId), OpsJobStatus.OPS_JOB_QUEUED_VALUE, OpsJobStatus.OPS_JOB_RUNNING_VALUE) == 1) {
            return true;
        }
        return findJob(jobId).filter(j -> j.getCancelRequested() && (j.getStatus() == OpsJobStatus.OPS_JOB_QUEUED
                || j.getStatus() == OpsJobStatus.OPS_JOB_RUNNING)).isPresent();
    }

    /** 作业列表（新的在前），可按状态 / 种类过滤。 */
    public List<OpsJobRow> listJobs(OpsJobStatus status, OpsJobKind kind, int limit) {
        StringBuilder where = new StringBuilder("1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND `status` = ?");
            args.add(status.getNumber());
        }
        if (kind != null) {
            where.append(" AND `kind` = ?");
            args.add(kind.getNumber());
        }
        where.append(" ORDER BY `created_ms` DESC, `job_id` DESC LIMIT ").append(limit);
        return withConnection("ops_job 列表", c -> db.findAll(c, OpsJobRow.class, where.toString(), args.toArray()));
    }

    /** 这个作业最大的事件序号（没有为 0）。 */
    public int maxEventSeq(long jobId) {
        return withConnection("ops_job_event 最大序号", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT MAX(`seq`) FROM `ops_job_event` WHERE `job_id` = ?")) {
                ps.setObject(1, PbMysql.uint64(jobId));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** 插入一条明细（计划时 PLANNED，或计划阶段就定了结局的行）。 */
    public void insertPlayer(OpsJobPlayerRow row) {
        withConnection("ops_job_player 插入", c -> {
            db.insert(c, row);
            return null;
        });
    }

    /**
     * 明细从 PLANNED 改成终态（条件更新：必须恰好改到 1 行，写档事务据此判定「这个玩家没被别的执行写过」）。
     * 在写玩家数据的同一事务里调用（同一条连接）。
     */
    public boolean finishPlayer(long jobId, long playerId, String outcome, long claimedEpoch, long preSnapshotId,
                                String detailJson, long writtenMs) {
        return update("ops_job_player 终结", "UPDATE `ops_job_player` SET `outcome` = ?, `claimed_epoch` = ?, "
                        + "`pre_snapshot_id` = ?, `detail_json` = ?, `written_ms` = ? "
                        + "WHERE `job_id` = ? AND `player_id` = ? AND `outcome` = ?",
                outcome, PbMysql.uint64(claimedEpoch), PbMysql.uint64(preSnapshotId), detailJson, writtenMs,
                PbMysql.uint64(jobId), PbMysql.uint64(playerId), PLANNED) == 1;
    }

    /** 某作业的明细，按玩家号升序、从 {@code afterPlayer}（不含）之后取 {@code limit} 条。 */
    public List<OpsJobPlayerRow> players(long jobId, long afterPlayer, int limit) {
        return withConnection("ops_job_player 列表", c -> db.findAll(c, OpsJobPlayerRow.class,
                "`job_id` = ? AND `player_id` > ? ORDER BY `player_id` LIMIT " + limit, PbMysql.uint64(jobId),
                PbMysql.uint64(afterPlayer)));
    }

    /** 计划中的明细（{@code outcome = PLANNED}）。 */
    public static final String PLANNED = "PLANNED";
    /** 单飞槽号（恒 1）。 */
    public static final int SLOT = 1;

    private int update(String task, String sql, Object... args) {
        return withConnection(task, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                return ps.executeUpdate();
            }
        });
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
