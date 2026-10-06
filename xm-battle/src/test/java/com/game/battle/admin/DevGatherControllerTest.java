package com.game.battle.admin;

import static com.game.battle.admin.DevBattleTestApp.OPERATOR;
import static com.game.battle.admin.DevBattleTestApp.TOKEN;
import static com.game.battle.admin.DevBattleTestApp.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.BattleAdmission;
import com.game.api.proto.DevGatherMember;
import com.game.api.proto.DevGatherMode;
import com.game.api.proto.DevGatherRequest;
import com.game.api.proto.DevGatherResponse;
import com.game.api.proto.SceneBattleReply;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.RoomOrigin;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.battle.testing.ManualBattleScheduler;
import com.game.battle.testing.StubBattleRoomService;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * dev gather 接口在真的内嵌 Tomcat 上（dev 运行模式；scene-battle-spec §7.18、§13.5）：只备战不建房回 200 + 快照；建房以 DEV_GATHER 来源走控制面
 * （在逻辑线程上）；第 k 人备战失败 422 + protobuf 应答体、按升序取消前 k − 1 人；建房不可分配 / 业务错误全部取消；令牌错 401；请求非法 400；
 * 节点没在运行 503；取消接口 204 / 422。
 *
 * <p>§13.5 这一行的其余短语在别处：「非 dev → 403」见 {@link DevGatherControllerProdTest}；「缺令牌 → 503」见
 * {@link DevGatherControllerNoTokenTest}；「{@code DEV_GATHER} 房间的结算端口被调用、结果事件端口不被调用」在房间层——
 * {@code room.ResultRoutingTest#dev_gather房间照常确认照常结算_但不投递结果事件}（这里的房间服务是桩，只能核对建房来源是 {@code DEV_GATHER}）。
 */
@SpringBootTest(classes = DevGatherTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=dev"})
class DevGatherControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    StubBattleRoomService rooms;

    @Autowired
    DevGatherTest.FakeScenes scenes;

    @Autowired
    DevGatherTestApp.SwitchableNode node;

    @Autowired
    SimpleMeterRegistry meters;

    @BeforeEach
    void reset() {
        rooms.calls.clear();
        rooms.creates.clear();
        rooms.origins.clear();
        rooms.createTip = 0;
        scenes.where.clear();
        scenes.prepareReplies.clear();
        scenes.calls.clear();
        scenes.prepares.clear();
        scenes.cancels.clear();
        scenes.targets.clear();
        scenes.cancelReply = CompletableFuture.completedFuture(DevGatherTest.handled(null));
        originalPlane = node.plane;
    }

    /** 用例开始时的控制面（有的用例把它换成准入闸已关的那个）。 */
    private BattleNodeServiceImpl originalPlane;

    @AfterEach
    void restore() {
        node.running = true;
        node.plane = originalPlane;
    }

    private static DevGatherRequest request(DevGatherMode mode, long... players) {
        DevGatherRequest.Builder request = DevGatherRequest.newBuilder().setMode(mode).setBattleId(55).setMatchMode(4).setBattleConfigId(1)
                .setSeed(7).setDeadlineMs(System.currentTimeMillis() + 300_000);
        for (long player : players) {
            request.addMembers(DevGatherMember.newBuilder().setPlayerId(player));
        }
        return request.build();
    }

    private double gathers(String mode, String result) {
        return meters.get("xm.battle.dev.gather").tag("mode", mode).tag("result", result).counter().count();
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void 只备战_200_回快照_不建房() throws Exception {
        scenes.online(101, 3, "scene-a");
        double before = gathers("prepare_only", "ok");

        HttpResponse<byte[]> response = post(port, DevGatherController.GATHER, request(DevGatherMode.DEV_GATHER_PREPARE_ONLY, 101)
                .toByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith(DevBattleController.CONTENT_TYPE));
        DevGatherResponse body = DevGatherResponse.parseFrom(response.body());
        assertThat(body.getFailure()).isEmpty();
        assertThat(body.getPrepareResultsCount()).isEqualTo(1);
        assertThat(scenes.prepares.get(0).getBattleNodeId()).isEqualTo(DevGatherTestApp.NODE_ID);
        assertThat(rooms.calls).isEmpty();
        assertThat(gathers("prepare_only", "ok")).isEqualTo(before + 1);
    }

    @Test
    void 建房_以DEV_GATHER来源走控制面_在逻辑线程上() throws Exception {
        scenes.online(101, 3, "scene-a").online(102, 4, "scene-b");

        HttpResponse<byte[]> response = post(port, DevGatherController.GATHER, request(DevGatherMode.DEV_GATHER_CREATE, 102, 101)
                .toByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        DevGatherResponse body = DevGatherResponse.parseFrom(response.body());
        assertThat(body.getCreateResult().getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(body.getCreateResult().getResponse()).getBattleId()).isEqualTo(55);
        assertThat(rooms.origins).containsExactly(RoomOrigin.DEV_GATHER);
        assertThat(rooms.threads).last().isEqualTo("test-battle-logic");
        assertThat(rooms.creates.get(0).getPlayersList()).extracting(p -> p.getPlayerId()).containsExactly(101L, 102L);
    }

    @Test
    void 第k人备战失败_422带protobuf应答_按升序取消前k减1人() throws Exception {
        scenes.online(101, 3, "scene-a").online(102, 3, "scene-a").online(103, 3, "scene-a");
        scenes.prepareReplies.put(103L, CompletableFuture.completedFuture(DevGatherTest.handled(DevGatherTest.rejected(1006))));
        double before = gathers("create", "prepare_failed");

        HttpResponse<byte[]> response = post(port, DevGatherController.GATHER, request(DevGatherMode.DEV_GATHER_CREATE, 103, 102, 101)
                .toByteArray());

        assertThat(response.statusCode()).isEqualTo(422);
        DevGatherResponse body = DevGatherResponse.parseFrom(response.body());
        assertThat(body.getFailure()).contains("1006");
        assertThat(body.getCancelledPlayerIdsList()).containsExactly(101L, 102L);
        assertThat(scenes.cancels).extracting(CancelBattlePrepareRequest::getPlayerId).containsExactly(101L, 102L);
        assertThat(rooms.calls).isEmpty();
        assertThat(gathers("create", "prepare_failed")).isEqualTo(before + 1);
    }

    @Test
    void 建房业务错误_全部取消_422() throws Exception {
        scenes.online(101, 3, "scene-a");
        rooms.createTip = 1005;
        double before = gathers("create", "create_failed");

        HttpResponse<byte[]> response = post(port, DevGatherController.GATHER, request(DevGatherMode.DEV_GATHER_CREATE, 101)
                .toByteArray());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(scenes.cancels).extracting(CancelBattlePrepareRequest::getPlayerId).containsExactly(101L);
        assertThat(DevGatherResponse.parseFrom(response.body()).getFailure()).contains("1005");
        assertThat(gathers("create", "create_failed")).isEqualTo(before + 1);
    }

    @Test
    void 建房不可分配_全部按升序取消_422_应答带NOT_ALLOCATABLE与原因_不碰房间() throws Exception {
        scenes.online(101, 3, "scene-a").online(102, 4, "scene-b");
        AdmissionGate closed = new AdmissionGate();
        closed.close();
        node.plane = new BattleNodeServiceImpl(closed, new ManualBattleScheduler(0), rooms, Runnable::run, 8,
                new BattleMetrics(new SimpleMeterRegistry()));
        double before = gathers("create", "create_failed");

        HttpResponse<byte[]> response = post(port, DevGatherController.GATHER, request(DevGatherMode.DEV_GATHER_CREATE, 102, 101)
                .toByteArray());

        assertThat(response.statusCode()).isEqualTo(422);
        DevGatherResponse body = DevGatherResponse.parseFrom(response.body());
        assertThat(body.getCreateResult().getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(body.getFailure()).contains("closed");
        assertThat(body.getCancelledPlayerIdsList()).containsExactly(101L, 102L);
        assertThat(scenes.calls).as("全员备战成功 → 建房被准入闸拒 → 按升序全部取消").containsExactly("locate:101", "prepare:101",
                "locate:102", "prepare:102", "cancel:101", "cancel:102");
        assertThat(scenes.cancels).allSatisfy(c -> assertThat(c.getBattleId()).isEqualTo(55));
        assertThat(rooms.calls).as("准入闸在碰房间之前就拒了，零副作用，不需要 destroy").isEmpty();
        assertThat(gathers("create", "create_failed")).isEqualTo(before + 1);
    }

    @Test
    void 请求非法400_节点没在运行503_令牌错401() throws Exception {
        double rejectedBefore = gathers("unknown", "rejected");
        HttpResponse<byte[]> invalid = post(port, DevGatherController.GATHER, request(DevGatherMode.DEV_GATHER_CREATE).toByteArray());
        HttpResponse<byte[]> garbage = post(port, DevGatherController.GATHER, TOKEN, OPERATOR, new byte[] {(byte) 0xFF, 0x01});
        node.running = false;
        HttpResponse<byte[]> notRunning = post(port, DevGatherController.GATHER, request(DevGatherMode.DEV_GATHER_CREATE, 101)
                .toByteArray());
        HttpResponse<byte[]> wrongToken = post(port, DevGatherController.GATHER, "wrong", OPERATOR,
                request(DevGatherMode.DEV_GATHER_CREATE, 101).toByteArray());

        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(text(invalid)).contains("members");
        assertThat(garbage.statusCode()).isEqualTo(400);
        assertThat(notRunning.statusCode()).isEqualTo(503);
        assertThat(wrongToken.statusCode()).isEqualTo(401);
        assertThat(scenes.calls).isEmpty();
        assertThat(gathers("unknown", "rejected")).as("形状非法与坏请求体").isEqualTo(rejectedBefore + 2);
        assertThat(gathers("create", "rejected")).as("节点没在运行").isGreaterThanOrEqualTo(1);
    }

    @Test
    void 取消接口_204_没有持有者422() throws Exception {
        scenes.online(101, 3, "scene-a");
        byte[] cancel = CancelBattlePrepareRequest.newBuilder().setPlayerId(101).setBattleId(55).build().toByteArray();
        byte[] offline = CancelBattlePrepareRequest.newBuilder().setPlayerId(404).setBattleId(55).build().toByteArray();

        assertThat(post(port, DevGatherController.CANCEL_PREPARE, cancel).statusCode()).isEqualTo(204);
        assertThat(post(port, DevGatherController.CANCEL_PREPARE, offline).statusCode()).isEqualTo(422);
        scenes.cancelReply = CompletableFuture.completedFuture(SceneBattleReply.getDefaultInstance());
        assertThat(post(port, DevGatherController.CANCEL_PREPARE, cancel).statusCode()).as("UNSPECIFIED 按传输失败").isEqualTo(503);
        assertThat(scenes.cancels).hasSize(2);
    }
}
