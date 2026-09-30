package com.game.gate.session;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * 会话号分配：{@code session_id = (gate 节点号 << 17) | 序号}，uint32（Java 里按位存进 int）。
 *
 * <ul>
 *   <li>节点号占高 15 位，范围 [1, 32767]；序号占低 17 位，范围 [1, 131071]，0 不发；</li>
 *   <li>序号循环递增，回绕后跳过仍在用的号；</li>
 *   <li>{@code 0xFFFFFFFF}（节点号 32767 + 序号 131071）保留不发（C++ 侧的无效会话号）。</li>
 * </ul>
 *
 * 非线程安全：由 {@link SessionRegistry} 在锁内调用。
 */
public final class SessionIdAllocator {

    public static final int SEQ_BITS = 17;
    public static final int MAX_SEQ = (1 << SEQ_BITS) - 1;
    public static final int MIN_NODE_ID = 1;
    public static final int MAX_NODE_ID = (1 << (32 - SEQ_BITS)) - 1;
    static final int RESERVED = 0xFFFFFFFF;

    private final int high;
    private int lastSeq;

    public SessionIdAllocator(int gateNodeId) {
        if (gateNodeId < MIN_NODE_ID || gateNodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("gate 节点号越界: " + gateNodeId + "，合法范围 [" + MIN_NODE_ID + ", " + MAX_NODE_ID + "]");
        }
        this.high = gateNodeId << SEQ_BITS;
    }

    /**
     * 下一个不在用的会话号。
     *
     * @param inUse 该号是否仍被某个会话占用
     * @return 号段内全部在用时为空
     */
    public OptionalInt next(IntPredicate inUse) {
        for (int i = 0; i < MAX_SEQ; i++) {
            lastSeq = lastSeq >= MAX_SEQ ? 1 : lastSeq + 1;
            int id = high | lastSeq;
            if (id == RESERVED || inUse.test(id)) {
                continue;
            }
            return OptionalInt.of(id);
        }
        return OptionalInt.empty();
    }
}
