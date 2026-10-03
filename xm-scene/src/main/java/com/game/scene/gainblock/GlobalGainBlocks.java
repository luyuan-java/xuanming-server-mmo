package com.game.scene.gainblock;

import java.util.Set;

/**
 * 全服产出封禁名单（不可变快照）：紧急止血用——名单上的币种 / 物品所有人都不得再获得（基线 GainBlockService 的全服名单）。
 * 来源是 Redis（{@code RedisKeys.gainBlocks}，xm-data 运维接口写），由 {@link GainBlockSync} 同步到每个场景节点。
 * 币种被封回 27005、物品被封回 1005（两种口径都是基线的客户端契约）。
 */
public record GlobalGainBlocks(Set<Integer> currencies, Set<Integer> items) {

    public static final GlobalGainBlocks NONE = new GlobalGainBlocks(Set.of(), Set.of());

    public GlobalGainBlocks {
        currencies = Set.copyOf(currencies);
        items = Set.copyOf(items);
    }

    public boolean blocksCurrency(int type) {
        return currencies.contains(type);
    }

    public boolean blocksItem(int configId) {
        return items.contains(configId);
    }

    /** 条目总数（指标用）。 */
    public int size() {
        return currencies.size() + items.size();
    }
}
