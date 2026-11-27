package com.game.data.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.state.SqlWriterBootGrant;
import com.game.player.store.state.SqlWriterLifecycleReceipt;
import com.game.player.store.state.SqlWriterRegistry;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 覆盖主仓缺口清单第 22 条及全部 11-11/11-12 残余：
 * 按槽位换代、一代结束即删 64 片 slot、1100 次重启永不耗尽名册、init 重跑幂等重放、
 * 缩容收尾顺序与金丝雀自删报告、以及跨集群 {@code registered_by} 归属硬门禁。
 */
class SqlWriterSlotSuccessionAndRetirerTest {

    @TempDir
    Path tempDir;

    private static final String SCHEMA = "auth_slot_test";
    private static final ByteString DEPLOY_DIGEST = ByteString.copyFrom(new byte[32]);
    private static final ByteString QUAL_SHA = ByteString.copyFrom(new byte[32]);

    private Connection initDb(String gen) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:slot_cat_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        Connection c = ds.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute("CREATE TABLE `" + SCHEMA + "`.route_meta("
                    + "id INT PRIMARY KEY,generation VARCHAR(36) NOT NULL,writable INT NOT NULL,"
                    + "fence_shards INT NOT NULL,writer_epoch BIGINT NOT NULL,catalog_revision BIGINT NOT NULL,"
                    + "freeze_run_id VARBINARY(16) NOT NULL)");
            s.execute("CREATE TABLE `" + SCHEMA + "`.writer_registry("
                    + "writer_key BINARY(16) PRIMARY KEY,generation VARCHAR(36) NOT NULL,instance_id BINARY(16) NOT NULL,"
                    + "sql_principal VARBINARY(288) NOT NULL,registration_digest BINARY(32) NOT NULL,"
                    + "state TINYINT NOT NULL,registered_ms BIGINT NOT NULL,retirement_request_id VARBINARY(16) NOT NULL,"
                    + "retirement_digest VARBINARY(32) NOT NULL,retired_ms BIGINT NOT NULL,boot_seq BIGINT NOT NULL DEFAULT 1,"
                    + "UNIQUE(sql_principal,boot_seq),UNIQUE(instance_id))");
            s.execute("CREATE TABLE `" + SCHEMA + "`.writer_slot("
                    + "writer_key BINARY(16) NOT NULL,slot TINYINT NOT NULL,generation VARCHAR(36) NOT NULL,"
                    + "writer_epoch BIGINT NOT NULL,state TINYINT NOT NULL,lock_touch TINYINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(writer_key,slot))");
            s.execute("CREATE TABLE `" + SCHEMA + "`.writer_lifecycle_receipt("
                    + "request_id BINARY(16) PRIMARY KEY,kind TINYINT NOT NULL,generation VARCHAR(36) NOT NULL,"
                    + "request_digest BINARY(32) NOT NULL,request_proto BLOB NOT NULL,"
                    + "previous_epoch BIGINT NOT NULL,resulting_epoch BIGINT NOT NULL,catalog_revision BIGINT NOT NULL)");
            s.execute("INSERT INTO `" + SCHEMA + "`.route_meta VALUES(1,'" + gen + "',1,64,1,1,X'')");
        }
        return c;
    }

    private static SqlWriterLifecycleReceipt.Request registerReq(String gen, String principal, long bootSeq, String registeredBy) {
        return SqlWriterLifecycleReceipt.Request.newBuilder()
                .setRequestId(SqlWriterSlotCatalog.randomUuidBytes())
                .setKind(SqlWriterLifecycleReceipt.Kind.REGISTER)
                .setWriter(SqlWriterRegistry.newBuilder()
                        .setWriterKey(SqlWriterSlotCatalog.randomUuidBytes())
                        .setGeneration(gen)
                        .setInstanceId(SqlWriterSlotCatalog.randomUuidBytes())
                        .setSqlPrincipal(ByteString.copyFrom(principal, StandardCharsets.UTF_8))
                        .setState(SqlWriterRegistry.State.ACTIVE)
                        .setBootSeq(bootSeq)
                        .build())
                .setExpectedEpoch(0)
                .setAuthoritySchema(SCHEMA)
                .addProtectedSchemas(SCHEMA)
                .setDeploymentDigest(DEPLOY_DIGEST)
                .setDeploymentId("pod-" + bootSeq)
                .setLockPrimitive(SqlWriterBootGrant.LockPrimitive.LOCK_TOUCH)
                .setLockQualificationSha256(QUAL_SHA)
                .setRegisteredBy(registeredBy)
                .build();
    }

    @Test
    void slotSuccessionRetiresPreviousDeletesSlotsAndEnforcesFreezeAndDrainGuards() throws Exception {
        String gen = UUID.randomUUID().toString();
        SqlWriterSlotCatalog catalog = new SqlWriterSlotCatalog(SCHEMA);
        try (Connection c = initDb(gen)) {
            // 代 1 注册
            var r1 = registerReq(gen, "match_slot_0", 1L, "k8s-sa:prod/registrar/uid-1");
            var res1 = catalog.registerSlotGeneration(c, r1, 1000L);
            assertThat(res1.replayed()).isFalse();
            assertThat(res1.retiredPredecessorReceipt()).isNull();
            assertThat(res1.registerReceipt().getCatalogRevision()).isEqualTo(2L);
            assertThat(catalog.countActiveWriters(c)).isEqualTo(1L);
            assertThat(catalog.countSlotRows(c)).isEqualTo(64L);

            // 旧 boot_seq 抢跑/重放拒绝
            assertThatThrownBy(() -> catalog.registerSlotGeneration(c, registerReq(gen, "match_slot_0", 1L, ""), 1001L))
                    .isInstanceOf(SQLException.class);

            // 冻结时返回可重试异常
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE `" + SCHEMA + "`.route_meta SET writable=0 WHERE id=1");
            }
            assertThatThrownBy(() -> catalog.registerSlotGeneration(c, registerReq(gen, "match_slot_0", 2L, ""), 1002L))
                    .isInstanceOf(SqlWriterSlotCatalog.FrozenRetryableException.class);
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE `" + SCHEMA + "`.route_meta SET writable=1 WHERE id=1");
            }

            // 排空 run 进行中禁止换代
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE `" + SCHEMA + "`.route_meta SET freeze_run_id=X'0102030405060708090A0B0C0D0E0F10' WHERE id=1");
            }
            assertThatThrownBy(() -> catalog.registerSlotGeneration(c, registerReq(gen, "match_slot_0", 2L, ""), 1003L))
                    .isInstanceOf(SQLException.class);
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE `" + SCHEMA + "`.route_meta SET freeze_run_id=X'' WHERE id=1");
            }

            // 代 2 换代：单事务写 r+1 REGISTER、删代 1 的 64 片 slot、写带 successor_request_id 的 r+2 RETIRE
            var r2 = registerReq(gen, "match_slot_0", 2L, "k8s-sa:prod/registrar/uid-1");
            var res2 = catalog.registerSlotGeneration(c, r2, 2000L);
            assertThat(res2.registerReceipt().getCatalogRevision()).isEqualTo(3L);
            assertThat(res2.retiredPredecessorReceipt()).isNotNull();
            assertThat(res2.retiredPredecessorReceipt().getCatalogRevision()).isEqualTo(4L);
            var retireProto = SqlWriterLifecycleReceipt.Request.parseFrom(res2.retiredPredecessorReceipt().getRequestProto());
            assertThat(retireProto.getSuccessorRequestId()).isEqualTo(r2.getRequestId());

            // 整表 slot 行恒为 ACTIVE * 64，上一代 slot 已被彻底删除（0 行）
            assertThat(catalog.countActiveWriters(c)).isEqualTo(1L);
            assertThat(catalog.countSlotRows(c)).isEqualTo(64L);
            assertThat(catalog.countSlotRowsForWriter(c, r1.getWriter().getWriterKey())).isZero();
            assertThat(catalog.countSlotRowsForWriter(c, r2.getWriter().getWriterKey())).isEqualTo(64L);

            // 已被换代的代 1 再次人工退休：确定返回 GenerationAlreadyEndedException
            var staleRetire = SqlWriterLifecycleReceipt.Request.newBuilder()
                    .setRequestId(SqlWriterSlotCatalog.randomUuidBytes())
                    .setKind(SqlWriterLifecycleReceipt.Kind.RETIRE)
                    .setWriter(r1.getWriter().toBuilder().setRegistrationDigest(res1.registerReceipt().getRequestDigest()))
                    .setExpectedEpoch(1L).setExpectedCatalogRevision(4L)
                    .setAuthoritySchema(SCHEMA).addProtectedSchemas(SCHEMA)
                    .setDeploymentDigest(DEPLOY_DIGEST).setDeploymentId("manual").build();
            assertThatThrownBy(() -> catalog.retireGeneration(c, staleRetire, 2100L))
                    .isInstanceOf(SqlWriterSlotCatalog.GenerationAlreadyEndedException.class);

            // 人工退休代 2：删 64 片 slot、幂等重放、同槽位可继续注册代 3
            var manualRetire = SqlWriterLifecycleReceipt.Request.newBuilder()
                    .setRequestId(SqlWriterSlotCatalog.randomUuidBytes())
                    .setKind(SqlWriterLifecycleReceipt.Kind.RETIRE)
                    .setWriter(r2.getWriter().toBuilder().setRegistrationDigest(res2.registerReceipt().getRequestDigest()))
                    .setExpectedEpoch(1L).setExpectedCatalogRevision(4L)
                    .setAuthoritySchema(SCHEMA).addProtectedSchemas(SCHEMA)
                    .setDeploymentDigest(DEPLOY_DIGEST).setDeploymentId("manual").build();
            var m1 = catalog.retireGeneration(c, manualRetire, 3000L);
            var m2 = catalog.retireGeneration(c, manualRetire, 3001L);
            assertThat(m2).isEqualTo(m1);
            assertThat(catalog.countActiveWriters(c)).isZero();
            assertThat(catalog.countSlotRows(c)).isZero();

            var r3 = registerReq(gen, "match_slot_0", 3L, "k8s-sa:prod/registrar/uid-1");
            catalog.registerSlotGeneration(c, r3, 4000L);
            assertThat(catalog.countActiveWriters(c)).isEqualTo(1L);
            assertThat(catalog.countSlotRows(c)).isEqualTo(64L);
        }

        // TestLockedSlotsOutcome 分类边界
        assertThat(SqlWriterSlotCatalog.classifyLockedSlots(List.of(), gen, 1L))
                .isEqualTo(SqlWriterSlotCatalog.LockedSlotsOutcome.ALREADY_ENDED);
        assertThat(SqlWriterSlotCatalog.classifyLockedSlots(
                List.of(new SqlWriterSlotCatalog.SlotRow(0, gen, 1L, 1)), gen, 1L))
                .isEqualTo(SqlWriterSlotCatalog.LockedSlotsOutcome.CORRUPT);
    }

    @Test
    void frequentRestartsExceeding1024NeverExhaustRosterAndInitReplayAndIdentityFailClosed() throws Exception {
        String gen = UUID.randomUUID().toString();
        SqlWriterSlotCatalog catalog = new SqlWriterSlotCatalog(SCHEMA);
        SqlWriterSlotRegistrar registrar = new SqlWriterSlotRegistrar();
        var roster = new SqlWriterSlotRegistrar.SlotRoster("game-match", List.of("slot_0", "slot_1"));
        SqlWriterSlotRegistrar.validateRosters(List.of(roster), Map.of("game-match", 2));

        try (Connection c = initDb(gen)) {
            // K8s 身份读取失败时什么都不登记（fail-closed）
            Path failRecord = tempDir.resolve("fail-boot.pb");
            assertThatThrownBy(() -> registrar.registerPodBoot(
                    c, catalog, roster, "game-match-0", "pod-fail", gen, SCHEMA, List.of(SCHEMA),
                    DEPLOY_DIGEST, SqlWriterBootGrant.LockPrimitive.LOCK_TOUCH, QUAL_SHA,
                    failRecord, true, () -> { throw new IOException("api unreachable"); }, 1000L))
                    .isInstanceOf(IOException.class);
            assertThat(catalog.countActiveWriters(c)).isZero();
            assertThat(failRecord).doesNotExist();

            // 同 Pod init 重跑幂等重放启动记录
            Path replayRecord = tempDir.resolve("pod-replay.pb");
            var first = registrar.registerPodBoot(
                    c, catalog, roster, "game-match-0", "pod-uid-a", gen, SCHEMA, List.of(SCHEMA),
                    DEPLOY_DIGEST, SqlWriterBootGrant.LockPrimitive.LOCK_TOUCH, QUAL_SHA,
                    replayRecord, true, () -> "k8s-sa:prod/registrar/uid-own", 1000L);
            var replay = registrar.registerPodBoot(
                    c, catalog, roster, "game-match-0", "pod-uid-a", gen, SCHEMA, List.of(SCHEMA),
                    DEPLOY_DIGEST, SqlWriterBootGrant.LockPrimitive.LOCK_TOUCH, QUAL_SHA,
                    replayRecord, true, () -> "k8s-sa:prod/registrar/uid-own", 1001L);
            assertThat(first.replayed()).isFalse();
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.registerReceipt().getRequestId()).isEqualTo(first.registerReceipt().getRequestId());

            // 同一槽位连续重启 1100 次（超过旧 1024 上限）：slot 表始终恒定 64 行，不耗尽名册
            for (int i = 2; i <= 1100; i++) {
                Path bootFile = tempDir.resolve("boot-" + i + ".pb");
                registrar.registerPodBoot(
                        c, catalog, roster, "game-match-0", "pod-uid-" + i, gen, SCHEMA, List.of(SCHEMA),
                        DEPLOY_DIGEST, SqlWriterBootGrant.LockPrimitive.LOCK_TOUCH, QUAL_SHA,
                        bootFile, false, null, 2000L + i);
            }
            assertThat(catalog.maxBootSeq(c, ByteString.copyFrom("slot_0", StandardCharsets.UTF_8))).isEqualTo(1100L);
            assertThat(catalog.countActiveWriters(c)).isEqualTo(1L);
            assertThat(catalog.countSlotRows(c)).isEqualTo(64L);
        }
    }

    @Test
    void shrinkRetirerChecksOrderCanaryCleanupAndClusterOwnership() throws Exception {
        String gen = UUID.randomUUID().toString();
        SqlWriterSlotCatalog catalog = new SqlWriterSlotCatalog(SCHEMA);
        var roster = new SqlWriterSlotRegistrar.SlotRoster("game-match",
                List.of("slot_0", "slot_1", "slot_2", "slot_3", "slot_4", "slot_5", "slot_6"));
        var retirer = new SqlWriterSlotRetirer("prod", SCHEMA, List.of(SCHEMA), DEPLOY_DIGEST);

        try (Connection c = initDb(gen)) {
            // slot_0..1 本集群合法登记；slot_2 外集群 UID；slot_3 异 namespace；slot_4 空身份；slot_5 非法格式；slot_6 SA 不存在
            catalog.registerSlotGeneration(c, registerReq(gen, "slot_0", 1L, "k8s-sa:prod/registrar/uid-own"), 1000L);
            catalog.registerSlotGeneration(c, registerReq(gen, "slot_1", 1L, "k8s-sa:prod/registrar/uid-own"), 1001L);
            catalog.registerSlotGeneration(c, registerReq(gen, "slot_2", 1L, "k8s-sa:prod/registrar/uid-foreign"), 1002L);
            catalog.registerSlotGeneration(c, registerReq(gen, "slot_3", 1L, "k8s-sa:staging/registrar/uid-own"), 1003L);
            catalog.registerSlotGeneration(c, registerReq(gen, "slot_4", 1L, ""), 1004L);
            catalog.registerSlotGeneration(c, registerReq(gen, "slot_5", 1L, "garbage-identity"), 1005L);
            catalog.registerSlotGeneration(c, registerReq(gen, "slot_6", 1L, "k8s-sa:prod/missing-sa/uid-x"), 1006L);

            // 1. Pod 仍在时不动且不查 SA 归属
            AtomicInteger saLookups = new AtomicInteger();
            var podAliveCluster = new SqlWriterSlotRetirer.ClusterObserver() {
                @Override public int desiredReplicas(String w) { return 1; }
                @Override public boolean podExists(String w, int ord) { return ord == 1; }
                @Override public Optional<String> liveServiceAccountUid(String ns, String sa) {
                    saLookups.incrementAndGet();
                    return "registrar".equals(sa) ? Optional.of("uid-own") : Optional.empty();
                }
            };
            var onlySlot1Roster = new SqlWriterSlotRegistrar.SlotRoster("game-match", List.of("slot_0", "slot_1"));
            var rPodAlive = retirer.reconcile(c, catalog, onlySlot1Roster, podAliveCluster, 2000L);
            assertThat(rPodAlive.retired()).isZero();
            assertThat(rPodAlive.pending()).isEqualTo(1);
            assertThat(saLookups.get()).isZero();

            // 2. 读完目录之后又扩回来：跳过不动
            AtomicInteger repCalls = new AtomicInteger();
            var scaledBackCluster = new SqlWriterSlotRetirer.ClusterObserver() {
                @Override public int desiredReplicas(String w) { return repCalls.getAndIncrement() == 0 ? 1 : 2; }
                @Override public boolean podExists(String w, int ord) { return false; }
                @Override public Optional<String> liveServiceAccountUid(String ns, String sa) { return Optional.of("uid-own"); }
            };
            var rScaledBack = retirer.reconcile(c, catalog, onlySlot1Roster, scaledBackCluster, 2100L);
            assertThat(rScaledBack.retired()).isZero();
            assertThat(rScaledBack.pending()).isZero();
            assertThat(rScaledBack.skipped()).isEqualTo(1);

            // 3. 整个工作负载删除（replicas=0，金丝雀收尾）且 Pod 已删：
            //    本集群 slot_0 与 slot_1 被退休并删 slot，外集群/异ns/空/乱码/缺失SA 5 个全部被保护不动并记 registeredElsewhere/failed
            var allGoneCluster = new SqlWriterSlotRetirer.ClusterObserver() {
                @Override public int desiredReplicas(String w) { return 0; }
                @Override public boolean podExists(String w, int ord) { return false; }
                @Override public Optional<String> liveServiceAccountUid(String ns, String sa) {
                    return "registrar".equals(sa) ? Optional.of("uid-own") : Optional.empty();
                }
            };
            var rMixed = retirer.reconcile(c, catalog, roster, allGoneCluster, 3000L);
            assertThat(rMixed.retired()).isEqualTo(2);
            assertThat(rMixed.registeredElsewhere()).isEqualTo(5);
            assertThat(rMixed.pending()).isEqualTo(5);
            assertThat(rMixed.failed()).isEqualTo(5);
            assertThat(rMixed.canDeleteCanaryCronJob()).isFalse();
            assertThat(catalog.countActiveWriters(c)).isEqualTo(5L);
            assertThat(catalog.countSlotRows(c)).isEqualTo(5L * 64L);

            // 4. 仅含本集群槽位的金丝雀工作负载：再次对账 pending==0 && failed==0，允许自删定时任务
            var rCanaryDone = retirer.reconcile(c, catalog, onlySlot1Roster, allGoneCluster, 3100L);
            assertThat(rCanaryDone.allSettled()).isTrue();
            assertThat(rCanaryDone.canDeleteCanaryCronJob()).isTrue();
        }
    }
}
