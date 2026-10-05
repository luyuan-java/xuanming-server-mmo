package com.game.guild.store;

import static com.game.guild.store.EconomyFixtures.COUNTER_DONATE;
import static com.game.guild.store.EconomyFixtures.COUNTER_SHOP;
import static com.game.guild.store.EconomyFixtures.CREDIT;
import static com.game.guild.store.EconomyFixtures.DAY_KEY;
import static com.game.guild.store.EconomyFixtures.DEBIT;
import static com.game.guild.store.EconomyFixtures.LEVEL_LOOKUP;
import static com.game.guild.store.EconomyFixtures.WEEK_KEY;
import static com.game.guild.store.EconomyFixtures.contribution;
import static com.game.guild.store.EconomyFixtures.counterUsed;
import static com.game.guild.store.EconomyFixtures.donation;
import static com.game.guild.store.EconomyFixtures.funds;
import static com.game.guild.store.EconomyFixtures.nextSeq;
import static com.game.guild.store.EconomyFixtures.opExists;
import static com.game.guild.store.EconomyFixtures.record;
import static com.game.guild.store.EconomyFixtures.roomy;
import static com.game.guild.store.EconomyFixtures.setContribution;
import static com.game.guild.store.EconomyFixtures.setFunds;
import static com.game.guild.store.EconomyFixtures.shopOrder;
import static com.game.guild.store.GuildMysqlFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.EconomyStore.DonationReserve;
import com.game.guild.store.EconomyStore.OpState;
import com.game.guild.store.EconomyStore.Reserved;
import com.game.guild.store.EconomyStore.ShopReserve;
import com.game.guild.store.EconomyStore.ShopReserved;
import com.game.guild.store.EconomyStore.ShopUsageKey;
import com.game.guild.store.EconomyStore.Upgraded;
import com.game.guild.store.GuildMysqlFixture.Recorder;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.TransactionType;
import com.google.protobuf.ByteString;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 经济仓储的真库用例（缺省跳过：{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}；移植 mmorpg economy_repo_test.go:754-1225、:1746-1885、
 * :1954-1994、:2258、asset_tables_shape_test.go；guild-economy-spec §11.3）：表形状、T-D / T-S / T-U 的成功与各种拒绝、未决守卫（16 / 512、
 * 只数本纪元）、同玩家并发守住每日上限、相邻新成员并发都成功、升级同 expected_level 并发只升一次、待结算与最近结果的排序、提前截止
 * （经 4.4 的退帮 / 踢人 / 解散与直接调用两路）、C1 的影响行数语义与连接池探测、无符号 ≥ 2^63 的 id 与令牌。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class JdbcEconomyStoreMysqlTest {

    private static GuildMysqlFixture db;
    private Recorder recorder;
    private JdbcEconomyStore econ;
    private JdbcGuildStore guilds;

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = GuildMysqlFixture.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() throws SQLException {
        db.reset();
        recorder = new Recorder();
        econ = EconomyFixtures.economy(db, recorder);
        guilds = db.store(recorder, EconomyTxHooks.INSTANCE);
    }

    private void seedGuild(long guildId, int zone, long leader, Map<Long, Integer> roles) throws SQLException {
        db.seedGuild(guildId, zone, 1, 50, leader, roles);
    }

    // ================================================================ 表形状

    /**
     * 资产三表经 pbmysql 建出来的键与索引（TestAssetTablesShape，asset_tables_shape_test.go:21）：索引名从 0 起、uk 是四列复合唯一键、
     * idx_2 的列序与未决行查询逐列一致、Q 无二级索引；枚举列是整数列。
     */
    @Test
    void 资产三表的键与索引() throws SQLException {
        Map<String, Map<String, List<String>>> want = Map.of(
                "guild_player_op_seq", Map.of("PRIMARY", List.of("player_id", "stream")),
                "guild_asset_op", Map.of(
                        "PRIMARY", List.of("op_id"),
                        "uk_guild_asset_op", List.of("player_id", "stream", "stream_epoch", "seq"),
                        "idx_guild_asset_op_0", List.of("status", "next_attempt_ms"),
                        "idx_guild_asset_op_1", List.of("guild_id", "op_id"),
                        "idx_guild_asset_op_2", List.of("player_id", "stream", "stream_epoch", "status", "seq")),
                "guild_daily_counter", Map.of(
                        "PRIMARY", List.of("player_id", "counter_kind", "ref_id", "period_key"),
                        "idx_guild_daily_counter_0", List.of("period_key")));
        Map<String, Boolean> unique = Map.of("PRIMARY", true, "uk_guild_asset_op", true);
        for (Map.Entry<String, Map<String, List<String>>> table : want.entrySet()) {
            Map<String, List<String>> got = new HashMap<>();
            Map<String, Boolean> gotUnique = new HashMap<>();
            try (Connection c = db.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS"
                            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
                ps.setString(1, table.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        got.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                        gotUnique.put(rs.getString(1), rs.getInt(3) == 0);
                    }
                }
            }
            assertThat(got).as("%s 的索引集合与列序", table.getKey()).isEqualTo(table.getValue());
            gotUnique.forEach((name, u) -> assertThat(u).as("%s.%s 的唯一性", table.getKey(), name)
                    .isEqualTo(unique.getOrDefault(name, false)));
        }
        for (String[] col : new String[][] {{"guild_asset_op", "kind"}, {"guild_asset_op", "status"},
                {"guild_daily_counter", "counter_kind"}}) {
            try (Connection c = db.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                            + " AND COLUMN_NAME = ?")) {
                ps.setString(1, col[0]);
                ps.setString(2, col[1]);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).as("%s.%s 应为整数列", col[0], col[1]).isEqualTo("int");
                }
            }
        }
    }

    // ================================================================ T-D 捐献预留

    /** 成功路径写满 28 列、占一次今日次数，可空的三列写成空值而不是 NULL，闸门拿到的是事务内读到的 zone。 */
    @Test
    void 捐献预留写未决行并占次数() throws SQLException {
        long guildId = 7501, leader = 8501, donor = 8502;
        int zone = 3;
        seedGuild(guildId, zone, leader, Map.of(donor, GuildRoles.MEMBER));

        AtomicReference<Integer> fenced = new AtomicReference<>();
        DonationReserve base = donation(9_500_001, donor, guildId, NOW);
        DonationReserve in = new DonationReserve(base.opId(), base.playerId(), base.guildId(), base.donateId(),
                base.minGuildLevel(), base.contributionGain(), base.fundsGain(), base.dailyLimit(), base.periodKey(),
                base.deadlineMs(), base.leaseUntilMs(), base.leaseToken(), base.nowMs(), base.payload(), z -> {
                    fenced.set(z);
                    return false;
                });
        Reserved res = econ.reserveDonation(in, roomy()).orThrow();
        assertThat(res.seq()).as("首笔 seq 从 1 起").isEqualTo(1);
        assertThat(res.streamEpoch()).as("纪元 = 事务内建 seq 行的时刻").isEqualTo(NOW);
        assertThat(fenced.get()).as("闸门必须拿事务内读到的 guild.zone_id").isEqualTo(zone);

        GuildAssetOpRow rec = record(db, in.opId());
        assertThat(rec.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(rec.getKind()).isEqualTo(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE);
        assertThat(rec.getStream()).isEqualTo(DEBIT);
        assertThat(rec.getGuildId()).isEqualTo(guildId);
        assertThat(rec.getDeadlineMs()).isEqualTo(in.deadlineMs());
        assertThat(rec.getLeaseUntilMs()).isEqualTo(in.leaseUntilMs());
        assertThat(rec.getNextAttemptMs()).as("同步投递握着租约，租约到期前循环不该碰它").isEqualTo(in.leaseUntilMs());
        assertThat(rec.getLeaseToken()).isEqualTo(in.leaseToken());
        assertThat(rec.getTxType()).isEqualTo(TransactionType.TX_GUILD_DONATE_VALUE);
        assertThat(rec.getPayload()).isEqualTo(in.payload());
        assertThat(rec.getPeriodKey()).isEqualTo(DAY_KEY);
        assertThat(rec.getRefId()).isEqualTo(1);
        assertThat(rec.getRefCount()).isEqualTo(1);
        assertThat(rec.getContributionDelta()).isEqualTo(10);
        assertThat(rec.getFundsDelta()).isEqualTo(1000);
        assertThat(rec.getStreamEpoch()).isEqualTo(res.streamEpoch());
        assertThat(rec.getCreatedMs()).isEqualTo(NOW);
        assertThat(rec.getAttempts()).isZero();
        assertThat(rec.getDurable()).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM guild_asset_op WHERE op_id = ? AND (payload IS NULL OR resolved_by IS NULL"
                + " OR resolve_reason IS NULL)", in.opId())).as("三个可空列必须显式写值，不能留 NULL").isZero();

        assertThat(econ.donateUsage(donor, DAY_KEY, roomy())).isEqualTo(Map.of(1, 1));
        List<GuildAssetOpRow> pending = econ.pendingOps(donor, DEBIT, 16, roomy());
        assertThat(pending).extracting(GuildAssetOpRow::getOpId).containsExactly(in.opId());
        assertThat(econ.opState(in.opId(), roomy()))
                .contains(new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, 0, 0));
        assertThat(econ.opState(1, roomy())).isEmpty();
        assertThat(econ.memberContribution(guildId, donor, roomy())).contains(new EconomyStore.Contribution(0, 0));
        assertThat(econ.memberContribution(guildId, 1, roomy())).isEmpty();
        // T-D 不失效缓存、不发 1213
        recorder.assertNoDeadlocks("捐献预留");
    }

    /** 达上限整体回滚：行不落、seq 不前进、次数不再加（seq 前进而行没落，scene 会看到一个永远不来的空洞 seq）。 */
    @Test
    void 捐献达上限整体回滚() throws SQLException {
        long guildId = 7511, leader = 8511, donor = 8512;
        seedGuild(guildId, 2, leader, Map.of(donor, GuildRoles.MEMBER));
        assertThat(econ.reserveDonation(donation(9_510_001, donor, guildId, NOW, 1), roomy()).isOk()).isTrue();
        TxOutcome<Reserved> second = econ.reserveDonation(donation(9_510_002, donor, guildId, NOW + 1, 1), roomy());
        assertThat(second.rejection()).isEqualTo(GuildReject.DONATE_LIMIT);
        assertThat(opExists(db, 9_510_002)).as("达上限时指令行必须随事务回滚").isFalse();
        assertThat(nextSeq(db, donor, DEBIT)).as("seq 不许因为被拒的那一笔前进").isEqualTo(2);
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).isEqualTo(1);
    }

    /** 帮会不在 / 不是成员 / 等级不够 / 合服闸门（含闸门读失败）各自回对应拒绝，且拒绝之后库里什么都没留下。 */
    @Test
    void 捐献的各种拒绝不留痕() throws SQLException {
        long guildId = 7521, missing = 7529, leader = 8521, donor = 8522, outsider = 8523;
        seedGuild(guildId, 2, leader, Map.of(donor, GuildRoles.MEMBER));

        assertThat(econ.reserveDonation(donation(9_520_001, donor, missing, NOW), roomy()).rejection())
                .isEqualTo(GuildReject.GUILD_GONE);
        assertThat(econ.reserveDonation(donation(9_520_002, outsider, guildId, NOW), roomy()).rejection())
                .isEqualTo(GuildReject.NOT_MEMBER);
        DonationReserve d = donation(9_520_003, donor, guildId, NOW);
        assertThat(econ.reserveDonation(withLevelAndFence(d, 2, null), roomy()).rejection())
                .isEqualTo(GuildReject.LEVEL_TOO_LOW);
        assertThat(econ.reserveDonation(withLevelAndFence(donation(9_520_004, donor, guildId, NOW), 1, z -> true), roomy())
                .rejection()).isEqualTo(GuildReject.ZONE_MERGING);
        assertThat(econ.reserveDonation(withLevelAndFence(donation(9_520_005, donor, guildId, NOW), 1, z -> {
            throw new IllegalStateException("redis down");
        }), roomy()).rejection()).as("闸门读不出来也按拒绝处理（fail-closed）").isEqualTo(GuildReject.ZONE_MERGING);

        assertThat(db.count("SELECT COUNT(*) FROM guild_asset_op")).as("被拒的预留不能留下指令行").isZero();
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).as("被拒的预留不能占次数").isNull();
        assertThat(db.count("SELECT COUNT(*) FROM guild_player_op_seq")).as("首次建出的 seq 行也随之回滚").isZero();
    }

    private static DonationReserve withLevelAndFence(DonationReserve d, int minLevel, GuildStore.ZoneFence fence) {
        return new DonationReserve(d.opId(), d.playerId(), d.guildId(), d.donateId(), minLevel, d.contributionGain(),
                d.fundsGain(), d.dailyLimit(), d.periodKey(), d.deadlineMs(), d.leaseUntilMs(), d.leaseToken(), d.nowMs(),
                d.payload(), fence);
    }

    /** 未决行达到 16 之后守卫拒绝下一笔（TOO_MANY_PENDING），被拒那笔不占次数（TestReserveDonation_TooManyPending）。 */
    @Test
    void 未决守卫_16行() throws SQLException {
        long guildId = 7531, leader = 8531, donor = 8532;
        seedGuild(guildId, 2, leader, Map.of(donor, GuildRoles.MEMBER));
        for (int i = 0; i < GuildLimits.ASSET_OP_MAX_PENDING; i++) {
            assertThat(econ.reserveDonation(donation(9_530_000 + i, donor, guildId, NOW + i, 100), roomy()).isOk())
                    .as("第 %d 笔", i + 1).isTrue();
        }
        TxOutcome<Reserved> over = econ.reserveDonation(donation(9_539_999, donor, guildId, NOW + 1000, 100), roomy());
        assertThat(over.rejection()).isEqualTo(GuildReject.TOO_MANY_PENDING);
        assertThat(opExists(db, 9_539_999)).isFalse();
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).as("被守卫拒绝的那笔不占次数").isEqualTo(16);
        assertThat(nextSeq(db, donor, DEBIT)).isEqualTo(17);

        // 有一笔离开 PENDING 就又能发
        db.exec("UPDATE guild_asset_op SET status = ? WHERE op_id = ?", GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_VALUE,
                9_530_000L);
        assertThat(econ.reserveDonation(donation(9_539_998, donor, guildId, NOW + 2000, 100), roomy()).isOk()).isTrue();
    }

    /** 跨度守卫：{@code next_seq − 最小未决 seq ≥ 512} → TOO_MANY_PENDING；只数<b>本纪元</b>的未决行（seq.go:192-220）。 */
    @Test
    void 未决守卫_跨度512_只数本纪元() throws SQLException {
        long guildId = 7533, leader = 8533, donor = 8534;
        seedGuild(guildId, 2, leader, Map.of(donor, GuildRoles.MEMBER));
        long epoch = NOW - 100_000;
        EconomyFixtures.ensureSeqRow(db, donor, DEBIT, epoch);
        // 本纪元一行未决 seq = 1；next_seq 推到 512 → 跨度 511，仍可分配（分到 512）
        EconomyFixtures.insertOp(db, EconomyFixtures.donateRecord(9_533_001, donor, guildId, epoch, 1,
                EconomyFixtures.donatePayload(1)).build());
        db.exec("UPDATE guild_player_op_seq SET next_seq = ? WHERE player_id = ? AND stream = ?", 512L, donor, DEBIT);
        Reserved ok = econ.reserveDonation(donation(9_533_002, donor, guildId, NOW, 100), roomy()).orThrow();
        assertThat(ok.seq()).isEqualTo(512);
        assertThat(ok.streamEpoch()).isEqualTo(epoch);
        // 现在 next_seq = 513，最小未决仍是 1：跨度 512 → 拒绝
        assertThat(econ.reserveDonation(donation(9_533_003, donor, guildId, NOW + 1, 100), roomy()).rejection())
                .isEqualTo(GuildReject.TOO_MANY_PENDING);

        // 换一个玩家：旧纪元的 16 行未决不挡新纪元（库恢复后抬了纪元）
        long other = 8535;
        db.seedMember(guildId, other, GuildRoles.MEMBER);
        long oldEpoch = NOW - 500_000;
        List<GuildAssetOpRow> olds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            olds.add(EconomyFixtures.donateRecord(9_534_000 + i, other, guildId, oldEpoch, i + 1,
                    EconomyFixtures.donatePayload(1)).build());
        }
        EconomyFixtures.insertOpsBulk(db, olds);
        EconomyFixtures.ensureSeqRow(db, other, DEBIT, NOW - 1000);
        Reserved fresh = econ.reserveDonation(donation(9_534_999, other, guildId, NOW, 100), roomy()).orThrow();
        assertThat(fresh.streamEpoch()).isEqualTo(NOW - 1000);
        assertThat(fresh.seq()).isEqualTo(1);
    }

    /** seq 行纪元为 0 / 缺行：故障，不分配（ErrSeqRowCorrupt / ErrSeqRowMissing）。 */
    @Test
    void seq行纪元为0是故障() throws SQLException {
        long guildId = 7537, leader = 8537, donor = 8538;
        seedGuild(guildId, 2, leader, Map.of(donor, GuildRoles.MEMBER));
        db.exec("INSERT INTO guild_player_op_seq (player_id, stream, next_seq, epoch, updated_ms) VALUES (?, ?, 1, 0, ?)",
                donor, DEBIT, NOW);
        assertThatThrownBy(() -> econ.reserveDonation(donation(9_537_001, donor, guildId, NOW), roomy()))
                .isInstanceOfSatisfying(GuildStoreException.class,
                        e -> assertThat(e.kind()).isEqualTo(GuildStoreException.Kind.SEQ_ROW_CORRUPT));
        assertThat(opExists(db, 9_537_001)).isFalse();
    }

    /**
     * 同一玩家 10 路并发捐献、每日上限 5：恰好 5 路成功、拿到的 seq 恰为 {1..5}，其余一律被每日上限拒绝（成员行锁把同一玩家的预留串行化；
     * TestReserveDonation_ConcurrentSamePlayerHonorsDailyLimit）。
     */
    @Test
    void 同玩家并发守住每日上限() throws SQLException {
        long guildId = 7911, leader = 8911, donor = 8912, opBase = 9_910_000;
        seedGuild(guildId, 2, leader, Map.of(donor, GuildRoles.MEMBER));
        List<AtomicReference<Reserved>> results = new ArrayList<>();
        List<Callable<TxOutcome<?>>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AtomicReference<Reserved> slot = new AtomicReference<>();
            results.add(slot);
            DonationReserve in = donation(opBase + i, donor, guildId, NOW, 5);
            calls.add(() -> {
                TxOutcome<Reserved> out = econ.reserveDonation(in, roomy());
                if (out.isOk()) {
                    slot.set(out.orThrow());
                }
                return out;
            });
        }
        @SuppressWarnings("unchecked")
        List<GuildMysqlFixture.Result> r = GuildMysqlFixture.concurrently(calls.toArray(new Callable[0]));
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < r.size(); i++) {
            if (r.get(i).ok()) {
                seqs.add(results.get(i).get().seq());
            } else {
                GuildMysqlFixture.assertRejectIn(r.get(i), GuildReject.DONATE_LIMIT);
                assertThat(r.get(i).reject()).as("超额的只能被每日上限拒绝，不能是写冲突").isEqualTo(GuildReject.DONATE_LIMIT);
            }
        }
        seqs.sort(Long::compare);
        assertThat(seqs).as("恰好 5 路成功，seq 连续无空洞").containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).isEqualTo(5);
        assertThat(EconomyFixtures.opCountInRange(db, opBase, opBase + 9)).isEqualTo(5);
        assertThat(nextSeq(db, donor, DEBIT)).as("seq 只因成功的 5 笔前进").isEqualTo(6);
        recorder.assertNoDeadlocks("同玩家并发捐献");
    }

    // ================================================================ T-S 兑换预留

    /** 扣可用帮贡、按份数占限购、写商店指令（永不中止）；达上限那一笔整体回滚。 */
    @Test
    void 兑换扣帮贡并占限购() throws SQLException {
        long guildId = 7541, leader = 8541, buyer = 8542;
        seedGuild(guildId, 2, leader, Map.of(buyer, GuildRoles.MEMBER));
        setContribution(db, guildId, buyer, 1000, 1000);

        ShopReserve first = shopOrder(9_540_001, buyer, guildId, 2, 300, 5, NOW);
        ShopReserved res = econ.reserveShopOrder(first, roomy()).orThrow();
        assertThat(res.balanceAfter()).isEqualTo(700);
        assertThat(res.seq()).isEqualTo(1);
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.SHOP, guildId));

        GuildAssetOpRow rec = record(db, first.opId());
        assertThat(rec.getKind()).isEqualTo(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP);
        assertThat(rec.getStream()).isEqualTo(CREDIT);
        assertThat(rec.getDeadlineMs()).as("商店指令永不中止").isZero();
        assertThat(rec.getRefId()).isEqualTo(101);
        assertThat(rec.getRefCount()).isEqualTo(2);
        assertThat(rec.getContributionDelta()).isEqualTo(300);
        assertThat(rec.getFundsDelta()).isZero();
        assertThat(rec.getPeriodKey()).isEqualTo(DAY_KEY);
        assertThat(rec.getTxType()).isEqualTo(TransactionType.TX_GUILD_SHOP_VALUE);

        assertThat(econ.reserveShopOrder(shopOrder(9_540_002, buyer, guildId, 3, 450, 5, NOW + 1), roomy()).orThrow()
                .balanceAfter()).isEqualTo(250);
        assertThat(econ.reserveShopOrder(shopOrder(9_540_003, buyer, guildId, 1, 150, 5, NOW + 2), roomy()).rejection())
                .isEqualTo(GuildReject.SHOP_LIMIT);
        assertThat(opExists(db, 9_540_003)).isFalse();
        assertThat(contribution(db, guildId, buyer)).as("兑换只扣可用余额，不动累计；被拒那笔不扣").containsExactly(1000, 250);
        assertThat(econ.shopUsage(buyer, DAY_KEY, WEEK_KEY, roomy())).isEqualTo(Map.of(new ShopUsageKey(101, DAY_KEY), 5));
        // 按周限购的用量与按日的同一条 IN 读回（日键 8 位、周键 6 位不相交）
        EconomyFixtures.insertCounter(db, buyer, COUNTER_SHOP, 104, WEEK_KEY, 2);
        assertThat(econ.shopUsage(buyer, DAY_KEY, WEEK_KEY, roomy())).containsEntry(new ShopUsageKey(104, WEEK_KEY), 2)
                .hasSize(2);
    }

    /** 帮贡不足 / 等级不够 / 不是成员回对应拒绝且不留痕；不限购的商品不占计数行。 */
    @Test
    void 兑换的各种拒绝_不限购不占计数行() throws SQLException {
        long guildId = 7551, leader = 8551, buyer = 8552;
        seedGuild(guildId, 2, leader, Map.of(buyer, GuildRoles.MEMBER));
        setContribution(db, guildId, buyer, 100, 100);

        assertThat(econ.reserveShopOrder(shopOrder(9_550_001, buyer, guildId, 1, 300, 5, NOW), roomy()).rejection())
                .isEqualTo(GuildReject.CONTRIBUTION_INSUFFICIENT);
        assertThat(opExists(db, 9_550_001)).isFalse();
        assertThat(counterUsed(db, buyer, COUNTER_SHOP, 101, DAY_KEY)).as("帮贡不足时不能占限购").isNull();
        ShopReserve s = shopOrder(9_550_002, buyer, guildId, 1, 30, 5, NOW);
        ShopReserve tooHigh = new ShopReserve(s.opId(), s.playerId(), s.guildId(), s.goodsId(), s.count(), 6, s.cost(),
                s.limitCount(), s.periodKey(), s.leaseUntilMs(), s.leaseToken(), s.nowMs(), s.payload(), null);
        assertThat(econ.reserveShopOrder(tooHigh, roomy()).rejection()).isEqualTo(GuildReject.LEVEL_TOO_LOW);
        assertThat(econ.reserveShopOrder(shopOrder(9_550_004, 8559, guildId, 1, 30, 5, NOW), roomy()).rejection())
                .isEqualTo(GuildReject.NOT_MEMBER);
        assertThat(econ.reserveShopOrder(shopOrder(9_550_005, buyer, 7559, 1, 30, 5, NOW), roomy()).rejection())
                .isEqualTo(GuildReject.GUILD_GONE);

        ShopReserved unlimited = econ.reserveShopOrder(shopOrder(9_550_003, buyer, guildId, 1, 30, 0, NOW), roomy()).orThrow();
        assertThat(unlimited.balanceAfter()).isEqualTo(70);
        assertThat(record(db, 9_550_003).getPeriodKey()).as("不限购 = 不占计数行，period_key 记 0").isZero();
        assertThat(db.count("SELECT COUNT(*) FROM guild_daily_counter WHERE player_id = ?", buyer)).isZero();
    }

    /**
     * 50 个 player_id 相邻、从没用过经济的新成员，各发 1 笔捐献 + 1 笔兑换，100 路同时放行：全部成功、零死锁（写事务是 READ COMMITTED 的
     * 行为证据；TestReserve_AdjacentNewMembersConcurrentAllSucceed）。
     */
    @Test
    void 相邻新成员并发预留都成功() throws SQLException {
        long guildId = 7921, leader = 8920, firstPlayer = 8921;
        int players = 50;
        Map<Long, Integer> roles = new HashMap<>();
        for (int i = 0; i < players; i++) {
            roles.put(firstPlayer + i, GuildRoles.MEMBER);
        }
        db.seedGuild(guildId, 2, 1, 100, leader, roles);
        db.exec("UPDATE guild_member SET contribution_total = 1000, contribution_balance = 1000 WHERE guild_id = ?", guildId);
        List<Callable<TxOutcome<?>>> calls = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            DonationReserve d = donation(9_920_000 + i, firstPlayer + i, guildId, NOW);
            ShopReserve s = shopOrder(9_921_000 + i, firstPlayer + i, guildId, 1, 30, 5, NOW);
            calls.add(() -> econ.reserveDonation(d, roomy()));
            calls.add(() -> econ.reserveShopOrder(s, roomy()));
        }
        @SuppressWarnings("unchecked")
        List<GuildMysqlFixture.Result> r = GuildMysqlFixture.concurrently(calls.toArray(new Callable[0]));
        for (int i = 0; i < r.size(); i++) {
            assertThat(r.get(i).ok()).as("第 %d 路必须成功：不同玩家之间不应有任何锁冲突 %s", i, r.get(i)).isTrue();
        }
        assertThat(db.count("SELECT COUNT(*) FROM guild_asset_op WHERE guild_id = ? AND status = ?", guildId,
                GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE)).isEqualTo(2L * players);
        for (int i = 0; i < players; i++) {
            assertThat(nextSeq(db, firstPlayer + i, DEBIT)).isEqualTo(2);
            assertThat(nextSeq(db, firstPlayer + i, CREDIT)).isEqualTo(2);
        }
        recorder.assertNoDeadlocks("相邻新成员并发预留");
        assertThat(recorder.lockWaits).isEmpty();
    }

    // ================================================================ T-U 升级

    /**
     * 1→2 级扣 GuildLevel[1].upgrade_cost_funds（20000），上限取 GuildLevel[2].max_members（35）；职位、闸门、expected_level、资金、满级、
     * 配表缺行各自拒绝；视图过期分支不扣钱但要求失效（TestUpgradeGuild_ChargesCurrentLevelCostAndRaisesCap）。
     */
    @Test
    void 升级扣当前等级花费并改上限() throws SQLException {
        long guildId = 7561, leader = 8561, officer = 8562, member = 8563;
        db.seedGuild(guildId, 2, 1, 30, leader, Map.of(officer, GuildRoles.OFFICER, member, GuildRoles.MEMBER));
        setFunds(db, guildId, 25_000);

        assertThat(econ.upgradeGuild(guildId, member, 1, LEVEL_LOOKUP, null, roomy()).rejection())
                .as("普通成员不能升级").isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(econ.upgradeGuild(guildId, 8569, 1, LEVEL_LOOKUP, null, roomy()).rejection())
                .isEqualTo(GuildReject.NOT_MEMBER);
        assertThat(econ.upgradeGuild(7569, leader, 1, LEVEL_LOOKUP, null, roomy()).rejection())
                .isEqualTo(GuildReject.GUILD_GONE);
        assertThat(econ.upgradeGuild(guildId, officer, 1, LEVEL_LOOKUP, zone -> true, roomy()).rejection())
                .isEqualTo(GuildReject.ZONE_MERGING);

        Upgraded stale = econ.upgradeGuild(guildId, officer, 2, LEVEL_LOOKUP, null, roomy()).orThrow();
        assertThat(stale.changed()).as("expected_level 对不上 = 无改动").isFalse();
        assertThat(stale.staleView()).isTrue();
        assertThat(stale.newLevel()).isEqualTo(1);
        assertThat(stale.invalidation()).as("视图过期也失效缓存").isEqualTo(Invalidation.of(GuildTxOp.UPGRADE, guildId));
        assertThat(funds(db, guildId)).isEqualTo(25_000);

        Upgraded res = econ.upgradeGuild(guildId, officer, 1, LEVEL_LOOKUP, null, roomy()).orThrow();
        assertThat(res.changed()).isTrue();
        assertThat(res.newLevel()).isEqualTo(2);
        assertThat(res.memberIds()).as("收件人 player_id 升序").containsExactly(leader, officer, member);
        assertThat(res.pushRecipients()).containsExactly(leader, member);
        assertThat(funds(db, guildId)).as("扣当前等级行的 20000").isEqualTo(5_000);
        assertThat(db.queryU64("SELECT max_members FROM guild WHERE guild_id = ?", guildId)).as("新上限取下一级行").isEqualTo(35);

        Upgraded again = econ.upgradeGuild(guildId, officer, 1, LEVEL_LOOKUP, null, roomy()).orThrow();
        assertThat(again.changed()).as("重复点击不能连升两级").isFalse();
        assertThat(funds(db, guildId)).isEqualTo(5_000);
        assertThat(econ.upgradeGuild(guildId, leader, 2, LEVEL_LOOKUP, null, roomy()).rejection())
                .isEqualTo(GuildReject.FUNDS_INSUFFICIENT);
        // expected_level = 0 不比对（proto 缺省，§9.1 第 13 条）
        assertThat(econ.upgradeGuild(guildId, leader, 0, LEVEL_LOOKUP, null, roomy()).rejection())
                .isEqualTo(GuildReject.FUNDS_INSUFFICIENT);

        db.exec("UPDATE guild SET level = ?, funds = ? WHERE guild_id = ?", 10, 9_000_000L, guildId);
        assertThat(econ.upgradeGuild(guildId, leader, 0, LEVEL_LOOKUP, null, roomy()).rejection())
                .isEqualTo(GuildReject.MAX_LEVEL);
        db.exec("UPDATE guild SET level = ? WHERE guild_id = ?", 9, guildId);
        EconomyStore.UpgradeLevels missingNext = level -> level == 10 ? null : LEVEL_LOOKUP.find(level);
        assertThat(econ.upgradeGuild(guildId, leader, 0, missingNext, null, roomy()).rejection())
                .as("配表缺下一级行按故障处理，不默认放行").isEqualTo(GuildReject.LEVEL_CONFIG_MISSING);
        db.exec("UPDATE guild SET level = ? WHERE guild_id = ?", 11, guildId);
        assertThat(econ.upgradeGuild(guildId, leader, 0, LEVEL_LOOKUP, null, roomy()).rejection())
                .as("当前等级行缺").isEqualTo(GuildReject.LEVEL_CONFIG_MISSING);
    }

    /** 帮主与长老同时按 expected_level = 1 点升级：只升一级、只扣一次钱，另一方拿到 changed = false（newLevel = 2）。 */
    @Test
    void 同expected_level并发只升一次() throws SQLException {
        long guildId = 7931, leader = 8931, officer = 8932;
        for (int round = 0; round < 10; round++) {
            db.reset();
            db.seedGuild(guildId, 2, 1, 30, leader, Map.of(officer, GuildRoles.OFFICER));
            setFunds(db, guildId, 25_000);
            List<AtomicReference<Upgraded>> slots = List.of(new AtomicReference<>(), new AtomicReference<>());
            List<GuildMysqlFixture.Result> r = GuildMysqlFixture.concurrently(
                    () -> capture(econ.upgradeGuild(guildId, leader, 1, LEVEL_LOOKUP, null, roomy()), slots.get(0)),
                    () -> capture(econ.upgradeGuild(guildId, officer, 1, LEVEL_LOOKUP, null, roomy()), slots.get(1)));
            assertThat(r).allMatch(GuildMysqlFixture.Result::ok);
            assertThat(slots.stream().filter(s -> s.get().changed()).count()).as("round %d：只有一方真的升级", round).isEqualTo(1);
            assertThat(slots).allSatisfy(s -> assertThat(s.get().newLevel()).isEqualTo(2));
            assertThat(funds(db, guildId)).as("只扣一次 20000").isEqualTo(5_000);
            assertThat(db.queryU64("SELECT level FROM guild WHERE guild_id = ?", guildId)).isEqualTo(2);
        }
        recorder.assertNoDeadlocks("同 expected_level 并发升级");
    }

    private static <T> TxOutcome<T> capture(TxOutcome<T> out, AtomicReference<T> slot) {
        if (out.isOk()) {
            slot.set(out.orThrow());
        }
        return out;
    }

    // ================================================================ 读

    /**
     * 最近结果按 (stream_epoch, seq) 倒序，新纪元整体排在旧纪元之前；待结算按同一对键升序；别的流不混进来
     * （TestRecentOps_OrderedByEpochThenSeqDesc）。
     */
    @Test
    void 最近结果与待结算的排序() throws SQLException {
        long player = 8701, oldEpoch = NOW - 200_000, newEpoch = NOW - 100_000;
        insert(801, player, oldEpoch, 1, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
        insert(802, player, oldEpoch, 2, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        insert(803, player, oldEpoch, 3, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED);
        insert(804, player, newEpoch, 1, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        EconomyFixtures.insertOp(db, EconomyFixtures.shopRecord(805, player, newEpoch, 9).setStream(DEBIT).build());

        assertThat(econ.recentOps(player, CREDIT, 3, roomy())).extracting(GuildAssetOpRow::getOpId)
                .containsExactly(804L, 803L, 802L);
        assertThat(econ.pendingOps(player, CREDIT, 16, roomy())).extracting(GuildAssetOpRow::getOpId)
                .containsExactly(802L, 804L);
        assertThat(econ.pendingOps(player, CREDIT, 0, roomy())).isEmpty();
        assertThat(econ.recentOps(player, CREDIT, 0, roomy())).isEmpty();
        assertThat(econ.recentOps(player, DEBIT, 20, roomy())).extracting(GuildAssetOpRow::getOpId).containsExactly(805L);
    }

    private void insert(long opId, long player, long epoch, long seq, GuildAssetOpStatus status) throws SQLException {
        EconomyFixtures.insertOp(db, EconomyFixtures.shopRecord(opId, player, epoch, seq).setStatus(status).build());
    }

    // ================================================================ seq 行

    /**
     * 事务内建 seq 行的语义（TestEnsureSeqRowTx_MatchesAssetopEnsureSeqRow 的 Java 版，Java 只有这一条建行路径）：next_seq 从 1 起、纪元 =
     * 建行时刻、已存在即空操作不改纪元。
     */
    @Test
    void 事务内建seq行_已存在即空操作() throws SQLException {
        long player = 8988;
        GuildTx tx = db.tx(recorder);
        for (long now : new long[] {NOW, NOW + 1}) {
            tx.run(GuildTxOp.DONATE, roomy(), t -> {
                JdbcEconomyStore.ensureSeqRowTx(t, player, CREDIT, now);
                return TxOutcome.ok(null);
            });
            assertThat(db.queryU64("SELECT next_seq FROM guild_player_op_seq WHERE player_id = ? AND stream = ?", player, CREDIT))
                    .isEqualTo(1);
            assertThat(db.queryU64("SELECT epoch FROM guild_player_op_seq WHERE player_id = ? AND stream = ?", player, CREDIT))
                    .as("纪元只在建行时写一次").isEqualTo(NOW);
            assertThat(db.queryU64("SELECT updated_ms FROM guild_player_op_seq WHERE player_id = ? AND stream = ?", player,
                    CREDIT)).isEqualTo(NOW);
        }
    }

    // ================================================================ C1 与连接池语义

    /** C1 的影响行数：1 = 新插、2 = 累加、0 = 达上限（值没变）；按 n 份占（兑换一次占多份）。 */
    @Test
    void 带上限upsert的影响行数语义() throws SQLException {
        long player = 8990;
        GuildTx tx = db.tx(recorder);
        List<Boolean> limited = new ArrayList<>();
        for (int n : new int[] {2, 2, 2, 1}) {
            tx.run(GuildTxOp.SHOP, roomy(), t -> {
                limited.add(JdbcEconomyStore.upsertCounterWithLimit(t, player, COUNTER_SHOP, 101, DAY_KEY, n, 5, NOW));
                return TxOutcome.ok(null);
            });
        }
        assertThat(limited).as("2（新插）→ 4（累加）→ 6 > 5 达上限 → 5（累加）").containsExactly(false, false, true, false);
        assertThat(counterUsed(db, player, COUNTER_SHOP, 101, DAY_KEY)).isEqualTo(5);
    }

    /**
     * 启动期探测：连接池必须是「实际改动行数」语义（Java 增项，guild-economy-spec §11.3：useAffectedRows 缺失时报错，防回归）。缺了它，
     * C1 达上限会回 1（found rows）被当成新插入，捐献次数与限购被突破。
     */
    @Test
    void 连接池影响行数语义探测() throws SQLException {
        GuildStartupChecks checks = db.startup(recorder);
        checks.checkAffectedRowsSemantics(roomy());
        assertThat(db.count("SELECT COUNT(*) FROM guild_daily_counter")).as("探测回滚、不留行").isZero();

        try (DruidDataSource foundRows = new DruidDataSource()) {
            foundRows.setUrl(GuildMysqlFixture.BASE_URL + "/" + db.database + GuildMysqlFixture.PARAMS
                    .replace("&useAffectedRows=true", ""));
            foundRows.setUsername(GuildMysqlFixture.USER);
            foundRows.setPassword(GuildMysqlFixture.PASSWORD);
            GuildStartupChecks wrong = new GuildStartupChecks(new GuildTx(foundRows::getConnection, 10, recorder));
            assertThatThrownBy(() -> wrong.checkAffectedRowsSemantics(roomy())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("useAffectedRows=true");
            // 同一个池上 C1 达上限确实回 1：自检看不出来，这正是要在启动期拒启的原因
            GuildTx wrongTx = new GuildTx(foundRows::getConnection, 10, recorder);
            EconomyFixtures.insertCounter(db, 8991, COUNTER_DONATE, 1, DAY_KEY, 5);
            List<Boolean> limited = new ArrayList<>();
            wrongTx.run(GuildTxOp.DONATE, roomy(), t -> {
                limited.add(JdbcEconomyStore.upsertCounterWithLimit(t, 8991, COUNTER_DONATE, 1, DAY_KEY, 1, 5, NOW));
                return TxOutcome.reject(GuildReject.WRITE_CONFLICT); // 回滚
            });
            assertThat(limited).containsExactly(false);
        }
        assertThat(db.count("SELECT COUNT(*) FROM guild_daily_counter WHERE player_id = 0")).isZero();
    }

    // ================================================================ 提前截止

    /**
     * 退帮 / 被踢 / 解散三个 4.4 事务把本帮未决捐献的截止提前到 now、next_attempt_ms 拉回 now，不抢租约；商店指令与绑定别的帮会的捐献一律不动
     * （TestAccelerate_LeaveKickDisbandPullDeadlineToNow；4.4 钩子换成 {@link EconomyTxHooks}）。
     */
    @Test
    void 退帮被踢解散把截止提前到now() throws SQLException {
        long guildId = 7681, other = 7682, leader = 8681, leaver = 8682, kicked = 8683, stayer = 8684;
        seedGuild(guildId, 2, leader, Map.of(leaver, GuildRoles.MEMBER, kicked, GuildRoles.MEMBER, stayer, GuildRoles.MEMBER));
        seedGuild(other, 2, 8685, Map.of());
        setContribution(db, guildId, leaver, 500, 500);

        DonationReserve leaverDonation = donation(9_680_001, leaver, guildId, NOW);
        DonationReserve kickedDonation = donation(9_680_002, kicked, guildId, NOW);
        DonationReserve stayerDonation = donation(9_680_003, stayer, guildId, NOW);
        for (DonationReserve in : List.of(leaverDonation, kickedDonation, stayerDonation)) {
            assertThat(econ.reserveDonation(in, roomy()).isOk()).isTrue();
        }
        ShopReserve leaverShop = shopOrder(9_680_004, leaver, guildId, 1, 30, 0, NOW);
        assertThat(econ.reserveShopOrder(leaverShop, roomy()).isOk()).isTrue();
        // 绑定别的帮会的一笔（早先在那边发起、至今未决）：过滤条件 guild_id 必须把它排除
        GuildAssetOpRow foreign = EconomyFixtures.donateRecord(9_680_005, leaver, other, NOW, 100,
                EconomyFixtures.donatePayload(100)).setNextAttemptMs(NOW + 30_000).build();
        EconomyFixtures.insertOp(db, foreign);

        long leaveAt = NOW + 100, kickAt = NOW + 200, disbandAt = NOW + 300;
        assertThat(guilds.leaveGuild(guildId, leaver, leaveAt, roomy()).isOk()).isTrue();
        GuildAssetOpRow rec = record(db, leaverDonation.opId());
        assertThat(rec.getDeadlineMs()).as("退帮：截止提前到 now").isEqualTo(leaveAt);
        assertThat(rec.getNextAttemptMs()).as("退帮：next_attempt_ms 拉回 now").isEqualTo(leaveAt);
        assertThat(rec.getLeaseUntilMs()).as("不抢租约").isEqualTo(leaverDonation.leaseUntilMs());
        assertThat(rec.getUpdatedMs()).isEqualTo(leaveAt);
        assertThat(record(db, kickedDonation.opId()).getDeadlineMs()).as("别人的捐献不动").isEqualTo(kickedDonation.deadlineMs());
        GuildAssetOpRow shopRec = record(db, leaverShop.opId());
        assertThat(shopRec.getDeadlineMs()).as("商店指令永不中止，不被提前截止").isZero();
        assertThat(shopRec.getNextAttemptMs()).isEqualTo(leaverShop.leaseUntilMs());
        GuildAssetOpRow foreignRec = record(db, foreign.getOpId());
        assertThat(foreignRec.getDeadlineMs()).as("绑定别的帮会的捐献不动").isEqualTo(foreign.getDeadlineMs());
        assertThat(foreignRec.getNextAttemptMs()).isEqualTo(foreign.getNextAttemptMs());

        assertThat(guilds.kickMember(guildId, leader, kicked, kickAt, roomy()).isOk()).isTrue();
        assertThat(record(db, kickedDonation.opId()).getDeadlineMs()).as("被踢：截止提前到 now").isEqualTo(kickAt);

        assertThat(guilds.disbandGuild(guildId, leader, disbandAt, null, roomy()).isOk()).isTrue();
        assertThat(record(db, stayerDonation.opId()).getDeadlineMs()).as("解散：全体成员的捐献截止提前到 now").isEqualTo(disbandAt);
        assertThat(record(db, leaverDonation.opId()).getDeadlineMs()).as("已到期的行不会被再改").isEqualTo(leaveAt);
        assertThat(record(db, kickedDonation.opId()).getDeadlineMs()).isEqualTo(kickAt);
        recorder.assertNoDeadlocks("退帮 / 被踢 / 解散的提前截止");
    }

    /**
     * 提前截止的过滤与分块（TestAccelerate_ChunksCandidatesAndSkipsNonMatching）：150 个玩家跨两批（100 + 50）候选读，命中行分布在两批
     * 首尾；五类不该动的行各放一条。再用更晚的 now 重放一次：已提前的行不再是候选，重放是 no-op。
     */
    @Test
    void 提前截止分块候选_跳过不匹配_重放是空操作() throws SQLException {
        long guildId = 7951, otherGuild = 7952, firstPlayer = 20_000, opBase = 9_950_000, epoch = NOW - 100_000;
        int players = 150;
        ByteString payload = EconomyFixtures.donatePayload(100);
        List<Long> playerIds = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            playerIds.add(firstPlayer + i);
        }
        Map<Long, Long> hits = new HashMap<>();
        int k = 0;
        for (int idx : new int[] {0, 99, 100, 149}) {
            long opId = opBase + k++;
            EconomyFixtures.insertOp(db, EconomyFixtures.donateRecord(opId, firstPlayer + idx, guildId, epoch, 1, payload).build());
            hits.put(opId, firstPlayer + idx);
        }
        List<GuildAssetOpRow> untouched = List.of(
                EconomyFixtures.shopRecord(opBase + 10, firstPlayer + 1, epoch, 1).setGuildId(guildId).build(),
                EconomyFixtures.donateRecord(opBase + 11, firstPlayer + 2, otherGuild, epoch, 1, payload).build(),
                EconomyFixtures.donateRecord(opBase + 12, firstPlayer + 3, guildId, epoch, 1, payload)
                        .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED).build(),
                EconomyFixtures.donateRecord(opBase + 13, firstPlayer + 4, guildId, epoch, 1, payload).setDeadlineMs(NOW).build(),
                EconomyFixtures.donateRecord(opBase + 14, firstPlayer + 500, guildId, epoch, 1, payload).build());
        for (GuildAssetOpRow row : untouched) {
            EconomyFixtures.insertOp(db, row);
        }

        long at = NOW + 50;
        accelerate(guildId, playerIds, at);
        for (Map.Entry<Long, Long> hit : hits.entrySet()) {
            GuildAssetOpRow rec = record(db, hit.getKey());
            assertThat(rec.getDeadlineMs()).as("op %d（player %d）：截止提前到 now", hit.getKey(), hit.getValue()).isEqualTo(at);
            assertThat(rec.getNextAttemptMs()).as("op %d", hit.getKey()).isEqualTo(at);
            assertThat(rec.getUpdatedMs()).isEqualTo(at);
            assertThat(rec.getStatus()).as("只改截止，不终结").isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        }
        for (GuildAssetOpRow want : untouched) {
            GuildAssetOpRow rec = record(db, want.getOpId());
            assertThat(rec.getDeadlineMs()).as("op %d 不该被提前", want.getOpId()).isEqualTo(want.getDeadlineMs());
            assertThat(rec.getNextAttemptMs()).isEqualTo(want.getNextAttemptMs());
            assertThat(rec.getUpdatedMs()).isEqualTo(want.getUpdatedMs());
        }

        accelerate(guildId, playerIds, at + 1000);
        for (long opId : hits.keySet()) {
            GuildAssetOpRow rec = record(db, opId);
            assertThat(rec.getDeadlineMs()).as("op %d：重放不能把已提前的截止再推后", opId).isEqualTo(at);
            assertThat(rec.getUpdatedMs()).as("op %d：重放是 no-op", opId).isEqualTo(at);
        }
    }

    private void accelerate(long guildId, List<Long> players, long now) {
        db.tx(recorder).run(GuildTxOp.LEAVE, roomy(), t -> {
            EconomyTxHooks.INSTANCE.accelerateDonationDeadlines(t, guildId, players, now);
            return TxOutcome.ok(null);
        });
    }

    // ================================================================ 无符号 ≥ 2^63

    /**
     * Java 增项（guild-economy-spec §11.3）：op_id / player_id / guild_id / 令牌 ≥ 2^63 全程按无符号绑定与读取——按有符号 long 绑定会被
     * 严格模式拒（1264）或在 MySQL 里排序相反。捐献与兑换预留、读回、提前截止的升序（{@link Long#compareUnsigned}）都要对。
     */
    @Test
    void 大于等于2的63次方的id与令牌() throws SQLException {
        long guildId = 0x8000_0000_0000_7001L, leader = 0x8000_0000_0000_8001L, donor = 0xFFFF_FFFF_FFFF_FFF0L;
        seedGuild(guildId, 2, leader, Map.of(donor, GuildRoles.MEMBER));
        setContribution(db, guildId, donor, 1000, 1000);
        long opHigh = 0xFFFF_FFFF_FFFF_FF00L;
        long opLow = 0x7FFF_FFFF_FFFF_FF00L;
        DonationReserve high = donation(opHigh, donor, guildId, NOW);
        assertThat(high.leaseToken()).as("令牌 ≥ 2^63").isNegative();
        assertThat(econ.reserveDonation(high, roomy()).isOk()).isTrue();
        assertThat(econ.reserveDonation(donation(opLow, donor, guildId, NOW + 1), roomy()).isOk()).isTrue();
        ShopReserved shop = econ.reserveShopOrder(shopOrder(0x8000_0000_0000_0001L, donor, guildId, 1, 30, 5, NOW), roomy())
                .orThrow();
        assertThat(shop.balanceAfter()).isEqualTo(970);

        GuildAssetOpRow rec = record(db, opHigh);
        assertThat(rec.getOpId()).isEqualTo(opHigh);
        assertThat(rec.getPlayerId()).isEqualTo(donor);
        assertThat(rec.getGuildId()).isEqualTo(guildId);
        assertThat(rec.getLeaseToken()).isEqualTo(high.leaseToken());
        assertThat(db.queryU64("SELECT lease_token FROM guild_asset_op WHERE op_id = ?", opHigh))
                .isEqualTo(high.leaseToken());
        assertThat(econ.pendingOps(donor, DEBIT, 16, roomy())).extracting(GuildAssetOpRow::getOpId)
                .containsExactly(opHigh, opLow);
        assertThat(econ.opState(opHigh, roomy())).isPresent();
        assertThat(econ.memberContribution(guildId, donor, roomy())).contains(new EconomyStore.Contribution(1000, 970));

        // 退帮：候选按无符号升序逐行点改（低 id 在前）
        long leaveAt = NOW + 100;
        assertThat(guilds.leaveGuild(guildId, donor, leaveAt, roomy()).isOk()).isTrue();
        assertThat(record(db, opHigh).getDeadlineMs()).isEqualTo(leaveAt);
        assertThat(record(db, opLow).getDeadlineMs()).isEqualTo(leaveAt);
        assertThat(JdbcGuildStore.sortedUnique(List.of(opHigh, opLow, opHigh))).containsExactly(opLow, opHigh);
        recorder.assertNoDeadlocks("≥ 2^63 的 id");
    }
}
