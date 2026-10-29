package com.game.data.consistency;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只登记Java正式表的固定SQL；游标使用十进制，支持UINT64高半区与零号坏行。 */
final class ConsistencySqlPlans {
    static final String NOW = "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED) AS db_now";
    record Key(BigDecimal first, BigDecimal second) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int value = first.compareTo(other.first); return value != 0 ? value : second.compareTo(other.second);
        }
        @Override public String toString() { return first.toPlainString() + ":" + second.toPlainString(); }
    }
    record Query(String sql, List<Object> parameters) {}
    record Finding(String reason, ConsistencyReport.Status status) {}
    record Plan(String id, String table, String key1, String key2, String columns, String joins,
            String projection, boolean distinct) {
        String second() { return key2 == null ? "0" : key2; }
        Query schema() { return new Query(select(" WHERE 1=0 LIMIT 1"),List.of()); }
        Query upper() {
            return new Query("SELECT " + key1 + " AS k1," + second() + " AS k2 FROM " + table
                    + " ORDER BY " + key1 + " DESC" + (key2 == null ? "" : "," + key2 + " DESC") + " LIMIT 1", List.of());
        }
        Query page(Key cursor, Key upper, int size) {
            var args = new ArrayList<Object>();
            String where = "";
            if (cursor != null) { where = bound(">", cursor, args) + " AND "; }
            where += bound("<=", upper, args);
            args.add(size);
            return new Query(select(" WHERE " + where + " ORDER BY " + key1
                    + (key2 == null ? "" : "," + key2) + " LIMIT ?"), args);
        }
        Query point(Key key) {
            var args = new ArrayList<Object>(); args.add(key.first);
            String where = " WHERE " + key1 + "=?";
            if (key2 != null) { where += " AND " + key2 + "=?"; args.add(key.second); }
            return new Query(select(where + " LIMIT 1"), args);
        }
        private String bound(String op, Key key, List<Object> args) {
            if (key2 == null) { args.add(key.first); return key1 + op + "?"; }
            // 明确展开范围，避免无符号tuple类型推导差异。
            boolean greater = op.equals(">");
            args.add(key.first); args.add(key.first); args.add(key.second);
            return "(" + key1 + (greater ? ">" : "<") + "? OR (" + key1 + "=? AND "
                    + key2 + op + "?))";
        }
        private String select(String tail) {
            return "SELECT d." + key1 + " AS k1," + (key2 == null ? "0" : "d." + key2)
                    + " AS k2," + projection + "," + NOW + " FROM (SELECT "
                    + (distinct ? "DISTINCT " : "") + columns + " FROM " + table + tail + ") d "
                    + joins + " ORDER BY d." + key1 + (key2 == null ? "" : ",d." + key2);
        }
    }
    static List<Plan> forCheck(String id) {
        return switch (id) {
            case "zone.player" -> List.of(zone(id, "player", "player_id"));
            case "zone.guild" -> List.of(zone(id, "guild", "guild_id"));
            case "account.player_account" -> List.of(new Plan(id, "player", "player_id", null,
                    "player_id,account,owner_revision", "LEFT JOIN account a ON a.account=d.account",
                    "a.account IS NULL AS missing,d.owner_revision AS revision", false));
            case "friend.orphan" -> List.of(friend("friend", "player_id", "friend_player_id"),
                    friend("friend_request", "from_player_id", "to_player_id"),
                    friend("friend_block", "player_id", "blocked_player_id"));
            case "friend.symmetry" -> List.of(new Plan(id, "friend", "player_id", "friend_player_id",
                    "player_id,friend_player_id", "LEFT JOIN friend r ON r.player_id=d.friend_player_id AND r.friend_player_id=d.player_id",
                    "r.player_id IS NULL AS missing", false));
            case "mail.state_orphan" -> List.of(new Plan(id, "mail_state", "mail_id", null, "mail_id",
                    "LEFT JOIN mail_message m ON m.mail_id=d.mail_id", "m.mail_id IS NULL AS missing", true));
            case "player.state_orphan" -> List.of(
                    new Plan(id, "player", "player_id", null, "player_id", "LEFT JOIN player_state s ON s.player_id=d.player_id",
                            "s.player_id IS NULL AS missing,s.data IS NULL AS null_data", false),
                    new Plan(id, "player_state", "player_id", null, "player_id", "LEFT JOIN player p ON p.player_id=d.player_id",
                            "p.player_id IS NULL AS missing,0 AS null_data", false));
            case "own.fence" -> List.of(new Plan(id, "player", "player_id", null, "player_id,owner_epoch",
                    "LEFT JOIN player_state s ON s.player_id=d.player_id",
                    "s.player_id IS NULL AS missing,d.owner_epoch AS owner_epoch,s.saved_epoch AS saved_epoch", false));
            case "match.expired_unrecoverable" -> List.of(new Plan(id, "match_global_formation", "battle_id", null,
                    "battle_id,operation_id,instance_id,state,deadline_ms,OCTET_LENGTH(create_request) AS create_length",
                    "LEFT JOIN match_battle_completion t ON t.battle_id=d.battle_id",
                    "d.operation_id,d.instance_id,d.state,d.deadline_ms,d.create_length,"
                            + "t.battle_id IS NULL AS completion_missing,t.terminal_decision,t.placement_operation_id,"
                            + "t.target_instance_id,t.outcome_protocol_version,t.preflight_supported_version,"
                            + "t.preflight_instance_id,t.adopted_outcome_protocol_version,t.adopted_outcome_observed", false));
            default -> ConsistencyExtraSqlPlans.forCheck(id);
        };
    }
    private static Plan zone(String id, String table, String pk) {
        return new Plan(id, table, pk, null, pk + ",zone_id", "LEFT JOIN zone_config z ON z.zone_id=d.zone_id "
                + "LEFT JOIN zone_merged_into m ON m.zone_id=d.zone_id",
                "d.zone_id,z.zone_id IS NULL AS missing,m.zone_id IS NOT NULL AS retired", false);
    }
    private static Plan friend(String table, String left, String right) {
        return new Plan("friend.orphan", table, left, right, left + "," + right,
                "LEFT JOIN player a ON a.player_id=d." + left + " LEFT JOIN player b ON b.player_id=d." + right,
                "a.player_id IS NULL OR b.player_id IS NULL AS missing", false);
    }
    static Key key(Map<String,Object> row) { return new Key(decimal(row,"k1"), decimal(row,"k2")); }
    static BigDecimal decimal(Map<String,Object> row, String field) {
        Object value = row.get(field);
        if (value == null) throw new ConsistencyBudget.Failure("row_decode_failed");
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new ConsistencyBudget.Failure("row_decode_failed"); }
    }
    static long number(Map<String,Object> row, String field) {
        try { return decimal(row,field).longValueExact(); }
        catch (ArithmeticException e) { throw new ConsistencyBudget.Failure("row_decode_failed"); }
    }
    static boolean flag(Map<String,Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof Boolean b) return b;
        long n = number(row,field);
        if (n != 0 && n != 1) throw new ConsistencyBudget.Failure("row_decode_failed");
        return n == 1;
    }
    static Integer integerOrNull(Map<String,Object> row, String field) {
        return row.get(field) == null ? null : Math.toIntExact(number(row,field));
    }
    static String string(Map<String,Object> row, String field) {
        Object value = row.get(field); return value == null ? null : value.toString();
    }
    static Finding finding(Plan plan, Map<String,Object> row) {
        return switch (plan.id) {
            case "zone.player", "zone.guild" -> number(row,"zone_id") == 0 ? block("zone_zero")
                    : flag(row,"retired") ? block("retired_zone_reference") : flag(row,"missing") ? block("zone_missing") : null;
            case "account.player_account" -> number(row,"revision") <= 0 ? block("owner_revision_invalid")
                    : flag(row,"missing") ? warn("account_missing") : null;
            case "friend.orphan", "mail.state_orphan" -> flag(row,"missing") ? warn("reference_missing") : null;
            // 好友双向边由同一事务写入；稳定缺少反向边是强不变量破坏，不能降为普通孤儿警告。
            case "friend.symmetry" -> flag(row,"missing") ? block("reverse_friend_missing") : null;
            case "player.state_orphan" -> flag(row,"missing") || flag(row,"null_data") ? block("state_missing_or_null") : null;
            case "own.fence" -> flag(row,"missing") ? block("state_missing")
                    : decimal(row,"saved_epoch").compareTo(decimal(row,"owner_epoch")) > 0 ? block("saved_epoch_ahead") : null;
            case "match.expired_unrecoverable" -> match(row);
            default -> ConsistencyExtraSqlPlans.finding(plan, row);
        };
    }
    private static Finding match(Map<String,Object> row) {
        // long只承载uint64位模式，不用其有符号次序作分页。
        long battle = decimal(row,"k1").toBigIntegerExact().longValue();
        var original = new MatchExpiredShape.Row(battle,string(row,"operation_id"),string(row,"instance_id"),
                Math.toIntExact(number(row,"state")),number(row,"deadline_ms"),row.get("create_length") == null ? -1 : number(row,"create_length"),
                flag(row,"completion_missing"),integerOrNull(row,"terminal_decision"),string(row,"placement_operation_id"),
                string(row,"target_instance_id"),integerOrNull(row,"outcome_protocol_version"),integerOrNull(row,"preflight_supported_version"),
                string(row,"preflight_instance_id"),integerOrNull(row,"adopted_outcome_protocol_version"),
                row.get("adopted_outcome_observed") == null ? null : flag(row,"adopted_outcome_observed"),null);
        var decision = MatchExpiredShape.inspect(original,number(row,"db_now"));
        return switch (decision.status()) {
            case SAFE -> null;
            case BROKEN -> block(decision.reason());
            case UNKNOWN -> new Finding(decision.reason(),ConsistencyReport.Status.UNKNOWN);
        };
    }
    static Finding route(Map<String,Object> row) {
        if (number(row,"id") != 1 || number(row,"fence_shards") != 64) return block("route_meta_shape");
        long writable = number(row,"writable");
        if (writable != 0 && writable != 1) return block("route_meta_shape");
        String generation = string(row,"generation");
        try { if (generation == null || !UUID.fromString(generation).toString().equals(generation)) return block("route_generation_invalid"); }
        catch (IllegalArgumentException e) { return block("route_generation_invalid"); }
        return null;
    }
    static Finding block(String reason) { return new Finding(reason,ConsistencyReport.Status.BLOCK); }
    static Finding warn(String reason) { return new Finding(reason,ConsistencyReport.Status.WARN); }
}
