package com.game.robot.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.proto.RedirectToGateNotify;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LoginResponse;
import com.game.robot.client.FakeGate;
import com.game.robot.client.FakeGateway;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RedirectTarget;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.FakeLoginWorld.OnEnter;
import com.game.table.LoginErrorTip;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 批次 5.4 给登录流程加的入口，对着带握手的假 gate 与假 gateway：进场结果带 gate 端点与角色的归属区；26 成功之后来的是 124 时
 * 通用登录明确失败、{@code enterOrRedirect} 把它作为一种结局交回；严格重登不建角、不取列表第一项、只认本次登录之后的 79；
 * 不因业务码抛异常的单步。等 79 的上限故意给得很大（30 s）：靠「干等到超时」才结束的实现会被每条用例 15 s 的上限判失败。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class PlayerFlowRedirectTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final MessageIds IDS = MessageIds.resolve(REGISTRY);
    private static final int REDIRECT = RedirectTarget.messageId(REGISTRY);
    private static final Duration SHORT = Duration.ofSeconds(3);
    private static final Duration LONG_SCENE_WAIT = Duration.ofSeconds(30);
    private static final String ACCOUNT = "robot_java_tvt1_a";
    private static final int HOME_ZONE = 1;
    private static final int KICKED = LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE;
    /** 假 124：目标是一个随便的地址（这里不跟随，只看解出来的内容）。 */
    private static final RedirectToGateNotify NOTIFY = RedirectToGateNotify.newBuilder().setTargetIp("10.0.0.7").setTargetPort(11010)
            .setTokenPayload(ByteString.copyFromUtf8("ticket-bytes")).setTokenSignature(ByteString.copyFromUtf8("ab".repeat(32)))
            .setTokenDeadline(4_000_000_000L).build();

    private final FakeGate.Events events = new FakeGate.Events();
    private final FakeLoginWorld world = new FakeLoginWorld(IDS, REDIRECT);
    private FakeGate gate;
    private FakeGateway gateway;
    private RobotClient client;
    private PlayerFlow flow;

    @BeforeEach
    void start() throws IOException {
        world.redirect = NOTIFY;
        gate = new FakeGate("home", events, world);
        gateway = new FakeGateway().assign(HOME_ZONE, gate.endpoint(), "login-token", "login-sig");
        client = new RobotClient(gateway.baseUrl(), HOME_ZONE, IDS, REDIRECT, SHORT, SHORT);
        flow = new PlayerFlow(client, "dev-secret", SHORT, LONG_SCENE_WAIT);
    }

    @AfterEach
    void stop() {
        client.close();
        gateway.close();
        gate.close();
    }

    /** 自己建连（assign-gate → TCP → 握手），给严格重登用。 */
    private GameConnection connect() throws RobotException {
        return client.connect(client.assignGate());
    }

    // ---------------------------------------------------------------- 进场结果的新分量

    @Test
    void 通用登录_进场结果带上实际连的gate端点与角色列表的归属区() throws Exception {
        long role = world.seed(ACCOUNT, HOME_ZONE);

        EnteredPlayer player = flow.enter(ACCOUNT, new Timings());
        try {
            assertThat(player.playerId()).isEqualTo(role);
            assertThat(player.created()).isFalse();
            assertThat(player.gate()).isEqualTo(gate.endpoint()).isEqualTo(player.connection().endpoint());
            assertThat(player.roles()).containsExactly(new RoleZone(role, HOME_ZONE));
            assertThat(player.homeZoneId()).isEqualTo(HOME_ZONE);
            assertThat(player.sceneInfo().getSceneId()).isEqualTo(FakeLoginWorld.SCENE_ID);
            assertThat(gate.sessions().get(0).verify().getPayload().toStringUtf8()).as("assign-gate 给了令牌就握手").isEqualTo("login-token");
            assertThat(gate.received(IDS.createPlayer())).isZero();
        } finally {
            player.connection().close();
        }
    }

    @Test
    void 通用登录_新号建角_角色列表取建角应答_归属区是建角时定的那个区() throws Exception {
        world.createZone = 2;

        EnteredPlayer player = flow.enter(ACCOUNT, new Timings());
        try {
            assertThat(player.created()).isTrue();
            assertThat(gate.received(IDS.createPlayer())).isEqualTo(1);
            assertThat(player.roles()).containsExactly(new RoleZone(player.playerId(), 2));
            assertThat(player.homeZoneId()).as("建角应答里本人那一项的 zone_id").isEqualTo(2);
            assertThat(world.lastEnterPlayer).isEqualTo(player.playerId());
        } finally {
            player.connection().close();
        }
    }

    @Test
    void 通用登录_79早于26的应答到达也算进场() throws Exception {
        world.seed(ACCOUNT, HOME_ZONE);
        world.onEnter = OnEnter.SCENE_BEFORE_REPLY;

        EnteredPlayer player = flow.enter(ACCOUNT, new Timings());
        try {
            List<Received> inbox = player.connection().inbox().snapshot(0);
            int scene = indexOf(inbox, IDS.notifyEnterScene());
            int reply = indexOf(inbox, IDS.enterGame());
            assertThat(scene).as("79 先到").isLessThan(reply);
            assertThat(player.sceneInfo().getSceneConfigId()).isEqualTo(FakeLoginWorld.SCENE_CONFIG);
        } finally {
            player.connection().close();
        }
    }

    // ---------------------------------------------------------------- 26 成功之后来的是 124

    @Test
    void 通用登录_进游戏成功后来的是124_立即失败_文案写明收到了124与目标_连接被关() throws Exception {
        world.seed(ACCOUNT, HOME_ZONE);
        world.onEnter = OnEnter.REDIRECT;

        assertThatThrownBy(() -> flow.enter(ACCOUNT, new Timings()))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("进游戏（26）成功")
                .hasMessageContaining("收到了 124 RedirectToGate（目标 10.0.0.7:11010）")
                .hasMessageContaining("不跟随 124")
                .hasMessageContaining("--run-tag");

        assertThat(events.await("home closed#0", Duration.ofSeconds(5))).as("失败时通用登录把自己建的连接关掉").isTrue();
        assertThat(gate.received(IDS.enterGame())).as("26 只发了一次：124 不是可重试的拒绝").isEqualTo(1);
    }

    @Test
    void enterOrRedirect_把124作为一种结局交回_连接保持_26的应答在124之前() throws Exception {
        long role = world.seed(ACCOUNT, HOME_ZONE);
        world.onEnter = OnEnter.REDIRECT;
        Timings timings = new Timings();

        EnterOutcome outcome = flow.enterOrRedirect(ACCOUNT, timings);

        assertThat(outcome).isInstanceOf(EnterOutcome.Redirected.class);
        EnterOutcome.Redirected redirected = (EnterOutcome.Redirected) outcome;
        try {
            assertThat(redirected.playerId()).isEqualTo(role);
            assertThat(redirected.connection().isOpen()).as("跟不跟由调用方定，连接不关").isTrue();
            assertThat(redirected.target().host()).isEqualTo("10.0.0.7");
            assertThat(redirected.target().port()).isEqualTo(11010);
            assertThat(redirected.target().tokenPayload()).isEqualTo(NOTIFY.getTokenPayload());
            assertThat(redirected.target().tokenSignature()).isEqualTo(NOTIFY.getTokenSignature());
            assertThat(redirected.target().tokenDeadline()).isEqualTo(4_000_000_000L);
            assertThat(redirected.roles()).containsExactly(new RoleZone(role, HOME_ZONE));

            List<Received> inbox = redirected.connection().inbox().snapshot(0);
            assertThat(redirected.frame().messageId()).isEqualTo(REDIRECT);
            assertThat(redirected.frame().requestId()).as("124 是推送").isZero();
            assertThat(redirected.frame().index()).as("26 的应答在前、124 在后").isGreaterThan(indexOf(inbox, IDS.enterGame()));
            assertThat(inbox).as("没有 79").noneMatch(r -> r.messageId() == IDS.notifyEnterScene());
            assertThat(timings.get(PlayerFlow.STEP_REDIRECT)).isNotNull();
            assertThat(timings.get(PlayerFlow.STEP_SCENE)).isNull();
        } finally {
            redirected.connection().close();
        }
    }

    @Test
    void enterOrRedirect_正常进场时交回进场结果() throws Exception {
        long role = world.seed(ACCOUNT, HOME_ZONE);
        Timings timings = new Timings();

        EnterOutcome outcome = flow.enterOrRedirect(ACCOUNT, timings);

        assertThat(outcome).isInstanceOf(EnterOutcome.Entered.class);
        try {
            assertThat(outcome.playerId()).isEqualTo(role);
            assertThat(((EnterOutcome.Entered) outcome).player().gate()).isEqualTo(gate.endpoint());
            assertThat(outcome.connection()).isSameAs(((EnterOutcome.Entered) outcome).player().connection());
            assertThat(timings.get(PlayerFlow.STEP_SCENE)).isNotNull();
            assertThat(timings.get(PlayerFlow.STEP_REDIRECT)).isNull();
        } finally {
            outcome.connection().close();
        }
    }

    // ---------------------------------------------------------------- 严格重登

    @Test
    void 严格重登_进的是指定的角色_不是列表第一项_不建角() throws Exception {
        long other = world.seed(ACCOUNT, 3);
        long role = world.seed(ACCOUNT, HOME_ZONE);

        try (GameConnection connection = connect()) {
            EnteredPlayer player = flow.reenter(connection, ACCOUNT, role, new Timings());

            assertThat(player.playerId()).isEqualTo(role);
            assertThat(world.lastEnterPlayer).as("26 请求里的 player_id").isEqualTo(role).isNotEqualTo(other);
            assertThat(player.created()).isFalse();
            assertThat(player.connection()).isSameAs(connection);
            assertThat(player.gate()).isEqualTo(gate.endpoint());
            assertThat(player.roles()).containsExactly(new RoleZone(other, 3), new RoleZone(role, HOME_ZONE));
            assertThat(player.homeZoneId()).as("本人那一项的归属区，不是第一项的").isEqualTo(HOME_ZONE);
            assertThat(gate.received(IDS.createPlayer())).isZero();
        }
    }

    @Test
    void 严格重登_角色列表里没有这个角色_失败_既不建角也不进游戏_连接留给调用方() throws Exception {
        long other = world.seed(ACCOUNT, HOME_ZONE);
        long missing = other + 1000;

        try (GameConnection connection = connect()) {
            assertThatThrownBy(() -> flow.reenter(connection, ACCOUNT, missing, new Timings()))
                    .isInstanceOf(RobotException.class)
                    .hasMessageContaining("严格重登")
                    .hasMessageContaining("player_id=" + Long.toUnsignedString(missing))
                    .hasMessageContaining("列表 [" + Long.toUnsignedString(other) + "@1]")
                    .hasMessageContaining("不建角");

            assertThat(gate.received(IDS.login())).isEqualTo(1);
            assertThat(gate.received(IDS.createPlayer())).isZero();
            assertThat(gate.received(IDS.enterGame())).isZero();
            assertThat(connection.isOpen()).as("连接是调用方给的，失败时不关").isTrue();
        }
    }

    @Test
    void 严格重登_角色列表为空_失败_绝不建角() throws Exception {
        // 通用登录在这里会发 14 造一个新角色、然后进场成功——正是「回不去原角色却显示通过」的假绿
        try (GameConnection connection = connect()) {
            assertThatThrownBy(() -> flow.reenter(connection, ACCOUNT, 0x8000_0000_0000_0777L, new Timings()))
                    .isInstanceOf(RobotException.class).hasMessageContaining("严格重登").hasMessageContaining("列表 []");

            assertThat(gate.received(IDS.createPlayer())).isZero();
            assertThat(gate.received(IDS.enterGame())).isZero();
        }
        assertThatThrownBy(() -> flow.reenter(null, ACCOUNT, 0, new Timings())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 严格重登_只认本次登录之后到的79与124_连接上早先的79不算() throws Exception {
        long role = world.seed(ACCOUNT, HOME_ZONE);

        try (GameConnection connection = connect()) {
            flow.reenter(connection, ACCOUNT, role, new Timings());
            assertThat(connection.inbox().snapshot(0)).filteredOn(r -> r.messageId() == IDS.notifyEnterScene()).hasSize(1);

            // 同一条连接上再登一次，这次 26 之后来的是 124：早先那条 79 不能被当成这次的进场
            world.onEnter = OnEnter.REDIRECT;
            EnterOutcome second = flow.reenterOrRedirect(connection, ACCOUNT, role, new Timings());

            assertThat(second).isInstanceOf(EnterOutcome.Redirected.class);
            assertThat(((EnterOutcome.Redirected) second).frame().index())
                    .isGreaterThan(indexOf(connection.inbox().snapshot(0), IDS.notifyEnterScene()));
        }
    }

    @Test
    void 严格重登_26成功之后来的是124_失败并写明_连接留给调用方() throws Exception {
        long role = world.seed(ACCOUNT, HOME_ZONE);
        world.onEnter = OnEnter.REDIRECT;

        try (GameConnection connection = connect()) {
            assertThatThrownBy(() -> flow.reenter(connection, ACCOUNT, role, new Timings()))
                    .isInstanceOf(RobotException.class).hasMessageContaining("收到了 124 RedirectToGate（目标 10.0.0.7:11010）");

            assertThat(connection.isOpen()).isTrue();
        }
    }

    @Test
    void 严格重登_进游戏撞上2005照常等一会儿重试() throws Exception {
        long role = world.seed(ACCOUNT, HOME_ZONE);
        world.inProgressFirst = 1;

        try (GameConnection connection = connect()) {
            EnteredPlayer player = flow.reenter(connection, ACCOUNT, role, new Timings());

            assertThat(player.playerId()).isEqualTo(role);
            assertThat(world.enterAttempts.get()).as("第一次 2005，第二次成功").isEqualTo(2);
        }
    }

    // ---------------------------------------------------------------- 不因业务码抛异常的单步

    @Test
    void tryEnterGame_业务拒绝码原样交回_不抛_2005也不重试() throws Exception {
        long role = world.seed(ACCOUNT, HOME_ZONE);

        try (GameConnection connection = connect()) {
            assertThat(RoleZone.of(flow.tryLogin(connection, ACCOUNT).getPlayersList())).containsExactly(new RoleZone(role, HOME_ZONE));

            world.onEnter = OnEnter.REJECT;
            world.rejectCode = KICKED;
            EnterGameResponse rejected = flow.tryEnterGame(connection, role);
            assertThat(rejected.hasErrorMessage()).isTrue();
            assertThat(rejected.getErrorMessage().getId()).as("持票者不符").isEqualTo(KICKED).isEqualTo(2011);

            world.inProgressFirst = world.enterAttempts.get() + 1;
            EnterGameResponse busy = flow.tryEnterGame(connection, role);
            assertThat(busy.getErrorMessage().getId()).isEqualTo(FakeLoginWorld.IN_PROGRESS);
            assertThat(world.enterAttempts.get()).as("两次调用各发一条 26，没有自动重试").isEqualTo(2);

            world.onEnter = OnEnter.SILENT;
            EnterGameResponse ok = flow.tryEnterGame(connection, role);
            assertThat(ok.hasErrorMessage()).isFalse();
            assertThat(ok.getPlayerId()).isEqualTo(role);
        }
    }

    @Test
    void tryLogin_只发48_空列表不建角_业务拒绝不抛() throws Exception {
        try (GameConnection connection = connect()) {
            LoginResponse empty = flow.tryLogin(connection, ACCOUNT);
            assertThat(empty.hasErrorMessage()).isFalse();
            assertThat(empty.getPlayersList()).isEmpty();
            assertThat(gate.received(IDS.createPlayer())).isZero();

            world.loginError = 2000;
            LoginResponse rejected = flow.tryLogin(connection, ACCOUNT);
            assertThat(rejected.getErrorMessage().getId()).isEqualTo(2000);
            assertThat(gate.received(IDS.login())).isEqualTo(2);
        }
    }

    // ---------------------------------------------------------------- 等 79 超时

    @Test
    void 等79超时的文案照旧_既没有79也没有124() throws Exception {
        world.seed(ACCOUNT, HOME_ZONE);
        world.onEnter = OnEnter.SILENT;
        PlayerFlow impatient = new PlayerFlow(client, "dev-secret", SHORT, Duration.ofMillis(300));

        assertThatThrownBy(() -> impatient.enter(ACCOUNT, new Timings()))
                .isInstanceOf(RobotException.class)
                .hasMessage("发出进游戏后 300 ms 内没有收到 79 NotifyEnterScene");
    }

    private static int indexOf(List<Received> inbox, int messageId) {
        return inbox.stream().filter(r -> r.messageId() == messageId).findFirst().orElseThrow().index();
    }
}
