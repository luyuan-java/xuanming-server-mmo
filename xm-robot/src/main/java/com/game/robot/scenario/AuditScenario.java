package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.contract.MessageIdRegistry;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmBlockCurrencyResponse;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmDeductCurrencyResponse;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.GmUnblockCurrencyResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资产流水与玩家快照端到端：GM 加 / 扣货币（成功的与被拒的都发），再经 xm-data 的运维查询（{@code /admin/transaction-log}）核对：
 * 恰好成功的那几笔落了库、顺序与字段都对；被拒的一笔都没有。另抓 scene 指标核对这几条都被 Kafka 确认、没有走兜底。
 * 然后下线、再上线，经 {@code /admin/player-snapshots} 核对快照 LOGIN → LOGOUT → LOGIN 依次落库、前后接得上。
 * 需要服务端放行 GM（dev / test）、Kafka 与 xm-data 在跑；运维令牌取环境变量 {@code XM_ADMIN_TOKEN}，没有就读本机切片脚本生成的
 * {@code run/xm-admin-token}。
 */
public final class AuditScenario {

    private static final String SERVICE = "SceneCurrencyClientPlayer";
    private static final int GOLD = 0;
    private static final int DIAMOND = 1;
    private static final int REASON_GM_GRANT = 9;
    private static final int REASON_GM_DEDUCT = 10;
    private static final int KIND_CURRENCY = 1;
    private static final Duration LANDING_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CALL_SPACING = Duration.ofMillis(400);
    private static final String REF = "PARITY「资产流水」行";
    private static final String REF_SNAPSHOT = "PARITY「玩家快照」行";
    private static final int CAUSE_LOGIN = 1;
    private static final int CAUSE_LOGOUT = 2;
    private static final Pattern AUDIT_COUNTER = Pattern.compile(
            "xm_scene_audit_records_total\\{[^}]*kind=\"transaction\"[^}]*result=\"([a-z_]+)\"[^}]*} ([0-9.E+]+)");

    private final PlayerFlow flow;
    private final String account;
    private final Duration requestTimeout;
    private final String dataUrl;
    private final String sceneMetricsUrl;
    private final String adminToken;
    private final int gmAdd;
    private final int gmDeduct;
    private final int gmBlock;
    private final int gmUnblock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public AuditScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                         Duration requestTimeout, String dataUrl, String sceneMetricsUrl, String adminToken) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.requestTimeout = requestTimeout;
        this.dataUrl = dataUrl;
        this.sceneMetricsUrl = sceneMetricsUrl;
        this.adminToken = adminToken;
        this.gmAdd = registry.requireId(SERVICE, "GmAddCurrency");
        this.gmDeduct = registry.requireId(SERVICE, "GmDeductCurrency");
        this.gmBlock = registry.requireId(SERVICE, "GmBlockCurrency");
        this.gmUnblock = registry.requireId(SERVICE, "GmUnblockCurrency");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "au" + runTag;
    }

    /** 运维令牌：环境变量优先，否则本机切片脚本生成的文件；都没有为 null。 */
    public static String resolveAdminToken(String fromEnv) {
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        try {
            Path file = Path.of("run", "xm-admin-token");
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        if (adminToken == null || adminToken.isEmpty()) {
            report.fail("运维令牌", "没有 XM_ADMIN_TOKEN，也没有 run/xm-admin-token（先用 tools/local/start-slice.sh 起切片）", REF);
            return report;
        }
        EnteredPlayer player = null;
        String phaseRef = REF;
        try {
            double[] countersBefore = auditCounters();
            long t0 = System.currentTimeMillis() - 1_000;
            player = flow.enter(account, new Timings());
            GameConnection c = player.connection();
            long pid = player.playerId();

            requireTip("GM 加钻石 500", 0, call(c, gmAdd, add(DIAMOND, 500), GmAddCurrencyResponse.parser())
                    .getErrorMessage().getId());
            requireTip("GM 扣钻石 120", 0, call(c, gmDeduct, deduct(DIAMOND, 120), GmDeductCurrencyResponse.parser())
                    .getErrorMessage().getId());
            requireTip("超额扣币（被拒）", 27000, call(c, gmDeduct, deduct(DIAMOND, 100_000),
                    GmDeductCurrencyResponse.parser()).getErrorMessage().getId());
            requireTip("未知币种（被拒）", 1005, call(c, gmAdd, add(99, 1), GmAddCurrencyResponse.parser())
                    .getErrorMessage().getId());
            requireTip("封禁金币", 0, call(c, gmBlock, GmBlockCurrencyRequest.newBuilder().setCurrencyType(GOLD).build(),
                    GmBlockCurrencyResponse.parser()).getErrorMessage().getId());
            requireTip("封禁后加金币（被拒）", 27005, call(c, gmAdd, add(GOLD, 1), GmAddCurrencyResponse.parser())
                    .getErrorMessage().getId());
            requireTip("解封金币", 0, call(c, gmUnblock, GmUnblockCurrencyRequest.newBuilder().setCurrencyType(GOLD).build(),
                    GmUnblockCurrencyResponse.parser()).getErrorMessage().getId());
            requireTip("哨兵：GM 加钻石 1", 0, call(c, gmAdd, add(DIAMOND, 1), GmAddCurrencyResponse.parser())
                    .getErrorMessage().getId());

            List<JsonNode> rows = awaitRows(pid, t0, 3);
            boolean exact = rows.size() == 3
                    && row(rows.get(0), REASON_GM_GRANT, "0", Long.toUnsignedString(pid), 500, "0", "500")
                    && row(rows.get(1), REASON_GM_DEDUCT, Long.toUnsignedString(pid), "0", -120, "500", "380")
                    && row(rows.get(2), REASON_GM_GRANT, "0", Long.toUnsignedString(pid), 1, "380", "381");
            boolean ordered = rows.size() == 3
                    && Long.compareUnsigned(Long.parseUnsignedLong(rows.get(0).get("txId").asText()),
                    Long.parseUnsignedLong(rows.get(1).get("txId").asText())) < 0
                    && Long.compareUnsigned(Long.parseUnsignedLong(rows.get(1).get("txId").asText()),
                    Long.parseUnsignedLong(rows.get(2).get("txId").asText())) < 0;
            report.check(exact && ordered, "恰好 3 笔成功的变动落库（被拒的 3 笔没有）、顺序与字段都对",
                    "共 " + rows.size() + " 行：" + summarize(rows), REF);

            double[] countersAfter = auditCounters();
            double acked = countersAfter[0] - countersBefore[0];
            double lost = countersAfter[1] - countersBefore[1];
            report.check(acked >= 3 && lost == 0, "scene 侧 3 条都被 Kafka 确认、没有走兜底日志",
                    "acked +" + acked + "，非 acked +" + lost, REF);

            // 玩家快照：进场时拍过 LOGIN；下线拍 LOGOUT（与写回同一份）；再上线又拍 LOGIN，玩法数据与位置接得上
            phaseRef = REF_SNAPSHOT;
            player.connection().close();
            player = null;
            List<JsonNode> afterLogout = awaitSnapshots(pid, t0, 2);
            player = flow.enter(account, new Timings());
            List<JsonNode> snapshots = awaitSnapshots(pid, t0, 3);
            report.check(snapshotChain(snapshots), "快照 LOGIN → LOGOUT → LOGIN 依次落库，下线那份与再上线那份等级 / 场景一致、带玩法数据",
                    "下线后 " + afterLogout.size() + " 行，再上线后 " + snapshots.size() + " 行：" + summarizeSnapshots(snapshots),
                    REF_SNAPSHOT);
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), phaseRef);
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    private static boolean row(JsonNode r, int reason, String from, String to, long delta, String before, String after) {
        return r.get("reason").asInt() == reason && r.get("kind").asInt() == KIND_CURRENCY
                && r.get("fromPlayer").asText().equals(from) && r.get("toPlayer").asText().equals(to)
                && r.get("currencyType").asLong() == DIAMOND && r.get("currencyDelta").asLong() == delta
                && r.get("balanceBefore").asText().equals(before) && r.get("balanceAfter").asText().equals(after)
                && r.get("zoneId").asLong() == 1;
    }

    private static String summarize(List<JsonNode> rows) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) {
            out.add("[reason=" + r.get("reason").asInt() + " delta=" + r.get("currencyDelta").asLong() + " "
                    + r.get("balanceBefore").asText() + "→" + r.get("balanceAfter").asText() + "]");
        }
        return String.join(" ", out);
    }

    /** 恰好 3 份：LOGIN、LOGOUT、LOGIN，时间与快照号都升序；LOGOUT 带玩法数据（钻石余额），下一份 LOGIN 的等级 / 场景与它一致。 */
    private static boolean snapshotChain(List<JsonNode> s) {
        if (s.size() != 3 || s.get(0).get("cause").asInt() != CAUSE_LOGIN || s.get(1).get("cause").asInt() != CAUSE_LOGOUT
                || s.get(2).get("cause").asInt() != CAUSE_LOGIN) {
            return false;
        }
        for (int i = 1; i < 3; i++) {
            if (s.get(i).get("timeMs").asLong() < s.get(i - 1).get("timeMs").asLong()
                    || Long.compareUnsigned(Long.parseUnsignedLong(s.get(i - 1).get("snapshotId").asText()),
                    Long.parseUnsignedLong(s.get(i).get("snapshotId").asText())) >= 0) {
                return false;
            }
        }
        JsonNode logout = s.get(1);
        JsonNode relogin = s.get(2);
        return logout.get("stateBytes").asLong() > 0
                && logout.get("stateBytes").asLong() == relogin.get("stateBytes").asLong()
                && logout.get("level").asLong() == relogin.get("level").asLong()
                && logout.get("sceneConfigId").asLong() == relogin.get("sceneConfigId").asLong()
                && logout.get("zoneId").asLong() == 1;
    }

    private static String summarizeSnapshots(List<JsonNode> rows) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) {
            out.add("[cause=" + r.get("cause").asInt() + " level=" + r.get("level").asLong() + " scene="
                    + r.get("sceneConfigId").asLong() + " bytes=" + r.get("stateBytes").asLong() + "]");
        }
        return String.join(" ", out);
    }

    /** 轮询快照查询，直到至少 {@code expected} 份或超时（快照经 Kafka 异步落库）。 */
    private List<JsonNode> awaitSnapshots(long playerId, long since, int expected) throws RobotException {
        long deadline = System.nanoTime() + LANDING_TIMEOUT.toNanos();
        List<JsonNode> rows = List.of();
        while (System.nanoTime() < deadline) {
            rows = adminQuery("/admin/player-snapshots", playerId, since);
            if (rows.size() >= expected) {
                // 再等一下确认没有多余的快照（恰好 3 份才算对）
                sleep(Duration.ofSeconds(2));
                return adminQuery("/admin/player-snapshots", playerId, since);
            }
            sleep(Duration.ofMillis(500));
        }
        return rows;
    }

    /** 轮询运维查询，直到至少 {@code expected} 行或超时（流水经 Kafka 异步落库）。 */
    private List<JsonNode> awaitRows(long playerId, long since, int expected) throws RobotException {
        long deadline = System.nanoTime() + LANDING_TIMEOUT.toNanos();
        List<JsonNode> rows = List.of();
        while (System.nanoTime() < deadline) {
            rows = adminQuery("/admin/transaction-log", playerId, since);
            if (rows.size() >= expected) {
                // 再等一下确认没有多余的行（被拒的变动不该落库）
                sleep(Duration.ofSeconds(2));
                return adminQuery("/admin/transaction-log", playerId, since);
            }
            sleep(Duration.ofMillis(500));
        }
        return rows;
    }

    private List<JsonNode> adminQuery(String path, long playerId, long since) throws RobotException {
        URI uri = URI.create(dataUrl + path + "?player=" + Long.toUnsignedString(playerId)
                + "&since=" + since);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("X-Xm-Admin-Token", adminToken).header("X-Xm-Operator", "xm-robot").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RobotException("xm-data 运维查询 " + path + " 返回 " + response.statusCode() + "：" + response.body());
            }
            List<JsonNode> rows = new ArrayList<>();
            json.readTree(response.body()).forEach(rows::add);
            return rows;
        } catch (IOException e) {
            throw new RobotException("调用 xm-data 运维查询失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-data 被中断", e);
        }
    }

    /** scene 指标里资产流水的 [acked, 非 acked 合计]。 */
    private double[] auditCounters() throws RobotException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(sceneMetricsUrl + "/actuator/prometheus"))
                .timeout(requestTimeout).GET().build();
        try {
            String text = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            double acked = 0;
            double lost = 0;
            Matcher m = AUDIT_COUNTER.matcher(text);
            while (m.find()) {
                double value = Double.parseDouble(m.group(2));
                if (m.group(1).equals("acked")) {
                    acked += value;
                } else {
                    lost += value;
                }
            }
            return new double[] {acked, lost};
        } catch (IOException e) {
            throw new RobotException("抓 scene 指标失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("抓 scene 指标被中断", e);
        }
    }

    /** 每次调用前歇一下：gate 按消息号限频（MessageLimiter，缺省每秒 3 条），连发同一条 GM 指令会被回 1008。 */
    private <T extends Message> T call(GameConnection c, int messageId, Message body, Parser<T> parser)
            throws RobotException {
        sleep(CALL_SPACING);
        return c.call(messageId, body, parser, requestTimeout);
    }

    private static void requireTip(String step, int expected, int actual) throws RobotException {
        if (expected != actual) {
            throw new RobotException(step + "：期望 tip=" + expected + "，实际 " + actual);
        }
    }

    private static GmAddCurrencyRequest add(int type, long amount) {
        return GmAddCurrencyRequest.newBuilder().setCurrencyType(type).setAmount(amount).build();
    }

    private static GmDeductCurrencyRequest deduct(int type, long amount) {
        return GmDeductCurrencyRequest.newBuilder().setCurrencyType(type).setAmount(amount).build();
    }

    private static void sleep(Duration duration) throws RobotException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
