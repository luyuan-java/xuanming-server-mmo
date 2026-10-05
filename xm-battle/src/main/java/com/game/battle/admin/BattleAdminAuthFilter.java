package com.game.battle.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * xm-battle 管理端口运维接口（{@code /admin/**}，6.2 只有 dev / test 专用的 {@code /admin/battle/dev/*}）的鉴权（battle-node-spec §7.12；
 * 语义同 xm-trade 的 {@code TradeAdminAuthFilter} / xm-data 的 {@code AdminAuthFilter}，xm-common 没有 servlet 依赖，只能各复制一份）：
 * <ul>
 *   <li>共享令牌：环境变量 {@value #TOKEN_ENV}（只从环境变量读，不进配置文件），请求头 {@value #TOKEN_HEADER}，常数时间比较；</li>
 *   <li>必填操作人 {@value #OPERATOR_HEADER}：UTF-8，1–64 字符、不含控制字符（它会进审计日志）；</li>
 *   <li>令牌没配置时一律 503（不提供无鉴权的运维面）；令牌错 401；缺操作人 400；</li>
 *   <li>每次调用（含处理中抛异常的）记一行运维审计日志 {@value #AUDIT_LOGGER}。</li>
 * </ul>
 * 运行模式（dev / test 才开放，否则 403）由 {@link DevBattleController} 判定，排在本过滤器之后：令牌仍是第一道闸。
 * 作用范围只由注册时的 URL 模式（{@code /admin/*}）决定：容器按解码、去掉 {@code ;参数} 之后的规范路径匹配；这里不再自己按原始 URI 判断
 * （原始 URI 里的 {@code /admin;x/...}、{@code /%61dmin/...} 与规范路径不一致，自行判断会被绕过）。日志用规范路径。
 * 管理端口缺省只绑本机（{@code XM_MANAGEMENT_ADDRESS}），不要把它挂到对外端口。
 */
public final class BattleAdminAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_ENV = "XM_ADMIN_TOKEN";
    public static final String TOKEN_HEADER = "X-Xm-Admin-Token";
    public static final String OPERATOR_HEADER = "X-Xm-Operator";
    static final String AUDIT_LOGGER = "xm.audit.admin";
    static final int MAX_OPERATOR_CHARS = 64;

    private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);

    private final byte[] token;

    /** @param token 运维令牌（null / 空 = 没配置：一律 503） */
    public BattleAdminAuthFilter(String token) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    /** 是否配置了令牌（启动日志用）。 */
    public boolean tokenConfigured() {
        return token.length > 0;
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
            } else {
                chain.doFilter(request, response);
                status = response.getStatus();
            }
        } finally {
            audit.info("admin operator={} method={} path={} query={} remote={} status={}",
                    operator == null ? "<invalid>" : operator, request.getMethod(), printable(path),
                    printable(request.getQueryString()), request.getRemoteAddr(), status);
        }
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
