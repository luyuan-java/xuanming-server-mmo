package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.proto.match.BattleWatchSummary;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 观战的纯函数（spectate-spec §4.1、§10.2）：建房窗口判定、列表条数收口、过期分界、落点 → 摘要、观战标记的编解码、索引成员的规范写法，
 * 以及 163 / 开局清退 / 清退原因共用的小常量。不碰 Redis、不记指标、无状态；全部线程安全。
 */
public final class SpectateRules {

    // ---------------------------------------------------------------- 清退的原因（写进 RemoveObserverRequest.reason，battle 只记日志）

    /** 163 入口处清掉旧标记（换场 / 随机观战）。与指标 {@code EvictReason.REWATCH} 的标签值逐字相同。 */
    public static final String REASON_REWATCH = "rewatch";
    /** 开局前清退正在观战的参战者。与 {@code EvictReason.ENTER_GATHER} 的标签值逐字相同。 */
    public static final String REASON_ENTER_GATHER = "enter_gather";
    /** 163 登记成功后的复查命中票据或战斗锁，自我清退。与 {@code EvictReason.CONCURRENT_QUEUE} 的标签值逐字相同。 */
    public static final String REASON_CONCURRENT_QUEUE = "concurrent_queue";

    /** 标记值里 nonce 的长度：16 位小写十六进制（64 位随机数）。 */
    public static final int NONCE_LENGTH = 16;

    private static final HexFormat HEX = HexFormat.of();

    private SpectateRules() {
    }

    // ---------------------------------------------------------------- 建房窗口（基线 watchbattlelogic.go:283-318）

    /**
     * AddObserver 回「房间不存在」（或直拨判死）时，房间是不是<b>可能只是还没建好</b>——是的话不能剔除：gather 在建房<b>之前</b>就写了落点记录，
     * 换节点重试时还会改写它；这时删掉记录，一场即将开打的战斗就丢了补签的定位。两个条件同时成立才算「可能在建」：
     * <ol>
     *   <li>读落点的那一刻这一场<b>还没公开</b>（公开在开局成功、落点按最终 attempt 补写之后：已公开 ⇒ 房间在公开前就已建成，此时的「不存在」是真收尾）；</li>
     *   <li>读的那一刻距落点的 {@code created_at_ms} 不超过 {@link MatchBudgets#GATHER_CREATE_STAGE_WORST_MS}（gather 从写落点到最后一次建房返回的
     *       最坏耗时，22.2 s）。出了窗口，房间还没建成则 gather 按预算已放弃，剔除正确。</li>
     * </ol>
     * {@code created_at_ms = 0}（不该出现的旧记录）按窗口外；读的时刻<b>早于</b>创建时刻（两个时钟源有偏差）按窗口内（宁可晚剔除）。
     * 三个入参必须来自<b>同一次</b>原子读（{@link SpectateStore#read} 的 {@code published} / {@code record} / {@code redisNowMs}）——
     * 先读落点、后查索引会把「改写到重试节点之后才公开」的场次误判成已公开（W6）。
     *
     * @param published   读的那一刻是否已在可观战索引里（随机选场挑出来的成员恒为 true）
     * @param createdAtMs 落点记录的 {@code created_at_ms}（Redis 时间，Unix 毫秒，按无符号比较）
     * @param checkedAtMs 读的那一刻的 Redis 时间
     */
    public static boolean roomMayBeCreating(boolean published, long createdAtMs, long checkedAtMs) {
        if (published) {
            return false;
        }
        if (createdAtMs == 0) {
            return false;
        }
        if (Long.compareUnsigned(checkedAtMs, createdAtMs) > 0
                && Long.compareUnsigned(checkedAtMs - createdAtMs, MatchBudgets.GATHER_CREATE_STAGE_WORST_MS) > 0) {
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 列表与过期

    /**
     * 164 的条数收口（基线 {@code listwatchablebattleslogic.go:39-45}）：0 → {@value MatchBudgets#WATCHABLE_LIST_DEFAULT}；
     * 大于 {@value MatchBudgets#WATCHABLE_LIST_MAX} → {@value MatchBudgets#WATCHABLE_LIST_MAX}；其余原样。
     *
     * @param requested 请求里的 {@code limit}（uint32 的位模式：负数就是大于 2^31 的值，收到上限）
     * @return [1, {@value MatchBudgets#WATCHABLE_LIST_MAX}] 里的值
     */
    public static int clampLimit(int requested) {
        if (requested == 0) {
            return MatchBudgets.WATCHABLE_LIST_DEFAULT;
        }
        if (requested < 0 || requested > MatchBudgets.WATCHABLE_LIST_MAX) {
            return MatchBudgets.WATCHABLE_LIST_MAX;
        }
        return requested;
    }

    /**
     * 过期分界：索引成员的分数（{@code created_at_ms}）<b>严格小于</b>它就是残留（那一场必已按 300 s 的期限收尾）。
     * = Redis 时间 − {@value MatchBudgets#SPECTATE_STALE_MS} ms。随机选场、列表、清扫同一个口径。
     */
    public static long staleCutoff(long redisNowMs) {
        return redisNowMs - MatchBudgets.SPECTATE_STALE_MS;
    }

    /** 这个分数的成员在 {@code redisNowMs} 时是否已过期（{@code score < staleCutoff}；恰在分界上的不算）。 */
    public static boolean stale(long score, long redisNowMs) {
        return score < staleCutoff(redisNowMs);
    }

    /**
     * 落点记录 → 客户端可见的观战摘要（{@code ListWatchableBattlesResponse.battles} 的一项；规格 §3.3）：{@code battle_id}、{@code battle_config_id}、
     * {@code created_at_ms}、{@code player_names}（顺序 = gather 的成员顺序）原样；{@code mode} 按数值写入（契约里没有的值也原样保留，不折成 0）。
     * 客户端用墙钟减 {@code created_at_ms} 算已开局时长，所以它必须是 Unix 毫秒——落点里存的是 Redis {@code TIME}，同量纲。
     */
    public static BattleWatchSummary summaryOf(BattlePlacement placement) {
        Objects.requireNonNull(placement, "placement");
        return BattleWatchSummary.newBuilder()
                .setBattleId(placement.getBattleId())
                .setModeValue(placement.getMode())
                .setBattleConfigId(placement.getBattleConfigId())
                .addAllPlayerNames(placement.getPlayerNamesList())
                .setCreatedAtMs(placement.getCreatedAtMs())
                .build();
    }

    // ---------------------------------------------------------------- 索引成员

    /** 索引成员的规范写法：battle_id 的无符号十进制（{@code Long.toUnsignedString}）。 */
    public static String member(long battleId) {
        return Long.toUnsignedString(battleId);
    }

    /**
     * 解析索引成员。只认 {@link #member} 写出来的<b>规范形</b>：非 0 的无符号 64 位十进制，没有符号、空白与前导零。其余一律为空——
     * 调用方按非法成员剔除（{@link SpectateStore.Eviction.Invalid}，按<b>原串</b> {@code ZREM}）。
     *
     * <p>比基线的 {@code strconv.ParseUint} 严（它接受前导零）：{@code "007"} 这样的成员若当成 7 号战斗，之后按战斗号做的剔除摘的是 {@code "7"}，
     * 这个成员就永远留在索引里；按非法成员处理才摘得掉。正常写者只写规范形，差别只在脏数据上。
     */
    public static OptionalLong parseMember(String member) {
        long id = canonicalUnsigned(member);
        return id == 0 ? OptionalLong.empty() : OptionalLong.of(id);
    }

    // ---------------------------------------------------------------- 观战标记

    /**
     * 一个观战标记解出来的两段。
     *
     * @param battleId 这名玩家可能正在看的战斗（非 0）
     * @param nonce    写下这个标记的那一次 163 的随机串（{@value #NONCE_LENGTH} 位小写十六进制）
     */
    public record Mark(long battleId, String nonce) {
    }

    /**
     * 标记的值：{@code "<battle_id 无符号十进制>:<nonce>"}。带 nonce 是为了两件事：抢标记的脚本被 Redis 客户端重发时认得出自己写的值（不误报 16016）；
     * 删标记按整串比较，两条并发 163 不会删掉对方刚抢到的标记（W3）。
     *
     * @throws IllegalArgumentException {@code battleId} 为 0，或 {@code nonce} 不是 {@link #newNonce} 的形状
     */
    public static String encodeMark(long battleId, String nonce) {
        if (battleId == 0) {
            throw new IllegalArgumentException("观战标记必须带 battle_id");
        }
        if (!validNonce(nonce)) {
            throw new IllegalArgumentException("nonce 必须是 " + NONCE_LENGTH + " 位小写十六进制: " + nonce);
        }
        return member(battleId) + ":" + nonce;
    }

    /**
     * {@link #encodeMark} 的逆。只认它写出来的形状；其余（空串、没有冒号、battle_id 不是规范的非 0 十进制、nonce 形状不对、
     * 基线那种只有 battle_id 的旧值）一律为空——调用方把这样的标记当脏值，按读到的<b>原串</b>删掉（163 第 7 行「值非法 → 删」）。
     */
    public static Optional<Mark> decodeMark(String value) {
        if (value == null) {
            return Optional.empty();
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon != value.lastIndexOf(':')) {
            return Optional.empty();
        }
        long battleId = canonicalUnsigned(value.substring(0, colon));
        String nonce = value.substring(colon + 1);
        if (battleId == 0 || !validNonce(nonce)) {
            return Optional.empty();
        }
        return Optional.of(new Mark(battleId, nonce));
    }

    /** 一个新的 nonce：{@value #NONCE_LENGTH} 位小写十六进制（64 位随机数；不要求密码学强度，只要求同一玩家的两次请求不撞）。 */
    public static String newNonce() {
        return HEX.toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    // ---------------------------------------------------------------- 预算（163 与开局清退共用的两步算术）

    /**
     * 「{@code request} 之前留出 {@code reserveMs}」的硬截止：交给 {@link ObserverDialer} 的 {@code hardStop}。
     * {@code request} 的剩余不足 {@code reserveMs} 时得到一个已经过了的截止（直拨器据此不发调用）。
     *
     * @param reserveMs 要留出来的毫秒数（163 换场用 {@link MatchBudgets#WATCH_REWATCH_RESERVE_MS}，登记用 {@link MatchBudgets#WATCH_ADD_RESERVE_MS}；
     *                  开局清退不留，直接用每人的截止）
     */
    public static Deadline reserveBefore(Deadline request, long reserveMs) {
        Objects.requireNonNull(request, "request");
        if (reserveMs < 0) {
            throw new IllegalArgumentException("预留不能为负: " + reserveMs);
        }
        return Deadline.after(Math.max(0, request.remainingMillis() - reserveMs));
    }

    /**
     * 一跳观众 RPC 的超时：{@code min(capMs, hardStop 的剩余)}（规格 §4.4「{@code min(3 s, 剩余预算 − 预留)}」）。
     *
     * @param capMs 这一跳的基线超时（{@link MatchBudgets#ADD_OBSERVER_TIMEOUT_MS} / {@link MatchBudgets#REMOVE_OBSERVER_TIMEOUT_MS}）
     */
    public static Duration hopTimeout(long capMs, Deadline hardStop) {
        Objects.requireNonNull(hardStop, "hardStop");
        return Duration.ofMillis(Math.max(0, Math.min(capMs, hardStop.remainingMillis())));
    }

    // ---------------------------------------------------------------- 内部

    private static boolean validNonce(String nonce) {
        if (nonce == null || nonce.length() != NONCE_LENGTH) {
            return false;
        }
        for (int i = 0; i < nonce.length(); i++) {
            char c = nonce.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** 规范的无符号 64 位十进制（只有 ASCII 数字、没有前导零、不溢出）的值；不是规范形、或值为 0 都返回 0。 */
    private static long canonicalUnsigned(String text) {
        if (text == null || text.isEmpty() || text.length() > 20 || text.charAt(0) == '0') {
            return 0;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
        }
        try {
            return Long.parseUnsignedLong(text);
        } catch (NumberFormatException e) { // 20 位但超过 2^64 − 1
            return 0;
        }
    }
}
