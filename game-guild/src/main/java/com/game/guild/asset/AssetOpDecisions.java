package com.game.guild.asset;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildDailyCounterKind;
import com.game.table.AssetErrorTip;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.random.RandomGenerator;

/**
 * 资产通道调用方的纯规则（基线 go/shared/assetop 的 decide.go、types.go:114-187、reconcile.go:530-536 / :613-633 / :746-752，以及
 * asset_store.go:762-777 的 counterRefund；guild-economy-spec §2.5、§2.8、§7.1「纯规则」）。
 *
 * <p>没有 I/O、没有时钟、没有自己的随机源（要用时由参数传进来），所以「四个结局 × 各种错误」的全表可以在单测里一次跑完
 * （AssetOpDecisionsTest 照 decide_test.go）。任意线程可调。
 */
public final class AssetOpDecisions {

    // ---- asset_error 段（27000 起）在帮会侧的唯一引用点（types.go:134-153）；码取自导表生成的枚举，不手写数字 ----

    /** 货币不足（捐献被 durable REJECTED 且是它 → 14025）。 */
    public static final int REASON_CURRENCY_INSUFFICIENT = AssetErrorTip.asset_error.kAssetCurrencyInsufficient_VALUE;
    /** 背包满（RETRY；兑换永不中止，背包满就一直等）。 */
    public static final int REASON_BAG_FULL = AssetErrorTip.asset_error.kAssetBagFull_VALUE;
    /** 战斗中（RETRY）。 */
    public static final int REASON_IN_BATTLE = AssetErrorTip.asset_error.kAssetInBattle_VALUE;
    /** 冻结 / 交接 / owner_epoch 为 0（RETRY）。 */
    public static final int REASON_FROZEN = AssetErrorTip.asset_error.kAssetFrozen_VALUE;
    /** 包非法（REJECTED 或 UNKNOWN）。 */
    public static final int REASON_INVALID_BUNDLE = AssetErrorTip.asset_error.kAssetInvalidBundle_VALUE;
    /** 封禁 / 账本损坏。 */
    public static final int REASON_BLOCKED = AssetErrorTip.asset_error.kAssetBlocked_VALUE;
    /** 不在本节点。 */
    public static final int REASON_PLAYER_NOT_HERE = AssetErrorTip.asset_error.kAssetPlayerNotHere_VALUE;
    /**
     * 部分发放。它既会出现在本次答复里，也可能是<b>上一次</b>答复留在行上的 last_reason：scene 的只读答复只在 seq 仍留在账本 partial_seqs
     * 环里时才带 partial，环会丢；丢了之后行上的 last_reason 是「这一笔曾经只发了一半」的唯一证据，所以在行上必须是粘性的
     * （{@link #carryPartialReason}），{@link #finalStatus} 同时看两处（types.go:136-151）。
     */
    public static final int REASON_PARTIAL_APPLIED = AssetErrorTip.asset_error.kAssetPartialApplied_VALUE;
    /** 验签失败（UNKNOWN）。 */
    public static final int REASON_AUTH_FAILED = AssetErrorTip.asset_error.kAssetAuthFailed_VALUE;

    /** 退避抖动缺省值（rnd 为 null 时取中值，不抖动）。 */
    private static final double JITTER_MIDPOINT = 0.5;
    /** 退避移位上限：只防溢出，真正封顶的是 max。 */
    private static final int MAX_BACKOFF_SHIFT = 16;
    private static final long DEFAULT_BASE_BACKOFF_MS = 1_000L;

    private AssetOpDecisions() {
    }

    // ================================================================ Decide / FinalStatus

    /**
     * 把一次投递的结果翻译成唯一的动作（Decide，decide.go:53-75）。判断顺序有讲究：
     * <ol>
     *   <li>传输层错误优先——根本不知道 scene 做了什么，只能重试（同 seq 重投是安全的，scene 只读答复）；</li>
     *   <li>结局翻转（{@link AssetOutcomeFlipException}，沿 cause 链找）与 UNKNOWN 单独告警，不能混进「重试」里被淹没；</li>
     *   <li>剩下的才按结局分。未来新增结局枚举落到 default 的 ALERT：宁可卡住 + 告警，也不静默当成功或重试（fail-closed）。</li>
     * </ol>
     *
     * @param res   scene 的结果；error 非 null 时忽略。两者都为 null 按 UNKNOWN（ALERT）
     * @param error 传输失败（Dubbo 异常、超时、定位故障）或结局翻转；null = 拿到了答复
     */
    public static AssetOpAction decide(AssetOpResult res, Throwable error) {
        if (error != null) {
            return isOutcomeFlip(error) ? AssetOpAction.ALERT : AssetOpAction.RETRY;
        }
        if (res == null) {
            return AssetOpAction.ALERT;
        }
        return switch (res.outcome()) {
            case ASSET_OUTCOME_UNKNOWN -> AssetOpAction.ALERT;
            case ASSET_OUTCOME_APPLIED, ASSET_OUTCOME_REJECTED ->
                    res.durable() ? AssetOpAction.FINALIZE : AssetOpAction.AWAIT_DURABLE;
            case ASSET_OUTCOME_RETRY, ASSET_OUTCOME_NOT_HERE -> AssetOpAction.RETRY;
            default -> AssetOpAction.ALERT;
        };
    }

    /** error 的 cause 链上是否有 {@link AssetOutcomeFlipException}（errors.Is(err, ErrOutcomeFlip)）。 */
    public static boolean isOutcomeFlip(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof AssetOutcomeFlipException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * 把一个终结结局映射成 outbox 行的最终状态（FinalStatus，decide.go:77-117）。只应在 {@link #decide} 返回 FINALIZE 后调用；
     * 其它情况返回 {@link AssetOpStatus#PENDING}，调用方必须当 bug 按 ALERT 重排，绝不能照着写库。
     *
     * <ul>
     *   <li>APPLIED：本次 partial、行上 last_reason 或本次 reason 是 27007 → APPLIED_PARTIAL，否则 APPLIED；</li>
     *   <li>REJECTED：Abort 且 reason == 0（中止占位）→ ABORTED，否则 REJECTED。<b>已知并接受的退化</b>：拒绝原因环（64 格）挤掉后，
     *       真业务拒绝在 Abort 重投时落成 ABORTED——账务相同（都退），只丢文案，不要为它加字段（decide.go:79-92）。</li>
     * </ul>
     */
    public static AssetOpStatus finalStatus(AssetRpc rpc, AssetOpResult res, AssetOp op) {
        Objects.requireNonNull(res, "res");
        return switch (res.outcome()) {
            case ASSET_OUTCOME_APPLIED -> res.partial() || op.lastReason() == REASON_PARTIAL_APPLIED
                    || res.reason() == REASON_PARTIAL_APPLIED ? AssetOpStatus.APPLIED_PARTIAL : AssetOpStatus.APPLIED;
            case ASSET_OUTCOME_REJECTED -> rpc == AssetRpc.ABORT_DEBIT && res.reason() == 0
                    ? AssetOpStatus.ABORTED : AssetOpStatus.REJECTED;
            default -> AssetOpStatus.PENDING;
        };
    }

    // ================================================================ 方向

    /**
     * 某条流的「正常投递」方法（ApplyRPCOf，types.go:113-132）：*_DEBIT → DEBIT，*_CREDIT 与 SYSTEM_CREDIT → CREDIT。
     * 空 = 流号非法（未指定或越界），调用方必须当坏行按 ALERT 重排，<b>绝不猜方向</b>——猜错就是把扣钱发成发钱。
     */
    public static Optional<AssetRpc> applyRpcOf(int stream) {
        AssetStream s = AssetStream.forNumber(stream);
        if (s == null) {
            return Optional.empty();
        }
        return switch (s) {
            case ASSET_STREAM_GUILD_DEBIT, ASSET_STREAM_TRADE_DEBIT -> Optional.of(AssetRpc.DEBIT);
            case ASSET_STREAM_GUILD_CREDIT, ASSET_STREAM_TRADE_CREDIT, ASSET_STREAM_SYSTEM_CREDIT ->
                    Optional.of(AssetRpc.CREDIT);
            default -> Optional.empty();
        };
    }

    /**
     * 这次发哪个方法（rpcFor，reconcile.go:530-536）：截止非 0 且 {@code now ≥ deadline}（无符号）→ ABORT_DEBIT；否则按流的方向。
     */
    public static Optional<AssetRpc> rpcFor(AssetOp op, long nowMs) {
        if (op.deadlineMs() != 0 && Long.compareUnsigned(nowMs, op.deadlineMs()) >= 0) {
            return Optional.of(AssetRpc.ABORT_DEBIT);
        }
        return applyRpcOf(op.stream());
    }

    // ================================================================ 退避

    /**
     * 下一次投递时刻：指数退避 + ±20% 抖动（NextAttemptMs，decide.go:119-156）。
     * {@code now + min(base << min(attempts, 16), max) × (0.8 + 0.4·rnd)}。
     *
     * <ul>
     *   <li>{@code baseMs ≤ 0} 按 1 s；{@code maxMs < baseMs} 按 base；移位溢出按 max；</li>
     *   <li>rnd 为 null 取 0.5（中值，不抖动）；越界（NaN、负数、≥ 1）夹回 [0, 1)。生产传 {@code ThreadLocalRandom::nextDouble}（E10：基线
     *       {@code Loop.Rand} 从未赋值、生产不抖动，Java 按配表与代码注释的设计意图启用抖动）。</li>
     * </ul>
     *
     * @param attempts 已投递次数（uint32 位模式）
     */
    public static long nextAttemptMs(long nowMs, int attempts, long baseMs, long maxMs, DoubleSupplier rnd) {
        long base = baseMs <= 0 ? DEFAULT_BASE_BACKOFF_MS : baseMs;
        long max = Math.max(maxMs, base);
        int shift = Integer.compareUnsigned(attempts, MAX_BACKOFF_SHIFT) > 0 ? MAX_BACKOFF_SHIFT : attempts;
        long shifted = base << shift;
        long delay = shifted > 0 && (shifted >>> shift) == base && shifted <= max ? shifted : max;

        double jitter = rnd == null ? JITTER_MIDPOINT : rnd.getAsDouble();
        if (Double.isNaN(jitter) || jitter < 0) {
            jitter = 0;
        } else if (jitter >= 1) {
            jitter = Math.nextDown(1.0);
        }
        double scaled = delay * (0.8 + 0.4 * jitter);
        return nowMs + (long) scaled;
    }

    // ================================================================ 重排前的粘性

    /**
     * 让 outbox 行上的 last_reason 对「曾见部分发放」保持粘性（carryPartialReason，reconcile.go:613-633）：行上已有 27007 而本次 reason
     * 不是 27007 时强制写 27007。传输失败不覆盖 last_reason（E12），但本地 NOT_HERE、坏流号的重排带的 reason 都是 0，玩家下线一次
     * 就能把 27007 抹平；之后 scene 若不再回 partial，这一行会被当成 APPLIED 终结并做<b>全额</b>对侧账。
     * 只作用于重排；终结那一侧的 {@link #finalStatus} 直接读 {@code op.lastReason()}。
     */
    public static AssetOpResult carryPartialReason(AssetOp op, AssetOpResult res) {
        if (op.lastReason() == REASON_PARTIAL_APPLIED && res.reason() != REASON_PARTIAL_APPLIED) {
            return res.withReason(REASON_PARTIAL_APPLIED);
        }
        return res;
    }

    // ================================================================ 对侧账的退款判定

    /**
     * 本次终结要退的计数（退哪类计数、退几份）。
     *
     * @param kind  退哪类计数
     * @param count 退几份（uint32，&gt; 0）
     */
    public record CounterRefund(GuildDailyCounterKind kind, int count) {
    }

    /**
     * 本次终结要不要退次数 / 退限购（counterRefund，asset_store.go:762-777）。<b>判定只此一处</b>：终结的 seq 行守卫（死锁复核 C6）与
     * 退款本身都按它走，两边条件一分叉就会出现「退了款却没先锁 seq 行」。
     * <ul>
     *   <li>只有 REJECTED / ABORTED 退；APPLIED / APPLIED_PARTIAL 不退；period_key == 0（不限购的商品，当初没占计数行）不退；</li>
     *   <li>DONATE 退今日次数 1 次；SHOP 退限购 ref_count 份（0 份不退）；其余 kind（活动发奖、未知）不退。</li>
     * </ul>
     */
    public static Optional<CounterRefund> counterRefund(GuildAssetOpKind kind, int refCount, int periodKey,
                                                        AssetOpStatus status) {
        if ((status != AssetOpStatus.REJECTED && status != AssetOpStatus.ABORTED) || periodKey == 0) {
            return Optional.empty();
        }
        return switch (kind) {
            case GUILD_ASSET_OP_KIND_DONATE ->
                    Optional.of(new CounterRefund(GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_DONATE, 1));
            case GUILD_ASSET_OP_KIND_SHOP -> refCount != 0
                    ? Optional.of(new CounterRefund(GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_SHOP, refCount))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    // ================================================================ 令牌

    /**
     * 生成一个租约令牌（newLeaseToken，reconcile.go:746-752；economy_logic.go:403-413）：64 位随机数、最低位置 1 保证非 0
     * （0 在表里表示「没有租约」）。约一半 ≥ 2^63，落库一律按无符号绑定。生产传 {@link java.security.SecureRandom}：令牌是
     * 「这次领取是不是我」的唯一凭据，可预测的令牌会让两个副本撞上同一个值、CAS 形同虚设。
     */
    public static long leaseToken(RandomGenerator random) {
        return random.nextLong() | 1L;
    }

    /** 结局是否是 scene 的终结结局（APPLIED / REJECTED；isSceneTerminal，caller.go:232-235），不看 durable。 */
    public static boolean isSceneTerminal(AssetOutcome outcome) {
        return outcome == AssetOutcome.ASSET_OUTCOME_APPLIED || outcome == AssetOutcome.ASSET_OUTCOME_REJECTED;
    }
}
