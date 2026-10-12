package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.AttributeScheme;
import com.game.player.store.state.AttributeState;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

class PlayerAttributesTest {

    private static UnknownFieldSet unknown(int field, long value) {
        return UnknownFieldSet.newBuilder()
                .addField(field, UnknownFieldSet.Field.newBuilder().addVarint(value).build()).build();
    }

    @Test
    void 新号_一个默认方案方案一_当前为它_游标为2_是原始状态() {
        PlayerAttributes attributes = PlayerAttributes.empty();

        assertThat(attributes.schemes()).extracting(PlayerAttributes.Scheme::id).containsExactly(1);
        assertThat(attributes.schemes().get(0).name()).isEqualTo("方案一");
        assertThat(attributes.activeSchemeId()).isEqualTo(1);
        assertThat(attributes.isPristine()).isTrue();
        assertThat(PlayerAttributes.restore(attributes.toState()).isPristine()).isTrue();
    }

    @Test
    void 持久化往返逐字段相同_两层不认识的字段都原样带回() {
        AttributeState stored = AttributeState.newBuilder()
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(1).setName("方案一").putAllocated(103, 150)
                        .setUnknownFields(unknown(9, 1)))
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(4).setName("备用").putAllocated(101, 7))
                .setActiveSchemeId(4).setNextSchemeId(5).setLastSwitchTime(1_800_000_000L)
                .setUnknownFields(unknown(99, 7))
                .build();

        PlayerAttributes attributes = PlayerAttributes.restore(stored);

        assertThat(attributes.toState()).isEqualTo(stored);
        assertThat(attributes.isPristine()).isFalse();
        assertThat(PlayerAttributes.restore(AttributeState.newBuilder()
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(1).setName("方案一"))
                .setActiveSchemeId(1).setNextSchemeId(2).setUnknownFields(unknown(99, 7)).build()).isPristine())
                .as("只有不认识的字段也不能省略，否则更新版本写的数据会丢").isFalse();
    }

    @Test
    void 补缺省_当前为0取第一个_游标为0取最大id加1_值为0的分配不存() {
        PlayerAttributes attributes = PlayerAttributes.restore(AttributeState.newBuilder()
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(3).setName("甲").putAllocated(103, 0))
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(7).setName("乙"))
                .build());

        assertThat(attributes.activeSchemeId()).isEqualTo(3);
        assertThat(attributes.addScheme("丙")).isEqualTo(8);
        assertThat(attributes.scheme(3).allocatedView()).isEmpty();
    }

    @Test
    void 没有方案但当前方案非0_补默认方案并让它生效_同基线() {
        PlayerAttributes attributes = PlayerAttributes.restore(AttributeState.newBuilder().setActiveSchemeId(7).build());

        assertThat(attributes.activeSchemeId()).isEqualTo(1);
        assertThat(attributes.activeScheme()).isNotNull();
        assertThat(attributes.isPristine()).isTrue();
    }

    @Test
    void 有方案而当前方案悬空_不修_同基线() {
        PlayerAttributes attributes = PlayerAttributes.restore(AttributeState.newBuilder()
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(1).setName("方案一"))
                .setActiveSchemeId(7).setNextSchemeId(2).build());

        assertThat(attributes.activeSchemeId()).isEqualTo(7);
        assertThat(attributes.activeScheme()).isNull();
    }

    @Test
    void 已分配按无符号值恢复与写回() {
        AttributeState stored = AttributeState.newBuilder()
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(1).setName("方案一").putAllocated(103, -1))
                .setActiveSchemeId(1).setNextSchemeId(2).build();

        PlayerAttributes attributes = PlayerAttributes.restore(stored);

        assertThat(attributes.scheme(1).allocated(103)).isEqualTo(0xFFFF_FFFFL);
        assertThat(attributes.toState()).isEqualTo(stored);
    }

    @Test
    void 设分配为0即删除键_切换方案记时刻() {
        PlayerAttributes attributes = PlayerAttributes.empty();
        PlayerAttributes.Scheme scheme = attributes.activeScheme();

        scheme.setAllocated(103, 5);
        assertThat(attributes.isPristine()).isFalse();
        scheme.setAllocated(103, 0);
        assertThat(attributes.isPristine()).isTrue();

        int second = attributes.addScheme("方案二");
        attributes.switchTo(second, 1_800_000_000L);
        assertThat(attributes.activeScheme().id()).isEqualTo(2);
        assertThat(attributes.lastSwitchTime()).isEqualTo(1_800_000_000L);
    }
}
