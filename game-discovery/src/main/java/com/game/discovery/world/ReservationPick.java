package com.game.discovery.world;

/**
 * 软预占选中的候选（scene-channels-spec §4.11）。
 *
 * @param index 选中者在候选列表里的下标（从 0 起）
 * @param load  选中时它的负载 = 目录人数 + 未到期的<b>别人的</b>预占数（不含本玩家自己已有的那条）
 */
public record ReservationPick(int index, long load) {
}
