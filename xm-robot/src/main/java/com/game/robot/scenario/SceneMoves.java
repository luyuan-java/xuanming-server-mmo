package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneS2C;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoRequest;
import com.game.proto.SceneInfoS2C;
import com.game.proto.login.LeaveGameRequest;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.CommonErrorTip;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * mirror / dungeon 探针的收发工具（沿用 5.2 {@code CrossNodeScenario} 的「先取收件箱序号再发、从序号起等」写法）：登录进场、
 * 按 gate 限频节拍发 63、等应答 / 79 / 自己的 21、「进不去」的两种形态、走两步、旁人看见 / 消失。
 * 场景线程独占；连接在 {@link #closeAll()} 里统一关闭。
 */
final class SceneMoves {

    /** 同图换场景保留坐标的容差（水平，米；§12.6 第 1 步）。 */
    static final double POSITION_TOLERANCE = 0.5;
    /** 确认「这段时间没有 79」的静默窗口。 */
    static final Duration QUIET_WINDOW = Duration.ofSeconds(1);
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;

    /** 走两步：2 m / 0.5 s = 4 m/s，远低于位移校验的 12 m/s（同 reconnect / cross-node）。 */
    private static final Vec3 WALK = new Vec3(2, 0, 0);
    private static final Duration WALK_TIME = Duration.ofMillis(500);
    private static final Vec3 FACING = new Vec3(0, 0, 90);

    /** 一条已应答的 63：发之前的收件箱序号、请求号、应答与拒绝码。 */
    record Sent(int mark, long requestId, Received reply, int tip) {
    }

    /**
     * 63 受理后的进场：79、其 scene_info、自己的新实体号与位置。
     * 分量不能叫 {@code notify}：记录分量不得与 {@code Object} 的方法同名（JLS §8.10.1），{@code notify()} 会解析成 {@code Object.notify()}。
     */
    record Arrival(Received enterNotify, SceneInfoComp info, long entity, Vec3 at) {
    }

    /** 「进不去」的观察结果（判定见 {@link InstanceChecks#enterRefused}）。 */
    record Refusal(int replyTip, boolean lateEnterFailed, long enters) {

        boolean ok() {
            return InstanceChecks.enterRefused(replyTip, lateEnterFailed, enters);
        }

        String describe() {
            return "应答 {" + replyTip + "}" + (replyTip == 0 ? (lateEnterFailed ? " 后 23 {3023}" : "，之后没有 23 {3023}") : "")
                    + "，79 " + enters + " 条";
        }
    }

    private final PlayerFlow flow;
    private final MessageIds ids;
    private final int enterScene;
    private final int sceneInfoC2S;
    private final int notifySceneInfo;
    private final int leaveGame;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    private final SendPacer pacer = new SendPacer();
    private final List<GameConnection> connections = new ArrayList<>();

    SceneMoves(PlayerFlow flow, MessageIds ids, MessageIdRegistry registry, Duration requestTimeout, Duration observeTimeout) {
        this.flow = flow;
        this.ids = ids;
        this.enterScene = registry.requireId("SceneSceneClientPlayer", "EnterScene");
        this.sceneInfoC2S = registry.requireId("SceneSceneClientPlayer", "SceneInfoC2S");
        this.notifySceneInfo = registry.requireId("SceneSceneClientPlayer", "NotifySceneInfo");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
    }

    MessageIds ids() {
        return ids;
    }

    PlayerFlow flow() {
        return flow;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    Duration observeTimeout() {
        return observeTimeout;
    }

    // ------------------------------------------------------------------ 登录 / 登出

    /** 登录进场并等自己的 21。 */
    ProbeBot login(String account, String role) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        connections.add(player.connection());
        ActorCreateS2C self = flow.awaitSelfActor(player, observeTimeout);
        return new ProbeBot(role, player, player.sceneInfo(), self.getEntity(), Vec3.of(self.getTransform().getLocation()), 0);
    }

    /** 契约里的干净登出：发完 17 立即关连接。 */
    void leave(ProbeBot bot) throws RobotException {
        bot.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        bot.connection().close();
    }

    void closeAll() {
        connections.forEach(GameConnection::close);
    }

    // ------------------------------------------------------------------ 63

    /** 按 gate 限频节拍发一条 63 并等应答（超时抛出）。 */
    Sent send(ProbeBot bot, EnterSceneC2SRequest request, String label) throws RobotException {
        GameConnection connection = bot.connection();
        pace(connection, 1);
        int mark = connection.inbox().size();
        long requestId = connection.send(enterScene, request);
        pacer.record(connection);
        Received reply = awaitReply(connection, mark, requestId)
                .orElseThrow(() -> new RobotException(label + "：" + requestTimeout.toMillis() + " ms 内没收到 63 的应答"
                        + connection.describeSince(mark)));
        return new Sent(mark, requestId, reply, CrossNodeScenario.replyTip(reply));
    }

    /** 背靠背连发的几条 63：发之前的收件箱序号与各条的请求号。 */
    record Burst(int mark, List<Long> requestIds) {
    }

    /** 背靠背连发 {@code count} 条同样的 63（先等出能放下这几条的限频窗口），不等应答。 */
    Burst sendBurst(ProbeBot bot, EnterSceneC2SRequest request, int count) throws RobotException {
        GameConnection connection = bot.connection();
        pace(connection, count);
        int mark = connection.inbox().size();
        List<Long> requestIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requestIds.add(connection.send(enterScene, request));
            pacer.record(connection);
        }
        return new Burst(mark, List.copyOf(requestIds));
    }

    Optional<Received> awaitReply(GameConnection connection, int mark, long requestId) throws RobotException {
        return connection.await(mark, r -> r.messageId() == enterScene && r.requestId() == requestId, requestTimeout);
    }

    /** 63 受理后的结局：79，或异步失败的 23 {1003 / 3023}（§5.5）；超时为空。 */
    Optional<Received> awaitOutcome(GameConnection connection, int mark, Duration timeout) throws RobotException {
        return connection.await(mark, r -> r.messageId() == ids.notifyEnterScene() || isAsyncFailure(r), timeout);
    }

    /** 63 受理后必须进场：等 79（先等到异步失败的 23 即抛出）与自己的 21。 */
    Arrival arrive(ProbeBot bot, int mark, String label) throws RobotException {
        GameConnection connection = bot.connection();
        Received outcome = awaitOutcome(connection, mark, requestTimeout)
                .orElseThrow(() -> new RobotException(label + "：63 受理后 " + requestTimeout.toMillis() + " ms 内没收到 79"
                        + connection.describeSince(mark)));
        if (outcome.messageId() != ids.notifyEnterScene()) {
            throw new RobotException(label + "：63 受理后收到 23 {" + CrossNodeScenario.tipId(outcome) + "}，没有进场"
                    + "（1003 = 取号调用失败 / 超时；3023 = scene-manager 拒绝或结果回来时源已变）");
        }
        SceneInfoComp info = outcome.parse(EnterSceneS2C.parser()).getSceneInfo();
        ActorCreateS2C self = awaitActor(connection, mark, bot.playerId(), observeTimeout)
                .orElseThrow(() -> new RobotException(label + "：79 之后 " + observeTimeout.toMillis() + " ms 内没收到自己的 21"
                        + connection.describeSince(mark)));
        Vec3 at = self.hasTransform() && self.getTransform().hasLocation() ? Vec3.of(self.getTransform().getLocation()) : bot.at();
        return new Arrival(outcome, info, self.getEntity(), at);
    }

    /**
     * 「进不去」的观察：应答 {0} 时再等 79 或 23 {3023}（至多请求超时）；最后留一个静默窗口数 79。
     */
    Refusal awaitRefusal(ProbeBot bot, Sent sent) throws RobotException {
        GameConnection connection = bot.connection();
        boolean late = false;
        if (sent.tip() == 0) {
            Optional<Received> outcome = awaitOutcome(connection, sent.mark(), requestTimeout);
            late = outcome.isPresent() && outcome.get().messageId() == ids.sendTip()
                    && CrossNodeScenario.tipId(outcome.get()) == InstanceChecks.ENTER_FAILED;
        }
        return new Refusal(sent.tip(), late, quietEnters(connection, sent.mark()));
    }

    /** 等一个静默窗口，返回从 {@code mark} 起收到的 79 条数。 */
    long quietEnters(GameConnection connection, int mark) throws RobotException {
        sleep(QUIET_WINDOW);
        return CrossNodeScenario.countOf(connection.inbox().snapshot(mark), ids.notifyEnterScene());
    }

    // ------------------------------------------------------------------ 43 → 31

    /** 发 43（应答是 Empty 不回包），等 scene 改推的 31；超时抛出。 */
    SceneInfoS2C querySceneInfo(ProbeBot bot, String label) throws RobotException {
        GameConnection connection = bot.connection();
        int mark = connection.inbox().size();
        connection.send(sceneInfoC2S, SceneInfoRequest.getDefaultInstance());
        Received pushed = connection.await(mark, r -> r.messageId() == notifySceneInfo, requestTimeout)
                .orElseThrow(() -> new RobotException(label + "：发 43 之后 " + requestTimeout.toMillis() + " ms 内没收到 31"
                        + connection.describeSince(mark)));
        return pushed.parse(SceneInfoS2C.parser());
    }

    // ------------------------------------------------------------------ 旁人

    Optional<ActorCreateS2C> awaitActor(GameConnection connection, int mark, long guid, Duration timeout) throws RobotException {
        return connection.await(mark, r -> CrossNodeScenario.findActor(ids, r, guid) != null, timeout)
                .map(r -> CrossNodeScenario.findActor(ids, r, guid));
    }

    /** 从 {@code mark} 起是否已经收到过含 {@code guid} 的 21 / 47（不等待）。 */
    boolean sawActor(GameConnection connection, int mark, long guid) {
        return connection.inbox().snapshot(mark).stream().anyMatch(r -> CrossNodeScenario.findActor(ids, r, guid) != null);
    }

    Optional<Received> awaitDestroy(GameConnection connection, int mark, long entity, Duration timeout) throws RobotException {
        return connection.await(mark, r -> CrossNodeScenario.destroys(ids, r, entity), timeout);
    }

    Optional<ActorBaseAttributesS2C> awaitSync(GameConnection connection, int mark, long playerId, Duration timeout)
            throws RobotException {
        return connection.await(mark, r -> CrossNodeScenario.syncOf(ids, r, playerId) != null, timeout)
                .map(r -> CrossNodeScenario.syncOf(ids, r, playerId));
    }

    List<Integer> tipsSince(GameConnection connection, int mark) {
        List<Integer> tips = new ArrayList<>();
        for (Received r : connection.inbox().snapshot(mark)) {
            if (r.messageId() == ids.sendTip()) {
                tips.add(CrossNodeScenario.tipId(r));
            }
        }
        return tips;
    }

    // ------------------------------------------------------------------ 移动

    /**
     * 从当前位置走 {@link #WALK} 停下（134 → 等 {@link #WALK_TIME} → 131），再发 77 确认 scene 已按序处理完；返回服务端裁决的停止点
     * （被纠偏时取 137 的位置）。
     */
    Vec3 walk(ProbeBot bot) throws RobotException {
        GameConnection connection = bot.connection();
        int mark = connection.inbox().size();
        Vec3 from = bot.at();
        Vec3 to = from.plus(WALK);
        Vec3 velocity = new Vec3(WALK.x() * 1000.0 / WALK_TIME.toMillis(), 0, 0);
        long now = System.nanoTime() / 1_000_000;
        connection.send(ids.moveStart(), MoveStartC2S.newBuilder().setStartLocation(from.toLocation())
                .setRotation(FACING.toRotation()).setVelocity(velocity.toVelocity()).setClientTimeMs(now).setInputSeq(1).build());
        sleep(WALK_TIME);
        connection.send(ids.moveStop(), MoveStopC2S.newBuilder().setEndLocation(to.toLocation())
                .setRotation(FACING.toRotation()).setClientTimeMs(now + WALK_TIME.toMillis()).setInputSeq(2).build());
        flow.listSkills(bot.player());
        Optional<Received> ack = connection.await(mark, r -> r.messageId() == ids.notifyMoveAck(), Duration.ZERO);
        return ack.isPresent() ? Vec3.of(ack.get().parse(MoveAckS2C.parser()).getServerLocation()) : to;
    }

    // ------------------------------------------------------------------ 内部

    /** 异步失败的 23：取号调用失败 1003，或拒绝 / 结果作废 3023。 */
    private boolean isAsyncFailure(Received r) {
        if (r.messageId() != ids.sendTip()) {
            return false;
        }
        int tip = CrossNodeScenario.tipId(r);
        return tip == SERVICE_UNAVAILABLE || tip == InstanceChecks.ENTER_FAILED;
    }

    private void pace(GameConnection connection, int count) throws RobotException {
        try {
            pacer.await(connection, count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等 gate 限频窗口被中断", e);
        }
    }

    static void sleep(Duration d) throws RobotException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("被中断", e);
        }
    }
}
