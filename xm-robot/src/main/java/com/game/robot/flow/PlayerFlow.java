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
import com.game.robot.client.RedirectTarget;
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
 *
 * <p>批次 5.4（跨 zone 传送与登录期重定向）起，26 成功之后来的可能不是 79 而是 124 RedirectToGate。四个入口的分工：
 * <ul>
 *   <li>{@link #enter}：现有场景用的通用登录。收到 124 即失败，文案写明「收到了 124」与它的目标（不跟随）；</li>
 *   <li>{@link #enterOrRedirect}：同上，但把「收到 124」作为一种结局交回（{@link EnterOutcome.Redirected}）；</li>
 *   <li>{@link #reenter} / {@link #reenterOrRedirect}：<b>严格重登</b>——在给定的连接上登录一个<b>已知</b>的角色，角色列表里没有它就失败，
 *       绝不建角。跟随 124 之后、断线重连回原角色都用它：通用登录在列表为空时会自动建角，把「回不去原角色」盖成假绿。</li>
 * </ul>
 * {@link #tryLogin} / {@link #tryEnterGame} 是不因业务码抛异常的单步，给要核对拒绝码的场景用。
 */
public final class PlayerFlow {

    public static final String STEP_ASSIGN = "分配gate";
    public static final String STEP_CONNECT = "建连握手";
    public static final String STEP_LOGIN = "登录";
    public static final String STEP_CREATE = "建角";
    public static final String STEP_ENTER = "进游戏";
    public static final String STEP_SCENE = "等79";
    /** 进游戏之后来的是 124：从发出进游戏到 124 到达。 */
    public static final String STEP_REDIRECT = "等124";

    /**
     * 进游戏撞上「上一个写者还没释放归属」时 login 回 2005（architecture.md §7 第 2 步：等不到让出即回它）。
     * 断线后立即重连正是这种情形，客户端只能等一会儿再发；最多试这么多次、每次隔 {@link #ENTER_RETRY_DELAY}。
     */
    private static final int ENTER_IN_PROGRESS = LoginErrorTip.login_error.kLoginInProgress_VALUE;
    private static final int ENTER_MAX_ATTEMPTS = 4;
    private static final Duration ENTER_RETRY_DELAY = Duration.ofSeconds(1);
    /** 通用登录：不指定角色，取列表第一项，列表为空就建角。 */
    private static final long ANY_PLAYER = 0;

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

    /**
     * 从零走到「已进场、收到 79」。失败时已建立的连接被关闭。进游戏成功后收到的是 124（这个角色正在别的区，入口区把它送回去）
     * 也算失败：不跟随，文案写明收到了 124 与它的目标。
     */
    public EnteredPlayer enter(String account, Timings timings) throws RobotException {
        GameConnection connection = openViaGateway(timings);
        try {
            // 连接是刚建的：79 从它的第 0 条下行找起（与批次 5.4 之前相同）
            return entered(loginAndEnter(connection, account, ANY_PLAYER, 0, timings));
        } catch (RobotException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /**
     * 同 {@link #enter}，但把「26 成功之后收到 124」作为一种结局交回，由调用方决定跟不跟（批次 5.4 的登录期重定向）。
     * 角色的取法也同 {@link #enter}（列表第一项，为空就建角）：要钉住「登录的是原来那个角色」时核对结局的 {@code playerId()}，
     * 或者自己建连后用 {@link #reenterOrRedirect}。失败时已建立的连接被关闭；两种结局下连接都保持打开，调用方负责关闭。
     */
    public EnterOutcome enterOrRedirect(String account, Timings timings) throws RobotException {
        GameConnection connection = openViaGateway(timings);
        try {
            return loginAndEnter(connection, account, ANY_PLAYER, 0, timings);
        } catch (RobotException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /**
     * 严格重登：在给定的连接上 Login(48) → EnterGame(26，2005 照常重试) → 等 79。48 的角色列表<b>必须含 {@code playerId}</b>，
     * 否则失败；<b>绝不发 CreatePlayer(14)</b>。收到 124 也算失败（文案写明）。
     *
     * <p>连接由调用方给（已握手：跟随 124 得到的新连接，或自己 {@code client.connect(client.assignGate())} 建的），
     * 失败时<b>不</b>关它。79 / 124 从本次调用开始时连接上的位置找起，所以连接上此前的下行不会被当成本次的。
     *
     * @param playerId 要登录的角色，非 0
     */
    public EnteredPlayer reenter(GameConnection connection, String account, long playerId, Timings timings) throws RobotException {
        return entered(reenterOrRedirect(connection, account, playerId, timings));
    }

    /** 同 {@link #reenter}（严格：列表必须含 {@code playerId}、绝不建角），但把「26 成功之后收到 124」作为一种结局交回。 */
    public EnterOutcome reenterOrRedirect(GameConnection connection, String account, long playerId, Timings timings)
            throws RobotException {
        if (playerId == ANY_PLAYER) {
            throw new IllegalArgumentException("严格重登要给出角色 id（0 不是合法的角色 id）");
        }
        // 连接是调用方给的，上面可能已经有下行：只认本次登录开始之后到的 79 / 124
        return loginAndEnter(connection, account, playerId, connection.inbox().size(), timings);
    }

    /**
     * 只发 Login(48)，把应答原样交回：<b>不因业务码抛异常</b>，也不建角。给「在一条特定的连接上以某个账号登录」的步骤用
     * （例：持别人的重定向票据握手之后登录自己的账号）；角色列表用 {@link RoleZone#of} 取。
     *
     * @throws RobotException 传输层失败：超时、连接被关、信封带错误
     */
    public LoginResponse tryLogin(GameConnection connection, String account) throws RobotException {
        // password 路径只填 account + password，auth_type 保持空串（robot 契约 §1.3 第 4 条）。
        return connection.call(client.ids().login(),
                LoginRequest.newBuilder().setAccount(account).setPassword(password).build(),
                LoginResponse.parser(), requestTimeout);
    }

    /**
     * 只发一次 EnterGame(26)，把应答原样交回：<b>不因业务码抛异常</b>（例：持票者不符时取 2011），2005 也不重试，不等 79。
     *
     * @throws RobotException 传输层失败：超时、连接被关、信封带错误
     */
    public EnterGameResponse tryEnterGame(GameConnection connection, long playerId) throws RobotException {
        return connection.call(client.ids().enterGame(), EnterGameRequest.newBuilder().setPlayerId(playerId).build(),
                EnterGameResponse.parser(), requestTimeout);
    }

    /** 分配 gate → 建连握手。 */
    private GameConnection openViaGateway(Timings timings) throws RobotException {
        long start = System.nanoTime();
        GateAssignment gate = client.assignGate();
        timings.record(STEP_ASSIGN, start);
        start = System.nanoTime();
        GameConnection connection = client.connect(gate);
        timings.record(STEP_CONNECT, start);
        return connection;
    }

    /** 只接受「进了场」；收到 124 的结局在这里变成失败。 */
    private static EnteredPlayer entered(EnterOutcome outcome) throws RobotException {
        return switch (outcome) {
            case EnterOutcome.Entered e -> e.player();
            case EnterOutcome.Redirected r -> throw new RobotException("进游戏（26）成功，但等 79 时收到了 124 RedirectToGate（目标 "
                    + r.target().endpoint() + "）：这个角色正在别的区，入口区把它送回去了（登录期重定向），本流程不跟随 124。"
                    + "同一批账号换区重跑时，先等上一次留下的重连租约（30 s）过期，或换 --prefix / --run-tag");
        };
    }

    /**
     * 登录 → 定角色 → 进游戏 → 等 79 或 124。
     *
     * @param strictPlayerId {@link #ANY_PLAYER} = 通用登录（列表第一项，为空就建角）；否则严格：列表必须含它，绝不建角
     * @param mark           从连接上第几条下行起找 79 / 124：79 可以早于 26 的应答到达，所以要取登录<b>之前</b>的位置
     */
    private EnterOutcome loginAndEnter(GameConnection connection, String account, long strictPlayerId, int mark, Timings timings)
            throws RobotException {
        MessageIds ids = client.ids();

        long start = System.nanoTime();
        LoginResponse login = tryLogin(connection, account);
        requireNoError("登录（48）", login.hasErrorMessage(), login.getErrorMessage());
        timings.record(STEP_LOGIN, start);

        List<AccountSimplePlayerWrapper> players = login.getPlayersList();
        boolean created = false;
        long playerId;
        if (strictPlayerId != ANY_PLAYER) {
            List<RoleZone> listed = RoleZone.of(players);
            if (listed.stream().noneMatch(role -> role.playerId() == strictPlayerId)) {
                throw new RobotException("严格重登：登录（48）的角色列表里没有 player_id=" + Long.toUnsignedString(strictPlayerId)
                        + "（列表 " + listed + "）；不建角——建出来的是另一个角色，会把「回不去原角色」盖成通过");
            }
            playerId = strictPlayerId;
        } else {
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
            playerId = players.get(0).getPlayer().getPlayerId();
            if (playerId == 0) {
                throw new RobotException("角色列表 players[0].player.player_id 为 0");
            }
        }
        List<RoleZone> roles = RoleZone.of(players);

        long enterStart = System.nanoTime();
        enterGame(connection, playerId);
        timings.record(STEP_ENTER, enterStart);

        Received first = awaitEnterSceneOrRedirect(connection, mark, enterStart);
        // 从发出进游戏到 79 / 124 到达；79 早于 26 应答到达时差值可能为负，按 0 记。
        long waitedMillis = Math.max(0, (first.receivedNanos() - enterStart) / 1_000_000);
        if (first.messageId() == client.redirectToGateId()) {
            timings.recordMillis(STEP_REDIRECT, waitedMillis);
            return new EnterOutcome.Redirected(connection, playerId, RedirectTarget.parse(first), first, roles);
        }
        timings.recordMillis(STEP_SCENE, waitedMillis);
        return new EnterOutcome.Entered(new EnteredPlayer(account, playerId, created, sceneInfoOf(first), connection,
                connection.endpoint(), roles));
    }

    /** EnterGame(26)：回的 player_id 必须等于请求（robot 契约 §4 步骤 D、清单第 8 条）；2005 按上面的约定等一会儿重试。 */
    private void enterGame(GameConnection connection, long playerId) throws RobotException {
        for (int attempt = 1; ; attempt++) {
            EnterGameResponse response = tryEnterGame(connection, playerId);
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
     * 等 79（可以早于 26 到达，所以从 {@code fromIndex}——登录之前的位置——找起）或 124（批次 5.4：登录期重定向下 26 成功、
     * 不会再有 79，干等到超时只看得到「没有收到 79」）。先到的那一条算数。79 的内容由 {@link #sceneInfoOf} 校验。
     */
    private Received awaitEnterSceneOrRedirect(GameConnection connection, int fromIndex, long enterStartNanos) throws RobotException {
        int enterScene = client.ids().notifyEnterScene();
        int redirect = client.redirectToGateId();
        Duration remaining = Duration.ofNanos(Math.max(0, enterStartNanos + enterSceneTimeout.toNanos() - System.nanoTime()));
        Optional<Received> first = connection.await(fromIndex, r -> r.messageId() == enterScene || r.messageId() == redirect, remaining);
        if (first.isEmpty()) {
            throw new RobotException("发出进游戏后 " + enterSceneTimeout.toMillis() + " ms 内没有收到 79 NotifyEnterScene"
                    + connection.describeSince(fromIndex));
        }
        return first.get();
    }

    /**
     * 79 必须带 {@code scene_info}，且 {@code scene_config_id}、{@code scene_id} 非 0（robot 契约 §4 步骤 E；scene 契约
     * 「Java 必须做到」第 1 条）。
     */
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
