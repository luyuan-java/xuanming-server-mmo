package com.game.scene.currency;

import com.game.scene.audit.AssetAudit;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;

/**
 * 货币增减的唯一入口（场景逻辑线程上调用）：规则在 {@link Wallet}，这里负责成功后的连带——记资产流水；
 * 以后的全服产出封禁、获取异常检测也接在这里（路线图 2.3），各玩法不直接改钱包。
 */
public final class CurrencyService {

    private final AssetAudit audit;

    public CurrencyService(AssetAudit audit) {
        this.audit = audit;
    }

    /** 加币；成功记一条流水。返回值同 {@link Wallet#add}。 */
    public Wallet.Change add(ScenePlayer player, int type, long amount, AssetAudit.Reason reason) {
        Wallet.Change change = player.wallet().add(type, amount);
        if (change.ok()) {
            audit.currencyChanged(player.playerId(), change.type(), amount, change.before(), change.after(), reason);
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
