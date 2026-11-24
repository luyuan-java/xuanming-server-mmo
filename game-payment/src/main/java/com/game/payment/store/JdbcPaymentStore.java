package com.game.payment.store;

import com.game.payment.store.PaymentRecords.CursorClaim;
import com.game.payment.store.PaymentRecords.InsertOutcome;
import com.game.payment.store.PaymentRecords.IntentRecord;
import com.game.payment.store.PaymentRecords.MallSync;
import com.game.payment.store.PaymentRecords.RecordMinorPaymentOutcome;
import com.game.payment.store.PaymentRecords.RefundProgress;
import com.game.payment.store.PaymentRecords.ReserveOutcome;
import com.game.payment.store.PaymentRecords.SpendLimitWindow;
import com.game.payment.store.PaymentRecords.SpendRejection;
import com.game.payment.store.PaymentRecords.TxnRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 基于标准 JDBC 的支付存储实现（兼容 MySQL 8 / TiDB / H2 MySQL 模式）。
 *
 * <p>普通推进均为单语句自动提交条件 {@code UPDATE}（自然键幂等 + 租约 CAS）；
 * 未成年人额度预占、迟到复核与原子落账运行在 {@code READ COMMITTED} 事务下，严格按
 * {@code payment_spend} → {@code payment_intent FOR UPDATE} → {@code payment_txn FOR UPDATE} 锁序执行。
 */
public final class JdbcPaymentStore implements PaymentStore {

    private static final String INTENT_COLUMNS =
            "order_hi,order_lo,order_id,player_id,channel,amount_minor,currency,state,expire_ms,"
                    + "next_check_ms,check_count,checked_ms,created_ms,updated_ms,channel_ref,channel_ref_ms,"
                    + "sku_id,payer_id,spender_hi,spender_lo,paid_ms,minor_age";

    private static final String INSERT_INTENT_SQL = "INSERT INTO payment_intent (" + INTENT_COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String TXN_COLUMNS =
            "channel,txn_hi,txn_lo,transaction_id,order_hi,order_lo,out_trade_no,player_id,"
                    + "paid_amount_minor,paid_currency,paid_ms,mall_state,mall_refund_reason,mall_attempts,"
                    + "next_attempt_ms,mall_last_error,refund_state,out_refund_no,refund_id,refund_ms,"
                    + "refund_attempts,refund_next_ms,refund_last_error,created_ms,updated_ms,fact_revision,"
                    + "fact_observed_ms,mall_revision,mall_due,channel_token,channel_order_id,"
                    + "store_amount_minor,store_currency,finalize_state,finalize_next_ms,product_id";

    private static final String INSERT_TXN_SQL = "INSERT INTO payment_txn (" + TXN_COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final DataSource dataSource;

    public JdbcPaymentStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 启动核对 4 张权威表均已存在（不自动建表，由运维/迁移脚本显式安装）。 */
    public void verifySchema() {
        String[] tables = {"payment_intent", "payment_spend", "payment_txn", "payment_channel_cursor"};
        try (Connection c = dataSource.getConnection()) {
            for (String t : tables) {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + t + " WHERE 1 = 0");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("payment schema verification failed: ensure db/payment-authority.sql is installed", e);
        }
    }

    @Override
    public IntentRecord findIntent(long orderHi, long orderLo) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + INTENT_COLUMNS + " FROM payment_intent WHERE order_hi=? AND order_lo=?")) {
            ps.setLong(1, orderHi);
            ps.setLong(2, orderLo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readIntent(rs) : null;
            }
        } catch (SQLException e) {
            throw new PaymentStorageException("findIntent failed", e);
        }
    }

    @Override
    public InsertOutcome<IntentRecord> insertIntent(IntentRecord rec) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_INTENT_SQL)) {
            bindIntent(ps, rec);
            ps.executeUpdate();
            return new InsertOutcome<>(rec, true);
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                IntentRecord existing = findIntent(rec.orderHi(), rec.orderLo());
                if (existing == null) {
                    throw new PaymentStorageException("duplicate intent disappeared on reread", e);
                }
                return new InsertOutcome<>(existing, false);
            }
            throw new PaymentStorageException("insertIntent failed", e);
        }
    }

    @Override
    public ReserveOutcome reserveIntent(IntentRecord rec, SpendLimitWindow limit) {
        try (Connection c = openSpendTx()) {
            try {
                lockSpender(c, rec.spenderHi(), rec.spenderLo(), rec.createdMs());
                SpendRejection rej = checkSpend(c, rec.spenderHi(), rec.spenderLo(),
                        rec.orderHi(), rec.orderLo(), rec.amountMinor(), limit);
                if (rej != SpendRejection.NONE) {
                    c.rollback();
                    return new ReserveOutcome(null, false, rej);
                }
                try (PreparedStatement ps = c.prepareStatement(INSERT_INTENT_SQL)) {
                    bindIntent(ps, rec);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (isDuplicateKey(e)) {
                        c.rollback();
                        IntentRecord existing = findIntent(rec.orderHi(), rec.orderLo());
                        if (existing == null) {
                            throw new PaymentStorageException("duplicate reserved intent disappeared", e);
                        }
                        return new ReserveOutcome(existing, false, SpendRejection.NONE);
                    }
                    throw e;
                }
                c.commit();
                return new ReserveOutcome(rec, true, SpendRejection.NONE);
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        } catch (SQLException e) {
            throw new PaymentStorageException("reserveIntent failed", e);
        }
    }

    @Override
    public void setChannelRef(long orderHi, long orderLo, String ref, long refMs, String payerId, long nowMs) {
        execUpdate("UPDATE payment_intent SET channel_ref=?,channel_ref_ms=?,payer_id=?,updated_ms=? "
                + "WHERE order_hi=? AND order_lo=? AND state=?", ps -> {
            ps.setString(1, nullToEmpty(ref));
            ps.setLong(2, refMs);
            ps.setString(3, nullToEmpty(payerId));
            ps.setLong(4, nowMs);
            ps.setLong(5, orderHi);
            ps.setLong(6, orderLo);
            ps.setInt(7, PaymentRecords.INTENT_CREATED);
        });
    }

    @Override
    public boolean claimActiveQuery(IntentRecord rec, long nowMs) {
        return execUpdate("UPDATE payment_intent SET checked_ms=?,updated_ms=? "
                + "WHERE order_hi=? AND order_lo=? AND state=? AND checked_ms=?", ps -> {
            ps.setLong(1, nowMs);
            ps.setLong(2, nowMs);
            ps.setLong(3, rec.orderHi());
            ps.setLong(4, rec.orderLo());
            ps.setInt(5, PaymentRecords.INTENT_CREATED);
            ps.setLong(6, rec.checkedMs());
        }) == 1;
    }

    @Override
    public List<IntentRecord> dueIntents(long nowMs, int limit) {
        String sql = "SELECT " + INTENT_COLUMNS + " FROM payment_intent WHERE state=? AND next_check_ms<=? "
                + "ORDER BY next_check_ms LIMIT ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, PaymentRecords.INTENT_CREATED);
            ps.setLong(2, nowMs);
            ps.setInt(3, limit);
            List<IntentRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readIntent(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PaymentStorageException("dueIntents failed", e);
        }
    }

    @Override
    public boolean claimCheck(IntentRecord rec, long leaseMs, long nowMs) {
        return execUpdate("UPDATE payment_intent SET next_check_ms=?,check_count=check_count+1,checked_ms=?,updated_ms=? "
                + "WHERE order_hi=? AND order_lo=? AND state=? AND next_check_ms=?", ps -> {
            ps.setLong(1, leaseMs);
            ps.setLong(2, nowMs);
            ps.setLong(3, nowMs);
            ps.setLong(4, rec.orderHi());
            ps.setLong(5, rec.orderLo());
            ps.setInt(6, PaymentRecords.INTENT_CREATED);
            ps.setLong(7, rec.nextCheckMs());
        }) == 1;
    }

    @Override
    public void scheduleCheck(IntentRecord rec, long leaseMs, long nextMs, long nowMs) {
        execUpdate("UPDATE payment_intent SET next_check_ms=?,updated_ms=? "
                + "WHERE order_hi=? AND order_lo=? AND state=? AND next_check_ms=?", ps -> {
            ps.setLong(1, nextMs);
            ps.setLong(2, nowMs);
            ps.setLong(3, rec.orderHi());
            ps.setLong(4, rec.orderLo());
            ps.setInt(5, PaymentRecords.INTENT_CREATED);
            ps.setLong(6, leaseMs);
        });
    }

    @Override
    public boolean closeIntent(long orderHi, long orderLo, long nowMs) {
        return execUpdate("UPDATE payment_intent SET state=?,channel_ref='',updated_ms=? "
                + "WHERE order_hi=? AND order_lo=? AND state=?", ps -> {
            ps.setInt(1, PaymentRecords.INTENT_CLOSED);
            ps.setLong(2, nowMs);
            ps.setLong(3, orderHi);
            ps.setLong(4, orderLo);
            ps.setInt(5, PaymentRecords.INTENT_CREATED);
        }) == 1;
    }

    @Override
    public void markIntentPaid(long orderHi, long orderLo, long nowMs) {
        execUpdate("UPDATE payment_intent SET state=?,paid_ms=CASE WHEN ?>created_ms THEN ? ELSE created_ms END,"
                + "channel_ref='',updated_ms=? WHERE order_hi=? AND order_lo=? AND state<>?", ps -> {
            ps.setInt(1, PaymentRecords.INTENT_PAID);
            ps.setLong(2, nowMs);
            ps.setLong(3, nowMs);
            ps.setLong(4, nowMs);
            ps.setLong(5, orderHi);
            ps.setLong(6, orderLo);
            ps.setInt(7, PaymentRecords.INTENT_PAID);
        });
    }

    @Override
    public void setIntentState(long orderHi, long orderLo, int state, long nowMs) {
        execUpdate("UPDATE payment_intent SET paid_ms=CASE WHEN ?=? THEN (CASE WHEN ?>created_ms THEN ? ELSE created_ms END) ELSE paid_ms END,"
                + "state=?,channel_ref='',updated_ms=? WHERE order_hi=? AND order_lo=?", ps -> {
            ps.setInt(1, state);
            ps.setInt(2, PaymentRecords.INTENT_PAID);
            ps.setLong(3, nowMs);
            ps.setLong(4, nowMs);
            ps.setInt(5, state);
            ps.setLong(6, nowMs);
            ps.setLong(7, orderHi);
            ps.setLong(8, orderLo);
        });
    }

    @Override
    public TxnRecord findTxn(int channel, long txnHi, long txnLo) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + TXN_COLUMNS + " FROM payment_txn WHERE channel=? AND txn_hi=? AND txn_lo=?")) {
            ps.setInt(1, channel);
            ps.setLong(2, txnHi);
            ps.setLong(3, txnLo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readTxn(rs) : null;
            }
        } catch (SQLException e) {
            throw new PaymentStorageException("findTxn failed", e);
        }
    }

    @Override
    public InsertOutcome<TxnRecord> insertTxn(TxnRecord rec) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_TXN_SQL)) {
            bindTxn(ps, rec);
            ps.executeUpdate();
            return new InsertOutcome<>(rec, true);
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                TxnRecord existing = findTxn(rec.channel(), rec.txnHi(), rec.txnLo());
                if (existing == null) {
                    throw new PaymentStorageException("duplicate txn disappeared on reread", e);
                }
                return new InsertOutcome<>(existing, false);
            }
            throw new PaymentStorageException("insertTxn failed", e);
        }
    }

    @Override
    public RecordMinorPaymentOutcome recordMinorPaymentTxn(TxnRecord rec, IntentRecord intent, SpendLimitWindow limit,
                                                           long paidMs, long nowMs, String overLimitReason) {
        try (Connection c = openSpendTx()) {
            try {
                lockSpender(c, intent.spenderHi(), intent.spenderLo(), nowMs);
                int state;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT state FROM payment_intent WHERE order_hi=? AND order_lo=? FOR UPDATE")) {
                    ps.setLong(1, intent.orderHi());
                    ps.setLong(2, intent.orderLo());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            throw new PaymentStorageException("minor intent missing during recordMinorPaymentTxn", null);
                        }
                        state = rs.getInt(1);
                    }
                }
                TxnRecord existing = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT " + TXN_COLUMNS + " FROM payment_txn WHERE channel=? AND txn_hi=? AND txn_lo=? FOR UPDATE")) {
                    ps.setInt(1, rec.channel());
                    ps.setLong(2, rec.txnHi());
                    ps.setLong(3, rec.txnLo());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existing = readTxn(rs);
                        }
                    }
                }
                boolean wasClosed = state == PaymentRecords.INTENT_CLOSED;
                if (existing != null) {
                    c.rollback();
                    return new RecordMinorPaymentOutcome(existing, false, wasClosed, false);
                }
                TxnRecord toInsert = rec;
                boolean overLimit = false;
                if (state == PaymentRecords.INTENT_CREATED) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE payment_intent SET state=?,paid_ms=?,channel_ref='',updated_ms=? "
                                    + "WHERE order_hi=? AND order_lo=? AND state=?")) {
                        ps.setInt(1, PaymentRecords.INTENT_PAID);
                        ps.setLong(2, paidMs);
                        ps.setLong(3, nowMs);
                        ps.setLong(4, intent.orderHi());
                        ps.setLong(5, intent.orderLo());
                        ps.setInt(6, PaymentRecords.INTENT_CREATED);
                        ps.executeUpdate();
                    }
                } else if (state == PaymentRecords.INTENT_CLOSED) {
                    SpendRejection rej = checkSpend(c, intent.spenderHi(), intent.spenderLo(),
                            intent.orderHi(), intent.orderLo(), intent.amountMinor(), limit);
                    if (rej != SpendRejection.NONE) {
                        overLimit = true;
                        toInsert = rec.withOrphan(overLimitReason);
                    } else {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE payment_intent SET state=?,paid_ms=?,channel_ref='',updated_ms=? "
                                        + "WHERE order_hi=? AND order_lo=? AND state=?")) {
                            ps.setInt(1, PaymentRecords.INTENT_PAID);
                            ps.setLong(2, paidMs);
                            ps.setLong(3, nowMs);
                            ps.setLong(4, intent.orderHi());
                            ps.setLong(5, intent.orderLo());
                            ps.setInt(6, PaymentRecords.INTENT_CLOSED);
                            ps.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(INSERT_TXN_SQL)) {
                    bindTxn(ps, toInsert);
                    ps.executeUpdate();
                }
                c.commit();
                return new RecordMinorPaymentOutcome(toInsert, true, wasClosed, overLimit);
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        } catch (SQLException e) {
            throw new PaymentStorageException("recordMinorPaymentTxn failed", e);
        }
    }

    @Override
    public boolean reviveMinorIntent(IntentRecord intent, SpendLimitWindow limit, long paidMs, long nowMs, boolean force) {
        try (Connection c = openSpendTx()) {
            try {
                lockSpender(c, intent.spenderHi(), intent.spenderLo(), nowMs);
                int state;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT state FROM payment_intent WHERE order_hi=? AND order_lo=? FOR UPDATE")) {
                    ps.setLong(1, intent.orderHi());
                    ps.setLong(2, intent.orderLo());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return false;
                        }
                        state = rs.getInt(1);
                    }
                }
                if (state != PaymentRecords.INTENT_CLOSED) {
                    c.rollback();
                    return true;
                }
                boolean within = checkSpend(c, intent.spenderHi(), intent.spenderLo(),
                        intent.orderHi(), intent.orderLo(), intent.amountMinor(), limit) == SpendRejection.NONE;
                if (!within && !force) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE payment_intent SET state=?,paid_ms=?,channel_ref='',updated_ms=? "
                                + "WHERE order_hi=? AND order_lo=? AND state=?")) {
                    ps.setInt(1, PaymentRecords.INTENT_PAID);
                    ps.setLong(2, paidMs);
                    ps.setLong(3, nowMs);
                    ps.setLong(4, intent.orderHi());
                    ps.setLong(5, intent.orderLo());
                    ps.setInt(6, PaymentRecords.INTENT_CLOSED);
                    ps.executeUpdate();
                }
                c.commit();
                return within;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        } catch (SQLException e) {
            throw new PaymentStorageException("reviveMinorIntent failed", e);
        }
    }

    @Override
    public boolean flipFact(TxnRecord rec, long observedMs, long nowMs) {
        return execUpdate("UPDATE payment_txn SET "
                + "next_attempt_ms=CASE WHEN mall_due=1 THEN next_attempt_ms ELSE ? END,"
                + "mall_due=CASE WHEN mall_state IN (?,?,?) THEN 1 ELSE mall_due END,"
                + "fact_revision=fact_revision+1,fact_observed_ms=?,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND fact_revision=?", ps -> {
            ps.setLong(1, nowMs);
            ps.setInt(2, PaymentRecords.MALL_PENDING);
            ps.setInt(3, PaymentRecords.MALL_BOUND);
            ps.setInt(4, PaymentRecords.MALL_REFUND_QUEUED);
            ps.setLong(5, observedMs);
            ps.setLong(6, nowMs);
            ps.setInt(7, rec.channel());
            ps.setLong(8, rec.txnHi());
            ps.setLong(9, rec.txnLo());
            ps.setInt(10, rec.factRevision());
        }) == 1;
    }

    @Override
    public void touchFactObserved(TxnRecord rec, long observedMs, long nowMs) {
        execUpdate("UPDATE payment_txn SET fact_observed_ms=?,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND fact_revision=? AND fact_observed_ms<?", ps -> {
            ps.setLong(1, observedMs);
            ps.setLong(2, nowMs);
            ps.setInt(3, rec.channel());
            ps.setLong(4, rec.txnHi());
            ps.setLong(5, rec.txnLo());
            ps.setInt(6, rec.factRevision());
            ps.setLong(7, observedMs);
        });
    }

    @Override
    public long oldestMallPendingMs() {
        return queryLong("SELECT COALESCE(MIN(created_ms), 0) FROM payment_txn WHERE mall_due=1", ps -> {});
    }

    @Override
    public long oldestFinalizePendingMs() {
        return queryLong("SELECT COALESCE(MIN(created_ms), 0) FROM payment_txn WHERE finalize_state=?",
                ps -> ps.setInt(1, PaymentRecords.FINALIZE_PENDING));
    }

    @Override
    public List<TxnRecord> dueMallTxns(long nowMs, int limit) {
        return queryTxns("SELECT " + TXN_COLUMNS + " FROM payment_txn WHERE mall_due=1 AND next_attempt_ms<=? "
                + "ORDER BY next_attempt_ms LIMIT ?", ps -> {
            ps.setLong(1, nowMs);
            ps.setInt(2, limit);
        });
    }

    @Override
    public boolean claimMall(TxnRecord rec, long leaseMs, long nowMs) {
        return execUpdate("UPDATE payment_txn SET next_attempt_ms=?,mall_attempts=mall_attempts+1,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND mall_due=1 AND next_attempt_ms=?", ps -> {
            ps.setLong(1, leaseMs);
            ps.setLong(2, nowMs);
            ps.setInt(3, rec.channel());
            ps.setLong(4, rec.txnHi());
            ps.setLong(5, rec.txnLo());
            ps.setLong(6, rec.nextAttemptMs());
        }) == 1;
    }

    @Override
    public void settleMall(TxnRecord rec, long leaseMs, MallSync s, long nowMs) {
        execUpdate("UPDATE payment_txn SET "
                + "mall_due=CASE WHEN ?=1 AND fact_revision>? THEN 1 ELSE 0 END,"
                + "mall_state=?,mall_refund_reason=?,mall_revision=?,mall_last_error=?,next_attempt_ms=?,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND mall_due=1 AND next_attempt_ms=?", ps -> {
            ps.setInt(1, s.keepSyncing() ? 1 : 0);
            ps.setInt(2, s.revision());
            ps.setInt(3, s.state());
            ps.setString(4, nullToEmpty(s.refundReason()));
            ps.setInt(5, s.revision());
            ps.setString(6, nullToEmpty(s.lastError()));
            ps.setLong(7, nowMs);
            ps.setLong(8, nowMs);
            ps.setInt(9, rec.channel());
            ps.setLong(10, rec.txnHi());
            ps.setLong(11, rec.txnLo());
            ps.setLong(12, leaseMs);
        });
    }

    @Override
    public void deferMall(TxnRecord rec, long leaseMs, long nextMs, String lastError, long nowMs) {
        execUpdate("UPDATE payment_txn SET next_attempt_ms=?,mall_last_error=?,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND mall_due=1 AND next_attempt_ms=?", ps -> {
            ps.setLong(1, nextMs);
            ps.setString(2, nullToEmpty(lastError));
            ps.setLong(3, nowMs);
            ps.setInt(4, rec.channel());
            ps.setLong(5, rec.txnHi());
            ps.setLong(6, rec.txnLo());
            ps.setLong(7, leaseMs);
        });
    }

    @Override
    public boolean claimRefund(TxnRecord rec, String outRefundNo, long leaseMs, long nowMs) {
        return execUpdate("UPDATE payment_txn SET "
                + "refund_state=CASE WHEN refund_state=? THEN ? ELSE refund_state END,"
                + "out_refund_no=CASE WHEN out_refund_no='' THEN ? ELSE out_refund_no END,"
                + "refund_next_ms=?,refund_attempts=refund_attempts+1,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND refund_state IN (?,?,?,?,?) AND refund_next_ms=?", ps -> {
            ps.setInt(1, PaymentRecords.REFUND_NONE);
            ps.setInt(2, PaymentRecords.REFUND_REQUESTED);
            ps.setString(3, nullToEmpty(outRefundNo));
            ps.setLong(4, leaseMs);
            ps.setLong(5, nowMs);
            ps.setInt(6, rec.channel());
            ps.setLong(7, rec.txnHi());
            ps.setLong(8, rec.txnLo());
            ps.setInt(9, PaymentRecords.REFUND_NONE);
            ps.setInt(10, PaymentRecords.REFUND_REQUESTED);
            ps.setInt(11, PaymentRecords.REFUND_SUCCEEDED);
            ps.setInt(12, PaymentRecords.REFUND_REPORTED);
            ps.setInt(13, PaymentRecords.REFUND_FAILED);
            ps.setLong(14, rec.refundNextMs());
        }) == 1;
    }

    @Override
    public void saveRefund(TxnRecord rec, long leaseMs, RefundProgress p, long nowMs) {
        execUpdate("UPDATE payment_txn SET refund_state=?,out_refund_no=?,refund_id=?,refund_ms=?,"
                + "refund_next_ms=?,refund_last_error=?,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND refund_next_ms=?", ps -> {
            ps.setInt(1, p.state());
            ps.setString(2, nullToEmpty(p.outRefundNo()));
            ps.setString(3, nullToEmpty(p.refundId()));
            ps.setLong(4, p.refundMs());
            ps.setLong(5, p.nextMs());
            ps.setString(6, nullToEmpty(p.lastError()));
            ps.setLong(7, nowMs);
            ps.setInt(8, rec.channel());
            ps.setLong(9, rec.txnHi());
            ps.setLong(10, rec.txnLo());
            ps.setLong(11, leaseMs);
        });
    }

    @Override
    public List<TxnRecord> dueFinalize(long nowMs, int limit) {
        return queryTxns("SELECT " + TXN_COLUMNS + " FROM payment_txn WHERE finalize_state=? AND finalize_next_ms<=? "
                + "ORDER BY finalize_next_ms LIMIT ?", ps -> {
            ps.setInt(1, PaymentRecords.FINALIZE_PENDING);
            ps.setLong(2, nowMs);
            ps.setInt(3, limit);
        });
    }

    @Override
    public boolean claimFinalize(TxnRecord rec, long leaseMs, long nowMs) {
        return execUpdate("UPDATE payment_txn SET finalize_next_ms=?,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND finalize_state=? AND finalize_next_ms=?", ps -> {
            ps.setLong(1, leaseMs);
            ps.setLong(2, nowMs);
            ps.setInt(3, rec.channel());
            ps.setLong(4, rec.txnHi());
            ps.setLong(5, rec.txnLo());
            ps.setInt(6, PaymentRecords.FINALIZE_PENDING);
            ps.setLong(7, rec.finalizeNextMs());
        }) == 1;
    }

    @Override
    public void saveFinalize(TxnRecord rec, long leaseMs, int state, long nextMs, long nowMs) {
        execUpdate("UPDATE payment_txn SET finalize_state=?,finalize_next_ms=?,updated_ms=? "
                + "WHERE channel=? AND txn_hi=? AND txn_lo=? AND finalize_next_ms=?", ps -> {
            ps.setInt(1, state);
            ps.setLong(2, nextMs);
            ps.setLong(3, nowMs);
            ps.setInt(4, rec.channel());
            ps.setLong(5, rec.txnHi());
            ps.setLong(6, rec.txnLo());
            ps.setLong(7, leaseMs);
        });
    }

    @Override
    public CursorClaim claimCursor(int channel, long owner, long initialMs, long leaseMs, long nowMs) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO payment_channel_cursor (channel,cursor_ms,lease_until_ms,lease_owner,updated_ms) VALUES (?,?,0,0,?)")) {
                ps.setInt(1, channel);
                ps.setLong(2, initialMs);
                ps.setLong(3, nowMs);
                ps.executeUpdate();
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
            }
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE payment_channel_cursor SET lease_until_ms=?,lease_owner=?,updated_ms=? "
                            + "WHERE channel=? AND lease_until_ms<?")) {
                ps.setLong(1, leaseMs);
                ps.setLong(2, owner);
                ps.setLong(3, nowMs);
                ps.setInt(4, channel);
                ps.setLong(5, nowMs);
                updated = ps.executeUpdate();
            }
            if (updated != 1) {
                return new CursorClaim(0L, false);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cursor_ms FROM payment_channel_cursor WHERE channel=? AND lease_owner=?")) {
                ps.setInt(1, channel);
                ps.setLong(2, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new CursorClaim(0L, false);
                    }
                    return new CursorClaim(rs.getLong(1), true);
                }
            }
        } catch (SQLException e) {
            throw new PaymentStorageException("claimCursor failed", e);
        }
    }

    @Override
    public void advanceCursor(int channel, long owner, long cursorMs, long nowMs) {
        execUpdate("UPDATE payment_channel_cursor SET cursor_ms=CASE WHEN cursor_ms<? THEN ? ELSE cursor_ms END,"
                + "lease_until_ms=0,updated_ms=? WHERE channel=? AND lease_owner=?", ps -> {
            ps.setLong(1, cursorMs);
            ps.setLong(2, cursorMs);
            ps.setLong(3, nowMs);
            ps.setInt(4, channel);
            ps.setLong(5, owner);
        });
    }

    // ---- 事务与行锁辅助 ----

    private Connection openSpendTx() throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            return c;
        } catch (SQLException | RuntimeException failure) {
            try {
                c.close();
            } catch (SQLException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    private static void lockSpender(Connection c, long spenderHi, long spenderLo, long nowMs) throws SQLException {
        // 兼容 MySQL/TiDB 与 H2 MySQL 模式：先尝试 UPDATE 拿行锁，未命中则 INSERT（并发撞键则回查 FOR UPDATE）。
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payment_spend SET updated_ms=? WHERE spender_hi=? AND spender_lo=?")) {
            ps.setLong(1, nowMs);
            ps.setLong(2, spenderHi);
            ps.setLong(3, spenderLo);
            if (ps.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment_spend (spender_hi,spender_lo,updated_ms) VALUES (?,?,?)")) {
            ps.setLong(1, spenderHi);
            ps.setLong(2, spenderLo);
            ps.setLong(3, nowMs);
            ps.executeUpdate();
            return;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payment_spend SET updated_ms=? WHERE spender_hi=? AND spender_lo=?")) {
            ps.setLong(1, nowMs);
            ps.setLong(2, spenderHi);
            ps.setLong(3, spenderLo);
            ps.executeUpdate();
        }
    }

    private static SpendRejection checkSpend(Connection c, long spenderHi, long spenderLo,
                                             long excludeOrderHi, long excludeOrderLo,
                                             long amountMinor, SpendLimitWindow limit) throws SQLException {
        long inflight = 0L;
        long month = 0L;
        long day = 0L;
        String sql = "SELECT state,amount_minor,paid_ms FROM payment_intent WHERE spender_hi=? AND spender_lo=? "
                + "AND (state=? OR (state=? AND paid_ms>=?)) AND NOT (order_hi=? AND order_lo=?) FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, spenderHi);
            ps.setLong(2, spenderLo);
            ps.setInt(3, PaymentRecords.INTENT_CREATED);
            ps.setInt(4, PaymentRecords.INTENT_PAID);
            ps.setLong(5, limit.monthStartMs());
            ps.setLong(6, excludeOrderHi);
            ps.setLong(7, excludeOrderLo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int state = rs.getInt(1);
                    long amt = rs.getLong(2);
                    long paidMs = rs.getLong(3);
                    if (state == PaymentRecords.INTENT_CREATED) {
                        inflight = saturatingAdd(inflight, amt);
                    } else if (state == PaymentRecords.INTENT_PAID && paidMs >= limit.monthStartMs()) {
                        month = saturatingAdd(month, amt);
                        if (paidMs >= limit.dayStartMs()) {
                            day = saturatingAdd(day, amt);
                        }
                    }
                }
            }
        }
        if (saturatingAdd(amountMinor, saturatingAdd(inflight, month)) > limit.monthlyFen()) {
            return SpendRejection.MONTHLY_LIMIT;
        }
        if (limit.dailyFen() > 0 && saturatingAdd(amountMinor, saturatingAdd(inflight, day)) > limit.dailyFen()) {
            return SpendRejection.DAILY_LIMIT;
        }
        return SpendRejection.NONE;
    }

    private static long saturatingAdd(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // ignore
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        SQLException cur = e;
        while (cur != null) {
            int code = cur.getErrorCode();
            String state = cur.getSQLState();
            if (code == 1062 || code == 23505 || "23000".equals(state) || "23505".equals(state)) {
                return true;
            }
            cur = cur.getNextException();
        }
        return false;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private int execUpdate(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PaymentStorageException("sql update failed", e);
        }
    }

    private long queryLong(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new PaymentStorageException("sql queryLong failed", e);
        }
    }

    private List<TxnRecord> queryTxns(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            List<TxnRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readTxn(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PaymentStorageException("sql queryTxns failed", e);
        }
    }

    private static void bindIntent(PreparedStatement ps, IntentRecord r) throws SQLException {
        ps.setLong(1, r.orderHi());
        ps.setLong(2, r.orderLo());
        ps.setString(3, nullToEmpty(r.orderId()));
        ps.setLong(4, r.playerId());
        ps.setInt(5, r.channel());
        ps.setLong(6, r.amountMinor());
        ps.setString(7, nullToEmpty(r.currency()));
        ps.setInt(8, r.state());
        ps.setLong(9, r.expireMs());
        ps.setLong(10, r.nextCheckMs());
        ps.setInt(11, r.checkCount());
        ps.setLong(12, r.checkedMs());
        ps.setLong(13, r.createdMs());
        ps.setLong(14, r.updatedMs());
        ps.setString(15, nullToEmpty(r.channelRef()));
        ps.setLong(16, r.channelRefMs());
        ps.setLong(17, r.skuId());
        ps.setString(18, nullToEmpty(r.payerId()));
        ps.setLong(19, r.spenderHi());
        ps.setLong(20, r.spenderLo());
        ps.setLong(21, r.paidMs());
        ps.setInt(22, r.minorAge());
    }

    private static IntentRecord readIntent(ResultSet rs) throws SQLException {
        return new IntentRecord(
                rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getInt(5),
                rs.getLong(6), rs.getString(7), rs.getInt(8), rs.getLong(9), rs.getLong(10),
                rs.getInt(11), rs.getLong(12), rs.getLong(13), rs.getLong(14), rs.getString(15),
                rs.getLong(16), rs.getLong(17), rs.getString(18), rs.getLong(19), rs.getLong(20),
                rs.getLong(21), rs.getInt(22));
    }

    private static void bindTxn(PreparedStatement ps, TxnRecord r) throws SQLException {
        ps.setInt(1, r.channel());
        ps.setLong(2, r.txnHi());
        ps.setLong(3, r.txnLo());
        ps.setString(4, nullToEmpty(r.transactionId()));
        ps.setLong(5, r.orderHi());
        ps.setLong(6, r.orderLo());
        ps.setString(7, nullToEmpty(r.outTradeNo()));
        ps.setLong(8, r.playerId());
        ps.setLong(9, r.paidAmountMinor());
        ps.setString(10, nullToEmpty(r.paidCurrency()));
        ps.setLong(11, r.paidMs());
        ps.setInt(12, r.mallState());
        ps.setString(13, nullToEmpty(r.mallRefundReason()));
        ps.setInt(14, r.mallAttempts());
        ps.setLong(15, r.nextAttemptMs());
        ps.setString(16, nullToEmpty(r.mallLastError()));
        ps.setInt(17, r.refundState());
        ps.setString(18, nullToEmpty(r.outRefundNo()));
        ps.setString(19, nullToEmpty(r.refundId()));
        ps.setLong(20, r.refundMs());
        ps.setInt(21, r.refundAttempts());
        ps.setLong(22, r.refundNextMs());
        ps.setString(23, nullToEmpty(r.refundLastError()));
        ps.setLong(24, r.createdMs());
        ps.setLong(25, r.updatedMs());
        ps.setInt(26, r.factRevision());
        ps.setLong(27, r.factObservedMs());
        ps.setInt(28, r.mallRevision());
        ps.setInt(29, r.mallDue());
        ps.setString(30, nullToEmpty(r.channelToken()));
        ps.setString(31, nullToEmpty(r.channelOrderId()));
        ps.setLong(32, r.storeAmountMinor());
        ps.setString(33, nullToEmpty(r.storeCurrency()));
        ps.setInt(34, r.finalizeState());
        ps.setLong(35, r.finalizeNextMs());
        ps.setString(36, nullToEmpty(r.productId()));
    }

    private static TxnRecord readTxn(ResultSet rs) throws SQLException {
        return new TxnRecord(
                rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                rs.getLong(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getString(10),
                rs.getLong(11), rs.getInt(12), rs.getString(13), rs.getInt(14), rs.getLong(15),
                rs.getString(16), rs.getInt(17), rs.getString(18), rs.getString(19), rs.getLong(20),
                rs.getInt(21), rs.getLong(22), rs.getString(23), rs.getLong(24), rs.getLong(25),
                rs.getInt(26), rs.getLong(27), rs.getInt(28), rs.getInt(29), rs.getString(30),
                rs.getString(31), rs.getLong(32), rs.getString(33), rs.getInt(34), rs.getLong(35),
                rs.getString(36));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 支付存储异常（包装底层 JDBC 错误，调用方按 1003 故障或重试处理）。 */
    public static final class PaymentStorageException extends RuntimeException {
        public PaymentStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
