package com.game.battle.room;

/**
 * 墙钟（Unix 毫秒，基线 {@code TimeSystem::NowMillisecondsUTC}；同 scene 的 {@code SceneClock}）。用于客户端可见的时间戳：
 * {@code action_deadline_ms}、房间期限 {@code deadlineMs}、票据过期判定、结果事件的 {@code finished_at_ms}。
 * <b>间隔一律交给 {@link BattleScheduler}</b>（回合窗口、整场期限、确认补发都是 scheduler 的延迟），不拿墙钟差值算间隔。
 *
 * <p>测试注入 {@code com.game.battle.testing.ManualBattleScheduler#clock()}，与虚拟时间同步推进。线程安全。
 */
@FunctionalInterface
public interface BattleClock {

    BattleClock SYSTEM = System::currentTimeMillis;

    long epochMillis();
}
