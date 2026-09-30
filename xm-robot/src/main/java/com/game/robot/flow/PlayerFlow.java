package com.game.robot.flow;

import com.game.proto.ActorCreateS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.CreatePlayerRequest;
import com.game.proto.login.CreatePlayerResponse;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.GateAssignment;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.table.LoginErrorTip;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 登录进场主链路（robot 契约 §0 / §4 步骤 A–E）：分配 gate → 建连握手 → Login(48) → 没有角色时 CreatePlayer(14) →
 * EnterGame(26) → 等 NotifyEnterScene(79)。每一步都按契约校验应答，不符即抛 {@link RobotException}（消息写明哪一步、依据哪条）。
 * 无状态，可被多个账号线程共用。
 */
public final class PlayerFlow {

    public static final String STEP_ASSIGN = "分配gate";
    public static final String STEP_CONNECT = "建连握手";
    public static final String STEP_LOGIN = "登录";
    public static final String STEP_CREATE = "建角";
    public static final String STEP_ENTER = "进游戏";
    public static final String STEP_SCENE = "等79";

    /**
     * 进游戏撞上「上一个写者还没释放归属」时 login 回 2005（architecture.md §7 第 2 步：等不到让出即回它）。
     * 断线后立即重连正是这种情形，客户端只能等一会儿再发；最多试这么多次、每次隔 {@link #ENTER_RETRY_DELAY}。
     */
    private static final int ENTER_IN_PROGRESS = LoginErrorTip.login_error.kLoginInProgress_VALUE;
    private static final int ENTER_MAX_ATTEMPTS = 4;
    private static final Duration ENTER_RETRY_DELAY = Duration.ofSeconds(1);

    private final RobotClient client;
    private final String password;
    private final Duration requestTimeout;
    private final Duration enterSceneTimeout;

    /**
     * @param password          开发口令（与 xm-login 的 {@code XM_LOGIN_DEV_PASSWORD} 相同），只放进 LoginRequest，不打印
     * @param requestTimeout    Login / CreatePlayer / EnterGame / ListSkills 各自等应答的上限（Go robot 15s）
     * @param enterSceneTimeout 从发出 EnterGame 起等 79 的上限
     */
    public PlayerFlow(RobotClient client, String password, Duration requestTimeout, Duration enterSceneTimeout) {
        this.client = client;
        this.password = password;
        this.requestTimeout = requestTimeout;
        this.enterSceneTimeout = enterSceneTimeout;
    }

    /** 从零走到「已进场、收到 79」。失败时已建立的连接被关闭。 */
    public EnteredPlayer enter(String account, Timings timings) throws RobotException {
        long start = System.nanoTime();
        GateAssignment gate = client.assignGate();
        timings.record(STEP_ASSIGN, start);
        start = System.nanoTime();
        GameConnection connection = client.connect(gate);
        timings.record(STEP_CONNECT, start);
        try {
            return enterOn(connection, account, timings);
        } catch (RobotException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private EnteredPlayer enterOn(GameConnection connection, String account, Timings timings) throws RobotException {
        MessageIds ids = client.ids();

        long start = System.nanoTime();
        // password 路径只填 account + password，auth_type 保持空串（robot 契约 §1.3 第 4 条）。
        LoginResponse login = connection.call(ids.login(),
                LoginRequest.newBuilder().setAccount(account).setPassword(password).build(),
                LoginResponse.parser(), requestTimeout);
        requireNoError("登录（48）", login.hasErrorMessage(), login.getErrorMessage());
        timings.record(STEP_LOGIN, start);

        List<AccountSimplePlayerWrapper> players = login.getPlayersList();
        boolean created = false;
        if (players.isEmpty()) {
            start = System.nanoTime();
            // 全默认值的空请求：服务端决定职业 / 性别并生成名字（robot 契约 §4 步骤 C）。
            CreatePlayerResponse create = connection.call(ids.createPlayer(), CreatePlayerRequest.getDefaultInstance(),
                    CreatePlayerResponse.parser(), requestTimeout);
            requireNoError("建角（14）", create.hasErrorMessage(), create.getErrorMessage());
            if (create.getPlayersCount() == 0) {
                throw new RobotException("建角（14）成功但 players 为空（robot 契约 §4 步骤 C：no players after create）");
            }
            players = create.getPlayersList();
            created = true;
            timings.record(STEP_CREATE, start);
        }
        // 与 Go robot 相同取 players[0]（robot 契约 §4 步骤 B / C）。
        long playerId = players.get(0).getPlayer().getPlayerId();
        if (playerId == 0) {
            throw new RobotException("角色列表 players[0].player.player_id 为 0");
        }

        long enterStart = System.nanoTime();
        enterGame(connection, playerId);
        timings.record(STEP_ENTER, enterStart);

        Received notify = awaitEnterScene(connection, enterStart);
        // 从发出进游戏到 79 到达；79 早于 26 应答到达时差值可能为负，按 0 记。
        timings.recordMillis(STEP_SCENE, Math.max(0, (notify.receivedNanos() - enterStart) / 1_000_000));
        return new EnteredPlayer(account, playerId, created, sceneInfoOf(notify), connection);
    }

    /** EnterGame(26)：回的 player_id 必须等于请求（robot 契约 §4 步骤 D、清单第 8 条）；2005 按上面的约定等一会儿重试。 */
    private void enterGame(GameConnection connection, long playerId) throws RobotException {
        EnterGameRequest request = EnterGameRequest.newBuilder().setPlayerId(playerId).build();
        for (int attempt = 1; ; attempt++) {
            EnterGameResponse response = connection.call(client.ids().enterGame(), request,
                    EnterGameResponse.parser(), requestTimeout);
            if (response.hasErrorMessage() && response.getErrorMessage().getId() == ENTER_IN_PROGRESS
                    && attempt < ENTER_MAX_ATTEMPTS) {
                sleep(ENTER_RETRY_DELAY);
                continue;
            }
            requireNoError("进游戏（26）", response.hasErrorMessage(), response.getErrorMessage());
            if (response.getPlayerId() != playerId) {
                throw new RobotException("进游戏（26）应答 player_id=" + response.getPlayerId() + "，应等于请求的 " + playerId
                        + "（robot 契约 §4 步骤 D）");
            }
            return;
        }
    }

    /**
     * 等 79（可以早于 26 到达，所以从连接上第一条下行找起）。必须带 {@code scene_info}，且 {@code scene_config_id}、
     * {@code scene_id} 非 0（robot 契约 §4 步骤 E；scene 契约「Java 必须做到」第 1 条）。
     */
    private Received awaitEnterScene(GameConnection connection, long enterStartNanos) throws RobotException {
        int id = client.ids().notifyEnterScene();
        Duration remaining = Duration.ofNanos(Math.max(0, enterStartNanos + enterSceneTimeout.toNanos() - System.nanoTime()));
        Optional<Received> notify = connection.await(0, r -> r.messageId() == id, remaining);
        if (notify.isEmpty()) {
            throw new RobotException("发出进游戏后 " + enterSceneTimeout.toMillis() + " ms 内没有收到 79 NotifyEnterScene"
                    + connection.describeSince(0));
        }
        return notify.get();
    }

    private static SceneInfoComp sceneInfoOf(Received notify) throws RobotException {
        EnterSceneS2C enterScene = notify.parse(EnterSceneS2C.parser());
        if (!enterScene.hasSceneInfo()) {
            throw new RobotException("79 NotifyEnterScene 不带 scene_info（Go robot 收到这种 79 不会发出就绪信号）");
        }
        SceneInfoComp info = enterScene.getSceneInfo();
        if (info.getSceneConfigId() == 0 || info.getSceneId() == 0) {
            throw new RobotException("79 的 scene_info 不完整：scene_config_id=" + info.getSceneConfigId()
                    + " scene_id=" + info.getSceneId());
        }
        return info;
    }

    /**
     * 等进场者自己的 21（{@code guid == player_id}，scene 契约 §3.2：紧跟 79 发出）。
     * 校验 {@code entity ≠ 0}、带 {@code transform.location}、{@code actor_type=PLAYER}。
     */
    public ActorCreateS2C awaitSelfActor(EnteredPlayer player, Duration timeout) throws RobotException {
        GameConnection connection = player.connection();
        int id = client.ids().notifyActorCreate();
        Optional<Received> self = connection.await(0, r -> {
            if (r.messageId() != id) {
                return false;
            }
            ActorCreateS2C actor = r.parseOrNull(ActorCreateS2C.parser());
            return actor != null && actor.getGuid() == player.playerId();
        }, timeout);
        if (self.isEmpty()) {
            throw new RobotException(player.account() + "：" + timeout.toMillis() + " ms 内没有收到自己的 21 NotifyActorCreate"
                    + connection.describeSince(0));
        }
        ActorCreateS2C actor = self.get().parse(ActorCreateS2C.parser());
        if (actor.getEntity() == 0) {
            throw new RobotException(player.account() + "：自己的 21 entity=0（scene 契约 §7 第 6 条：实体号必须非 0）");
        }
        if (!actor.hasTransform() || !actor.getTransform().hasLocation()) {
            throw new RobotException(player.account() + "：自己的 21 不带 transform.location（scene 契约 §3.2）");
        }
        return actor;
    }

    /** ListSkills(77)。只负责收应答，内容由场景判定。 */
    public ListSkillsResponse listSkills(EnteredPlayer player) throws RobotException {
        return player.connection().call(client.ids().listSkills(), ListSkillsRequest.getDefaultInstance(),
                ListSkillsResponse.parser(), requestTimeout);
    }

    /**
     * 登录阶段的应答以 {@code error_message} <b>是否存在</b>判失败（robot 契约 §3.4 第 3 条：Go 判 {@code ErrorMessage != nil}），
     * 所以成功时带一个 id=0 的空 tip 也是违约。
     */
    static void requireNoError(String step, boolean hasError, TipInfoMessage error) throws RobotException {
        if (!hasError) {
            return;
        }
        if (error.getId() == 0) {
            throw new RobotException(step + " 应答带了 id=0 的 error_message：成功时不得设置该字段，Go robot 会判为失败"
                    + "（robot 契约 §3.4 第 3 条）");
        }
        throw new RobotException(step + " 被拒：error_message.id=" + error.getId()
                + (error.getParametersCount() > 0 ? " parameters=" + error.getParametersList() : ""));
    }

    private static void sleep(Duration duration) throws RobotException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
