package com.game.scene.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.game.api.proto.CreateDungeonInstanceRequest;
import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.DestroyInstanceRequest;
import com.game.api.proto.DestroyInstanceResponse;
import com.game.api.proto.TeleportPlayerRequest;
import com.game.api.proto.TeleportPlayerResponse;
import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * dev 实例管理口（批次 5.3，dungeon-mirror-spec §6.13、§12.2 第 15 条）的 HTTP 适配：运行模式不是 dev / test 一律 403（先于解析请求体、
 * 不碰场景节点）；请求体解不开 / 超长 400；业务结论原样放进应答体的 {@code tip_id}（建副本 / 销毁的规则在 {@code InstanceLifecycleTest}）；
 * 建副本等不到结果按取号失败回 1003；场景节点没在运行 / 处理出错 503。鉴权在 {@link SceneAdminAuthFilter}（另测）。
 */
class SceneAdminControllerTest {

    /** 假的业务入口：记录调用，按测试给的 future 完成。 */
    private static final class FakeAdmin implements SceneAdminController.InstanceAdmin, SceneAdminController.PlayerAdmin {

        final List<String> calls = new ArrayList<>();
        CompletableFuture<CreateDungeonInstanceResponse> created = new CompletableFuture<>();
        CompletableFuture<DestroyInstanceResponse> destroyed = new CompletableFuture<>();
        CompletableFuture<TeleportPlayerResponse> teleported = new CompletableFuture<>();

        @Override
        public CompletableFuture<CreateDungeonInstanceResponse> createDungeon(int dungeonConfigId) {
            calls.add("create " + dungeonConfigId);
            return created;
        }

        @Override
        public CompletableFuture<DestroyInstanceResponse> destroyInstance(long sceneId) {
            calls.add("destroy " + Long.toUnsignedString(sceneId));
            return destroyed;
        }

        @Override
        public CompletableFuture<TeleportPlayerResponse> teleportPlayer(long playerId, double x, double y, double z) {
            calls.add("teleport " + Long.toUnsignedString(playerId) + " " + x + " " + y + " " + z);
            return teleported;
        }
    }

    private final FakeAdmin admin = new FakeAdmin();

    private SceneAdminController controller(RunMode mode) {
        return new SceneAdminController(admin, admin, mode, Duration.ofMillis(100));
    }

    private static MockHttpServletRequest post(String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.addHeader(SceneAdminAuthFilter.OPERATOR_HEADER, "ops");
        request.setContentType(SceneAdminController.CONTENT_TYPE);
        request.setContent(body);
        return request;
    }

    private static byte[] create(int dungeonConfigId) {
        return CreateDungeonInstanceRequest.newBuilder().setDungeonConfigId(dungeonConfigId).build().toByteArray();
    }

    private static byte[] destroy(long sceneId) {
        return DestroyInstanceRequest.newBuilder().setSceneId(sceneId).build().toByteArray();
    }

    private static byte[] teleport(long playerId, double x, double y, double z) {
        return TeleportPlayerRequest.newBuilder().setPlayerId(playerId).setX(x).setY(y).setZ(z).build().toByteArray();
    }

    @Test
    void 运行模式prod一律403_先于解析请求体_不碰场景节点() throws Exception {
        SceneAdminController prod = controller(RunMode.PROD);

        assertThat(prod.create(post(SceneAdminController.CREATE_PATH, create(1))).getStatusCode().value()).isEqualTo(403);
        assertThat(prod.create(post(SceneAdminController.CREATE_PATH, new byte[] {(byte) 0xFF, 0x01})).getStatusCode()
                .value()).as("坏请求体也是 403").isEqualTo(403);
        assertThat(prod.destroy(post(SceneAdminController.DESTROY_PATH, destroy(5))).getStatusCode().value())
                .isEqualTo(403);
        assertThat(admin.calls).isEmpty();
    }

    @Test
    void dev与test放行_建副本成功_应答体是protobuf原样() throws Exception {
        CreateDungeonInstanceResponse ok = CreateDungeonInstanceResponse.newBuilder().setSceneId(0x8000_0000_0000_0001L)
                .setSceneConfigId(17).setSceneNodeId(3).build();
        admin.created = CompletableFuture.completedFuture(ok);

        for (RunMode mode : List.of(RunMode.DEV, RunMode.TEST)) {
            ResponseEntity<byte[]> response = controller(mode).create(post(SceneAdminController.CREATE_PATH, create(1)));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getContentType()).hasToString(SceneAdminController.CONTENT_TYPE);
            assertThat(CreateDungeonInstanceResponse.parseFrom(response.getBody())).isEqualTo(ok);
        }
        assertThat(admin.calls).containsExactly("create 1", "create 1");
    }

    @Test
    void 业务拒绝码原样带回_表里没有3005_取号失败1003() throws Exception {
        SceneAdminController dev = controller(RunMode.DEV);
        admin.created = CompletableFuture.completedFuture(CreateDungeonInstanceResponse.newBuilder().setTipId(3005).build());
        assertThat(CreateDungeonInstanceResponse.parseFrom(dev.create(post(SceneAdminController.CREATE_PATH, create(9)))
                .getBody()).getTipId()).isEqualTo(3005);

        admin.created = CompletableFuture.completedFuture(CreateDungeonInstanceResponse.newBuilder().setTipId(1003).build());
        assertThat(CreateDungeonInstanceResponse.parseFrom(dev.create(post(SceneAdminController.CREATE_PATH, create(1)))
                .getBody()).getTipId()).isEqualTo(1003);
    }

    @Test
    void 建副本等不到结果_按取号失败回1003() throws Exception {
        admin.created = new CompletableFuture<>();

        ResponseEntity<byte[]> response = controller(RunMode.DEV).create(post(SceneAdminController.CREATE_PATH, create(1)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(CreateDungeonInstanceResponse.parseFrom(response.getBody()).getTipId()).isEqualTo(1003);
    }

    @Test
    void 场景节点没在运行或处理出错_503() throws Exception {
        admin.created = CompletableFuture.failedFuture(new IllegalStateException("场景节点没在运行"));
        admin.destroyed = CompletableFuture.failedFuture(new IllegalStateException("逻辑线程已停"));
        SceneAdminController dev = controller(RunMode.DEV);

        assertThat(dev.create(post(SceneAdminController.CREATE_PATH, create(1))).getStatusCode().value()).isEqualTo(503);
        assertThat(dev.destroy(post(SceneAdminController.DESTROY_PATH, destroy(5))).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void 请求体解不开或超长_400_不碰场景节点() throws Exception {
        SceneAdminController dev = controller(RunMode.DEV);

        assertThat(dev.create(post(SceneAdminController.CREATE_PATH, new byte[] {(byte) 0xFF, 0x01})).getStatusCode()
                .value()).isEqualTo(400);
        assertThat(dev.destroy(post(SceneAdminController.DESTROY_PATH,
                new byte[SceneAdminController.MAX_BODY_BYTES + 1])).getStatusCode().value()).isEqualTo(400);
        assertThat(admin.calls).isEmpty();
    }

    @Test
    void 销毁_tip原样带回_号按无符号传给场景节点() throws Exception {
        admin.destroyed = CompletableFuture.completedFuture(DestroyInstanceResponse.newBuilder().setTipId(3000).build());

        ResponseEntity<byte[]> response = controller(RunMode.DEV)
                .destroy(post(SceneAdminController.DESTROY_PATH, destroy(0x8000_0000_0000_0009L)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(DestroyInstanceResponse.parseFrom(response.getBody()).getTipId()).isEqualTo(3000);
        assertThat(admin.calls).containsExactly("destroy 9223372036854775817");
    }

    @Test
    void 瞬移prod先于解析请求体拒绝_不调用逻辑入口() throws Exception {
        SceneAdminController prod = controller(RunMode.PROD);
        for (byte[] body : List.of(teleport(9, 1, 2, 3), new byte[] {(byte) 0xFF, 0x01},
                new byte[SceneAdminController.MAX_BODY_BYTES + 1])) {
            assertThat(prod.teleport(post(SceneAdminController.TELEPORT_PATH, body)).getStatusCode().value())
                    .isEqualTo(403);
        }
        assertThat(admin.calls).isEmpty();
    }

    @Test
    void 瞬移请求体解不开或超长_400且不调用逻辑入口() throws Exception {
        SceneAdminController dev = controller(RunMode.DEV);
        for (byte[] body : List.of(new byte[] {(byte) 0xFF, 0x01}, new byte[SceneAdminController.MAX_BODY_BYTES + 1])) {
            assertThat(dev.teleport(post(SceneAdminController.TELEPORT_PATH, body)).getStatusCode().value())
                    .isEqualTo(400);
        }
        assertThat(admin.calls).isEmpty();
    }

    @Test
    void 瞬移dev与test放行_玩家无符号号与坐标原样传入_tip逐项透传() throws Exception {
        for (RunMode mode : List.of(RunMode.DEV, RunMode.TEST)) {
            for (int tip : List.of(0, 1004, 1005, 1006)) {
                TeleportPlayerResponse expected = TeleportPlayerResponse.newBuilder().setTipId(tip).build();
                admin.teleported = CompletableFuture.completedFuture(expected);
                ResponseEntity<byte[]> response = controller(mode).teleport(post(SceneAdminController.TELEPORT_PATH,
                        teleport(0x8000_0000_0000_0009L, -1.25, -0.0, 3.5)));

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getHeaders().getContentType()).hasToString(SceneAdminController.CONTENT_TYPE);
                assertThat(TeleportPlayerResponse.parseFrom(response.getBody())).isEqualTo(expected);
            }
        }
        assertThat(admin.calls).hasSize(8).allSatisfy(call ->
                assertThat(call).isEqualTo("teleport 9223372036854775817 -1.25 -0.0 3.5"));
    }

    /** 强制走超时分支；不靠线程调度或真实睡眠制造取消竞态。 */
    private static CompletableFuture<TeleportPlayerResponse> timedOut() {
        return new CompletableFuture<>() {
            @Override
            public TeleportPlayerResponse get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException("模拟HTTP等待预算耗尽");
            }
        };
    }

    @Test
    void 瞬移逻辑线程超时_撤掉future并回503() throws Exception {
        admin.teleported = timedOut();
        ResponseEntity<byte[]> response = controller(RunMode.DEV)
                .teleport(post(SceneAdminController.TELEPORT_PATH, teleport(9, 1, 2, 3)));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(admin.teleported.isCancelled()).isTrue();
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("已撤掉（不会再瞬移）");
        assertThat(admin.calls).containsExactly("teleport 9 1.0 2.0 3.0");
    }

    @Test
    void 瞬移超时撤不掉且结果恰好成功_照实返回已定结果() throws Exception {
        admin.teleported = timedOut();
        TeleportPlayerResponse expected = TeleportPlayerResponse.newBuilder().setTipId(1004).build();
        admin.teleported.complete(expected);
        ResponseEntity<byte[]> response = controller(RunMode.DEV)
                .teleport(post(SceneAdminController.TELEPORT_PATH, teleport(9, 1, 2, 3)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(TeleportPlayerResponse.parseFrom(response.getBody())).isEqualTo(expected);
        assertThat(admin.teleported.isCancelled()).isFalse();
    }

    @Test
    void 瞬移异常完成_回503() throws Exception {
        admin.teleported = CompletableFuture.failedFuture(new IllegalStateException("场景节点没在运行"));
        ResponseEntity<byte[]> response = controller(RunMode.DEV)
                .teleport(post(SceneAdminController.TELEPORT_PATH, teleport(9, 1, 2, 3)));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("场景节点没在运行");
    }

    @Test
    void 瞬移等待中断_撤掉future并恢复中断标志() throws Exception {
        admin.teleported = new CompletableFuture<>() {
            @Override
            public TeleportPlayerResponse get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("模拟HTTP线程中断");
            }
        };
        try {
            ResponseEntity<byte[]> response = controller(RunMode.DEV)
                    .teleport(post(SceneAdminController.TELEPORT_PATH, teleport(9, 1, 2, 3)));
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(admin.teleported.isCancelled()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 瞬移审计逐项记录操作人玩家号坐标和tip() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SceneAdminAuthFilter.AUDIT_LOGGER);
        Level levelBefore = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.INFO);
        try {
            admin.teleported = CompletableFuture.completedFuture(TeleportPlayerResponse.newBuilder().setTipId(1006).build());
            MockHttpServletRequest request = post(SceneAdminController.TELEPORT_PATH,
                    teleport(0x8000_0000_0000_0009L, -1.25, 2.5, -0.0));
            request.removeHeader(SceneAdminAuthFilter.OPERATOR_HEADER);
            request.addHeader(SceneAdminAuthFilter.OPERATOR_HEADER,
                    new String("张三".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

            assertThat(controller(RunMode.DEV).teleport(request).getStatusCode().value()).isEqualTo(200);

            assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                    "admin scene player-teleport operator=张三 player_id=9223372036854775817 x=-1.25 y=2.5 z=-0.0 tip=1006");
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(levelBefore);
            captured.stop();
        }
    }
}
