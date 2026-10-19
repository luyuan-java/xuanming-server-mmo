package com.game.scene.inbox;

import com.game.player.store.inbox.InboxLedgerRules;
import com.game.player.store.state.InboxDecisionState;
import com.game.player.store.state.InboxDomainCursorState;
import com.game.player.store.state.InboxLedgerState;

/**
 * 一个玩家的收件箱消费账本（{@code player_state.inbox_ledger}；只在场景逻辑线程上读写），规则同 mmorpg C++ {@code player_inbox_rules}：
 * 每个域「按 seq 升序已处理到哪」与最近 32 条结局，与 GM 处置的效果同一份记录、同一次围栏写落盘（IB-3）。
 * <ul>
 *   <li>加载时校验（{@link InboxLedgerRules#validate}，与 game-inbox 共用一份）；损坏不改写、原样带回，{@link #invalidReason()} 非 null，
 *       调用方停用该玩家的收件箱（fail-closed）。<b>不要清空账本重建</b>：那会重复执行已执行过的处置；</li>
 *   <li>记账只追加：seq 必须大于该域水位、结局必须可记（APPLIED / REJECTED / SKIPPED），否则不改任何状态；超过 32 条挤掉最旧的；</li>
 *   <li>游标按 domain 无符号升序写出（周期存盘按值比对，顺序必须确定）；查找一律按 domain 字段，不靠下标；本版本不认识的字段原样保留。</li>
 * </ul>
 */
public final class InboxLedger {

    private InboxLedgerState state;
    private final String invalidReason;

    private InboxLedger(InboxLedgerState state, String invalidReason) {
        this.state = state;
        this.invalidReason = invalidReason;
    }

    /** 新号 / 从没消费过：空账本。 */
    public static InboxLedger empty() {
        return new InboxLedger(InboxLedgerState.getDefaultInstance(), null);
    }

    /** 从存档恢复；不自洽时返回一本「损坏」账本（原样带回存档、不改写）。 */
    public static InboxLedger restore(InboxLedgerState stored) {
        return new InboxLedger(stored, InboxLedgerRules.validate(stored, false));
    }

    /** 加载时判定的损坏原因（可直接进日志）；null = 自洽。 */
    public String invalidReason() {
        return invalidReason;
    }

    /** 某个域的游标；没有（该域从未处理过）为 null。账本损坏时也照实返回（调用方先看 {@link #invalidReason()}）。 */
    public InboxDomainCursorState cursor(int domain) {
        return InboxLedgerRules.findCursor(state, domain);
    }

    /** 某个域的水位（没有为 0）。 */
    public long decidedThrough(int domain) {
        return InboxLedgerRules.decidedThrough(state, domain);
    }

    /**
     * 记一条结局。前置：账本自洽、decision.seq &gt; 该域水位（无符号）且结局可记账；不满足时返回 false 且<b>不改动任何状态</b>（调用方记 ERROR 并停用该域）。
     * 通过后：压入环尾（超过 32 条挤掉最旧的），decided_through_seq = seq；该域没有游标时按 domain 升序插入一条。
     */
    public boolean record(int domain, InboxDecisionState decision) {
        if (invalidReason != null || !InboxLedgerRules.isRecordableOutcome(decision.getOutcome()) || decision.getSeq() == 0) {
            return false;
        }
        InboxLedgerState.Builder builder = state.toBuilder();
        int index = -1;
        for (int i = 0; i < builder.getCursorsCount(); i++) {
            if (builder.getCursors(i).getDomain() == domain) {
                index = i;
                break;
            }
        }
        InboxDomainCursorState.Builder cursor = index >= 0 ? builder.getCursors(index).toBuilder()
                : InboxDomainCursorState.newBuilder().setDomain(domain);
        if (Long.compareUnsigned(decision.getSeq(), cursor.getDecidedThroughSeq()) <= 0) {
            return false;
        }
        cursor.addRecent(decision);
        while (cursor.getRecentCount() > InboxLedgerRules.RECENT_CAPACITY) {
            cursor.removeRecent(0);
        }
        cursor.setDecidedThroughSeq(decision.getSeq());
        if (index >= 0) {
            builder.setCursors(index, cursor);
        } else {
            int at = 0;
            while (at < builder.getCursorsCount() && Integer.compareUnsigned(builder.getCursors(at).getDomain(), domain) < 0) {
                at++;
            }
            builder.addCursors(at, cursor);
        }
        state = builder.build();
        return true;
    }

    /** 是不是「从没消费过」（写回时省掉这一段）。 */
    public boolean isPristine() {
        return state.equals(InboxLedgerState.getDefaultInstance());
    }

    /** 写回的存档形态（损坏的账本原样带回）。 */
    public InboxLedgerState toState() {
        return state;
    }
}
