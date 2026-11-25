package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.trade.TradeAssetSelection;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeRoleIdentity;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PlayerTradeGuardAndReferencePriceTest {

    private static final int GOLD = CurrencyType.CURRENCY_TYPE_GOLD_VALUE;
    private static final int DIAMOND = CurrencyType.CURRENCY_TYPE_DIAMOND_VALUE;

    @Test
    void 中位参考价剔除同账号对敲与小样本并忽略过期成交() {
        long now = 10L * 24 * 3600 * 1000;
        var samples = new ArrayList<ReferencePrice.MarketFillSample>();
        // 8 笔正常样本 + 5 笔同账号对敲天价样本（应被剔除，有效样本 8 < 10 -> 样本不足）。
        for (int i = 0; i < 8; i++) {
            samples.add(new ReferencePrice.MarketFillSample(
                    3001, GOLD, 100L, 1, "seller-real", "buyer-real", now - (i + 1L) * 3600 * 1000));
        }
        for (int i = 0; i < 5; i++) {
            samples.add(new ReferencePrice.MarketFillSample(
                    3001, GOLD, 999_999L, 1, "wash-account", "wash-account", now - 1000));
        }
        var insufficient = ReferencePrice.compute(
                samples, 3001, GOLD, now, ReferencePrice.DEFAULT_REFERENCE_WINDOW_MS, 10);
        assertThat(insufficient.insufficientSample()).isTrue();
        assertThat(insufficient.sampleCount()).isEqualTo(8);
        assertThat(insufficient.medianUnitPrice()).isZero();

        // 再补 2 笔真实成交（120、140）以及 1 笔 8 天前过期成交（应忽略）。
        samples.add(new ReferencePrice.MarketFillSample(3001, GOLD, 120L, 1, "s1", "b1", now - 500));
        samples.add(new ReferencePrice.MarketFillSample(3001, GOLD, 140L, 1, "s2", "b2", now - 400));
        samples.add(new ReferencePrice.MarketFillSample(3001, GOLD, 5000L, 1, "s3", "b3", now - 8L * 24 * 3600 * 1000));
        var valid = ReferencePrice.compute(samples, 3001, GOLD, now, ReferencePrice.DEFAULT_REFERENCE_WINDOW_MS, 10);
        assertThat(valid.insufficientSample()).isFalse();
        assertThat(valid.sampleCount()).isEqualTo(10);
        assertThat(valid.medianUnitPrice()).isEqualTo(100L);
    }

    @Test
    void 方案A严格耗材模式放行白名单与限额内银两并拦截贵重实例与超额() throws Exception {
        long now = 1_760_000_000_000L;
        var guard = new PlayerTradeGuard(
                new PlayerTradeGuard.Policy(
                        PlayerTradeGuard.MODE_STRICT_CONSUMABLES,
                        Set.of(501, 502),
                        10_000L,
                        GOLD,
                        0L,
                        0,
                        0L,
                        false),
                null,
                null,
                (playerId, currencyType, dayStartMs) -> playerId == 101L ? 7_000L : 0L);

        // 允许：低价耗材 + 当日剩余额度内的银两
        var res = guard.evaluate(
                101L,
                202L,
                TradeAssetSelection.newBuilder().putItems(501, 5).setCurrencyType(GOLD).setCurrencyAmount(3_000L).build(),
                TradeAssetSelection.newBuilder().putItems(502, 2).build(),
                now);
        assertThat(res.requireCoolingLock()).isFalse();
        assertThat(res.reason()).isEqualTo("strict_consumables_ok");

        // 拒绝：超过单日累计银两上限（7000 + 3001 > 10000）
        assertRefusal(
                CommerceRules.NOT_TRADABLE,
                () -> guard.evaluate(
                        101L,
                        202L,
                        TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(3_001L).build(),
                        TradeAssetSelection.newBuilder().putItems(502, 1).build(),
                        now));

        // 拒绝：唯一装备实例、宠物、非耗材核心材料（如打造料 3001）
        assertRefusal(
                CommerceRules.NOT_TRADABLE,
                () -> guard.evaluate(
                        101L,
                        202L,
                        TradeAssetSelection.newBuilder().addItemUuids(90_001L).build(),
                        TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(1L).build(),
                        now));
        assertRefusal(
                CommerceRules.NOT_TRADABLE,
                () -> guard.evaluate(
                        101L,
                        202L,
                        TradeAssetSelection.newBuilder().setPetId(777L).build(),
                        TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(1L).build(),
                        now));
        assertRefusal(
                CommerceRules.NOT_TRADABLE,
                () -> guard.evaluate(
                        101L,
                        202L,
                        TradeAssetSelection.newBuilder().putItems(3001, 20).build(),
                        TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(1L).build(),
                        now));
    }

    @Test
    void 方案B熟人三道硬锁校验好友天数与参考价偏离并触发48小时冷静锁() throws Exception {
        long now = 1_760_000_000_000L;
        ReferencePrice.Reader prices = (cfgId, currency, ts) -> cfgId == 3001 && currency == GOLD
                ? new ReferencePrice.View(3001, GOLD, 1_000L, 20, false)
                : new ReferencePrice.View(cfgId, currency, 0L, 0, true);
        var basePolicy = new PlayerTradeGuard.Policy(
                PlayerTradeGuard.MODE_ACQUAINTANCE_LOCKED,
                Set.of(),
                0L,
                GOLD,
                PlayerTradeGuard.DEFAULT_MIN_FRIEND_DURATION_MS,
                50,
                PlayerTradeGuard.DEFAULT_COOLING_LOCK_DURATION_MS,
                true);

        // 1) 好友不足 7 天：禁止 1 银两换贵重材料
        var shortFriend = new PlayerTradeGuard(
                basePolicy,
                (a, b, ts) -> new PlayerTradeGuard.FriendshipStatus(
                        true, PlayerTradeGuard.DEFAULT_MIN_FRIEND_DURATION_MS - 1),
                prices,
                null);
        assertRefusal(
                CommerceRules.NOT_TRADABLE,
                () -> shortFriend.evaluate(
                        101L,
                        202L,
                        TradeAssetSelection.newBuilder().putItems(3001, 10).build(),
                        TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(1L).build(),
                        now));

        // 2) 满 7 天熟人 + 合理对价（10000 vs 8000，偏离 20% <= 50%）：直接放行不加冷静锁
        var longFriend = new PlayerTradeGuard(
                basePolicy,
                (a, b, ts) -> new PlayerTradeGuard.FriendshipStatus(
                        true, PlayerTradeGuard.DEFAULT_MIN_FRIEND_DURATION_MS),
                prices,
                null);
        var fair = longFriend.evaluate(
                101L,
                202L,
                TradeAssetSelection.newBuilder().putItems(3001, 10).build(),
                TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(8_000L).build(),
                now);
        assertThat(fair.requireCoolingLock()).isFalse();
        assertThat(fair.reason()).isEqualTo("acquaintance_fair_value_ok");

        // 3) 满 7 天熟人 + 严重偏离（1 银两换 10000 银两材料）：触发 48 小时冷静保护锁
        var deviated = longFriend.evaluate(
                101L,
                202L,
                TradeAssetSelection.newBuilder().putItems(3001, 10).build(),
                TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(1L).build(),
                now);
        assertThat(deviated.requireCoolingLock()).isTrue();
        assertThat(deviated.coolingLockDurationMs()).isEqualTo(PlayerTradeGuard.DEFAULT_COOLING_LOCK_DURATION_MS);

        // 4) 未开启熟人冷静锁时，严重偏离直接拒绝
        var strictAcquaintance = new PlayerTradeGuard(
                new PlayerTradeGuard.Policy(
                        PlayerTradeGuard.MODE_ACQUAINTANCE_LOCKED,
                        Set.of(),
                        0L,
                        GOLD,
                        PlayerTradeGuard.DEFAULT_MIN_FRIEND_DURATION_MS,
                        50,
                        PlayerTradeGuard.DEFAULT_COOLING_LOCK_DURATION_MS,
                        false),
                (a, b, ts) -> new PlayerTradeGuard.FriendshipStatus(
                        true, PlayerTradeGuard.DEFAULT_MIN_FRIEND_DURATION_MS),
                prices,
                null);
        assertRefusal(
                CommerceRules.NOT_TRADABLE,
                () -> strictAcquaintance.evaluate(
                        101L,
                        202L,
                        TradeAssetSelection.newBuilder().putItems(3001, 10).build(),
                        TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(1L).build(),
                        now));
    }

    @Test
    void sql单日限额追踪汇总买卖双侧并在坏字节或触顶时failClosed() throws Exception {
        long now = 1_760_000_000_000L;
        long dayStart = (now / PlayerTradeGuard.DAY_MS) * PlayerTradeGuard.DAY_MS;
        var sellerRows = new ArrayList<byte[]>();
        sellerRows.add(TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(3_500L))
                .setBuyerAssets(TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(9_999L))
                .build()
                .toByteArray());
        sellerRows.add(TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(TradeAssetSelection.newBuilder().setCurrencyType(DIAMOND).setCurrencyAmount(500L))
                .build()
                .toByteArray());
        var buyerRows = new ArrayList<byte[]>();
        buyerRows.add(TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(8_888L))
                .setBuyerAssets(TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(4_000L))
                .build()
                .toByteArray());

        var tracker = new PlayerTradeGuard.SqlDailyTracker(workflowQueryDataSource(sellerRows, buyerRows));
        long used = tracker.dailyCurrencyOutflow(101L, GOLD, dayStart);
        assertThat(used).isEqualTo(7_500L);

        // 接入 PlayerTradeGuard 后：剩余 2500 放行，2501 拦截（防拆单绕单日限额）。
        var guard = new PlayerTradeGuard(
                new PlayerTradeGuard.Policy(
                        PlayerTradeGuard.MODE_STRICT_CONSUMABLES,
                        Set.of(501),
                        10_000L,
                        GOLD,
                        0L,
                        0,
                        0L,
                        false),
                null,
                null,
                tracker);
        guard.evaluate(
                101L,
                202L,
                TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(2_500L).build(),
                TradeAssetSelection.newBuilder().putItems(501, 1).build(),
                now);
        assertRefusal(
                CommerceRules.NOT_TRADABLE,
                () -> guard.evaluate(
                        101L,
                        202L,
                        TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(2_501L).build(),
                        TradeAssetSelection.newBuilder().putItems(501, 1).build(),
                        now));

        // 溢出饱和保护 + 坏字节 fail-closed
        assertThat(PlayerTradeGuard.saturatingAddUnsigned(-11L, 20L)).isEqualTo(-1L);
        sellerRows.clear();
        sellerRows.add("not-a-valid-protobuf-wire".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> tracker.dailyCurrencyOutflow(101L, GOLD, dayStart))
                .isInstanceOf(SQLException.class);

        // 单侧触达扫描上限（1000 笔/日）直接饱和拒放
        byte[] oneGold = TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(TradeAssetSelection.newBuilder().setCurrencyType(GOLD).setCurrencyAmount(1L))
                .build()
                .toByteArray();
        sellerRows.clear();
        for (int i = 0; i < PlayerTradeGuard.MAX_DAILY_SCAN_ROWS; i++) {
            sellerRows.add(oneGold);
        }
        buyerRows.clear();
        assertThat(tracker.dailyCurrencyOutflow(101L, GOLD, dayStart)).isEqualTo(-1L);
    }

    @Test
    void sql参考价读取过滤同账号对敲并计算中位数() throws Exception {
        long now = 10L * 24 * 3600 * 1000;
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 10; i++) {
            byte[] wf = TradeCommerceWorkflow.newBuilder()
                    .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("seller-" + i).setOwnerRevision(1))
                    .setBuyerIdentity(TradeRoleIdentity.newBuilder().setAccount("buyer-" + i).setOwnerRevision(1))
                    .build()
                    .toByteArray();
            rows.add(new Object[] {Long.toUnsignedString(100L + i * 10L), "1", Long.toUnsignedString(now - 1000L), wf});
        }
        // 加 3 条同账号对敲高价单，应被过滤掉
        byte[] washWf = TradeCommerceWorkflow.newBuilder()
                .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("wash").setOwnerRevision(1))
                .setBuyerIdentity(TradeRoleIdentity.newBuilder().setAccount("wash").setOwnerRevision(1))
                .build()
                .toByteArray();
        for (int i = 0; i < 3; i++) {
            rows.add(new Object[] {"999999", "1", Long.toUnsignedString(now - 500L), washWf});
        }

        var reader = new ReferencePrice.SqlReader(fillQueryDataSource(rows));
        var view = reader.referencePrice(3001, GOLD, now);
        assertThat(view.insufficientSample()).isFalse();
        assertThat(view.sampleCount()).isEqualTo(10);
        // 100,110,120,130,140,150,160,170,180,190 -> (140 + 150) / 2 = 145
        assertThat(view.medianUnitPrice()).isEqualTo(145L);
    }

    @Test
    void 人民币卖家收入解析严格校验争议冻结金额不越界() throws Exception {
        var mapper = new ObjectMapper();
        var validNode = mapper.readTree("""
                {
                  "orderId": "00112233-4455-6677-8899-aabbccddeeff",
                  "grossFen": 1000,
                  "feeFen": 50,
                  "netFen": 950,
                  "reservedFen": 100,
                  "paidFen": 200,
                  "forfeitedFen": 50,
                  "disputeFrozenFen": 600,
                  "disputeCaseId": "CASE-001",
                  "availableMs": 123456
                }
                """);
        var parsed = RmbConsignmentClient.income(validNode);
        assertThat(parsed.getNetFen()).isEqualTo(950L);
        assertThat(parsed.getOrderId()).isEqualTo("00112233-4455-6677-8899-aabbccddeeff");

        // 超界争议冻结（100+200+50+601 > 950）必须 fail-closed 拒收
        var overflowNode = mapper.readTree("""
                {
                  "orderId": "00112233-4455-6677-8899-aabbccddeeff",
                  "grossFen": 1000,
                  "feeFen": 50,
                  "netFen": 950,
                  "reservedFen": 100,
                  "paidFen": 200,
                  "forfeitedFen": 50,
                  "disputeFrozenFen": 601,
                  "disputeCaseId": "CASE-001",
                  "availableMs": 123456
                }
                """);
        assertThatThrownBy(() -> RmbConsignmentClient.income(overflowNode))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("收入原件非法");
    }

    @Test
    void 配置启动校验拦截非法当面交易模式与超界卖家信用窗口() {
        var market = List.of(new CommerceProperties.Market(3001, GOLD, "玄铁"));
        assertThatThrownBy(() -> new CommerceProperties(
                true, Set.of(3001), Set.of(), Set.of(GOLD), false, market,
                new CommerceProperties.PlayerTrade("unknown_mode", Set.of(), 0L, GOLD, 0L, 50, 0L, false),
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommerceProperties(
                true, Set.of(3001), Set.of(), Set.of(GOLD), false, market,
                new CommerceProperties.PlayerTrade(PlayerTradeGuard.MODE_ACQUAINTANCE_LOCKED, Set.of(), 0L, GOLD, 0L, 100, 0L, false),
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommerceProperties(
                true, Set.of(3001), Set.of(), Set.of(GOLD), false, market,
                null,
                new CommerceProperties.SellerCredit(91L * 24 * 3600 * 1000, 0L, 1, 0L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommerceProperties(
                true, Set.of(3001), Set.of(), Set.of(GOLD), false, market,
                null,
                new CommerceProperties.SellerCredit(0L, 0L, 11, 0L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommerceProperties(
                true, Set.of(3001), Set.of(), Set.of(GOLD), false, market,
                null,
                new CommerceProperties.SellerCredit(0L, 0L, 1, 0L, 31L * 24 * 3600 * 1000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRefusal(int expectedTip, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(CommerceRules.Refusal.class)
                .extracting(e -> ((CommerceRules.Refusal) e).tip())
                .isEqualTo(expectedTip);
    }

    private static DataSource workflowQueryDataSource(List<byte[]> sellerRows, List<byte[]> buyerRows) {
        return stubDataSource(sql -> {
            List<byte[]> target = sql.contains("seller_player_id=?") ? sellerRows : buyerRows;
            int[] idx = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> ++idx[0] < target.size();
                        case "getBytes" -> target.get(idx[0]);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        });
    }

    private static DataSource fillQueryDataSource(List<Object[]> rows) {
        return stubDataSource(sql -> {
            int[] idx = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> ++idx[0] < rows.size();
                        case "getString" -> (String) rows.get(idx[0])[((Integer) args[0]) - 1];
                        case "getBytes" -> (byte[]) rows.get(idx[0])[((Integer) args[0]) - 1];
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        });
    }

    private static DataSource stubDataSource(java.util.function.Function<String, ResultSet> queryHandler) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (dsProxy, dsMethod, dsArgs) -> {
                    if (!"getConnection".equals(dsMethod.getName())) {
                        throw new UnsupportedOperationException(dsMethod.getName());
                    }
                    return Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (cProxy, cMethod, cArgs) -> switch (cMethod.getName()) {
                                case "prepareStatement" -> {
                                    String sql = (String) cArgs[0];
                                    yield Proxy.newProxyInstance(
                                            PreparedStatement.class.getClassLoader(),
                                            new Class<?>[] {PreparedStatement.class},
                                            (sProxy, sMethod, sArgs) -> switch (sMethod.getName()) {
                                                case "executeQuery" -> queryHandler.apply(sql);
                                                case "setQueryTimeout", "setInt", "setLong", "setObject", "close" -> null;
                                                default -> throw new UnsupportedOperationException(sMethod.getName());
                                            });
                                }
                                case "close" -> null;
                                default -> throw new UnsupportedOperationException(cMethod.getName());
                            });
                });
    }
}
