package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.api.proto.DevGatherMember;
import com.game.api.proto.DevGatherMode;
import com.game.api.proto.DevGatherPrepareResult;
import com.game.api.proto.DevGatherRequest;
import com.game.api.proto.DevGatherResponse;
import com.game.api.proto.SceneBattleStatus;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.TipInfoMessage;
import com.game.robot.client.BattleAdminClient.DevGather;
import com.game.robot.client.BattleAdminClient.GatherOutcome;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * dev gather / 取消备战的客户端（scene-battle-spec §7.18）：请求按字段号编码、应答按字段号解（都用 xm-api 的生成类钉住）、200 与 422 都解出应答体、
 * 其余状态码抛出、取消的请求体是契约 {@code CancelBattlePrepareRequest}。
 */
class BattleAdminGatherTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<byte[]> responseBody = new AtomicReference<>(new byte[0]);
    private final List<String> paths = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (paths) {
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(body);
            }
            byte[] out = responseBody.get();
            exchange.sendResponseHeaders(status.get(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BattleAdminClient client() {
        return new BattleAdminClient("http://127.0.0.1:" + server.getAddress().getPort(), "token", Duration.ofSeconds(5));
    }

    private static DevGather request() {
        return new DevGather(BattleAdminClient.GATHER_CREATE, 1_760_000_000_000_001L, 4, 1, 42, 1_760_000_300_000L, 1_760_000_060_000L,
                List.of(new DevGather.Member(20, 1), new DevGather.Member(-3L, 0)));
    }

    @Test
    void 请求编码_与xm_api生成类逐字节相同() throws Exception {
        DevGatherRequest expected = DevGatherRequest.newBuilder().setMode(DevGatherMode.DEV_GATHER_CREATE).setBattleId(1_760_000_000_000_001L)
                .setMatchMode(4).setBattleConfigId(1).setSeed(42).setDeadlineMs(1_760_000_300_000L).setPrepareDeadlineMs(1_760_000_060_000L)
                .addMembers(DevGatherMember.newBuilder().setPlayerId(20).setTeamIndex(1))
                .addMembers(DevGatherMember.newBuilder().setPlayerId(-3L))
                .build();

        byte[] encoded = BattleAdminClient.encodeGather(request());

        assertThat(DevGatherRequest.parseFrom(encoded)).isEqualTo(expected);
        assertThat(encoded).isEqualTo(expected.toByteArray());
        assertThat(BattleAdminClient.GATHER_PREPARE_ONLY).isEqualTo(DevGatherMode.DEV_GATHER_PREPARE_ONLY_VALUE);
        assertThat(BattleAdminClient.GATHER_CREATE).isEqualTo(DevGatherMode.DEV_GATHER_CREATE_VALUE);
        assertThat(BattleAdminClient.SCENE_BATTLE_HANDLED).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED_VALUE);
    }

    @Test
    void 应答解码_字段号对照生成类钉住() throws Exception {
        PrepareBattleResponse ok = PrepareBattleResponse.newBuilder().setTableFingerprint("0123456789abcdef0123456789abcdef")
                .setSnapshot(BattlePlayerSnapshot.newBuilder().setPlayerId(20)).build();
        PrepareBattleResponse rejected = PrepareBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1006)).build();
        DevGatherResponse response = DevGatherResponse.newBuilder()
                .addPrepareResults(DevGatherPrepareResult.newBuilder().setPlayerId(20).setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                        .setResponse(ok.toByteString()).setSceneNodeId(3).setSceneInstanceId("scene-a"))
                .addPrepareResults(DevGatherPrepareResult.newBuilder().setPlayerId(-3L).setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                        .setResponse(rejected.toByteString()))
                .setCreateResult(CreateBattleResult.newBuilder().setAdmission(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE).setReason("closed"))
                .setFailure("player_id=… 备战被拒 tip=1006")
                .addCancelledPlayerIds(20).addCancelledPlayerIds(-1L)
                .build();

        GatherOutcome outcome = BattleAdminClient.decodeGather(422, response.toByteArray());

        assertThat(outcome.httpStatus()).isEqualTo(422);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.prepareResults()).hasSize(2);
        assertThat(outcome.of(20).ok()).isTrue();
        assertThat(outcome.of(20).response()).isEqualTo(ok);
        assertThat(outcome.of(20).sceneNodeId()).isEqualTo(3);
        assertThat(outcome.of(20).sceneInstanceId()).isEqualTo("scene-a");
        assertThat(outcome.of(-3L).ok()).isFalse();
        assertThat(outcome.of(-3L).tip()).isEqualTo(1006);
        assertThat(outcome.created().admission()).isEqualTo(BattleAdminClient.ADMISSION_NOT_ALLOCATABLE);
        assertThat(outcome.created().reason()).isEqualTo("closed");
        assertThat(outcome.failure()).contains("1006");
        assertThat(outcome.cancelledPlayerIds()).containsExactly(20L, -1L);
        assertThat(outcome.describe()).contains("tip=1006").contains("cancelled=[20, 18446744073709551615]");
    }

    @Test
    void 应答解码_没有建房结果为null_非packed的取消名单也认() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeUInt64(4, 7);
        out.writeUInt64(4, 9);
        out.flush();

        GatherOutcome outcome = BattleAdminClient.decodeGather(200, bytes.toByteArray());

        assertThat(outcome.created()).isNull();
        assertThat(outcome.cancelledPlayerIds()).containsExactly(7L, 9L);
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.prepareResults()).isEmpty();
        assertThat(UnknownFieldSet.parseFrom(bytes.toByteArray()).getField(4).getVarintList()).hasSize(2);
    }

    @Test
    void gather_200与422都解出应答体_路径正确() throws Exception {
        DevGatherResponse body = DevGatherResponse.newBuilder()
                .addPrepareResults(DevGatherPrepareResult.newBuilder().setPlayerId(20).setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                        .setResponse(PrepareBattleResponse.newBuilder().setSnapshot(BattlePlayerSnapshot.newBuilder().setPlayerId(20)).build()
                                .toByteString()))
                .setCreateResult(CreateBattleResult.newBuilder().setAdmission(BattleAdmission.BATTLE_ADMISSION_ADMITTED)
                        .setResponse(CreateBattleResponse.newBuilder().setBattleId(5).build().toByteString()))
                .build();
        responseBody.set(body.toByteArray());

        GatherOutcome ok = client().gather(request());
        status.set(422);
        responseBody.set(DevGatherResponse.newBuilder().setFailure("建房被拒 tip=1005").build().toByteArray());
        GatherOutcome failed = client().gather(request());

        assertThat(ok.ok()).isTrue();
        assertThat(ok.created().ok()).isTrue();
        assertThat(failed.ok()).isFalse();
        assertThat(failed.failure()).contains("1005");
        assertThat(paths).containsOnly(BattleAdminClient.GATHER);
        assertThat(DevGatherRequest.parseFrom(bodies.get(0)).getMembersCount()).isEqualTo(2);
    }

    @Test
    void gather_其余状态码抛出带提示_raw不判状态() throws Exception {
        status.set(403);
        responseBody.set("battle dev 管理接口只在运行模式 dev / test 下开放".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client().gather(request())).isInstanceOf(RobotException.class).hasMessageContaining("403")
                .hasMessageContaining("dev / test");
        assertThat(client().gatherRaw(request()).status()).isEqualTo(403);
    }

    @Test
    void 取消备战_请求体是契约CancelBattlePrepareRequest_返回原始状态() throws Exception {
        status.set(204);

        BattleAdminClient.HttpResult result = client().cancelPrepare(20, 7);

        assertThat(result.status()).isEqualTo(204);
        assertThat(paths).containsExactly(BattleAdminClient.CANCEL_PREPARE);
        assertThat(CancelBattlePrepareRequest.parseFrom(bodies.get(0)))
                .isEqualTo(CancelBattlePrepareRequest.newBuilder().setPlayerId(20).setBattleId(7).build());
    }
}
