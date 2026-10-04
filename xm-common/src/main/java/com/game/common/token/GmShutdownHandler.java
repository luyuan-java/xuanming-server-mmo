package com.game.common.token;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GM 签名停机的受理逻辑（gate 与 scene 共用；HTTP 适配在各自的控制器里，这里不依赖 Servlet）：
 * <ul>
 *   <li>只收本机来的请求（管理端口为了让 Prometheus 跨机抓取可能绑到内网地址，停机接口不能跟着暴露）；
 *       确需远程调用时显式打开 {@code allowRemote}；</li>
 *   <li>{@code GET /gm/identity}：回本进程的 {@code zone_id / node_id / instance_id}，签名工具据此签名；</li>
 *   <li>{@code POST /gm/graceful-shutdown}：签名信封与原因全在请求头里（没有请求体：未认证的调用方塞不进大块数据），
 *       签名目标是 {@code 区:节点号:实例}（{@link #target}）——Java 的节点号按区分配、重启后复用，只绑节点号的签名能拿去停
 *       别的区同号节点、或重启后的新进程；绑上实例 id 之后一份签名只对这一个进程有效。</li>
 * </ul>
 * 拒绝只打 ERROR（操作人、原因截到 128 字符、控制字符替换），回 403 不带原因；节点没在运行回 503。线程安全。
 */
public final class GmShutdownHandler {

    public static final String OPERATOR_HEADER = "X-Xm-Gm-Operator";
    public static final String TIMESTAMP_HEADER = "X-Xm-Gm-Timestamp";
    public static final String NONCE_HEADER = "X-Xm-Gm-Nonce";
    public static final String SIGNATURE_HEADER = "X-Xm-Gm-Signature";
    /** 原因：UTF-8 百分号编码（{@link java.net.URLEncoder} 的写法）。 */
    public static final String REASON_HEADER = "X-Xm-Gm-Reason";
    static final int LOG_FIELD_MAX = 128;

    private static final Logger log = LoggerFactory.getLogger(GmShutdownHandler.class);

    /** 本进程的身份；节点号 ≤ 0 表示没在运行。 */
    public record Identity(int zoneId, int nodeId, String instanceId) {
    }

    /** 应答：HTTP 状态、JSON 体、是否已受理（受理了调用方在应答写出后退出进程）。 */
    public record Reply(int status, Map<String, Object> body, boolean accepted) {
    }

    /** 请求头里的信封与原因（原样，未解码）。 */
    public record Request(String remoteAddr, String operator, String timestamp, String nonce, String signature,
                          String encodedReason) {
    }

    private final String method;
    private final GmRequestAuth auth;
    private final Supplier<Identity> identity;
    private final IntSupplier affected;
    private final boolean allowRemote;

    /**
     * @param method      方法名（{@code Gate.GmGracefulShutdown} / {@code Scene.GmGracefulShutdown}）
     * @param affected    受理时回的影响数（gate 会话数 / scene 在线人数）
     * @param allowRemote 是否接受非本机来的请求
     */
    public GmShutdownHandler(String method, GmRequestAuth auth, Supplier<Identity> identity, IntSupplier affected,
                             boolean allowRemote) {
        this.method = method;
        this.auth = auth;
        this.identity = identity;
        this.affected = affected;
        this.allowRemote = allowRemote;
    }

    /** 签名目标：{@code 区:节点号:实例}。 */
    public static String target(int zoneId, int nodeId, String instanceId) {
        return zoneId + ":" + nodeId + ":" + instanceId;
    }

    public Reply identity(String remoteAddr) {
        if (!allowed(remoteAddr)) {
            return forbidden();
        }
        Identity id = identity.get();
        if (id.nodeId() <= 0) {
            return notRunning();
        }
        return new Reply(200, Map.of("zone_id", id.zoneId(), "node_id", id.nodeId(), "instance_id", id.instanceId(),
                "method", method), false);
    }

    public Reply shutdown(Request request) {
        if (!allowed(request.remoteAddr())) {
            log.error("GM 停机请求被拒：不是本机来的 method={} remote={}", method, clip(request.remoteAddr()));
            return forbidden();
        }
        Identity id = identity.get();
        if (id.nodeId() <= 0) {
            return notRunning();
        }
        String reason = decode(request.encodedReason());
        GmRequestAuth.Result result = reason == null ? GmRequestAuth.Result.MALFORMED_ENVELOPE
                : auth.verify(method, target(id.zoneId(), id.nodeId(), id.instanceId()),
                        new GmRequestAuth.Envelope(request.operator(), request.timestamp(), request.nonce(),
                                request.signature()), reason);
        if (result != GmRequestAuth.Result.OK) {
            log.error("GM 停机请求被拒 method={} zone={} node_id={} result={} operator={} reason={}", method, id.zoneId(),
                    id.nodeId(), result.logName(), clip(request.operator()), clip(reason));
            return forbidden();
        }
        int count = affected.getAsInt();
        log.warn("GM 停机已受理 method={} zone={} node_id={} instance={} operator={} reason={} 影响={}", method, id.zoneId(),
                id.nodeId(), id.instanceId(), request.operator(), clip(reason), count);
        return new Reply(200, Map.of("affected_count", count), true);
    }

    /** 本机来的（或打开了远程）才受理。对端地址是 IP 字面量，不会触发 DNS。 */
    private boolean allowed(String remoteAddr) {
        if (allowRemote) {
            return true;
        }
        if (remoteAddr == null) {
            return false;
        }
        String a = remoteAddr.startsWith("[") && remoteAddr.endsWith("]")
                ? remoteAddr.substring(1, remoteAddr.length() - 1) : remoteAddr;
        return a.startsWith("127.") || a.equals("::1") || a.equals("0:0:0:0:0:0:0:1");
    }

    /** 百分号解码；没有为空串；不合法为 null（按信封不合法拒）。 */
    static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            String reason = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            return reason.length() > GmRequestAuth.MAX_REASON_LENGTH ? null : reason;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String clip(String s) {
        if (s == null) {
            return "";
        }
        String head = s.length() <= LOG_FIELD_MAX ? s : s.substring(0, LOG_FIELD_MAX) + "…";
        StringBuilder out = new StringBuilder(head.length());
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }

    private static Reply forbidden() {
        return new Reply(403, Map.of("error", "forbidden"), false);
    }

    private static Reply notRunning() {
        return new Reply(503, Map.of("error", "not_running"), false);
    }
}
