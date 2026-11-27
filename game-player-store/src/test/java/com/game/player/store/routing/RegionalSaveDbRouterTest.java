package com.game.player.store.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RegionalSaveDbRouterTest {

    @Test
    void enforcesRegionalNamingManifestLazyOpenAndPerPoolReadQuota() {
        List<String> manifest = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            manifest.add(RegionalSaveDbRouter.dbNameFor(RegionalSaveDbRouter.storeIdFor(2, i)));
        }
        assertThat(manifest).containsExactly(
                "player_store_1002000_db", "player_store_1002001_db",
                "player_store_1002002_db", "player_store_1002003_db");

        // 拒绝旧按区编号与 zone_<n>_db
        assertThatThrownBy(() -> RegionalSaveDbRouter.dbNameFor(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionalSaveDbRouter.dbNameFor(999_999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionalSaveDbRouter.validateManifest(2, 4,
                List.of("zone_1_db", "player_store_1002001_db", "player_store_1002002_db", "player_store_1002003_db")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionalSaveDbRouter.validateManifest(2, 4, manifest.subList(0, 3)))
                .isInstanceOf(IllegalArgumentException.class);

        // 启动时打开数为 0，首次路由指向才懒打开且单飞
        AtomicInteger opens = new AtomicInteger();
        var router = new RegionalSaveDbRouter<>(2, 4, 24, manifest, db -> {
            opens.incrementAndGet();
            return "ds:" + db;
        });
        assertThat(router.openedCount()).isZero();
        assertThat(opens.get()).isZero();
        assertThat(router.poolSizePerDb()).isEqualTo(6);
        // 预算很小时保底每库 2 条连接
        assertThat(RegionalSaveDbRouter.poolSizePerDb(3, 64)).isEqualTo(2);

        // 无符号 player_id % K 选库
        int storeA = router.assignStoreForNewPlayer(100L);
        int storeB = router.assignStoreForNewPlayer(101L);
        assertThat(storeA).isEqualTo(1_002_000);
        assertThat(storeB).isEqualTo(1_002_001);

        var handleA = router.openLazy(storeA);
        var handleA2 = router.openLazy(storeA);
        assertThat(handleA2).isSameAs(handleA);
        assertThat(opens.get()).isEqualTo(1);
        assertThat(router.openedCount()).isEqualTo(1);

        // 每库连接池独立读配额（池容量 6 -> 3），耗尽池 A 不影响池 B
        var handleB = router.openLazy(storeB);
        assertThat(handleA.readQuota().maxPermits()).isEqualTo(3);
        assertThat(handleA.readQuota().tryAcquire()).isTrue();
        assertThat(handleA.readQuota().tryAcquire()).isTrue();
        assertThat(handleA.readQuota().tryAcquire()).isTrue();
        assertThat(handleA.readQuota().tryAcquire()).isFalse();
        assertThat(handleB.readQuota().tryAcquire()).isTrue();
        handleA.readQuota().release();
        handleB.readQuota().release();
    }
}
