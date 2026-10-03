package com.game.scene.audit;

import com.game.audit.proto.TransactionLogRecord;
import com.game.scene.metrics.SceneMetrics.AuditResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计兜底日志 {@value #LOGGER}：没能由 Kafka 确认的记录在这里留下完整一行（一行一条、键值对，可被日志采集回灌）。
 * 资产照改、流水不丢——流水号已发出的会带上，日后按号回灌不会重复。任意线程可调。
 */
final class AuditFallbackLog {

    static final String LOGGER = "xm.audit.fallback";

    private static final Logger log = LoggerFactory.getLogger(LOGGER);

    void transaction(TransactionLogRecord r, AuditResult result) {
        log.warn("transaction result={} tx_id={} time_ms={} reason={} kind={} from={} to={} currency_type={} delta={} "
                        + "before={} after={} item_uuid={} item_config_id={} item_quantity={} correlation_id={} zone={} extra={}",
                result.name().toLowerCase(java.util.Locale.ROOT), Long.toUnsignedString(r.getTxId()), r.getTimeMs(),
                r.getReasonValue(), r.getKindValue(), Long.toUnsignedString(r.getFromPlayer()),
                Long.toUnsignedString(r.getToPlayer()), r.getCurrencyType(), r.getCurrencyDelta(),
                Long.toUnsignedString(r.getBalanceBefore()), Long.toUnsignedString(r.getBalanceAfter()),
                Long.toUnsignedString(r.getItemUuid()), Integer.toUnsignedString(r.getItemConfigId()),
                Integer.toUnsignedString(r.getItemQuantity()), Long.toUnsignedString(r.getCorrelationId()),
                Integer.toUnsignedString(r.getZoneId()), r.getExtra());
    }
}
