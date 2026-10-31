package com.game.scene.battle;

import com.game.api.match.MatchBudgets;
import com.game.discovery.battle.BattleRedis;
import java.util.Map;

/**
 * 一名玩家身上的回合制战斗冻结（基线 {@code InBattleComp{battle_id, battle_node_id, deadline_ms, state, prepare_deadline_ms}}；
 * scene-battle-spec §7.4）。可变，<b>只在场景逻辑线程上改</b>；不持久化，随实例生灭（跨断线靠 Redis 锁重建）。
 *
 * <p>异步回调按「实例 + 冻结对象」双重核对：回调回来时 {@code player.battle().freeze() != 这个对象} 就丢弃，所以同一局在新实例上沿用时
 * 复制成一个新对象（{@link #carriedCopy}），不搬同一个。
 *
 * <p><b>FIGHTING 的到期判据</b>（2026-10-30，与 mmorpg C++ 同批）：不是战斗期限本身，而是 {@link #fightingReleaseAtMs}「期限 + Match 终局宽限
 * {@link MatchBudgets#TERMINAL_OUTCOME_GRACE_MS} + 收件箱投递余量 {@link #INBOX_DELIVERY_MARGIN_MS}」。Match 在期限之后的宽限内仍接受原局 COMPLETE，
 * 个人结算随后才经收件箱到达；冻结若在此之前解开，资产通道（只看冻结）就会放行，玩家可先把本局消耗的物品转走、结算到达时消耗被夹紧，
 * 形成可逐局重复的有界资产复制。{@link #effectiveDeadlineMs()} 对 FIGHTING 就返回这个时刻，reaper 的宽限 / 判废与锁 TTL 都按它算
 * （锁 TTL = 它的剩余 + 60 s，锁仍比冻结多活 60 s）；锁里的 {@code d} 照旧写原始战斗期限。PREPARING 不变。
 */
public final class BattleFreeze {

    /**
     * COMPLETE 之后个人结算到达 scene 并应用的余量：Match 个人发件箱的首投 / 回执退避与 scene 收件箱的拉取节拍之和的上界（取整到 60 s）。
     * 与 {@link MatchBudgets#TERMINAL_OUTCOME_GRACE_MS} 一起构成 FIGHTING 冻结在战斗期限之后还要保持的窗口。
     */
    public static final long INBOX_DELIVERY_MARGIN_MS = 60_000L;

    /** 阶段（锁的 {@code s}：P / F）。 */
    public enum Phase {
        PREPARING,
        FIGHTING
    }

    /**
     * FIGHTING 冻结的到期时刻：战斗期限 + {@link MatchBudgets#TERMINAL_OUTCOME_GRACE_MS} + {@link #INBOX_DELIVERY_MARGIN_MS}（饱和加法）。
     * 期限 ≤ 0（缺失 / 损坏）原样返回，不凭空给出窗口。确认续锁、按锁重建复核、reaper 判到期都用它，锁 TTL 按它的剩余 + 60 s。
     */
    public static long fightingReleaseAtMs(long deadlineMs) {
        if (deadlineMs <= 0) {
            return deadlineMs;
        }
        long window = MatchBudgets.TERMINAL_OUTCOME_GRACE_MS + INBOX_DELIVERY_MARGIN_MS;
        return deadlineMs > Long.MAX_VALUE - window ? Long.MAX_VALUE : deadlineMs + window;
    }

    private final long battleId;
    private final int battleNodeId;
    private Phase phase;
    private long deadlineMs;
    private long prepareDeadlineMs;
    /**
     * 「这一局的 144 不必再推」：在本实例上 PrepareBattle 建的为真（顶替基线 BattlePrepareSessionComp，D7：确认到达时不必推）；按锁重建、
     * 换了会话沿用旧实例的为假，第一次推 144（确认升级、迟到确认、重建复核、沿用时当场推）之后置真——同一个冻结对象上 144 恰好一次（审计 FRZ-4 / RDS-8）。
     * 同会话的重复进场沿用时照抄旧值（{@link #carriedCopy}）。
     */
    private boolean preparedHere;
    /** 备战写锁在途。 */
    private boolean lockPending;
    /** 写锁在途时收到取消：写锁完成后再删锁。 */
    private boolean cancelRequested;
    /** FIGHTING 的条件续期已确认成功（D9）：之后的重复确认零 Redis。 */
    private boolean lockExtended;
    /** reaper 的 rescue 读在途（防重复处理）。 */
    private boolean rescuing;

    public BattleFreeze(long battleId, int battleNodeId, Phase phase, long deadlineMs, long prepareDeadlineMs,
                        boolean preparedHere) {
        this.battleId = battleId;
        this.battleNodeId = battleNodeId;
        this.phase = phase;
        this.deadlineMs = deadlineMs;
        this.prepareDeadlineMs = prepareDeadlineMs;
        this.preparedHere = preparedHere;
    }

    /**
     * 按锁的字段重建一个冻结（纯函数；基线 {@code pb.cpp:608-637}，scene-battle-spec §1.2 的四条取值规则，§7.8 第 3 步 / 审计 FRZ-10）：
     * <ol>
     *   <li>字段缺失或不是无符号十进制 → 按 0（{@code n} 缺失 = 节点号 0）；battle_id 以调用方给的为准（锁的 {@code b} 已由调用方核对过）；</li>
     *   <li>{@code s} 只有等于 {@code P} 才是备战，缺失或不认识一律按 FIGHTING（保守：宁可多冻、不放过一场在打的局）；</li>
     *   <li>{@code d} 为 0 → 按锁的剩余 TTL 反推：{@code now + max(ttl, 0) × 1000}（TTL 为 -1 / -2 时取 now）。FIGHTING 的锁 TTL 已经覆盖了
     *       终局宽限 + 投递余量 + 60 s，反推出来的「期限」再经 {@link #effectiveDeadlineMs()} 叠加一次窗口——偏保守（多冻一会儿），不会提前解冻；</li>
     *   <li>备战且 {@code p} 为 0 → 取 {@code d}；FIGHTING 的 {@code p} 原样（可以是 0）。</li>
     * </ol>
     * 结果 {@code preparedHere = false}（不是本实例备战的，确认 / 复核时还要推 144）、{@code lockExtended = (阶段 == FIGHTING)}
     * （锁上已是 F = 之前的确认续过期）；其余运行态标记为假。
     *
     * @param lock   锁的字段（{@code ENTER_READ} 读到的那份；没有的字段就是没有键）
     * @param ttlSec 锁的剩余 TTL（秒；-1 永不过期，-2 不存在）
     */
    static BattleFreeze fromLock(long battleId, Map<String, String> lock, long ttlSec, long nowMs) {
        boolean fighting = !BattleRedis.STATE_PREPARING.equals(lock.get(BattleRedis.FIELD_STATE));
        long deadline = BattleRedis.parseUnsigned(lock.get(BattleRedis.FIELD_DEADLINE));
        if (deadline == 0) {
            deadline = nowMs + Math.max(ttlSec, 0) * 1000;
        }
        long prepareDeadline = BattleRedis.parseUnsigned(lock.get(BattleRedis.FIELD_PREPARE_DEADLINE));
        if (!fighting && prepareDeadline == 0) {
            prepareDeadline = deadline;
        }
        BattleFreeze freeze = new BattleFreeze(battleId, (int) BattleRedis.parseUnsigned(lock.get(BattleRedis.FIELD_NODE)),
                fighting ? Phase.FIGHTING : Phase.PREPARING, deadline, prepareDeadline, false);
        freeze.lockExtended = fighting;
        return freeze;
    }

    public long battleId() {
        return battleId;
    }

    public int battleNodeId() {
        return battleNodeId;
    }

    public Phase phase() {
        return phase;
    }

    public long deadlineMs() {
        return deadlineMs;
    }

    public long prepareDeadlineMs() {
        return prepareDeadlineMs;
    }

    /**
     * 有效期限（reaper 判到期、锁 TTL 的基准）：PREPARING 看备战期限（0 时退回战斗期限）；FIGHTING 看 {@link #fightingReleaseAtMs}（战斗期限 +
     * 终局宽限 + 收件箱投递余量），不是战斗期限本身——见类注释。
     */
    public long effectiveDeadlineMs() {
        if (phase == Phase.PREPARING) {
            return prepareDeadlineMs != 0 ? prepareDeadlineMs : deadlineMs;
        }
        return fightingReleaseAtMs(deadlineMs);
    }

    public boolean preparedHere() {
        return preparedHere;
    }

    boolean lockPending() {
        return lockPending;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    boolean lockExtended() {
        return lockExtended;
    }

    boolean rescuing() {
        return rescuing;
    }

    void upgrade(long deadlineMs) {
        this.phase = Phase.FIGHTING;
        if (deadlineMs != 0) {
            this.deadlineMs = deadlineMs;
        }
    }

    void setPreparedHere(boolean preparedHere) {
        this.preparedHere = preparedHere;
    }

    void setLockPending(boolean lockPending) {
        this.lockPending = lockPending;
    }

    void requestCancel() {
        this.cancelRequested = true;
    }

    void setLockExtended(boolean lockExtended) {
        this.lockExtended = lockExtended;
    }

    void setRescuing(boolean rescuing) {
        this.rescuing = rescuing;
    }

    /**
     * 同 epoch 沿用旧实例时复制给新实例（§7.8 第 0 步）：运行态标记复位（{@code rescuing} / {@code cancelRequested} / {@code lockPending}），
     * {@code lockExtended} 照抄。{@code preparedHere}（「这一局的 144 不必再推」）看会话：
     * <ul>
     *   <li><b>会话没变</b>（同会话的重复进场）→ 照抄旧值：这个会话的客户端知道什么、不知道什么都没变——在这个会话上备战的、已经推过 144 的不必再推，
     *       之后的确认升级不会多推一条（同基线：按「备战时记下的会话号 ≠ 当前会话号」才推）；旧值为假的照旧还欠着一条——PREPARING 的等确认升级时推，
     *       FIGHTING 的（按 F 锁重建后复核还在途、还没来得及推）由沿用方当场推；</li>
     *   <li><b>会话变了</b>（重连 / 顶号）→ 假：新会话的客户端要凭 144 补签（FIGHTING 的沿用时当场推，PREPARING 的等确认升级时推）。</li>
     * </ul>
     *
     * @param sameSession 新实例的会话与旧实例相同
     */
    BattleFreeze carriedCopy(boolean sameSession) {
        BattleFreeze copy = new BattleFreeze(battleId, battleNodeId, phase, deadlineMs, prepareDeadlineMs, sameSession && preparedHere);
        copy.lockExtended = lockExtended;
        return copy;
    }

    @Override
    public String toString() {
        return "BattleFreeze{battle_id=" + Long.toUnsignedString(battleId) + ", node=" + battleNodeId + ", " + phase
                + ", deadline=" + deadlineMs + ", prepare_deadline=" + prepareDeadlineMs + ", prepared_here=" + preparedHere
                + ", lock_pending=" + lockPending + ", lock_extended=" + lockExtended + "}";
    }
}
