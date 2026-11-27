package com.game.common.id;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 永久业务 ID 全局号源登记处（对齐主仓 {@code docs/design/player-data-tidb-scaling-plan.md} §6.3）：
 * <ul>
 *   <li>将 6 位地域码（{@code 0..63}，0 仅限 dev/test）唯一绑定到一个部署环境身份（{@code deploymentId}）与一对本地预留存储
 *       （{@code primaryStoreId}, {@code secondaryStoreId}）；</li>
 *   <li>首次初始化（{@link #initialize}）与事故恢复/换绑（{@link #recoverAndRebind}）严格分离：已占用或已退役的码拒绝重新初始化；</li>
 *   <li>克隆快照或另一集群复配同一地域码时因 {@code deploymentId} / 本地存储对不匹配而立即拒发；</li>
 *   <li>本身作为第三份独立预留记录（实现 {@link IdReservationStore}），任一份或两份本地记录丢失/回档均由登记处兜住水位。</li>
 * </ul>
 */
public interface GlobalRegionRegistry extends IdReservationStore {

    int MIN_REGION_CODE = 0;
    int MAX_REGION_CODE = 63;

    record RegionBinding(int regionCode,
                         String deploymentId,
                         String primaryStoreId,
                         String secondaryStoreId,
                         long watermark,
                         boolean retired) {
    }

    /** 查询指定地域码的权威绑定；未登记返回 empty。 */
    Optional<RegionBinding> findBinding(int regionCode);

    /** 首次初始化绑定一个未占用、未退役的地域码（与事故恢复严格分离）。 */
    RegionBinding initialize(int regionCode, String deploymentId, String primaryStoreId, String secondaryStoreId, long initialFloor);

    /** 事故恢复或灾备换绑：显式抬升水位至 {@code Math.max(oldWatermark, recoveryFloor)} 并更新受信任环境/存储对，旧集群随后自动失效。 */
    RegionBinding recoverAndRebind(int regionCode, String newDeploymentId, String newPrimaryStoreId, String newSecondaryStoreId, long recoveryFloor);

    /** 退役指定地域码（永久留墓碑，禁止复用）；除显式 {@code allowUnbound} 外须校验当前调用方环境与绑定一致。 */
    RegionBinding retire(int regionCode, String callerDeploymentId, boolean allowUnbound);

    /** 校验给定调用方环境及本地存储对是否对本地域码拥有合法未退役的独占绑定。 */
    default RegionBinding verifyBinding(int regionCode, String deploymentId, String primaryStoreId, String secondaryStoreId) {
        RegionBinding binding = findBinding(regionCode)
                .orElseThrow(() -> new IllegalStateException("全局号源登记处未初始化该地域码 region=" + regionCode));
        if (binding.retired()) {
            throw new IllegalStateException("地域码已退役禁止发号或复用 region=" + regionCode);
        }
        if (!Objects.equals(binding.deploymentId(), deploymentId)
                || !Objects.equals(binding.primaryStoreId(), primaryStoreId)
                || !Objects.equals(binding.secondaryStoreId(), secondaryStoreId)) {
            throw new IllegalStateException("地域码绑定身份或本地预留存储不匹配（疑似克隆环境或跨集群冲突）region=" + regionCode);
        }
        return binding;
    }

    static int validateRegionCode(int regionCode, boolean prodMode) {
        if (regionCode < MIN_REGION_CODE || regionCode > MAX_REGION_CODE) {
            throw new IllegalArgumentException("地域码越界 [0, 63]: " + regionCode);
        }
        if (prodMode && regionCode == 0) {
            throw new IllegalArgumentException("生产环境禁止使用地域码 0（仅限 dev/test）");
        }
        return regionCode;
    }

    /** 内存线程安全参考实现（支持单测故障注入与进程内集成验证）。 */
    final class InMemory implements GlobalRegionRegistry {

        private final String id;
        private final Map<Integer, RegionBinding> bindings = new ConcurrentHashMap<>();
        private volatile boolean failWrites;
        private volatile boolean failReads;

        public InMemory(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public void setFailWrites(boolean fail) {
            this.failWrites = fail;
        }

        public void setFailReads(boolean fail) {
            this.failReads = fail;
        }

        /** 测试故障注入：模拟登记处意外回档到旧水位。 */
        public synchronized void simulateRollback(int regionCode, long rolledBackWatermark) {
            RegionBinding cur = bindings.get(regionCode);
            if (cur != null) {
                bindings.put(regionCode, new RegionBinding(cur.regionCode(), cur.deploymentId(),
                        cur.primaryStoreId(), cur.secondaryStoreId(), rolledBackWatermark, cur.retired()));
            }
        }

        @Override
        public String storeId() {
            return id;
        }

        @Override
        public synchronized Optional<RegionBinding> findBinding(int regionCode) {
            if (failReads) {
                throw new IllegalStateException("全局号源登记处不可达");
            }
            return Optional.ofNullable(bindings.get(regionCode));
        }

        @Override
        public synchronized RegionBinding initialize(int regionCode, String deploymentId,
                                                     String primaryStoreId, String secondaryStoreId, long initialFloor) {
            validateRegionCode(regionCode, false);
            requireNonBlank(deploymentId, "deploymentId");
            requireNonBlank(primaryStoreId, "primaryStoreId");
            requireNonBlank(secondaryStoreId, "secondaryStoreId");
            if (primaryStoreId.equals(secondaryStoreId)) {
                throw new IllegalArgumentException("主副预留记录必须落在两套独立存储上");
            }
            if (initialFloor < 0) {
                throw new IllegalArgumentException("初始下界不能为负: " + initialFloor);
            }
            if (failWrites) {
                throw new IllegalStateException("全局号源登记处写入失败");
            }
            RegionBinding existing = bindings.get(regionCode);
            if (existing != null) {
                throw new IllegalStateException(existing.retired()
                        ? "地域码已退役，禁止重新初始化 region=" + regionCode
                        : "地域码已初始化，禁止重复初始化（事故恢复请走 recoverAndRebind）region=" + regionCode);
            }
            RegionBinding created = new RegionBinding(regionCode, deploymentId, primaryStoreId, secondaryStoreId, initialFloor, false);
            bindings.put(regionCode, created);
            return created;
        }

        @Override
        public synchronized RegionBinding recoverAndRebind(int regionCode, String newDeploymentId,
                                                           String newPrimaryStoreId, String newSecondaryStoreId, long recoveryFloor) {
            validateRegionCode(regionCode, false);
            requireNonBlank(newDeploymentId, "newDeploymentId");
            requireNonBlank(newPrimaryStoreId, "newPrimaryStoreId");
            requireNonBlank(newSecondaryStoreId, "newSecondaryStoreId");
            if (newPrimaryStoreId.equals(newSecondaryStoreId)) {
                throw new IllegalArgumentException("主副预留记录必须落在两套独立存储上");
            }
            if (recoveryFloor < 0) {
                throw new IllegalArgumentException("恢复水位不能为负: " + recoveryFloor);
            }
            if (failWrites) {
                throw new IllegalStateException("全局号源登记处写入失败");
            }
            RegionBinding existing = bindings.get(regionCode);
            if (existing != null && existing.retired()) {
                throw new IllegalStateException("已退役地域码禁止恢复复用 region=" + regionCode);
            }
            long floor = existing == null ? recoveryFloor : Math.max(existing.watermark(), recoveryFloor);
            RegionBinding updated = new RegionBinding(regionCode, newDeploymentId, newPrimaryStoreId, newSecondaryStoreId, floor, false);
            bindings.put(regionCode, updated);
            return updated;
        }

        @Override
        public synchronized RegionBinding retire(int regionCode, String callerDeploymentId, boolean allowUnbound) {
            if (failWrites) {
                throw new IllegalStateException("全局号源登记处写入失败");
            }
            RegionBinding existing = bindings.get(regionCode);
            if (existing == null) {
                throw new IllegalStateException("地域码未登记 region=" + regionCode);
            }
            if (!allowUnbound && !Objects.equals(existing.deploymentId(), callerDeploymentId)) {
                throw new IllegalStateException("非绑定部署环境禁止退役在用地域码 region=" + regionCode);
            }
            RegionBinding retired = new RegionBinding(existing.regionCode(), existing.deploymentId(),
                    existing.primaryStoreId(), existing.secondaryStoreId(), existing.watermark(), true);
            bindings.put(regionCode, retired);
            return retired;
        }

        @Override
        public synchronized OptionalLong readWatermark(int regionCode) {
            if (failReads) {
                throw new IllegalStateException("全局号源登记处不可达");
            }
            RegionBinding b = bindings.get(regionCode);
            return b == null ? OptionalLong.empty() : OptionalLong.of(b.watermark());
        }

        @Override
        public synchronized long raiseWatermark(int regionCode, long newUpperBound) {
            if (failWrites) {
                throw new IllegalStateException("全局号源登记处写入失败");
            }
            RegionBinding b = bindings.get(regionCode);
            if (b == null || b.retired()) {
                throw new IllegalStateException("地域码未登记或已退役，拒绝抬升水位 region=" + regionCode);
            }
            long raised = Math.max(b.watermark(), newUpperBound);
            bindings.put(regionCode, new RegionBinding(b.regionCode(), b.deploymentId(),
                    b.primaryStoreId(), b.secondaryStoreId(), raised, false));
            return raised;
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " 不能为空");
            }
        }
    }
}
