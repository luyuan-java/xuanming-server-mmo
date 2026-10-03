package com.game.data.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.proto.PlayerSnapshotRecord;
import com.game.audit.proto.SnapshotCause;
import com.game.data.consume.ConsumerLoop.Decoded;
import com.game.data.metrics.DataMetrics.Outcome;
import com.google.protobuf.ByteString;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class PlayerSnapshotDecoderTest {

    private final PlayerSnapshotDecoder decoder = new PlayerSnapshotDecoder();

    private static PlayerSnapshotRecord.Builder valid() {
        return PlayerSnapshotRecord.newBuilder().setSnapshotId(9).setPlayerId(1001).setTimeMs(123)
                .setCause(SnapshotCause.SNAPSHOT_LOGOUT).setZoneId(1).setOwnerEpoch(4).setLevel(12).setSceneConfigId(3)
                .setPosX(1.5).setPosY(2).setPosZ(-3).setPlayerState(ByteString.copyFrom(new byte[] {8, 1}));
    }

    private Decoded<PlayerSnapshotRow> decode(byte[] value) {
        return decoder.decode(new ConsumerRecord<>("t", 0, 0, "1001", value));
    }

    @Test
    void 合法记录逐字段转成行_玩法数据原样() {
        PlayerSnapshotRow row = decode(valid().build().toByteArray()).row();

        assertThat(row).usingRecursiveComparison().ignoringFields("playerState")
                .isEqualTo(new PlayerSnapshotRow(9, 1001, 123, 2, 1, 4, 12, 3, 1.5, 2, -3, null));
        assertThat(row.playerState()).containsExactly(8, 1);
        assertThat(row.toString()).contains("snapshotId=9", "playerStateBytes=2").doesNotContain("[B@");
    }

    @Test
    void 空载荷与解不出算decode_error_缺号或原因未指定算invalid_未知原因原样保留() {
        assertThat(decode(null).skipped()).isEqualTo(Outcome.DECODE_ERROR);
        assertThat(decode(new byte[0]).skipped()).isEqualTo(Outcome.DECODE_ERROR);
        assertThat(decode(new byte[] {(byte) 0xff, (byte) 0xff}).skipped()).isEqualTo(Outcome.DECODE_ERROR);
        assertThat(decode(valid().setSnapshotId(0).build().toByteArray()).skipped()).isEqualTo(Outcome.INVALID);
        assertThat(decode(valid().setPlayerId(0).build().toByteArray()).skipped()).isEqualTo(Outcome.INVALID);
        assertThat(decode(valid().setCause(SnapshotCause.SNAPSHOT_CAUSE_UNSPECIFIED).build().toByteArray()).skipped())
                .isEqualTo(Outcome.INVALID);
        assertThat(decode(valid().setCauseValue(99).build().toByteArray()).row().cause()).isEqualTo(99);
    }
}
