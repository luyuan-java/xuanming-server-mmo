package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.game.match.proto.BattlePlacement;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleRouting;
import com.game.proto.RemoveObserverResponse;
import com.google.protobuf.UnknownFieldSet;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** 原件是恢复凭据；标记往返不能丢字段，也不能把另一操作的取消回执当作本次证明。 */
class LocalObserverOperationTest {
    private static final String OPERATION = "acafe001-1234-4567-8901-123456789abc";
    private static final long NOW = 1_790_000_000_000L;

    @Test
    void 创建原件固定完整Add与原端点_标记可无损恢复() {
        var original = original();
        assertThat(original.add().getSpectatorOperationId()).isEqualTo(OPERATION);
        assertThat(original.add().getAdmitBeforeMs()).isEqualTo(NOW + 25_000);
        assertThat(original.add().getTargetInstanceId()).isEqualTo("battle-original");
        assertThat(original.add().getObserverName()).isEqualTo("观战玩家");
        assertThat(original.add().getRouting()).isEqualTo(routing());
        assertThat(LocalObserverOperation.decode(original.mark())).contains(original);
        var changedPlacement = original.placement().toBuilder().setRpcHost("replacement.test")
                .setBattleInstanceId("replacement-instance").build();
        assertThat(changedPlacement).isNotEqualTo(original.placement());
        assertThat(LocalObserverOperation.decode(original.mark()).orElseThrow().placement())
                .isEqualTo(placement());
    }

    @Test
    void 取消只使用原操作的角色战斗截止和实例() {
        var original = original();
        var cancel = original.remove("rewatch");
        assertThat(cancel.getBattleId()).isEqualTo(original.battleId());
        assertThat(cancel.getObserverPlayerId()).isEqualTo(original.playerId());
        assertThat(cancel.getSpectatorOperationId()).isEqualTo(OPERATION);
        assertThat(cancel.getAdmitBeforeMs()).isEqualTo(original.add().getAdmitBeforeMs());
        assertThat(cancel.getTargetInstanceId()).isEqualTo("battle-original");
        assertThat(cancel.getReason()).isEqualTo("rewatch");
        assertThat(original.remove("enter_gather").toBuilder().setReason("rewatch").build()).isEqualTo(cancel);
    }

    @Test
    void 取消正证明必须同时绑定操作与实例() {
        var original = original();
        var proof = RemoveObserverResponse.newBuilder().setCancelled(true)
                .setSpectatorOperationId(OPERATION).setTargetInstanceId("battle-original").build();
        assertThat(original.confirms(proof)).isTrue();
        assertThat(original.confirms(null)).isFalse();
        assertThat(original.confirms(RemoveObserverResponse.getDefaultInstance())).isFalse();
        assertThat(original.confirms(proof.toBuilder().setCancelled(false).build())).isFalse();
        assertThat(original.confirms(proof.toBuilder().setSpectatorOperationId("acafe002-1234-4567-8901-123456789abc").build())).isFalse();
        assertThat(original.confirms(proof.toBuilder().setTargetInstanceId("replacement-instance").build())).isFalse();
    }

    @Test
    void 标记保留两份protobuf的未知字段() {
        var unknown = UnknownFieldSet.newBuilder().addField(65_000,
                UnknownFieldSet.Field.newBuilder().addVarint(987654321L).build()).build();
        var original = new LocalObserverOperation(placement().toBuilder().setUnknownFields(unknown).build(),
                original().add().toBuilder().setUnknownFields(unknown).build());
        var restored = LocalObserverOperation.decode(original.mark()).orElseThrow();
        assertThat(restored).isEqualTo(original);
        assertThat(restored.placement().getUnknownFields()).isEqualTo(unknown);
        assertThat(restored.add().getUnknownFields()).isEqualTo(unknown);
    }

    @Test
    void 空值旧格式损坏编码和未知版本不能恢复成有效原件() {
        assertThat(LocalObserverOperation.decode(null)).isEmpty();
        assertThat(LocalObserverOperation.decode("")).isEmpty();
        assertThat(LocalObserverOperation.decode("601:old-nonce")).isEmpty();
        assertThat(LocalObserverOperation.decode("observer-v1:%%%:%%%")).isEmpty();
        assertThat(LocalObserverOperation.decode("observer-v1::")).isEmpty();
        assertThat(LocalObserverOperation.decode(original().mark() + ":extra")).isEmpty();
        assertThat(LocalObserverOperation.decode(original().mark().replace("observer-v1:", "observer-v2:"))).isEmpty();
    }

    @Test
    void 能解析但未经规范编码的标记也拒绝() {
        var original = original();
        // protobuf 允许无填充 Base64 解码；持久标记必须保留唯一规范串，避免同原件存在多份 CAS 值。
        AddObserverRequest paddedAdd = original.add();
        while (paddedAdd.getSerializedSize() % 3 == 0) {
            paddedAdd = paddedAdd.toBuilder().setObserverName(paddedAdd.getObserverName() + "a").build();
        }
        var padded = new LocalObserverOperation(original.placement(), paddedAdd).mark();
        assertThat(padded).endsWith("=");
        assertThat(LocalObserverOperation.decode(padded)).isPresent();
        assertThat(LocalObserverOperation.decode(padded.replace("=", ""))).isEmpty();
    }

    @Test
    void 损坏protobuf与超过上限的原件均拒绝() {
        String invalidProto = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff});
        String placementBytes = Base64.getEncoder().encodeToString(placement().toByteArray());
        assertThat(LocalObserverOperation.decode("observer-v1:" + placementBytes + ":" + invalidProto)).isEmpty();
        assertThat(LocalObserverOperation.decode("observer-v1:" + "x".repeat(65_536))).isEmpty();
        var huge = new LocalObserverOperation(placement(), original().add().toBuilder()
                .setObserverName("x".repeat(65_536)).build());
        assertThatIllegalArgumentException().isThrownBy(huge::mark);
    }

    @Test
    void 操作号拒绝零号简写和大小写变体() {
        for (String invalid : new String[] {"", "00000000-0000-0000-0000-000000000000",
                "1-1-1-1-1", OPERATION.toUpperCase(java.util.Locale.ROOT)}) {
            assertThatIllegalArgumentException().as(invalid).isThrownBy(() -> new LocalObserverOperation(placement(),
                    original().add().toBuilder().setSpectatorOperationId(invalid).build()));
        }
    }

    @Test
    void 端点和原件身份不一致不能生成恢复标记() {
        var add = original().add();
        for (var invalid : new BattlePlacement[] {
                placement().toBuilder().setBattleId(602).build(),
                placement().toBuilder().setBattleNodeId(0).build(),
                placement().toBuilder().clearRpcHost().build(),
                placement().toBuilder().setRpcPort(0).build(),
                placement().toBuilder().setRpcPort(65_536).build(),
                placement().toBuilder().setBattleInstanceId("different-instance").build()}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new LocalObserverOperation(invalid, add));
        }
        for (var invalid : new AddObserverRequest[] {
                add.toBuilder().setObserverPlayerId(0).build(),
                add.toBuilder().setAdmitBeforeMs(0).build(),
                add.toBuilder().clearRouting().build(),
                add.toBuilder().setRouting(routing().toBuilder().setGateNodeId(0)).build(),
                add.toBuilder().setRouting(routing().toBuilder().setSessionId(0)).build(),
                add.toBuilder().setRouting(routing().toBuilder().clearGateInstanceId()).build(),
                add.toBuilder().setRouting(routing().toBuilder().setSceneNodeId(1)).build()}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new LocalObserverOperation(placement(), invalid));
        }
    }

    @Test
    void 已过截止的原件仍可恢复用于清退_不会重置准入时限() {
        var old = new LocalObserverOperation(placement(), original().add().toBuilder().setAdmitBeforeMs(1).build());
        var restored = LocalObserverOperation.decode(old.mark()).orElseThrow();
        assertThat(restored.add().getAdmitBeforeMs()).isEqualTo(1);
        assertThat(restored.remove("recover").getAdmitBeforeMs()).isEqualTo(1);
    }

    @Test
    void 创建截止拒绝非法时钟与加法溢出() {
        for (long invalid : new long[] {0, -1, Long.MAX_VALUE - 24_999}) {
            assertThatIllegalArgumentException().isThrownBy(() -> LocalObserverOperation.create(
                    placement(), 701, routing(), "观战玩家", OPERATION, invalid));
        }
        assertThat(LocalObserverOperation.create(placement(), 701, routing(), "观战玩家", OPERATION,
                Long.MAX_VALUE - 25_000).add().getAdmitBeforeMs()).isEqualTo(Long.MAX_VALUE);
    }

    private static LocalObserverOperation original() {
        return LocalObserverOperation.create(placement(), 701, routing(), "观战玩家", OPERATION, NOW);
    }

    private static BattlePlacement placement() {
        return BattlePlacement.newBuilder().setBattleId(601).setBattleNodeId(7)
                .setRpcHost("original.test").setRpcPort(21200).setBattleInstanceId("battle-original")
                .setAttempt(2).setCreatedAtMs(NOW - 1_000).build();
    }

    private static BattleRouting routing() {
        return BattleRouting.newBuilder().setGateNodeId(3).setGateInstanceId("gate-original")
                .setSessionId(41).setZoneId(1).build();
    }
}
