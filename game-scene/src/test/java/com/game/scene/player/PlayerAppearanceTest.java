package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.AppearanceState;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

/** 角色形象试用位图、未知高位保留、幂等回执环与未知字段往返测试（支撑 PlayerStateSchemaLedgerTest ALLOW_ADDITIONS）。 */
class PlayerAppearanceTest {

    @Test
    void 紧凑位图低位在前_置位已知位时保留未知高位与未知字段() throws Exception {
        byte[] rawBits = new byte[] { 0x00, 0x00, 0x02 }; // bit 17 (未知高位)
        byte[] rawOwnedBits = new byte[] { 0x00, 0x00, 0x08 }; // bit 19 (未知高位)
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(777L).build())
                .build();
        AppearanceState persisted = AppearanceState.newBuilder()
                .setTrialUsedBits(ByteString.copyFrom(rawBits))
                .setOwnedBits(ByteString.copyFrom(rawOwnedBits))
                .setUnknownFields(unknown)
                .build();
        byte[] wire = persisted.toByteArray();

        PlayerAppearance restored = PlayerAppearance.restore(AppearanceState.parseFrom(wire));
        assertThat(restored.isTrialUsed(0)).isFalse();
        assertThat(restored.isTrialUsed(10)).isFalse();
        assertThat(restored.isOwned(1)).isFalse();

        restored.startTrial("01_ice_sword_girl", 0, 1_800_003_600L);
        restored.markTrialUsed(10);
        restored.markOwned(1);
        restored.markOwned(9);
        restored.rememberIdempotency(101L, PlayerAppearance.KIND_START_TRIAL, "01_ice_sword_girl");

        AppearanceState saved = restored.toState();
        byte[] savedBits = saved.getTrialUsedBits().toByteArray();
        assertThat(savedBits).hasSize(3);
        assertThat(savedBits[0] & 0xFF).isEqualTo(0x01); // bit 0
        assertThat(savedBits[1] & 0xFF).isEqualTo(0x04); // bit 10
        assertThat(savedBits[2] & 0xFF).isEqualTo(0x02); // bit 17 未知高位未丢
        byte[] savedOwnedBits = saved.getOwnedBits().toByteArray();
        assertThat(savedOwnedBits).hasSize(3);
        assertThat(savedOwnedBits[0] & 0xFF).isEqualTo(0x02); // bit 1
        assertThat(savedOwnedBits[1] & 0xFF).isEqualTo(0x02); // bit 9
        assertThat(savedOwnedBits[2] & 0xFF).isEqualTo(0x08); // bit 19 未知高位未丢
        assertThat(saved.getUnknownFields().hasField(99)).isTrue();
        assertThat(saved.getUnknownFields().getField(99).getVarintList()).containsExactly(777L);
    }

    @Test
    void 幂等回执按seq上限淘汰并区分重放与换参() {
        PlayerAppearance appearance = PlayerAppearance.empty();
        for (int i = 1; i <= PlayerAppearance.MAX_RECENT_REQUESTS + 5; i++) {
            appearance.rememberIdempotency(i, PlayerAppearance.KIND_SWITCH, "app_" + i);
        }
        assertThat(appearance.checkIdempotency(1L, PlayerAppearance.KIND_SWITCH, "app_1"))
                .isEqualTo(PlayerAppearance.IdempotencyVerdict.NEW);
        assertThat(appearance.checkIdempotency(PlayerAppearance.MAX_RECENT_REQUESTS + 5L,
                PlayerAppearance.KIND_SWITCH, "app_" + (PlayerAppearance.MAX_RECENT_REQUESTS + 5)))
                .isEqualTo(PlayerAppearance.IdempotencyVerdict.REPLAY);
        assertThat(appearance.checkIdempotency(PlayerAppearance.MAX_RECENT_REQUESTS + 5L,
                PlayerAppearance.KIND_SWITCH, "other"))
                .isEqualTo(PlayerAppearance.IdempotencyVerdict.MISMATCH);
    }
}
