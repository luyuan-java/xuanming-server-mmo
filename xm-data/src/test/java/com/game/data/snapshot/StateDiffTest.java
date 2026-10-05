package com.game.data.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.snapshot.StateDiff.Change;
import com.game.player.store.state.ActiveMission;
import com.game.player.store.state.AttributeScheme;
import com.game.player.store.state.AttributeState;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.Facing;
import com.game.player.store.state.MissionState;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.Vitals;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T-D1 的反射比较器部分：标量 / repeated / map / 子消息有无 / 未知字段按字节 / 500 条截断；以及 StateJson 的未知字段与 uint64。 */
class StateDiffTest {

    @Test
    void 标量与uint64按无符号_子消息有无_repeated按下标() {
        Vitals a = Vitals.newBuilder().setHealth(-1L).setMana(5).build();
        Vitals b = Vitals.newBuilder().setHealth(3).setMana(5).build();
        List<Change> changes = new StateDiff().compare("vitals", a, b).changes();
        assertThat(changes).containsExactly(new Change("vitals.health", "18446744073709551615", "3"));

        MissionState ms = MissionState.newBuilder().addCompletedIds(1).addCompletedIds(2).build();
        MissionState mc = MissionState.newBuilder().addCompletedIds(1).addCompletedIds(3).addCompletedIds(4)
                .addActive(ActiveMission.newBuilder().setMissionId(9).addProgress(1)).build();
        assertThat(new StateDiff().compare("mission", ms, mc).changes()).containsExactly(
                new Change("mission.active[0]", StateDiff.ABSENT, StateDiff.SET),
                new Change("mission.completed_ids[1]", "2", "3"),
                new Change("mission.completed_ids[2]", StateDiff.ABSENT, "4"));

        PlayerState withFacing = PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(1.5)).build();
        assertThat(new StateDiff().compare("", PlayerState.getDefaultInstance(), withFacing).changes()).containsExactly(
                new Change("facing", StateDiff.UNSET, StateDiff.SET), new Change("facing.x", "0.0", "1.5"));
    }

    @Test
    void map按键比较_键对侧没有记absent() {
        AttributeState a = AttributeState.newBuilder().addSchemes(AttributeScheme.newBuilder().setSchemeId(1)
                .putAllocated(1, 5).putAllocated(2, 3)).build();
        AttributeState b = AttributeState.newBuilder().addSchemes(AttributeScheme.newBuilder().setSchemeId(1)
                .putAllocated(1, 6).putAllocated(3, 1)).build();

        assertThat(new StateDiff().compare("attribute", a, b).changes()).containsExactly(
                new Change("attribute.schemes[0].allocated{1}", "5", "6"),
                new Change("attribute.schemes[0].allocated{2}", "3", StateDiff.ABSENT),
                new Change("attribute.schemes[0].allocated{3}", StateDiff.ABSENT, "1"));
    }

    @Test
    void 未知字段按字节比较() {
        Vitals a = Vitals.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build()).build();
        List<Change> changes = new StateDiff().compare("vitals", a, Vitals.getDefaultInstance()).changes();
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo("vitals.<unknown>");
            assertThat(c.snapshot()).isEqualTo("3 bytes");
            assertThat(c.current()).isEqualTo("0 bytes");
        });
    }

    @Test
    void 超过500条截断() {
        MissionState.Builder a = MissionState.newBuilder();
        MissionState.Builder b = MissionState.newBuilder();
        for (int i = 0; i < 600; i++) {
            a.addCompletedIds(i);
            b.addCompletedIds(i + 1);
        }
        StateDiff diff = new StateDiff().compare("mission", a.build(), b.build());
        assertThat(diff.changes()).hasSize(StateDiff.MAX_CHANGES);
        assertThat(diff.truncated()).isTrue();
    }

    @Test
    void compareField只比一个字段_路径带父路径() {
        MissionState ms = MissionState.newBuilder().addCompletedIds(1).addCompletedIds(2).build();
        MissionState mc = MissionState.newBuilder().addCompletedIds(1).addCompletedIds(3)
                .addActive(ActiveMission.newBuilder().setMissionId(9)).build();

        assertThat(new StateDiff().compareField("mission", MissionState.getDescriptor().findFieldByName("completed_ids"),
                ms, mc).changes()).as("只比 completed_ids，不碰 active").containsExactly(
                new Change("mission.completed_ids[1]", "2", "3"));
    }

    @Test
    void 段级差异按描述符覆盖PlayerState的全部非资产段_资产组不在其中() {
        PlayerState s = PlayerState.newBuilder().setVitals(Vitals.newBuilder().setHealth(100))
                .setCurrency(CurrencyState.newBuilder().addBalances(1)).build();
        PlayerState c = PlayerState.newBuilder().setVitals(Vitals.newBuilder().setHealth(80))
                .setFacing(Facing.newBuilder().setY(1)).setCurrency(CurrencyState.newBuilder().addBalances(2)).build();

        Map<String, Object> sections = SnapshotDiffService.sectionDiff(s, c);

        List<String> nonAsset = PlayerState.getDescriptor().getFields().stream()
                .filter(fd -> !SnapshotDiffService.ASSET_GROUP_FIELDS.contains(fd.getNumber()))
                .map(FieldDescriptor::getName).toList();
        assertThat(nonAsset).as("现有的非资产段").contains("facing", "attribute", "mission", "vitals")
                .doesNotContain("currency", "bag", "pets", "asset_ledger");
        assertThat(sections).as("以后新加的玩法段也自动进差异").containsKeys(nonAsset.toArray(String[]::new))
                .containsKeys("unknown", "changesTruncated")
                .doesNotContainKeys("currency", "bag", "pets", "asset_ledger");
        assertThat(section(sections, "attribute")).containsEntry("same", true);
        assertThat(section(sections, "vitals")).containsEntry("same", false).doesNotContainKey("presence");
        assertThat(changes(section(sections, "vitals"))).extracting(m -> m.get("path")).containsExactly("vitals.health");
        assertThat(section(sections, "facing")).containsEntry("same", false).containsKey("presence");
        assertThat(changes(section(sections, "facing"))).extracting(m -> m.get("path")).containsExactly("facing.y");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> sections, String name) {
        return (Map<String, Object>) sections.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> changes(Map<String, Object> section) {
        return (List<Map<String, Object>>) section.get("changes");
    }

    @Test
    void StateJson_uint64输出十进制字符串_未知字段带路径与原样字节_坏字节给parseError() {
        BagItemState item = BagItemState.newBuilder().setItemUuid(-2L).setConfigId(501).setStackSize(3)
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(50, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build())
                .build();
        PlayerState state = PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(-1L))
                .setBag(BagState.newBuilder().addItems(item))
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(200, UnknownFieldSet.Field.newBuilder().addVarint(5).build()).build())
                .build();
        ObjectMapper json = new ObjectMapper();

        Map<String, Object> out = StateJson.render(state.toByteArray(), json);

        JsonNode s = (JsonNode) out.get("state");
        assertThat(s.at("/currency/balances/0").asText()).isEqualTo("18446744073709551615");
        assertThat(s.at("/bag/items/0/item_uuid").asText()).isEqualTo("18446744073709551614");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unknown = (List<Map<String, Object>>) out.get("unknownFields");
        assertThat(unknown).extracting(m -> m.get("path")).containsExactly("", "bag.items[0]");
        assertThat(unknown.get(0)).containsEntry("bytes", 3);

        Map<String, Object> bad = StateJson.render(new byte[] {0x0a, 0x7f}, json);
        assertThat(bad).containsKeys("parseError", "rawBase64").doesNotContainKey("state");
    }
}
