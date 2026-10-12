package com.game.discovery.world;

/**
 * 进场选频道的一个候选（scene-channels-spec §4.11 第 5 步）：场景号与节点目录上报的人数（节点权威计数，不含预占）。
 * 候选的顺序就是并列时的优先顺序——调用方按 (node_id, scene_id) 无符号升序排好再交给 {@link WorldChannelStore#reserve}（D20）。
 *
 * @param sceneId          场景号（非 0）
 * @param directoryPlayers 目录里的 {@code SceneEntry.player_count}（uint32，按无符号处理）
 */
public record ReservationCandidate(long sceneId, int directoryPlayers) {

    public ReservationCandidate {
        if (sceneId == 0) {
            throw new IllegalArgumentException("scene_id 不能为 0");
        }
    }
}
