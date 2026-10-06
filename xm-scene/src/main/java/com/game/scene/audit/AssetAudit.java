package com.game.scene.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产流水（审计）出口：每次资产变动成功后调用（场景逻辑线程上，不得阻塞）。
 * 生产实现是 {@link KafkaAssetAudit}：经审计管线（{@link AuditPipeline}）发往 Kafka，xm-data 落库（architecture.md §4.5）；
 * {@link #log()} 只在关闭管线（{@code xm.audit.enabled=false}）时用，写专用日志 {@value #LOGGER}。
 * 语义同基线：尽力而为，只是审计，不影响玩法结果。
 */
public interface AssetAudit {

    String LOGGER = "xm.audit.asset";
    /** {@code extra} 的长度上限（xm-data 列宽）；超了换成 {@value #EXTRA_TRUNCATED}。 */
    int MAX_EXTRA = 1024;
    String EXTRA_TRUNCATED = "{\"truncated\":true}";

    /** 变动原因（基线 TransactionType 的子集；随接入的玩法补充）。 */
    enum Reason {
        /** GM 凭空发放（与玩法产出分开记：审计要一眼分出 GM 造的币）。 */
        GM_GRANT,
        /** GM 扣除。 */
        GM_DEDUCT,
        /** 属性洗点的金币（基线记通用消费 TX_CURRENCY_DEDUCT，Java 分开记原因）。 */
        ATTRIBUTE_RESET,
        /** 开新加点方案的金币（同上）。 */
        ATTRIBUTE_SCHEME_CREATE,
        /** 宝宝洗点的金币（同上）。 */
        PET_RESET,
        /** 宝宝改名的金币（同上）。 */
        PET_RENAME,
        /** 任务奖励（基线 TX_QUEST_REWARD）。 */
        QUEST_REWARD,
        /** 系统发放的物品（基线 TX_SYSTEM_GRANT：没有更具体来源的入包）。 */
        SYSTEM_GRANT,
        /** 物品销毁（基线 TX_ITEM_DESTROY）：临时格淘汰、整理合并掉的空实例。 */
        ITEM_DESTROY,
        /** 补缴抵扣（基线 TX_DEFERRED_CLAWBACK）：到账的收入先抵欠款。 */
        DEFERRED_CLAWBACK,
        /** 以下是资产通道按请求的流水原因（数值同基线 TransactionType）：帮会捐献扣款。 */
        GUILD_DONATE,
        /** 帮会商店发物。 */
        GUILD_SHOP,
        /** 帮会活动发奖。 */
        GUILD_ACTIVITY_REWARD,
        /** 聚宝斋卖出扣款（交易二期）。 */
        AUCTION_SELL,
        /** 聚宝斋买到发放（交易二期）。 */
        AUCTION_BUY,
        /** 玩家间交易发放（交易二期）。 */
        TRADE,
        /** 邮件附件（预留）。 */
        MAIL_ATTACHMENT,
        /** 回合制战斗结算的金币（Java 独有 TX_BATTLE_REWARD 1005，关联号 = battle_id；scene-battle-spec D23）。 */
        BATTLE_REWARD,
        /** 战斗掉落入包（同基线 TX_ITEM_AWARD，关联号 = battle_id）。 */
        ITEM_AWARD
    }

    /**
     * @param delta  有符号变动额（加为正、扣为负）
     * @param before 变动前余额
     * @param after  变动后余额
     */
    default void currencyChanged(long playerId, int currencyType, long delta, long before, long after, Reason reason) {
        currencyChanged(playerId, currencyType, delta, before, after, reason, 0, "");
    }

    /** 带关联号的货币流水（资产通道的单号）。 */
    default void currencyChanged(long playerId, int currencyType, long delta, long before, long after, Reason reason,
                                 long correlationId) {
        currencyChanged(playerId, currencyType, delta, before, after, reason, correlationId, "");
    }

    /**
     * 完整形态：补缴抵扣与它跟随的那笔收入共用一个关联号，抵扣那条带附加信息（同基线 {@code {"debt_remaining":N}}）。
     *
     * @param correlationId 关联号（0 = 无）
     * @param extra         附加信息 JSON（空 = 无）
     */
    void currencyChanged(long playerId, int currencyType, long delta, long before, long after, Reason reason,
                         long correlationId, String extra);

    /**
     * 物品入包（获得方 to_player，同基线 LogItemCreate）：每个配置一条。
     *
     * @param itemUuid      写到的第一个实例（可能是并入的既有堆，同基线 PrimaryWrittenGuid）
     * @param quantity      这一批的数量（不按实例拆）
     * @param correlationId 关联号（0 = 无）
     * @param extra         附加信息 JSON（空 = 无）
     */
    void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason, long correlationId,
                    String extra);

    /** 物品销毁（扣减方 from_player，同基线 LogItemDestroy）：每个实例一条，数量是销毁掉的量。 */
    void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason, long correlationId,
                       String extra);

    /** 写专用日志的实现（关闭审计管线时用）。 */
    static AssetAudit log() {
        Logger log = LoggerFactory.getLogger(LOGGER);
        return new AssetAudit() {
            @Override
            public void currencyChanged(long playerId, int currencyType, long delta, long before, long after,
                                        Reason reason, long correlationId, String extra) {
                log.info("currency player={} type={} delta={} before={} after={} reason={} correlation={} extra={}",
                        Long.toUnsignedString(playerId), currencyType, delta, Long.toUnsignedString(before),
                        Long.toUnsignedString(after), reason, Long.toUnsignedString(correlationId), extra);
            }

            @Override
            public void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                                   long correlationId, String extra) {
                log.info("item_gained player={} uuid={} config={} quantity={} reason={} correlation={} extra={}",
                        Long.toUnsignedString(playerId), Long.toUnsignedString(itemUuid),
                        Integer.toUnsignedString(configId), quantity, reason, Long.toUnsignedString(correlationId), extra);
            }

            @Override
            public void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                                      long correlationId, String extra) {
                log.info("item_destroyed player={} uuid={} config={} quantity={} reason={} correlation={} extra={}",
                        Long.toUnsignedString(playerId), Long.toUnsignedString(itemUuid),
                        Integer.toUnsignedString(configId), quantity, reason, Long.toUnsignedString(correlationId), extra);
            }
        };
    }
}
