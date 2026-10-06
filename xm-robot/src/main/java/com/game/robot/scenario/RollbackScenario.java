package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.contract.MessageIdRegistry;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LeaveGameRequest;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.LoginErrorTip;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * GM 回档端到端（批次 7.2b，data-ops-spec §12.6 的 rollback 场景）：一个新号，经 xm-data 的运维接口（令牌 + 操作人 + 幂等键）回档。
 * <ol>
 *   <li>进场 GM 加钻石 1000 → LeaveGame；等 scene 写回释放后拍一份手工快照 S（GM_MANUAL，内容 = 已落盘状态）。</li>
 *   <li>再进场加 500 → LeaveGame；离线回档到 S（{@code ifOnline=reject}）→ 作业 SUCCEEDED、明细 RESTORED；同幂等键重提回同一个作业。</li>
 *   <li>重登：钻石 = 1000。在线再加 300 → 1300：{@code reject} 回档 → REJECTED {@code player_online}、余额不变、连接仍在；
 *       {@code kick} 回档 → 旧连接收到 23 {2017} 并断开、作业 SUCCEEDED。</li>
 *   <li>重登：钻石 = 1000；PRE_ROLLBACK 安全快照里是 1300；流水有 TX_ROLLBACK_RESTORE（before 1300、after 1000、关联号 = 作业号）。</li>
 * </ol>
 * 需要 dev / test（GM 加币）、xm-data 的 {@code XM_DATA_OPS_ENABLED=true}（本机切片脚本缺省打开，沉降 3 s）、xm-guild 在跑（帮会检查）。
 * 运维令牌取环境变量 {@code XM_ADMIN_TOKEN}，没有就读本机切片脚本生成的 {@code run/xm-admin-token}。
 */
public final class RollbackScenario {

    private static final String SERVICE = "SceneCurrencyClientPlayer";
    private static final int DIAMOND = 1;
    private static final int REASON_ROLLBACK_RESTORE = 16;
    private static final int KICKED_BY_ANOTHER = LoginErrorTip.login_error.kLoginBeKickByAnOtherAccount_VALUE;
    /** 作业要经过沉降（本机 3 s）+ 帮会检查 + 复查等待（本机 2 s）；kick 时还要等 scene 写回。 */
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration LANDING_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CALL_SPACING = Duration.ofMillis(400);
    private static final String REF = "PARITY「GM 回档」行；data-ops-spec §4、§12.6";

    private final PlayerFlow flow;
    private final String account;
    private final String runTag;
    private final Duration requestTimeout;
    private final String dataUrl;
    private final String adminToken;
    private final int sendTip;
    private final int gmAdd;
    private final int getCurrencyList;
    private final int leaveGame;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public RollbackScenario(PlayerFlow flow, MessageIdRegistry registry, int sendTip, String accountPrefix, String runTag,
                            Duration requestTimeout, String dataUrl, String adminToken) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.runTag = runTag;
        this.requestTimeout = requestTimeout;
        this.dataUrl = dataUrl;
        this.adminToken = adminToken;
        this.sendTip = sendTip;
        this.gmAdd = registry.requireId(SERVICE, "GmAddCurrency");
        this.getCurrencyList = registry.requireId(SERVICE, "GetCurrencyList");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "rb" + runTag;
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
        try {
            // ---------------------------------------------------------------- 1 加钻 1000 → 下线 → 手工快照 S
            player = flow.enter(account, new Timings());
            long pid = player.playerId();
            requireBalance("GM 加钻石 1000", add(player.connection(), 1000), 1000);
            leave(player);
            player = null;
            JsonNode manual = manualSnapshotWhenReleased(pid);
            String snapshotId = manual.get("snapshotId").asText();
            report.check(!manual.get("online").asBoolean() && manual.get("ownerReleased").asBoolean(),
                    "下线后手工快照（GM_MANUAL）：归属已释放、内容 = 已落盘状态", "snapshot=" + snapshotId + " timeMs="
                            + manual.get("timeMs").asLong() + " savedEpoch=" + manual.get("savedEpoch").asText(), REF);

            // ---------------------------------------------------------------- 2 再加 500 → 下线 → 离线回档到 S
            player = flow.enter(account, new Timings());
            requireBalance("再加钻石 500", add(player.connection(), 500), 1500);
            leave(player);
            player = null;
            awaitPersistedDiamond(pid, snapshotId, 1500);
            String offlineKey = "robot-rb-" + runTag + "-offline";
            JsonNode accepted = rollback(pid, snapshotId, "reject", offlineKey);
            String offlineJob = accepted.get("jobId").asText();
            JsonNode offline = awaitJob(offlineJob);
            JsonNode offlineDetail = get("/admin/ops-jobs/" + offlineJob + "/players").get(0);
            report.check("succeeded".equals(offline.get("status").asText())
                            && "RESTORED".equals(offlineDetail.get("outcome").asText())
                            && !"0".equals(offlineDetail.get("preSnapshotId").asText()),
                    "离线回档到 S：作业 SUCCEEDED、明细 RESTORED 带安全快照号", "status=" + offline.get("status").asText()
                            + " code=" + offline.get("resultCode").asText() + " outcome=" + offlineDetail.get("outcome").asText()
                            + " events=" + eventTypes(offline), REF);
            JsonNode replay = rollback(pid, snapshotId, "reject", offlineKey);
            report.check(offlineJob.equals(replay.get("jobId").asText()) && replay.get("replayed").asBoolean(),
                    "同一个 Idempotency-Key 重提回同一个作业", "jobId=" + replay.get("jobId").asText() + " replayed="
                            + replay.get("replayed").asBoolean(), REF);

            // ---------------------------------------------------------------- 3 重登核对 → 在线加 300 → reject / kick
            player = flow.enter(account, new Timings());
            long afterOffline = diamond(player.connection());
            report.check(afterOffline == 1000, "离线回档后重登：钻石 = 快照里的 1000", "钻石=" + afterOffline, REF);
            requireBalance("在线再加 300", add(player.connection(), 300), 1300);

            JsonNode rejected = awaitJob(rollback(pid, snapshotId, "reject", "robot-rb-" + runTag + "-reject")
                    .get("jobId").asText());
            long stillOnline = diamond(player.connection());
            report.check("rejected".equals(rejected.get("status").asText())
                            && "player_online".equals(rejected.get("resultCode").asText()) && stillOnline == 1300
                            && player.connection().isOpen(),
                    "在线 + ifOnline=reject：作业 REJECTED player_online、余额不变、连接仍在",
                    "status=" + rejected.get("status").asText() + " code=" + rejected.get("resultCode").asText()
                            + " 钻石=" + stillOnline + " open=" + player.connection().isOpen(), REF);

            GameConnection old = player.connection();
            int mark = old.inbox().size();
            String kickJob = rollback(pid, snapshotId, "kick", "robot-rb-" + runTag + "-kick").get("jobId").asText();
            Optional<Received> kicked = old.await(mark, r -> r.messageId() == sendTip && tipId(r) == KICKED_BY_ANOTHER,
                    Duration.ofSeconds(40));
            report.check(kicked.isPresent(), "在线 + ifOnline=kick：旧连接收到 23 {" + KICKED_BY_ANOTHER + "}",
                    kicked.isPresent() ? "收到" : "40 s 内没收到" + old.describeSince(mark), REF);
            old.close();
            player = null;
            JsonNode kick = awaitJob(kickJob);
            report.check("succeeded".equals(kick.get("status").asText()), "kick 回档作业 SUCCEEDED",
                    "status=" + kick.get("status").asText() + " code=" + kick.get("resultCode").asText()
                            + " events=" + eventTypes(kick), REF);

            // ---------------------------------------------------------------- 4 重登核对；安全快照与回档流水
            player = flow.enter(account, new Timings());
            long afterKick = diamond(player.connection());
            report.check(afterKick == 1000, "kick 回档后重登：钻石 = 1000", "钻石=" + afterKick, REF);
            JsonNode kickDetail = get("/admin/ops-jobs/" + kickJob + "/players").get(0);
            String pre = kickDetail.get("preSnapshotId").asText();
            JsonNode preSnapshot = get("/admin/player-snapshots/" + pre + "?includeState=true");
            // 快照详情的玩法数据：{playerState: {state: <JsonFormat，uint64 是十进制字符串>, unknownFields: [...]}}
            long preDiamond = preSnapshot.at("/playerState/state/currency/balances/" + DIAMOND).asLong(-1);
            report.check("PRE_ROLLBACK".equals(preSnapshot.get("causeName").asText()) && preDiamond == 1300,
                    "安全快照 PRE_ROLLBACK 里是被覆盖之前的 1300", "cause=" + preSnapshot.get("causeName").asText()
                            + " 钻石=" + preDiamond + " operator=" + preSnapshot.get("operator").asText(), REF);
            JsonNode rows = get("/admin/transaction-log?player=" + pid + "&reasons=" + REASON_ROLLBACK_RESTORE);
            boolean restoreRow = false;
            for (JsonNode r : rows) {
                if (r.get("currencyType").asInt() == DIAMOND && "1300".equals(r.get("balanceBefore").asText())
                        && "1000".equals(r.get("balanceAfter").asText()) && kickJob.equals(r.get("correlationId").asText())
                        && Long.toUnsignedString(pid).equals(r.get("fromPlayer").asText())) {
                    restoreRow = true;
                }
            }
            report.check(restoreRow, "回档流水 TX_ROLLBACK_RESTORE：钻石 1300 → 1000、扣减方是玩家、关联号 = 作业号",
                    rows.size() + " 行：" + rows, REF);
            leave(player);
            player = null;
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), REF);
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    // ------------------------------------------------------------------ 游戏内

    private void leave(EnteredPlayer player) throws RobotException {
        player.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        sleep(Duration.ofMillis(200));
        player.connection().close();
    }

    private long add(GameConnection c, long amount) throws RobotException {
        sleep(CALL_SPACING);
        GmAddCurrencyResponse r = c.call(gmAdd, GmAddCurrencyRequest.newBuilder().setCurrencyType(DIAMOND)
                .setAmount(amount).build(), GmAddCurrencyResponse.parser(), requestTimeout);
        if (r.getErrorMessage().getId() != 0) {
            throw new RobotException("GM 加钻石 " + amount + " 失败：tip=" + r.getErrorMessage().getId());
        }
        return r.getBalanceAfter();
    }

    private long diamond(GameConnection c) throws RobotException {
        sleep(CALL_SPACING);
        GetCurrencyListResponse r = c.call(getCurrencyList, GetCurrencyListRequest.getDefaultInstance(),
                GetCurrencyListResponse.parser(), requestTimeout);
        List<Long> values = r.getCurrency().getValuesList();
        return values.size() > DIAMOND ? values.get(DIAMOND) : 0;
    }

    private static void requireBalance(String step, long actual, long expected) throws RobotException {
        if (actual != expected) {
            throw new RobotException(step + "：期望余额 " + expected + "，实际 " + actual);
        }
    }

    private static int tipId(Received r) {
        TipInfoMessage tip = r.parseOrNull(TipInfoMessage.parser());
        return tip == null ? -1 : tip.getId();
    }

    // ------------------------------------------------------------------ 运维接口

    /** 拍手工快照，直到快照里归属已释放（scene 的离场写回已提交）；每次新幂等键。 */
    private JsonNode manualSnapshotWhenReleased(long pid) throws RobotException {
        long deadline = System.nanoTime() + LANDING_TIMEOUT.toNanos();
        for (int attempt = 1; ; attempt++) {
            ObjectNode body = json.createObjectNode();
            body.put("player", Long.toUnsignedString(pid));
            body.put("cause", "GM_MANUAL");
            body.put("note", "robot rollback 场景的回档点");
            body.put("reason", "robot 回档演练");
            JsonNode r = send("POST", "/admin/player-snapshots", body.toString(),
                    "robot-rb-" + runTag + "-snap-" + attempt, 200);
            if (r.get("ownerReleased").asBoolean() || System.nanoTime() > deadline) {
                return r;
            }
            sleep(Duration.ofMillis(500));
        }
    }

    /** 等已落盘的钻石到 {@code expected} 且归属已释放（差异接口的「当前」= 已落盘状态）。 */
    private void awaitPersistedDiamond(long pid, String snapshotId, long expected) throws RobotException {
        long deadline = System.nanoTime() + LANDING_TIMEOUT.toNanos();
        JsonNode diff = null;
        while (System.nanoTime() < deadline) {
            diff = get("/admin/players/" + Long.toUnsignedString(pid) + "/snapshot-diff?snapshot=" + snapshotId);
            JsonNode row = diff.at("/currency/balances/" + DIAMOND);
            if (diff.at("/current/ownerReleased").asBoolean() && Long.toString(expected).equals(row.path("current").asText())) {
                return;
            }
            sleep(Duration.ofMillis(500));
        }
        throw new RobotException("下线后 " + LANDING_TIMEOUT.toSeconds() + " s 内已落盘钻石没到 " + expected + "：" + diff);
    }

    private JsonNode rollback(long pid, String snapshotId, String ifOnline, String key) throws RobotException {
        ObjectNode body = json.createObjectNode();
        body.put("scope", "players");
        body.putArray("players").add(Long.toUnsignedString(pid));
        body.put("snapshotId", snapshotId);
        body.put("ifOnline", ifOnline);
        body.put("reason", "robot 回档演练（" + ifOnline + "）");
        return send("POST", "/admin/rollbacks", body.toString(), key, 202);
    }

    /** 轮询作业直到终态。 */
    private JsonNode awaitJob(String jobId) throws RobotException {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        JsonNode job = null;
        while (System.nanoTime() < deadline) {
            job = get("/admin/ops-jobs/" + jobId);
            String status = job.get("status").asText();
            if (!"queued".equals(status) && !"running".equals(status)) {
                return job;
            }
            sleep(Duration.ofMillis(500));
        }
        throw new RobotException("作业 " + jobId + " 在 " + JOB_TIMEOUT.toSeconds() + " s 内没有终结：" + job);
    }

    private static String eventTypes(JsonNode job) {
        StringBuilder out = new StringBuilder();
        for (JsonNode e : job.path("events")) {
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(e.get("type").asText());
        }
        return out.toString();
    }

    private JsonNode get(String pathAndQuery) throws RobotException {
        return send("GET", pathAndQuery, null, null, 200);
    }

    private JsonNode send(String method, String pathAndQuery, String body, String idempotencyKey, int expected)
            throws RobotException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(dataUrl + pathAndQuery)).timeout(requestTimeout)
                .header("X-Xm-Admin-Token", adminToken).header("X-Xm-Operator", "xm-robot");
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expected) {
                throw new RobotException("xm-data " + method + " " + pathAndQuery + " 返回 " + response.statusCode()
                        + "（期望 " + expected + "）：" + response.body());
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new RobotException("调用 xm-data 运维接口失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-data 被中断", e);
        }
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
