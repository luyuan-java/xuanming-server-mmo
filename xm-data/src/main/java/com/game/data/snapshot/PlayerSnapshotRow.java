package com.game.data.snapshot;

import com.game.audit.proto.PlayerSnapshotRecord;

/**
 * 一行玩家快照（{@code player_snapshot}）。无符号整数按位存进 long / int（Java 版发出的号、玩家号、epoch 都小于 2^63）。
 *
 * @param playerState 玩法数据（{@code xm.storage.PlayerState}）序列化字节，原样保存
 */
public record PlayerSnapshotRow(long snapshotId, long playerId, long timeMs, int cause, int zoneId, long ownerEpoch,
                                int level, int sceneConfigId, double posX, double posY, double posZ,
                                byte[] playerState) {

    static PlayerSnapshotRow of(PlayerSnapshotRecord r) {
        return new PlayerSnapshotRow(r.getSnapshotId(), r.getPlayerId(), r.getTimeMs(), r.getCauseValue(), r.getZoneId(),
                r.getOwnerEpoch(), r.getLevel(), r.getSceneConfigId(), r.getPosX(), r.getPosY(), r.getPosZ(),
                r.getPlayerState().toByteArray());
    }

    /** 毒丸日志用：玩法数据只给字节数（本体可按日志里的 topic / 分区 / 位点从 Kafka 重读）。 */
    @Override
    public String toString() {
        return "PlayerSnapshotRow[snapshotId=" + Long.toUnsignedString(snapshotId) + ", playerId="
                + Long.toUnsignedString(playerId) + ", timeMs=" + timeMs + ", cause=" + cause + ", zoneId="
                + Integer.toUnsignedString(zoneId) + ", ownerEpoch=" + Long.toUnsignedString(ownerEpoch) + ", level="
                + Integer.toUnsignedString(level) + ", sceneConfigId=" + Integer.toUnsignedString(sceneConfigId)
                + ", pos=(" + posX + "," + posY + "," + posZ + "), playerStateBytes="
                + (playerState == null ? 0 : playerState.length) + "]";
    }
}
