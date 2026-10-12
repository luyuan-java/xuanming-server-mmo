package com.game.scene.player;

/** 测试用物品 guid 源：从 1000 起递增，可设剩余预算（0 = 发号源不可用）。失败不消耗预算。 */
final class TestGuids implements ItemGuids {

    long budget = Long.MAX_VALUE;
    private long next = 1000;

    @Override
    public long[] tryMint(int count) {
        if (count > budget) {
            return null;
        }
        budget -= count;
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = next++;
        }
        return out;
    }
}
