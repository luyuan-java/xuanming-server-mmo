package com.game.match.admin;

import com.game.common.RunMode;
import com.game.common.deadline.Deadline;
import com.game.match.MatchProperties;
import com.game.match.activity.ActivityBattleService;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev / test 专用的活动开战接口 {@code POST /admin/match/dev/activity-battle}（match-spec §7.2 末条、§9.10；Java 独有，供 robot 的
 * {@code match-activity} 场景用——真正的调用方 xm-guild 随批次 4.6 接入）。走与 Dubbo 提供方<b>同一个实现</b>（{@link ActivityBattleService}），
 * 所以参数校验、预检、建票、gather 的行为与 xm-guild 将来经 {@code MatchInternalService} 看到的完全一样。
 *
 * <p>请求体是契约 {@code match.StartActivityBattleRequest} 的 protobuf 二进制，应答 200 带 {@code StartActivityBattleResponse} 二进制
 * （{@value #CONTENT_TYPE}）。挂在管理端口（缺省 18113，只绑本机）。HTTP 状态：
 * <ol>
 *   <li>令牌没配 503、令牌错 401：在管理口的鉴权过滤器里（{@code /admin/*}，{@code X-Xm-Admin-Token}，同 xm-trade 播种接口）；</li>
 *   <li>运行模式不是 dev / test → <b>403</b>，先于解析请求体，不读任何依赖；</li>
 *   <li>缺操作人（{@value #OPERATOR_HEADER}，UTF-8，1–64 字符、不含控制字符）→ 400（过滤器也查；这里再查一遍，保证审计行里一定有操作人）；</li>
 *   <li>请求体超过 {@value #MAX_BODY_BYTES} 字节或不是 StartActivityBattleRequest → 400；</li>
 *   <li>其余一律 200：业务拒绝（参数非法、成员不在线 / 在战斗中 / 没准备好、内部故障）在应答的 {@code reject} 里。</li>
 * </ol>
 *
 * <p><b>注意它建的是正常房间</b>（不是 battle 的 dev 房间）：打完照常结算发奖、照常发活动结果事件。所以调用方填的 guild_id / activity_id
 * 应当是不存在的值，结果的消费方（4.6 的 xm-guild）必须把不认识的 (guild, activity) 当终态销账。
 *
 * <p>整请求预算从受理时刻起算（{@code xm.match.request-budget}）；在 Tomcat 线程上执行（只在 dev / test 生效、低频）。每次真正执行了的调用
 * 记一行运维审计（操作人、名单、结果）。
 */
@RestController
public class DevActivityBattleController {

    public static final String PATH = "/admin/match/dev/activity-battle";
    public static final String CONTENT_TYPE = "application/x-protobuf";
    /** 操作人请求头（与管理口鉴权过滤器同名）。 */
    public static final String OPERATOR_HEADER = "X-Xm-Operator";
    /** 运维审计日志的 logger 名（全仓管理口共用）。 */
    static final String AUDIT_LOGGER = "xm.audit.admin";
    /** 请求体上限：合法请求只有至多 5 个玩家号加一个活动上下文，几十字节；留足余量。 */
    static final int MAX_BODY_BYTES = 4 * 1024;
    static final int MAX_OPERATOR_CHARS = 64;

    private static final MediaType PROTOBUF = MediaType.parseMediaType(CONTENT_TYPE);
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(DevActivityBattleController.class);

    private final ActivityBattleService service;
    private final RunMode runMode;
    private final long budgetMillis;

    public DevActivityBattleController(ActivityBattleService service, RunMode runMode, MatchProperties props) {
        this.service = Objects.requireNonNull(service, "service");
        this.runMode = Objects.requireNonNull(runMode, "runMode");
        this.budgetMillis = props.requestBudget().toMillis();
    }

    @PostMapping(PATH)
    public ResponseEntity<byte[]> startActivityBattle(HttpServletRequest http) throws IOException {
        Deadline deadline = Deadline.after(budgetMillis);
        if (runMode != RunMode.DEV && runMode != RunMode.TEST) {
            return text(HttpStatus.FORBIDDEN, "match dev 管理接口只在运行模式 dev / test 下开放（当前 " + runMode.name().toLowerCase(Locale.ROOT) + "）");
        }
        String operator = operator(http.getHeader(OPERATOR_HEADER));
        if (operator == null) {
            return text(HttpStatus.BAD_REQUEST, "缺少操作人（" + OPERATOR_HEADER + "，UTF-8，1–" + MAX_OPERATOR_CHARS + " 字符）");
        }
        byte[] body;
        try (InputStream in = http.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            return text(HttpStatus.BAD_REQUEST, "请求体超过 " + MAX_BODY_BYTES + " 字节");
        }
        StartActivityBattleRequest request;
        try {
            request = StartActivityBattleRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            log.warn("[match-admin] 请求体不是 StartActivityBattleRequest: {}", e.getMessage());
            return text(HttpStatus.BAD_REQUEST, "请求体不是 match.StartActivityBattleRequest 的 protobuf 二进制");
        }
        StartActivityBattleResponse response = service.start(request, deadline);
        audit.info("admin match dev activity-battle operator={} config={} members={} reject={} offender={} battle_id={}", operator,
                Integer.toUnsignedString(request.getBattleConfigId()),
                request.getMemberPlayerIdsList().stream().map(Long::toUnsignedString).toList(), response.getRejectValue(),
                Long.toUnsignedString(response.getOffenderPlayerId()), Long.toUnsignedString(response.getBattleId()));
        return ResponseEntity.ok().contentType(PROTOBUF).body(response.toByteArray());
    }

    /**
     * 操作人：容器按 ISO-8859-1 解请求头字节，这里还原成 UTF-8。不合法（缺失 / 空白 / 超长 / 含控制字符 / 不是合法 UTF-8）返回 null。
     * 规则同全仓管理口的鉴权过滤器（xm-trade 的 {@code TradeAdminAuthFilter.operator}）。
     */
    static String operator(String rawHeader) {
        if (rawHeader == null) {
            return null;
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(rawHeader.getBytes(StandardCharsets.ISO_8859_1))).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        if (decoded.isBlank() || decoded.codePointCount(0, decoded.length()) > MAX_OPERATOR_CHARS
                || decoded.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return decoded;
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message.getBytes(StandardCharsets.UTF_8));
    }
}
