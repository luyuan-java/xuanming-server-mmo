package com.game.player.store.inbox;

import com.game.player.store.state.InboxDecisionState;
import com.game.player.store.state.InboxDomainCursorState;
import com.game.player.store.state.InboxLedgerState;
import java.util.HashSet;
import java.util.Set;

/**
 * 玩家持久收件箱消费账本（{@code player_state.inbox_ledger}）的<b>只读</b>规则：结构校验与按字段查找。
 * scene 的在线账本（{@code com.game.scene.inbox.InboxLedger}，只在场景逻辑线程上读写）与 game-inbox 读已落盘账本
 * （强制作废的证明、两个对账入口）共用这一份代码，口径分叉无从发生（mmorpg 是 C++ {@code player_inbox_rules::ValidateLedger}
 * 与 Go {@code validLedger} 两份、靠测试对拍；PARITY 收件箱行第 ⑤ 条）。
 *
 * <p>账本形状（mmorpg {@code docs/design/player-inbox-service.md} §4.4）：每个域一条游标（domain 非 0 且唯一）；
 * {@code decided_through_seq} = 已按 seq 升序处理到哪；最近结局环按 seq 严格升序、seq 非 0、都不超过水位、结局可记账、
 * 至多 {@link #RECENT_CAPACITY} 条。seq / request_id 是 uint64，放在 long 里按无符号比较。
 *
 * <p>结局取值是 {@code player_inbox.PlayerInboxOutcome} 的数值（本模块不依赖 game-proto，用常量表达；game-inbox 与 game-scene 的测试
 * 把它们与枚举逐项对拍）。线程安全（无状态）。
 */
public final class InboxLedgerRules {

    /**
     * 每域最近结局环的容量。能装下「已处理、未确认」的全部条目的前提：每批至多 16 条，且上一批全部确认之前不拉下一批（设计 §4.4、§7.4）。
     * 与 mmorpg C++ {@code kRecentCapacity}、Go {@code ledgerRecentCapacity} 同值（PARITY 第 ⑤ 条）；Java 两侧（scene 记账、game-inbox 证明
     * 与对账）只认这一个常量。
     */
    public static final int RECENT_CAPACITY = 32;

    /** {@code PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED}。 */
    public static final int OUTCOME_APPLIED = 1;
    /** {@code PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_REJECTED}。 */
    public static final int OUTCOME_REJECTED = 2;
    /** {@code PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_SKIPPED}。 */
    public static final int OUTCOME_SKIPPED = 3;
    /** {@code PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_UNKNOWN}：只进确认、不入环。 */
    public static final int OUTCOME_UNKNOWN = 4;

    private InboxLedgerRules() {
    }

    /** 结局能不能进最近结局环：只有 APPLIED / REJECTED / SKIPPED（UNKNOWN 只确认、不入环）。 */
    public static boolean isRecordableOutcome(int outcome) {
        return outcome == OUTCOME_APPLIED || outcome == OUTCOME_REJECTED || outcome == OUTCOME_SKIPPED;
    }

    /** 按 domain 字段找游标（不靠下标）；没有返回 null。账本须先过 {@link #validate}（域唯一）。 */
    public static InboxDomainCursorState findCursor(InboxLedgerState ledger, int domain) {
        for (InboxDomainCursorState cursor : ledger.getCursorsList()) {
            if (cursor.getDomain() == domain) {
                return cursor;
            }
        }
        return null;
    }

    /** 某个域的水位；账本里没有该域时为 0。 */
    public static long decidedThrough(InboxLedgerState ledger, int domain) {
        InboxDomainCursorState cursor = findCursor(ledger, domain);
        return cursor == null ? 0 : cursor.getDecidedThroughSeq();
    }

    /** 按 seq 字段在环里找一条（环 ≤ 32 条，线性扫）；没有返回 null。 */
    public static InboxDecisionState findRecent(InboxDomainCursorState cursor, long seq) {
        for (InboxDecisionState decision : cursor.getRecentList()) {
            if (decision.getSeq() == seq) {
                return decision;
            }
        }
        return null;
    }

    /**
     * 整份结构校验（与 mmorpg C++ {@code ValidateLedger} 逐条同口径）：各域 domain 非 0 且唯一；每个域的环不超过 {@link #RECENT_CAPACITY} 条；
     * 环里 seq 非 0、严格升序、都不超过 decided_through_seq，结局可记账。
     *
     * @param requireRequestId 另查环里 request_id 非 0（game-inbox 拿账本当证据时的口径，同 Go {@code validLedger}：入箱拒收 request_id = 0，
     *                         环里出现 0 只可能是数据已坏，把它当成「另一个 request_id」会凭空造出证明）。scene 加载时不查这一项
     *                         （0 也能比，只会在回放核对时判 MISMATCH），同 C++
     * @return null = 合法；否则是可直接进日志的损坏原因
     */
    public static String validate(InboxLedgerState ledger, boolean requireRequestId) {
        Set<Integer> seenDomains = new HashSet<>();
        for (int i = 0; i < ledger.getCursorsCount(); i++) {
            InboxDomainCursorState cursor = ledger.getCursors(i);
            String where = "cursor[" + i + "] domain=" + Integer.toUnsignedString(cursor.getDomain());
            if (cursor.getDomain() == 0) {
                return where + ": domain is 0";
            }
            if (!seenDomains.add(cursor.getDomain())) {
                return where + ": duplicate domain";
            }
            if (cursor.getRecentCount() > RECENT_CAPACITY) {
                return where + ": recent has " + cursor.getRecentCount() + " entries (max " + RECENT_CAPACITY + ")";
            }
            long previous = 0;
            for (int j = 0; j < cursor.getRecentCount(); j++) {
                InboxDecisionState decision = cursor.getRecent(j);
                String entry = where + " recent[" + j + "] seq=" + Long.toUnsignedString(decision.getSeq());
                if (decision.getSeq() == 0) {
                    return entry + ": seq is 0";
                }
                if (Long.compareUnsigned(decision.getSeq(), previous) <= 0) {
                    return entry + ": not strictly ascending";
                }
                if (Long.compareUnsigned(decision.getSeq(), cursor.getDecidedThroughSeq()) > 0) {
                    return entry + ": beyond decided_through_seq=" + Long.toUnsignedString(cursor.getDecidedThroughSeq());
                }
                if (!isRecordableOutcome(decision.getOutcome())) {
                    return entry + ": outcome " + Integer.toUnsignedString(decision.getOutcome()) + " is not recordable";
                }
                if (requireRequestId && decision.getRequestId() == 0) {
                    return entry + ": request_id is 0";
                }
                previous = decision.getSeq();
            }
        }
        return null;
    }
}
