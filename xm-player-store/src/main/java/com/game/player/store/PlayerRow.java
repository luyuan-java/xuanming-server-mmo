package com.game.player.store;

/**
 * {@code player} 表的一行。MyBatis 按列名（下划线转驼峰）映射到属性，所以是可变 JavaBean。
 * 只在存储层与调用方之间传递，不跨线程共享同一实例。
 */
public class PlayerRow {

    private long playerId;
    private String account;
    private int zoneId;
    private String name;
    private int classId;
    private int gender;
    private String appearanceId = "";
    private int level = 1;
    private int sceneConfigId;
    private double posX;
    private double posY;
    private double posZ;
    private long ownerEpoch;
    private long createdAt;
    private long updatedAt;

    public long getPlayerId() {
        return playerId;
    }

    public void setPlayerId(long playerId) {
        this.playerId = playerId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public int getZoneId() {
        return zoneId;
    }

    public void setZoneId(int zoneId) {
        this.zoneId = zoneId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getClassId() {
        return classId;
    }

    public void setClassId(int classId) {
        this.classId = classId;
    }

    public int getGender() {
        return gender;
    }

    public void setGender(int gender) {
        this.gender = gender;
    }

    public String getAppearanceId() {
        return appearanceId;
    }

    public void setAppearanceId(String appearanceId) {
        this.appearanceId = appearanceId == null ? "" : appearanceId;
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

    public long getOwnerEpoch() {
        return ownerEpoch;
    }

    public void setOwnerEpoch(long ownerEpoch) {
        this.ownerEpoch = ownerEpoch;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
