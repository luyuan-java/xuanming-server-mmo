package com.game.data.admin;

import com.game.data.metrics.DataMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 运维接口（/admin/**）的鉴权：共享令牌（环境变量 {@code XM_ADMIN_TOKEN}，请求头 {@value #TOKEN_HEADER}，常数时间比较）
 * + 必填操作人（{@value #OPERATOR_HEADER}，UTF-8，1–64 字符、不含控制字符——它会进审计日志）。令牌没配置时一律 503
 * （不提供无鉴权的运维面）。每次调用（含处理中抛异常的）记一行运维审计日志 {@value #AUDIT_LOGGER}。管理端口缺省只绑本机。
 *
 * <p>作用范围只由注册时的 URL 模式（{@code /admin/*}）决定：容器按解码、去掉 {@code ;参数} 之后的规范路径匹配。
 * 这里不再自己按原始 URI 判断要不要过滤——原始 URI 里的 {@code /admin;x/...}、{@code /%61dmin/...} 与规范路径不一致，
 * 自行判断会被绕过。日志与指标也用规范路径。
 */
public final class AdminAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Xm-Admin-Token";
    public static final String OPERATOR_HEADER = "X-Xm-Operator";
    static final String AUDIT_LOGGER = "xm.audit.admin";

    private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);
    /**
     * 指标的 op 标签只取已知接口（任意路径不得变成标签值，否则可被刷成高基数）。精确路径在这里；带路径参数的接口（快照详情、
     * 玩家 / 物品 / 回收……）在 {@link #opOf} 里按前缀归类。
     */
    private static final Map<String, String> KNOWN_OPS = Map.of(
            AuditQueryController.TRANSACTION_LOG_PATH, "transaction_log",
            AuditQueryController.PLAYER_SNAPSHOTS_PATH, "player_snapshots",
            KillSwitchAdminController.PATH, "killswitch");

    private final byte[] token;
    private final DataMetrics metrics;

    public AdminAuthFilter(String token, DataMetrics metrics) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = normalizedPath(request);
        String operator = operator(request.getHeader(OPERATOR_HEADER));
        // 处理中抛异常时 response.getStatus() 还是 200，按 500 记
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            if (token.length == 0) {
                status = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
                response.sendError(status, "运维令牌未配置（XM_ADMIN_TOKEN）");
            } else if (!matches(request.getHeader(TOKEN_HEADER))) {
                status = HttpServletResponse.SC_UNAUTHORIZED;
                response.sendError(status);
            } else if (operator == null) {
                status = HttpServletResponse.SC_BAD_REQUEST;
                response.sendError(status, "缺少操作人（" + OPERATOR_HEADER + "，UTF-8，1–64 字符）");
            } else {
                chain.doFilter(request, response);
                status = response.getStatus();
            }
        } finally {
            audit.info("admin operator={} method={} path={} query={} remote={} status={}",
                    operator == null ? "<invalid>" : operator, request.getMethod(), printable(path),
                    printable(request.getQueryString()),
                    request.getRemoteAddr(), status);
            metrics.adminRequest(opOf(path), Integer.toString(status));
        }
    }

    /**
     * 指标的 op 标签：已知接口取固定值，全服产出封禁 / 区服目录 / 公告 / 白名单 / gate 排空 / 运维快照详情 / 玩家 / 物品 / 回收的
     * 各子路径各归成一个，其余一律 other（任意路径不得变成标签值）。
     */
    static String opOf(String path) {
        String known = KNOWN_OPS.get(path);
        if (known != null) {
            return known;
        }
        if (under(path, AuditQueryController.PLAYER_SNAPSHOTS_PATH)) {
            return "player_snapshots";
        }
        if (under(path, PlayerOpsAdminController.PATH)) {
            return "players";
        }
        if (under(path, ItemAdminController.PATH)) {
            return "items";
        }
        if (under(path, RecallAdminController.PATH)) {
            return "recalls";
        }
        if (under(path, RollbackAdminController.PATH)) {
            return "rollbacks";
        }
        if (under(path, RollbackAdminController.ZONE_SNAPSHOTS_PATH)) {
            return "zone_snapshots";
        }
        if (under(path, OpsJobAdminController.PATH)) {
            return "ops_jobs";
        }
        if (under(path, GainBlockController.PATH)) {
            return "gain_blocks";
        }
        if (under(path, ZoneAdminController.PATH)) {
            return "zones";
        }
        if (under(path, AnnouncementAdminController.PATH)) {
            return "announcements";
        }
        if (under(path, GateDrainAdminController.PATH)) {
            return "gates";
        }
        return under(path, WhitelistAdminController.PATH) ? "whitelist" : "other";
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** 写审计日志前把控制字符换成 ?（路径是解码后的，%0A 之类会变成真换行，可伪造日志行）。 */
    static String printable(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "?");
    }

    /** 容器规范化后的路径（已解码、已去掉 ;参数）。 */
    static String normalizedPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }

    /**
     * 操作人：容器按 ISO-8859-1 解请求头字节，这里还原成 UTF-8（中文名常含 0x80–0x9F 的续字节，按 ISO-8859-1 看是 C1 控制字符）。
     * 不合法（缺失 / 空白 / 超长 / 含控制字符 / 不是合法 UTF-8）返回 null。
     */
    static String operator(String rawHeader) {
        if (rawHeader == null) {
            return null;
        }
        byte[] bytes = rawHeader.getBytes(StandardCharsets.ISO_8859_1);
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
        if (decoded.isBlank() || decoded.codePointCount(0, decoded.length()) > 64
                || decoded.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return decoded;
    }

    private boolean matches(String presented) {
        return presented != null && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }
}
