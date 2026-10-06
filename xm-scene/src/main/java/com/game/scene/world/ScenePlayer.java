package com.game.scene.world;

import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorType;
import com.game.proto.Rotation;
import com.game.proto.Transform;
import com.game.scene.asset.AssetOpLedger;
import com.game.scene.battle.BattleLedger;
import com.game.scene.battle.PlayerBattle;
import com.game.scene.player.GainWindows;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerAttributes;
import com.game.scene.player.PlayerMissions;
import com.game.scene.player.PlayerPets;
import com.game.scene.player.PlayerSkillState;
import com.game.scene.player.Wallet;
import com.google.protobuf.UnknownFieldSet;
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
    /** 等级（存档列 player.level；加载时已压回上限）。只经属性系统改（GM 设等级），规则见 PlayerLevels。 */
    private int level;
    /** 拥有的技能（skill_table_id，保序）。首批不持久化技能，每次进场按配表发放，见 SceneWorld 注释。 */
    private final List<Integer> skills;
    private final MoveGuard moveGuard;
    private Scene scene;
    /** 位置记录的写序号（本次进场内单调递增，见 PlayerLocationDirectory；只在逻辑线程上读写）。 */
    private long locationSeq;
    private Vec3 position;
    /**
     * 最近一次移动上报的朝向（持久化在 {@code player_state.facing}，进场时恢复）；
     * 从没上报过为 null（66 的 transform 就不带 rotation，基线同）。
     */
    private Rotation rotation;
    /**
     * 加载到的状态里本版本不认识的玩法数据（更新版本写入的字段）。写回时原样带上，滚动升级 / 回滚期间旧版本节点不会把它们抹掉。
     */
    private final UnknownFieldSet unknownStateFields;
    /** 货币。 */
    private final Wallet wallet;
    /** 属性加点（方案落库；当前气血 / 法力不满时落 player_state.vitals，进场由属性系统按新上限夹取 / 复活；二级属性加载时算出）。 */
    private final PlayerAttributes attributes;
    /** 四个固定背包（存档原样收下，进场景前由背包服务按配表规整）。 */
    private final PlayerBags bags;
    /** 技能运行态（施法阶段、冷却、行为 / 战斗状态；不持久化，随实例清空）。 */
    private final PlayerSkillState skillState = new PlayerSkillState();
    /** 任务（存档原样收下，派生索引由任务服务在进场景前重建）。 */
    private final PlayerMissions missions;
    /** 宝宝（存档原样收下，进场景前由宝宝服务纠正出战号、同步等级）。 */
    private final PlayerPets pets;
    /** 资产通道幂等账本（与资产同一份记录、同一次围栏写；加载时校验，损坏则原样带回并关闭该玩家的资产通道）。 */
    private final AssetOpLedger assetLedger;
    /** 回合制战斗结算账本（与资产同一份记录、同一次围栏写；加载时校验，损坏则原样带回，结算一律延后、备战一律 1006，scene-battle-spec §7.12）。 */
    private final BattleLedger battleLedger;
    /** 回合制战斗运行态（冻结、进场恢复；不持久化，随实例清空，scene-battle-spec §7.4）。 */
    private final PlayerBattle battle = new PlayerBattle();
    /** 角色名（存档列 player.name；战斗快照的 player_name）。 */
    private String name = "";
    /** 获取滑动窗口（获取异常检测；不持久化，随实例清空）。 */
    private final GainWindows gainWindows = new GainWindows();
    /** 库里此刻的样子（最近一次确认落库的快照）：周期存盘的脏比对基准；null = 不确定（上次在线存盘失败），下次无条件写。 */
    private PlayerSave lastPersisted;
    /** 一次在线存盘已提交、结果还没回来：期间不再提交新的（结果回来后下个周期再比）。 */
    private boolean progressSaveInFlight;
    /** 在途的跨节点换图（null = 没有；不持久化，规则见 {@link SceneWorld} 的「跨节点换图」一节）。 */
    private PlayerSwitch switching;
    /** 当前速度（已截断到信任上限）；(0,0,0) 为静止。 */
    private Vec3 velocity = Vec3.ORIGIN;
    private int syncDirty;
    /** 最近一次收到该玩家客户端消息时的帧号（挂机判定）。 */
    private long lastActiveFrame;

    ScenePlayer(long playerId, long entity, SessionKey session, long ownerEpoch, int classId, int gender,
                String appearanceId, int level, List<Integer> skills, Vec3 position, long nowNanos) {
        this(playerId, entity, session, ownerEpoch, classId, gender, appearanceId, level, skills, position,
                PlayerState.getDefaultInstance(), nowNanos);
    }

    /** @param state 持久化的玩法数据（从未写过为默认实例），这里把各玩法数据恢复到内存状态 */
    ScenePlayer(long playerId, long entity, SessionKey session, long ownerEpoch, int classId, int gender,
                String appearanceId, int level, List<Integer> skills, Vec3 position, PlayerState state,
                long nowNanos) {
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
        if (state.hasFacing()) {
            Facing f = state.getFacing();
            this.rotation = Rotation.newBuilder().setX(f.getX()).setY(f.getY()).setZ(f.getZ()).build();
        }
        this.unknownStateFields = state.getUnknownFields();
        this.wallet = state.hasCurrency() ? Wallet.restore(state.getCurrency()) : Wallet.empty();
        this.attributes = state.hasAttribute() ? PlayerAttributes.restore(state.getAttribute()) : PlayerAttributes.empty();
        if (state.hasVitals()) {
            attributes.restoreVitals(state.getVitals());
        }
        this.bags = state.hasBag() ? PlayerBags.restore(state.getBag()) : PlayerBags.empty();
        this.missions = state.hasMission() ? PlayerMissions.restore(state.getMission()) : PlayerMissions.empty();
        this.pets = state.hasPets() ? PlayerPets.restore(state.getPets()) : PlayerPets.empty();
        this.assetLedger = state.hasAssetLedger() ? AssetOpLedger.restore(state.getAssetLedger()) : AssetOpLedger.empty();
        this.battleLedger = state.hasBattleLedger() ? BattleLedger.restore(state.getBattleLedger()) : BattleLedger.empty();
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

    /** 只供属性系统调用（GM 设等级；调用方负责校验 1..85 并随后重算）。 */
    public void setLevel(int level) {
        this.level = level;
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

    /** 下一次写位置记录用的序号。 */
    public long nextLocationSeq() {
        return ++locationSeq;
    }

    /** 最近一次写位置记录用的序号（在线续期不递增）。 */
    public long locationSeq() {
        return locationSeq;
    }

    /** 同一 epoch 的重复进场接替旧实例时接着它的序号往下数（否则新实例的写会被当成乱序的旧写丢掉）。 */
    void continueLocationSeq(long from) {
        locationSeq = Math.max(locationSeq, from);
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
     * 停下（速度清零，不置脏位）：离场写回 / 被接管 / 停服前、换场景时、跨节点换图冻结时调用（基线 StopMotionForExit；
     * 冻结时由调用方另置速度脏位，让还看得见它的人收到「停了」的 66）。
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
        return new PlayerSave(playerId, ownerEpoch, level, scene.configId(), position, persistentState());
    }

    /** 当前内存状态里需要持久化的玩法数据。新增玩法数据时在这里写、在构造器里恢复。 */
    PlayerState persistentState() {
        PlayerState.Builder state = PlayerState.newBuilder().setUnknownFields(unknownStateFields);
        if (rotation != null) {
            state.setFacing(Facing.newBuilder().setX(rotation.getX()).setY(rotation.getY()).setZ(rotation.getZ()));
        }
        if (!wallet.isPristine()) {
            state.setCurrency(wallet.toState());
        }
        if (!attributes.isPristine()) {
            state.setAttribute(attributes.toState());
        }
        if (!attributes.vitalsFull()) {
            state.setVitals(attributes.toVitals());
        }
        if (!bags.isPristine()) {
            state.setBag(bags.toState());
        }
        if (!missions.isPristine()) {
            state.setMission(missions.toState());
        }
        if (!pets.isPristine()) {
            state.setPets(pets.toState());
        }
        if (!assetLedger.isPristine()) {
            state.setAssetLedger(assetLedger.toState());
        }
        if (!battleLedger.isPristine()) {
            state.setBattleLedger(battleLedger.toState());
        }
        return state.build();
    }

    /** 玩家的货币（逻辑线程上读写）。 */
    public Wallet wallet() {
        return wallet;
    }

    /** 玩家的属性加点状态（逻辑线程上读写；写入只经属性系统）。 */
    public PlayerAttributes attributes() {
        return attributes;
    }

    /** 玩家的四个固定背包（逻辑线程上读写；写入只经背包服务）。 */
    public PlayerBags bags() {
        return bags;
    }

    /** 玩家的宝宝（逻辑线程上读写；写入只经宝宝服务）。 */
    public PlayerPets pets() {
        return pets;
    }

    /** 玩家的技能运行态（逻辑线程上读写；写入只经技能服务）。 */
    public PlayerSkillState skillState() {
        return skillState;
    }

    /** 玩家的任务（逻辑线程上读写；写入只经任务服务）。 */
    public PlayerMissions missions() {
        return missions;
    }

    /** 玩家的资产通道账本（逻辑线程上读写；写入只经资产通道）。 */
    public AssetOpLedger assetLedger() {
        return assetLedger;
    }

    /** 回合制战斗结算账本（逻辑线程上读写；写入只经战斗结算服务）。 */
    public BattleLedger battleLedger() {
        return battleLedger;
    }

    /** 回合制战斗运行态（逻辑线程上读写；改冻结只经 {@code PlayerBattleService}）。 */
    public PlayerBattle battle() {
        return battle;
    }

    /**
     * 有在途的回合制战斗（备战或战斗中，基线 {@code IsInBattle}）：各在途闸的唯一谓词（scene-battle-spec §7.4、§7.13）。
     * 与 {@link #frozen()}（跨节点换图的交出冻结）始终互斥。
     */
    public boolean inBattle() {
        return battle.inBattle();
    }

    /** 角色名（存档列 player.name；新号 / 测试为空串）。 */
    public String name() {
        return name;
    }

    void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /**
     * 最近一次确认落库的玩法数据（资产通道据此判 durable：结局出现在这份里才算已落盘）；null = 不确定（上次在线存盘结局不明）。
     */
    public PlayerState persistedState() {
        return lastPersisted == null ? null : lastPersisted.state();
    }

    /** 玩家的获取滑动窗口（逻辑线程上读写；只由获取异常检测使用）。 */
    public GainWindows gainWindows() {
        return gainWindows;
    }

    PlayerSave lastPersisted() {
        return lastPersisted;
    }

    void markPersisted(PlayerSave save) {
        this.lastPersisted = save;
    }

    boolean progressSaveInFlight() {
        return progressSaveInFlight;
    }

    void setProgressSaveInFlight(boolean inFlight) {
        this.progressSaveInFlight = inFlight;
    }

    /** 跨节点换图的阶段（scene-handoff-spec §5.5）：NONE / RESOLVING（等选目标，不冻结）/ FREEZING（交出在途）。 */
    public SwitchPhase switchPhase() {
        return switching == null ? SwitchPhase.NONE : switching.phase();
    }

    /**
     * 是否处于冻结（交出事务在途，§5.9）：冻结中玩家的可变状态必须与冻结快照一致，各写入口按基线码拒绝
     * （背包 / 宝宝 / 属性 / 任务 1005，货币与资产通道 27003）。RESOLVING 不算冻结。
     */
    public boolean frozen() {
        return switching != null && switching.phase() == SwitchPhase.FREEZING;
    }

    PlayerSwitch switching() {
        return switching;
    }

    void setSwitching(PlayerSwitch switching) {
        this.switching = switching;
    }
}
