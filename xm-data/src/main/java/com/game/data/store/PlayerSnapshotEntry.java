package com.game.data.store;

/** 查询结果的一行快照元数据（不含玩法数据本体；MyBatis 按列名下划线转驼峰填充）。 */
public class PlayerSnapshotEntry {

    private long snapshotId;
    private long playerId;
    private long timeMs;
    private int cause;
    private int zoneId;
    private long ownerEpoch;
    private int level;
    private int sceneConfigId;
    private double posX;
    private double posY;
    private double posZ;
    private long stateBytes;
    private long ingestedAt;

    public long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public long getPlayerId() {
        return playerId;
    }

    public void setPlayerId(long playerId) {
        this.playerId = playerId;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public void setTimeMs(long timeMs) {
        this.timeMs = timeMs;
    }

    public int getCause() {
        return cause;
    }

    public void setCause(int cause) {
        this.cause = cause;
    }

    public int getZoneId() {
        return zoneId;
    }

    public void setZoneId(int zoneId) {
        this.zoneId = zoneId;
    }

    public long getOwnerEpoch() {
        return ownerEpoch;
    }

    public void setOwnerEpoch(long ownerEpoch) {
        this.ownerEpoch = ownerEpoch;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getSceneConfigId() {
        return sceneConfigId;
    }

    public void setSceneConfigId(int sceneConfigId) {
        this.sceneConfigId = sceneConfigId;
    }

    public double getPosX() {
        return posX;
    }

    public void setPosX(double posX) {
        this.posX = posX;
    }

    public double getPosY() {
        return posY;
    }

    public void setPosY(double posY) {
        this.posY = posY;
    }

    public double getPosZ() {
        return posZ;
    }

    public void setPosZ(double posZ) {
        this.posZ = posZ;
    }

    public long getStateBytes() {
        return stateBytes;
    }

    public void setStateBytes(long stateBytes) {
        this.stateBytes = stateBytes;
    }

    public long getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(long ingestedAt) {
        this.ingestedAt = ingestedAt;
    }
}
