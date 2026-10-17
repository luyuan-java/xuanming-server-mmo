package com.game.guild.asset;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.proto.*;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.player.store.state.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** 玩家SQL事务未提交时，在线旧scene声称durable不能释放活动奖励；提交后按同seq恢复partial。 */
class AssetOpSqlProofTiDbTest {
    @Test
    void 玩家SQL未提交不可终结提交后同操作恢复且保留partial() throws Exception {
        String base = System.getenv("MAIL_RANK_TEST_JDBC");
        if (base == null || base.isBlank()) {
            assertNotEquals("1", System.getenv("MAIL_RANK_REQUIRE_TESTS"), "真库门禁要求DSN");
            Assumptions.assumeTrue(false, "未提供TiDB夹具");
        }
        String database = "guild_proof_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var pool = new DruidDataSource();
        try {
            try (var c = DriverManager.getConnection(base, "root", ""); var s = c.createStatement()) {
                s.execute("CREATE DATABASE " + database);
            }
            pool.setUrl(base + database + "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true");
            pool.setUsername("root");
            pool.setPassword("");
            pool.setMaxActive(4);
            pool.setInitialSize(2);
            pool.setConnectionInitSqls(List.of(StrongTiDbConnectionPolicy.SESSION_SQL));
            pool.init();
            try (var first = pool.getConnection(); var second = pool.getConnection(); var s = first.createStatement()) {
                StrongTiDbConnectionPolicy.verify(first, true);
                StrongTiDbConnectionPolicy.verify(second, true);
                s.execute("CREATE TABLE player_state(player_id BIGINT UNSIGNED PRIMARY KEY,data MEDIUMBLOB NOT NULL)");
            }
            long epoch = 1_700_000_000_000L;
            var stream = AssetOpStreamLedgerState.newBuilder().setStream(2).setStreamEpoch(epoch).setMaxSeq(1).addPartialSeqs(1);
            for (int i = 0; i < 16; i++) {
                stream.addSeenBits(i == 0 ? 1 : 0);
                stream.addAppliedBits(i == 0 ? 1 : 0);
            }
            byte[] snapshot =
                    PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.newBuilder().addStreams(stream)).build().toByteArray();
            var backing = new InMemoryAssetStore();
            var clock = new AtomicLong(1_800_000_000_000L);
            var bundle = AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(9).setCount(1)).build();
            backing.insert(InMemoryAssetStore.pending(1, 7, 1, bundle)
                            .setStream(2)
                            .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD)
                            .setTxType(26)
                            .build());
            var config = new AssetOpLoop.Config(Duration.ofSeconds(2), 100, 1, Duration.ofSeconds(10), Duration.ofMillis(2500), 1000,
                    Duration.ofSeconds(60), Duration.ofHours(1), 3);
            var loop = new AssetOpLoop(config, backing,
                    (rpc, request, budget)
                            -> CompletableFuture.completedFuture(AssetOpCaller.Delivery.answered(
                                    new AssetOpResult(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true, false, false))),
                    PersistedLedgerReader.of(pool), AssetOpMetrics.NONE, clock::get, Runnable::run, Runnable::run, new Random(7),
                    () -> 0.5);
            try (var transaction = pool.getConnection();
                    var insert = transaction.prepareStatement("INSERT INTO player_state(player_id,data) VALUES(7,?)")) {
                transaction.setAutoCommit(false);
                insert.setBytes(1, snapshot);
                insert.executeUpdate();
                var first = loop.claim(1).orElseThrow();
                assertFalse(loop.processClaimed(first).finalized(), "RPC durable不能替代未提交的玩家SQL");
                transaction.commit();
                clock.addAndGet(60_000);
                var retried = loop.claim(1).orElseThrow();
                assertEquals(first.seq(), retried.seq());
                assertEquals(first.streamEpoch(), retried.streamEpoch());
                var result = loop.processClaimed(retried);
                assertTrue(result.finalized());
                assertEquals(AssetOpStatus.APPLIED_PARTIAL, result.status());
            }
        } finally {
            pool.close();
            assertTrue(database.matches("guild_proof_it_[0-9a-f]{16}"));
            try (var c = DriverManager.getConnection(base, "root", ""); var s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + database);
            }
        }
    }
}
