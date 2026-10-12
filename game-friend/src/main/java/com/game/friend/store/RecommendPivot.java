package com.game.friend.store;

import java.util.concurrent.ThreadLocalRandom;

/** 随机兜底的锚点：在 [lo, lo + span) 里均匀取一个（span 按无符号解释；雪花号的差值远小于 2^63）。 */
public final class RecommendPivot {

    private RecommendPivot() {
    }

    /** 返回 [0, span) 里的偏移量。 */
    public static long pick(long lo, long span) {
        if (span > 0) {
            return ThreadLocalRandom.current().nextLong(span);
        }
        return Long.remainderUnsigned(ThreadLocalRandom.current().nextLong(), span);
    }
}
