package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 运维只读探针不能意外放开商城或内部支付接口。 */
class MallProbeAuthenticationTest {
    private final MallAuthentication filter = new MallAuthentication(
            new MallProperties(false, "g".repeat(32), "p".repeat(32), null, null), mock(MallNonceStore.class));

    @Test void 只读精确探针免交易验签() throws Exception {
        for (String group : new String[] {"readiness", "liveness"}) {
            var request = new MockHttpServletRequest("GET", "/actuator/health/" + group);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(chain.getRequest()).isSameAs(request);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test void 业务与伪装探针仍需签名() throws Exception {
        for (String path : new String[] {"/api/skus", "/internal/payments", "/actuator/health/readiness/", "/actuator/health/readiness"}) {
            var request = new MockHttpServletRequest("POST", path);
            request.setServletPath(path);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(chain.getRequest()).isNull();
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }
}
