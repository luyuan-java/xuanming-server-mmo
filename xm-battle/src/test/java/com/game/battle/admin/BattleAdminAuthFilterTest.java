package com.game.battle.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 管理接口鉴权（同 xm-trade）：令牌没配 503、令牌错 401、缺 / 坏操作人 400、通过后放行；操作人按 UTF-8 还原、拒控制字符与超长。 */
class BattleAdminAuthFilterTest {

    private static MockHttpServletRequest request(String token, String operator) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", DevBattleController.CREATE);
        request.setServletPath(DevBattleController.CREATE);
        if (token != null) {
            request.addHeader(BattleAdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.addHeader(BattleAdminAuthFilter.OPERATOR_HEADER, operator);
        }
        return request;
    }

    private static int run(BattleAdminAuthFilter filter, MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    @Test
    void 令牌没配_一律503() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertThat(run(new BattleAdminAuthFilter(null), request("t", "op"), chain)).isEqualTo(503);
        assertThat(run(new BattleAdminAuthFilter(""), request("", "op"), new MockFilterChain())).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
        assertThat(new BattleAdminAuthFilter("").tokenConfigured()).isFalse();
    }

    @Test
    void 令牌错401_缺操作人400_通过后放行() throws Exception {
        BattleAdminAuthFilter filter = new BattleAdminAuthFilter("tok");
        assertThat(run(filter, request(null, "op"), new MockFilterChain())).isEqualTo(401);
        assertThat(run(filter, request("TOK", "op"), new MockFilterChain())).isEqualTo(401);
        assertThat(run(filter, request("tok", null), new MockFilterChain())).isEqualTo(400);
        MockFilterChain chain = new MockFilterChain();
        assertThat(run(filter, request("tok", "op"), chain)).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 操作人按UTF8还原_拒控制字符_超长_空白() {
        String chinese = new String("运维小王".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertThat(BattleAdminAuthFilter.operator(chinese)).isEqualTo("运维小王");
        assertThat(BattleAdminAuthFilter.operator("a\nb")).isNull();
        assertThat(BattleAdminAuthFilter.operator("x".repeat(65))).isNull();
        assertThat(BattleAdminAuthFilter.operator("x".repeat(64))).hasSize(64);
        assertThat(BattleAdminAuthFilter.operator("   ")).isNull();
        assertThat(BattleAdminAuthFilter.printable("a\nb")).isEqualTo("a?b");
    }
}
