package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildReject;
import com.game.guild.store.EconomyStore.DonationReserve;
import com.game.guild.store.EconomyStore.ShopReserve;
import com.game.guild.store.EconomyStore.Upgraded;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 经济仓储的不连库单测（照 mmorpg economy_repo_test.go:53-348 的纯单测部分；guild-economy-spec §11.1、§11.3「语句形状」）：
 * 手写列清单与 proto 字段序、入参校验必须在碰库之前失败、候选读不加锁、锁定语句的形状、结果的失效与收件人。
 */
class EconomyStoreUnitTest {

    private static final long NOW = 1_700_000_000_000L;

    /** 碰库即失败的事务基座：取连接就抛 AssertionError，证明「畸形输入在碰库之前就失败」。 */
    private static GuildTx noDatabase() {
        return new GuildTx(maxWait -> {
            throw new AssertionError("入参校验之前不许碰库");
        }, 3, GuildTxListener.NONE);
    }

    private static Deadline d() {
        return Deadline.after(30_000);
    }

    // ================================================================ 列清单

    /**
     * 手写列清单与 proto 字段序逐一一致（TestAssetOpColumnsCoverEveryProtoField，economy_repo_test.go:58）：往 GuildAssetOpRow 加字段却忘了
     * 同步，表现是「新列永远零值」或运行期列数不符，编译期都不报。
     */
    @Test
    void 列清单覆盖每个proto字段且顺序一致() {
        List<String> want = new ArrayList<>();
        for (FieldDescriptor f : GuildAssetOpRow.getDescriptor().getFields()) {
            want.add(f.getName());
        }
        List<String> got = new ArrayList<>();
        for (String part : AssetOpColumns.COLUMNS.split(",")) {
            got.add(part.trim().replace("`", ""));
        }
        assertThat(got).isEqualTo(want);
        assertThat(AssetOpColumns.COUNT).isEqualTo(got.size()).isEqualTo(28);
        assertThat(AssetOpColumns.insertArgs(GuildAssetOpRow.getDefaultInstance())).hasSize(want.size());
        assertThat(AssetOpColumns.INSERT.chars().filter(c -> c == '?').count()).isEqualTo(want.size());
        assertThat(AssetOpColumns.INSERT).startsWith("INSERT INTO guild_asset_op (");
        // 字段号也是 1..28 连续（列序 = 字段序 = 字段号序）
        for (int i = 0; i < want.size(); i++) {
            assertThat(GuildAssetOpRow.getDescriptor().getFields().get(i).getNumber()).isEqualTo(i + 1);
        }
    }

    /** 插入参数按 GuildJdbc 的绑定约定：uint64 → Long、uint32 与枚举 → Integer、bytes → byte[]；可空三列写空值而不是 NULL。 */
    @Test
    void 插入参数的类型与可空列() {
        Object[] args = AssetOpColumns.insertArgs(GuildAssetOpRow.newBuilder().setLeaseToken(-1L).build());
        int i = 0;
        for (FieldDescriptor f : GuildAssetOpRow.getDescriptor().getFields()) {
            Object arg = args[i++];
            switch (f.getType()) {
                case UINT64 -> assertThat(arg).as(f.getName()).isInstanceOf(Long.class);
                case UINT32, ENUM -> assertThat(arg).as(f.getName()).isInstanceOf(Integer.class);
                case BYTES -> assertThat(arg).as(f.getName()).isEqualTo(new byte[0]);
                case STRING -> assertThat(arg).as(f.getName()).isEqualTo("");
                default -> throw new AssertionError("未预期的列类型 " + f.getType() + "：" + f.getName());
            }
            assertThat(GuildJdbc.bindValue(arg)).as("%s 必须能被绑定", f.getName()).isNotNull();
        }
        assertThat(GuildJdbc.bindValue(args[21])).as("lease_token ≥ 2^63 按无符号绑定")
                .isEqualTo(new java.math.BigInteger("18446744073709551615"));
    }

    // ================================================================ 入参校验先于碰库

    private static DonationReserve goodDonation() {
        return new DonationReserve(1, 2, 3, 1, 1, 10, 1000, 5, 20_260_921, NOW + 600_000, NOW + 10_000, 7, NOW,
                ByteString.copyFrom(new byte[] {0x08}), null);
    }

    private static ShopReserve goodShop() {
        return new ShopReserve(1, 2, 3, 101, 1, 1, 30, 5, 20_260_921, NOW + 10_000, 7, NOW,
                ByteString.copyFrom(new byte[] {0x08}), null);
    }

    /** 编程错误（0 值 id / 空 payload / 0 上限 / 0 截止）在碰库之前失败（TestReserveRejectsMalformedInputBeforeTouchingStorage）。 */
    @Test
    void 畸形入参在碰库之前失败() {
        JdbcEconomyStore store = new JdbcEconomyStore(noDatabase());
        Map<String, UnaryOperator<DonationReserve>> donationBreakers = Map.of(
                "op_id 为 0", in -> new DonationReserve(0, in.playerId(), in.guildId(), in.donateId(), in.minGuildLevel(),
                        in.contributionGain(), in.fundsGain(), in.dailyLimit(), in.periodKey(), in.deadlineMs(),
                        in.leaseUntilMs(), in.leaseToken(), in.nowMs(), in.payload(), null),
                "lease token 为 0", in -> new DonationReserve(in.opId(), in.playerId(), in.guildId(), in.donateId(),
                        in.minGuildLevel(), in.contributionGain(), in.fundsGain(), in.dailyLimit(), in.periodKey(),
                        in.deadlineMs(), in.leaseUntilMs(), 0, in.nowMs(), in.payload(), null),
                "payload 为空", in -> new DonationReserve(in.opId(), in.playerId(), in.guildId(), in.donateId(),
                        in.minGuildLevel(), in.contributionGain(), in.fundsGain(), in.dailyLimit(), in.periodKey(),
                        in.deadlineMs(), in.leaseUntilMs(), in.leaseToken(), in.nowMs(), ByteString.EMPTY, null),
                "每日上限为 0", in -> new DonationReserve(in.opId(), in.playerId(), in.guildId(), in.donateId(),
                        in.minGuildLevel(), in.contributionGain(), in.fundsGain(), 0, in.periodKey(), in.deadlineMs(),
                        in.leaseUntilMs(), in.leaseToken(), in.nowMs(), in.payload(), null),
                "截止为 0 即永不中止", in -> new DonationReserve(in.opId(), in.playerId(), in.guildId(), in.donateId(),
                        in.minGuildLevel(), in.contributionGain(), in.fundsGain(), in.dailyLimit(), in.periodKey(), 0,
                        in.leaseUntilMs(), in.leaseToken(), in.nowMs(), in.payload(), null),
                "租约不晚于 now", in -> new DonationReserve(in.opId(), in.playerId(), in.guildId(), in.donateId(),
                        in.minGuildLevel(), in.contributionGain(), in.fundsGain(), in.dailyLimit(), in.periodKey(),
                        in.deadlineMs(), in.nowMs(), in.leaseToken(), in.nowMs(), in.payload(), null),
                "周期键为 0", in -> new DonationReserve(in.opId(), in.playerId(), in.guildId(), in.donateId(),
                        in.minGuildLevel(), in.contributionGain(), in.fundsGain(), in.dailyLimit(), 0, in.deadlineMs(),
                        in.leaseUntilMs(), in.leaseToken(), in.nowMs(), in.payload(), null),
                "now 为 0", in -> new DonationReserve(in.opId(), in.playerId(), in.guildId(), in.donateId(),
                        in.minGuildLevel(), in.contributionGain(), in.fundsGain(), in.dailyLimit(), in.periodKey(),
                        in.deadlineMs(), in.leaseUntilMs(), in.leaseToken(), 0, in.payload(), null));
        assertThat(goodDonation().invalidReason()).isNull();
        donationBreakers.forEach((name, breaker) -> {
            DonationReserve bad = breaker.apply(goodDonation());
            assertThat(bad.invalidReason()).as(name).isNotNull();
            assertThatThrownBy(() -> store.reserveDonation(bad, d())).as(name).isInstanceOf(IllegalArgumentException.class);
        });

        // 一次买的份数就超过周期限购：纯判断，不碰库直接拒（首次插入计数行时 upsert 拦不住 count > limit）
        ShopReserve over = new ShopReserve(1, 2, 3, 101, 6, 1, 180, 5, 20_260_921, NOW + 10_000, 7, NOW,
                ByteString.copyFrom(new byte[] {0x08}), null);
        assertThat(store.reserveShopOrder(over, d()).rejection()).isEqualTo(GuildReject.SHOP_LIMIT);
        // 份数按无符号比较：-1 = 4294967295 份
        ShopReserve huge = new ShopReserve(1, 2, 3, 101, -1, 1, 180, 5, 20_260_921, NOW + 10_000, 7, NOW,
                ByteString.copyFrom(new byte[] {0x08}), null);
        assertThat(store.reserveShopOrder(huge, d()).rejection()).isEqualTo(GuildReject.SHOP_LIMIT);

        assertThat(goodShop().invalidReason()).isNull();
        ShopReserve zeroCost = new ShopReserve(1, 2, 3, 101, 1, 1, 0, 5, 20_260_921, NOW + 10_000, 7, NOW,
                ByteString.copyFrom(new byte[] {0x08}), null);
        assertThatThrownBy(() -> store.reserveShopOrder(zeroCost, d())).as("cost 为 0 会让扣帮贡的写入自检误判")
                .isInstanceOf(IllegalArgumentException.class);
        ShopReserve mismatched = new ShopReserve(1, 2, 3, 101, 1, 1, 30, 5, 0, NOW + 10_000, 7, NOW,
                ByteString.copyFrom(new byte[] {0x08}), null);
        assertThatThrownBy(() -> store.reserveShopOrder(mismatched, d())).as("限购份数与周期键必须同为 0 或同非 0")
                .isInstanceOf(IllegalArgumentException.class);
        ShopReserve noLease = new ShopReserve(1, 2, 3, 101, 1, 1, 30, 0, 0, NOW, 7, NOW,
                ByteString.copyFrom(new byte[] {0x08}), null);
        assertThatThrownBy(() -> store.reserveShopOrder(noLease, d())).isInstanceOf(IllegalArgumentException.class);
        ShopReserve emptyPayload = new ShopReserve(1, 2, 3, 101, 1, 1, 30, 0, 0, NOW + 1, 7, NOW, ByteString.EMPTY, null);
        assertThatThrownBy(() -> store.reserveShopOrder(emptyPayload, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upgradeGuild(1, 2, 0, null, null, d())).isInstanceOf(NullPointerException.class);
    }

    /** deadline_ms = 0 在本表是「永不中止」：now 为 0 的提前截止必须在发语句之前拒绝（TestAccelerateRequiresNonZeroNow）。 */
    @Test
    void 提前截止要求now非0() {
        assertThatThrownBy(() -> JdbcEconomyStore.accelerateDonationDeadlines(null, 1, List.of(2L), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EconomyTxHooks.INSTANCE.accelerateDonationDeadlines(null, 1, List.of(2L), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ================================================================ 语句形状

    /**
     * 两条静态纪律（TestEconomyCandidateReadsTakeNoLocks，economy_repo_test.go:232）：「按二级条件找行」的候选读必须是普通读；guild_member 上
     * 本仓储拥有的锁定读 / UPDATE 必须带 FORCE INDEX (PRIMARY)。点改的定位必须是完整主键等值；点锁只许完整主键等值 + FOR UPDATE。
     */
    @Test
    void 候选读不加锁_成员语句强制走主键_点锁形状() {
        Map<String, String> plainReads = Map.of(
                "O1", JdbcEconomyStore.SELECT_ACCELERATE_CANDIDATES_HEAD + JdbcGuildStore.placeholders(2)
                        + JdbcEconomyStore.SELECT_ACCELERATE_CANDIDATES_TAIL,
                "Q1", JdbcEconomyStore.SEQ_ROW_EXISTS,
                "O4", JdbcEconomyStore.SELECT_PENDING_SEQS,
                "G11", JdbcEconomyStore.SELECT_GUILD_ZONE_LEVEL,
                "C2", JdbcEconomyStore.SELECT_DONATE_USAGE,
                "C3", JdbcEconomyStore.SELECT_SHOP_USAGE,
                "M14", JdbcEconomyStore.SELECT_MEMBER_CONTRIBUTION,
                "O6", JdbcEconomyStore.SELECT_OP_STATE,
                "O7", JdbcEconomyStore.SELECT_PENDING_OPS,
                "O8", JdbcEconomyStore.SELECT_RECENT_OPS);
        plainReads.forEach((name, sql) -> {
            String upper = sql.toUpperCase(Locale.ROOT);
            for (String clause : new String[] {"FOR UPDATE", "FOR SHARE", "LOCK IN SHARE MODE"}) {
                assertThat(upper).as("%s 是普通读，不许加锁: %s", name, sql).doesNotContain(clause);
            }
        });
        for (String sql : new String[] {JdbcEconomyStore.LOCK_MEMBER_BALANCE, JdbcEconomyStore.DEBIT_CONTRIBUTION,
                JdbcGuildStore.LOCK_MEMBER_ROLE}) {
            assertThat(sql).contains("guild_member FORCE INDEX (PRIMARY)");
        }
        assertThat(JdbcEconomyStore.ACCELERATE_DONATION_DEADLINE).contains("WHERE `op_id` = ? AND");
        assertThat(JdbcEconomyStore.LOCK_ASSET_OP).isEqualTo("SELECT `op_id` FROM guild_asset_op WHERE `op_id` = ? FOR UPDATE");
        assertThat(JdbcEconomyStore.LOCK_SEQ_ROW).endsWith("WHERE player_id = ? AND stream = ? FOR UPDATE");
        assertThat(JdbcEconomyStore.LOCK_GUILD_FOR_UPGRADE).endsWith("WHERE guild_id = ? FOR UPDATE");
        // 事务内建 seq 行必须仍是「已存在即空操作」的 INSERT IGNORE、next_seq 从 1 起（与基线 assetop.EnsureSeqRow 同义）
        assertThat(JdbcEconomyStore.ENSURE_SEQ_ROW).startsWith("INSERT IGNORE INTO guild_player_op_seq ")
                .endsWith("VALUES (?, ?, 1, ?, ?)");
        // C1 的赋值顺序不能换：updated_ms 必须先看旧 used_count
        String upsert = JdbcEconomyStore.UPSERT_COUNTER_WITH_LIMIT;
        assertThat(upsert.indexOf("updated_ms = IF(")).isLessThan(upsert.indexOf("used_count = IF("));
        assertThat(upsert.chars().filter(c -> c == '?').count()).isEqualTo(12);
        // 升级的 UPDATE 带 level 与 funds 作提交点复核
        assertThat(JdbcEconomyStore.UPGRADE_GUILD).endsWith("WHERE guild_id = ? AND level = ? AND funds >= ?");
        // 待结算按 (stream_epoch, seq) 升序、最近结果倒序：只按 seq 排会把库恢复前后两个纪元交错
        assertThat(JdbcEconomyStore.SELECT_PENDING_OPS).endsWith("ORDER BY `stream_epoch` ASC, `seq` ASC LIMIT ?");
        assertThat(JdbcEconomyStore.SELECT_RECENT_OPS).endsWith("ORDER BY `stream_epoch` DESC, `seq` DESC LIMIT ?");
    }

    /** SQL 不写 status / kind / stream / counter_kind / tx_type 的数字字面量（guild_db.proto:94；economy_repo.go:35）。 */
    @Test
    void 不写枚举数字字面量() throws java.io.IOException {
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile(
                "(status|kind|stream|counter_kind|tx_type)`?\\s*(=|IN)\\s*\\(?[0-9]");
        for (String file : new String[] {"src/main/java/com/game/guild/store/JdbcEconomyStore.java",
                "src/main/java/com/game/guild/asset/JdbcGuildAssetStore.java"}) {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            assertThat(literal.matcher(text).find()).as("%s 出现了枚举列的数字字面量", file).isFalse();
        }
    }

    // ================================================================ 结果

    @Test
    void 结果的失效与收件人() {
        Upgraded changed = new Upgraded(7, 2, true, 3, List.of(1L, 2L, 3L), false);
        assertThat(changed.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.UPGRADE, 7));
        assertThat(changed.pushRecipients()).as("全体成员除操作者").containsExactly(1L, 3L);
        Upgraded stale = new Upgraded(7, 2, false, 3, List.of(), true);
        assertThat(stale.invalidation()).as("视图过期也要失效").isEqualTo(Invalidation.of(GuildTxOp.UPGRADE, 7));
        assertThat(stale.pushRecipients()).as("没升就不推").isEmpty();
        Upgraded nothing = new Upgraded(7, 2, false, 3, List.of(), false);
        assertThat(nothing.invalidation().isEmpty()).isTrue();
        assertThat(new EconomyStore.ShopReserved(7, 1, 2, 70).invalidation())
                .isEqualTo(Invalidation.of(GuildTxOp.SHOP, 7));
    }
}
