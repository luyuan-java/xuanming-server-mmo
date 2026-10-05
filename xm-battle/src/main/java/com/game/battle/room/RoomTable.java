package com.game.battle.room;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 房间表（基线 {@code battle_room_table.h RoomTable}；battle-node-spec §4.2、§7.6）：房间的增删与生命周期回调绑在一起。
 * 底层容器藏在本类里，能改表的只有 {@link #emplace} / {@link #erase}，各自<b>恰好</b>触发一次 {@link RoomHooks} 回调——
 * 调用方没有「直接写 map 绕过回调」的路径。
 *
 * <p>不打日志：重复插入等异常由调用点记日志。<b>线程</b>：不加锁，只在逻辑线程上用。回调里重入增删是程序缺陷，直接抛
 * {@link IllegalStateException}（基线只在注释里禁止）。
 *
 * @param <R> 房间类型（生产是 {@link BattleRoom}；单测用假房间）
 */
final class RoomTable<R> {

    private final Map<Long, R> rooms = new HashMap<>();
    private final RoomHooks hooks;
    private boolean inHook;

    RoomTable() {
        this(RoomHooks.NONE);
    }

    RoomTable(RoomHooks hooks) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    /** 按 id 查房间，没有时返回 null。 */
    R find(long battleId) {
        return rooms.get(battleId);
    }

    /**
     * 插入并恰好触发一次 {@link RoomHooks#onCreated}，返回表内对象。拒绝（返回 null、不触发回调）的两种情况：
     * id 已存在（表里那间保持原样）；{@code room} 为 null。
     */
    R emplace(long battleId, R room) {
        rejectReentry();
        if (room == null || rooms.containsKey(battleId)) {
            return null;
        }
        rooms.put(battleId, room);
        fire(true, battleId);
        return room;
    }

    /** 移除并恰好触发一次 {@link RoomHooks#onRemoved}，返回 true；房间不存在时什么也不做、不触发回调，返回 false。 */
    boolean erase(long battleId) {
        rejectReentry();
        if (rooms.remove(battleId) == null) {
            return false;
        }
        fire(false, battleId);
        return true;
    }

    /** 当前全部 id 的快照（按无符号升序，便于日志与测试对照）。批量移除时先拷 id 再逐个 {@link #erase}，不能边遍历边删。 */
    List<Long> ids() {
        List<Long> ids = new ArrayList<>(rooms.keySet());
        ids.sort(Long::compareUnsigned);
        return ids;
    }

    int size() {
        return rooms.size();
    }

    boolean isEmpty() {
        return rooms.isEmpty();
    }

    private void fire(boolean created, long battleId) {
        inHook = true;
        try {
            if (created) {
                hooks.onCreated(battleId);
            } else {
                hooks.onRemoved(battleId);
            }
        } finally {
            inHook = false;
        }
    }

    private void rejectReentry() {
        if (inHook) {
            throw new IllegalStateException("房间表回调里不得重入增删");
        }
    }
}
