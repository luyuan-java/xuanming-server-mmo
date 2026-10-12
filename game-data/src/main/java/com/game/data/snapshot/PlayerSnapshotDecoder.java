package com.game.data.snapshot;

import com.game.audit.proto.PlayerSnapshotRecord;
import com.game.audit.proto.SnapshotCause;
import com.game.data.consume.ConsumerLoop.Decoded;
import com.game.data.consume.ConsumerLoop.Decoder;
import com.game.data.metrics.DataMetrics.Outcome;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 玩家快照的解码：空载荷 / 解不出 → decode_error；快照号或玩家号为 0、触发原因未指定 → invalid（都跳过）。
 * 更新版本生产者发来的、本版本不认识的原因按数值原样落库。
 */
public final class PlayerSnapshotDecoder implements Decoder<PlayerSnapshotRow> {

    @Override
    public Decoded<PlayerSnapshotRow> decode(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null || record.value().length == 0) {
            return Decoded.skip(Outcome.DECODE_ERROR);
        }
        PlayerSnapshotRecord parsed;
        try {
            parsed = PlayerSnapshotRecord.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            return Decoded.skip(Outcome.DECODE_ERROR);
        }
        if (parsed.getSnapshotId() == 0 || parsed.getPlayerId() == 0
                || parsed.getCauseValue() == SnapshotCause.SNAPSHOT_CAUSE_UNSPECIFIED_VALUE) {
            return Decoded.skip(Outcome.INVALID);
        }
        return Decoded.ok(PlayerSnapshotRow.of(parsed));
    }
}
