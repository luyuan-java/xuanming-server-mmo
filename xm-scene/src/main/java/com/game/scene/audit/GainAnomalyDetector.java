package com.game.scene.audit;

import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.GainWindow;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 获取异常检测（基线 AnomalyDetector）：每玩家 × 每币种 / 每物品配置一个滑动窗口，窗口内获取次数或累计量超过阈值就告警。
 * 只告警、不拦截、不影响玩法。逻辑线程上调用（窗口挂在玩家实例上，见 {@code GainWindows}）。
 *
 * <p>告警写日志 {@value #LOGGER}（带玩家号）并计 {@code xm.scene.gain.anomalies{category, currency_type}}（不带玩家号）。
 * 与基线的差异（有意）：① 越线时告警一次，仍在线外的后续获取不再重复告警；某次获取到来时窗口已回到线内（含过期清空）就重新武装
 * （基线超线后每次获取都告警，刷怪 / 批量领奖会刷屏）；② 阈值的某一维填 0 只关掉这一维（基线次数填 0 等于每次都超）；
 * ③ 不发 Kafka（基线的 {@code anomaly_alert_topic} 在 Go / Java 都没有消费者）；④ 阈值来自配置（基线是写死的缺省值）。
 */
public final class GainAnomalyDetector {

    public static final String LOGGER = "xm.audit.anomaly";
    static final String CURRENCY = "currency";
    static final String ITEM = "item";

    private static final Logger log = LoggerFactory.getLogger(LOGGER);

    /**
     * 一组阈值（缺省同基线：600 秒内超过 50 次或累计超过 100000）。
     *
     * @param maxCount  窗口内次数上限（超过即告警；0 = 不看次数）
     * @param maxAmount 窗口内累计量上限（超过即告警；0 = 不看累计量）
     */
    public record Threshold(Duration window, int maxCount, long maxAmount) {

        /** 窗口的取值范围：1 秒到 1 天（要排在下面两个常量之前初始化）。 */
        static final Duration MIN_WINDOW = Duration.ofSeconds(1);
        static final Duration MAX_WINDOW = Duration.ofDays(1);
        public static final Threshold DEFAULT = new Threshold(Duration.ofSeconds(600), 50, 100_000);
        public static final Threshold OFF = new Threshold(Duration.ofSeconds(1), 0, 0);

        public Threshold {
            if (window == null || window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0
                    || maxCount < 0 || maxAmount < 0) {
                throw new IllegalArgumentException("获取异常阈值非法（窗口 1 秒到 1 天，上限不能为负）: window=" + window
                        + " maxCount=" + maxCount + " maxAmount=" + maxAmount);
            }
        }

        long windowNanos() {
            return window.toNanos();
        }

        public boolean off() {
            return maxCount == 0 && maxAmount == 0;
        }

        boolean exceededBy(GainWindow w) {
            return (maxCount > 0 && w.count() > maxCount) || (maxAmount > 0 && w.total() > maxAmount);
        }
    }

    private final Threshold defaults;
    private final Map<Integer, Threshold> currencyOverrides;
    private final Map<Integer, Threshold> itemOverrides;
    private final SceneClock clock;
    private final SceneMetrics metrics;

    /**
     * @param currencyOverrides 按币种覆盖的阈值（没有的币种用 {@code defaults}）
     * @param itemOverrides     按物品配置覆盖的阈值（同上）
     */
    public GainAnomalyDetector(Threshold defaults, Map<Integer, Threshold> currencyOverrides,
                               Map<Integer, Threshold> itemOverrides, SceneClock clock, SceneMetrics metrics) {
        this.defaults = defaults;
        this.currencyOverrides = Map.copyOf(currencyOverrides);
        this.itemOverrides = Map.copyOf(itemOverrides);
        this.clock = clock;
        this.metrics = metrics;
    }

    /** 全部关闭（测试 / 不需要检测的装配）。 */
    public static GainAnomalyDetector off() {
        return new GainAnomalyDetector(Threshold.OFF, Map.of(), Map.of(), SceneClock.SYSTEM, SceneMetrics.noop());
    }

    /**
     * 记一次货币获取（成功入账之后调用，量取请求加的数额，同基线）。
     *
     * @return 这一次让窗口越线并告警了
     */
    public boolean currencyGained(ScenePlayer player, int type, long amount) {
        Threshold t = currencyOverrides.getOrDefault(type, defaults);
        if (t.off()) {
            return false;
        }
        if (!record(player.gainWindows().currency(type), t, amount)) {
            return false;
        }
        GainWindow w = player.gainWindows().currency(type);
        log.warn("anomaly category={} player={} currency_type={} count={} total={} max_count={} max_amount={} window_s={}",
                CURRENCY, Long.toUnsignedString(player.playerId()), type, w.count(), w.total(), t.maxCount(),
                t.maxAmount(), t.window().toSeconds());
        metrics.gainAnomaly(CURRENCY, Integer.toString(type));
        return true;
    }

    /**
     * 记一次物品获取（入包成功之后，每个配置一次，量取请求数量，同基线 RecordItemGain）。指标不带配置号（高基数），只进日志。
     *
     * @return 这一次让窗口越线并告警了
     */
    public boolean itemGained(ScenePlayer player, int configId, long quantity) {
        Threshold t = itemOverrides.getOrDefault(configId, defaults);
        if (t.off()) {
            return false;
        }
        if (!record(player.gainWindows().item(configId), t, quantity)) {
            return false;
        }
        GainWindow w = player.gainWindows().item(configId);
        log.warn("anomaly category={} player={} config_id={} count={} total={} max_count={} max_amount={} window_s={}",
                ITEM, Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(configId), w.count(), w.total(),
                t.maxCount(), t.maxAmount(), t.window().toSeconds());
        metrics.gainAnomaly(ITEM, SceneMetrics.NO_CURRENCY_TYPE);
        return true;
    }

    /** 记一次获取；返回这一次是否让窗口越线（且此前未处于告警中）。 */
    private boolean record(GainWindow w, Threshold t, long amount) {
        long now = clock.nanoTime();
        w.prune(now, t.windowNanos());
        if (!t.exceededBy(w)) {
            // 这次获取之前窗口已回到线内（含过期清空）：重新武装，这次若越线就是新的一轮
            w.alerting(false);
        }
        w.append(now, amount);
        if (!t.exceededBy(w) || w.alerting()) {
            return false;
        }
        w.alerting(true);
        return true;
    }
}
