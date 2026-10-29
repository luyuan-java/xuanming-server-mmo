package com.game.data.consistency;

import static com.game.data.consistency.ConsistencyReport.*;
import static org.assertj.core.api.Assertions.*;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 执行候选固定 SQL 的进程内 H2 用例；不接受任何真实数据库系统属性或环境变量。 */
class ConsistencyExtraSqlPlansTest {
    @Test
    void 好友容量分别识别错计数与有边无容量_同一玩家不按边数重复计() throws Exception {
        try (var db = memory()) {
            friendSchema(db);
            execute(db,
                    "INSERT INTO friend_capacity VALUES(1,2),(3,1),(5,0)",
                    "INSERT INTO friend VALUES(1,2),(1,3),(4,1),(4,2)");

            var result = run(db, "friend.capacity");

            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.coverage()).isEqualTo(Coverage.COMPLETE);
            assertThat(result.scannedCount()).isEqualTo(5);
            assertThat(result.confirmedBad()).isEqualTo(2);
            assertThat(result.reasons()).containsExactlyInAnyOrder("friend_capacity_mismatch", "friend_capacity_missing");
        }
    }

    @Test
    void 零边无容量与零计数守卫行均合法() throws Exception {
        try (var db = memory()) {
            friendSchema(db);
            execute(db, "INSERT INTO friend_capacity VALUES(7,0)");

            var result = run(db, "friend.capacity");

            assertThat(result.status()).isEqualTo(Status.PASS);
            assertThat(result.scannedCount()).isEqualTo(1);
            assertThat(result.confirmedBad()).isZero();
            assertThat(result.countExact()).isTrue();
        }
    }

    @Test
    void 成员申请检查两端_不将永不删除的锁守卫或零号哨兵当孤儿() throws Exception {
        try (var db = memory()) {
            guildSchema(db);
            execute(db,
                    "CREATE TABLE guild_player_state(player_id BIGINT PRIMARY KEY)",
                    "INSERT INTO guild_player_state VALUES(0),(9999)",
                    "INSERT INTO guild VALUES(1,1,100,1)",
                    "INSERT INTO player VALUES(1,1)",
                    "INSERT INTO guild_member VALUES(1,1,3),(1,404,0),(999,1,0)",
                    "INSERT INTO guild_application VALUES(1,405),(998,1)");

            var result = run(db, "guild.member");

            assertThat(result.scannedCount()).isEqualTo(5);
            assertThat(result.confirmedBad()).isEqualTo(4);
            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.reasons()).containsExactlyInAnyOrder("guild_reference_missing", "guild_player_missing");
            assertThat(result.coverage()).isEqualTo(Coverage.COMPLETE);
        }
    }

    @Test
    void 帮主必须是本帮角色三且唯一_不能仅检查名义帮主() throws Exception {
        try (var db = memory()) {
            guildSchema(db);
            execute(db,
                    "INSERT INTO guild VALUES(1,10,10,1),(2,20,10,1),(3,30,10,1),(4,40,10,1)",
                    "INSERT INTO guild_member VALUES(1,10,3),(2,20,0),(3,30,3),(3,31,3)");

            var result = run(db, "guild.leader");

            assertThat(result.scannedCount()).isEqualTo(4);
            assertThat(result.confirmedBad()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.reasons()).containsExactlyInAnyOrder("guild_leader_membership_invalid", "guild_leader_cardinality");
        }
    }

    @Test
    void 帮会容量读取各行真实上限_等于上限和无符号大上限合法() throws Exception {
        try (var db = memory()) {
            guildSchema(db);
            execute(db,
                    "INSERT INTO guild VALUES(1,10,2,1),(2,20,1,1),(3,30,4294967295,1)",
                    "INSERT INTO guild_member VALUES(1,10,3),(1,11,0),(2,20,3),(2,21,0),(3,30,3)");

            var result = run(db, "guild.capacity");

            assertThat(result.status()).isEqualTo(Status.WARN);
            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.reasons()).containsExactly("guild_capacity_exceeded");
        }
    }

    @Test
    void 成员归属检查不把待审批的异区申请当成员() throws Exception {
        try (var db = memory()) {
            guildSchema(db);
            execute(db,
                    "INSERT INTO guild VALUES(1,10,10,1),(2,20,10,2)",
                    "INSERT INTO player VALUES(10,1),(20,1),(30,1)",
                    "INSERT INTO guild_member VALUES(1,10,3),(2,20,3)",
                    "INSERT INTO guild_application VALUES(2,30)");

            var result = run(db, "zone.guild_member_home");

            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.scannedCount()).isEqualTo(2);
            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.reasons()).containsExactly("guild_member_home_mismatch");
        }
    }

    @Test
    void 合服写闸关闭时成员与帮会分阶段归属不同只能未知() throws Exception {
        try (var db = memory()) {
            guildSchema(db);
            execute(db,
                    "INSERT INTO guild VALUES(1,10,10,1)",
                    "INSERT INTO player VALUES(10,2)",
                    "INSERT INTO guild_member VALUES(1,10,3)",
                    "UPDATE route_meta SET writable=0");

            var result = run(db, "zone.guild_member_home");

            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.confirmedBad()).isZero();
            assertThat(result.reasons()).containsExactly("concurrent_maintenance");
            assertThat(result.countExact()).isFalse();
        }
    }

    @Test
    void 合服写闸关闭时挂单区与卖家归属两项均未知而不报告坏行() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db,
                    "INSERT INTO player VALUES(10,2)",
                    "INSERT INTO trade_listing VALUES(1,2,1,1,10,1)",
                    "UPDATE route_meta SET writable=0");

            for (String id : List.of("zone.trade_listing", "zone.trade_listing_home")) {
                var result = run(db, id);
                assertThat(result.status()).as(id).isEqualTo(Status.UNKNOWN);
                assertThat(result.confirmedBad()).isZero();
                assertThat(result.reasons()).containsExactly("concurrent_maintenance");
            }
        }
    }

    @Test
    void 写闸元数据缺失损坏或代次变化不能确认归属异常() throws Exception {
        try (var db = memory()) {
            guildSchema(db);
            execute(db,
                    "INSERT INTO guild VALUES(1,10,10,1)",
                    "INSERT INTO player VALUES(10,2)",
                    "INSERT INTO guild_member VALUES(1,10,3)",
                    "UPDATE route_meta SET generation='bad'");

            var malformed = run(db, "zone.guild_member_home");
            assertThat(malformed.status()).isEqualTo(Status.UNKNOWN);
            assertThat(malformed.reasons()).containsExactly("route_meta_unavailable");
            execute(db, "DELETE FROM route_meta");
            var missing = run(db, "zone.guild_member_home");
            assertThat(missing.status()).isEqualTo(Status.UNKNOWN);
            assertThat(missing.reasons()).containsExactly("route_meta_unavailable");
            execute(db, "INSERT INTO route_meta VALUES(1,'00000000-0000-0000-0000-000000000001',1,64)");
            var changed = service(reader(db), budget -> update(db,
                    "UPDATE route_meta SET generation='00000000-0000-0000-0000-000000000002'"))
                    .run(request("zone.guild_member_home")).results().getFirst();
            assertThat(changed.status()).isEqualTo(Status.UNKNOWN);
            assertThat(changed.confirmedBad()).isZero();
            assertThat(changed.reasons()).containsExactly("concurrent_change");
        }
    }

    @Test
    void 归属比较缺少引用原件时未知而非把空值当相等() throws Exception {
        try (var db = memory()) {
            guildSchema(db);
            execute(db, "INSERT INTO guild_member VALUES(404,405,3)");

            var result = run(db, "zone.guild_member_home");

            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
            assertThat(result.reasons()).containsExactly("home_reference_unavailable");
        }
    }

    @Test
    void 四种未终结挂单都检查区_三种历史终态不按当前区和卖家误报() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db,
                    "INSERT INTO trade_listing VALUES(1,1,0,99,90,1),(2,2,0,99,90,1),(3,3,0,99,90,1),"
                            + "(4,4,0,99,90,1),(5,5,0,99,90,1),(6,6,0,99,90,1),(7,7,0,99,90,1)");

            var zones = run(db, "zone.trade_listing");
            var sellers = run(db, "trade.seller");

            assertThat(zones.scannedCount()).isEqualTo(7);
            assertThat(zones.confirmedBad()).isEqualTo(4);
            assertThat(zones.status()).isEqualTo(Status.BLOCK);
            assertThat(sellers.confirmedBad()).isEqualTo(4);
            assertThat(sellers.status()).isEqualTo(Status.WARN);
            assertThat(sellers.coverage()).isEqualTo(Coverage.COMPLETE);
        }
    }

    @Test
    void 历史终态原件独立运行可通过_不将已不存在卖家和旧区误作当前引用() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db, "INSERT INTO trade_listing VALUES(4,4,1,99,90,1),(6,6,1,99,90,1),(7,7,1,99,90,1)");

            for (String id : List.of("zone.trade_listing", "zone.trade_listing_home", "trade.seller")) {
                var result = run(db, id);
                assertThat(result.status()).as(id).isEqualTo(Status.PASS);
                assertThat(result.coverage()).isEqualTo(Coverage.COMPLETE);
            }
        }
    }

    @Test
    void 挂单当前市场与当前归属比较_不可比较只供审计的上架旧区() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db,
                    "INSERT INTO player VALUES(10,2)",
                    "INSERT INTO trade_listing VALUES(1,2,1,2,10,999),(2,3,1,1,10,1),(3,4,1,1,10,1)");

            var result = run(db, "zone.trade_listing_home");

            assertThat(result.status()).isEqualTo(Status.WARN);
            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.reasons()).containsExactly("listing_home_mismatch");
        }
    }

    @Test
    void 收藏检查收藏者_不能因历史挂单清理而将收藏记录误判为卖家孤儿() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db, "INSERT INTO player VALUES(1,1)", "INSERT INTO trade_favorite VALUES(1,404),(999,405)");

            var result = run(db, "trade.seller");

            assertThat(result.scannedCount()).isEqualTo(2);
            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.reasons()).containsExactly("favorite_player_missing");
        }
    }

    @Test
    void 未知挂单状态不会落入历史终态而假绿() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db, "INSERT INTO trade_listing VALUES(1,0,1,1,1,1)");

            for (String id : List.of("zone.trade_listing", "zone.trade_listing_home", "trade.seller")) {
                var result = run(db, id);
                assertThat(result.status()).as(id).isEqualTo(Status.UNKNOWN);
                assertThat(result.countExact()).isFalse();
                assertThat(result.reasons()).contains("listing_status_unknown");
            }
        }
    }

    @Test
    void 活跃挂单区为零缺失或退休均阻断_正常已知区通过() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db,
                    "INSERT INTO zone_config VALUES(1),(2)",
                    "INSERT INTO zone_merged_into VALUES(2)",
                    "INSERT INTO trade_listing VALUES(1,1,1,0,10,1),(2,2,1,99,10,1),"
                            + "(3,3,1,2,10,1),(4,5,1,1,10,1)");

            var result = run(db, "zone.trade_listing");

            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.coverage()).isEqualTo(Coverage.COMPLETE);
            assertThat(result.scannedCount()).isEqualTo(4);
            assertThat(result.confirmedBad()).isEqualTo(3);
            assertThat(result.reasons()).containsExactlyInAnyOrder(
                    "listing_zone_zero", "listing_zone_missing", "listing_zone_retired");
        }
    }

    @Test
    void 挂单复核仍不匹配但版本已变不能确认旧原件() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db,
                    "INSERT INTO player VALUES(10,2)",
                    "INSERT INTO trade_listing VALUES(1,2,1,1,10,1)");
            var service = service(reader(db), budget -> update(db, "UPDATE trade_listing SET version=2"));

            var result = service.run(request("zone.trade_listing_home")).results().getFirst();

            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.confirmedBad()).isZero();
            assertThat(result.countExact()).isFalse();
            assertThat(result.reasons()).containsExactly("concurrent_change");
        }
    }

    @Test
    void 活跃挂单找不到卖家时不能把归属空值作为零通过() throws Exception {
        try (var db = memory()) {
            tradeSchema(db);
            execute(db, "INSERT INTO trade_listing VALUES(1,2,1,0,404,1)");

            var result = run(db, "zone.trade_listing_home");

            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.confirmedBad()).isZero();
            assertThat(result.reasons()).containsExactly("home_reference_unavailable");
        }
    }

    @Test
    void 成员组合主键分页覆盖零号及无符号高半区且不重复() throws Exception {
        try (var db = memory()) {
            execute(db,
                    "CREATE TABLE player(player_id DECIMAL(20) PRIMARY KEY)",
                    "CREATE TABLE guild(guild_id DECIMAL(20) PRIMARY KEY)",
                    "CREATE TABLE guild_member(guild_id DECIMAL(20),player_id DECIMAL(20),"
                            + "PRIMARY KEY(guild_id,player_id))",
                    "CREATE TABLE guild_application(guild_id DECIMAL(20),player_id DECIMAL(20),"
                            + "PRIMARY KEY(guild_id,player_id))",
                    "INSERT INTO guild_member VALUES(0,0),(0,18446744073709551615),"
                            + "(9223372036854775808,0),(9223372036854775808,18446744073709551615),"
                            + "(18446744073709551615,18446744073709551615)");

            var result = run(db, "guild.member");

            assertThat(result.scannedCount()).isEqualTo(5);
            assertThat(result.confirmedBad()).isEqualTo(5);
            assertThat(result.samples()).hasSize(5);
            assertThat(result.samples()).extracting(Sample::identityHash).doesNotHaveDuplicates();
            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.countExact()).isTrue();
        }
    }

    @Test
    void 作业只对成功仍有计划报警_其他终态未写计划合法且未覆盖活动槽不能整项通过() throws Exception {
        try (var db = memory()) {
            opsSchema(db);
            execute(db,
                    "INSERT INTO ops_job VALUES(1,1),(2,2),(3,3),(4,4),(5,5),(6,6),(7,7),(8,8),(9,9)",
                    "INSERT INTO ops_job_player VALUES(1,10,'PLANNED'),(2,10,'PLANNED'),(3,10,'PLANNED'),"
                            + "(4,10,'PLANNED'),(5,10,'PLANNED'),(6,10,'PLANNED'),(7,10,'PLANNED'),"
                            + "(8,10,'PLANNED'),(9,10,'PLANNED')");

            var result = run(db, "ops.job_residue");

            assertThat(result.status()).isEqualTo(Status.WARN);
            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
            assertThat(result.countExact()).isFalse();
            assertThat(result.reasons()).containsExactlyInAnyOrder("succeeded_job_has_planned_detail", "ops_active_rule_unavailable");
        }
    }

    @Test
    void 空作业明细也不能掩盖尚未覆盖的活动槽规则() throws Exception {
        try (var db = memory()) {
            opsSchema(db);

            var result = run(db, "ops.job_residue");

            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.reasons()).containsExactly("ops_active_rule_unavailable");
            assertThat(result.countExact()).isFalse();
        }
    }

    @Test
    void 孤儿作业明细可确认但未知状态与过长结局必须保留未知覆盖() throws Exception {
        try (var db = memory()) {
            opsSchema(db);
            execute(db,
                    "INSERT INTO ops_job VALUES(1,0),(2,3)",
                    "INSERT INTO ops_job_player VALUES(1,10,'PLANNED'),(2,10,'" + "X".repeat(65) + "'),"
                            + "(3,10,'PLANNED')");

            var result = run(db, "ops.job_residue");

            assertThat(result.status()).isEqualTo(Status.WARN);
            assertThat(result.scannedCount()).isEqualTo(3);
            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
            assertThat(result.countExact()).isFalse();
            assertThat(result.reasons()).containsExactlyInAnyOrder("job_status_unknown", "job_outcome_shape_unknown",
                    "job_detail_parent_missing", "ops_active_rule_unavailable");
        }
    }

    @Test
    void 空源仍执行依赖证明_缺父表或列必须未知() throws Exception {
        try (var db = memory()) {
            execute(db, "CREATE TABLE guild_member(guild_id BIGINT,player_id BIGINT,PRIMARY KEY(guild_id,player_id))");
            var calls = new AtomicInteger();
            ConsistencySqlReader database = reader(db);
            ConsistencySqlReader counted = (sql, parameters, limit, budget) -> {
                calls.incrementAndGet();
                assertThat(sql).contains("WHERE 1=0", "LEFT JOIN guild", "LEFT JOIN player");
                return database.query(sql, parameters, limit, budget);
            };

            var result = service(counted, budget -> budget.check()).run(request("zone.guild_member_home")).results().getFirst();

            assertThat(calls).hasValue(1);
            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.reasons()).containsExactly("sql_failed");
        }
    }

    @Test
    void 一页预算截断保持已证实坏行但不得宣称全覆盖() throws Exception {
        try (var db = memory()) {
            friendSchema(db);
            execute(db, "INSERT INTO friend_capacity VALUES(1,1),(2,1)");
            var input = new ConsistencyService.Request(List.of("friend.capacity"), 1, 1, 60_000L, 60_000L, false);

            var result = service(reader(db), budget -> budget.check()).run(input).results().getFirst();

            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
            assertThat(result.countExact()).isFalse();
            assertThat(result.reasons()).contains("partial_coverage");
        }
    }

    @Test
    void 双读之间已修复视为在途_仍坏但原件改变必须未知() throws Exception {
        try (var db = memory()) {
            friendSchema(db);
            execute(db, "INSERT INTO friend_capacity VALUES(1,1)");
            var fixed = service(reader(db), budget -> update(db, "UPDATE friend_capacity SET friend_count=0"));

            var transientResult = fixed.run(request("friend.capacity")).results().getFirst();

            assertThat(transientResult.status()).isEqualTo(Status.PASS);
            assertThat(transientResult.transientCount()).isEqualTo(1);
            assertThat(transientResult.confirmedBad()).isZero();
            execute(db, "UPDATE friend_capacity SET friend_count=1");
            var changed = service(reader(db), budget -> update(db, "UPDATE friend_capacity SET friend_count=2"));

            var changedResult = changed.run(request("friend.capacity")).results().getFirst();

            assertThat(changedResult.status()).isEqualTo(Status.UNKNOWN);
            assertThat(changedResult.reasons()).containsExactly("concurrent_change");
        }
    }

    @Test
    void 不支持的账号上限与租约政策明确未知且不读取私有业务池() {
        ConsistencySqlReader never = (sql, parameters, limit, budget) -> {
            throw new AssertionError("缺权威政策不应尝试 SQL");
        };
        var input = new ConsistencyService.Request(
                List.of("account.player_cap", "own.lease_skew", "own.abandoned"), 1, 1, 60_000L, 60_000L, false);

        var report = service(never, budget -> budget.check()).run(input);

        assertThat(report.results()).allSatisfy(result -> assertThat(result.status()).isEqualTo(Status.UNKNOWN));
        assertThat(report.results()).extracting(result -> result.reasons().getFirst()).containsExactly(
                "login_cap_authority_unavailable", "lease_clock_policy_unavailable", "lease_observation_policy_unavailable");
        assertThat(report.executed()).isEmpty();
    }

    @Test
    void 固定计划不读取账号请求原文或哨兵表且都带依赖证明() {
        for (String check : ConsistencyExtraSqlPlans.CHECKS) {
            assertThat(ConsistencyExtraSqlPlans.forCheck(check)).as(check).isNotEmpty().allSatisfy(plan -> {
                assertThat(plan.schema().sql()).contains("WHERE 1=0", "LIMIT 1").doesNotContain(
                        "seller_account", "request_json", "operator", "guild_player_state", "FOR UPDATE", "DELETE ", "UPDATE ");
                assertThat(plan.page(null, new ConsistencySqlPlans.Key(java.math.BigDecimal.TEN, java.math.BigDecimal.TEN), 1).sql())
                        .contains("LIMIT ?", "ORDER BY");
            });
        }
    }

    private static Connection memory() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:consistency_extra_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
    }

    private static void friendSchema(Connection db) throws Exception {
        execute(db,
                "CREATE TABLE friend(player_id BIGINT,friend_player_id BIGINT,PRIMARY KEY(player_id,friend_player_id))",
                "CREATE TABLE friend_capacity(player_id BIGINT PRIMARY KEY,friend_count BIGINT)");
    }

    private static void guildSchema(Connection db) throws Exception {
        routeSchema(db);
        execute(db,
                "CREATE TABLE player(player_id BIGINT PRIMARY KEY,zone_id BIGINT)",
                "CREATE TABLE guild(guild_id BIGINT PRIMARY KEY,leader_id BIGINT,max_members BIGINT,zone_id BIGINT)",
                "CREATE TABLE guild_member(guild_id BIGINT,player_id BIGINT,role INT,PRIMARY KEY(guild_id,player_id))",
                "CREATE TABLE guild_application(guild_id BIGINT,player_id BIGINT,PRIMARY KEY(guild_id,player_id))");
    }

    private static void tradeSchema(Connection db) throws Exception {
        routeSchema(db);
        execute(db,
                "CREATE TABLE player(player_id BIGINT PRIMARY KEY,zone_id BIGINT)",
                "CREATE TABLE zone_config(zone_id BIGINT PRIMARY KEY)",
                "CREATE TABLE zone_merged_into(zone_id BIGINT PRIMARY KEY)",
                "CREATE TABLE trade_listing(listing_id BIGINT PRIMARY KEY,status INT,version BIGINT,market_zone BIGINT,"
                        + "seller_player_id BIGINT,seller_zone_at_listing BIGINT)",
                "CREATE TABLE trade_favorite(player_id BIGINT,listing_id BIGINT,PRIMARY KEY(player_id,listing_id))");
    }

    private static void routeSchema(Connection db) throws Exception {
        execute(db,
                "CREATE TABLE route_meta(id INT PRIMARY KEY,generation VARCHAR(36),writable INT,fence_shards INT)",
                "INSERT INTO route_meta VALUES(1,'00000000-0000-0000-0000-000000000001',1,64)");
    }

    private static void opsSchema(Connection db) throws Exception {
        execute(db,
                "CREATE TABLE ops_job(job_id BIGINT PRIMARY KEY,status INT)",
                "CREATE TABLE ops_job_player(job_id BIGINT,player_id BIGINT,outcome VARCHAR(255),PRIMARY KEY(job_id,player_id))");
    }

    private static void execute(Connection db, String... statements) throws Exception {
        try (var statement = db.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static void update(Connection db, String sql) {
        try {
            execute(db, sql);
        } catch (Exception failure) {
            throw new AssertionError("修改进程内测试原件失败", failure);
        }
    }

    private static Result run(Connection db, String id) {
        return service(reader(db), budget -> budget.check()).run(request(id)).results().getFirst();
    }

    private static ConsistencyService.Request request(String id) {
        return new ConsistencyService.Request(List.of(id), 1, 100, 60_000L, 60_000L, false);
    }

    private static ConsistencyService service(ConsistencySqlReader reader, ConsistencyService.Delay delay) {
        var namespace = GameNamespace.of("extra");
        return new ConsistencyService(reader, null, namespace, new RedisKeys(namespace), delay);
    }

    private static ConsistencySqlReader reader(Connection db) {
        return (sql, parameters, limit, budget) -> {
            budget.check();
            var result = new ArrayList<Map<String, Object>>();
            try (var statement = db.prepareStatement(sql)) {
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                try (var rows = statement.executeQuery()) {
                    var metadata = rows.getMetaData();
                    while (rows.next()) {
                        budget.check();
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                        }
                        result.add(row);
                    }
                }
                return result;
            } catch (java.sql.SQLException failure) {
                throw new ConsistencyBudget.Failure("sql_failed");
            }
        };
    }
}
