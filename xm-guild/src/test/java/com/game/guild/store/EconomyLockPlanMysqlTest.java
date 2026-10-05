package com.game.guild.store;

import static com.game.guild.store.EconomyFixtures.DEADLINE_MS;
import static com.game.guild.store.EconomyFixtures.DEBIT;
import static com.game.guild.store.GuildMysqlFixture.NOW;
import static com.game.guild.store.LockPlans.LOCK_READ;
import static com.game.guild.store.LockPlans.POINT_WRITE;

import com.game.guild.rules.GuildRoles;
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
 * 经济仓储锁定语句的执行计划回归（真库，缺省跳过；移植 mmorpg economy_lock_plan_mysql_test.go:97 的请求路径半边；guild-economy-spec §1.4
 * 「静态与计划回归」、§11.3「语句形状」）。资产 Store 那一半见 {@code AssetStoreLockPlanMysqlTest}。
 *
 * <p>最该拦的是 guild_member：主键 (guild_id, player_id) 之外另有唯一键 uk_guild_member(player_id)，M1 / M12 / M13 的 WHERE 同时钉死了两个唯一
 * 索引的全部列——被规划到 uk 上就是「二级 → 主键」取锁，与离帮按主键删成员行反序成环。夹具只有被查的那几行（小表是最坏情况），参数与夹具行
 * 逐列对得上。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class EconomyLockPlanMysqlTest {

    private static final long GUILD = 7801;
    private static final long LEADER = 8801;
    private static final long MEMBER = 8802;
    private static final long OP_ID = 9_780_001;
    private static final long TOKEN = 0x5a5a;

    private static GuildMysqlFixture db;

    @BeforeAll
    static void seed() throws SQLException {
        db = GuildMysqlFixture.create();
        db.reset();
        db.seedGuild(GUILD, 2, 1, 50, LEADER, Map.of(MEMBER, GuildRoles.MEMBER));
        EconomyFixtures.setFunds(db, GUILD, 25_000);
        EconomyFixtures.setContribution(db, GUILD, MEMBER, 100, 100);
        EconomyFixtures.ensureSeqRow(db, MEMBER, DEBIT, NOW);
        EconomyFixtures.insertOp(db, EconomyFixtures.donateRecord(OP_ID, MEMBER, GUILD, NOW, 1,
                EconomyFixtures.donatePayload(100)).setLeaseToken(TOKEN).build());
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void 经济仓储的锁定语句都是完整主键点操作() throws SQLException {
        int pending = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE;
        LockPlans.assertPrimaryKeyPointOperations(db, List.of(
                // guild（锁序位置 1）：升级锁帮会、升一级（funds >= cost 对夹具行成立）
                new PlanCase("G12 LOCK_GUILD_FOR_UPGRADE", JdbcEconomyStore.LOCK_GUILD_FOR_UPGRADE, List.of(GUILD), "guild",
                        LOCK_READ),
                new PlanCase("G13 UPGRADE_GUILD", JdbcEconomyStore.UPGRADE_GUILD,
                        List.of(2, 20_000L, 35, GUILD, 1, 20_000L), "guild", POINT_WRITE),
                // guild_member（位置 3）：FORCE INDEX (PRIMARY) 的三条
                new PlanCase("M1 LOCK_MEMBER_ROLE", JdbcGuildStore.LOCK_MEMBER_ROLE, List.of(GUILD, MEMBER), "guild_member",
                        LOCK_READ),
                new PlanCase("M12 LOCK_MEMBER_BALANCE", JdbcEconomyStore.LOCK_MEMBER_BALANCE, List.of(GUILD, MEMBER),
                        "guild_member", LOCK_READ),
                new PlanCase("M13 DEBIT_CONTRIBUTION", JdbcEconomyStore.DEBIT_CONTRIBUTION, List.of(10L, GUILD, MEMBER, 10L),
                        "guild_member", POINT_WRITE),
                // guild_player_op_seq（位置 5）：AllocateSeq 的锁定读与推进
                new PlanCase("Q3 LOCK_SEQ_ROW", JdbcEconomyStore.LOCK_SEQ_ROW, List.of(MEMBER, DEBIT), "guild_player_op_seq",
                        LOCK_READ),
                new PlanCase("Q4 BUMP_SEQ", JdbcEconomyStore.BUMP_SEQ, List.of(NOW, MEMBER, DEBIT), "guild_player_op_seq",
                        POINT_WRITE),
                // guild_asset_op（位置 6）：提前截止的点锁与点改
                new PlanCase("O2 LOCK_ASSET_OP", JdbcEconomyStore.LOCK_ASSET_OP, List.of(OP_ID), "guild_asset_op", LOCK_READ),
                new PlanCase("O3 ACCELERATE_DONATION_DEADLINE", JdbcEconomyStore.ACCELERATE_DONATION_DEADLINE,
                        List.of(NOW, NOW, NOW, OP_ID, pending, NOW), "guild_asset_op", POINT_WRITE),
                // O6：非锁定的单行回读，只断言走主键
                new PlanCase("O6 SELECT_OP_STATE", JdbcEconomyStore.SELECT_OP_STATE, List.of(OP_ID), "guild_asset_op", null)));
        // 夹具行的截止在 NOW 之后，O3 的 deadline_ms > ? 对它成立
        org.assertj.core.api.Assertions.assertThat(EconomyFixtures.record(db, OP_ID).getDeadlineMs()).isEqualTo(NOW + DEADLINE_MS);
    }
}
