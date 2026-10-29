package com.game.scene.equip;

import static org.assertj.core.api.Assertions.*;
import com.game.player.store.state.*;
import com.game.scene.audit.AssetAudit;
import com.game.scene.battle.BattleSnapshots;
import com.game.scene.player.*;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.world.WorldTestAccess;
import com.game.proto.BattleRouting;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EquipmentServiceTest {

    @Test void 零级旧档按一级穿戴_门槛仍拒绝高于有效等级的装备() {
        for (int storedLevel : new int[] {0, 1}) {
            var allowed = new EquipmentFixture();
            allowed.player.setLevel(storedLevel);
            long id = allowed.grant(1, 1);
            assertThat(allowed.equipment.equip(allowed.player, id).tip()).isZero();

            var refused = new EquipmentFixture(new RecordingAssetAudit(), 2);
            refused.player.setLevel(storedLevel);
            long higher = refused.grant(1, 1);
            var before = refused.player.bags().toState();
            assertThat(refused.equipment.equip(refused.player, higher).tip()).isEqualTo(28002);
            assertThat(refused.player.bags().toState()).isEqualTo(before);
        }
    }
    @Test void 新铸只掷一次_穿脱原样搬运_加成不落库且按比例保持血蓝() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); var raw = f.item(id).equipment(); int rolls = f.rolls;
        var before = f.player.attributes().derived();
        f.player.attributes().setHealth(before.maxHealth() / 2);
        assertThat(f.equipment.equip(f.player, id).tip()).isZero();
        assertThat(f.item(id).equipment()).isEqualTo(raw);
        assertThat(f.player.attributes().derived().physicalAttack()).isEqualTo(before.physicalAttack() + 40);
        assertThat(f.player.attributes().derived().maxHealth()).isEqualTo(before.maxHealth() + 100);
        assertThat(f.player.attributes().health()).isLessThanOrEqualTo(f.player.attributes().derived().maxHealth() / 2);
        assertThat(f.equipment.unequip(f.player, id).tip()).isZero();
        assertThat(f.player.attributes().derived()).isEqualTo(before);
        assertThat(f.player.attributes().health()).isLessThanOrEqualTo(before.maxHealth() / 2);
        assertThat(f.item(id).equipment()).isEqualTo(raw); assertThat(f.rolls).isEqualTo(rolls);
    }
    @Test void 两个槽满时替换最小槽_原物品和未知字段全部保留() {
        var f = new EquipmentFixture(); long a = f.grant(1, 1), b = f.grant(1, 1), c = f.grant(2, 1);
        f.equipment.equip(f.player, a); f.equipment.equip(f.player, b);
        var old = f.item(a).equipment();
        assertThat(f.equipment.equip(f.player, c).tip()).isZero();
        assertThat(f.player.bags().bag(BagType.EQUIPMENT).item(c).slot()).isZero();
        assertThat(f.player.bags().bag(BagType.INVENTORY).item(a).equipment()).isEqualTo(old);
        assertThat(f.player.bags().bag(BagType.EQUIPMENT).item(b)).isNotNull();
    }
    @Test void 强化成功和概率失败均扣款材料_失败掉级_只放大基础属性() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.fund(); f.equipment.equip(f.player, id);
        var initial = f.player.attributes().derived();
        var success = f.equipment.enhance(f.player, id);
        assertThat(success.tip()).isZero(); assertThat(success.success()).isTrue(); assertThat(success.enhanceLevel()).isEqualTo(1);
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(900);
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(10)).isEqualTo(8);
        assertThat(f.player.attributes().derived().physicalAttack()).isEqualTo(initial.physicalAttack() + 2);
        assertThat(f.player.attributes().derived().maxHealth()).isEqualTo(initial.maxHealth());
        f.randomValue = 9999;
        var failed = f.equipment.enhance(f.player, id);
        assertThat(failed.tip()).isZero(); assertThat(failed.success()).isFalse(); assertThat(failed.enhanceLevel()).isZero();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(700);
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(10)).isEqualTo(7);
        assertThat(f.player.attributes().derived()).isEqualTo(initial);
    }
    @Test void 纯校验失败不掷骰不扣款不改实例() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); int rolls = f.rolls;
        var original = f.player.bags().toState();
        assertThat(f.equipment.enhance(f.player, id).tip()).isEqualTo(28013);
        assertThat(f.player.bags().toState()).isEqualTo(original); assertThat(f.rolls).isEqualTo(rolls);
        f.grant(10, 2); original = f.player.bags().toState();
        assertThat(f.equipment.enhance(f.player, id).tip()).isEqualTo(28014);
        assertThat(f.player.bags().toState()).isEqualTo(original); assertThat(f.rolls).isEqualTo(rolls);
    }
    @Test void 镶嵌取下与战斗快照只带装备combat_不重复叠基础暴击() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.grant(11, 1); f.equipment.equip(f.player, id);
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isZero();
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(11)).isZero();
        assertThat(f.player.attributes().combat().getPhysicalCritRate()).isEqualTo(7);
        var snapshot = BattleSnapshots.build(f.player, f.world.tables, List.of(), BattleRouting.getDefaultInstance());
        assertThat(snapshot.getCombat().getPhysicalCritRate()).isEqualTo(7);
        long baseCrit = f.world.tables.classRow(f.player.classId()).getInitCritchance();
        assertThat(f.attributes.buildPanel(f.player).getCombatList()).singleElement().satisfies(c -> assertThat(c.getValue()).isEqualTo(baseCrit + 7));
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isEqualTo(28018);
        assertThat(f.equipment.removeGem(f.player, id, 0).tip()).isZero();
        assertThat(f.player.attributes().combat().getPhysicalCritRate()).isZero();
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(11)).isEqualTo(1);
    }
    @Test void 满包取石不清孔不丢宝石_有同配置未满堆时可以取下() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.grant(11, 1); f.equipment.equip(f.player, id);
        f.equipment.inlay(f.player, id, 0, 11); f.grant(1, f.player.bags().bag(BagType.INVENTORY).freeCells());
        var original = f.player.bags().toState();
        assertThat(f.equipment.removeGem(f.player, id, 0).tip()).isEqualTo(28021);
        assertThat(f.player.bags().toState()).isEqualTo(original);
        var stackState = original.toBuilder(); var first = stackState.getItems(0);
        stackState.setItems(0, first.toBuilder().setConfigId(11).setStackSize(1).clearEquipment());
        var replacement = PlayerBags.restore(stackState.build()); replacement.normalize(f.bags.tables()); f.player.bags().commitPrepared(replacement);
        assertThat(f.equipment.removeGem(f.player, id, 0).tip()).isZero();
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(11)).isEqualTo(2);
    }
    @Test void 空equip与完全缺失的presence不同_旧存档不初始化_未初始化不能强化() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.item(id).equipment(null); int rolls = f.rolls;
        var original = f.player.bags().toState(); assertThat(original.getItems(0).hasEquipment()).isFalse();
        var restored = PlayerBags.restore(original); restored.normalize(f.bags.tables()); f.player.bags().commitPrepared(restored);
        assertThat(f.equipment.enhance(f.player, id).tip()).isEqualTo(28011);
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isEqualTo(28017);
        assertThat(f.equipment.removeGem(f.player, id, 0).tip()).isEqualTo(28019);
        assertThat(f.rolls).isEqualTo(rolls);
        f.item(id).equipment(EquipmentState.getDefaultInstance());
        assertThat(f.player.bags().toState().getItems(0).hasEquipment()).isTrue();
    }
    @Test void 资产通道整实例跨玩家搬运保留嵌套未知字段_再次恢复不重掷() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1);
        var unknown = UnknownFieldSet.newBuilder().addField(99, UnknownFieldSet.Field.newBuilder().addVarint(123).build()).build();
        f.item(id).equipment(f.item(id).equipment().toBuilder().setEnhanceLevel(1).setUnknownFields(unknown)
                .addGems(EquipmentGemState.newBuilder().setSocket(0).setGemConfigId(11).setUnknownFields(unknown)).build());
        var original = f.item(id).equipment(); int rolls = f.rolls;
        var debit = f.bags.prepareDebit(f.player, Map.of(), List.of(id)); assertThat(debit.tip()).isZero();
        assertThat(debit.instances()).singleElement().satisfies(i -> assertThat(i.getEquipment()).isEqualTo(original));
        debit.commit(AssetAudit.Reason.TRADE, 77);
        var other = f.world.enter(21, 1002);
        var credit = f.bags.prepareCredit(other, Map.of(), debit.instances()); assertThat(credit.tip()).isZero(); credit.commit(AssetAudit.Reason.TRADE, 77);
        assertThat(other.bags().bag(BagType.INVENTORY).item(id).equipment()).isEqualTo(original);
        var restored = PlayerBags.restore(other.bags().toState()); restored.normalize(f.bags.tables());
        assertThat(restored.bag(BagType.INVENTORY).item(id).equipment()).isEqualTo(original); assertThat(f.rolls).isEqualTo(rolls);
    }
    @Test void 审计抛异常仍提交金币材料与装备同一候选() {
        var delegate = new RecordingAssetAudit();
        AssetAudit failing = new AssetAudit() {
            @Override public void currencyChanged(long player, int type, long delta, long before, long after, Reason reason, long correlation, String extra) {
                if (reason == Reason.EQUIP_ENHANCE) throw new IllegalStateException("审计不可用");
                delegate.currencyChanged(player, type, delta, before, after, reason, correlation, extra);
            }
            @Override public void itemGained(long p, long guid, int config, long count, Reason reason, long correlation, String extra) { }
            @Override public void itemDestroyed(long p, long guid, int config, long count, Reason reason, long correlation, String extra) {
                if (reason == Reason.EQUIP_ENHANCE) throw new IllegalStateException("审计不可用");
            }
        };
        var f = new EquipmentFixture(failing); long id = f.grant(1, 1); f.fund();
        assertThat(f.equipment.enhance(f.player, id).tip()).isZero();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(900);
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(10)).isEqualTo(8);
        assertThat(f.item(id).equipment().getEnhanceLevel()).isEqualTo(1);
    }
    @Test void 冻结与战斗拒绝所有装备写入_保留原资产() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.fund();
        f.world.fighting(f.player.playerId(), 7);
        var original = f.player.bags().toState();
        assertThat(f.equipment.equip(f.player, id).tip()).isEqualTo(28007);
        assertThat(f.equipment.enhance(f.player, id).tip()).isEqualTo(28007);
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isEqualTo(28007);
        assertThat(f.equipment.removeGem(f.player, id, 0).tip()).isEqualTo(28007);
        assertThat(f.player.bags().toState()).isEqualTo(original);
        assertThat(f.world.count("game.scene.battle.gate.rejects", "gate", "equipment")).isEqualTo(4);
        WorldTestAccess.startFreezing(f.player);
        assertThat(f.equipment.unequip(f.player, id).tip()).isEqualTo(28008);
        assertThat(f.world.count("game.scene.battle.gate.rejects", "gate", "equipment")).as("冻结优先，不误计战斗闸").isEqualTo(4);
    }
    @Test void 更新版本材料和宝石不可数量消费_干净单位充足时只扣干净堆() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.fund(); long gem = f.grant(11, 1);
        var unknown = UnknownFieldSet.newBuilder().addField(99, UnknownFieldSet.Field.newBuilder().addVarint(9).build()).build();
        var original = f.player.bags().toState().toBuilder();
        for (int i = 0; i < original.getItemsCount(); i++) if (original.getItems(i).getConfigId() == 10 || original.getItems(i).getItemUuid() == gem)
            original.setItems(i, original.getItems(i).toBuilder().setUnknownFields(unknown));
        var candidate = PlayerBags.restore(original.build()); candidate.normalize(f.bags.tables()); f.player.bags().commitPrepared(candidate);
        var protectedState = f.player.bags().toState(); int rolls = f.rolls;
        assertThat(f.equipment.enhance(f.player, id).tip()).isEqualTo(1006);
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isEqualTo(1006);
        assertThat(f.player.bags().toState()).isEqualTo(protectedState); assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(1000);
        assertThat(f.rolls).isEqualTo(rolls);
        f.grant(10, 2); f.grant(11, 1);
        assertThat(f.equipment.enhance(f.player, id).tip()).isZero();
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isZero();
        for (var raw : protectedState.getItemsList()) if (!raw.getUnknownFields().asMap().isEmpty())
            assertThat(f.player.bags().toState().getItemsList()).contains(raw);
    }
    @Test void 带装备段的材料不合并不扣除_整理仍保留整实例() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.fund();
        var material = f.player.bags().bag(BagType.INVENTORY).items().stream().filter(i -> i.configId() == 10).findFirst().orElseThrow();
        material.equipment(EquipmentState.newBuilder().setEnhanceLevel(9).build());
        long materialId = material.guid();
        f.grant(10, 2); f.bags.sortByPlayer(f.player, BagType.INVENTORY);
        assertThat(f.player.bags().bag(BagType.INVENTORY).item(materialId).size()).isEqualTo(10);
        assertThat(f.equipment.enhance(f.player, id).tip()).isZero();
        assertThat(f.player.bags().bag(BagType.INVENTORY).item(materialId).equipment().getEnhanceLevel()).isEqualTo(9);
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(10)).isEqualTo(10);
    }
    @Test void 同孔多余记录只退款一颗_保留其他孔与装备未知字段() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1);
        var unknown = UnknownFieldSet.newBuilder().addField(99, UnknownFieldSet.Field.newBuilder().addVarint(9).build()).build();
        var other = EquipmentGemState.newBuilder().setSocket(7).setGemConfigId(11).setUnknownFields(unknown).build();
        f.item(id).equipment(f.item(id).equipment().toBuilder().setUnknownFields(unknown)
                .addGems(EquipmentGemState.newBuilder().setSocket(0)).addGems(EquipmentGemState.newBuilder().setSocket(0).setGemConfigId(11))
                .addGems(EquipmentGemState.newBuilder().setSocket(0).setGemConfigId(11)).addGems(other).build());
        assertThat(f.equipment.removeGem(f.player, id, 0).tip()).isZero();
        assertThat(f.player.bags().bag(BagType.INVENTORY).total(11)).isEqualTo(1);
        assertThat(f.item(id).equipment().getGemsList()).containsExactly(other);
        assertThat(f.item(id).equipment().getUnknownFields()).isEqualTo(unknown);
    }
    @Test void 跨节点冻结快照保留整实例与owner围栏_目标新代恢复不重掷() throws Exception {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.fund(); f.equipment.enhance(f.player, id);
        f.grant(11, 1); f.equipment.inlay(f.player, id, 0, 11); f.equipment.equip(f.player, id);
        var unknown = UnknownFieldSet.newBuilder().addField(99, UnknownFieldSet.Field.newBuilder().addVarint(123).build()).build();
        f.item(id).equipment(f.item(id).equipment().toBuilder().setUnknownFields(unknown)
                .setAffixes(0, f.item(id).equipment().getAffixes(0).toBuilder().setUnknownFields(unknown)).build());
        var before = f.item(id).equipment(); int rolls = f.rolls;
        var handoff = f.world.freezeForHandOff(f.player);
        assertThat(handoff.frozen().ownerEpoch()).isEqualTo(1);
        assertThat(handoff.frozen().state().getBag().getItemsList()).anySatisfy(item -> {
            assertThat(item.getItemUuid()).isEqualTo(id); assertThat(item.getEquipment()).isEqualTo(before);
        });
        assertThat(f.equipment.enhance(f.player, id).tip()).isEqualTo(28008);
        var serialized = PlayerState.parseFrom(handoff.frozen().state().toByteArray());
        var target = new com.game.scene.battle.BattleFixture();
        var restored = target.enter(31, f.player.playerId(), 2, target.scene1, serialized);
        f.attributes.initializeOnLoad(restored);
        assertThat(restored.ownerEpoch()).isEqualTo(2);
        assertThat(restored.bags().bag(BagType.EQUIPMENT).item(id).equipment()).isEqualTo(before);
        assertThat(restored.wallet().balance(Wallet.GOLD)).isEqualTo(900);
        assertThat(restored.attributes().combat().getPhysicalCritRate()).isEqualTo(7);
        assertThat(f.rolls).isEqualTo(rolls);
    }
    @Test void 随机源故障发生在整批入包之前_不得留下已并入堆或已铸装备() {
        var f = new EquipmentFixture(); f.grant(10, 1); var original = f.player.bags().toState();
        ItemCatalog failing = new ItemCatalog() {
            @Override public ItemSpec item(int config) { return f.bags.tables().item(config); }
            @Override public List<Integer> equipSlots(int kind) { return f.bags.tables().equipSlots(kind); }
            @Override public EquipmentState createEquipment(int config) { throw new IllegalStateException("随机源故障"); }
        };
        assertThatThrownBy(() -> f.player.bags().bag(BagType.INVENTORY).add(Map.of(1, 1L, 10, 1L), failing,
                count -> new long[] {88})).isInstanceOf(IllegalStateException.class);
        assertThat(f.player.bags().toState()).isEqualTo(original);
    }
    @Test void 恢复战斗锁尚未完成时拒绝装备写入() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); var original = f.player.bags().toState();
        com.game.scene.battle.BattleFixture.setRecovery(f.player, com.game.scene.battle.PlayerBattle.Recovery.PENDING);
        assertThat(f.equipment.equip(f.player, id).tip()).isEqualTo(28007);
        assertThat(f.player.bags().toState()).isEqualTo(original);
        assertThat(f.world.count("game.scene.battle.gate.rejects", "gate", "equipment")).isEqualTo(1);
    }
    @Test void 业务拒绝码与校验顺序_未持有非装备无孔未持宝石及最高级() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1), material = f.grant(10, 1);
        var original = f.player.bags().toState();
        assertThat(f.equipment.equip(f.player, 77).tip()).isEqualTo(28000);
        assertThat(f.equipment.enhance(f.player, material).tip()).isEqualTo(28001);
        assertThat(f.equipment.unequip(f.player, id).tip()).isEqualTo(28005);
        assertThat(f.equipment.inlay(f.player, id, 0, 10).tip()).isEqualTo(28015);
        assertThat(f.equipment.inlay(f.player, id, -1, 11).tip()).isEqualTo(28017);
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isEqualTo(28016);
        assertThat(f.player.bags().toState()).isEqualTo(original);
        f.item(id).equipment(f.item(id).equipment().toBuilder().setEnhanceLevel(-1).build());
        assertThat(f.equipment.enhance(f.player, id).tip()).isEqualTo(28012);
        assertThat(f.item(id).equipment().getEnhanceLevel()).isEqualTo(-1);
    }
    @Test void 八条无效宝石记录也计入写入上限_不能再追加不会生效的记录() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.grant(11, 1);
        var raw = f.item(id).equipment().toBuilder();
        for (int i = 0; i < 8; i++) raw.addGems(EquipmentGemState.newBuilder().setSocket(i));
        f.item(id).equipment(raw.build()); var original = f.player.bags().toState();
        assertThat(f.equipment.inlay(f.player, id, 0, 11).tip()).isEqualTo(28010);
        assertThat(f.player.bags().toState()).isEqualTo(original);
    }
    @Test void 卸装满包零改动_不因卸装丢属性或回血() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.equipment.equip(f.player, id);
        f.grant(1, f.player.bags().bag(BagType.INVENTORY).freeCells());
        var original = f.player.bags().toState(); var derived = f.player.attributes().derived(); long health = f.player.attributes().health();
        assertThat(f.equipment.unequip(f.player, id).tip()).isEqualTo(28006);
        assertThat(f.player.bags().toState()).isEqualTo(original);
        assertThat(f.player.attributes().derived()).isEqualTo(derived); assertThat(f.player.attributes().health()).isEqualTo(health);
    }
    @Test void 候选准备后背包已变化或进入冻结必须拒绝_不扣金币() {
        var f = new EquipmentFixture(); f.fund();
        var debit = f.currency.prepareDeduction(f.player, Wallet.GOLD, 100);
        var candidate = PlayerBags.restore(f.player.bags().toState()); candidate.normalize(f.bags.tables());
        f.grant(11, 1); var changed = f.player.bags().toState();
        assertThatThrownBy(() -> debit.commit(candidate, AssetAudit.Reason.EQUIP_ENHANCE, 77, "")).isInstanceOf(IllegalStateException.class);
        assertThat(f.player.bags().toState()).isEqualTo(changed); assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(1000);
        var current = f.currency.prepareDeduction(f.player, Wallet.GOLD, 100);
        WorldTestAccess.startFreezing(f.player);
        assertThatThrownBy(() -> current.commit(candidate, AssetAudit.Reason.EQUIP_ENHANCE, 77, "")).isInstanceOf(IllegalStateException.class);
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(1000);
    }
}
