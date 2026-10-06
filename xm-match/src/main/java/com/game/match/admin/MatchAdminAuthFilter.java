package com.game.match.admin;

import com.game.common.RunMode;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.AdminOp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * xm-match 管理端口运维接口（{@code /admin/**}；6.4 只有 dev / test 专用的 {@code /admin/match/dev/*}：读评分、活动开战）的鉴权
 * （match-spec §9.10；语义同 xm-trade 的 {@code TradeAdminAuthFilter} / xm-battle 的 {@code BattleAdminAuthFilter}，xm-common 没有 servlet 依赖，
 * 只能各复制一份）。判定次序固定：
 * <ol>
 *   <li>令牌没配置（环境变量 {@value #TOKEN_ENV}，只从环境变量读、不进配置文件）→ 一律 503：不提供无鉴权的运维面；</li>
 *   <li>请求头 {@value #TOKEN_HEADER} 与令牌不符（常数时间比较）→ 401；</li>
 *   <li>缺操作人 {@value #OPERATOR_HEADER}（UTF-8，1–64 字符、不含控制字符；它会进审计日志）→ 400；</li>
 *   <li>路径在 {@value #DEV_PREFIX} 之下而运行模式不是 dev / test → 403。<b>令牌仍是第一道闸</b>：dev 口在 prod 也注册（只回 403），
 *       没带对令牌的人看不出这一点。这条判定放在过滤器里而不是各控制器里：dev 口的两个控制器不必各自记得去查运行模式。</li>
 * </ol>
 * 每次调用（含被拒的、处理中抛异常的）记一行运维审计日志 {@value #AUDIT_LOGGER}，并计 {@code xm_match_admin_requests_total{op, status}}
 * （{@code op} 只取已知接口，其余一律 {@code other}：任意路径不得变成标签值）。
 *
 * <p>作用范围只由注册时的 URL 模式（{@code /admin/*}）决定：容器按解码、去掉 {@code ;参数} 之后的规范路径匹配；这里判 dev 前缀、日志与指标也都用规范路径
 * （原始 URI 里的 {@code /admin;x/...}、{@code /%61dmin/...} 与规范路径不一致，自行按原始 URI 判断会被绕过）。
 * 管理端口缺省只绑本机（{@code XM_MANAGEMENT_ADDRESS}），不要把它挂到对外端口。
 */
public final class MatchAdminAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_ENV = "XM_ADMIN_TOKEN";
    public static final String TOKEN_HEADER = "X-Xm-Admin-Token";
    public static final String OPERATOR_HEADER = "X-Xm-Operator";
    /** 运维审计日志的 logger 名（全仓管理口同一个）。 */
    public static final String AUDIT_LOGGER = "xm.audit.admin";

    /** dev / test 专用接口的路径前缀（规范路径）。 */
    public static final String DEV_PREFIX = "/admin/match/dev/";
    /** {@code GET /admin/match/dev/rating/{pid}}：读评分。 */
    public static final String DEV_RATING_PREFIX = DEV_PREFIX + "rating/";
    /** {@code POST /admin/match/dev/activity-battle}：活动开战（protobuf 字节进出）。 */
    public static final String DEV_ACTIVITY_BATTLE_PATH = DEV_PREFIX + "activity-battle";

    static final int MAX_OPERATOR_CHARS = 64;

    private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);

    private final byte[] token;
    private final RunMode runMode;
    private final MatchMetrics metrics;

    /**
     * @param token   运维令牌（null / 空 = 没配置：一律 503）
     * @param runMode 运行模式（只有 dev / test 放行 dev 口）
     */
    public MatchAdminAuthFilter(String token, RunMode runMode, MatchMetrics metrics) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.runMode = Objects.requireNonNull(runMode, "runMode");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 是否配置了令牌（启动日志用）。 */
    public boolean tokenConfigured() {
        return token.length > 0;
    }

    /** dev 口在当前运行模式下是否放行（启动日志用）。 */
    public boolean devEndpointsOpen() {
        return runMode == RunMode.DEV || runMode == RunMode.TEST;
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
                response.sendError(status, "运维令牌未配置（" + TOKEN_ENV + "）");
            } else if (!matches(request.getHeader(TOKEN_HEADER))) {
                status = HttpServletResponse.SC_UNAUTHORIZED;
                response.sendError(status);
            } else if (operator == null) {
                status = HttpServletResponse.SC_BAD_REQUEST;
                response.sendError(status, "缺少操作人（" + OPERATOR_HEADER + "，UTF-8，1–64 字符）");
            } else if (isDevPath(path) && !devEndpointsOpen()) {
                status = HttpServletResponse.SC_FORBIDDEN;
                response.sendError(status, "dev 管理口只在运行模式 dev / test 下开放（当前 " + runMode.name().toLowerCase(Locale.ROOT) + "）");
            } else {
                chain.doFilter(request, response);
                status = response.getStatus();
            }
        } finally {
            audit.info("admin operator={} method={} path={} query={} remote={} status={}",
                    operator == null ? "<invalid>" : operator, request.getMethod(), printable(path),
                    printable(request.getQueryString()), request.getRemoteAddr(), status);
            metrics.adminRequest(opOf(path), status);
        }
    }

    /** 是不是 dev / test 专用接口（按规范路径的前缀）。 */
    static boolean isDevPath(String path) {
        return path.startsWith(DEV_PREFIX);
    }

    /** 指标的 op 标签：已知接口取固定值，其余一律 other（任意路径不得变成标签值，否则可被刷成高基数）。 */
    static AdminOp opOf(String path) {
        if (path.startsWith(DEV_RATING_PREFIX)) {
            return AdminOp.RATING;
        }
        return DEV_ACTIVITY_BATTLE_PATH.equals(path) ? AdminOp.ACTIVITY_BATTLE : AdminOp.OTHER;
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
     * 不合法（缺失 / 空白 / 超长 / 含控制字符 / 不是合法 UTF-8）返回 null。dev 口的控制器写自己的审计行时也用它取操作人。
     */
    public static String operator(String rawHeader) {
        if (rawHeader == null) {
            return null;
        }
        byte[] bytes = rawHeader.getBytes(StandardCharsets.ISO_8859_1);
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        if (decoded.isBlank() || decoded.codePointCount(0, decoded.length()) > MAX_OPERATOR_CHARS
                || decoded.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return decoded;
    }

    private boolean matches(String presented) {
        return presented != null && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }
}
