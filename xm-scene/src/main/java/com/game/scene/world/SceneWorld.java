package com.game.scene.world;

import static com.game.scene.world.SceneMessageIds.push;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneEntry;
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
import com.game.scene.world.PlayerRepository.LoadResult;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *       写回的是离开这一刻的位置（外推在同一线程上，离开之后不会再推）；
 *       没进成的进场（失败、取消、链路断开时还在加载）只释放（{@link PlayerRepository#release}），
 *       否则那份归属要等租约过期才能再被夺取；</li>
 *   <li>login 发来接管请求（{@link #onTakeoverRequested}，别的会话要进这个角色）：持有该 epoch 的实例写回释放，
 *       并通知 gate 把旧会话踢下线（23 {2017}）；</li>
 *   <li>续约报告失去归属（{@link #onOwnershipLost}）：实例的写回只会被围栏拒掉，立即移除（不写回）并踢掉会话。</li>
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

    private final SceneTables tables;
    private final SceneMessageIds ids;
    private final ClientSink sink;
    private final PlayerRepository repository;
    /** 场景号与实体号的发号器（本节点的雪花），必须恒非 0。 */
    private final LongSupplier idGenerator;
    private final SceneClock clock;
    private final SceneMetrics metrics;

    private final Map<Long, Scene> scenes = new LinkedHashMap<>();
    private final Map<Long, ScenePlayer> playersById = new HashMap<>();
    private final Map<SessionKey, ScenePlayer> playersBySession = new HashMap<>();
    /** 已收到 PlayerEnter、正在加载存档的会话。离开 / 断链时删掉即取消，加载回来发现不在就丢弃。 */
    private final Map<SessionKey, PendingEnter> pendingEnters = new HashMap<>();
    /** 帧内视野变化的复用缓冲（只在 {@link #step()} 里用）。 */
    private final ViewChanges viewChanges = new ViewChanges();
    private boolean acceptingEnters = true;
    /** 已跑过的帧数（下一帧的帧号）。偶数帧做属性同步。 */
    private long frame;

    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator, SceneClock clock, SceneMetrics metrics) {
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
        }

        int savedConfigId = data.sceneConfigId();
        Vec3 savedPosition = data.position();
        int level = data.level();
        List<Integer> skills = tables.initialSkills();
        if (previous != null) {
            // 本节点上还挂着这个玩家的旧实例，而库里的归属已经被这次进场夺走——只会发生在旧实例失去归属之后
            // （续约失败、租约过期被强制夺权，续约检测还没来得及移除它）。旧实例的内存状态比库里新（只在离场时写回），
            // 沿用它；它的 epoch 已过期，写回只会被围栏拒绝，所以不写。旧实例挂在别的会话上时，告诉 gate 把那个会话踢掉，
            // 不让它悬空（请求转到 scene 后找不到玩家、收不到任何提示）。
            savedConfigId = previous.scene().configId();
            savedPosition = previous.position();
            level = previous.level();
            skills = previous.skills();
            removePlayer(previous, false);
            if (!previous.session().equals(key)) {
                kick(previous, "被新的进场接管（旧实例已失去归属）");
            }
        }

        ScenePlayer player = new ScenePlayer(playerId, nextId(), key, epoch, data.classId(),
                data.gender(), data.appearanceId(), level, skills,
                resolveEnterPosition(scene.configId(), savedConfigId, savedPosition), clock.nanoTime());
        playersById.put(playerId, player);
        playersBySession.put(key, player);
        enterScene(player, scene);
        sink.enterResult(key.linkId(), key.sessionId(), playerId, epoch, 0);
        log.info("玩家进场 player={} session={} scene_id={} entity={} epoch={} 接管旧实例={}", playerId, key,
                scene.sceneId(), player.entity(), player.ownerEpoch(), previous != null);
    }

    /**
     * 进场落位（基线 EnsureValidEnterLocation，Java 版没有导航网格）：存档坐标属于同一场景配置且不是 (0,0,0) 就沿用；
     * 换地图、新号或坐标无效一律落到目标场景出生点。
     */
    private Vec3 resolveEnterPosition(int targetConfigId, int savedConfigId, Vec3 saved) {
        if (savedConfigId == targetConfigId && saved.isFinite() && !saved.isOrigin()) {
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
     * 会重新带上速度。
     */
    void switchScene(ScenePlayer player, Scene target) {
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
        player.setVelocity(Vec3.ORIGIN);
        player.moveGuard().reset(at, clock.nanoTime());
        enterScene(player, target);
        log.info("玩家换场景 player={} {} -> {}", player.playerId(), from.sceneId(), target.sceneId());
    }

    // ------------------------------------------------------------------ 移动

    /**
     * 134 MoveStart / 132 MoveSync / 131 MoveStop（契约文档 movement §4.3）。应答是 {@code Empty}，任何情况都不回包。
     * <ol>
     *   <li>位置、朝向、速度有任何非有限值：整条静默丢弃，状态不变（基线不查，NaN 会进 Transform 并被广播）；</li>
     *   <li>位置：经 {@link MoveGuard} 位移校验（Java 版比基线严：基线无导航网格时原样接受任何坐标），超额度就沿上报方向截断；</li>
     *   <li>朝向：用请求里的 rotation 整体覆盖（请求没带就是全零，基线同）；</li>
     *   <li>速度：MoveStart / MoveSync 按三维模长截断到 10 m/s，MoveStop 清零；Transform 与 Velocity 都置脏位
     *       （即使值没变，基线同），下一个偶数帧的 66 带上；</li>
     *   <li>裁决位置与上报位置水平偏差 &gt; 0.5 m：给本人发 137，{@code server_velocity} 填<b>处理完这条输入之后</b>的速度
     *       （基线填的是之前的速度；客户端据此重演预测，处理后的才对，契约文档 §9 第 4 条）。</li>
     * </ol>
     * 位置变化在下一帧的视野刷新里重新判定进出视野。
     */
    void applyMove(ScenePlayer player, MoveInput input) {
        if (!input.isFinite()) {
            metrics.move(MoveResult.INVALID);
            log.debug("移动输入含非有限值，丢弃 player={} input_seq={}", player.playerId(), input.inputSeq());
            return;
        }
        Vec3 accepted = player.moveGuard().admit(input.location(), clock.nanoTime());
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
     * 由 {@link SceneTicker} 在场景逻辑线程上调用。没有移动、没有脏字段时只是遍历一遍玩家，不分配。
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

    private void kick(ScenePlayer player, String reason) {
        sink.playerKicked(player.session().linkId(), player.session().sessionId(), player.playerId(),
                player.ownerEpoch(), KICKED_BY_ANOTHER);
        log.warn("踢出玩家 player={} session={} epoch={} 原因={}", player.playerId(), player.session(),
                player.ownerEpoch(), reason);
    }

    /**
     * 移出场景（看得见它的人收到 51，离开者什么也收不到）并按需写回。写回的坐标就是此刻的位置：
     * 外推与写回在同一线程上，移除之后这个实例不会再被推（基线要先 StopMotionForExit 清速度，这里不需要）。
     */
    private void removePlayer(ScenePlayer player, boolean save) {
        List<ScenePlayer> watchers = player.scene().remove(player);
        playersById.remove(player.playerId(), player);
        playersBySession.remove(player.session(), player);
        publishPopulation(player.scene().configId());
        metrics.aoiLeft(watchers.size());
        broadcast(watchers, destroyMessage(player));
        if (save) {
            repository.save(player.toSave());
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
        for (PendingEnter pending : pendingEnters.values()) {
            releaseClaim(pending.playerId(), pending.ownerEpoch());
        }
        pendingEnters.clear();
        List<ScenePlayer> all = List.copyOf(playersById.values());
        for (ScenePlayer player : all) {
            repository.save(player.toSave());
        }
        playersById.clear();
        playersBySession.clear();
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

    void sendTo(ScenePlayer player, MessageContent content) {
        sink.send(player.session().linkId(), List.of(player.session().sessionId()), content);
    }

    /**
     * 发给自己和看得见自己的人（70 SkillUsed）。基线的收件人不含施法者本人，Java 版现有行为含本人
     * （契约文档 AOI §7 第 8 条；改之前要到客户端仓库核对是否会重复播放），这里只把「视野内」换成兴趣列表。
     */
    void broadcastToSelfAndWatchers(ScenePlayer player, MessageContent content) {
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
