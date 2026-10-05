package com.game.battle.room;

import com.game.battle.engine.BattleConstants;

/**
 * 房间的代码常量（不开放配置；battle-node-spec §8.3，注释写清与谁联动）。回合窗口直接用引擎的
 * {@link BattleConstants#ROUND_DURATION_MS} / {@link BattleConstants#AUTO_ROUND_INTERVAL_MS}。
 */
final class RoomConstants {

    /**
     * match 没填（或填了已过去的）整场期限时的兜底：(回合上限 30 + 2 回合余量) × 6000 = 192000 ms（基线 {@code room.cpp:559-567}）。
     * 房间是纯内存对象，没有期限的房间在玩家全部掉线后会靠回合超时空转到打满，兜底保证任何房间的生存期有界。
     */
    static final long DEFAULT_DEADLINE_MS = (BattleConstants.DEFAULT_MAX_ROUNDS + 2L) * BattleConstants.ROUND_DURATION_MS;

    /** 确认事件补发周期（基线 {@code kConfirmResendIntervalSec = 10}）。 */
    static final long CONFIRM_RESEND_INTERVAL_MS = 10_000;

    /**
     * 建房首发之后的补发次数（§11 N19：用计次代替基线的墙钟比较 {@code now ≥ until}，标称行为相同）：+10 … +170 s 各一次，
     * +180 s 那次触发时停表、不发；每名参战者最多 18 条。
     */
    static final int CONFIRM_RESENDS = 17;

    /**
     * 补发窗口（基线 {@code kConfirmResendWindowMs = 180000}）= 周期 × (补发次数 + 1)。<b>联动</b>：必须 ≥ match 最长 matched TTL（96 s，
     * {@code queue.go:367-380}）+ scene 备战期锁余量（60 s，{@code kLockExtraTtlSec}），单测钉住；6.3 / 6.4 改公式或常量时回看这里。
     */
    static final long CONFIRM_RESEND_WINDOW_MS = CONFIRM_RESEND_INTERVAL_MS * (CONFIRM_RESENDS + 1);

    /** 每房观众上限（基线 {@code kMaxObserversPerRoom}，{@code room.cpp:64}）：约束单房间出站扇出，防观战成为放大器。 */
    static final int MAX_OBSERVERS_PER_ROOM = 20;

    private RoomConstants() {
    }
}
