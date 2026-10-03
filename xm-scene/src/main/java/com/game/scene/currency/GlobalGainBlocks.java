package com.game.scene.currency;

import java.util.Set;

/**
 * 全服产出封禁名单（不可变快照）：紧急止血用——名单上的币种所有人都不得再获得（基线 GainBlockService 的全服名单）。
 * 来源是 Redis（{@code RedisKeys.gainBlocks}，xm-data 运维接口写），由 {@link GainBlockSync} 同步到每个场景节点。
 * 物品类名单随背包批次加。
 */
public record GlobalGainBlocks(Set<Integer> currencies) {

    public static final GlobalGainBlocks NONE = new GlobalGainBlocks(Set.of());

    public GlobalGainBlocks {
        currencies = Set.copyOf(currencies);
    }

    public boolean blocksCurrency(int type) {
        return currencies.contains(type);
    }

    public int size() {
        return currencies.size();
    }
}
