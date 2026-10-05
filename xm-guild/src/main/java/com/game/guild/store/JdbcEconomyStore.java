package com.game.guild.store;

import com.game.api.proto.AssetStream;
import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.GuildStore.ZoneFence;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.guild.store.pb.GuildDailyCounterKind;
import com.game.proto.TransactionType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link EconomyStore} 的 MySQL 实现（语句逐字对齐 mmorpg economy_repo.go 与 assetop/seq.go 的 AllocateSeq；编号接续 guild-spec §1.9，
 * 见 guild-economy-spec §1.4 的 SQL 目录）。
 *
 * <p><b>锁序</b>（economy_repo.go:15-34；guild-economy-spec §1.3）：全序 G → S → M → A → Q → O → C → P。
 * <ul>
 *   <li>T-D / T-S：G 普通读（只提供 zone 与等级）→ M 本人成员行 X → Q(p, stream) 缺行时在成员行锁之下建（死锁复核 C2）→ Q FOR UPDATE →
 *       本纪元未决行<b>普通读</b>（RC 语句级快照，不加锁）→ 插新 O 行 → C 带上限 upsert（只写请求开头 start 所在周期的行）→（T-S）回到已锁的
 *       M 行扣帮贡（同一行再次 UPDATE 不算新加锁）；</li>
 *   <li>T-U：G FOR UPDATE → M 操作者 X；</li>
 *   <li>离帮 / 被踢 / 解散的提前截止（{@link #accelerateDonationDeadlines}，O 钩子）：候选普通读 → 按 op_id 无符号升序逐行「O2 主键点锁 →
 *       O3 带复核点改」。</li>
 * </ul>
 * 锁定语句一律完整主键等值点操作；guild_member 上的锁定 SELECT 与 UPDATE 带 {@code FORCE INDEX (PRIMARY)}（WHERE 同时钉死了
 * uk_guild_member，被规划到唯一键就是「二级 → 主键」取锁，与离帮按主键删成员行反序）。EXPLAIN 回归钉在 EconomyLockPlanMysqlTest。
 *
 * <p><b>守卫不变量</b>：插 PENDING DONATE 行之前必先持本人成员行 X 锁（提前截止候选集因此完整，economy_repo.go:547-550）；seq 行的每个
 * 建行者都先持同一成员行（C2）；计数行的每个悲观写者都先持同一 (p, stream) 的 Q 行（C6）。今后新增调 {@link #allocateSeq} 的路径
 * （4.6 活动发奖）也必须先锁同一成员行。预留事务必须是 READ COMMITTED（{@link GuildTx} 固定 RC）：未决行普通读只在 RC 的语句级快照下
 * 看得见前一个分配者已提交的行（seq.go:145-168）。
 *
 * <p>连接池必须 {@code useAffectedRows=true}：C1 的 1 / 2 / 0、写入自检、「加 0 跳过」都依赖它（启动期由
 * {@link GuildStartupChecks#checkAffectedRowsSemantics} 探测）。SQL 不写 status / kind / stream / counter_kind / tx_type 的数字字面量，
 * 一律绑定生成常量。线程安全；全部方法阻塞。
 */
public final class JdbcEconomyStore implements EconomyStore {

    private static final int STREAM_GUILD_DEBIT = AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE;
    private static final int STREAM_GUILD_CREDIT = AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE;
    private static final int STATUS_PENDING = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE;
    private static final int KIND_DONATE = GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE_VALUE;
    private static final int COUNTER_DONATE = GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_DONATE_VALUE;
    private static final int COUNTER_SHOP = GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_SHOP_VALUE;

    // ---------------------------------------------------------------- G：guild
    /** G11：T-D / T-S 的事务内<b>普通读</b>（锁 G 会让同帮所有捐献在一把行锁上排队）。无行 → GUILD_GONE。 */
    static final String SELECT_GUILD_ZONE_LEVEL = "SELECT zone_id, level FROM guild WHERE guild_id = ?";
    /** G12：T-U 锁帮会行。无行 → GUILD_GONE。 */
    static final String LOCK_GUILD_FOR_UPGRADE = "SELECT level, funds, zone_id FROM guild WHERE guild_id = ? FOR UPDATE";
    /** G13：升一级、扣当前等级行的花费、上限取下一级行（恰好一行；条件带 level 与 funds 作提交点复核）。 */
    static final String UPGRADE_GUILD = "UPDATE guild SET level = ?, funds = funds - ?, max_members = ?"
            + " WHERE guild_id = ? AND level = ? AND funds >= ?";

    // ---------------------------------------------------------------- M：guild_member
    /** M12：T-S 锁本人成员行读可用帮贡。无行 → NOT_MEMBER。 */
    static final String LOCK_MEMBER_BALANCE = "SELECT contribution_balance FROM guild_member FORCE INDEX (PRIMARY)"
            + " WHERE guild_id = ? AND player_id = ? FOR UPDATE";
    /** M13：扣帮贡（影响 ≠ 1 → CONTRIBUTION_INSUFFICIENT）。 */
    static final String DEBIT_CONTRIBUTION = "UPDATE guild_member FORCE INDEX (PRIMARY) SET contribution_balance = contribution_balance - ?"
            + " WHERE guild_id = ? AND player_id = ? AND contribution_balance >= ?";
    /** M14：直读帮贡两列（不走缓存）。 */
    static final String SELECT_MEMBER_CONTRIBUTION = "SELECT contribution_total, contribution_balance FROM guild_member"
            + " WHERE guild_id = ? AND player_id = ?";

    // ---------------------------------------------------------------- Q：guild_player_op_seq
    /** Q1：建 seq 行前的普通读（完整主键等值、不带锁定子句；行建出后永不删除，常态命中、不发写）。 */
    static final String SEQ_ROW_EXISTS = "SELECT 1 FROM guild_player_op_seq WHERE `player_id` = ? AND `stream` = ?";
    /**
     * Q2：事务内建 seq 行（成员行锁之下）。与基线 assetop.EnsureSeqRow 的建行语句逐字同义：next_seq 从 1 起、纪元 = 建行时刻毫秒、
     * 已存在即空操作不改纪元（seq.go:93）。
     */
    static final String ENSURE_SEQ_ROW = "INSERT IGNORE INTO guild_player_op_seq (player_id, stream, next_seq, epoch, updated_ms)"
            + " VALUES (?, ?, 1, ?, ?)";
    /** Q3：AllocateSeq 锁 seq 行。 */
    static final String LOCK_SEQ_ROW = "SELECT next_seq, epoch FROM guild_player_op_seq WHERE player_id = ? AND stream = ? FOR UPDATE";
    /** Q4：推进 next_seq（影响必须为 1）。 */
    static final String BUMP_SEQ = "UPDATE guild_player_op_seq SET next_seq = next_seq + 1, updated_ms = ? WHERE player_id = ? AND stream = ?";

    // ---------------------------------------------------------------- O：guild_asset_op
    /**
     * O4：AllocateSeq 读本纪元未决 seq。<b>普通读，不加锁</b>（seq.go:136-168：加锁读经 idx_2 是「二级 → 主键」，与终结的主键 CAS 在同一 op 行上
     * 反序成环；RC 下普通读看得见前一个分配者已提交的行）。LIMIT = MaxPending + 1，区分「刚好到上限」与「超了」。
     */
    static final String SELECT_PENDING_SEQS = "SELECT seq FROM guild_asset_op WHERE player_id = ? AND stream = ? AND stream_epoch = ?"
            + " AND status = ? ORDER BY seq LIMIT ?";
    /**
     * O2：op 行的主键点锁（死锁复核 C5 / V1）。<b>只许</b>是完整主键等值 + FOR UPDATE、不带任何复核条件：TiDB 只有这种形状才走 Point_Get
     * 快路径（PRIMARY key → 行 key），同一 op 行上的全部悲观写者（提前截止、重排、毒行、终结、人工终结、清理）先在它上面排队；
     * 复核条件留在随后的点改 / 点删里。MySQL 下是 PRIMARY const，锁集不变。读不到 = 行已被清理。
     */
    public static final String LOCK_ASSET_OP = "SELECT `op_id` FROM guild_asset_op WHERE `op_id` = ? FOR UPDATE";
    /** O1 前缀：提前截止候选普通读（IN 按 100 分块，由调用方拼占位符；不带任何锁定子句）。 */
    static final String SELECT_ACCELERATE_CANDIDATES_HEAD = "SELECT `op_id` FROM guild_asset_op WHERE `player_id` IN (";
    /** O1 后缀：绑定 (GUILD_DEBIT, PENDING, G, DONATE, now)。 */
    static final String SELECT_ACCELERATE_CANDIDATES_TAIL = ") AND `stream` = ? AND `status` = ? AND `guild_id` = ? AND `kind` = ?"
            + " AND `deadline_ms` > ? ORDER BY `op_id`";
    /**
     * O3：完整主键等值点改。{@code status = PENDING AND deadline_ms > now} 是提交点复核：候选读之后已被终结 / 已被提前（或本语句重放）
     * 影响 0 行，是 no-op 而不是错误。只改 deadline_ms / next_attempt_ms / updated_ms，<b>不抢租约</b>；LEAST 把退避中的行拉回「现在」。
     */
    static final String ACCELERATE_DONATION_DEADLINE = "UPDATE guild_asset_op"
            + " SET `deadline_ms` = ?, `next_attempt_ms` = LEAST(`next_attempt_ms`, ?), `updated_ms` = ?"
            + " WHERE `op_id` = ? AND `status` = ? AND `deadline_ms` > ?";
    /** O6：同步投递后回读单行状态。 */
    static final String SELECT_OP_STATE = "SELECT `status`, `last_reason`, `reason_tip_id` FROM guild_asset_op WHERE `op_id` = ?";
    /** O7：待结算（idx_2 的 (player_id, stream) 前缀；按 (stream_epoch, seq) 排：纪元进唯一键之后，只按 seq 排会把两个纪元交错）。 */
    static final String SELECT_PENDING_OPS = "SELECT " + AssetOpColumns.COLUMNS + " FROM guild_asset_op"
            + " WHERE `player_id` = ? AND `stream` = ? AND `status` = ? ORDER BY `stream_epoch` ASC, `seq` ASC LIMIT ?";
    /** O8：最近结果（沿 uk_guild_asset_op 倒序，最多扫 limit 行，与历史行数无关；含 PENDING）。 */
    static final String SELECT_RECENT_OPS = "SELECT " + AssetOpColumns.COLUMNS + " FROM guild_asset_op"
            + " WHERE `player_id` = ? AND `stream` = ? ORDER BY `stream_epoch` DESC, `seq` DESC LIMIT ?";

    // ---------------------------------------------------------------- C：guild_daily_counter
    /**
     * C1：计数行带上限 upsert（X-13，取代「锁不存在的行再插入」）。参数 (p, kind, ref, period, n, now, n, limit, now, n, limit, n)。
     * 影响行数：1 = 新插入、2 = 已累加、<b>0 = 达上限</b>（值没变；依赖 useAffectedRows=true）。<b>赋值顺序不能换</b>：MySQL 按从左到右求值，
     * updated_ms 在前，它的条件读到的 used_count 仍是旧值（economy_repo.go:83-95）。
     */
    static final String UPSERT_COUNTER_WITH_LIMIT = "INSERT INTO guild_daily_counter"
            + " (player_id, counter_kind, ref_id, period_key, used_count, updated_ms) VALUES (?, ?, ?, ?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE"
            + " updated_ms = IF(used_count + ? <= ?, ?, updated_ms),"
            + " used_count = IF(used_count + ? <= ?, used_count + ?, used_count)";
    /** C2：本人某游戏日的捐献用量。 */
    static final String SELECT_DONATE_USAGE = "SELECT ref_id, used_count FROM guild_daily_counter"
            + " WHERE player_id = ? AND counter_kind = ? AND period_key = ?";
    /** C3：本人日键与周键的商店用量（两个数值域不相交，一条 IN）。 */
    static final String SELECT_SHOP_USAGE = "SELECT ref_id, period_key, used_count FROM guild_daily_counter"
            + " WHERE player_id = ? AND counter_kind = ? AND period_key IN (?, ?)";

    /** AllocateSeq 的结果：纪元 + 流水号，两个一起才能唯一定位一笔业务。 */
    record SeqAlloc(long epoch, long seq) {
    }

    private final GuildTx tx;

    public JdbcEconomyStore(GuildTx tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    // ================================================================ T-D 捐献预留

    @Override
    public TxOutcome<Reserved> reserveDonation(DonationReserve in, Deadline deadline) {
        String invalid = in.invalidReason();
        if (invalid != null) {
            throw new IllegalArgumentException("reserve donation op " + id(in.opId()) + ": " + invalid);
        }
        ZoneFence fence = in.fence() == null ? ZoneFence.OPEN : in.fence();
        return tx.run(GuildTxOp.DONATE, deadline, t -> {
            int[] zoneLevel = readGuildZoneLevel(t, in.guildId());
            if (zoneLevel == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            if (Integer.compareUnsigned(zoneLevel[1], in.minGuildLevel()) < 0) {
                return TxOutcome.reject(GuildReject.LEVEL_TOO_LOW);
            }
            if (JdbcGuildStore.fenceRejects(fence, zoneLevel[0], in.guildId())) {
                return TxOutcome.reject(GuildReject.ZONE_MERGING);
            }
            // 本人成员行加锁：同一玩家的并发捐献在这里串行，也让离帮事务（删这一行）与本事务互斥——离帮先提交，这里读不到行；
            // 本事务先提交，离帮里的提前截止看得见这一行。这是提前截止候选普通读「完整」的前提（economy_repo.go:547-550）。
            if (t.one(JdbcGuildStore.LOCK_MEMBER_ROLE, rs -> GuildJdbc.u32(rs, 1), in.guildId(), in.playerId()) == null) {
                return TxOutcome.reject(GuildReject.NOT_MEMBER);
            }
            ensureSeqRowTx(t, in.playerId(), STREAM_GUILD_DEBIT, in.nowMs());
            SeqAlloc alloc = allocateSeq(t, in.playerId(), STREAM_GUILD_DEBIT, in.nowMs());
            if (alloc == null) {
                return TxOutcome.reject(GuildReject.TOO_MANY_PENDING);
            }
            GuildAssetOpRow row = GuildAssetOpRow.newBuilder()
                    .setOpId(in.opId())
                    .setPlayerId(in.playerId())
                    .setStream(STREAM_GUILD_DEBIT)
                    .setSeq(alloc.seq())
                    .setGuildId(in.guildId())
                    .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE)
                    .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                    .setNextAttemptMs(in.leaseUntilMs()) // 同步投递握着租约；租约到期前循环不会碰它
                    .setDeadlineMs(in.deadlineMs())
                    .setPayload(in.payload())
                    .setRefId(in.donateId())
                    .setRefCount(1) // 捐献恒 1 次
                    .setPeriodKey(in.periodKey())
                    .setContributionDelta(in.contributionGain())
                    .setFundsDelta(in.fundsGain())
                    .setCreatedMs(in.nowMs())
                    .setUpdatedMs(in.nowMs())
                    .setLeaseUntilMs(in.leaseUntilMs())
                    .setLeaseToken(in.leaseToken())
                    .setTxType(TransactionType.TX_GUILD_DONATE_VALUE)
                    .setStreamEpoch(alloc.epoch())
                    .build();
            t.update(AssetOpColumns.INSERT, AssetOpColumns.insertArgs(row));
            if (upsertCounterWithLimit(t, in.playerId(), COUNTER_DONATE, in.donateId(), in.periodKey(), 1, in.dailyLimit(),
                    in.nowMs())) {
                return TxOutcome.reject(GuildReject.DONATE_LIMIT);
            }
            return TxOutcome.ok(new Reserved(alloc.seq(), alloc.epoch()));
        });
    }

    // ================================================================ T-S 兑换预留

    @Override
    public TxOutcome<ShopReserved> reserveShopOrder(ShopReserve in, Deadline deadline) {
        String invalid = in.invalidReason();
        if (invalid != null) {
            throw new IllegalArgumentException("reserve shop order op " + id(in.opId()) + ": " + invalid);
        }
        // 纯判断先做：一次买的份数就超过周期限购，不必碰库。首次插入计数行时 upsert 不会拦截 count > limit（VALUES 直接写入），
        // 所以这一刀不能省（economy_repo.go:660-664；它在发号之后，会白烧一个号——照搬，EN1 不采纳）。
        if (in.limitCount() != 0 && Integer.compareUnsigned(in.count(), in.limitCount()) > 0) {
            return TxOutcome.reject(GuildReject.SHOP_LIMIT);
        }
        ZoneFence fence = in.fence() == null ? ZoneFence.OPEN : in.fence();
        return tx.run(GuildTxOp.SHOP, deadline, t -> {
            int[] zoneLevel = readGuildZoneLevel(t, in.guildId());
            if (zoneLevel == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            if (Integer.compareUnsigned(zoneLevel[1], in.requiredGuildLevel()) < 0) {
                return TxOutcome.reject(GuildReject.LEVEL_TOO_LOW);
            }
            if (JdbcGuildStore.fenceRejects(fence, zoneLevel[0], in.guildId())) {
                return TxOutcome.reject(GuildReject.ZONE_MERGING);
            }
            Long balance = t.one(LOCK_MEMBER_BALANCE, rs -> GuildJdbc.u64(rs, 1), in.guildId(), in.playerId());
            if (balance == null) {
                return TxOutcome.reject(GuildReject.NOT_MEMBER);
            }
            if (Long.compareUnsigned(balance, in.cost()) < 0) {
                return TxOutcome.reject(GuildReject.CONTRIBUTION_INSUFFICIENT);
            }
            ensureSeqRowTx(t, in.playerId(), STREAM_GUILD_CREDIT, in.nowMs());
            SeqAlloc alloc = allocateSeq(t, in.playerId(), STREAM_GUILD_CREDIT, in.nowMs());
            if (alloc == null) {
                return TxOutcome.reject(GuildReject.TOO_MANY_PENDING);
            }
            GuildAssetOpRow row = GuildAssetOpRow.newBuilder()
                    .setOpId(in.opId())
                    .setPlayerId(in.playerId())
                    .setStream(STREAM_GUILD_CREDIT)
                    .setSeq(alloc.seq())
                    .setGuildId(in.guildId())
                    .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP)
                    .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                    .setNextAttemptMs(in.leaseUntilMs())
                    .setDeadlineMs(0) // R7：物品属于玩家，商店指令永不中止
                    .setPayload(in.payload())
                    .setRefId(in.goodsId())
                    .setRefCount(in.count())
                    .setPeriodKey(in.periodKey())
                    .setContributionDelta(in.cost()) // 已扣帮贡；永久拒绝时按它退回
                    .setCreatedMs(in.nowMs())
                    .setUpdatedMs(in.nowMs())
                    .setLeaseUntilMs(in.leaseUntilMs())
                    .setLeaseToken(in.leaseToken())
                    .setTxType(TransactionType.TX_GUILD_SHOP_VALUE)
                    .setStreamEpoch(alloc.epoch())
                    .build();
            t.update(AssetOpColumns.INSERT, AssetOpColumns.insertArgs(row));
            if (in.limitCount() != 0 && upsertCounterWithLimit(t, in.playerId(), COUNTER_SHOP, in.goodsId(), in.periodKey(),
                    in.count(), in.limitCount(), in.nowMs())) {
                return TxOutcome.reject(GuildReject.SHOP_LIMIT);
            }
            // 行已锁、余额已判，这条理论上必中；不中说明锁内读到的行在写的时候变了，不能提交一份「扣了个寂寞」的订单
            if (t.update(DEBIT_CONTRIBUTION, in.cost(), in.guildId(), in.playerId(), in.cost()) != 1) {
                return TxOutcome.reject(GuildReject.CONTRIBUTION_INSUFFICIENT);
            }
            return TxOutcome.ok(new ShopReserved(in.guildId(), alloc.seq(), alloc.epoch(), balance - in.cost()));
        });
    }

    // ================================================================ T-U 升级

    @Override
    public TxOutcome<Upgraded> upgradeGuild(long guildId, long playerId, int expectedLevel, UpgradeLevels levels,
                                            ZoneFence fence, Deadline deadline) {
        Objects.requireNonNull(levels, "levels");
        ZoneFence zoneFence = fence == null ? ZoneFence.OPEN : fence;
        return tx.run(GuildTxOp.UPGRADE, deadline, t -> {
            long[] guild = t.one(LOCK_GUILD_FOR_UPGRADE,
                    rs -> new long[] {GuildJdbc.u32(rs, 1), GuildJdbc.u64(rs, 2), GuildJdbc.u32(rs, 3)}, guildId);
            if (guild == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            int level = (int) guild[0];
            long funds = guild[1];
            int zoneId = (int) guild[2];
            Integer role = t.one(JdbcGuildStore.LOCK_MEMBER_ROLE, rs -> GuildJdbc.u32(rs, 1), guildId, playerId);
            if (role == null) {
                return TxOutcome.reject(GuildReject.NOT_MEMBER);
            }
            // 比 Rank 不比 role 原值：role 编码不连续，role >= OFFICER 会把未知编码一起放进来（economy_repo.go:849-852）
            if (!GuildRoles.rank(role).atLeast(GuildRoles.Rank.OFFICER)) {
                return TxOutcome.reject(GuildReject.RANK_TOO_LOW);
            }
            if (JdbcGuildStore.fenceRejects(zoneFence, zoneId, guildId)) {
                return TxOutcome.reject(GuildReject.ZONE_MERGING);
            }
            if (expectedLevel != 0 && expectedLevel != level) {
                // 调用方看到的是过期快照（典型：上次升级已提交但 COMMIT 回执丢失）：不改任何行、提交；结果带 staleView 让服务失效缓存
                return TxOutcome.ok(new Upgraded(guildId, playerId, false, level, List.of(), true));
            }
            UpgradeLevel current = levels.find(level);
            if (current == null) {
                return TxOutcome.reject(GuildReject.LEVEL_CONFIG_MISSING);
            }
            if (current.upgradeCostFunds() == 0) {
                return TxOutcome.reject(GuildReject.MAX_LEVEL);
            }
            UpgradeLevel next = levels.find(level + 1);
            if (next == null) {
                return TxOutcome.reject(GuildReject.LEVEL_CONFIG_MISSING);
            }
            long cost = current.upgradeCostFunds();
            if (Long.compareUnsigned(funds, cost) < 0) {
                return TxOutcome.reject(GuildReject.FUNDS_INSUFFICIENT);
            }
            t.updateExactlyOne("帮会 " + id(guildId) + " 从 " + Integer.toUnsignedString(level) + " 级升级", UPGRADE_GUILD,
                    level + 1, cost, next.maxMembers(), guildId, level, cost);
            List<Long> members = t.ids(JdbcGuildStore.SELECT_MEMBER_IDS, guildId);
            return TxOutcome.ok(new Upgraded(guildId, playerId, true, level + 1, members, false));
        });
    }

    // ================================================================ 读

    @Override
    public Map<Integer, Integer> donateUsage(long playerId, int dayKey, Deadline deadline) {
        return tx.read(readBudget(deadline), db -> {
            Map<Integer, Integer> out = new HashMap<>();
            db.forEach(SELECT_DONATE_USAGE, rs -> new int[] {GuildJdbc.u32(rs, 1), GuildJdbc.u32(rs, 2)},
                    r -> out.put(r[0], r[1]), playerId, COUNTER_DONATE, dayKey);
            return out;
        });
    }

    @Override
    public Map<ShopUsageKey, Integer> shopUsage(long playerId, int dayKey, int weekKey, Deadline deadline) {
        return tx.read(readBudget(deadline), db -> {
            Map<ShopUsageKey, Integer> out = new HashMap<>();
            db.forEach(SELECT_SHOP_USAGE,
                    rs -> new int[] {GuildJdbc.u32(rs, 1), GuildJdbc.u32(rs, 2), GuildJdbc.u32(rs, 3)},
                    r -> out.put(new ShopUsageKey(r[0], r[1]), r[2]), playerId, COUNTER_SHOP, dayKey, weekKey);
            return out;
        });
    }

    @Override
    public Optional<Contribution> memberContribution(long guildId, long playerId, Deadline deadline) {
        return Optional.ofNullable(tx.read(readBudget(deadline), db -> db.one(SELECT_MEMBER_CONTRIBUTION,
                rs -> new Contribution(GuildJdbc.u64(rs, 1), GuildJdbc.u64(rs, 2)), guildId, playerId)));
    }

    @Override
    public Optional<OpState> opState(long opId, Deadline deadline) {
        return Optional.ofNullable(tx.read(readBudget(deadline), db -> db.one(SELECT_OP_STATE,
                rs -> new OpState(statusOf(rs.getInt(1)), GuildJdbc.u32(rs, 2), GuildJdbc.u32(rs, 3)), opId)));
    }

    @Override
    public List<GuildAssetOpRow> pendingOps(long playerId, int stream, int limit, Deadline deadline) {
        if (limit <= 0) {
            return List.of();
        }
        return tx.read(readBudget(deadline), db -> db.list(SELECT_PENDING_OPS, AssetOpColumns::scan,
                playerId, stream, STATUS_PENDING, limit));
    }

    @Override
    public List<GuildAssetOpRow> recentOps(long playerId, int stream, int limit, Deadline deadline) {
        if (limit <= 0) {
            return List.of();
        }
        return tx.read(readBudget(deadline), db -> db.list(SELECT_RECENT_OPS, AssetOpColumns::scan, playerId, stream, limit));
    }

    // ================================================================ 离帮 / 被踢 / 解散提前截止（O 钩子）

    /**
     * 把这些玩家在本帮的<b>未决捐献</b>截止提前到 now（accelerateDonationDeadlines，economy_repo.go:370-456；guild-economy-spec §2.9、§6.2）。
     *
     * <p>调用方契约：在删成员行与删申请<b>之后</b>、快照或删 guild 行<b>之前</b>调用，且已持有这些 (G, p) 的 guild_member X 锁（被踢锁目标、
     * 退帮锁本人、解散锁全体）到提交——捐献预留插 PENDING DONATE 行之前必先锁同一成员行，所以候选读之后不可能再冒出新的未决捐献，
     * 候选集是完整的。
     *
     * <p>步骤：O1 候选<b>普通读</b>（IN 按 100 分块，每块读完关游标）→ 全部批次合并、去重、按 op_id 无符号升序 → 逐行 O2 主键点锁（读不到 =
     * 已被清理，跳过）→ O3 带复核点改（影响 0 行跳过，不自检）。只改 deadline / next_attempt / updated 三列、<b>不抢租约</b>；只作用于
     * DONATE（商店 / 活动的物品属于玩家，照常投递）。效果：循环领到它时 {@code now ≥ deadline} → 改发 Abort。
     *
     * @param nowMs 必须 &gt; 0（deadline_ms = 0 在本表是「永不中止」，传 0 会把所有未决捐献改成永不中止——fail-closed 拒绝）
     */
    public static void accelerateDonationDeadlines(GuildJdbc t, long guildId, List<Long> playerIds, long nowMs)
            throws SQLException {
        if (nowMs == 0) {
            throw new IllegalArgumentException("accelerate donation deadlines of guild " + id(guildId) + ": now must be > 0");
        }
        List<Long> candidates = new ArrayList<>();
        for (int from = 0; from < playerIds.size(); from += GuildLimits.IN_LIST_CHUNK) {
            List<Long> batch = playerIds.subList(from, Math.min(playerIds.size(), from + GuildLimits.IN_LIST_CHUNK));
            Object[] args = new Object[batch.size() + 5];
            for (int i = 0; i < batch.size(); i++) {
                args[i] = batch.get(i);
            }
            args[batch.size()] = STREAM_GUILD_DEBIT;
            args[batch.size() + 1] = STATUS_PENDING;
            args[batch.size() + 2] = guildId;
            args[batch.size() + 3] = KIND_DONATE;
            args[batch.size() + 4] = nowMs;
            candidates.addAll(t.ids(SELECT_ACCELERATE_CANDIDATES_HEAD + JdbcGuildStore.placeholders(batch.size())
                    + SELECT_ACCELERATE_CANDIDATES_TAIL, args));
        }
        for (long opId : JdbcGuildStore.sortedUnique(candidates)) {
            // 先主键点锁、再带复核条件点改（V1）。读不到 = 候选读之后已被清理（只删终态行），与点改影响 0 行同义
            if (!t.exists(LOCK_ASSET_OP, opId)) {
                continue;
            }
            t.update(ACCELERATE_DONATION_DEADLINE, nowMs, nowMs, nowMs, opId, STATUS_PENDING, nowMs);
        }
    }

    // ================================================================ 事务内的共用步骤

    /** G11：非锁定读 [zone_id, level]；无行 → null。 */
    private static int[] readGuildZoneLevel(GuildJdbc t, long guildId) throws SQLException {
        return t.one(SELECT_GUILD_ZONE_LEVEL, rs -> new int[] {GuildJdbc.u32(rs, 1), GuildJdbc.u32(rs, 2)}, guildId);
    }

    /**
     * 在 T-D / T-S 事务内、<b>已持本人成员行 X 锁之后</b>按需建 seq 行（ensureSeqRowTx，economy_repo.go:756-794；死锁复核 C2）。
     * 先普通读（行建出后永不删除，常态命中、不发写；拿到成员行锁之后读，前一个持锁者已提交的行在 RC 下一定看得见）；缺行才 INSERT IGNORE。
     * 理由：旧写法事务外自动提交建行，首插者回滚时排队者继承间隙 S、插入意向互挡 1213；成员行是全部建行者的守卫，首插者回滚时没有排队者。
     */
    static void ensureSeqRowTx(GuildJdbc t, long playerId, int stream, long nowMs) throws SQLException {
        if (t.exists(SEQ_ROW_EXISTS, playerId, stream)) {
            return;
        }
        // 纪元必须为正（调用方的入参校验已拒掉 now == 0，这里是接缝上的第二道）
        if (nowMs == 0) {
            throw new IllegalArgumentException("ensure guild_player_op_seq row (player=" + id(playerId) + " stream="
                    + Integer.toUnsignedString(stream) + "): epoch must be positive (nowMs=0)");
        }
        t.update(ENSURE_SEQ_ROW, playerId, stream, nowMs, nowMs);
    }

    /**
     * 在调用方的业务事务内分配下一个 seq（AllocateSeq，assetop/seq.go:172-238）：Q3 锁 seq 行（无行 → SEQ_ROW_MISSING；纪元 0 →
     * SEQ_ROW_CORRUPT）→ O4 普通读本纪元未决 seq（≤ 17 行）→ 守卫：{@code 未决数 ≥ 16} 或 {@code next_seq − 最小未决 ≥ 512} → null
     * （TOO_MANY_PENDING）→ Q4 推进 → 返回 {纪元, 原 next_seq}。只数<b>本纪元</b>的未决行：旧纪元的未决行不该挡住新操作。
     *
     * <p>普通读在 RC 下正确的四条理由（seq.go:155-162）：同 (p, stream) 分配者在 Q 行上串行；PENDING 行只由持 Q 锁的分配者插入；读发生在
     * 拿到 Q 锁之后、RC 每语句新快照；行只会离开 PENDING（并发终结只会让读到的集合偏大，守卫只会更保守）。
     *
     * @return null = 守卫拒绝（不是故障）
     */
    static SeqAlloc allocateSeq(GuildJdbc t, long playerId, int stream, long nowMs) throws SQLException {
        long[] row = t.one(LOCK_SEQ_ROW, rs -> new long[] {GuildJdbc.u64(rs, 1), GuildJdbc.u64(rs, 2)}, playerId, stream);
        if (row == null) {
            throw new GuildStoreException(GuildStoreException.Kind.SEQ_ROW_MISSING,
                    "guild_player_op_seq 行缺失（player=" + id(playerId) + " stream=" + Integer.toUnsignedString(stream) + "）");
        }
        long nextSeq = row[0];
        long epoch = row[1];
        if (epoch == 0) {
            throw new GuildStoreException(GuildStoreException.Kind.SEQ_ROW_CORRUPT,
                    "guild_player_op_seq 纪元为 0（player=" + id(playerId) + " stream=" + Integer.toUnsignedString(stream) + "）");
        }
        List<Long> pending = t.ids(SELECT_PENDING_SEQS, playerId, stream, epoch, STATUS_PENDING,
                GuildLimits.ASSET_OP_MAX_PENDING + 1);
        if (pending.size() >= GuildLimits.ASSET_OP_MAX_PENDING) {
            return null;
        }
        if (!pending.isEmpty() && Long.compareUnsigned(nextSeq - pending.getFirst(), GuildLimits.ASSET_OP_MAX_SPAN) >= 0) {
            return null;
        }
        // 行刚刚被 FOR UPDATE 锁住，更新不到只可能是有人在事务外删了它；继续下去会把同一个 seq 发第二次，必须失败
        t.updateExactlyOne("推进 guild_player_op_seq.next_seq（player=" + id(playerId) + "）", BUMP_SEQ, nowMs, playerId, stream);
        return new SeqAlloc(epoch, nextSeq);
    }

    /**
     * 占用计数行 n 次；超过 limit 时一次都不占（upsertCounterWithLimit，economy_repo.go:337-368）。硬前提（C6）：调用方已持有
     * guild_player_op_seq(p, 该计数所属的流) 的 X（AllocateSeq 已锁住它）。periodKey 必须是请求开头 start 的周期键（清理不持 seq 行，
     * 它与本语句不相遇只靠「清理截止键至少早 24 h」）。
     *
     * @return true = 达上限（值没变）
     */
    static boolean upsertCounterWithLimit(GuildJdbc t, long playerId, int kind, int refId, int periodKey, int n, int limit,
                                          long nowMs) throws SQLException {
        int affected = t.update(UPSERT_COUNTER_WITH_LIMIT, playerId, kind, refId, periodKey, n, nowMs, n, limit, nowMs, n,
                limit, n);
        return switch (affected) {
            case 1, 2 -> false;
            case 0 -> true;
            // 主键 upsert 不可能动到 > 2 行；真出现说明会话语义被改了（如 CLIENT_FOUND_ROWS），自检必须暴露它
            default -> throw new GuildStoreException(GuildStoreException.Kind.ROW_COUNT_MISMATCH,
                    "占用计数行（player=" + id(playerId) + " ref=" + Integer.toUnsignedString(refId) + "）影响行数异常 " + affected);
        };
    }

    private static Deadline readBudget(Deadline request) {
        return Deadline.after(Math.min(request.remainingMillis(), GuildLimits.ECONOMY_READ_BUDGET_MS));
    }

    private static GuildAssetOpStatus statusOf(int value) {
        GuildAssetOpStatus status = GuildAssetOpStatus.forNumber(value);
        return status == null ? GuildAssetOpStatus.UNRECOGNIZED : status;
    }

    private static String id(long unsigned) {
        return Long.toUnsignedString(unsigned);
    }
}
