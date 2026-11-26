package com.game.scene.equip;

import com.game.player.store.state.BagItemState;
import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.EquipmentGemState;
import com.game.proto.EnhanceEquipRequest;
import com.game.scene.attribute.AttributeService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.BattleGate;
import com.game.scene.player.*;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import com.game.table.EquipErrorTip.equip_error;
import com.game.table.ItemTable;
import com.google.protobuf.ByteString;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 装备命令的单线程领域服务。先验证并准备完整候选，提交后才做属性重算与旁路审计。 */
public final class EquipmentService {
    private static final Logger log = LoggerFactory.getLogger(EquipmentService.class);
    private static final int VERSION_UNAVAILABLE = 1006;
    public record Outcome(int tip, boolean success, int enhanceLevel, boolean attributesChanged,
                          boolean replayed, int enhancePityCount, int enhancePityLimit) {
        public Outcome(int tip, boolean success, int enhanceLevel, boolean attributesChanged) {
            this(tip, success, enhanceLevel, attributesChanged, false, 0, 0);
        }
        static Outcome refused(int tip) { return new Outcome(tip, false, 0, false); }
    }
    private record Located(BagItem item, ItemTable row, boolean worn, int tip) { }
    private final EquipmentRules rules;
    private final BagService bags;
    private final CurrencyService currency;
    private final AttributeService attributes;
    private final SceneMetrics metrics;

    public EquipmentService(EquipmentRules rules, BagService bags, CurrencyService currency,
                            AttributeService attributes, SceneMetrics metrics) {
        this.rules = Objects.requireNonNull(rules);
        this.bags = Objects.requireNonNull(bags);
        this.currency = Objects.requireNonNull(currency);
        this.attributes = Objects.requireNonNull(attributes);
        this.metrics = Objects.requireNonNull(metrics);
    }
    public EquipmentRules rules() { return rules; }
    private int writable(ScenePlayer p) {
        if (p.frozen()) {
            return equip_error.kEquipFrozen_VALUE;
        }
        if (p.inBattle() || p.battle().recovery() != com.game.scene.battle.PlayerBattle.Recovery.READY) {
            metrics.battleGateReject(BattleGate.EQUIPMENT);
            return equip_error.kEquipInBattle_VALUE;
        }
        return 0;
    }
    private PlayerBags candidate(ScenePlayer p) {
        PlayerBags copy = PlayerBags.restore(p.bags().toState()); copy.normalize(bags.tables()); return copy;
    }
    private Located locate(PlayerBags source, long id) {
        BagItem item = source.bag(BagType.INVENTORY).item(id); boolean worn = false;
        if (item == null) { item = source.bag(BagType.EQUIPMENT).item(id); worn = true; }
        if (item == null) return new Located(null, null, false, equip_error.kEquipItemNotFound_VALUE);
        ItemTable row = rules.tables().item(item.configId());
        if (row == null || row.getEquipKind() == 0 || row.getMaxStackSize() != 1)
            return new Located(item, row, worn, equip_error.kEquipNotEquipment_VALUE);
        return new Located(item, row, worn, item.size() == 1 ? 0 : equip_error.kEquipItemNotFound_VALUE);
    }
    private Outcome commit(ScenePlayer p, PlayerBags candidate, boolean worn, boolean success, int level) {
        p.bags().commitPrepared(candidate);
        if (worn) attributes.equipmentChanged(p);
        return new Outcome(0, success, level, worn);
    }
    public Outcome equip(ScenePlayer p, long id) {
        int gate = writable(p); if (gate != 0) return Outcome.refused(gate);
        Bag inventory = p.bags().bag(BagType.INVENTORY), equipped = p.bags().bag(BagType.EQUIPMENT);
        BagItem item = inventory.item(id);
        if (item == null || item.size() != 1) return Outcome.refused(equip_error.kEquipItemNotFound_VALUE);
        ItemTable row = rules.tables().item(item.configId());
        if (row == null || row.getEquipKind() == 0 || row.getMaxStackSize() != 1) return Outcome.refused(equip_error.kEquipNotEquipment_VALUE);
        if (Integer.compareUnsigned(PlayerLevels.effective(p.level()), row.getEquipLevel()) < 0) return Outcome.refused(equip_error.kEquipLevelNotEnough_VALUE);
        if (row.getEquipClass() != 0 && row.getEquipClass() != p.classId()) return Outcome.refused(equip_error.kEquipClassMismatch_VALUE);
        var slots = bags.tables().equipSlots(row.getEquipKind()).stream().filter(s -> Integer.compareUnsigned(s, equipped.capacity()) < 0)
                .sorted(Integer::compareUnsigned).toList();
        if (slots.isEmpty()) return Outcome.refused(equip_error.kEquipNoSlot_VALUE);
        int slot = slots.stream().filter(s -> equipped.items().stream().noneMatch(i -> i.slot() == s)).findFirst().orElse(slots.getFirst());
        BagItem replaced = equipped.items().stream().filter(i -> i.slot() == slot).findFirst().orElse(null);
        if (replaced != null && !carriable(replaced)) return Outcome.refused(equip_error.kEquipInternalError_VALUE);
        int returnSlot = 0;
        Set<Integer> occupied = new HashSet<>();
        inventory.items().stream().filter(i -> i.guid() != id).forEach(i -> occupied.add(i.slot()));
        while (occupied.contains(returnSlot)) returnSlot++;
        var snapshot = p.bags().toState().toBuilder();
        for (int i = 0; i < snapshot.getItemsCount(); i++) {
            BagItemState stored = snapshot.getItems(i);
            if (stored.getItemUuid() == id) snapshot.setItems(i, stored.toBuilder().setBagType(BagType.EQUIPMENT.code()).setPos(slot));
            else if (replaced != null && stored.getItemUuid() == replaced.guid())
                snapshot.setItems(i, stored.toBuilder().setBagType(BagType.INVENTORY.code()).setPos(returnSlot));
        }
        PlayerBags next = PlayerBags.restore(snapshot.build()); next.normalize(bags.tables());
        return commit(p, next, true, true, 0);
    }
    public Outcome unequip(ScenePlayer p, long id) {
        int gate = writable(p); if (gate != 0) return Outcome.refused(gate);
        BagItem item = p.bags().bag(BagType.EQUIPMENT).item(id);
        if (item == null) return Outcome.refused(equip_error.kEquipNotEquipped_VALUE);
        if (!carriable(item)) return Outcome.refused(equip_error.kEquipInternalError_VALUE);
        Bag inventory = p.bags().bag(BagType.INVENTORY);
        if (inventory.freeCells() == 0) return Outcome.refused(equip_error.kEquipBagFull_VALUE);
        int slot = 0; Set<Integer> occupied = new HashSet<>(); inventory.items().forEach(i -> occupied.add(i.slot()));
        while (occupied.contains(slot)) slot++;
        var state = p.bags().toState().toBuilder();
        for (int i = 0; i < state.getItemsCount(); i++) if (state.getItems(i).getItemUuid() == id)
            state.setItems(i, state.getItems(i).toBuilder().setBagType(BagType.INVENTORY.code()).setPos(slot));
        PlayerBags next = PlayerBags.restore(state.build()); next.normalize(bags.tables());
        return commit(p, next, true, true, 0);
    }
    private boolean carriable(BagItem item) {
        var row = rules.tables().item(item.configId());
        return row != null && row.getMaxStackSize() == 1 && item.size() == 1;
    }
    private int quantityTip(ScenePlayer p, int config, long count, int insufficient) {
        Bag inventory = p.bags().bag(BagType.INVENTORY);
        if (bags.tables().hasUnknownItemSchema(config)) return VERSION_UNAVAILABLE;
        if (inventory.plainQuantity(config) >= count) return 0;
        return inventory.total(config) >= count ? VERSION_UNAVAILABLE : insufficient;
    }
    public Outcome grant(ScenePlayer p, int config, int count) {
        int gate = writable(p); if (gate != 0) return Outcome.refused(gate);
        if (config == 0 || count == 0 || Integer.compareUnsigned(count, 99) > 0 || rules.tables().item(config) == null)
            return Outcome.refused(equip_error.kEquipGrantInvalid_VALUE);
        var result = bags.addItems(p, BagType.INVENTORY, Map.of(config, Integer.toUnsignedLong(count)), AssetAudit.Reason.GM_GRANT, 0, "");
        return new Outcome(result.tip(), result.ok(), 0, false);
    }
    public Outcome enhance(ScenePlayer p, long id) {
        return enhanceCore(p, id, null, null, false, 0L, null);
    }

    /** RPC 强化入口：先按 client_request_id 幂等查账，再校乐观等级/保底次数与 profile 规则。 */
    public Outcome enhance(ScenePlayer p, EnhanceEquipRequest request) {
        if (request.getItemId() == 0) {
            return Outcome.refused(CommonErrorTip.common_error.kInvalidParameter_VALUE);
        }
        long reqId = request.getClientRequestId();
        ByteString fingerprint = null;
        if (reqId != 0) {
            PlayerClientRequestLedger ledger = p.clientRequestLedger();
            if (!ledger.isValid()) {
                return Outcome.refused(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
            }
            fingerprint = PlayerClientRequestLedger.fingerprint(request.toBuilder().clearClientRequestId().build());
            var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_ENHANCE_EQUIP, fingerprint);
            if (check.verdict() == PlayerClientRequestLedger.Verdict.MISMATCH) {
                return Outcome.refused(CommonErrorTip.common_error.kInvalidParameter_VALUE);
            }
            if (check.verdict() == PlayerClientRequestLedger.Verdict.REPLAY) {
                ClientRequestRecordState rec = check.record();
                return new Outcome(0, rec.getEnhanceSuccess(), rec.getEnhanceLevel(), false, true,
                        rec.getEnhancePityCount(), resolvePityLimitForReplay(p, request.getItemId()));
            }
        }
        Integer expectedPity = request.hasExpectedPityCount() ? request.getExpectedPityCount() : null;
        return enhanceCore(p, request.getItemId(), request.getExpectedLevel(), expectedPity,
                request.getAllowBoundMaterials(), reqId, fingerprint);
    }

    private int resolvePityLimitForReplay(ScenePlayer p, long itemId) {
        Located found = locate(p.bags(), itemId);
        if (found.tip() != 0 || found.item().equipment() == null) return 0;
        int profile = found.row().getEquipProfile();
        int level = rules.level(found.item());
        if (level >= rules.tables().maxEnhance(profile)) return 0;
        var next = rules.tables().enhance(profile, level + 1);
        return next != null && rules.isEnhanceRowAvailable(next) ? next.getPityLimit() : 0;
    }

    private Outcome enhanceCore(ScenePlayer p, long id, Integer expectedLevel, Integer expectedPity,
                                boolean allowBoundMaterials, long clientRequestId, ByteString fingerprint) {
        int gate = writable(p); if (gate != 0) return Outcome.refused(gate);
        Located found = locate(p.bags(), id); if (found.tip() != 0) return Outcome.refused(found.tip());
        int profile = found.row().getEquipProfile();
        int maxEnhance = rules.tables().maxEnhance(profile);
        if (found.item().equipment() == null || maxEnhance == 0) return Outcome.refused(equip_error.kEquipEnhanceUnavailable_VALUE);
        int current = rules.level(found.item());
        int currentPity = found.item().equipment().getEnhancePityCount();
        if (expectedLevel != null && (expectedLevel != current || (expectedPity != null && expectedPity != currentPity))) {
            return Outcome.refused(equip_error.kEquipEnhanceLevelChanged_VALUE);
        }
        if (current >= maxEnhance) return Outcome.refused(equip_error.kEquipEnhanceMaxLevel_VALUE);
        var row = rules.tables().enhance(profile, current + 1);
        if (row == null) return Outcome.refused(equip_error.kEquipInternalError_VALUE);
        if (!rules.isEnhanceRowAvailable(row)) return Outcome.refused(equip_error.kEquipEnhanceUnavailable_VALUE);
        boolean material = row.getMaterialId() != 0 && row.getMaterialCount() != 0;
        ItemTable materialRow = rules.tables().item(row.getMaterialId());
        if (material && (materialRow == null || materialRow.getEquipKind() != 0
                || (profile != 0 && materialRow.getBattleUsable() != 0)))
            return Outcome.refused(equip_error.kEquipInternalError_VALUE);
        long count = Integer.toUnsignedLong(row.getMaterialCount());
        int materialTip = material ? quantityTip(p, row.getMaterialId(), count, equip_error.kEquipEnhanceMaterialNotEnough_VALUE) : 0;
        if (materialTip != 0) return Outcome.refused(materialTip);
        // 可交易装备强化 T/B 隔离：可交易石充足时始终仅扣可交易石（即使传 allow_bound_materials=true 也不误耗绑定石）；
        // 可交易石不足时默认拒回 kEquipBindConfirmRequired，确认后按实际消耗绑定堆传染。
        boolean equipCurrentlyBound = found.item().bound();
        boolean tradableOnly = false;
        if (material && !equipCurrentlyBound) {
            long tradableAvail = p.bags().bag(BagType.INVENTORY).plainTradableQuantity(row.getMaterialId());
            if (tradableAvail >= count) {
                tradableOnly = true;
            } else if (!allowBoundMaterials) {
                return Outcome.refused(equip_error.kEquipBindConfirmRequired_VALUE);
            }
        }
        if (row.getCostGold() < 0) return Outcome.refused(equip_error.kEquipEnhanceGoldNotEnough_VALUE);
        // 已绑定(B)装备强化允许优先消耗绑定银两；可交易(T)装备严格只扣流通银两(防绑定染毒与洗白)。
        var debit = equipCurrentlyBound
                ? currency.prepareGoldPreferBindDeduction(p, row.getCostGold())
                : currency.prepareDeduction(p, Wallet.GOLD, row.getCostGold());
        if (debit.tipId() != 0) return Outcome.refused(equip_error.kEquipEnhanceGoldNotEnough_VALUE);
        int pityLimit = row.getPityLimit();
        var roll = rules.rollEnhanceWithPity(row.getSuccessRate(), currentPity, pityLimit);
        boolean success = roll.success();
        int level = success ? current + 1 : (int) Math.max(0, current - Integer.toUnsignedLong(row.getFailLevelLoss()));
        PlayerBags next = candidate(p);
        List<Bag.Drawn> removed = List.of();
        boolean consumedBound = false;
        if (material) {
            Bag.DrainOutcome drain = next.bag(BagType.INVENTORY).drainPlainExact(row.getMaterialId(), count, tradableOnly);
            removed = drain.drawn();
            consumedBound = drain.consumedBound();
        }
        BagItem target = locate(next, id).item();
        target.equipment(target.equipment().toBuilder()
                .setEnhanceLevel(level)
                .setEnhancePityCount(roll.nextPityCount())
                .build());
        if (!equipCurrentlyBound && consumedBound) {
            target.setBound(true);
        }
        String extra = "{\"success\":" + success + ",\"from\":" + current + ",\"to\":" + level
                + (pityLimit > 0 ? ",\"pity\":" + roll.nextPityCount() + ",\"pity_triggered\":" + roll.triggeredByPity() : "")
                + "}";
        debit.commit(next, AssetAudit.Reason.EQUIP_ENHANCE, id, extra);
        if (clientRequestId != 0 && fingerprint != null) {
            p.clientRequestLedger().remember(ClientRequestRecordState.newBuilder()
                    .setKey(clientRequestId)
                    .setKind(PlayerClientRequestLedger.KIND_ENHANCE_EQUIP)
                    .setFingerprint(fingerprint)
                    .setEnhanceSuccess(success)
                    .setEnhanceLevel(level)
                    .setEnhancePityCount(roll.nextPityCount()));
        }
        bags.auditEquipment(p, removed, List.of(), AssetAudit.Reason.EQUIP_ENHANCE, id, extra);
        boolean changed = found.worn() && level != current;
        if (changed) attributes.equipmentChanged(p);
        return new Outcome(0, success, level, changed, false, roll.nextPityCount(), pityLimit);
    }
    public Outcome inlay(ScenePlayer p, long id, int socket, int config) {
        int gate = writable(p); if (gate != 0) return Outcome.refused(gate);
        Located found = locate(p.bags(), id); if (found.tip() != 0) return Outcome.refused(found.tip());
        // 对齐 C++ player_equip.cpp:1466：V1 装备（equip_profile != 0）未开放旧孔位镶嵌。
        if (found.row().getEquipProfile() != 0) return Outcome.refused(equip_error.kEquipGemSocketInvalid_VALUE);
        var state = found.item().equipment();
        if (state == null) return Outcome.refused(equip_error.kEquipGemSocketInvalid_VALUE);
        var gem = rules.tables().gem(config); ItemTable gemItem = rules.tables().item(config);
        if (gem == null || gemItem == null || gemItem.getEquipKind() != 0)
            return Outcome.refused(equip_error.kEquipGemNotGem_VALUE);
        var definition = rules.tables().sockets(found.row().getEquipKind()).stream().filter(s -> s.getSocket() == socket).findFirst().orElse(null);
        if (definition == null) return Outcome.refused(equip_error.kEquipGemSocketInvalid_VALUE);
        if (state.getGemsList().stream().anyMatch(g -> g.getSocket() == socket && g.getGemConfigId() != 0))
            return Outcome.refused(equip_error.kEquipGemSocketOccupied_VALUE);
        if (gem.getGemType() == 0 || definition.getGemType() != 0 && definition.getGemType() != gem.getGemType())
            return Outcome.refused(equip_error.kEquipGemTypeMismatch_VALUE);
        if (state.getGemsCount() >= 8) return Outcome.refused(equip_error.kEquipInternalError_VALUE);
        int gemTip = quantityTip(p, config, 1, equip_error.kEquipGemNotOwned_VALUE);
        if (gemTip != 0) return Outcome.refused(gemTip);
        // shortcut: EquipmentGemState lacks per-gem bound bit, upgrade when adding bound field to player_state.proto and C++ EquipGem
        if (p.bags().bag(BagType.INVENTORY).plainTradableQuantity(config) < 1L)
            return Outcome.refused(equip_error.kEquipGemNotOwned_VALUE);
        PlayerBags next = candidate(p);
        var removed = next.bag(BagType.INVENTORY).drainPlainExact(config, 1, true).drawn();
        locate(next, id).item().equipment(state.toBuilder().addGems(EquipmentGemState.newBuilder().setSocket(socket).setGemConfigId(config)).build());
        Outcome result = commit(p, next, found.worn(), true, 0);
        bags.auditEquipment(p, removed, List.of(), AssetAudit.Reason.EQUIP_INLAY, id, "{\"socket\":" + Integer.toUnsignedString(socket) + "}");
        return result;
    }
    public Outcome removeGem(ScenePlayer p, long id, int socket) {
        int gate = writable(p); if (gate != 0) return Outcome.refused(gate);
        Located found = locate(p.bags(), id); if (found.tip() != 0) return Outcome.refused(found.tip());
        var state = found.item().equipment();
        var gem = state == null ? null : state.getGemsList().stream().filter(g -> g.getSocket() == socket && g.getGemConfigId() != 0).findFirst().orElse(null);
        if (gem == null) return Outcome.refused(equip_error.kEquipGemSocketEmpty_VALUE);
        ItemTable row = rules.tables().item(gem.getGemConfigId());
        if (row == null || row.getEquipKind() != 0) return Outcome.refused(equip_error.kEquipInternalError_VALUE);
        PlayerBags next = candidate(p);
        var added = bags.prepareEquipmentReturn(p, next, gem.getGemConfigId());
        if (!added.ok()) return Outcome.refused(added.tip() == Bag.NO_SPACE ? equip_error.kEquipGemBagFull_VALUE : equip_error.kEquipInternalError_VALUE);
        locate(next, id).item().equipment(state.toBuilder().clearGems().addAllGems(state.getGemsList().stream().filter(g -> g.getSocket() != socket).toList()).build());
        Outcome result = commit(p, next, found.worn(), true, 0);
        bags.auditEquipment(p, List.of(), added.written(), AssetAudit.Reason.EQUIP_GEM_REMOVE, id, "{\"socket\":" + Integer.toUnsignedString(socket) + "}");
        boolean refunded = false;
        for (var record : state.getGemsList()) if (record.getSocket() == socket) {
            if (!refunded && record.getGemConfigId() == gem.getGemConfigId()) refunded = true;
            else log.error("取石清理同孔多余记录，未退还 player={} guid={} socket={} gem={}",
                    Long.toUnsignedString(p.playerId()), Long.toUnsignedString(id), Integer.toUnsignedString(socket), record);
        }
        return result;
    }
}
