package com.game.trade.admin;

import static com.game.trade.admin.SeedEndpointTestApp.OPERATOR;
import static com.game.trade.admin.SeedEndpointTestApp.TOKEN;
import static com.game.trade.admin.SeedEndpointTestApp.post;
import static com.game.trade.admin.SeedEndpointTestApp.seed;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.trade.service.TradeServiceFixture;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 播种接口在 prod 运行模式下（接口总注册，trade-spec §4.8、§9.5）：鉴权通过后一律 403，计 seed rejected，<b>先于解析请求体</b>（坏请求体也是 403）、
 * 不碰库、不查归属区、不发号；鉴权失败照样先是 401 / 400。
 */
@SpringBootTest(classes = SeedEndpointTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=prod"})
class SeedListingEndpointProdTest {

    @LocalServerPort
    int port;

    @Autowired
    TradeServiceFixture f;

    @Test
    void 非dev_test一律403_先于解析_零IO不消耗号() throws Exception {
        HttpResponse<byte[]> valid = seed(port, TradeServiceFixture.seedRequest().build());
        HttpResponse<byte[]> garbage = post(port, SeedListingController.PATH, TOKEN, OPERATOR, new byte[] {(byte) 0xFF, 0x01});
        HttpResponse<byte[]> noToken = post(port, SeedListingController.PATH, null, OPERATOR, new byte[0]);

        assertThat(valid.statusCode()).isEqualTo(403);
        assertThat(garbage.statusCode()).isEqualTo(403);
        assertThat(noToken.statusCode()).as("令牌仍是第一道闸").isEqualTo(401);
        assertThat(f.seedResults("rejected")).isEqualTo(2);
        assertThat(f.store.calls).isEmpty();
        assertThat(f.homes.calls).isEmpty();
        assertThat(f.idCalls.get()).isZero();
        assertThat(f.meters.get("xm.trade.admin.requests").tag("op", "seed_listing").tag("status", "403").counter().count())
                .isEqualTo(2);
    }
}
