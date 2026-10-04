package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorCreateS2C;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LeaveGameRequest;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.ConfigTables;
import com.game.table.LoginErrorTip;
import com.game.table.WorldTable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 短线重连与落点（批次 3.3）：一个新账号 A
 * <ol>
 *   <li>进游戏落在默认主世界（World 表第一行），用 63 换到另一张世界地图 M2（落 M2 出生点），走几步停下；</li>
 *   <li><b>短线重连</b>：A 断开后立即重连进游戏 → 回到 M2 的同一个场景实例、断开前走到的点（重连租约内按位置记录落点）；</li>
 *   <li><b>顶号</b>：A 在线时从另一条连接进同一角色 → 旧连接收到 23 {2017} 后被关闭，新连接回到 M2 同一实例；</li>
 *   <li><b>干净登出</b>：发完 LeaveGame 立即关连接（契约里的收尾方式），再进游戏 → 按首登落默认主世界出生点。</li>
 * </ol>
 * 依据：登录契约 §8；scene 契约 §1（首登落 World 第一行）。第 2、3 步回原实例与第 4 步换图落出生点是 Java 的行为——基线在 zone 内
 * 重连 / 顶号只按 location 定 zone、落默认主世界人数最少的频道，跨登录不存地图、合法坐标原样保留（PARITY「短线重连与落点」行有意差异⑥⑦）。
 */
public final class ReconnectScenario {

    private static final String REF = "PARITY「短线重连与落点」行；登录契约 §8；scene 契约 §1";
    private static final int KICKED_BY_ANOTHER = LoginErrorTip.login_error.kLoginBeKickByAnOtherAccount_VALUE;
    private static final double LOCATION_EPS = 1e-3;
    private static final Vec3 DEFAULT_SPAWN = new Vec3(180.0, 200.0, 0.0);
    /** 断开前在 M2 里走的位移与用时（2 m / 0.5 s = 4 m/s，远低于位移校验的 12 m/s）。 */
    private static final Vec3 WALK = new Vec3(2, 0, 0);
    private static final Duration WALK_TIME = Duration.ofMillis(500);
    private static final Vec3 FACING = new Vec3(0, 0, 90);

    private final PlayerFlow flow;
    private final MessageIds ids;
    private final ConfigTables tables;
    private final String account;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    private final int enterScene;
    private final int leaveGame;
    private final CheckReport report = new CheckReport();
    private final List<GameConnection> connections = new ArrayList<>();

    public ReconnectScenario(PlayerFlow flow, MessageIds ids, MessageIdRegistry registry, Path tableDir,
                             String accountPrefix, String runTag, Duration requestTimeout, Duration observeTimeout) {
        this.flow = flow;
        this.ids = ids;
        this.tables = ConfigTables.load(tableDir);
        this.account = accountName(accountPrefix, runTag);
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
        this.enterScene = registry.requireId("SceneSceneClientPlayer", "EnterScene");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "rc" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.toString(), REF);
        } finally {
            connections.forEach(GameConnection::close);
        }
        return report;
    }

    private void runChecks() throws RobotException {
        List<Integer> worlds = worldConfigs();
        int home = worlds.get(0);
        if (worlds.size() < 2) {
            throw new RobotException("World 表只有一张世界地图，换不了图：" + worlds);
        }
        int other = worlds.get(1);

        // 1. 首登落默认主世界；换到 M2
        EnteredPlayer first = enter();
        report.check(first.sceneInfo().getSceneConfigId() == home, "首次进游戏落默认主世界（World 第一行）",
                "79 scene_config_id=" + first.sceneInfo().getSceneConfigId() + "，期望 " + home, REF);
        SceneInfoComp m2 = switchTo(first, other);
        Vec3 walked = walk(first, spawn(other));

        // 2. 短线重连：断开后立即重连 → 同一实例、同一位置
        first.connection().close();
        EnteredPlayer back = enter();
        ActorCreateS2C backSelf = flow.awaitSelfActor(back, observeTimeout);
        checkSameInstance(back.sceneInfo(), m2, "短线重连回到原场景实例");
        Vec3 backAt = Vec3.of(backSelf.getTransform().getLocation());
        report.check(backAt.approx(walked, LOCATION_EPS), "短线重连回到原位置（断开前走到的点）",
                "自己的 21 位置 " + backAt + "，期望 " + walked, REF);

        // 3. 顶号：在线时从另一条连接进同一角色
        int mark = back.connection().inbox().size();
        EnteredPlayer replacing = enter();
        Optional<Received> kicked = back.connection().await(mark, r -> r.messageId() == ids.sendTip()
                && tipId(r) == KICKED_BY_ANOTHER, requestTimeout);
        report.check(kicked.isPresent(), "顶号：旧连接收到 23 {2017}",
                kicked.isPresent() ? "收到" : requestTimeout.toMillis() + " ms 内没收到" + back.connection().describeSince(mark),
                REF);
        checkSameInstance(replacing.sceneInfo(), m2, "顶号：新连接回到原场景实例");

        // 4. 干净登出后再进：按首登落默认主世界出生点
        // 契约里的收尾方式：发完 17 立即关连接、不等应答（gate 按主动离开通知 scene）
        replacing.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        replacing.connection().close();
        EnteredPlayer fresh = enter();
        ActorCreateS2C freshSelf = flow.awaitSelfActor(fresh, observeTimeout);
        report.check(fresh.sceneInfo().getSceneConfigId() == home, "LeaveGame 后再进游戏按首登落默认主世界",
                "79 scene_config_id=" + fresh.sceneInfo().getSceneConfigId() + "，期望 " + home
                        + "（不是上次所在的 " + other + "）", REF);
        Vec3 freshAt = Vec3.of(freshSelf.getTransform().getLocation());
        Vec3 spawnHome = spawn(home);
        report.check(freshAt.approx(spawnHome, LOCATION_EPS), "换了地图落出生点",
                "自己的 21 位置 " + freshAt + "，期望 " + spawnHome, REF);
    }

    /**
     * 在当前地图走 {@link #WALK} 停下（134 → 等 {@link #WALK_TIME} → 131，速度远低于位移校验的 12 m/s），再用一次有应答的请求
     * （77 ListSkills）确认 scene 已按序处理完这两条移动；返回服务端裁决的停止点（被纠偏时取 137 的位置）。
     */
    private Vec3 walk(EnteredPlayer player, Vec3 from) throws RobotException {
        GameConnection connection = player.connection();
        int mark = connection.inbox().size();
        Vec3 to = from.plus(WALK);
        Vec3 velocity = new Vec3(WALK.x() * 1000.0 / WALK_TIME.toMillis(), 0, 0);
        long now = System.nanoTime() / 1_000_000;
        connection.send(ids.moveStart(), MoveStartC2S.newBuilder().setStartLocation(from.toLocation())
                .setRotation(FACING.toRotation()).setVelocity(velocity.toVelocity()).setClientTimeMs(now).setInputSeq(1).build());
        sleep(WALK_TIME);
        connection.send(ids.moveStop(), MoveStopC2S.newBuilder().setEndLocation(to.toLocation())
                .setRotation(FACING.toRotation()).setClientTimeMs(now + WALK_TIME.toMillis()).setInputSeq(2).build());
        connection.call(ids.listSkills(), ListSkillsRequest.getDefaultInstance(), ListSkillsResponse.parser(), requestTimeout);
        Optional<Received> ack = connection.await(mark, r -> r.messageId() == ids.notifyMoveAck(), Duration.ZERO);
        Vec3 stopped = to;
        if (ack.isPresent()) {
            stopped = Vec3.of(ack.get().parse(MoveAckS2C.parser()).getServerLocation());
        }
        report.check(!stopped.approx(from, LOCATION_EPS), "M2 里走几步停下", "从 " + from + " 走到 " + stopped, REF);
        return stopped;
    }

    private EnteredPlayer enter() throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        connections.add(player.connection());
        return player;
    }

    /** 63 换到 {@code configId}：应答无错、随后 79 是目标地图。 */
    private SceneInfoComp switchTo(EnteredPlayer player, int configId) throws RobotException {
        GameConnection connection = player.connection();
        int mark = connection.inbox().size();
        EnterSceneC2SResponse response = connection.call(enterScene, EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(configId)).build(),
                EnterSceneC2SResponse.parser(), requestTimeout);
        if (response.hasErrorMessage() && response.getErrorMessage().getId() != 0) {
            throw new RobotException("换场景（63）到 " + configId + " 回 " + response.getErrorMessage().getId());
        }
        Optional<Received> notify = connection.await(mark, r -> r.messageId() == ids.notifyEnterScene(), observeTimeout);
        if (notify.isEmpty()) {
            throw new RobotException("换场景后 " + observeTimeout.toMillis() + " ms 内没收到 79" + connection.describeSince(mark));
        }
        SceneInfoComp info = notify.get().parse(EnterSceneS2C.parser()).getSceneInfo();
        report.check(info.getSceneConfigId() == configId, "63 换到第二张世界地图",
                "79 scene_config_id=" + info.getSceneConfigId() + " scene_id=" + info.getSceneId(), REF);
        return info;
    }

    private void checkSameInstance(SceneInfoComp actual, SceneInfoComp expected, String name) {
        report.check(actual.getSceneConfigId() == expected.getSceneConfigId() && actual.getSceneId() == expected.getSceneId(),
                name, "79 scene_config_id=" + actual.getSceneConfigId() + " scene_id=" + actual.getSceneId()
                        + "，期望 " + expected.getSceneConfigId() + " / " + expected.getSceneId(), REF);
    }

    private List<Integer> worldConfigs() {
        Set<Integer> worlds = new LinkedHashSet<>();
        for (WorldTable row : tables.world().all()) {
            if (row.getSceneId() != 0) {
                worlds.add(row.getSceneId());
            }
        }
        return List.copyOf(worlds);
    }

    /** 同 scene：BaseScene 的出生点（有限且不全为 0），否则基线常量 (180, 200, 0)。 */
    private Vec3 spawn(int configId) {
        return tables.baseScene().find(configId)
                .map(row -> new Vec3(row.getSpawnX(), row.getSpawnY(), row.getSpawnZ()))
                .filter(v -> Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z())
                        && !(v.x() == 0 && v.y() == 0 && v.z() == 0))
                .orElse(DEFAULT_SPAWN);
    }

    private static int tipId(Received r) {
        TipInfoMessage tip = r.parseOrNull(TipInfoMessage.parser());
        return tip == null ? -1 : tip.getId();
    }

    private static void sleep(Duration d) throws RobotException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("被中断");
        }
    }
}
