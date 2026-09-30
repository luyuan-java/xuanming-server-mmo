package com.game.scene.world;

import com.game.proto.Transform;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorType;
import java.util.List;

/**
 * 场景里的一个玩家（一次进场一个实例）。只在场景逻辑线程上读写。
 *
 * <p>{@code entity} 是场景内实体号（雪花号，恒非 0，一次进场内稳定），客户端用它做技能目标；
 * 它与 {@code playerId}（ActorCreateS2C.guid）不是一回事。
 */
public final class ScenePlayer {

    private final long playerId;
    private final long entity;
    private final SessionKey session;
    private final long ownerEpoch;
    private final int classId;
    private final int gender;
    private final String appearanceId;
    private final int level;
    /** 拥有的技能（skill_table_id，保序）。首批不持久化技能，每次进场按配表发放，见 SceneWorld 注释。 */
    private final List<Integer> skills;
    private Scene scene;
    private Vec3 position;

    ScenePlayer(long playerId, long entity, SessionKey session, long ownerEpoch, int classId, int gender,
                String appearanceId, int level, List<Integer> skills, Vec3 position) {
        this.playerId = playerId;
        this.entity = entity;
        this.session = session;
        this.ownerEpoch = ownerEpoch;
        this.classId = classId;
        this.gender = gender;
        this.appearanceId = appearanceId;
        this.level = level;
        this.skills = List.copyOf(skills);
        this.position = position;
    }

    public long playerId() {
        return playerId;
    }

    public long entity() {
        return entity;
    }

    public SessionKey session() {
        return session;
    }

    public long ownerEpoch() {
        return ownerEpoch;
    }

    public int classId() {
        return classId;
    }

    public int gender() {
        return gender;
    }

    public String appearanceId() {
        return appearanceId;
    }

    public int level() {
        return level;
    }

    public List<Integer> skills() {
        return skills;
    }

    public boolean hasSkill(int skillTableId) {
        return skills.contains(skillTableId);
    }

    public Scene scene() {
        return scene;
    }

    public Vec3 position() {
        return position;
    }

    void setScene(Scene scene) {
        this.scene = scene;
    }

    void setPosition(Vec3 position) {
        this.position = position;
    }

    /**
     * 21 / 47 里的一项（基线 ViewSystem::FillActorCreateMessageInfo）：transform 只填 location，
     * rotation / scale 不填；玩家的 config_id 不填（0）。
     */
    ActorCreateS2C toActorCreate() {
        return ActorCreateS2C.newBuilder()
                .setEntity(entity)
                .setTransform(Transform.newBuilder().setLocation(position.toProto()))
                .setActorType(ActorType.ACTOR_TYPE_PLAYER)
                .setGuid(playerId)
                .setAppearanceId(appearanceId)
                .setClassId(classId)
                .setGender(gender)
                .build();
    }

    PlayerSave toSave() {
        return new PlayerSave(playerId, ownerEpoch, level, scene.configId(), position);
    }
}
