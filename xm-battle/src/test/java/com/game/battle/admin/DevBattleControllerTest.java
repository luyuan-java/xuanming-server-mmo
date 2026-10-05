package com.game.battle.admin;

import static com.game.battle.admin.DevBattleTestApp.OPERATOR;
import static com.game.battle.admin.DevBattleTestApp.TOKEN;
import static com.game.battle.admin.DevBattleTestApp.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.RoomOrigin;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.battle.testing.ManualBattleScheduler;
import com.game.battle.testing.StubBattleRoomService;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * dev 管理接口在真的内嵌 Tomcat 上（dev 运行模式；battle-node-spec §7.12、§13.6）：建房补全路由后以 DEV 来源走控制面、补不全 422 不建房、
 * 读 Redis 失败 503、NOT_ALLOCATABLE 原样 200、销毁 / 清退 204、补签与登记观众回契约应答、令牌 / 操作人 / 请求体的错误码、节点没在运行 503。
 */
@SpringBootTest(classes = DevBattleTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=dev"})
class DevBattleControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    StubBattleRoomService rooms;

    @Autowired
    FakeLookups lookups;

    @Autowired
    DevBattleTestApp.SwitchableBackend backend;

    private BattleNodeServiceImpl original;

    @BeforeEach
    void reset() {
        rooms.calls.clear();
        rooms.creates.clear();
        rooms.origins.clear();
        rooms.createTip = 0;
        rooms.ticketTip = 0;
        lookups.clear();
        lookups.online(101, 1, 2, "gate-inst", 33, 4, "scene-inst");
        original = backend.plane;
    }

    @AfterEach
    void restore() {
        backend.plane = original;
        backend.running = true;
    }

    private static CreateBattleRequest pve(long battleId, long... players) {
        CreateBattleRequest.Builder request = CreateBattleRequest.newBuilder().setBattleId(battleId).setMatchMode(4)
                .setBattleConfigId(1).setSeed(42);
        for (long player : players) {
            request.addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(player));
        }
        return request.build();
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void 建房_补全路由_以DEV来源走控制面_回CreateBattleResult() throws Exception {
        HttpResponse<byte[]> response = post(port, DevBattleController.CREATE, pve(9, 101).toByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith(DevBattleController.CONTENT_TYPE));
        CreateBattleResult result = CreateBattleResult.parseFrom(response.body());
        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(result.getResponse()).getBattleId()).isEqualTo(9);
        assertThat(rooms.origins).containsExactly(RoomOrigin.DEV);
        assertThat(rooms.threads).last().isEqualTo("test-battle-logic");
        BattleRouting routing = rooms.creates.get(0).getPlayers(0).getRouting();
        assertThat(routing.getGateInstanceId()).isEqualTo("gate-inst");
        assertThat(routing.getSceneInstanceId()).isEqualTo("scene-inst");
        assertThat(routing.getSessionId()).isEqualTo(33);
    }

    @Test
    void 建房业务拒绝_200且tip在应答字节里() throws Exception {
        rooms.createTip = 1006;
        CreateBattleResult result = CreateBattleResult.parseFrom(post(port, DevBattleController.CREATE, pve(9, 101).toByteArray()).body());
        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(result.getResponse()).getErrorMessage().getId()).isEqualTo(1006);
    }

    @Test
    void 快照路由补不全_422写明原因_不建房() throws Exception {
        HttpResponse<byte[]> response = post(port, DevBattleController.CREATE, pve(9, 101, 404).toByteArray());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(text(response)).contains("404").contains("不在线");
        assertThat(rooms.calls).isEmpty();
    }

    @Test
    void 读Redis失败_503_不建房() throws Exception {
        lookups.failure = new IllegalStateException("redis down");
        HttpResponse<byte[]> response = post(port, DevBattleController.CREATE, pve(9, 101).toByteArray());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(rooms.calls).isEmpty();
    }

    @Test
    void 不可分配_原样200带NOT_ALLOCATABLE() throws Exception {
        AdmissionGate closed = new AdmissionGate();
        closed.close();
        backend.plane = new BattleNodeServiceImpl(closed, new ManualBattleScheduler(0), rooms, Runnable::run, 8,
                new BattleMetrics(new SimpleMeterRegistry()));
        CreateBattleResult result = CreateBattleResult.parseFrom(post(port, DevBattleController.CREATE, pve(9, 101).toByteArray()).body());

        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(result.getReason()).isEqualTo("closed");
        assertThat(rooms.calls).isEmpty();
    }

    @Test
    void 销毁与清退观众_204() throws Exception {
        assertThat(post(port, DevBattleController.DESTROY, DestroyBattleRequest.newBuilder().setBattleId(9).build().toByteArray())
                .statusCode()).isEqualTo(204);
        assertThat(post(port, DevBattleController.REMOVE_OBSERVER, RemoveObserverRequest.newBuilder().setBattleId(9)
                .setObserverPlayerId(101).build().toByteArray()).statusCode()).isEqualTo(204);
        assertThat(rooms.calls).containsExactly("destroyBattle", "removeObserver");
    }

    @Test
    void 补签回契约应答() throws Exception {
        rooms.ticketTip = 1005;
        HttpResponse<byte[]> response = post(port, DevBattleController.ISSUE_TICKET, IssueBattleTicketRequest.newBuilder()
                .setBattleId(9).setPlayerId(101).build().toByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(IssueBattleTicketResponse.parseFrom(response.body()).getErrorMessage().getId()).isEqualTo(1005);
    }

    @Test
    void 登记观众_补全gate路由_scene字段为0() throws Exception {
        HttpResponse<byte[]> response = post(port, DevBattleController.ADD_OBSERVER, AddObserverRequest.newBuilder()
                .setBattleId(9).setObserverPlayerId(101).build().toByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(AddObserverResponse.parseFrom(response.body()).hasErrorMessage()).isFalse();
        assertThat(rooms.calls).containsExactly("addObserver");

        HttpResponse<byte[]> offline = post(port, DevBattleController.ADD_OBSERVER, AddObserverRequest.newBuilder()
                .setBattleId(9).setObserverPlayerId(404).build().toByteArray());
        assertThat(offline.statusCode()).isEqualTo(422);
    }

    @Test
    void 节点没在运行_503() throws Exception {
        backend.running = false;
        HttpResponse<byte[]> response = post(port, DevBattleController.CREATE, pve(9, 101).toByteArray());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(text(response)).contains("没在运行");
    }

    @Test
    void 令牌错401_缺操作人400_请求体不是对应消息400() throws Exception {
        byte[] body = pve(9, 101).toByteArray();

        assertThat(post(port, DevBattleController.CREATE, null, OPERATOR, body).statusCode()).isEqualTo(401);
        assertThat(post(port, DevBattleController.CREATE, "wrong", OPERATOR, body).statusCode()).isEqualTo(401);
        assertThat(post(port, DevBattleController.CREATE, TOKEN, null, body).statusCode()).isEqualTo(400);
        assertThat(post(port, DevBattleController.CREATE, TOKEN, OPERATOR, new byte[] {(byte) 0xFF, 0x01}).statusCode())
                .isEqualTo(400);
        assertThat(post(port, "/admin;x=1/battle/dev/create", null, OPERATOR, body).statusCode()).as("绕过形路径照样要令牌")
                .isEqualTo(401);
        assertThat(rooms.calls).isEmpty();
    }
}
