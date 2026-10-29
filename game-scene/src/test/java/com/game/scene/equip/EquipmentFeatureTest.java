package com.game.scene.equip;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.common.RunMode;
import com.game.proto.*;
import com.game.scene.bag.BagFeature;
import com.game.scene.player.BagType;
import com.game.scene.testing.Contracts;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.WorldTestAccess;
import com.google.protobuf.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class EquipmentFeatureTest {
    private ClientRequestHandler handler(EquipmentFixture f, RunMode mode) {
        var bag = new BagFeature(f.bags);
        return new ClientRequestHandler(f.world.world, Contracts.REGISTRY, Contracts.IDS, mode,
                List.of(bag, new EquipmentFeature(f.equipment, bag, f.attributes, Contracts.REGISTRY)));
    }
    private List<MessageContent> call(EquipmentFixture f, ClientRequestHandler handler, String method, Message body) {
        f.world.sink.clear();
        handler.onClientForward(f.player.session().linkId(), ClientForward.newBuilder()
                .setSessionId(f.player.session().sessionId()).setPlayerId(f.player.playerId()).setRequestId(91)
                .setMessageId(Contracts.REGISTRY.requireId("SceneBagClientPlayer", method)).setBody(body.toByteString()).build());
        return f.world.sink.to(f.player.session().linkId(), f.player.session().sessionId());
    }
    @Test void 穿脱完整分发_先推面板后回整包_应答保留请求号() throws Exception {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); var handler = handler(f, RunMode.DEV);
        var frames = call(f, handler, "EquipItem", EquipItemRequest.newBuilder().setItemId(id).build());
        assertThat(frames).extracting(MessageContent::getMessageId).containsExactly(170, Contracts.REGISTRY.requireId("SceneBagClientPlayer", "EquipItem"));
        assertThat(frames).extracting(MessageContent::getId).containsExactly(0L, 91L);
        var equip = EquipItemResponse.parseFrom(frames.getLast().getSerializedMessage());
        assertThat(equip.getErrorMessage().getId()).isZero(); assertThat(equip.hasBag()).isTrue(); assertThat(equip.hasEquipment()).isTrue();
        assertThat(equip.getEquipment().getItemsList()).singleElement().satisfies(item -> {
            assertThat(item.getItemId()).isEqualTo(id); assertThat(item.getName()).isEqualTo("测试剑");
            assertThat(item.getBaseAttrs(0).getValue()).isEqualTo(40); assertThat(item.getAffixes(0).getValue()).isEqualTo(100);
            assertThat(item.hasEnhanceNext()).isTrue(); assertThat(item.getGemSocketsCount()).isEqualTo(1);
        });
        assertThat(equip.getEquipment().getLayout().getEquipSlotsCount()).isEqualTo(2);
        frames = call(f, handler, "UnequipItem", UnequipItemRequest.newBuilder().setItemId(id).build());
        assertThat(frames).hasSize(2);
        var unequip = UnequipItemResponse.parseFrom(frames.getLast().getSerializedMessage());
        assertThat(unequip.getErrorMessage().getId()).isZero(); assertThat(unequip.getEquipment().getItemsList()).isEmpty();
        assertThat(unequip.getBag().getItems(0).getItemId()).isEqualTo(id);
    }
    @Test void 概率失败仍是成功业务应答_背包里的装备强化不推面板() throws Exception {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.fund(); var handler = handler(f, RunMode.TEST);
        var frames = call(f, handler, "EnhanceEquip", EnhanceEquipRequest.newBuilder().setItemId(id).build());
        assertThat(frames).hasSize(1);
        var enhanced = EnhanceEquipResponse.parseFrom(frames.getFirst().getSerializedMessage());
        assertThat(enhanced.getErrorMessage().getId()).isZero(); assertThat(enhanced.getSuccess()).isTrue();
        assertThat(enhanced.getEnhanceLevel()).isEqualTo(1); assertThat(enhanced.hasBag()).isTrue(); assertThat(enhanced.hasEquipment()).isTrue();
        f.randomValue = 9999;
        frames = call(f, handler, "EnhanceEquip", EnhanceEquipRequest.newBuilder().setItemId(id).build());
        assertThat(frames).hasSize(1);
        var failed = EnhanceEquipResponse.parseFrom(frames.getFirst().getSerializedMessage());
        assertThat(failed.getErrorMessage().getId()).isZero(); assertThat(failed.getSuccess()).isFalse();
        assertThat(failed.getEnhanceLevel()).isZero(); assertThat(failed.getBag().hasCurrency()).isTrue();
    }
    @Test void 镶嵌和取石均回两包_失败不附成功快照() throws Exception {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.grant(11, 1); var handler = handler(f, RunMode.DEV);
        var frames = call(f, handler, "InlayGem", InlayGemRequest.newBuilder().setItemId(id).setSocket(0).setGemConfigId(11).build());
        var inlay = InlayGemResponse.parseFrom(frames.getLast().getSerializedMessage());
        assertThat(inlay.getErrorMessage().getId()).isZero(); assertThat(inlay.hasBag()).isTrue(); assertThat(inlay.hasEquipment()).isTrue();
        frames = call(f, handler, "RemoveGem", RemoveGemRequest.newBuilder().setItemId(id).setSocket(0).build());
        var removed = RemoveGemResponse.parseFrom(frames.getLast().getSerializedMessage());
        assertThat(removed.getErrorMessage().getId()).isZero(); assertThat(removed.hasBag()).isTrue(); assertThat(removed.hasEquipment()).isTrue();
        frames = call(f, handler, "RemoveGem", RemoveGemRequest.newBuilder().setItemId(id).setSocket(0).build());
        removed = RemoveGemResponse.parseFrom(frames.getLast().getSerializedMessage());
        assertThat(removed.getErrorMessage().getId()).isEqualTo(28019); assertThat(removed.hasBag()).isFalse(); assertThat(removed.hasEquipment()).isFalse();
    }
    @Test void GM生产拒绝_开发发放上限99_冻结用装备业务码() throws Exception {
        var f = new EquipmentFixture();
        var request = GmGrantItemRequest.newBuilder().setConfigId(1).setCount(1).build();
        var frames = call(f, handler(f, RunMode.PROD), "GmGrantItem", request);
        assertThat(GmGrantItemResponse.parseFrom(frames.getLast().getSerializedMessage()).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(f.player.bags().bag(BagType.INVENTORY).items()).isEmpty();
        frames = call(f, handler(f, RunMode.DEV), "GmGrantItem", request);
        assertThat(GmGrantItemResponse.parseFrom(frames.getLast().getSerializedMessage()).getBag().getItemsCount()).isEqualTo(1);
        frames = call(f, handler(f, RunMode.DEV), "GmGrantItem", request.toBuilder().setCount(100).build());
        assertThat(GmGrantItemResponse.parseFrom(frames.getLast().getSerializedMessage()).getErrorMessage().getId()).isEqualTo(28009);
        WorldTestAccess.startFreezing(f.player);
        frames = call(f, handler(f, RunMode.DEV), "EquipItem", EquipItemRequest.newBuilder().setItemId(1).build());
        assertThat(EquipItemResponse.parseFrom(frames.getLast().getSerializedMessage()).getErrorMessage().getId()).isEqualTo(28008);
        assertThat(frames).hasSize(1);
    }
}
