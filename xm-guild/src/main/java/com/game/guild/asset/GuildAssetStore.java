package com.game.guild.asset;

import com.game.common.deadline.Deadline;
import com.game.guild.store.Invalidation;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 帮会资产指令账本的后台 Store（基线 asset_store.go 的 GuildAssetStore = assetop.Store + ManualResolver + PendingAgeReader，外加
 * asset_op_divergence_repo.go；guild-economy-spec §2.6–§2.8、§2.11–§2.13、§7.1「资产 Store」）。它回答四件事：哪些行到期了
 * （{@link #listDue}）、这一行归我处理（{@link #claim}）、这一轮没结论改天再来（{@link #reschedule}）、结论定了一次性落库并做对侧账
 * （{@link #finalizeOp} / {@link #resolveManually}）。另有最老未决年龄、assetopfix 的两个读、清理与回档检查的只读查询。
 *
 * <p><b>纪律</b>：
 * <ul>
 *   <li>后台写一律经 {@code BackgroundTx}（RC；1213 / 9007 / 1205 都重跑，3 次；清理每行 1 次），<b>不</b>走请求路径的 GuildTx；</li>
 *   <li>对 op 行的每个悲观写者（重排、毒行、终结、人工终结、清理）在带复核条件的写之前，先在同一事务里跑完整主键点锁（TiDB 规则 V1）；
 *       <b>领取保留自动提交、不许包进事务</b>（asset_store.go:54-59）；</li>
 *   <li>终结与人工终结都同写 {@code next_attempt_ms = now}：终态行的 next_attempt_ms 就是终结时刻，清理判龄与回档检查都依赖它
 *       （漏写 = 回档检查漏行，fail-open）；</li>
 *   <li>结算一律记给发起时绑定的帮会（op.guild_id，D2），不看玩家此刻在哪个帮；没有退款指令；</li>
 *   <li>副作用（orphan 计数、日志、缓存失效、终结回调）只在提交之后发（事务可能被重跑）。</li>
 * </ul>
 * 时刻一律由调用方给（循环 / CLI 的时钟），Store 不读墙钟（清理的 now 同样由调用方给）。截止时刻：各方法取
 * {@code min(自身子预算, 调用方给的截止)}；循环的 settle 截止只有 700 ms 且不随请求取消。线程安全；全部方法阻塞（JDBC），
 * 只在后台 worker / settle 执行器 / 清理线程 / CLI 主线程上调用。
 *
 * <p>失败语义：SQL 故障 / 截止用尽抛 {@link Deadline.DependencyException}；写入自检失败抛 {@code GuildStoreException}；入参违约在碰库之前抛
 * {@link IllegalArgumentException}。租约丢失、CAS 落空、毒行都不是故障，以结果返回。
 */
public interface GuildAssetStore {

    // ================================================================ 领取

    /** 领取的结局（基线 (Op, bool, error) 的三种情形）。 */
    sealed interface ClaimResult permits Claimed, Lost, Poisoned {
    }

    /** 领到了：回读的行仍是 PENDING 且令牌是本次的。 */
    record Claimed(AssetOp op) implements ClaimResult {
        public Claimed {
            Objects.requireNonNull(op, "op");
        }
    }

    /** 没领到：已被别的副本领走、已终结，或 CAS 之后回读发现已被人工终结 / 令牌已换（不下发）。 */
    record Lost() implements ClaimResult {
        public static final Lost INSTANCE = new Lost();
    }

    /**
     * 毒行：payload 为空或解不开（ErrPoisonRow）。Store 已把它推迟到调用方给的 poisonUntilMs、放掉租约、last_outcome 记 UNKNOWN；
     * 循环计 {@code claim{poison}} 与 {@code store_errors{decode}} 后继续下一行。
     *
     * @param cause 解码失败的原因（只进日志）
     */
    record Poisoned(String cause) implements ClaimResult {
    }

    // ================================================================ 重排

    /** 重排的结局。 */
    enum RescheduleResult {
        /** 已写回：attempts + 1、next_attempt_ms、清租约（以及答复的 durable / last_outcome / last_reason）。 */
        RESCHEDULED,
        /**
         * 租约已被别的副本接管（ErrLeaseLost）：本次结果一个字都没写进去。不是故障、不重试，但必须计
         * {@code reschedule_lost_total}——返回成功会把「我的结果被丢弃」伪装成成功。
         */
        LEASE_LOST
    }

    // ================================================================ 终结

    /** 对侧账无处可记的三种情形（orphan 指标的两个 label 都取自这里的固定集合，不带任何 id）。 */
    enum Orphan {
        /** 捐献扣款成功，但绑定的帮会已解散：资金与帮贡都记不上。 */
        DONATE_GUILD_GONE("donate", "guild_gone"),
        /** 捐献扣款成功，帮会还在但捐献者已离帮：只记资金、跳过帮贡。 */
        DONATE_MEMBER_GONE("donate", "member_gone"),
        /** 兑换被永久拒绝，但兑换者已不是该帮成员：帮贡退不回去（限购照退）。 */
        SHOP_REFUND_MEMBER_GONE("shop", "refund_member_gone");

        private final String kind;
        private final String what;

        Orphan(String kind, String what) {
            this.kind = kind;
            this.what = what;
        }

        /** {@code kind} label：donate / shop。 */
        public String kind() {
            return kind;
        }

        /** {@code what} label：guild_gone / member_gone / refund_member_gone。 */
        public String what() {
            return what;
        }
    }

    /**
     * 对侧账做了什么（counterpartyOutcome，asset_store.go:648-655）。
     *
     * @param touched     改过 guild 或 guild_member 行 → 提交后失效 guild(G) 与 p 的映射（op = asset_finalize）
     * @param orphan      非 null = 无处可记（提交后计 orphan 指标、打 INFO）
     * @param unknownKind kind 不认识：只做了 CAS（提交后打 ERROR）
     */
    record Counterparty(boolean touched, Orphan orphan, boolean unknownKind) {
        public static final Counterparty NONE = new Counterparty(false, null, false);
    }

    /**
     * 一次<b>本次</b>终结的摘要（FinalizedOp，asset_store.go:331-336），供提交后的推送使用。
     *
     * @param origin 谁触发的（E9）：推送只在 {@link DeliveryOrigin#LOOP} 时发
     */
    record FinalizedOp(long opId, long playerId, long guildId, GuildAssetOpKind kind, AssetOpStatus status,
                       DeliveryOrigin origin) {
    }

    /**
     * 终结 / 人工终结的结果（spec §7.3：{@code (finalized, counterpartyOutcome)}）。
     *
     * @param finalized    是否<b>本次</b>终结（CAS 命中）；false = 已被别的副本 / 人工终结，或行不存在
     * @param op           本次终结的摘要；未终结时为 null
     * @param counterparty 对侧账；未终结时为 {@link Counterparty#NONE}
     */
    record FinalizeResult(boolean finalized, FinalizedOp op, Counterparty counterparty) {
        public static final FinalizeResult NOT_FINALIZED = new FinalizeResult(false, null, Counterparty.NONE);
    }

    // ================================================================ 清理与回档检查

    /** 清理指标的 {@code table} label（固定集合）。 */
    enum CleanupTable {
        ASSET_OP("guild_asset_op"),
        DAILY_COUNTER("guild_daily_counter");

        private final String label;

        CleanupTable(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 一轮清理删掉的行数。 */
    record CleanupReport(long assetOpsDeleted, long countersDeleted) {
    }

    /**
     * 回档检查的一行摘要（GuildAssetOpBrief，guild_internal.proto:27-37）：stream / kind / status 都是库里的整数原值。
     * status 只会是 APPLIED 或 APPLIED_PARTIAL；updated_ms = 终结时刻。
     */
    record AppliedOpBrief(long opId, long playerId, long guildId, int stream, int kind, int status, long fundsDelta,
                          long contributionDelta, long updatedMs) {
    }

    /**
     * 一页结果。
     *
     * @param nextAfterOpId 下一页游标：还有下一页时 = 本页最后一行的 op_id，否则 0（已查尽）
     */
    record AppliedOpsPage(List<AppliedOpBrief> ops, long nextAfterOpId) {
        public AppliedOpsPage {
            ops = List.copyOf(ops);
        }
    }

    // ================================================================ 提交后的钩子

    /**
     * Store 提交之后要做的事（装配时绑定：失效 → 帮会缓存失效组件；orphan / 清理 → 指标；终结 → 推送）。实现必须便宜、不抛异常
     * （Store 仍会兜住异常只记日志：提交已经发生，不能把一次已生效的终结报成失败）。
     */
    interface Listener {
        /** 终结改过 guild / 成员行：失效 guild(G) 与 p 的映射（op = asset_finalize）。 */
        void invalidate(Invalidation invalidation);

        /** 对侧账无处可记（{@code xm_guild_asset_orphans_total{kind,what}}）。 */
        void orphan(Orphan orphan);

        /** 清理删掉的行（{@code xm_guild_asset_cleanup_deleted_total{table}}）；每批调用一次，n &gt; 0。 */
        void cleanupDeleted(CleanupTable table, long n);

        /** 本次终结（CAS 命中）并已提交：基线 OnFinalized，origin 决定推不推送。 */
        void finalized(FinalizedOp op);

        Listener NONE = new Listener() {
            @Override
            public void invalidate(Invalidation invalidation) {
            }

            @Override
            public void orphan(Orphan orphan) {
            }

            @Override
            public void cleanupDeleted(CleanupTable table, long n) {
            }

            @Override
            public void finalized(FinalizedOp op) {
            }
        };
    }

    // ================================================================ 方法

    /**
     * 到期行（ListDue，asset_store.go:365-423）：两段非加锁读，只回主键。第一段只取新行（attempts &lt; 3）并占满 limit；只有它不够时才发
     * 第二段取老行、只补缺口（X-03 防饿死：退避封顶的老行永远「早就到期」，一条 SQL 会让它们吃光名额）。两段按 op_id 去重，第一段排前。
     * 总条数 ≤ limit；limit ≤ 0 返回空。
     */
    List<Long> listDue(long nowMs, int limit, Deadline deadline);

    /**
     * 领取一行（Claim，asset_store.go:425-491）：自动提交的主键 CAS（{@code lease_until_ms < now} 才领）→ 回读整行 → 必须仍是 PENDING 且令牌
     * 是本次的，否则不下发（中间可能被人工终结——对一条已判 ABORTED 的捐献再扣一次 = 丢玩家的钱）→ payload 为空或解不开 → 毒行推迟。
     * CorrelationID = op_id；DeadlineMs / Attempts / LastReason 取库里的值（看得到离帮提前截止）。
     *
     * @param poisonUntilMs 毒行推迟到的绝对时刻（循环按 poison-delay 算好的；Store 不另写毒行延迟常量）
     * @param token         本次领取的令牌（非 0）
     */
    ClaimResult claim(long opId, long nowMs, long leaseUntilMs, long poisonUntilMs, long token, Deadline deadline);

    /**
     * 重排：带令牌的 CAS（Reschedule，asset_store.go:520-565；O2 + O14）：attempts + 1、next_attempt_ms、清租约，并写下本次 scene 答复的
     * durable / last_outcome / last_reason。部分发放码的粘性由调用方在传入前做（{@link AssetOpDecisions#carryPartialReason}），这里照写。
     * 本地合成的 NOT_HERE 也是答复，走这里。
     */
    RescheduleResult reschedule(AssetOp op, long nextAttemptMs, AssetOpResult answer, long nowMs, Deadline settle);

    /**
     * 重排，但<b>没有</b> scene 答复（E12：传输失败、坏流号）：只推进 attempts、next_attempt_ms、清租约，<b>不</b>覆盖 last_outcome /
     * last_reason / durable——让 assetopfix 的前置「last_outcome = UNKNOWN 且 attempts ≥ 10」可信（基线作者在 assetopfix/main.go:62-67
     * 明确想要这一修法），27007 粘性也自然保留。
     */
    RescheduleResult rescheduleWithoutAnswer(AssetOp op, long nextAttemptMs, long nowMs, Deadline settle);

    /**
     * 终结（Finalize，asset_store.go:574-591、:657-742）：事务外读不可变列 → 事务内按锁序锁对侧账的行（G / M / Q 守卫）→ O2 点锁 → CAS
     * （{@code status = PENDING}，同写 durable = 1、last_outcome / last_reason、reason_tip_id（只在 REJECTED 时写 scene 原因）、
     * next_attempt_ms = updated_ms = now）→ 影响 1 行才做对侧账。提交后：orphan、未知 kind 日志、失效缓存、{@link Listener#finalized}。
     *
     * @param status 必须是四个终态之一（否则 {@link IllegalArgumentException}，不碰库）
     */
    FinalizeResult finalizeOp(long opId, AssetOpStatus status, AssetOpResult result, long nowMs, DeliveryOrigin origin,
                              Deadline settle);

    /**
     * 人工终结（ResolveManually，asset_store.go:593-634）：与 {@link #finalizeOp} 共用同一把 {@code status = PENDING} 的 CAS 与同一份对侧账
     * （人工与循环同时下手只有一个赢家）；写 resolved_by / resolve_reason，同写 next_attempt_ms = now；<b>不置 durable、不改 last_outcome</b>
     * （保留最后一次 scene 真实答复作证据）。碰库之前再校验一次 {@link ManualResolution#validate}（不合法 → {@link IllegalArgumentException}）。
     * 终结回调的 origin 是 {@link DeliveryOrigin#MANUAL}（不推送）。
     */
    FinalizeResult resolveManually(ManualResolution resolution, long nowMs, Deadline deadline);

    /** 某条流最老未决行的 created_ms（O18；喂 {@code pending_oldest_age_seconds{stream}}）；空 = 没有未决行。 */
    OptionalLong oldestPendingCreatedMs(int stream, Deadline deadline);

    /** 按主键读一整行（assetopfix）；空 = 行不存在。 */
    Optional<GuildAssetOpRow> getOp(long opId, Deadline deadline);

    /** 创建时刻早于 createdBeforeMs 的未决行，按 (created_ms, op_id) 升序，至多 limit 条（O19，assetopfix list）；limit ≤ 0 返回空。 */
    List<GuildAssetOpRow> listStuck(long createdBeforeMs, int limit, Deadline deadline);

    /**
     * 跑一轮清理（CleanupOnce，asset_store.go:1009-1040）：终态行（APPLIED / REJECTED / ABORTED，next_attempt_ms &lt; now − 保留期；
     * <b>PENDING 与 APPLIED_PARTIAL 永不删</b>）、过期日键计数行、过期周键计数行三类各自分批（每批候选 ≤ 500、批间 100 ms、至多 20 批，
     * 候选不足 500 即停），每行一个 RC 短事务「点锁 → 带复核点删」（1 次尝试、2 s 上限）。计数截止时刻 = now − max(保留期, 8 天)。
     * 一类失败不影响另外两类；已删的行数照常经 {@link Listener#cleanupDeleted} 计。线程被中断时停止并报错。
     *
     * @throws IllegalArgumentException 保留期 ≤ 0
     * @throws Deadline.DependencyException 任一类出错（其余类的错误挂在 suppressed 上）
     */
    CleanupReport cleanupOnce(long nowMs, Duration terminalRetention, Duration counterRetention);

    /**
     * 回档检查查询（ListAppliedAssetOpsSince，asset_op_divergence_repo.go:105-144）：<b>一条非锁定普通读</b>，不开事务、不加锁、不写索引提示；
     * 按 op_id 升序取 limit + 1 行判断有没有下一页。不另设子预算（调用方在等答复，超时即整次调用失败）；不保证跨页快照一致，
     * 由消费方的写后复查兜底。入参越界（{@link AppliedOpsQuery#invalidReason}）在碰库之前抛 {@link IllegalArgumentException}。
     */
    AppliedOpsPage listAppliedSince(AppliedOpsQuery query, Deadline deadline);
}
