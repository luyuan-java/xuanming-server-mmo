package com.game.battle.room;

/**
 * 计时器句柄（{@link BattleScheduler#after} / {@link BattleScheduler#every} 的返回值；基线 {@code TimerTaskComp::Cancel}）。
 *
 * <p>{@link #cancel()} 幂等、不抛异常。<b>在逻辑线程上</b>调用 cancel 之后，这个任务保证不会再执行（到期执行也在逻辑线程上，二者天然串行）；
 * 从别的线程调用只保证尽力取消。房间的回合 / 期限 / 确认补发三个句柄在收尾、销毁、作废时一律先取消（battle-node-spec §7.6）。
 */
@FunctionalInterface
public interface Cancellable {

    /** 什么也不做的句柄（房间字段的初值）。 */
    Cancellable NONE = () -> {
    };

    void cancel();
}
