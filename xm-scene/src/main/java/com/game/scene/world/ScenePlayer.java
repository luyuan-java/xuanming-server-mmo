package com.game.scene.world;

import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorType;
import com.game.proto.Rotation;
import com.game.proto.Transform;
import java.util.List;

/**
 * 场景里的一个玩家（一次进场一个实例）。只在场景逻辑线程上读写。
 *
 * <p>{@code entity} 是场景内实体号（雪花号，恒非 0，一次进场内稳定），客户端用它做技能目标；
 * 它与 {@code playerId}（ActorCreateS2C.guid、66 的 entity_id）不是一回事。
 *
 * <p>位置只能经所在 {@link Scene} 改（{@link Scene#relocate}），这样格子索引与视野刷新不会漏；
 * 本类只保存状态，规则在 {@link SceneWorld}。
 */
public final class ScenePlayer {

    /** 66 的脏位：transform（location + rotation）。 */
    static final int DIRTY_TRANSFORM = 1;
    /** 66 的脏位：velocity。 */
    static final int DIRTY_VELOCITY = 1 << 1;

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
    private final MoveGuard moveGuard;
    private Scene scene;
    private Vec3 position;
    /** 最近一次移动上报的朝向；从没上报过为 null（66 的 transform 就不带 rotation，基线同）。 */
    private Rotation rotation;
    /** 当前速度（已截断到信任上限）；(0,0,0) 为静止。 */
    private Vec3 velocity = Vec3.ORIGIN;
    private int syncDirty;
    /** 最近一次收到该玩家客户端消息时的帧号（挂机判定）。 */
    private long lastActiveFrame;

    ScenePlayer(long playerId, long entity, SessionKey session, long ownerEpoch, int classId, int gender,
                String appearanceId, int level, List<Integer> skills, Vec3 position, long nowNanos) {
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
        this.moveGuard = new MoveGuard(position, nowNanos);
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

    public Vec3 velocity() {
        return velocity;
    }

    Rotation rotation() {
        return rotation;
    }

    MoveGuard moveGuard() {
        return moveGuard;
    }

    long lastActiveFrame() {
        return lastActiveFrame;
    }

    int syncDirty() {
        return syncDirty;
    }

    void setScene(Scene scene) {
        this.scene = scene;
    }

    /** 只由 {@link Scene#relocate}（在场景内）或换场景时（不在任何场景里）调用。 */
    void setPosition(Vec3 position) {
        this.position = position;
    }

    void setRotation(Rotation rotation) {
        this.rotation = rotation;
    }

    void setVelocity(Vec3 velocity) {
        this.velocity = velocity;
    }

    /**
     * 停下（速度清零，不置脏位）：离场写回 / 被接管 / 停服前、换场景时调用（基线 StopMotionForExit）。
     * 离场时实体随即被销毁（旁人收到 51），不需要再发「停了」的 66；换场景后新场景的人从 21 看到的本来就是静止的它。
     */
    void stopMotion() {
        this.velocity = Vec3.ORIGIN;
    }

    /**
     * 有人刚开始看见它（进视野 / 进场）：21 / 47 只带位置，朝向与速度要靠 66 补上（基线缺口 9：移动中的实体进视野时
     * 显示为静止，要等它下次改速度）。朝向上报过就置 transform 脏位（transform 带 rotation），在动就置 velocity 脏位，
     * 下一个同步帧（≤ 100 ms）的 66 一并带给全部观察者——不另发单播，66 的「每实体每 100 ms 至多一条」不被打破；
     * 老观察者多收一次相同的值，无害。从没上报过朝向、静止的实体，21 / 47 已是全部状态，什么也不置。
     */
    void markFullStateForNewWatcher() {
        if (rotation != null) {
            syncDirty |= DIRTY_TRANSFORM;
        }
        if (!velocity.isOrigin()) {
            syncDirty |= DIRTY_VELOCITY;
        }
    }

    void markActive(long frame) {
        this.lastActiveFrame = frame;
    }

    void markDirty(int bits) {
        syncDirty |= bits;
    }

    void clearDirty() {
        syncDirty = 0;
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

    /**
     * 66 {@code ActorBaseAttributesS2C}：只写 {@code dirty} 指定的字段（契约文档 AOI §5.1）。
     * <ul>
     *   <li>{@code entity_id} 每条都带，取 player_id（guid 口径，不是场景实体号）——修基线缺口：基线移动触发的 66 不带它，
     *       观察者无法归属；字段是加出来的，旧客户端忽略也不出错；</li>
     *   <li>transform：location 总在，rotation 上报过才在（全零也写，线上为 {@code 12 00}），scale 从不写；</li>
     *   <li>velocity：脏就写，<b>全零也写</b>（线上 {@code 1a 00}，表示「停了」）。</li>
     * </ul>
     */
    ActorBaseAttributesS2C toBaseAttributes(int dirty) {
        ActorBaseAttributesS2C.Builder builder = ActorBaseAttributesS2C.newBuilder().setEntityId(playerId);
        if ((dirty & DIRTY_TRANSFORM) != 0) {
            Transform.Builder transform = Transform.newBuilder().setLocation(position.toProto());
            if (rotation != null) {
                transform.setRotation(rotation);
            }
            builder.setTransform(transform);
        }
        if ((dirty & DIRTY_VELOCITY) != 0) {
            builder.setVelocity(velocity.toVelocity());
        }
        return builder.build();
    }

    PlayerSave toSave() {
        return new PlayerSave(playerId, ownerEpoch, level, scene.configId(), position);
    }
}
