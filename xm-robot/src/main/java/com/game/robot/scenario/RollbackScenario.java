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
import com.game.proto.login.EnterGameResponse;
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
import java.util.ArrayList;
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
 *   <li>撤销（§4.11）：下线后以 kick 回档的 {@code preSnapshotId} 再回档一次 → 差异接口无资产差异、重登钻石回到 1300。</li>
 *   <li>按时刻选源：LeaveGame → 轮询到 scene 发的 LOGOUT 快照经 Kafka 落库（内容 1300）→ 再进场加 250、下线 → 以那份快照的内容时刻作
 *       {@code targetTimeMs} 预演并回档 → 选中的就是它、差异接口无资产差异、钻石 = 1300。</li>
 *   <li>运维持有期间进游戏：上一步的作业夺到归属（CLAIMED 事件）之后立刻进游戏 → 这条连接上第一条 EnterGame 应答是 2005
 *       （login 的既有行为；{@link PlayerFlow} 会自动重试 2005，所以不看它的最终结果，而是翻这条连接的收包记录）。</li>
 * </ol>
 * 步骤 2 另核对「回档后差异接口无资产差异」。没有做的：帮会联动（快照之后捐献 → 回档被拒 / 带原因放行，前置步骤多）、整区回档与
 * {@code /admin/zone-snapshots}。步骤 5–7 与步骤 2 的差异核对是 2026-10-06 追加的，当天在本机单 scene 与双 scene 切片上各跑过一遍
 * （19 项全部通过，记录在 data-ops-spec §13.4 的「最终验证」）；纯函数与请求编码另有单测。
 *
 * <p>需要 dev / test（GM 加币）、xm-data 的 {@code XM_DATA_OPS_ENABLED=true}（本机切片脚本缺省打开，沉降 3 s、min-target-age 5 s）、
 * xm-guild 在跑（帮会检查）、Kafka 审计链路在跑（步骤 6 的 LOGOUT 快照）。步骤 7 要求作业持有归属的时间长于 login 等让出的 3 s
 * （沉降 + 复查等待；本机切片是 3 s + 2 s）。运维令牌取环境变量 {@code XM_ADMIN_TOKEN}，没有就读本机切片脚本生成的 {@code run/xm-admin-token}。
 */
public final class RollbackScenario {

    private static final String SERVICE = "SceneCurrencyClientPlayer";
    private static final int DIAMOND = 1;
    private static final int REASON_ROLLBACK_RESTORE = 16;
    private static final int KICKED_BY_ANOTHER = LoginErrorTip.login_error.kLoginBeKickByAnOtherAccount_VALUE;
    /** 进游戏撞上「归属还被别的写者持有」时 login 回的 tip（2005）：运维持有期间登录看到的就是它。 */
    static final int ENTER_IN_PROGRESS = LoginErrorTip.login_error.kLoginInProgress_VALUE;
    /** 判「这份快照是这次离场拍的」时给两边墙钟留的余量（robot 与 scene 不一定在同一台机器上）。 */
    private static final long CLOCK_SLACK_MS = 2000;
    /** 按时刻回档要等目标时刻早于现在 min-target-age（本机切片 5 s）：最多等这么久。 */
    private static final Duration TARGET_AGE_TIMEOUT = Duration.ofSeconds(40);
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
    private final int enterGame;
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
        this.enterGame = registry.requireId("ClientPlayerLogin", "EnterGame");
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
            List<String> offlineDifferences = assetDifferences(diff(pid, snapshotId));
            report.check(offlineDifferences.isEmpty(), "离线回档后差异接口：已落盘状态与快照 S 没有资产差异",
                    offlineDifferences.isEmpty() ? "无差异" : String.join("；", offlineDifferences), REF);

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

            // ---------------------------------------------------------------- 5 按 preSnapshotId 撤销 kick 回档（§4.11）
            leave(player);
            player = null;
            awaitPersistedDiamond(pid, snapshotId, 1000);
            JsonNode undo = awaitJob(rollback(pid, pre, "reject", "robot-rb-" + runTag + "-undo").get("jobId").asText());
            List<String> undoDifferences = assetDifferences(diff(pid, pre));
            report.check("succeeded".equals(undo.get("status").asText()) && undoDifferences.isEmpty(),
                    "按 preSnapshotId 撤销 kick 回档：作业 SUCCEEDED，已落盘状态与安全快照没有资产差异",
                    "status=" + undo.get("status").asText() + " code=" + undo.get("resultCode").asText() + " 差异="
                            + undoDifferences, REF);
            player = flow.enter(account, new Timings());
            long afterUndo = diamond(player.connection());
            report.check(afterUndo == 1300, "撤销后重登：钻石回到被覆盖之前的 1300", "钻石=" + afterUndo, REF);

            // ---------------------------------------------------------------- 6 登出快照经 Kafka 落库 → 按 targetTimeMs 选中
            long beforeLeave = System.currentTimeMillis();
            leave(player);
            player = null;
            JsonNode logout = awaitLogoutSnapshot(pid, beforeLeave - CLOCK_SLACK_MS);
            String logoutId = logout.get("snapshotId").asText();
            long logoutTime = logout.get("timeMs").asLong();
            long logoutDiamond = get("/admin/player-snapshots/" + logoutId + "?includeState=true")
                    .at("/playerState/state/currency/balances/" + DIAMOND).asLong(-1);
            report.check(logoutDiamond == 1300, "scene 发的 LOGOUT 快照经 Kafka 落库：内容是离场时的 1300",
                    "snapshot=" + logoutId + " timeMs=" + logoutTime + " 钻石=" + logoutDiamond, REF);
            player = flow.enter(account, new Timings());
            requireBalance("再加钻石 250", add(player.connection(), 250), 1550);
            leave(player);
            player = null;
            awaitPersistedDiamond(pid, logoutId, 1550);
            // 目标时刻取那份 LOGOUT 快照的内容时刻（选源含等号）：这之后的 LOGIN / LOGOUT 快照都更晚，选中的只能是它
            JsonNode plan = dryRunByTimeWhenOldEnough(pid, logoutTime);
            String plannedByDryRun = plan.at("/players/0/snapshot/snapshotId").asText();
            report.check(logoutId.equals(plannedByDryRun), "按 targetTimeMs 预演：选中的是那份经 Kafka 落库的 LOGOUT 快照",
                    "选中=" + plannedByDryRun + " 期望=" + logoutId + " cause="
                            + plan.at("/players/0/snapshot/causeName").asText(), REF);
            String timeJob = send("POST", "/admin/rollbacks", rollbackBody(json, pid, null, logoutTime, "reject", false)
                    .toString(), "robot-rb-" + runTag + "-time", 202).get("jobId").asText();

            // ---------------------------------------------------------------- 7 运维持有归属期间进游戏 → 2005
            boolean claimed = awaitEvent(timeJob, "CLAIMED");
            List<Integer> enterOutcomes;
            try {
                player = flow.enter(account, new Timings());
                enterOutcomes = enterGameOutcomes(player.connection().inbox().snapshot(0), enterGame);
            } catch (RobotException e) {
                if (!isEnterInProgressFailure(e.getMessage())) {
                    throw e;
                }
                // PlayerFlow 重试到头每一次都是 2005（持有时间长于它的重试窗口）：作业终结后下面再进
                enterOutcomes = List.of(ENTER_IN_PROGRESS);
            }
            report.check(claimed && firstEnterRejectedInProgress(enterOutcomes),
                    "运维持有归属期间进游戏：第一条 EnterGame 应答是 " + ENTER_IN_PROGRESS + "（收尾释放之后才进得去）",
                    "CLAIMED=" + claimed + " 这条连接上历次 EnterGame 应答的 tip=" + enterOutcomes, REF);
            JsonNode byTime = awaitJob(timeJob);
            JsonNode byTimeDetail = get("/admin/ops-jobs/" + timeJob + "/players").get(0);
            report.check("succeeded".equals(byTime.get("status").asText())
                            && logoutId.equals(byTimeDetail.get("plannedSnapshotId").asText())
                            && "RESTORED".equals(byTimeDetail.get("outcome").asText()),
                    "按 targetTimeMs 回档：作业 SUCCEEDED，写回的就是那份 LOGOUT 快照",
                    "status=" + byTime.get("status").asText() + " code=" + byTime.get("resultCode").asText() + " planned="
                            + byTimeDetail.get("plannedSnapshotId").asText() + " outcome="
                            + byTimeDetail.get("outcome").asText(), REF);
            List<String> byTimeDifferences = assetDifferences(diff(pid, logoutId));
            report.check(byTimeDifferences.isEmpty(), "按时刻回档后差异接口：已落盘状态与选中的快照没有资产差异",
                    byTimeDifferences.isEmpty() ? "无差异" : String.join("；", byTimeDifferences), REF);
            if (player == null) {
                player = flow.enter(account, new Timings());
            }
            long afterByTime = diamond(player.connection());
            report.check(afterByTime == 1300, "按时刻回档后：钻石 = 那份 LOGOUT 快照里的 1300", "钻石=" + afterByTime, REF);
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
        return send("POST", "/admin/rollbacks", rollbackBody(json, pid, snapshotId, null, ifOnline, false).toString(), key,
                202);
    }

    /**
     * 单人回档的请求体（{@code POST /admin/rollbacks}；字段名同 xm-data 的 {@code RollbackRequest.Body}）：{@code snapshotId}（按号）与
     * {@code targetTimeMs}（按时刻，该时刻之前最近的一份）二选一；玩家号、快照号是十进制字符串（uint64）。
     */
    static ObjectNode rollbackBody(ObjectMapper json, long pid, String snapshotId, Long targetTimeMs, String ifOnline,
                                   boolean dryRun) {
        if ((snapshotId == null) == (targetTimeMs == null)) {
            throw new IllegalArgumentException("snapshotId 与 targetTimeMs 二选一");
        }
        ObjectNode body = json.createObjectNode();
        body.put("scope", "players");
        body.putArray("players").add(Long.toUnsignedString(pid));
        if (snapshotId != null) {
            body.put("snapshotId", snapshotId);
        } else {
            body.put("targetTimeMs", targetTimeMs.longValue());
        }
        body.put("ifOnline", ifOnline);
        body.put("reason", "robot 回档演练（" + ifOnline + (dryRun ? "，预演" : "") + "）");
        if (dryRun) {
            body.put("dryRun", true);
        }
        return body;
    }

    private JsonNode diff(long pid, String snapshotId) throws RobotException {
        return get("/admin/players/" + Long.toUnsignedString(pid) + "/snapshot-diff?snapshot=" + snapshotId);
    }

    /** 轮询快照列表，直到出现内容时刻不早于 {@code sinceMs} 的 LOGOUT 快照（scene 在离场时拍、经 Kafka 落库）。 */
    private JsonNode awaitLogoutSnapshot(long pid, long sinceMs) throws RobotException {
        long deadline = System.nanoTime() + LANDING_TIMEOUT.toNanos();
        JsonNode list = null;
        while (System.nanoTime() < deadline) {
            list = get("/admin/player-snapshots?player=" + Long.toUnsignedString(pid) + "&cause=LOGOUT&order=desc&limit=5");
            Optional<JsonNode> found = newestAtOrAfter(list, sinceMs);
            if (found.isPresent()) {
                return found.get();
            }
            sleep(Duration.ofMillis(500));
        }
        throw new RobotException("离场后 " + LANDING_TIMEOUT.toSeconds() + " s 内没有等到 LOGOUT 快照落库（Kafka 审计链路在跑吗？）"
                + "：最近的 LOGOUT 快照 " + list);
    }

    /**
     * 按时刻回档的预演（同步、只读、不要幂等键）。目标时刻必须早于现在 xm-data 的 {@code min-target-age}（robot 不知道它配了多少，
     * 本机切片是 5 s）：回 400 就隔 1 s 再试，直到通过或超时。
     */
    private JsonNode dryRunByTimeWhenOldEnough(long pid, long targetTimeMs) throws RobotException {
        String body = rollbackBody(json, pid, null, targetTimeMs, "reject", true).toString();
        long deadline = System.nanoTime() + TARGET_AGE_TIMEOUT.toNanos();
        HttpResponse<String> last;
        while (true) {
            last = exchange("POST", "/admin/rollbacks", body, null);
            if (last.statusCode() == 200) {
                return parse(last.body());
            }
            if (last.statusCode() != 400 || System.nanoTime() > deadline) {
                throw new RobotException("按 targetTimeMs=" + targetTimeMs + " 预演回档返回 " + last.statusCode()
                        + "（400 一般是目标时刻距现在不足 xm-data 的 min-target-age，已等 " + TARGET_AGE_TIMEOUT.toSeconds()
                        + " s 内重试）：" + last.body());
            }
            sleep(Duration.ofSeconds(1));
        }
    }

    /** 轮询作业直到出现某类事件；作业已终结仍没有、或超时，返回 false。 */
    private boolean awaitEvent(String jobId, String type) throws RobotException {
        long deadline = System.nanoTime() + LANDING_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode job = get("/admin/ops-jobs/" + jobId);
            for (JsonNode e : job.path("events")) {
                if (type.equals(e.path("type").asText())) {
                    return true;
                }
            }
            String status = job.get("status").asText();
            if (!"queued".equals(status) && !"running".equals(status)) {
                return false;
            }
            sleep(Duration.ofMillis(200));
        }
        return false;
    }

    // ------------------------------------------------------------------ 纯函数（有单测）

    /**
     * 一条连接上历次 EnterGame 应答的结局，按到达顺序：成功记 0，被拒记应答体里的 tip 号（信封层的错误记信封上的 tip），
     * 消息体解析不了记 -1。只看应答（{@code id} 回显请求号、不为 0），不看推送。
     * {@link PlayerFlow} 对 2005 会自动重试，最终成不成功说明不了「中途被拒过」，所以要翻收包记录。
     */
    static List<Integer> enterGameOutcomes(List<Received> log, int enterGameMessageId) {
        List<Integer> out = new ArrayList<>();
        for (Received r : log) {
            if (r.messageId() != enterGameMessageId || r.requestId() == 0) {
                continue;
            }
            if (r.envelopeTipId() != 0) {
                out.add(r.envelopeTipId());
                continue;
            }
            EnterGameResponse response = r.parseOrNull(EnterGameResponse.parser());
            if (response == null) {
                out.add(-1);
            } else {
                out.add(response.hasErrorMessage() ? response.getErrorMessage().getId() : 0);
            }
        }
        return out;
    }

    /** 「运维持有期间进不去」成立：第一条 EnterGame 应答就是 2005（之后重试成功与否都不影响）。 */
    static boolean firstEnterRejectedInProgress(List<Integer> outcomes) {
        return !outcomes.isEmpty() && outcomes.get(0) == ENTER_IN_PROGRESS;
    }

    /**
     * {@link PlayerFlow#enter} 重试到头仍被 2005 拒绝时抛的异常（它的文案是「进游戏（26） 被拒：error_message.id=2005」）。
     * 只认这一种；别的失败照常往外抛。
     */
    static boolean isEnterInProgressFailure(String message) {
        return message != null && message.contains("进游戏（26）")
                && message.matches("(?s).*error_message\\.id=" + ENTER_IN_PROGRESS + "(\\D.*)?");
    }

    /**
     * 差异接口（{@code GET /admin/players/{id}/snapshot-diff}）应答里的<b>资产</b>差异：余额有变的币种、物品（按配置聚合 / 只在一边 /
     * 堆叠变化）、宝宝。没有差异返回空；应答缺这几段也算差异（形状变了不能悄悄当成「无差异」）。
     */
    static List<String> assetDifferences(JsonNode diff) {
        List<String> out = new ArrayList<>();
        JsonNode balances = diff.path("currency").path("balances");
        JsonNode items = diff.path("items");
        JsonNode pets = diff.path("pets");
        if (!balances.isArray() || !items.isObject() || !pets.isObject()) {
            out.add("差异应答缺 currency.balances / items / pets：" + diff);
            return out;
        }
        for (JsonNode row : balances) {
            if (!"0".equals(row.path("delta").asText())) {
                out.add("货币 " + row.path("currencyType").asText() + "：快照 " + row.path("snapshot").asText() + " → 现在 "
                        + row.path("current").asText());
            }
        }
        for (String list : List.of("byConfig", "onlyInSnapshot", "onlyInCurrent", "stackChanged")) {
            if (!items.path(list).isArray()) {
                out.add("差异应答缺 items." + list);
            } else if (!items.path(list).isEmpty()) {
                out.add("物品 " + list + "：" + items.path(list));
            }
        }
        for (String list : List.of("onlyInSnapshot", "onlyInCurrent", "changed")) {
            if (!pets.path(list).isArray()) {
                out.add("差异应答缺 pets." + list);
            } else if (!pets.path(list).isEmpty()) {
                out.add("宝宝 " + list + "：" + pets.path(list));
            }
        }
        return out;
    }

    /** 快照元数据列表里内容时刻（{@code timeMs}）不早于 {@code sinceMs} 的最新一份（同毫秒取号大的）；没有为空。 */
    static Optional<JsonNode> newestAtOrAfter(JsonNode snapshots, long sinceMs) {
        JsonNode best = null;
        for (JsonNode s : snapshots) {
            long t = s.path("timeMs").asLong(Long.MIN_VALUE);
            if (t < sinceMs) {
                continue;
            }
            if (best == null || t > best.path("timeMs").asLong() || (t == best.path("timeMs").asLong()
                    && Long.compareUnsigned(Long.parseUnsignedLong(s.path("snapshotId").asText("0")),
                    Long.parseUnsignedLong(best.path("snapshotId").asText("0"))) > 0)) {
                best = s;
            }
        }
        return Optional.ofNullable(best);
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
        HttpResponse<String> response = exchange(method, pathAndQuery, body, idempotencyKey);
        if (response.statusCode() != expected) {
            throw new RobotException("xm-data " + method + " " + pathAndQuery + " 返回 " + response.statusCode()
                    + "（期望 " + expected + "）：" + response.body());
        }
        return parse(response.body());
    }

    private JsonNode parse(String body) throws RobotException {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new RobotException("xm-data 的应答不是 JSON：" + body, e);
        }
    }

    /** 发一次请求，原样返回状态码与应答体（调用方自己判状态码）。 */
    private HttpResponse<String> exchange(String method, String pathAndQuery, String body, String idempotencyKey)
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
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
