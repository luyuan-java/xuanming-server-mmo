package com.game.scene.world;

import static com.game.scene.world.SceneMessageIds.push;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneEntry;
import com.game.proto.MessageContent;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.EnterSceneS2C;
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
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景节点上的全部场景与玩家状态，以及进场 / 离场 / 换场景的规则。
 *
 * <p><b>线程模型</b>：本类不加锁，所有方法只能在场景逻辑线程上调用（包括 {@link PlayerRepository#load} 的回调）。
 * 阻塞 I/O 全在 {@link PlayerRepository} 实现里，出站全经 {@link ClientSink}，所以本类可以脱离 Netty / MySQL 单测。
 *
 * <p><b>进场下行顺序</b>（契约文档 {@code docs/reference/mmorpg-client-contract-scene.md} §3）：
 * 79 NotifyEnterScene → 21 NotifyActorCreate(自己) → 47 NotifyActorListCreate(视野内他人，非空才发)
 * → 给视野内他人补发 21(新进场者)，最后给 gate 回 {@code PlayerEnterResult}。与基线的两处差异是有意修复：
 * 每次进场成功都发 79 / 21（基线同场景幂等早退不发）；老观察者会收到新进场者的 21（基线收不到）。
 * 进场失败只回 {@code PlayerEnterResult{tip=3023}}，给客户端的 23 由 gate 发（见 {@code failEnter}）。
 * 进场结果总是回显这次进场的 owner_epoch，gate 据此丢弃同一会话上更早一次进场的迟到结果。
 *
 * <p><b>视野</b>：同场景内三维距离不超过 {@link #VIEW_RADIUS}，逐人比较。首批没有移动，玩家数量小，
 * 不做格子 AOI；上移动同步时要换成格子索引。
 *
 * <p><b>归属</b>（xm-player-store 的 PlayerStore「归属协议」）：
 * <ul>
 *   <li>进场要求库里的 {@code owner_epoch} 等于 gate 带来的 epoch（login 进游戏时夺得），且不低于本节点上同一玩家
 *       现有实例的 epoch；</li>
 *   <li>玩家离开（主动 / 断线 / 链路断开 / 停服）一律「最终写回并释放」（{@link PlayerRepository#save}）；
 *       没进成的进场（失败、取消、链路断开时还在加载）只释放（{@link PlayerRepository#release}），
 *       否则那份归属要等租约过期才能再被夺取；</li>
 *   <li>login 发来接管请求（{@link #onTakeoverRequested}，别的会话要进这个角色）：持有该 epoch 的实例写回释放，
 *       并通知 gate 把旧会话踢下线（23 {2017}）；</li>
 *   <li>续约报告失去归属（{@link #onOwnershipLost}）：实例的写回只会被围栏拒掉，立即移除（不写回）并踢掉会话。</li>
 * </ul>
 *
 * <p><b>技能</b>：xm-player-store 目前没有技能列，每次进场都按配表发放初始技能（等同基线「新号」），
 * 老号存档里的技能要等存储补列后再接。
 */
public final class SceneWorld {

    private static final Logger log = LoggerFactory.getLogger(SceneWorld.class);

    /** 基线 {@code ViewRadius.radius = 10}（player_lifecycle.cpp InitPlayerFromAllData）。 */
    public static final double VIEW_RADIUS = 10.0;

    private static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    /** 被别的会话接管 / 失去归属时推给旧会话的 tip（基线顶号同码，经 23 推送，本里程碑不发 34）。 */
    static final int KICKED_BY_ANOTHER = LoginErrorTip.login_error.kLoginBeKickByAnOtherAccount_VALUE;

    private final SceneTables tables;
    private final SceneMessageIds ids;
    private final ClientSink sink;
    private final PlayerRepository repository;
    /** 场景号与实体号的发号器（本节点的雪花），必须恒非 0。 */
    private final LongSupplier idGenerator;

    private final Map<Long, Scene> scenes = new LinkedHashMap<>();
    private final Map<Long, ScenePlayer> playersById = new HashMap<>();
    private final Map<SessionKey, ScenePlayer> playersBySession = new HashMap<>();
    /** 已收到 PlayerEnter、正在加载存档的会话。离开 / 断链时删掉即取消，加载回来发现不在就丢弃。 */
    private final Map<SessionKey, PendingEnter> pendingEnters = new HashMap<>();
    private boolean acceptingEnters = true;

    public SceneWorld(SceneTables tables, SceneMessageIds ids, ClientSink sink, PlayerRepository repository,
                      LongSupplier idGenerator) {
        this.tables = tables;
        this.ids = ids;
        this.sink = sink;
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------ 场景

    public Scene createScene(int configId) {
        Scene scene = new Scene(nextId(), configId);
        scenes.put(scene.sceneId(), scene);
        log.info("创建场景 scene_id={} scene_config_id={}", scene.sceneId(), configId);
        return scene;
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
                resolveEnterPosition(scene.configId(), savedConfigId, savedPosition));
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

    private void enterScene(ScenePlayer player, Scene scene) {
        player.setScene(scene);
        List<ScenePlayer> others = viewersOf(player);
        scene.add(player);

        ActorCreateS2C self = player.toActorCreate();
        sendTo(player, push(ids.notifyEnterScene(), EnterSceneS2C.newBuilder().setSceneInfo(scene.info()).build()));
        sendTo(player, push(ids.notifyActorCreate(), self));
        if (others.isEmpty()) {
            return;
        }
        ActorListCreateS2C.Builder list = ActorListCreateS2C.newBuilder();
        for (ScenePlayer other : others) {
            list.addActorList(other.toActorCreate());
        }
        sendTo(player, push(ids.notifyActorListCreate(), list.build()));
        broadcast(others, push(ids.notifyActorCreate(), self));
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

    /** 离开当前场景（旧视野收到 51）再进入目标场景（79 / 21 / 47 / 21）。换地图落到出生点，同图换线保留坐标。 */
    void switchScene(ScenePlayer player, Scene target) {
        Scene from = player.scene();
        if (from == target) {
            return;
        }
        List<ScenePlayer> oldViewers = viewersOf(player);
        from.remove(player);
        broadcast(oldViewers, destroyMessage(player));
        if (target.configId() != from.configId()) {
            player.setPosition(tables.spawnPoint(target.configId()));
        }
        enterScene(player, target);
        log.info("玩家换场景 player={} {} -> {}", player.playerId(), from.sceneId(), target.sceneId());
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

    /** gate 链路断开：取消这条链路上的进场（释放归属），移除其上全部玩家（视野内他人收到 51）并写回。幂等。 */
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

    private void removePlayer(ScenePlayer player, boolean save) {
        List<ScenePlayer> viewers = viewersOf(player);
        player.scene().remove(player);
        playersById.remove(player.playerId(), player);
        playersBySession.remove(player.session(), player);
        broadcast(viewers, destroyMessage(player));
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
     * 返回写回人数。
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
        scenes.values().forEach(Scene::clear);
        return all.size();
    }

    // ------------------------------------------------------------------ 查询与下行（包内给请求处理用）

    ScenePlayer playerBySession(SessionKey key) {
        return playersBySession.get(key);
    }

    /** 同场景内、视野半径内的其他玩家，按进场顺序。 */
    List<ScenePlayer> viewersOf(ScenePlayer player) {
        List<ScenePlayer> viewers = new ArrayList<>();
        for (ScenePlayer other : player.scene().players()) {
            if (other != player && other.position().within(player.position(), VIEW_RADIUS)) {
                viewers.add(other);
            }
        }
        return viewers;
    }

    void sendTo(ScenePlayer player, MessageContent content) {
        sink.send(player.session().linkId(), List.of(player.session().sessionId()), content);
    }

    /** 发给自己和视野内他人（基线 BroadcastMessageToVisiblePlayers 的收件人包括施法者自己）。 */
    void broadcastToSelfAndViewers(ScenePlayer player, MessageContent content) {
        List<ScenePlayer> recipients = new ArrayList<>();
        recipients.add(player);
        recipients.addAll(viewersOf(player));
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
