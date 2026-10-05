package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.robot.client.AdminClient;
import com.game.robot.client.BattleAdminClient;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.BattleSupport.LobbyBot;
import com.game.table.CommonErrorTip;
import com.google.protobuf.ByteString;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * battle 直连面的负面用例（批次 6.2，battle-node-spec §13.8「场景 battle-edge」、§3.3–§3.5）。每条用例都新建连接，互不影响限频器与非法包计数：
 * <ul>
 *   <li>握手之前（都不回包，被关）：握手前发 {@code ClientRequest}（G4）、首帧发大厅的 {@code ClientTokenVerifyRequest}（类型名不收）、坏校验和；</li>
 *   <li>握手判定（逐字的拒绝串，然后 FIN，R4）：签名翻转一个字节 / 改成大写 / payload 追加未知字段 → {@code invalid ticket signature}；
 *       期限 5 s 的房间等 6 s 再握手 → {@code ticket rejected: expired}（字段判定先于名单判定）；</li>
 *   <li>已验证连接上的闸（信封错误，§3.5）：号 157 → 1005（id 与 message_id 回显、没有体）；整条 1025 B → 1010、恰好 1024 B 照常；
 *       1 秒内 4 条 140 → 第 4 条 1008；50 个非法包断开；再握手（垃圾 payload）回 success + 原 battle_id（B1）；</li>
 *   <li>逐帧分发（§7.4）：同一次写里合法的 140 后跟坏校验和的帧 → 先回 140 的应答，然后才断开；</li>
 *   <li>{@code --slow}：连上不握手，10 ± 1 s 被关（G3）。</li>
 * </ul>
 * 需要 A、B 两个新号在线（dev 建房按在线目录补路由）。{@code --expect-dev deny}（xm-battle 以 prod 运行）：dev 接口回 403，只跑不需要票的
 * 握手前用例与「垃圾票 → invalid ticket signature」，直连地址取 {@code --battle-admin-url} 的主机 + 缺省直连端口 {@value #DEFAULT_CLIENT_PORT}。
 */
public final class BattleEdgeScenario {

    static final String REF = "battle-node-spec §13.8 battle-edge";
    /** xm-battle 直连面缺省端口（application.yaml {@code xm.battle.client-port}）。 */
    static final int DEFAULT_CLIENT_PORT = 12000;
    /** 握手期限 10 s（G3），robot 接受 ±1 s。 */
    static final long HANDSHAKE_DEADLINE_MS = 10_000;
    static final long HANDSHAKE_DEADLINE_SLACK_MS = 1_000;
    /** 体积闸：整条 ClientRequest 序列化后 > 1024 B（§3.5 ①）。 */
    static final int MAX_REQUEST_BYTES = 1024;
    /** 非法包阈值缺省 50（§3.5）。 */
    static final int ILLEGAL_PACKET_THRESHOLD = 50;
    /** 过期用例的房间期限。 */
    static final Duration EXPIRING_DEADLINE = Duration.ofSeconds(5);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(3);
    private static final String INVALID_SIGNATURE = "invalid ticket signature";
    private static final String EXPIRED = "ticket rejected: expired";
    private static final int TIP_INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int TIP_RATE_LIMITED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    private static final int TIP_SIZE_EXCEEDED = CommonErrorTip.common_error.kMessageSizeExceeded_VALUE;

    private final RobotClient client;
    private final PlayerFlow flow;
    private final BattleIds ids;
    private final BattleAdminClient admin;
    private final String accountA;
    private final String accountB;
    private final boolean expectDevAllowed;
    private final boolean slow;
    private final Duration requestTimeout;
    private final CheckReport report = new CheckReport();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final SplittableRandom random = new SplittableRandom();

    private String host;
    private int port;

    public BattleEdgeScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, BattleAdminClient admin, String accountPrefix,
                              String runTag, boolean expectDevAllowed, boolean slow, Duration requestTimeout) {
        this.client = client;
        this.flow = flow;
        this.ids = BattleIds.resolve(registry);
        this.admin = admin;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.expectDevAllowed = expectDevAllowed;
        this.slow = slow;
        this.requestTimeout = requestTimeout;
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "be" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", message(e), REF);
        } finally {
            for (AutoCloseable c : closeables) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响结论
                }
            }
        }
        return report;
    }

    private void runChecks() throws RobotException {
        if (!admin.hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-battle 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        if (!expectDevAllowed) {
            runProdChecks();
            return;
        }
        LobbyBot a = enter("A", accountA);
        LobbyBot b = enter("B", accountB);
        String metricsBefore = admin.scrapeMetrics();

        // 主房间：PVP 1V1（B 不出手，6 s 定时结算，血厚打不完）；A 的票取自大厅 177
        long battleId = BattleFixtures.newBattleId(random);
        int markA = a.mark();
        BattleAdminClient.CreateOutcome created = admin.create(BattleFixtures.pvp(battleId,
                System.currentTimeMillis() + BattleScenario.LONG_DEADLINE.toMillis(), BattleFixtures.tank(a.id(), "A", 0, 0),
                BattleFixtures.tank(b.id(), "B", 1, 0)));
        if (!created.ok()) {
            throw new RobotException("dev 建房没有受理：" + created.describe());
        }
        BattleAssignedS2C ticket = a.awaitAssigned(markA, battleId).parse(BattleAssignedS2C.parser());
        host = ticket.getHost();
        port = ticket.getPort();
        // 过期用例的房间：期限 5 s，先建好，其它用例跑完再握手
        long expiringId = BattleFixtures.newBattleId(random);
        int markExpiring = a.mark();
        long expireAt = System.currentTimeMillis() + EXPIRING_DEADLINE.toMillis();
        BattleAdminClient.CreateOutcome expiring = admin.create(BattleFixtures.pve(expiringId, expireAt, BattleFixtures.hero(a.id(), "A", 0)));
        if (!expiring.ok()) {
            throw new RobotException("dev 建过期用例的房间没有受理：" + expiring.describe());
        }
        BattleAssignedS2C expiringTicket = a.awaitAssigned(markExpiring, expiringId).parse(BattleAssignedS2C.parser());
        report.note("A=" + BattleSupport.uid(a.id()) + " B=" + BattleSupport.uid(b.id()) + " battle_id=" + BattleSupport.uid(battleId)
                + " 过期用例 battle_id=" + BattleSupport.uid(expiringId) + " 直连 " + host + ":" + port);

        // ---- 握手之前 ----
        edge("握手前发 ClientRequest → 立即被关、无回包（G4）", () -> requestBeforeVerify(battleId));
        edge("首帧发大厅的 ClientTokenVerifyRequest → 被关、无回包", () -> lobbyVerifyType(ticket.getTokenPayload(), ticket.getTokenSignature()));
        edge("坏校验和的握手帧 → 被关、无回包", () -> badChecksum(ticket.getTokenPayload(), ticket.getTokenSignature()));

        // ---- 握手判定 ----
        edge("签名翻转一个字节 → 「" + INVALID_SIGNATURE + "」后 FIN", () -> rejected(ticket.getTokenPayload(),
                BattleFixtures.flipFirstHexChar(ticket.getTokenSignature()), INVALID_SIGNATURE));
        edge("签名改成大写 → 「" + INVALID_SIGNATURE + "」后 FIN（验签对大小写敏感）", () -> rejected(ticket.getTokenPayload(),
                BattleFixtures.upperCaseSignature(ticket.getTokenSignature()), INVALID_SIGNATURE));
        edge("payload 追加一个未知字段 → 「" + INVALID_SIGNATURE + "」后 FIN（验签用原字节）", () -> rejected(
                BattleFixtures.tamperedPayload(ticket.getTokenPayload()), ticket.getTokenSignature(), INVALID_SIGNATURE));

        // ---- 已验证连接上的闸 ----
        edge("已验证后发号 157 → 信封 {message_id 157, id 回显, error 1005}，没有体", () -> notWhitelisted(ticket, battleId));
        edge("整条 1025 B → 信封 1010；恰好 1024 B 照常应答", () -> oversize(ticket, battleId));
        edge("1 秒内 4 条 140 → 前 3 条照常应答、第 4 条信封 1008", () -> rateLimited(ticket, battleId));
        edge("连续 " + ILLEGAL_PACKET_THRESHOLD + " 个非法包 → 第 " + ILLEGAL_PACKET_THRESHOLD + " 个断开", () -> illegalThreshold(ticket, battleId));
        edge("已验证后再握手（垃圾 payload）→ success + 原 battle_id（B1）", () -> repeatHandshake(ticket, battleId));
        edge("同一次写里合法的 140 后跟坏帧 → 先回 140 的应答再断开（逐帧分发）", () -> validThenCorrupt(ticket, battleId));
        if (slow) {
            edge("连上不握手 → 10 ± 1 s 被关（G3）", this::idleHandshakeTimeout);
        } else {
            report.note("没带 --slow：跳过「连上不握手 10 s 被关」");
        }

        // ---- 过期票 ----
        edge("期限 5 s 的房间、6 s 后再握手 → 「" + EXPIRED + "」后 FIN（字段判定先于名单判定）", () -> {
            long wait = expireAt + 1000 - System.currentTimeMillis();
            if (wait > 0) {
                BattleSupport.sleep(Duration.ofMillis(wait));
            }
            rejected(expiringTicket.getTokenPayload(), expiringTicket.getTokenSignature(), EXPIRED);
        });

        admin.destroy(battleId, "robot_battle_edge");
        String metricsAfter = admin.scrapeMetrics();
        metricGrew(metricsBefore, metricsAfter, "xm_battle_disconnects_total", "reason=\"request_before_verify\"");
        metricGrew(metricsBefore, metricsAfter, "xm_battle_disconnects_total", "reason=\"illegal_packets\"");
        metricGrew(metricsBefore, metricsAfter, "xm_battle_handshakes_total", "result=\"ticket_hmac_mismatch\"");
        metricGrew(metricsBefore, metricsAfter, "xm_battle_handshakes_total", "result=\"expired\"");
        metricGrew(metricsBefore, metricsAfter, "xm_battle_handshakes_total", "result=\"repeat\"");
    }

    /** prod：dev 接口 403；不需要票的握手前用例照跑，垃圾票按签名不符拒绝。 */
    private void runProdChecks() throws RobotException {
        BattleAdminClient.HttpResult denied = admin.post(BattleAdminClient.CREATE, BattleFixtures.pve(BattleFixtures.newBattleId(random),
                System.currentTimeMillis() + 60_000, BattleFixtures.hero(1, "probe", 0)));
        report.check(denied.status() == 403, "dev 接口在 prod 运行模式下回 403（--expect-dev deny）", "status=" + denied.status() + " " + denied.text(),
                "battle-node-spec §7.12");
        host = URI.create(admin.baseUrl()).getHost();
        port = DEFAULT_CLIENT_PORT;
        report.note("直连 " + host + ":" + port + "（--battle-admin-url 的主机 + 缺省直连端口）");
        ByteString payload = ByteString.copyFromUtf8("garbage-payload");
        ByteString signature = ByteString.copyFrom("0".repeat(64), StandardCharsets.US_ASCII);
        edge("握手前发 ClientRequest → 立即被关、无回包（G4）", () -> requestBeforeVerify(1));
        edge("首帧发大厅的 ClientTokenVerifyRequest → 被关、无回包", () -> lobbyVerifyType(payload, signature));
        edge("坏校验和的握手帧 → 被关、无回包", () -> badChecksum(payload, signature));
        edge("垃圾票 → 「" + INVALID_SIGNATURE + "」后 FIN（prod 必有密钥，先验签）", () -> rejected(payload, signature, INVALID_SIGNATURE));
    }

    // ---------------------------------------------------------------- 握手之前

    private void requestBeforeVerify(long battleId) throws RobotException {
        try (Direct d = open("握手前请求")) {
            d.raw().send(ClientRequest.newBuilder().setId(1).setMessageId(ids.getBattleState())
                    .setBody(GetBattleStateRequest.newBuilder().setBattleId(battleId).build().toByteString()).build());
            List<BattleFrame> tail = d.untilClosed(0, CLOSE_TIMEOUT);
            report.check(BattleSupport.onlyClosed(tail), "握手前发 ClientRequest → 立即被关、无回包（G4）", BattleSupport.labels(tail).toString(),
                    "battle-node-spec §3.3");
        }
    }

    private void lobbyVerifyType(ByteString payload, ByteString signature) throws RobotException {
        try (Direct d = open("大厅握手类型")) {
            d.raw().send(ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(signature).build());
            List<BattleFrame> tail = d.untilClosed(0, CLOSE_TIMEOUT);
            report.check(BattleSupport.onlyClosed(tail), "首帧发大厅的 ClientTokenVerifyRequest → 被关、无回包", BattleSupport.labels(tail).toString(),
                    "battle-node-spec §3.2");
        }
    }

    private void badChecksum(ByteString payload, ByteString signature) throws RobotException {
        try (Direct d = open("坏校验和")) {
            d.raw().sendRaw(BattleFixtures.corruptChecksum(BattleFixtures.encodeFrame(
                    BattleTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(signature).build())));
            List<BattleFrame> tail = d.untilClosed(0, CLOSE_TIMEOUT);
            report.check(BattleSupport.onlyClosed(tail), "坏校验和的握手帧 → 被关、无回包", BattleSupport.labels(tail).toString(), "battle-node-spec §3.2");
        }
    }

    private void idleHandshakeTimeout() throws RobotException {
        long start = System.nanoTime();
        try (Direct d = open("不握手")) {
            List<BattleFrame> tail = d.untilClosed(0, Duration.ofMillis(HANDSHAKE_DEADLINE_MS + 2 * HANDSHAKE_DEADLINE_SLACK_MS));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(tail.get(tail.size() - 1).atNanos() - start);
            report.check(BattleSupport.onlyClosed(tail) && Math.abs(elapsed - HANDSHAKE_DEADLINE_MS) <= HANDSHAKE_DEADLINE_SLACK_MS,
                    "连上不握手 → 10 ± 1 s 被关、无回包（G3）", elapsed + " ms " + BattleSupport.labels(tail), "battle-node-spec §3.3");
        }
    }

    // ---------------------------------------------------------------- 握手判定

    private void rejected(ByteString payload, ByteString signature, String expectedError) throws RobotException {
        try (Direct d = open("拒绝")) {
            BattleTokenVerifyResponse response = d.verify(payload, signature);
            List<BattleFrame> tail = d.untilClosed(1, Duration.ofSeconds(2));
            report.check(!response.getSuccess() && response.getError().equals(expectedError) && response.getBattleId() == 0
                            && BattleOrder.exactly(tail, List.of(BattleOrder.FIN)) == null,
                    "握手被拒「" + expectedError + "」：拒绝串逐字一致、battle_id 不在线上，应答之后 FIN（R4）",
                    BattleScenario.describe(response) + " 之后 " + BattleSupport.labels(tail), "battle-node-spec §3.4");
        }
    }

    // ---------------------------------------------------------------- 已验证连接上的闸

    private void notWhitelisted(BattleAssignedS2C ticket, long battleId) throws RobotException {
        try (Direct d = verified("157", ticket, battleId)) {
            int from = d.mark();
            long id = d.raw().request(ids.notWhitelisted(), state(battleId));
            BattleFrame reply = d.awaitReply(from, ids.notWhitelisted(), id, requestTimeout);
            report.check(reply.isEnvelopeError() && reply.envelopeTipId() == TIP_INVALID_PARAMETER && reply.requestId() == id
                            && reply.content().getSerializedMessage().isEmpty() && d.raw().isOpen(),
                    "已验证后发号 " + ids.notWhitelisted() + " → 信封 1005（id / message_id 回显、没有体），不断连",
                    reply.label() + " id=" + reply.requestId(), "battle-node-spec §3.5 ③");
        }
    }

    private void oversize(BattleAssignedS2C ticket, long battleId) throws RobotException {
        try (Direct d = verified("体积", ticket, battleId)) {
            int from = d.mark();
            d.raw().send(BattleFixtures.paddedRequest(77, ids.getBattleState(), state(battleId), MAX_REQUEST_BYTES + 1));
            BattleFrame tooBig = d.awaitReply(from, ids.getBattleState(), 77, requestTimeout);
            report.check(tooBig.isEnvelopeError() && tooBig.envelopeTipId() == TIP_SIZE_EXCEEDED,
                    "整条 " + (MAX_REQUEST_BYTES + 1) + " B 的 140 → 信封 1010（id 回显）", tooBig.label(), "battle-node-spec §3.5 ①");
            int from2 = d.mark();
            d.raw().send(BattleFixtures.paddedRequest(78, ids.getBattleState(), state(battleId), MAX_REQUEST_BYTES));
            BattleFrame exact = d.awaitReply(from2, ids.getBattleState(), 78, requestTimeout);
            report.check(exact.isReply(), "恰好 " + MAX_REQUEST_BYTES + " B 的 140 → 照常应答", exact.label(), "battle-node-spec §3.5 ①");
        }
    }

    private void rateLimited(BattleAssignedS2C ticket, long battleId) throws RobotException {
        try (Direct d = verified("限频", ticket, battleId)) {
            int from = d.mark();
            List<Long> requestIds = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requestIds.add(d.raw().request(ids.getBattleState(), state(battleId)));
            }
            List<String> outcomes = new ArrayList<>();
            for (long id : requestIds) {
                outcomes.add(d.awaitReply(from, ids.getBattleState(), id, requestTimeout).label());
            }
            String ok = "reply:" + ids.getBattleState();
            List<String> expected = List.of(ok, ok, ok, "error:" + ids.getBattleState() + ":" + TIP_RATE_LIMITED);
            report.check(outcomes.equals(expected), "1 秒内 4 条 140：前 3 条照常应答、第 4 条信封 1008（每条连接一份滑动窗口）",
                    outcomes.toString(), "battle-node-spec §3.5 ②");
        }
    }

    private void illegalThreshold(BattleAssignedS2C ticket, long battleId) throws RobotException {
        try (Direct d = verified("非法包", ticket, battleId)) {
            int from = d.mark();
            int below = ILLEGAL_PACKET_THRESHOLD - 1;
            for (int i = 0; i < below; i++) {
                d.raw().request(ids.notWhitelisted(), state(battleId));
            }
            AtomicInteger errors = new AtomicInteger();
            Optional<BattleFrame> last = d.await(from, f -> f.isEnvelopeError() && errors.incrementAndGet() == below, requestTimeout);
            String whitelist = "error:" + ids.notWhitelisted() + ":" + TIP_INVALID_PARAMETER;
            String limited = "error:" + ids.notWhitelisted() + ":" + TIP_RATE_LIMITED;
            boolean allEnvelope = d.since(from).stream().filter(f -> !f.isPush())
                    .allMatch(f -> f.label().equals(whitelist) || f.label().equals(limited));
            Optional<BattleFrame> earlyClose = d.await(from, BattleFrame::isClosed, BattleScenario.SILENCE);
            report.check(last.isPresent() && allEnvelope && earlyClose.isEmpty(),
                    "前 " + below + " 个非法包（157：白名单外 1005 / 同号限频 1008）各回一条信封错误，连接仍在",
                    "信封错误 " + errors.get() + " 条，提前关闭=" + earlyClose.isPresent(), "battle-node-spec §3.5");
            int before = d.mark();
            d.raw().request(ids.notWhitelisted(), state(battleId));
            List<BattleFrame> tail = d.untilClosed(before, CLOSE_TIMEOUT);
            long nonPush = tail.stream().filter(f -> !f.isPush()).count();
            report.check(tail.get(tail.size() - 1).isClosed() && nonPush <= 2,
                    "第 " + ILLEGAL_PACKET_THRESHOLD + " 个非法包 → 断开（刚写的信封错误不保证送达）", BattleSupport.labels(tail).toString(),
                    "battle-node-spec §3.5");
        }
    }

    private void repeatHandshake(BattleAssignedS2C ticket, long battleId) throws RobotException {
        try (Direct d = verified("再握手", ticket, battleId)) {
            int from = d.mark();
            d.raw().send(BattleTokenVerifyRequest.newBuilder().setPayload(ByteString.copyFromUtf8("garbage"))
                    .setSignature(ByteString.copyFromUtf8("garbage")).build());
            Optional<BattleFrame> again = d.await(from, BattleFrame::isVerify, requestTimeout);
            report.check(again.isPresent() && again.get().verify().getSuccess() && again.get().verify().getBattleId() == battleId && d.raw().isOpen(),
                    "已验证后再握手（垃圾 payload）→ success + 原 battle_id，不看新票、不断连（B1）",
                    again.map(BattleFrame::label).orElse("没有应答 " + d.labelsSince(from)), "battle-node-spec §3.4 第 0 步");
        }
    }

    private void validThenCorrupt(BattleAssignedS2C ticket, long battleId) throws RobotException {
        try (Direct d = verified("逐帧", ticket, battleId)) {
            int from = d.mark();
            byte[] good = BattleFixtures.encodeFrame(ClientRequest.newBuilder().setId(1).setMessageId(ids.getBattleState())
                    .setBody(state(battleId).toByteString()).build());
            byte[] bad = BattleFixtures.corruptChecksum(BattleFixtures.encodeFrame(ClientRequest.newBuilder().setId(2)
                    .setMessageId(ids.getBattleState()).setBody(state(battleId).toByteString()).build()));
            byte[] both = new byte[good.length + bad.length];
            System.arraycopy(good, 0, both, 0, good.length);
            System.arraycopy(bad, 0, both, good.length, bad.length);
            d.raw().sendRaw(both);
            List<BattleFrame> tail = d.untilClosed(from, CLOSE_TIMEOUT).stream().filter(f -> !f.isPush()).toList();
            boolean ok = tail.size() == 2 && tail.get(0).isReplyTo(ids.getBattleState(), 1) && tail.get(1).isClosed();
            report.check(ok, "同一次写里合法的 140 后跟坏校验和的帧 → 先回 140 的应答，然后才断开（逐帧分发，§7.4）",
                    BattleSupport.labels(tail).toString(), "battle-node-spec §7.4");
        }
    }

    // ---------------------------------------------------------------- 工具

    @FunctionalInterface
    private interface EdgeCase {
        void run() throws RobotException;
    }

    /** 一条用例：中断只记这一条失败，接着跑下一条。 */
    private void edge(String name, EdgeCase body) {
        try {
            body.run();
        } catch (RobotException | RuntimeException e) {
            report.fail("用例中断：" + name, message(e), REF);
        }
    }

    private LobbyBot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        closeables.add(player.connection());
        return new LobbyBot(name, player, ids, requestTimeout);
    }

    private Direct open(String name) throws RobotException {
        return Direct.open(client, name, host, port, ids);
    }

    /** 新连接、凭票握手（不补拉 140：用例自己数请求）。 */
    private Direct verified(String name, BattleAssignedS2C ticket, long battleId) throws RobotException {
        Direct d = open(name);
        BattleTokenVerifyResponse response = d.verify(ticket.getTokenPayload(), ticket.getTokenSignature());
        if (!response.getSuccess() || response.getBattleId() != battleId) {
            d.close();
            throw new RobotException(name + "：凭票握手没成功（" + BattleScenario.describe(response) + "）");
        }
        return d;
    }

    private static GetBattleStateRequest state(long battleId) {
        return GetBattleStateRequest.newBuilder().setBattleId(battleId).build();
    }

    private void metricGrew(String before, String after, String metric, String label) {
        double b = AdminClient.sum(before, metric, label);
        double a = AdminClient.sum(after, metric, label);
        report.check(a > b, "指标 " + metric + "{" + label + "} 有增长", b + " → " + a, "battle-node-spec §9");
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
