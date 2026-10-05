package com.game.trade.admin;

import static com.game.trade.admin.SeedEndpointTestApp.OPERATOR;
import static com.game.trade.admin.SeedEndpointTestApp.TOKEN;
import static com.game.trade.admin.SeedEndpointTestApp.parse;
import static com.game.trade.admin.SeedEndpointTestApp.post;
import static com.game.trade.admin.SeedEndpointTestApp.seed;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.trade.SeedListingResponse;
import com.game.trade.rules.TradeTips;
import com.game.trade.service.TradeServiceFixture;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 播种接口在真的内嵌 Tomcat 上（dev 运行模式；trade-spec §4.8 状态映射、§9.5）：令牌错 401、缺操作人 400、请求体不是 SeedListingRequest 400、
 * 参数非法 / 卖家无归属区 200 + in-band、成功 200 带 {@code market_zone} 与非 0 编号、连调两次两个编号（不幂等）；「绕过形」路径
 * （{@code /admin;x=1/...}、{@code /%61dmin/...}）经容器规范化后照样要令牌。
 */
@SpringBootTest(classes = SeedEndpointTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=dev"})
class SeedListingEndpointTest {

    @LocalServerPort
    int port;

    @Autowired
    TradeServiceFixture f;

    @BeforeEach
    void reset() {
        f.store.calls.clear();
        f.store.insertedListings.clear();
        f.store.listings.clear();
        f.homes.calls.clear();
    }

    private double admin(String status) {
        return f.meters.get("xm.trade.admin.requests").tag("op", "seed_listing").tag("status", status).counter().count();
    }

    @Test
    void 成功_200带protobuf应答_market_zone是卖家归属区_每次一个新号() throws Exception {
        double before = admin("200");

        HttpResponse<byte[]> first = seed(port, TradeServiceFixture.seedRequest().build());
        f.nextListingId.set(5002);
        HttpResponse<byte[]> second = seed(port, TradeServiceFixture.seedRequest().build());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith(SeedListingController.CONTENT_TYPE));
        SeedListingResponse a = parse(first);
        SeedListingResponse b = parse(second);
        assertThat(a.hasErrorMessage()).isFalse();
        assertThat(a.getListingId()).isEqualTo(5001);
        assertThat(a.getMarketZone()).isEqualTo(TradeServiceFixture.ZONE_A);
        assertThat(b.getListingId()).as("不幂等").isEqualTo(5002);
        assertThat(f.store.insertedListings).hasSize(2);
        assertThat(admin("200") - before).isEqualTo(2);
    }

    @Test
    void 参数非法与卖家无归属区_200加in_band() throws Exception {
        HttpResponse<byte[]> invalid = seed(port, TradeServiceFixture.seedRequest().setPriceFen(0).build());
        HttpResponse<byte[]> unmapped = seed(port, TradeServiceFixture.seedRequest().setSellerPlayerId(TradeServiceFixture.PLAYER_U).build());
        HttpResponse<byte[]> empty = post(port, SeedListingController.PATH, TOKEN, OPERATOR, new byte[0]);

        assertThat(invalid.statusCode()).isEqualTo(200);
        assertThat(parse(invalid).getErrorMessage().getId()).isEqualTo(TradeTips.INVALID_PARAMETER);
        assertThat(parse(invalid).getErrorMessage().getParametersList()).isEmpty();
        assertThat(unmapped.statusCode()).isEqualTo(200);
        assertThat(parse(unmapped).getErrorMessage().getId()).isEqualTo(TradeTips.HOME_ZONE_UNKNOWN);
        assertThat(parse(unmapped).getListingId()).isZero();
        assertThat(empty.statusCode()).as("空请求体是合法的空消息：校验不过").isEqualTo(200);
        assertThat(parse(empty).getErrorMessage().getId()).isEqualTo(TradeTips.INVALID_PARAMETER);
        assertThat(f.store.insertedListings).isEmpty();
    }

    @Test
    void 令牌错401_缺操作人400_请求体不是SeedListingRequest400_过大400() throws Exception {
        byte[] body = TradeServiceFixture.seedRequest().build().toByteArray();

        assertThat(post(port, SeedListingController.PATH, null, OPERATOR, body).statusCode()).isEqualTo(401);
        assertThat(post(port, SeedListingController.PATH, "wrong", OPERATOR, body).statusCode()).isEqualTo(401);
        assertThat(post(port, SeedListingController.PATH, TOKEN, null, body).statusCode()).isEqualTo(400);
        assertThat(post(port, SeedListingController.PATH, TOKEN, OPERATOR, new byte[] {(byte) 0xFF, 0x01}).statusCode())
                .isEqualTo(400);
        assertThat(post(port, SeedListingController.PATH, TOKEN, OPERATOR, new byte[SeedListingController.MAX_BODY_BYTES + 1])
                .statusCode()).isEqualTo(400);
        assertThat(f.store.calls).isEmpty();
        assertThat(f.homes.calls).isEmpty();
    }

    @Test
    void 绕过形路径经容器规范化后照样要令牌() throws Exception {
        byte[] body = TradeServiceFixture.seedRequest().build().toByteArray();
        for (String path : new String[] {"/admin;x=1/trade/seed-listing", "/%61dmin/trade/seed-listing", "/admin/./trade/seed-listing",
                "/admin//trade/seed-listing", "/foo/../admin/trade/seed-listing"}) {
            HttpResponse<byte[]> response = post(port, path, null, OPERATOR, body);
            assertThat(response.statusCode()).as(path).isIn(400, 401);
        }
        assertThat(f.store.calls).isEmpty();
    }
}
