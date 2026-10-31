package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.proto.BattlePlacement;
import com.game.proto.BattleActivityContext;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.MatchMode;
import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 观战可见性的单一策略表（mmorpg battle-spectate-tiered-fanout.md §10 第 6 条；与 mmorpg Go 版 {@code spectate_visibility_test.go} 同口径）：
 * 每条规则、活动局一律私密、默认拒绝、每个 {@link MatchMode} 都在显式决策表里，以及两种「冻结值」的读法（落点记录的数值、全球模式的建房原件字节）。
 */
class SpectateVisibilityPolicyTest {

    private static final BattleActivityContext GUILD_TRIAL = BattleActivityContext.newBuilder()
            .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(555).setActivityId(3).build();

    @Test
    void 只有PVE单人与PVE组队公开_计分局切磋人数档PVP未开放与未填一律私密() {
        assertThat(SpectateVisibilityPolicy.decide(MatchMode.MATCH_MODE_PVE_SOLO, null)).isEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        assertThat(SpectateVisibilityPolicy.decide(MatchMode.MATCH_MODE_PVE_TEAM, null)).isEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        for (MatchMode closed : List.of(MatchMode.MATCH_MODE_1V1, MatchMode.MATCH_MODE_5V5, MatchMode.MATCH_MODE_PVP_CHALLENGE,
                MatchMode.MATCH_MODE_PVP_TEAM, MatchMode.MATCH_MODE_3V3, MatchMode.MATCH_MODE_UNSPECIFIED)) {
            assertThat(SpectateVisibilityPolicy.decide(closed, null)).as(closed.name()).isEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        }
    }

    @Test
    void 表外的值与null按私密_默认拒绝而不是默认放行() {
        assertThat(SpectateVisibilityPolicy.decide(MatchMode.UNRECOGNIZED, null)).isEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        assertThat(SpectateVisibilityPolicy.decide(null, null)).isEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        assertThat(SpectateVisibilityPolicy.decide(MatchMode.forNumber(4242), null)).as("契约里没有的数值 forNumber 回 null")
                .isEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MatchMode.class, names = "UNRECOGNIZED", mode = EnumSource.Mode.EXCLUDE)
    void 活动局一律私密_与模式无关_kind为NONE的上下文按普通对局(MatchMode mode) {
        assertThat(SpectateVisibilityPolicy.decide(mode, GUILD_TRIAL)).as("帮会历练等活动局").isEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        BattleActivityContext none = BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_NONE).build();
        assertThat(SpectateVisibilityPolicy.decide(mode, none)).isEqualTo(SpectateVisibilityPolicy.decide(mode, null));
    }

    @Test
    void 每个MatchMode枚举值都在显式决策表里_新增模式没进表就红() {
        List<MatchMode> declared = Arrays.stream(MatchMode.values()).filter(mode -> mode != MatchMode.UNRECOGNIZED).toList();
        assertThat(SpectateVisibilityPolicy.decidedModes()).as("新增 MatchMode 必须在 SpectateVisibilityPolicy 的决策表里显式表态")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void 决定出的值永不是UNSPECIFIED_建房请求里不会出现没填() {
        for (MatchMode mode : MatchMode.values()) {
            assertThat(SpectateVisibilityPolicy.decide(mode, null)).as(mode.name()).isNotEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_UNSPECIFIED);
            assertThat(SpectateVisibilityPolicy.decide(mode, GUILD_TRIAL)).isNotEqualTo(eSpectateVisibility.SPECTATE_VISIBILITY_UNSPECIFIED);
        }
    }

    @Test
    void 只认PUBLIC_未填私密与认不出的值都不可观战() {
        assertThat(SpectateVisibilityPolicy.isPublic(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC)).isTrue();
        assertThat(SpectateVisibilityPolicy.isPublic(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE)).isFalse();
        assertThat(SpectateVisibilityPolicy.isPublic(eSpectateVisibility.SPECTATE_VISIBILITY_UNSPECIFIED)).isFalse();
        assertThat(SpectateVisibilityPolicy.isPublic(eSpectateVisibility.UNRECOGNIZED)).isFalse();
        assertThat(SpectateVisibilityPolicy.isPublic((eSpectateVisibility) null)).isFalse();
    }

    @Test
    void 落点记录存的是枚举数值_只有PUBLIC的数值可观战() {
        assertThat(SpectateVisibilityPolicy.isPublic(placement(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC_VALUE))).isTrue();
        assertThat(SpectateVisibilityPolicy.isPublic(placement(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE_VALUE))).isFalse();
        assertThat(SpectateVisibilityPolicy.isPublic(placement(0))).as("漏填 / 老写者").isFalse();
        assertThat(SpectateVisibilityPolicy.isPublic(placement(99))).as("认不出的数值").isFalse();
    }

    @Test
    void 全球模式读建房原件字节_公开私密空与坏字节() {
        ByteString publicCreate = CreateBattleRequest.newBuilder().setBattleId(7)
                .setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC).build().toByteString();
        ByteString privateCreate = CreateBattleRequest.newBuilder().setBattleId(7)
                .setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE).build().toByteString();
        ByteString unspecified = CreateBattleRequest.newBuilder().setBattleId(7).build().toByteString();

        assertThat(SpectateVisibilityPolicy.createRequestPublic(publicCreate)).isTrue();
        assertThat(SpectateVisibilityPolicy.createRequestPublic(privateCreate)).isFalse();
        assertThat(SpectateVisibilityPolicy.createRequestPublic(unspecified)).as("没填的老原件").isFalse();
        assertThat(SpectateVisibilityPolicy.createRequestPublic(ByteString.EMPTY)).as("还没组好建房请求").isFalse();
        assertThat(SpectateVisibilityPolicy.createRequestPublic(null)).isFalse();
        assertThat(SpectateVisibilityPolicy.createRequestPublic(ByteString.copyFrom(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff})))
                .as("坏字节 fail-closed").isFalse();
    }

    private static BattlePlacement placement(int visibility) {
        return BattlePlacement.newBuilder().setBattleId(7).setSpectateVisibility(visibility).build();
    }
}
