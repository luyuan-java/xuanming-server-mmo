package com.game.battle.room;

/** 房间的来源（battle-node-spec §7.6、§7.12）。 */
public enum RoomOrigin {
    /** 经 Dubbo {@code BattleNodeService.createBattle} 由 match 建的房（6.4 起）。 */
    MATCH,
    /**
     * dev / test 管理接口 {@code POST /admin/battle/dev/create} 建的房：照常推 177 / 143 / 139 / 150、照常补发确认（6.2 只记日志），
     * 但<b>永不</b>投递结算与结果事件（{@code SettlementSink} / {@code ActivityResultSink} / {@code BattleResultSink} 一律跳过，只记日志），
     * 免得 6.3 / 6.4 落地后 dev 接口变成发奖口子（§7.9）。
     */
    DEV,
    /**
     * dev / test 管理接口 {@code POST /admin/battle/dev/gather} 建的房（scene-battle-spec §7.18、D27）：快照经 {@code SceneBattleService.prepareBattle}
     * 由 scene 出（不是调用方伪造），所以照常确认、<b>照常结算</b>（{@code SettlementSink} 照调）；对局结果事件仍跳过（没有 match），
     * 不接受 {@code activity_context}。只在运行模式 dev / test 下能建出来。
     */
    DEV_GATHER;

    /** 是否投递结算（{@code SettlementSink}）：dev 建房永不结算，dev gather 与 match 照常。 */
    public boolean settles() {
        return this != DEV;
    }

    /** 是否投递对局结果（{@code BattleResultSink} / {@code ActivityResultSink}）：只有 match 的房间。 */
    public boolean publishesResult() {
        return this == MATCH;
    }
}
