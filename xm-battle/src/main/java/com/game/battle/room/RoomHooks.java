package com.game.battle.room;

/**
 * 房间表的生命周期回调（基线 {@code battle_room_table.h Hooks}；battle-node-spec §4.2、§7.6）。
 *
 * <p>契约：{@link RoomTable#emplace} 成功时恰好调一次 {@link #onCreated}，{@link RoomTable#erase} 成功时恰好调一次 {@link #onRemoved}；
 * 回调在表<b>已经更新之后</b>同步调用（onCreated 时房间已在表里，onRemoved 时已不在）。回调必须快速返回，可以查表，<b>不得</b>重入增删。
 * Java 里 hooks 驱动房间数（{@code xm_battle_rooms} 与目录的 {@code room_count}）；基线驱动 Agones 单元计数，Java 不移植。
 */
interface RoomHooks {

    /** 什么也不做（基线「没设回调就跳过」）。 */
    RoomHooks NONE = new RoomHooks() {
        @Override
        public void onCreated(long battleId) {
        }

        @Override
        public void onRemoved(long battleId) {
        }
    };

    /** 房间已插表之后调用。 */
    void onCreated(long battleId);

    /** 房间已从表里移除之后调用。 */
    void onRemoved(long battleId);
}
