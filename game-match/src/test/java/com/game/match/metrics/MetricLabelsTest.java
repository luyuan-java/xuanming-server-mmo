package com.game.match.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.dispatch.MatchMethods;
import com.game.proto.match.MatchMode;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 指标标签的净化（match-spec §11、§15.1；修基线 B2）：客户端能控制的模式与副本号，进标签之前必须收敛到有界集合。
 */
class MetricLabelsTest {

    /** Dungeon 表里只有 1、2、3。 */
    private final MetricLabels labels = new MetricLabels(id -> id >= 1 && id <= 3);

    @Test
    void 模式_已知值取枚举名_与基线对已知值的输出相同() {
        assertThat(MetricLabels.mode(0)).isEqualTo("MATCH_MODE_UNSPECIFIED");
        assertThat(MetricLabels.mode(1)).isEqualTo("MATCH_MODE_5V5");
        assertThat(MetricLabels.mode(2)).isEqualTo("MATCH_MODE_3V3");
        assertThat(MetricLabels.mode(3)).isEqualTo("MATCH_MODE_1V1");
        assertThat(MetricLabels.mode(4)).isEqualTo("MATCH_MODE_PVE_SOLO");
        assertThat(MetricLabels.mode(5)).isEqualTo("MATCH_MODE_PVE_TEAM");
        assertThat(MetricLabels.mode(6)).isEqualTo("MATCH_MODE_PVP_CHALLENGE");
    }

    @Test
    void 模式_契约里没有的值一律unknown_不输出数字串() {
        assertThat(MetricLabels.mode(7)).isEqualTo("unknown");
        assertThat(MetricLabels.mode(-1)).isEqualTo("unknown");
        assertThat(MetricLabels.mode(Integer.MAX_VALUE)).isEqualTo("unknown");
        assertThat(MetricLabels.mode(MatchMode.UNRECOGNIZED)).isEqualTo("unknown");
        assertThat(MetricLabels.mode((MatchMode) null)).isEqualTo("unknown");
        assertThat(MetricLabels.mode(MatchMode.MATCH_MODE_1V1)).isEqualTo("MATCH_MODE_1V1");
    }

    @Test
    void 模式标签的取值集合有界_扫遍全部int也只有八个() {
        Set<String> seen = new HashSet<>();
        for (int mode = -1000; mode <= 1000; mode++) {
            seen.add(MetricLabels.mode(mode));
        }
        assertThat(seen).hasSize(8).contains("unknown");
    }

    @Test
    void 副本号_只取0或表里存在的id_其余other() {
        assertThat(labels.config(0)).isEqualTo("0");
        assertThat(labels.config(1)).isEqualTo("1");
        assertThat(labels.config(3)).isEqualTo("3");
        assertThat(labels.config(4)).as("表里没有").isEqualTo("other");
        assertThat(labels.config(12345)).isEqualTo("other");
        assertThat(labels.config(-1)).as("uint32 的 0xFFFFFFFF").isEqualTo("other");
        assertThat(labels.config(Integer.MIN_VALUE)).isEqualTo("other");
    }

    @Test
    void 副本号标签的取值集合有界_客户端造任意多条队列也撑不大() {
        Set<String> seen = new HashSet<>();
        for (int config = -5000; config <= 5000; config++) {
            seen.add(labels.config(config));
        }
        assertThat(seen).containsExactlyInAnyOrder("0", "1", "2", "3", "other");
    }

    @Test
    void 负的副本号不去查表() {
        MetricLabels strict = new MetricLabels(id -> {
            assertThat(id).as("查表的只会是正数").isPositive();
            return true;
        });
        assertThat(strict.config(-7)).isEqualTo("other");
        assertThat(strict.config(7)).isEqualTo("7");
    }

    @Test
    void 方法名_只取契约的十个_其余unknown() {
        for (String method : MatchMethods.ALL) {
            assertThat(MetricLabels.method(method)).isEqualTo(method);
        }
        assertThat(MetricLabels.method("JoinQueue")).isEqualTo("JoinQueue");
        assertThat(MetricLabels.method("joinqueue")).isEqualTo("unknown");
        assertThat(MetricLabels.method("StartActivityBattle")).isEqualTo("unknown");
        assertThat(MetricLabels.method(null)).isEqualTo("unknown");
        assertThat(MetricLabels.method("")).isEqualTo("unknown");
    }

    @Test
    void 活动类型_已知值去前缀小写_未知值unknown() {
        assertThat(MetricLabels.activityKind(0)).isEqualTo("none");
        assertThat(MetricLabels.activityKind(1)).isEqualTo("guild_trial");
        assertThat(MetricLabels.activityKind(2)).isEqualTo("unknown");
        assertThat(MetricLabels.activityKind(-1)).isEqualTo("unknown");
        assertThat(MetricLabels.activityKind(99)).isEqualTo("unknown");
    }
}
