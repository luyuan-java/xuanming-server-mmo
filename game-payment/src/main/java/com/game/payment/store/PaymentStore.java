package com.game.payment.store;

import com.game.payment.store.PaymentRecords.CursorClaim;
import com.game.payment.store.PaymentRecords.InsertOutcome;
import com.game.payment.store.PaymentRecords.IntentRecord;
import com.game.payment.store.PaymentRecords.MallSync;
import com.game.payment.store.PaymentRecords.RecordMinorPaymentOutcome;
import com.game.payment.store.PaymentRecords.RefundProgress;
import com.game.payment.store.PaymentRecords.ReserveOutcome;
import com.game.payment.store.PaymentRecords.SpendLimitWindow;
import com.game.payment.store.PaymentRecords.TxnRecord;
import java.util.List;

/**
 * 支付服务权威 SQL 存储接口（单条条件更新 + 未成年人额度行锁事务）。
 */
public interface PaymentStore {

    // ---- payment_intent ----

    IntentRecord findIntent(long orderHi, long orderLo);

    InsertOutcome<IntentRecord> insertIntent(IntentRecord rec);

    ReserveOutcome reserveIntent(IntentRecord rec, SpendLimitWindow limit);

    void setChannelRef(long orderHi, long orderLo, String ref, long refMs, String payerId, long nowMs);

    boolean claimActiveQuery(IntentRecord rec, long nowMs);

    List<IntentRecord> dueIntents(long nowMs, int limit);

    boolean claimCheck(IntentRecord rec, long leaseMs, long nowMs);

    void scheduleCheck(IntentRecord rec, long leaseMs, long nextMs, long nowMs);

    boolean closeIntent(long orderHi, long orderLo, long nowMs);

    void markIntentPaid(long orderHi, long orderLo, long nowMs);

    void setIntentState(long orderHi, long orderLo, int state, long nowMs);

    // ---- payment_txn & minor spend ----

    TxnRecord findTxn(int channel, long txnHi, long txnLo);

    InsertOutcome<TxnRecord> insertTxn(TxnRecord rec);

    RecordMinorPaymentOutcome recordMinorPaymentTxn(TxnRecord rec, IntentRecord intent, SpendLimitWindow limit,
                                                    long paidMs, long nowMs, String overLimitReason);

    boolean reviveMinorIntent(IntentRecord intent, SpendLimitWindow limit, long paidMs, long nowMs, boolean force);

    boolean flipFact(TxnRecord rec, long observedMs, long nowMs);

    void touchFactObserved(TxnRecord rec, long observedMs, long nowMs);

    long oldestMallPendingMs();

    long oldestFinalizePendingMs();

    List<TxnRecord> dueMallTxns(long nowMs, int limit);

    boolean claimMall(TxnRecord rec, long leaseMs, long nowMs);

    void settleMall(TxnRecord rec, long leaseMs, MallSync sync, long nowMs);

    void deferMall(TxnRecord rec, long leaseMs, long nextMs, String lastError, long nowMs);

    boolean claimRefund(TxnRecord rec, String outRefundNo, long leaseMs, long nowMs);

    void saveRefund(TxnRecord rec, long leaseMs, RefundProgress progress, long nowMs);

    List<TxnRecord> dueFinalize(long nowMs, int limit);

    boolean claimFinalize(TxnRecord rec, long leaseMs, long nowMs);

    void saveFinalize(TxnRecord rec, long leaseMs, int state, long nextMs, long nowMs);

    // ---- payment_channel_cursor ----

    CursorClaim claimCursor(int channel, long owner, long initialMs, long leaseMs, long nowMs);

    void advanceCursor(int channel, long owner, long cursorMs, long nowMs);
}
