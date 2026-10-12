package com.game.battle.engine;

/**
 * 开局被拒的原因（替代基线 {@code bool Initialize} 的 false，规格 D1、§10.3）。battle 节点据此打日志，对外照样回 1002。
 *
 * <p>各项检查的先后只影响报出哪个原因，不影响「收或拒」的结果集合（规格 §1.4）。{@link #baselineLogsError()} 标出基线在引擎里
 * 打 ERROR 的四处：Java 引擎不打日志，节点可按它保持同样的日志级别。
 */
public enum InitRejection {

    /** {@code battle_id == 0}（{@code engine.cpp:47}）。 */
    MISSING_BATTLE_ID(false),
    /** 请求里没有玩家快照（{@code engine.cpp:47}）。 */
    NO_PLAYERS(false),
    /** 某队（只数 {@code team_index <= 1} 的快照，不含宝宝）超过 {@link BattleConstants#MAX_BATTLE_TEAM_SIZE} 人（{@code engine.cpp:59-66}）。 */
    TEAM_OVERSIZE(true),
    /** {@code player_id == 0} 或 {@code team_index > 1}（{@code engine.cpp:119-121}）。 */
    INVALID_PLAYER(false),
    /** player_id 的 bit63 置位，落进引擎局内号段（{@code engine.cpp:124-128}）。 */
    RESERVED_PLAYER_ID(true),
    /** 同一 player_id 重复参战（{@code engine.cpp:129-131}）。 */
    DUPLICATE_PLAYER(false),
    /** 宝宝快照的 {@code pet_id == 0}（{@code engine.cpp:188-190}）。 */
    PET_ID_ZERO(false),
    /** 同一只宝宝（按 pet_id）在本局出现两次（{@code engine.cpp:192-196}）。 */
    DUPLICATE_PET(true),
    /** 宝宝快照的 {@code owner_player_id} 非 0 且不等于所在玩家快照的 player_id（{@code engine.cpp:211-216}）。 */
    PET_OWNER_MISMATCH(true),
    /** 全部单位就位后有一方没有任何单位（team 0 记 A 方，其余记 B 方；{@code engine.cpp:102-104}）。 */
    ONE_SIDED(false);

    private final boolean baselineLogsError;

    InitRejection(boolean baselineLogsError) {
        this.baselineLogsError = baselineLogsError;
    }

    /** 基线是否在这一处打 ERROR 日志（{@code engine.cpp:19-20} 所说的四处）。 */
    public boolean baselineLogsError() {
        return baselineLogsError;
    }
}
