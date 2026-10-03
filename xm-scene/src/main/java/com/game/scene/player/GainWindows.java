package com.game.scene.player;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家的获取滑动窗口（获取异常检测用）。只在逻辑线程上读写；不持久化——挂在场景内的玩家实例上，玩家离开 / 换实例即随之清空
 * （同基线下线清桶，且不必另做清理）。物品与币种分开存：币种号可能等于物品 config id（同基线 anomaly_detector.h）。
 */
public final class GainWindows {

    private final Map<Integer, GainWindow> currencies = new HashMap<>();
    private final Map<Integer, GainWindow> items = new HashMap<>();

    public GainWindow currency(int type) {
        return currencies.computeIfAbsent(type, t -> new GainWindow());
    }

    public GainWindow item(int configId) {
        return items.computeIfAbsent(configId, t -> new GainWindow());
    }
}
