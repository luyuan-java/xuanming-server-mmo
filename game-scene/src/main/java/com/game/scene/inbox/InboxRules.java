package com.game.scene.inbox;

import com.game.player.store.inbox.InboxLedgerRules;
import com.game.player.store.state.InboxDecisionState;
import com.game.player.store.state.InboxDomainCursorState;
import com.game.player.store.state.InboxLedgerState;
import com.game.proto.player_inbox.PlayerInboxDirective;

/**
 * 收件箱消费端的纯规则（mmorpg C++ {@code player_inbox_rules.{h,cpp}}，设计 §4.4、§7.3–§7.7）：零场景状态、零时钟、零随机数，可单测。
 * 只回答「一条投递是什么（新 / 回放 / UNKNOWN / MISMATCH）」「接下来怎么处理」「一条已定结局此刻算不算已落盘」「退避多久」几个问题。
 * 账本结构校验与按字段查找用 game-player-store 的 {@link InboxLedgerRules}（与 game-inbox 共用同一份）。seq / request_id 都是 uint64，按无符号比较。
 */
public final class InboxRules {

    /** 每批最多拉多少条（FetchPlayerInboxRequest.max_items）。最近结局环装得下「一批在途确认、一批刚落盘」的前提。 */
    public static final int FETCH_BATCH_MAX = 16;

    static {
        if (FETCH_BATCH_MAX * 2 > InboxLedgerRules.RECENT_CAPACITY) {
            throw new IllegalStateException("最近结局环必须装得下至少两批：一批在途确认、一批刚落盘");
        }
    }

    /** 去抖：两次检查至少间隔 1 秒（新落地、战斗解冻除外）。 */
    public static final long MIN_CHECK_GAP_MS = 1000;
    /** 前置闸 / 传输失败的退避下限与上限。 */
    public static final long BACKOFF_MIN_MS = 1000;
    public static final long BACKOFF_MAX_MS = 60_000;
    /** 围栏判 STALE：60 秒。 */
    public static final long STALE_OWNER_BACKOFF_MS = 60_000;
    /** 单个 tick 最多发起的检查数。 */
    public static final int TICK_CHECK_BUDGET = 200;
    /** 等落盘期间补请求存盘的间隔。 */
    public static final long RESAVE_INTERVAL_MS = 5000;
    /** 记账后请求存盘的节流（同资产通道的 500 ms）。 */
    public static final long PERSIST_THROTTLE_MS = 500;
    /** 抖动参数取值 [0, 1000]。 */
    public static final int JITTER_PERMILLE_MAX = 1000;
    /** 在线兜底 ±20%。 */
    public static final int PERIODIC_JITTER_PERCENT = 20;
    /**
     * scene 日志告警的窗口（同 mmorpg：{@code count_over_time(... |= "metric=..." [10m]) > 0}）。问题还在时相邻两条告警行的间隔必须落在窗口之内，
     * 否则告警会「亮 10 分钟、灭几十分钟」地反复自愈。
     */
    public static final long SCENE_LOG_ALERT_WINDOW_MS = 10 * 60_000L;
    /** 节拍之外留给 tick 粒度与单 tick 检查预算造成的顺延的余量。 */
    public static final long STUCK_WARN_SLACK_MS = 2 * 60_000L;
    /** 不会自愈的延后（账本损坏）每连续失败这么多次再打一次 WARN（退避封顶后至多 8 × 60 s）。 */
    public static final int STUCK_WARN_EVERY_FAILURES = 8;
    /** 整个节点的停用（注册表自检失败、消费开关关着）按时间补打：每 5 分钟一次。 */
    public static final long NODE_STUCK_RELOG_INTERVAL_MS = 5 * 60_000L;

    static {
        if (STUCK_WARN_EVERY_FAILURES * BACKOFF_MAX_MS + STUCK_WARN_SLACK_MS > SCENE_LOG_ALERT_WINDOW_MS
                || NODE_STUCK_RELOG_INTERVAL_MS + STUCK_WARN_SLACK_MS > SCENE_LOG_ALERT_WINDOW_MS) {
            throw new IllegalStateException("卡住时两条 WARN 的最长间隔必须落在 scene 日志告警窗口之内");
        }
    }

    private static final int BACKOFF_JITTER_PERCENT = 20;
    /** 1s &lt;&lt; 6 = 64s，随即被 60s 封顶。 */
    private static final int BACKOFF_MAX_SHIFT = 6;

    private InboxRules() {
    }

    // ================================================================ 投递分类（设计 §7.6 第 3 步，IB-9）

    /** 一条投递在账本里的分类。 */
    public enum DeliveryClass {
        /** seq &gt; decided_through：交给执行器。 */
        NEW,
        /** seq ≤ decided_through，环里有同一 seq 且 request_id、msg_type 都一致：回放原结局。 */
        REPLAY,
        /** seq ≤ decided_through，环里有同一 seq 但 request_id 或 msg_type 不一致：什么都不确认，停用。 */
        MISMATCH,
        /** seq ≤ decided_through，环里已没有这一条：报 UNKNOWN。 */
        UNKNOWN
    }

    /**
     * 分类结果。{@code recorded}：REPLAY 时是环里的原结局（原样回放）；MISMATCH 时是环里同一 seq 的那一条，<b>只供日志对照</b>（值班据它的
     * request_id / msg_type 预判能否走 VoidItem 的 FORCE_MISMATCH），绝不能拿它确认；NEW / UNKNOWN 为 null。
     */
    public record Classified(DeliveryClass deliveryClass, InboxDecisionState recorded) {
    }

    /** cursor 为 null 时一律 NEW。 */
    public static Classified classify(InboxDomainCursorState cursor, long seq, long requestId, int msgType) {
        if (cursor == null || Long.compareUnsigned(seq, cursor.getDecidedThroughSeq()) > 0) {
            return new Classified(DeliveryClass.NEW, null);
        }
        InboxDecisionState recorded = InboxLedgerRules.findRecent(cursor, seq);
        if (recorded == null) {
            return new Classified(DeliveryClass.UNKNOWN, null);
        }
        if (recorded.getRequestId() != requestId || recorded.getMsgType() != msgType) {
            return new Classified(DeliveryClass.MISMATCH, recorded);
        }
        return new Classified(DeliveryClass.REPLAY, recorded);
    }

    /** 一条投递接下来怎么处理（设计 §7.6、§7.9，评审 M1）。 */
    public enum ItemAction {
        /** 新条目、无指令：交给执行器；本二进制没有该类型的执行器时由调用方停用该域。 */
        EXECUTE,
        /** 新条目、VOID：记 SKIPPED（reason = void_reason），不查执行器、不解码载荷。 */
        SKIP_VOIDED,
        /** 回放账本环里的原结局。 */
        REPLAY,
        /** 报 UNKNOWN（只进确认、不入环）。 */
        ACK_UNKNOWN,
        /** 什么都不确认，停用（IB-9）。 */
        HALT_MISMATCH,
        /** 新条目带本二进制不认识的指令：版本错位，停用，不猜。 */
        HALT_UNKNOWN_DIRECTIVE
    }

    /**
     * 判定顺序是这个函数存在的全部理由：<b>先按账本分类，只有新条目才看指令，执行器留给调用方最后查</b>。
     * <ul>
     *   <li>回放 / UNKNOWN / MISMATCH 只核对或报告账本里已有的事实，与指令、与本二进制认不认识这个类型都无关。反过来不能把 VOID 判定提到分类之前：
     *       已执行过的条目被补挂 VOID 后重投，必须回放原结局（IB-4、IB-9），报成 SKIPPED 会让服务端把已生效的处置终结成「跳过」；</li>
     *   <li>新条目带 VOID 时直接记 SKIPPED，不需要执行器：否则一条本二进制不认识类型的条目即使被运维作废，每次登录仍会先撞上「未知类型」再次停用该域。</li>
     * </ul>
     *
     * @param directive {@code PlayerInboxDirective} 的数值
     */
    public static ItemAction plan(DeliveryClass deliveryClass, int directive) {
        switch (deliveryClass) {
            case REPLAY -> {
                return ItemAction.REPLAY;
            }
            case UNKNOWN -> {
                return ItemAction.ACK_UNKNOWN;
            }
            case NEW -> {
                // 往下看指令
            }
            default -> {
                return ItemAction.HALT_MISMATCH;
            }
        }
        if (directive == PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_VOID_VALUE) {
            return ItemAction.SKIP_VOIDED;
        }
        if (directive != PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_NONE_VALUE) {
            return ItemAction.HALT_UNKNOWN_DIRECTIVE;
        }
        return ItemAction.EXECUTE;
    }

    // ================================================================ 落盘判定（设计 §7.7）

    /** 一条已定结局在最近一次确认落库的存档里的状态。 */
    public enum Durability {
        /** 可以确认。 */
        DURABLE,
        /** 存档还没追上：继续等落盘。 */
        NOT_YET,
        /** 存档里同一 seq 的结局与内存不同：结局固定被破坏，只可能是缺陷，不报 durable。 */
        OUTCOME_DIFFERS,
        /** 存档水位已过这一条，但环里找不到它（按「环装得下」的推导不可能发生）：不报 durable。 */
        RING_MISSING
    }

    /**
     * persisted 是最近一次<b>确认落库</b>的那份账本（Java 的「已落盘」= 带 owner_epoch 条件的 player_state 提交成功，比 mmorpg 的 Redis 落盘强）：
     * <ul>
     *   <li>APPLIED / REJECTED / SKIPPED：存档里该域 decided_through ≥ seq，且环里同一 seq 的 outcome、reason、request_id、msg_type 都与内存一致；</li>
     *   <li>UNKNOWN：只要存档里该域 decided_through ≥ seq 就算落盘，<b>不要求</b>环里有这一条（UNKNOWN 按定义就不在环里）。</li>
     * </ul>
     */
    public static Durability durability(InboxLedgerState persisted, int domain, InboxDecisionState decision) {
        InboxDomainCursorState cursor = InboxLedgerRules.findCursor(persisted, domain);
        if (cursor == null || Long.compareUnsigned(cursor.getDecidedThroughSeq(), decision.getSeq()) < 0) {
            return Durability.NOT_YET;
        }
        if (decision.getOutcome() == InboxLedgerRules.OUTCOME_UNKNOWN) {
            return Durability.DURABLE;
        }
        InboxDecisionState onDisk = InboxLedgerRules.findRecent(cursor, decision.getSeq());
        if (onDisk == null) {
            return Durability.RING_MISSING;
        }
        if (onDisk.getOutcome() != decision.getOutcome() || onDisk.getReason() != decision.getReason()
                || onDisk.getRequestId() != decision.getRequestId() || onDisk.getMsgType() != decision.getMsgType()) {
            return Durability.OUTCOME_DIFFERS;
        }
        return Durability.DURABLE;
    }

    // ================================================================ 时长

    /**
     * 第 failStreak 次连续失败后的退避：1s、2s、4s … 封顶 60s，再乘 [0.8, 1.2] 的抖动，结果夹在 [1s, 60s]。failStreak 为 0 按 1 处理。
     */
    public static long backoffMs(int failStreak, int jitterPermille) {
        int streak = Math.max(failStreak, 1);
        int shift = Math.min(streak - 1, BACKOFF_MAX_SHIFT);
        long base = Math.min(BACKOFF_MIN_MS << shift, BACKOFF_MAX_MS);
        long jittered = applyJitter(base, jitterPermille, BACKOFF_JITTER_PERCENT);
        return Math.min(Math.max(jittered, BACKOFF_MIN_MS), BACKOFF_MAX_MS);
    }

    /** 在线有界兜底的间隔：minutes 分钟 ±20%；minutes == 0 = 关闭，返回 0。 */
    public static long periodicDelayMs(int minutes, int jitterPermille) {
        if (minutes <= 0) {
            return 0;
        }
        return applyJitter(minutes * 60_000L, jitterPermille, PERIODIC_JITTER_PERCENT);
    }

    /** 不会自愈的延后原因是否到了再打一次 WARN：failStreak 为 8 的正整数倍（「首次」由调用方按原因刚变化判定）。 */
    public static boolean stuckWarnDue(int failStreak) {
        return failStreak != 0 && failStreak % STUCK_WARN_EVERY_FAILURES == 0;
    }

    /** base × [1 − spread%, 1 + spread%]，抖动位置由 jitterPermille ∈ [0, 1000] 线性决定。 */
    static long applyJitter(long baseMs, int jitterPermille, int spreadPercent) {
        long clamped = Math.min(Math.max(jitterPermille, 0), JITTER_PERMILLE_MAX);
        long low = 1000 - spreadPercent * 10L;
        long span = spreadPercent * 20L;
        long factor = low + span * clamped / JITTER_PERMILLE_MAX;
        return baseMs * factor / 1000;
    }
}
