package com.game.data.rollback;

import com.game.data.ops.OpsException;
import com.game.player.store.state.PlayerState;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 部分回档（SECTIONS）的段名（data-ops-spec §4.5，D5）。段名固定，资产组不可拆：
 * <ul>
 *   <li>{@code assets} = currency（不含 blocked_types，含欠款）+ bag + pets + asset_ledger（以及以后新加的段，见 {@link #ASSET_FIELDS}）
 *       一起恢复：资产通道账本记录的是货币与物品的结局，
 *       必须与资产同记录、同一次写（{@code player_state.proto:32-34}）。不开放 {@code currency} / {@code bag} / {@code pets} 单独的段名；</li>
 *   <li>{@code mission} 必须与 {@code assets} 同选：单独恢复任务会让快照之后已领过的奖励（在资产里）可以再领一次；</li>
 *   <li>{@code level} / {@code position} / {@code facing} / {@code attribute} / {@code vitals} 自由组合。</li>
 * </ul>
 * 不给 sections = FULL（整份替换）；给了空列表 → 400（修 H4：基线 PARTIAL 不带字段 = 全量）。
 */
public enum RollbackSection {
    LEVEL("level", 0),
    POSITION("position", 0),
    FACING("facing", PlayerState.FACING_FIELD_NUMBER),
    ATTRIBUTE("attribute", PlayerState.ATTRIBUTE_FIELD_NUMBER),
    VITALS("vitals", PlayerState.VITALS_FIELD_NUMBER),
    MISSION("mission", PlayerState.MISSION_FIELD_NUMBER),
    ASSETS("assets", 0);

    /** 资产组拆开的名字：写了回 400 并提示用 assets。 */
    private static final List<String> ASSET_PARTS = List.of("currency", "bag", "pets", "asset_ledger", "assetledger",
            "ledger", "debts");

    private final String wire;
    /** 对应的 PlayerState 字段号；0 = 不是单个玩法段（player 行的列，或资产组）。 */
    private final int field;

    RollbackSection(String wire, int field) {
        this.wire = wire;
        this.field = field;
    }

    public String wire() {
        return wire;
    }

    int field() {
        return field;
    }

    /** 明确不属于资产组的玩法段（有自己的段名）。 */
    static final Set<Integer> NON_ASSET_FIELDS = Set.of(PlayerState.FACING_FIELD_NUMBER, PlayerState.ATTRIBUTE_FIELD_NUMBER,
            PlayerState.MISSION_FIELD_NUMBER, PlayerState.VITALS_FIELD_NUMBER);

    /**
     * 资产组包含的 PlayerState 字段 = 描述符里<b>除</b> {@link #NON_ASSET_FIELDS} 以外的全部字段（现在是 currency / bag / pets / asset_ledger，
     * 以及以后新加的段）。新加的段缺省归资产组是保守的选择：账本类字段（资产通道账本、6.3 的战斗结算账本……）必须与资产同记录、同一次写，
     * 漏进资产组只会让 {@code assets} 回档多恢复一段，而漏出资产组会让资产与账本对不上（重复记账 / 扣款消失）。非资产的新段要显式加进
     * {@link #NON_ASSET_FIELDS} 并给自己的段名。
     */
    static final Set<Integer> ASSET_FIELDS = assetFields();

    private static Set<Integer> assetFields() {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        for (com.google.protobuf.Descriptors.FieldDescriptor fd : PlayerState.getDescriptor().getFields()) {
            if (!NON_ASSET_FIELDS.contains(fd.getNumber())) {
                out.add(fd.getNumber());
            }
        }
        return Set.copyOf(out);
    }

    private static final Map<String, RollbackSection> BY_WIRE = Map.of(LEVEL.wire, LEVEL, POSITION.wire, POSITION,
            FACING.wire, FACING, ATTRIBUTE.wire, ATTRIBUTE, VITALS.wire, VITALS, MISSION.wire, MISSION, ASSETS.wire, ASSETS);

    /**
     * 解析请求里的段名。{@code null} = FULL（返回空集）；空列表、未知名、拆开的资产组、{@code mission} 不带 {@code assets} 都回 400。
     */
    public static Set<RollbackSection> parse(List<String> names) {
        if (names == null) {
            return EnumSet.noneOf(RollbackSection.class);
        }
        if (names.isEmpty()) {
            throw OpsException.badRequest("sections 给了就不能为空（不给 = 整份回档 FULL）");
        }
        EnumSet<RollbackSection> out = EnumSet.noneOf(RollbackSection.class);
        for (String raw : names) {
            String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (ASSET_PARTS.contains(name)) {
                throw OpsException.badRequest("资产组不可拆分：货币 / 背包 / 宝宝 / 资产通道账本只能用 assets 一起恢复（" + raw + "）");
            }
            RollbackSection section = BY_WIRE.get(name);
            if (section == null) {
                throw OpsException.badRequest("未知的段名：" + raw + "（只有 level / position / facing / attribute / vitals / mission / assets）");
            }
            out.add(section);
        }
        if (out.contains(MISSION) && !out.contains(ASSETS)) {
            throw OpsException.badRequest("mission 必须与 assets 同选：单独恢复任务会让快照之后已领过的奖励可以再领一次");
        }
        return out;
    }

    /** 是否恢复资产组（FULL 或选了 assets）。 */
    public static boolean restoresAssets(Set<RollbackSection> sections) {
        return sections.isEmpty() || sections.contains(ASSETS);
    }
}
