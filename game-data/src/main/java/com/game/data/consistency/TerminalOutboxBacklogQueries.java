package com.game.data.consistency;

/**
 * Java 终局永久原件的三类积压；一次 SELECT 取得计数、最老发出时间与数据库时钟。
 * 只用于观察，不以 retry_ms、玩家在线状态或重放批次大小缩小库存，也不授予任何投递/销账权。
 */
public final class TerminalOutboxBacklogQueries {
    private TerminalOutboxBacklogQueries() { }

    public static String snapshot() {
        return "SELECT backlog.kind,backlog.pending_rows,backlog.oldest_ms," + ConsistencySqlPlans.NOW + " "
                + "FROM ("
                + "SELECT 'result' AS kind,COUNT(*) AS pending_rows,CASE WHEN COUNT(issued_at_ms)=COUNT(*) THEN MIN(issued_at_ms) END AS oldest_ms "
                + "FROM match_battle_completion WHERE terminal_decision=1 AND result_published=0 "
                + "UNION ALL "
                + "SELECT 'first' AS kind,COUNT(*) AS pending_rows,CASE WHEN COUNT(issued_at_ms)=COUNT(*) THEN MIN(issued_at_ms) END AS oldest_ms "
                + "FROM match_battle_player_completion WHERE applied=0 AND owner_revision>0 AND inbox_seq=0 "
                + "UNION ALL "
                + "SELECT 'waiting' AS kind,COUNT(*) AS pending_rows,CASE WHEN COUNT(issued_at_ms)=COUNT(*) THEN MIN(issued_at_ms) END AS oldest_ms "
                + "FROM match_battle_player_completion WHERE applied=0 AND owner_revision>0 AND inbox_seq>0"
                + ") backlog";
    }
}
