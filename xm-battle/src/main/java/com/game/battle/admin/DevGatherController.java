package com.game.battle.admin;

import com.game.api.proto.CreateBattleResult;
import com.game.api.proto.DevGatherRequest;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.DevGatherMode;
import com.game.battle.metrics.BattleMetrics.DevGatherResult;
import com.game.battle.room.RoomOrigin;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.common.RunMode;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleRequest;
import com.game.proto.DestroyBattleRequest;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev / test 专用的 gather 接口（scene-battle-spec §7.18、D27；6.4 的 match 落地之前验收 6.3）：经 {@code SceneBattleService} 真实备战、取 scene 出的
 * 快照、以 {@link RoomOrigin#DEV_GATHER} 建房（照常确认、照常结算）。挂在管理端口（缺省 18112，只绑本机），鉴权与审计在 {@link BattleAdminAuthFilter}
 * （令牌 + 操作人），运行模式不是 dev / test 一律 403（先于解析请求体）。编排见 {@link DevGather}。
 *
 * <table>
 *   <caption>接口</caption>
 *   <tr><th>接口</th><th>请求体</th><th>应答</th></tr>
 *   <tr><td>{@value #GATHER}</td><td>{@code xm.api.DevGatherRequest}</td>
 *       <td>200 / 422 {@code xm.api.DevGatherResponse}（422 = 有人备战失败或建房失败，{@code failure} 写原因，已按升序补发取消）</td></tr>
 *   <tr><td>{@value #CANCEL_PREPARE}</td><td>契约 {@code CancelBattlePrepareRequest}</td>
 *       <td>204（scene 已处理，幂等）；422 玩家此刻没有持有者节点；503 定位 / 调用失败</td></tr>
 * </table>
 * 其余状态：请求体过大 / 不是对应消息 / 形状非法 400；节点没在运行 503。
 *
 * <p><b>线程</b>：Tomcat 线程上做 Redis 查询与 Dubbo 调用并阻塞等待（主路径整体上限 10 s），不碰房间与逻辑线程。
 */
@RestController
public class DevGatherController {

    public static final String GATHER = DevBattleController.BASE + "/gather";
    public static final String CANCEL_PREPARE = DevBattleController.BASE + "/cancel-prepare";

    private static final MediaType PROTOBUF = MediaType.parseMediaType(DevBattleController.CONTENT_TYPE);
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger audit = LoggerFactory.getLogger(BattleAdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(DevGatherController.class);

    /** 节点是否在运行、控制面与本节点号（生产来自 {@code BattleNode}）。 */
    @FunctionalInterface
    public interface Node {

        /** 节点没在运行（启动未完成 / 已停机）时为空。 */
        Optional<Running> running();
    }

    /** 运行中的节点：控制面进程内入口与本节点号。 */
    public record Running(BattleNodeServiceImpl plane, int nodeId) {
    }

    private final DevGather gather;
    private final Node node;
    private final RunMode runMode;
    private final BattleMetrics metrics;

    public DevGatherController(DevGather gather, Node node, RunMode runMode, BattleMetrics metrics) {
        this.gather = Objects.requireNonNull(gather, "gather");
        this.node = Objects.requireNonNull(node, "node");
        this.runMode = Objects.requireNonNull(runMode, "runMode");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @PostMapping(GATHER)
    public ResponseEntity<byte[]> gather(HttpServletRequest http) throws IOException {
        Parsed<DevGatherRequest> parsed = admitAndParse(http, DevGatherRequest.parser(), "DevGatherRequest");
        if (parsed.rejection() != null) {
            metrics.devGather(DevGatherMode.UNKNOWN, parsed.rejection().getStatusCode().value() == HttpStatus.FORBIDDEN.value()
                    ? DevGatherResult.FORBIDDEN : DevGatherResult.REJECTED);
            return parsed.rejection();
        }
        DevGatherRequest request = parsed.request();
        String invalid = DevGather.invalidReason(request);
        if (invalid != null) {
            metrics.devGather(DevGatherMode.UNKNOWN, DevGatherResult.REJECTED);
            audit(http, "dev-gather", request.getBattleId(), "invalid");
            return text(HttpStatus.BAD_REQUEST, "DevGatherRequest 非法：" + invalid);
        }
        DevGatherMode mode = request.getMode() == com.game.api.proto.DevGatherMode.DEV_GATHER_CREATE ? DevGatherMode.CREATE
                : DevGatherMode.PREPARE_ONLY;
        Optional<Running> running = node.running();
        if (running.isEmpty()) {
            metrics.devGather(mode, DevGatherResult.REJECTED);
            audit(http, "dev-gather", request.getBattleId(), "not_running");
            return text(HttpStatus.SERVICE_UNAVAILABLE, "battle 节点没在运行（启动未完成或已停机）");
        }
        DevGather.Outcome outcome = gather.gather(request, plane(running.get()));
        metrics.devGather(mode, outcome.ok() ? DevGatherResult.OK
                : outcome.createAttempted() ? DevGatherResult.CREATE_FAILED : DevGatherResult.PREPARE_FAILED);
        audit(http, "dev-gather", request.getBattleId(), outcome.ok() ? "ok mode=" + request.getMode() : "failed");
        return ResponseEntity.status(outcome.ok() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).contentType(PROTOBUF)
                .body(outcome.response().toByteArray());
    }

    @PostMapping(CANCEL_PREPARE)
    public ResponseEntity<byte[]> cancelPrepare(HttpServletRequest http) throws IOException {
        Parsed<CancelBattlePrepareRequest> parsed = admitAndParse(http, CancelBattlePrepareRequest.parser(), "CancelBattlePrepareRequest");
        if (parsed.rejection() != null) {
            return parsed.rejection();
        }
        DevGather.CancelOutcome outcome = gather.cancelPrepare(parsed.request());
        audit(http, "dev-cancel-prepare", parsed.request().getBattleId(), "status=" + outcome.status());
        if (outcome.status() == HttpStatus.NO_CONTENT.value()) {
            return ResponseEntity.noContent().build();
        }
        return text(HttpStatus.valueOf(outcome.status()), outcome.message());
    }

    // ---------------------------------------------------------------- 内部

    private record Parsed<T>(T request, ResponseEntity<byte[]> rejection) {
    }

    /** 控制面以 DEV_GATHER 来源建房（与 Dubbo 同一条准入与投递路径）。 */
    private static DevGather.Plane plane(Running running) {
        BattleNodeServiceImpl plane = running.plane();
        return new DevGather.Plane() {
            @Override
            public int battleNodeId() {
                return running.nodeId();
            }

            @Override
            public CompletableFuture<CreateBattleResult> create(CreateBattleRequest request) {
                return plane.createBattle(request, RoomOrigin.DEV_GATHER);
            }

            @Override
            public CompletableFuture<?> destroy(DestroyBattleRequest request) {
                return plane.destroyBattle(request);
            }
        };
    }

    /** 运行模式闸（先于解析请求体）→ 读请求体（有上限）→ 解析。 */
    private <T> Parsed<T> admitAndParse(HttpServletRequest http, Parser<T> parser, String type) throws IOException {
        if (runMode != RunMode.DEV && runMode != RunMode.TEST) {
            return new Parsed<>(null, text(HttpStatus.FORBIDDEN, "battle dev 管理接口只在运行模式 dev / test 下开放（当前 "
                    + runMode.name().toLowerCase(Locale.ROOT) + "）"));
        }
        byte[] body;
        try (InputStream in = http.getInputStream()) {
            body = in.readNBytes(DevBattleController.MAX_BODY_BYTES + 1);
        }
        if (body.length > DevBattleController.MAX_BODY_BYTES) {
            return new Parsed<>(null, text(HttpStatus.BAD_REQUEST, "请求体超过 " + DevBattleController.MAX_BODY_BYTES + " 字节"));
        }
        try {
            return new Parsed<>(parser.parseFrom(body), null);
        } catch (InvalidProtocolBufferException e) {
            log.warn("[battle-admin] 请求体不是 {}: {}", type, e.getMessage());
            return new Parsed<>(null, text(HttpStatus.BAD_REQUEST, "请求体不是 " + type + " 的 protobuf 二进制"));
        }
    }

    private static void audit(HttpServletRequest http, String op, long battleId, String result) {
        audit.info("admin battle {} operator={} battle_id={} result={}", op,
                BattleAdminAuthFilter.operator(http.getHeader(BattleAdminAuthFilter.OPERATOR_HEADER)),
                Long.toUnsignedString(battleId), result);
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message.getBytes(StandardCharsets.UTF_8));
    }
}
