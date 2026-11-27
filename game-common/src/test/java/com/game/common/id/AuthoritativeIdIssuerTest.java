package com.game.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthoritativeIdIssuerTest {

    @Test
    void threeRecordReservationPreventsDuplicatesAcrossRollbacksAndRestarts() {
        var primary = new AuthoritativeIdIssuer.InMemoryReservationStore("etcd-a");
        var secondary = new AuthoritativeIdIssuer.InMemoryReservationStore("etcd-b");
        var registry = new GlobalRegionRegistry.InMemory("global-registry");
        registry.initialize(7, "k8s-prod-cn1", "etcd-a", "etcd-b", 0L);

        var issuer1 = new AuthoritativeIdIssuer(7, true, "k8s-prod-cn1", primary, secondary, registry, 100L, () -> 1_000_000L);
        Set<Long> issued = new HashSet<>();
        for (int i = 0; i < 150; i++) {
            long id = issuer1.nextId();
            assertThat(AuthoritativeIdIssuer.regionOf(id)).isEqualTo(7);
            assertThat(AuthoritativeIdIssuer.counterOf(id)).isEqualTo(i);
            assertThat(issued.add(id)).isTrue();
        }
        assertThat(issuer1.confirmedWatermark()).isEqualTo(200L);

        // 故障 1：两套本地 etcd 同时回档到旧快照 50，登记处仍在 200 -> 重启的新进程自动从 200 之上续发且记 recordsBehindRegistry
        primary.simulateRollback(7, 50L);
        secondary.simulateRollback(7, 50L);
        var issuer2 = new AuthoritativeIdIssuer(7, true, "k8s-prod-cn1", primary, secondary, registry, 100L, () -> 1_000_000L);
        long afterLocalRollback = issuer2.nextId();
        assertThat(AuthoritativeIdIssuer.counterOf(afterLocalRollback)).isEqualTo(200L);
        assertThat(issued.add(afterLocalRollback)).isTrue();
        assertThat(issuer2.recordsBehindRegistryCount()).isGreaterThanOrEqualTo(1L);
        assertThat(primary.readWatermark(7)).hasValue(300L);
        assertThat(secondary.readWatermark(7)).hasValue(300L);

        // 故障 2：三份记录同时被回档，但存活进程记得 confirmedWatermark=300 -> 下一块仍从 300 之上起并把三份抬回 400
        for (int i = 1; i < 100; i++) {
            assertThat(issued.add(issuer2.nextId())).isTrue();
        }
        primary.simulateRollback(7, 10L);
        secondary.simulateRollback(7, 10L);
        registry.simulateRollback(7, 10L);
        long afterTripleRollback = issuer2.nextId();
        assertThat(AuthoritativeIdIssuer.counterOf(afterTripleRollback)).isEqualTo(300L);
        assertThat(issued.add(afterTripleRollback)).isTrue();
        assertThat(primary.readWatermark(7)).hasValue(400L);
        assertThat(secondary.readWatermark(7)).hasValue(400L);
        assertThat(registry.readWatermark(7)).hasValue(400L);
    }

    @Test
    void failsClosedWhenAnyRecordWriteFailsOrCloneOrRetiredOrProdRegionZero() {
        var primary = new AuthoritativeIdIssuer.InMemoryReservationStore("etcd-a");
        var secondary = new AuthoritativeIdIssuer.InMemoryReservationStore("etcd-b");
        var registry = new GlobalRegionRegistry.InMemory("global-registry");
        registry.initialize(3, "cluster-orig", "etcd-a", "etcd-b", 1000L);

        // 生产环境禁止地域码 0
        assertThatThrownBy(() -> new AuthoritativeIdIssuer(0, true, "cluster-orig", primary, secondary, registry))
                .isInstanceOf(IllegalArgumentException.class);

        // 重复初始化被拒（须与恢复分离）
        assertThatThrownBy(() -> registry.initialize(3, "cluster-orig", "etcd-a", "etcd-b", 1000L))
                .isInstanceOf(IllegalStateException.class);

        // 克隆环境 / 另一集群配同一个地域码被拒
        var cloneIssuer = new AuthoritativeIdIssuer(3, true, "cluster-clone", primary, secondary, registry, 50L, () -> 1_000L);
        assertThatThrownBy(cloneIssuer::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不匹配");

        // 副记录写入失败：绝不推进内存段、绝不发号
        var issuer = new AuthoritativeIdIssuer(3, true, "cluster-orig", primary, secondary, registry, 50L, () -> 1_000L);
        secondary.setFailWrites(true);
        assertThatThrownBy(issuer::nextId).isInstanceOf(IllegalStateException.class);
        assertThat(issuer.confirmedWatermark()).isZero();
        secondary.setFailWrites(false);
        assertThat(AuthoritativeIdIssuer.counterOf(issuer.nextId())).isGreaterThanOrEqualTo(1050L);

        // 灾备换绑后原集群被拒，新集群无缝从更高水位继续
        registry.recoverAndRebind(3, "cluster-dr", "etcd-dr-a", "etcd-dr-b", 5000L);
        var drPrimary = new AuthoritativeIdIssuer.InMemoryReservationStore("etcd-dr-a");
        var drSecondary = new AuthoritativeIdIssuer.InMemoryReservationStore("etcd-dr-b");
        var drIssuer = new AuthoritativeIdIssuer(3, true, "cluster-dr", drPrimary, drSecondary, registry, 50L, () -> 1_000L);
        assertThat(AuthoritativeIdIssuer.counterOf(drIssuer.nextId())).isEqualTo(5000L);

        // 退役校验归属且永久禁止复用
        assertThatThrownBy(() -> registry.retire(3, "cluster-orig", false))
                .isInstanceOf(IllegalStateException.class);
        registry.retire(3, "cluster-dr", false);
        assertThatThrownBy(() -> registry.initialize(3, "cluster-new", "etcd-x", "etcd-y", 0L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scramble58IsBijective() {
        Set<Long> seen = new HashSet<>();
        for (long c = 0; c < 10_000L; c++) {
            long s = AuthoritativeIdIssuer.scramble58(c);
            assertThat(seen.add(s)).isTrue();
            assertThat(AuthoritativeIdIssuer.unscramble58(s)).isEqualTo(c);
        }
    }
}
