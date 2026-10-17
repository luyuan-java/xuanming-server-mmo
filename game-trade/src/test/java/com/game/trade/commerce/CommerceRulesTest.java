package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.*;

import com.game.proto.*;
import com.game.proto.trade.*;
import com.google.protobuf.ByteString;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommerceRulesTest {
    @Test
    void 混合材料合计4096可托管_4097在副作用前拒绝() {
        var policy = new CommerceRules.Policy(Set.of(10, 11), Set.of(), Set.of(), false, List.of());
        assertThatCode(
            () -> policy.selection(TradeAssetSelection.newBuilder().putItems(10, 2048).putItems(11, 2048).build()))
            .doesNotThrowAnyException();
        assertThatThrownBy(
            () -> policy.selection(TradeAssetSelection.newBuilder().putItems(10, 2048).putItems(11, 2049).build()))
            .isInstanceOf(CommerceRules.Refusal.class);
    }
    @Test
    void 托管转换credit清掉debit选择字段_原实例未知字段完整保留_逐个核对身份() {
        var item = AssetItemInstance.newBuilder()
                       .setItemUuid(77)
                       .setConfigId(10)
                       .setStackSize(1)
                       .setInstanceProto(ByteString.copyFrom(new byte[] {8, 1, (byte) 248, 127, 3}))
                       .build();
        var pet = AssetPetInstance.newBuilder()
                      .setPetId(88)
                      .setInstanceProto(ByteString.copyFrom(new byte[] {8, 2, (byte) 248, 127, 4}))
                      .build();
        var leg = TradeCommerceLeg.newBuilder()
                      .setBundle(AssetBundle.newBuilder().addItemUuids(77).setPetId(88))
                      .setSnapshot(AssetSnapshot.newBuilder().addItems(item).setPet(pet))
                      .build();
        var credit = CommerceRules.transfer(leg);
        assertThat(credit.getItemUuidsList()).isEmpty();
        assertThat(credit.getPetId()).isZero();
        assertThat(credit.getItemInstancesList()).containsExactly(item);
        assertThat(credit.getPetInstance()).isEqualTo(pet);
        assertThat(leg.getBundle().getItemUuidsList()).containsExactly(77L);
        assertThatThrownBy(
            ()
                -> CommerceRules.transfer(leg.toBuilder()
                        .setSnapshot(leg.getSnapshot().toBuilder().setItems(0, item.toBuilder().setItemUuid(78)))
                        .build()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()
                               -> CommerceRules.transfer(leg.toBuilder()
                                       .setSnapshot(leg.getSnapshot().toBuilder().setPet(pet.toBuilder().setPetId(89)))
                                       .build()))
            .isInstanceOf(IllegalStateException.class);
    }
}
