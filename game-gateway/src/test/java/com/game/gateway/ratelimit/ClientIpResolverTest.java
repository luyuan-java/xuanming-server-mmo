package com.game.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 客户端 IP：不可信对端不看 XFF；可信对端从右往左剥可信跳；只认 IP 字面量；网段写错拒绝启动。 */
class ClientIpResolverTest {

    @Test
    void 没配可信代理_一律取对端() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());
        assertThat(resolver.resolve("10.0.0.1", "1.2.3.4")).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(null, null)).isEqualTo("unknown");
    }

    @Test
    void 可信对端_从右往左剥掉可信的跳() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "192.168.1.5", "fd00::/8"));
        assertThat(resolver.resolve("10.1.2.3", "1.2.3.4, 10.9.9.9")).isEqualTo("1.2.3.4");
        assertThat(resolver.resolve("10.1.2.3", "6.6.6.6, 1.2.3.4, 192.168.1.5")).as("伪造的最左跳不采信").isEqualTo("1.2.3.4");
        assertThat(resolver.resolve("10.1.2.3", "10.2.2.2")).as("全可信取对端").isEqualTo("10.1.2.3");
        assertThat(resolver.resolve("10.1.2.3", null)).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve("11.0.0.1", "1.2.3.4")).as("对端不可信").isEqualTo("11.0.0.1");
        assertThat(resolver.resolve("fd00::1", "2001:db8::7")).isEqualTo("2001:db8:0:0::/64");
        assertThat(resolver.resolve("10.1.2.3", "evil.example.com")).as("主机名不解析、不当桶键").isEqualTo("10.1.2.3");
        assertThat(resolver.resolve("10.1.2.3", "abc, 10.9.9.9")).as("全十六进制字母也不查 DNS").isEqualTo("10.9.9.9");
        assertThat(resolver.resolve("10.1.2.3", "1.2.3.256")).isEqualTo("10.1.2.3");
        assertThat(ClientIpResolver.literal("1.2.3.4")).containsExactly(1, 2, 3, 4);
        assertThat(ClientIpResolver.literal("01.2.3")).isNull();
        assertThat(ClientIpResolver.literal(".::1")).isNull();
    }

    @Test
    void 多行XFF头按顺序拼起来_代理另起一行时不采信客户端伪造的那行() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(resolver.resolve(request)).isEqualTo("1.2.3.4");
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("10.1.2.3");
        assertThat(resolver.resolve(direct)).isEqualTo("10.1.2.3");
    }

    @Test
    void 桶标识_IPv4点分_IPv4映射归IPv4_IPv6归到64前缀() {
        assertThat(ClientIpResolver.bucketOf("1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(ClientIpResolver.bucketOf("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(ClientIpResolver.bucketOf("2001:db8:1:2:aaaa::1")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientIpResolver.bucketOf("2001:DB8:1:2:ffff:ffff:ffff:ffff")).as("同一 /64 一个桶")
                .isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientIpResolver.bucketOf("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0::/64");
        assertThat(new ClientIpResolver(List.of()).resolve("2001:db8:9::5", null)).isEqualTo("2001:db8:9:0::/64");
    }

    @Test
    void 网段写错拒绝启动() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("not-an-ip"))).isInstanceOf(IllegalArgumentException.class);
    }
}
