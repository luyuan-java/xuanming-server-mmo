package com.game.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NetProtectionTest {

    @Test
    void 三态熔断器_连续失败打开_半开单探测与取消归还_下线清理() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        EndpointCircuitBreaker breaker = new EndpointCircuitBreaker(3, Duration.ofSeconds(10), clock::get);

        assertThat(breaker.tryAcquire("match")).isTrue();
        breaker.recordFailure("match");
        breaker.recordFailure("match");
        assertThat(breaker.stateOf("match")).isEqualTo(EndpointCircuitBreaker.State.CLOSED);
        breaker.recordFailure("match");
        assertThat(breaker.stateOf("match")).isEqualTo(EndpointCircuitBreaker.State.OPEN);
        assertThat(breaker.isOpen("match")).isTrue();
        assertThat(breaker.tryAcquire("match")).isFalse();

        // 10s 后进入 HALF_OPEN：仅允许单条探测
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(breaker.stateOf("match")).isEqualTo(EndpointCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire("match")).isTrue();
        assertThat(breaker.tryAcquire("match")).isFalse();

        // 取消归还探测锁后，另一条可重新拿探测权，不计失败
        breaker.releaseProbe("match");
        assertThat(breaker.tryAcquire("match")).isTrue();

        // 探测成功立即恢复 CLOSED
        breaker.recordSuccess("match");
        assertThat(breaker.stateOf("match")).isEqualTo(EndpointCircuitBreaker.State.CLOSED);
        assertThat(breaker.isOpen("match")).isFalse();

        breaker.evict("match");
        assertThat(breaker.trackedEndpoints()).isZero();
    }

    @Test
    void 公网来源前缀限速_仅限公网且承压生效_满表放行计数() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        SourcePrefixRateLimiter limiter = new SourcePrefixRateLimiter(1.0, 2.0, 2, clock::get);

        InetAddress pub1 = InetAddress.getByName("203.0.113.10");
        InetAddress pub1SameSlash24 = InetAddress.getByName("203.0.113.99");
        InetAddress pub2 = InetAddress.getByName("198.51.100.5");
        InetAddress pub3 = InetAddress.getByName("192.0.2.7");
        InetAddress privateIp = InetAddress.getByName("10.0.1.5");
        InetAddress cgnat = InetAddress.getByName("100.64.1.9");
        InetAddress loopback6 = InetAddress.getByName("::1");
        InetAddress ula6 = InetAddress.getByName("fd00::1");
        InetAddress pub6 = InetAddress.getByName("2001:db8:1:2::10");

        assertThat(SourcePrefixRateLimiter.isPublicAddress(pub1)).isTrue();
        assertThat(SourcePrefixRateLimiter.isPublicAddress(pub6)).isTrue();
        assertThat(SourcePrefixRateLimiter.isPublicAddress(privateIp)).isFalse();
        assertThat(SourcePrefixRateLimiter.isPublicAddress(cgnat)).isFalse();
        assertThat(SourcePrefixRateLimiter.isPublicAddress(loopback6)).isFalse();
        assertThat(SourcePrefixRateLimiter.isPublicAddress(ula6)).isFalse();

        // 未承压一律放行
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAdmit(pub1, false)).isTrue();
        }
        assertThat(limiter.trackedPrefixes()).isZero();

        // 承压时同一 /24 共享桶（容量 2）
        assertThat(limiter.tryAdmit(pub1, true)).isTrue();
        assertThat(limiter.tryAdmit(pub1SameSlash24, true)).isTrue();
        assertThat(limiter.tryAdmit(pub1, true)).isFalse();

        // 私网地址即便承压也放行
        assertThat(limiter.tryAdmit(privateIp, true)).isTrue();
        assertThat(limiter.tryAdmit(cgnat, true)).isTrue();

        // 满表（maxPrefixes=2）后新前缀 fail-open 并计数
        assertThat(limiter.tryAdmit(pub2, true)).isTrue();
        assertThat(limiter.trackedPrefixes()).isEqualTo(2);
        assertThat(limiter.tryAdmit(pub3, true)).isTrue();
        assertThat(limiter.untrackedTotal()).isEqualTo(1);
    }
}
