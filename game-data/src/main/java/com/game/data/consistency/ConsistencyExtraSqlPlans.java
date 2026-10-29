package com.game.data.consistency;

import static com.game.data.consistency.ConsistencySqlPlans.*;

import com.game.data.ops.OpsJobStore;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.proto.trade.ListingStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 库存 §7 的第二批固定只读 SQL。主键范围、总预算、强读和双读复核复用核心运行器。
 * 不读取账号、玩家存档、物品内容或运维请求原文，也不调用领域服务的恢复型读口。
 */
final class ConsistencyExtraSqlPlans {
    static final List<String> CHECKS = List.of(
            "friend.capacity", "guild.member", "guild.leader", "guild.capacity",
            "zone.guild_member_home", "zone.trade_listing", "zone.trade_listing_home",
            "trade.seller", "ops.job_residue");

    // Java guild_tables.proto 的存储角色；GuildRoles.LEADER = 3，2 为空号。
    // game-data 不依赖 game-guild 服务模块，不能为只读巡检引入跨服务装配。
    private static final int GUILD_LEADER = 3;
    private static final String ROUTE_JOIN = " LEFT JOIN route_meta route ON route.id=1";
    private static final String ROUTE_PROJECTION = ",route.id AS route_id,route.generation AS route_generation,"
            + "route.writable AS route_writable,route.fence_shards AS route_fence_shards";

    private ConsistencyExtraSqlPlans() {
    }

    static List<Plan> forCheck(String id) {
        return switch (id) {
            case "friend.capacity" -> friendCapacity();
            case "guild.member" -> List.of(guildReference("guild_member"), guildReference("guild_application"));
            case "guild.leader" -> List.of(guildLeader());
            case "guild.capacity" -> List.of(new Plan(id, "guild", "guild_id", null,
                    "guild_id,max_members", "",
                    "d.max_members,(SELECT COUNT(*) FROM guild_member m WHERE m.guild_id=d.guild_id) AS member_count",
                    false));
            case "zone.guild_member_home" -> List.of(new Plan(id, "guild_member", "guild_id", "player_id",
                    "guild_id,player_id",
                    "LEFT JOIN guild g ON g.guild_id=d.guild_id LEFT JOIN player p ON p.player_id=d.player_id"
                            + ROUTE_JOIN,
                    "g.guild_id IS NULL AS guild_missing,p.player_id IS NULL AS player_missing,"
                            + "g.zone_id AS guild_zone,p.zone_id AS player_zone" + ROUTE_PROJECTION,
                    false));
            case "zone.trade_listing" -> List.of(new Plan(id, "trade_listing", "listing_id", null,
                    "listing_id,status,version,market_zone",
                    "LEFT JOIN zone_config z ON z.zone_id=d.market_zone "
                            + "LEFT JOIN zone_merged_into m ON m.zone_id=d.market_zone" + ROUTE_JOIN,
                    "d.status,d.version,d.market_zone,z.zone_id IS NULL AS zone_missing,"
                            + "m.zone_id IS NOT NULL AS zone_retired" + ROUTE_PROJECTION,
                    false));
            case "zone.trade_listing_home" -> List.of(new Plan(id, "trade_listing", "listing_id", null,
                    "listing_id,status,version,market_zone,seller_player_id",
                    "LEFT JOIN player p ON p.player_id=d.seller_player_id" + ROUTE_JOIN,
                    "d.status,d.version,d.market_zone,d.seller_player_id,"
                            + "p.player_id IS NULL AS player_missing,p.zone_id AS player_zone" + ROUTE_PROJECTION,
                    false));
            case "trade.seller" -> tradeSeller();
            case "ops.job_residue" -> List.of(new Plan(id, "ops_job_player", "job_id", "player_id",
                    "job_id,player_id,outcome", "LEFT JOIN ops_job j ON j.job_id=d.job_id",
                    "j.job_id IS NULL AS job_missing,j.status,LEFT(d.outcome,64) AS outcome,"
                            + "CHAR_LENGTH(d.outcome) AS outcome_length",
                    false));
            default -> List.of();
        };
    }

    private static List<Plan> friendCapacity() {
        return List.of(
                new Plan("friend.capacity", "friend_capacity", "player_id", null,
                        "player_id,friend_count", "",
                        "d.friend_count,(SELECT COUNT(*) FROM friend f WHERE f.player_id=d.player_id) AS edge_count",
                        false),
                // 每个有边的发起玩家只计一次；零边且无容量行是清扫器允许的正常状态。
                new Plan("friend.capacity", "friend", "player_id", null, "player_id",
                        "LEFT JOIN friend_capacity c ON c.player_id=d.player_id",
                        "c.player_id IS NULL AS capacity_missing", true));
    }

    private static Plan guildReference(String table) {
        return new Plan("guild.member", table, "guild_id", "player_id", "guild_id,player_id",
                "LEFT JOIN guild g ON g.guild_id=d.guild_id LEFT JOIN player p ON p.player_id=d.player_id",
                "g.guild_id IS NULL AS guild_missing,p.player_id IS NULL AS player_missing", false);
    }

    private static Plan guildLeader() {
        return new Plan("guild.leader", "guild", "guild_id", null, "guild_id,leader_id",
                "LEFT JOIN guild_member m ON m.guild_id=d.guild_id AND m.player_id=d.leader_id",
                "d.leader_id,m.player_id IS NULL AS leader_missing,m.role AS leader_role,"
                        + "(SELECT COUNT(*) FROM guild_member leaders WHERE leaders.guild_id=d.guild_id"
                        + " AND leaders.role=" + GUILD_LEADER + ") AS leader_count",
                false);
    }

    private static List<Plan> tradeSeller() {
        return List.of(
                new Plan("trade.seller", "trade_listing", "listing_id", null,
                        "listing_id,status,version,seller_player_id",
                        "LEFT JOIN player p ON p.player_id=d.seller_player_id",
                        "d.status,d.version,d.seller_player_id,p.player_id IS NULL AS player_missing", false),
                new Plan("trade.seller", "trade_favorite", "player_id", "listing_id",
                        "player_id,listing_id", "LEFT JOIN player p ON p.player_id=d.player_id",
                        "p.player_id IS NULL AS player_missing", false));
    }

    static Finding finding(Plan plan, Map<String, Object> row) {
        return switch (plan.id()) {
            case "friend.capacity" -> friendCapacity(plan, row);
            case "guild.member" -> flag(row, "guild_missing") ? block("guild_reference_missing")
                    : flag(row, "player_missing") ? warn("guild_player_missing") : null;
            case "guild.leader" -> guildLeader(row);
            case "guild.capacity" -> decimal(row, "member_count").compareTo(decimal(row, "max_members")) > 0
                    ? warn("guild_capacity_exceeded") : null;
            case "zone.guild_member_home" -> guildHome(row);
            case "zone.trade_listing" -> listingZone(row);
            case "zone.trade_listing_home" -> listingHome(row);
            case "trade.seller" -> plan.table().equals("trade_favorite")
                    ? (flag(row, "player_missing") ? warn("favorite_player_missing") : null)
                    : (activeListing(row) && flag(row, "player_missing") ? warn("listing_seller_missing") : null);
            case "ops.job_residue" -> jobResidue(row);
            default -> throw new ConsistencyBudget.Failure("not_implemented");
        };
    }

    private static Finding friendCapacity(Plan plan, Map<String, Object> row) {
        if (plan.table().equals("friend")) {
            return flag(row, "capacity_missing") ? block("friend_capacity_missing") : null;
        }
        return decimal(row, "friend_count").compareTo(decimal(row, "edge_count")) != 0
                ? block("friend_capacity_mismatch") : null;
    }

    private static Finding guildLeader(Map<String, Object> row) {
        if (flag(row, "leader_missing") || number(row, "leader_role") != GUILD_LEADER) {
            return block("guild_leader_membership_invalid");
        }
        return number(row, "leader_count") != 1 ? block("guild_leader_cardinality") : null;
    }

    private static Finding guildHome(Map<String, Object> row) {
        Finding maintenance = routeMaintenance(row);
        if (maintenance != null) {
            return maintenance;
        }
        if (flag(row, "guild_missing") || flag(row, "player_missing")) {
            return unknown("home_reference_unavailable");
        }
        return decimal(row, "guild_zone").compareTo(decimal(row, "player_zone")) != 0
                ? block("guild_member_home_mismatch") : null;
    }

    private static Finding listingZone(Map<String, Object> row) {
        if (!activeListing(row)) {
            return null;
        }
        Finding maintenance = routeMaintenance(row);
        if (maintenance != null) {
            return maintenance;
        }
        if (number(row, "market_zone") == 0) {
            return block("listing_zone_zero");
        }
        if (flag(row, "zone_retired")) {
            return block("listing_zone_retired");
        }
        return flag(row, "zone_missing") ? block("listing_zone_missing") : null;
    }

    private static Finding listingHome(Map<String, Object> row) {
        if (!activeListing(row)) {
            return null;
        }
        Finding maintenance = routeMaintenance(row);
        if (maintenance != null) {
            return maintenance;
        }
        if (flag(row, "player_missing")) {
            return unknown("home_reference_unavailable");
        }
        return decimal(row, "market_zone").compareTo(decimal(row, "player_zone")) != 0
                ? warn("listing_home_mismatch") : null;
    }

    /**
     * 合服先关闭全局写闸，再分批搬玩家、帮会和挂单；中间归属不一致是合法状态。
     * 在同一句强读里观察写闸与实体，避免预检后合服开始的竞态；代次也参与核心的二读比较。
     */
    private static Finding routeMaintenance(Map<String, Object> row) {
        String generation = string(row, "route_generation");
        if (row.get("route_id") == null || row.get("route_writable") == null
                || row.get("route_fence_shards") == null || generation == null) {
            return unknown("route_meta_unavailable");
        }
        if (number(row, "route_id") != 1 || number(row, "route_fence_shards") != 64) {
            return unknown("route_meta_unavailable");
        }
        try {
            if (!UUID.fromString(generation).toString().equals(generation)) {
                return unknown("route_meta_unavailable");
            }
        } catch (IllegalArgumentException malformed) {
            return unknown("route_meta_unavailable");
        }
        long writable = number(row, "route_writable");
        if (writable == 0) {
            return unknown("concurrent_maintenance");
        }
        return writable == 1 ? null : unknown("route_meta_unavailable");
    }

    private static boolean activeListing(Map<String, Object> row) {
        // 当前 Java ListingStatuses 与此契约枚举逐值对拍（TradeTablesTest）。终态原件不套卖家当前 home。
        ListingStatus status = ListingStatus.forNumber(Math.toIntExact(number(row, "status")));
        if (status == null) {
            throw new ConsistencyBudget.Failure("listing_status_unknown");
        }
        return switch (status) {
            case LISTING_STATUS_ESCROWING, LISTING_STATUS_LISTED, LISTING_STATUS_LOCKED,
                    LISTING_STATUS_RETURNING -> true;
            case LISTING_STATUS_SOLD, LISTING_STATUS_RETURNED, LISTING_STATUS_ESCROW_REJECTED -> false;
            default -> throw new ConsistencyBudget.Failure("listing_status_unknown");
        };
    }

    private static Finding jobResidue(Map<String, Object> row) {
        if (flag(row, "job_missing")) {
            return warn("job_detail_parent_missing");
        }
        OpsJobStatus status = OpsJobStatus.forNumber(Math.toIntExact(number(row, "status")));
        if (status == null || status == OpsJobStatus.OPS_JOB_STATUS_UNSPECIFIED) {
            return unknown("job_status_unknown");
        }
        if (number(row, "outcome_length") > 64) {
            return unknown("job_outcome_shape_unknown");
        }
        // FAILED / CANCELLED / INTERRUPTED 等终态允许保留未写的计划，不能一律报残留。
        return status == OpsJobStatus.OPS_JOB_SUCCEEDED && OpsJobStore.PLANNED.equals(string(row, "outcome"))
                ? warn("succeeded_job_has_planned_detail") : null;
    }

    /** 子规则未覆盖不能因已扫描部分为空而宣布整项 PASS COMPLETE。 */
    static String coverageGap(String id) {
        return id.equals("ops.job_residue") ? "ops_active_rule_unavailable" : null;
    }

    static String unavailableReason(String id) {
        return switch (id) {
            case "account.player_cap" -> "login_cap_authority_unavailable";
            case "own.lease_skew" -> "lease_clock_policy_unavailable";
            case "own.abandoned" -> "lease_observation_policy_unavailable";
            default -> "not_implemented";
        };
    }

    private static Finding unknown(String reason) {
        return new Finding(reason, ConsistencyReport.Status.UNKNOWN);
    }
}
