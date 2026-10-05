package com.game.robot.client;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * xm-scene dev / test 实例管理口的 HTTP 客户端（dungeon-mirror-spec §6.13、Q3；契约 {@code xm-api/src/main/proto/xm/api/scene_admin.proto}）：
 * <ul>
 *   <li>{@code POST /admin/scene/instance/create}（建副本）与 {@code POST /admin/scene/instance/destroy}（显式销毁实例），挂在 scene 管理端口
 *       （缺省 18104）；</li>
 *   <li>请求 / 应答体是 protobuf 二进制（{@value #CONTENT_TYPE}）；鉴权同 xm-trade 播种：令牌头 {@code X-Xm-Admin-Token}
 *       （令牌同 {@link AdminClient#resolveToken}）+ 操作人 {@code X-Xm-Operator}；</li>
 *   <li>HTTP 状态：令牌没配 503、令牌错 401、缺操作人或请求体不对 400、运行模式不是 dev / test 403；其余 200，结论在应答体的 {@code tip_id}。</li>
 * </ul>
 * robot 不依赖 xm-api（那会把 Dubbo 拖进探针），消息按字段号手工编解码；字段号由单测对照 xm-api 的生成类钉住（同 {@link BattleAdminClient}）。
 */
public final class SceneAdminClient {

    public static final String CREATE = "/admin/scene/instance/create";
    public static final String DESTROY = "/admin/scene/instance/destroy";
    public static final String CONTENT_TYPE = "application/x-protobuf";

    /**
     * 建副本的结论（{@code xm.api.CreateDungeonInstanceResponse}）。
     *
     * @param tipId         0 = 建好；3005 = Dungeon 表没有这一行（或为 0）；1003 = 取号失败；3023 = scene-manager 拒绝或本地拒建
     * @param sceneId       新副本的全服号（成功时非 0）
     * @param sceneConfigId 副本地图 = Dungeon.scene_id
     * @param sceneNodeId   实例所在节点（= 收到请求的节点）
     */
    public record Created(int tipId, long sceneId, int sceneConfigId, int sceneNodeId) {

        public String describe() {
            return "tip_id=" + tipId + " scene_id=" + Long.toUnsignedString(sceneId) + " scene_config_id="
                    + Integer.toUnsignedString(sceneConfigId) + " scene_node_id=" + Integer.toUnsignedString(sceneNodeId);
        }
    }

    /** 一次调用的原始结果（状态码 + 应答体）。 */
    public record HttpResult(int status, byte[] body) {

        public String text() {
            return preview(body);
        }
    }

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * @param baseUrl scene 管理端口（如 {@code http://127.0.0.1:18104}，不带结尾斜杠）
     * @param token   运维令牌；null 表示没有（调用直接报错，不发请求）
     */
    public SceneAdminClient(String baseUrl, String token, Duration timeout) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.timeout = timeout;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    /** 建副本。200 才返回；是否建成看 {@link Created#tipId()}，由调用方判定。 */
    public Created createDungeon(int dungeonConfigId) throws RobotException {
        return decodeCreated(expect200(CREATE, encodeCreate(dungeonConfigId)).body());
    }

    /** 显式销毁实例，返回 {@code tip_id}（0 = 已受理 / 已在排空中；3000 = 本节点没有；3005 = 主世界频道或号为 0）。 */
    public int destroy(long sceneId) throws RobotException {
        return decodeDestroyed(expect200(DESTROY, encodeDestroy(sceneId)).body());
    }

    /** 建副本，不判状态码（prod 模式下核对 403 用）。 */
    public HttpResult createRaw(int dungeonConfigId) throws RobotException {
        return post(CREATE, encodeCreate(dungeonConfigId));
    }

    /** 显式销毁，不判状态码（prod 模式下核对 403 用）。 */
    public HttpResult destroyRaw(long sceneId) throws RobotException {
        return post(DESTROY, encodeDestroy(sceneId));
    }

    /** 不判状态码的原始调用。 */
    public HttpResult post(String path, byte[] body) throws RobotException {
        if (!hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-scene 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("X-Xm-Admin-Token", token).header("X-Xm-Operator", AdminClient.OPERATOR)
                .header("Content-Type", CONTENT_TYPE).header("Accept", CONTENT_TYPE + ", text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResult(response.statusCode(), response.body());
        } catch (ConnectException e) {
            throw new RobotException("连不上 xm-scene 管理口 " + baseUrl + path + "（xm-scene 未起，或 --scene-admin-url 指错了）：" + e, e);
        } catch (HttpTimeoutException e) {
            throw new RobotException("xm-scene 管理口 " + timeout.toMillis() + " ms 内没有应答：" + e, e);
        } catch (IOException e) {
            throw new RobotException("调用 xm-scene 管理口失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-scene 管理口被中断", e);
        }
    }

    /** 抓 scene 管理端口的 Prometheus 文本（副本恒建在收到请求的节点上，指标就在这个端口）。 */
    public String scrapeMetrics() throws RobotException {
        return scrape(http, baseUrl, timeout);
    }

    /** 抓任一服务管理端口的 Prometheus 文本；非 200 抛出。 */
    public static String scrape(String metricsBaseUrl, Duration timeout) throws RobotException {
        return scrape(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), metricsBaseUrl, timeout);
    }

    private static String scrape(HttpClient http, String metricsBaseUrl, Duration timeout) throws RobotException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(metricsBaseUrl + "/actuator/prometheus")).timeout(timeout).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RobotException("抓 " + metricsBaseUrl + " 的指标返回 " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new RobotException("抓 " + metricsBaseUrl + " 的指标失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("抓指标被中断", e);
        }
    }

    // ------------------------------------------------------------------ 编解码（字段号见 scene_admin.proto）

    /** {@code CreateDungeonInstanceRequest{uint32 dungeon_config_id = 1}}；0 按 proto3 不写出。 */
    static byte[] encodeCreate(int dungeonConfigId) {
        return encode(out -> {
            if (dungeonConfigId != 0) {
                out.writeUInt32(1, dungeonConfigId);
            }
        });
    }

    /** {@code DestroyInstanceRequest{uint64 scene_id = 1}}；0 按 proto3 不写出。 */
    static byte[] encodeDestroy(long sceneId) {
        return encode(out -> {
            if (sceneId != 0) {
                out.writeUInt64(1, sceneId);
            }
        });
    }

    /** {@code CreateDungeonInstanceResponse{tip_id 1, scene_id 2, scene_config_id 3, scene_node_id 4}}，缺字段按 0。 */
    static Created decodeCreated(byte[] body) throws RobotException {
        try {
            UnknownFieldSet fields = UnknownFieldSet.parseFrom(body);
            return new Created((int) varint(fields, 1), varint(fields, 2), (int) varint(fields, 3), (int) varint(fields, 4));
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("xm-scene 建副本接口的 200 应答体不是 CreateDungeonInstanceResponse：" + e.getMessage(), e);
        }
    }

    /** {@code DestroyInstanceResponse{tip_id 1}}，缺字段按 0。 */
    static int decodeDestroyed(byte[] body) throws RobotException {
        try {
            return (int) varint(UnknownFieldSet.parseFrom(body), 1);
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("xm-scene 销毁接口的 200 应答体不是 DestroyInstanceResponse：" + e.getMessage(), e);
        }
    }

    /** 按 HTTP 状态给的排查提示（§6.13 的状态映射）。 */
    public static String statusHint(int status) {
        return switch (status) {
            case 403 -> "（xm-scene 运行模式不是 dev / test，实例管理口关闭；本机切片缺省 XM_RUN_MODE=dev）";
            case 401 -> "（运维令牌不对：robot 的 XM_ADMIN_TOKEN / run/xm-admin-token 与 xm-scene 进程的 XM_ADMIN_TOKEN 不一致）";
            case 503 -> "（xm-scene 进程没配 XM_ADMIN_TOKEN，实例管理口一律拒绝）";
            case 400 -> "（缺操作人头 X-Xm-Operator，或请求体不是对应的 protobuf 二进制）";
            case 404 -> "（这个端口上没有实例管理口：--scene-admin-url 指错了端口，或 xm-scene 版本早于批次 5.3）";
            default -> "";
        };
    }

    private HttpResult expect200(String path, byte[] body) throws RobotException {
        HttpResult result = post(path, body);
        if (result.status() != 200) {
            throw new RobotException("xm-scene 实例管理口 POST " + path + " 返回 " + result.status() + statusHint(result.status())
                    + "：" + result.text());
        }
        return result;
    }

    private interface Writer {
        void write(CodedOutputStream out) throws IOException;
    }

    private static byte[] encode(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        try {
            writer.write(out);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("写内存缓冲不会失败", e);
        }
        return bytes.toByteArray();
    }

    private static long varint(UnknownFieldSet fields, int number) {
        if (!fields.hasField(number)) {
            return 0;
        }
        List<Long> values = fields.getField(number).getVarintList();
        return values.isEmpty() ? 0 : values.get(values.size() - 1);
    }

    /** 非 2xx 应答体的前 200 个字符（错误多是 UTF-8 文本）。 */
    private static String preview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
