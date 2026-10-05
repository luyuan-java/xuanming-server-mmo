package com.game.discovery.world;

import static com.game.discovery.world.WorldPlanBatchTest.channel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.WorldChannel;
import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 脚本参数编码与回复解析（不连 Redis；回复按 ByteArrayCodec + MULTI 的形态手工构造：整数 Long、字符串 byte[]）。 */
class WorldPlanCodecTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String s(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    void 写入参数是令牌_期望版本_写入后版本_再接_op_三元组_号一律无符号十进制() {
        WorldChannel big = channel(-2L, 1, 3, 0);   // scene_id = 2^64 - 2
        WorldPlanBatch batch = new WorldPlanBatch(9, 1_791_168_000_000L)
                .putChannel(big)
                .removeChannel(-1L)
                .setDesired(1, 4)
                .seedDesired(-1, 16)                // conf = 2^32 - 1
                .removeDesired(2)
                .setCooldown(1, 1_791_168_059_123L)
                .removeCooldown(3);
        List<byte[]> args = WorldPlanCodec.writeArgs("inst-1:abc", batch);
        assertThat(args).hasSize(3 + 7 * 3);
        assertThat(s(args.get(0))).isEqualTo("inst-1:abc");
        assertThat(s(args.get(1))).isEqualTo("9");
        assertThat(s(args.get(2))).isEqualTo("1791168000000");
        List<String> codes = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        for (int i = 3; i < args.size(); i += 3) {
            codes.add(s(args.get(i)));
            fields.add(s(args.get(i + 1)));
        }
        assertThat(codes).containsExactly("S", "D", "Q", "N", "X", "C", "Y");
        assertThat(fields).containsExactly("18446744073709551614", "18446744073709551615", "1", "4294967295", "2", "1", "3");
        assertThat(args.get(5)).isEqualTo(big.toBuilder().setPlanVersion(1_791_168_000_000L).build().toByteArray());
        assertThat(s(args.get(11))).isEqualTo("4");
        assertThat(s(args.get(14))).isEqualTo("16");
        assertThat(s(args.get(20))).isEqualTo("1791168059123");
        assertThat(args.get(8)).isEmpty();
    }

    @Test
    void 写入回复_一已写入取批次的新版本_负一围栏_负二冲突() {
        WorldPlanBatch batch = new WorldPlanBatch(11, 1_791_168_000_000L);
        assertThat(WorldPlanCodec.writeResult(1L, batch)).isEqualTo(WorldPlanWriteResult.written(1_791_168_000_000L));
        assertThat(WorldPlanCodec.writeResult(-1L, batch)).isEqualTo(WorldPlanWriteResult.FENCED);
        assertThat(WorldPlanCodec.writeResult(-2L, batch)).isEqualTo(WorldPlanWriteResult.CONFLICT);
        assertThat(WorldPlanCodec.writeResult(1L, batch).isWritten()).isTrue();
        assertThat(WorldPlanWriteResult.FENCED.isWritten()).isFalse();
        assertThatThrownBy(() -> WorldPlanCodec.writeResult(0L, batch)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.writeResult(12L, batch)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.writeResult(-3L, batch)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.writeResult(null, batch)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 版本号_不存在为_0_非整数或负数是数据损坏() {
        assertThat(WorldPlanCodec.parseVersion("k", null)).isZero();
        assertThat(WorldPlanCodec.parseVersion("k", b("17"))).isEqualTo(17);
        assertThat(WorldPlanCodec.parseVersion("k", "17")).isEqualTo(17);
        assertThatThrownBy(() -> WorldPlanCodec.parseVersion("k", b("x"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.parseVersion("k", b("-1"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 拉取回复按_scene_id_无符号升序() {
        WorldChannel a = channel(-1L, 1, 2, 0);   // 最大的无符号号
        WorldChannel c = channel(5, 2, 1, 0);
        WorldPlan plan = WorldPlanCodec.parsePlan(1, List.of(b("3"),
                b("18446744073709551615"), a.toByteArray(), b("5"), c.toByteArray()));
        assertThat(plan.version()).isEqualTo(3);
        assertThat(plan.channels()).containsExactly(c, a);
        assertThat(plan.channelsOn(2)).containsExactly(a);
        assertThat(plan.channelsOn(9)).isEmpty();
        assertThat(WorldPlanCodec.parsePlan(1, List.of(b("0"))).channels()).isEmpty();
    }

    @Test
    void 拉取回复里的坏记录整体失败而不是跳过() {
        WorldChannel a = channel(7, 1, 2, 0);
        // 字段与记录的 scene_id 不符
        assertThatThrownBy(() -> WorldPlanCodec.parsePlan(1, List.of(b("1"), b("8"), a.toByteArray())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("field=8");
        // 字段不是数字
        assertThatThrownBy(() -> WorldPlanCodec.parsePlan(1, List.of(b("1"), b("x"), a.toByteArray())))
                .isInstanceOf(IllegalStateException.class);
        // 值解不开
        assertThatThrownBy(() -> WorldPlanCodec.parsePlan(1, List.of(b("1"), b("7"), new byte[] {(byte) 0xff, 0x01})))
                .isInstanceOf(IllegalStateException.class);
        // 形状不对
        assertThatThrownBy(() -> WorldPlanCodec.parsePlan(1, List.of(b("1"), b("7"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.parsePlan(1, 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 快照回复_三段平铺_期望数宽松_冷却坏值按一直冷却() {
        WorldChannel a = channel(7, 1, 2, 0);
        List<Object> reply = List.of(b("5"), 1_791_168_059_123L, 2L, 6L, 4L,
                b("7"), a.toByteArray(),
                b("1"), b("16"), b("2"), b("0"), b("3"), b("abc"),
                b("1"), b("1791168060000"), b("2"), b("zz"));
        WorldPlanSnapshot snap = WorldPlanCodec.parseSnapshot(1, reply);
        assertThat(snap.version()).isEqualTo(5);
        assertThat(snap.nowMs()).isEqualTo(1_791_168_059_123L);
        assertThat(snap.channels()).containsExactly(java.util.Map.entry(7L, a));
        // 0 这种不合法的期望数也原样给规划器（它按「≥ 1 才算」判）；解析不了的跳过
        assertThat(snap.desired()).containsExactly(java.util.Map.entry(1, 16), java.util.Map.entry(2, 0));
        assertThat(snap.cooldownUntilMs()).containsExactly(
                java.util.Map.entry(1, 1_791_168_060_000L), java.util.Map.entry(2, Long.MAX_VALUE));
    }

    @Test
    void 快照回复形状不对就失败() {
        assertThatThrownBy(() -> WorldPlanCodec.parseSnapshot(1, List.of(b("0"), 1L, 2L, 0L, 0L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.parseSnapshot(1, List.of(b("0"), 1L, 1L, 0L, 0L, b("7"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.parseSnapshot(1, List.of(b("0"), 1L)))
                .isInstanceOf(IllegalStateException.class);
        WorldPlanSnapshot empty = WorldPlanCodec.parseSnapshot(1, List.of(b("0"), 1L, 0L, 0L, 0L));
        assertThat(empty.channels()).isEmpty();
        assertThat(empty.desired()).isEmpty();
        assertThat(empty.cooldownUntilMs()).isEmpty();
    }

    @Test
    void 快照的三张表按无符号升序迭代且不可修改() {
        WorldPlanSnapshot snap = new WorldPlanSnapshot(1, 2,
                java.util.Map.of(-1L, channel(-1L, 1, 1, 0), 3L, channel(3, 1, 1, 1)),
                java.util.Map.of(-1, 1, 2, 1), java.util.Map.of());
        assertThat(snap.channels().keySet()).containsExactly(3L, -1L);
        assertThat(snap.desired().keySet()).containsExactly(2, -1);
        assertThatThrownBy(() -> snap.desired().put(5, 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 预占回复下标从_1_起_越界是故障() {
        assertThat(WorldPlanCodec.parsePick(List.of(2L, 5L), 3)).isEqualTo(new ReservationPick(1, 5));
        assertThatThrownBy(() -> WorldPlanCodec.parsePick(List.of(0L, 5L), 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.parsePick(List.of(4L, 5L), 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorldPlanCodec.parsePick(List.of(1L), 3)).isInstanceOf(IllegalStateException.class);
        assertThat(WorldPlanCodec.parseCounts(List.of(1L, 0L), 2)).containsExactly(1L, 0L);
        assertThatThrownBy(() -> WorldPlanCodec.parseCounts(List.of(1L), 2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 频道键共用_zone_hash_tag_号按无符号() {
        assertThat(RedisKeys.worldChannels(3)).isEqualTo("xm:world:{z:3}:ch");
        assertThat(RedisKeys.worldDesired(3)).isEqualTo("xm:world:{z:3}:desired");
        assertThat(RedisKeys.worldCooldown(3)).isEqualTo("xm:world:{z:3}:cooldown");
        assertThat(RedisKeys.worldPlanVersion(3)).isEqualTo("xm:world:{z:3}:ver");
        assertThat(RedisKeys.worldLeader(3)).isEqualTo("xm:world:{z:3}:leader");
        assertThat(RedisKeys.worldReservations(3, -1L)).isEqualTo("xm:world:{z:3}:resv:18446744073709551615");
        assertThat(RedisKeys.worldChannels(-1)).isEqualTo("xm:world:{z:4294967295}:ch");
        assertThat(RedisKeys.worldZones()).isEqualTo("xm:world:zones");
    }
}
