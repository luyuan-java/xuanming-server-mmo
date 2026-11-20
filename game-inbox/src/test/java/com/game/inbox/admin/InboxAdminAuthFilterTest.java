package com.game.inbox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class InboxAdminAuthFilterTest {

    private static String asTomcatHeader(String utf8) {
        return new String(utf8.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static MockHttpServletRequest request(String token, String operator) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/inbox/players/1/summary");
        request.setServletPath("/admin/inbox/players/1/summary");
        if (token != null) {
            request.addHeader(InboxAdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.addHeader(InboxAdminAuthFilter.OPERATOR_HEADER, operator);
        }
        return request;
    }

    @Test
    void 令牌与操作人按码点上限校验_并仅在链路内设置认证属性() throws Exception {
        var filter = new InboxAdminAuthFilter("secret");
        var unauthenticated = request("secret", "ops");
        assertThatThrownBy(() -> InboxAdminAuthFilter.authenticatedOperator(unauthenticated))
                .isInstanceOf(ResponseStatusException.class);

        // 30 个中文字符 = 90 字节、30 个 Unicode 码点：与其余五个 AdminAuthFilter 一致放行
        String thirtyChinese = "运".repeat(30);
        assertThat(InboxAdminAuthFilter.operator(asTomcatHeader(thirtyChinese))).isEqualTo(thirtyChinese);
        assertThat(InboxAdminAuthFilter.operator(asTomcatHeader("运".repeat(64)))).hasSize(64);
        assertThat(InboxAdminAuthFilter.operator(asTomcatHeader("运".repeat(65)))).isNull();
        assertThat(InboxAdminAuthFilter.operator("x".repeat(65))).isNull();
        assertThat(InboxAdminAuthFilter.operator("a\nb")).isNull();

        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockHttpServletRequest req = request("secret", asTomcatHeader(thirtyChinese));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (r, w) -> seenInChain.set(InboxAdminAuthFilter.authenticatedOperator((HttpServletRequest) r));

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(seenInChain.get()).isEqualTo(thirtyChinese);
        // 链路结束后移除属性，防止复用请求残留认证态
        assertThatThrownBy(() -> InboxAdminAuthFilter.authenticatedOperator(req))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 令牌缺失503_令牌错401_非法操作人400() throws Exception {
        MockHttpServletResponse r503 = new MockHttpServletResponse();
        new InboxAdminAuthFilter("").doFilter(request("secret", "ops"), r503, (a, b) -> { });
        assertThat(r503.getStatus()).isEqualTo(503);

        var filter = new InboxAdminAuthFilter("secret");
        MockHttpServletResponse r401 = new MockHttpServletResponse();
        filter.doFilter(request("wrong", "ops"), r401, (a, b) -> { });
        assertThat(r401.getStatus()).isEqualTo(401);

        MockHttpServletResponse r400 = new MockHttpServletResponse();
        filter.doFilter(request("secret", "   "), r400, (a, b) -> { });
        assertThat(r400.getStatus()).isEqualTo(400);
    }
}
