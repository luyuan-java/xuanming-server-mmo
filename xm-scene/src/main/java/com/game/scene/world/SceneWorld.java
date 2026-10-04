package com.game.scene.world;

import static com.game.scene.world.SceneMessageIds.push;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneEntry;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.MessageContent;
import com.game.proto.MoveAckS2C;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.BroadcastKind;
import com.game.scene.metrics.SceneMetrics.MoveResult;
import com.game.scene.metrics.SceneMetrics.PeriodicSave;
import com.game.scene.player.PlayerLevels;
import com.game.scene.team.TeamFollow;
import com.game.scene.world.PlayerRepository.LoadResult;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p><b>指标</b>（{@link SceneMetrics}）：场景配置下的在线人数在每次人数变化后推送绝对值；移动裁决、视野变化通知、
 * 帧与帧内广播耗时（经 {@link SceneClock} 计时）都在这里记，与规则写在同一处，不另设观察者。
 */
public final class SceneWorld {

    private static final Logger log = LoggerFactory.getLogger(SceneWorld.class);

    private static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    /** 被别的会话接管 / 失去归属时推给旧会话的 tip（基线顶号同码，经 23 推送，本里程碑不发 34）。 */
    static final int KICKED_BY_ANOTHER = LoginErrorTip.login_error.kLoginBeKickByAnOtherAccount_VALUE;
    /** 位置续期的槽数（每秒一个槽）：每个在线玩家每这么多秒续一次。 */
    static final int LOCATION_REFRESH_SLOTS = (int) PlayerLocationDirectory.REFRESH_INTERVAL.toSeconds();

    private final SceneTables tables;
    private final SceneMessageIds ids;
    private final ClientSink sink;
    private final PlayerRepository repository;
    /** 场景号与实体号的发号器（本节点的雪花），必须恒非 0。 */
    private final LongSupplier idGenerator;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private final PlayerInitializer playerInitializer;
    private final PlayerSnapshots snapshots;
    private final PlayerLocations locations;
    private final TeamFollow teamFollow;

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
    /** 停服开始后不再做周期存盘（最终写回由 {@link #shutdown()} 统一做）。 */
    private boolean periodicSaveStopped;
    /** 周期存盘已走过的秒数（槽号 = 它对存盘周期取模）。 */
    private long saveSecond;
    /** 位置续期已走过的秒数（槽号 = 它对 {@link #LOCATION_REFRESH_SLOTS} 取模）。 */
    private long locationSecond;
    /** 已跑过的帧数（下一帧的帧号）。偶数帧做属性同步。 */
    private long frame;

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

    public Scene createScene(int configId) {
        Scene scene = new Scene(nextId(), configId);
        scenes.put(scene.sceneId(), scene);
        publishPopulation(configId);
        log.info("创建场景 scene_id={} scene_config_id={}", scene.sceneId(), configId);
        return scene;
    }

    /**
     * 把某场景配置下的在线人数（各频道合计）推给指标。场景只在启动时建、数量很少，按配置现数一遍即可；
     * 推的是绝对值，任何一次人数变化后推都能纠正之前的偏差。
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

    /** 节点目录快照（scene-manager 按它分配场景）。 */
    public List<SceneEntry> sceneEntries() {
        List<SceneEntry> entries = new ArrayList<>(scenes.size());
        for (Scene scene : scenes.values()) {
            entries.add(SceneEntry.newBuilder()
                    .setSceneId(scene.sceneId())
                    .setSceneConfigId(scene.configId())
                    .setPlayerCount(scene.playerCount())
                    .build());
        }
        return entries;
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
     * 客户端换场景（63）的目标：指定了 scene_id 就找本节点上的这个场景；只给配置时，
     * 配置与当前相同视为留在原场景（基线 scene_manager 挑回原频道即幂等不动），否则取本节点该配置人数最少的场景。
     * 目标不在本节点返回 null（跨节点换场景首批不做）。
     */
    Scene resolveSwitchTarget(Scene current, long sceneId, int configId) {
        if (sceneId != 0) {
            return scenes.get(sceneId);
        }
        if (configId == current.configId()) {
            return current;
        }
        Scene best = null;
        for (Scene scene : scenes.values()) {
            if (scene.configId() == configId && (best == null || scene.playerCount() < best.playerCount())) {
                best = scene;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 进场

    public void onPlayerEnter(long linkId, PlayerEnter enter) {
        SessionKey key = new SessionKey(linkId, enter.getSessionId());
        long playerId = enter.getPlayerId();
        long epoch = enter.getOwnerEpoch();
        if (!acceptingEnters) {
            failEnter(key, playerId, epoch, "本节点已停止接收新玩家");
            return;
        }
        if (playerId == 0 || enter.getSessionId() == 0) {
            failEnter(key, playerId, epoch, "player_id 或 session_id 为 0");
            return;
        }
        if (!scenes.containsKey(enter.getSceneId())) {
            failEnter(key, playerId, epoch, "场景不在本节点 scene_id=" + enter.getSceneId());
            return;
        }
        PendingEnter pending = new PendingEnter(key, playerId, enter.getSceneId(), epoch);
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
        if (pendingEnters.get(key) != pending) {
            // 取消这次进场的一方（离开 / 断链 / 被取代 / 接管 / 停服）已经负责释放归属。
            log.info("进场加载返回时会话已离开或已被新的进场取代，丢弃 player={} session={}", playerId, key);
            return;
        }
        pendingEnters.remove(key);

        if (!(result instanceof LoadResult.Found found)) {
            if (result instanceof LoadResult.Failed failed) {
                log.error("加载玩家数据失败 player={}", playerId, failed.error());
                failEnter(key, playerId, epoch, "加载玩家数据失败");
            } else {
                failEnter(key, playerId, epoch, "玩家不存在");
            }
            return;
        }
        PlayerData data = found.data();
        if (!acceptingEnters) {
            failEnter(key, playerId, epoch, "本节点已停止接收新玩家");
            return;
        }
        if (data.ownerEpoch() != epoch) {
            failEnter(key, playerId, epoch, "owner_epoch 不符（库里 " + data.ownerEpoch() + "，请求 " + epoch + "），进场请求已过期");
            return;
        }
        ScenePlayer previous = playersById.get(playerId);
        if (previous != null && previous.ownerEpoch() > epoch) {
            failEnter(key, playerId, epoch, "本节点已有更新归属的实例（epoch " + previous.ownerEpoch() + "），进场请求已过期");
            return;
        }
        Scene scene = scenes.get(pending.sceneId());
        if (scene == null) {
            failEnter(key, playerId, epoch, "场景不在本节点 scene_id=" + pending.sceneId());
            return;
        }

        // 同一会话上挂着另一个玩家（gate 复用会话换角色）：按正常离开处理，它自己的 epoch 仍有效，写回并释放。
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
                kick(previous, "被新的进场接管（旧实例已失去归属）");
            }
        }

        ScenePlayer player = new ScenePlayer(playerId, nextId(), key, epoch, data.classId(),
                data.gender(), data.appearanceId(), level, skills,
                resolveEnterPosition(scene.configId(), savedConfigId, savedPosition), state, clock.nanoTime());
        if (previous != null && previous.ownerEpoch() == epoch) {
            player.continueLocationSeq(previous.locationSeq());
        }
        try {
            playerInitializer.initialize(player);
        } catch (RuntimeException e) {
            log.error("玩家状态初始化失败 player={}", playerId, e);
            failEnter(key, playerId, epoch, "玩家状态初始化失败");
            return;
        }
        // 脏比对基准是库里此刻的样子（不是刚建出来的内存状态）：接管旧实例、出生点改派等与库不同的情形，第一次到期就会写。
        player.markPersisted(data.asPersisted());
        playersById.put(playerId, player);
        playersBySession.put(key, player);
        playersByEntity.put(player.entity(), player);
        enterScene(player, scene);
        sink.enterResult(key.linkId(), key.sessionId(), playerId, epoch, 0);
        locations.entered(player);
        // 用内存状态拍（接管旧实例时库里那份是旧的）
        snapshots.capture(player.toSave(), PlayerSnapshots.Cause.LOGIN);
        log.info("玩家进场 player={} session={} scene_id={} entity={} epoch={} 接管旧实例={}", playerId, key,
                scene.sceneId(), player.entity(), player.ownerEpoch(), previous != null);
        // 进场（登录 / 重连 / 顶号）之后查组队跟随：异步读，结果回到逻辑线程（team-spec §6.10）
        teamFollow.onEnteredScene(this, player);
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
     * 客户端重试进游戏不必等租约过期。
     */
    private void failEnter(SessionKey key, long playerId, long epoch, String reason) {
        log.warn("进场失败 player={} session={} epoch={} 原因={}", playerId, key, epoch, reason);
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
     */
    public void switchScene(ScenePlayer player, Scene target) {
        Scene from = player.scene();
        if (from == target) {
            return;
        }
        List<ScenePlayer> oldWatchers = from.remove(player);
        publishPopulation(from.configId());
        metrics.aoiLeft(oldWatchers.size());
        broadcast(oldWatchers, destroyMessage(player));
        Vec3 at = target.configId() != from.configId() ? tables.spawnPoint(target.configId()) : player.position();
        player.setPosition(at);
        player.stopMotion();
        player.moveGuard().reset(at, clock.nanoTime());
        enterScene(player, target);
        locations.entered(player);
        log.info("玩家换场景 player={} {} -> {}", player.playerId(), from.sceneId(), target.sceneId());
        teamFollow.onEnteredScene(this, player);
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
            log.debug("离开的会话不在本节点（已离开或已被顶替） player={} session={}", leave.getPlayerId(), key);
            return;
        }
        if (player.playerId() != leave.getPlayerId()) {
            log.warn("离开帧的 player_id 与会话上的玩家不符，忽略 帧={} 会话上={} session={}",
                    leave.getPlayerId(), player.playerId(), key);
            return;
        }
        removePlayer(player, true);
        // 主动离开（LeaveGame）= 干净登出：删位置记录，下次进游戏按首登落点；断线留 30 s 重连租约（同基线断线租约）
        if (leave.getVoluntary()) {
            locations.loggedOut(player);
        } else {
            locations.disconnected(player);
        }
        log.info("玩家离场 player={} session={} 主动={}", player.playerId(), key, leave.getVoluntary());
    }

    /** gate 链路断开：取消这条链路上的进场（释放归属），移除其上全部玩家（看得见它们的人收到 51）并写回。幂等。 */
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
        for (ScenePlayer player : onLink) {
            removePlayer(player, true);
            locations.disconnected(player);
        }
        if (!onLink.isEmpty()) {
            log.info("gate 链路断开，移除其上玩家 link={} 人数={}", linkId, onLink.size());
        }
    }

    // ------------------------------------------------------------------ 归属：接管与失去

    /**
     * login 请本节点让出 (playerId, ownerEpoch)：别的会话要进这个角色（顶号），或上次离开的写回还没落库。
     * 持有正是这个 epoch 的实例写回并释放、通知 gate 踢掉它的会话；还在加载中的进场取消并释放。
     * 别的 epoch（更旧或更新）一律不动：迟到的接管请求碰不到之后新进场的实例。幂等。
     */
    public void onTakeoverRequested(long playerId, long ownerEpoch) {
        ScenePlayer player = playersById.get(playerId);
        if (player != null && player.ownerEpoch() == ownerEpoch) {
            removePlayer(player, true);
            kick(player, "数据归属被新的进游戏接管");
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
     */
    public void onOwnershipLost(Collection<OwnedPlayer> lost) {
        for (OwnedPlayer owned : lost) {
            ScenePlayer player = playersById.get(owned.playerId());
            if (player != null && player.ownerEpoch() == owned.ownerEpoch()) {
                removePlayer(player, false);
                kick(player, "续约发现已失去数据归属");
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
     */
    public SaveRequest requestSave(ScenePlayer player) {
        if (periodicSaveStopped || playersById.get(player.playerId()) != player) {
            return SaveRequest.STOPPED;
        }
        return submitProgress(player);
    }

    private SaveRequest submitProgress(ScenePlayer player) {
        if (player.progressSaveInFlight()) {
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
            case SAVED -> player.markPersisted(snapshot);
            case FAILED -> {
                // 失败不等于没写进去（提交阶段断连时结局未知）：库里是什么不再确定，作废比对基准，下个周期无条件重写。
                player.markPersisted(null);
                log.warn("在线存盘失败，下个周期按最新状态重写 player={} epoch={}", player.playerId(), player.ownerEpoch());
            }
            case FENCED -> {
                // 归属已被夺走（epoch 变了）或已释放：实例写回只会被拒，按续约发现失去归属处理。离场后才回来的不动。
                if (playersById.get(player.playerId()) == player) {
                    removePlayer(player, false);
                    kick(player, "在线存盘被归属围栏拒绝（已失去数据归属）");
                }
            }
        }
    }

    private void kick(ScenePlayer player, String reason) {
        sink.playerKicked(player.session().linkId(), player.session().sessionId(), player.playerId(),
                player.ownerEpoch(), KICKED_BY_ANOTHER);
        log.warn("踢出玩家 player={} session={} epoch={} 原因={}", player.playerId(), player.session(),
                player.ownerEpoch(), reason);
    }

    /**
     * 移出场景（看得见它的人收到 51，离开者什么也收不到）并按需写回，顺序同基线退出流程：
     * 先停下（速度清零，StopMotionForExit）→ 离开场景（51）→ 写回此刻的位置（含 z）。
     * 外推与写回在同一线程上，停下之后到写回之间不会再被推；实例移出后不在任何场景的玩家列表里，此后不再外推，
     * 迟到的移动输入按会话找不到它、直接丢弃，写回的状态不会再变。
     */
    private void removePlayer(ScenePlayer player, boolean save) {
        player.stopMotion();
        List<ScenePlayer> watchers = player.scene().remove(player);
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
     */
    public int shutdown() {
        acceptingEnters = false;
        periodicSaveStopped = true;
        for (PendingEnter pending : pendingEnters.values()) {
            releaseClaim(pending.playerId(), pending.ownerEpoch());
        }
        pendingEnters.clear();
        List<ScenePlayer> all = List.copyOf(playersById.values());
        for (ScenePlayer player : all) {
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

    /** 按 player_id 找本节点上的玩家（已进场的实例；加载中的不算）；没有为 null。 */
    public ScenePlayer playerById(long playerId) {
        return playersById.get(playerId);
    }

    /** 按场景实体号找本节点上的玩家（任意场景）；没有为 null。 */
    public ScenePlayer playerByEntity(long entity) {
        return playersByEntity.get(entity);
    }

    void sendTo(ScenePlayer player, MessageContent content) {
        sink.send(player.session().linkId(), List.of(player.session().sessionId()), content);
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

    private record PendingEnter(SessionKey session, long playerId, long sceneId, long ownerEpoch) {
    }
}
