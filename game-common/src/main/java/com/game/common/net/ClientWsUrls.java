package com.game.common.net;

import java.net.URI;

/**
 * 小游戏客户端 WebSocket 地址校验与拼接（对齐 {@code minigame-websocket-gate.md} W7）。
 *
 * <p>fail-closed：{@code CLIENT_WS_BASE_URL} 非空时须以 {@code wss://} 或 {@code ws://} 开头、不含查询串或片段、
 * 含非空主机名，且本节点通告地址须为 IPv4 字面量（ws-edge 仅接受 IPv4 路径段）。
 */
public final class ClientWsUrls {

    public static final String BASE_URL_ENV = "CLIENT_WS_BASE_URL";

    private ClientWsUrls() {
    }

    /** 校验并规范化基地址（去掉尾部斜杠）；为空/空白返回空串。非法抛 {@link IllegalArgumentException}。 */
    public static String parseBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.contains("?") || trimmed.contains("#")) {
            throw new IllegalArgumentException("CLIENT_WS_BASE_URL 不得包含查询串或片段: " + trimmed);
        }
        if (!trimmed.startsWith("wss://") && !trimmed.startsWith("ws://")) {
            throw new IllegalArgumentException("CLIENT_WS_BASE_URL 必须以 ws:// 或 wss:// 开头: " + trimmed);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CLIENT_WS_BASE_URL 不是合法 URI: " + trimmed, e);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("CLIENT_WS_BASE_URL 缺少主机名: " + trimmed);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 构造节点通告给小游戏的 WebSocket 地址：{@code <base>/<ipv4>/<port>}；基地址为空时返回空串。 */
    public static String buildAdvertiseUrl(String rawBaseUrl, String host, int port) {
        String base = parseBaseUrl(rawBaseUrl);
        if (base.isEmpty()) {
            return "";
        }
        if (!isIpv4Literal(host)) {
            throw new IllegalArgumentException("启用 CLIENT_WS_BASE_URL 时节点通告地址必须是 IPv4 字面量: " + host);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("启用 CLIENT_WS_BASE_URL 时端口非法: " + port);
        }
        return base + "/" + host.trim() + "/" + port;
    }

    /** 严格四段十进制 IPv4 字面量校验（不接受域名、空白或前导零）。 */
    public static boolean isIpv4Literal(String host) {
        if (host == null) {
            return false;
        }
        String s = host.trim();
        if (s.isEmpty()) {
            return false;
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return false;
            }
            int val = 0;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                val = val * 10 + (c - '0');
            }
            if (val > 255) {
                return false;
            }
        }
        return true;
    }
}
