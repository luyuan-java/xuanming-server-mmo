package com.game.guild.asset;

import static com.game.guild.store.EconomyFixtures.LEASE_MS;
import static com.game.guild.store.EconomyFixtures.record;
import static com.game.guild.store.EconomyFixtures.roomy;
import static com.game.guild.store.GuildMysqlFixture.NOW;
import static com.game.guild.store.GuildMysqlFixture.execOn;
import static com.game.guild.store.GuildMysqlFixture.finish;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetOutcome;
import com.game.guild.asset.GuildAssetStore.RescheduleResult;
import com.game.guild.store.EconomyFixtures;
import com.game.guild.store.EconomyFixtures.AssetEvents;
import com.game.guild.store.EconomyFixtures.BackgroundRecorder;
import com.game.guild.store.GuildMysqlFixture;
import com.game.guild.store.InnoDbDeadlockWatch;
import com.game.guild.store.JdbcEconomyStore;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 后台 CAS 排在悲观写者之后（真库，缺省跳过；移植 mmorpg economy_repo_test.go:2482-2579 TestEconomyLockOrder_BackgroundCASQueuesBehindPessimisticWriter；
 * guild-economy-spec §11.3「后台 CAS 排在悲观写者之后」）。写者 B 在显式 RC 事务里对一行未决捐献做提前截止（O2 点锁 → O3 点改），暂不提交；
 * 此时循环对同一行做重排或推迟毒行；随后 B 提交。修复后（G-C2 / V1）重排 / 毒行在显式事务里首句点锁，排在 B 后面，B 立即提交，后台写随后
 * 写入。TiDB 上才有鉴别力（修复前 B 的 COMMIT 会卡满乐观锁 TTL）；MySQL 上是行为回归。判据：B 的 COMMIT 耗时 &lt; 2 s、后台写最终写入、零死锁。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class AssetStoreLockOrderMysqlTest {

    private static final long GUILD = 7995;
    private static final long PLAYER = 8995;
    private static final long TOKEN = 0x6b6b;
    private static final long COMMIT_CEILING_MS = 2_000;

    private static GuildMysqlFixture db;
    private BackgroundRecorder background;
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
        background = new BackgroundRecorder();
        store = EconomyFixtures.assets(db, background, new AssetEvents());
    }

    @Test
    void 重排排在提前截止之后() throws Exception {
        AssetOp op = new AssetOp(9_995_001, PLAYER, EconomyFixtures.DEBIT, 1, NOW, 9_995_001, 24,
                com.game.api.proto.AssetBundle.getDefaultInstance(), 0, 0, TOKEN, 0);
        runBehindWriter(9_995_001, 1, () -> {
            AssetOpResult retry = new AssetOpResult(AssetOutcome.ASSET_OUTCOME_RETRY, AssetOpDecisions.REASON_IN_BATTLE, false,
                    false, false);
            return store.reschedule(op, NOW + 5000, retry, NOW + 2, roomy());
        }, rec -> {
            assertThat(rec.getAttempts()).isEqualTo(1);
            assertThat(rec.getNextAttemptMs()).as("重排排在 B 之后写入").isEqualTo(NOW + 5000);
            assertThat(rec.getLeaseUntilMs()).isZero();
        }, RescheduleResult.RESCHEDULED);
    }

    @Test
    void 毒行推迟排在提前截止之后() throws Exception {
        runBehindWriter(9_995_002, 2, () -> {
            store.markPoison(9_995_002, TOKEN, NOW + 2, NOW + 3_600_000, roomy());
            return null;
        }, rec -> {
            assertThat(rec.getNextAttemptMs()).as("毒行推迟排在 B 之后写入").isEqualTo(NOW + 3_600_000);
            assertThat(rec.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE);
        }, null);
    }

    private void runBehindWriter(long opId, long seq, java.util.concurrent.Callable<Object> backgroundWrite,
                                 Consumer<GuildAssetOpRow> check, Object wantResult) throws Exception {
        // 同步投递期间的形状：握着租约，next_attempt_ms = 租约到期时刻 > now（提前截止的 LEAST 真的会改 idx_0）
        EconomyFixtures.insertOp(db, EconomyFixtures.donateRecord(opId, PLAYER, GUILD, NOW, seq,
                EconomyFixtures.donatePayload(100)).setLeaseToken(TOKEN).setLeaseUntilMs(NOW + LEASE_MS).build());
        InnoDbDeadlockWatch watch = InnoDbDeadlockWatch.start(db);
        long acceleratedAt = NOW + 1;
        Connection writer = db.begin();
        CompletableFuture<Object> done;
        long commitElapsed;
        try {
            // 与生产的 accelerateDonationDeadlines 同序：先主键点锁，再带复核条件点改
            execOn(writer, JdbcEconomyStore.LOCK_ASSET_OP, opId);
            execOn(writer, "UPDATE guild_asset_op SET `deadline_ms` = ?, `next_attempt_ms` = LEAST(`next_attempt_ms`, ?),"
                    + " `updated_ms` = ? WHERE `op_id` = ? AND `status` = ? AND `deadline_ms` > ?", acceleratedAt, acceleratedAt,
                    acceleratedAt, opId, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE, acceleratedAt);
            done = CompletableFuture.supplyAsync(() -> {
                try {
                    return backgroundWrite.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            db.awaitLockWaits(1, 300);
            long start = System.nanoTime();
            finish(writer, true);
            commitElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } catch (SQLException | RuntimeException e) {
            finish(writer, false);
            throw e;
        }
        assertThat(done.get(30, TimeUnit.SECONDS)).isEqualTo(wantResult);
        assertThat(commitElapsed).as("持锁写者的 COMMIT 被后台写卡了 %d ms（TiDB 上这就是 G-C2）", commitElapsed)
                .isLessThan(COMMIT_CEILING_MS);
        GuildAssetOpRow stored = record(db, opId);
        assertThat(stored.getDeadlineMs()).as("B 的提前截止已提交").isEqualTo(acceleratedAt);
        assertThat(stored.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        check.accept(stored);
        background.assertNoDeadlocks("后台 CAS ‖ 悲观写者");
        watch.assertNone("后台 CAS ‖ 悲观写者");
    }
}
