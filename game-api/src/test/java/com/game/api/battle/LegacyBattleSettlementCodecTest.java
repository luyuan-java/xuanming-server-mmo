package com.game.api.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.BattleSettlementData;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class LegacyBattleSettlementCodecTest {
    @Test
    void 旧信封固定字节读写一致_不是裸结算字节() throws Exception {
        var settlement = BattleSettlementData.newBuilder().setBattleId(7).setPlayerId(9).setGoldGain(100).build();
        byte[] original = hex("0a06080710094064");
        assertThat(LegacyBattleSettlementCodec.encode(settlement)).containsExactly(original);
        assertThat(LegacyBattleSettlementCodec.decode(original)).contains(settlement);
        assertThat(LegacyBattleSettlementCodec.decode(ByteString.copyFrom(original))).contains(settlement);
        assertThat(LegacyBattleSettlementCodec.encode(settlement)).isNotEqualTo(settlement.toByteArray());
    }

    @Test
    void 空信封与显式空结算保持存在性差异() throws Exception {
        assertThat(LegacyBattleSettlementCodec.decode(new byte[0])).isEmpty();
        assertThat(LegacyBattleSettlementCodec.decode(hex("1007"))).isEmpty();
        assertThat(LegacyBattleSettlementCodec.decode(hex("0a00"))).contains(BattleSettlementData.getDefaultInstance());
        assertThat(LegacyBattleSettlementCodec.encode(BattleSettlementData.getDefaultInstance())).containsExactly(hex("0a00"));
    }

    @Test
    void 重复结算message按protobuf合并_相同标量取最后值() throws Exception {
        var decoded = LegacyBattleSettlementCodec.decode(hex("0a0208070a04100940050a024006")).orElseThrow();
        assertThat(decoded.getBattleId()).isEqualTo(7);
        assertThat(decoded.getPlayerId()).isEqualTo(9);
        assertThat(decoded.getGoldGain()).isEqualTo(6);
    }

    @Test
    void 未知信封字段可忽略_嵌套未知字段与uint64高位保留() throws Exception {
        var unknown = UnknownFieldSet.newBuilder().addField(100,
                UnknownFieldSet.Field.newBuilder().addVarint(77).build()).build();
        var settlement = BattleSettlementData.newBuilder().setBattleId(-1L).setPlayerId(Long.MIN_VALUE)
                .setGoldGain(-1L).setUnknownFields(unknown).build();
        byte[] original = LegacyBattleSettlementCodec.encode(settlement);
        byte[] extended = java.util.Arrays.copyOf(original, original.length + 2);
        extended[original.length] = 16;
        extended[original.length + 1] = 1;
        assertThat(LegacyBattleSettlementCodec.decode(extended)).contains(settlement);
        assertThat(LegacyBattleSettlementCodec.encode(LegacyBattleSettlementCodec.decode(extended).orElseThrow()))
                .containsExactly(original);
    }

    @Test
    void 截断长度_截断嵌套varint_非法tag均拒绝() {
        for (String malformed : new String[]{"0a", "0a0208", "0a0108", "00", "0c"}) {
            assertThatThrownBy(() -> LegacyBattleSettlementCodec.decode(hex(malformed)))
                    .isInstanceOf(InvalidProtocolBufferException.class);
        }
    }

    private static byte[] hex(String text) { return HexFormat.of().parseHex(text); }
}
