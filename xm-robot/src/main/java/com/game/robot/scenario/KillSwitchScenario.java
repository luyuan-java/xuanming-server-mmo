package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.contract.MessageIdRegistry;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.PullChatHistoryRequest;
import com.game.proto.friend.ListBlocksRequest;
import com.game.robot.client.AdminClient;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.CommonErrorTip;
import com.game.table.SceneErrorTip;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按方法热关停端到端（基线 go/shared/killswitch；Java 规则写在 Redis 哈希 xm:killswitch，经 xm-data 运维接口）：
 * <ol>
 *   <li>关停 {@code ClientPlayerChat/PullChatHistory} → 拉历史拿到信封 1003（各进程每秒同步一次规则，轮询等生效）；别的方法照常；</li>
 *   <li>{@code ClientPlayerChat/*} 关停 + 精确规则 {@code deny=false} 豁免拉历史 → 拉历史照常；</li>
 *   <li>全局 {@code *} 关停 + 精确豁免拉历史 → ListBlocks 1003、拉历史照常（gate 放行后 chat 的 Dubbo 过滤器不再拦一次）；</li>
 *   <li>Dubbo 层：关停 {@code com.game.api.SceneDirectoryService/assign} → 另一个账号进游戏被拒（login 调场景分配失败，3023）；</li>
 *   <li>删掉全部规则 → 恢复。</li>
 * </ol>
 * <b>只在隔离的本机切片上跑</b>：{@code xm:killswitch} 是全服共享的，第 3、4 步会在几秒内关掉整个区的客户端请求与进游戏。
 * 保护（同 drain 场景）：本场景写的规则都带 reason {@value #MARKER}；开跑前本场景要用的规则键上已有别的规则（运维写的）就一个字都不写、
 * 整体跳过；带标记的是上次中断留下的，直接删；写过的键登记在关闭钩子里，Ctrl-C 也会删掉。
 */
public final class KillSwitchScenario {

    static final String MARKER = "xm-robot killswitch 演练";
    private static final String REF = "PARITY「热关停」行";
    private static final String PATH = "/admin/killswitch";
    private static final String PULL = "ClientPlayerChat/PullChatHistory";
    private static final String CHAT_ALL = "ClientPlayerChat/*";
    private static final String GLOBAL = "*";
    private static final String DUBBO_ASSIGN = "com.game.api.SceneDirectoryService/assign";
    private static final List<String> PATTERNS = List.of(PULL, CHAT_ALL, GLOBAL, DUBBO_ASSIGN);
    private static final Duration SYNC_WAIT = Duration.ofSeconds(6);
    private static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    private static final String ENTER_SCENE_FAILED = "error_message.id=" + SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;

    private final PlayerFlow flow;
    private final AdminClient admin;
    private final String account;
    private final String secondAccount;
    private final int pull;
    private final int listBlocks;
    /** 本次写过的规则键（关闭钩子按它清理）。 */
    private final Set<String> written = ConcurrentHashMap.newKeySet();

    public KillSwitchScenario(PlayerFlow flow, AdminClient admin, MessageIdRegistry registry, String accountPrefix,
                              String runTag) {
        this.flow = flow;
        this.admin = admin;
        this.account = accountName(accountPrefix, runTag);
        this.secondAccount = account + "b";
        this.pull = registry.requireId("ClientPlayerChat", "PullChatHistory");
        this.listBlocks = registry.requireId("ClientPlayerFriend", "ListBlocks");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "ks" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        if (!admin.hasToken()) {
            report.fail("缺运维令牌", "设 XM_ADMIN_TOKEN 或在仓库根目录放 run/xm-admin-token（start-slice.sh 会生成）", REF);
            return report;
        }
        try {
            Optional<String> occupied = occupiedByOthers();
            if (occupied.isPresent()) {
                // 运维正在用这些规则键止血：一个字都不写，免得撤掉别人的规则
                report.note("规则键上已有运维写的规则，跳过 killswitch 场景：" + occupied.get());
                return report;
            }
        } catch (RobotException e) {
            report.fail("列出现有规则", e.getMessage(), REF);
            return report;
        }
        Thread hook = new Thread(this::clearQuietly, "killswitch-scenario-clear");
        Runtime.getRuntime().addShutdownHook(hook);
        EnteredPlayer player = null;
        try {
            player = flow.enter(account, new Timings());
            runChecks(report, player.connection());
        } catch (RobotException e) {
            report.fail("流程异常", e.getMessage(), REF);
        } finally {
            if (!clearQuietly()) {
                report.note("清理规则失败，请手工删除：" + written.stream().map(p -> "DELETE " + PATH + "?pattern=" + p).toList());
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // 已经在关闭中：钩子会跑
            }
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    private void runChecks(CheckReport report, GameConnection c) throws RobotException {
        PullChatHistoryRequest pullWorld = PullChatHistoryRequest.newBuilder()
                .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).build();
        Message blocksReq = ListBlocksRequest.getDefaultInstance();

        set(PULL, true);
        report.check(eventually(() -> envelopeTip(c, pull, pullWorld) == SERVICE_UNAVAILABLE), "关停拉历史 → 信封 1003",
                "等待 ≤ " + SYNC_WAIT.toSeconds() + " s", REF);
        report.check(envelopeTip(c, listBlocks, blocksReq) == 0, "别的方法照常（ListBlocks）", "", REF);

        set(CHAT_ALL, true);
        set(PULL, false);
        report.check(eventually(() -> envelopeTip(c, pull, pullWorld) == 0), "服务通配关停 + 精确规则豁免 → 拉历史照常", "", REF);

        remove(CHAT_ALL);
        set(GLOBAL, true);
        report.check(eventually(() -> envelopeTip(c, listBlocks, blocksReq) == SERVICE_UNAVAILABLE
                        && envelopeTip(c, pull, pullWorld) == 0),
                "全局 * 关停 + 精确豁免 → ListBlocks 1003、拉历史照常（后端 Dubbo 过滤器不再拦一次）", "", REF);
        remove(GLOBAL);
        remove(PULL);

        set(DUBBO_ASSIGN, true);
        String[] lastFailure = {""};
        boolean assignBlocked = eventually(() -> {
            try {
                flow.enter(secondAccount, new Timings()).connection().close();
                return false;
            } catch (RobotException e) {
                lastFailure[0] = e.getMessage();
                return e.getMessage() != null && e.getMessage().contains(ENTER_SCENE_FAILED);
            }
        });
        report.check(assignBlocked, "Dubbo 层关停 SceneDirectoryService/assign → 另一个账号进游戏被拒（3023）", lastFailure[0], REF);
        remove(DUBBO_ASSIGN);

        boolean restored = eventually(() -> {
            if (envelopeTip(c, listBlocks, blocksReq) != 0 || envelopeTip(c, pull, pullWorld) != 0) {
                return false;
            }
            try {
                flow.enter(secondAccount, new Timings()).connection().close();
                return true;
            } catch (RobotException e) {
                return false;
            }
        });
        report.check(restored, "删掉规则后恢复（客户端方法与进游戏）", "", REF);
    }

    /** 本场景要用的规则键上有没有别人（reason 不是本场景标记）写的规则；有就返回描述。 */
    private Optional<String> occupiedByOthers() throws RobotException {
        JsonNode rules = admin.get(PATH);
        if (rules == null || !rules.isArray()) {
            return Optional.empty();
        }
        for (JsonNode rule : rules) {
            if (PATTERNS.contains(rule.path("pattern").asText()) && !MARKER.equals(rule.path("reason").asText())) {
                return Optional.of(rule.toString());
            }
        }
        return Optional.empty();
    }

    private void set(String pattern, boolean deny) throws RobotException {
        written.add(pattern);
        admin.put(PATH, "{\"pattern\":\"" + pattern + "\",\"deny\":" + deny + ",\"reason\":\"" + MARKER + "\"}");
    }

    private void remove(String pattern) throws RobotException {
        admin.delete(PATH + "?pattern=" + AdminClient.query(pattern));
        written.remove(pattern);
    }

    /** 删掉本场景写过的全部规则；有删不掉的返回 false。 */
    private boolean clearQuietly() {
        boolean ok = true;
        for (String p : List.copyOf(written)) {
            try {
                remove(p);
            } catch (RobotException | RuntimeException e) {
                ok = false;
                System.err.println("[killswitch] 删除规则失败，请手工删除：DELETE " + PATH + "?pattern=" + p + "（" + e + "）");
            }
        }
        return ok;
    }

    @FunctionalInterface
    private interface Probe {
        boolean ok() throws RobotException;
    }

    /** 规则每秒同步一次：在等待上限内反复探测（每次间隔 1.1 s，避开 gate 按消息号的限频）。 */
    private static boolean eventually(Probe probe) throws RobotException {
        long deadline = System.nanoTime() + SYNC_WAIT.toNanos();
        while (true) {
            if (probe.ok()) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            sleep(1100);
        }
    }

    /** 发一个请求，返回应答信封上的 tip（0 = 正常应答）；超时返回 -1。 */
    private static int envelopeTip(GameConnection c, int messageId, Message body) throws RobotException {
        int mark = c.inbox().size();
        long id = c.send(messageId, body);
        Optional<Received> reply = c.await(mark, r -> r.messageId() == messageId && r.requestId() == id, Duration.ofSeconds(5));
        return reply.map(Received::envelopeTipId).orElse(-1);
    }

    private static void sleep(long millis) throws RobotException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
