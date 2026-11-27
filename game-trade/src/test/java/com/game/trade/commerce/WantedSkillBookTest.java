package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.proto.AssetBundle;
import com.game.proto.AssetItemInstance;
import com.game.proto.AssetSnapshot;
import com.game.proto.ItemComp;
import com.game.proto.SealedSkillBookData;
import com.game.proto.trade.CreateWantedSkillBookOrderRequest;
import com.game.proto.trade.FulfillWantedSkillBookOrderRequest;
import com.game.proto.trade.ListWantedSkillBookOrdersRequest;
import com.game.proto.trade.ListWantedSkillBookOrdersResponse;
import com.game.proto.trade.TradeCommerceLeg;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeRoleIdentity;
import com.game.proto.trade.TradeWantedSkillBookTerms;
import com.game.proto.trade.WantedSkillBookOrderPhase;
import com.game.proto.trade.WantedSkillBookOrderResponse;
import com.game.table.ConfigTables;
import com.game.trade.store.pb.TradeCommerceRow;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 悬赏求书（kind 7）全链路单测：税率、封卷道籍快照核对、状态机、先书后款、买家被拒全额退回与客户端协议。 */
class WantedSkillBookTest {

    private static final Policy POLICY = new Policy(Set.of(), Set.of(), Set.of(0), true, List.of());

    private static final WantedSkillBook.SkillCatalog TEST_CATALOG = new WantedSkillBook.SkillCatalog() {
        @Override
        public Optional<WantedSkillBook.SkillInfo> lookup(int skillTableId) {
            return switch (skillTableId) {
                case 5001 -> Optional.of(new WantedSkillBook.SkillInfo(1, "青木回春诀", "五行本源卷"));
                case 5002 -> Optional.of(new WantedSkillBook.SkillInfo(1, "烈火焚天诀", "五行本源卷"));
                case 5999 -> Optional.of(new WantedSkillBook.SkillInfo(9, "稀世禁术", "稀世卷"));
                default -> Optional.empty();
            };
        }

        @Override
        public boolean isEligible(int skillTableId) {
            return skillTableId == 5001 || skillTableId == 5002;
        }
    };

    private static AssetItemInstance sealedBookInstance(long itemUuid, int configId, int stackSize,
                                                        int skillId, int rarityTier, boolean bound,
                                                        String crafterName, long crafterId) {
        ItemComp item = ItemComp.newBuilder()
                .setItemId(itemUuid)
                .setConfigId(configId)
                .setSize(stackSize)
                .setBound(bound)
                .setSealedSkillBook(SealedSkillBookData.newBuilder()
                        .setSkillTableId(skillId)
                        .setScrollId(1)
                        .setRarityTier(rarityTier)
                        .setCrafterName(crafterName)
                        .setCrafterPlayerId(crafterId))
                .build();
        return AssetItemInstance.newBuilder()
                .setItemUuid(itemUuid)
                .setConfigId(configId)
                .setStackSize(stackSize)
                .setInstanceProto(item.toByteString())
                .build();
    }

    private static TradeCommerceRow.Builder newWantedRow(long commerceId, long posterId, long rewardGold) {
        return TradeCommerceRow.newBuilder()
                .setCommerceId(commerceId)
                .setKind(WantedSkillBook.KIND)
                .setStatus(ESCROWING)
                .setBuyerPlayerId(posterId)
                .setUnitPrice(rewardGold)
                .setCurrencyType(0)
                .setItemConfigId(WantedSkillBook.SEALED_SKILL_BOOK_CONFIG_ID);
    }

    private static TradeCommerceWorkflow.Builder newWantedWorkflow(int skillId, boolean requireSig, long rewardGold) {
        return TradeCommerceWorkflow.newBuilder()
                .setStage(WantedSkillBook.STAGE_ESCROW)
                .setWantedSkillBook(TradeWantedSkillBookTerms.newBuilder()
                        .setSkillTableId(skillId)
                        .setScrollId(1)
                        .setRequireMasterSignature(requireSig)
                        .setRewardGold(rewardGold)
                        .setSellerNetGold(WantedSkillBook.sellerNetGold(rewardGold))
                        .setPosterName("求书道友"));
    }

    @Test
    void 悬赏集市税5pct向上取整且实得相减一致() {
        assertThat(WantedSkillBook.taxGold(0L)).isZero();
        assertThat(WantedSkillBook.taxGold(100L)).isEqualTo(5L);
        assertThat(WantedSkillBook.sellerNetGold(100L)).isEqualTo(95L);
        // 101 * 500 = 50500 -> ceil(50500 / 10000) = 6
        assertThat(WantedSkillBook.taxGold(101L)).isEqualTo(6L);
        assertThat(WantedSkillBook.sellerNetGold(101L)).isEqualTo(95L);
        assertThat(WantedSkillBook.taxGold(50_000L)).isEqualTo(2_500L);
        assertThat(WantedSkillBook.sellerNetGold(50_000L)).isEqualTo(47_500L);
    }

    @Test
    void 封卷道籍快照核对覆盖绑定稀世技能错署名错与实例损坏() {
        TradeWantedSkillBookTerms plainTerms = TradeWantedSkillBookTerms.newBuilder()
                .setSkillTableId(5001).setRequireMasterSignature(false).build();
        TradeWantedSkillBookTerms signedTerms = TradeWantedSkillBookTerms.newBuilder()
                .setSkillTableId(5001).setRequireMasterSignature(true).build();

        AssetBundle validUnsigned = AssetBundle.newBuilder()
                .addItemInstances(sealedBookInstance(701L, 3414, 1, 5001, 0, false, "", 0L)).build();
        AssetBundle validSigned = AssetBundle.newBuilder()
                .addItemInstances(sealedBookInstance(702L, 3414, 1, 5001, 0, false, "玄机宗师", 88L)).build();
        AssetBundle boundBook = AssetBundle.newBuilder()
                .addItemInstances(sealedBookInstance(703L, 3414, 1, 5001, 0, true, "玄机宗师", 88L)).build();
        AssetBundle rareBook = AssetBundle.newBuilder()
                .addItemInstances(sealedBookInstance(704L, 3414, 1, 5001, 1, false, "玄机宗师", 88L)).build();
        AssetBundle wrongSkill = AssetBundle.newBuilder()
                .addItemInstances(sealedBookInstance(705L, 3414, 1, 5002, 0, false, "玄机宗师", 88L)).build();
        AssetBundle wrongConfig = AssetBundle.newBuilder()
                .addItemInstances(sealedBookInstance(706L, 3415, 1, 5001, 0, false, "玄机宗师", 88L)).build();

        assertThat(WantedSkillBook.bookMatches(validUnsigned, plainTerms)).isTrue();
        assertThat(WantedSkillBook.bookMatches(validUnsigned, signedTerms)).isFalse();
        assertThat(WantedSkillBook.bookMatches(validSigned, signedTerms)).isTrue();
        assertThat(WantedSkillBook.bookMatches(boundBook, plainTerms)).isFalse();
        assertThat(WantedSkillBook.bookMatches(rareBook, plainTerms)).isFalse();
        assertThat(WantedSkillBook.bookMatches(wrongSkill, plainTerms)).isFalse();
        assertThat(WantedSkillBook.bookMatches(wrongConfig, plainTerms)).isFalse();
    }

    @Test
    void 求购单全链路_托管挂榜_不符退书重挂_相符先书后款销毁5pct税() {
        var r = newWantedRow(9001L, 100L, 1_000L);
        var w = newWantedWorkflow(5001, true, 1_000L);

        // 1. 建单托管悬赏银两（1000 流通银两）
        CommerceRules.plan(r, w, 1_000L, POLICY);
        assertThat(WantedSkillBook.phase(r.build(), w.build()))
                .isEqualTo(WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_ESCROWING);
        var buyerDebit = CommerceRules.leg(w, "buyer_debit");
        assertThat(buyerDebit).isNotNull();
        assertThat(buyerDebit.getBundle().getCurrencies(0).getAmount()).isEqualTo(1_000L);

        // 托管成功 -> 挂榜 wanted_open / WAITING
        buyerDebit.setOpId(10L).setOutcome(2);
        CommerceRules.plan(r, w, 1_100L, POLICY);
        assertThat(w.getStage()).isEqualTo(WantedSkillBook.STAGE_OPEN);
        assertThat(r.getStatus()).isEqualTo(WAITING);
        assertThat(WantedSkillBook.phase(r.build(), w.build()))
                .isEqualTo(WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_OPEN);

        // 2. 第 1 次揭榜交来缺少署名的书 -> 原样退书（TX_AUCTION_RETURN=30）-> 退书到账后清零揭榜人回到挂榜
        r.setSellerPlayerId(200L).setStatus(ESCROWING);
        w.setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("a2").setOwnerRevision(1))
                .setQuantityReserved(1L).setStage(WantedSkillBook.STAGE_FILL);
        String key1 = CommerceEngine.key(WantedSkillBook.FULFILL_SCOPE, 200L, "req-fulfill-0001");
        String debitRole1 = WantedSkillBook.debitRole(1L, key1);
        CommerceRules.add(w, debitRole1, 200L, 3, AssetBundle.newBuilder().addItemUuids(801L).build());
        CommerceRules.leg(w, debitRole1)
                .setOpId(11L).setOutcome(2)
                .setSnapshot(AssetSnapshot.newBuilder()
                        .addItems(sealedBookInstance(801L, 3414, 1, 5001, 0, false, "", 0L)));

        CommerceRules.plan(r, w, 1_200L, POLICY);
        var ret1 = CommerceRules.leg(w, "seller_return_1");
        assertThat(ret1).isNotNull();
        assertThat(CommerceRules.legTxType(ret1)).isEqualTo(TX_AUCTION_RETURN);
        assertThat(r.getSellerPlayerId()).isEqualTo(200L);

        ret1.setOpId(12L).setOutcome(2);
        CommerceRules.plan(r, w, 1_300L, POLICY);
        assertThat(r.getSellerPlayerId()).isZero();
        assertThat(w.hasSellerIdentity()).isFalse();
        assertThat(w.getStage()).isEqualTo(WantedSkillBook.STAGE_OPEN);
        assertThat(r.getStatus()).isEqualTo(WAITING);

        // 3. 第 2 次揭榜交来带宗师署名的合规书 -> 进入交付，先书后款，卖家所得 950（50 税销毁）
        r.setSellerPlayerId(300L).setStatus(ESCROWING);
        w.setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("a3").setOwnerRevision(1))
                .setQuantityReserved(2L).setStage(WantedSkillBook.STAGE_FILL);
        String key2 = CommerceEngine.key(WantedSkillBook.FULFILL_SCOPE, 300L, "req-fulfill-0002");
        String debitRole2 = WantedSkillBook.debitRole(2L, key2);
        CommerceRules.add(w, debitRole2, 300L, 3, AssetBundle.newBuilder().addItemUuids(802L).build());
        CommerceRules.leg(w, debitRole2)
                .setOpId(13L).setOutcome(2)
                .setSnapshot(AssetSnapshot.newBuilder()
                        .addItems(sealedBookInstance(802L, 3414, 1, 5001, 0, false, "大宗师", 99L)));

        CommerceRules.plan(r, w, 1_400L, POLICY);
        assertThat(w.getStage()).isEqualTo("deliver");
        assertThat(r.getStatus()).isEqualTo(DELIVERING);
        var buyerReceive = CommerceRules.leg(w, "buyer_receive");
        var sellerReceive = CommerceRules.leg(w, "seller_receive");
        assertThat(buyerReceive).isNotNull();
        assertThat(sellerReceive).isNotNull();
        assertThat(sellerReceive.getBundle().getCurrencies(0).getAmount()).isEqualTo(950L);
        // 先书后款闸：书未到账前卖家所得压住不发
        assertThat(CommerceRules.legReadyToSend(w, buyerReceive)).isTrue();
        assertThat(CommerceRules.legReadyToSend(w, sellerReceive)).isFalse();

        // 书到账后放行卖家所得 -> 结案 SETTLED
        buyerReceive.setOpId(14L).setOutcome(2);
        assertThat(CommerceRules.legReadyToSend(w, sellerReceive)).isTrue();
        sellerReceive.setOpId(15L).setOutcome(2);
        CommerceRules.plan(r, w, 1_500L, POLICY);
        assertThat(w.getStage()).isEqualTo("done");
        assertThat(r.getStatus()).isEqualTo(SETTLED);
    }

    @Test
    void 发榜人收书被拒整单退回退全额悬赏而非95pct() {
        // 对齐 Go delivery_order_test.go TestWantedBuyerRejectedRefundsTheFullReward
        var r = newWantedRow(9002L, 100L, 1_000L).setSellerPlayerId(300L);
        var w = newWantedWorkflow(5001, false, 1_000L);
        CommerceRules.add(w, "buyer_debit", 100L, 3, WantedSkillBook.rewardBundle(1_000L));
        CommerceRules.leg(w, "buyer_debit").setOpId(20L).setOutcome(2);
        String key = CommerceEngine.key(WantedSkillBook.FULFILL_SCOPE, 300L, "req-fulfill-full");
        String debitRole = WantedSkillBook.debitRole(1L, key);
        CommerceRules.add(w, debitRole, 300L, 3, AssetBundle.newBuilder().addItemUuids(803L).build());
        CommerceRules.leg(w, debitRole)
                .setOpId(21L).setOutcome(2)
                .setSnapshot(AssetSnapshot.newBuilder()
                        .addItems(sealedBookInstance(803L, 3414, 1, 5001, 0, false, "", 0L)));
        w.setQuantityReserved(1L).setStage(WantedSkillBook.STAGE_FILL);

        // 进交付
        CommerceRules.plan(r, w, 2_000L, POLICY);
        assertThat(w.getStage()).isEqualTo("deliver");
        // 买家收书被拒（outcome=3），卖家所得从未发出
        CommerceRules.leg(w, "buyer_receive").setOpId(22L).setOutcome(3);

        CommerceRules.plan(r, w, 2_100L, POLICY);
        assertThat(w.getStage()).isEqualTo("return");
        assertThat(r.getStatus()).isEqualTo(CANCEL_PENDING);
        var sellerReturn = CommerceRules.leg(w, "seller_return");
        var buyerReturn = CommerceRules.leg(w, "buyer_return");
        assertThat(sellerReturn).isNotNull();
        assertThat(sellerReturn.getBundle().getItemInstances(0).getItemUuid()).isEqualTo(803L);
        assertThat(CommerceRules.legTxType(sellerReturn)).isEqualTo(TX_AUCTION_RETURN);
        assertThat(buyerReturn).isNotNull();
        // 关键：发榜人收回完整 1000 悬赏，绝不白丢 50 集市税
        assertThat(buyerReturn.getBundle().getCurrencies(0).getAmount()).isEqualTo(1_000L);
        assertThat(CommerceRules.legTxType(buyerReturn)).isEqualTo(TX_AUCTION_RETURN);

        sellerReturn.setOpId(23L).setOutcome(2);
        buyerReturn.setOpId(24L).setOutcome(2);
        CommerceRules.plan(r, w, 2_200L, POLICY);
        assertThat(w.getStage()).isEqualTo("done");
        assertThat(r.getStatus()).isEqualTo(CANCELLED);
    }

    @Test
    void 撤单仅允许发榜人在托管与挂榜阶段_揭榜人重放区分在途不符与未扣到() {
        // 1. 挂榜阶段发榜人请求撤单 -> buyer_return 退全额 -> CANCELLED
        var r = newWantedRow(9003L, 100L, 500L).setStatus(WAITING);
        var w = newWantedWorkflow(5001, false, 500L).setStage(WantedSkillBook.STAGE_OPEN);
        CommerceRules.add(w, "buyer_debit", 100L, 3, WantedSkillBook.rewardBundle(500L));
        CommerceRules.leg(w, "buyer_debit").setOpId(30L).setOutcome(2);
        w.setCancelRequested(true);
        r.setStatus(CANCEL_PENDING);
        CommerceRules.plan(r, w, 3_000L, POLICY);
        assertThat(w.getStage()).isEqualTo("return");
        assertThat(CommerceRules.leg(w, "buyer_return").getBundle().getCurrencies(0).getAmount()).isEqualTo(500L);

        // 2. 无状态校验与目录开关门禁
        CommerceStore store = mock(CommerceStore.class);
        CommerceEngine disabledEngine = new CommerceEngine(store, new AtomicLong(1)::incrementAndGet, POLICY, () -> 1000L);
        assertThatThrownBy(() -> disabledEngine.createWantedSkillBookOrder(100L, "",
                CreateWantedSkillBookOrderRequest.newBuilder()
                        .setClientRequestId("req-12345678").setSkillTableId(5001).setRewardGold(1000L).build(),
                Deadline.after(5_000)))
                .isInstanceOf(Refusal.class)
                .extracting(e -> ((Refusal) e).tip()).isEqualTo(WantedSkillBook.ERR_DISABLED);

        CommerceEngine enabledEngine = disabledEngine.withWantedSkills(TEST_CATALOG);
        // 悬赏额低于下限 100 或高于上限 50000、或技能非可求购 -> INVALID
        for (long badReward : List.of(99L, 50_001L)) {
            assertThatThrownBy(() -> enabledEngine.createWantedSkillBookOrder(100L, "",
                    CreateWantedSkillBookOrderRequest.newBuilder()
                            .setClientRequestId("req-12345678").setSkillTableId(5001).setRewardGold(badReward).build(),
                    Deadline.after(5_000)))
                    .isInstanceOf(Refusal.class)
                    .extracting(e -> ((Refusal) e).tip()).isEqualTo(INVALID);
        }
        assertThatThrownBy(() -> enabledEngine.createWantedSkillBookOrder(100L, "",
                CreateWantedSkillBookOrderRequest.newBuilder()
                        .setClientRequestId("req-12345678").setSkillTableId(5999).setRewardGold(500L).build(),
                Deadline.after(5_000)))
                .isInstanceOf(Refusal.class)
                .extracting(e -> ((Refusal) e).tip()).isEqualTo(INVALID);
    }

    @Test
    void 悬赏榜与我的求购单查询游标与配表加载校验() throws Exception {
        // 验证真实 config-data/tables 可加载为 SkillCatalog
        ConfigTables tables = ConfigTables.load(Path.of("../config-data/tables"));
        WantedSkillBook.SkillCatalog realCatalog = WantedSkillBook.SkillCatalog.fromTables(tables);
        assertThat(realCatalog.isEligible(0)).isFalse();

        // 客户端协议 ListWantedSkillBookOrders：公开榜按悬赏额降序、技能过滤与翻页游标
        CommerceStore store = mock(CommerceStore.class);
        TradeCommerceRow row1 = newWantedRow(201L, 100L, 2_000L).setStatus(WAITING).setCreatedMs(100L)
                .setWorkflowProto(newWantedWorkflow(5001, false, 2_000L).setStage(WantedSkillBook.STAGE_OPEN).build().toByteString())
                .build();
        TradeCommerceRow row2 = newWantedRow(202L, 101L, 1_000L).setStatus(WAITING).setCreatedMs(200L)
                .setWorkflowProto(newWantedWorkflow(5002, true, 1_000L).setStage(WantedSkillBook.STAGE_OPEN).build().toByteString())
                .build();
        when(store.list(anyString(), any(), any(), any(), any())).thenReturn(List.of(row1, row2));

        CommerceEngine engine = new CommerceEngine(store, new AtomicLong(1)::incrementAndGet, POLICY, () -> 5_000L)
                .withWantedSkills(TEST_CATALOG);
        CommerceClientService svc = new CommerceClientService(engine, (ids, d) -> Map.of());

        var resp = (ListWantedSkillBookOrdersResponse) svc.handle(
                "ListWantedSkillBookOrders", 100L,
                ListWantedSkillBookOrdersRequest.newBuilder().setFilterSkillTableId(5001).setLimit(2).build(),
                ListWantedSkillBookOrdersResponse.getDefaultInstance(),
                Deadline.after(5_000));
        assertThat(resp.getErrorMessage().getId()).isZero();
        assertThat(resp.getOrdersCount()).isEqualTo(1);
        assertThat(resp.getOrders(0).getOrderId()).isEqualTo("201");
        assertThat(resp.getOrders(0).getSkillName()).isEqualTo("青木回春诀");
        assertThat(resp.getOrders(0).getScrollName()).isEqualTo("五行本源卷");
        assertThat(resp.getOrders(0).getIsMine()).isTrue();
        // 扫满 limit=2 行，next_cursor 指向末行 (1000:202)
        assertThat(resp.getNextCursor()).isEqualTo("1000:202");

        // 游标编解码往返
        var parsedBoard = WantedSkillBook.BoardCursor.parse("1000:202").orElseThrow();
        assertThat(parsedBoard.rewardGold()).isEqualTo(1_000L);
        assertThat(parsedBoard.commerceId()).isEqualTo(202L);
        assertThat(WantedSkillBook.BoardCursor.parse("bad")).isEmpty();
        var parsedMy = WantedSkillBook.MyCursor.parse("170000:99").orElseThrow();
        assertThat(parsedMy.createdMs()).isEqualTo(170_000L);
        assertThat(parsedMy.commerceId()).isEqualTo(99L);
    }
}
