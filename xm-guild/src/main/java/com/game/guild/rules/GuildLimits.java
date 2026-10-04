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

    private GuildLimits() {
    }
}
