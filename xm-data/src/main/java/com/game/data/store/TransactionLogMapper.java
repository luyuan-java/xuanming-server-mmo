package com.game.data.store;

import com.game.data.txlog.TransactionLogRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * {@code transaction_log} 的读写。无符号列（uint64 / uint32 按位放在 long / int 里）经 {@link UnsignedLongTypeHandler} /
 * {@link UnsignedIntTypeHandler} 绑定与读取：余额的中间值（补缴抵扣链）、调用方给的关联号都可能 ≥ 2^63。
 */
@Mapper
public interface TransactionLogMapper {

    String U64 = "typeHandler=com.game.data.store.UnsignedLongTypeHandler";
    String U32 = "typeHandler=com.game.data.store.UnsignedIntTypeHandler";

    /**
     * 多行插入；主键已存在的行不动（重放幂等）。用 ON DUPLICATE KEY UPDATE 而不是 INSERT IGNORE：
     * 后者在严格模式下会把数据错误降成警告、静默写进截断值。
     *
     * @return 受影响行数（连接串带 useAffectedRows=true 时 = 新插入的行数，重复行计 0）
     */
    @Insert("""
            <script>
            INSERT INTO transaction_log (tx_id, time_ms, reason, kind, from_player, to_player, currency_type, currency_delta,
                balance_before, balance_after, item_uuid, item_config_id, item_quantity, correlation_id, extra, zone_id,
                ingested_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.txId,""" + U64 + "}, #{r.timeMs}, #{r.reason," + U32 + "}, #{r.kind," + U32 + "}, #{r.fromPlayer," + U64
            + "}, #{r.toPlayer," + U64 + "}, #{r.currencyType," + U32 + "}, #{r.currencyDelta}, #{r.balanceBefore," + U64
            + "}, #{r.balanceAfter," + U64 + "}, #{r.itemUuid," + U64 + "}, #{r.itemConfigId," + U32 + "}, #{r.itemQuantity,"
            + U32 + "}, #{r.correlationId," + U64 + "}, #{r.extra}, #{r.zoneId," + U32 + """
            }, #{ingestedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE tx_id = tx_id
            </script>""")
    int insertAll(@Param("rows") List<TransactionLogRow> rows, @Param("ingestedAt") long ingestedAt);

    /** 某玩家作为扣减方的流水（走 idx_txlog_from），按时间、流水号升序。 */
    @Select("""
            SELECT * FROM transaction_log
             WHERE from_player = #{player,""" + U64 + """
            } AND time_ms >= #{since} AND time_ms < #{until}
             ORDER BY time_ms, tx_id LIMIT #{limit}""")
    @Results(id = "transactionLog", value = {
            @Result(column = "tx_id", property = "txId", typeHandler = UnsignedLongTypeHandler.class),
            @Result(column = "reason", property = "reason", typeHandler = UnsignedIntTypeHandler.class),
            @Result(column = "kind", property = "kind", typeHandler = UnsignedIntTypeHandler.class),
            @Result(column = "from_player", property = "fromPlayer", typeHandler = UnsignedLongTypeHandler.class),
            @Result(column = "to_player", property = "toPlayer", typeHandler = UnsignedLongTypeHandler.class),
            @Result(column = "currency_type", property = "currencyType", typeHandler = UnsignedIntTypeHandler.class),
            @Result(column = "balance_before", property = "balanceBefore", typeHandler = UnsignedLongTypeHandler.class),
            @Result(column = "balance_after", property = "balanceAfter", typeHandler = UnsignedLongTypeHandler.class),
            @Result(column = "item_uuid", property = "itemUuid", typeHandler = UnsignedLongTypeHandler.class),
            @Result(column = "item_config_id", property = "itemConfigId", typeHandler = UnsignedIntTypeHandler.class),
            @Result(column = "item_quantity", property = "itemQuantity", typeHandler = UnsignedIntTypeHandler.class),
            @Result(column = "correlation_id", property = "correlationId", typeHandler = UnsignedLongTypeHandler.class),
            @Result(column = "zone_id", property = "zoneId", typeHandler = UnsignedIntTypeHandler.class)})
    List<TransactionLogEntry> findByFromPlayer(@Param("player") long player, @Param("since") long since,
                                               @Param("until") long until, @Param("limit") int limit);

    /** 某玩家作为获得方的流水（走 idx_txlog_to）。 */
    @Select("""
            SELECT * FROM transaction_log
             WHERE to_player = #{player,""" + U64 + """
            } AND time_ms >= #{since} AND time_ms < #{until}
             ORDER BY time_ms, tx_id LIMIT #{limit}""")
    @ResultMap("transactionLog")
    List<TransactionLogEntry> findByToPlayer(@Param("player") long player, @Param("since") long since,
                                             @Param("until") long until, @Param("limit") int limit);

    /** 删一批早于 {@code before} 的流水（保留期清理）。 */
    @Delete("DELETE FROM transaction_log WHERE time_ms < #{before} LIMIT #{limit}")
    int deleteOlderThan(@Param("before") long before, @Param("limit") int limit);
}
