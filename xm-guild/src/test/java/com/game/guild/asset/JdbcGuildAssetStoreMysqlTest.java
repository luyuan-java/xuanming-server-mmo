package com.game.guild.asset;

import static com.game.guild.store.EconomyFixtures.COUNTER_ACTIVITY;
import static com.game.guild.store.EconomyFixtures.COUNTER_DONATE;
import static com.game.guild.store.EconomyFixtures.COUNTER_SHOP;
import static com.game.guild.store.EconomyFixtures.CREDIT;
import static com.game.guild.store.EconomyFixtures.DAY_KEY;
import static com.game.guild.store.EconomyFixtures.DAY_MS;
import static com.game.guild.store.EconomyFixtures.DEBIT;
import static com.game.guild.store.EconomyFixtures.LEASE_MS;
import static com.game.guild.store.EconomyFixtures.abortedPlaceholder;
import static com.game.guild.store.EconomyFixtures.applied;
import static com.game.guild.store.EconomyFixtures.contribution;
import static com.game.guild.store.EconomyFixtures.counterUsed;
import static com.game.guild.store.EconomyFixtures.donateRecord;
import static com.game.guild.store.EconomyFixtures.donation;
import static com.game.guild.store.EconomyFixtures.funds;
import static com.game.guild.store.EconomyFixtures.insertOp;
import static com.game.guild.store.EconomyFixtures.insertOpsBulk;
import static com.game.guild.store.EconomyFixtures.opExists;
import static com.game.guild.store.EconomyFixtures.record;
import static com.game.guild.store.EconomyFixtures.rejected;
import static com.game.guild.store.EconomyFixtures.roomy;
import static com.game.guild.store.EconomyFixtures.setContribution;
import static com.game.guild.store.EconomyFixtures.setFunds;
import static com.game.guild.store.EconomyFixtures.shopOrder;
import static com.game.guild.store.EconomyFixtures.shopRecord;
import static com.game.guild.store.GuildMysqlFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetOutcome;
import com.game.common.time.GameDay;
import com.game.guild.asset.GuildAssetStore.AppliedOpBrief;
import com.game.guild.asset.GuildAssetStore.AppliedOpsPage;
import com.game.guild.asset.GuildAssetStore.Claimed;
import com.game.guild.asset.GuildAssetStore.ClaimResult;
import com.game.guild.asset.GuildAssetStore.CleanupReport;
import com.game.guild.asset.GuildAssetStore.CleanupTable;
import com.game.guild.asset.GuildAssetStore.FinalizeResult;
import com.game.guild.asset.GuildAssetStore.FinalizedOp;
import com.game.guild.asset.GuildAssetStore.Lost;
import com.game.guild.asset.GuildAssetStore.Orphan;
import com.game.guild.asset.GuildAssetStore.Poisoned;
import com.game.guild.asset.GuildAssetStore.RescheduleResult;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.EconomyFixtures;
import com.game.guild.store.EconomyFixtures.AssetEvents;
import com.game.guild.store.EconomyFixtures.BackgroundRecorder;
import com.game.guild.store.EconomyStore.DonationReserve;
import com.game.guild.store.EconomyStore.ShopReserve;
import com.game.guild.store.EconomyTxHooks;
import com.game.guild.store.GuildMysqlFixture;
import com.game.guild.store.GuildMysqlFixture.Recorder;
import com.game.guild.store.GuildTxOp;
import com.game.guild.store.Invalidation;
import com.game.guild.store.JdbcEconomyStore;
import com.game.guild.store.JdbcGuildStore;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.TransactionType;
import com.google.protobuf.ByteString;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 资产 Store 的真库用例（缺省跳过；移植 mmorpg economy_repo_test.go:1230-1740、:1890-1948 与 asset_op_divergence_repo_test.go:167-371；
 * guild-economy-spec §11.3）：ListDue 新行优先、Claim 返回整行并持租约、毒行推迟（含已被人工终结的竞态）、租约丢失、E12 的重排变体、
 * Finalize 全部分支（三种 orphan、PARTIAL 只 CAS、活动只 CAS、未知 kind、行不存在）、人工终结写审计列并同写 next_attempt、最老未决、
 * assetopfix 的两个读、清理保留 PENDING 与 PARTIAL、回档检查 G1–G6 与 G5b、令牌 ≥ 2^63。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class JdbcGuildAssetStoreMysqlTest {

    private static GuildMysqlFixture db;
    private Recorder recorder;
    private BackgroundRecorder background;
    private AssetEvents events;
    private JdbcEconomyStore econ;
    private JdbcGuildStore guilds;
    private JdbcGuildAssetStore store;

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
        background = new BackgroundRecorder();
        events = new AssetEvents();
        econ = EconomyFixtures.economy(db, recorder);
        guilds = db.store(recorder, EconomyTxHooks.INSTANCE);
        store = EconomyFixtures.assets(db, background, events);
    }

    private void seedGuild(long guildId, long leader, Map<Long, Integer> roles) throws SQLException {
        db.seedGuild(guildId, 2, 1, 50, leader, roles);
    }

    private DonationReserve reserveDonation(long opId, long donor, long guildId) {
        DonationReserve in = donation(opId, donor, guildId, NOW);
        assertThat(econ.reserveDonation(in, roomy()).isOk()).isTrue();
        return in;
    }

    private FinalizeResult finalizeOp(long opId, AssetOpStatus status, AssetOpResult res, long now) {
        return store.finalizeOp(opId, status, res, now, DeliveryOrigin.LOOP, roomy());
    }

    // ================================================================ 领取与重排

    /** 第一段只取新行并优先占名额，第二段只补缺口；租约未到期、未到期、非未决的行都不出现（TestListDue_FreshRowsFirst）。 */
    @Test
    void 到期行新行优先() throws SQLException {
        long player = 8571, epoch = NOW - 100_000;
        for (int i = 0; i < 3; i++) { // 老行：attempts 已过新行判据，且更早到期
            insertOp(db, shopRecord(101 + i, player, epoch, i + 1).setAttempts(5).setNextAttemptMs(NOW - 50_000 + i).build());
        }
        for (int i = 0; i < 2; i++) { // 新行
            insertOp(db, shopRecord(201 + i, player, epoch, 10 + i).setNextAttemptMs(NOW - 10 + i).build());
        }
        insertOp(db, shopRecord(301, player, epoch, 20).setNextAttemptMs(NOW + 1000).build());
        insertOp(db, shopRecord(302, player, epoch, 21).setLeaseUntilMs(NOW + 5000).build());
        insertOp(db, shopRecord(303, player, epoch, 22).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED).build());

        assertThat(store.listDue(NOW, 2, roomy())).as("新行满额时老行一条都不取").containsExactly(201L, 202L);
        assertThat(store.listDue(NOW, 4, roomy())).as("第二段只补缺口，新行排在前面").containsExactly(201L, 202L, 101L, 102L);
        assertThat(store.listDue(NOW, 100, roomy())).containsExactly(201L, 202L, 101L, 102L, 103L);
        // 新行判据是 attempts < 3
        db.exec("UPDATE guild_asset_op SET attempts = 2 WHERE op_id = 101");
        assertThat(store.listDue(NOW, 3, roomy())).containsExactly(101L, 201L, 202L);
    }

    /** 领到的 Op 带齐纪元 / correlation（= op_id）/ 令牌 / 包 / 库里的截止与次数；租约期内别人领不到（TestClaim_ReturnsFullOpAndHoldsLease）。 */
    @Test
    void 领取返回整行并持租约() throws Exception {
        long player = 8581;
        GuildAssetOpRow row = shopRecord(401, player, NOW - 100_000, 7).setAttempts(2)
                .setLastReason(AssetOpDecisions.REASON_BAG_FULL).setDeadlineMs(NOW + 99).build();
        insertOp(db, row);

        long token = 0xabc1;
        ClaimResult claimed = store.claim(401, NOW, NOW + LEASE_MS, NOW + 3_600_000, token, roomy());
        assertThat(claimed).isInstanceOf(Claimed.class);
        AssetOp op = ((Claimed) claimed).op();
        assertThat(op.opId()).isEqualTo(401);
        assertThat(op.playerId()).isEqualTo(player);
        assertThat(op.stream()).isEqualTo(CREDIT);
        assertThat(op.seq()).isEqualTo(7);
        assertThat(op.streamEpoch()).isEqualTo(row.getStreamEpoch());
        assertThat(op.correlationId()).as("correlation_id 取 op_id，不是 ref_id").isEqualTo(401);
        assertThat(op.leaseToken()).isEqualTo(token);
        assertThat(op.txType()).isEqualTo(TransactionType.TX_GUILD_SHOP_VALUE);
        assertThat(op.attempts()).isEqualTo(2);
        assertThat(op.lastReason()).isEqualTo(AssetOpDecisions.REASON_BAG_FULL);
        assertThat(op.deadlineMs()).as("截止取库里的值（看得到离帮提前截止）").isEqualTo(NOW + 99);
        assertThat(op.bundle()).isEqualTo(AssetBundle.parseFrom(row.getPayload()));

        GuildAssetOpRow stored = record(db, 401);
        assertThat(stored.getLeaseUntilMs()).isEqualTo(NOW + LEASE_MS);
        assertThat(stored.getLeaseToken()).isEqualTo(token);
        assertThat(stored.getUpdatedMs()).isEqualTo(NOW);

        assertThat(store.claim(401, NOW + 1, NOW + 1 + LEASE_MS, NOW + 3_600_000, 0xabc2, roomy()))
                .as("租约期内另一个副本领不到").isEqualTo(Lost.INSTANCE);
        assertThat(store.claim(999, NOW, NOW + LEASE_MS, NOW + 3_600_000, 0xabc3, roomy())).isEqualTo(Lost.INSTANCE);
        // 终态行领不到
        insertOp(db, shopRecord(402, player, NOW - 100_000, 8).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED).build());
        assertThat(store.claim(402, NOW, NOW + LEASE_MS, NOW + 3_600_000, 0xabc5, roomy())).isEqualTo(Lost.INSTANCE);
    }

    /**
     * payload 解不开或为 NULL / 空都是毒行：推迟到循环给的 poisonUntilMs、放掉租约、last_outcome 记 UNKNOWN；不许把空包下发给 scene。
     * 竞态：领取的 CAS 之后行已被人工终结——推迟不能动终态行（TestClaim_PoisonRowIsDeferred）。
     */
    @Test
    void 毒行推迟_不动终态行() throws SQLException {
        long player = 8591, poisonUntil = NOW + 3_600_000;
        insertOp(db, shopRecord(501, player, NOW - 100_000, 1).setPayload(ByteString.copyFrom(new byte[] {(byte) 0xff, (byte) 0xff}))
                .build());
        insertOp(db, shopRecord(502, player, NOW - 100_000, 2).build());
        db.exec("UPDATE guild_asset_op SET payload = NULL WHERE op_id = 502");
        insertOp(db, shopRecord(504, player, NOW - 100_000, 4).setPayload(ByteString.EMPTY).build());

        for (long opId : new long[] {501, 502, 504}) {
            ClaimResult result = store.claim(opId, NOW, NOW + LEASE_MS, poisonUntil, 0x77, roomy());
            assertThat(result).as("op %d", opId).isInstanceOf(Poisoned.class);
            GuildAssetOpRow stored = record(db, opId);
            assertThat(stored.getNextAttemptMs()).as("毒行推迟到循环算好的时刻").isEqualTo(poisonUntil);
            assertThat(stored.getLeaseUntilMs()).as("毒行放掉租约").isZero();
            assertThat(stored.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE);
            assertThat(stored.getStatus()).as("毒行不终结").isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        }

        long token = 0x78, resolvedAt = NOW - 5;
        insertOp(db, shopRecord(503, player, NOW - 100_000, 3).setPayload(ByteString.copyFrom(new byte[] {(byte) 0xff}))
                .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED).setLeaseToken(token).setNextAttemptMs(resolvedAt)
                .setUpdatedMs(resolvedAt).setLastOutcome(AssetOutcome.ASSET_OUTCOME_RETRY_VALUE).build());
        store.markPoison(503, token, NOW, poisonUntil, roomy());
        GuildAssetOpRow stored = record(db, 503);
        assertThat(stored.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED);
        assertThat(stored.getNextAttemptMs()).as("终态行的 next_attempt_ms 必须仍是终结时刻").isEqualTo(resolvedAt);
        assertThat(stored.getUpdatedMs()).as("终态行此后没有任何路径再改").isEqualTo(resolvedAt);
        assertThat(stored.getLastOutcome()).as("证据不能被抹成 0").isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY_VALUE);
        // 推迟时令牌已换（别的副本接管）：同样一个字不动
        store.markPoison(501, 0x99, NOW + 1, poisonUntil + 1, roomy());
        assertThat(record(db, 501).getNextAttemptMs()).isEqualTo(poisonUntil);
        background.assertNoDeadlocks("毒行");
    }

    /** 重排带错令牌必须回 LEASE_LOST（不能伪装成功）；带对令牌则推进次数、放租约、记下本次答复（TestClaimThenReschedule_LeaseLost）。 */
    @Test
    void 重排_租约丢失_与E12不覆盖答复() throws SQLException {
        long player = 8601;
        insertOp(db, shopRecord(601, player, NOW - 100_000, 1).build());
        AssetOp op = ((Claimed) store.claim(601, NOW, NOW + LEASE_MS, NOW + 3_600_000, 0xa1, roomy())).op();

        AssetOpResult retry = new AssetOpResult(AssetOutcome.ASSET_OUTCOME_RETRY, AssetOpDecisions.REASON_IN_BATTLE, false, false,
                false);
        AssetOp stolen = new AssetOp(op.opId(), op.playerId(), op.stream(), op.seq(), op.streamEpoch(), op.correlationId(),
                op.txType(), op.bundle(), op.attempts(), op.deadlineMs(), 0xb2, op.lastReason());
        assertThat(store.reschedule(stolen, NOW + 5000, retry, NOW + 1, roomy())).isEqualTo(RescheduleResult.LEASE_LOST);
        assertThat(record(db, 601).getAttempts()).as("租约不是我的，一个字都不能写进去").isZero();

        assertThat(store.reschedule(op, NOW + 5000, retry, NOW + 1, roomy())).isEqualTo(RescheduleResult.RESCHEDULED);
        GuildAssetOpRow stored = record(db, 601);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getNextAttemptMs()).isEqualTo(NOW + 5000);
        assertThat(stored.getLeaseUntilMs()).isZero();
        assertThat(stored.getDurable()).isZero();
        assertThat(stored.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY_VALUE);
        assertThat(stored.getLastReason()).isEqualTo(AssetOpDecisions.REASON_IN_BATTLE);
        assertThat(stored.getUpdatedMs()).isEqualTo(NOW + 1);
        assertThat(stored.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(store.reschedule(op, NOW + 6000, retry, NOW + 2, roomy()))
                .as("租约已清但令牌未换：同一令牌再重排照样命中（令牌只在领取时换）").isEqualTo(RescheduleResult.RESCHEDULED);

        // E12：传输失败的重排只推进 attempts / next_attempt_ms / 清租约，不覆盖 last_outcome / last_reason / durable
        AssetOp again = ((Claimed) store.claim(601, NOW + 7000, NOW + 7000 + LEASE_MS, NOW + 3_600_000, 0xa3, roomy())).op();
        assertThat(store.rescheduleWithoutAnswer(again, NOW + 9000, NOW + 7001, roomy())).isEqualTo(RescheduleResult.RESCHEDULED);
        stored = record(db, 601);
        assertThat(stored.getAttempts()).isEqualTo(3);
        assertThat(stored.getNextAttemptMs()).isEqualTo(NOW + 9000);
        assertThat(stored.getLeaseUntilMs()).isZero();
        assertThat(stored.getLastOutcome()).as("E12：保留上一个真实答复").isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY_VALUE);
        assertThat(stored.getLastReason()).isEqualTo(AssetOpDecisions.REASON_IN_BATTLE);
        assertThat(store.rescheduleWithoutAnswer(stolen, NOW + 9000, NOW + 7002, roomy())).isEqualTo(RescheduleResult.LEASE_LOST);
        // 本地 NOT_HERE 是答复：照常覆盖（reason 0）
        AssetOp third = ((Claimed) store.claim(601, NOW + 10_000, NOW + 10_000 + LEASE_MS, NOW + 3_600_000, 0xa5, roomy())).op();
        assertThat(store.reschedule(third, NOW + 12_000, AssetOpResult.localNotHere(), NOW + 10_001, roomy()))
                .isEqualTo(RescheduleResult.RESCHEDULED);
        assertThat(record(db, 601).getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE_VALUE);
        assertThat(record(db, 601).getLastReason()).isZero();
        // 终态行（点锁读得到但 CAS 落空）与已被清理的行（点锁读不到）都是 LEASE_LOST
        db.exec("UPDATE guild_asset_op SET status = ? WHERE op_id = 601", GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_VALUE);
        assertThat(store.reschedule(third, NOW + 13_000, AssetOpResult.localNotHere(), NOW + 12_001, roomy()))
                .isEqualTo(RescheduleResult.LEASE_LOST);
        db.exec("DELETE FROM guild_asset_op WHERE op_id = 601");
        assertThat(store.rescheduleWithoutAnswer(third, NOW + 13_000, NOW + 12_001, roomy())).isEqualTo(RescheduleResult.LEASE_LOST);
        background.assertNoDeadlocks("重排");
    }

    // ================================================================ 终结与对侧账

    /**
     * APPLIED 按 op.guild_id 记资金与帮贡、同写 next_attempt_ms = now、失效缓存、回调一次；重复终结 CAS 落空，对侧账不做第二遍
     * （TestFinalize_DonateAppliedCreditsFundsAndContribution）。
     */
    @Test
    void 捐献已应用记资金与帮贡() throws SQLException {
        long guildId = 7611, leader = 8611, donor = 8612;
        seedGuild(guildId, leader, Map.of(donor, GuildRoles.MEMBER));
        DonationReserve in = reserveDonation(9_610_001, donor, guildId);

        long finalNow = NOW + 1234;
        FinalizeResult res = finalizeOp(in.opId(), AssetOpStatus.APPLIED, applied(), finalNow);
        assertThat(res.finalized()).isTrue();
        assertThat(res.counterparty()).isEqualTo(new GuildAssetStore.Counterparty(true, null, false));
        assertThat(funds(db, guildId)).isEqualTo(1000);
        assertThat(contribution(db, guildId, donor)).containsExactly(10, 10);

        GuildAssetOpRow rec = record(db, in.opId());
        assertThat(rec.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
        assertThat(rec.getDurable()).isEqualTo(1);
        assertThat(rec.getNextAttemptMs()).as("终态行的 next_attempt_ms 必须等于终结时刻（回档检查按它判）").isEqualTo(finalNow);
        assertThat(rec.getUpdatedMs()).isEqualTo(finalNow);
        assertThat(rec.getLeaseUntilMs()).isZero();
        assertThat(rec.getReasonTipId()).isZero();
        assertThat(rec.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED_VALUE);

        assertThat(events.invalidations).as("改过帮会 / 成员行：失效 G 与 p 的映射")
                .containsExactly(Invalidation.of(GuildTxOp.ASSET_FINALIZE, guildId, donor));
        assertThat(events.finalized).containsExactly(new FinalizedOp(in.opId(), donor, guildId,
                GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, AssetOpStatus.APPLIED, DeliveryOrigin.LOOP));
        assertThat(events.orphans).isEmpty();

        assertThat(finalizeOp(in.opId(), AssetOpStatus.APPLIED, applied(), finalNow + 1)).as("第二次终结 CAS 落空")
                .isEqualTo(FinalizeResult.NOT_FINALIZED);
        assertThat(funds(db, guildId)).as("对侧账只记一次").isEqualTo(1000);
        assertThat(events.finalized).as("只有本次终结才回调").hasSize(1);
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).as("APPLIED 保留次数").isEqualTo(1);
        // 同步投递的终结带 SYNC（推送层据此不推）
        DonationReserve sync = reserveDonation(9_610_002, donor, guildId);
        store.finalizeOp(sync.opId(), AssetOpStatus.APPLIED, applied(), finalNow + 2, DeliveryOrigin.SYNC, roomy());
        assertThat(events.finalized.getLast().origin()).isEqualTo(DeliveryOrigin.SYNC);
        background.assertNoDeadlocks("终结");
    }

    /** D2：绑定的帮会已解散，资金与帮贡都记不上，计 orphan{donate,guild_gone}，不补偿，也不能凭空把帮会行写回来。 */
    @Test
    void 捐献已应用但帮会已解散是孤儿() throws SQLException {
        long guildId = 7621, leader = 8621, donor = 8622;
        seedGuild(guildId, leader, Map.of(donor, GuildRoles.MEMBER));
        DonationReserve in = reserveDonation(9_620_001, donor, guildId);
        assertThat(guilds.disbandGuild(guildId, leader, NOW + 10, null, roomy()).isOk()).isTrue();

        FinalizeResult res = finalizeOp(in.opId(), AssetOpStatus.APPLIED, applied(), NOW + 20);
        assertThat(res.finalized()).as("帮会不在也要终结，否则这一行会被永远重投").isTrue();
        assertThat(res.counterparty()).isEqualTo(new GuildAssetStore.Counterparty(false, Orphan.DONATE_GUILD_GONE, false));
        assertThat(events.orphans).containsExactly(Orphan.DONATE_GUILD_GONE);
        assertThat(events.invalidations).as("没改帮会 / 成员行就不失效").isEmpty();
        assertThat(db.guildRows(guildId)).as("资金无处可记，帮会行不能被写回来").isZero();
        assertThat(db.memberships(donor)).isZero();
        assertThat(record(db, in.opId()).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
    }

    /** D2：捐献者已离帮而帮会还在，资金照记给 op.guild_id，帮贡跳过，计 orphan{donate,member_gone}。 */
    @Test
    void 捐献已应用但捐献者已离帮只记资金() throws SQLException {
        long guildId = 7631, leader = 8631, donor = 8632;
        seedGuild(guildId, leader, Map.of(donor, GuildRoles.MEMBER));
        DonationReserve in = reserveDonation(9_630_001, donor, guildId);
        assertThat(guilds.leaveGuild(guildId, donor, NOW + 10, roomy()).isOk()).isTrue();

        FinalizeResult res = finalizeOp(in.opId(), AssetOpStatus.APPLIED, applied(), NOW + 20);
        assertThat(res.finalized()).isTrue();
        assertThat(res.counterparty()).isEqualTo(new GuildAssetStore.Counterparty(true, Orphan.DONATE_MEMBER_GONE, false));
        assertThat(funds(db, guildId)).as("资金照记给发起时绑定的帮会").isEqualTo(1000);
        assertThat(contribution(db, guildId, donor)).as("成员行不能被写回来").isNull();
        assertThat(contribution(db, guildId, leader)).as("帮贡不能错记到别人头上").containsExactly(0, 0);
        assertThat(events.orphans).containsExactly(Orphan.DONATE_MEMBER_GONE);
        assertThat(events.invalidations).containsExactly(Invalidation.of(GuildTxOp.ASSET_FINALIZE, guildId, donor));
    }

    /** REJECTED / ABORTED 退回今日次数；reason_tip_id 只在 REJECTED 时写 scene 原因（ABORTED 写 0）；资金不动。 */
    @Test
    void 捐献被拒或中止退次数() throws SQLException {
        long guildId = 7641, leader = 8641, donor = 8642;
        seedGuild(guildId, leader, Map.of(donor, GuildRoles.MEMBER));
        DonationReserve rejectedOne = reserveDonation(9_640_001, donor, guildId);
        assertThat(finalizeOp(rejectedOne.opId(), AssetOpStatus.REJECTED, rejected(), NOW + 10).finalized()).isTrue();
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).as("REJECTED 退回次数").isZero();
        GuildAssetOpRow rec = record(db, rejectedOne.opId());
        assertThat(rec.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED);
        assertThat(rec.getReasonTipId()).isEqualTo(AssetOpDecisions.REASON_CURRENCY_INSUFFICIENT);
        assertThat(rec.getLastReason()).isEqualTo(AssetOpDecisions.REASON_CURRENCY_INSUFFICIENT);

        DonationReserve abortedOne = donation(9_640_002, donor, guildId, NOW + 20);
        assertThat(econ.reserveDonation(abortedOne, roomy()).isOk()).isTrue();
        assertThat(finalizeOp(abortedOne.opId(), AssetOpStatus.ABORTED, abortedPlaceholder(), NOW + 30).finalized()).isTrue();
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).as("ABORTED 退回次数").isZero();
        assertThat(record(db, abortedOne.opId()).getReasonTipId()).as("ABORTED 的 reason_tip_id 写 0").isZero();
        assertThat(funds(db, guildId)).as("被拒 / 中止的捐献不记资金").isZero();
        assertThat(events.invalidations).as("只退了计数：不失效").isEmpty();
        // 计数行已不在（被清理 / 本来就没有）：退款影响 0 行无害；兜到 0 防减穿
        DonationReserve third = donation(9_640_003, donor, guildId, NOW + 40);
        assertThat(econ.reserveDonation(third, roomy()).isOk()).isTrue();
        db.exec("UPDATE guild_daily_counter SET used_count = 0 WHERE player_id = ?", donor);
        assertThat(finalizeOp(third.opId(), AssetOpStatus.REJECTED, rejected(), NOW + 50).finalized()).isTrue();
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).as("兜到 0，不报 1690").isZero();
        db.exec("DELETE FROM guild_daily_counter WHERE player_id = ?", donor);
        DonationReserve fourth = donation(9_640_004, donor, guildId, NOW + 60);
        assertThat(econ.reserveDonation(fourth, roomy()).isOk()).isTrue();
        db.exec("DELETE FROM guild_daily_counter WHERE player_id = ?", donor);
        assertThat(finalizeOp(fourth.opId(), AssetOpStatus.ABORTED, abortedPlaceholder(), NOW + 70).finalized()).isTrue();
    }

    /** 部分发放只终结、不做对侧账：商店不退帮贡不退限购，捐献不记资金不退次数（TestFinalize_AppliedPartialOnlyCAS）。 */
    @Test
    void 部分发放只CAS() throws SQLException {
        long guildId = 7651, leader = 8651, player = 8652;
        seedGuild(guildId, leader, Map.of(player, GuildRoles.MEMBER));
        setContribution(db, guildId, player, 1000, 1000);
        ShopReserve shop = shopOrder(9_650_001, player, guildId, 2, 300, 5, NOW);
        assertThat(econ.reserveShopOrder(shop, roomy()).isOk()).isTrue();
        DonationReserve donate = donation(9_650_002, player, guildId, NOW + 1);
        assertThat(econ.reserveDonation(donate, roomy()).isOk()).isTrue();

        AssetOpResult partial = new AssetOpResult(AssetOutcome.ASSET_OUTCOME_APPLIED, AssetOpDecisions.REASON_PARTIAL_APPLIED,
                true, true, false);
        for (long opId : new long[] {shop.opId(), donate.opId()}) {
            FinalizeResult res = finalizeOp(opId, AssetOpStatus.APPLIED_PARTIAL, partial, NOW + 50);
            assertThat(res.finalized()).isTrue();
            assertThat(res.counterparty()).isEqualTo(GuildAssetStore.Counterparty.NONE);
            GuildAssetOpRow rec = record(db, opId);
            assertThat(rec.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL);
            assertThat(rec.getNextAttemptMs()).isEqualTo(NOW + 50);
            assertThat(rec.getReasonTipId()).isZero();
        }
        assertThat(contribution(db, guildId, player)[1]).as("部分发放不退帮贡").isEqualTo(700);
        assertThat(counterUsed(db, player, COUNTER_SHOP, 101, DAY_KEY)).as("部分发放不退限购").isEqualTo(2);
        assertThat(counterUsed(db, player, COUNTER_DONATE, 1, DAY_KEY)).as("部分发放不退次数").isEqualTo(1);
        assertThat(funds(db, guildId)).as("部分发放不记资金").isZero();
        assertThat(events.invalidations).isEmpty();
    }

    /** 永久拒绝退帮贡与限购；兑换者已离帮时帮贡退不回去，计 orphan{shop,refund_member_gone}，限购照退。 */
    @Test
    void 兑换被拒退帮贡与限购_离帮后只退限购() throws SQLException {
        long guildId = 7661, leader = 8661, buyer = 8662;
        seedGuild(guildId, leader, Map.of(buyer, GuildRoles.MEMBER));
        setContribution(db, guildId, buyer, 1000, 1000);
        ShopReserve first = shopOrder(9_660_001, buyer, guildId, 2, 300, 5, NOW);
        assertThat(econ.reserveShopOrder(first, roomy()).isOk()).isTrue();
        AssetOpResult bagFull = new AssetOpResult(AssetOutcome.ASSET_OUTCOME_REJECTED, AssetOpDecisions.REASON_BAG_FULL, true,
                false, false);
        FinalizeResult res = finalizeOp(first.opId(), AssetOpStatus.REJECTED, bagFull, NOW + 10);
        assertThat(res.counterparty().touched()).isTrue();
        assertThat(contribution(db, guildId, buyer)[1]).as("永久拒绝退帮贡").isEqualTo(1000);
        assertThat(counterUsed(db, buyer, COUNTER_SHOP, 101, DAY_KEY)).as("永久拒绝退限购").isZero();
        assertThat(record(db, first.opId()).getReasonTipId()).isEqualTo(AssetOpDecisions.REASON_BAG_FULL);
        assertThat(events.invalidations).containsExactly(Invalidation.of(GuildTxOp.ASSET_FINALIZE, guildId, buyer));

        ShopReserve second = shopOrder(9_660_002, buyer, guildId, 1, 150, 5, NOW + 20);
        assertThat(econ.reserveShopOrder(second, roomy()).isOk()).isTrue();
        assertThat(guilds.leaveGuild(guildId, buyer, NOW + 30, roomy()).isOk()).isTrue();
        FinalizeResult orphan = finalizeOp(second.opId(), AssetOpStatus.ABORTED, abortedPlaceholder(), NOW + 40);
        assertThat(orphan.finalized()).isTrue();
        assertThat(orphan.counterparty()).isEqualTo(new GuildAssetStore.Counterparty(false, Orphan.SHOP_REFUND_MEMBER_GONE,
                false));
        assertThat(events.orphans).containsExactly(Orphan.SHOP_REFUND_MEMBER_GONE);
        assertThat(contribution(db, guildId, buyer)).as("离帮者的成员行不能被退款写回来").isNull();
        assertThat(counterUsed(db, buyer, COUNTER_SHOP, 101, DAY_KEY)).as("限购属于玩家，照退").isZero();
        // 商店 APPLIED：无对侧账
        setContribution(db, guildId, leader, 500, 500);
        ShopReserve third = shopOrder(9_660_003, leader, guildId, 1, 50, 0, NOW + 50);
        assertThat(econ.reserveShopOrder(third, roomy()).isOk()).isTrue();
        assertThat(finalizeOp(third.opId(), AssetOpStatus.APPLIED, applied(), NOW + 60).counterparty())
                .isEqualTo(GuildAssetStore.Counterparty.NONE);
        assertThat(contribution(db, guildId, leader)[1]).isEqualTo(450);
    }

    /**
     * 活动发奖的帮贡与资金在入队事务里已记完（4.6），终结（APPLIED 或 REJECTED）只做 CAS——帮会资金、成员帮贡、任何计数行都不动，不计 orphan；
     * 终态行照样同写 next_attempt_ms = now（TestFinalize_ActivityRewardOnlyCAS）。未知 kind 同样只 CAS、提交后打 ERROR。
     */
    @Test
    void 活动发奖与未知kind只CAS() throws SQLException {
        long guildId = 7941, leader = 8941, player = 8942;
        seedGuild(guildId, leader, Map.of(player, GuildRoles.MEMBER));
        setFunds(db, guildId, 777);
        setContribution(db, guildId, player, 100, 60);
        for (int kind : new int[] {COUNTER_DONATE, COUNTER_SHOP, COUNTER_ACTIVITY}) {
            EconomyFixtures.insertCounter(db, player, kind, 1, DAY_KEY, 2);
        }
        for (int i = 0; i < 3; i++) {
            GuildAssetOpRow.Builder b = shopRecord(9_940_001 + i, player, NOW - 100_000, i + 1).setGuildId(guildId)
                    .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD)
                    .setTxType(TransactionType.TX_GUILD_ACTIVITY_REWARD_VALUE).setRefId(1).setRefCount(1).setPeriodKey(DAY_KEY)
                    .setContributionDelta(50).setFundsDelta(500);
            if (i == 2) {
                b.setKindValue(9); // 未知 kind
            }
            insertOp(db, b.build());
        }
        record Case(long opId, AssetOpStatus status, AssetOpResult res, long now, boolean unknown) {
        }
        for (Case c : List.of(new Case(9_940_001, AssetOpStatus.APPLIED, applied(), NOW + 100, false),
                new Case(9_940_002, AssetOpStatus.REJECTED, rejected(), NOW + 200, false),
                new Case(9_940_003, AssetOpStatus.REJECTED, rejected(), NOW + 300, true))) {
            FinalizeResult res = finalizeOp(c.opId(), c.status(), c.res(), c.now());
            assertThat(res.finalized()).as("op %d", c.opId()).isTrue();
            assertThat(res.counterparty()).isEqualTo(new GuildAssetStore.Counterparty(false, null, c.unknown()));
            GuildAssetOpRow rec = record(db, c.opId());
            assertThat(rec.getStatus()).isEqualTo(AssetOpStatus.toRecord(c.status()));
            assertThat(rec.getNextAttemptMs()).as("op %d：终态行的 next_attempt_ms 必须等于终结时刻", c.opId()).isEqualTo(c.now());
            assertThat(rec.getUpdatedMs()).isEqualTo(c.now());
        }
        assertThat(funds(db, guildId)).as("活动发奖终结不动帮会资金").isEqualTo(777);
        assertThat(contribution(db, guildId, player)).as("活动发奖终结不动帮贡").containsExactly(100, 60);
        for (int kind : new int[] {COUNTER_DONATE, COUNTER_SHOP, COUNTER_ACTIVITY}) {
            assertThat(counterUsed(db, player, kind, 1, DAY_KEY)).as("不退任何计数（kind=%d）", kind).isEqualTo(2);
        }
        assertThat(events.orphans).as("只 CAS 的终结不计 orphan").isEmpty();
        assertThat(events.finalized).as("本次终结照常回调").hasSize(3);
        assertThat(events.finalized.get(0).kind()).isEqualTo(GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD);
        // 行不存在：不终结、不报错
        assertThat(finalizeOp(9_949_999, AssetOpStatus.APPLIED, applied(), NOW)).isEqualTo(FinalizeResult.NOT_FINALIZED);
    }

    /**
     * 人工终结走同一把 CAS 与同一份对侧账，留下操作人与依据、同写 next_attempt_ms = now，不置 durable、不改 last_outcome；之后自动终结落空
     * （TestResolveManually_WritesAuditAndNextAttempt）。
     */
    @Test
    void 人工终结写审计列并同写next_attempt() throws SQLException {
        long guildId = 7671, leader = 8671, donor = 8672;
        seedGuild(guildId, leader, Map.of(donor, GuildRoles.MEMBER));
        DonationReserve in = reserveDonation(9_670_001, donor, guildId);
        db.exec("UPDATE guild_asset_op SET last_outcome = ?, attempts = 12 WHERE op_id = ?", AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE,
                in.opId());

        long resolveNow = NOW + 7_200_000;
        ManualResolution manual = new ManualResolution(in.opId(), AssetOpStatus.APPLIED, "ops-alice", "交易流水已核对，已扣款");
        FinalizeResult res = store.resolveManually(manual, resolveNow, roomy());
        assertThat(res.finalized()).isTrue();
        assertThat(res.op().origin()).isEqualTo(DeliveryOrigin.MANUAL);

        GuildAssetOpRow rec = record(db, in.opId());
        assertThat(rec.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
        assertThat(rec.getResolvedBy()).isEqualTo("ops-alice");
        assertThat(rec.getResolveReason()).isEqualTo(manual.reason());
        assertThat(rec.getNextAttemptMs()).as("人工终结同样同写 next_attempt_ms = now").isEqualTo(resolveNow);
        assertThat(rec.getUpdatedMs()).isEqualTo(resolveNow);
        assertThat(rec.getDurable()).as("人工终结不是 scene 确认的落盘结局，不置 durable").isZero();
        assertThat(rec.getLastOutcome()).as("last_outcome 保留最后一次 scene 真实答复").isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE);
        assertThat(rec.getLeaseUntilMs()).isZero();
        assertThat(funds(db, guildId)).as("人工判 APPLIED 同样记资金").isEqualTo(1000);

        assertThat(store.resolveManually(manual, resolveNow, roomy()).finalized()).as("已非 PENDING").isFalse();
        assertThat(finalizeOp(in.opId(), AssetOpStatus.APPLIED, applied(), resolveNow + 1).finalized())
                .as("人工与自动只有一个赢家").isFalse();
        assertThat(funds(db, guildId)).isEqualTo(1000);

        // 人工判 ABORTED：退次数（与自动终结同一份对侧账）
        DonationReserve second = reserveDonation(9_670_002, donor, guildId);
        assertThat(store.resolveManually(new ManualResolution(second.opId(), AssetOpStatus.ABORTED, "ops-bob", "scene 回 UNKNOWN"),
                resolveNow, roomy()).finalized()).isTrue();
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).isEqualTo(1);
    }

    // ================================================================ 读

    @Test
    void 最老未决_GetOp_ListStuck() throws SQLException {
        long player = 8701;
        assertThat(store.oldestPendingCreatedMs(CREDIT, roomy())).isEmpty();
        insertOp(db, shopRecord(801, player, NOW - 200_000, 1).setCreatedMs(NOW - 9_000).build());
        insertOp(db, shopRecord(802, player, NOW - 200_000, 2).setCreatedMs(NOW - 5_000).build());
        insertOp(db, shopRecord(803, player, NOW - 200_000, 3).setCreatedMs(NOW - 20_000)
                .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED).build());
        insertOp(db, shopRecord(804, player, NOW - 200_000, 4).setStream(DEBIT).setCreatedMs(NOW - 30_000).build());

        assertThat(store.oldestPendingCreatedMs(CREDIT, roomy())).hasValue(NOW - 9_000);
        assertThat(store.oldestPendingCreatedMs(DEBIT, roomy())).hasValue(NOW - 30_000);
        assertThat(store.listStuck(NOW, 10, roomy())).extracting(GuildAssetOpRow::getOpId).containsExactly(804L, 801L, 802L);
        assertThat(store.listStuck(NOW - 6_000, 10, roomy())).extracting(GuildAssetOpRow::getOpId).containsExactly(804L, 801L);
        assertThat(store.listStuck(NOW, 1, roomy())).extracting(GuildAssetOpRow::getOpId).containsExactly(804L);
        assertThat(store.getOp(803, roomy()).orElseThrow().getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED);
        assertThat(store.getOp(999, roomy())).isEmpty();
    }

    // ================================================================ 清理

    /**
     * 只删保留期外的 APPLIED / REJECTED / ABORTED；APPLIED_PARTIAL（待人工补偿）与 PENDING 永不自动删；过期的日键 / 周键计数行删掉，当期的
     * 保留（TestCleanupOnce_KeepsPendingAndPartial）。
     */
    @Test
    void 清理保留未决与部分发放() throws SQLException {
        long player = 8691, oldMs = NOW - 31 * DAY_MS, recentMs = NOW - DAY_MS;
        record Case(long opId, GuildAssetOpStatus status, long finalMs, boolean survives) {
        }
        List<Case> cases = List.of(
                new Case(701, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, oldMs, false),
                new Case(702, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED, oldMs, false),
                new Case(703, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED, oldMs, false),
                new Case(704, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL, oldMs, true),
                new Case(705, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, oldMs, true),
                new Case(706, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, recentMs, true));
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            insertOp(db, shopRecord(c.opId(), player, oldMs, i + 1).setStatus(c.status()).setNextAttemptMs(c.finalMs())
                    .setCreatedMs(oldMs).setUpdatedMs(c.finalMs()).build());
        }
        int oldDay = GameDay.dayKey(NOW - 31 * DAY_MS);
        int recentDay = GameDay.dayKey(NOW - DAY_MS);
        int oldWeek = GameDay.weekKey(NOW - 60 * DAY_MS);
        int currentWeek = GameDay.weekKey(NOW);
        for (int key : new int[] {oldDay, recentDay, oldWeek, currentWeek}) {
            EconomyFixtures.insertCounter(db, player, COUNTER_SHOP, 101, key, 1);
        }

        CleanupReport report = store.cleanupOnce(NOW, Duration.ofDays(30), Duration.ofDays(30));
        for (Case c : cases) {
            assertThat(opExists(db, c.opId())).as("op %d status=%s", c.opId(), c.status()).isEqualTo(c.survives());
        }
        Map<Integer, Boolean> survives = Map.of(oldDay, false, recentDay, true, oldWeek, false, currentWeek, true);
        survives.forEach((key, alive) -> {
            try {
                assertThat(counterUsed(db, player, COUNTER_SHOP, 101, key) != null).as("period_key=%d", key).isEqualTo(alive);
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        });
        assertThat(report).isEqualTo(new CleanupReport(3, 2));
        assertThat(events.cleaned).isEqualTo(Map.of(CleanupTable.ASSET_OP, 3L, CleanupTable.DAILY_COUNTER, 2L));
        // 再跑一轮：没有可删的了
        assertThat(store.cleanupOnce(NOW, Duration.ofDays(30), Duration.ofDays(30))).isEqualTo(new CleanupReport(0, 0));
        background.assertNoDeadlocks("清理");
    }

    /** 清理分批：一批候选满 500 才取下一批（按候选数而不是删除数判），跨批全部删光。 */
    @Test
    void 清理跨批() throws SQLException {
        long player = 8692, oldMs = NOW - 40 * DAY_MS;
        List<GuildAssetOpRow> rows = new ArrayList<>();
        for (int i = 0; i < 1_100; i++) {
            rows.add(shopRecord(100_000 + i, player, oldMs, i + 1).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED)
                    .setNextAttemptMs(oldMs).build());
            if (rows.size() == 1_000) {
                insertOpsBulk(db, rows);
                rows.clear();
            }
        }
        insertOpsBulk(db, rows);
        assertThat(store.cleanupOnce(NOW, Duration.ofDays(30), Duration.ofDays(30)).assetOpsDeleted()).isEqualTo(1_100);
        assertThat(db.count("SELECT COUNT(*) FROM guild_asset_op")).isZero();
    }

    // ================================================================ 回档检查（G1–G6、G5b）

    private static final long SINCE = NOW - 3_600_000;

    private static GuildAssetOpRow terminal(long opId, long player, long guildId, GuildAssetOpStatus status, long finalMs) {
        return donateRecord(opId, player, guildId, 1, opId, EconomyFixtures.donatePayload(10_000)).setStatus(status)
                .setNextAttemptMs(finalMs).setUpdatedMs(finalMs).build();
    }

    private AppliedOpsPage list(int zone, List<Long> players, long since, long after, int limit) {
        return store.listAppliedSince(new AppliedOpsQuery(zone, players, since, after, limit), roomy());
    }

    /** G1：只回 APPLIED / APPLIED_PARTIAL；别的状态与别的玩家都不出现；摘要各字段与库行一致。 */
    @Test
    void 回档检查只回已应用与部分发放() throws SQLException {
        long a = 8801, b = 8802, other = 8803, guildId = 7801, at = SINCE + 10;
        insertOpsBulk(db, List.of(
                terminal(9_800_001, a, guildId, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, at),
                terminal(9_800_002, a, guildId, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, at),
                terminal(9_800_003, a, guildId, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED, at),
                terminal(9_800_004, b, guildId, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED, at),
                terminal(9_800_005, b, guildId, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL, at + 1),
                terminal(9_800_006, other, guildId, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, at)));
        AppliedOpsPage page = list(0, List.of(a, b), SINCE, 0, 500);
        assertThat(page.nextAfterOpId()).isZero();
        assertThat(page.ops()).extracting(AppliedOpBrief::opId).containsExactly(9_800_002L, 9_800_005L);
        assertThat(page.ops().getFirst()).isEqualTo(new AppliedOpBrief(9_800_002, a, guildId, DEBIT,
                GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE_VALUE, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_VALUE, 1000,
                10, at));
        assertThat(page.ops().get(1).status()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL_VALUE);
        assertThat(page.ops().get(1).playerId()).isEqualTo(b);
    }

    /** G2：next_attempt_ms == since 不返回，since + 1 返回（终结时刻等于 since 的行在快照之内）。 */
    @Test
    void 回档检查since严格大于() throws SQLException {
        long player = 8811;
        insertOpsBulk(db, List.of(
                terminal(9_810_001, player, 7811, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, SINCE),
                terminal(9_810_002, player, 7811, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, SINCE + 1)));
        assertThat(list(0, List.of(player), SINCE, 0, 500).ops()).extracting(AppliedOpBrief::opId).containsExactly(9_810_002L);
    }

    /** G3：1201 行按 limit 500 翻三页，op_id 严格升序、无重无漏，末页游标 0；恰好 500 行时一页查完、游标 0。 */
    @Test
    void 回档检查按op_id翻页() throws SQLException {
        long player = 8821, exactly500 = 8822, base = 9_820_000, exactBase = 9_830_000;
        int total = 1201;
        List<Long> want = new ArrayList<>();
        List<GuildAssetOpRow> rows = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            want.add(base + i);
            // 终结时刻与 op_id 刻意反序：证明翻页按 op_id 而不是按 next_attempt_ms
            rows.add(terminal(base + i, player, 7821, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, SINCE + total + 1 - i));
        }
        insertOpsBulk(db, rows);
        List<Long> got = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        long after = 0;
        while (true) {
            AppliedOpsPage page = list(0, List.of(player), SINCE, after, 500);
            pages.add(page.ops().size());
            page.ops().forEach(o -> got.add(o.opId()));
            if (page.nextAfterOpId() == 0) {
                break;
            }
            assertThat(page.nextAfterOpId()).as("游标 = 本页最后一行的 op_id").isEqualTo(page.ops().getLast().opId());
            after = page.nextAfterOpId();
            assertThat(pages.size()).as("翻页没有收敛").isLessThan(10);
        }
        assertThat(pages).containsExactly(500, 500, 201);
        assertThat(got).isEqualTo(want);

        rows.clear();
        for (int i = 1; i <= 500; i++) {
            rows.add(terminal(exactBase + i, exactly500, 7822, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, SINCE + i));
        }
        insertOpsBulk(db, rows);
        AppliedOpsPage exact = list(0, List.of(exactly500), SINCE, 0, 500);
        assertThat(exact.ops()).hasSize(500);
        assertThat(exact.nextAfterOpId()).as("恰好 limit 行：一页查完，不再给出空的下一页").isZero();
    }

    /** G4：zone_id = 0 全返回；zone_id = Z 时保留本 zone 与<b>已解散帮会</b>（guild 行已删，LEFT JOIN 取到 NULL）的行，滤掉别的 zone。 */
    @Test
    void 回档检查zone过滤保留已解散帮() throws SQLException {
        long player = 8831, guildZ1 = 7831, guildZ2 = 7832, disbanded = 7833, at = SINCE + 10;
        db.seedGuild(guildZ1, 1, 1, 50, 8834, Map.of());
        db.seedGuild(guildZ2, 2, 1, 50, 8835, Map.of());
        insertOpsBulk(db, List.of(
                terminal(9_840_001, player, guildZ1, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, at),
                terminal(9_840_002, player, guildZ2, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, at),
                terminal(9_840_003, player, disbanded, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, at)));
        Map<Integer, List<Long>> want = Map.of(
                0, List.of(9_840_001L, 9_840_002L, 9_840_003L),
                1, List.of(9_840_001L, 9_840_003L),
                2, List.of(9_840_002L, 9_840_003L),
                99, List.of(9_840_003L));
        want.forEach((zone, ids) -> {
            AppliedOpsPage page = list(zone, List.of(player), SINCE, 0, 500);
            assertThat(page.nextAfterOpId()).isZero();
            assertThat(page.ops()).as("zone=%d", zone).extracting(AppliedOpBrief::opId).isEqualTo(ids);
        });
    }

    /** G5：assetopfix 人工判 APPLIED 的行必须查得到，且按人工终结时刻判。 */
    @Test
    void 回档检查看得到人工终结的行() throws SQLException {
        long donor = 8842;
        seedGuild(7841, 8841, Map.of(donor, GuildRoles.MEMBER));
        DonationReserve in = reserveDonation(9_850_001, donor, 7841);
        assertThat(list(0, List.of(donor), NOW - 1, 0, 500).ops()).as("未决行不是分歧").isEmpty();
        long resolveNow = NOW + 7_200_000;
        assertThat(store.resolveManually(new ManualResolution(in.opId(), AssetOpStatus.APPLIED, "ops-alice", "回档检查用例"),
                resolveNow, roomy()).finalized()).isTrue();
        assertThat(list(0, List.of(donor), resolveNow - 1, 0, 500).ops()).extracting(AppliedOpBrief::opId)
                .containsExactly(in.opId());
        assertThat(list(0, List.of(donor), resolveNow, 0, 500).ops()).as("人工终结时刻 = since 时不算分歧").isEmpty();
    }

    /** G5b：经真实 Finalize 终结一行，next_attempt_ms == 传入的 now，且 since = now − 1 时能被查到。 */
    @Test
    void 回档检查看得到Store终结的行() throws SQLException {
        long donor = 8852;
        seedGuild(7851, 8851, Map.of(donor, GuildRoles.MEMBER));
        DonationReserve in = reserveDonation(9_860_001, donor, 7851);
        long finalNow = NOW + 1234;
        assertThat(finalizeOp(in.opId(), AssetOpStatus.APPLIED, applied(), finalNow).finalized()).isTrue();
        assertThat(record(db, in.opId()).getNextAttemptMs()).isEqualTo(finalNow);
        AppliedOpsPage page = list(0, List.of(donor), finalNow - 1, 0, 500);
        assertThat(page.nextAfterOpId()).isZero();
        assertThat(page.ops()).extracting(AppliedOpBrief::opId).containsExactly(in.opId());
        assertThat(page.ops().getFirst().updatedMs()).isEqualTo(finalNow);
        assertThat(list(0, List.of(donor), finalNow, 0, 500).ops()).isEmpty();
    }

    // ================================================================ 无符号

    /** Java 增项：令牌 / op_id / player_id ≥ 2^63 的领取、重排、终结、回档检查全流程（按有符号 long 绑定会被严格模式拒）。 */
    @Test
    void 令牌与id大于等于2的63次方全流程() throws SQLException {
        long guildId = 0x8000_0000_0000_7701L, leader = 0x8000_0000_0000_8701L, donor = 0xFFFF_FFFF_FFFF_FF01L;
        seedGuild(guildId, leader, Map.of(donor, GuildRoles.MEMBER));
        long opId = 0xFFFF_FFFF_FFFF_0001L;
        DonationReserve in = reserveDonation(opId, donor, guildId);

        long token = 0xF000_0000_0000_0001L;
        assertThat(store.claim(opId, in.leaseUntilMs(), in.leaseUntilMs() + LEASE_MS, NOW + 3_600_000, token, roomy()))
                .as("插行租约（令牌 ≥ 2^63）到期之前循环领不到").isEqualTo(Lost.INSTANCE);
        long claimAt = in.leaseUntilMs() + 1;
        AssetOp op = ((Claimed) store.claim(opId, claimAt, claimAt + LEASE_MS, NOW + 3_600_000, token, roomy())).op();
        assertThat(op.opId()).isEqualTo(opId);
        assertThat(op.leaseToken()).isEqualTo(token);
        assertThat(op.playerId()).isEqualTo(donor);
        assertThat(record(db, opId).getLeaseToken()).isEqualTo(token);
        assertThat(store.listDue(claimAt + LEASE_MS + 1, 10, roomy())).as("领到的租约过期之后又到期").containsExactly(opId);
        assertThat(store.reschedule(op, NOW + 50_000, AssetOpResult.localNotHere(), NOW + 20_000, roomy()))
                .isEqualTo(RescheduleResult.RESCHEDULED);
        AssetOp op2 = ((Claimed) store.claim(opId, NOW + 50_000, NOW + 60_000, NOW + 3_600_000, 0xFFFF_FFFF_FFFF_FFFFL,
                roomy())).op();
        assertThat(op2.attempts()).isEqualTo(1);
        FinalizeResult res = finalizeOp(opId, AssetOpStatus.APPLIED, applied(), NOW + 50_001);
        assertThat(res.finalized()).isTrue();
        assertThat(funds(db, guildId)).isEqualTo(1000);
        assertThat(contribution(db, guildId, donor)).containsExactly(10, 10);
        assertThat(list(0, List.of(donor), NOW, 0, 500).ops()).extracting(AppliedOpBrief::opId).containsExactly(opId);
        assertThat(list(2, List.of(donor), NOW, 0x8000_0000_0000_0000L, 500).ops()).extracting(AppliedOpBrief::opId)
                .as("游标按无符号比较").containsExactly(opId);
        background.assertNoDeadlocks("≥ 2^63");
    }
}
