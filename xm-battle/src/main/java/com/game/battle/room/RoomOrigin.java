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
    DEV
}
