package com.game.guild.rules;

import java.util.List;

/**
 * 帮会的代码常量（guild-spec §0.5「代码常量」、§7.12 最后一行）。写死在代码里，不做成配置项。
 *
 * <p>业务上限里随配表走的那几项（申请有效期、每人 / 每帮待审数、成员与长老上限）<b>不在这里</b>：只读配表、用时现查，
 * 见 {@link GuildTableRules}（基线 guild_manage_logic.go:37-96）。可配的预算（整请求 3500 ms、缓存 TTL、推送超时、在线读上限、
 * 语句超时、线程池）归 {@code xm.guild.*} 配置项（§7.12）。
 */
public final class GuildLimits {

    // ---- 输入上限（constants.go:196-206） ----

    /** 帮名上限：trim 后的码点数（constants.go:199；与客户端输入框的 24 字一致）。 */
    public static final int MAX_GUILD_NAME_RUNES = 24;

    /** 判重键 name_norm 的码点上限：NFKC 会把少数兼容字符展开（如 ㍿ → 株式会社），留一倍余量（guild_repo.go:519-522）。 */
    public static final int MAX_GUILD_NAME_NORM_RUNES = 48;

    /**
     * 公告上限，按<b>未 trim 的 UTF-8 字节数</b>计（constants.go:200-203）：gate 单包 1 KB，600 字节加上 id 与信封仍小于 1 KB。
     */
    public static final int MAX_ANNOUNCEMENT_BYTES = 600;

    /** 排行单页条数上限（constants.go:205）；客户端来源一律夹到它（guild_logic.go:615）。 */
    public static final int MAX_RANK_PAGE_SIZE = 50;

    /** 排行缺省页长：请求 page_size == 0 时取它（guild_logic.go:598-601）。 */
    public static final int DEFAULT_RANK_PAGE_SIZE = 20;

    /** 排行缺省页码：请求 page == 0 时取它（guild_logic.go:602-605）。 */
    public static final int DEFAULT_RANK_PAGE = 1;

    // ---- 帮会与申请 ----

    /**
     * GuildLevel.max_members 的<b>校验</b>上限，不是运行期人数上限（constants.go:189-194）：推送收件人与单帮快照的包体预算都按它估过，
     * 配表填得更大时启动拒绝（{@link GuildTableRules#validate}）。
     */
    public static final int MAX_GUILD_MEMBERS_CAP = 100;

    /** 新建帮会的等级（constants.go:186）；建帮的成员上限取 GuildLevel 这一级的 max_members。 */
    public static final int DEFAULT_INIT_LEVEL = 1;

    /**
     * ListMyGuildApplications 单次返回上限（guild_manage_logic.go:47-50）。它就是每人待审上限的校验上界
     * （{@link GuildTableRules#MAX_PENDING_PER_PLAYER}），所以列表不会被截断。
     */
    public static final int MY_APPLICATIONS_LIMIT = 10;

    /** 同一（帮会, 申请人）在此窗口内至多推一次 APPLICATION_RECEIVED（guild_manage_repo.go:681-683，{@code SET NX PX}）。 */
    public static final long APPLY_PUSH_COOLDOWN_MS = 60_000L;

    /** 每次申请提交之后顺手清理本帮过期申请的行数上限（guild_manage_repo.go:1857）。 */
    public static final int PURGE_EXPIRED_APPLICATIONS_PER_APPLY = 10;

    // ---- 事务（guild_manage_repo.go:103-123、:257、:315） ----

    /** 单次事务尝试的子预算，取 {@code min(请求剩余, 它)}（guild_manage_repo.go:115）。 */
    public static final long TX_BUDGET_MS = 1_500L;

    /** 解散事务的子预算（全文件最重的事务，单独放宽；guild_manage_repo.go:123）。 */
    public static final long TX_BUDGET_DISBAND_MS = 2_500L;

    /** 死锁（1213 / 9007）整体重跑的总尝试次数（guild_manage_repo.go:103）；1205 不重试。 */
    public static final int MAX_TX_ATTEMPTS = 3;

    /** 重跑之间的随机退避下界（含），毫秒（guild_manage_repo.go:257：10 ms + [0, 40 ms)）。 */
    public static final long TX_RETRY_BACKOFF_MIN_MS = 10L;

    /** 重跑之间的随机退避上界（不含），毫秒。 */
    public static final long TX_RETRY_BACKOFF_MAX_MS = 50L;

    /** 连接级 {@code innodb_lock_wait_timeout}（秒；guild_manage_repo.go:315）。friend 是 3，guild 照基线取 1。 */
    public static final int LOCK_WAIT_TIMEOUT_SECONDS = 1;

    /** 业务 SQL 的 IN 列表分块大小（guild_manage_repo.go:955、:1206）。 */
    public static final int IN_LIST_CHUNK = 100;

    // ---- 提交后的缓存失效（guild_manage_repo.go:509-574） ----

    /** 同步失效失败后，后台重试的间隔（毫秒）。全部用尽才计 {@code xm_guild_cache_invalidation_failures_total}。 */
    public static final List<Long> INVALIDATE_RETRY_DELAYS_MS = List.of(100L, 400L, 1_600L);

    /** 后台失效重试的总预算（毫秒；guild_manage_repo.go:553）。 */
    public static final long INVALIDATE_BACKGROUND_BUDGET_MS = 3_000L;

    // ---- 排行维护锁（基线 guild_repo.go:156-160、:786-816；Java D16 改为短 TTL + 续期） ----

    /** 维护锁 TTL（毫秒）。基线 5 min；Java 取 30 s 并由持有者续期（D16，已拍板）。 */
    public static final long RANK_LOCK_TTL_MS = 30_000L;

    /** 等锁的上限（毫秒；基线 guildRankLockWait = 5 s）。请求路径另受 {@code 剩余预算 − RANK_LOCK_REQUEST_RESERVE_MS} 约束（§7.4）。 */
    public static final long RANK_LOCK_MAX_WAIT_MS = 5_000L;

    /** 等锁的轮询间隔（毫秒；guild_repo.go:790）。 */
    public static final long RANK_LOCK_POLL_MS = 20L;

    /** 请求路径等排行锁时给回包装配留的预算（毫秒；§7.4：{@code min(剩余预算 − 300 ms, 5 s)}）。 */
    public static final long RANK_LOCK_REQUEST_RESERVE_MS = 300L;

    /** 重建排行的临时键 TTL（毫秒；D16，修 §9.1 第 7 条的永久泄漏）。 */
    public static final long RANK_REBUILD_TMP_TTL_MS = 600_000L;

    // ---- 依赖查询 ----

    /** 归属区查询（一次 player 表点查）的超时上限，实际取 {@code min(它, 剩余预算)}（home_zone.go:28；§7.4）。 */
    public static final long HOME_ZONE_LOOKUP_TIMEOUT_MS = 1_500L;

    // 展示名的每批人数不在这里：D5 读 xm_java.player，用 PlayerProfiles.BATCH（64；基线 player_name_resolver.go:54 是 500）。

    // ================================================================ 4.5 帮会经济与资产通道（guild-economy-spec §0.7、§7.6「代码常量」）
    // 这些都不开放配置。可配的循环参数（间隔、批量、Workers、租约、OpBudget、退避封顶、毒行推迟、保留期……）归 xm.guild.asset-op.*。

    // ---- 未决守卫（assetop/types.go:234-246）：scene 1024 位账本窗口正确性证明的一部分，不是可调业务数值 ----

    /** 本纪元未决行数上限：未决行数 ≥ 它 → TOO_MANY_PENDING。也是待结算列表的条数上限（economy_logic.go:661-663）。 */
    public static final int ASSET_OP_MAX_PENDING = 16;
    /** 跨度上限：{@code next_seq − 最小未决 seq ≥ 它} → TOO_MANY_PENDING。 */
    public static final long ASSET_OP_MAX_SPAN = 512L;

    // ---- 商店 ----

    /** 单次兑换份数上限 MaxShopBuyCount（constants.go:179）。cost_contribution ≤ 1e9 的配表上限以它为前提（× 20 不溢出）。 */
    public static final int MAX_SHOP_BUY_COUNT = 20;

    // ---- 同步投递（economy_logic.go:64-74；guild.go:64-68） ----

    /** 同步投递预算上限（economySyncBudget）。实际取 {@code min(它, 请求剩余 − SYNC_TAIL_RESERVE_MS)}。 */
    public static final long ASSET_SYNC_BUDGET_MS = 2_500L;
    /** 同步投递之后要给请求留的尾巴（700 落库 + 300 回读与编码）。 */
    public static final long ASSET_SYNC_TAIL_RESERVE_MS = 1_000L;
    /** 同步投递预算的下限：低于它不做同步投递，行在租约到期后由循环领走。 */
    public static final long ASSET_SYNC_MIN_BUDGET_MS = 300L;

    // ---- 读页（economy_logic.go:76-80） ----

    /** 最近结果的时间窗。 */
    public static final long RECENT_RESULT_WINDOW_MS = 600_000L;
    /** 最近结果扫描的行数（O8 的 LIMIT）。 */
    public static final int RECENT_RESULT_SCAN = 20;
    /** 最近结果返回条数上限。 */
    public static final int RECENT_RESULT_KEEP = 5;

    // ---- 仓储子预算（economy_repo.go:81；asset_store.go:98-112） ----

    /** 经济读查询（C2 / C3 / M14 / O6 / O7 / O8）的子预算。 */
    public static final long ECONOMY_READ_BUDGET_MS = 1_000L;
    /** 后台 Store：ListDue、Reschedule、毒行、读的子预算（取它与调用方给的 settle 截止的较小者）。 */
    public static final long ASSET_STORE_READ_BUDGET_MS = 1_000L;
    /** 后台 Store：Claim 的子预算。 */
    public static final long ASSET_STORE_CLAIM_BUDGET_MS = 1_000L;
    /** 后台 Store：Finalize / ResolveManually（含事务外那一次不可变列读）的子预算；循环路径实际只有 settle 的 700 ms。 */
    public static final long ASSET_STORE_FINALIZE_BUDGET_MS = 2_000L;

    // ---- 后台写事务（asset_store.go:109-112；assetop/seq.go:252-260、:316-324） ----

    /** 后台写（重排 / 毒行 / 终结 / 人工终结）的总尝试次数；1213 / 9007 / 1205 都重跑。清理每行 1 次。 */
    public static final int BACKGROUND_TX_ATTEMPTS = 3;
    /** 后台写重跑退避的基数（指数、±20% 抖动）。 */
    public static final long BACKGROUND_TX_BASE_BACKOFF_MS = 10L;
    /** 后台写重跑退避的封顶。 */
    public static final long BACKGROUND_TX_MAX_BACKOFF_MS = 200L;

    // ---- 清理（asset_store.go:114-142） ----

    /** 每批候选行数；一批候选不足它即停。 */
    public static final int CLEANUP_BATCH_SIZE = 500;
    /** 批间停顿（给业务写事务让路）。 */
    public static final long CLEANUP_BATCH_PAUSE_MS = 100L;
    /** 每类每轮至多几批。 */
    public static final int CLEANUP_MAX_BATCHES = 20;
    /** 单行清理短事务（点锁 + 点删 + 提交）的上限。 */
    public static final long CLEANUP_STMT_BUDGET_MS = 2_000L;
    /** 计数行清理截止时刻离 now 的最小距离（死锁复核 C5 补遗）：保证上一周期在切周 / 切日后至少再留 24 h。 */
    public static final long MIN_COUNTER_CLEANUP_AGE_MS = 8L * 24 * 3_600_000L;
    /** 日键（8 位 YYYYMMDD）的下界。 */
    public static final int DAY_KEY_FLOOR = 19_700_101;
    /** 周键（6 位 YYYYWW）的下界；日键与周键数值域不相交，各走一段 BETWEEN。 */
    public static final int WEEK_KEY_FLOOR = 100_000;

    // ---- 资产通道调用方与重投循环（assetop/caller.go:23-32；reconcile.go:28-74、:278） ----

    /** 单次资产 RPC 的超时上限（实际取它与剩余预算的较小者）。 */
    public static final long ASSET_CALL_TIMEOUT_MS = 800L;
    /** 未 durable 时用同一请求重查的间隔（每次重签）。 */
    public static final List<Long> ASSET_REQUERY_DELAYS_MS = List.of(100L, 200L, 400L);
    /** 落库预留（settle）：从 OpBudget 切出，不随请求取消。 */
    public static final long ASSET_SETTLE_BUDGET_MS = 700L;
    /** 租约相对单行预算必须留出的余量：{@code lease ≥ op-budget + 它}。 */
    public static final long ASSET_LEASE_HEADROOM_MS = 2_000L;
    /** Workers 的上限。 */
    public static final int ASSET_MAX_WORKERS = 64;
    /** 「结局有了但还没落盘」的短重排间隔（不进配置）。 */
    public static final long ASSET_AWAIT_DURABLE_DELAY_MS = 500L;
    /** ListDue 第一段「新行」的判据：{@code attempts < 它}。 */
    public static final int ASSET_FRESH_ATTEMPT_LIMIT = 3;
    /** 最老未决行年龄的刷新间隔。 */
    public static final long ASSET_PENDING_AGE_INTERVAL_MS = 30_000L;
    /** 离线读已落盘账本的单次上限（E8）。 */
    public static final long ASSET_LEDGER_READ_TIMEOUT_MS = 300L;

    // ---- 人工终结（asset_store.go:612-615；assetopfix/main.go:53-71） ----

    /** resolved_by 的上限（按码点数）。 */
    public static final int RESOLVED_BY_MAX_CHARS = 64;
    /** resolve_reason 的上限（按码点数）。 */
    public static final int RESOLVE_REASON_MAX_CHARS = 191;

    // ---- 回档分歧检查的内部查询（asset_op_divergence_repo.go:34-39；guild_internal_server.go:38） ----

    /** player_ids 的 IN 占位符上限。 */
    public static final int APPLIED_OPS_MAX_PLAYER_IDS = 100;
    /** 单页行数上限；请求 limit = 0 时也取它。 */
    public static final int APPLIED_OPS_MAX_PAGE_LIMIT = 500;
    /** 保留期判定的安全余量：{@code cutoff = now + 它 − TerminalRetention}。 */
    public static final long APPLIED_OPS_RETENTION_SAFETY_MS = 3_600_000L;

    private GuildLimits() {
    }
}
