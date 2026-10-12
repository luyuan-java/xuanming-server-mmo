package com.game.trade.service;

import static com.game.trade.service.TradeServiceFixture.HOUR_MS;
import static com.game.trade.service.TradeServiceFixture.NOW_MS;
import static com.game.trade.service.TradeServiceFixture.PLAYER_C;
import static com.game.trade.service.TradeServiceFixture.PLAYER_U;
import static com.game.trade.service.TradeServiceFixture.budget;
import static com.game.trade.service.TradeServiceFixture.seedRequest;
import static com.game.trade.service.TradeServiceFixture.storeDown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.RunMode;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.trade.ListingPhase;
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SeedListingResponse;
import com.game.trade.rules.ListingRules;
import com.game.trade.rules.TradeLimits;
import com.game.trade.rules.TradeTips;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingStatuses;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * dev 播种的服务层单测（移植基线 admin_logic_test.go:25-155；trade-spec §9.2）：运行模式闸门、校验、卖家归属区、发号、插入，外加租约失效与预算到期。
 */
class SeedListingServiceTest {

    private final TradeServiceFixture f = new TradeServiceFixture();

    @Test
    void 非dev_test拒绝_计rejected_零IO不消耗号_seed也不越过闸门() {
        SeedListingService seeds = f.seeds(RunMode.PROD);

        assertThat(seeds.enabled()).isFalse();
        assertThat(seeds.admit()).isFalse();
        assertThat(f.seedResults("rejected")).isEqualTo(1);
        assertThatThrownBy(() -> seeds.seed(seedRequest().build(), budget())).isInstanceOf(IllegalStateException.class);

        assertThat(f.store.calls).isEmpty();
        assertThat(f.homes.calls).isEmpty();
        assertThat(f.idCalls.get()).as("被拒绝的调用不许消耗号").isZero();
    }

    @Test
    void test与dev档放行() {
        for (RunMode mode : List.of(RunMode.TEST, RunMode.DEV)) {
            TradeServiceFixture fx = new TradeServiceFixture();
            SeedListingService seeds = fx.seeds(mode);

            assertThat(seeds.admit()).isTrue();
            SeedListingResponse resp = seeds.seed(seedRequest().build(), budget());

            assertThat(resp.hasErrorMessage()).as(mode.name()).isFalse();
            assertThat(fx.store.insertedListings).hasSize(1);
            assertThat(fx.seedResults("rejected")).isZero();
        }
    }

    @Test
    void 校验不过in_band1005_计rejected_零IO() {
        List<Consumer<SeedListingRequest.Builder>> invalid = List.of(
                b -> b.setPriceFen(0),
                b -> b.setSellerPlayerId(0),
                b -> b.setSaleDurationMs(0),
                b -> b.setNoticeDurationMs(TradeLimits.MAX_NOTICE_DURATION_MS + 1),
                b -> b.setIconKey("A"),
                b -> b.setTitle("   "),
                b -> b.setSubcategory(6));
        for (int i = 0; i < invalid.size(); i++) {
            TradeServiceFixture fx = new TradeServiceFixture();
            SeedListingRequest.Builder in = seedRequest();
            invalid.get(i).accept(in);

            SeedListingResponse resp = fx.seeds(RunMode.DEV).seed(in.build(), budget());

            assertThat(resp.getErrorMessage().getId()).as("用例 %d", i).isEqualTo(TradeTips.INVALID_PARAMETER);
            assertThat(resp.getErrorMessage().getParametersList()).isEmpty();
            assertThat(resp.getListingId()).isZero();
            assertThat(fx.store.calls).isEmpty();
            assertThat(fx.homes.calls).isEmpty();
            assertThat(fx.idCalls.get()).isZero();
            assertThat(fx.seedResults("rejected")).isEqualTo(1);
        }
    }

    @Test
    void 成功_行字段逐个对_market_zone来自卖家归属区() {
        f.homes.zones.put(PLAYER_C, 7);
        SeedListingRequest in = seedRequest().setSellerPlayerId(PLAYER_C).setNoticeDurationMs(HOUR_MS).setSaleDurationMs(2 * HOUR_MS)
                .build();

        SeedListingResponse resp = f.seeds(RunMode.DEV).seed(in, budget());

        assertThat(resp.hasErrorMessage()).as("成功不设 error_message").isFalse();
        assertThat(resp.getListingId()).isEqualTo(5001);
        assertThat(resp.getMarketZone()).isEqualTo(7);
        assertThat(f.homes.calls).containsExactly(PLAYER_C);
        assertThat(f.idCalls.get()).isEqualTo(1);
        assertThat(f.seedResults("ok")).isEqualTo(1);
        assertThat(f.homeLookups("ok")).isEqualTo(1);

        assertThat(f.store.insertedListings).hasSize(1);
        Listing row = f.store.insertedListings.get(0);
        assertThat(row.listingId()).isEqualTo(5001);
        assertThat(row.sellerPlayerId()).isEqualTo(PLAYER_C);
        assertThat(row.sellerAccount()).isEmpty();
        assertThat(row.marketZone()).isEqualTo(7);
        assertThat(row.sellerZoneAtListing()).isEqualTo(7);
        assertThat(row.status()).isEqualTo(ListingStatuses.LISTED);
        assertThat(row.noticeEndMs()).as("notice_end = now + notice").isEqualTo(NOW_MS + HOUR_MS);
        assertThat(row.saleEndMs()).as("sale_end = notice_end + sale").isEqualTo(NOW_MS + 3 * HOUR_MS);
        assertThat(row.createdMs()).isEqualTo(NOW_MS);
        assertThat(row.updatedMs()).isEqualTo(NOW_MS);
        assertThat(row.version()).isZero();
        assertThat(row.title()).isEqualTo(in.getTitle());
        assertThat(row.category()).isEqualTo(in.getCategoryValue());
        assertThat(row.subcategory()).isEqualTo(in.getSubcategory());
        assertThat(row.priceFen()).isEqualTo(in.getPriceFen());
        assertThat(row.level()).isEqualTo(in.getLevel());
        assertThat(row.summary()).isEqualTo(in.getSummary());
        assertThat(row.description()).isEqualTo(in.getDescription());
        assertThat(row.iconKey()).isEqualTo(in.getIconKey());
        assertThat(ListingRules.phase(row, NOW_MS)).isEqualTo(ListingPhase.LISTING_PHASE_PUBLIC_NOTICE);
    }

    @Test
    void 无公示期立即寄售_标题原文不trim() {
        SeedListingResponse resp = f.seeds(RunMode.DEV).seed(seedRequest().setTitle("  带空白的标题 ").build(), budget());

        assertThat(resp.hasErrorMessage()).isFalse();
        Listing row = f.store.insertedListings.get(0);
        assertThat(row.noticeEndMs()).as("无公示期：notice_end 等于上架时刻").isEqualTo(NOW_MS);
        assertThat(ListingRules.phase(row, NOW_MS)).isEqualTo(ListingPhase.LISTING_PHASE_ON_SALE);
        assertThat(row.title()).isEqualTo("  带空白的标题 ");
    }

    @Test
    void 不幂等_每次调用一个新号() {
        SeedListingService seeds = f.seeds(RunMode.DEV);
        seeds.seed(seedRequest().build(), budget());
        f.nextListingId.set(5002);
        seeds.seed(seedRequest().build(), budget());

        assertThat(f.store.insertedListings).extracting(Listing::listingId).containsExactly(5001L, 5002L);
        assertThat(f.idCalls.get()).isEqualTo(2);
    }

    @Test
    void 失败分支矩阵() {
        record Case(String name, Consumer<TradeServiceFixture> prepare, Consumer<SeedListingRequest.Builder> mutate, int code,
                    boolean minted, String result) {
        }
        List<Case> cases = List.of(
                new Case("卖家归属区未映射", fx -> { }, b -> b.setSellerPlayerId(PLAYER_U), TradeTips.HOME_ZONE_UNKNOWN, false, "rejected"),
                new Case("归属区查询故障", fx -> fx.homes.failure = new DependencyException("boom"), b -> { },
                        TradeTips.SERVICE_UNAVAILABLE, false, "error"),
                new Case("租约失效（发号抛异常）", fx -> fx.idFailure = new IllegalStateException("listing_id 节点号租约无效"), b -> { },
                        TradeTips.SERVICE_UNAVAILABLE, true, "error"),
                new Case("发号返回 0", fx -> fx.nextListingId.set(0), b -> { }, TradeTips.SERVICE_UNAVAILABLE, true, "error"),
                new Case("插入故障", fx -> fx.store.failOn.put("InsertListing", storeDown()), b -> { },
                        TradeTips.SERVICE_UNAVAILABLE, true, "error"));
        for (Case c : cases) {
            TradeServiceFixture fx = new TradeServiceFixture();
            c.prepare().accept(fx);
            SeedListingRequest.Builder in = seedRequest();
            c.mutate().accept(in);

            SeedListingResponse resp = fx.seeds(RunMode.DEV).seed(in.build(), budget());

            assertThat(resp.getErrorMessage().getId()).as(c.name()).isEqualTo(c.code());
            assertThat(resp.getErrorMessage().getParametersList()).as(c.name()).isEmpty();
            assertThat(resp.getListingId()).as(c.name()).isZero();
            assertThat(resp.getMarketZone()).as(c.name()).isZero();
            assertThat(fx.idCalls.get() > 0).as(c.name()).isEqualTo(c.minted());
            assertThat(fx.store.insertedListings).as(c.name()).isEmpty();
            assertThat(fx.seedResults(c.result())).as(c.name()).isEqualTo(1);
        }
    }

    @Test
    void 插入卡住时预算到期in_band1003() {
        f.store.blockOn.add("InsertListing");

        long start = System.nanoTime();
        SeedListingResponse resp = f.seeds(RunMode.DEV).seed(seedRequest().build(), Deadline.after(300));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.getErrorMessage().getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(elapsedMs).isLessThan(TradeServiceFixture.BUDGET_MS);
        assertThat(f.store.deadlines).hasSize(1);
        assertThat(f.homes.deadlines.get(0)).as("归属区与插入共用一个预算").isSameAs(f.store.deadlines.get(0));
    }
}
