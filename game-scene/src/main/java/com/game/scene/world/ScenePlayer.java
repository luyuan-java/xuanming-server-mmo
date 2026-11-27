package com.game.scene.world;

import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.common.base.RoleAdmissionIdentity;
import com.game.common.player.SceneAdmissionAuthority;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorMoveS2C;
import com.game.proto.TeleportS2C;
import com.game.proto.ActorType;
import com.game.proto.Rotation;
import com.game.proto.Transform;
import com.game.scene.asset.AssetOpLedger;
import com.game.scene.battle.BattleLedger;
import com.game.scene.battle.PlayerBattle;
import com.game.scene.inbox.InboxLedger;
import com.game.scene.inbox.PlayerInboxRuntime;
import com.game.scene.player.GainWindows;
import com.game.scene.player.PlayerActivityVitality;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerAttributes;
import com.game.scene.player.PlayerAppearance;
import com.game.scene.player.PlayerBattleResults;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerItemGainBlock;
import com.game.scene.player.PlayerMissions;
import com.game.scene.player.PlayerMysteryMerchant;
import com.game.scene.player.PlayerSeason;
import com.game.scene.player.PlayerPets;
import com.game.scene.player.PlayerRealtimeBuffs;
import com.game.scene.player.PlayerSkillState;
import com.game.scene.player.Wallet;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;

/**
 * 场景里的一个玩家（一次进场一个实例）。只在场景逻辑线程上读写。
 *
 * <p>{@code entity} 是场景内实体号（雪花号，恒非 0，一次进场内稳定），用于技能目标与 21 / 47 / 133 / 135 / 130；
 * 它与 {@code playerId}（ActorCreateS2C.guid、66 的 entity_id）不是一回事。
 *
 * <p>位置只能经所在 {@link Scene} 改（{@link Scene#relocate} / {@link Scene#teleport}），这样格子索引与视野刷新不会漏；
 * 本类只保存状态，规则在 {@link SceneWorld}。
 */
public final class ScenePlayer {

    /** 66 的脏位：transform（location + rotation）。 */
    static final int DIRTY_TRANSFORM = 1;
    /** 66 的脏位：velocity。 */
    static final int DIRTY_VELOCITY = 1 << 1;

    private static final java.util.concurrent.atomic.AtomicLong INSTANCE_SEQ = new java.util.concurrent.atomic.AtomicLong(1L);

    /** 本进程内单调唯一的进场实例标识（供异步回调按值核对身份，严禁跨线程捕获可变 ScenePlayer 引用）。 */
    private final long instanceId = INSTANCE_SEQ.getAndIncrement();
    private final long playerId;
    private final long entity;
    /** 当前绑定的 gate 会话；进入断线保留 / 离线托管后置 null，重连接回时重新绑定。 */
    private SessionKey session;
    private final long ownerEpoch;
    /** 本次SQL OPEN已登记的完整原件；跨本节点换图保持，绝不按当前归属重建。 */
    private RoleAdmissionIdentity admission;
    private final int classId;
    private final int gender;
    /** 角色唯一永久形象（存档列 player.appearance_id；切形象扣卡覆盖）。 */
    private String appearanceId;
    private boolean appearanceIdDirty;
    /** 等级（存档列 player.level；加载时已压回上限）。只经属性系统改（GM 设等级），规则见 PlayerLevels。 */
    private int level;
    /** 持久技能与不可变的本版本配置；瞬时施法状态另存 skillState。 */
    private final com.game.scene.player.PlayerSkills learnedSkills;
    private final com.game.scene.skill.ClassSkillCatalog skillCatalog;
    private final MoveGuard moveGuard;
    private Scene scene;
    /** SQL 成员关系的运行时提示；仅供 AOI 排序，不作为玩法或资产授权，随本次进场实例销毁。 */
    private boolean teamMembershipKnown;
    private long teamId;
    private long teamMembershipEpoch;
    private boolean confirmedTeamLeader;
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
    /** 技能运行态（施法阶段与行为/战斗状态不持久化；冷却开始时刻随玩家存档全量保留）。 */
    private final PlayerSkillState skillState = new PlayerSkillState();
    /** 实时场景 Buff 运行态与下线保留存档（仅 keep_on_logout=1 的条目持久化，加载规整前原样暂存）。 */
    private final PlayerRealtimeBuffs realtimeBuffs;
    /** 任务（存档原样收下，派生索引由任务服务在进场景前重建）。 */
    private final PlayerMissions missions;
    /** 宝宝（存档原样收下，进场景前由宝宝服务纠正出战号、同步等级）。 */
    private final PlayerPets pets;
    /** 资产通道幂等账本（与资产同一份记录、同一次围栏写；加载时校验，损坏则原样带回并关闭该玩家的资产通道）。 */
    private final AssetOpLedger assetLedger;
    /** 回合制战斗结算账本（与资产同一份记录、同一次围栏写；加载时校验，损坏则原样带回，结算一律延后、备战一律 1006，scene-battle-spec §7.12）。 */
    private final BattleLedger battleLedger;
    /**
     * 玩家持久收件箱的消费账本（与 GM 处置的效果同一份记录、同一次围栏写；加载时校验，损坏则原样带回并停用该玩家的收件箱；
     * mmorpg player-inbox-service.md §4.4）。
     */
    private final InboxLedger inboxLedger;
    /** 角色形象 1 小时试用位图、在途试用态与幂等回执（永久形象在 appearanceId）。 */
    private final PlayerAppearance appearance;
    /** 个人物品获取封锁清单（GM 收件箱类型 5 / 6；与收件箱账本同一次存盘提交）。 */
    private final PlayerItemGainBlock itemGainBlock;
    /** 活动中心活跃度积分与档位领取账本（同一次存盘提交）。 */
    private final PlayerActivityVitality activityVitality;
    /** 本人近期战斗结果列表（至多 10 条，结算应用同帧写入、确认落盘后对客户端公开）。 */
    private final PlayerBattleResults battleResults;
    /** 客户端请求幂等键账本 + 装备打造防重水位 + 装备材料自选账本。 */
    private final PlayerClientRequestLedger clientRequestLedger;
    /** 神秘商人个人每轮限购记录。 */
    private final PlayerMysteryMerchant mysteryMerchant;
    private final PlayerSeason season;
    /** 五行试炼志 V2 运行态（含普通首领试炼首通困难门禁）。 */
    private final com.game.scene.player.PlayerTrialJournal trialJournal;
    /** 手艺熟练度、未学技能书架、永久解锁技能与宗师原子代打运行态。 */
    private final com.game.scene.player.PlayerCraft craft;
    /** 瑞兽迎春限时活动进度、永久资格、终身领奖回执与在途开局运行态。 */
    private final com.game.scene.player.PlayerFestivalBeast festivalBeast;
    /** 官方灵石百宝阁个人与共享组限购记录。 */
    private final com.game.scene.player.PlayerSpiritShop spiritShop;
    /** 角色累计总经验（与 player.level 列同一次存盘提交）。 */
    private final com.game.scene.player.PlayerProgression progression;
    /** 青云游历手记双轨进度、已解锁典藏与零战力纪念外显。 */
    private final com.game.scene.player.PlayerTravelPass travelPass;
    /** 待寄个人邮件出箱（名匠代打 95% 净银两分成等，与货币/背包同事务落盘）。 */
    private final com.game.scene.player.PlayerMailOutbox mailOutbox;
    private final com.game.scene.player.BattleReservationBook battleReservations;
    /** 收件箱运行时状态（不持久化；null = 还没在本节点落地过 / 消费关闭；只经 {@code InboxService} 读写）。 */
    private PlayerInboxRuntime inboxRuntime;
    /** 回合制战斗运行态（冻结、进场恢复；不持久化，随实例清空，scene-battle-spec §7.4）。 */
    private final PlayerBattle battle = new PlayerBattle();
    /** 角色名（存档列 player.name；战斗快照的 player_name）。 */
    private String name = "";
    /** 个人局外人观战交流偏好（0=未设置，1=允许，2=禁止观战期间交流，绝不禁止观战）。 */
    private int spectateChatPref;
    /** 上次角色改名成功时刻（Unix 毫秒，存档列 player.renamed_ms；0 = 从未改名）。 */
    private long renamedMs;
    /** 角色改名在途标记（逻辑线程内存态，不持久化）。 */
    private boolean renameInFlight;
    /** 挂机月卡到期时刻（Unix 秒，存档列 player.afk_pass_expires_at；0 = 未开通或已清除）。 */
    private long afkPassExpiresAt;
    /** 最近一次挂机月卡开通幂等请求号（存档列 player.afk_pass_last_req_id）。 */
    private long afkPassLastReqId;
    /** 本次会话挂机月卡开通幂等键有界集合（最多 64 条，启动由存档 afk_pass_last_req_id 预热）。 */
    private final java.util.LinkedHashSet<Long> afkPassRequestIds = new java.util.LinkedHashSet<>();
    /** 最近一次已落盘确认的挂机月卡到期时刻与 Redis 投影确认序号。 */
    private long committedAfkPassExpiresAt;
    private long afkPassProjectionSeq;
    private long afkPassConfirmedSeq;
    /** 断线保留 / 离线托管运行态（null = 在线正常连接）。 */
    private ConnectionLostHold.State connectionLostHold;
    /** 未成年人防沉迷可玩截止时刻（Unix 毫秒，0 = 不限）；由 login 进场带入，封顶断线保留与月卡离线托管。 */
    private long minorPlayDeadlineMs;
    /** 账号级阶段一可交易 60 份配额异步同步运行态（逻辑线程内存态，不持久化）。 */
    private boolean accountStage1TradableLoaded;
    private boolean accountStage1TradableSyncInFlight;
    private int accountStage1TradableUsed;
    private int accountStage1TradableRetryAttempts;
    private long accountStage1TradableNextRetryAtMs;
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
    /** 输入触发的 133 待发标记；只由 applyMove 置位，不落盘。 */
    private boolean moveBroadcastPending;
    /** 瞬移后等待客户端在目标附近首次上报；不落盘，换场景或新瞬移作废。 */
    private TeleportSettle teleportSettle;

    record TeleportSettle(double targetX, double targetY, long deadlineNanos) { }
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
        this(playerId, entity, session, ownerEpoch, classId, gender, appearanceId, level, skills, position, state,
                nowNanos, com.game.scene.skill.ClassSkillCatalog.generic(skills));
    }

    ScenePlayer(long playerId, long entity, SessionKey session, long ownerEpoch, int classId, int gender,
                String appearanceId, int level, List<Integer> seeds, Vec3 position, PlayerState state,
                long nowNanos, com.game.scene.skill.ClassSkillCatalog skillCatalog) {
        this.playerId = playerId;
        this.entity = entity;
        this.session = session;
        this.ownerEpoch = ownerEpoch;
        this.classId = classId;
        this.gender = gender;
        this.appearanceId = appearanceId;
        this.level = level;
        this.skillCatalog = java.util.Objects.requireNonNull(skillCatalog);
        this.learnedSkills = com.game.scene.player.PlayerSkills.restore(state.getSkills());
        skillCatalog.grantSeeds(classId, level, learnedSkills, seeds);
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
        this.inboxLedger = state.hasInboxLedger() ? InboxLedger.restore(state.getInboxLedger()) : InboxLedger.empty();
        this.appearance = state.hasAppearance() ? PlayerAppearance.restore(state.getAppearance()) : PlayerAppearance.empty();
        this.itemGainBlock = state.hasItemGainBlock() ? PlayerItemGainBlock.restore(state.getItemGainBlock()) : PlayerItemGainBlock.empty();
        this.activityVitality = state.hasActivityVitality() ? PlayerActivityVitality.restore(state.getActivityVitality()) : PlayerActivityVitality.empty();
        this.battleResults = state.hasBattleResults() ? PlayerBattleResults.restore(state.getBattleResults()) : PlayerBattleResults.empty();
        this.clientRequestLedger = state.hasClientRequestLedger()
                ? PlayerClientRequestLedger.restore(state.getClientRequestLedger()) : PlayerClientRequestLedger.empty();
        this.mysteryMerchant = state.hasMysteryMerchant()
                ? PlayerMysteryMerchant.restore(state.getMysteryMerchant()) : PlayerMysteryMerchant.empty();
        this.season = state.hasSeason()
                ? PlayerSeason.restore(state.getSeason()) : PlayerSeason.empty();
        this.trialJournal = state.hasTrialJournal()
                ? com.game.scene.player.PlayerTrialJournal.restore(state.getTrialJournal())
                : com.game.scene.player.PlayerTrialJournal.empty();
        this.craft = state.hasCraft()
                ? com.game.scene.player.PlayerCraft.restore(state.getCraft())
                : com.game.scene.player.PlayerCraft.empty();
        this.festivalBeast = state.hasFestivalBeast()
                ? com.game.scene.player.PlayerFestivalBeast.restore(state.getFestivalBeast())
                : com.game.scene.player.PlayerFestivalBeast.empty();
        this.spiritShop = state.hasSpiritShop()
                ? com.game.scene.player.PlayerSpiritShop.restore(state.getSpiritShop())
                : com.game.scene.player.PlayerSpiritShop.empty();
        this.progression = state.hasProgression()
                ? com.game.scene.player.PlayerProgression.restore(state.getProgression())
                : com.game.scene.player.PlayerProgression.empty();
        this.travelPass = state.hasTravelPass()
                ? com.game.scene.player.PlayerTravelPass.restore(state.getTravelPass())
                : com.game.scene.player.PlayerTravelPass.empty();
        this.mailOutbox = state.hasMailOutbox()
                ? com.game.scene.player.PlayerMailOutbox.restore(state.getMailOutbox())
                : com.game.scene.player.PlayerMailOutbox.empty();
        this.battleReservations = state.hasBattleReservations()
                ? com.game.scene.player.BattleReservationBook.restore(state.getBattleReservations())
                : com.game.scene.player.BattleReservationBook.empty();
        if (state.hasSkillCooldowns()) {
            this.skillState.restoreCooldowns(state.getSkillCooldowns());
        }
        this.realtimeBuffs = state.hasSavedBuffs()
                ? PlayerRealtimeBuffs.fromSaved(state.getSavedBuffs())
                : PlayerRealtimeBuffs.empty();
        this.spectateChatPref = state.getSpectateChatPref();
    }

    public long instanceId() {
        return instanceId;
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

    public long teamId() { return teamId; }

    public boolean teamMembershipKnown() { return teamMembershipKnown; }

    /** 最近一次权威组队快照已确认本角色是队长（离线托管跳过 15s 周期跟随重查，但保留跟随意图应对惰性转让）。 */
    public boolean confirmedTeamLeader() { return teamMembershipKnown && teamId != 0 && confirmedTeamLeader; }

    /** 只接受单调的成员关系；相同 epoch 不能换队，缺失快照不能覆盖已经见过的永久版本。 */
    boolean applyTeamMembership(long id, long epoch) {
        return applyTeamMembership(id, epoch, false);
    }

    boolean applyTeamMembership(long id, long epoch, boolean isLeader) {
        if (teamMembershipKnown && (Long.compareUnsigned(epoch, teamMembershipEpoch) < 0
                || epoch == teamMembershipEpoch && id != teamId)) return false;
        teamMembershipKnown = true;
        teamId = id;
        teamMembershipEpoch = epoch;
        confirmedTeamLeader = id != 0 && isLeader;
        return true;
    }

    public RoleAdmissionIdentity admission() { return admission; }

    /** 仅World在加载成功、创建实体时绑定一次；其后任何替换都拒绝。 */
    void bindAdmission(RoleAdmissionIdentity original) {
        SceneAdmissionAuthority.original(original);
        if (admission != null || original.getPlayerId() != playerId || original.getOwnerEpoch() != ownerEpoch
                || original.getSessionId() != session.sessionId()) throw new SecurityException("场景实例原准入不匹配");
        admission = original;
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

    /** 只供角色形象系统调用：消耗形象卡后覆盖角色唯一永久形象。 */
    public void setPermanentAppearanceId(String appearanceId) {
        String next = appearanceId == null ? "" : appearanceId;
        if (!next.equals(this.appearanceId)) {
            this.appearanceId = next;
            this.appearanceIdDirty = true;
        }
    }

    /** 此刻实际生效对外展示的外观 ID：试用有效期内取试用形象，否则取永久形象。 */
    public String effectiveAppearanceId(long nowSec) {
        return appearance.effectiveAppearance(appearanceId, nowSec);
    }

    public PlayerAppearance appearance() {
        return appearance;
    }

    /** 个人物品获取封锁清单（逻辑线程上读写；写入只经 {@code BagService}）。 */
    public PlayerItemGainBlock itemGainBlock() {
        return itemGainBlock;
    }

    /** 活动中心活跃度账本（逻辑线程上读写；写入只经 {@code ActivityVitalityService}）。 */
    public PlayerActivityVitality activityVitality() {
        return activityVitality;
    }

    /** 本人近期战斗结果列表（逻辑线程上读写；结算同帧写入、落盘后对外公开）。 */
    public PlayerBattleResults battleResults() {
        return battleResults;
    }

    /** 客户端请求幂等键账本与装备打造/材料自选水位（逻辑线程上读写）。 */
    public PlayerClientRequestLedger clientRequestLedger() {
        return clientRequestLedger;
    }

    /** 神秘商人个人限购记录（逻辑线程上读写）。 */
    public PlayerMysteryMerchant mysteryMerchant() {
        return mysteryMerchant;
    }

    public PlayerSeason season() {
        return season;
    }

    public int level() {
        return level;
    }

    /** 只供属性系统调用（GM 设等级；调用方负责校验 1..85 并随后重算）。 */
    public void setLevel(int level) {
        this.level = level;
    }

    /** 角色累计总经验持久状态（逻辑线程上读写；与 player.level 同事务落盘）。 */
    public com.game.scene.player.PlayerProgression progression() {
        return progression;
    }

    public List<Integer> skills() {
        return skillCatalog.usableIds(classId, level, learnedSkills);
    }

    public boolean hasSkill(int skillTableId) {
        return skillCatalog.usable(classId, level, learnedSkills, skillTableId);
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

    public Rotation rotation() {
        return rotation;
    }

    /** 当前速度非零；只有正在动的新目标需要 135 补发。 */
    public boolean isMoving() {
        return !velocity.isOrigin();
    }

    void markMoveBroadcast() { moveBroadcastPending = true; }

    boolean moveBroadcastPending() { return moveBroadcastPending; }

    void clearMoveBroadcast() { moveBroadcastPending = false; }

    TeleportSettle teleportSettle() { return teleportSettle; }

    void setTeleportSettle(TeleportSettle settle) { teleportSettle = settle; }

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

    /** 只由 {@link Scene#relocate} / {@link Scene#teleport} 或换场景时（不在任何场景里）调用。 */
    void setPosition(Vec3 position) {
        this.position = position;
    }

    public void setRotation(Rotation rotation) {
        this.rotation = rotation;
    }

    public void setVelocity(Vec3 velocity) {
        this.velocity = velocity;
    }

    /**
     * 只清速度，不置脏位、也不发 133。离场 / 换场景 / 瞬移由销毁与创建传达；停服不发下行。
     * 仍留在场景里的停步必须经 SceneWorld 的 haltMotion，原先在动才当场发 133，并保留 66 脏位语义。
     */
    public void stopMotion() {
        this.velocity = Vec3.ORIGIN;
    }

    public void markTransformAndVelocityDirty() {
        syncDirty |= DIRTY_TRANSFORM | DIRTY_VELOCITY;
    }

    public void markTransformDirty() {
        syncDirty |= DIRTY_TRANSFORM;
    }

    public void markVelocityDirty() {
        syncDirty |= DIRTY_VELOCITY;
    }

    /**
     * 有人刚开始看见它（进视野 / 进场）：保留原有 66 补发语义；21 / 47 已带朝向，在动目标另由 135 补速度。
     * 朝向上报过就置 transform 脏位（transform 带 rotation），在动就置 velocity 脏位，
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
     * 21 / 47 里的一项：location 恒在，rotation 非 null 时带上（2.10 有意差异 X3，基线只填 location）。
     * scale 与玩家的 config_id 不填；客户端创建时即读取朝向。
     */
    ActorCreateS2C toActorCreate() {
        return ActorCreateS2C.newBuilder()
                .setEntity(entity)
                .setTransform(movementTransform())
                .setActorType(ActorType.ACTOR_TYPE_PLAYER)
                .setGuid(playerId)
                .setAppearanceId(effectiveAppearanceId(java.time.Instant.now().getEpochSecond()))
                .setClassId(classId)
                .setGender(gender)
                .build();
    }

    /** 133 的权威快照：实体号不是 playerId；停止时 velocity 仍存在（线上 1a 00）。 */
    ActorMoveS2C toActorMove(long serverTimeMs) {
        return ActorMoveS2C.newBuilder().setEntity(entity).setTransform(movementTransform())
                .setVelocity(velocity.toVelocity()).setServerTimeMs(serverTimeMs)
                .setMoveState(isMoving() ? 1 : 0).build();
    }

    /** 130 只给本人：位置恒在，朝向非 null 必带；input_seq 不设，与基线一致。 */
    TeleportS2C toTeleport(int reasonCode) {
        return TeleportS2C.newBuilder().setEntity(entity).setTransform(movementTransform())
                .setReason(reasonCode).build();
    }

    private Transform.Builder movementTransform() {
        Transform.Builder transform = Transform.newBuilder().setLocation(position.toProto());
        if (rotation != null) transform.setRotation(rotation);
        return transform;
    }

    /**
     * 66 {@code ActorBaseAttributesS2C}：只写 {@code dirty} 指定的字段（契约文档 AOI §5.1）。
     * <ul>
     *   <li>{@code entity_id} 每条都带，取 player_id（guid 口径，不是场景实体号）；当前 C++ 也取 PlayerGuidComp，
     *       旧基线曾缺此字段；本批不改变既有 66 内容或同步规则；</li>
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
        return new PlayerSave(playerId, ownerEpoch, level, scene.configId(), position, persistentState(),
                appearanceIdDirty ? appearanceId : "", afkPassExpiresAt, afkPassLastReqId);
    }

    /** 当前内存状态里需要持久化的玩法数据。新增玩法数据时在这里写、在构造器里恢复。 */
    PlayerState persistentState() {
        PlayerState.Builder state = PlayerState.newBuilder().setUnknownFields(unknownStateFields);
        if (rotation != null) {
            state.setFacing(Facing.newBuilder().setX(rotation.getX()).setY(rotation.getY()).setZ(rotation.getZ()));
        }
        if (!learnedSkills.isPristine()) state.setSkills(learnedSkills.toState());
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
        if (!inboxLedger.isPristine()) {
            state.setInboxLedger(inboxLedger.toState());
        }
        if (!appearance.isPristine()) {
            state.setAppearance(appearance.toState());
        }
        if (!itemGainBlock.isPristine()) {
            state.setItemGainBlock(itemGainBlock.toState());
        }
        if (!activityVitality.isPristine()) {
            state.setActivityVitality(activityVitality.toState());
        }
        if (!battleResults.isPristine()) {
            state.setBattleResults(battleResults.toState());
        }
        if (!clientRequestLedger.isPristine()) {
            state.setClientRequestLedger(clientRequestLedger.toState());
        }
        if (!mysteryMerchant.isPristine()) {
            state.setMysteryMerchant(mysteryMerchant.toState());
        }
        if (!season.isEmpty()) {
            state.setSeason(season.toState());
        }
        if (!trialJournal.isPristine()) {
            state.setTrialJournal(trialJournal.toState());
        }
        if (!craft.isPristine()) {
            state.setCraft(craft.toState());
        }
        if (!festivalBeast.isPristine()) {
            state.setFestivalBeast(festivalBeast.toState());
        }
        if (!spiritShop.isPristine()) {
            state.setSpiritShop(spiritShop.toState());
        }
        if (!progression.isPristine()) {
            state.setProgression(progression.toState());
        }
        if (!travelPass.isPristine()) {
            state.setTravelPass(travelPass.toState());
        }
        if (!mailOutbox.isPristine()) {
            state.setMailOutbox(mailOutbox.toState());
        }
        if (!skillState.isCooldownStatePristine()) {
            state.setSkillCooldowns(skillState.toCooldownState());
        }
        if (!realtimeBuffs.isPristine()) {
            state.setSavedBuffs(realtimeBuffs.toState());
        }
        if (spectateChatPref != 0) {
            state.setSpectateChatPref(spectateChatPref);
        }
        // 战斗道具预留非空时落盘；空预留保活现有空态快照脏比较不变，存盘组件存在性门禁由 SaveRowGuard 提供。
        if (!battleReservations.isEmpty()) {
            state.setBattleReservations(battleReservations.toState());
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
    public com.game.scene.player.PlayerSkills learnedSkills() { return learnedSkills; }

    public com.game.scene.skill.ClassSkillCatalog skillCatalog() { return skillCatalog; }

    public PlayerSkillState skillState() {
        return skillState;
    }

    /** 玩家实时场景 Buff 运行态与下线保留存档（逻辑线程上读写；加载规整经 {@code SkillService.initializeOnLoad}）。 */
    public PlayerRealtimeBuffs realtimeBuffs() {
        return realtimeBuffs;
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

    /** 五行试炼志 V2 运行态（逻辑线程上读写；含普通首领首通门禁与永久/赛季战法印）。 */
    public com.game.scene.player.PlayerTrialJournal trialJournal() {
        return trialJournal;
    }

    /** 手艺熟练度、未学技能书架、永久解锁技能与宗师原子代打运行态（逻辑线程上读写）。 */
    public com.game.scene.player.PlayerCraft craft() {
        return craft;
    }

    /** 瑞兽迎春限时活动运行态（逻辑线程上读写）。 */
    public com.game.scene.player.PlayerFestivalBeast festivalBeast() {
        return festivalBeast;
    }

    /** 官方灵石百宝阁个人与共享组限购运行态（逻辑线程上读写；写入只经 {@code SpiritShopService}）。 */
    public com.game.scene.player.PlayerSpiritShop spiritShop() {
        return spiritShop;
    }

    /** 青云游历手记双轨进度、已解锁典藏与零战力纪念外显运行态（逻辑线程上读写；写入只经 {@code TravelPassService}）。 */
    public com.game.scene.player.PlayerTravelPass travelPass() {
        return travelPass;
    }

    /** 玩家待寄个人邮件出箱（逻辑线程上读写；写入与消费只经 {@code PlayerMailOutboxService}）。 */
    public com.game.scene.player.PlayerMailOutbox mailOutbox() {
        return mailOutbox;
    }

    /** 战斗道具预留账本（逻辑线程上读写；与背包同在 PlayerState 单事务围栏提交）。 */
    public com.game.scene.player.BattleReservationBook battleReservations() {
        return battleReservations;
    }

    /** 收件箱消费账本（逻辑线程上读写；写入只经收件箱消费端）。 */
    public InboxLedger inboxLedger() {
        return inboxLedger;
    }

    /** 收件箱运行时状态；null = 还没在本节点落地过（或消费关闭）。 */
    public PlayerInboxRuntime inboxRuntime() {
        return inboxRuntime;
    }

    /** 只供收件箱消费端调用（落地时建、同 epoch 接替时转交）。 */
    public void setInboxRuntime(PlayerInboxRuntime runtime) {
        this.inboxRuntime = runtime;
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

    /** 上次角色改名成功时刻（Unix 毫秒；0 = 从未改名）。 */
    public long renamedMs() {
        return renamedMs;
    }

    void setRenamedMs(long renamedMs) {
        this.renamedMs = renamedMs;
    }

    /** SQL 原子改名提交后更新内存角色名与冷却时间戳。 */
    public void applyRename(String newName, long renamedMs) {
        this.name = newName == null ? "" : newName;
        this.renamedMs = renamedMs;
    }

    public boolean renameInFlight() {
        return renameInFlight;
    }

    public void setRenameInFlight(boolean inFlight) {
        this.renameInFlight = inFlight;
    }

    public long afkPassExpiresAt() {
        return afkPassExpiresAt;
    }

    public void setAfkPassExpiresAt(long expiresAtSec) {
        this.afkPassExpiresAt = expiresAtSec;
    }

    void restoreAfkPass(long expiresAtSec, long lastReqId) {
        this.afkPassExpiresAt = expiresAtSec;
        this.afkPassLastReqId = lastReqId;
        this.committedAfkPassExpiresAt = expiresAtSec;
        if (lastReqId != 0) {
            this.afkPassRequestIds.add(lastReqId);
        }
    }

    public boolean hasAfkPassRequest(long reqId) {
        return reqId != 0 && afkPassRequestIds.contains(reqId);
    }

    public void recordAfkPassRequest(long reqId) {
        if (reqId == 0) {
            return;
        }
        this.afkPassLastReqId = reqId;
        this.afkPassRequestIds.add(reqId);
        while (this.afkPassRequestIds.size() > 64) {
            var it = this.afkPassRequestIds.iterator();
            it.next();
            it.remove();
        }
    }

    public long initAfkPassProjection(long expiresAtSec) {
        this.committedAfkPassExpiresAt = expiresAtSec;
        this.afkPassProjectionSeq = 1L;
        this.afkPassConfirmedSeq = 0L;
        return 1L;
    }

    public boolean needsAfkPassProjectionSync(long committedExpiresAtSec) {
        return committedExpiresAtSec != this.committedAfkPassExpiresAt
                || this.afkPassConfirmedSeq != this.afkPassProjectionSeq;
    }

    public long nextAfkPassProjectionSeq(long committedExpiresAtSec) {
        this.committedAfkPassExpiresAt = committedExpiresAtSec;
        return ++this.afkPassProjectionSeq;
    }

    public void confirmAfkPassProjection(long seq) {
        if (seq > this.afkPassConfirmedSeq && seq <= this.afkPassProjectionSeq) {
            this.afkPassConfirmedSeq = seq;
        }
    }

    public long committedAfkPassExpiresAt() {
        return committedAfkPassExpiresAt;
    }

    public boolean hasUnconfirmedAfkPassProjection() {
        return afkPassConfirmedSeq != afkPassProjectionSeq;
    }

    public ConnectionLostHold.State connectionLostHold() {
        return connectionLostHold;
    }

    /** 本场景已知的所属账号阶段一累计可交易已用份数（>= 本角色 tradable_earned_total，封顶 60）。 */
    public int knownAccountStage1TradableUsed() {
        var state = clientRequestLedger.materialReward();
        int charUsed = state.getMigratedToV2() ? state.getTradableEarnedTotal() : 0;
        int merged = Math.max(charUsed, accountStage1TradableUsed);
        return Math.min(merged, PlayerClientRequestLedger.TRADABLE_LIFETIME_LIMIT);
    }

    boolean accountStage1TradableLoaded() {
        return accountStage1TradableLoaded;
    }

    void setAccountStage1TradableLoaded(boolean loaded) {
        this.accountStage1TradableLoaded = loaded;
    }

    boolean accountStage1TradableSyncInFlight() {
        return accountStage1TradableSyncInFlight;
    }

    void setAccountStage1TradableSyncInFlight(boolean inFlight) {
        this.accountStage1TradableSyncInFlight = inFlight;
    }

    int accountStage1TradableUsed() {
        return accountStage1TradableUsed;
    }

    void mergeAccountStage1TradableUsed(int used) {
        int clamped = Math.min(Math.max(0, used), PlayerClientRequestLedger.TRADABLE_LIFETIME_LIMIT);
        if (clamped > this.accountStage1TradableUsed) {
            this.accountStage1TradableUsed = clamped;
        }
    }

    int incrementAccountStage1TradableRetryAttempts() {
        return ++this.accountStage1TradableRetryAttempts;
    }

    void resetAccountStage1TradableRetry() {
        this.accountStage1TradableRetryAttempts = 0;
        this.accountStage1TradableNextRetryAtMs = 0L;
    }

    long accountStage1TradableNextRetryAtMs() {
        return accountStage1TradableNextRetryAtMs;
    }

    void setAccountStage1TradableNextRetryAtMs(long nextRetryAtMs) {
        this.accountStage1TradableNextRetryAtMs = nextRetryAtMs;
    }

    void enterConnectionLostHold(ConnectionLostHold.State hold) {
        this.session = null;
        this.connectionLostHold = hold;
    }

    void resumeConnectionLostHold(SessionKey newSession) {
        this.session = java.util.Objects.requireNonNull(newSession, "newSession");
        this.connectionLostHold = null;
    }

    public long minorPlayDeadlineMs() {
        return minorPlayDeadlineMs;
    }

    void setMinorPlayDeadlineMs(long minorPlayDeadlineMs) {
        this.minorPlayDeadlineMs = Math.max(0L, minorPlayDeadlineMs);
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

    /**
     * 换图在途的阶段（scene-handoff-spec §5.5、zone-travel-spec §5.5）：NONE / PRECHECK（226 的在队检查，不冻结）/ RESOLVING（等选目标，不冻结）/
     * FREEZING（交出在途）。只读状态、不做过期判定；要判「在途」挡请求的经 {@link SceneWorld#switchInFlight}。
     */
    public SwitchPhase switchPhase() {
        return switching == null ? SwitchPhase.NONE : switching.phase();
    }

    /**
     * 是否处于冻结（交出事务在途，§5.9；跨节点换图与跨 zone 传送都算）：冻结中玩家的可变状态必须与冻结快照一致，各写入口按基线码拒绝
     * （背包 / 宝宝 / 属性 / 任务 1005，货币与资产通道 27003）。PRECHECK / RESOLVING 不算冻结。
     */
    public boolean frozen() {
        return switching != null && switching.phase() == SwitchPhase.FREEZING;
    }

    public int spectateChatPref() {
        return spectateChatPref;
    }

    public void setSpectateChatPref(int pref) {
        this.spectateChatPref = pref;
    }

    PlayerSwitch switching() {
        return switching;
    }

    void setSwitching(PlayerSwitch switching) {
        this.switching = switching;
    }
}
