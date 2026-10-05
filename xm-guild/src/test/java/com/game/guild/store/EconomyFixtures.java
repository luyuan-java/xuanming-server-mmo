package com.game.guild.store;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetStream;
import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.guild.asset.AssetOpResult;
import com.game.guild.asset.GuildAssetStore;
import com.game.guild.asset.JdbcGuildAssetStore;
import com.game.guild.store.EconomyStore.DonationReserve;
import com.game.guild.store.EconomyStore.ShopReserve;
import com.game.guild.store.EconomyStore.UpgradeLevel;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.TransactionType;
import com.google.protobuf.ByteString;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 经济仓储 / 资产 Store 真库用例的公共夹具（照 mmorpg economy_repo_test.go:350-748 的 econ* 助手）。建在 {@link GuildMysqlFixture} 之上
 * （同一个一次性库、同一套连接口径）；时间一律用 {@link GuildMysqlFixture#NOW} 派生的常量显式传入。
 */
public final class EconomyFixtures {

    /** 插行租约（xm.guild.asset-op.lease 缺省）。 */
    public static final long LEASE_MS = 10_000;
    /** GuildRule.asset_op_deadline_seconds × 1000。 */
    public static final long DEADLINE_MS = 600_000;
    public static final long DAY_MS = 86_400_000L;
    /** NOW 所在的游戏日键 / 周键。 */
    public static final int DAY_KEY = GameDay.dayKey(GuildMysqlFixture.NOW);
    public static final int WEEK_KEY = GameDay.weekKey(GuildMysqlFixture.NOW);
    public static final int DEBIT = AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE;
    public static final int CREDIT = AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE;
    /** 计数种类的库值。 */
    public static final int COUNTER_DONATE = com.game.guild.store.pb.GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_DONATE_VALUE;
    public static final int COUNTER_SHOP = com.game.guild.store.pb.GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_SHOP_VALUE;
    public static final int COUNTER_ACTIVITY =
            com.game.guild.store.pb.GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_ACTIVITY_VALUE;

    /** GuildLevel 的真实数据（config-data/tables；guild-economy-spec §0.5）：1→2 扣 20000、上限 35。 */
    private static final Map<Integer, UpgradeLevel> LEVELS = Map.of(
            1, new UpgradeLevel(20_000, 30), 2, new UpgradeLevel(50_000, 35), 3, new UpgradeLevel(100_000, 40),
            4, new UpgradeLevel(180_000, 45), 5, new UpgradeLevel(300_000, 50), 6, new UpgradeLevel(460_000, 60),
            7, new UpgradeLevel(680_000, 70), 8, new UpgradeLevel(960_000, 80), 9, new UpgradeLevel(1_300_000, 90),
            10, new UpgradeLevel(0, 100));

    public static final EconomyStore.UpgradeLevels LEVEL_LOOKUP = LEVELS::get;

    private EconomyFixtures() {
    }

    // ================================================================ 组件

    /** 经济仓储（请求路径，GuildTx）。 */
    public static JdbcEconomyStore economy(GuildMysqlFixture db, GuildTxListener listener) {
        return new JdbcEconomyStore(db.tx(listener));
    }

    /** 后台事务基座。 */
    public static BackgroundTx backgroundTx(GuildMysqlFixture db, BackgroundTx.Listener listener) {
        return new BackgroundTx(db.dataSource::getConnection, 10, listener);
    }

    /** 资产 Store（后台写走 BackgroundTx）。 */
    public static JdbcGuildAssetStore assets(GuildMysqlFixture db, BackgroundTx.Listener background,
                                             GuildAssetStore.Listener listener) {
        return new JdbcGuildAssetStore(backgroundTx(db, background), listener);
    }

    /** 每次调用一个宽裕的截止（关心的是 SQL 行为，不是超时）。 */
    public static Deadline roomy() {
        return Deadline.after(30_000);
    }

    // ================================================================ 入参

    public static ByteString donatePayload(long amount) {
        return AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0).setAmount(amount))
                .build().toByteString();
    }

    public static ByteString shopPayload(int itemId, int count) {
        return AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(itemId).setCount(count)).build()
                .toByteString();
    }

    /** 一笔「银两小捐」（GuildDonate 第 1 行）：扣 10000、帮贡 10、资金 1000、每日 5 次。令牌 = op_id | 2^62。 */
    public static DonationReserve donation(long opId, long playerId, long guildId, long now) {
        return donation(opId, playerId, guildId, now, 5);
    }

    public static DonationReserve donation(long opId, long playerId, long guildId, long now, int dailyLimit) {
        return new DonationReserve(opId, playerId, guildId, 1, 1, 10, 1000, dailyLimit, DAY_KEY, now + DEADLINE_MS,
                now + LEASE_MS, opId | (1L << 62), now, donatePayload(10_000), null);
    }

    /** 一笔商品 101 的兑换；limit &gt; 0 时按日限购。 */
    public static ShopReserve shopOrder(long opId, long playerId, long guildId, int count, long cost, int limit, long now) {
        return new ShopReserve(opId, playerId, guildId, 101, count, 1, cost, limit, limit > 0 ? DAY_KEY : 0, now + LEASE_MS,
                opId | (1L << 62), now, shopPayload(15, 5 * count), null);
    }

    /** 一行商店指令的骨架（未决、已到期、guild 7700）；调用方再改。 */
    public static GuildAssetOpRow.Builder shopRecord(long opId, long playerId, long epoch, long seq) {
        long now = GuildMysqlFixture.NOW;
        return GuildAssetOpRow.newBuilder().setOpId(opId).setPlayerId(playerId).setStream(CREDIT).setSeq(seq).setGuildId(7700)
                .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                .setNextAttemptMs(now - 10).setPayload(shopPayload(15, 5)).setRefId(101).setRefCount(1)
                .setCreatedMs(now - 1000).setUpdatedMs(now - 1000).setTxType(TransactionType.TX_GUILD_SHOP_VALUE)
                .setStreamEpoch(epoch);
    }

    /** 一行捐献指令的骨架（未决、截止在 NOW 之后）；调用方再改。 */
    public static GuildAssetOpRow.Builder donateRecord(long opId, long playerId, long guildId, long epoch, long seq,
                                                       ByteString payload) {
        long now = GuildMysqlFixture.NOW;
        return GuildAssetOpRow.newBuilder().setOpId(opId).setPlayerId(playerId).setStream(DEBIT).setSeq(seq).setGuildId(guildId)
                .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                .setNextAttemptMs(now + LEASE_MS).setDeadlineMs(now + DEADLINE_MS).setPayload(payload).setRefId(1).setRefCount(1)
                .setPeriodKey(DAY_KEY).setContributionDelta(10).setFundsDelta(1000).setCreatedMs(now).setUpdatedMs(now)
                .setTxType(TransactionType.TX_GUILD_DONATE_VALUE).setStreamEpoch(epoch);
    }

    /** scene 已落盘的全额应用答复。 */
    public static AssetOpResult applied() {
        return new AssetOpResult(com.game.api.proto.AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true, false, false);
    }

    /** scene 已落盘的永久拒绝答复（货币不足）。 */
    public static AssetOpResult rejected() {
        return new AssetOpResult(com.game.api.proto.AssetOutcome.ASSET_OUTCOME_REJECTED, 27000, true, false, false);
    }

    /** scene 已落盘的中止占位答复（REJECTED、reason 0）。 */
    public static AssetOpResult abortedPlaceholder() {
        return new AssetOpResult(com.game.api.proto.AssetOutcome.ASSET_OUTCOME_REJECTED, 0, true, false, false);
    }

    // ================================================================ 夹具读写（直接 SQL，刻意绕开被测方法）

    public static void setContribution(GuildMysqlFixture db, long guildId, long playerId, long total, long balance)
            throws SQLException {
        db.exec("UPDATE guild_member SET contribution_total = ?, contribution_balance = ? WHERE guild_id = ? AND player_id = ?",
                total, balance, guildId, playerId);
    }

    public static void setFunds(GuildMysqlFixture db, long guildId, long funds) throws SQLException {
        db.exec("UPDATE guild SET funds = ? WHERE guild_id = ?", funds, guildId);
    }

    public static long funds(GuildMysqlFixture db, long guildId) throws SQLException {
        return db.queryU64("SELECT funds FROM guild WHERE guild_id = ?", guildId);
    }

    /** [total, balance]；成员行不在返回 null。 */
    public static long[] contribution(GuildMysqlFixture db, long guildId, long playerId) throws SQLException {
        try (Connection c = db.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT contribution_total, contribution_balance FROM guild_member WHERE guild_id = ? AND player_id = ?")) {
            ps.setObject(1, GuildJdbc.bindValue(guildId));
            ps.setObject(2, GuildJdbc.bindValue(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[] {GuildJdbc.u64(rs, 1), GuildJdbc.u64(rs, 2)} : null;
            }
        }
    }

    /** 一行计数的 used_count；行不存在返回 null。 */
    public static Long counterUsed(GuildMysqlFixture db, long playerId, int kind, int refId, int periodKey) throws SQLException {
        return db.queryU64("SELECT used_count FROM guild_daily_counter WHERE player_id = ? AND counter_kind = ? AND ref_id = ?"
                + " AND period_key = ?", playerId, kind, refId, periodKey);
    }

    public static void insertCounter(GuildMysqlFixture db, long playerId, int kind, int refId, int periodKey, int used)
            throws SQLException {
        db.exec("INSERT INTO guild_daily_counter (player_id, counter_kind, ref_id, period_key, used_count, updated_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?)", playerId, kind, refId, periodKey, used, GuildMysqlFixture.NOW);
    }

    /** 读一整行；不存在返回 null。 */
    public static GuildAssetOpRow record(GuildMysqlFixture db, long opId) throws SQLException {
        try (Connection c = db.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + AssetOpColumns.COLUMNS + " FROM guild_asset_op WHERE op_id = ?")) {
            ps.setObject(1, GuildJdbc.bindValue(opId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? AssetOpColumns.scan(rs) : null;
            }
        }
    }

    public static boolean opExists(GuildMysqlFixture db, long opId) throws SQLException {
        return db.count("SELECT COUNT(*) FROM guild_asset_op WHERE op_id = ?", opId) == 1;
    }

    /** 走生产的 INSERT 语句与参数顺序插一行指令。 */
    public static void insertOp(GuildMysqlFixture db, GuildAssetOpRow row) throws SQLException {
        db.exec(AssetOpColumns.INSERT, AssetOpColumns.insertArgs(row));
    }

    /** 一条多行 INSERT 插一批指令（给需要成百上千行夹具的并发回归用；每批 ≤ 2000 行）。 */
    public static void insertOpsBulk(GuildMysqlFixture db, List<GuildAssetOpRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String tuple = "(" + JdbcGuildStore.placeholders(AssetOpColumns.COUNT) + ")";
        StringBuilder sql = new StringBuilder("INSERT INTO guild_asset_op (" + AssetOpColumns.COLUMNS + ") VALUES ");
        sql.append(String.join(", ", Collections.nCopies(rows.size(), tuple)));
        List<Object> args = new ArrayList<>(rows.size() * AssetOpColumns.COUNT);
        for (GuildAssetOpRow row : rows) {
            Collections.addAll(args, AssetOpColumns.insertArgs(row));
        }
        db.exec(sql.toString(), args.toArray());
    }

    /** seq 行的 next_seq；行不存在返回 null。 */
    public static Long nextSeq(GuildMysqlFixture db, long playerId, int stream) throws SQLException {
        return db.queryU64("SELECT next_seq FROM guild_player_op_seq WHERE player_id = ? AND stream = ?", playerId, stream);
    }

    /** 走生产的建行语句建 seq 行（纪元 = epoch）。 */
    public static void ensureSeqRow(GuildMysqlFixture db, long playerId, int stream, long epoch) throws SQLException {
        db.exec(JdbcEconomyStore.ENSURE_SEQ_ROW, playerId, stream, epoch, epoch);
    }

    public static long opCountInRange(GuildMysqlFixture db, long lo, long hi) throws SQLException {
        return db.count("SELECT COUNT(*) FROM guild_asset_op WHERE op_id BETWEEN ? AND ?", lo, hi);
    }

    // ================================================================ 记录器

    /** 后台事务的重跑记录器：锁序回归断言「被重跑吸收掉的死锁」为零（同 {@link GuildMysqlFixture.Recorder}）。 */
    public static final class BackgroundRecorder implements BackgroundTx.Listener {
        public final List<String> deadlocks = Collections.synchronizedList(new ArrayList<>());
        public final List<String> lockWaits = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void deadlockObserved(BackgroundTx.Op op) {
            deadlocks.add(op.label());
        }

        @Override
        public void lockWaitTimeout(BackgroundTx.Op op) {
            lockWaits.add(op.label());
        }

        public void assertNoDeadlocks(String what) {
            org.assertj.core.api.Assertions.assertThat(deadlocks)
                    .as("%s：后台写出现了被重跑吸收掉的死锁（返回值看不出来），必须为 0 次", what).isEmpty();
        }
    }

    /** 资产 Store 提交后钩子的记录器。 */
    public static final class AssetEvents implements GuildAssetStore.Listener {
        public final List<Invalidation> invalidations = Collections.synchronizedList(new ArrayList<>());
        public final List<GuildAssetStore.Orphan> orphans = Collections.synchronizedList(new ArrayList<>());
        public final List<GuildAssetStore.FinalizedOp> finalized = Collections.synchronizedList(new ArrayList<>());
        public final Map<GuildAssetStore.CleanupTable, Long> cleaned = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void invalidate(Invalidation invalidation) {
            invalidations.add(invalidation);
        }

        @Override
        public void orphan(GuildAssetStore.Orphan orphan) {
            orphans.add(orphan);
        }

        @Override
        public void cleanupDeleted(GuildAssetStore.CleanupTable table, long n) {
            cleaned.merge(table, n, Long::sum);
        }

        @Override
        public void finalized(GuildAssetStore.FinalizedOp op) {
            finalized.add(op);
        }
    }
}
