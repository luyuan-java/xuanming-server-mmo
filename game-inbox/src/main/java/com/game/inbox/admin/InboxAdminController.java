package com.game.inbox.admin;

import com.game.common.deadline.Deadline;
import com.game.inbox.InboxProperties;
import com.game.inbox.rules.InboxTips;
import com.game.inbox.service.GmSanctions;
import com.game.inbox.store.InboxAdminStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.player_inbox.GetPlayerInboxSummaryResponse;
import com.game.proto.player_inbox.GmSanctionPlayerRequest;
import com.game.proto.player_inbox.ListPlayerInboxItemsRequest;
import com.game.proto.player_inbox.ListPlayerInboxItemsResponse;
import com.game.proto.player_inbox.LookupPlayerInboxRequest;
import com.game.proto.player_inbox.LookupPlayerInboxResponse;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxProducerKind;
import com.game.proto.player_inbox.ReconcilePlayerInboxRequest;
import com.game.proto.player_inbox.ReconcilePlayerInboxResponse;
import com.game.proto.player_inbox.VoidPlayerInboxItemRequest;
import com.game.proto.player_inbox.VoidPlayerInboxItemResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.protobuf.util.JsonFormat;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收件箱运维接口（mmorpg {@code PlayerInboxAdmin} 服务的 Java 对应，设计 §5.2、§12：Java 的运维写接口惯例是 HTTP + {@code Idempotency-Key}）。
 * 挂在管理端口（缺省只绑本机），鉴权与每次调用一行审计在 {@link InboxAdminAuthFilter}；操作人取请求头 {@code X-Game-Operator}。
 *
 * <p>请求 / 应答体是契约 proto 消息的 JSON 形状（{@link JsonFormat}，字段名用 proto 原名，枚举用名字），形状与 mmorpg 的 grpcurl 调用一致：
 * 业务结论（含拒绝）在应答的 {@code error_message} 里、HTTP 200；只有请求本身不合法（路径 / 头 / JSON 解析失败、与路径不一致）回 400。
 * <ul>
 *   <li>{@code POST /admin/inbox/players/{id}/sanctions}：GM 处置入箱。{@code Idempotency-Key} = request_id（十进制 uint64，按工单只生成一次，
 *       重试原样带回，issued_at_ms 同理）；体 = {@code GmSanctionPlayerRequest}（issued_at_ms、block_gain / unblock_gain / attach_debt、reason）；</li>
 *   <li>{@code GET .../summary}、{@code GET .../items?domain=&state=&after_seq=&limit=}、{@code GET .../requests/{requestId}?producer_kind=}；</li>
 *   <li>{@code POST .../domains/{domain}/items/{seq}/void}：体 = {@code VoidPlayerInboxItemRequest}（reason、note、mode；强制模式 note 必填，
 *       以 force_verdict = PROVEN 且 state = VOIDED 判成功）；</li>
 *   <li>{@code GET .../reconcile?domain=}：按玩家对账（读已落盘账本）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/inbox/players")
public class InboxAdminController {

    private static final Logger log = LoggerFactory.getLogger(InboxAdminController.class);
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final JsonFormat.Parser PARSER = JsonFormat.parser();
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().preservingProtoFieldNames().alwaysPrintFieldsWithNoPresence();

    private final GmSanctions sanctions;
    private final InboxAdminStore admin;
    private final long budgetMs;

    public InboxAdminController(GmSanctions sanctions, InboxAdminStore admin, InboxProperties properties) {
        this.sanctions = sanctions;
        this.admin = admin;
        this.budgetMs = properties.requestBudget().toMillis();
    }

    @PostMapping(path = "/{playerId}/sanctions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> sanction(@PathVariable String playerId, @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
                                           @RequestBody(required = false) String body, HttpServletRequest http) {
        long player = parseU64(playerId);
        long requestId = parseU64(key);
        if (player == 0 || requestId == 0) {
            return badRequest("路径里的玩家号与 " + IDEMPOTENCY_HEADER + "（十进制 uint64）都必须非 0");
        }
        GmSanctionPlayerRequest.Builder request = GmSanctionPlayerRequest.newBuilder();
        String parsed = parse(body, request);
        if (parsed != null) {
            return badRequest(parsed);
        }
        String operator = operator(http);
        if ((request.getPlayerId() != 0 && request.getPlayerId() != player) || (request.getRequestId() != 0 && request.getRequestId() != requestId)
                || (!request.getOperator().isEmpty() && !request.getOperator().equals(operator))) {
            return badRequest("请求体里的 player_id / request_id / operator 与路径、" + IDEMPOTENCY_HEADER + "、操作人头不一致（不写也行）");
        }
        request.setPlayerId(player).setRequestId(requestId).setOperator(operator);
        return ok(sanctions.sanction(Deadline.after(budgetMs), request.build(), peer(http)));
    }

    @GetMapping(path = "/{playerId}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> summary(@PathVariable String playerId) {
        long player = parseU64(playerId);
        if (player == 0) {
            return badRequest("玩家号必须是非 0 的十进制 uint64");
        }
        return call(() -> admin.summary(Deadline.after(budgetMs), player),
                tip -> GetPlayerInboxSummaryResponse.newBuilder().setPlayerId(player).setErrorMessage(tip).build());
    }

    @GetMapping(path = "/{playerId}/items", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> list(@PathVariable String playerId, @RequestParam(required = false) String domain,
                                       @RequestParam(required = false) String state,
                                       @RequestParam(name = "after_seq", required = false) String afterSeq,
                                       @RequestParam(required = false) String limit) {
        long player = parseU64(playerId);
        PlayerInboxDomain d = parseEnum(domain, PlayerInboxDomain::valueOf, PlayerInboxDomain::forNumber);
        PlayerInboxItemState s = state == null || state.isBlank() ? PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED
                : parseEnum(state, PlayerInboxItemState::valueOf, PlayerInboxItemState::forNumber);
        if (player == 0 || d == null || s == null) {
            return badRequest("需要非 0 的玩家号与合法的 domain（state 可省）");
        }
        Long after = optionalU64(afterSeq);
        Long lim = optionalU64(limit);
        if (after == null || lim == null || Long.compareUnsigned(lim, Integer.MAX_VALUE) > 0) {
            return badRequest("after_seq / limit 必须是十进制无符号整数");
        }
        ListPlayerInboxItemsRequest request = ListPlayerInboxItemsRequest.newBuilder().setPlayerId(player).setDomain(d).setState(s)
                .setAfterSeq(after).setLimit(lim.intValue()).build();
        return call(() -> admin.list(Deadline.after(budgetMs), request), tip -> ListPlayerInboxItemsResponse.newBuilder().setErrorMessage(tip).build());
    }

    @GetMapping(path = "/{playerId}/requests/{requestId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> lookup(@PathVariable String playerId, @PathVariable String requestId,
                                         @RequestParam(name = "producer_kind", required = false) String producerKind) {
        long player = parseU64(playerId);
        long request = parseU64(requestId);
        PlayerInboxProducerKind producer = producerKind == null || producerKind.isBlank() ? PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_GM
                : parseEnum(producerKind, PlayerInboxProducerKind::valueOf, PlayerInboxProducerKind::forNumber);
        if (player == 0 || request == 0 || producer == null) {
            return badRequest("需要非 0 的玩家号、request_id 与合法的 producer_kind（缺省 GM）");
        }
        LookupPlayerInboxRequest lookup = LookupPlayerInboxRequest.newBuilder().setPlayerId(player).setProducerKind(producer)
                .setRequestId(request).build();
        return call(() -> admin.lookup(Deadline.after(budgetMs), lookup), tip -> LookupPlayerInboxResponse.newBuilder().setErrorMessage(tip).build());
    }

    @PostMapping(path = "/{playerId}/domains/{domain}/items/{seq}/void", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> voidItem(@PathVariable String playerId, @PathVariable String domain, @PathVariable String seq,
                                           @RequestBody(required = false) String body, HttpServletRequest http) {
        long player = parseU64(playerId);
        long s = parseU64(seq);
        PlayerInboxDomain d = parseEnum(domain, PlayerInboxDomain::valueOf, PlayerInboxDomain::forNumber);
        if (player == 0 || s == 0 || d == null) {
            return badRequest("需要非 0 的玩家号、seq 与合法的 domain");
        }
        VoidPlayerInboxItemRequest.Builder request = VoidPlayerInboxItemRequest.newBuilder();
        String parsed = parse(body, request);
        if (parsed != null) {
            return badRequest(parsed);
        }
        String operator = operator(http);
        if ((request.getPlayerId() != 0 && request.getPlayerId() != player) || (request.getSeq() != 0 && request.getSeq() != s)
                || (request.getDomainValue() != 0 && request.getDomain() != d)
                || (!request.getOperator().isEmpty() && !request.getOperator().equals(operator))) {
            return badRequest("请求体里的 player_id / domain / seq / operator 与路径、操作人头不一致（不写也行）");
        }
        InboxAdminStore.VoidParams params = new InboxAdminStore.VoidParams(player, d, s, request.getReason(), operator, request.getNote(),
                peer(http), request.getMode());
        return call(() -> admin.voidItem(Deadline.after(budgetMs), params),
                tip -> VoidPlayerInboxItemResponse.newBuilder().setErrorMessage(tip).build());
    }

    @GetMapping(path = "/{playerId}/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reconcile(@PathVariable String playerId, @RequestParam(required = false) String domain) {
        long player = parseU64(playerId);
        PlayerInboxDomain d = domain == null || domain.isBlank() ? PlayerInboxDomain.PLAYER_INBOX_DOMAIN_UNSPECIFIED
                : parseEnum(domain, PlayerInboxDomain::valueOf, PlayerInboxDomain::forNumber);
        if (player == 0 || d == null) {
            return badRequest("需要非 0 的玩家号；domain 可省（= 全部域）");
        }
        ReconcilePlayerInboxRequest request = ReconcilePlayerInboxRequest.newBuilder().setPlayerId(player).setDomain(d).build();
        return call(() -> admin.reconcilePlayer(Deadline.after(budgetMs), request),
                tip -> ReconcilePlayerInboxResponse.newBuilder().setErrorMessage(tip).build());
    }

    // ================================================================ 辅助

    @FunctionalInterface
    private interface StoreCall {
        Message run() throws SQLException;
    }

    /** 库 / 存档故障折成应答里的 kPlayerInboxStoreUnavailable（HTTP 200，同 gRPC 的 in-band 故障码）。 */
    private static ResponseEntity<String> call(StoreCall call, Function<TipInfoMessage, Message> fault) {
        try {
            return ok(call.run());
        } catch (SQLException | RuntimeException e) {
            log.error("[PlayerInbox] 运维接口失败", e);
            return ok(fault.apply(TipInfoMessage.newBuilder().setId(InboxTips.STORE_UNAVAILABLE).build()));
        }
    }

    private static ResponseEntity<String> ok(Message message) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(PRINTER.print(message));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("应答转 JSON 失败", e);
        }
    }

    private static ResponseEntity<String> badRequest(String why) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body(why);
    }

    /** JSON 体 → proto（不认识的字段拒绝）；空体按空消息。返回 null = 成功，否则是原因。 */
    private static String parse(String body, Message.Builder builder) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            PARSER.merge(body, builder);
            return null;
        } catch (InvalidProtocolBufferException | RuntimeException e) {
            return "请求体不是合法的 " + builder.getDescriptorForType().getName() + " JSON: " + e.getMessage();
        }
    }

    /** 十进制 uint64；空 / 非法 / 0 都返回 0（调用方按非法处理）。 */
    static long parseU64(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 可省的十进制 uint64：空 = 0；非法返回 null。 */
    static Long optionalU64(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 枚举名或数值；不认识 / UNSPECIFIED 以外的非法值返回 null。 */
    static <E extends Enum<E> & ProtocolMessageEnum> E parseEnum(String text, Function<String, E> byName, IntFunction<E> byNumber) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            E value = byName.apply(t);
            return value.name().equals("UNRECOGNIZED") ? null : value;
        } catch (IllegalArgumentException e) {
            try {
                return byNumber.apply(Integer.parseUnsignedInt(t));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static String operator(HttpServletRequest http) {
        String operator = InboxAdminAuthFilter.operator(http.getHeader(InboxAdminAuthFilter.OPERATOR_HEADER));
        return operator == null ? "" : operator;
    }

    private static String peer(HttpServletRequest http) {
        return http.getRemoteAddr() + ":" + http.getRemotePort();
    }
}
