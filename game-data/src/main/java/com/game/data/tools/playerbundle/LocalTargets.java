package com.game.data.tools.playerbundle;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 导入的写前闸门一：写目标必须是本机。线上数据只许进隔离环境，把线上连接串误当本地是最可能的事故。
 *
 * <p>只认 {@code localhost} 与回环 IP 字面量，<b>不做 DNS 解析</b>（解析结果可被篡改，也会把线上域名误判成本机）；本地 docker 服务名
 * 经 {@code --allow-target-host} 显式放行（精确匹配、不分大小写）。挡不住「127.0.0.1 上是 port-forward 出来的线上实例」——那由闸门二
 * {@link InstanceIdentity} 比对实例身份兜住。
 *
 * <p>报错文本只含主机名，不含整串 URL（JDBC URL 可能带口令）。
 */
final class LocalTargets {

    private LocalTargets() {
    }

    /**
     * 核对 JDBC URL 的主机；通过返回 null，否则返回报错文本。只接受单主机的 {@code jdbc:mysql://host[:port]/...} 与测试用的
     * {@code jdbc:h2:mem:}（内存库就在本进程里）；多主机、负载均衡 / 复制协议一律拒绝（分不清最终写到哪台）。
     */
    static String checkJdbcUrl(String url, Set<String> allowHosts) {
        if (url == null || !url.startsWith("jdbc:")) {
            return "JDBC URL 不是 jdbc: 开头";
        }
        if (url.startsWith("jdbc:h2:mem:")) {
            return null;
        }
        if (!url.startsWith("jdbc:mysql://")) {
            return "JDBC URL 只支持单主机的 jdbc:mysql://（多主机 / 负载均衡 / 复制协议分不清写到哪台，拒绝）";
        }
        String rest = url.substring("jdbc:mysql://".length());
        int end = indexOfAny(rest, "/?");
        String authority = end < 0 ? rest : rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.isEmpty() || authority.contains(",") || authority.contains("(")) {
            return "JDBC URL 必须恰好写一个主机（多主机或地址表达式分不清写到哪台，拒绝）";
        }
        return checkHost("MySQL", hostOf(authority), allowHosts);
    }

    /** JDBC URL 的 {@code host:port}（导出包 / 预览里记录来源与目标用；不含账号口令与参数）。认不出时回 {@code unknown}。 */
    static String describeJdbc(String url) {
        if (url == null) {
            return "unknown";
        }
        if (url.startsWith("jdbc:h2:mem:")) {
            return "h2:mem";
        }
        if (!url.startsWith("jdbc:mysql://")) {
            return "unknown";
        }
        String rest = url.substring("jdbc:mysql://".length());
        int end = indexOfAny(rest, "/?");
        String authority = end < 0 ? rest : rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        return at >= 0 ? authority.substring(at + 1) : authority;
    }

    /** Redis 地址的 {@code host:port}（不含口令）。认不出时回 {@code unknown}。 */
    static String describeRedis(String address) {
        try {
            URI uri = new URI(address);
            return uri.getHost() == null ? "unknown" : uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (URISyntaxException e) {
            return "unknown";
        }
    }

    /** 核对 Redisson 地址（{@code redis://host:port} / {@code rediss://host:port}）；通过返回 null，否则返回报错文本。 */
    static String checkRedisAddress(String address, Set<String> allowHosts) {
        URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) {
            return "Redis 地址不是合法 URI";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("redis") && !scheme.equals("rediss")) {
            return "Redis 地址必须是 redis:// 或 rediss://";
        }
        return checkHost("Redis", uri.getHost(), allowHosts);
    }

    static String checkHost(String what, String host, Set<String> allowHosts) {
        if (host == null || host.isBlank()) {
            return what + " 目标主机为空";
        }
        String bare = host.strip();
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        if (isLoopback(bare)) {
            return null;
        }
        for (String allowed : allowHosts) {
            if (allowed.equalsIgnoreCase(bare)) {
                return null;
            }
        }
        return what + " 目标主机 " + bare + " 不是本机回环地址；导入只写隔离环境。确是本地 docker 服务名时用 --allow-target-host "
                + bare + " 显式放行";
    }

    /** {@code localhost} 或回环 IP 字面量（IPv4 127/8、IPv6 ::1）；主机名一律不解析。 */
    static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!looksLikeIpLiteral(host)) {
            return false;
        }
        try {
            // 字面量才走到这里：InetAddress.getByName 对 IP 字面量只做格式解析，不查 DNS
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksLikeIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return host.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.');
        }
        return host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.') && host.chars().filter(c -> c == '.').count() == 3;
    }

    private static String hostOf(String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 0 ? authority : authority.substring(1, close);
        }
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
