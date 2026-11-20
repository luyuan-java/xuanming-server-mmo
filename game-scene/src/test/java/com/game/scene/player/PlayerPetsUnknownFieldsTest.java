package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 持久字段台账放行 game.storage.PetEntry 新增字段的证据：restore → 修改 → toState 保留实例的未知字段与条目字段。 */
class PlayerPetsUnknownFieldsTest {
    @Test
    void 宝宝实例未知字段与条目回执经恢复修改存盘后原样保留() {
        UnknownFieldSet extra = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build();
        PetEntry entry = PetEntry.newBuilder().setPetId(11).setPetTableId(1).setLevel(3)
                .putEntryAllocated(2, 5).setEntryStateVersion(4).setEntryLastRequestId("r1")
                .setEntryLastBaseVersion(3).setEntryLastConfigVersion(9).putEntryLastTarget(2, 5)
                .setCultivationTier(2).setUnknownFields(extra).build();

        PlayerPets pets = PlayerPets.restore(PetState.newBuilder().addPets(entry).build());
        pets.pets().getFirst().rename("小白");
        PetEntry saved = pets.toState().getPets(0);

        assertThat(saved.getUnknownFields()).isEqualTo(extra);
        assertThat(saved.getName()).isEqualTo("小白");
        assertThat(saved.getEntryAllocatedMap()).isEqualTo(Map.of(2, 5));
        assertThat(saved.getEntryLastTargetMap()).isEqualTo(Map.of(2, 5));
        assertThat(saved.toBuilder().setName("").setUnknownFields(UnknownFieldSet.getDefaultInstance()).build())
                .isEqualTo(entry.toBuilder().setUnknownFields(UnknownFieldSet.getDefaultInstance()).build());
    }
}
