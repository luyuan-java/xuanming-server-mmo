package com.game.common.workshard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 验证 64 片 HRW 分工的顺序无关性、均匀分布、成员表为空/过期保活与 SQL 过滤生成。 */
class WorkShardTest {

    @Test
    void HRW归属与成员顺序无关且64片均匀覆盖() {
        List<String> m1 = List.of("node-a", "node-b", "node-c");
        List<String> m2 = List.of("node-c", "node-a", "node-b");
        Map<String, Integer> counts = new HashMap<>();
        for (int s = 0; s < WorkShard.SHARDS; s++) {
            String key = WorkShard.shardKey("trade_commerce", s);
            String o1 = WorkShard.owner(key, m1);
            String o2 = WorkShard.owner(key, m2);
            assertThat(o1).isEqualTo(o2).isIn(m1);
            counts.merge(o1, 1, Integer::sum);
        }
        assertThat(counts.keySet()).containsExactlyInAnyOrderElementsOf(m1);
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(WorkShard.SHARDS);
        // 增删副本只迁移该副本名下的片，其余片归属不变
        List<String> shrunk = List.of("node-a", "node-b");
        for (int s = 0; s < WorkShard.SHARDS; s++) {
            String key = WorkShard.shardKey("trade_commerce", s);
            String before = WorkShard.owner(key, m1);
            String after = WorkShard.owner(key, shrunk);
            if (!"node-c".equals(before)) {
                assertThat(after).isEqualTo(before);
            }
        }
    }

    @Test
    void 成员表为空或过期回落全片_不在非空表内返回零片_正常分片产出MOD条件() {
        AtomicLong now = new AtomicLong(1_000L);
        WorkShard.Membership membership = new WorkShard.Membership(30_000L, now::get);
        membership.setSelf("node-a");

        // 未读到成员表 -> 保活做全片
        assertThat(membership.sqlFilter("trade", "commerce_id").isAll()).isTrue();

        // 成员表含本副本与另两副本 -> 子集 MOD(...,64) IN(...)
        membership.updateMembers(List.of("node-a", "node-b", "node-c"));
        WorkShard.SqlFilter subset = membership.sqlFilter("trade", "commerce_id");
        assertThat(subset.isAll()).isFalse();
        assertThat(subset.isNone()).isFalse();
        assertThat(subset.sql()).startsWith("MOD(commerce_id,64) IN(");
        assertThat(subset.args()).hasSizeBetween(1, 63);

        // 本副本未在非空成员表内（刚启动或丢租约）-> 不做任何片 (1=0)
        membership.updateMembers(List.of("node-b", "node-c"));
        assertThat(membership.sqlFilter("trade", "commerce_id").isNone()).isTrue();

        // 超过 30 秒过期 -> 回落全片保活
        now.addAndGet(30_001L);
        assertThat(membership.sqlFilter("trade", "commerce_id").isAll()).isTrue();

        // 无符号取模兼容高位 bit63 置位 ID
        assertThat(WorkShard.shardOf(-1L)).isEqualTo((int) Long.remainderUnsigned(-1L, 64L));
    }
}
