package com.game.scene.world;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 跨 zone 传送（226）受理前的「在不在队」检查（批次 5.4，zone-travel-spec §5.5 的 PRECHECK，3026）。scene 不缓存队伍号，
 * 每次都要读一次成员关系；world 不碰 future，读由实现去做。
 *
 * <p>契约同 {@link RemoteSwitchTargets}：{@link #check} 不阻塞调用线程（场景逻辑线程上调用）；结果回调<b>一定在场景逻辑线程上、
 * 不在 {@code check} 的调用栈内、恰好一次</b>（实现保证在 {@code timeout} 之内出结果，到时回 {@link TeamCheck#UNKNOWN}）。
 * 逻辑线程已停止时结果丢弃。
 */
public interface TeamChecks {

    /**
     * @param playerId 玩家
     * @param timeout  这次读的上限（{@code xm.scene.travel.team-check-timeout}，必须为正）
     */
    void check(long playerId, Duration timeout, Consumer<TeamCheck> onDone);

    /** 检查的结果。 */
    enum TeamCheck {
        /** 不在任何队伍里：226 可以受理。 */
        NOT_IN_TEAM,
        /** 在队：226 回 3026。 */
        IN_TEAM,
        /** 读失败、超时、数据损坏：判定不了，226 回 3027（fail-closed，不放行）。 */
        UNKNOWN
    }
}
