package com.game.scene.world;

import static com.game.scene.world.SceneMessageIds.push;

import com.game.api.ChannelKinds;
import com.game.api.proto.ChannelState;
import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.WorldChannel;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.MessageContent;
import com.game.proto.MoveAckS2C;
import com.game.proto.SceneInfoComp;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.BroadcastKind;
import com.game.scene.metrics.SceneMetrics.ChannelPlanApply;
import com.game.scene.metrics.SceneMetrics.ChannelRelocation;
import com.game.scene.metrics.SceneMetrics.InstanceEvent;
import com.game.scene.metrics.SceneMetrics.InstanceKind;
import com.game.scene.metrics.SceneMetrics.MirrorRequest;
import com.game.scene.metrics.SceneMetrics.MirrorResolve;
import com.game.scene.metrics.SceneMetrics.MoveResult;
import com.game.scene.metrics.SceneMetrics.PeriodicSave;
import com.game.scene.metrics.SceneMetrics.SwitchResolve;
import com.game.scene.metrics.SceneMetrics.TransferEnter;
import com.game.scene.metrics.SceneMetrics.TransferResult;
import com.game.scene.player.PlayerLevels;
import com.game.scene.team.TeamFollow;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.LoadResult;
import com.game.scene.world.PlayerRepository.ProbeOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.RemoteSwitchTargets.Selection;
import com.game.table.CommonErrorTip;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景节点上的全部场景与玩家状态，以及进场 / 离场 / 换场景 / 移动 / 视野 / 属性同步的规则。
 *
 * <p><b>线程模型</b>：本类不加锁，所有方法只能在场景逻辑线程上调用（包括 {@link PlayerRepository#load} 的回调与
 * {@link #step()}）。阻塞 I/O 全在 {@link PlayerRepository} 实现里，出站全经 {@link ClientSink}，时间经 {@link SceneClock}，
 * 所以本类可以脱离 Netty / MySQL / 真实时钟单测。
 *
 * <p><b>进场下行顺序</b>（契约文档 {@code docs/reference/mmorpg-client-contract-scene.md} §3）：
 * 79 NotifyEnterScene → 21 NotifyActorCreate(自己) → 47 NotifyActorListCreate(进场者看得见的人，非空才发)
 * → 给看得见进场者的人补发 21(新进场者)，最后给 gate 回 {@code PlayerEnterResult}。与基线的两处差异是有意修复：
 * 每次进场成功都发 79 / 21（基线同场景幂等早退不发）；老观察者会收到新进场者的 21（基线收不到）。
 * 进场失败只回 {@code PlayerEnterResult{tip=3023}}，给客户端的 23 由 gate 发（见 {@code failEnter}）。
 * 进场结果总是回显这次进场的 owner_epoch，gate 据此丢弃同一会话上更早一次进场的迟到结果。
 *
 * <p><b>视野</b>：每个场景一个 {@link ViewIndex}（均匀方格 + 双向兴趣列表，规则见该类）。进场 / 离场当场发 21 / 47 / 51；
 * 移动带来的进出视野在帧里合并成每个观察者一条 47、一条 64。51、66、70 的收件人都是「看得见该玩家的人」，
 * 所以客户端收到某实体的任何推送之前一定先收到过它的创建消息，收到销毁消息之后不会再收到它的推送。
 * 21 / 47 只带位置；有新观察者时把目标的朝向 / 速度置脏，下一个同步帧的 66 补给全部观察者（修基线缺口 9，
 * 不另发单播，66 仍是每实体每 100 ms 至多一条）。
 *
 * <p><b>移动</b>（契约文档 {@code mmorpg-client-contract-movement.md}）：134 / 132 / 131 在收到时当场裁决（{@link #applyMove}），
 * 永不回包；只有裁决位置与上报位置水平偏差 &gt; 0.5 m 时给本人发 137。
 *
 * <p><b>帧</b>（{@link #step()}，20 FPS，由 {@link SceneTicker} 驱动）：外推（含挂机停推）→ 视野刷新并发 47 / 64 →
 * 偶数帧属性同步（66）→ 帧号 +1。外推放在视野刷新之前，这一帧的 47 / 64 与随后的 66 用的是同一份位置
 * （基线是视野 → 外推 → 同步，移动带来的 47 / 64 晚一帧；两种顺序都满足「47 先于 66、64 之后不再有 66」）。
 *
 * <p><b>归属</b>（xm-player-store 的 PlayerStore「归属协议」）：
 * <ul>
 *   <li>进场要求库里的 {@code owner_epoch} 等于 gate 带来的 epoch（login 进游戏时夺得），且不低于本节点上同一玩家
 *       现有实例的 epoch；</li>
 *   <li>玩家离开（主动 / 断线 / 链路断开 / 停服）一律「最终写回并释放」（{@link PlayerRepository#save}），
 *       先清速度再写回（基线 StopMotionForExit 的顺序），写回的是离开这一刻的位置（外推在同一线程上，离开之后不会再推）；
 *       之后到来的移动输入找不到实例，直接丢弃；
 *       没进成的进场（失败、取消、链路断开时还在加载）只释放（{@link PlayerRepository#release}），
 *       否则那份归属要等租约过期才能再被夺取；</li>
 *   <li>login 发来接管请求（{@link #onTakeoverRequested}，别的会话要进这个角色）：持有该 epoch 的实例写回释放，
 *       并通知 gate 把旧会话踢下线（23 {2017}）；</li>
 *   <li>续约报告失去归属（{@link #onOwnershipLost}）：实例的写回只会被围栏拒掉，立即移除（不写回）并踢掉会话。</li>
 *   <li>周期存盘（{@link #saveDuePlayers}，每秒一次）：{@code player_id} 对周期取模等于当前槽号的玩家到期，每人每周期恰好一次；
 *       与上次确认落库的快照相同就跳过（基线脏比较快路径），不同才提交在线存盘（{@link PlayerRepository#saveProgress}，不释放归属）。
 *       同一玩家同时至多一个在途；成功才更新快照（失败下个周期按最新状态重写）；被围栏拒绝 = 已失去归属，按续约失去归属处理。
 *       在线存盘要求归属未释放，迟到的在线存盘盖不过已提交的最终写回，所以离场不必等在途的在线存盘。</li>
 * </ul>
 *
 * <p><b>技能</b>：xm-player-store 目前没有技能列，每次进场都按配表发放初始技能（等同基线「新号」），
 * 老号存档里的技能要等存储补列后再接。
 *
 * <p><b>主世界频道</b>（批次 5.1，scene-channels-spec §4.10、§4.12）：场景（频道）不再由节点自己发号自建，而是按 scene-manager 写在 Redis 的
 * 频道计划建出来（{@link #applyChannelPlan}，scene_id 来自计划）；计划把频道转为排空、或本地有而计划里没有时，频道转为排空中：
 * 在场玩家由 {@link #drainStep} 同节点改派（同图优先，否则本节点默认大世界），空了即销毁。排空中的频道不接受 63 显式进入、
 * 进场加载完成时改进兄弟频道。对应基线 C++ CreateScene / DestroyScene（cpp/nodes/scene/handler/grpc/scene_node_service.cpp:19-125）
 * 与 BeginSceneDrain（cpp/libs/services/scene/player/system/player_lifecycle.cpp:2140-2363），Java 不经 RPC、不跨进程（D3、D8）。
 *
 * <p><b>跨节点换图</b>（批次 5.2，scene-handoff-spec §5.5–§5.8；装配见 {@link CrossNodeSwitch}）：63 的目标本节点解析不了时
 * （{@link SwitchTarget.Remote}）先回应答 {@code {0}}、进 RESOLVING，经 scene-manager 选目标（{@link RemoteSwitchTargets}）；结果在别的节点就
 * 冻结（停下 → 拍冻结快照 → FREEZING）并提交一笔<b>交出</b>事务（{@link PlayerRepository#handOff}：带围栏写回快照 + epoch E→E+1，原子）。
 * 交出提交后移除实例（旁人 51，不写回、不拍快照）、留墓碑，经同一条链路给 gate 发 {@code PlayerTransfer}，gate 改绑后由目标节点
 * 按普通进场加载 E+1（{@code PlayerEnter.transfer = true}：不拍 LOGIN 快照、立即续约一次）。冻结中的离开 / 接管只记下，等交出结局出来再处理；
 * 冻结中续约报失去 E、在线存盘被围栏拒都不踢人（交出提交后 E 当然写不进去），由交出结局裁决。新 epoch E+1 的释放方只有三个且互斥：
 * 源节点（只在 PlayerTransfer 确定没发出时）、gate 的 abandonEnter（只在 PlayerEnter{E+1} 确定没发出时）、目标节点的进场失败 / 离场。
 *
 * <p><b>镜像 / 副本实例</b>（批次 5.3，dungeon-mirror-spec §6.7–§6.11）：实例由本节点自有、随进程消亡，节点目录（{@link #sceneEntries}）是唯一登记（D1）。
 * 镜像恒与源频道同节点（D2）：63 {@code {mirror_config_id ≠ 0, scene_id = 0}} 同步校验后回 {@code {0}}、进 RESOLVING，向 scene-manager 取一个全服
 * scene_id（{@link InstanceIds}），结果回到逻辑线程复核后在同一个任务里建镜像（{@link #createInstance}）并 {@link #switchScene} 换入。副本只经
 * dev 管理口建（{@link #createDungeon}）。实例空置满超时进入回收宽限、宽限满且仍空才销毁（宽限内在途进场到达即复活）；源频道销毁时镜像级联排空、
 * 居民同图改派；管理口显式销毁同样「排空后销毁」（{@link #maintainScenes}、{@link #destroyInstance}）。人数、在途进场、销毁同在本线程，
 * 不需要基线那套 Redis Lua CAS。
 *
 * <p><b>指标</b>（{@link SceneMetrics}）：场景配置下的在线人数在每次人数变化后推送绝对值；移动裁决、视野变化通知、
 * 帧与帧内广播耗时（经 {@link SceneClock} 计时）、频道数与计划应用 / 改派、跨节点换图的选目标 / 交出 / 交出进场都在这里记，
 * 与规则写在同一处，不另设观察者。
 */
public final class SceneWorld {

    private static final Logger log = LoggerFactory.getLogger(SceneWorld.class);

    private static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    /** 63 参数错（镜像分支的一切同步拒绝也用它，dungeon-mirror-spec Q11）；管理口：Dungeon 表没有这一行 / 销毁的是主世界频道。 */
    private static final int ENTER_PARAM_ERROR = SceneErrorTip.scene_error.kEnterSceneParamError_VALUE;
    /** 管理口销毁：本节点没有这个场景。 */
    private static final int SCENE_NOT_FOUND = SceneErrorTip.scene_error.kEnterSceneNotFound_VALUE;
    /** 选目标调用失败 / 超时时推的 tip（基线第一跳 gRPC 传输失败推 23 {1003}，player_lifecycle.cpp:3195-3216）。 */
    private static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    /** 被别的会话接管 / 失去归属时推给旧会话的 tip（基线顶号同码，经 23 推送，本里程碑不发 34）。 */
    static final int KICKED_BY_ANOTHER = LoginErrorTip.login_error.kLoginBeKickByAnOtherAccount_VALUE;
    /**
     * RESOLVING 槽比选目标的本地兜底超时多活的时长：结果回调由实现保证恰好一次（兜底超时到了也会回），这个槽只防回调丢失
     * 让玩家永远卡在 3014；多给 1 s，正常的超时结果总是先于槽过期回来。
     */
    static final long RESOLVE_SLOT_GRACE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** 位置续期的槽数（每秒一个槽）：每个在线玩家每这么多秒续一次。 */
    static final int LOCATION_REFRESH_SLOTS = (int) PlayerLocationDirectory.REFRESH_INTERVAL.toSeconds();

    private final SceneTables tables;
    private final SceneMessageIds ids;
    private final ClientSink sink;
    private final PlayerRepository repository;
    /**
     * 实体号的发号器（本节点的雪花），必须恒非 0。生产的场景号来自频道计划（scene-manager 全服租约发，D18），
     * 只有测试 / 本地入口 {@link #createScene(int)} 还用它发场景号。
     */
    private final LongSupplier idGenerator;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private final PlayerInitializer playerInitializer;
    private final PlayerSnapshots snapshots;
    private final PlayerLocations locations;
    private final TeamFollow teamFollow;
    private final CrossNodeSwitch crossNode;
    private final SceneInstances instances;
    private final BattleHooks battle;

    private final Map<Long, Scene> scenes = new LinkedHashMap<>();
    private final Map<Long, ScenePlayer> playersById = new HashMap<>();
    private final Map<SessionKey, ScenePlayer> playersBySession = new HashMap<>();
    /** 场景实体号 → 玩家（节点内全部场景；技能目标按实体号找人，同基线 actorRegistry）。 */
    private final Map<Long, ScenePlayer> playersByEntity = new HashMap<>();
    /** 已收到 PlayerEnter、正在加载存档的会话。离开 / 断链时删掉即取消，加载回来发现不在就丢弃。 */
    private final Map<SessionKey, PendingEnter> pendingEnters = new HashMap<>();
    /** 帧内视野变化的复用缓冲（只在 {@link #step()} 里用）。 */
    private final ViewChanges viewChanges = new ViewChanges();
    private boolean acceptingEnters = true;
    /** 已应用的频道计划版本（节点目录的 applied_plan_version；0 = 还没应用过）。 */
    private long appliedPlanVersion;
    /** 停服开始后不再做周期存盘（最终写回由 {@link #shutdown()} 统一做）。 */
    private boolean periodicSaveStopped;
    /** 周期存盘已走过的秒数（槽号 = 它对存盘周期取模）。 */
    private long saveSecond;
    /** 位置续期已走过的秒数（槽号 = 它对 {@link #LOCATION_REFRESH_SLOTS} 取模）。 */
    private long locationSecond;
    /** 已跑过的帧数（下一帧的帧号）。偶数帧做属性同步。 */
    private long frame;
    /** 跨节点换图的令牌（单调递增，只用于日志对照）。 */
    private long switchTokens;
    /** 冻结中（交出在途）的玩家数（指标 {@code xm.scene.transfers.in.flight}）。 */
    private int transfersInFlight;
    /** 交出后留下的墓碑（会话 → 墓碑；见 {@link TransferTombstone}）。过期的在每秒的位置续期里清掉。 */
    private final Map<SessionKey, TransferTombstone> transferTombstones = new HashMap<>();

    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics) {
        this(tables, ids, sink, repository, idGenerator, clock, metrics, PlayerInitializer.NONE, PlayerSnapshots.NONE);
    }

    /**
     * @param playerInitializer 玩家实例建好后、进场景前调用（玩法按配表规整状态、算派生值）
     * @param snapshots         进场 / 离场写回时各拍一份玩家快照（回档素材）
     */
    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics,
                      PlayerInitializer playerInitializer, PlayerSnapshots snapshots) {
        this(tables, ids, sink, repository, idGenerator, clock, metrics, playerInitializer, snapshots,
                PlayerLocations.NONE);
    }

    /** @param locations 玩家位置记录（进场 / 换场景写、在线续期、断线缩到重连租约、主动离开删），login 进游戏时按它落点 */
    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics,
                      PlayerInitializer playerInitializer, PlayerSnapshots snapshots, PlayerLocations locations) {
        this(tables, ids, sink, repository, idGenerator, clock, metrics, playerInitializer, snapshots, locations,
                TeamFollow.NONE);
    }

    /** @param teamFollow 进场 / 换场景之后的组队跟随检查（team-spec §6.10；缺省不跟随） */
    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics,
                      PlayerInitializer playerInitializer, PlayerSnapshots snapshots, PlayerLocations locations,
                      TeamFollow teamFollow) {
        this(tables, ids, sink, repository, idGenerator, clock, metrics, playerInitializer, snapshots, locations,
                teamFollow, CrossNodeSwitch.DISABLED);
    }

    /** @param crossNode 跨节点换图的装配（批次 5.2；{@link CrossNodeSwitch#DISABLED} = 63 的远端去向回 3023） */
    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics,
                      PlayerInitializer playerInitializer, PlayerSnapshots snapshots, PlayerLocations locations,
                      TeamFollow teamFollow, CrossNodeSwitch crossNode) {
        this(tables, ids, sink, repository, idGenerator, clock, metrics, playerInitializer, snapshots, locations,
                teamFollow, crossNode, SceneInstances.DISABLED);
    }

    /**
     * @param instances 镜像 / 副本实例的装配与参数（批次 5.3；{@link SceneInstances#DISABLED} = 不接取号：63 镜像分支受理后推 23 {1003}，
     *                  节点本地的回收 / 级联照常）
     */
    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics,
                      PlayerInitializer playerInitializer, PlayerSnapshots snapshots, PlayerLocations locations,
                      TeamFollow teamFollow, CrossNodeSwitch crossNode, SceneInstances instances) {
        this(tables, ids, sink, repository, idGenerator, clock, metrics, playerInitializer, snapshots, locations,
                teamFollow, crossNode, instances, BattleHooks.NONE);
    }

    /**
     * @param battle 回合制战斗的钩子（批次 6.3：进场恢复、落盘后销账、原地解冻后重跑进场恢复；{@link BattleHooks#NONE} = 不接战斗）
     */
    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics,
                      PlayerInitializer playerInitializer, PlayerSnapshots snapshots, PlayerLocations locations,
                      TeamFollow teamFollow, CrossNodeSwitch crossNode, SceneInstances instances, BattleHooks battle) {
        this.battle = battle;
        this.crossNode = crossNode;
        this.instances = instances;
        this.playerInitializer = playerInitializer;
        this.snapshots = snapshots;
        this.locations = locations;
        this.teamFollow = teamFollow;
        this.tables = tables;
        this.ids = ids;
        this.sink = sink;
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ 场景

    /**
     * 用本节点的发号器建一个承载中的场景。<b>只给单测与本地装配用</b>：生产路径只走频道计划（{@link #applyChannelPlan}，
     * scene_id 由 scene-manager 全服租约发，D18）——这里发的号与别的节点不保证不撞。
     */
    public Scene createScene(int configId) {
        return addScene(SceneInfoComp.newBuilder().setSceneConfigId(configId).setSceneId(nextId()).build());
    }

    private Scene addScene(SceneInfoComp info) {
        return addScene(info, SceneKind.WORLD, 0);
    }

    /**
     * 登记一个场景（低层入口，<b>不做业务校验</b>）：频道由 {@link #applyChannelPlan} / {@link #createScene} 经 {@link #addScene(SceneInfoComp)} 进来；
     * 批次 5.3 的镜像 / 副本实例只能经建实例的入口（dungeon-mirror-spec §6.8 {@code createInstance(spec)}：号非 0、种类、源是本地未排空的主世界频道、
     * 上限、停止接客等全部校验过）再调这里。scene_id 本地已存在是调用方的编程错误（号全服唯一），抛 {@link IllegalStateException}、不覆盖。
     *
     * @param sourceSceneId 镜像的源频道（{@link SceneKind#MIRROR} 必须非 0），其它种类为 0
     */
    Scene addScene(SceneInfoComp info, SceneKind kind, long sourceSceneId) {
        if (scenes.containsKey(info.getSceneId())) {
            throw new IllegalStateException("场景号本地已存在 scene_id=" + Long.toUnsignedString(info.getSceneId()));
        }
        Scene scene = new Scene(info, kind, sourceSceneId);
        scenes.put(scene.sceneId(), scene);
        publishPopulation(scene.configId());
        publishChannels();
        if (kind.isInstance()) {
            log.info("创建实例 scene_id={} scene_config_id={} kind={} source={}", Long.toUnsignedString(scene.sceneId()),
                    scene.configId(), kind, Long.toUnsignedString(sourceSceneId));
        } else {
            log.info("创建场景 scene_id={} scene_config_id={}", Long.toUnsignedString(scene.sceneId()), scene.configId());
        }
        return scene;
    }

    /**
     * 把某场景配置下的在线人数（各频道合计）推给指标。频道数受 World 表与期望频道数约束（每节点至多几百个），按配置现数一遍即可；
     * 推的是绝对值，任何一次人数变化后推都能纠正之前的偏差（频道销毁后剩下的同图频道照样合计）。
     */
    private void publishPopulation(int configId) {
        int players = 0;
        for (Scene scene : scenes.values()) {
            if (scene.configId() == configId) {
                players += scene.playerCount();
            }
        }
        metrics.scenePlayers(configId, players);
    }

    /**
     * 承载中 / 排空中的<b>主世界频道</b>数推给指标（建、销毁、状态变化之后）。镜像 / 副本实例不是频道，不计入（批次 5.3 R4；实例另有指标）。
     */
    private void publishChannels() {
        int active = 0;
        int draining = 0;
        for (Scene scene : scenes.values()) {
            if (!scene.isWorldChannel()) {
                continue;
            }
            if (scene.draining()) {
                draining++;
            } else {
                active++;
            }
        }
        metrics.channels(active, draining);
    }

    /**
     * 节点目录快照（scene-manager 按它分配场景；排空中的带 {@code draining}，分配不再选它，D13）。批次 5.3 起每条带种类与镜像源
     * （dungeon-mirror-spec §6.5）：scene-manager 按地图选频道只认 WORLD（R1），「谁是镜像源」从 MIRROR 条目的 {@code source_scene_id} 推导；
     * 目录是实例的唯一登记（D1）。
     */
    public List<SceneEntry> sceneEntries() {
        List<SceneEntry> entries = new ArrayList<>(scenes.size());
        for (Scene scene : scenes.values()) {
            entries.add(SceneEntry.newBuilder()
                    .setSceneId(scene.sceneId())
                    .setSceneConfigId(scene.configId())
                    .setPlayerCount(scene.playerCount())
                    .setDraining(scene.draining())
                    .setKind(scene.kind().channelKind())
                    .setSourceSceneId(scene.sourceSceneId())
                    .build());
        }
        return entries;
    }

    /** 已应用的频道计划版本（节点目录的 {@code applied_plan_version}，与 {@link #sceneEntries()} 在同一个逻辑任务里取）。 */
    public long appliedPlanVersion() {
        return appliedPlanVersion;
    }

    // ------------------------------------------------------------------ 主世界频道：按计划建 / 排空 / 销毁

    /**
     * 应用频道计划里属于本节点的记录（scene-channels-spec §4.10.2；对应基线 C++ HandleCreateScene / HandleDestroyScene，
     * cpp/nodes/scene/handler/grpc/scene_node_service.cpp:19-125——基线由 scene_manager 发 RPC，Java 由节点按计划自己收敛，D3）：
     * <ul>
     *   <li>版本号与已应用的相同 → 什么也不做（拉取者重投同一版本；领导者每次写入的版本号以 Redis TIME 托底，丢写后重写也不会撞上
     *       已应用的号，见 {@code WorldPlanBatch}）。版本号<b>回退</b>（Redis 被清空、主从切换丢了写、领导者还没重写）→ ERROR 后照样应用：
     *       计划是唯一权威（§4.1），拒绝它会让节点停在旧计划上、与领导者永远对不齐（单个拉取者串行应用，不会乱序）；</li>
     *   <li>ACTIVE 记录：本地没有 → 按计划的 scene_id 建（按 scene_id 幂等，同 scene_node_service.cpp:23-37）；本地有且在排空 → 改回承载中
     *       （缩容排空超时回滚，D10）。拒绝（ERROR + {@code rejected}，不建、不动本地）：scene_id 或 conf 为 0（补上基线 gRPC 入口缺的零号检查，
     *       B12；muduo 入口有，scene_handler.cpp:854-859）、conf 不是本节点 World 表里的图、种类不是主世界、本地同号异图；</li>
     *   <li>DRAINING 记录：本地有 → 标排空（孤儿图也照样排空，所以这里不查 World 表）；本地没有 → 什么也不做（不为排空记录建场景：
     *       节点重启后领导者看到 applied_plan_version 追上、场景缺席就删记录，§4.14）；</li>
     *   <li>本地有、计划里没有 → 按排空处理（节点侧孤儿：记录已删、频道换了节点号，或计划外自建的场景）。<b>只对主世界频道</b>：
     *       镜像 / 副本实例本来就不在计划里（批次 5.3 R2）；计划里出现与本地实例同号的记录一律拒绝、不动实例。</li>
     * </ul>
     * 频道从不跨节点「搬」：调用方只交来本节点号名下的记录，别的节点号名下的同号记录对本节点等于「计划里没有」（D4）。
     * 记下版本号，随即推进一次排空（{@link #drainStep}）。逻辑线程上调用。
     *
     * @param records 本节点号名下的记录（调用方按节点号过滤，{@code WorldPlan.channelsOn}）
     * @return 是否应用了（与已应用的版本相同时为 false）
     */
    public boolean applyChannelPlan(long version, List<WorldChannel> records) {
        if (version == appliedPlanVersion) {
            return false;
        }
        if (Long.compareUnsigned(version, appliedPlanVersion) < 0) {
            log.error("频道计划版本号回退 {} -> {}（Redis 被清空或主从切换丢了写？），按当前计划应用",
                    Long.toUnsignedString(appliedPlanVersion), Long.toUnsignedString(version));
        }
        Set<Integer> worldMaps = new HashSet<>(tables.worldSceneConfigIds());
        Set<Long> planned = new HashSet<>();
        int created = 0;
        int drained = 0;
        int reactivated = 0;
        int rejected = 0;
        for (WorldChannel record : records) {
            long sceneId = record.getSceneId();
            planned.add(sceneId);
            Scene local = scenes.get(sceneId);
            String problem = rejection(record, local, worldMaps);
            if (problem != null) {
                rejected++;
                metrics.channelPlanApply(ChannelPlanApply.REJECTED);
                log.error("拒绝频道计划记录（{}）version={} scene_id={} scene_config_id={} state={} kind={}", problem,
                        Long.toUnsignedString(version), Long.toUnsignedString(sceneId),
                        Integer.toUnsignedString(record.getSceneConfigId()), record.getState(), record.getKind());
                continue;
            }
            if (record.getState() == ChannelState.CHANNEL_ACTIVE) {
                if (local == null) {
                    addScene(SceneInfoComp.newBuilder()
                            .setSceneConfigId(record.getSceneConfigId())
                            .setSceneId(sceneId)
                            .build());
                    created++;
                } else if (local.draining()) {
                    local.setDraining(false);
                    reactivated++;
                    log.info("频道改回承载中 scene_id={} scene_config_id={}", Long.toUnsignedString(sceneId),
                            local.configId());
                }
            } else if (local != null && !local.draining()) {
                local.setDraining(true);
                drained++;
                log.info("频道转为排空 scene_id={} scene_config_id={} 原因={} 在场={}", Long.toUnsignedString(sceneId),
                        local.configId(), record.getDrainReason(), local.playerCount());
            }
        }
        for (Scene scene : scenes.values()) {
            // 「计划外即排空」只作用于主世界频道（批次 5.3 R2）：镜像 / 副本实例本来就不在计划里（节点自有，D1），
            // 不能一建出来就被当孤儿排空；它们的回收、级联与显式销毁由节点本地处理（dungeon-mirror-spec §6.10、§6.11）
            if (scene.isWorldChannel() && !planned.contains(scene.sceneId()) && !scene.draining()) {
                scene.setDraining(true);
                drained++;
                log.info("计划里没有本地场景，转为排空 scene_id={} scene_config_id={} 在场={}",
                        Long.toUnsignedString(scene.sceneId()), scene.configId(), scene.playerCount());
            }
        }
        appliedPlanVersion = version;
        metrics.channelPlanApply(ChannelPlanApply.APPLIED);
        publishChannels();
        log.info("应用频道计划 version={} 本节点记录={} 新建={} 转排空={} 改回承载={} 拒绝={}", Long.toUnsignedString(version),
                records.size(), created, drained, reactivated, rejected);
        drainStep();
        return true;
    }

    /** 一条计划记录为什么不能应用；能应用为 null。 */
    private static String rejection(WorldChannel record, Scene local, Set<Integer> worldMaps) {
        if (record.getSceneId() == 0) {
            return "scene_id 为 0";
        }
        if (local != null && !local.isWorldChannel()) {
            // 实例号与频道号同一个全服发号器发（Q8），撞号只可能是发号器坏了：两种状态都不动本地实例（fail-closed，批次 5.3）
            return "本地同号场景是实例（" + local.kind() + "，发号器撞号？）";
        }
        if (record.getState() == ChannelState.CHANNEL_DRAINING) {
            return null;
        }
        if (record.getState() != ChannelState.CHANNEL_ACTIVE) {
            return "未知状态";
        }
        if (record.getSceneConfigId() == 0) {
            return "scene_config_id 为 0";
        }
        // 计划里只有主世界频道；UNSPECIFIED 按主世界收（proto3 缺省值），5.3 的镜像 / 副本（只出现在节点目录）与不认识的种类
        // 不当主世界建（fail-closed，dungeon-mirror-spec §6.5）
        if (!ChannelKinds.isWorldChannel(record.getKind())) {
            return "不是主世界频道";
        }
        if (!worldMaps.contains(record.getSceneConfigId())) {
            return "不是本节点 World 表里的图（两边配表不一致？）";
        }
        if (local != null && local.configId() != record.getSceneConfigId()) {
            return "本地同号场景的图不同（本地 " + local.configId() + "）";
        }
        return null;
    }

    /**
     * 推进一次排空（scene-channels-spec §4.10.3）：排空中的场景里每个在场玩家按「本节点同图承载中频道里人数最少的（并列取 scene_id 无符号小的）
     * → 本节点默认大世界（World 第一行）承载中频道里人数最少的 → 原地不动（计 blocked，下次再试）」改派，经 {@link #switchScene}：
     * 旁人收到 51，本人收到 79（新 scene_id）/ 21 / 47，同图保留坐标、换图落出生点，写位置记录，触发组队跟随（跟随不会跟进排空频道，§4.13）。
     * <b>不存盘、不动归属</b>：玩家留在同一进程、owner_epoch 不变，所以省掉基线的「存盘 → handoff 标记 → EnterScene(0,0)」
     * （player_lifecycle.cpp:2140-2363）；基线落默认大世界（B1），Java 同图优先（D8）。
     * 人数为 0 且没有指向它的在途进场 → 销毁（基线 DestroyScene「先排空再销毁」，scene_node_service.cpp:91-121）。
     * 每次应用计划后与每秒各调一次（逻辑线程）。跨节点换图冻结中（FREEZING）的玩家跳过、等下一次推进（计 {@code switching}，
     * scene-handoff-spec §5.5；选目标中的 RESOLVING 不冻结，照常改派）；回合制战斗在途的玩家同样跳过（计 {@code in_battle}，scene-battle-spec D26）。
     *
     * <p>批次 5.3：回收宽限中（{@link DrainCause#IDLE}）的实例不改派（宽限中不会有人），宽限满且仍空、没有在途进场才销毁；主世界频道在这里被销毁时，
     * 以它为源的镜像转级联排空（{@link #destroyScene}），并在<b>同一次推进</b>里接着改派、销毁（dungeon-mirror-spec §6.11）。
     *
     * @return 本次销毁的场景数
     */
    public int drainStep() {
        Deque<Scene> work = new ArrayDeque<>();
        for (Scene scene : scenes.values()) {
            if (scene.draining()) {
                work.add(scene);
            }
        }
        long now = clock.nanoTime();
        long grace = instances.reclaimGrace().toNanos();
        int destroyed = 0;
        while (!work.isEmpty()) {
            Scene from = work.poll();
            if (scenes.get(from.sceneId()) != from || !from.draining()) {
                continue;
            }
            if (from.drainCause() == DrainCause.IDLE) {
                if (from.playerCount() == 0 && !hasPendingEnter(from.sceneId())
                        && now - from.drainingSinceNanos() >= grace) {
                    destroyScene(from, work);
                    destroyed++;
                }
                continue;
            }
            relocateResidents(from);
            if (from.playerCount() == 0 && !hasPendingEnter(from.sceneId())) {
                destroyScene(from, work);
                destroyed++;
            }
        }
        return destroyed;
    }

    private void relocateResidents(Scene from) {
        if (from.playerCount() == 0) {
            return;
        }
        List<ScenePlayer> movable = new ArrayList<>(from.playerCount());
        for (ScenePlayer player : from.players()) {
            if (player.frozen()) {
                // 跨节点换图冻结中（scene-handoff-spec §5.5）：冻结快照与内存必须一致，这次不改派；交出结局出来后实例离开或解冻，下一次推进再看
                metrics.channelRelocation(ChannelRelocation.SWITCHING);
            } else if (player.inBattle()) {
                // 回合制战斗在途（scene-battle-spec §7.13 世界内部第 2 条，D26）：留在原频道直到结算解冻，下一次推进再看
                metrics.channelRelocation(ChannelRelocation.IN_BATTLE);
            } else {
                movable.add(player);
            }
        }
        for (int i = 0; i < movable.size(); i++) {
            ScenePlayer player = movable.get(i);
            if (player.scene() != from) {
                continue;
            }
            Scene to = relocationTarget(from);
            if (to == null) {
                // 去向只取决于本节点的频道，与玩家无关：一个找不到，剩下的也找不到
                for (int j = i; j < movable.size(); j++) {
                    metrics.channelRelocation(ChannelRelocation.BLOCKED);
                }
                if (from.firstRelocationBlocked()) {
                    log.warn("排空频道找不到改派目标（本节点没有同图与默认大世界的承载中频道），原地不动、每秒重试 scene_id={} "
                            + "scene_config_id={} 在场={}", Long.toUnsignedString(from.sceneId()), from.configId(),
                            from.playerCount());
                }
                return;
            }
            from.relocationUnblocked();
            ChannelRelocation kind = to.configId() == from.configId() ? ChannelRelocation.SAME_MAP
                    : ChannelRelocation.DEFAULT_WORLD;
            metrics.channelRelocation(kind);
            log.info("排空改派 player={} {} -> {}（{}）", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(from.sceneId()), Long.toUnsignedString(to.sceneId()), kind);
            switchScene(player, to);
        }
    }

    /** 排空改派 / 进场重定向的去向（§4.10.3 的顺序）：本节点同图承载中频道 → 本节点默认大世界承载中频道 → 没有（null）。 */
    private Scene relocationTarget(Scene from) {
        Scene sameMap = leastLoadedActive(from.configId(), from);
        if (sameMap != null) {
            return sameMap;
        }
        List<Integer> maps = tables.worldSceneConfigIds();
        if (maps.isEmpty()) {
            return null;
        }
        int defaultWorld = maps.get(0);
        return defaultWorld == from.configId() ? null : leastLoadedActive(defaultWorld, null);
    }

    /**
     * 本节点某图承载中<b>主世界频道</b>里人数最少的（并列取 scene_id 无符号小的，确定、不依赖插入序）；{@code exclude} 不参与。没有为 null。
     * 镜像 / 副本实例不当频道（批次 5.3 R3）：镜像与源同图，不过滤就会把排空改派、进场重定向、只带地图的 63 分进别人的私有镜像。
     */
    private Scene leastLoadedActive(int configId, Scene exclude) {
        Scene best = null;
        for (Scene scene : scenes.values()) {
            if (scene == exclude || !scene.isWorldChannel() || scene.draining() || scene.configId() != configId) {
                continue;
            }
            if (best == null || scene.playerCount() < best.playerCount() || scene.playerCount() == best.playerCount()
                    && Long.compareUnsigned(scene.sceneId(), best.sceneId()) < 0) {
                best = scene;
            }
        }
        return best;
    }

    private boolean hasPendingEnter(long sceneId) {
        for (PendingEnter pending : pendingEnters.values()) {
            if (pending.sceneId() == sceneId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 销毁一个已空、没有在途进场的场景（调用方保证）。实例计 {@code destroyed_*}（按排空原因）；主世界频道被销毁时，本节点以它为源、
     * 还没在级联 / 显式销毁中的镜像（含回收宽限中的：源已不在，不再给它复活的机会）转级联排空并放进 {@code cascaded}，由调用方接着推进
     * （dungeon-mirror-spec §6.11；对应基线 instance_lifecycle.go:276-297 与 world_autoscale.go:437-457 的强制级联，Java 在节点本地做、
     * 居民同图改派，D11）。销毁之后立即补发目录。
     */
    private void destroyScene(Scene scene, Collection<Scene> cascaded) {
        scenes.remove(scene.sceneId());
        scene.clear();
        publishPopulation(scene.configId());
        publishChannels();
        if (scene.kind().isInstance()) {
            InstanceEvent event = switch (scene.drainCause()) {
                case IDLE -> InstanceEvent.DESTROYED_IDLE;
                case CASCADE -> InstanceEvent.DESTROYED_CASCADE;
                case ADMIN -> InstanceEvent.DESTROYED_ADMIN;
                case NONE, PLAN -> null;
            };
            if (event == null) {
                log.error("实例以意外的排空原因被销毁（实例不在频道计划里，不该是 {}） scene_id={}", scene.drainCause(),
                        Long.toUnsignedString(scene.sceneId()));
            } else {
                metrics.instanceLifecycle(instanceKind(scene.kind()), event);
            }
            publishInstances();
            log.info("销毁实例 scene_id={} scene_config_id={} kind={} 原因={}", Long.toUnsignedString(scene.sceneId()),
                    scene.configId(), scene.kind(), scene.drainCause());
        } else {
            log.info("销毁场景 scene_id={} scene_config_id={}", Long.toUnsignedString(scene.sceneId()), scene.configId());
            long now = clock.nanoTime();
            boolean any = false;
            for (Scene mirror : scenes.values()) {
                if (mirror.kind() == SceneKind.MIRROR && mirror.sourceSceneId() == scene.sceneId() && !cascading(mirror)) {
                    startCascade(mirror, now, "源频道已销毁");
                    cascaded.add(mirror);
                    any = true;
                }
            }
            if (any) {
                publishInstances();
            }
        }
        instances.directoryChanged().run();
    }

    /** 已在级联或显式销毁中（这两种不复活、也不再改原因）。 */
    private static boolean cascading(Scene scene) {
        return scene.draining() && (scene.drainCause() == DrainCause.CASCADE || scene.drainCause() == DrainCause.ADMIN);
    }

    private void startCascade(Scene mirror, long now, String reason) {
        DrainCause before = mirror.drainCause();
        mirror.beginDrain(DrainCause.CASCADE, now);
        metrics.instanceLifecycle(InstanceKind.MIRROR, InstanceEvent.CASCADE_STARTED);
        log.info("镜像级联排空（{}） scene_id={} source={} 在场={} 原状态={}", reason, Long.toUnsignedString(mirror.sceneId()),
                Long.toUnsignedString(mirror.sourceSceneId()), mirror.playerCount(), before);
    }

    // ------------------------------------------------------------------ 镜像 / 副本实例（批次 5.3，dungeon-mirror-spec §6.7–§6.11）

    /**
     * 每秒一次的场景维护（逻辑线程；替代单纯的排空推进，§6.10、§6.11）：对每个实例先做级联兜底（镜像的源不在本地或不是主世界频道 → 级联），
     * 再做空闲回收判定（空置满超时 → 回收宽限；宽限中发现有人 → 复活，纵深防御），最后 {@link #drainStep} 推进排空并销毁。
     * 实例的状态有变就立即补发目录。
     *
     * @return 本次销毁的场景数
     */
    public int maintainScenes() {
        long now = clock.nanoTime();
        Set<Long> pendingTargets = pendingEnterTargets();
        boolean changed = false;
        for (Scene scene : scenes.values()) {
            if (scene.isWorldChannel()) {
                continue;
            }
            if (cascadeIfSourceGone(scene, now)) {
                changed = true;
            } else if (checkIdle(scene, pendingTargets, now)) {
                changed = true;
            }
        }
        if (changed) {
            publishInstances();
            instances.directoryChanged().run();
        }
        return drainStep();
    }

    /** 级联兜底（§6.11「兜底」）：镜像的源已不在本地或不是主世界频道 → 转级联。已在级联 / 显式销毁中的不动。 */
    private boolean cascadeIfSourceGone(Scene scene, long now) {
        if (scene.kind() != SceneKind.MIRROR || cascading(scene)) {
            return false;
        }
        Scene source = scenes.get(scene.sourceSceneId());
        if (source != null && source.isWorldChannel()) {
            return false;
        }
        startCascade(scene, now, "兜底检查：源频道不在本节点");
        return true;
    }

    /**
     * 空闲回收判定（§6.10 第 1–2 条）：有人或有指向它的在途进场 → 清空置起点；否则没有起点就记 now，满超时（按种类，0 = 不回收）就进入回收宽限。
     * 回收宽限中发现有人（本地进入一律不放行、在途进场到达即复活，正常不会出现）→ 当复活处理。
     *
     * @return 状态是否变了
     */
    private boolean checkIdle(Scene scene, Set<Long> pendingTargets, long now) {
        if (scene.draining()) {
            if (scene.drainCause() == DrainCause.IDLE && scene.playerCount() > 0) {
                log.error("回收宽限中的实例里有人（漏了「宽限中不接本地进入」的闸？），按复活处理 scene_id={} 在场={}",
                        Long.toUnsignedString(scene.sceneId()), scene.playerCount());
                revive(scene, "宽限中发现有人");
                return true;
            }
            return false;
        }
        if (scene.playerCount() > 0 || pendingTargets.contains(scene.sceneId())) {
            scene.markOccupied();
            return false;
        }
        if (!scene.emptySinceKnown()) {
            scene.markEmptySince(now);
            return false;
        }
        long timeout = instances.idleTimeoutNanos(scene.kind());
        if (timeout <= 0 || now - scene.emptySinceNanos() < timeout) {
            return false;
        }
        scene.beginDrain(DrainCause.IDLE, now);
        metrics.instanceLifecycle(instanceKind(scene.kind()), InstanceEvent.RECLAIM_STARTED);
        log.info("实例空置满 {} 秒，进入回收宽限 scene_id={} kind={} 宽限={}", TimeUnit.NANOSECONDS.toSeconds(timeout),
                Long.toUnsignedString(scene.sceneId()), scene.kind(), instances.reclaimGrace());
        return true;
    }

    /** 回收宽限中复活（§6.10 第 3 条）：改回承载中、清空置起点；调用方负责补发目录与实例指标（在途进场到达时由 {@link #onPlayerLoaded} 一并做）。 */
    private void revive(Scene scene, String why) {
        scene.stopDraining();
        scene.markOccupied();
        metrics.instanceLifecycle(instanceKind(scene.kind()), InstanceEvent.REVIVED);
        log.info("回收宽限中的实例复活（{}） scene_id={} kind={}", why, Long.toUnsignedString(scene.sceneId()), scene.kind());
    }

    /** 加载中的进场指向的场景号（每秒维护一次取一份，不在每个实例上扫一遍）。 */
    private Set<Long> pendingEnterTargets() {
        if (pendingEnters.isEmpty()) {
            return Set.of();
        }
        Set<Long> targets = new HashSet<>();
        for (PendingEnter pending : pendingEnters.values()) {
            targets.add(pending.sceneId());
        }
        return targets;
    }

    /** 人数变化之后更新实例的空置起点（变空记 now、有人清掉；在途进场由每秒维护补判，精度 1 s 以内）。 */
    private void noteOccupancy(Scene scene) {
        if (scene.isWorldChannel()) {
            return;
        }
        if (scene.playerCount() > 0) {
            scene.markOccupied();
        } else if (!scene.emptySinceKnown()) {
            scene.markEmptySince(clock.nanoTime());
        }
    }

    /**
     * 建一个实例（§6.8；逻辑线程）——<b>建实例的唯一入口</b>：63 镜像分支拿到号之后、dev 管理口建副本都经这里。拒绝（不建，ERROR，计
     * {@code lifecycle{rejected}}）：scene_id 或 conf 为 0、本地已有同号（号全服唯一，同号就是发号器坏了）、本节点停止接客、实例数达上限；
     * 镜像：源不在本地 / 不是主世界频道 / 在排空、地图与源不同；副本：地图与 Dungeon 表不符。建好后空置起点 = now（创建者在同一个任务里换入后清掉），
     * 计 {@code created}、更新实例指标、立即补发目录。
     *
     * @return 建好的实例；被拒为 null
     */
    public Scene createInstance(InstanceSpec spec) {
        String problem = instanceRejection(spec);
        InstanceKind kind = instanceKind(spec.kind());
        if (problem != null) {
            metrics.instanceLifecycle(kind, InstanceEvent.REJECTED);
            log.error("拒绝建实例（{}） scene_id={} kind={} scene_config_id={} source={} mirror={} dungeon={}", problem,
                    Long.toUnsignedString(spec.sceneId()), spec.kind(), spec.sceneConfigId(),
                    Long.toUnsignedString(spec.sourceSceneId()), Integer.toUnsignedString(spec.mirrorConfigId()),
                    Integer.toUnsignedString(spec.dungeonConfigId()));
            return null;
        }
        Scene scene = addScene(spec.toInfo(), spec.kind(), spec.sourceSceneId());
        scene.markEmptySince(clock.nanoTime());
        metrics.instanceLifecycle(kind, InstanceEvent.CREATED);
        publishInstances();
        instances.directoryChanged().run();
        return scene;
    }

    private String instanceRejection(InstanceSpec spec) {
        if (spec.sceneId() == 0 || spec.sceneConfigId() == 0) {
            return "scene_id 或 scene_config_id 为 0";
        }
        if (scenes.containsKey(spec.sceneId())) {
            return "scene_id 本地已存在（发号器撞号？）";
        }
        if (!acceptingEnters) {
            return "本节点已停止接客";
        }
        if (instanceCount() >= instances.maxPerNode()) {
            return "本节点实例数已达上限 " + instances.maxPerNode();
        }
        if (spec.kind() == SceneKind.MIRROR) {
            Scene source = scenes.get(spec.sourceSceneId());
            if (source == null) {
                return "源频道不在本节点";
            }
            if (!source.isWorldChannel()) {
                return "源不是主世界频道（" + source.kind() + "）";
            }
            if (source.draining()) {
                return "源频道在排空";
            }
            if (source.configId() != spec.sceneConfigId()) {
                return "镜像的地图与源频道不同（源 " + source.configId() + "）";
            }
            return null;
        }
        OptionalInt map = tables.dungeonSceneConfigId(spec.dungeonConfigId());
        if (map.isEmpty() || map.getAsInt() != spec.sceneConfigId()) {
            return "副本地图与 Dungeon 表不符（表里 " + (map.isEmpty() ? "没有这一行" : map.getAsInt()) + "）";
        }
        return null;
    }

    /** 本节点的实例数（镜像 + 副本，含回收宽限 / 排空中的；每节点上限 D19 按它算）。 */
    int instanceCount() {
        int count = 0;
        for (Scene scene : scenes.values()) {
            if (scene.kind().isInstance()) {
                count++;
            }
        }
        return count;
    }

    /** 本节点上 {@code creators} 含这个玩家的实例数（每创建者上限 D19 按它算）。 */
    int instancesCreatedBy(long playerId) {
        int count = 0;
        for (Scene scene : scenes.values()) {
            if (scene.kind().isInstance() && scene.createdBy(playerId)) {
                count++;
            }
        }
        return count;
    }

    /** 各种实例按状态的数量推给指标（建、销毁、状态变化之后）。 */
    private void publishInstances() {
        int[][] counts = new int[InstanceKind.values().length][3];
        for (Scene scene : scenes.values()) {
            if (!scene.kind().isInstance()) {
                continue;
            }
            int state = !scene.draining() ? 0 : scene.drainCause() == DrainCause.IDLE ? 1 : 2;
            counts[instanceKind(scene.kind()).ordinal()][state]++;
        }
        for (InstanceKind kind : InstanceKind.values()) {
            int[] c = counts[kind.ordinal()];
            metrics.instances(kind, c[0], c[1], c[2]);
        }
    }

    private static InstanceKind instanceKind(SceneKind kind) {
        return kind == SceneKind.DUNGEON ? InstanceKind.DUNGEON : InstanceKind.MIRROR;
    }

    /**
     * 63 镜像分支的同步校验（§6.7「镜像分支同步校验」；排在 3014、全 0 之后，3008 之前）：返回 0 = 受理（调用方回 {@code {0}} 后调
     * {@link #beginMirrorCreate}），否则 3005——当前场景不是主世界频道（镜像的镜像、副本作源，Q7）、{@code mirror_config_id} 不在 Mirror 表（D7）、
     * 当前频道在排空、本节点停止接客（D8）、本节点实例数或本人创建的实例数达上限（D19）。每条镜像请求恰好计一次 {@code mirror.requests}。
     */
    int checkMirrorRequest(ScenePlayer player, int mirrorConfigId) {
        MirrorRequest result = mirrorRequestResult(player, mirrorConfigId);
        metrics.mirrorRequest(result);
        if (result == MirrorRequest.ACCEPTED) {
            return 0;
        }
        log.info("拒绝建镜像（{}） player={} scene_id={} mirror_config_id={}", result,
                Long.toUnsignedString(player.playerId()), Long.toUnsignedString(player.scene().sceneId()),
                Integer.toUnsignedString(mirrorConfigId));
        return ENTER_PARAM_ERROR;
    }

    private MirrorRequest mirrorRequestResult(ScenePlayer player, int mirrorConfigId) {
        Scene current = player.scene();
        if (!current.isWorldChannel()) {
            return MirrorRequest.BAD_SOURCE;
        }
        if (!tables.mirrorExists(mirrorConfigId)) {
            return MirrorRequest.BAD_MIRROR_CONFIG;
        }
        if (current.draining()) {
            return MirrorRequest.SOURCE_DRAINING;
        }
        if (!acceptingEnters) {
            return MirrorRequest.NOT_ACCEPTING;
        }
        if (instanceCount() >= instances.maxPerNode()) {
            return MirrorRequest.NODE_CAP;
        }
        if (instancesCreatedBy(player.playerId()) >= instances.maxPerCreator()) {
            return MirrorRequest.CREATOR_CAP;
        }
        return MirrorRequest.ACCEPTED;
    }

    /**
     * 63 镜像分支已受理（应答 {@code {0}} 已回）：进 RESOLVING（{@link PlayerSwitch#mirrorCreate}，复用 5.2 的在途槽与本地兜底超时——
     * 期间再发 63 回 3014，D4），向 scene-manager 取一个全服 scene_id。不冻结：离场 / 断链 / 接管 / 失去归属照现有逻辑处理，迟到的结果按引用比对丢弃
     * （号作废，任何地方都不留幽灵镜像，D3）。没装配取号（{@link SceneInstances#DISABLED}）= 没有 scene-manager 可用：推 23 {1003}（D9）。
     */
    void beginMirrorCreate(ScenePlayer player, int mirrorConfigId) {
        Scene source = player.scene();
        long token = ++switchTokens;
        PlayerSwitch sw = PlayerSwitch.mirrorCreate(token, source.sceneId(), mirrorConfigId,
                clock.nanoTime() + instances.resolveTimeout().toNanos() + RESOLVE_SLOT_GRACE_NANOS);
        player.setSwitching(sw);
        log.info("建镜像：请 scene-manager 发实例号 player={} token={} 源 scene_id={} scene_config_id={} mirror_config_id={}",
                Long.toUnsignedString(player.playerId()), token, Long.toUnsignedString(source.sceneId()),
                source.configId(), Integer.toUnsignedString(mirrorConfigId));
        if (!instances.enabled()) {
            onMirrorIssued(player, sw, new InstanceIds.Result.Failed("实例取号没装配"));
            return;
        }
        instances.ids().create(new InstanceIds.Request(player.playerId(), SceneKind.MIRROR, source.sceneId(),
                source.configId(), mirrorConfigId, 0), result -> onMirrorIssued(player, sw, result));
    }

    /** 镜像取号的结果（逻辑线程，§6.7「结果回到逻辑线程」）：先核对实例与在途槽，再按结果分派。 */
    private void onMirrorIssued(ScenePlayer player, PlayerSwitch sw, InstanceIds.Result result) {
        if (playersById.get(player.playerId()) != player || player.switching() != sw) {
            metrics.mirrorResolve(MirrorResolve.STALE);
            log.info("镜像取号结果回来时实例已离开 / 已重新进场 / 在途已作废，丢弃（号作废） player={} token={} 结果={}",
                    Long.toUnsignedString(player.playerId()), sw.token(), result);
            return;
        }
        player.setSwitching(null);
        switch (result) {
            case InstanceIds.Result.Failed failed -> {
                metrics.mirrorResolve(MirrorResolve.ERROR);
                log.warn("镜像取号调用失败，留在原地 player={} token={}: {}", Long.toUnsignedString(player.playerId()),
                        sw.token(), failed.reason());
                pushTip(player, SERVICE_UNAVAILABLE);
            }
            case InstanceIds.Result.Refused refused -> {
                metrics.mirrorResolve(MirrorResolve.REJECTED);
                log.info("scene-manager 拒绝发镜像号，留在原地 player={} token={} tip={}",
                        Long.toUnsignedString(player.playerId()), sw.token(), refused.tipId());
                pushTip(player, ENTER_FAILED);
            }
            case InstanceIds.Result.Issued issued -> onMirrorIdIssued(player, sw, issued);
        }
    }

    private void onMirrorIdIssued(ScenePlayer player, PlayerSwitch sw, InstanceIds.Result.Issued issued) {
        long sourceSceneId = sw.wantSceneId();
        int mirrorConfigId = sw.wantConfigId();
        if (issued.sceneNodeId() != instances.localNodeId()) {
            // 5.3 放置恒为发起节点（D2）；跨节点放置（Q2）接上时这里改为 SceneTransfers.begin(Reason.MIRROR)，绝不当成本地实例（R11）
            metrics.mirrorResolve(MirrorResolve.WRONG_NODE);
            log.error("scene-manager 把镜像放到了别的节点（5.3 不支持跨节点放置），不建 player={} node={} scene_id={}",
                    Long.toUnsignedString(player.playerId()), issued.sceneNodeId(), Long.toUnsignedString(issued.sceneId()));
            pushTip(player, ENTER_FAILED);
            return;
        }
        String moved = mirrorSourceMoved(player, sourceSceneId);
        if (moved != null) {
            metrics.mirrorResolve(MirrorResolve.SOURCE_MOVED);
            log.info("镜像号回来时条件已不满足（{}），不建 player={} 源 scene_id={} 号={}", moved,
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(sourceSceneId),
                    Long.toUnsignedString(issued.sceneId()));
            pushTip(player, ENTER_FAILED);
            return;
        }
        Scene source = player.scene();
        Scene mirror = createInstance(InstanceSpec.mirror(issued.sceneId(), source.configId(), sourceSceneId,
                mirrorConfigId, player.playerId()));
        if (mirror == null) {
            // 本地重号（发号器失效的迹象）等：createInstance 已记 ERROR
            metrics.mirrorResolve(MirrorResolve.CREATE_REJECTED);
            pushTip(player, ENTER_FAILED);
            return;
        }
        metrics.mirrorResolve(MirrorResolve.CREATED);
        log.info("建镜像成功，换入 player={} 源 scene_id={} 镜像 scene_id={}", Long.toUnsignedString(player.playerId()),
                Long.toUnsignedString(sourceSceneId), Long.toUnsignedString(mirror.sceneId()));
        switchScene(player, mirror);
    }

    /** 号回来时复核（D6）：玩家仍在源频道、源仍是承载中的主世界频道、节点仍接客、没到上限；不满足的原因，满足为 null。 */
    private String mirrorSourceMoved(ScenePlayer player, long sourceSceneId) {
        Scene current = player.scene();
        if (current.sceneId() != sourceSceneId) {
            return "玩家已不在源场景（被排空改派走了？）";
        }
        if (!current.isWorldChannel() || current.draining()) {
            return "源频道在排空";
        }
        if (!acceptingEnters) {
            return "本节点已停止接客";
        }
        if (instanceCount() >= instances.maxPerNode()) {
            return "本节点实例数已达上限";
        }
        if (instancesCreatedBy(player.playerId()) >= instances.maxPerCreator()) {
            return "本人创建的实例数已达上限";
        }
        return null;
    }

    /**
     * dev 管理口建副本（§6.13；逻辑线程）：Dungeon 表没有这一行（或为 0）→ 3005；取号调用失败 / 超时 / 没装配 → 1003；scene-manager 拒绝、
     * 号落在别的节点或本地拒建 → 3023；成功 → {@code {0, scene_id, scene_config_id = Dungeon.scene_id, scene_node_id = 本节点}}。
     * 结果经 {@code result} 交回（在逻辑线程上完成；取号在途时不在本调用栈内）。
     *
     * <p>调用方放弃（HTTP 线程等结果超时 / 被中断时 {@code cancel} 了 {@code result}）之后不留没人知道号的副本（D3「不留幽灵」）：开始前已放弃 → 不取号；
     * 号回来时已放弃 → 不建（号作废）；建好之后交不回去（放弃恰好落在建与交之间，{@code complete} 返回 false）→ 当场显式销毁（副本还是空的，
     * 同一个任务里就销毁掉，计 {@code destroyed_admin}）。{@code complete} 与 {@code cancel} 是原子的，两边恰好一边赢：要么操作者拿到号，要么副本不在。
     */
    public void createDungeon(int dungeonConfigId, CompletableFuture<CreateDungeonInstanceResponse> result) {
        if (result.isDone()) {
            log.info("管理口建副本：调用方已放弃（等结果超时），不再取号 dungeon_config_id={}",
                    Integer.toUnsignedString(dungeonConfigId));
            return;
        }
        OptionalInt map = tables.dungeonSceneConfigId(dungeonConfigId);
        if (map.isEmpty()) {
            log.info("管理口建副本：Dungeon 表没有这一行 dungeon_config_id={}", Integer.toUnsignedString(dungeonConfigId));
            result.complete(dungeonResult(ENTER_PARAM_ERROR));
            return;
        }
        int sceneConfigId = map.getAsInt();
        if (!instances.enabled()) {
            result.complete(dungeonResult(SERVICE_UNAVAILABLE));
            return;
        }
        instances.ids().create(new InstanceIds.Request(0, SceneKind.DUNGEON, 0, sceneConfigId, 0, dungeonConfigId),
                issued -> onDungeonIssued(dungeonConfigId, sceneConfigId, issued, result));
    }

    private void onDungeonIssued(int dungeonConfigId, int sceneConfigId, InstanceIds.Result issuedResult,
                                 CompletableFuture<CreateDungeonInstanceResponse> result) {
        switch (issuedResult) {
            case InstanceIds.Result.Failed failed -> {
                log.warn("管理口建副本：取号调用失败 dungeon_config_id={}: {}", dungeonConfigId, failed.reason());
                result.complete(dungeonResult(SERVICE_UNAVAILABLE));
            }
            case InstanceIds.Result.Refused refused -> {
                log.info("管理口建副本：scene-manager 拒绝发号 dungeon_config_id={} tip={}", dungeonConfigId, refused.tipId());
                result.complete(dungeonResult(ENTER_FAILED));
            }
            case InstanceIds.Result.Issued issued -> {
                if (issued.sceneNodeId() != instances.localNodeId()) {
                    log.error("scene-manager 把副本放到了别的节点（5.3 不支持跨节点放置），不建 node={} scene_id={}",
                            issued.sceneNodeId(), Long.toUnsignedString(issued.sceneId()));
                    result.complete(dungeonResult(ENTER_FAILED));
                    return;
                }
                if (result.isDone()) {
                    log.warn("管理口建副本：号回来时调用方已放弃（等结果超时），不建，号作废 scene_id={} dungeon_config_id={}",
                            Long.toUnsignedString(issued.sceneId()), dungeonConfigId);
                    return;
                }
                Scene dungeon = createInstance(InstanceSpec.dungeon(issued.sceneId(), sceneConfigId, dungeonConfigId));
                if (dungeon == null) {
                    result.complete(dungeonResult(ENTER_FAILED));
                    return;
                }
                log.info("管理口建副本 scene_id={} scene_config_id={} dungeon_config_id={}",
                        Long.toUnsignedString(dungeon.sceneId()), sceneConfigId, dungeonConfigId);
                boolean delivered = result.complete(CreateDungeonInstanceResponse.newBuilder()
                        .setSceneId(dungeon.sceneId())
                        .setSceneConfigId(sceneConfigId)
                        .setSceneNodeId(instances.localNodeId())
                        .build());
                if (!delivered) {
                    log.warn("管理口建副本：刚建好调用方就放弃了（等结果超时），当场销毁，不留没人知道号的副本 scene_id={}",
                            Long.toUnsignedString(dungeon.sceneId()));
                    destroyInstance(dungeon.sceneId());
                }
            }
        }
    }

    private static CreateDungeonInstanceResponse dungeonResult(int tipId) {
        return CreateDungeonInstanceResponse.newBuilder().setTipId(tipId).build();
    }

    /**
     * dev 管理口显式销毁一个实例（§6.11、D17；逻辑线程）：scene_id 为 0 或是主世界频道（只能由频道计划销毁，修基线 B10）→ 3005；
     * 不在本节点 → 3000；已在级联 / 显式销毁中 → 0（幂等）；否则（含回收宽限中的：不再给它复活的机会）转排空（ADMIN），当场推进一次——
     * 居民按改派规则移走（镜像 → 同图主世界频道、坐标保留；副本 → 默认主世界出生点），空了即销毁、计 {@code destroyed_admin}。
     *
     * @return tip（0 = 已受理）
     */
    public int destroyInstance(long sceneId) {
        if (sceneId == 0) {
            return ENTER_PARAM_ERROR;
        }
        Scene scene = scenes.get(sceneId);
        if (scene == null) {
            return SCENE_NOT_FOUND;
        }
        if (scene.isWorldChannel()) {
            return ENTER_PARAM_ERROR;
        }
        if (cascading(scene)) {
            return 0;
        }
        scene.beginDrain(DrainCause.ADMIN, clock.nanoTime());
        log.info("管理口显式销毁实例 scene_id={} kind={} 在场={}", Long.toUnsignedString(sceneId), scene.kind(),
                scene.playerCount());
        publishInstances();
        instances.directoryChanged().run();
        drainStep();
        return 0;
    }

    public int playerCount() {
        return playersById.size();
    }

    /** 本节点内存里持有的全部归属（续约用的快照；每个玩家一项）。 */
    public List<OwnedPlayer> ownedPlayers() {
        List<OwnedPlayer> owned = new ArrayList<>(playersById.size());
        for (ScenePlayer player : playersById.values()) {
            owned.add(new OwnedPlayer(player.playerId(), player.ownerEpoch()));
        }
        return owned;
    }

    Scene sceneById(long sceneId) {
        return scenes.get(sceneId);
    }

    /**
     * 客户端换场景（63）的去向（scene-channels-spec §4.12，scene-handoff-spec §5.5、D6）：
     * <ul>
     *   <li>指定了 scene_id：本节点上的这个场景、且在承载中才放行（不看种类：按号加入镜像 / 副本实例，同基线不查 creators）；在排空中拒绝
     *       （D11，基线放行、B5）；不在本节点 → 远端（scene-manager 在全 zone 目录里找，不回落到按地图挑）；</li>
     *   <li>按地图选只在<b>主世界频道</b>里选（批次 5.3 R3）；当前场景是镜像 / 副本实例时只带当前地图也必须离开实例：本节点该图有主世界频道就去
     *       人数最少的，没有则按下面「只带别的地图」的规则；</li>
     *   <li>只带当前地图（基线 scene_manager 在全 zone 该图频道里预占最少者，enterscenelogic.go:1267-1286、world_init.go:437-513）：
     *       在本节点同图承载中频道里选人数最少的，当前场景的人数含自己；并列<b>优先留在原地</b>，再取 scene_id 小的（D17，基线并列取 SMEMBERS
     *       顺序第一个、B13）；当前场景在排空中就不算它，本节点再没有该图的承载中频道 → 远端。选回原场景 = 应答成功、不发 79
     *       （基线挑回原频道走同落点重连，同样不发）；</li>
     *   <li>只带别的地图：本节点该图承载中频道里人数最少的（并列取 scene_id 小的）；本节点没有时，是主世界图 → 远端（5.1 切 hash 覆盖后才常见；
     *       per-node 覆盖下每节点每图都有频道，Q4），不是 → 拒绝（scene-manager 同样不为非主世界图选频道，省一次往返）。</li>
     * </ul>
     * 只带地图时先在本节点选、本节点没有才跨节点（D6：少一次库写与客户端重新加载）。跨节点换图没装配（{@link CrossNodeSwitch#DISABLED}）时
     * 远端一律按 5.1 回 3023。
     */
    SwitchTarget resolveSwitchTarget(Scene current, long sceneId, int configId) {
        if (sceneId != 0) {
            Scene target = scenes.get(sceneId);
            if (target == null) {
                return remoteOrReject();
            }
            return target.draining() ? new SwitchTarget.Reject(ENTER_FAILED) : new SwitchTarget.Local(target);
        }
        if (configId == current.configId()) {
            Scene other = leastLoadedActive(configId, current);
            if (!current.isWorldChannel()) {
                // 身在镜像 / 副本里只带当前地图（批次 5.3 R3）：只在该图的主世界频道里选，不允许留在实例里（基线只在 world_channels 里选，
                // 镜像不在其中）；本地没有时同「只带别的地图」：世界地图（镜像）→ 远端，否则（副本地图 17–19）→ 拒绝
                if (other != null) {
                    return new SwitchTarget.Local(other);
                }
                return tables.worldSceneConfigIds().contains(configId) ? remoteOrReject()
                        : new SwitchTarget.Reject(ENTER_FAILED);
            }
            if (current.draining()) {
                return other == null ? remoteOrReject() : new SwitchTarget.Local(other);
            }
            return new SwitchTarget.Local(other != null && other.playerCount() < current.playerCount() ? other : current);
        }
        Scene best = leastLoadedActive(configId, null);
        if (best != null) {
            return new SwitchTarget.Local(best);
        }
        return tables.worldSceneConfigIds().contains(configId) ? remoteOrReject() : new SwitchTarget.Reject(ENTER_FAILED);
    }

    private SwitchTarget remoteOrReject() {
        return crossNode.enabled() ? new SwitchTarget.Remote() : new SwitchTarget.Reject(ENTER_FAILED);
    }

    // ------------------------------------------------------------------ 进场

    /**
     * gate 发来的进场（登录 / 重连 / 顶号，或跨节点换图的交出进场 {@code transfer = true}）。两者走同一套加载与归属校验
     * （库里 epoch 不是请求的 epoch 就 3023），交出进场只多三点（scene-handoff-spec §5.8）：不拍 LOGIN 快照（D8）、计
     * {@code xm.scene.transfer.enters}、进场成功后立即单独续约一次新 epoch。落位规则相同：交出事务写的是源场景的地图与坐标，
     * 所以同图保留坐标、换图落出生点（D10）。
     */
    public void onPlayerEnter(long linkId, PlayerEnter enter) {
        SessionKey key = new SessionKey(linkId, enter.getSessionId());
        long playerId = enter.getPlayerId();
        long epoch = enter.getOwnerEpoch();
        boolean transfer = enter.getTransfer();
        if (!acceptingEnters) {
            failEnter(key, playerId, epoch, transfer, "本节点已停止接收新玩家");
            return;
        }
        if (playerId == 0 || enter.getSessionId() == 0) {
            failEnter(key, playerId, epoch, transfer, "player_id 或 session_id 为 0");
            return;
        }
        // 排空中的场景照样放行：在途进场挡住它的销毁，加载完成时改进兄弟频道（§4.10.4）
        if (!scenes.containsKey(enter.getSceneId())) {
            failEnter(key, playerId, epoch, transfer,
                    "场景不在本节点 scene_id=" + Long.toUnsignedString(enter.getSceneId()));
            return;
        }
        PendingEnter pending = new PendingEnter(key, playerId, enter.getSceneId(), epoch, transfer);
        PendingEnter replaced = pendingEnters.put(key, pending);
        if (replaced != null) {
            log.warn("同一会话在加载完成前再次进场，只处理最新一次 player={} session={}", playerId, key);
            if (replaced.playerId() != playerId || replaced.ownerEpoch() != epoch) {
                // 被取代的那次进场不会再有写者：释放它夺得的归属（带围栏，已被更新的夺权取代时什么也不做）。
                repository.release(replaced.playerId(), replaced.ownerEpoch());
            }
        }
        repository.load(playerId, result -> onPlayerLoaded(pending, result));
    }

    private void onPlayerLoaded(PendingEnter pending, LoadResult result) {
        SessionKey key = pending.session();
        long playerId = pending.playerId();
        long epoch = pending.ownerEpoch();
        boolean transfer = pending.transfer();
        if (pendingEnters.get(key) != pending) {
            // 取消这次进场的一方（离开 / 断链 / 被取代 / 接管 / 停服）已经负责释放归属。
            log.info("进场加载返回时会话已离开或已被新的进场取代，丢弃 player={} session={}", playerId, key);
            return;
        }
        pendingEnters.remove(key);

        if (!(result instanceof LoadResult.Found found)) {
            if (result instanceof LoadResult.Failed failed) {
                log.error("加载玩家数据失败 player={}", playerId, failed.error());
                failEnter(key, playerId, epoch, transfer, "加载玩家数据失败");
            } else {
                failEnter(key, playerId, epoch, transfer, "玩家不存在");
            }
            return;
        }
        PlayerData data = found.data();
        if (!acceptingEnters) {
            failEnter(key, playerId, epoch, transfer, "本节点已停止接收新玩家");
            return;
        }
        if (data.ownerEpoch() != epoch) {
            failEnter(key, playerId, epoch, transfer,
                    "owner_epoch 不符（库里 " + data.ownerEpoch() + "，请求 " + epoch + "），进场请求已过期");
            return;
        }
        ScenePlayer previous = playersById.get(playerId);
        if (previous != null && previous.ownerEpoch() > epoch) {
            failEnter(key, playerId, epoch, transfer,
                    "本节点已有更新归属的实例（epoch " + previous.ownerEpoch() + "），进场请求已过期");
            return;
        }
        Scene scene = scenes.get(pending.sceneId());
        if (scene == null) {
            failEnter(key, playerId, epoch, transfer,
                    "场景不在本节点 scene_id=" + Long.toUnsignedString(pending.sceneId()));
            return;
        }
        // 回收宽限中的实例（批次 5.3 §6.10 第 3 条）：在途进场（登录重连回原实例、5.2 显式加入）在宽限内到达 → 复活，照常进入。
        // 宽限的意义就是「scene-manager 分配时还在 → 到达时还在」；在途期间 hasPendingEnter 挡住了销毁。复活推迟到玩家状态初始化成功之后：
        // 存档损坏等原因进场失败时不复活，否则这个空实例会被反复重连续命、复活计数虚高（评审意见）。本方法整段在逻辑线程上同步执行，中间不会被销毁
        Scene reviveOnEnter = scene.draining() && scene.drainCause() == DrainCause.IDLE ? scene : null;
        if (reviveOnEnter != null) {
            // 照常进入这个实例，不改派
        } else if (scene.draining()) {
            // 分配时目录还没报排空（节点拉到计划后 ≤1 s 内才补发目录，D13）：改进兄弟频道（§4.10.4），顺序同排空改派；
            // 都没有就进排空中的这个、随后被改派。79 与位置记录给的都是实际进入的场景（gate 只按节点路由，不看场景号）。
            // 级联 / 显式销毁中的实例同样改派：镜像 → 同图主世界频道，副本 → 默认大世界（批次 5.3 §6.11）
            Scene sibling = relocationTarget(scene);
            if (sibling != null) {
                metrics.channelRelocation(ChannelRelocation.ENTER_REDIRECT);
                log.info("进场目标频道在排空，改进兄弟频道 player={} {} -> {}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(scene.sceneId()), Long.toUnsignedString(sibling.sceneId()));
                scene = sibling;
            }
        }

        // 同一会话上挂着另一个玩家（gate 复用会话换角色）：按正常离开处理，它自己的 epoch 仍有效，写回并释放。
        // 它若正冻结在交出中：写回与在途交出谁先提交都安全（写回先 → 交出被围栏拒；交出先 → 写回被拒、结局出来时释放新 epoch），
        // removePlayer 会把那次换图标成「已移出」，结局只做收尾。
        ScenePlayer sessionOccupant = playersBySession.get(key);
        if (sessionOccupant != null && sessionOccupant != previous) {
            removePlayer(sessionOccupant, true);
            locations.loggedOut(sessionOccupant);
        }

        int savedConfigId = data.sceneConfigId();
        Vec3 savedPosition = data.position();
        int level = PlayerLevels.clampStored(data.level());
        if (level != data.level()) {
            log.warn("存档等级超出上限，压回 {} player={} 存档等级={}", level, playerId, Integer.toUnsignedLong(data.level()));
        }
        PlayerState state = data.state();
        List<Integer> skills = tables.initialSkills();
        if (previous != null) {
            // 本节点上还挂着这个玩家的旧实例，而库里的归属已经被这次进场夺走——只会发生在旧实例失去归属之后
            // （续约失败、租约过期被强制夺权，续约检测还没来得及移除它）。它的 epoch 已过期，写回只会被围栏拒绝，所以不写。
            // 旧实例挂在别的会话上时，告诉 gate 把那个会话踢掉，不让它悬空（请求转到 scene 后找不到玩家、收不到任何提示）。
            if (previous.ownerEpoch() == epoch
                    || previous.lastPersisted() != null && sameContent(previous.lastPersisted(), data.asPersisted())) {
                // 同一 epoch 的重复进场（旧实例仍持有归属，不可能有别的写者），或库里仍是旧实例最近一次确认写进去的样子
                // （期间没有别的节点写过）：旧实例的内存只会更新，沿用它。
                savedConfigId = previous.scene().configId();
                savedPosition = previous.position();
                level = previous.level();
                state = previous.persistentState();
                skills = previous.skills();
            } else {
                // 期间别的节点夺过归属并写过库（或旧实例上次存盘结局不明）：库里那份才是权威，沿用旧内存会把别人已落库的改动
                // （含资产通道已回报 durable 的结局）整个盖掉。旧实例没落库的改动随之作废，与宕机丢失同等。
                log.warn("接管旧实例时库里的数据已不是它最近一次落库的样子，以库为准 player={} 旧epoch={} 新epoch={}",
                        playerId, previous.ownerEpoch(), epoch);
            }
            removePlayer(previous, false);
            if (!previous.session().equals(key)) {
                kick(previous, KICKED_BY_ANOTHER, "被新的进场接管（旧实例已失去归属）");
            }
        }

        ScenePlayer player = new ScenePlayer(playerId, nextId(), key, epoch, data.classId(),
                data.gender(), data.appearanceId(), level, skills,
                resolveEnterPosition(scene.configId(), savedConfigId, savedPosition), state, clock.nanoTime());
        player.setName(data.name());
        if (previous != null && previous.ownerEpoch() == epoch) {
            player.continueLocationSeq(previous.locationSeq());
        }
        try {
            playerInitializer.initialize(player);
        } catch (RuntimeException e) {
            log.error("玩家状态初始化失败 player={}", playerId, e);
            failEnter(key, playerId, epoch, transfer, "玩家状态初始化失败");
            return;
        }
        if (reviveOnEnter != null) {
            revive(reviveOnEnter, "在途进场到达 player=" + Long.toUnsignedString(playerId));
            publishInstances();
            instances.directoryChanged().run();
        }
        // 脏比对基准是库里此刻的样子（不是刚建出来的内存状态）：接管旧实例、出生点改派等与库不同的情形，第一次到期就会写。
        player.markPersisted(data.asPersisted());
        playersById.put(playerId, player);
        playersBySession.put(key, player);
        playersByEntity.put(player.entity(), player);
        enterScene(player, scene);
        sink.enterResult(key.linkId(), key.sessionId(), playerId, epoch, 0);
        locations.entered(player);
        if (transfer) {
            // 交出进场（scene-handoff-spec §5.8）：不是登录，不拍 LOGIN 快照（D8，冻结快照已在库里）。新 epoch 的租约从源节点提交交出时起算，
            // 中间隔着 gate 建链、握手与加载，立即单独续约一次把余量拉回来（Q5），不等下一轮周期续约。
            metrics.transferEnter(TransferEnter.OK);
            crossNode.renewSoon().accept(new OwnedPlayer(playerId, epoch));
        } else {
            // 用内存状态拍（接管旧实例时库里那份是旧的）
            snapshots.capture(player.toSave(), PlayerSnapshots.Cause.LOGIN);
        }
        log.info("玩家进场 player={} session={} scene_id={} entity={} epoch={} 接管旧实例={} 交出进场={}", playerId, key,
                Long.toUnsignedString(scene.sceneId()), player.entity(), player.ownerEpoch(), previous != null, transfer);
        // 进场（登录 / 重连 / 顶号 / 交出进场）之后查组队跟随：异步读，结果回到逻辑线程（team-spec §6.10）
        teamFollow.onEnteredScene(this, player);
        // 进场恢复（scene-battle-spec §7.8）：组队跟随之后（同基线组队 3.5 步在战斗 6 步之前）；同 epoch 接替旧实例时沿用它的冻结
        battle.onEntered(this, player, previous != null && previous.ownerEpoch() == epoch ? previous : null);
    }

    /** 两份写回内容相同（不比 owner_epoch：库里的 epoch 已被这次进场夺权改掉）。 */
    private static boolean sameContent(PlayerSave a, PlayerSave b) {
        return a.level() == b.level() && a.sceneConfigId() == b.sceneConfigId() && a.position().equals(b.position())
                && a.state().equals(b.state());
    }

    /**
     * 进场落位（基线 EnsureValidEnterLocation，Java 版没有导航网格）：存档坐标属于同一场景配置、在世界范围内
     * （{@link MovementRules#insideWorld}，旧版本可能写进过极端坐标）且不是 (0,0,0) 就沿用；
     * 换地图、新号或坐标无效一律落到目标场景出生点。
     */
    private Vec3 resolveEnterPosition(int targetConfigId, int savedConfigId, Vec3 saved) {
        if (savedConfigId == targetConfigId && MovementRules.insideWorld(saved) && !saved.isOrigin()) {
            return saved;
        }
        return tables.spawnPoint(targetConfigId);
    }

    /**
     * 放进场景并当场发进场下行。基线进场落位会置 Transform 脏位、之后发一条不带 entity_id 的 66；
     * 这里不置：21 / 47 已经带了位置，那条 66 对客户端没有新信息（scene 契约文档 §7.4「可以不发」）。
     */
    private void enterScene(ScenePlayer player, Scene scene) {
        player.setScene(scene);
        player.markActive(frame);
        ViewIndex.Entered entered = scene.add(player);
        noteOccupancy(scene);
        publishPopulation(scene.configId());
        metrics.aoiEntered(entered.seen().size() + entered.seers().size());
        // 新观察者只从 21 / 47 拿到位置：朝向、速度由下一个同步帧的 66 补上（见 markFullStateForNewWatcher）。
        for (ScenePlayer seen : entered.seen()) {
            seen.markFullStateForNewWatcher();
        }
        if (!entered.seers().isEmpty()) {
            player.markFullStateForNewWatcher();
        }

        ActorCreateS2C self = player.toActorCreate();
        sendTo(player, push(ids.notifyEnterScene(), EnterSceneS2C.newBuilder().setSceneInfo(scene.info()).build()));
        sendTo(player, push(ids.notifyActorCreate(), self));
        if (!entered.seen().isEmpty()) {
            sendTo(player, push(ids.notifyActorListCreate(), actorList(entered.seen())));
        }
        broadcast(entered.seers(), push(ids.notifyActorCreate(), self));
    }

    /**
     * 进场失败只回 {@code PlayerEnterResult{tip=3023}}（回显 epoch），客户端看到的 23 {@code TipInfoMessage{3023}} 由 gate 发
     * （xm-api node_link.proto 约定「失败时 gate 解绑会话上的场景与玩家并把 tip 发给客户端」）。scene 再直推一条 23
     * 会让客户端收到两次同一提示。同时释放这次进场夺得的归属（带围栏：它已被更新的夺权取代时什么也不做），
     * 客户端重试进游戏不必等租约过期。交出进场（{@code transfer}）同样处理：gate 据结果推 23 后断开（不回大厅），释放的是交出铸出的 E+1。
     */
    private void failEnter(SessionKey key, long playerId, long epoch, boolean transfer, String reason) {
        log.warn("进场失败 player={} session={} epoch={} 交出进场={} 原因={}", playerId, key, epoch, transfer, reason);
        if (transfer) {
            metrics.transferEnter(TransferEnter.FAILED);
        }
        sink.enterResult(key.linkId(), key.sessionId(), playerId, epoch, ENTER_FAILED);
        releaseClaim(playerId, epoch);
    }

    private void releaseClaim(long playerId, long epoch) {
        if (playerId != 0 && epoch != 0) {
            repository.release(playerId, epoch);
        }
    }

    // ------------------------------------------------------------------ 换场景（本节点内）

    /**
     * 离开当前场景（看得见它的人收到 51）再进入目标场景（79 / 21 / 47 / 21）。换地图落到出生点，同图换线保留坐标。
     * 换场景时停下（速度清零）并把位移校验的锚点移到落点：新场景里的人从 21 看到的是静止的它，客户端的下一条移动上行
     * 会重新带上速度。换完同步调组队跟随钩子（{@link TeamFollow}；被跟随换场景也调，它已与队长同场景，不会循环）。
     * 冻结中（交出在途）的玩家不换（调用方都已挡掉：63 回 3014、排空改派与组队跟随跳过；这里是纵深防御，记 ERROR）。
     */
    public void switchScene(ScenePlayer player, Scene target) {
        Scene from = player.scene();
        if (from == target) {
            return;
        }
        if (player.frozen()) {
            log.error("冻结中的玩家不能换场景（调用方漏了冻结闸），忽略 player={} {} -> {}", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(from.sceneId()), Long.toUnsignedString(target.sceneId()));
            return;
        }
        List<ScenePlayer> oldWatchers = from.remove(player);
        noteOccupancy(from);
        publishPopulation(from.configId());
        metrics.aoiLeft(oldWatchers.size());
        broadcast(oldWatchers, destroyMessage(player));
        Vec3 at = target.configId() != from.configId() ? tables.spawnPoint(target.configId()) : player.position();
        player.setPosition(at);
        player.stopMotion();
        player.moveGuard().reset(at, clock.nanoTime());
        enterScene(player, target);
        locations.entered(player);
        log.info("玩家换场景 player={} {} -> {}", Long.toUnsignedString(player.playerId()),
                Long.toUnsignedString(from.sceneId()), Long.toUnsignedString(target.sceneId()));
        teamFollow.onEnteredScene(this, player);
    }

    // ------------------------------------------------------------------ 跨节点换图（批次 5.2，scene-handoff-spec §5.5）

    /**
     * 换图「在途」判定（基线 IsSceneChangeBusy）：选目标中 / 镜像取号中（RESOLVING，批次 5.3 D4）或冻结中（FREEZING）为 true。
     * RESOLVING 槽过了期限（结果回调丢了，正常不会）就<b>当场作废</b>（有副作用：清掉这个槽）、不再挡，迟到的结果按过期丢弃
     * （{@code player.switching() != sw} → STALE）。逻辑线程上调用。
     *
     * <p>两个调用方：63 的处理器（在途回 3014）；回合制战斗的备战闸（在途回 1006，scene-battle-spec §7.5 第 1.3 步 / D4）——
     * 备战必须经这里而不是直接看 {@link ScenePlayer#switchPhase()}：后者不做过期判定，回调万一丢了，这名玩家的备战会一直被挡到他再发一次 63 或重登
     * （审计 GAT-14；D4 只允许 ≤ 选目标兜底超时的竞态）。槽在这里清掉之后，迟到的选目标结果不会在战斗冻结挂上之后再进交出。
     */
    public boolean switchInFlight(ScenePlayer player) {
        PlayerSwitch sw = player.switching();
        if (sw == null) {
            return false;
        }
        if (sw.phase() == SwitchPhase.RESOLVING && clock.nanoTime() - sw.resolveDeadlineNanos() >= 0) {
            log.warn("选目标 / 取号的结果过了期限还没回来，作废这次在途 player={} token={} 用途={}",
                    Long.toUnsignedString(player.playerId()), sw.token(), sw.purpose());
            player.setSwitching(null);
            return false;
        }
        return true;
    }

    /**
     * 63 的远端去向已受理（应答 {@code {0}} 已回）：进 RESOLVING，请 scene-manager 选目标。不冻结——玩家照常游玩，
     * 离场 / 断链 / 接管 / 失去归属照现有逻辑处理；结果回来时实例已不在或换图已作废就丢弃（计 stale）。
     */
    void beginRemoteSwitch(ScenePlayer player, long wantSceneId, int wantConfigId) {
        if (!crossNode.enabled()) {
            throw new IllegalStateException("跨节点换图没装配，不该解析出远端去向");
        }
        if (player.inBattle()) {
            // handoff-spec :106「begin 拒绝战斗中的玩家」（scene-battle-spec §7.13）：63 已先回 3023，走到这里是调用方漏了战斗闸
            metrics.switchResolve(SwitchResolve.IN_BATTLE);
            log.error("回合制战斗中的玩家不能发起跨节点换图（调用方漏了战斗闸），忽略 player={} battle={}",
                    Long.toUnsignedString(player.playerId()), player.battle().freeze());
            return;
        }
        long token = ++switchTokens;
        PlayerSwitch sw = new PlayerSwitch(token, wantSceneId, wantConfigId,
                clock.nanoTime() + crossNode.resolveTimeout().toNanos() + RESOLVE_SLOT_GRACE_NANOS);
        player.setSwitching(sw);
        log.info("跨节点换图：请 scene-manager 选目标 player={} token={} 当前 scene_id={} 指定 scene_id={} scene_config_id={}",
                Long.toUnsignedString(player.playerId()), token, Long.toUnsignedString(player.scene().sceneId()),
                Long.toUnsignedString(wantSceneId), wantConfigId);
        crossNode.targets().select(player.playerId(), player.scene().sceneId(), wantSceneId, wantConfigId,
                selection -> onSwitchTargetSelected(player, sw, selection));
    }

    /** 选目标的结果（逻辑线程）。先核对实例与令牌（§5.5 第 4 步），再按结果分派。 */
    private void onSwitchTargetSelected(ScenePlayer player, PlayerSwitch sw, Selection selection) {
        if (playersById.get(player.playerId()) != player || player.switching() != sw
                || sw.phase() != SwitchPhase.RESOLVING) {
            metrics.switchResolve(SwitchResolve.STALE);
            log.debug("选目标的结果回来时实例已离开 / 已重新进场 / 换图已作废，丢弃 player={} token={}",
                    Long.toUnsignedString(player.playerId()), sw.token());
            return;
        }
        switch (selection) {
            case Selection.Failed failed -> {
                player.setSwitching(null);
                metrics.switchResolve(SwitchResolve.ERROR);
                log.warn("选目标调用失败，留在原地 player={} token={}: {}", Long.toUnsignedString(player.playerId()),
                        sw.token(), failed.reason());
                pushTip(player, SERVICE_UNAVAILABLE);
            }
            case Selection.Refused refused -> {
                player.setSwitching(null);
                metrics.switchResolve(SwitchResolve.REJECTED);
                log.info("scene-manager 拒绝换图目标，留在原地 player={} token={} tip={}",
                        Long.toUnsignedString(player.playerId()), sw.token(), refused.tipId());
                pushTip(player, ENTER_FAILED);
            }
            case Selection.Chosen chosen -> onSwitchTargetChosen(player, sw, chosen);
        }
    }

    private void onSwitchTargetChosen(ScenePlayer player, PlayerSwitch sw, Selection.Chosen chosen) {
        Scene local = scenes.get(chosen.sceneId());
        if (local != null) {
            // 结果在本节点：目录过时（63 时还不在 / 已在排空），或只带地图时选中本节点另一个频道
            player.setSwitching(null);
            if (local == player.scene()) {
                metrics.switchResolve(SwitchResolve.SAME);
                return;
            }
            if (local.draining()) {
                metrics.switchResolve(SwitchResolve.REJECTED);
                log.info("选中的本节点场景在排空，留在原地 player={} scene_id={}", Long.toUnsignedString(player.playerId()),
                        Long.toUnsignedString(local.sceneId()));
                pushTip(player, ENTER_FAILED);
                return;
            }
            metrics.switchResolve(SwitchResolve.LOCAL);
            switchScene(player, local);
            return;
        }
        if (chosen.sceneNodeId() == 0 || chosen.sceneId() == 0) {
            player.setSwitching(null);
            metrics.switchResolve(SwitchResolve.ERROR);
            log.error("scene-manager 的选目标应答残缺（节点号或场景号为 0），留在原地 player={} 应答={}",
                    Long.toUnsignedString(player.playerId()), chosen);
            pushTip(player, SERVICE_UNAVAILABLE);
            return;
        }
        if (chosen.sceneNodeId() == crossNode.localNodeId()) {
            // 指向本节点却不在本节点：目录过时（场景已销毁）。绝不交给自己（同一实例不能换 epoch）
            player.setSwitching(null);
            metrics.switchResolve(SwitchResolve.REJECTED);
            log.warn("选目标指向本节点上已不存在的场景，留在原地 player={} scene_id={}",
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(chosen.sceneId()));
            pushTip(player, ENTER_FAILED);
            return;
        }
        if (player.inBattle()) {
            // 两种冻结互斥（scene-battle-spec §7.13 世界内部第 1 条；基线起交接前复查 lc.cpp:2853-2860）：选目标期间进了战斗
            // （迟到确认 / 进场恢复在 RESOLVING 时挂上了冻结）→ 中止换图、推 23 {3023}。按 D4 应恒为 0，是纵深防御
            player.setSwitching(null);
            metrics.switchResolve(SwitchResolve.IN_BATTLE);
            log.info("选目标回来时玩家已在回合制战斗中，中止跨节点换图 player={} battle={}", Long.toUnsignedString(player.playerId()),
                    player.battle().freeze());
            pushTip(player, ENTER_FAILED);
            return;
        }
        metrics.switchResolve(SwitchResolve.REMOTE);
        freeze(player, sw, chosen);
    }

    /**
     * 冻结并提交交出（§5.5「目标在别的节点」）：停下（旁人收到「停了」的 66，服务器不再外推）→ 拍冻结快照 → FREEZING →
     * {@link PlayerRepository#handOff}。冻结前已在途的在线存盘不等：它与交出都带 (E, 未释放) 围栏，谁先提交都安全。
     */
    private void freeze(ScenePlayer player, PlayerSwitch sw, Selection.Chosen target) {
        if (!player.velocity().isOrigin()) {
            player.stopMotion();
            player.markDirty(ScenePlayer.DIRTY_VELOCITY);
        }
        PlayerSave snapshot = player.toSave();
        sw.freeze(target.sceneNodeId(), target.sceneId(), target.sceneConfigId(), snapshot, clock.nanoTime());
        transfersInFlight++;
        metrics.transfersInFlight(transfersInFlight);
        log.info("跨节点换图：冻结并提交交出 player={} token={} epoch={} → node={} scene_id={} scene_config_id={}",
                Long.toUnsignedString(player.playerId()), sw.token(), player.ownerEpoch(), target.sceneNodeId(),
                Long.toUnsignedString(target.sceneId()), target.sceneConfigId());
        repository.handOff(snapshot, outcome -> onHandOffDone(player, sw, outcome));
    }

    /** 交出的结局（逻辑线程）。 */
    private void onHandOffDone(ScenePlayer player, PlayerSwitch sw, HandOffOutcome outcome) {
        if (player.switching() != sw || sw.phase() != SwitchPhase.FREEZING) {
            log.error("交出结局到达时换图状态不符（同一次交出的结局回了两次？），忽略 player={} token={} 结局={}",
                    Long.toUnsignedString(player.playerId()), sw.token(), outcome);
            return;
        }
        switch (outcome) {
            case HandOffOutcome.HandedOff handedOff -> onHandedOff(player, sw, handedOff.newEpoch());
            case HandOffOutcome.LeaseTooShort ignored -> unfreezeInPlace(player, sw, TransferResult.LEASE_TOO_SHORT);
            case HandOffOutcome.Fenced ignored -> onTransferFenced(player, sw);
            case HandOffOutcome.Failed failed -> {
                // 结局不明（重试用尽 / 线程池拒绝）：加锁读探测，截止前给出确定结论；仍然冻结
                log.warn("交出结局不明，探测归属 player={} token={} epoch={} 尝试={}",
                        Long.toUnsignedString(player.playerId()), sw.token(), player.ownerEpoch(),
                        failed.attempts().leases().size());
                repository.probe(failed, probed -> onProbeDone(player, sw, probed));
            }
        }
    }

    /** 交出探测的结局（逻辑线程）。 */
    private void onProbeDone(ScenePlayer player, PlayerSwitch sw, ProbeOutcome outcome) {
        if (player.switching() != sw || sw.phase() != SwitchPhase.FREEZING) {
            log.error("交出探测结局到达时换图状态不符，忽略 player={} token={} 结局={}",
                    Long.toUnsignedString(player.playerId()), sw.token(), outcome);
            return;
        }
        switch (outcome) {
            case ProbeOutcome.NotCommitted ignored -> unfreezeInPlace(player, sw, TransferResult.ABORTED_IN_PLACE);
            case ProbeOutcome.HandedOff handedOff -> onHandedOff(player, sw, handedOff.newEpoch());
            case ProbeOutcome.Lost ignored -> onTransferLost(player, sw);
        }
    }

    /**
     * 已交出（库里 E+1、未释放、冻结快照已落库）。冻结中没有别的事：移除实例（旁人 51，不写回、不拍 LOGOUT）→ 留墓碑 →
     * 经同一条链路发 PlayerTransfer。帧确定没写出（链路已断 / 不可写）→ 源节点释放 E+1、位置以 (E, 序号+1) 转重连租约。
     * 冻结中会话已离开 / 被请求让出 → 不发 PlayerTransfer（没人会拿 E+1 进场，释放安全），照离开 / 接管收尾。
     */
    private void onHandedOff(ScenePlayer player, PlayerSwitch sw, long newEpoch) {
        long playerId = player.playerId();
        long fromEpoch = player.ownerEpoch();
        if (newEpoch != fromEpoch + 1) {
            // 存储层保证 = E+1（同一行锁内读回）；对不上照样按库里的值走，只留 ERROR 待查
            log.error("交出后的 epoch 不是 E+1 player={} E={} 新={}", Long.toUnsignedString(playerId), fromEpoch, newEpoch);
        }
        if (sw.detached()) {
            // 实例已被停服 / 换角色 / 接替移出（那条路径已写回，必被围栏拒）：PlayerTransfer 没发、也不会发，新 epoch 只有本节点知道
            finishTransfer(player, sw, TransferResult.LEFT);
            repository.release(playerId, newEpoch);
            log.info("交出已提交但实例已移出世界，释放新 epoch player={} epoch {}→{}", Long.toUnsignedString(playerId),
                    fromEpoch, newEpoch);
            return;
        }
        if (!player.toSave().equals(sw.snapshot())) {
            // 冻结快照已经落库、内存却在冻结期间被改过：改动随实例移除而丢失。说明有入口漏了冻结闸（§5.9 事后检测），应恒为 0
            metrics.postFreezeMutation();
            log.error("冻结期间玩家状态被改过（漏掉的冻结闸），这部分改动随交出丢失 player={} token={}",
                    Long.toUnsignedString(playerId), sw.token());
        }
        switch (sw.pendingAbort()) {
            case LEAVE -> {
                finishTransfer(player, sw, TransferResult.LEFT);
                removePlayer(player, false);
                repository.release(playerId, newEpoch);
                writeLeaveLocation(player, sw.leaveVoluntary());
                log.info("交出已提交但冻结中会话已离开，释放新 epoch player={} epoch {}→{} 主动={}",
                        Long.toUnsignedString(playerId), fromEpoch, newEpoch, sw.leaveVoluntary());
            }
            case TAKEOVER -> {
                finishTransfer(player, sw, TransferResult.TAKEN_OVER);
                removePlayer(player, false);
                repository.release(playerId, newEpoch);
                kick(player, KICKED_BY_ANOTHER, "冻结中数据归属被新的进游戏接管（交出已提交，释放新 epoch）");
            }
            case NONE -> sendTransfer(player, sw, newEpoch);
        }
    }

    private void sendTransfer(ScenePlayer player, PlayerSwitch sw, long newEpoch) {
        long playerId = player.playerId();
        SessionKey session = player.session();
        player.setSwitching(null);
        removePlayer(player, false);
        TransferTombstone tombstone = new TransferTombstone(session, newEpoch, player,
                clock.nanoTime() + crossNode.tombstoneTtl().toNanos());
        boolean written = sink.playerTransfer(session.linkId(), session.sessionId(), playerId, player.ownerEpoch(),
                newEpoch, sw.targetNodeId(), sw.targetSceneId(), () -> onTransferWriteFailed(tombstone));
        if (!written) {
            // 帧确定没写出：gate 不知道 E+1（它随链路断开关会话），由源节点释放；位置转重连租约（重连回到原场景，库里就是冻结快照）
            finishTransfer(player, sw, TransferResult.LINK_GONE);
            repository.release(playerId, newEpoch);
            locations.disconnected(player);
            log.warn("交出已提交但到 gate 的链路已断 / 不可写，释放新 epoch player={} epoch {}→{}",
                    Long.toUnsignedString(playerId), player.ownerEpoch(), newEpoch);
            return;
        }
        purgeExpiredTombstones();
        transferTombstones.put(session, tombstone);
        finishTransfer(player, sw, TransferResult.HANDED_OFF);
        log.info("跨节点换图：已交出并通知 gate 改绑 player={} token={} epoch {}→{} → node={} scene_id={}",
                Long.toUnsignedString(playerId), sw.token(), player.ownerEpoch(), newEpoch, sw.targetNodeId(),
                Long.toUnsignedString(sw.targetSceneId()));
    }

    /**
     * PlayerTransfer 交给链路之后异步写失败（链路在冲刷前关闭；逻辑线程）：gate 没收到改绑指令、不会发 PlayerEnter{E+1}，源节点释放 E+1；
     * 位置转重连租约（交叉的 leave 已按墓碑写过就不再写）。
     */
    private void onTransferWriteFailed(TransferTombstone tombstone) {
        transferTombstones.remove(tombstone.session(), tombstone);
        repository.release(tombstone.playerId(), tombstone.toEpoch());
        if (tombstone.markLocationWritten()) {
            locations.disconnected(tombstone.removed());
        }
        log.warn("PlayerTransfer 没写出去，释放新 epoch player={} epoch {}→{}", Long.toUnsignedString(tombstone.playerId()),
                tombstone.fromEpoch(), tombstone.toEpoch());
    }

    /** 交出没提交（剩余租约不足 / 探测确认没提交）：原地解冻。冻结中会话已离开 / 被请求让出的，照现有离开 / 接管流程写回并释放 E。 */
    private void unfreezeInPlace(ScenePlayer player, PlayerSwitch sw, TransferResult result) {
        finishTransfer(player, sw, result);
        if (sw.detached()) {
            return;
        }
        switch (sw.pendingAbort()) {
            case LEAVE -> {
                removePlayer(player, true);
                writeLeaveLocation(player, sw.leaveVoluntary());
                log.info("交出没提交，冻结中会话已离开，写回并释放 player={} 结局={}", Long.toUnsignedString(player.playerId()),
                        result);
            }
            case TAKEOVER -> {
                removePlayer(player, true);
                kick(player, KICKED_BY_ANOTHER, "冻结中数据归属被新的进游戏接管（交出没提交，写回并释放）");
            }
            case NONE -> {
                log.info("交出没提交，原地解冻 player={} token={} 结局={}", Long.toUnsignedString(player.playerId()),
                        sw.token(), result);
                pushTip(player, ENTER_FAILED);
                // 冻结期间到达的确认只续了锁、没挂冻结，到达的结算被延后，销账回来也没 forget 账本（scene-battle-spec §7.7、§7.10、§7.12）：
                // 原地解冻后重跑一次完整的进场恢复（第 1 步起，规格 §10.5 要求的「锁步骤」的超集）。期间 recovery = PENDING：
                // 备战 1006、结算 DEFERRED，读失败由 reaper 重试
                battle.onUnfrozenInPlace(this, player);
            }
        }
    }

    /** 交出被围栏拒：归属已不是本实例的（租约过期被夺），同续约失去归属——移除（不写回）并踢 2017；会话已离开的不踢。 */
    private void onTransferFenced(ScenePlayer player, PlayerSwitch sw) {
        finishTransfer(player, sw, TransferResult.FENCED);
        if (sw.detached()) {
            return;
        }
        removePlayer(player, false);
        if (sw.pendingAbort() != PlayerSwitch.PendingAbort.LEAVE) {
            kick(player, KICKED_BY_ANOTHER, "交出时发现已失去数据归属");
        } else {
            log.warn("交出时发现已失去数据归属（会话已离开） player={}", Long.toUnsignedString(player.playerId()));
        }
    }

    /**
     * 交出结局不明且探测判定不了（锁等待超时、读到别人的归属 / 已释放）：fail-closed，移除（不写回、不释放）并踢 3023
     * （gate 推 23 {3023} 后断开，D5）。可能已提交（E+1 无人持有，等租约过期）或已被夺。会话已离开的不踢。
     */
    private void onTransferLost(ScenePlayer player, PlayerSwitch sw) {
        finishTransfer(player, sw, TransferResult.LOST_UNKNOWN);
        if (sw.detached()) {
            return;
        }
        removePlayer(player, false);
        if (sw.pendingAbort() != PlayerSwitch.PendingAbort.LEAVE) {
            kick(player, ENTER_FAILED, "交出结局无法确认（fail-closed）");
        } else {
            log.warn("交出结局无法确认（会话已离开） player={}", Long.toUnsignedString(player.playerId()));
        }
    }

    /** 一次交出终结：摘掉换图状态（之后 removePlayer 不再把它当「冻结中被移出」）、计结局与冻结时长。 */
    private void finishTransfer(ScenePlayer player, PlayerSwitch sw, TransferResult result) {
        if (player.switching() == sw) {
            player.setSwitching(null);
        }
        transfersInFlight--;
        metrics.transfersInFlight(transfersInFlight);
        metrics.transfer(result, clock.nanoTime() - sw.frozenAtNanos());
    }

    /** 冻结中会话离开 / 所在链路断开：只记下，等交出结局（同一玩家只有一个写在途；实例与旁人视野保持到结局回来）。 */
    private boolean deferLeaveIfFrozen(ScenePlayer player, boolean voluntary) {
        PlayerSwitch sw = player.switching();
        if (sw == null || sw.phase() != SwitchPhase.FREEZING) {
            return false;
        }
        sw.requestLeave(voluntary);
        log.info("冻结中会话离开，等交出结局再处理 player={} token={} 主动={}", Long.toUnsignedString(player.playerId()),
                sw.token(), voluntary);
        return true;
    }

    /**
     * 实例已不在时到来的 PlayerLeave：若这个会话刚交出（墓碑在、玩家对得上、没过期），就是与 PlayerTransfer 在链路上交叉的那条——
     * 按 voluntary 用 (E, 序号+1) 写登出墓碑或重连租约（gate 随后把那次 transfer 当过期并放弃 E+1）。绝不释放 E+1。
     */
    private boolean consumeTransferTombstone(SessionKey key, PlayerLeave leave) {
        TransferTombstone tombstone = transferTombstones.get(key);
        if (tombstone == null || tombstone.playerId() != leave.getPlayerId()) {
            return false;
        }
        transferTombstones.remove(key);
        if (tombstone.expired(clock.nanoTime())) {
            return false;
        }
        if (tombstone.markLocationWritten()) {
            writeLeaveLocation(tombstone.removed(), leave.getVoluntary());
        }
        log.info("交出后迟到的离开（与 PlayerTransfer 在链路上交叉），按旧 epoch 补写位置 player={} session={} 主动={}",
                Long.toUnsignedString(tombstone.playerId()), key, leave.getVoluntary());
        return true;
    }

    private void purgeExpiredTombstones() {
        if (transferTombstones.isEmpty()) {
            return;
        }
        long now = clock.nanoTime();
        transferTombstones.values().removeIf(tombstone -> tombstone.expired(now));
    }

    /** 离开后的位置记录：主动离开写登出墓碑，断线写重连租约（同 {@link #onPlayerLeave}）。 */
    private void writeLeaveLocation(ScenePlayer player, boolean voluntary) {
        if (voluntary) {
            locations.loggedOut(player);
        } else {
            locations.disconnected(player);
        }
    }

    /** 给本人推 23 {@code TipInfoMessage{tip}}（异步换图的结局，应答早已回过）。 */
    private void pushTip(ScenePlayer player, int tipId) {
        sendTo(player, push(ids.sendTipToClient(), SceneMessageIds.tip(tipId)));
    }

    // ------------------------------------------------------------------ 移动

    /**
     * 134 MoveStart / 132 MoveSync / 131 MoveStop（契约文档 movement §4.3）。应答是 {@code Empty}，任何情况都不回包。
     * <ol>
     *   <li>位置、朝向、速度有任何非有限值，或位置任一分量超出世界范围 ±{@link MovementRules#WORLD_LIMIT}：
     *       整条静默丢弃，状态不变、不发 137（基线不查，NaN 会进 Transform 并被广播，极端坐标原样接受）；</li>
     *   <li>位置：经 {@link MoveGuard} 位移校验（Java 版比基线严：基线无导航网格时原样接受任何坐标），超额度就把水平分量
     *       沿上报方向截断，高度取上报值；校验给不出有限位置时同样整条丢弃（上一条已挡住，属纵深防御）；</li>
     *   <li>朝向：用请求里的 rotation 整体覆盖（请求没带就是全零，基线同）；</li>
     *   <li>速度：MoveStart / MoveSync 按三维模长截断到 10 m/s，MoveStop 清零；Transform 与 Velocity 都置脏位
     *       （即使值没变，基线同），下一个偶数帧的 66 带上；</li>
     *   <li>裁决位置与上报位置水平偏差 &gt; 0.5 m：给本人发 137，{@code server_velocity} 填<b>处理完这条输入之后</b>的速度
     *       （基线填的是之前的速度；客户端据此重演预测，处理后的才对，契约文档 §9 第 4 条）。</li>
     * </ol>
     * 位置变化在帧内的视野刷新里重新判定进出视野（{@link ViewIndex} 的重判节奏：静止立即，移动中每累计 1 m）。
     */
    void applyMove(ScenePlayer player, MoveInput input) {
        if (!input.isAcceptable()) {
            metrics.move(MoveResult.INVALID);
            log.debug("移动输入含非有限值或坐标超出世界范围，丢弃 player={} input_seq={}", player.playerId(),
                    input.inputSeq());
            return;
        }
        Optional<Vec3> admitted = player.moveGuard().admit(input.location(), clock.nanoTime());
        if (admitted.isEmpty()) {
            metrics.move(MoveResult.INVALID);
            log.warn("位移校验给不出有限位置，丢弃（上游范围检查应已挡住） player={} input_seq={} 上报={} 锚点={}",
                    player.playerId(), input.inputSeq(), input.location(), player.moveGuard().anchor());
            return;
        }
        Vec3 accepted = admitted.get();
        player.scene().relocate(player, accepted);
        player.setRotation(input.rotation());
        player.setVelocity(MovementRules.clampSpeed(input.velocity()));
        player.markDirty(ScenePlayer.DIRTY_TRANSFORM | ScenePlayer.DIRTY_VELOCITY);
        boolean correct = MovementRules.needsCorrection(accepted, input.location());
        metrics.move(correct ? MoveResult.CORRECTED
                : accepted.equals(input.location()) ? MoveResult.ACCEPTED : MoveResult.CLAMPED);
        if (correct) {
            log.debug("移动纠偏 player={} input_seq={} 上报={} 裁决={}", player.playerId(), input.inputSeq(),
                    input.location(), accepted);
            sendTo(player, push(ids.notifyMoveAck(), MoveAckS2C.newBuilder()
                    .setInputSeq(input.inputSeq())
                    .setServerLocation(accepted.toLocation())
                    .setServerVelocity(player.velocity().toVelocity())
                    .setServerTimeMs(clock.epochMillis())
                    .build()));
        }
    }

    /** 收到该玩家的任一客户端消息：刷新活跃帧（挂机判定的唯一输入，基线 LastActiveFrameComp）。 */
    void touch(ScenePlayer player) {
        player.markActive(frame);
    }

    // ------------------------------------------------------------------ 帧

    /**
     * 跑一帧（固定步长 {@link MovementRules#STEP_SECONDS}）：外推 → 视野刷新（47 / 64）→ 偶数帧属性同步（66）→ 帧号 +1。
     * 由 {@link SceneTicker} 在场景逻辑线程上调用。没有移动、没有脏字段时只是遍历一遍玩家，不新建集合、不发消息。
     * 指标：整帧耗时每帧记一次；两个广播阶段（全部场景合计）每执行一次记一次——视野变化每帧、属性同步偶数帧。
     */
    public void step() {
        long start = clock.nanoTime();
        long viewBroadcastNanos = 0;
        for (Scene scene : scenes.values()) {
            integrate(scene);
            scene.refreshViews(viewChanges);
            long emitStart = clock.nanoTime();
            emitViewChanges();
            viewBroadcastNanos += clock.nanoTime() - emitStart;
        }
        metrics.broadcast(BroadcastKind.VIEW_CHANGES, viewBroadcastNanos);
        if (frame % 2 == 0) {
            long syncStart = clock.nanoTime();
            for (Scene scene : scenes.values()) {
                syncAttributes(scene);
            }
            metrics.broadcast(BroadcastKind.ATTRIBUTE_SYNC, clock.nanoTime() - syncStart);
        }
        frame++;
        metrics.tick(clock.nanoTime() - start);
    }

    long frame() {
        return frame;
    }

    /**
     * 服务器外推（基线 MovementSystem）：速度非零的玩家 {@code location += velocity × 0.05}，置 Transform 脏位。
     * 连续 {@link MovementRules#AFK_FRAMES} 帧没有任何客户端消息即停推（基线 AfkSystem）；停推时把速度清零并置脏位，
     * 让看得见它的人收到一条「停了」的 66——基线停推但不清速度，观察者的客户端按旧速度一直外推下去。
     * 没有导航网格，不做撞墙夹持（与基线无导航场景一致）。
     */
    private void integrate(Scene scene) {
        for (ScenePlayer player : scene.players()) {
            Vec3 velocity = player.velocity();
            if (velocity.isOrigin()) {
                continue;
            }
            if (frame - player.lastActiveFrame() >= MovementRules.AFK_FRAMES) {
                player.setVelocity(Vec3.ORIGIN);
                player.markDirty(ScenePlayer.DIRTY_VELOCITY);
                log.debug("玩家 {} 帧内无消息，停止外推 player={}", MovementRules.AFK_FRAMES, player.playerId());
                continue;
            }
            scene.relocate(player, player.position().plusScaled(velocity, MovementRules.STEP_SECONDS));
            player.markDirty(ScenePlayer.DIRTY_TRANSFORM);
        }
    }

    /** 把本次视野刷新的变化发出去：每个观察者先一条 47（新看见的）、再一条 64（看不见了的），与基线同序。 */
    private void emitViewChanges() {
        if (viewChanges.isEmpty()) {
            return;
        }
        viewChanges.forEach((watcher, delta) -> {
            metrics.aoiEntered(delta.added().size());
            metrics.aoiLeft(delta.removed().size());
            if (!delta.added().isEmpty()) {
                sendTo(watcher, push(ids.notifyActorListCreate(), actorList(delta.added())));
                for (ScenePlayer target : delta.added()) {
                    target.markFullStateForNewWatcher();
                }
            }
            if (!delta.removed().isEmpty()) {
                ActorListDestroyS2C.Builder destroyed = ActorListDestroyS2C.newBuilder();
                for (ScenePlayer target : delta.removed()) {
                    destroyed.addEntity(target.entity());
                }
                sendTo(watcher, push(ids.notifyActorListDestroy(), destroyed.build()));
            }
        });
        viewChanges.clear();
    }

    /**
     * 属性同步（基线 ActorStateAttributeSyncSystem，偶数帧，每个玩家最多 10 Hz）：有脏字段的玩家把脏字段拼成一条 66，
     * 序列化一次，发给看得见它的人（不含自己：自己的移动靠客户端预测 + 137 纠偏），发完清脏位。
     * 没人看得见它时<b>保留</b>脏位，等第一次有人看见时把积压的字段一次发出（基线同）。
     */
    private void syncAttributes(Scene scene) {
        for (ScenePlayer player : scene.players()) {
            int dirty = player.syncDirty();
            if (dirty == 0) {
                continue;
            }
            Set<ScenePlayer> watchers = scene.watchers(player);
            if (watchers.isEmpty()) {
                continue;
            }
            broadcast(watchers, push(ids.syncBaseAttribute(), player.toBaseAttributes(dirty)));
            player.clearDirty();
        }
    }

    // ------------------------------------------------------------------ 离场

    public void onPlayerLeave(long linkId, PlayerLeave leave) {
        SessionKey key = new SessionKey(linkId, leave.getSessionId());
        PendingEnter pending = pendingEnters.get(key);
        if (pending != null && pending.playerId() == leave.getPlayerId()) {
            pendingEnters.remove(key);
            releaseClaim(pending.playerId(), pending.ownerEpoch());
            if (leave.getVoluntary()) {
                // 加载中就 LeaveGame：同样是干净登出，盖掉更早那次进场留下的位置记录（断线则留着，它就是重连租约）
                locations.loggedOutWhileLoading(pending.playerId(), pending.ownerEpoch());
            }
            log.info("进场加载中离开，取消本次进场 player={} session={}", leave.getPlayerId(), key);
        }
        ScenePlayer player = playersBySession.get(key);
        if (player == null) {
            if (!consumeTransferTombstone(key, leave)) {
                log.debug("离开的会话不在本节点（已离开或已被顶替） player={} session={}", leave.getPlayerId(), key);
            }
            return;
        }
        if (player.playerId() != leave.getPlayerId()) {
            log.warn("离开帧的 player_id 与会话上的玩家不符，忽略 帧={} 会话上={} session={}",
                    leave.getPlayerId(), player.playerId(), key);
            return;
        }
        if (deferLeaveIfFrozen(player, leave.getVoluntary())) {
            return;
        }
        removePlayer(player, true);
        // 主动离开（LeaveGame）= 干净登出：删位置记录，下次进游戏按首登落点；断线留 30 s 重连租约（同基线断线租约）
        writeLeaveLocation(player, leave.getVoluntary());
        log.info("玩家离场 player={} session={} 主动={}", player.playerId(), key, leave.getVoluntary());
    }

    /**
     * gate 链路断开：取消这条链路上的进场（释放归属），移除其上全部玩家（看得见它们的人收到 51）并写回。幂等。
     * 冻结中（交出在途）的玩家只记下「断线离开」，实例保留到交出结局出来（见「跨节点换图」）；这条链路上的交出墓碑随之作废
     * （之后不会再有从它来的 PlayerLeave）。
     */
    public void onLinkClosed(long linkId) {
        for (Iterator<PendingEnter> it = pendingEnters.values().iterator(); it.hasNext(); ) {
            PendingEnter pending = it.next();
            if (pending.session().linkId() == linkId) {
                it.remove();
                releaseClaim(pending.playerId(), pending.ownerEpoch());
            }
        }
        List<ScenePlayer> onLink = new ArrayList<>();
        for (ScenePlayer player : playersBySession.values()) {
            if (player.session().linkId() == linkId) {
                onLink.add(player);
            }
        }
        int removed = 0;
        for (ScenePlayer player : onLink) {
            if (deferLeaveIfFrozen(player, false)) {
                continue;
            }
            removePlayer(player, true);
            locations.disconnected(player);
            removed++;
        }
        transferTombstones.keySet().removeIf(session -> session.linkId() == linkId);
        if (!onLink.isEmpty()) {
            log.info("gate 链路断开，移除其上玩家 link={} 人数={} 冻结中待结局={}", linkId, removed, onLink.size() - removed);
        }
    }

    // ------------------------------------------------------------------ 归属：接管与失去

    /**
     * login 请本节点让出 (playerId, ownerEpoch)：别的会话要进这个角色（顶号），或上次离开的写回还没落库。
     * 持有正是这个 epoch 的实例写回并释放、通知 gate 踢掉它的会话；还在加载中的进场取消并释放。
     * 别的 epoch（更旧或更新）一律不动：迟到的接管请求碰不到之后新进场的实例。幂等。
     * 冻结中（交出在途）的实例只记下「被请求让出」，等交出结局：没提交就照这里写回并释放、踢旧会话；已提交就释放 E+1、踢旧会话
     * （login 退避期内重试会再发，那时 E 已释放或 E+1 已释放）。交出提交后对 E+1 的让出请求在源节点什么也碰不到，由目标节点处理。
     */
    public void onTakeoverRequested(long playerId, long ownerEpoch) {
        ScenePlayer player = playersById.get(playerId);
        if (player != null && player.ownerEpoch() == ownerEpoch) {
            PlayerSwitch sw = player.switching();
            if (sw != null && sw.phase() == SwitchPhase.FREEZING) {
                sw.requestTakeover();
                log.info("冻结中被请求让出，等交出结局再处理 player={} token={} epoch={}",
                        Long.toUnsignedString(playerId), sw.token(), ownerEpoch);
                return;
            }
            removePlayer(player, true);
            kick(player, KICKED_BY_ANOTHER, "数据归属被新的进游戏接管");
            return;
        }
        for (Iterator<PendingEnter> it = pendingEnters.values().iterator(); it.hasNext(); ) {
            PendingEnter pending = it.next();
            if (pending.playerId() == playerId && pending.ownerEpoch() == ownerEpoch) {
                it.remove();
                releaseClaim(playerId, ownerEpoch);
                sink.playerKicked(pending.session().linkId(), pending.session().sessionId(), playerId, ownerEpoch,
                        KICKED_BY_ANOTHER);
                log.info("加载中的进场被新的进游戏接管，取消并踢掉会话 player={} session={} epoch={}",
                        playerId, pending.session(), ownerEpoch);
                return;
            }
        }
        log.debug("接管请求不涉及本节点 player={} epoch={}", playerId, ownerEpoch);
    }

    /**
     * 续约报告这些归属已经不在本节点手里（epoch 被夺走、已释放或玩家已删）：对应实例的写回只会被围栏拒绝，
     * 立即移除（不写回）并踢掉会话，不让一个没有归属的实例继续被玩、被别人看见。epoch 对不上的（已离开后又进来）不动。
     * 冻结中（交出在途）的实例忽略：交出提交之后 E 当然续不上，交出结局会裁决它的去留（scene-handoff-spec §5.5）。
     */
    public void onOwnershipLost(Collection<OwnedPlayer> lost) {
        for (OwnedPlayer owned : lost) {
            ScenePlayer player = playersById.get(owned.playerId());
            if (player != null && player.ownerEpoch() == owned.ownerEpoch()) {
                if (player.frozen()) {
                    log.info("续约报告失去归属，但实例正冻结在交出中，交给交出结局裁决 player={} epoch={}",
                            Long.toUnsignedString(owned.playerId()), owned.ownerEpoch());
                    continue;
                }
                removePlayer(player, false);
                kick(player, KICKED_BY_ANOTHER, "续约发现已失去数据归属");
            }
        }
    }

    // ------------------------------------------------------------------ 周期存盘

    /**
     * 周期存盘的一秒（每秒在逻辑线程上调一次）：{@code player_id} 对 {@code intervalSeconds} 取模等于本秒槽号的玩家到期，
     * 每人每周期恰好一次、单次工作量约为在线人数 / 周期秒数（基线 SCENE_PLAYER_SAVE_INTERVAL_SECONDS 的分槽做法）。
     * 停服开始后什么也不做。
     *
     * @return 本次提交的在线存盘数
     */
    public int saveDuePlayers(int intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("存盘周期必须为正: " + intervalSeconds);
        }
        long slot = saveSecond++ % intervalSeconds;
        if (periodicSaveStopped) {
            return 0;
        }
        int submitted = 0;
        for (ScenePlayer player : playersById.values()) {
            if (Long.remainderUnsigned(player.playerId(), intervalSeconds) != slot) {
                continue;
            }
            switch (submitProgress(player)) {
                case IN_FLIGHT -> metrics.periodicSave(PeriodicSave.IN_FLIGHT);
                case UNCHANGED -> metrics.periodicSave(PeriodicSave.UNCHANGED);
                // 存储积压：这一轮余下的到期玩家都推到下个周期，不把续约 / 最终写回堵在后面。
                case DEFERRED -> metrics.periodicSave(PeriodicSave.DEFERRED);
                case WRITTEN -> {
                    metrics.periodicSave(PeriodicSave.WRITTEN);
                    submitted++;
                }
                case STOPPED -> {
                }
            }
        }
        return submitted;
    }

    /**
     * 位置续期的一秒（每秒在逻辑线程上调一次）：{@code player_id} 对 {@link #LOCATION_REFRESH_SLOTS} 取模等于本秒槽号的在线玩家
     * 续一次位置记录的 TTL，每人每 {@link PlayerLocationDirectory#REFRESH_INTERVAL} 恰好一次。
     *
     * @return 本次续期的人数
     */
    public int refreshDueLocations() {
        purgeExpiredTombstones();
        long slot = locationSecond++ % LOCATION_REFRESH_SLOTS;
        List<ScenePlayer> due = new ArrayList<>();
        for (ScenePlayer player : playersById.values()) {
            if (Long.remainderUnsigned(player.playerId(), LOCATION_REFRESH_SLOTS) == slot) {
                due.add(player);
            }
        }
        if (!due.isEmpty()) {
            locations.refresh(due);
        }
        return due.size();
    }

    /** 一次立即存盘请求的结局（{@link #requestSave}）。 */
    public enum SaveRequest {
        /** 已提交在线存盘，结局回来后更新落库快照。 */
        WRITTEN,
        /** 与最近一次确认落库的快照相同：库里已是此刻的样子，不写。 */
        UNCHANGED,
        /** 已有一次在线存盘在途（它的快照可能不含最新改动，回来后下次请求 / 下个周期再比）。 */
        IN_FLIGHT,
        /** 存储积压，没提交。 */
        DEFERRED,
        /** 已停服或该实例已不在本节点，没提交。 */
        STOPPED
    }

    /**
     * 立刻为这个玩家提交一次在线存盘（不等周期）：资产通道记账后要尽快落盘、据实回报 durable（基线 SavePlayerToRedis）。
     * 规则同周期存盘（同一玩家至多一个在途、与落库快照相同就不写、存储积压就不提交），只是不按槽号。
     * 冻结中（交出在途）回 {@link SaveRequest#IN_FLIGHT}：那笔交出就是它的写（scene-handoff-spec §5.5）。
     */
    public SaveRequest requestSave(ScenePlayer player) {
        if (periodicSaveStopped || playersById.get(player.playerId()) != player) {
            return SaveRequest.STOPPED;
        }
        return submitProgress(player);
    }

    private SaveRequest submitProgress(ScenePlayer player) {
        if (player.progressSaveInFlight() || player.frozen()) {
            // 冻结中：交出事务写的就是冻结快照，同一玩家不再提交第二笔写
            return SaveRequest.IN_FLIGHT;
        }
        PlayerSave snapshot = player.toSave();
        if (snapshot.equals(player.lastPersisted())) {
            return SaveRequest.UNCHANGED;
        }
        if (!repository.acceptsProgress()) {
            return SaveRequest.DEFERRED;
        }
        player.setProgressSaveInFlight(true);
        repository.saveProgress(snapshot, result -> onProgressSaved(player, snapshot, result));
        return SaveRequest.WRITTEN;
    }

    /** 在线存盘的结局（逻辑线程）。实例可能已经离场（离场不等在途的在线存盘），那就只清在途标记。 */
    private void onProgressSaved(ScenePlayer player, PlayerSave snapshot, ProgressResult result) {
        player.setProgressSaveInFlight(false);
        switch (result) {
            case SAVED -> {
                player.markPersisted(snapshot);
                if (playersById.get(player.playerId()) == player) {
                    // 快路径销账（scene-battle-spec §7.12，D19）：账本条目此刻已在落库快照里的逐条销账
                    battle.onPersisted(this, player);
                }
            }
            case FAILED -> {
                // 失败不等于没写进去（提交阶段断连时结局未知）：库里是什么不再确定，作废比对基准，下个周期无条件重写。
                player.markPersisted(null);
                log.warn("在线存盘失败，下个周期按最新状态重写 player={} epoch={}", player.playerId(), player.ownerEpoch());
            }
            case FENCED -> {
                // 归属已被夺走（epoch 变了）或已释放：实例写回只会被拒，按续约发现失去归属处理。离场后才回来的不动。
                // 冻结中的不踢：冻结前就在途的在线存盘晚于交出提交时当然被拒，交出结局会裁决去留（scene-handoff-spec §5.5）。
                if (playersById.get(player.playerId()) == player) {
                    if (player.frozen()) {
                        log.info("冻结前在途的在线存盘被围栏拒绝，交给交出结局裁决 player={} epoch={}",
                                Long.toUnsignedString(player.playerId()), player.ownerEpoch());
                        return;
                    }
                    removePlayer(player, false);
                    kick(player, KICKED_BY_ANOTHER, "在线存盘被归属围栏拒绝（已失去数据归属）");
                }
            }
        }
    }

    /**
     * 通知 gate 踢掉这个实例的会话：gate 推 23 {@code TipInfoMessage{tipId}} 后断开（按会话当前绑定的 epoch 过滤）。
     * 顶号 / 失去归属用 2017；交出结局无法确认用 3023（scene-handoff-spec §5.5）。
     */
    private void kick(ScenePlayer player, int tipId, String reason) {
        sink.playerKicked(player.session().linkId(), player.session().sessionId(), player.playerId(),
                player.ownerEpoch(), tipId);
        log.warn("踢出玩家 player={} session={} epoch={} tip={} 原因={}", player.playerId(), player.session(),
                player.ownerEpoch(), tipId, reason);
    }

    /**
     * 移出场景（看得见它的人收到 51，离开者什么也收不到）并按需写回，顺序同基线退出流程：
     * 先停下（速度清零，StopMotionForExit）→ 离开场景（51）→ 写回此刻的位置（含 z）。
     * 外推与写回在同一线程上，停下之后到写回之间不会再被推；实例移出后不在任何场景的玩家列表里，此后不再外推，
     * 迟到的移动输入按会话找不到它、直接丢弃，写回的状态不会再变。
     * 冻结中（交出在途）的实例被别的路径移出（同会话换角色、被更高 epoch 的进场接替）时，把那次换图标成「已移出」：
     * 交出结局出来后只做收尾（见 {@link #onHandedOff}）。交出结局自己的收尾先摘掉换图状态再移除，不会走到这里的标记。
     */
    private void removePlayer(ScenePlayer player, boolean save) {
        PlayerSwitch sw = player.switching();
        if (sw != null && sw.phase() == SwitchPhase.FREEZING) {
            sw.detach();
        }
        player.stopMotion();
        List<ScenePlayer> watchers = player.scene().remove(player);
        noteOccupancy(player.scene());
        playersById.remove(player.playerId(), player);
        playersBySession.remove(player.session(), player);
        playersByEntity.remove(player.entity(), player);
        publishPopulation(player.scene().configId());
        metrics.aoiLeft(watchers.size());
        broadcast(watchers, destroyMessage(player));
        if (save) {
            PlayerSave written = player.toSave();
            repository.save(written);
            snapshots.capture(written, PlayerSnapshots.Cause.LOGOUT);
        }
    }

    private MessageContent destroyMessage(ScenePlayer player) {
        return push(ids.notifyActorDestroy(), ActorDestroyS2C.newBuilder().setEntity(player.entity()).build());
    }

    // ------------------------------------------------------------------ 停服 / 失去节点号

    /** 不再接收新玩家（节点号租约丢失时）：已在场的玩家照常服务直到离开。 */
    public void stopAcceptingEnters() {
        acceptingEnters = false;
    }

    /**
     * 停服：拒绝新进场、取消加载中的进场（释放归属）、把在场玩家全部写回并释放、清空（不再给客户端发消息）。
     * 返回写回人数。之后 {@link #step()} 没有玩家可推，是空操作。
     * 冻结中（交出在途）的玩家照常提交写回 E：与在途交出谁先提交都安全（写回先 → 交出被围栏拒；交出先 → 写回被拒，
     * 结局回到逻辑线程时释放新 epoch，scene-handoff-spec §5.2、§5.5）。这笔释放在本方法返回之后才提交，所以停服流程在关存储线程池
     * 之前要等在途交出 / 探测的结局处理完（{@code SceneShutdown} → {@code StoragePlayerRepository.awaitTransfersSettled}），
     * 否则它撞上已关闭的池被拒、E+1 悬空到租约过期；逻辑线程先停的（投递被拒）由存储线程代为释放。
     */
    public int shutdown() {
        acceptingEnters = false;
        periodicSaveStopped = true;
        for (PendingEnter pending : pendingEnters.values()) {
            releaseClaim(pending.playerId(), pending.ownerEpoch());
        }
        pendingEnters.clear();
        transferTombstones.clear();
        List<ScenePlayer> all = List.copyOf(playersById.values());
        for (ScenePlayer player : all) {
            PlayerSwitch sw = player.switching();
            if (sw != null && sw.phase() == SwitchPhase.FREEZING) {
                sw.detach();
            }
            player.stopMotion();
            PlayerSave written = player.toSave();
            repository.save(written);
            snapshots.capture(written, PlayerSnapshots.Cause.LOGOUT);
        }
        playersById.clear();
        playersBySession.clear();
        playersByEntity.clear();
        for (Scene scene : scenes.values()) {
            scene.clear();
            metrics.scenePlayers(scene.configId(), 0);
        }
        return all.size();
    }

    // ------------------------------------------------------------------ 查询与下行（包内给请求处理用）

    ScenePlayer playerBySession(SessionKey key) {
        return playersBySession.get(key);
    }

    /** 本世界的指标出口（包内给请求分发计冻结闸的拒绝 / 丢弃用）。 */
    SceneMetrics metrics() {
        return metrics;
    }

    /** 按 player_id 找本节点上的玩家（已进场的实例；加载中的不算）；没有为 null。 */
    public ScenePlayer playerById(long playerId) {
        return playersById.get(playerId);
    }

    /** 按场景实体号找本节点上的玩家（任意场景）；没有为 null。 */
    public ScenePlayer playerByEntity(long entity) {
        return playersByEntity.get(entity);
    }

    /** 给本人下发一条消息（经它所在 gate 链路的会话）。逻辑线程上调用。 */
    public void sendTo(ScenePlayer player, MessageContent content) {
        sink.send(player.session().linkId(), List.of(player.session().sessionId()), content);
    }

    /**
     * 回合制战斗备战即停步（scene-battle-spec §7.5 第 3 步，D5）：速度清零并置速度脏位，看得见它的人在下一个同步帧收到速度 0 的 66。
     * 与跨节点换图冻结的停步同一做法（{@link #freeze}）。逻辑线程上调用。
     */
    public void haltForBattle(ScenePlayer player) {
        player.stopMotion();
        player.markDirty(ScenePlayer.DIRTY_VELOCITY);
    }

    /** 本节点在场玩家的快照（逻辑线程上调用；遍历时可以改集合）。 */
    public List<ScenePlayer> playersSnapshot() {
        return new ArrayList<>(playersById.values());
    }

    /**
     * 发给自己和看得见自己的人（70 SkillUsed、33 SkillInterrupted）。基线的收件人不含施法者本人，Java 版含本人
     * （PARITY「70 收件人」行：客户端只按 70 播特效、33 只记日志），「视野内」换成兴趣列表。
     */
    public void broadcastToSelfAndWatchers(ScenePlayer player, MessageContent content) {
        Set<ScenePlayer> watchers = player.scene().watchers(player);
        List<ScenePlayer> recipients = new ArrayList<>(watchers.size() + 1);
        recipients.add(player);
        recipients.addAll(watchers);
        broadcast(recipients, content);
    }

    /** 按链路分组，同一链路上的会话合成一帧下发。 */
    private void broadcast(Collection<ScenePlayer> recipients, MessageContent content) {
        if (recipients.isEmpty()) {
            return;
        }
        Map<Long, List<Integer>> sessionsByLink = new LinkedHashMap<>();
        for (ScenePlayer recipient : recipients) {
            sessionsByLink.computeIfAbsent(recipient.session().linkId(), k -> new ArrayList<>())
                    .add(recipient.session().sessionId());
        }
        sessionsByLink.forEach((linkId, sessionIds) -> sink.send(linkId, sessionIds, content));
    }

    private static ActorListCreateS2C actorList(Collection<ScenePlayer> actors) {
        ActorListCreateS2C.Builder list = ActorListCreateS2C.newBuilder();
        for (ScenePlayer actor : actors) {
            list.addActorList(actor.toActorCreate());
        }
        return list.build();
    }

    private long nextId() {
        long id = idGenerator.getAsLong();
        if (id == 0) {
            // 客户端把实体号 0 当作「没有目标」（契约文档 §7.6），发出 0 会让该实体永远无法被选为技能目标。
            throw new IllegalStateException("发号器返回了 0");
        }
        return id;
    }

    /** @param transfer 跨节点换图的交出进场（{@code PlayerEnter.transfer}） */
    private record PendingEnter(SessionKey session, long playerId, long sceneId, long ownerEpoch, boolean transfer) {
    }
}
