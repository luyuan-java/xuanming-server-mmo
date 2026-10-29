package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.state.InboxDecisionState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleOutcome;
import com.game.proto.player_inbox.*;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.BattleSettlementService;
import com.game.scene.battle.PlayerBattleService;
import com.game.scene.battle.SceneBattleTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.inbox.InboxConsumers;
import com.game.scene.inbox.InboxService;
import com.game.scene.inbox.InboxTransport;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.mission.MissionService;
import com.game.scene.mission.MissionTables;
import com.game.scene.pet.PetService;
import com.game.scene.pet.PetTables;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemGuids;
import com.game.scene.player.Wallet;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** 正式消费/资产/World回调的组合回归；内存仓库只控制回调，不冒实际SQL证据。 */
class BattleInboxLifecycleTest {
    private static final int DOMAIN = PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_BATTLE_SETTLEMENT_VALUE;
    private static final PlayerInboxMessageType TYPE = PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT;

    @Test void 旧SQL快照在途时零效果_同E重连继承槽_真实回调后才允许应用() {
        var f = new Fixture();
        ScenePlayer old = f.enter(PlayerState.getDefaultInstance());
        old.wallet().add(Wallet.GOLD, 1);
        assertThat(f.world.requestSave(old)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        var pending = f.repo.takeProgress();
        PlayerState before = old.persistentState();
        assertThat(f.battle.applyInbox(old, source(11, 10)).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(old.persistentState()).isEqualTo(before);

        ScenePlayer current = f.enter(old.persistedState());
        assertThat(current).isNotSameAs(old);
        assertThat(current.progressSaveInFlight()).isTrue();
        PlayerState carried = current.persistentState();
        assertThat(f.battle.applyInbox(current, source(11, 10)).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(current.persistentState()).isEqualTo(carried);
        pending.complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(current.progressSaveInFlight()).isFalse();
        assertThat(f.battle.applyInbox(current, source(11, 10)).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(current.wallet().balance(Wallet.GOLD)).isEqualTo(11);
        assertThat(f.repo.pendingProgress()).as("效果函数自身不启动存盘").isZero();
    }

    @Test void 无旧存盘时普通异常丢弃整实体_不存部分状态_同E只从原SQL重载() {
        var f = new Fixture();
        ScenePlayer old = f.enter(PlayerState.getDefaultInstance());
        PlayerState persisted = old.persistedState();
        f.failCurrencyAudit = true; // 注入货币已经改内存之后的普通异常。
        assertThat(f.battle.applyInbox(old, source(12, 10)).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(old.wallet().balance(Wallet.GOLD)).isEqualTo(10);
        assertThat(f.world.playerById(7)).isNull();
        assertThat(f.repo.pendingProgress()).isZero();
        assertThat(f.repo.saves()).isEmpty();
        assertThat(old.inboxLedger().decidedThrough(DOMAIN)).isZero();
        f.failCurrencyAudit = false;
        ScenePlayer reloaded = f.enter(persisted);
        assertThat(reloaded.wallet().balance(Wallet.GOLD)).isZero();
        assertThat(f.battle.applyInbox(reloaded, source(12, 10)).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(reloaded.wallet().balance(Wallet.GOLD)).isEqualTo(10);
    }

    @Test void 错误调用隔离时仍有旧SQL槽必须拒绝移除_不得打开同E重登窗口() {
        var f = new Fixture();
        ScenePlayer player = f.enter(PlayerState.getDefaultInstance());
        player.wallet().add(Wallet.GOLD, 1);
        f.world.requestSave(player);
        assertThatThrownBy(() -> f.world.discardUnpersistedBattleEffect(player, player.ownerEpoch(), new IllegalStateException()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.world.playerById(7)).isSameAs(player);
        assertThat(player.progressSaveInFlight()).isTrue();
    }

    @Test void 旧局结算不能改新局HP或冻结_个人150必须等本次SQL确认() {
        var f = new Fixture(); ScenePlayer player = f.enter(PlayerState.getDefaultInstance());
        var current = new com.game.scene.battle.BattleFreeze(999, 21, com.game.scene.battle.BattleFreeze.Phase.FIGHTING,
                f.clock.epochMillis() + 300_000, f.clock.epochMillis() + 60_000, true);
        BattleFixture.setFreeze(player, current);
        PlayerState before = player.persistentState();
        assertThat(f.battle.applyInbox(player, source(17, 10)).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(player.persistentState()).isEqualTo(before); assertThat(player.battle().freeze()).isSameAs(current);
        assertThat(f.sink.messageIdsTo(1, 11)).isEmpty();
        BattleFixture.setFreeze(player, null);
        f.fetch().reply(item(1, 17, source(17, 10)));
        assertThat(f.sink.messageIdsTo(1, 11)).doesNotContain(150);
        f.repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        f.logic.runAll();
        assertThat(f.sink.messageIdsTo(1, 11)).contains(150);
    }

    @Test void 资产与域水位同快照SAVED后才ACK_FAILED与时间流逝都不ACK() {
        var f = new Fixture();
        ScenePlayer player = f.enter(PlayerState.getDefaultInstance());
        f.fetch().reply(item(1, 13, source(13, 10)));
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(10);
        assertThat(player.inboxLedger().decidedThrough(DOMAIN)).isEqualTo(1);
        assertThat(f.transport.acks).isEmpty();
        var save = f.repo.takeProgress();
        assertThat(save.save().state().getInboxLedger().getCursors(0).getDecidedThroughSeq()).isEqualTo(1);
        save.complete(PlayerRepository.ProgressResult.FAILED);
        f.clock.advanceMillis(5001); f.inbox.tick();
        assertThat(f.transport.acks).isEmpty();
        f.repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(f.transport.acks).hasSize(1);
        assertThat(f.transport.acks.getFirst().getDecisions(0).getOutcome()).isEqualTo(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED);
        assertThat(player.persistedState().getCurrency().getBalances(Wallet.GOLD)).isEqualTo(10);
    }

    @Test void Redis丢失及最近环淘汰后原SQL域水位仍不重复金币消耗或击杀() {
        var f = new Fixture();
        ScenePlayer player = f.enter(PlayerState.getDefaultInstance());
        f.fetch().reply(item(1, 14, source(14, 10)));
        f.repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        for (int seq = 2; seq <= 40; seq++) assertThat(player.inboxLedger().record(DOMAIN, InboxDecisionState.newBuilder()
                .setSeq(seq).setRequestId(seq + 100).setMsgType(TYPE.getNumber())
                .setOutcome(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE).build())).isTrue();
        f.world.requestSave(player); f.repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        PlayerState stored = player.persistedState();
        assertThat(stored.getInboxLedger().getCursors(0).getRecentList()).noneMatch(d -> d.getSeq() == 1);
        // 不保留任何Battle Redis原件或进程内通知；新实体只带正式持久快照。
        var next = new Fixture();
        next.guids.set(Math.max(next.guids.get(), f.guids.get()));
        ScenePlayer reloaded = next.enter(stored);
        next.fetch().reply(item(1, 14, source(14, 10)));
        assertThat(reloaded.wallet().balance(Wallet.GOLD)).isEqualTo(10);
        assertThat(reloaded.inboxLedger().decidedThrough(DOMAIN)).isEqualTo(40);
        assertThat(next.repo.pendingProgress()).isZero();
    }

    @Test void 已转出或永久缺配置宝宝只跳子效果_铸号失败仍首副作用前DEFER() {
        var f = new Fixture(); ScenePlayer player = f.enter(PlayerState.getDefaultInstance());
        var missingPet = source(15, 10).toBuilder().addPets(BattlePetSettlementData.newBuilder().setPetId(9001)).build();
        assertThat(f.battle.applyInbox(player, missingPet).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(10);
        var configured = new Fixture();
        var pet = com.game.player.store.state.PetEntry.newBuilder().setPetId(9002).setPetTableId(999999).setLevel(1).setHealth(17).build();
        ScenePlayer retired = configured.enter(PlayerState.newBuilder().setPets(com.game.player.store.state.PetState.newBuilder().addPets(pet)).build());
        var beforePets=retired.pets().toState();
        assertThat(configured.battle.applyInbox(retired,source(18,10).toBuilder().addPets(BattlePetSettlementData.newBuilder().setPetId(9002)).build()).outcome())
                .isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(retired.wallet().balance(Wallet.GOLD)).isEqualTo(10);assertThat(retired.pets().toState()).isEqualTo(beforePets);
        var mint = new Fixture(); ScenePlayer blocked = mint.enter(PlayerState.getDefaultInstance());
        PlayerState before = blocked.persistentState();mint.failMint = true;
        var dropped = source(19, 10).toBuilder().addItemsGained(BattleItemEntry.newBuilder().setItemTableId(11).setCount(1)).build();
        assertThat(mint.battle.applyInbox(blocked, dropped).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(blocked.persistentState()).isEqualTo(before);
    }

    @Test void 当前battleUsable热更不抹原消耗_真GUID扣减且无关opaque逐字保留() {
        var f=new Fixture();ScenePlayer player=f.enter(PlayerState.getDefaultInstance());
        assertThat(f.bags.addItems(player,BagType.INVENTORY,java.util.Map.of(10,2L),AssetAudit.Reason.SYSTEM_GRANT,0,"").ok()).isTrue();
        var original=player.bags().toState();long consumedGuid=original.getItems(0).getItemUuid();
        var unknown=com.google.protobuf.UnknownFieldSet.newBuilder().addField(900,com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        var opaque=com.game.player.store.state.BagItemState.newBuilder().setItemUuid(700001).setConfigId(999999).setStackSize(1)
                .setBagType(BagType.INVENTORY.code()).setPos(1).setUnknownFields(unknown).build();
        var candidate=com.game.scene.player.PlayerBags.restore(original.toBuilder().addItems(opaque).build());candidate.normalize(f.bags.tables());player.bags().commitPrepared(candidate);
        var kept=player.bags().toState().getItemsList().stream().filter(i->i.getItemUuid()==700001).findFirst().orElseThrow();
        var hot=new SceneBattleTables(f.tables.fingerprint(),f.tables.skills(),f.tables.castableSkills(),java.util.Set.of(),f.tables.classes());
        var service=new BattleSettlementService(f.currency,f.bags,f.pets,f.missions,hot,SceneBattleMetrics.noop(),f.clock,184);
        var consumed=source(20,10).toBuilder().addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(10).setCount(1)).build();
        assertThat(service.applyInbox(f.world,player,consumed).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.bags().toState().getItemsList().stream().filter(i->i.getItemUuid()==consumedGuid).findFirst().orElseThrow().getStackSize()).isEqualTo(1);
        assertThat(player.bags().toState().getItemsList().stream().filter(i->i.getItemUuid()==700001).findFirst().orElseThrow()).isEqualTo(kept);
        var shortfall=source(21,0).toBuilder().addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(10).setCount(3)).build();
        assertThat(service.applyInbox(f.world,player,shortfall).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.bags().bag(BagType.INVENTORY).total(10)).isZero();
        assertThat(player.bags().toState().getItemsList().stream().filter(i->i.getItemUuid()==700001).findFirst().orElseThrow()).isEqualTo(kept);
    }

    @Test void 本人及全服金币封禁只跳金币_其它奖励消费水位同存盘_解封重登原ID不补发(){
        for(boolean global:List.of(false,true)){
            var f=new Fixture();var player=f.enter(PlayerState.getDefaultInstance());
            assertThat(f.bags.addItems(player,BagType.INVENTORY,java.util.Map.of(10,2L),AssetAudit.Reason.SYSTEM_GRANT,0,"").ok()).isTrue();
            assertThat(f.missions.accept(player,0,7)).isZero();
            if(global)f.currency.applyGlobalBlocks(new com.game.scene.gainblock.GlobalGainBlocks(java.util.Set.of(Wallet.GOLD),java.util.Set.of()));
            else assertThat(f.currency.block(player,Wallet.GOLD)).isZero();
            var settlement=source(24,10).toBuilder().addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(10).setCount(1))
                    .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(11).setCount(1))
                    .addDefeatedMonsters(com.game.proto.BattleMonsterDefeat.newBuilder().setMonsterConfigId(1).setCount(1)).build();
            f.fetch().reply(item(1,24,settlement));
            assertThat(player.wallet().balance(Wallet.GOLD)).isZero();
            assertThat(player.bags().bag(BagType.INVENTORY).total(10)).isEqualTo(1);
            assertThat(player.bags().bag(BagType.INVENTORY).total(11)).isEqualTo(1);
            assertThat(player.attributes().health()).isEqualTo(10);assertThat(player.attributes().mana()).isEqualTo(10);
            assertThat(player.missions().active(7).progress(0)).isEqualTo(1);
            assertThat(player.inboxLedger().decidedThrough(DOMAIN)).isEqualTo(1);assertThat(f.transport.acks).isEmpty();
            f.repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
            assertThat(f.transport.acks).singleElement().satisfies(ack->assertThat(ack.getDecisions(0).getOutcome()).isEqualTo(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED));
            var next=new Fixture();next.guids.set(f.guids.get());var reloaded=next.enter(player.persistedState());
            reloaded.wallet().unblock(Wallet.GOLD);var before=reloaded.persistentState();
            next.fetch().reply(item(1,24,settlement));assertThat(reloaded.persistentState()).isEqualTo(before);
            assertThat(reloaded.wallet().balance(Wallet.GOLD)).isZero();assertThat(next.repo.pendingProgress()).isZero();
        }
    }

    @Test void 金币溢出仍整笔延后_封禁不绕过整包候选失败(){
        var overflow=new Fixture();var player=overflow.enter(PlayerState.getDefaultInstance());
        player.wallet().add(Wallet.GOLD,Long.MAX_VALUE);var before=player.persistentState();
        assertThat(overflow.battle.applyInbox(player,source(25,1)).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(player.persistentState()).isEqualTo(before);
        var blocked=new Fixture();var other=blocked.enter(PlayerState.getDefaultInstance());other.wallet().block(Wallet.GOLD);blocked.failMint=true;
        var original=other.persistentState();var reward=source(26,10).toBuilder()
                .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(11).setCount(1)).build();
        assertThat(blocked.battle.applyInbox(other,reward).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(other.persistentState()).isEqualTo(original);
    }

    @Test void 消耗相关unknown实例与损坏宠物均在首副作用前拒绝() {
        var unknown=com.google.protobuf.UnknownFieldSet.newBuilder().addField(900,com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        var f=new Fixture();ScenePlayer player=f.enter(PlayerState.newBuilder().setBag(com.game.player.store.state.BagState.newBuilder()
                .addItems(com.game.player.store.state.BagItemState.newBuilder().setItemUuid(700002).setConfigId(10).setStackSize(2).setUnknownFields(unknown))).build());
        PlayerState before=player.persistentState();
        var value=source(22,10).toBuilder().addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(10).setCount(1)).build();
        assertThat(f.battle.applyInbox(player,value).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(player.persistentState()).isEqualTo(before);
        var pets=new Fixture();ScenePlayer invalid=pets.enter(PlayerState.newBuilder().setPets(com.game.player.store.state.PetState.newBuilder().setUnknownFields(unknown)).build());
        PlayerState beforePet=invalid.persistentState();
        assertThat(pets.battle.applyInbox(invalid,source(23,10)).outcome()).isEqualTo(BattleSettlementService.Outcome.DEFERRED);
        assertThat(invalid.persistentState()).isEqualTo(beforePet);
    }

    @Test void 自动领奖正常失败保claimable_金币消耗击杀与水位只一次_以后可手动领() {
        var f = new Fixture(); ScenePlayer player = f.enter(PlayerState.getDefaultInstance());
        assertThat(f.missions.accept(player, 0, 7)).isZero();
        assertThat(f.bags.addItems(player, BagType.INVENTORY, java.util.Map.of(10, 2L), AssetAudit.Reason.SYSTEM_GRANT, 0, "").ok()).isTrue();
        f.failMint = true;
        var settlement = source(16, 10).toBuilder().addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(10).setCount(1))
                .addDefeatedMonsters(com.game.proto.BattleMonsterDefeat.newBuilder().setMonsterConfigId(1).setCount(8)).build();
        f.fetch().reply(item(1, 16, settlement));
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(10);
        assertThat(player.bags().bag(BagType.INVENTORY).total(10)).isEqualTo(1);
        assertThat(player.missions().isComplete(7)).isTrue(); assertThat(player.missions().isClaimable(7)).isTrue();
        f.repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        var next = new Fixture();
        // 重登使用同一唯一号源的已分配上界，不能把已持久道具guid重新铸成任务奖品。
        next.guids.set(Math.max(next.guids.get(), f.guids.get()));
        ScenePlayer reloaded = next.enter(player.persistedState());
        PlayerState beforeReplay = reloaded.persistentState();
        next.fetch().reply(item(1, 16, settlement));
        assertThat(reloaded.persistentState()).isEqualTo(beforeReplay);
        assertThat(reloaded.missions().isClaimable(7)).isTrue();
        assertThat(next.missions.claim(reloaded, 0, 7)).isZero();
        assertThat(reloaded.missions().isClaimable(7)).isFalse();
        PlayerState afterClaim = reloaded.persistentState();
        assertThat(next.missions.claim(reloaded, 0, 7)).isNotZero();
        assertThat(reloaded.persistentState()).isEqualTo(afterClaim);
    }

    @Test void 未知物品表行不得被已知投影普通化扣减_其它配置和装备槽保持() {
        var unknown=com.google.protobuf.UnknownFieldSet.newBuilder().addField(900,com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        // 只覆写本用例关心的 Item 行，其余装备配置仍取完整正式表。
        var config=org.mockito.Mockito.spy(BattleFixture.CONFIG);
        var items=org.mockito.Mockito.mock(com.game.table.ItemRows.class);
        var rows=BattleFixture.CONFIG.item().all().stream().map(row->row.getId()==10?row.toBuilder().setUnknownFields(unknown).build():row).toList();
        org.mockito.Mockito.doReturn(items).when(config).item();org.mockito.Mockito.when(items.all()).thenReturn(rows);
        var tables=BagTables.from(config);assertThat(tables.hasUnknownItemSchema(10)).isTrue();assertThat(tables.hasUnknownItemSchema(11)).isFalse();
        assertThat(tables.item(10)).isEqualTo(BagTables.from(BattleFixture.CONFIG).item(10));
        assertThat(tables.equipSlots(1)).isEqualTo(BagTables.from(BattleFixture.CONFIG).equipSlots(1));
        var f=new Fixture();ScenePlayer player=f.enter(PlayerState.getDefaultInstance());
        assertThat(f.bags.addItems(player,BagType.INVENTORY,java.util.Map.of(10,2L),AssetAudit.Reason.SYSTEM_GRANT,0,"").ok()).isTrue();
        var before=player.persistentState();var bags=new BagService(tables,f.ids,f.audit,GainAnomalyDetector.off(),SceneMetrics.noop());
        assertThat(bags.prepareBattle(player,java.util.Map.of(10,1L),java.util.Map.of()).tip()).isNotZero();
        assertThat(player.persistentState()).isEqualTo(before);
        assertThat(bags.prepareBattle(player,java.util.Map.of(11,1L),java.util.Map.of()).tip()).isZero();
    }
    private static BattleSettlementData source(long battle, long gold) {
        return BattleSettlementData.newBuilder().setPlayerId(7).setBattleId(battle).setGoldGain(gold)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setHealth(10).setMana(10).build();
    }
    private static PlayerInboxItem item(long seq, long battle, BattleSettlementData source) {
        return PlayerInboxItem.newBuilder().setSeq(seq).setRequestId(battle).setMsgType(TYPE).setPayload(source.toByteString()).build();
    }
    private record Fetch(FetchPlayerInboxRequest request, BiConsumer<FetchPlayerInboxResponse, Throwable> callback) {
        void reply(PlayerInboxItem item) {
            callback.accept(FetchPlayerInboxResponse.newBuilder().setPlayerId(request.getPlayerId()).setDomain(request.getDomain())
                    .setCorrelationId(request.getCorrelationId()).setFence(PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_MATCH).addItems(item).build(), null);
        }
    }
    private static final class Transport implements InboxTransport {
        final List<Fetch> fetches = new ArrayList<>();
        final List<AckPlayerInboxRequest> acks = new ArrayList<>();
        public void fetch(FetchPlayerInboxRequest request, BiConsumer<FetchPlayerInboxResponse, Throwable> done) { fetches.add(new Fetch(request, done)); }
        public void ack(AckPlayerInboxRequest request, BiConsumer<AckPlayerInboxResponse, Throwable> done) { acks.add(request); }
    }
    private static final class Fixture {
        final ManualClock clock = new ManualClock();
        final ManualExecutor logic = new ManualExecutor();
        final FakePlayerRepository repo = new FakePlayerRepository();
        final RecordingSink sink = new RecordingSink();
        final Transport transport = new Transport();
        final AtomicLong guids = new AtomicLong(9000);
        boolean failCurrencyAudit, failMint;
        final AssetAudit audit = new AssetAudit() {
            public void currencyChanged(long p, int t, long d, long b, long a, Reason r, long id, String x) {
                if (failCurrencyAudit) throw new IllegalStateException("固定结算异常");
            }
            public void itemGained(long p, long g, int c, long q, Reason r, long id, String x) { }
            public void itemDestroyed(long p, long g, int c, long q, Reason r, long id, String x) { }
        };
        final ItemGuids ids = count -> {
            if (failMint) return new long[0];
            long[] out = new long[count]; for (int i = 0; i < count; i++) out[i] = guids.incrementAndGet(); return out;
        };
        final CurrencyService currency = new CurrencyService(audit, GainAnomalyDetector.off(), SceneMetrics.noop(), clock);
        final BagService bags = new BagService(BagTables.from(BattleFixture.CONFIG), ids, audit, GainAnomalyDetector.off(), SceneMetrics.noop());
        final PetService pets = new PetService(PetTables.from(BattleFixture.CONFIG), currency, ids, clock, new SplittableRandom(1), SceneMetrics.noop());
        final MissionService missions = new MissionService(MissionTables.from(BattleFixture.CONFIG), bags, clock);
        final SceneBattleTables tables = SceneBattleTables.from(BattleFixture.CONFIG);
        final BattleSettlementService settlements = new BattleSettlementService(currency, bags, pets, missions, tables, SceneBattleMetrics.noop(), clock, 184);
        final PlayerBattleService battle = new PlayerBattleService(new FakeBattleLocks(clock::epochMillis), settlements, tables, pets,
                player -> com.game.proto.BattleRouting.getDefaultInstance(), TeamFollow.NONE, SceneBattleMetrics.noop(), clock, logic, 144, 150);
        final InboxService inbox = new InboxService(InboxConsumers.withBattle(currency, battle), transport, clock,
                new InboxService.Settings(true, 30), () -> 9, () -> 500);
        final SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, guids::incrementAndGet, clock,
                SceneMetrics.noop(), player -> { bags.initializeOnLoad(player); pets.initializeOnLoad(player); missions.initializeOnLoad(player); },
                PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE, CrossNodeSwitch.DISABLED, SceneInstances.DISABLED,
                battle, ZoneTravel.DISABLED, inbox, new SceneAdmissionSource("9", "battle-inbox-unit", 1));
        final Scene scene = world.createScene(1);
        Fixture() {
            battle.attach(world); inbox.attach(world);
            battle.withInboxFailureHandler((player, failure) -> world.discardUnpersistedBattleEffect(player, player.ownerEpoch(), failure));
        }
        ScenePlayer enter(PlayerState state) {
            repo.put(new PlayerData(7, 1, 3, 1, "", 1, 1, new Vec3(180, 200, 0), state));
            world.onPlayerEnter(1, SceneWorldTest.enterFrame(11, 7, scene.sceneId(), 1).toBuilder()
                    .setAccount("原账号").setOwnerRevision(3).setLoginGeneration(1).build());
            repo.completeAll(); logic.runAll(); sink.clear(); return world.playerById(7);
        }
        Fetch fetch() {
            inbox.tick(); return transport.fetches.stream().filter(fetch -> fetch.request().getDomainValue() == DOMAIN).reduce((a, b) -> b).orElseThrow();
        }
    }
}
