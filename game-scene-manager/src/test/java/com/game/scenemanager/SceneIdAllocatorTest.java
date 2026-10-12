package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 全服 scene_id 发号器（批次 5.3 R5）：租约有效才发号、续期滞后暂停后自动恢复、确认丢失是终态且只回调一次、丢失之后登记的监听立即回调。
 * 真 Redis 上的占号见 {@code SceneIdAllocatorRedisIntegrationTest}。
 */
class SceneIdAllocatorTest {

    private boolean valid = true;
    private final SceneIdAllocator ids = SceneIdAllocator.forTesting(9, () -> valid);

    @Test
    void 租约有效时发号_号非0不重复_worker是租约号() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = ids.tryNext().orElseThrow();
            assertThat(id).isNotZero();
            assertThat(seen.add(id)).isTrue();
        }
        assertThat(ids.worker()).isEqualTo(9);
        assertThat(ids.leaseValid()).isTrue();
    }

    @Test
    void 续期滞后期间暂停发号_恢复后自动恢复() {
        valid = false;
        assertThat(ids.tryNext()).isEmpty();
        assertThat(ids.leaseValid()).isFalse();

        valid = true;
        assertThat(ids.tryNext()).isPresent();
    }

    @Test
    void 确认丢失是终态_监听只回调一次_之后登记的立即回调() {
        AtomicInteger before = new AtomicInteger();
        ids.onLost(before::incrementAndGet);

        ids.leaseLost();
        ids.leaseLost();

        assertThat(before).hasValue(1);
        assertThat(ids.isLost()).isTrue();
        assertThat(ids.tryNext()).as("即使续期判定仍为真也不再发号").isEmpty();
        assertThat(ids.leaseValid()).isFalse();

        AtomicInteger after = new AtomicInteger();
        ids.onLost(after::incrementAndGet);
        assertThat(after).hasValue(1);
    }

    @Test
    void 一个监听抛异常不影响其余监听() {
        AtomicInteger second = new AtomicInteger();
        ids.onLost(() -> {
            throw new IllegalStateException("boom");
        });
        ids.onLost(second::incrementAndGet);

        ids.leaseLost();

        assertThat(second).hasValue(1);
    }

    @Test
    void 测试用发号器关闭是空操作() {
        ids.close();
        ids.close();
        assertThat(ids.tryNext()).isPresent();
    }
}
