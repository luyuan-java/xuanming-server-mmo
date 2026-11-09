package com.game.api.battle;

import com.game.proto.BattleSettlementData;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.Objects;
import java.util.Optional;

/**
 * Java 旧 Redis 结算通道的字节兼容边界，不是当前客户端或 Kafka 契约。
 *
 * <p>上游已删除 BattleSettlementEvent；删除前仅含 message 字段 settlement = 1。
 * Java 兼容通道仍需读写这一层信封，不能直接替换成 BattleSettlementData 的字节。
 * 使用 protobuf 的动态消息保留原解析语义（字段存在性、重复 message 合并、未知字段与坏包校验），
 * 不恢复已退休的共享 proto 或事件注册。
 */
public final class LegacyBattleSettlementCodec {
    private static final Descriptors.Descriptor ENVELOPE = descriptor();
    private static final Descriptors.FieldDescriptor SETTLEMENT = ENVELOPE.findFieldByNumber(1);

    private LegacyBattleSettlementCodec() { }

    /** 与旧生成类 setSettlement(...).build().toByteArray() 相同，包括显式空 message 的 0a00。 */
    public static byte[] encode(BattleSettlementData settlement) {
        return DynamicMessage.newBuilder(ENVELOPE).setField(SETTLEMENT, Objects.requireNonNull(settlement))
                .build().toByteArray();
    }

    public static Optional<BattleSettlementData> decode(byte[] bytes) throws InvalidProtocolBufferException {
        return settlement(DynamicMessage.parseFrom(ENVELOPE, bytes));
    }

    public static Optional<BattleSettlementData> decode(ByteString bytes) throws InvalidProtocolBufferException {
        return settlement(DynamicMessage.parseFrom(ENVELOPE, bytes));
    }

    private static Optional<BattleSettlementData> settlement(DynamicMessage envelope) throws InvalidProtocolBufferException {
        if (!envelope.hasField(SETTLEMENT)) return Optional.empty();
        Message value = (Message) envelope.getField(SETTLEMENT);
        return Optional.of(BattleSettlementData.parseFrom(value.toByteString()));
    }

    private static Descriptors.Descriptor descriptor() {
        Descriptors.Descriptor payload = BattleSettlementData.getDescriptor();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("game/api/compat/legacy_battle_settlement_envelope.proto")
                .setPackage("game.api.compat").setSyntax("proto3")
                .addDependency(payload.getFile().getName())
                .addMessageType(DescriptorProto.newBuilder().setName("LegacyBattleSettlementEnvelope")
                        .addField(FieldDescriptorProto.newBuilder().setName("settlement").setNumber(1)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName("." + payload.getFullName())))
                .build();
        try {
            return Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[]{payload.getFile()})
                    .findMessageTypeByName("LegacyBattleSettlementEnvelope");
        } catch (Descriptors.DescriptorValidationException invalid) {
            throw new ExceptionInInitializerError(invalid);
        }
    }
}
