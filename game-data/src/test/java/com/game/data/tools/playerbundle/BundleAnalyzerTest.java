package com.game.data.tools.playerbundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.proto.PlayerLocation;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 判读（{@link BundleAnalyzer}）：归属三列 + {@code saved_epoch} → 在线与否、最近一次存盘是否落库；位置记录、战斗锁、待结算奖励与
 * 晚于存盘时刻的流水给出警告。
 */
class BundleAnalyzerTest {

    private static final long NOW = 1_000_000L;

    private static PlayerBundle.Player player(long epoch, int released, long leaseUntil) {
        return new PlayerBundle.Player("42", "acc", 3, "Alice", 1, 1, "", 10, 1001, 0, 0, 0, Long.toUnsignedString(epoch), released,
                leaseUntil, 1, 2);
    }

    private static PlayerBundle.State state(long savedEpoch, long updatedAt) {
        return new PlayerBundle.State(true, Long.toUnsignedString(savedEpoch), updatedAt, 3, "x", "AAAA", null);
    }

    private static PlayerBundle.Analysis analyze(PlayerBundle.Player p, PlayerBundle.State s, PlayerBundle.TxlogCounts counts,
                                                 PlayerRedis.RawEntry location, PlayerRedis.RawEntry lock, PlayerRedis.RawEntry settle) {
        return BundleAnalyzer.analyze(p, s, counts, location, lock, settle, NOW, List.of());
    }

    private static PlayerRedis.RawEntry absent(String key) {
        return new PlayerRedis.RawEntry(key, "none", -2, null, null, null, null);
    }

    private static PlayerRedis.RawEntry hash(String key, Map<String, byte[]> fields) {
        return new PlayerRedis.RawEntry(key, "hash", 30_000, null, fields, null, null);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 已释放且本代写回过_最终写回已落库() {
        PlayerBundle.Analysis a = analyze(player(7, 1, 0), state(7, 900_000), new PlayerBundle.TxlogCounts(5, 0L),
                absent("loc"), absent("lock"), absent("settle"));

        assertThat(a.ownership()).isEqualTo(BundleAnalyzer.RELEASED);
        assertThat(a.online()).startsWith("离线").contains("无位置记录");
        assertThat(a.persistence()).contains("最终写回已落库").contains("epoch 7");
        assertThat(a.leaseRemainingMs()).isNull();
        assertThat(a.warnings()).isEmpty();
    }

    @Test
    void 已释放但本代没写过玩法数据_库里是更早一代落的盘() {
        PlayerBundle.Analysis a = analyze(player(8, 1, 0), state(7, 900_000), null, null, null, null);

        assertThat(a.ownership()).isEqualTo(BundleAnalyzer.RELEASED);
        assertThat(a.persistence()).contains("epoch 7 写入").contains("epoch 8 没写玩法数据就释放");
        assertThat(a.online()).contains("未读 Redis");
    }

    @Test
    void 已释放且没有玩法数据_初始状态_有流水则告警() {
        PlayerBundle.Analysis a = analyze(player(3, 1, 0), PlayerBundle.State.absent(), new PlayerBundle.TxlogCounts(2, null),
                null, null, null);

        assertThat(a.persistence()).contains("从未写过玩法数据");
        assertThat(a.warnings()).anyMatch(w -> w.contains("没有 player_state 行却有 2 条资产流水"));
    }

    @Test
    void 租约未过期_在线_库里不是最终态() {
        Map<String, byte[]> loc = new LinkedHashMap<>();
        loc.put("e", b("9"));
        loc.put("q", b("3"));
        loc.put("s", b("o"));
        loc.put("v", PlayerLocation.newBuilder().setPlayerId(42).setZoneId(3).setSceneNodeId(5).setSceneConfigId(1001).setOwnerEpoch(9)
                .build().toByteArray());
        PlayerBundle.Analysis a = analyze(player(9, 0, NOW + 20_000), state(9, NOW - 60_000), new PlayerBundle.TxlogCounts(4, 1L),
                hash("loc", loc), absent("lock"), absent("settle"));

        assertThat(a.ownership()).isEqualTo(BundleAnalyzer.HELD);
        assertThat(a.leaseRemainingMs()).isEqualTo(20_000);
        assertThat(a.online()).contains("租约还剩 20000 ms").contains("位置记录：在线").contains("zone 3 节点 5");
        assertThat(a.persistence()).contains("本代（epoch 9）最近一次在线存盘").contains("距导出 60000 ms").contains("save-interval");
        assertThat(a.warnings()).anyMatch(w -> w.contains("导出时玩家在线"))
                .anyMatch(w -> w.contains("1 条资产流水的时刻晚于") && !w.contains("首要嫌疑"));
    }

    @Test
    void 租约已过期_写者失联_最终写回没落库_晚到的流水是首要嫌疑() {
        PlayerBundle.Analysis a = analyze(player(9, 0, NOW - 5_000), state(8, NOW - 400_000), new PlayerBundle.TxlogCounts(10, 3L),
                null, null, null);

        assertThat(a.ownership()).isEqualTo(BundleAnalyzer.HELD_LEASE_EXPIRED);
        assertThat(a.leaseRemainingMs()).isEqualTo(-5_000);
        assertThat(a.online()).contains("写者失联").contains("已过期 5000 ms");
        assertThat(a.persistence()).startsWith("最终写回没有落库").contains("本代（epoch 9）还没有在线存盘落库过").contains("已经丢失");
        assertThat(a.warnings()).anyMatch(w -> w.contains("3 条资产流水") && w.contains("首要嫌疑"))
                .anyMatch(w -> w.contains("进度已丢失"));
    }

    @Test
    void 导入占位_认得出来() {
        PlayerBundle.Analysis a = analyze(player(4, 0, LocalPlayerImporter.IMPORT_HOLD_LEASE), state(4, 1), null, null, null, null);

        assertThat(a.ownership()).isEqualTo(BundleAnalyzer.IMPORT_HOLD);
        assertThat(a.online()).contains("--replace");
        assertThat(a.leaseRemainingMs()).isNull();
    }

    @Test
    void 战斗锁与待结算奖励_位置在别的zone_登出墓碑() {
        Map<String, byte[]> lock = Map.of("b", b("77"), "s", b("F"));
        Map<String, byte[]> settle = Map.of("77", new byte[] {1, 2}, "78", new byte[] {3});
        Map<String, byte[]> loc = new LinkedHashMap<>();
        loc.put("e", b("7"));
        loc.put("s", b("o"));
        loc.put("v", PlayerLocation.newBuilder().setZoneId(9).setSceneNodeId(2).build().toByteArray());
        PlayerBundle.Analysis a = analyze(player(7, 1, 0), state(7, 1), null, hash("loc", loc), hash("lock", lock), hash("settle", settle));

        assertThat(a.warnings()).anyMatch(w -> w.contains("战斗锁") && w.contains("battle_id=77") && w.contains("状态 F"))
                .anyMatch(w -> w.contains("2 笔待结算战斗奖励"))
                .anyMatch(w -> w.contains("位置记录在 zone 9") && w.contains("player.zone_id=3"));

        Map<String, byte[]> tomb = Map.of("e", b("7"), "s", b("x"));
        assertThat(analyze(player(7, 1, 0), state(7, 1), null, hash("loc", tomb), null, null).online()).contains("登出墓碑");
    }

    @Test
    void saved_epoch大于owner_epoch_告警判读不可信_读取提示排在最前() {
        PlayerBundle.Analysis a = BundleAnalyzer.analyze(player(3, 1, 0), state(5, 1), null, null, null, null, NOW,
                List.of("没读 Redis"));

        assertThat(a.warnings().get(0)).isEqualTo("没读 Redis");
        assertThat(a.warnings()).anyMatch(w -> w.contains("saved_epoch=5 大于 owner_epoch=3"));
    }

    @Test
    void 没有玩家行() {
        PlayerBundle.Analysis a = BundleAnalyzer.analyze(null, null, null, null, null, null, NOW, List.of());

        assertThat(a.ownership()).isEqualTo(BundleAnalyzer.NO_PLAYER);
    }
}
