import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * GM 签名停机的运维工具（同 mmorpg 对 Gate.GmGracefulShutdown / Scene.GmGracefulShutdown 的签名调用）：
 * 先 {@code GET /gm/identity} 取目标进程的区、节点号、实例 id 与方法名，按 {@code com.game.common.token.GmRequestAuth} 的
 * canonical 串签名（目标 = {@code 区:节点号:实例}，签名只对这一个进程有效），再 {@code POST /gm/graceful-shutdown}
 * （信封与原因都在请求头里），打印应答（{@code affected_count} = gate 的会话数 / scene 的在线玩家数）。
 *
 * <p>密钥只从环境变量 {@code XM_GM_ADMIN_SECRET} 读（不接受命令行传入，免得进 shell 历史）。接口只收本机来的请求，
 * 在目标机器上运行（或目标打开了 XM_GM_ALLOW_REMOTE）。
 *
 * <p>用法（JDK 21 单文件运行）：
 * <pre>
 * XM_GM_ADMIN_SECRET=... java tools/GmShutdown.java --url http://127.0.0.1:18103 --operator alice --reason "滚动发布" \
 *     [--expect-node 3]
 * </pre>
 * {@code --expect-node}：只在目标的节点号就是它时才发（防止端口指错停错进程）。
 * 退出码：0 已受理；1 被拒或请求失败；2 用法错误。
 */
public final class GmShutdown {

    private static final Pattern OPERATOR = Pattern.compile("[\\x21-\\x7E]{1,64}");
    private static final int MAX_REASON = 256;

    private GmShutdown() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        if (opts == null) {
            usage();
            System.exit(2);
        }
        String secret = System.getenv("XM_GM_ADMIN_SECRET");
        if (secret == null || secret.isBlank()) {
            System.err.println("没设环境变量 XM_GM_ADMIN_SECRET");
            System.exit(2);
        }
        String operator = opts.get("operator");
        if (!OPERATOR.matcher(operator).matches()) {
            System.err.println("--operator 只能是 1–64 个可打印 ASCII 字符（不含空格），服务端按此校验");
            System.exit(2);
        }
        String reason = opts.getOrDefault("reason", "");
        if (reason.length() > MAX_REASON) {
            System.err.println("--reason 至多 " + MAX_REASON + " 个字符");
            System.exit(2);
        }
        HttpClient http = HttpClient.newHttpClient();
        String url = opts.get("url");

        HttpResponse<String> identity = send(http, HttpRequest.newBuilder(URI.create(url + "/gm/identity"))
                .timeout(Duration.ofSeconds(10)).GET().build());
        if (identity.statusCode() != 200) {
            System.out.println("取目标身份失败 HTTP " + identity.statusCode() + " " + identity.body());
            System.exit(1);
        }
        String zone = field(identity.body(), "zone_id");
        String node = field(identity.body(), "node_id");
        String instance = field(identity.body(), "instance_id");
        String method = field(identity.body(), "method");
        if (zone == null || node == null || instance == null || method == null) {
            System.out.println("目标身份应答不完整：" + identity.body());
            System.exit(1);
        }
        System.out.println("目标 " + method + " zone=" + zone + " node_id=" + node + " instance=" + instance);
        if (opts.containsKey("expect-node") && !opts.get("expect-node").equals(node)) {
            System.out.println("目标节点号是 " + node + "，不是 --expect-node " + opts.get("expect-node") + "，没发停机请求");
            System.exit(1);
        }

        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        String target = zone + ":" + node + ":" + instance;
        String canonical = method + '\n' + target + '\n' + operator + '\n' + timestamp + '\n' + nonce + '\n' + reason;
        String signature = hmacHex(secret.trim().getBytes(StandardCharsets.UTF_8), canonical);

        HttpResponse<String> response = send(http, HttpRequest.newBuilder(URI.create(url + "/gm/graceful-shutdown"))
                .timeout(Duration.ofSeconds(10))
                .header("X-Xm-Gm-Operator", operator)
                .header("X-Xm-Gm-Timestamp", timestamp)
                .header("X-Xm-Gm-Nonce", nonce)
                .header("X-Xm-Gm-Signature", signature)
                .header("X-Xm-Gm-Reason", URLEncoder.encode(reason, StandardCharsets.UTF_8))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        System.out.println("HTTP " + response.statusCode() + " " + response.body());
        System.exit(response.statusCode() == 200 ? 0 : 1);
    }

    private static HttpResponse<String> send(HttpClient http, HttpRequest request) throws InterruptedException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            System.err.println("请求失败（" + request.uri() + "）：" + e);
            System.exit(1);
            throw new IllegalStateException(e);
        }
    }

    /** 从扁平 JSON 对象里取一个字段的原文（数字或不含转义的字符串）。 */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"\\\\]*)\"|(-?\\d+))").matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                return null;
            }
            opts.put(args[i].substring(2), args[++i]);
        }
        for (String required : List.of("url", "operator")) {
            if (!opts.containsKey(required)) {
                return null;
            }
        }
        return opts;
    }

    private static void usage() {
        System.err.println("用法：XM_GM_ADMIN_SECRET=... java tools/GmShutdown.java --url <管理端口地址> --operator <操作人>"
                + " [--reason <原因>] [--expect-node <节点号>]");
    }

    private static String hmacHex(byte[] secret, String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
