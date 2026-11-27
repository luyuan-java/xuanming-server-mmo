package com.game.scene.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产流水（审计）出口：每次资产变动成功后调用（场景逻辑线程上，不得阻塞）。
 * 生产实现是 {@link KafkaAssetAudit}：经审计管线（{@link AuditPipeline}）发往 Kafka，game-data 落库（architecture.md §4.5）；
 * {@link #log()} 只在关闭管线（{@code game.audit.enabled=false}）时用，写专用日志 {@value #LOGGER}。
 * 语义同基线：尽力而为，只是审计，不影响玩法结果。
 */
public interface AssetAudit {

    String LOGGER = "game.audit.asset";
    /** {@code extra} 的长度上限（game-data 列宽）；超了换成 {@value #EXTRA_TRUNCATED}。 */
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
        /** 职业技能修习。 */
        SKILL_UPGRADE,
        /** 宝宝洗点的金币（同上）。 */
        PET_RESET,
        /** 宝宝改名的金币（同上）。 */
        PET_RENAME,
        /** 宠物商店扣款。 */
        PET_BUY,
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
        /** 帮会全天帮战 V2：发布江湖追杀令托管银两扣款。 */
        GUILD_BOUNTY_ESCROW,
        /** 帮会全天帮战 V2：帮战奖励 / 战功箱 / 灵脉分红发放。 */
        GUILD_WAR_REWARD,
        /** 帮会全天帮战 V2：追杀赏金 / 反杀赏金 / 过期退还发放。 */
        GUILD_BOUNTY_PAYOUT,
        /** 聚宝斋卖出扣款（交易二期）。 */
        AUCTION_SELL,
        /** 聚宝斋买到发放（交易二期）。 */
        AUCTION_BUY,
        /** 交易 / 聚宝斋托管资产退回本人（TX_AUCTION_RETURN = 30；不受获取封禁约束）。 */
        AUCTION_RETURN,
        /** 玩家间交易发放（交易二期）。 */
        TRADE,
        /** 邮件附件（预留）。 */
        MAIL_ATTACHMENT,
        /** 回合制战斗结算的金币（Java 独有 TX_BATTLE_REWARD 1005，关联号 = battle_id；scene-battle-spec D23）。 */
        BATTLE_REWARD,
        /** 战斗掉落入包（同基线 TX_ITEM_AWARD，关联号 = battle_id）。 */
        ITEM_AWARD,
        /** 装备强化：成败均扣金币和材料，关联号为装备实例。 */
        EQUIP_ENHANCE,
        /** 镶嵌消耗一颗宝石。 */
        EQUIP_INLAY,
        /** 取下宝石返还到人物背包。 */
        EQUIP_GEM_REMOVE,
        /** 挂机月卡开通 / 续费扣款。 */
        AFK_PASS_BUY,
        /** 神秘商人购买。 */
        MYSTERY_MERCHANT_BUY,
        /** 装备打造与固定词条安装。 */
        EQUIP_CRAFT,
        /** 单槽赛季印追赶扣款。 */
        SEASON_SEAL_CATCHUP,
        /** 渠道撤单/拒付后的官方商城发放扣回（TX_PAYMENT_REVERSAL = 31）。 */
        PAYMENT_REVERSAL,
        /** 五行试炼志里程碑发奖（TX_TRIAL_JOURNAL_MILESTONE = 36）。 */
        TRIAL_JOURNAL_MILESTONE,
        /** 五行试炼志拓印兑换仅自用法宝（TX_TRIAL_ARTIFACT_REDEEM = 37）。 */
        TRIAL_ARTIFACT_REDEEM,
        /** 装备品相打磨 / 无损重铸（TX_EQUIP_POLISH_REFORGE = 38）。 */
        EQUIP_POLISH_REFORGE,
        /** 分卷宗技能制书 / 拆解返片（TX_SKILL_SCROLL_CRAFT = 39）。 */
        SKILL_SCROLL_CRAFT,
        /** 主城宗师原子代打费与 5% 系统税回收（TX_CRAFT_PROXY_FEE = 40）。 */
        CRAFT_PROXY_FEE,
        /** 官方灵石百宝阁直购（TX_SPIRIT_SHOP_BUY = 44）。 */
        SPIRIT_SHOP_BUY,
        /** 游历手记典藏解锁扣款（TX_TRAVEL_PASS_UNLOCK = 45）。 */
        TRAVEL_PASS_UNLOCK,
        /** 游历手记双轨奖励发放（TX_TRAVEL_PASS_REWARD = 46）。 */
        TRAVEL_PASS_REWARD,
        /** 7 套非永久流派方案槽位按月灵石租用/扩容/续期（TX_SKILL_LOADOUT_RENT = 48）。 */
        SKILL_LOADOUT_RENT,
        /** 天墉生死状普通战书银两预扣。 */
        JIANGHU_DUEL_FEE,
        /** 天墉生死状普通战书作废退还。 */
        JIANGHU_DUEL_REFUND,
        /** 天墉生死状清风洗尘（银两或洗尘符清除败绩印记）。 */
        JIANGHU_CLEAR_MARK;

        /**
         * 本次入账是否属于「新增获得」（受全服与单玩家获取封禁约束）。
         * 交易退回本人托管资产（{@link #AUCTION_RETURN}）与生死状战书预扣退还（{@link #JIANGHU_DUEL_REFUND}）
         * 是同一所有者的资产回流，不算新增获得，不受获取封禁拦截（对齐 C++ {@code GainBlockService::CountsAsGain}）。
         */
        public boolean countsAsGain() {
            return this != AUCTION_RETURN && this != JIANGHU_DUEL_REFUND;
        }
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

    /**
     * 在资产变动记流水的统一收口处触发合并存盘（主仓 11-12「资产变动约 1 秒内提交」）：
     * 认出玩家后<b>先</b>请求合并存盘，再委托记录流水——即使流水因号段耗尽、超限或队列满被拒收，资产已变仍保证约 1 秒内落盘。
     */
    static AssetAudit withAssetSaveTrigger(AssetAudit delegate, java.util.function.LongConsumer onPlayerAssetMutated) {
        java.util.Objects.requireNonNull(delegate, "delegate");
        java.util.Objects.requireNonNull(onPlayerAssetMutated, "onPlayerAssetMutated");
        return new AssetAudit() {
            @Override
            public void currencyChanged(long playerId, int currencyType, long delta, long before, long after,
                                        Reason reason, long correlationId, String extra) {
                if (playerId != 0L) {
                    onPlayerAssetMutated.accept(playerId);
                }
                delegate.currencyChanged(playerId, currencyType, delta, before, after, reason, correlationId, extra);
            }

            @Override
            public void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                                   long correlationId, String extra) {
                if (playerId != 0L) {
                    onPlayerAssetMutated.accept(playerId);
                }
                delegate.itemGained(playerId, itemUuid, configId, quantity, reason, correlationId, extra);
            }

            @Override
            public void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                                      long correlationId, String extra) {
                if (playerId != 0L) {
                    onPlayerAssetMutated.accept(playerId);
                }
                delegate.itemDestroyed(playerId, itemUuid, configId, quantity, reason, correlationId, extra);
            }
        };
    }

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
