package com.game.scene.admin;

import com.game.api.proto.CreateDungeonInstanceRequest;
import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.DestroyInstanceRequest;
import com.game.api.proto.DestroyInstanceResponse;
import com.game.common.RunMode;
import com.game.scene.SceneNodeProperties;
import com.game.table.CommonErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev / test 实例管理口（批次 5.3，dungeon-mirror-spec §6.13、Q3；契约 xm-api {@code scene_admin.proto}）：只给 robot 与本机切片验证副本 /
 * 显式销毁用，生产不可达，没有任何客户端消息号。挂在 scene 管理端口（缺省 18104，只绑本机），鉴权与每次调用一行审计在 {@link SceneAdminAuthFilter}。
 * <ul>
 *   <li>{@code POST /admin/scene/instance/create}：{@code CreateDungeonInstanceRequest} → {@code CreateDungeonInstanceResponse}——Dungeon 表没有
 *       这一行 3005；取号调用失败 / 超时 1003；scene-manager 拒绝或本地拒建 3023；成功 0 + 新副本的 scene_id / 地图 / 节点号；</li>
 *   <li>{@code POST /admin/scene/instance/destroy}：{@code DestroyInstanceRequest} → {@code DestroyInstanceResponse}——0 已受理（排空后销毁，
 *       含重复调用）、3000 本节点没有、3005 是主世界频道或号为 0。</li>
 * </ul>
 * 请求 / 应答体是 protobuf 二进制（{@value #CONTENT_TYPE}）。HTTP 状态：过滤器的 503 / 401 / 400；运行模式不是 dev / test → <b>403</b>
 * （先于解析请求体）；请求体超长或解不开 → 400；场景节点没在运行 / 处理出错 → 503；其余 200 + in-band {@code tip_id}。
 *
 * <p>线程：HTTP 线程只做鉴权与编解码，业务经 {@link InstanceAdmin} 投到场景逻辑线程（建副本另经 scene-manager 取号），这里限时等结果。
 */
@RestController
public class SceneAdminController {

    public static final String CREATE_PATH = "/admin/scene/instance/create";
    public static final String DESTROY_PATH = "/admin/scene/instance/destroy";
    public static final String CONTENT_TYPE = "application/x-protobuf";
    /** 请求体上限：两个请求都只有一个整数字段，留足余量。 */
    static final int MAX_BODY_BYTES = 1024;
    /**
     * 等结果的余量：取号自带本地兜底超时，但它从逻辑线程发起调用时才起算，逻辑线程积压时结果可能晚于这个窗口。多等这么久仍没回来就撤掉结果
     * （{@code cancel}）、按取号失败回 1003——撤掉之后逻辑线程不再建这个副本，建好了也当场销毁，不会「回了失败却建出一个没人知道号的副本」。
     */
    static final Duration WAIT_SLACK = Duration.ofSeconds(2);

    private static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    private static final MediaType PROTOBUF = MediaType.parseMediaType(CONTENT_TYPE);
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger audit = LoggerFactory.getLogger(SceneAdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(SceneAdminController.class);

    /** 管理口的业务入口（生产 = {@code SceneNode}：投到逻辑线程执行；任意线程可调、不阻塞）。 */
    public interface InstanceAdmin {

        /**
         * 建副本。调用方等不到结果放弃时 {@code cancel} 返回的 future，实现据此保证不留没人知道号的副本（还没取号就不取、号回来时不建、
         * 刚建好交不回去就当场销毁；见 {@code SceneWorld.createDungeon}）。
         */
        CompletableFuture<CreateDungeonInstanceResponse> createDungeon(int dungeonConfigId);

        CompletableFuture<DestroyInstanceResponse> destroyInstance(long sceneId);
    }

    private final InstanceAdmin admin;
    private final RunMode runMode;
    private final Duration wait;

    /** 生产装配：运行模式取 {@code xm.run-mode}，等结果的上限取 {@code xm.scene.switch-resolve-timeout} 加 {@link #WAIT_SLACK}。 */
    @Autowired
    public SceneAdminController(InstanceAdmin admin, SceneNodeProperties props) {
        this(admin, RunMode.parse(props.runMode()), props.scene().switchResolveTimeout());
    }

    /**
     * @param runMode        运行模式：只有 dev / test 放行
     * @param resolveTimeout 取号的本地兜底超时；HTTP 线程最多等它加 {@link #WAIT_SLACK}
     */
    SceneAdminController(InstanceAdmin admin, RunMode runMode, Duration resolveTimeout) {
        this.admin = admin;
        this.runMode = runMode;
        this.wait = resolveTimeout.plus(WAIT_SLACK);
    }

    @PostMapping(CREATE_PATH)
    public ResponseEntity<byte[]> create(HttpServletRequest request) throws IOException {
        if (!runMode.allowsGmCommands()) {
            return forbidden();
        }
        CreateDungeonInstanceRequest in = parse(request, CreateDungeonInstanceRequest.parser());
        if (in == null) {
            return text(HttpStatus.BAD_REQUEST, "请求体不是 xm.api.CreateDungeonInstanceRequest 的 protobuf 二进制（或超过 "
                    + MAX_BODY_BYTES + " 字节）");
        }
        CompletableFuture<CreateDungeonInstanceResponse> pending = admin.createDungeon(in.getDungeonConfigId());
        CreateDungeonInstanceResponse out;
        try {
            out = pending.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            out = abandon(pending);
            if (out == null) {
                log.warn("管理口建副本 {} ms 内没有结果，撤掉结果（不再建）、按取号失败回 1003 dungeon_config_id={}",
                        wait.toMillis(), in.getDungeonConfigId());
                out = CreateDungeonInstanceResponse.newBuilder().setTipId(SERVICE_UNAVAILABLE).build();
            }
        } catch (ExecutionException e) {
            return unavailable(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out = abandon(pending);
            if (out == null) {
                return text(HttpStatus.SERVICE_UNAVAILABLE, "等待结果被中断（已撤掉，不会再建）");
            }
        }
        audit.info("admin scene instance-create operator={} dungeon_config_id={} tip={} scene_id={} scene_config_id={}",
                SceneAdminAuthFilter.operator(request.getHeader(SceneAdminAuthFilter.OPERATOR_HEADER)),
                Integer.toUnsignedString(in.getDungeonConfigId()), out.getTipId(), Long.toUnsignedString(out.getSceneId()),
                out.getSceneConfigId());
        return ResponseEntity.ok().contentType(PROTOBUF).body(out.toByteArray());
    }

    @PostMapping(DESTROY_PATH)
    public ResponseEntity<byte[]> destroy(HttpServletRequest request) throws IOException {
        if (!runMode.allowsGmCommands()) {
            return forbidden();
        }
        DestroyInstanceRequest in = parse(request, DestroyInstanceRequest.parser());
        if (in == null) {
            return text(HttpStatus.BAD_REQUEST, "请求体不是 xm.api.DestroyInstanceRequest 的 protobuf 二进制（或超过 "
                    + MAX_BODY_BYTES + " 字节）");
        }
        DestroyInstanceResponse out;
        try {
            out = admin.destroyInstance(in.getSceneId()).get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return text(HttpStatus.SERVICE_UNAVAILABLE, "场景逻辑线程 " + wait.toMillis() + " ms 内没有执行完");
        } catch (ExecutionException e) {
            return unavailable(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return text(HttpStatus.SERVICE_UNAVAILABLE, "等待结果被中断");
        }
        audit.info("admin scene instance-destroy operator={} scene_id={} tip={}",
                SceneAdminAuthFilter.operator(request.getHeader(SceneAdminAuthFilter.OPERATOR_HEADER)),
                Long.toUnsignedString(in.getSceneId()), out.getTipId());
        return ResponseEntity.ok().contentType(PROTOBUF).body(out.toByteArray());
    }

    /**
     * 不再等建副本的结果：撤掉它（逻辑线程据此不建 / 建好即销毁）。撤不掉说明结果恰好在这一刻定了——成功就照实返回（号带回去，不留没人知道的副本），
     * 失败返回 null（按调用方的口径答）。{@code cancel} 与逻辑线程上的 {@code complete} 原子互斥，恰好一边赢。
     */
    private static CreateDungeonInstanceResponse abandon(CompletableFuture<CreateDungeonInstanceResponse> pending) {
        if (pending.cancel(false)) {
            return null;
        }
        return pending.state() == Future.State.SUCCESS ? pending.resultNow() : null;
    }

    /** 读请求体并解析；超长或解不开为 null。 */
    private static <M extends Message> M parse(HttpServletRequest request, Parser<M> parser) throws IOException {
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            return null;
        }
        try {
            return parser.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    private ResponseEntity<byte[]> forbidden() {
        return text(HttpStatus.FORBIDDEN, "实例管理口只在运行模式 dev / test 下开放（当前 " + runMode + "）");
    }

    private static ResponseEntity<byte[]> unavailable(Throwable cause) {
        log.warn("管理口调用失败（场景节点没在运行或处理出错）: {}", String.valueOf(cause));
        return text(HttpStatus.SERVICE_UNAVAILABLE, "场景节点没在运行或处理出错：" + cause);
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message.getBytes(StandardCharsets.UTF_8));
    }
}
