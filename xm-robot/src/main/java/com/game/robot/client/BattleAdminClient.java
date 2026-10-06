package com.game.robot.client;

import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.RemoveObserverRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * xm-battle dev / test 管理接口的 HTTP 客户端（battle-node-spec §7.12、§13.8；6.4 的 match 落地之前，robot 经它建房 / 销毁 / 补签 / 登记观众）：
 * <ul>
 *   <li>{@code POST /admin/battle/dev/*}，挂在 xm-battle 管理端口（缺省 18112，只绑本机）；</li>
 *   <li>请求体与应答都是契约 protobuf 二进制（{@value #CONTENT_TYPE}）；鉴权同 xm-trade 播种接口：令牌头 {@code X-Xm-Admin-Token}
 *       （令牌同 {@link AdminClient#resolveToken}）+ 操作人 {@code X-Xm-Operator}；</li>
 *   <li>HTTP 状态：令牌没配 503、令牌错 401、缺操作人或请求体不对 400、运行模式不是 dev / test 403、快照路由补不全 422（玩家不在线 / 位置记录不是在线
 *       状态）、节点没在运行或控制面拒绝 503、5 s 内没结果 504；其余一律 200 / 204，结论在应答体里。</li>
 * </ul>
 * 建房应答是 {@code xm.api.CreateBattleResult}（{@code admission} / {@code reason} / {@code response}）：robot 不依赖 xm-api（那会把 Dubbo 拖进探针），
 * 按字段号解（1 = admission 枚举、2 = reason、3 = 契约 {@code CreateBattleResponse} 字节），字段号由单测对照 xm-api 的生成类钉住。
 * 6.3 的 dev gather（{@value #GATHER}，scene-battle-spec §7.18）同样按字段号编 {@code DevGatherRequest}、解 {@code DevGatherResponse}；
 * 它的 422 也带 protobuf 应答体（有人备战失败 / 建房失败，已补发取消）。取消备战（{@value #CANCEL_PREPARE}）的请求体是契约
 * {@code CancelBattlePrepareRequest}。
 */
public final class BattleAdminClient {

    public static final String BASE = "/admin/battle/dev";
    public static final String CREATE = BASE + "/create";
    public static final String DESTROY = BASE + "/destroy";
    public static final String ISSUE_TICKET = BASE + "/issue-ticket";
    public static final String ADD_OBSERVER = BASE + "/add-observer";
    public static final String REMOVE_OBSERVER = BASE + "/remove-observer";
    /** dev gather（scene-battle-spec §7.18）：经 scene 真实备战，再以 DEV_GATHER 来源建房。 */
    public static final String GATHER = BASE + "/gather";
    /** dev 取消备战（scene-battle-spec §7.18）：定位玩家此刻的 scene 后调取消。 */
    public static final String CANCEL_PREPARE = BASE + "/cancel-prepare";
    public static final String CONTENT_TYPE = "application/x-protobuf";

    /** {@code xm.api.BattleAdmission}：受理 / 不可分配（准入闸没开、在途超限）。 */
    public static final int ADMISSION_ADMITTED = 1;
    public static final int ADMISSION_NOT_ALLOCATABLE = 2;

    /**
     * 建房结局（{@code xm.api.CreateBattleResult} 的 robot 侧形状）。
     *
     * @param admission 准入结论（{@link #ADMISSION_ADMITTED} / {@link #ADMISSION_NOT_ALLOCATABLE}；0 = 提供方违约）
     * @param reason    不可分配的原因（not_started / closed / overloaded …）
     * @param response  受理时的契约应答（业务错误在 {@code error_message}）；没有时为默认实例
     */
    public record CreateOutcome(int admission, String reason, CreateBattleResponse response) {

        public boolean admitted() {
            return admission == ADMISSION_ADMITTED;
        }

        /** 受理且没有业务错误。 */
        public boolean ok() {
            return admitted() && !response.hasErrorMessage();
        }

        public String describe() {
            return "admission=" + admission + (reason.isEmpty() ? "" : " reason=" + reason)
                    + (response.hasErrorMessage() ? " tip=" + response.getErrorMessage().getId() : "");
        }
    }

    /** {@code xm.api.DevGatherMode}。 */
    public static final int GATHER_PREPARE_ONLY = 1;
    public static final int GATHER_CREATE = 2;
    /** {@code xm.api.SceneBattleStatus}：已在 scene 逻辑线程处理（备战的业务结论在应答体里）。 */
    public static final int SCENE_BATTLE_HANDLED = 1;

    /**
     * {@code xm.api.DevGatherRequest} 的 robot 侧形状（robot 不依赖 xm-api，按字段号编码，单测对照生成类钉住）。
     *
     * @param mode              {@link #GATHER_PREPARE_ONLY} / {@link #GATHER_CREATE}
     * @param prepareDeadlineMs 0 = 沿用 deadlineMs
     * @param members           成员（player_id → team_index，按给定顺序编码；接口按 player_id 升序处理）
     */
    public record DevGather(int mode, long battleId, int matchMode, int battleConfigId, long seed, long deadlineMs,
                            long prepareDeadlineMs, List<Member> members) {

        public DevGather {
            members = List.copyOf(members);
        }

        public record Member(long playerId, int teamIndex) {
        }

        /** 单人 PVE（team 0）。 */
        public static DevGather solo(int mode, long battleId, int matchMode, int battleConfigId, long seed, long deadlineMs,
                                     long prepareDeadlineMs, long playerId) {
            return new DevGather(mode, battleId, matchMode, battleConfigId, seed, deadlineMs, prepareDeadlineMs,
                    List.of(new Member(playerId, 0)));
        }
    }

    /**
     * 一个成员的备战结局（{@code xm.api.DevGatherPrepareResult}）。
     *
     * @param status   {@code SceneBattleStatus} 数值（0 = 没发出或传输失败）
     * @param response 契约 {@code PrepareBattleResponse}（status = HANDLED 时；否则默认实例）
     * @param error    定位 / 传输失败的原因
     */
    public record PrepareResult(long playerId, int status, PrepareBattleResponse response, String error, int sceneNodeId,
                                String sceneInstanceId) {

        /** 备战成功：scene 已处理、没有业务错误、带快照。 */
        public boolean ok() {
            return status == SCENE_BATTLE_HANDLED && !response.hasErrorMessage() && response.hasSnapshot();
        }

        /** 业务拒绝码（没有为 0）。 */
        public int tip() {
            return response.getErrorMessage().getId();
        }
    }

    /**
     * gather 的结局（{@code xm.api.DevGatherResponse} + HTTP 状态）。
     *
     * @param httpStatus 200 = 成功；422 = 失败（{@code failure} 写原因）
     * @param created    CREATE 走到建房时的结果；没有为 null
     */
    public record GatherOutcome(int httpStatus, List<PrepareResult> prepareResults, CreateOutcome created, String failure,
                                List<Long> cancelledPlayerIds) {

        public GatherOutcome {
            prepareResults = List.copyOf(prepareResults);
            cancelledPlayerIds = List.copyOf(cancelledPlayerIds);
        }

        public boolean ok() {
            return httpStatus == 200 && failure.isEmpty();
        }

        /** 某人的备战结局；没有为 null。 */
        public PrepareResult of(long playerId) {
            return prepareResults.stream().filter(r -> r.playerId() == playerId).findFirst().orElse(null);
        }

        public String describe() {
            StringBuilder out = new StringBuilder("http=").append(httpStatus);
            for (PrepareResult r : prepareResults) {
                out.append(" [").append(Long.toUnsignedString(r.playerId())).append(" status=").append(r.status());
                if (r.tip() != 0) {
                    out.append(" tip=").append(r.tip());
                }
                if (!r.error().isEmpty()) {
                    out.append(" error=").append(r.error());
                }
                out.append(']');
            }
            if (created != null) {
                out.append(" create{").append(created.describe()).append('}');
            }
            if (!failure.isEmpty()) {
                out.append(" failure=").append(failure);
            }
            if (!cancelledPlayerIds.isEmpty()) {
                out.append(" cancelled=").append(cancelledPlayerIds.stream().map(Long::toUnsignedString).toList());
            }
            return out.toString();
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
     * @param baseUrl xm-battle 管理端口（如 {@code http://127.0.0.1:18112}，不带结尾斜杠）
     * @param token   运维令牌；null 表示没有（调用直接报错，不发请求）
     */
    public BattleAdminClient(String baseUrl, String token, Duration timeout) {
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

    /** dev 建房（快照路由留空由接口按在线目录 / 位置记录补全）。200 才返回；业务结论由调用方判定。 */
    public CreateOutcome create(CreateBattleRequest request) throws RobotException {
        return decodeCreateResult(expect(CREATE, request, 200).body());
    }

    /** dev 销毁（幂等，204）。 */
    public void destroy(long battleId, String reason) throws RobotException {
        expect(DESTROY, DestroyBattleRequest.newBuilder().setBattleId(battleId).setReason(reason).build(), 204);
    }

    /** dev 补签（模拟 179 的 battle 侧）。 */
    public IssueBattleTicketResponse issueTicket(long battleId, long playerId) throws RobotException {
        byte[] body = expect(ISSUE_TICKET, IssueBattleTicketRequest.newBuilder().setBattleId(battleId).setPlayerId(playerId).build(), 200)
                .body();
        try {
            return IssueBattleTicketResponse.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("xm-battle 补签接口的 200 应答体不是 IssueBattleTicketResponse：" + e.getMessage(), e);
        }
    }

    /** dev 登记观众（观众路由留空由接口按在线目录补全）。 */
    public AddObserverResponse addObserver(long battleId, long observerPlayerId, String observerName) throws RobotException {
        byte[] body = expect(ADD_OBSERVER, AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerPlayerId)
                .setObserverName(observerName).build(), 200).body();
        try {
            return AddObserverResponse.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("xm-battle 登记观众接口的 200 应答体不是 AddObserverResponse：" + e.getMessage(), e);
        }
    }

    /** dev 清退观众（幂等，204）。 */
    public void removeObserver(long battleId, long observerPlayerId, String reason) throws RobotException {
        expect(REMOVE_OBSERVER, RemoveObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerPlayerId)
                .setReason(reason).build(), 204);
    }

    /**
     * dev gather：200（全员备战成功；CREATE 时还建房受理且无业务错误）与 422（有人备战失败 / 建房失败，已补发取消）都解出应答体；
     * 其余状态码抛出（带排查提示）。
     */
    public GatherOutcome gather(DevGather request) throws RobotException {
        HttpResult result = postBytes(GATHER, encodeGather(request));
        if (result.status() != 200 && result.status() != 422) {
            throw new RobotException("xm-battle dev 接口 POST " + GATHER + " 返回 " + result.status() + statusHint(result.status()) + "："
                    + result.text());
        }
        return decodeGather(result.status(), result.body());
    }

    /** 不判状态码的 gather（prod 模式下核对 403 用）。 */
    public HttpResult gatherRaw(DevGather request) throws RobotException {
        return postBytes(GATHER, encodeGather(request));
    }

    /** dev 取消备战：返回原始结果（204 = scene 已处理，幂等；422 = 玩家此刻没有持有者节点；503 = 定位 / 调用失败）。 */
    public HttpResult cancelPrepare(long playerId, long battleId) throws RobotException {
        return post(CANCEL_PREPARE, CancelBattlePrepareRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId).build());
    }

    /** 不判状态码的原始调用（prod 模式下核对 403 用）。 */
    public HttpResult post(String path, Message body) throws RobotException {
        return postBytes(path, body.toByteArray());
    }

    private HttpResult postBytes(String path, byte[] body) throws RobotException {
        if (!hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-battle 相同），或先用 tools/local/start-slice.sh 生成 "
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
            throw new RobotException("连不上 xm-battle 管理接口 " + baseUrl + path + "（xm-battle 未起，或 --battle-admin-url 指错了）：" + e, e);
        } catch (HttpTimeoutException e) {
            throw new RobotException("xm-battle 管理接口 " + timeout.toMillis() + " ms 内没有应答：" + e, e);
        } catch (IOException e) {
            throw new RobotException("调用 xm-battle 管理接口失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-battle 管理接口被中断", e);
        }
    }

    /** 抓 xm-battle 管理端口的 Prometheus 文本（配合 {@link AdminClient#sum} 取值）。 */
    public String scrapeMetrics() throws RobotException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/prometheus")).timeout(timeout).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RobotException("抓 xm-battle 指标返回 " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new RobotException("抓 xm-battle 指标失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("抓 xm-battle 指标被中断", e);
        }
    }

    /**
     * 解 {@code xm.api.CreateBattleResult}：1 = admission（varint）、2 = reason（UTF-8）、3 = response（契约 {@code CreateBattleResponse}）。
     * 缺字段按 proto3 缺省（0 / 空）。
     */
    static CreateOutcome decodeCreateResult(byte[] body) throws RobotException {
        try {
            UnknownFieldSet fields = UnknownFieldSet.parseFrom(body);
            int admission = (int) last(fields.getField(1).getVarintList(), 0L).longValue();
            String reason = last(fields.getField(2).getLengthDelimitedList(), ByteString.EMPTY).toStringUtf8();
            CreateBattleResponse response = CreateBattleResponse.parseFrom(last(fields.getField(3).getLengthDelimitedList(), ByteString.EMPTY));
            return new CreateOutcome(admission, reason, response);
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("xm-battle 建房接口的 200 应答体不是 CreateBattleResult：" + e.getMessage(), e);
        }
    }

    /**
     * 编码 {@code xm.api.DevGatherRequest}：1 mode、2 battle_id、3 match_mode、4 battle_config_id、5 seed、6 deadline_ms、7 prepare_deadline_ms、
     * 8 members{1 player_id、2 team_index}。0 值不写（与生成类的序列化逐字节相同）。
     */
    static byte[] encodeGather(DevGather request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            if (request.mode() != 0) {
                out.writeEnum(1, request.mode());
            }
            writeUInt64(out, 2, request.battleId());
            writeUInt32(out, 3, request.matchMode());
            writeUInt32(out, 4, request.battleConfigId());
            writeUInt64(out, 5, request.seed());
            writeUInt64(out, 6, request.deadlineMs());
            writeUInt64(out, 7, request.prepareDeadlineMs());
            for (DevGather.Member member : request.members()) {
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                CodedOutputStream m = CodedOutputStream.newInstance(inner);
                writeUInt64(m, 1, member.playerId());
                writeUInt32(m, 2, member.teamIndex());
                m.flush();
                out.writeBytes(8, ByteString.copyFrom(inner.toByteArray()));
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * 解 {@code xm.api.DevGatherResponse}：1 prepare_results{1 player_id、2 status、3 response、4 error、5 scene_node_id、6 scene_instance_id}、
     * 2 create_result（{@code CreateBattleResult}）、3 failure、4 cancelled_player_ids（packed 或逐条 varint 都认）。
     */
    static GatherOutcome decodeGather(int httpStatus, byte[] body) throws RobotException {
        try {
            UnknownFieldSet fields = UnknownFieldSet.parseFrom(body);
            List<PrepareResult> results = new ArrayList<>();
            for (ByteString raw : fields.getField(1).getLengthDelimitedList()) {
                UnknownFieldSet r = UnknownFieldSet.parseFrom(raw);
                ByteString response = last(r.getField(3).getLengthDelimitedList(), ByteString.EMPTY);
                results.add(new PrepareResult(last(r.getField(1).getVarintList(), 0L), (int) last(r.getField(2).getVarintList(), 0L).longValue(),
                        PrepareBattleResponse.parseFrom(response), last(r.getField(4).getLengthDelimitedList(), ByteString.EMPTY).toStringUtf8(),
                        (int) last(r.getField(5).getVarintList(), 0L).longValue(),
                        last(r.getField(6).getLengthDelimitedList(), ByteString.EMPTY).toStringUtf8()));
            }
            List<ByteString> create = fields.getField(2).getLengthDelimitedList();
            CreateOutcome created = create.isEmpty() ? null : decodeCreateResult(last(create, ByteString.EMPTY).toByteArray());
            String failure = last(fields.getField(3).getLengthDelimitedList(), ByteString.EMPTY).toStringUtf8();
            List<Long> cancelled = new ArrayList<>(fields.getField(4).getVarintList());
            for (ByteString packed : fields.getField(4).getLengthDelimitedList()) {
                CodedInputStream in = packed.newCodedInput();
                while (!in.isAtEnd()) {
                    cancelled.add(in.readUInt64());
                }
            }
            return new GatherOutcome(httpStatus, results, created, failure, cancelled);
        } catch (IOException e) {
            throw new RobotException("xm-battle gather 接口的 " + httpStatus + " 应答体不是 DevGatherResponse：" + e.getMessage(), e);
        }
    }

    private static void writeUInt64(CodedOutputStream out, int field, long value) throws IOException {
        if (value != 0) {
            out.writeUInt64(field, value);
        }
    }

    private static void writeUInt32(CodedOutputStream out, int field, int value) throws IOException {
        if (value != 0) {
            out.writeUInt32(field, value);
        }
    }

    /** 按 HTTP 状态给的排查提示（battle-node-spec §7.12 的状态映射）。 */
    static String statusHint(int status) {
        return switch (status) {
            case 403 -> "（xm-battle 运行模式不是 dev / test，dev 接口关闭；本机切片缺省 XM_RUN_MODE=dev）";
            case 401 -> "（运维令牌不对：robot 的 XM_ADMIN_TOKEN / run/xm-admin-token 与 xm-battle 进程的 XM_ADMIN_TOKEN 不一致）";
            case 503 -> "（xm-battle 没配 XM_ADMIN_TOKEN，或节点没在运行 / 控制面拒绝 / 读 Redis 失败）";
            case 400 -> "（缺操作人头 X-Xm-Operator，或请求体不是对应的 protobuf 二进制）";
            case 422 -> "（快照路由补不全：玩家不在线，或位置记录不是在线状态——先登录进场再建房）";
            case 504 -> "（battle 逻辑线程 5 s 内没有应答）";
            case 404 -> "（这个端口上没有 dev 接口：--battle-admin-url 指错了端口，或 xm-battle 版本过旧）";
            default -> "";
        };
    }

    private HttpResult expect(String path, Message body, int expectedStatus) throws RobotException {
        HttpResult result = post(path, body);
        if (result.status() != expectedStatus) {
            throw new RobotException("xm-battle dev 接口 POST " + path + " 返回 " + result.status() + statusHint(result.status()) + "：" + result.text());
        }
        return result;
    }

    private static <T> T last(List<T> values, T fallback) {
        return values.isEmpty() ? fallback : values.get(values.size() - 1);
    }

    /** 非 2xx 应答体的前 200 个字符（错误多是 UTF-8 文本）。 */
    private static String preview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
