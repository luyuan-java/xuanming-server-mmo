package com.game.data.txlog;

import com.game.audit.proto.AssetKind;
import com.game.audit.proto.TransactionLogRecord;
import com.game.data.consume.ConsumerLoop.Decoded;
import com.game.data.consume.ConsumerLoop.Decoder;
import com.game.data.metrics.DataMetrics.Outcome;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 资产流水的解码：空载荷 / 解不出 → decode_error；流水号为 0、种类未指定 → invalid（都跳过）。
 * 更新版本生产者发来的、本版本不认识的原因 / 种类按数值原样落库，不拒绝。
 */
public final class TransactionLogDecoder implements Decoder<TransactionLogRow> {

    @Override
    public Decoded<TransactionLogRow> decode(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null || record.value().length == 0) {
            return Decoded.skip(Outcome.DECODE_ERROR);
        }
        TransactionLogRecord parsed;
        try {
            parsed = TransactionLogRecord.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            return Decoded.skip(Outcome.DECODE_ERROR);
        }
        if (parsed.getTxId() == 0 || parsed.getKindValue() == AssetKind.ASSET_KIND_UNSPECIFIED_VALUE) {
            return Decoded.skip(Outcome.INVALID);
        }
        return Decoded.ok(TransactionLogRow.of(parsed));
    }
}
