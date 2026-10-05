package com.game.guild.store;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildReject;
import com.game.guild.store.GuildStore.ZoneFence;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 帮会经济的请求路径存储（基线 go/guild/internal/data/economy_repo.go 的 Java 版；guild-economy-spec §1.4、§3 各 RPC 的仓储步骤、§7.3）：
 * 三个写事务（捐献预留 T-D、兑换预留 T-S、升级 T-U）与六个读（C2 / C3 / M14 / O6 / O7 / O8）。只<b>发起</b>资产指令（写 PENDING 行、
 * 占次数 / 扣帮贡）与做纯 guild 表的升级；指令的领取、重排、终结与对侧账在 {@code com.game.guild.asset.GuildAssetStore}。
 *
 * <p><b>结局</b>（同 {@link GuildStore}）：业务拒绝与写冲突以 {@link TxOutcome} 返回（已回滚）；SQL 故障 / 请求预算用完抛
 * {@link Deadline.DependencyException}；seq 行缺失 / 纪元为 0 / 写入自检失败抛 {@link GuildStoreException}；入参违约（调用方的编程错误）
 * 在碰库之前抛 {@link IllegalArgumentException}——后三者上层一律定性为信封 1003。
 *
 * <p><b>纪律</b>：写事务只走 {@link GuildTx}（RC、1213 / 9007 重跑、1205 与子预算 → WRITE_CONFLICT）；锁序 G → M → Q → O → C；捐献与兑换
 * 只普通读 G（锁 G 会让同帮所有捐献在一把锁上排队），升级才 {@code FOR UPDATE} G；合服闸门在事务内、zone 取事务内读到的
 * guild.zone_id。缓存失效在提交之后由服务按结果里的 {@link Invalidation} 去做（T-D 不失效；T-S 失效 G；T-U 在改了或视图过期时失效 G）。
 *
 * <p>时刻、令牌、op_id 一律由调用方传入（一个请求只取一次 now，周期键 / 截止 / 租约 / created_ms 都从它算）。线程安全；全部方法阻塞
 * （JDBC），只在 guild-worker 线程上调用。
 */
public interface EconomyStore {

    // ================================================================ 入参

    /**
     * 捐献预留 T-D 的全部输入（DonationReserve，economy_repo.go:482-519）。
     *
     * @param periodKey    {@code GameDay.dayKey(start)}
     * @param deadlineMs   {@code start + asset_op_deadline_seconds × 1000}
     * @param leaseUntilMs {@code start + lease}（插行租约，同步投递期间循环看不见这一行）
     * @param leaseToken   本请求的令牌（非 0）
     * @param payload      {@code AssetBundle{currencies:[{currency_type, cost_amount}]}} 的序列化字节（非空）
     * @param fence        事务内合服闸门；null 按 {@link ZoneFence#OPEN}
     */
    record DonationReserve(long opId, long playerId, long guildId, int donateId, int minGuildLevel, long contributionGain,
                           long fundsGain, int dailyLimit, int periodKey, long deadlineMs, long leaseUntilMs, long leaseToken,
                           long nowMs, ByteString payload, ZoneFence fence) {
        public DonationReserve {
            Objects.requireNonNull(payload, "payload");
        }

        /**
         * 碰库之前的入参校验（validate，economy_repo.go:501-519）。这些都是调用方的编程错误（不是玩家可触发的拒绝）。DailyLimit / DeadlineMs
         * 为 0 尤其要拒：前者让 upsert 永远判「达上限」，后者在本表表示「永不中止」。
         *
         * @return null = 合法；否则是原因
         */
        public String invalidReason() {
            if (opId == 0 || playerId == 0 || guildId == 0) {
                return "ids must be non-zero (op=" + u(opId) + " player=" + u(playerId) + " guild=" + u(guildId) + ")";
            }
            if (nowMs == 0 || leaseToken == 0) {
                return "now and lease token must be non-zero";
            }
            if (Long.compareUnsigned(leaseUntilMs, nowMs) <= 0 || Long.compareUnsigned(deadlineMs, nowMs) <= 0) {
                return "lease (" + u(leaseUntilMs) + ") and deadline (" + u(deadlineMs) + ") must be after now (" + u(nowMs) + ")";
            }
            if (dailyLimit == 0 || periodKey == 0) {
                return "daily limit (" + Integer.toUnsignedString(dailyLimit) + ") and period key ("
                        + Integer.toUnsignedString(periodKey) + ") must be non-zero";
            }
            if (payload.isEmpty()) {
                return "empty payload";
            }
            return null;
        }
    }

    /**
     * 兑换预留 T-S 的全部输入（ShopReserve，economy_repo.go:612-647）。
     *
     * @param count      份数（≥ 1，服务已按 MaxBuyCount 校验）
     * @param cost       {@code cost_contribution × count}（&gt; 0：扣 0 的 UPDATE 影响 0 行，会被写入自检误判成帮贡不足）
     * @param limitCount 周期限购份数；0 = 不限购
     * @param periodKey  {@code GameDay.periodKey(limit_period, start)}；不限购时为 0（两者必须同为 0 或同非 0）
     * @param payload    {@code AssetBundle{items:[{item_id, item_count × count}]}} 的序列化字节（非空）
     */
    record ShopReserve(long opId, long playerId, long guildId, int goodsId, int count, int requiredGuildLevel, long cost,
                       int limitCount, int periodKey, long leaseUntilMs, long leaseToken, long nowMs, ByteString payload,
                       ZoneFence fence) {
        public ShopReserve {
            Objects.requireNonNull(payload, "payload");
        }

        /** 碰库之前的入参校验（validate，economy_repo.go:629-647）；null = 合法。 */
        public String invalidReason() {
            if (opId == 0 || playerId == 0 || guildId == 0) {
                return "ids must be non-zero (op=" + u(opId) + " player=" + u(playerId) + " guild=" + u(guildId) + ")";
            }
            if (nowMs == 0 || leaseToken == 0 || Long.compareUnsigned(leaseUntilMs, nowMs) <= 0) {
                return "now / lease token / lease until invalid";
            }
            if (count == 0 || cost == 0) {
                return "count (" + Integer.toUnsignedString(count) + ") and cost (" + u(cost) + ") must be non-zero";
            }
            if ((limitCount == 0) != (periodKey == 0)) {
                return "limit count (" + Integer.toUnsignedString(limitCount) + ") and period key ("
                        + Integer.toUnsignedString(periodKey) + ") must be both zero or both non-zero";
            }
            if (payload.isEmpty()) {
                return "empty payload";
            }
            return null;
        }
    }

    /** 一级的升级数据（GuildLevel 行的两列）：升到下一级要花的资金（0 = 满级）与这一级的成员上限。 */
    record UpgradeLevel(long upgradeCostFunds, int maxMembers) {
    }

    /** 按等级查 GuildLevel（基线 LevelLookup，economy_repo.go:798-799）。null = 配表缺行（→ {@link GuildReject#LEVEL_CONFIG_MISSING}，故障）。 */
    @FunctionalInterface
    interface UpgradeLevels {
        UpgradeLevel find(int level);
    }

    // ================================================================ 结果

    /** 捐献预留的结果：本次分到的 (纪元, 流水号)，同步投递要原样带给 scene。T-D 提交后不失效任何缓存（只改了 Q、O、C）。 */
    record Reserved(long seq, long streamEpoch) {
    }

    /**
     * 兑换预留的结果。
     *
     * @param balanceAfter 扣帮贡之后的可用余额（回包直接用，不再读一次）
     */
    record ShopReserved(long guildId, long seq, long streamEpoch, long balanceAfter) {
        /** 帮贡在成员快照里：提交后失效 guild(G)（economy_repo.go:751-752）。 */
        public Invalidation invalidation() {
            return Invalidation.of(GuildTxOp.SHOP, guildId);
        }
    }

    /**
     * 升级的结果（UpgradeResult，economy_repo.go:801-806）。
     *
     * @param changed   真的升了一级（扣了钱）
     * @param newLevel  事务内读到 / 写下的等级
     * @param memberIds changed 时：升级后全体成员（player_id 无符号升序）
     * @param staleView expected_level 与库里等级不符（上次升级已提交但回执丢失）：不扣钱，但必须失效缓存（economy_repo.go:821-827）
     */
    record Upgraded(long guildId, long actorId, boolean changed, int newLevel, List<Long> memberIds, boolean staleView) {
        public Upgraded {
            memberIds = List.copyOf(memberIds);
        }

        /** {@code changed || staleView} 时失效 guild(G)（economy_repo.go:891-894）。 */
        public Invalidation invalidation() {
            return changed || staleView ? Invalidation.of(GuildTxOp.UPGRADE, guildId) : Invalidation.none(GuildTxOp.UPGRADE);
        }

        /** LEVEL_UP(10) 的收件人：仅 changed 时，全体成员除操作者（economy_logic.go:851-855）。 */
        public List<Long> pushRecipients() {
            if (!changed) {
                return List.of();
            }
            List<Long> out = new ArrayList<>(memberIds.size());
            for (long p : memberIds) {
                if (p != actorId) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    /** 帮贡两列（M14）。 */
    record Contribution(long total, long balance) {
    }

    /** 商店用量的键：同一商品只按日或按周之一限购，键里带上周期键，调用方不必知道哪个商品是哪种周期（economy_repo.go:947-949）。 */
    record ShopUsageKey(int goodsId, int periodKey) {
    }

    /** 同步投递之后回读的单行状态（O6；结局以库为准，economy_repo.go:1014-1020）。 */
    record OpState(GuildAssetOpStatus status, int lastReason, int reasonTipId) {
    }

    // ================================================================ 写

    /**
     * 捐献预留 T-D（op = donate；ReserveDonation，economy_repo.go:521-608）：G11 普通读 zone / 等级 → 等级门槛 → 闸门 → M1 锁本人成员行 →
     * 按需建 seq 行（GUILD_DEBIT）→ 分 seq（守卫 16 / 512）→ 插 PENDING 行（28 列全写）→ 占今日次数（C1，n = 1）。
     * 拒绝：GUILD_GONE / LEVEL_TOO_LOW / ZONE_MERGING / NOT_MEMBER / TOO_MANY_PENDING / DONATE_LIMIT / WRITE_CONFLICT。
     * 任何拒绝都整体回滚：seq 不前进、行不落、次数不占（首次建出的 seq 行也随之回滚）。
     */
    TxOutcome<Reserved> reserveDonation(DonationReserve in, Deadline deadline);

    /**
     * 兑换预留 T-S（op = shop；ReserveShopOrder，economy_repo.go:649-754）：事务前纯判断 {@code count > limit_count}（SHOP_LIMIT，
     * 首次插入计数行时 upsert 拦不住）→ G11 → 等级 → 闸门 → M12 锁成员行读余额 → 余额不足 → 建 seq 行（GUILD_CREDIT）→ 分 seq →
     * 插 PENDING 行（deadline 0，永不中止）→ 限购时 C1（n = count）→ M13 扣帮贡（影响 ≠ 1 → CONTRIBUTION_INSUFFICIENT）。
     */
    TxOutcome<ShopReserved> reserveShopOrder(ShopReserve in, Deadline deadline);

    /**
     * 升级 T-U（op = upgrade；UpgradeGuild，economy_repo.go:808-896）：G12 锁帮会 → M1 锁操作者 → Rank &lt; 长老 → RANK_TOO_LOW → 闸门 →
     * {@code expected != 0 && != level} → 不改任何行、提交（changed = false，staleView）→ 当前等级行缺 → LEVEL_CONFIG_MISSING →
     * cost == 0 → MAX_LEVEL → 下一级行缺 → LEVEL_CONFIG_MISSING → funds &lt; cost → FUNDS_INSUFFICIENT → G13（扣<b>当前等级行</b>的花费、
     * 上限取下一级行）→ M10 读成员。
     */
    TxOutcome<Upgraded> upgradeGuild(long guildId, long playerId, int expectedLevel, UpgradeLevels levels, ZoneFence fence,
                                     Deadline deadline);

    // ================================================================ 读（自动提交，各 1000 ms 子预算）

    /** C2：本人某游戏日各捐献选项已用次数 donate_id → used_count（含结算中的占用）；没有计数行的选项不在 map 里。 */
    Map<Integer, Integer> donateUsage(long playerId, int dayKey, Deadline deadline);

    /** C3：本人本游戏日 / 本游戏周的商店用量（日键 8 位、周键 6 位，数值域不相交，一条 IN）。 */
    Map<ShopUsageKey, Integer> shopUsage(long playerId, int dayKey, int weekKey, Deadline deadline);

    /** M14：直读帮贡两列（不走缓存）；空 = 不是该帮成员。 */
    Optional<Contribution> memberContribution(long guildId, long playerId, Deadline deadline);

    /** O6：按主键回读一行的状态；空 = 行不存在。 */
    Optional<OpState> opState(long opId, Deadline deadline);

    /** O7：本人某条流上的未决指令，按 (stream_epoch, seq) 升序，至多 limit 条；limit ≤ 0 返回空。 */
    List<GuildAssetOpRow> pendingOps(long playerId, int stream, int limit, Deadline deadline);

    /** O8：本人某条流上最新的 limit 条指令（含 PENDING），按 (stream_epoch, seq) 倒序；状态 / 时间窗 / kind / 帮会过滤由调用方做。 */
    List<GuildAssetOpRow> recentOps(long playerId, int stream, int limit, Deadline deadline);

    private static String u(long v) {
        return Long.toUnsignedString(v);
    }
}
