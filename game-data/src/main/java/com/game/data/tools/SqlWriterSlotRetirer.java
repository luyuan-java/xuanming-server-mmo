package com.game.data.tools;

import com.game.player.store.state.SqlWriterLifecycleReceipt;
import com.game.player.store.state.SqlWriterRegistry;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写者缩容收尾器（主仓缺口清单第 22 条残余 11-12 三节：自动结束被缩掉槽位末代、部署当场收尾与金丝雀自删、归属核验）：
 * <ol>
 *   <li>无本地状态，每轮重新从集群与目录对账；工作负载整套删除视为 {@code replicas = 0}；</li>
 *   <li>严格顺序：读期望副本数 → 快照全部 {@code ordinal >= replicas} 槽位在目录里的 ACTIVE 末代 → 再读副本数并核查该序号 Pod 是否仍在 →
 *       仅在确认 Pod 已不存在后才读回 REGISTER 收据核验 {@code registered_by} 集群归属（{@code k8s-sa:<ns>/<sa>/<uid>}）；</li>
 *   <li>非本集群登记（外集群 UID / 异 namespace / 空身份 / 无法解析 / SA 不存在 / API 不可读）记 {@code registeredElsewhere} 并计入 {@code pending}、不动该代且本轮失败；</li>
 *   <li>闸门冻结或排空 run 进行中跳过并计入 {@code pending}；别处先退休不算失败；catalog 版本竞争有界重试；未知结局本轮报错；</li>
 *   <li>汇总报告带 {@code pending}，供部署当场收敛与金丝雀收尾判定是否可删除定时任务。</li>
 * </ol>
 */
public final class SqlWriterSlotRetirer {

    public static final int DEFAULT_MAX_CAS_RETRIES = 3;
    private static final Pattern K8S_SA_PATTERN = Pattern.compile("^k8s-sa:([^/:]+)/([^/:]+)/([^/:]+)$");

    public record K8sSaIdentity(String namespace, String serviceAccount, String uid) {
        public String format() {
            return "k8s-sa:" + namespace + "/" + serviceAccount + "/" + uid;
        }
    }

    /** 解析 {@code k8s-sa:<namespace>/<ServiceAccount>/<UID>}，不合规返回 {@code null}。 */
    public static K8sSaIdentity parseK8sSaIdentity(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Matcher m = K8S_SA_PATTERN.matcher(value);
        if (!m.matches()) {
            return null;
        }
        String ns = m.group(1);
        String sa = m.group(2);
        String uid = m.group(3);
        if (ns.isBlank() || sa.isBlank() || uid.isBlank()) {
            return null;
        }
        return new K8sSaIdentity(ns, sa, uid);
    }

    /** 集群只读查询接口（查询 StatefulSet 副本数、Pod 是否存在、本 namespace ServiceAccount 实时 UID）。 */
    public interface ClusterObserver {
        /** @return 期望副本数；工作负载已整体删除返回 0。 */
        int desiredReplicas(String workloadName) throws IOException;

        /** @return 指定序号的 Pod 对象当前是否仍存在于集群中。 */
        boolean podExists(String workloadName, int ordinal) throws IOException;

        /** @return 本集群本 namespace 下指定 ServiceAccount 的实时 UID，不存在返回 empty。 */
        Optional<String> liveServiceAccountUid(String namespace, String serviceAccountName) throws IOException;
    }

    /** 单轮缩容收尾的汇总报告（{@code pending == 0 && failed == 0} 表示全部已收敛）。 */
    public record RetirementReport(
            int retired,
            int pending,
            int skipped,
            int registeredElsewhere,
            int failed) {

        public boolean allSettled() {
            return pending == 0 && failed == 0;
        }

        /** 金丝雀删除后，只有当场这一轮收尾确认 {@code pending == 0 && failed == 0} 才允许删除其收尾定时任务。 */
        public boolean canDeleteCanaryCronJob() {
            return allSettled();
        }
    }

    private final String ownNamespace;
    private final String authoritySchema;
    private final List<String> protectedSchemas;
    private final ByteString deploymentDigest;
    private final int maxCasRetries;

    public SqlWriterSlotRetirer(String ownNamespace, String authoritySchema, List<String> protectedSchemas,
                                ByteString deploymentDigest) {
        this(ownNamespace, authoritySchema, protectedSchemas, deploymentDigest, DEFAULT_MAX_CAS_RETRIES);
    }

    public SqlWriterSlotRetirer(String ownNamespace, String authoritySchema, List<String> protectedSchemas,
                                ByteString deploymentDigest, int maxCasRetries) {
        if (ownNamespace == null || ownNamespace.isBlank()) {
            throw new IllegalArgumentException("ownNamespace 不能为空");
        }
        this.ownNamespace = ownNamespace;
        this.authoritySchema = Objects.requireNonNull(authoritySchema, "authoritySchema");
        this.protectedSchemas = List.copyOf(protectedSchemas);
        this.deploymentDigest = Objects.requireNonNull(deploymentDigest, "deploymentDigest");
        this.maxCasRetries = Math.max(1, maxCasRetries);
    }

    /** 执行一轮无状态缩容收尾对账。 */
    public RetirementReport reconcile(Connection c, SqlWriterSlotCatalog catalog,
                                      SqlWriterSlotRegistrar.SlotRoster roster,
                                      ClusterObserver cluster, long nowMs) throws Exception {
        int replicasBefore = cluster.desiredReplicas(roster.workloadName());
        if (replicasBefore < 0) {
            throw new IOException("非法期望副本数: " + replicasBefore);
        }

        // 第 1 步：先对被缩掉的槽位（ordinal >= replicasBefore）快照目录里的在役末代
        record Candidate(int ordinal, SqlWriterSlotCatalog.ActiveGenerationSnapshot snapshot) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int ordinal = replicasBefore; ordinal < roster.slotPrincipals().size(); ordinal++) {
            ByteString principal = ByteString.copyFrom(roster.principalForOrdinal(ordinal), StandardCharsets.UTF_8);
            var snap = catalog.snapshotActiveGeneration(c, principal);
            if (snap != null) {
                candidates.add(new Candidate(ordinal, snap));
            }
        }
        if (candidates.isEmpty()) {
            return new RetirementReport(0, 0, 0, 0, 0);
        }

        // 第 2 步：再读期望副本数，防止读目录之后工作负载又扩回来
        int replicasAfter = cluster.desiredReplicas(roster.workloadName());
        int retired = 0;
        int pending = 0;
        int skipped = 0;
        int registeredElsewhere = 0;
        int failed = 0;

        for (Candidate candidate : candidates) {
            if (candidate.ordinal() < replicasAfter) {
                skipped++;
                continue;
            }
            if (cluster.podExists(roster.workloadName(), candidate.ordinal())) {
                // Pod 仍在：尚未真正缩掉，不动，计入 pending（等待 Pod 彻底移除后收尾）
                pending++;
                continue;
            }
            var snap = candidate.snapshot();
            if (!snap.writable() || snap.drainRunActive()) {
                // 闸门冻结或排空 run 开着：跳过，留给下一轮
                skipped++;
                pending++;
                continue;
            }

            // 第 3 步：仅在确认 Pod 已不在、即将退休前核验 REGISTER 收据里的集群归属
            OwnershipCheck ownership = verifyClusterOwnership(c, catalog, snap, cluster);
            if (ownership != OwnershipCheck.OWNED) {
                if (ownership == OwnershipCheck.FOREIGN) {
                    registeredElsewhere++;
                }
                pending++;
                failed++;
                continue;
            }

            // 第 4 步：按快照精确身份 + boot_seq 退休（有界重试 catalog_revision 竞争）
            RetireOutcome outcome = retireWithCasRetry(c, catalog, snap, roster.workloadName(), candidate.ordinal(), nowMs);
            switch (outcome) {
                case RETIRED -> retired++;
                case ALREADY_RETIRED -> skipped++;
                case FROZEN_SKIPPED -> { skipped++; pending++; }
                case FAILED -> { pending++; failed++; }
            }
        }
        return new RetirementReport(retired, pending, skipped, registeredElsewhere, failed);
    }

    private enum OwnershipCheck { OWNED, FOREIGN, UNREADABLE }

    private OwnershipCheck verifyClusterOwnership(Connection c, SqlWriterSlotCatalog catalog,
                                                  SqlWriterSlotCatalog.ActiveGenerationSnapshot snap,
                                                  ClusterObserver cluster) {
        final SqlWriterLifecycleReceipt.Request regReq;
        try {
            regReq = catalog.readRegisterRequest(c, snap.registrationDigest());
        } catch (Exception e) {
            return OwnershipCheck.UNREADABLE;
        }
        if (regReq == null) {
            return OwnershipCheck.FOREIGN;
        }
        K8sSaIdentity parsed = parseK8sSaIdentity(regReq.getRegisteredBy());
        if (parsed == null || !ownNamespace.equals(parsed.namespace())) {
            return OwnershipCheck.FOREIGN;
        }
        final Optional<String> liveUid;
        try {
            liveUid = cluster.liveServiceAccountUid(ownNamespace, parsed.serviceAccount());
        } catch (IOException e) {
            return OwnershipCheck.UNREADABLE;
        }
        if (liveUid.isEmpty() || !parsed.uid().equals(liveUid.get())) {
            return OwnershipCheck.FOREIGN;
        }
        return OwnershipCheck.OWNED;
    }

    private enum RetireOutcome { RETIRED, ALREADY_RETIRED, FROZEN_SKIPPED, FAILED }

    private RetireOutcome retireWithCasRetry(Connection c, SqlWriterSlotCatalog catalog,
                                             SqlWriterSlotCatalog.ActiveGenerationSnapshot initialSnap,
                                             String workload, int ordinal, long nowMs) {
        SqlWriterSlotCatalog.ActiveGenerationSnapshot current = initialSnap;
        for (int attempt = 0; attempt < maxCasRetries; attempt++) {
            SqlWriterLifecycleReceipt.Request req = SqlWriterLifecycleReceipt.Request.newBuilder()
                    .setRequestId(SqlWriterSlotCatalog.randomUuidBytes())
                    .setKind(SqlWriterLifecycleReceipt.Kind.RETIRE)
                    .setWriter(SqlWriterRegistry.newBuilder()
                            .setWriterKey(initialSnap.writerKey())
                            .setGeneration(initialSnap.generation())
                            .setInstanceId(initialSnap.instanceId())
                            .setSqlPrincipal(initialSnap.sqlPrincipal())
                            .setRegistrationDigest(initialSnap.registrationDigest())
                            .setState(SqlWriterRegistry.State.ACTIVE)
                            .setBootSeq(initialSnap.bootSeq())
                            .build())
                    .setExpectedEpoch(current.epoch())
                    .setExpectedCatalogRevision(current.catalogRevision())
                    .setAuthoritySchema(authoritySchema)
                    .addAllProtectedSchemas(protectedSchemas)
                    .setDeploymentDigest(deploymentDigest)
                    .setDeploymentId("retirer-" + workload + "-" + ordinal)
                    .build();
            try {
                catalog.retireGeneration(c, req, nowMs);
                return RetireOutcome.RETIRED;
            } catch (SqlWriterSlotCatalog.GenerationAlreadyEndedException ended) {
                return RetireOutcome.ALREADY_RETIRED;
            } catch (SqlWriterSlotCatalog.FrozenRetryableException frozen) {
                return RetireOutcome.FROZEN_SKIPPED;
            } catch (SqlWriterSlotCatalog.CatalogRevisionConflictException conflict) {
                try {
                    var refreshed = catalog.snapshotActiveGeneration(c, initialSnap.sqlPrincipal());
                    if (refreshed == null || !refreshed.writerKey().equals(initialSnap.writerKey())
                            || refreshed.bootSeq() != initialSnap.bootSeq()) {
                        return RetireOutcome.ALREADY_RETIRED;
                    }
                    if (!refreshed.writable() || refreshed.drainRunActive()) {
                        return RetireOutcome.FROZEN_SKIPPED;
                    }
                    current = refreshed;
                } catch (Exception rereadFailed) {
                    return RetireOutcome.FAILED;
                }
            } catch (Exception unknownOrFailed) {
                return RetireOutcome.FAILED;
            }
        }
        return RetireOutcome.FAILED;
    }
}
