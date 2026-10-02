package com.game.scene.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产流水（审计）出口：每次资产变动成功后调用一次（场景逻辑线程上，不得阻塞）。
 * mmorpg 发 Kafka {@code TransactionLogEntry}；Java 版在流水管线（路线图 2.3）接入前，先写专用日志
 * {@value #LOGGER}（一行一条、键值对，可被日志采集），语义同基线：尽力而为，只是审计，不影响玩法结果。
 */
public interface AssetAudit {

    String LOGGER = "xm.audit.asset";

    /** 变动原因（基线 TransactionType 的子集；随接入的玩法补充）。 */
    enum Reason {
        /** GM 凭空发放（与玩法产出分开记：审计要一眼分出 GM 造的币）。 */
        GM_GRANT,
        /** GM 扣除。 */
        GM_DEDUCT
    }

    /**
     * @param delta  有符号变动额（加为正、扣为负）
     * @param before 变动前余额
     * @param after  变动后余额
     */
    void currencyChanged(long playerId, int currencyType, long delta, long before, long after, Reason reason);

    /** 写专用日志的实现。 */
    static AssetAudit log() {
        Logger log = LoggerFactory.getLogger(LOGGER);
        return (playerId, currencyType, delta, before, after, reason) -> log.info(
                "currency player={} type={} delta={} before={} after={} reason={}",
                Long.toUnsignedString(playerId), currencyType, delta, before, after, reason);
    }
}
