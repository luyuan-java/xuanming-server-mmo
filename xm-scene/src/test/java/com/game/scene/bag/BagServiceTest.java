package com.game.scene.bag;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.audit.GainAnomalyDetector.Threshold;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingAssetAudit.Item;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 背包服务：闸（全服禁发回 1005）、资产流水（入包 / 淘汰）、获取异常检测。配表用测试专用的小表。 */
class BagServiceTest {

    private static final long PLAYER = 1001;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final AtomicLong guidSeq = new AtomicLong(5000);
    private final BagTables tables = new BagTables(Map.of(
            1, new com.game.scene.player.ItemCatalog.ItemSpec(1, 1, 1),
            9, new com.game.scene.player.ItemCatalog.ItemSpec(9, 2, 0),
            10, new com.game.scene.player.ItemCatalog.ItemSpec(10, 999, 0)),
            Map.of(1, List.of(0, 1)));
    private final SceneMetrics metrics = new SceneMetrics(meters);
    private final BagService service = new BagService(tables, count -> {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = guidSeq.incrementAndGet();
        }
        return out;
    }, audit, new GainAnomalyDetector(new Threshold(Duration.ofSeconds(60), 0, 100), Map.of(), Map.of(),
            new ManualClock(), metrics), metrics);
    private final ScenePlayer player = WorldTestAccess.player(PLAYER);

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void 入包每个配置一条流水_记写到的第一个实例_量是整批请求量_带关联号与附加信息() {
        Bag.AddResult first = service.addItems(player, BagType.INVENTORY, Map.of(10, 5L), Reason.SYSTEM_GRANT, 0, "");
        long stack = first.written().get(0).guids().get(0);
        audit.items.clear();

        Bag.AddResult result = service.addItems(player, BagType.INVENTORY, Map.of(10, 1000L, 1, 2L),
                Reason.SYSTEM_GRANT, 42, "{\"source\":\"test\"}");

        assertThat(result.ok()).isTrue();
        assertThat(audit.items).containsExactly(
                new Item(true, PLAYER, result.written().get(0).guids().get(0), 1, 2, Reason.SYSTEM_GRANT, 42,
                        "{\"source\":\"test\"}"),
                new Item(true, PLAYER, stack, 10, 1000, Reason.SYSTEM_GRANT, 42, "{\"source\":\"test\"}"));
    }

    @Test
    void 全服禁发的物品回1005_零写入不记流水_计封禁拒绝_先于数量校验() {
        service.applyGlobalBlocks(new GlobalGainBlocks(Set.of(), Set.of(10)));

        assertThat(service.addItems(player, BagType.INVENTORY, Map.of(1, 1L, 10, 1L), Reason.SYSTEM_GRANT, 0, "").tip())
                .isEqualTo(1005);
        assertThat(service.addItems(player, BagType.INVENTORY, Map.of(10, 0L), Reason.SYSTEM_GRANT, 0, "").tip())
                .isEqualTo(1005);
        assertThat(player.bags().bag(BagType.INVENTORY).itemCount()).isZero();
        assertThat(audit.items).isEmpty();
        assertThat(counter("xm.scene.gain.blocked", "category", "item")).isEqualTo(2);

        service.applyGlobalBlocks(new GlobalGainBlocks(Set.of(10), Set.of()));
        assertThat(service.addItems(player, BagType.INVENTORY, Map.of(10, 1L), Reason.SYSTEM_GRANT, 0, "").ok())
                .as("币种 10 被封不影响物品 10").isTrue();
    }

    @Test
    void 临时格淘汰的实例各记一条销毁流水_数量是真实堆叠数_不带关联号() {
        Bag temporary = player.bags().bag(BagType.TEMPORARY);
        for (int i = 0; i < temporary.capacity(); i++) {
            service.addItems(player, BagType.TEMPORARY, Map.of(1, 1L), Reason.SYSTEM_GRANT, 0, "");
        }
        long oldest = temporary.items().get(0).guid();
        audit.items.clear();

        Bag.AddResult result = service.addItems(player, BagType.TEMPORARY, Map.of(1, 1L), Reason.SYSTEM_GRANT, 9, "x");

        assertThat(result.evicted()).extracting(Bag.Removed::guid).containsExactly(oldest);
        assertThat(audit.items.get(0)).isEqualTo(new Item(false, PLAYER, oldest, 1, 1, Reason.ITEM_DESTROY, 0, ""));
        assertThat(audit.items.get(1).gained()).isTrue();
    }

    @Test
    void 入包成功才做异常检测_按配置计() {
        service.addItems(player, BagType.INVENTORY, Map.of(10, 100L), Reason.SYSTEM_GRANT, 0, "");
        assertThat(counter("xm.scene.gain.anomalies", "category", "item")).isZero();
        service.addItems(player, BagType.INVENTORY, Map.of(10, 1L), Reason.SYSTEM_GRANT, 0, "");
        assertThat(counter("xm.scene.gain.anomalies", "category", "item")).isEqualTo(1);

        service.applyGlobalBlocks(new GlobalGainBlocks(Set.of(), Set.of(9)));
        service.addItems(player, BagType.INVENTORY, Map.of(9, 1000L), Reason.SYSTEM_GRANT, 0, "");
        assertThat(player.gainWindows().item(9).count()).as("被拒的不进窗口").isZero();
    }

    @Test
    void 整理已经最优时不记流水() {
        service.addItems(player, BagType.INVENTORY, Map.of(1, 1L), Reason.SYSTEM_GRANT, 0, "");
        audit.items.clear();
        Bag.SortResult result = service.sortByPlayer(player, BagType.INVENTORY);
        assertThat(result.changed()).isFalse();
        assertThat(audit.items).isEmpty();
    }
}
