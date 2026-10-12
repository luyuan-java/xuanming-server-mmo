package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionIdAllocatorTest {

    @Test
    void 高15位是节点号_低17位序号从1开始() {
        SessionIdAllocator allocator = new SessionIdAllocator(3);
        int first = allocator.next(id -> false).getAsInt();
        assertThat(first >>> SessionIdAllocator.SEQ_BITS).isEqualTo(3);
        assertThat(first & SessionIdAllocator.MAX_SEQ).isEqualTo(1);
        assertThat(allocator.next(id -> false).getAsInt() & SessionIdAllocator.MAX_SEQ).isEqualTo(2);
    }

    @Test
    void 跳过在用的号() {
        SessionIdAllocator allocator = new SessionIdAllocator(1);
        int busy1 = (1 << SessionIdAllocator.SEQ_BITS) | 1;
        int busy2 = (1 << SessionIdAllocator.SEQ_BITS) | 2;
        Set<Integer> inUse = Set.of(busy1, busy2);
        assertThat(allocator.next(inUse::contains).getAsInt()).isEqualTo((1 << SessionIdAllocator.SEQ_BITS) | 3);
    }

    @Test
    void 序号到顶回绕到1_且不发0() {
        SessionIdAllocator allocator = new SessionIdAllocator(2);
        Set<Integer> seen = new HashSet<>();
        int last = 0;
        for (int i = 0; i < SessionIdAllocator.MAX_SEQ; i++) {
            last = allocator.next(id -> false).getAsInt();
            assertThat(last & SessionIdAllocator.MAX_SEQ).isNotZero();
            seen.add(last);
        }
        assertThat(seen).hasSize(SessionIdAllocator.MAX_SEQ);
        assertThat(last & SessionIdAllocator.MAX_SEQ).isEqualTo(SessionIdAllocator.MAX_SEQ);
        assertThat(allocator.next(id -> false).getAsInt() & SessionIdAllocator.MAX_SEQ).isEqualTo(1);
    }

    @Test
    void 回绕后跳过仍在用的号() {
        SessionIdAllocator allocator = new SessionIdAllocator(5);
        for (int i = 0; i < SessionIdAllocator.MAX_SEQ; i++) {
            allocator.next(id -> false);
        }
        int stillUsed = (5 << SessionIdAllocator.SEQ_BITS) | 1;
        assertThat(allocator.next(id -> id == stillUsed).getAsInt()).isEqualTo((5 << SessionIdAllocator.SEQ_BITS) | 2);
    }

    @Test
    void 节点号32767时不发全1的保留号() {
        SessionIdAllocator allocator = new SessionIdAllocator(SessionIdAllocator.MAX_NODE_ID);
        Set<Integer> seen = new HashSet<>();
        OptionalInt id;
        while ((id = allocator.next(seen::contains)).isPresent()) {
            seen.add(id.getAsInt());
        }
        assertThat(seen).hasSize(SessionIdAllocator.MAX_SEQ - 1).doesNotContain(0xFFFFFFFF);
    }

    @Test
    void 号段全在用时返回空() {
        SessionIdAllocator allocator = new SessionIdAllocator(9);
        assertThat(allocator.next(id -> true)).isEmpty();
    }

    @Test
    void 节点号越界拒绝() {
        assertThatThrownBy(() -> new SessionIdAllocator(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionIdAllocator(SessionIdAllocator.MAX_NODE_ID + 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
