package com.game.data.rollback;

import com.game.data.store.PersistedPlayer;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 回档的恢复内容（data-ops-spec §4.5）：由「快照」与「现档」算出要写回的 player 行可变列与 {@code player_state}，以及资产变化
 * （§4.10 回档流水的素材）。纯函数，不碰库。
 *
 * <ul>
 *   <li><b>FULL</b>：等级 / 场景 / 坐标取快照值；{@code player_state} 整份换成快照内容（包括新版本写下、本版本不认识的字段；快照之后新增的段被清掉，
 *       修 H5）。<b>例外</b>：{@code currency.blocked_types}（GM 封禁获取的币种）保留现档值——处罚状态不是资产，回档不解除处罚（D6）。
 *       现档损坏时（FULL 正是修复手段，允许）封禁名单无从保留，取快照值并记进明细。</li>
 *   <li><b>SECTIONS</b>：以现档为底（现档里不认识的字段保留），只把选中的段换成快照里的；快照没有这段就清掉。现档损坏 → {@link StateInvalidException}。
 *       选了 {@code assets} 而快照与现档的顶层未知字段不同 → {@link UnknownSectionsException}（不认识的段可能是新账本，不能留在快照之后）。</li>
 * </ul>
 */
public final class RestoreBuilder {

    /** 快照或现档的 player_state 解析失败（{@code side} = snapshot / current）。 */
    public static class StateInvalidException extends RuntimeException {
        private final String side;

        public StateInvalidException(String side, String message, Throwable cause) {
            super(message, cause);
            this.side = side;
        }

        public String side() {
            return side;
        }
    }

    /**
     * SECTIONS 选了 {@code assets}，而快照与现档在 {@code PlayerState} 顶层有本版本不认识、且两边不同的字段（较新的 scene 写下的新段，
     * 例如滚动升级时 scene 先上了一个新的账本字段）。资产组按本版本的描述符计算（{@link RollbackSection#ASSET_FIELDS}），不认识的段既不能
     * 判断是不是账本、也不会随资产一起恢复——账本留在快照之后、资产回到快照，会重复记账或扣款消失。fail-closed：这名玩家不写
     * （明细 {@code unknown_sections}），先升级 xm-data 或改用 FULL。是 {@link StateInvalidException} 的子类：漏接的地方按 {@code state_invalid} 处理。
     */
    public static final class UnknownSectionsException extends StateInvalidException {
        private final List<Integer> fields;

        UnknownSectionsException(List<Integer> fields) {
            super("both", "快照与现档有本版本不认识、且两边不同的顶层字段 " + fields
                    + "：部分回档的 assets 无法判断它们是否属于资产组，拒绝（先升级 xm-data，或改用整份回档 FULL）", null);
            this.fields = fields;
        }

        /** 两边不同的未知字段号（升序）。 */
        public List<Integer> fields() {
            return fields;
        }
    }

    /** 一个币种的余额变化。 */
    public record CurrencyChange(int currencyType, long before, long after) {
    }

    /** 一个物品实例的数量变化（只在恢复后里 = 获得；只在现档里 = 扣减；两边都有 = 数量差）。 */
    public record ItemChange(long itemUuid, int configId, long before, long after) {
    }

    /**
     * 恢复结果。
     *
     * @param assetsRestored 资产组是否被恢复（FULL 或选了 assets）：只有这时才写回档流水
     * @param currentValid   现档能否解析（不能时不写回档流水：前后余额无从得知）
     */
    public record Restored(PlayerState state, long level, int sceneConfigId, double posX, double posY, double posZ,
                           boolean assetsRestored, boolean currentValid, List<CurrencyChange> currency,
                           List<ItemChange> items, Map<String, Object> detail) {
    }

    private RestoreBuilder() {
    }

    public static PlayerState parse(byte[] data, String side) {
        try {
            return PlayerState.parseFrom(data == null ? new byte[0] : data);
        } catch (InvalidProtocolBufferException e) {
            throw new StateInvalidException(side, ("snapshot".equals(side) ? "快照" : "现档") + "的 player_state 解析失败："
                    + e.getMessage(), e);
        }
    }

    /**
     * SECTIONS 且选了 {@code assets} 时，快照与现档顶层的未知字段必须相同，否则抛 {@link UnknownSectionsException}（见其说明）。
     * 两边相同时没有要回退的东西，照常；FULL（整份替换，未知字段随快照）与不动资产组的 SECTIONS（未知字段保留现档，§4.5）不受影响。
     */
    public static void requireSameUnknownFields(PlayerState snap, PlayerState cur, Set<RollbackSection> sections) {
        if (sections.isEmpty() || !sections.contains(RollbackSection.ASSETS)) {
            return;
        }
        UnknownFieldSet s = snap.getUnknownFields();
        UnknownFieldSet c = cur.getUnknownFields();
        if (s.equals(c)) {
            return;
        }
        TreeSet<Integer> differing = new TreeSet<>(s.asMap().keySet());
        differing.addAll(c.asMap().keySet());
        differing.removeIf(n -> Objects.equals(s.asMap().get(n), c.asMap().get(n)));
        throw new UnknownSectionsException(List.copyOf(differing));
    }

    /**
     * @param sections 空 = FULL
     * @throws StateInvalidException 快照损坏；或 SECTIONS 时现档损坏；或 SECTIONS 选了 assets 而两边的未知字段不同（{@link UnknownSectionsException}）
     */
    public static Restored build(PlayerSnapshotEntry snapshot, PersistedPlayer current, Set<RollbackSection> sections) {
        PlayerState snap = parse(snapshot.getPlayerState(), "snapshot");
        PlayerState cur = null;
        boolean currentValid = true;
        try {
            cur = parse(current.stateBytes(), "current");
        } catch (StateInvalidException e) {
            if (!sections.isEmpty()) {
                throw e;
            }
            currentValid = false;
        }
        boolean full = sections.isEmpty();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", full ? "FULL" : "SECTIONS");
        if (!full) {
            detail.put("sections", sections.stream().map(RollbackSection::wire).toList());
        }
        detail.put("snapshotId", Long.toUnsignedString(snapshot.getSnapshotId()));
        detail.put("snapshotTimeMs", snapshot.getTimeMs());

        PlayerState restored;
        if (full) {
            PlayerState.Builder b = snap.toBuilder();
            if (currentValid) {
                b.getCurrencyBuilder().clearBlockedTypes().addAllBlockedTypes(cur.getCurrency().getBlockedTypesList());
                // 快照里本没有货币段、现档也没有封禁：不凭空造出一个空的货币段
                if (!snap.hasCurrency() && cur.getCurrency().getBlockedTypesCount() == 0) {
                    b.clearCurrency();
                }
                detail.put("blockedTypesKept", cur.getCurrency().getBlockedTypesList());
            } else {
                detail.put("currentStateInvalid", true);
                detail.put("blockedTypesFromSnapshot", snap.getCurrency().getBlockedTypesList());
            }
            restored = b.build();
        } else {
            requireSameUnknownFields(snap, cur, sections);
            PlayerState.Builder b = cur.toBuilder();
            for (RollbackSection section : sections) {
                if (section.field() != 0) {
                    copyField(b, snap, section.field());
                }
            }
            if (sections.contains(RollbackSection.ASSETS)) {
                for (int field : RollbackSection.ASSET_FIELDS) {
                    copyField(b, snap, field);
                }
                // 封禁名单保留现档值（D6）
                if (b.hasCurrency() || cur.getCurrency().getBlockedTypesCount() > 0) {
                    b.getCurrencyBuilder().clearBlockedTypes().addAllBlockedTypes(cur.getCurrency().getBlockedTypesList());
                }
                detail.put("blockedTypesKept", cur.getCurrency().getBlockedTypesList());
            }
            restored = b.build();
        }

        long level = full || sections.contains(RollbackSection.LEVEL)
                ? Integer.toUnsignedLong(snapshot.getLevel()) : Integer.toUnsignedLong(current.getLevel());
        boolean position = full || sections.contains(RollbackSection.POSITION);
        int sceneConfigId = position ? snapshot.getSceneConfigId() : current.getSceneConfigId();
        double x = position ? snapshot.getPosX() : current.getPosX();
        double y = position ? snapshot.getPosY() : current.getPosY();
        double z = position ? snapshot.getPosZ() : current.getPosZ();
        detail.put("level", List.of(Integer.toUnsignedLong(current.getLevel()), level));
        detail.put("sceneConfigId", List.of(Integer.toUnsignedLong(current.getSceneConfigId()),
                Integer.toUnsignedLong(sceneConfigId)));

        boolean assets = RollbackSection.restoresAssets(sections);
        List<CurrencyChange> currency = new ArrayList<>();
        List<ItemChange> items = new ArrayList<>();
        if (assets && currentValid) {
            currency = currencyChanges(cur.getCurrency(), restored.getCurrency());
            items = itemChanges(cur, restored);
            List<Map<String, Object>> cv = new ArrayList<>();
            for (CurrencyChange c : currency) {
                cv.add(Map.of("currencyType", c.currencyType(), "before", Long.toUnsignedString(c.before()),
                        "after", Long.toUnsignedString(c.after())));
            }
            detail.put("currency", cv);
            int added = 0;
            int removed = 0;
            int changed = 0;
            for (ItemChange i : items) {
                if (i.before() == 0) {
                    added++;
                } else if (i.after() == 0) {
                    removed++;
                } else {
                    changed++;
                }
            }
            detail.put("items", Map.of("added", added, "removed", removed, "stackChanged", changed));
            detail.put("pets", List.of(cur.getPets().getPetsCount(), restored.getPets().getPetsCount()));
        } else if (assets) {
            detail.put("txlogSkipped", "现档损坏：前后余额无从得知，不写回档流水");
        }
        return new Restored(restored, level, sceneConfigId, x, y, z, assets, currentValid, currency, items, detail);
    }

    /** 快照有这段就整段换上，没有就清掉。 */
    private static void copyField(PlayerState.Builder b, PlayerState snap, int number) {
        FieldDescriptor fd = PlayerState.getDescriptor().findFieldByNumber(number);
        if (fd.isRepeated() ? snap.getRepeatedFieldCount(fd) > 0 : snap.hasField(fd)) {
            b.setField(fd, snap.getField(fd));
        } else {
            b.clearField(fd);
        }
    }

    /** 逐币种（下标 = 币种）比较；余额没变的不出现。 */
    static List<CurrencyChange> currencyChanges(CurrencyState before, CurrencyState after) {
        List<CurrencyChange> out = new ArrayList<>();
        int types = Math.max(before.getBalancesCount(), after.getBalancesCount());
        for (int type = 0; type < types; type++) {
            long b = type < before.getBalancesCount() ? before.getBalances(type) : 0;
            long a = type < after.getBalancesCount() ? after.getBalances(type) : 0;
            if (a != b) {
                out.add(new CurrencyChange(type, b, a));
            }
        }
        return out;
    }

    /** 逐实例（按 uuid）比较堆叠数；没变的不出现。按 uuid 升序。 */
    static List<ItemChange> itemChanges(PlayerState before, PlayerState after) {
        Map<Long, BagItemState> b = new TreeMap<>(Long::compareUnsigned);
        before.getBag().getItemsList().forEach(i -> b.putIfAbsent(i.getItemUuid(), i));
        Map<Long, BagItemState> a = new TreeMap<>(Long::compareUnsigned);
        after.getBag().getItemsList().forEach(i -> a.putIfAbsent(i.getItemUuid(), i));
        TreeMap<Long, ItemChange> out = new TreeMap<>(Long::compareUnsigned);
        b.forEach((uuid, item) -> {
            BagItemState now = a.get(uuid);
            long qb = Integer.toUnsignedLong(item.getStackSize());
            long qa = now == null ? 0 : Integer.toUnsignedLong(now.getStackSize());
            if (qa != qb) {
                out.put(uuid, new ItemChange(uuid, item.getConfigId(), qb, qa));
            }
        });
        a.forEach((uuid, item) -> {
            if (!b.containsKey(uuid)) {
                out.put(uuid, new ItemChange(uuid, item.getConfigId(), 0, Integer.toUnsignedLong(item.getStackSize())));
            }
        });
        return new ArrayList<>(out.values());
    }
}
