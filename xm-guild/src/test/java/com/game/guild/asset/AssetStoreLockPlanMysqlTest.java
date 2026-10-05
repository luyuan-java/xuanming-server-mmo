package com.game.guild.asset;

import static com.game.guild.store.EconomyFixtures.COUNTER_SHOP;
import static com.game.guild.store.EconomyFixtures.CREDIT;
import static com.game.guild.store.EconomyFixtures.DAY_KEY;
import static com.game.guild.store.EconomyFixtures.DAY_MS;
import static com.game.guild.store.EconomyFixtures.LEASE_MS;
import static com.game.guild.store.GuildMysqlFixture.NOW;
import static com.game.guild.store.LockPlans.LOCK_READ;
import static com.game.guild.store.LockPlans.POINT_WRITE;

import com.game.api.proto.AssetOutcome;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.EconomyFixtures;
import com.game.guild.store.GuildMysqlFixture;
import com.game.guild.store.JdbcEconomyStore;
import com.game.guild.store.LockPlans;
import com.game.guild.store.LockPlans.PlanCase;
import com.game.guild.store.pb.GuildAssetOpStatus;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 资产 Store 锁定语句的执行计划回归（真库，缺省跳过；移植 mmorpg economy_lock_plan_mysql_test.go:97 的后台半边；guild-economy-spec §11.3
 * 「语句形状」）：领取 / 重排 / 毒行 / 终结 / 人工终结全是主键 CAS；对侧账的 G / M / Q / C 是主键点锁点改；清理是主键点锁 + 带复核点删
 * （单表 DELETE 不收索引提示，只能靠这里钉住它走主键）。夹具：每张表只有被查的那几行；guild_asset_op 一行未决、一行保留期外的终态
 * （两类语句要求的状态互斥）。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class AssetStoreLockPlanMysqlTest {

    private static final long GUILD = 7802;
    private static final long LEADER = 8803;
    private static final long MEMBER = 8804;
    private static final long OP_ID = 9_780_011;
    private static final long OLD_OP_ID = 9_780_012;
    private static final long TOKEN = 0x5a5b;
    private static final int GOODS = 101;

    private static GuildMysqlFixture db;

    @BeforeAll
    static void seed() throws SQLException {
        db = GuildMysqlFixture.create();
        db.reset();
        db.seedGuild(GUILD, 2, 1, 50, LEADER, Map.of(MEMBER, GuildRoles.MEMBER));
        EconomyFixtures.setContribution(db, GUILD, MEMBER, 100, 100);
        EconomyFixtures.insertCounter(db, MEMBER, COUNTER_SHOP, GOODS, DAY_KEY, 3);
        EconomyFixtures.ensureSeqRow(db, MEMBER, CREDIT, NOW);
        EconomyFixtures.insertOp(db, EconomyFixtures.shopRecord(OP_ID, MEMBER, NOW, 1).setGuildId(GUILD).setLeaseToken(TOKEN)
                .build());
        EconomyFixtures.insertOp(db, EconomyFixtures.shopRecord(OLD_OP_ID, MEMBER, NOW, 2).setGuildId(GUILD)
                .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED).setNextAttemptMs(NOW - 31 * DAY_MS).build());
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void 资产Store的锁定语句都是完整主键点操作() throws SQLException {
        int pending = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE;
        int applied = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_VALUE;
        int rejected = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED_VALUE;
        int aborted = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED_VALUE;
        int outcomeApplied = AssetOutcome.ASSET_OUTCOME_APPLIED_VALUE;
        int outcomeRetry = AssetOutcome.ASSET_OUTCOME_RETRY_VALUE;
        int outcomeUnknown = AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE;
        String op = "guild_asset_op";
        LockPlans.assertPrimaryKeyPointOperations(db, List.of(
                // guild（位置 1）：终结 DONATE APPLIED 的对侧账
                new PlanCase("G14 LOCK_GUILD_FUNDS", JdbcGuildAssetStore.LOCK_GUILD_FUNDS, List.of(GUILD), "guild", LOCK_READ),
                new PlanCase("G15 CREDIT_GUILD_FUNDS", JdbcGuildAssetStore.CREDIT_GUILD_FUNDS, List.of(1000L, GUILD), "guild",
                        POINT_WRITE),
                // guild_member（位置 3）：记帮贡 / 退帮贡（FORCE INDEX (PRIMARY)）
                new PlanCase("M15 CREDIT_CONTRIBUTION", JdbcGuildAssetStore.CREDIT_CONTRIBUTION,
                        List.of(10L, 10L, GUILD, MEMBER), "guild_member", POINT_WRITE),
                new PlanCase("M16 REFUND_CONTRIBUTION", JdbcGuildAssetStore.REFUND_CONTRIBUTION, List.of(10L, GUILD, MEMBER),
                        "guild_member", POINT_WRITE),
                // guild_player_op_seq（位置 5）：退款分支的计数行守卫（C6）
                new PlanCase("Q5 LOCK_SEQ_GUARD", JdbcGuildAssetStore.LOCK_SEQ_GUARD, List.of(MEMBER, CREDIT),
                        "guild_player_op_seq", LOCK_READ),
                // guild_asset_op（位置 6）：全是主键 CAS
                new PlanCase("O11 CLAIM", JdbcGuildAssetStore.CLAIM, List.of(NOW + LEASE_MS, TOKEN + 1, NOW, OP_ID, pending, NOW),
                        op, POINT_WRITE),
                new PlanCase("O13 POISON", JdbcGuildAssetStore.POISON,
                        List.of(outcomeUnknown, NOW + 3_600_000, NOW, OP_ID, TOKEN, pending), op, POINT_WRITE),
                new PlanCase("O14 RESCHEDULE", JdbcGuildAssetStore.RESCHEDULE,
                        List.of(NOW + 5000, 0, outcomeRetry, 0, NOW, OP_ID, pending, TOKEN), op, POINT_WRITE),
                new PlanCase("O14' RESCHEDULE_WITHOUT_ANSWER", JdbcGuildAssetStore.RESCHEDULE_WITHOUT_ANSWER,
                        List.of(NOW + 5000, NOW, OP_ID, pending, TOKEN), op, POINT_WRITE),
                new PlanCase("O16 FINALIZE", JdbcGuildAssetStore.FINALIZE,
                        List.of(applied, outcomeApplied, 0, 0, NOW, NOW, OP_ID, pending), op, POINT_WRITE),
                new PlanCase("O17 RESOLVE", JdbcGuildAssetStore.RESOLVE,
                        List.of(applied, "ops", "lock-plan", NOW, NOW, OP_ID, pending), op, POINT_WRITE),
                new PlanCase("O15 SELECT_IMMUTABLE", JdbcGuildAssetStore.SELECT_IMMUTABLE, List.of(OP_ID), op, null),
                new PlanCase("O12 SELECT_BY_ID", JdbcGuildAssetStore.SELECT_BY_ID, List.of(OP_ID), op, null),
                // op 行写前的主键点锁（V1）：未决行与终态行各点一次，证明两种行上计划相同
                new PlanCase("O2 LOCK_ASSET_OP(未决行)", JdbcEconomyStore.LOCK_ASSET_OP, List.of(OP_ID), op, LOCK_READ),
                new PlanCase("O2 LOCK_ASSET_OP(终态行)", JdbcEconomyStore.LOCK_ASSET_OP, List.of(OLD_OP_ID), op, LOCK_READ),
                new PlanCase("O21 CLEANUP_TERMINAL_OP", JdbcGuildAssetStore.CLEANUP_TERMINAL_OP,
                        List.of(OLD_OP_ID, applied, rejected, aborted, NOW), op, POINT_WRITE),
                // guild_daily_counter（位置 7）：退计数、清理点锁与点删
                new PlanCase("C4 REFUND_COUNTER", JdbcGuildAssetStore.REFUND_COUNTER,
                        List.of(1, 1, NOW, MEMBER, COUNTER_SHOP, GOODS, DAY_KEY), "guild_daily_counter", POINT_WRITE),
                new PlanCase("C6 LOCK_CLEANUP_COUNTER", JdbcGuildAssetStore.LOCK_CLEANUP_COUNTER,
                        List.of(MEMBER, COUNTER_SHOP, GOODS, DAY_KEY), "guild_daily_counter", LOCK_READ),
                new PlanCase("C7 CLEANUP_COUNTER", JdbcGuildAssetStore.CLEANUP_COUNTER,
                        List.of(MEMBER, COUNTER_SHOP, GOODS, DAY_KEY, GuildLimits.DAY_KEY_FLOOR, DAY_KEY), "guild_daily_counter",
                        POINT_WRITE)));
    }
}
