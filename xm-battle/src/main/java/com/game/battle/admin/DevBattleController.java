package com.game.battle.admin;

import com.game.api.proto.CreateBattleResult;
import com.game.battle.admin.DevRoutingResolver.LookupException;
import com.game.battle.admin.DevRoutingResolver.Resolution;
import com.game.battle.room.RoomOrigin;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.common.RunMode;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev / test 专用的 battle 管理接口（battle-node-spec §7.12，§11 N21、Q8）：6.4 的 match 落地之前，robot 经它建房 / 销毁 / 补签 / 登记观众，
 * 让 6.2 能端到端验收。挂在管理端口（缺省 18112，只绑本机），鉴权与每次调用一行审计在 {@link BattleAdminAuthFilter}。
 *
 * <p>请求体与应答都是契约 protobuf 二进制（{@value #CONTENT_TYPE}）：
 * <table>
 *   <caption>接口</caption>
 *   <tr><th>接口</th><th>请求体</th><th>应答</th></tr>
 *   <tr><td>{@value #CREATE}</td><td>{@code CreateBattleRequest}</td><td>200 {@code xm.api.CreateBattleResult}</td></tr>
 *   <tr><td>{@value #DESTROY}</td><td>{@code DestroyBattleRequest}</td><td>204</td></tr>
 *   <tr><td>{@value #ISSUE_TICKET}</td><td>{@code IssueBattleTicketRequest}</td><td>200 {@code IssueBattleTicketResponse}</td></tr>
 *   <tr><td>{@value #ADD_OBSERVER}</td><td>{@code AddObserverRequest}</td><td>200 {@code AddObserverResponse}</td></tr>
 *   <tr><td>{@value #REMOVE_OBSERVER}</td><td>{@code RemoveObserverRequest}</td><td>204</td></tr>
 * </table>
 *
 * <p>HTTP 状态：过滤器先判令牌（没配 503、错 401、缺操作人 400）；运行模式不是 dev / test → <b>403</b>，先于解析请求体；
 * 请求体过大 / 不是对应消息 → 400；建房 / 登记观众时快照路由补不全（{@link DevRoutingResolver}）→ 422 并写明原因、不建房；节点没在运行、
 * 读 Redis 失败、控制面拒绝投递 → 503；5 s 内没有应答 → 504；其余（含 NOT_ALLOCATABLE 与业务 tip）一律 200 / 204，结论在应答体里。
 *
 * <p><b>线程</b>：Redis 查询在 Tomcat 线程上做，然后调用进程内的 {@link BattleNodeServiceImpl}（与 Dubbo 同一条准入与投递路径），限时等结果，
 * 不碰房间。建的房标 {@link RoomOrigin#DEV}：照常推送、照常补发确认，但<b>永不</b>投递结算与结果事件（不会变成发奖口子）。
 */
@RestController
public class DevBattleController {

    public static final String BASE = "/admin/battle/dev";
    public static final String CREATE = BASE + "/create";
    public static final String DESTROY = BASE + "/destroy";
    public static final String ISSUE_TICKET = BASE + "/issue-ticket";
    public static final String ADD_OBSERVER = BASE + "/add-observer";
    public static final String REMOVE_OBSERVER = BASE + "/remove-observer";
    public static final String CONTENT_TYPE = "application/x-protobuf";
    /** 请求体上限：10 名参战者的快照（含宝宝、技能、道具、buff）远小于它。 */
    static final int MAX_BODY_BYTES = 256 * 1024;
    /** 等控制面结果的上限（同 §7.12）。 */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private static final MediaType PROTOBUF = MediaType.parseMediaType(CONTENT_TYPE);
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger audit = LoggerFactory.getLogger(BattleAdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(DevBattleController.class);

    private final DevBattleBackend backend;
    private final DevRoutingResolver routing;
    private final RunMode runMode;

    public DevBattleController(DevBattleBackend backend, DevRoutingResolver routing, RunMode runMode) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.runMode = Objects.requireNonNull(runMode, "runMode");
    }

    @PostMapping(CREATE)
    public ResponseEntity<byte[]> create(HttpServletRequest http) throws IOException {
        Parsed<CreateBattleRequest> parsed = admitAndParse(http, CreateBattleRequest.parser(), "CreateBattleRequest");
        if (parsed.rejection() != null) {
            return parsed.rejection();
        }
        Resolution<CreateBattleRequest> filled;
        try {
            filled = routing.fillCreate(parsed.request());
        } catch (LookupException e) {
            return lookupFailed(e);
        }
        if (!filled.ok()) {
            audit(http, "dev-create", parsed.request().getBattleId(), "unresolved_routing");
            return text(HttpStatus.UNPROCESSABLE_ENTITY, "快照路由补不全，不建房：" + String.join("；", filled.unresolved()));
        }
        Outcome<CreateBattleResult> outcome = call(plane -> plane.createBattle(filled.request(), RoomOrigin.DEV));
        if (outcome.failure() != null) {
            audit(http, "dev-create", parsed.request().getBattleId(), "failed");
            return outcome.failure();
        }
        CreateBattleResult result = outcome.value();
        audit(http, "dev-create", parsed.request().getBattleId(), describe(result));
        return protobuf(result.toByteArray());
    }

    @PostMapping(DESTROY)
    public ResponseEntity<byte[]> destroy(HttpServletRequest http) throws IOException {
        Parsed<DestroyBattleRequest> parsed = admitAndParse(http, DestroyBattleRequest.parser(), "DestroyBattleRequest");
        if (parsed.rejection() != null) {
            return parsed.rejection();
        }
        Outcome<?> outcome = call(plane -> plane.destroyBattle(parsed.request()));
        audit(http, "dev-destroy", parsed.request().getBattleId(), outcome.failure() == null ? "ok" : "failed");
        return outcome.failure() != null ? outcome.failure() : ResponseEntity.noContent().build();
    }

    @PostMapping(ISSUE_TICKET)
    public ResponseEntity<byte[]> issueTicket(HttpServletRequest http) throws IOException {
        Parsed<IssueBattleTicketRequest> parsed = admitAndParse(http, IssueBattleTicketRequest.parser(), "IssueBattleTicketRequest");
        if (parsed.rejection() != null) {
            return parsed.rejection();
        }
        Outcome<IssueBattleTicketResponse> outcome = call(plane -> plane.issueBattleTicket(parsed.request()));
        if (outcome.failure() != null) {
            audit(http, "dev-issue-ticket", parsed.request().getBattleId(), "failed");
            return outcome.failure();
        }
        audit(http, "dev-issue-ticket", parsed.request().getBattleId(), "tip=" + outcome.value().getErrorMessage().getId());
        return protobuf(outcome.value().toByteArray());
    }

    /**
     * 登记观众（dev / test 专用）。<b>不写 match 的观战标记</b>（{@code xm:{match}:watching:<pid>}，xm-match 的 163 才写）：经这个接口登记的观众，
     * match 不知道他在观战——他随后排队 / 被挑战 / 整队开战时，开局前的清退（spectate-spec §4.6）摘不到他，名单里的这一项要等该场结束或调
     * {@value #REMOVE_OBSERVER} 才清；可观战列表与随机观战也与它无关。只在 dev / test 出现，可以接受（spectate-spec Q14、§7.1 第 14 条）；
     * 要验「观战中开局被清退」请走真的 163。
     */
    @PostMapping(ADD_OBSERVER)
    public ResponseEntity<byte[]> addObserver(HttpServletRequest http) throws IOException {
        Parsed<AddObserverRequest> parsed = admitAndParse(http, AddObserverRequest.parser(), "AddObserverRequest");
        if (parsed.rejection() != null) {
            return parsed.rejection();
        }
        Resolution<AddObserverRequest> filled;
        try {
            filled = routing.fillObserver(parsed.request());
        } catch (LookupException e) {
            return lookupFailed(e);
        }
        if (!filled.ok()) {
            audit(http, "dev-add-observer", parsed.request().getBattleId(), "unresolved_routing");
            return text(HttpStatus.UNPROCESSABLE_ENTITY, "观众路由补不全，不登记：" + String.join("；", filled.unresolved()));
        }
        Outcome<AddObserverResponse> outcome = call(plane -> plane.addObserver(filled.request()));
        if (outcome.failure() != null) {
            audit(http, "dev-add-observer", parsed.request().getBattleId(), "failed");
            return outcome.failure();
        }
        audit(http, "dev-add-observer", parsed.request().getBattleId(), "tip=" + outcome.value().getErrorMessage().getId());
        return protobuf(outcome.value().toByteArray());
    }

    @PostMapping(REMOVE_OBSERVER)
    public ResponseEntity<byte[]> removeObserver(HttpServletRequest http) throws IOException {
        Parsed<RemoveObserverRequest> parsed = admitAndParse(http, RemoveObserverRequest.parser(), "RemoveObserverRequest");
        if (parsed.rejection() != null) {
            return parsed.rejection();
        }
        Outcome<?> outcome = call(plane -> plane.removeObserver(parsed.request()));
        audit(http, "dev-remove-observer", parsed.request().getBattleId(), outcome.failure() == null ? "ok" : "failed");
        return outcome.failure() != null ? outcome.failure() : ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- 内部

    private record Parsed<T>(T request, ResponseEntity<byte[]> rejection) {
    }

    private record Outcome<T>(T value, ResponseEntity<byte[]> failure) {
    }

    @FunctionalInterface
    private interface PlaneCall<T> {
        CompletableFuture<T> invoke(BattleNodeServiceImpl plane);
    }

    /** 运行模式闸（先于解析请求体）→ 读请求体（有上限）→ 解析。 */
    private <T> Parsed<T> admitAndParse(HttpServletRequest http, Parser<T> parser, String type) throws IOException {
        if (runMode != RunMode.DEV && runMode != RunMode.TEST) {
            return new Parsed<>(null, text(HttpStatus.FORBIDDEN, "battle dev 管理接口只在运行模式 dev / test 下开放（当前 "
                    + runMode.name().toLowerCase(Locale.ROOT) + "）"));
        }
        byte[] body;
        try (InputStream in = http.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            return new Parsed<>(null, text(HttpStatus.BAD_REQUEST, "请求体超过 " + MAX_BODY_BYTES + " 字节"));
        }
        try {
            return new Parsed<>(parser.parseFrom(body), null);
        } catch (InvalidProtocolBufferException e) {
            log.warn("[battle-admin] 请求体不是 {}: {}", type, e.getMessage());
            return new Parsed<>(null, text(HttpStatus.BAD_REQUEST, "请求体不是 " + type + " 的 protobuf 二进制"));
        }
    }

    /** 调控制面并限时等结果（Tomcat 线程上阻塞，不碰逻辑线程）。 */
    private <T> Outcome<T> call(PlaneCall<T> invocation) {
        Optional<BattleNodeServiceImpl> plane = backend.controlPlane();
        if (plane.isEmpty()) {
            return new Outcome<>(null, text(HttpStatus.SERVICE_UNAVAILABLE, "battle 节点没在运行（启动未完成或已停机）"));
        }
        CompletableFuture<T> future;
        try {
            future = invocation.invoke(plane.get());
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        try {
            return new Outcome<>(future.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), null);
        } catch (TimeoutException e) {
            return new Outcome<>(null, text(HttpStatus.GATEWAY_TIMEOUT, "battle 逻辑线程 " + CALL_TIMEOUT.toSeconds() + " s 内没有应答"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[battle-admin] 控制面调用失败", cause);
            return new Outcome<>(null, text(HttpStatus.SERVICE_UNAVAILABLE, "battle 控制面调用失败：" + cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome<>(null, text(HttpStatus.SERVICE_UNAVAILABLE, "等待被中断"));
        }
    }

    private static ResponseEntity<byte[]> lookupFailed(LookupException e) {
        log.warn("[battle-admin] 补全路由时读 Redis 失败", e);
        return text(HttpStatus.SERVICE_UNAVAILABLE, "补全路由失败（读在线目录 / 位置记录 / scene 目录出错）：" + e.getMessage());
    }

    private static String describe(CreateBattleResult result) {
        StringBuilder out = new StringBuilder("admission=").append(result.getAdmission().name());
        if (!result.getReason().isEmpty()) {
            out.append(" reason=").append(result.getReason());
        }
        if (!result.getResponse().isEmpty()) {
            try {
                out.append(" tip=").append(CreateBattleResponse.parseFrom(result.getResponse()).getErrorMessage().getId());
            } catch (InvalidProtocolBufferException e) {
                out.append(" tip=?");
            }
        }
        return out.toString();
    }

    private static void audit(HttpServletRequest http, String op, long battleId, String result) {
        audit.info("admin battle {} operator={} battle_id={} result={}", op,
                BattleAdminAuthFilter.operator(http.getHeader(BattleAdminAuthFilter.OPERATOR_HEADER)),
                Long.toUnsignedString(battleId), result);
    }

    private static ResponseEntity<byte[]> protobuf(byte[] body) {
        return ResponseEntity.ok().contentType(PROTOBUF).body(body);
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message.getBytes(StandardCharsets.UTF_8));
    }
}
