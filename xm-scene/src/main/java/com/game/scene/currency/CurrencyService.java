package com.game.scene.currency;

import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.gainblock.RedisGainBlockSource;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;

/**
 * 货币增减的唯一入口（场景逻辑线程上调用）：规则在 {@link Wallet}，这里负责全服产出封禁的判定与成功后的连带——
 * 记资产流水、获取异常检测。各玩法不直接改钱包。
 */
public final class CurrencyService {

    private final AssetAudit audit;
    private final GainAnomalyDetector anomalies;
    private final SceneMetrics metrics;
    /** 当前生效的全服产出封禁名单（只在逻辑线程上读写，由 {@link GainBlockSync} 投递过来）。 */
    private GlobalGainBlocks globalBlocks = GlobalGainBlocks.NONE;

    public CurrencyService(AssetAudit audit, GainAnomalyDetector anomalies, SceneMetrics metrics) {
        this.audit = audit;
        this.anomalies = anomalies;
        this.metrics = metrics;
    }

    /** 不做异常检测、不出指标（测试用）。 */
    public CurrencyService(AssetAudit audit) {
        this(audit, GainAnomalyDetector.off(), SceneMetrics.noop());
    }

    /** 换上新的全服产出封禁名单（逻辑线程上调用）。 */
    public void applyGlobalBlocks(GlobalGainBlocks blocks) {
        globalBlocks = blocks;
    }

    /**
     * 加币。判定顺序同基线 AddCurrency：参数 → 全服封禁 → 本人封禁 → 入账（都在 {@link Wallet#add(int, long, boolean)}）；
     * 成功记一条流水并做获取异常检测。返回值同 {@link Wallet#add}。
     */
    public Wallet.Change add(ScenePlayer player, int type, long amount, AssetAudit.Reason reason) {
        boolean globallyBlocked = globalBlocks.blocksCurrency(type);
        Wallet.Change change = player.wallet().add(type, amount, globallyBlocked);
        if (change.ok()) {
            audit.currencyChanged(player.playerId(), change.type(), amount, change.before(), change.after(), reason);
            anomalies.currencyGained(player, change.type(), amount);
        } else if (globallyBlocked && change.tipId() == Wallet.BLOCKED) {
            metrics.gainBlocked(RedisGainBlockSource.CURRENCY);
        }
        return change;
    }

    /** 扣币；成功记一条流水（增减额为负）。返回值同 {@link Wallet#deduct}。 */
    public Wallet.Change deduct(ScenePlayer player, int type, long amount, AssetAudit.Reason reason) {
        Wallet.Change change = player.wallet().deduct(type, amount);
        if (change.ok()) {
            audit.currencyChanged(player.playerId(), change.type(), -amount, change.before(), change.after(), reason);
        }
        return change;
    }
}
