package com.game.scene.currency;

import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.gainblock.RedisGainBlockSource;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;

/**
 * 货币增减的唯一入口（场景逻辑线程上调用）：规则在 {@link Wallet}，这里负责玩家状态相关的闸（跨节点换图冻结、全服产出封禁）
 * 与成功后的连带——记资产流水、获取异常检测。各玩法不直接改钱包。
 *
 * <p><b>冻结闸</b>（scene-handoff-spec §0.5、§5.9；基线 CurrencySystem 的 IsCrossZoneFrozen）：跨节点换图的交出事务在途
 * （{@link ScenePlayer#frozen()}）时，加 / 扣在纯参数校验之后回 27003 {@link Wallet#FROZEN}（RETRY 类，余额不变、不记流水）；
 * GM 封禁 / 解封回 1005（基线 player_currency_handler 同码）。客户端入口另按冻结策略收拢，这里是纵深防御——任务、属性、宝宝、
 * 资产通道等内部路径都经过这里。选目标中（RESOLVING）不冻结。
 */
public final class CurrencyService {

    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;

    private final AssetAudit audit;
    private final GainAnomalyDetector anomalies;
    private final SceneMetrics metrics;
    private final SceneClock clock;
    /** 当前生效的全服产出封禁名单（只在逻辑线程上读写，由 {@link GainBlockSync} 投递过来）。 */
    private GlobalGainBlocks globalBlocks = GlobalGainBlocks.NONE;

    public CurrencyService(AssetAudit audit, GainAnomalyDetector anomalies, SceneMetrics metrics, SceneClock clock) {
        this.audit = audit;
        this.anomalies = anomalies;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 不做异常检测、不出指标、系统时钟（测试用）。 */
    public CurrencyService(AssetAudit audit) {
        this(audit, GainAnomalyDetector.off(), SceneMetrics.noop(), SceneClock.SYSTEM);
    }

    /** 换上新的全服产出封禁名单（逻辑线程上调用）。 */
    public void applyGlobalBlocks(GlobalGainBlocks blocks) {
        globalBlocks = blocks;
    }

    /** 不对单的加币（关联号 0）。 */
    public Wallet.Change add(ScenePlayer player, int type, long amount, AssetAudit.Reason reason) {
        return add(player, type, amount, reason, 0);
    }

    /**
     * 加币。判定顺序同基线 AddCurrency：参数 → 冻结（27003）→ 全服封禁 → 本人封禁 → 抵补缴欠款 → 入账（冻结之外都在
     * {@link Wallet#add(int, long, boolean, long)}）；成功记流水并按请求的数额做获取异常检测（同基线）。返回值同 {@link Wallet#add}。
     * <p>流水与基线的差异（有意）：基线先记一条只有 −抵扣额、没有前后余额的补缴流水，再记一条 +数额但 after = before + 净入账的收入流水
     * （单条前后余额对不上）。这里先记 +数额（before → before + 数额；有欠款时中间值可能越过 2^63，按 uint64 记、xm-data 按无符号落库），
     * 有抵扣再记 −抵扣额（→ 实际余额，附加信息同基线 {@code {"debt_remaining":N}}），两条共用关联号，每条自身的前后余额都对得上、首尾相接。
     *
     * @param correlationId 关联号（资产通道的单号；0 = 无）
     */
    public Wallet.Change add(ScenePlayer player, int type, long amount, AssetAudit.Reason reason, long correlationId) {
        return add(player, type, amount, reason, correlationId, nowSeconds());
    }

    /**
     * 同 {@link #add(ScenePlayer, int, long, AssetAudit.Reason, long)}，欠款到期按调用方给的时刻判：一次请求里先 {@link #checkAdd} 预检、
     * 后加币的调用方（资产通道）两次用同一个时刻，预检的结论才对加币成立。
     *
     * @param nowSeconds 当前 Unix 秒
     */
    public Wallet.Change add(ScenePlayer player, int type, long amount, AssetAudit.Reason reason, long correlationId,
                             long nowSeconds) {
        if (frozenRefusal(player, type, amount)) {
            return player.wallet().rejected(Wallet.FROZEN, type);
        }
        boolean globallyBlocked = globalBlocks.blocksCurrency(type);
        Wallet.Change change = player.wallet().add(type, amount, globallyBlocked, nowSeconds);
        if (change.ok()) {
            long gross = change.before() + amount;
            audit.currencyChanged(player.playerId(), change.type(), amount, change.before(), gross, reason,
                    correlationId);
            if (change.clawback() != 0) {
                Wallet.Debt debt = player.wallet().debt(change.type());
                long remaining = debt == null ? 0 : debt.remaining();
                audit.currencyChanged(player.playerId(), change.type(), -change.clawback(), gross, change.after(),
                        AssetAudit.Reason.DEFERRED_CLAWBACK, correlationId,
                        "{\"debt_remaining\":" + Long.toUnsignedString(remaining) + "}");
            }
            anomalies.currencyGained(player, change.type(), amount);
        } else if (globallyBlocked && change.tipId() == Wallet.BLOCKED) {
            metrics.gainBlocked(RedisGainBlockSource.CURRENCY);
        }
        return change;
    }

    /** 不对单的扣币（关联号 0）。 */
    public Wallet.Change deduct(ScenePlayer player, int type, long amount, AssetAudit.Reason reason) {
        return deduct(player, type, amount, reason, 0);
    }

    /** 扣币：参数 → 冻结（27003）→ 余额；成功记一条流水（增减额为负）。返回值同 {@link Wallet#deduct}。 */
    public Wallet.Change deduct(ScenePlayer player, int type, long amount, AssetAudit.Reason reason,
                                long correlationId) {
        if (frozenRefusal(player, type, amount)) {
            return player.wallet().rejected(Wallet.FROZEN, type);
        }
        Wallet.Change change = player.wallet().deduct(type, amount);
        if (change.ok()) {
            audit.currencyChanged(player.playerId(), change.type(), -amount, change.before(), change.after(), reason,
                    correlationId);
        }
        return change;
    }

    /**
     * 不改钱包、只问「这笔加币在 {@code nowSeconds} 这一刻会不会成」：资产通道发放前的预检（同基线 ApplyCredit 的逐币种预检）。
     * 返回 0 = 会成；否则是 {@link #add} 会回的拒绝码（含冻结 27003）。随后的加币要传同一个时刻。
     */
    public int checkAdd(ScenePlayer player, int type, long amount, long nowSeconds) {
        if (frozenRefusal(player, type, amount)) {
            return Wallet.FROZEN;
        }
        return player.wallet().checkAdd(type, amount, globalBlocks.blocksCurrency(type), nowSeconds);
    }

    /** GM 封禁获取（94）：冻结中 1005（同基线，先于参数校验）；否则同 {@link Wallet#block}。返回 tip。 */
    public int block(ScenePlayer player, int type) {
        return player.frozen() ? INVALID_PARAMETER : player.wallet().block(type);
    }

    /** GM 解除封禁（95）：冻结中 1005；否则同 {@link Wallet#unblock}。返回 tip。 */
    public int unblock(ScenePlayer player, int type) {
        return player.frozen() ? INVALID_PARAMETER : player.wallet().unblock(type);
    }

    /** 当前 Unix 秒（欠款到期判断用）。 */
    public long nowSeconds() {
        return clock.epochMillis() / 1000;
    }

    /** 参数合法而玩家冻结中：回 27003。参数不合法的照常交给钱包回 1005（冻结不该把坏请求变成「可重投」）。 */
    private static boolean frozenRefusal(ScenePlayer player, int type, long amount) {
        return player.frozen() && Wallet.checkRequest(type, amount) == 0;
    }
}
