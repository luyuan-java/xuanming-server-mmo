package com.game.data.store;

import com.game.data.txlog.TransactionLogRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code transaction_log} 的读写。 */
@Mapper
public interface TransactionLogMapper {

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
                (#{r.txId}, #{r.timeMs}, #{r.reason}, #{r.kind}, #{r.fromPlayer}, #{r.toPlayer}, #{r.currencyType},
                 #{r.currencyDelta}, #{r.balanceBefore}, #{r.balanceAfter}, #{r.itemUuid}, #{r.itemConfigId},
                 #{r.itemQuantity}, #{r.correlationId}, #{r.extra}, #{r.zoneId}, #{ingestedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE tx_id = tx_id
            </script>""")
    int insertAll(@Param("rows") List<TransactionLogRow> rows, @Param("ingestedAt") long ingestedAt);

    /** 某玩家作为扣减方的流水（走 idx_txlog_from），按时间、流水号升序。 */
    @Select("""
            SELECT * FROM transaction_log
             WHERE from_player = #{player} AND time_ms >= #{since} AND time_ms < #{until}
             ORDER BY time_ms, tx_id LIMIT #{limit}""")
    List<TransactionLogEntry> findByFromPlayer(@Param("player") long player, @Param("since") long since,
                                               @Param("until") long until, @Param("limit") int limit);

    /** 某玩家作为获得方的流水（走 idx_txlog_to）。 */
    @Select("""
            SELECT * FROM transaction_log
             WHERE to_player = #{player} AND time_ms >= #{since} AND time_ms < #{until}
             ORDER BY time_ms, tx_id LIMIT #{limit}""")
    List<TransactionLogEntry> findByToPlayer(@Param("player") long player, @Param("since") long since,
                                             @Param("until") long until, @Param("limit") int limit);

    /** 删一批早于 {@code before} 的流水（保留期清理）。 */
    @Delete("DELETE FROM transaction_log WHERE time_ms < #{before} LIMIT #{limit}")
    int deleteOlderThan(@Param("before") long before, @Param("limit") int limit);
}
