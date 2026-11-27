package com.game.match.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.MatchProperties;
import com.game.match.feature.MatchFeatureTables;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.rating.RatingTestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 剧本审核流水保留期配置边界、多页游标清理、逾期年龄指标与失败告警计数校验。 */
class BattleScriptReviewCleanupTest {

    @Test
    void 保留期配置缺省或0取180天_越出30至365天拒启() {
        MatchProperties defaultProps = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(defaultProps.scriptReviewRetentionDays()).isEqualTo(180);

        MatchProperties zeroProps = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, 0);
        assertThat(zeroProps.scriptReviewRetentionDays()).isEqualTo(180);

        MatchProperties minProps = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, 30);
        assertThat(minProps.scriptReviewRetentionDays()).isEqualTo(30);

        MatchProperties maxProps = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, 365);
        assertThat(maxProps.scriptReviewRetentionDays()).isEqualTo(365);

        for (int bad : new int[] {-1, 1, 29, 366, 999}) {
            assertThatThrownBy(() -> new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 单轮连续翻多页清完过期审核流水_未过期保留_逾期年龄与失败计数准确() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_900_000_000_000L);
            BattleScriptStore store = new BattleScriptStore(db.dataSource, now::get);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));

            long dayMs = Duration.ofDays(1).toMillis();
            // 插入 5 条已超 180 天（分别老 190..186 天）与 1 条未过期（老 10 天）
            try (Connection c = db.dataSource.getConnection();
                 PreparedStatement ins = c.prepareStatement(
                         "INSERT INTO match_battle_script_review(script_id,revision,owner_player_id,approved,reviewer,reason,reviewed_ms,content) "
                                 + "VALUES(?,?,?,?,?,?,?,?)")) {
                for (int i = 0; i < 5; i++) {
                    ins.setLong(1, 1000L + i);
                    ins.setInt(2, 1);
                    ins.setLong(3, 500L);
                    ins.setBoolean(4, true);
                    ins.setString(5, "gm");
                    ins.setString(6, "");
                    ins.setLong(7, now.get() - (190L - i) * dayMs);
                    ins.setBytes(8, new byte[] {1});
                    ins.executeUpdate();
                }
                ins.setLong(1, 2000L);
                ins.setInt(2, 1);
                ins.setLong(3, 500L);
                ins.setBoolean(4, true);
                ins.setString(5, "gm");
                ins.setString(6, "");
                ins.setLong(7, now.get() - 10L * dayMs);
                ins.setBytes(8, new byte[] {2});
                ins.executeUpdate();
            }

            // 第一轮限 1 页（每页 2 行）：删掉最老 2 行，仍有超期 8 天的行 -> 逾期秒数约 8 天
            try (BattleScriptReviewCleanup partial = new BattleScriptReviewCleanup(
                    store, Duration.ofDays(180), metrics, now::get, 2, 1)) {
                assertThat(partial.purgeOnce()).isEqualTo(2L);
                double overdue = meters.get("game.match.retention.overdue.age")
                        .tag("kind", "battle_script_review").gauge().value();
                assertThat(overdue).isGreaterThanOrEqualTo(Duration.ofDays(7).toSeconds());
            }

            // 第二轮多页清完剩余 3 条过期行，仅留未过期那 1 条 -> 逾期秒数归零，总删除 = 5
            try (BattleScriptReviewCleanup full = new BattleScriptReviewCleanup(
                    store, Duration.ofDays(180), metrics, now::get, 2, 10)) {
                assertThat(full.purgeOnce()).isEqualTo(3L);
                assertThat(meters.get("game.match.script.reviews.purged").counter().count()).isEqualTo(5.0);
                assertThat(meters.get("game.match.retention.overdue.age")
                        .tag("kind", "battle_script_review").gauge().value()).isZero();
                assertThat(store.oldestReviewMs()).isEqualTo(now.get() - 10L * dayMs);
            }

            // 数据库失败时计入失败指标而不崩溃
            DataSource broken = Mockito.mock(DataSource.class);
            Mockito.when(broken.getConnection()).thenThrow(new SQLException("db down"));
            try (BattleScriptReviewCleanup failing = new BattleScriptReviewCleanup(
                    new BattleScriptStore(broken, now::get), Duration.ofDays(180), metrics, now::get, 2, 5)) {
                failing.runRoundForTest();
                assertThat(meters.get("game.match.retention.purge.failures")
                        .tag("kind", "battle_script_review").counter().count()).isEqualTo(1.0);
            }
        }
    }
}
