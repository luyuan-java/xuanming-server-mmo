package com.game.scene.battle;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 挂在场景玩家上的回合制战斗运行态（scene-battle-spec §7.4）：冻结、进场恢复状态、结算应用中的不重入标记。不持久化，随实例生灭。
 * 只在场景逻辑线程上读写；改冻结只经 {@code PlayerBattleService}。
 *
 * <p><b>过期读的两道防线</b>（审计 FRZ-1 / FRZ-9；Redis 脚本各自异步，执行次序与回调次序都没有保证，§1.8）：
 * <ul>
 *   <li><b>恢复代际</b>（{@link #nextRecoveryGeneration()}）：每发一次进场恢复读就加一，回调只认最新一代——
 *       同一实例上先后发出的两次恢复读（进场 + 原地解冻 / reaper 重试）不会各跑一遍；</li>
 *   <li><b>已销账集合</b>（{@link #markWrittenOff} / {@link #writtenOff}）：本实例已经为它发出过销账脚本的 battle_id。销账删记录、放锁之后，
 *       更早拍下的快照（恢复读、读锁、rescue 读）里还带着这一局——账本此时可能已经 forget，只靠账本去重就会再发一次奖。
 *       凡是在这个集合里的局，一律按「已应用」处理，不再应用、不再按锁重建。有界（{@value #WRITTEN_OFF_CAPACITY}，先进先出），稳态 0~1 个。</li>
 * </ul>
 */
public final class PlayerBattle {

    /** 已销账集合的容量（与账本容量同量级；满了淘汰最早的——那一局的销账早已落地，过期读不会迟到这么久）。 */
    static final int WRITTEN_OFF_CAPACITY = BattleLedger.CAPACITY;

    /** 进场恢复（§7.8，D20）：恢复读在途 / 已就绪 / 读失败或有延后的待结算记录（由 reaper 重跑）。 */
    public enum Recovery {
        PENDING,
        READY,
        RETRY
    }

    private BattleFreeze freeze;
    /** 新实例在进场恢复读回来之前是 PENDING（fail-closed：没接战斗服务的装配里一直是它，结算一律延后）。 */
    private Recovery recovery = Recovery.PENDING;
    private boolean applying;
    /** 最近一次发出的进场恢复读是第几代（0 = 还没发过）。 */
    private long recoveryGeneration;
    /** 本实例已发出销账脚本的 battle_id（插入序，有界）。 */
    private final Set<Long> writtenOff = new LinkedHashSet<>();

    /** 当前冻结；null = 没有在途战斗。 */
    public BattleFreeze freeze() {
        return freeze;
    }

    /** 有在途战斗（备战或战斗中）：基线 {@code IsInBattle}，全部在途闸的唯一判据。 */
    public boolean inBattle() {
        return freeze != null;
    }

    public Recovery recovery() {
        return recovery;
    }

    void setFreeze(BattleFreeze freeze) {
        this.freeze = freeze;
    }

    void setRecovery(Recovery recovery) {
        this.recovery = recovery;
    }

    boolean applying() {
        return applying;
    }

    void setApplying(boolean applying) {
        this.applying = applying;
    }

    /** 开始新一代进场恢复读，返回它的代际号（回调据此判自己是不是最新一代）。 */
    long nextRecoveryGeneration() {
        return ++recoveryGeneration;
    }

    /** 最近一次发出的进场恢复读的代际号；0 = 还没发过。 */
    long recoveryGeneration() {
        return recoveryGeneration;
    }

    /** 记下「本实例已为这一局发出销账」（0 号不记）。 */
    void markWrittenOff(long battleId) {
        if (battleId == 0 || !writtenOff.add(battleId)) {
            return;
        }
        if (writtenOff.size() > WRITTEN_OFF_CAPACITY) {
            Iterator<Long> oldest = writtenOff.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** 同 epoch 沿用旧实例的内存时，把它已销账的局也沿用过来（旧实例的账本与这份认识是一体的）。 */
    void inheritWrittenOff(PlayerBattle previous) {
        for (long battleId : previous.writtenOff) {
            markWrittenOff(battleId);
        }
    }

    /** 本实例是否已为这一局发出过销账：是 → 任何还带着它的读都是过期快照（或是已决定丢弃的局），不得再应用、不得再按锁重建。 */
    boolean writtenOff(long battleId) {
        return battleId != 0 && writtenOff.contains(battleId);
    }
}
