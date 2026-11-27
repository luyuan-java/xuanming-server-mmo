package com.game.data.tools;

import com.game.player.store.state.SqlWriterBootGrant;
import com.game.player.store.state.SqlWriterLifecycleReceipt;
import com.game.player.store.state.SqlWriterRegistry;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 写者名册按槽位换代与「一代结束即删 64 片 slot 行」的 SQL 目录事务实现（主仓缺口清单第 22 条及 11-11/11-12 残余）：
 * <ul>
 *   <li>同一槽位账号由各代共用，{@code boot_seq} 严格递增，{@code writer_key}/{@code instance_id} 每代唯一；</li>
 *   <li>注册即换代在单事务内先锁上一代 64 片 slot（等待在途写事务结束）再锁 {@code route_meta} 与登记行，写新一代 64 片与 REGISTER 收据（r+1），
 *       删除上一代 64 片 slot（{@code state=1} 且精确匹配 generation/epoch），标记上一代 REVOKED 并写带 {@code successor_request_id} 的 RETIRE 收据（r+2）；</li>
 *   <li>人工/排空/缩容退休同样删齐 64 片 slot 行，登记行与收据永久保留；读不到 slot 行即确定判定为 {@link LockedSlotsOutcome#ALREADY_ENDED}；</li>
 *   <li>排空 run 进行中（{@code freeze_run_id} 非空）拒绝换代；闸门冻结（{@code writable=0}）抛出可重试异常 {@link FrozenRetryableException}。</li>
 * </ul>
 */
public final class SqlWriterSlotCatalog {

    public static final int FENCE_SHARDS = 64;

    public enum LockedSlotsOutcome {
        /** 完整锁定 64 片在役 slot。 */
        ACTIVE_COMPLETE,
        /** 无任何 slot 行：该代已经结束（换代/退休），要求在役的路径必须返回确定性拒绝。 */
        ALREADY_ENDED,
        /** 行数非 0 且非 64，或世代/epoch/state 不一致：控制原件损坏。 */
        CORRUPT
    }

    /** 闸门冻结（writable=0）时的可重试异常。 */
    public static final class FrozenRetryableException extends SQLException {
        public FrozenRetryableException(String reason) {
            super(reason, "40001");
        }
    }

    /** 目标代次已经结束（slot 行为空或已 REVOKED）时的确定性拒绝。 */
    public static final class GenerationAlreadyEndedException extends SQLException {
        public GenerationAlreadyEndedException(String reason) {
            super(reason, "45000");
        }
    }

    /** 目录 revision CAS 冲突（并发注册/退休），调用方可在有界次数内重读快照重试。 */
    public static final class CatalogRevisionConflictException extends SQLException {
        public CatalogRevisionConflictException(String reason) {
            super(reason, "40001");
        }
    }

    /** 目录里某槽位在役写者的只读快照（缩容收尾先读快照再查集群）。 */
    public record ActiveGenerationSnapshot(
            ByteString writerKey,
            String generation,
            ByteString instanceId,
            ByteString sqlPrincipal,
            ByteString registrationDigest,
            long bootSeq,
            long epoch,
            long catalogRevision,
            boolean writable,
            boolean drainRunActive) {
    }

    /** 一次注册或换代事务的落地结果。 */
    public record RegistrationResult(
            SqlWriterLifecycleReceipt registerReceipt,
            SqlWriterLifecycleReceipt retiredPredecessorReceipt,
            boolean replayed) {
    }

    private final String schema;

    public SqlWriterSlotCatalog(String schema) {
        if (schema == null || !schema.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("authority schema 非法: " + schema);
        }
        this.schema = schema;
    }

    private String table(String name) {
        return "`" + schema + "`." + name;
    }

    /** 纯规则：根据锁定读回的 slot 行数与元数据一致性判定结果（对应主仓 {@code TestLockedSlotsOutcome}）。 */
    public static LockedSlotsOutcome classifyLockedSlots(List<SlotRow> rows, String expectedGeneration, long expectedEpoch) {
        if (rows == null || rows.isEmpty()) {
            return LockedSlotsOutcome.ALREADY_ENDED;
        }
        if (rows.size() != FENCE_SHARDS) {
            return LockedSlotsOutcome.CORRUPT;
        }
        for (int i = 0; i < FENCE_SHARDS; i++) {
            SlotRow r = rows.get(i);
            if (r.slot() != i || r.state() != 1 || r.epoch() != expectedEpoch
                    || !Objects.equals(expectedGeneration, r.generation())) {
                return LockedSlotsOutcome.CORRUPT;
            }
        }
        return LockedSlotsOutcome.ACTIVE_COMPLETE;
    }

    public record SlotRow(int slot, String generation, long epoch, int state) {}

    /**
     * 按槽位注册新一代写者（单事务内自动换代上一代并删除其 64 片 slot）。
     * {@code request.getExpectedEpoch()} 必须为 0（收据落地时记录实际 {@code route_meta.writer_epoch}）。
     */
    public RegistrationResult registerSlotGeneration(Connection c, SqlWriterLifecycleReceipt.Request input, long nowMs) throws Exception {
        validateRegisterRequest(input);
        ByteString digest = registerDigest(input);
        SqlWriterLifecycleReceipt.Request stampedRequest = input.toBuilder()
                .setWriter(input.getWriter().toBuilder().setRegistrationDigest(digest))
                .build();
        return inTx(c, () -> {
            // 1. 同 request_id 幂等重放
            SqlWriterLifecycleReceipt prior = readLifecycleReceipt(c, stampedRequest.getRequestId());
            if (prior != null) {
                if (prior.getKind() != SqlWriterLifecycleReceipt.Kind.REGISTER || !prior.getRequestDigest().equals(digest)) {
                    throw new SQLException("REGISTER request_id 已用于另一份原件", "45000");
                }
                return new RegistrationResult(prior, null, true);
            }

            // 2. 先查本账号现有在役代（未锁 route_meta 之前先锁上一代 64 片 slot，等待其全部在途写事务结束）
            ByteString principal = stampedRequest.getWriter().getSqlPrincipal();
            ByteString preKey = findActiveWriterKey(c, principal);
            List<SlotRow> lockedPreSlots = List.of();
            if (preKey != null) {
                lockedPreSlots = lockSlotsForUpdate(c, preKey);
            }

            // 3. 锁 route_meta（核对可写、非冻结、无排空 run）
            MetaRow meta = lockMetaForUpdate(c);
            if (!meta.generation().equals(stampedRequest.getWriter().getGeneration())) {
                throw new SQLException("REGISTER 世代与 route_meta 不符", "45000");
            }
            if (meta.writable() == 0) {
                throw new FrozenRetryableException("逻辑库当前冻结(writable=0)，注册稍后重试");
            }
            if (!meta.freezeRunId().isEmpty()) {
                throw new SQLException("排空 run 进行中(freeze_run_id 非空)，禁止槽位换代", "45000");
            }
            if (stampedRequest.getExpectedCatalogRevision() != 0
                    && meta.catalogRevision() != stampedRequest.getExpectedCatalogRevision()) {
                throw new CatalogRevisionConflictException("REGISTER catalog_revision 冲突");
            }

            // 4. 锁该账号全部登记行，核实 boot_seq 严格递增并复核真实的在役上一代
            List<RegistryRow> allForPrincipal = lockRegistryByPrincipalForUpdate(c, principal);
            long maxBootSeq = 0L;
            RegistryRow activePredecessor = null;
            for (RegistryRow row : allForPrincipal) {
                if (Long.compareUnsigned(row.bootSeq(), maxBootSeq) > 0) {
                    maxBootSeq = row.bootSeq();
                }
                if (row.state() == 1) {
                    if (activePredecessor != null) {
                        throw new SQLException("同槽位账号存在多个 ACTIVE 代，目录不变量损坏", "45000");
                    }
                    activePredecessor = row;
                }
            }
            long newBootSeq = stampedRequest.getWriter().getBootSeq();
            if (Long.compareUnsigned(newBootSeq, maxBootSeq) <= 0) {
                throw new SQLException("boot_seq 必须严格大于该账号已有的最大代次(" + Long.toUnsignedString(maxBootSeq) + ")", "45000");
            }

            // 若并发出现新的 ACTIVE 上一代（尚未锁它的 64 片），先补锁再复核
            if (activePredecessor != null && !activePredecessor.writerKey().equals(preKey)) {
                lockedPreSlots = lockSlotsForUpdate(c, activePredecessor.writerKey());
            }

            long revAfterRegister = meta.catalogRevision() + 1L;
            var writer = stampedRequest.getWriter();

            // 5. 写入新一代登记行与 64 片 slot
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + table("writer_registry")
                            + "(writer_key,generation,instance_id,sql_principal,registration_digest,state,registered_ms,"
                            + "retirement_request_id,retirement_digest,retired_ms,boot_seq) VALUES(?,?,?,?,?,1,?,?,?,?,?)")) {
                ps.setBytes(1, writer.getWriterKey().toByteArray());
                ps.setString(2, meta.generation());
                ps.setBytes(3, writer.getInstanceId().toByteArray());
                ps.setBytes(4, principal.toByteArray());
                ps.setBytes(5, digest.toByteArray());
                ps.setObject(6, u64Big(nowMs));
                ps.setBytes(7, new byte[0]);
                ps.setBytes(8, new byte[0]);
                ps.setObject(9, BigInteger.ZERO);
                ps.setObject(10, u64Big(newBootSeq));
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("插入新一代 writer_registry 失败", "45000");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + table("writer_slot")
                            + "(writer_key,slot,generation,writer_epoch,state,lock_touch) VALUES(?,?,?,?,1,0)")) {
                for (int slot = 0; slot < FENCE_SHARDS; slot++) {
                    ps.setBytes(1, writer.getWriterKey().toByteArray());
                    ps.setInt(2, slot);
                    ps.setString(3, meta.generation());
                    ps.setObject(4, u64Big(meta.epoch()));
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("插入新一代 writer_slot 失败", "45000");
                    }
                }
            }
            SqlWriterLifecycleReceipt registerReceipt = SqlWriterLifecycleReceipt.newBuilder()
                    .setRequestId(stampedRequest.getRequestId())
                    .setKind(SqlWriterLifecycleReceipt.Kind.REGISTER)
                    .setGeneration(meta.generation())
                    .setRequestDigest(digest)
                    .setRequestProto(ByteString.copyFrom(canonical(stampedRequest)))
                    .setPreviousEpoch(meta.epoch())
                    .setResultingEpoch(meta.epoch())
                    .setCatalogRevision(revAfterRegister)
                    .build();
            insertLifecycleReceipt(c, registerReceipt);

            long finalCatalogRev = revAfterRegister;
            SqlWriterLifecycleReceipt retireReceipt = null;

            // 6. 同事务结束上一代：删除其 64 片 slot（精确匹配 state=1 & generation & epoch），标记 REVOKED，追加带 successor_request_id 的 RETIRE 收据
            if (activePredecessor != null) {
                LockedSlotsOutcome outcome = classifyLockedSlots(lockedPreSlots, meta.generation(), meta.epoch());
                if (outcome != LockedSlotsOutcome.ACTIVE_COMPLETE) {
                    throw new SQLException("上一代 ACTIVE 写者 64 片状态异常: " + outcome, "45000");
                }
                int deleted = deleteActiveSlots(c, activePredecessor.writerKey(), meta.generation(), meta.epoch());
                if (deleted != FENCE_SHARDS) {
                    throw new SQLException("换代删除上一代 slot 数量不为 64: " + deleted, "45000");
                }
                ByteString retireRequestId = deterministicSuccessionRetireId(stampedRequest.getRequestId());
                long revAfterRetire = revAfterRegister + 1L;
                SqlWriterLifecycleReceipt.Request retireReq = SqlWriterLifecycleReceipt.Request.newBuilder()
                        .setRequestId(retireRequestId)
                        .setKind(SqlWriterLifecycleReceipt.Kind.RETIRE)
                        .setWriter(SqlWriterRegistry.newBuilder()
                                .setWriterKey(activePredecessor.writerKey())
                                .setGeneration(activePredecessor.generation())
                                .setInstanceId(activePredecessor.instanceId())
                                .setSqlPrincipal(activePredecessor.sqlPrincipal())
                                .setRegistrationDigest(activePredecessor.registrationDigest())
                                .setState(SqlWriterRegistry.State.ACTIVE)
                                .setBootSeq(activePredecessor.bootSeq())
                                .build())
                        .setExpectedEpoch(meta.epoch())
                        .setExpectedCatalogRevision(revAfterRegister)
                        .setAuthoritySchema(schema)
                        .addAllProtectedSchemas(stampedRequest.getProtectedSchemasList())
                        .setDeploymentDigest(stampedRequest.getDeploymentDigest())
                        .setDeploymentId(stampedRequest.getDeploymentId())
                        .setSuccessorRequestId(stampedRequest.getRequestId())
                        .build();
                ByteString retireDigest = sha256(canonical(retireReq));
                markRegistryRevoked(c, activePredecessor.writerKey(), retireRequestId, retireDigest, nowMs);
                retireReceipt = SqlWriterLifecycleReceipt.newBuilder()
                        .setRequestId(retireRequestId)
                        .setKind(SqlWriterLifecycleReceipt.Kind.RETIRE)
                        .setGeneration(meta.generation())
                        .setRequestDigest(retireDigest)
                        .setRequestProto(ByteString.copyFrom(canonical(retireReq)))
                        .setPreviousEpoch(meta.epoch())
                        .setResultingEpoch(meta.epoch())
                        .setCatalogRevision(revAfterRetire)
                        .build();
                insertLifecycleReceipt(c, retireReceipt);
                finalCatalogRev = revAfterRetire;
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + table("route_meta") + " SET catalog_revision=? WHERE id=1 AND catalog_revision=?")) {
                ps.setObject(1, u64Big(finalCatalogRev));
                ps.setObject(2, u64Big(meta.catalogRevision()));
                if (ps.executeUpdate() != 1) {
                    throw new CatalogRevisionConflictException("route_meta catalog_revision CAS 失败");
                }
            }
            return new RegistrationResult(registerReceipt, retireReceipt, false);
        });
    }

    /**
     * 人工退休 / 缩容收尾（非排空 run 的 RETIRE）：
     * <ul>
     *   <li>同 request_id 重放幂等返回原收据；</li>
     *   <li>锁 64 片 slot 若空集，确定返回 {@link GenerationAlreadyEndedException}；</li>
     *   <li>闸门冻结抛 {@link FrozenRetryableException}，排空 run 进行中拒绝；</li>
     *   <li>精确核对 {@code (writer_key, instance_id, sql_principal, registration_digest, boot_seq)} 与在役状态，
     *       删除 64 片 slot（带 generation/epoch/state=1 条件且必须为 64 行），标记登记行 REVOKED 并永久追加 RETIRE 收据。</li>
     * </ul>
     */
    public SqlWriterLifecycleReceipt retireGeneration(Connection c, SqlWriterLifecycleReceipt.Request input, long nowMs) throws Exception {
        validateRetireRequest(input);
        ByteString digest = sha256(canonical(input));
        return inTx(c, () -> {
            SqlWriterLifecycleReceipt prior = readLifecycleReceipt(c, input.getRequestId());
            if (prior != null) {
                if (prior.getKind() != SqlWriterLifecycleReceipt.Kind.RETIRE || !prior.getRequestDigest().equals(digest)) {
                    throw new SQLException("RETIRE request_id 已用于另一份原件", "45000");
                }
                return prior;
            }

            List<SlotRow> lockedSlots = lockSlotsForUpdate(c, input.getWriter().getWriterKey());
            MetaRow meta = lockMetaForUpdate(c);
            if (meta.writable() == 0) {
                throw new FrozenRetryableException("逻辑库当前冻结(writable=0)，退休跳过");
            }
            if (!meta.freezeRunId().isEmpty()) {
                throw new FrozenRetryableException("排空 run 进行中，非 run 退休跳过");
            }
            if (!meta.generation().equals(input.getWriter().getGeneration()) || meta.epoch() != input.getExpectedEpoch()) {
                throw new SQLException("RETIRE 世代或 epoch 与当前 route_meta 不符", "45000");
            }
            if (meta.catalogRevision() != input.getExpectedCatalogRevision()) {
                throw new CatalogRevisionConflictException("RETIRE catalog_revision 冲突");
            }

            LockedSlotsOutcome outcome = classifyLockedSlots(lockedSlots, meta.generation(), meta.epoch());
            if (outcome == LockedSlotsOutcome.ALREADY_ENDED) {
                throw new GenerationAlreadyEndedException("目标写者代已结束（slot 行为空）");
            }
            if (outcome != LockedSlotsOutcome.ACTIVE_COMPLETE) {
                throw new SQLException("退休目标 64 片状态损坏: " + outcome, "45000");
            }

            RegistryRow reg = lockRegistryByKeyForUpdate(c, input.getWriter().getWriterKey());
            if (reg == null || reg.state() != 1) {
                throw new GenerationAlreadyEndedException("目标写者登记行已非 ACTIVE");
            }
            var wanted = input.getWriter();
            if (!reg.instanceId().equals(wanted.getInstanceId())
                    || !reg.sqlPrincipal().equals(wanted.getSqlPrincipal())
                    || !reg.registrationDigest().equals(wanted.getRegistrationDigest())
                    || (wanted.getBootSeq() != 0 && reg.bootSeq() != wanted.getBootSeq())) {
                throw new SQLException("退休目标身份或 boot_seq 与在役登记行不匹配", "45000");
            }

            int deleted = deleteActiveSlots(c, wanted.getWriterKey(), meta.generation(), meta.epoch());
            if (deleted != FENCE_SHARDS) {
                throw new SQLException("退休删除 slot 数量不为 64: " + deleted, "45000");
            }
            markRegistryRevoked(c, wanted.getWriterKey(), input.getRequestId(), digest, nowMs);
            long nextCatalog = meta.catalogRevision() + 1L;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + table("route_meta") + " SET catalog_revision=? WHERE id=1 AND catalog_revision=?")) {
                ps.setObject(1, u64Big(nextCatalog));
                ps.setObject(2, u64Big(meta.catalogRevision()));
                if (ps.executeUpdate() != 1) {
                    throw new CatalogRevisionConflictException("RETIRE catalog_revision CAS 失败");
                }
            }
            SqlWriterLifecycleReceipt receipt = SqlWriterLifecycleReceipt.newBuilder()
                    .setRequestId(input.getRequestId())
                    .setKind(SqlWriterLifecycleReceipt.Kind.RETIRE)
                    .setGeneration(meta.generation())
                    .setRequestDigest(digest)
                    .setRequestProto(ByteString.copyFrom(canonical(input)))
                    .setPreviousEpoch(meta.epoch())
                    .setResultingEpoch(meta.epoch())
                    .setCatalogRevision(nextCatalog)
                    .build();
            insertLifecycleReceipt(c, receipt);
            return receipt;
        });
    }

    /** 只读快照：按槽位账号读取最新一代 ACTIVE 写者与当前 route_meta 状态（缩容收尾先读目录再查集群）。 */
    public ActiveGenerationSnapshot snapshotActiveGeneration(Connection c, ByteString sqlPrincipal) throws SQLException {
        MetaRow meta = readMeta(c);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT writer_key,generation,instance_id,sql_principal,registration_digest,boot_seq "
                        + "FROM " + table("writer_registry") + " WHERE sql_principal=? AND state=1 ORDER BY boot_seq DESC LIMIT 1")) {
            ps.setBytes(1, sqlPrincipal.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                return new ActiveGenerationSnapshot(
                        ByteString.copyFrom(r.getBytes(1)),
                        r.getString(2),
                        ByteString.copyFrom(r.getBytes(3)),
                        ByteString.copyFrom(r.getBytes(4)),
                        ByteString.copyFrom(r.getBytes(5)),
                        u64(r, 6),
                        meta.epoch(),
                        meta.catalogRevision(),
                        meta.writable() == 1,
                        !meta.freezeRunId().isEmpty());
            }
        }
    }

    /** 读取指定槽位账号在目录中已登记的最大 boot_seq（不存在返回 0）。 */
    public long maxBootSeq(Connection c, ByteString sqlPrincipal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(boot_seq) FROM " + table("writer_registry") + " WHERE sql_principal=?")) {
            ps.setBytes(1, sqlPrincipal.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next() || r.getObject(1) == null) {
                    return 0L;
                }
                return u64(r, 1);
            }
        }
    }

    /** 按 registration_digest 读回原始 REGISTER 请求（供缩容收尾核验 {@code registered_by} 集群归属）。 */
    public SqlWriterLifecycleReceipt.Request readRegisterRequest(Connection c, ByteString registrationDigest) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT request_proto FROM " + table("writer_lifecycle_receipt") + " WHERE kind=1 AND request_digest=?")) {
            ps.setBytes(1, registrationDigest.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                byte[] bytes = r.getBytes(1);
                if (r.next()) {
                    throw new SQLException("REGISTER 收据摘要不唯一", "45000");
                }
                return SqlWriterLifecycleReceipt.Request.parseFrom(bytes);
            }
        }
    }

    /** 统计当前表里 ACTIVE 写者数与 writer_slot 总行数（用于验证 slot 表恒为 ACTIVE × 64）。 */
    public long countActiveWriters(Connection c) throws SQLException {
        return countQuery(c, "SELECT COUNT(*) FROM " + table("writer_registry") + " WHERE state=1");
    }

    public long countSlotRows(Connection c) throws SQLException {
        return countQuery(c, "SELECT COUNT(*) FROM " + table("writer_slot"));
    }

    public long countSlotRowsForWriter(Connection c, ByteString writerKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table("writer_slot") + " WHERE writer_key=?")) {
            ps.setBytes(1, writerKey.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getLong(1) : 0L;
            }
        }
    }

    private long countQuery(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet r = ps.executeQuery()) {
            return r.next() ? r.getLong(1) : 0L;
        }
    }

    // ------------------------------------------------------------------ 内部 SQL 与摘要工具

    private record MetaRow(String generation, long epoch, long catalogRevision, int writable, ByteString freezeRunId) {}

    private record RegistryRow(ByteString writerKey, String generation, ByteString instanceId, ByteString sqlPrincipal,
                               ByteString registrationDigest, int state, long bootSeq) {}

    @FunctionalInterface
    private interface TxWork<T> {
        T run() throws Exception;
    }

    private static <T> T inTx(Connection c, TxWork<T> work) throws Exception {
        boolean prevAuto = c.getAutoCommit();
        if (!prevAuto) {
            throw new SQLException("目录事务入口不能继承外部事务");
        }
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(false);
        try {
            T result = work.run();
            c.commit();
            c.setAutoCommit(true);
            return result;
        } catch (Exception failure) {
            try {
                c.rollback();
                c.setAutoCommit(true);
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private MetaRow readMeta(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT generation,writer_epoch,catalog_revision,writable,freeze_run_id,fence_shards FROM "
                        + table("route_meta") + " WHERE id=1");
             ResultSet r = ps.executeQuery()) {
            if (!r.next() || r.getInt(6) != FENCE_SHARDS) {
                throw new SQLException("route_meta 缺失或分片契约不为 64", "45000");
            }
            return new MetaRow(r.getString(1), u64(r, 2), u64(r, 3), r.getInt(4), ByteString.copyFrom(r.getBytes(5)));
        }
    }

    private MetaRow lockMetaForUpdate(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT generation,writer_epoch,catalog_revision,writable,freeze_run_id,fence_shards FROM "
                        + table("route_meta") + " WHERE id=1 FOR UPDATE");
             ResultSet r = ps.executeQuery()) {
            if (!r.next() || r.getInt(6) != FENCE_SHARDS) {
                throw new SQLException("route_meta 缺失或分片契约不为 64", "45000");
            }
            return new MetaRow(r.getString(1), u64(r, 2), u64(r, 3), r.getInt(4), ByteString.copyFrom(r.getBytes(5)));
        }
    }

    private ByteString findActiveWriterKey(Connection c, ByteString principal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT writer_key FROM " + table("writer_registry") + " WHERE sql_principal=? AND state=1 LIMIT 1")) {
            ps.setBytes(1, principal.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? ByteString.copyFrom(r.getBytes(1)) : null;
            }
        }
    }

    private List<SlotRow> lockSlotsForUpdate(Connection c, ByteString writerKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT slot,generation,writer_epoch,state FROM " + table("writer_slot")
                        + " WHERE writer_key=? ORDER BY slot FOR UPDATE")) {
            ps.setBytes(1, writerKey.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                List<SlotRow> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new SlotRow(r.getInt(1), r.getString(2), u64(r, 3), r.getInt(4)));
                }
                return out;
            }
        }
    }

    private List<RegistryRow> lockRegistryByPrincipalForUpdate(Connection c, ByteString principal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT writer_key,generation,instance_id,sql_principal,registration_digest,state,boot_seq FROM "
                        + table("writer_registry") + " WHERE sql_principal=? ORDER BY boot_seq FOR UPDATE")) {
            ps.setBytes(1, principal.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                List<RegistryRow> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new RegistryRow(
                            ByteString.copyFrom(r.getBytes(1)), r.getString(2),
                            ByteString.copyFrom(r.getBytes(3)), ByteString.copyFrom(r.getBytes(4)),
                            ByteString.copyFrom(r.getBytes(5)), r.getInt(6), u64(r, 7)));
                }
                return out;
            }
        }
    }

    private RegistryRow lockRegistryByKeyForUpdate(Connection c, ByteString writerKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT writer_key,generation,instance_id,sql_principal,registration_digest,state,boot_seq FROM "
                        + table("writer_registry") + " WHERE writer_key=? FOR UPDATE")) {
            ps.setBytes(1, writerKey.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                return new RegistryRow(
                        ByteString.copyFrom(r.getBytes(1)), r.getString(2),
                        ByteString.copyFrom(r.getBytes(3)), ByteString.copyFrom(r.getBytes(4)),
                        ByteString.copyFrom(r.getBytes(5)), r.getInt(6), u64(r, 7));
            }
        }
    }

    private int deleteActiveSlots(Connection c, ByteString writerKey, String generation, long epoch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + table("writer_slot")
                        + " WHERE writer_key=? AND generation=? AND writer_epoch=? AND state=1")) {
            ps.setBytes(1, writerKey.toByteArray());
            ps.setString(2, generation);
            ps.setObject(3, u64Big(epoch));
            return ps.executeUpdate();
        }
    }

    private void markRegistryRevoked(Connection c, ByteString writerKey, ByteString retireRequestId,
                                     ByteString retireDigest, long retiredMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + table("writer_registry")
                        + " SET state=2,retirement_request_id=?,retirement_digest=?,retired_ms=? WHERE writer_key=? AND state=1")) {
            ps.setBytes(1, retireRequestId.toByteArray());
            ps.setBytes(2, retireDigest.toByteArray());
            ps.setObject(3, u64Big(retiredMs));
            ps.setBytes(4, writerKey.toByteArray());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("标记 writer_registry REVOKED 未命中在役行", "45000");
            }
        }
    }

    private SqlWriterLifecycleReceipt readLifecycleReceipt(Connection c, ByteString requestId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision "
                        + "FROM " + table("writer_lifecycle_receipt") + " WHERE request_id=?")) {
            ps.setBytes(1, requestId.toByteArray());
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                return SqlWriterLifecycleReceipt.newBuilder()
                        .setRequestId(ByteString.copyFrom(r.getBytes(1)))
                        .setKindValue(r.getInt(2))
                        .setGeneration(r.getString(3))
                        .setRequestDigest(ByteString.copyFrom(r.getBytes(4)))
                        .setRequestProto(ByteString.copyFrom(r.getBytes(5)))
                        .setPreviousEpoch(u64(r, 6))
                        .setResultingEpoch(u64(r, 7))
                        .setCatalogRevision(u64(r, 8))
                        .build();
            }
        }
    }

    private void insertLifecycleReceipt(Connection c, SqlWriterLifecycleReceipt receipt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + table("writer_lifecycle_receipt")
                        + "(request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision) "
                        + "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setBytes(1, receipt.getRequestId().toByteArray());
            ps.setInt(2, receipt.getKindValue());
            ps.setString(3, receipt.getGeneration());
            ps.setBytes(4, receipt.getRequestDigest().toByteArray());
            ps.setBytes(5, receipt.getRequestProto().toByteArray());
            ps.setObject(6, u64Big(receipt.getPreviousEpoch()));
            ps.setObject(7, u64Big(receipt.getResultingEpoch()));
            ps.setObject(8, u64Big(receipt.getCatalogRevision()));
            if (ps.executeUpdate() != 1) {
                throw new SQLException("写入 writer_lifecycle_receipt 失败", "45000");
            }
        }
    }

    private void validateRegisterRequest(SqlWriterLifecycleReceipt.Request req) {
        if (req.getKind() != SqlWriterLifecycleReceipt.Kind.REGISTER
                || req.getRequestId().size() != 16
                || req.getExpectedEpoch() != 0
                || !req.getSuccessorRequestId().isEmpty()
                || !schema.equals(req.getAuthoritySchema())
                || req.getDeploymentId().isBlank()
                || req.getDeploymentDigest().size() != 32
                || req.getLockQualificationSha256().size() != 32
                || (req.getLockPrimitive() != SqlWriterBootGrant.LockPrimitive.SELECT_ONLY
                && req.getLockPrimitive() != SqlWriterBootGrant.LockPrimitive.LOCK_TOUCH)) {
            throw new IllegalArgumentException("REGISTER 请求形状不合法");
        }
        var w = req.getWriter();
        if (w.getWriterKey().size() != 16 || w.getInstanceId().size() != 16
                || w.getSqlPrincipal().isEmpty() || w.getSqlPrincipal().size() > 288
                || !w.getSqlPrincipal().isValidUtf8()
                || w.getState() != SqlWriterRegistry.State.ACTIVE
                || w.getBootSeq() == 0) {
            throw new IllegalArgumentException("REGISTER writer 身份或 boot_seq 非法");
        }
        UUID.fromString(w.getGeneration());
    }

    private void validateRetireRequest(SqlWriterLifecycleReceipt.Request req) {
        if (req.getKind() != SqlWriterLifecycleReceipt.Kind.RETIRE
                || req.getRequestId().size() != 16
                || req.getExpectedEpoch() == 0
                || req.getExpectedCatalogRevision() == 0
                || !req.getSuccessorRequestId().isEmpty()
                || !req.getMaintenanceRunId().isEmpty()
                || !schema.equals(req.getAuthoritySchema())) {
            throw new IllegalArgumentException("非 run RETIRE 请求形状不合法");
        }
        var w = req.getWriter();
        if (w.getWriterKey().size() != 16 || w.getInstanceId().size() != 16
                || w.getRegistrationDigest().size() != 32
                || w.getSqlPrincipal().isEmpty()) {
            throw new IllegalArgumentException("RETIRE writer 身份缺失");
        }
    }

    public static ByteString registerDigest(SqlWriterLifecycleReceipt.Request request) throws Exception {
        return sha256(canonical(request.toBuilder()
                .setWriter(request.getWriter().toBuilder().clearRegistrationDigest())
                .build()));
    }

    public static ByteString deterministicSuccessionRetireId(ByteString registerRequestId) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update("slot-succession-retire:".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        md.update(registerRequestId.toByteArray());
        byte[] digest = md.digest();
        return ByteString.copyFrom(digest, 0, 16);
    }

    public static byte[] canonical(Message message) throws IOException {
        byte[] bytes = new byte[message.getSerializedSize()];
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.useDeterministicSerialization();
        message.writeTo(out);
        out.checkNoSpaceLeft();
        return bytes;
    }

    public static ByteString sha256(byte[] raw) throws Exception {
        return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(raw));
    }

    public static ByteString randomUuidBytes() {
        UUID uuid = UUID.randomUUID();
        return ByteString.copyFrom(ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array());
    }

    private static BigInteger u64Big(long v) {
        return new BigInteger(Long.toUnsignedString(v));
    }

    private static long u64(ResultSet r, int col) throws SQLException {
        Object o = r.getObject(col);
        if (o instanceof BigInteger b) {
            return b.longValue();
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseUnsignedLong(r.getString(col));
    }
}
