package com.game.scene.spiritshop;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PlayerSpiritShopState;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.SpiritShopGoodsPurchaseState;
import com.game.proto.BuySpiritShopGoodsRequest;
import com.game.proto.BuySpiritShopGoodsResponse;
import com.game.proto.GetSpiritShopInfoRequest;
import com.game.proto.GetSpiritShopInfoResponse;
import com.game.proto.MessageContent;
import com.game.proto.SpiritShopGoodsView;
import com.game.proto.SpiritShopItemEntry;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.bag.BagFeature;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerSpiritShop;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.world.ScenePlayer;
import com.game.table.SpiritShopGoodsTable;
import com.game.table.SpiritShopLimitGroupTable;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 官方灵石百宝阁 V1 全链路验收（509 查询 / 510 购买，对齐 {@code spirit-shop-and-starter-packs-v1.md}）。
 */
class SpiritShopFeatureAndServiceTest {

    private static final String SERVICE = SpiritShopFeature.SERVICE;

    @Test
    void 真实配表_首套共享组300封顶_绑定分堆防洗白与幂等重放() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001);

        // 预先在背包里放入可交易的 3001 与 3002，验证百宝阁绑定产物绝不与可交易堆合堆洗白
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(3001, 10L, 3002, 10L),
                false, Reason.GM_GRANT, 0L, "").ok()).isTrue();
        assertThat(f.currency.add(player, Wallet.DIAMOND, 400L, Reason.GM_GRANT).ok()).isTrue();
        assertThat(f.currency.add(player, Wallet.BOUND_DIAMOND, 500L, Reason.GM_GRANT).ok()).isTrue();

        // 1. 509 查询：9 件在售商品按 (tab_id, sort_order, goods_id) 排好，双余额分别展示
        GetSpiritShopInfoResponse infoResp = call(f, player, "GetSpiritShopInfo",
                GetSpiritShopInfoRequest.getDefaultInstance(), GetSpiritShopInfoResponse.parser());
        assertThat(infoResp.getErrorMessage().getId()).isZero();
        assertThat(infoResp.getInfo().getDiamondBalance()).isEqualTo(400L);
        assertThat(infoResp.getInfo().getBindDiamondBalance()).isEqualTo(500L);
        assertThat(infoResp.getInfo().getLimitGroupsList()).singleElement().satisfies(g -> {
            assertThat(g.getGroupId()).isEqualTo(1);
            assertThat(g.getMaxWeight()).isEqualTo(300);
            assertThat(g.getUsedWeight()).isZero();
            assertThat(g.getResetKind()).isZero();
        });
        assertThat(infoResp.getInfo().getGoodsList()).extracting(SpiritShopGoodsView::getGoodsId)
                .containsSubsequence(101, 102, 103, 201, 202, 301, 302, 303, 304)
                .doesNotContain(401, 402);

        SpiritShopGoodsView pack60 = goodsView(infoResp.getInfo().getGoodsList(), 101);
        SpiritShopGoodsView pack180 = goodsView(infoResp.getInfo().getGoodsList(), 102);
        SpiritShopGoodsView pack300 = goodsView(infoResp.getInfo().getGoodsList(), 103);

        // 2. 先买 101（60 份，60 灵石）-> 再买 102（180 份，180 灵石，累计 240/300）
        BuySpiritShopGoodsRequest buy101First = buyReq(9001L, pack60, 1);
        BuySpiritShopGoodsResponse r1 = call(f, player, "BuySpiritShopGoods", buy101First, BuySpiritShopGoodsResponse.parser());
        assertThat(r1.getErrorMessage().getId()).isZero();
        assertThat(r1.getReplayed()).isFalse();
        assertThat(r1.getInfo().getDiamondBalance()).isEqualTo(340L);
        assertThat(r1.getInfo().getBindDiamondBalance()).isEqualTo(500L);
        assertThat(r1.getInfo().getShopRevision()).isEqualTo(1L);
        assertThat(r1.getInfo().getLimitGroups(0).getUsedWeight()).isEqualTo(60);

        BuySpiritShopGoodsResponse r2 = call(f, player, "BuySpiritShopGoods", buyReq(9002L, pack180, 1),
                BuySpiritShopGoodsResponse.parser());
        assertThat(r2.getErrorMessage().getId()).isZero();
        assertThat(r2.getInfo().getLimitGroups(0).getUsedWeight()).isEqualTo(240);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(160L);

        // 3. 已用 240/300：再买 103（300 份）与 102（180 份）均应被共享组上限拒回 37005
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9003L, pack300, 1),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.GROUP_LIMIT_REACHED);
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9004L, pack180, 1),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.GROUP_LIMIT_REACHED);

        // 4. 再补 1 份 101（60 份）刚好封顶 300/300；继续买 101 拒回 37005
        BuySpiritShopGoodsRequest buy101Last = buyReq(9005L, pack60, 1);
        BuySpiritShopGoodsResponse r3 = call(f, player, "BuySpiritShopGoods", buy101Last, BuySpiritShopGoodsResponse.parser());
        assertThat(r3.getErrorMessage().getId()).isZero();
        assertThat(r3.getInfo().getLimitGroups(0).getUsedWeight()).isEqualTo(300);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(100L);
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9006L, pack60, 1),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.GROUP_LIMIT_REACHED);

        // 5. 绑定堆与可交易堆严格隔离：3001 与 3002 各保留 10 份可交易 + 新增 150 份绑定
        Bag inv = player.bags().bag(BagType.INVENTORY);
        assertThat(inv.plainTradableQuantity(3001)).isEqualTo(10L);
        assertThat(inv.plainTradableQuantity(3002)).isEqualTo(10L);
        assertThat(boundQuantity(inv, 3001)).isEqualTo(150L);
        assertThat(boundQuantity(inv, 3002)).isEqualTo(150L);

        // 6. 即使限购已满且余额清零，旧 client_request_id 幂等重放仍返回成功且 replayed=true、零额外扣费
        assertThat(f.currency.deduct(player, Wallet.DIAMOND, 100L, Reason.GM_DEDUCT).ok()).isTrue();
        BuySpiritShopGoodsResponse replay = call(f, player, "BuySpiritShopGoods", buy101Last, BuySpiritShopGoodsResponse.parser());
        assertThat(replay.getErrorMessage().getId()).isZero();
        assertThat(replay.getReplayed()).isTrue();
        assertThat(replay.getInfo().getLimitGroups(0).getUsedWeight()).isEqualTo(300);
        assertThat(boundQuantity(inv, 3001)).isEqualTo(150L);

        // 同 client_request_id 篡改参数回 kInvalidParameter(1005)；client_request_id=0 同样回 1005
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9005L, pack60, 2),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.INVALID_PARAMETER);
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(0L, pack60, 1),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.INVALID_PARAMETER);

        // 7. 审计流水带同一 correlation_id = client_request_id 与 TX_SPIRIT_SHOP_BUY（SPIRIT_SHOP_BUY）
        assertThat(f.audit.currencies).anySatisfy(c -> {
            assertThat(c.reason()).isEqualTo(Reason.SPIRIT_SHOP_BUY);
            assertThat(c.correlationId()).isEqualTo(9001L);
            assertThat(c.type()).isEqualTo(Wallet.DIAMOND);
            assertThat(c.delta()).isEqualTo(-60L);
            assertThat(c.extra()).contains("\"source\":\"spirit_shop\"", "\"goods_id\":101", "\"bound\":true");
        });
        assertThat(f.audit.items).filteredOn(i -> i.reason() == Reason.SPIRIT_SHOP_BUY && i.correlationId() == 9001L)
                .hasSize(2);
    }

    @Test
    void 严禁绑定灵石混扣_报价防呆_满包双物品原子回滚与封禁门禁() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1002);
        GetSpiritShopInfoResponse info = call(f, player, "GetSpiritShopInfo",
                GetSpiritShopInfoRequest.getDefaultInstance(), GetSpiritShopInfoResponse.parser());
        SpiritShopGoodsView pack60 = goodsView(info.getInfo().getGoodsList(), 101);
        SpiritShopGoodsView charm = goodsView(info.getInfo().getGoodsList(), 201);

        // 1. 仅持有 500 绑定灵石、0 灵石：买 60 灵石的定星保底符严拒 37006，绝不拿绑定灵石抵扣
        assertThat(f.currency.add(player, Wallet.BOUND_DIAMOND, 500L, Reason.GM_GRANT).ok()).isTrue();
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9101L, charm, 1),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.CURRENCY_NOT_ENOUGH);
        assertThat(player.wallet().balance(Wallet.BOUND_DIAMOND)).isEqualTo(500L);

        // 补足真实灵石
        assertThat(f.currency.add(player, Wallet.DIAMOND, 500L, Reason.GM_GRANT).ok()).isTrue();

        // 2. 报价防呆：价格、币种、物品数、绑定态任一不符均拒回 37002 且零扣费
        assertThat(call(f, player, "BuySpiritShopGoods",
                buyReq(9102L, charm, 1).toBuilder().setExpectedUnitPrice(charm.getUnitPrice() - 1).build(),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.PRICE_CHANGED);
        assertThat(call(f, player, "BuySpiritShopGoods",
                buyReq(9103L, charm, 1).toBuilder().setExpectedCurrencyType(Wallet.BOUND_DIAMOND).build(),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.PRICE_CHANGED);
        BuySpiritShopGoodsRequest wrongBound = buyReq(9104L, pack60, 1).toBuilder()
                .setExpectedItems(0, pack60.getItems(0).toBuilder().setBound(false)).build();
        assertThat(call(f, player, "BuySpiritShopGoods", wrongBound,
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.PRICE_CHANGED);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(500L);

        // 3. 非法份数（0 与超过 max_buy_per_request）与不存在/下架商品
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9105L, pack60, 0),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.INVALID_COUNT);
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9106L, pack60, pack60.getMaxBuyPerRequest() + 1),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.INVALID_COUNT);
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9107L, pack60, 1).toBuilder().setGoodsId(9999).build(),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.GOODS_OFF_SHELF);

        // 4. 个人物品获取封禁：封禁双物品里的第二个物品 3002 -> 整笔拒回 37010、零扣费、零发 3001
        assertThat(f.bags.blockItemGain(player, 3002)).isZero();
        assertThat(call(f, player, "BuySpiritShopGoods", buyReq(9108L, pack60, 1),
                BuySpiritShopGoodsResponse.parser()).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.ITEM_BLOCKED);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(500L);
        assertThat(player.bags().bag(BagType.INVENTORY).total(3001)).isZero();
        assertThat(f.bags.unblockItemGain(player, 3002)).isZero();

        // 5. 满包双物品原子回滚：把人物背包填到只剩 1 格空位（够放 3001、放不下 3002）-> 拒回 37007，零扣灵石、零发物、零占限额
        Bag inv = player.bags().bag(BagType.INVENTORY);
        int fillSlots = inv.capacity() - 1;
        // 找一个最大堆叠=1 的装备/道具填满 n-1 格
        int singleStackItemId = BattleFixture.CONFIG.item().all().stream()
                .filter(it -> it.getMaxStackSize() == 1)
                .findFirst().orElseThrow().getId();
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(singleStackItemId, (long) fillSlots),
                true, Reason.GM_GRANT, 0L, "").ok()).isTrue();
        assertThat(inv.items()).hasSize(fillSlots);

        BuySpiritShopGoodsResponse fullResp = call(f, player, "BuySpiritShopGoods", buyReq(9109L, pack60, 1),
                BuySpiritShopGoodsResponse.parser());
        assertThat(fullResp.getErrorMessage().getId()).isEqualTo(SpiritShopService.BAG_FULL);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(500L);
        assertThat(inv.total(3001)).isZero();
        assertThat(inv.total(3002)).isZero();
        assertThat(player.spiritShop().groupUsedWeight(1, PlayerSpiritShop.RESET_LIFETIME, 0L)).isZero();
        assertThat(player.spiritShop().shopRevision()).isZero();
    }

    @Test
    void 周限购抗时钟回拨_脏表failClosed_损坏存档拒买与存档往返保留未知字段() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1003);
        assertThat(f.currency.add(player, Wallet.DIAMOND, 1000L, Reason.GM_GRANT).ok()).isTrue();

        // 构造含合法周限购商品、合法周共享组以及一条重复双物品脏行的表
        SpiritShopLimitGroupTable weeklyGroup = SpiritShopLimitGroupTable.newBuilder()
                .setId(2).setName("每周限额").setResetKind(1).setMaxWeight(3).build();
        SpiritShopGoodsTable weeklyGoods = SpiritShopGoodsTable.newBuilder()
                .setId(501).setTabId(1).setSortOrder(1).setEnabled(1)
                .setName("周限购包").setCurrencyType(Wallet.DIAMOND).setPrice(10).setMaxBuyPerRequest(5)
                .setItemId1(3405).setItemCount1(1).setItemBound1(1)
                .setLimitResetKind(1).setLimitCount(2)
                .setLimitGroupId(2).setLimitGroupWeight(1)
                .build();
        SpiritShopGoodsTable duplicateItemsBadRow = SpiritShopGoodsTable.newBuilder()
                .setId(502).setTabId(1).setSortOrder(2).setEnabled(1)
                .setName("碰撞脏行").setCurrencyType(Wallet.DIAMOND).setPrice(10).setMaxBuyPerRequest(1)
                .setItemId1(3001).setItemCount1(1).setItemBound1(1)
                .setItemId2(3001).setItemCount2(1).setItemBound2(1)
                .build();
        SpiritShopTables customTables = new SpiritShopTables(
                List.of(weeklyGroup), List.of(weeklyGoods, duplicateItemsBadRow), BattleFixture.CONFIG.item().all());
        assertThat(customTables.sellableGoods(501)).isNotNull();
        assertThat(customTables.sellableGoods(502)).as("同 (item_id, bound) 碰撞行必须 fail-closed 过滤").isNull();

        SpiritShopService service = new SpiritShopService(customTables, f.bags, f.currency, f.clock, f.sceneMetrics);
        BagFeature bagFeature = new BagFeature(f.bags);
        // 2026-11-25 12:00 UTC+8
        long week1Ms = 1_795_579_200_000L;
        BuySpiritShopGoodsRequest buyTwo = BuySpiritShopGoodsRequest.newBuilder()
                .setClientRequestId(9201L).setGoodsId(501).setCount(2)
                .setExpectedCurrencyType(Wallet.DIAMOND).setExpectedUnitPrice(10)
                .addExpectedItems(SpiritShopItemEntry.newBuilder().setItemConfigId(3405).setItemCount(1).setBound(true))
                .build();
        assertThat(service.buy(f.world, player, buyTwo, bagFeature, week1Ms).getErrorMessage().getId()).isZero();
        // 本周已满 2/2 -> 再买 1 份拒 37004
        assertThat(service.buy(f.world, player, buyTwo.toBuilder().setClientRequestId(9202L).setCount(1).build(),
                bagFeature, week1Ms + 60_000L).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.LIMIT_REACHED);

        // 时钟回拨 14 天 -> 保守按已满算，严禁清零重置
        long rolledBackMs = week1Ms - 14L * 86_400_000L;
        assertThat(service.buy(f.world, player, buyTwo.toBuilder().setClientRequestId(9203L).setCount(1).build(),
                bagFeature, rolledBackMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.LIMIT_REACHED);

        // 正常推进到下一周（+7 天）-> 周限购重置，可再买 1 份
        long week2Ms = week1Ms + 7L * 86_400_000L;
        BuySpiritShopGoodsResponse nextWeekResp = service.buy(f.world, player,
                buyTwo.toBuilder().setClientRequestId(9204L).setCount(1).build(), bagFeature, week2Ms);
        assertThat(nextWeekResp.getErrorMessage().getId()).isZero();
        assertThat(nextWeekResp.getInfo().getGoods(0).getBoughtCount()).isEqualTo(1);
        assertThat(nextWeekResp.getInfo().getGoods(0).getNextResetMs()).isGreaterThan(week2Ms);

        // PlayerState 存档往返 + 未知字段保留
        PlayerState saved = com.game.scene.world.WorldTestAccess.persistentState(player);
        assertThat(saved.hasSpiritShop()).isTrue();
        PlayerSpiritShopState withUnknown = saved.getSpiritShop().toBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(777L).build())
                        .build())
                .build();
        PlayerSpiritShop restored = PlayerSpiritShop.restore(withUnknown);
        assertThat(restored.isValid()).isTrue();
        assertThat(restored.shopRevision()).isEqualTo(2L);
        assertThat(restored.toState().getUnknownFields().hasField(99)).isTrue();

        // 冻结状态购买回 kSpiritShopFrozen(37008)，509 查询仍放行
        com.game.scene.world.WorldTestAccess.startFreezing(player);
        assertThat(service.getInfo(player, week2Ms).getErrorMessage().getId()).isZero();
        assertThat(service.buy(f.world, player,
                buyTwo.toBuilder().setClientRequestId(9205L).setCount(1).build(), bagFeature, week2Ms)
                .getErrorMessage().getId()).isEqualTo(SpiritShopService.FROZEN);

        // 损坏存档（重复 goods_id）fail-closed 标记 invalid
        PlayerSpiritShopState corrupt = PlayerSpiritShopState.newBuilder()
                .addGoodsRecords(SpiritShopGoodsPurchaseState.newBuilder().setGoodsId(501).setLifetimeBought(1))
                .addGoodsRecords(SpiritShopGoodsPurchaseState.newBuilder().setGoodsId(501).setLifetimeBought(2))
                .build();
        PlayerSpiritShop invalidShop = PlayerSpiritShop.restore(corrupt);
        assertThat(invalidShop.isValid()).isFalse();
        assertThat(invalidShop.toState().getGoodsRecordsCount()).isEqualTo(2);
    }

    @Test
    void V2_定时上下架_等级解锁_四件大礼包原子回滚与多选一自选包全链路() {
        // 2027 春节礼盒定时窗：2027-02-05 00:00:00 +08:00 ~ 2027-02-20 23:59:59 +08:00
        long springStartMs = 1_801_756_800_000L;
        long springEndMs = 1_803_139_199_000L;

        // 1. V2 配表 fail-closed 拦截坏行（时间倒挂、原价<=现价、等级倒挂、跳槽填 item3、单物品设为多选一）
        SpiritShopGoodsTable badWindow = SpiritShopGoodsTable.newBuilder()
                .setId(601).setTabId(4).setSortOrder(1).setEnabled(1).setName("时间倒挂")
                .setCurrencyType(Wallet.DIAMOND).setPrice(100).setMaxBuyPerRequest(1)
                .setItemId1(3405).setItemCount1(1).setItemBound1(1)
                .setStartTimeMs(springEndMs).setEndTimeMs(springStartMs).build();
        SpiritShopGoodsTable badOriginalPrice = SpiritShopGoodsTable.newBuilder()
                .setId(602).setTabId(4).setSortOrder(2).setEnabled(1).setName("原价不高于现价")
                .setCurrencyType(Wallet.DIAMOND).setPrice(100).setOriginalPrice(100).setMaxBuyPerRequest(1)
                .setItemId1(3405).setItemCount1(1).setItemBound1(1).build();
        SpiritShopGoodsTable badLevelRange = SpiritShopGoodsTable.newBuilder()
                .setId(603).setTabId(4).setSortOrder(3).setEnabled(1).setName("等级倒挂")
                .setCurrencyType(Wallet.DIAMOND).setPrice(100).setMaxBuyPerRequest(1)
                .setItemId1(3405).setItemCount1(1).setItemBound1(1)
                .setMinLevel(30).setMaxLevel(20).build();
        SpiritShopGoodsTable badGap = SpiritShopGoodsTable.newBuilder()
                .setId(604).setTabId(4).setSortOrder(4).setEnabled(1).setName("跳槽填物品3")
                .setCurrencyType(Wallet.DIAMOND).setPrice(100).setMaxBuyPerRequest(1)
                .setItemId1(3001).setItemCount1(1).setItemBound1(1)
                .setItemId3(3405).setItemCount3(1).setItemBound3(1).build();
        SpiritShopGoodsTable badChooseOne = SpiritShopGoodsTable.newBuilder()
                .setId(605).setTabId(4).setSortOrder(5).setEnabled(1).setName("单物品自选")
                .setCurrencyType(Wallet.DIAMOND).setPrice(100).setMaxBuyPerRequest(1)
                .setGrantMode(SpiritShopTables.GRANT_MODE_CHOOSE_ONE)
                .setItemId1(3405).setItemCount1(1).setItemBound1(1).build();
        SpiritShopTables checkedBad = new SpiritShopTables(
                List.of(), List.of(badWindow, badOriginalPrice, badLevelRange, badGap, badChooseOne),
                BattleFixture.CONFIG.item().all());
        assertThat(checkedBad.sellableGoods()).isEmpty();

        // 2. 真实 2027 春节礼盒（401 / 402）定时窗与等级门槛
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1004);
        assertThat(f.currency.add(player, Wallet.DIAMOND, 1000L, Reason.GM_GRANT).ok()).isTrue();
        SpiritShopService service = new SpiritShopService(
                SpiritShopTables.from(BattleFixture.CONFIG), f.bags, f.currency, f.clock, f.sceneMetrics);
        BagFeature bagFeature = new BagFeature(f.bags);

        player.setLevel(20);
        // 开售前 1ms：隐藏 401/402，仅保留常态商品
        List<SpiritShopGoodsView> beforeWindowGoods = service.buildInfo(player, springStartMs - 1).getGoodsList();
        int everydayCount = beforeWindowGoods.size();
        assertThat(everydayCount).isGreaterThanOrEqualTo(9);
        assertThat(beforeWindowGoods).extracting(SpiritShopGoodsView::getGoodsId).doesNotContain(401, 402);

        // 先在活动期拉一份 401 与 402 报价
        List<SpiritShopGoodsView> inWindowGoods = service.buildInfo(player, springStartMs).getGoodsList();
        assertThat(inWindowGoods).hasSize(everydayCount + 2);
        SpiritShopGoodsView g401 = goodsView(inWindowGoods, 401);
        SpiritShopGoodsView g402 = goodsView(inWindowGoods, 402);
        assertThat(g401.getTabId()).isEqualTo(4);
        assertThat(g401.getUnitPrice()).isEqualTo(180L);
        assertThat(g401.getOriginalPrice()).isEqualTo(300L);
        assertThat(g401.getGrantMode()).isEqualTo(SpiritShopTables.GRANT_MODE_ALL);
        assertThat(g401.getItemsCount()).isEqualTo(4);
        assertThat(g402.getTabId()).isEqualTo(4);
        assertThat(g402.getUnitPrice()).isEqualTo(120L);
        assertThat(g402.getOriginalPrice()).isEqualTo(180L);
        assertThat(g402.getGrantMode()).isEqualTo(SpiritShopTables.GRANT_MODE_CHOOSE_ONE);
        assertThat(g402.getItemsCount()).isEqualTo(4);

        BuySpiritShopGoodsRequest buy401 = buyReq(9501L, g401, 1);
        // 开售前直接发包 -> 37011
        assertThat(service.buy(f.world, player, buy401, bagFeature, springStartMs - 1).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.NOT_IN_SALE_WINDOW);

        // 活动期内但等级 9 < min_level(10) -> 货架隐藏 + 购买回 37012
        player.setLevel(9);
        List<SpiritShopGoodsView> lowLevelGoods = service.buildInfo(player, springStartMs).getGoodsList();
        assertThat(lowLevelGoods).hasSize(everydayCount);
        assertThat(lowLevelGoods).extracting(SpiritShopGoodsView::getGoodsId).doesNotContain(401, 402);
        assertThat(service.buy(f.world, player, buy401, bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.LEVEL_NOT_MET);

        // 3. 满包 4 物品原子回滚：用独立满包角色把背包填到仅剩 3 格空位 -> 401 需要 4 格，必须整批回滚 37007 零扣费零发物
        player.setLevel(10);
        ScenePlayer fullBagPlayer = f.enter(11, 1005);
        fullBagPlayer.setLevel(10);
        assertThat(f.currency.add(fullBagPlayer, Wallet.DIAMOND, 1000L, Reason.GM_GRANT).ok()).isTrue();
        Bag fullInv = fullBagPlayer.bags().bag(BagType.INVENTORY);
        int fillSlots = fullInv.capacity() - 3;
        int singleStackItemId = BattleFixture.CONFIG.item().all().stream()
                .filter(it -> it.getMaxStackSize() == 1)
                .findFirst().orElseThrow().getId();
        assertThat(f.bags.addItems(fullBagPlayer, BagType.INVENTORY, Map.of(singleStackItemId, (long) fillSlots),
                true, Reason.GM_GRANT, 0L, "").ok()).isTrue();
        assertThat(service.buy(f.world, fullBagPlayer, buy401, bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.BAG_FULL);
        assertThat(fullBagPlayer.wallet().balance(Wallet.DIAMOND)).isEqualTo(1000L);
        assertThat(fullBagPlayer.bags().bag(BagType.INVENTORY).total(3001)).isZero();
        assertThat(fullBagPlayer.bags().bag(BagType.INVENTORY).total(3402)).isZero();

        // 4. ALL 礼包传非 0 chosen_item_index -> 37013；正常购买 -> 按折后价 180 扣费并发 4 件绑定物
        assertThat(service.buy(f.world, player, buy401.toBuilder().setClientRequestId(9502L).setChosenItemIndex(1).build(),
                bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.INVALID_CHOICE);

        BuySpiritShopGoodsResponse r401 = service.buy(f.world, player, buy401, bagFeature, springStartMs);
        assertThat(r401.getErrorMessage().getId()).isZero();
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(820L);
        Bag invAfter401 = player.bags().bag(BagType.INVENTORY);
        assertThat(boundQuantity(invAfter401, 3001)).isEqualTo(60L);
        assertThat(boundQuantity(invAfter401, 3002)).isEqualTo(60L);
        assertThat(boundQuantity(invAfter401, 3405)).isEqualTo(2L);
        assertThat(boundQuantity(invAfter401, 3402)).isEqualTo(1L);
        assertThat(service.buy(f.world, player, buy401.toBuilder().setClientRequestId(9503L).build(),
                bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.LIMIT_REACHED);

        // 5. 多选一自选包（402）：越界下标 37013、下标与 expected_items 错配 37002、仅校验选中项封禁、限购 2 份
        BuySpiritShopGoodsRequest buy402Choice1 = BuySpiritShopGoodsRequest.newBuilder()
                .setClientRequestId(9601L)
                .setGoodsId(402)
                .setCount(1)
                .setExpectedCurrencyType(g402.getCurrencyType())
                .setExpectedUnitPrice(g402.getUnitPrice())
                .setChosenItemIndex(1)
                .addExpectedItems(g402.getItems(1)) // 3412 x1
                .build();
        assertThat(service.buy(f.world, player, buy402Choice1.toBuilder().setClientRequestId(9600L).setChosenItemIndex(4).build(),
                bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.INVALID_CHOICE);
        assertThat(service.buy(f.world, player, buy402Choice1.toBuilder().setClientRequestId(9600L).setChosenItemIndex(0).build(),
                bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.PRICE_CHANGED);

        // 封禁未选中的候选项 3405（下标 3）：选下标 1（3412）不受影响照常买成，选下标 3 拒回 37010
        assertThat(f.bags.blockItemGain(player, 3405)).isZero();
        BuySpiritShopGoodsResponse r402c1 = service.buy(f.world, player, buy402Choice1, bagFeature, springStartMs);
        assertThat(r402c1.getErrorMessage().getId()).isZero();
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(700L);
        assertThat(boundQuantity(player.bags().bag(BagType.INVENTORY), 3412)).isEqualTo(1L);
        assertThat(player.bags().bag(BagType.INVENTORY).total(3408)).isZero();

        BuySpiritShopGoodsRequest buy402Choice3 = BuySpiritShopGoodsRequest.newBuilder()
                .setClientRequestId(9602L)
                .setGoodsId(402)
                .setCount(1)
                .setExpectedCurrencyType(g402.getCurrencyType())
                .setExpectedUnitPrice(g402.getUnitPrice())
                .setChosenItemIndex(3)
                .addExpectedItems(g402.getItems(3)) // 3405 x3
                .build();
        assertThat(service.buy(f.world, player, buy402Choice3, bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.ITEM_BLOCKED);
        assertThat(f.bags.unblockItemGain(player, 3405)).isZero();

        assertThat(service.buy(f.world, player, buy402Choice3, bagFeature, springStartMs).getErrorMessage().getId()).isZero();
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(580L);
        assertThat(boundQuantity(player.bags().bag(BagType.INVENTORY), 3405)).isEqualTo(5L); // 401 给的 2 + 402 选的 3

        // 同幂等键 9601 篡改下标 -> 1005；过期后新单拒回 37011，但旧 9601 仍幂等重放成功且零重复发物
        assertThat(service.buy(f.world, player,
                buy402Choice1.toBuilder().setChosenItemIndex(2).clearExpectedItems().addExpectedItems(g402.getItems(2)).build(),
                bagFeature, springStartMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.INVALID_PARAMETER);

        List<SpiritShopGoodsView> afterWindowGoods = service.buildInfo(player, springEndMs).getGoodsList();
        assertThat(afterWindowGoods).hasSize(everydayCount);
        assertThat(afterWindowGoods).extracting(SpiritShopGoodsView::getGoodsId).doesNotContain(401, 402);
        assertThat(service.buy(f.world, player, buy402Choice1.toBuilder().setClientRequestId(9699L).build(),
                bagFeature, springEndMs).getErrorMessage().getId())
                .isEqualTo(SpiritShopService.NOT_IN_SALE_WINDOW);

        BuySpiritShopGoodsResponse expiredReplay = service.buy(f.world, player, buy402Choice1, bagFeature, springEndMs + 1000L);
        assertThat(expiredReplay.getErrorMessage().getId()).isZero();
        assertThat(expiredReplay.getReplayed()).isTrue();
        assertThat(boundQuantity(player.bags().bag(BagType.INVENTORY), 3412)).isEqualTo(1L);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(580L);
    }

    private static SpiritShopGoodsView goodsView(List<SpiritShopGoodsView> list, int goodsId) {
        return list.stream().filter(g -> g.getGoodsId() == goodsId).findFirst().orElseThrow();
    }

    private static BuySpiritShopGoodsRequest buyReq(long reqId, SpiritShopGoodsView goods, int count) {
        return BuySpiritShopGoodsRequest.newBuilder()
                .setClientRequestId(reqId)
                .setGoodsId(goods.getGoodsId())
                .setCount(count)
                .setExpectedCurrencyType(goods.getCurrencyType())
                .setExpectedUnitPrice(goods.getUnitPrice())
                .addAllExpectedItems(goods.getItemsList())
                .build();
    }

    private static long boundQuantity(Bag bag, int configId) {
        long sum = 0L;
        for (BagItem item : bag.items()) {
            if (item.configId() == configId && item.bound()) {
                sum += item.size();
            }
        }
        return sum;
    }

    private static <T extends Message> T call(BattleFixture f, ScenePlayer player, String method,
                                              Message req, com.google.protobuf.Parser<T> parser)
            throws InvalidProtocolBufferException {
        int msgId = Contracts.REGISTRY.requireId(SERVICE, method);
        long reqId = f.request(player, msgId, req);
        MessageContent reply = f.pushes(player, msgId).stream()
                .filter(m -> m.getId() == reqId).findFirst().orElseThrow();
        return parser.parseFrom(reply.getSerializedMessage());
    }
}
