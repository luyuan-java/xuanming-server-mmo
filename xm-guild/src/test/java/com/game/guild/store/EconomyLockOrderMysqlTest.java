package com.game.guild.store;

import static com.game.guild.store.EconomyFixtures.COUNTER_DONATE;
import static com.game.guild.store.EconomyFixtures.COUNTER_SHOP;
import static com.game.guild.store.EconomyFixtures.CREDIT;
import static com.game.guild.store.EconomyFixtures.DAY_KEY;
import static com.game.guild.store.EconomyFixtures.DAY_MS;
import static com.game.guild.store.EconomyFixtures.DEBIT;
import static com.game.guild.store.EconomyFixtures.abortedPlaceholder;
import static com.game.guild.store.EconomyFixtures.applied;
import static com.game.guild.store.EconomyFixtures.counterUsed;
import static com.game.guild.store.EconomyFixtures.donateRecord;
import static com.game.guild.store.EconomyFixtures.donation;
import static com.game.guild.store.EconomyFixtures.nextSeq;
import static com.game.guild.store.EconomyFixtures.opCountInRange;
import static com.game.guild.store.EconomyFixtures.opExists;
import static com.game.guild.store.EconomyFixtures.record;
import static com.game.guild.store.EconomyFixtures.rejected;
import static com.game.guild.store.EconomyFixtures.roomy;
import static com.game.guild.store.EconomyFixtures.setContribution;
import static com.game.guild.store.EconomyFixtures.shopOrder;
import static com.game.guild.store.GuildMysqlFixture.NOW;
import static com.game.guild.store.GuildMysqlFixture.TTL;
import static com.game.guild.store.GuildMysqlFixture.execOn;
import static com.game.guild.store.GuildMysqlFixture.finish;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.guild.asset.AssetOpStatus;
import com.game.guild.asset.DeliveryOrigin;
import com.game.guild.asset.GuildAssetStore.FinalizeResult;
import com.game.guild.asset.GuildAssetStore.Orphan;
import com.game.guild.asset.JdbcGuildAssetStore;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.EconomyFixtures.AssetEvents;
import com.game.guild.store.EconomyFixtures.BackgroundRecorder;
import com.game.guild.store.EconomyStore.DonationReserve;
import com.game.guild.store.EconomyStore.Reserved;
import com.game.guild.store.EconomyStore.ShopReserve;
import com.game.guild.store.GuildMysqlFixture.Recorder;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.TransactionType;
import com.google.protobuf.ByteString;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 经济事务与 4.4 事务、后台写之间的并发锁序回归（真库，缺省跳过；移植 mmorpg economy_repo_test.go:2004-2676 的 TestEconomyLockOrder_*、
 * TestReserveDonation_FirstSeqRowCreatorsSerializeOnMember，外加 guild_lock_order_mysql_test.go 里「解散停在提前截止」那一例的原形；
 * guild-economy-spec §11.3「锁序并发」）。
 *
 * <p><b>判据</b>（死锁修复契约 P6）：两个事务基座的重跑记录器（GuildTx 的 {@link Recorder}、BackgroundTx 的 {@link BackgroundRecorder}）
 * 都是零——1213 会被重跑吸收掉，只看返回值看不出死锁；外加 {@link InnoDbDeadlockWatch}（本库出现在 InnoDB「最近一次死锁」里就红）与各用例
 * 对业务结局、不变量的断言。并发参与者里只调被测接口、收集结局，断言全在主线程做。TiDB 上才有鉴别力的几例（C6 / G-C2）在 MySQL 上是行为回归。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class EconomyLockOrderMysqlTest {

    /** 循环多轮的对撞轮数（基线 30）。 */
    private static final int ROUNDS = 30;

    private static GuildMysqlFixture db;
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "economy-lock-order");
        t.setDaemon(true);
        return t;
    });

    private Recorder recorder;
    private BackgroundRecorder background;
    private AssetEvents events;
    private JdbcEconomyStore econ;
    private JdbcGuildStore guilds;
    private JdbcGuildAssetStore assets;

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
        assets = EconomyFixtures.assets(db, background, events);
    }

    private void assertNoDeadlocks(String what, InnoDbDeadlockWatch watch) throws SQLException {
        recorder.assertNoDeadlocks(what);
        background.assertNoDeadlocks(what);
        watch.assertNone(what);
    }

    /** 一路并发参与者的结局：返回值或异常。 */
    private record Outcome(Object value, Throwable error) {
        Object orThrow() {
            if (error != null) {
                throw new AssertionError("并发参与者抛了异常", error);
            }
            return value;
        }
    }

    /** 统一闸门同时放行若干路，返回与入参一一对应的结局。 */
    private static List<Outcome> together(List<Callable<?>> calls) {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Outcome>> futures = new ArrayList<>();
        for (Callable<?> call : calls) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return new Outcome(call.call(), null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                }
            }, POOL));
        }
        start.countDown();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private static CompletableFuture<Outcome> async(Callable<?> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new Outcome(call.call(), null);
            } catch (Throwable t) {
                return new Outcome(null, t);
            }
        }, POOL);
    }

    private FinalizeResult finalizeOp(long opId, AssetOpStatus status, com.game.guild.asset.AssetOpResult res, long now) {
        return assets.finalizeOp(opId, status, res, now, DeliveryOrigin.LOOP, roomy());
    }

    // ================================================================ 预留 ‖ 终结

    /**
     * 同一玩家「捐献预留」循环 ‖ 「终结上一笔为 REJECTED」循环（TestEconomyLockOrder_ReserveDonationVersusFinalizeRejected）。修复后预留对上一笔的
     * op 行不加任何锁（未决行是普通读），终结的退款分支先锁 Q 行（C6）：两边先在 Q 上串行，再到计数行——只会单向等待。
     * 预留每成功一笔就交给终结方，「预留第 i+1 笔」与「终结第 i 笔」恰好重叠。
     */
    @Test
    void 捐献预留与终结被拒交替() throws Exception {
        long guildId = 7961, leader = 8961, donor = 8962, opBase = 9_960_000;
        int rounds = 200;
        db.seedGuild(guildId, 2, 1, 50, leader, Map.of(donor, GuildRoles.MEMBER));
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);
        LinkedBlockingQueue<Long> handoff = new LinkedBlockingQueue<>();
        long done = -1;
        CompletableFuture<Outcome> reserver = async(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    TxOutcome<Reserved> out = econ.reserveDonation(donation(opBase + i, donor, guildId, NOW + i, 1000), roomy());
                    if (!out.isOk()) {
                        return "第 " + i + " 笔预留被拒: " + out.rejection();
                    }
                    handoff.put(opBase + i);
                }
                return null;
            } finally {
                handoff.put(done);
            }
        });
        CompletableFuture<Outcome> finalizer = async(() -> {
            List<String> errors = new ArrayList<>();
            while (true) {
                long opId = handoff.take();
                if (opId == done) {
                    return errors;
                }
                FinalizeResult res = finalizeOp(opId, AssetOpStatus.REJECTED, rejected(), NOW + rounds + (opId - opBase));
                if (!res.finalized()) {
                    errors.add("终结 op " + opId + "：CAS 落空（只有本协程终结它，不该落空）");
                }
            }
        });
        assertThat(reserver.get(120, TimeUnit.SECONDS).orThrow()).isNull();
        assertThat((List<?>) finalizer.get(120, TimeUnit.SECONDS).orThrow()).isEmpty();
        assertThat(db.count("SELECT COUNT(*) FROM guild_asset_op WHERE player_id = ? AND status = ?", donor,
                GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED_VALUE)).as("每一笔都被终结成 REJECTED").isEqualTo(rounds);
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).as("REJECTED 全额退次数").isZero();
        assertThat(nextSeq(db, donor, DEBIT)).isEqualTo(rounds + 1);
        assertNoDeadlocks("捐献预留 ‖ 终结 REJECTED", watch);
    }

    /**
     * 退帮（含提前截止）‖ 清理 ‖ 终结同一玩家的未决捐献，多轮三方同时放行（TestEconomyLockOrder_LeaveVersusCleanupVersusFinalize）。每轮给玩家预置
     * 一批保留期外、同一 (player, stream, epoch) 前缀、同一 guild_id 的终态行，让修复前的计划恰好扫过它们。任何事务都先拿 op 行的聚簇记录、
     * 再碰它的二级项，不存在反向边。每轮：三方零错误；第二笔未决捐献被提前到离帮时刻（候选读完整）；旧终态行全部被清理。
     */
    @Test
    void 退帮_清理_终结三方对撞() throws Exception {
        long guildId = 7971, leader = 8971, donor = 8972, opBase = 9_970_000, oldOpBase = 9_980_000;
        int oldPerRound = 200;
        long roundStep = 10_000;
        db.seedGuild(guildId, 2, 1, 50, leader, Map.of(donor, GuildRoles.MEMBER));
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);
        ByteString payload = EconomyFixtures.donatePayload(100);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round * roundStep;
            if (round > 0) {
                db.seedMember(guildId, donor, GuildRoles.MEMBER); // 上一轮离帮了，重新入帮
            }
            DonationReserve first = donation(opBase + 2L * round, donor, guildId, now, 10_000);
            DonationReserve second = donation(opBase + 2L * round + 1, donor, guildId, now + 1, 10_000);
            Reserved reservedFirst = econ.reserveDonation(first, roomy()).orThrow();
            assertThat(econ.reserveDonation(second, roomy()).isOk()).isTrue();

            long oldLo = oldOpBase + (long) round * oldPerRound;
            List<GuildAssetOpRow> olds = new ArrayList<>();
            for (int k = 0; k < oldPerRound; k++) {
                long finalMs = now - 31 * DAY_MS;
                olds.add(donateRecord(oldLo + k, donor, guildId, reservedFirst.streamEpoch(),
                        1_000_000L + (long) round * oldPerRound + k, payload).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED)
                        .setNextAttemptMs(finalMs).setCreatedMs(finalMs).setUpdatedMs(finalMs).build());
            }
            EconomyFixtures.insertOpsBulk(db, olds);

            long leaveAt = now;
            List<Outcome> r = together(List.of(
                    () -> guilds.leaveGuild(guildId, donor, leaveAt, roomy()),
                    () -> assets.cleanupOnce(leaveAt, Duration.ofDays(30), Duration.ofDays(30)),
                    () -> finalizeOp(first.opId(), AssetOpStatus.REJECTED, rejected(), leaveAt)));
            assertThat(((TxOutcome<?>) r.get(0).orThrow()).isOk()).as("第 %d 轮离帮", round).isTrue();
            r.get(1).orThrow();
            assertThat(((FinalizeResult) r.get(2).orThrow()).finalized()).as("第 %d 轮：第一笔只有这一个终结者", round).isTrue();

            assertThat(record(db, first.opId()).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED);
            GuildAssetOpRow rec = record(db, second.opId());
            assertThat(rec.getStatus()).as("第 %d 轮第二笔", round).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
            assertThat(rec.getDeadlineMs()).as("第 %d 轮：候选读漏了第二笔，截止没被提前", round).isEqualTo(leaveAt);
            assertThat(rec.getNextAttemptMs()).as("第 %d 轮：next_attempt_ms 没被拉回离帮时刻", round).isEqualTo(leaveAt);
            assertThat(db.memberships(donor)).as("第 %d 轮：离帮之后成员行必须不在", round).isZero();
            assertThat(opCountInRange(db, oldLo, oldLo + oldPerRound - 1)).as("第 %d 轮：保留期外的终态行应被清理删光", round).isZero();

            // 收尾：第二笔也终结掉，免得未决行跨轮累积撞上 MaxPending
            assertThat(finalizeOp(second.opId(), AssetOpStatus.ABORTED, abortedPlaceholder(), now + 2).finalized()).isTrue();
        }
        assertNoDeadlocks("退帮 ‖ 清理 ‖ 终结", watch);
    }

    /**
     * 死锁复核 C2 的确定性回归（TestReserveDonation_FirstSeqRowCreatorsSerializeOnMember）：首插者 A 在事务里先锁 p 的成员行、再插 seq 行
     * 未提交；B、C 两笔捐献预留同时放行，然后 A 回滚。修复后 B、C 都排在 A 的成员行锁上、碰不到 seq 记录；A 回滚后先拿到成员行的一方建行、
     * 分配、提交，另一方读到已提交的行直接分配。判据：零死锁；两笔都成功、seq 恰为 {1, 2}、表里恰好一行。
     */
    @Test
    void 首次建seq行的建行者在成员行上串行() throws Exception {
        long guildId = 7985, leader = 8987, player = 8985;
        db.seedGuild(guildId, 2, 1, 50, leader, Map.of(player, GuildRoles.MEMBER));
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);
        Connection first = db.begin();
        CompletableFuture<Outcome> b;
        CompletableFuture<Outcome> c;
        try {
            execOn(first, JdbcGuildStore.LOCK_MEMBER_ROLE, guildId, player);
            execOn(first, JdbcEconomyStore.ENSURE_SEQ_ROW, player, DEBIT, NOW, NOW);
            b = async(() -> econ.reserveDonation(donation(9_985_001, player, guildId, NOW + 1), roomy()));
            c = async(() -> econ.reserveDonation(donation(9_985_002, player, guildId, NOW + 2), roomy()));
            // 修复后两个锁等待都在成员行上；锁等待上限 1 s：最多等到 500 ms 就回滚首插者
            db.awaitLockWaits(2, 500);
        } finally {
            finish(first, false);
        }
        @SuppressWarnings("unchecked")
        TxOutcome<Reserved> rb = (TxOutcome<Reserved>) b.get(30, TimeUnit.SECONDS).orThrow();
        @SuppressWarnings("unchecked")
        TxOutcome<Reserved> rc = (TxOutcome<Reserved>) c.get(30, TimeUnit.SECONDS).orThrow();
        assertThat(rb.isOk()).as("首插者回滚后 B 必须成功 %s", rb).isTrue();
        assertThat(rc.isOk()).as("首插者回滚后 C 必须成功 %s", rc).isTrue();
        assertThat(List.of(rb.orThrow().seq(), rc.orThrow().seq())).containsExactlyInAnyOrder(1L, 2L);
        assertThat(db.count("SELECT COUNT(*) FROM guild_player_op_seq WHERE player_id = ? AND stream = ?", player, DEBIT))
                .as("B、C 合起来恰好建出一行").isEqualTo(1);
        assertNoDeadlocks("首次建 seq 行：首插者回滚", watch);
    }

    /**
     * 预留与清理在同一 (player, stream, epoch) 前缀上对撞（TestEconomyLockOrder_ReserveVersusCleanupSameStream，审计 #15）：预留对任何历史 op 行
     * 都不加锁，清理每行一个短事务只锁一行，两边没有共享的锁。每轮给两条流各预置 100 条同纪元、保留期外的终态行。
     */
    @Test
    void 预留与同前缀清理() throws Exception {
        long guildId = 7981, leader = 8981, player = 8982, opBase = 9_990_000, oldOpBase = 9_100_000;
        int oldPerRound = 200;
        long roundStep = 10_000;
        db.seedGuild(guildId, 2, 1, 50, leader, Map.of(player, GuildRoles.MEMBER));
        setContribution(db, guildId, player, 1000, 1000);
        EconomyFixtures.ensureSeqRow(db, player, DEBIT, NOW);
        EconomyFixtures.ensureSeqRow(db, player, CREDIT, NOW);
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);
        ByteString payload = EconomyFixtures.donatePayload(100);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round * roundStep;
            DonationReserve donationIn = donation(opBase + 2L * round, player, guildId, now, 10_000);
            ShopReserve shopIn = shopOrder(opBase + 2L * round + 1, player, guildId, 1, 30, 0, now);
            long oldLo = oldOpBase + (long) round * oldPerRound;
            List<GuildAssetOpRow> olds = new ArrayList<>();
            for (int k = 0; k < oldPerRound; k++) {
                long finalMs = now - 31 * DAY_MS;
                GuildAssetOpRow.Builder b = donateRecord(oldLo + k, player, guildId, NOW, 1_000_000L + (long) round * oldPerRound + k,
                        payload).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED).setNextAttemptMs(finalMs)
                        .setCreatedMs(finalMs).setUpdatedMs(finalMs);
                if (k % 2 == 1) {
                    b.setStream(CREDIT).setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP)
                            .setTxType(TransactionType.TX_GUILD_SHOP_VALUE);
                }
                olds.add(b.build());
            }
            EconomyFixtures.insertOpsBulk(db, olds);

            List<Outcome> r = together(List.of(
                    () -> econ.reserveDonation(donationIn, roomy()),
                    () -> econ.reserveShopOrder(shopIn, roomy()),
                    () -> assets.cleanupOnce(now, Duration.ofDays(30), Duration.ofDays(30))));
            assertThat(((TxOutcome<?>) r.get(0).orThrow()).isOk()).as("第 %d 轮捐献预留 %s", round, r.get(0)).isTrue();
            assertThat(((TxOutcome<?>) r.get(1).orThrow()).isOk()).as("第 %d 轮兑换预留 %s", round, r.get(1)).isTrue();
            r.get(2).orThrow();
            assertThat(opCountInRange(db, oldLo, oldLo + oldPerRound - 1)).as("第 %d 轮：保留期外的终态行应被清理删光", round).isZero();
            for (long opId : new long[] {donationIn.opId(), shopIn.opId()}) {
                assertThat(finalizeOp(opId, AssetOpStatus.REJECTED, rejected(), now + 1).finalized())
                        .as("第 %d 轮收尾 op %d", round, opId).isTrue();
            }
        }
        assertNoDeadlocks("捐献 / 兑换预留 ‖ 同一 (player, stream, epoch) 前缀上的清理", watch);
    }

    // ================================================================ 提前截止候选集完整性

    /**
     * 提前截止候选集完整性的前提（TestEconomyLockOrder_MemberLockBlocksReserveDonation）：占锁事务扮演「持成员锁的离帮事务」，锁住 (G,p) 后
     * 放一笔捐献预留，等它在库里锁等待，再删掉成员行并提交——预留必须失败（NOT_MEMBER；慢机器上等满 1 s 则是 WRITE_CONFLICT），且不留行、
     * 不占次数。若有人把预留里的成员锁拿掉，它会先插进一行——那一行正是离帮的候选读看不见的。
     */
    @Test
    void 成员行锁挡住捐献预留() throws Exception {
        long guildId = 7991, leader = 8991, donor = 8992, opId = 9_991_001;
        db.seedGuild(guildId, 2, 1, 50, leader, Map.of(donor, GuildRoles.MEMBER));
        EconomyFixtures.ensureSeqRow(db, donor, DEBIT, NOW);
        Connection holder = db.begin();
        CompletableFuture<Outcome> reserve;
        try {
            execOn(holder, JdbcGuildStore.LOCK_MEMBER_ROLE, guildId, donor);
            reserve = async(() -> econ.reserveDonation(donation(opId, donor, guildId, NOW), roomy()));
            db.awaitLockWaits(1, 500);
            execOn(holder, JdbcGuildStore.DELETE_MEMBER, guildId, donor);
            finish(holder, true);
        } catch (SQLException | RuntimeException e) {
            finish(holder, false);
            throw e;
        }
        TxOutcome<?> out = (TxOutcome<?>) reserve.get(30, TimeUnit.SECONDS).orThrow();
        assertThat(out.rejection()).as("持成员锁期间的捐献预留必须被挡住，不能先插进一行")
                .isIn(GuildReject.NOT_MEMBER, GuildReject.WRITE_CONFLICT);
        assertThat(opExists(db, opId)).isFalse();
        assertThat(counterUsed(db, donor, COUNTER_DONATE, 1, DAY_KEY)).isNull();
    }

    /**
     * 同一前提的并发版本（TestEconomyLockOrder_LeaveVersusReserveDonationCandidateComplete）：离帮 ‖ 同一玩家的捐献预留，多轮同时放行。合法结局只有
     * 两种：预留先提交 → 离帮的候选读看得见、截止被提前；离帮先拿到成员锁 → 预留 NOT_MEMBER、不留行。预留成功、成员已不在而截止没被提前 =
     * 候选集漏行。
     */
    @Test
    void 退帮与捐献预留_候选集完整() throws Exception {
        long guildId = 7993, leader = 8993, donor = 8994, opBase = 9_993_000;
        long roundStep = 10_000;
        db.seedGuild(guildId, 2, 1, 50, leader, Map.of(donor, GuildRoles.MEMBER));
        EconomyFixtures.ensureSeqRow(db, donor, DEBIT, NOW);
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);
        int reservedRounds = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round * roundStep;
            long leaveAt = now + 5;
            if (round > 0) {
                db.seedMember(guildId, donor, GuildRoles.MEMBER);
            }
            DonationReserve in = donation(opBase + round, donor, guildId, now, 10_000);
            List<Outcome> r = together(List.of(
                    () -> econ.reserveDonation(in, roomy()),
                    () -> guilds.leaveGuild(guildId, donor, leaveAt, roomy())));
            assertThat(((TxOutcome<?>) r.get(1).orThrow()).isOk()).as("第 %d 轮离帮", round).isTrue();
            assertThat(db.memberships(donor)).as("第 %d 轮：离帮之后成员行必须不在", round).isZero();
            TxOutcome<?> reserve = (TxOutcome<?>) r.get(0).orThrow();
            if (!reserve.isOk()) {
                assertThat(reserve.rejection()).as("第 %d 轮：预留只能因为已不是成员而失败", round).isEqualTo(GuildReject.NOT_MEMBER);
                assertThat(opExists(db, in.opId())).isFalse();
                continue;
            }
            reservedRounds++;
            GuildAssetOpRow rec = record(db, in.opId());
            assertThat(rec.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
            assertThat(rec.getDeadlineMs()).as("第 %d 轮：预留先提交，离帮的候选读却漏了它", round).isEqualTo(leaveAt);
            assertThat(rec.getNextAttemptMs()).as("第 %d 轮：next_attempt_ms 没被拉回离帮时刻", round).isEqualTo(leaveAt);
            assertThat(finalizeOp(in.opId(), AssetOpStatus.ABORTED, abortedPlaceholder(), leaveAt + 1).finalized()).isTrue();
        }
        System.out.printf("[退帮 ‖ 捐献预留] %d 轮中预留先提交 %d 轮、离帮先拿到成员锁 %d 轮%n", ROUNDS, reservedRounds,
                ROUNDS - reservedRounds);
        assertNoDeadlocks("离帮 ‖ 捐献预留（候选集完整性）", watch);
    }

    // ================================================================ C6：兑换孤儿退款 ‖ 别帮兑换

    /**
     * C6 的兑换孤儿形态（TestEconomyLockOrder_ShopOrphanRefundVersusReserveElsewhere）：p 在帮 A 下过 12 笔限购商品 101 的兑换（PENDING；商店永不
     * 中止）后离开 A、加入 B。每轮同时放行「在 B 兑换同一商品」与「把 A 的一笔旧订单终结成 REJECTED（退限购）」：两边先在 Q(p, CREDIT) 上串行。
     * 判据：零死锁；旧订单全部 REJECTED 且各计一次 refund_member_gone；最终限购占用 = 新订单份数；B 里只扣新订单的帮贡。
     */
    @Test
    void 兑换孤儿退款与别帮兑换同一商品() throws Exception {
        long oldGuild = 7996, newGuild = 7997, oldLeader = 8996, newLeader = 8997, buyer = 8998;
        long oldBase = 9_996_000, newBase = 9_997_000, cost = 30, balance = 10_000;
        int orphans = 12, limit = 1000;
        db.seedGuild(oldGuild, 2, 1, 50, oldLeader, Map.of(buyer, GuildRoles.MEMBER));
        db.seedGuild(newGuild, 2, 1, 50, newLeader, Map.of());
        setContribution(db, oldGuild, buyer, balance, balance);
        for (int i = 0; i < orphans; i++) {
            assertThat(econ.reserveShopOrder(shopOrder(oldBase + i, buyer, oldGuild, 1, cost, limit, NOW + i), roomy()).isOk())
                    .isTrue();
        }
        assertThat(guilds.leaveGuild(oldGuild, buyer, NOW + orphans, roomy()).isOk()).as("离开 A（商店指令不被提前截止）").isTrue();
        db.seedMember(newGuild, buyer, GuildRoles.MEMBER);
        setContribution(db, newGuild, buyer, balance, balance);
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);

        for (int i = 0; i < orphans; i++) {
            ShopReserve order = shopOrder(newBase + i, buyer, newGuild, 1, cost, limit, NOW + 1000 + i * 10L);
            long old = oldBase + i;
            List<Outcome> r = together(List.of(
                    () -> econ.reserveShopOrder(order, roomy()),
                    () -> finalizeOp(old, AssetOpStatus.REJECTED, rejected(), order.nowMs())));
            assertThat(((TxOutcome<?>) r.get(0).orThrow()).isOk()).as("第 %d 轮：在 B 兑换", i).isTrue();
            assertThat(((FinalizeResult) r.get(1).orThrow()).finalized()).as("第 %d 轮：旧订单只有这一个终结者", i).isTrue();
            assertThat(finalizeOp(order.opId(), AssetOpStatus.APPLIED, applied(), order.nowMs() + 1).finalized()).isTrue();
        }
        assertThat(db.count("SELECT COUNT(*) FROM guild_asset_op WHERE op_id BETWEEN ? AND ? AND status = ?", oldBase,
                oldBase + orphans - 1, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED_VALUE)).isEqualTo(orphans);
        assertThat(events.orphans).as("兑换者已离开 A：帮贡退不回去，每笔计一次孤儿")
                .isEqualTo(Collections.nCopies(orphans, Orphan.SHOP_REFUND_MEMBER_GONE));
        assertThat(counterUsed(db, buyer, COUNTER_SHOP, 101, DAY_KEY)).as("旧订单的限购全额退回，只剩新订单的占用").isEqualTo(orphans);
        assertThat(EconomyFixtures.contribution(db, newGuild, buyer)[1]).as("B 里只扣新订单的帮贡").isEqualTo(balance - cost * orphans);
        assertNoDeadlocks("兑换孤儿退款 ‖ 别帮兑换同一商品", watch);
    }

    // ================================================================ 与 4.4 事务：解散停在提前截止

    /**
     * 4.4 的「解散 ‖ 审批通过，uk 上 p 的后继是解散帮的成员」一例的基线原形（guild_lock_order_mysql_test.go）：4.4 用测试闸门表把解散停在
     * 「提前截止」那一步；现在换成真钩子，靠一笔被占锁事务点锁住的未决捐献把解散停在 O2 点锁上——删成员行与删申请之后、提交之前。
     * 判据：两边都成功、零死锁、p 名下申请清干净。
     */
    @Test
    void 解散停在提前截止_与审批通过() throws Exception {
        long g = 7671, g2 = 7672, gOld = 7673, lg = 8671, l2 = 8672, lOld = 8673, p = 8790, q = 8791;
        int zone = 2;
        db.seedGuild(g, zone, 1, 50, lg, Map.of(q, GuildRoles.MEMBER));
        db.seedGuild(g2, zone, 50, l2);
        db.seedGuild(gOld, zone, 1, 50, lOld, Map.of(p, GuildRoles.MEMBER));
        db.seedState(lg, q, p);
        db.seedApplication(g, p, NOW, NOW + TTL);
        db.seedApplication(g2, p, NOW, NOW + TTL);
        DonationReserve pendingDonation = donation(9_671_001, q, g, NOW);
        assertThat(econ.reserveDonation(pendingDonation, roomy()).isOk()).isTrue();
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);

        // 一个 RR 事务先做一次一致性读建出读视图，挡住 purge：否则 (p,gOld) 的删除标记项随时可能被清掉
        Connection reader = db.dataSource.getConnection();
        reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        reader.setAutoCommit(false);
        Connection holder = null;
        CompletableFuture<Outcome> disband;
        CompletableFuture<Outcome> approve;
        try {
            execOn(reader, "SELECT COUNT(*) FROM guild_member");
            db.exec("DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", gOld, p);
            // 占锁事务点锁 q 的未决捐献：解散会停在提前截止的 O2（删成员与删申请之后）
            holder = db.begin();
            execOn(holder, JdbcEconomyStore.LOCK_ASSET_OP, pendingDonation.opId());
            disband = async(() -> guilds.disbandGuild(g, lg, NOW + 10, null, roomy()));
            db.awaitLockWaits(1, 300);
            approve = async(() -> guilds.reviewApplication(g2, l2, p, true, zone, NOW + 10, roomy()));
            db.awaitLockWaits(2, 300);
            GuildMysqlFixture.sleep(100);
            finish(holder, true);
            holder = null;
            assertThat(((TxOutcome<?>) disband.get(30, TimeUnit.SECONDS).orThrow()).isOk()).as("解散").isTrue();
            assertThat(((TxOutcome<?>) approve.get(30, TimeUnit.SECONDS).orThrow()).isOk()).as("G2 批准 p").isTrue();
        } finally {
            if (holder != null) {
                finish(holder, false);
            }
            finish(reader, false);
        }
        assertThat(db.guildRows(g)).isZero();
        assertThat(db.memberships(q)).isZero();
        assertThat(db.roleOf(g2, p)).isEqualTo(GuildRoles.MEMBER);
        assertThat(db.playerApplications(p)).as("I2 / I3：p 名下申请清干净").isZero();
        assertThat(record(db, pendingDonation.opId()).getDeadlineMs()).as("解散把 q 的未决捐献截止提前到 now").isEqualTo(NOW + 10);
        assertNoDeadlocks("Disband(G，停在提前截止) ‖ Review(G2,p,通过)", watch);
    }
}
