package com.game.inbox.admin;

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
 * 收件箱运维接口（{@code /admin/inbox/*}）的鉴权（mmorpg 设计 §1.6：运维面用<b>独立</b>的口令，常驻注入，不与回档等运维口令共用；空 = 接口停用）。
 * 语义同 game-data / game-trade 的运维过滤器：
 * <ul>
 *   <li>令牌只从环境变量 {@value #TOKEN_ENV} 读（不进配置文件），请求头 {@value #TOKEN_HEADER}，常数时间比较；没配置一律 503；令牌错 401；</li>
 *   <li>必填操作人 {@value #OPERATOR_HEADER}（UTF-8，1–64 字节、不含控制字符）：它进条目的 operator_name 与审计行，缺失 / 不合法 400；</li>
 *   <li>每次调用（含处理中抛异常的）记一行运维审计日志 {@value #AUDIT_LOGGER}，带调用方地址（审计行里唯一不可伪造的部分）。</li>
 * </ul>
 * 作用范围只由注册时的 URL 模式决定（容器按规范化后的路径匹配）。管理端口缺省只绑本机。
 */
public final class InboxAdminAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_ENV = "GAME_INBOX_ADMIN_TOKEN";
    public static final String TOKEN_HEADER = "X-Game-Admin-Token";
    public static final String OPERATOR_HEADER = "X-Game-Operator";
    static final String AUDIT_LOGGER = "game.audit.admin";
    /** 与条目 operator_name 的上限同值（字节）。 */
    static final int MAX_OPERATOR_BYTES = 64;

    private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);

    private final byte[] token;

    /** @param token 运维令牌（null / 空 = 没配置：一律 503） */
    public InboxAdminAuthFilter(String token) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean tokenConfigured() {
        return token.length > 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        String operator = operator(request.getHeader(OPERATOR_HEADER));
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            if (token.length == 0) {
                status = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
                response.sendError(status, "收件箱运维令牌未配置（" + TOKEN_ENV + "）");
            } else if (!matches(request.getHeader(TOKEN_HEADER))) {
                status = HttpServletResponse.SC_UNAUTHORIZED;
                response.sendError(status);
            } else if (operator == null) {
                status = HttpServletResponse.SC_BAD_REQUEST;
                response.sendError(status, "缺少操作人（" + OPERATOR_HEADER + "，UTF-8，1–64 字节）");
            } else {
                chain.doFilter(request, response);
                status = response.getStatus();
            }
        } finally {
            audit.info("inbox-admin operator={} method={} path={} query={} remote={} status={}", operator == null ? "<invalid>" : operator,
                    request.getMethod(), printable(path), printable(request.getQueryString()), request.getRemoteAddr(), status);
        }
    }

    /** 写审计日志前把控制字符换成 ?（解码后的路径里 %0A 会变成真换行，可伪造日志行）。 */
    static String printable(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "?");
    }

    /**
     * 操作人：容器按 ISO-8859-1 解请求头字节，这里还原成 UTF-8。不合法（缺失 / 空白 / 超过 64 字节 / 含控制字符 / 不是合法 UTF-8）返回 null。
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
        if (decoded.isBlank() || bytes.length > MAX_OPERATOR_BYTES || decoded.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return decoded;
    }

    private boolean matches(String presented) {
        return presented != null && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }
}
