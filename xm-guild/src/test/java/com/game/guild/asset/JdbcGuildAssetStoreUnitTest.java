package com.game.guild.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.guild.asset.JdbcGuildAssetStore.CounterCutoffs;
import com.game.guild.rules.GuildLimits;
import com.game.guild.store.BackgroundTx;
import com.game.guild.store.JdbcEconomyStore;
import com.game.guild.store.JdbcGuildStore;
import com.game.guild.store.pb.GuildAssetOpStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 资产 Store 的不连库单测（照 mmorpg economy_repo_test.go 的纯单测部分、asset_op_divergence_repo_test.go:42-136 的 G6、
 * guild_internal_server_test.go:84-204 的入参与保留期判定；guild-economy-spec §11.1、§11.3「语句形状」）。
 */
class JdbcGuildAssetStoreUnitTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY_MS = 86_400_000L;

    /** 碰库即失败的后台事务基座：取连接就抛 AssertionError。 */
    private static JdbcGuildAssetStore noDatabase() {
        BackgroundTx tx = new BackgroundTx(maxWait -> {
            throw new AssertionError("入参校验之前不许碰库");
        }, 3, BackgroundTx.Listener.NONE);
        return new JdbcGuildAssetStore(tx, GuildAssetStore.Listener.NONE);
    }

    private static Deadline d() {
        return Deadline.after(30_000);
    }

    // ================================================================ 入参先于碰库

    /** 终结只能落成四个终态之一（TestFinalizeAndResolveRefuseNonFinalStatusBeforeTouchingStorage）。 */
    @Test
    void 终结与人工终结拒绝非终态_不碰库() {
        JdbcGuildAssetStore store = noDatabase();
        AssetOpResult applied = new AssetOpResult(com.game.api.proto.AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true, false, false);
        for (AssetOpStatus status : new AssetOpStatus[] {AssetOpStatus.PENDING, null}) {
            assertThatThrownBy(() -> store.finalizeOp(1, status, applied, NOW, DeliveryOrigin.LOOP, d()))
                    .as("finalize %s", status).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.resolveManually(new ManualResolution(1, status, "ops", "test"), NOW, d()))
                    .as("resolve %s", status).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.resolveManually(new ManualResolution(1, AssetOpStatus.APPLIED, "", "r"), NOW, d()))
                .as("操作人必填").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.resolveManually(new ManualResolution(1, AssetOpStatus.APPLIED, "o".repeat(65), "r"),
                NOW, d())).as("审计列超长在写库之前拒绝").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim(1, NOW, NOW + 10_000, NOW + 3_600_000, 0, d()))
                .as("令牌 0 = 没有租约").isInstanceOf(IllegalArgumentException.class);
        assertThat(store.listDue(NOW, 0, d())).isEmpty();
        assertThat(store.listStuck(NOW, 0, d())).isEmpty();
        assertThatThrownBy(() -> store.cleanupOnce(NOW, Duration.ZERO, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.cleanupOnce(NOW, Duration.ofDays(30), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 回档检查的入参越界在碰库之前失败（TestListAppliedSinceRejectsMalformedInputBeforeTouchingStorage）。 */
    @Test
    void 回档检查畸形入参不碰库() {
        JdbcGuildAssetStore store = noDatabase();
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= GuildLimits.APPLIED_OPS_MAX_PLAYER_IDS + 1; i++) {
            tooMany.add(i);
        }
        AppliedOpsQuery good = new AppliedOpsQuery(0, List.of(1L), NOW, 0, GuildLimits.APPLIED_OPS_MAX_PAGE_LIMIT);
        assertThat(good.invalidReason()).isNull();
        Map<String, UnaryOperator<AppliedOpsQuery>> breakers = Map.of(
                "player_ids 为空", q -> new AppliedOpsQuery(0, List.of(), q.sinceMs(), 0, q.limit()),
                "player_ids 超上限", q -> new AppliedOpsQuery(0, tooMany, q.sinceMs(), 0, q.limit()),
                "limit 为 0", q -> new AppliedOpsQuery(0, q.playerIds(), q.sinceMs(), 0, 0),
                "limit 超上限", q -> new AppliedOpsQuery(0, q.playerIds(), q.sinceMs(), 0, GuildLimits.APPLIED_OPS_MAX_PAGE_LIMIT + 1),
                "since 为 0", q -> new AppliedOpsQuery(0, q.playerIds(), 0, 0, q.limit()));
        breakers.forEach((name, breaker) -> {
            AppliedOpsQuery bad = breaker.apply(good);
            assertThat(bad.invalidReason()).as(name).isNotNull();
            assertThatThrownBy(() -> store.listAppliedSince(bad, d())).as(name).isInstanceOf(IllegalArgumentException.class);
        });
    }

    /** 提供方的入参判定顺序与文案（guild_internal_server_test.go:84）。 */
    @Test
    void 提供方入参判定顺序与文案() {
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            tooMany.add(i);
        }
        assertThat(AppliedOpsQuery.requestError(List.of(), NOW, 0)).isEqualTo("player_ids is required");
        assertThat(AppliedOpsQuery.requestError(null, NOW, 0)).isEqualTo("player_ids is required");
        assertThat(AppliedOpsQuery.requestError(tooMany, NOW, 0)).isEqualTo("player_ids has 101 entries, limit 100");
        assertThat(AppliedOpsQuery.requestError(List.of(1L, 0L), NOW, 0)).isEqualTo("player_ids must not contain 0");
        assertThat(AppliedOpsQuery.requestError(List.of(1L, 2L, 1L), NOW, 0)).isEqualTo("player_ids must not contain duplicates");
        assertThat(AppliedOpsQuery.requestError(List.of(1L), 0, 0)).isEqualTo("since_ms is required");
        assertThat(AppliedOpsQuery.requestError(List.of(1L), NOW, 501)).isEqualTo("limit 501 exceeds 500");
        assertThat(AppliedOpsQuery.requestError(List.of(1L), NOW, -1)).as("limit 是 uint32").isEqualTo("limit 4294967295 exceeds 500");
        assertThat(AppliedOpsQuery.requestError(List.of(1L, 0x8000_0000_0000_0000L), NOW, 500)).isNull();
        // 判定顺序：空列表先于 since
        assertThat(AppliedOpsQuery.requestError(List.of(), 0, 999)).isEqualTo("player_ids is required");
        assertThat(AppliedOpsQuery.effectiveLimit(0)).isEqualTo(500);
        assertThat(AppliedOpsQuery.effectiveLimit(7)).isEqualTo(7);
    }

    /** 保留期下界 = now + 1 h − 保留期，下溢取 0（guild_internal_server_test.go:170-204 的 cutoff 样例）。 */
    @Test
    void 保留期下界与下溢() {
        long retention30d = Duration.ofDays(30).toMillis();
        // 基线跨服务契约样例：cutoff_ms=1697411600000（now = 1700000000000、保留期 30 d）
        assertThat(AppliedOpsQuery.retentionCutoffMs(1_700_000_000_000L, retention30d)).isEqualTo(1_697_411_600_000L);
        assertThat(AppliedOpsQuery.retentionCutoffMs(1_000, retention30d)).as("小时间戳下溢取 0").isZero();
        assertThat(AppliedOpsQuery.retentionCutoffMs(retention30d - GuildLimits.APPLIED_OPS_RETENTION_SAFETY_MS, retention30d))
                .as("恰好相等也取 0").isZero();
        assertThat(AppliedOpsQuery.retentionCutoffMs(retention30d - GuildLimits.APPLIED_OPS_RETENTION_SAFETY_MS + 1,
                retention30d)).isEqualTo(1);
    }

    // ================================================================ 语句形状

    /** 回档检查语句的两种完整形状（TestListAppliedSinceStatementShape，asset_op_divergence_repo_test.go:75）。 */
    @Test
    void 回档检查语句形状_占位符与参数一一对应_不加锁() {
        assertThat(JdbcGuildAssetStore.appliedOpsSql(1, false)).isEqualTo(
                "SELECT o.`op_id`, o.`player_id`, o.`guild_id`, o.`stream`, o.`kind`, o.`status`,"
                        + " o.`funds_delta`, o.`contribution_delta`, o.`updated_ms` FROM guild_asset_op o"
                        + " WHERE o.`status` IN (?, ?) AND o.`next_attempt_ms` > ? AND o.`player_id` IN (?)"
                        + " AND o.`op_id` > ? ORDER BY o.`op_id` ASC LIMIT ?");
        assertThat(JdbcGuildAssetStore.appliedOpsSql(2, true)).isEqualTo(
                "SELECT o.`op_id`, o.`player_id`, o.`guild_id`, o.`stream`, o.`kind`, o.`status`,"
                        + " o.`funds_delta`, o.`contribution_delta`, o.`updated_ms` FROM guild_asset_op o"
                        + " LEFT JOIN guild g ON g.guild_id = o.`guild_id`"
                        + " WHERE o.`status` IN (?, ?) AND o.`next_attempt_ms` > ? AND o.`player_id` IN (?,?)"
                        + " AND o.`op_id` > ? AND (g.zone_id = ? OR g.guild_id IS NULL) ORDER BY o.`op_id` ASC LIMIT ?");
        for (int players : new int[] {1, 2, GuildLimits.APPLIED_OPS_MAX_PLAYER_IDS}) {
            for (int zone : new int[] {0, 7}) {
                AppliedOpsQuery q = new AppliedOpsQuery(zone, Collections.nCopies(players, 9L), 1, 5, 500);
                String sql = JdbcGuildAssetStore.appliedOpsSql(players, zone != 0);
                Object[] args = JdbcGuildAssetStore.appliedOpsArgs(q);
                assertThat(sql.chars().filter(c -> c == '?').count()).as("players=%d zone=%d", players, zone)
                        .isEqualTo(args.length);
                assertThat(args[0]).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_VALUE);
                assertThat(args[1]).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL_VALUE);
                assertThat(args[args.length - 1]).as("多取一行只用来判断还有没有下一页").isEqualTo(501);
                String upper = sql.toUpperCase(Locale.ROOT);
                for (String clause : new String[] {"FOR UPDATE", "FOR SHARE", "LOCK IN SHARE MODE"}) {
                    assertThat(upper).as("回档检查是纯非锁定读").doesNotContain(clause);
                }
                assertThat(upper).as("不写索引提示").doesNotContain("FORCE INDEX").doesNotContain("USE INDEX");
            }
        }
    }

    /**
     * 回档检查的源码守卫（TestListAppliedSinceSourceGuards 的 Java 版）：listAppliedSince 只走自动提交读，不开事务、不点锁——加了这些它就进了
     * 取锁序列，要重新推演。
     */
    @Test
    void 回档检查不开事务不点锁() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/game/guild/asset/JdbcGuildAssetStore.java"));
        int start = source.indexOf("public AppliedOpsPage listAppliedSince(");
        int end = source.indexOf("static String appliedOpsSql(", start);
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        String body = source.substring(start, end);
        assertThat(body).contains("tx.autocommit(").doesNotContain("tx.run(").doesNotContain("LOCK_ASSET_OP")
                .doesNotContain("LOCK_SEQ_GUARD").doesNotContain("FOR UPDATE");
    }

    /**
     * 候选读不加锁、点锁只许完整主键等值 + FOR UPDATE、点改 / 点删按完整主键定位、成员语句强制走主键（TestEconomyCandidateReadsTakeNoLocks
     * 的 Store 半边）。
     */
    @Test
    void 资产Store语句形状() {
        Map<String, String> plainReads = Map.of(
                "O9", JdbcGuildAssetStore.LIST_DUE_FRESH,
                "O10", JdbcGuildAssetStore.LIST_DUE_AGED,
                "O12", JdbcGuildAssetStore.SELECT_BY_ID,
                "O15", JdbcGuildAssetStore.SELECT_IMMUTABLE,
                "O18", JdbcGuildAssetStore.OLDEST_PENDING,
                "O19", JdbcGuildAssetStore.LIST_STUCK,
                "O20", JdbcGuildAssetStore.LIST_CLEANUP_TERMINAL_OPS,
                "C5", JdbcGuildAssetStore.LIST_CLEANUP_COUNTERS);
        plainReads.forEach((name, sql) -> {
            String upper = sql.toUpperCase(Locale.ROOT);
            for (String clause : new String[] {"FOR UPDATE", "FOR SHARE", "LOCK IN SHARE MODE"}) {
                assertThat(upper).as("%s 是普通读: %s", name, sql).doesNotContain(clause);
            }
        });
        // 点锁：只许完整主键等值 + FOR UPDATE，不带复核条件（TiDB 才走 Point_Get 快路径）
        assertThat(JdbcGuildAssetStore.LOCK_CLEANUP_COUNTER)
                .endsWith("WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ? FOR UPDATE");
        assertThat(JdbcGuildAssetStore.LOCK_SEQ_GUARD).endsWith("WHERE `player_id` = ? AND `stream` = ? FOR UPDATE");
        assertThat(JdbcGuildAssetStore.LOCK_GUILD_FUNDS).endsWith("WHERE guild_id = ? FOR UPDATE");
        // 点改 / 点删的定位是完整主键等值（复核条件另带）
        for (String sql : new String[] {JdbcGuildAssetStore.CLAIM, JdbcGuildAssetStore.POISON, JdbcGuildAssetStore.RESCHEDULE,
                JdbcGuildAssetStore.RESCHEDULE_WITHOUT_ANSWER, JdbcGuildAssetStore.FINALIZE, JdbcGuildAssetStore.RESOLVE,
                JdbcGuildAssetStore.CLEANUP_TERMINAL_OP}) {
            assertThat(sql).contains("WHERE `op_id` = ? AND");
        }
        assertThat(JdbcGuildAssetStore.CLEANUP_COUNTER)
                .contains("player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ? AND");
        for (String sql : new String[] {JdbcGuildAssetStore.CREDIT_CONTRIBUTION, JdbcGuildAssetStore.REFUND_CONTRIBUTION}) {
            assertThat(sql).contains("guild_member FORCE INDEX (PRIMARY)");
        }
        // 终结与人工终结都同写 next_attempt_ms（终态行的 next_attempt_ms = 终结时刻，回档检查依赖它）
        assertThat(JdbcGuildAssetStore.FINALIZE).contains("`next_attempt_ms` = ?").contains("`durable` = 1");
        assertThat(JdbcGuildAssetStore.RESOLVE).contains("`next_attempt_ms` = ?").doesNotContain("durable")
                .doesNotContain("last_outcome");
        // 毒行推迟带令牌与 PENDING 两个条件；E12 的重排不碰 durable / last_outcome / last_reason
        assertThat(JdbcGuildAssetStore.POISON).endsWith("WHERE `op_id` = ? AND `lease_token` = ? AND `status` = ?");
        assertThat(JdbcGuildAssetStore.RESCHEDULE_WITHOUT_ANSWER).doesNotContain("durable").doesNotContain("last_outcome")
                .doesNotContain("last_reason");
        // 清理只删三种终态：APPLIED_PARTIAL 与 PENDING 永不自动删（占位符 3 个，绑定见实现）
        assertThat(JdbcGuildAssetStore.CLEANUP_TERMINAL_OP).contains("`status` IN (?, ?, ?)");
        // 退计数兜到 0，防 unsigned 减穿 1690
        assertThat(JdbcGuildAssetStore.REFUND_COUNTER).contains("IF(used_count >= ?, used_count - ?, 0)");
        assertThat(JdbcEconomyStore.LOCK_ASSET_OP).endsWith("WHERE `op_id` = ? FOR UPDATE");
        assertThat(JdbcGuildStore.LOCK_MEMBER_ROLE).contains("FORCE INDEX (PRIMARY)");
    }

    // ================================================================ 计数行清理截止

    /**
     * 计数行清理的截止键删不到「切周 / 切日后仍可能被在途预留 upsert 写的上一周期行」（TestCounterCleanupCutoffsSparePreviousPeriod，
     * economy_repo_test.go:317；C5 补遗）。
     */
    @Test
    void 计数清理截止不碰上一周期() {
        long minRetention = 7 * DAY_MS; // 配置允许的最短计数保留期
        // 2026-09-14 是周一，05:00 切周（与 GameDay 单测同一锚点）
        long weekSwitch = LocalDateTime.of(2026, 9, 14, GameDay.RESET_HOUR, 0).toInstant(GameDay.ZONE).toEpochMilli();
        long before = weekSwitch - 1_000;
        long after = weekSwitch + 1_000;
        // 前提：不夹紧时，保留期 7 天在切周后 1 秒算出的周截止恰是上一周——这正是要防的情形
        assertThat(GameDay.weekKey(after - minRetention)).isEqualTo(GameDay.weekKey(before));

        CounterCutoffs cut = JdbcGuildAssetStore.counterCleanupCutoffs(after, minRetention);
        assertThat(cut.weekKey()).as("切周后 1 秒：上一周的计数行不能进清理范围").isLessThan(GameDay.weekKey(before));
        assertThat(cut.dayKey()).as("切日后 1 秒：上一日的计数行不能进清理范围").isLessThan(GameDay.dayKey(before));

        // 逐小时扫 6 周（含 2026-W53 → 2027-W01 跨 ISO 年）：任意时刻，24 h 内开始的请求所写的日键 / 周键都严格大于截止键
        long scanFrom = LocalDateTime.of(2026, 12, 14, 0, 30).toInstant(GameDay.ZONE).toEpochMilli();
        for (long now = scanFrom; now < scanFrom + 42 * DAY_MS; now += 3_600_000L) {
            for (long retention : new long[] {1, minRetention}) {
                CounterCutoffs c = JdbcGuildAssetStore.counterCleanupCutoffs(now, retention);
                long recent = now - DAY_MS;
                assertThat(c.weekKey()).as("now=%s retention=%d", Instant.ofEpochMilli(now), retention)
                        .isLessThan(GameDay.weekKey(recent));
                assertThat(c.dayKey()).as("now=%s retention=%d", Instant.ofEpochMilli(now), retention)
                        .isLessThan(GameDay.dayKey(recent));
            }
        }
        // 保留期长于下限时不夹紧：截止就是 now − retention 所在的周期
        CounterCutoffs thirty = JdbcGuildAssetStore.counterCleanupCutoffs(after, 30 * DAY_MS);
        assertThat(thirty).isEqualTo(new CounterCutoffs(GameDay.dayKey(after - 30 * DAY_MS), GameDay.weekKey(after - 30 * DAY_MS)));
        // 日键与周键数值域不相交，各走一段 BETWEEN
        assertThat(GuildLimits.DAY_KEY_FLOOR).isGreaterThan(999_999);
        assertThat(GameDay.weekKey(NOW)).isBetween(GuildLimits.WEEK_KEY_FLOOR, 999_999);
        assertThat(GameDay.dayKey(NOW)).isGreaterThanOrEqualTo(GuildLimits.DAY_KEY_FLOOR);
    }
}
