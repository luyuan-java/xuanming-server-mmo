package com.game.gateway.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 IP（同 mmorpg ClientIpResolver）：只有 socket 对端在可信代理网段里才看 {@code X-Forwarded-For}，从右往左剥掉可信的跳，
 * 第一个不可信的就是客户端；都可信则取对端。只认 IP 字面量，不做 DNS 解析；碰到不是字面量的跳就停在离我们最近的那个可信跳
 * （基线把这一跳原样当客户端 IP，桶键成了任意串）。
 * 没配可信代理时一律取对端（放在负载均衡后面时要配，否则所有请求共用一个 IP 桶）。结果按 {@link #bucketOf} 归一（IPv6 取 /64）。线程安全。
 */
public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** IPv6 字面量最长 45 个字符（含内嵌 IPv4）。 */
    private static final int MAX_LITERAL = 45;

    private final List<Cidr> trusted;

    public ClientIpResolver(List<String> trustedProxies) {
        List<Cidr> parsed = new ArrayList<>();
        for (String spec : trustedProxies) {
            if (spec == null || spec.isBlank()) {
                continue;
            }
            Cidr cidr = Cidr.parse(spec.trim());
            if (cidr == null) {
                throw new IllegalArgumentException("xm.gateway.rate-limit.trusted-proxies 里有非法网段: " + spec);
            }
            parsed.add(cidr);
        }
        this.trusted = List.copyOf(parsed);
        if (trusted.isEmpty()) {
            log.info("限流的客户端 IP：没配可信代理，X-Forwarded-For 一律不看、取对端地址（放在负载均衡后面时要配 trusted-proxies）");
        }
    }

    /**
     * 按请求解析：对端取 {@code getRemoteAddr()}（application.yaml 关掉了 Spring 的 forward-headers 改写，这里就是 socket 对端）；
     * <b>所有</b> {@code X-Forwarded-For} 头按出现顺序拼起来——代理追加一行新头而不是改写客户端带来的那行时，只看第一行等于采信伪造值。
     */
    public String resolve(HttpServletRequest request) {
        List<String> lines = Collections.list(request.getHeaders("X-Forwarded-For"));
        return resolve(request.getRemoteAddr(), lines.isEmpty() ? null : String.join(",", lines));
    }

    /**
     * @param peer          socket 对端地址（{@code request.getRemoteAddr()}）
     * @param forwardedFor  {@code X-Forwarded-For} 请求头（可为空）
     * @return 限流用的客户端标识（{@link #bucketOf}）
     */
    public String resolve(String peer, String forwardedFor) {
        return bucketOf(clientOf(peer, forwardedFor));
    }

    private String clientOf(String peer, String forwardedFor) {
        if (peer == null || peer.isBlank()) {
            return "unknown";
        }
        if (!isTrusted(peer) || forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        String[] hops = forwardedFor.split(",");
        String nearest = peer;
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                continue;
            }
            if (literal(hop) == null) {
                // 不是 IP 字面量（伪造或损坏）：停在离我们最近的那个可信跳——不拿任意串当桶键
                return nearest;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
            nearest = hop;
        }
        return peer;
    }

    /**
     * 限流桶用的客户端标识：IPv4（含 IPv4 映射的 IPv6）取点分四段；IPv6 取 /64 前缀（如 {@code 2001:db8:1:2::/64}）——
     * 一个家庭 / 手机网络通常分到整个 /64，按 /128 分桶的话换个源地址就是一个新桶，IP 限流形同虚设、桶键也随之无界增长。
     * 不是字面量的（对端地址不该出现）原样返回、截到 {@link #MAX_LITERAL} 个字符。
     */
    static String bucketOf(String ip) {
        byte[] addr = literal(ip);
        if (addr == null) {
            return ip.length() > MAX_LITERAL ? ip.substring(0, MAX_LITERAL) : ip;
        }
        if (addr.length == 4) {
            return (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "." + (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            prefix.append(Integer.toHexString(((addr[i] & 0xFF) << 8) | (addr[i + 1] & 0xFF))).append(':');
        }
        return prefix.append(":/64").toString();
    }

    private boolean isTrusted(String ip) {
        if (trusted.isEmpty()) {
            return false;
        }
        byte[] addr = literal(ip);
        if (addr == null) {
            return false;
        }
        for (Cidr cidr : trusted) {
            if (cidr.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /** IP 字面量 → 字节；不是字面量（含主机名）为 null，绝不触发 DNS 解析。 */
    static byte[] literal(String s) {
        if (s.isEmpty() || s.length() > MAX_LITERAL) {
            return null;
        }
        if (s.indexOf(':') < 0) {
            return ipv4(s);
        }
        // 首字符是十六进制数字或冒号、又含冒号的串，InetAddress 只按 IPv6 字面量解析（不合法直接抛），不会去查 DNS；
        // 先滤掉字面量里不可能出现的字符
        if (s.charAt(0) == '.') {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')
                    || ch == '.' || ch == ':';
            if (!ok) {
                return null;
            }
        }
        try {
            return InetAddress.getByName(s).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 点分十进制四段、每段 0..255（不认八进制 / 十六进制 / 省略段的写法）。 */
    private static byte[] ipv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] addr = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char ch = p.charAt(j);
                if (ch < '0' || ch > '9') {
                    return null;
                }
                v = v * 10 + (ch - '0');
            }
            if (v > 255) {
                return null;
            }
            addr[i] = (byte) v;
        }
        return addr;
    }

    private record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String spec) {
            int slash = spec.indexOf('/');
            byte[] addr = literal(slash < 0 ? spec : spec.substring(0, slash));
            if (addr == null) {
                return null;
            }
            int bits = addr.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(spec.substring(slash + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (bits < 0 || bits > addr.length * 8) {
                    return null;
                }
            }
            return new Cidr(addr, bits);
        }

        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = prefixBits / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixBits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }
}
