package com.game.data.tools;

import com.game.player.store.state.SqlWriterBootGrant;
import com.game.player.store.state.SqlWriterLifecycleReceipt;
import com.game.player.store.state.SqlWriterRegistry;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写者槽位注册器（主仓缺口清单第 22 条 + 11-12 归属补齐）：
 * <ul>
 *   <li>名册仅含槽位模板（槽位号 → SQL 账号），不预写 request_id / writer_key / instance_id / deployment_id / boot_seq；</li>
 *   <li>按 StatefulSet Pod 名称末尾序号选槽位，每次启动现场生成新身份，连续重启超过旧 1024 上限永不耗尽；</li>
 *   <li>首次注册前把本代完整请求落盘到 Pod 私有启动记录，同一 Pod 内 init 重跑时直接重放记录、不重新生成；</li>
 *   <li>在 K8s 环境下通过 {@link ClusterIdentityProvider} 现场取得 {@code k8s-sa:<ns>/<sa>/<uid>} 写入 {@code registered_by}，读不到直接拒绝注册（fail-closed）；集群外允许空串。</li>
 * </ul>
 */
public final class SqlWriterSlotRegistrar {

    public static final int MAX_SLOTS = 1024;
    private static final Pattern POD_ORDINAL = Pattern.compile("^.+-([0-9]+)$");

    /** 单个工作负载的槽位名册模板（不包含任何逐代一次性字段）。 */
    public record SlotRoster(String workloadName, List<String> slotPrincipals) {
        public SlotRoster {
            if (workloadName == null || workloadName.isBlank()) {
                throw new IllegalArgumentException("workloadName 不能为空");
            }
            if (slotPrincipals == null || slotPrincipals.isEmpty() || slotPrincipals.size() > MAX_SLOTS) {
                throw new IllegalArgumentException("槽位数必须在 1.." + MAX_SLOTS + " 之间");
            }
            slotPrincipals = List.copyOf(slotPrincipals);
        }

        public String principalForOrdinal(int ordinal) {
            if (ordinal < 0 || ordinal >= slotPrincipals.size()) {
                throw new IllegalArgumentException("Pod 序号 " + ordinal + " 超出名册槽位范围 0.." + (slotPrincipals.size() - 1));
            }
            return slotPrincipals.get(ordinal);
        }
    }

    /** 集群注册者身份提供者（K8s 环境中读取本进程 ServiceAccount UID 并返回 {@code k8s-sa:<ns>/<sa>/<uid>}）。 */
    @FunctionalInterface
    public interface ClusterIdentityProvider {
        /** @return K8s 环境返回 {@code k8s-sa:<ns>/<sa>/<uid>}，集群外返回空串 {@code ""}；读取失败必须抛异常。 */
        String resolveRegisteredBy() throws IOException;
    }

    /** 跨全部名册与副本数做部署前静态校验（槽位数 >= 副本数、<= 1024、所有名册账号全局唯一）。 */
    public static void validateRosters(List<SlotRoster> rosters, java.util.Map<String, Integer> replicasByWorkload) {
        Set<String> seenPrincipals = new HashSet<>();
        for (SlotRoster roster : rosters) {
            int replicas = replicasByWorkload.getOrDefault(roster.workloadName(), 0);
            if (replicas < 0 || roster.slotPrincipals().size() < replicas) {
                throw new IllegalArgumentException("工作负载 " + roster.workloadName() + " 槽位数小于期望副本数 " + replicas);
            }
            for (String principal : roster.slotPrincipals()) {
                if (principal == null || principal.isBlank()) {
                    throw new IllegalArgumentException("名册存在空 SQL 账号");
                }
                if (!seenPrincipals.add(principal)) {
                    throw new IllegalArgumentException("名册之间存在重复 SQL 账号: " + principal);
                }
            }
        }
    }

    /** 从 StatefulSet Pod 名解析末尾槽位序号（如 {@code game-match-3} → 3）。 */
    public static int parsePodOrdinal(String podName) {
        if (podName == null) {
            throw new IllegalArgumentException("Pod 名不能为空");
        }
        Matcher m = POD_ORDINAL.matcher(podName);
        if (!m.matches()) {
            throw new IllegalArgumentException("不是合法的 StatefulSet Pod 名（缺末尾序号）: " + podName);
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * 为当前 Pod 的槽位生成或重放本代注册请求并提交至逻辑库目录。
     * 若 {@code bootRecordPath} 已存在，直接重放原完整请求（init 容器重启幂等）；否则先查归属、生成并落盘记录再注册。
     */
    public SqlWriterSlotCatalog.RegistrationResult registerPodBoot(
            Connection c,
            SqlWriterSlotCatalog catalog,
            SlotRoster roster,
            String podName,
            String deploymentId,
            String generation,
            String authoritySchema,
            List<String> protectedSchemas,
            ByteString deploymentDigest,
            SqlWriterBootGrant.LockPrimitive lockPrimitive,
            ByteString lockQualificationSha256,
            Path bootRecordPath,
            boolean inKubernetes,
            ClusterIdentityProvider identityProvider,
            long nowMs) throws Exception {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(bootRecordPath, "bootRecordPath");
        SqlWriterLifecycleReceipt.Request request;
        if (Files.exists(bootRecordPath)) {
            request = SqlWriterLifecycleReceipt.Request.parseFrom(Files.readAllBytes(bootRecordPath));
        } else {
            int ordinal = parsePodOrdinal(podName);
            String principal = roster.principalForOrdinal(ordinal);
            String registeredBy = "";
            if (inKubernetes) {
                if (identityProvider == null) {
                    throw new IOException("K8s 环境中缺少集群身份提供者，拒绝注册");
                }
                registeredBy = identityProvider.resolveRegisteredBy();
                if (SqlWriterSlotRetirer.parseK8sSaIdentity(registeredBy) == null) {
                    throw new IOException("K8s 环境中未能取得合法 k8s-sa 归属身份，拒绝注册: " + registeredBy);
                }
            }
            ByteString principalBytes = ByteString.copyFrom(principal, StandardCharsets.UTF_8);
            long nextBootSeq = catalog.maxBootSeq(c, principalBytes) + 1L;
            request = SqlWriterLifecycleReceipt.Request.newBuilder()
                    .setRequestId(SqlWriterSlotCatalog.randomUuidBytes())
                    .setKind(SqlWriterLifecycleReceipt.Kind.REGISTER)
                    .setWriter(SqlWriterRegistry.newBuilder()
                            .setWriterKey(SqlWriterSlotCatalog.randomUuidBytes())
                            .setGeneration(generation)
                            .setInstanceId(SqlWriterSlotCatalog.randomUuidBytes())
                            .setSqlPrincipal(principalBytes)
                            .setState(SqlWriterRegistry.State.ACTIVE)
                            .setBootSeq(nextBootSeq)
                            .build())
                    .setExpectedEpoch(0)
                    .setAuthoritySchema(authoritySchema)
                    .addAllProtectedSchemas(protectedSchemas)
                    .setDeploymentDigest(deploymentDigest)
                    .setDeploymentId(deploymentId)
                    .setLockPrimitive(lockPrimitive)
                    .setLockQualificationSha256(lockQualificationSha256)
                    .setRegisteredBy(registeredBy)
                    .build();
            if (bootRecordPath.getParent() != null) {
                Files.createDirectories(bootRecordPath.getParent());
            }
            Files.write(bootRecordPath, SqlWriterSlotCatalog.canonical(request));
        }
        return catalog.registerSlotGeneration(c, request, nowMs);
    }
}
