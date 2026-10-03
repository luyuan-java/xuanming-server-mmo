package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.contract.MessageIdRegistry;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.robot.client.AdminClient;
import com.game.robot.client.GameConnection;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.time.Duration;

/**
 * 资产防护端到端：
 * <ol>
 *   <li>全服产出封禁：经 xm-data 运维接口封禁绑钻（{@code PUT /admin/gain-blocks/currency/2}），轮询 GM 加绑钻直到被拒 27005
 *       （名单经 Redis 通知秒级生效，须在 3s 内），别的币种不受影响；解封后恢复。封禁请求发出过就解封（名单全服生效，不能留下）；
 *       绑钻本来就在名单上（运维的真实封禁）时不演练。</li>
 *   <li>获取异常检测：GM 一次加钻石 100001（超过缺省累计阈值 100000）→ scene 指标 {@code xm_scene_gain_anomalies_total}
 *       恰好 +1；线外再加一次不再告警（越线只告警一次）。</li>
 * </ol>
 * 需要服务端放行 GM（dev / test）、Redis 与 xm-data 在跑；运维令牌同 audit 场景。
 */
public final class GuardScenario {

    private static final String SERVICE = "SceneCurrencyClientPlayer";
    private static final int GOLD = 0;
    private static final int DIAMOND = 1;
    private static final int BOUND_DIAMOND = 2;
    private static final int TIP_BLOCKED = 27005;
    private static final long OVER_DEFAULT_AMOUNT = 100_001;
    private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(15);
    /** 封禁 / 解封经 pub/sub 通知生效的时限（远小于 10s 的兜底重读周期）。 */
    private static final Duration NOTIFY_BOUND = Duration.ofSeconds(3);
    private static final Duration CALL_SPACING = Duration.ofMillis(400);
    private static final String BLOCK_PATH = "/admin/gain-blocks/currency/" + BOUND_DIAMOND;
    private static final String REF_BLOCK = "PARITY「全服产出封禁」行";
    private static final String REF_ANOMALY = "PARITY「获取异常检测」行";

    private final PlayerFlow flow;
    private final String account;
    private final Duration requestTimeout;
    private final AdminClient admin;
    private final String sceneMetricsUrl;
    private final int gmAdd;

    public GuardScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                         Duration requestTimeout, AdminClient admin, String sceneMetricsUrl) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.requestTimeout = requestTimeout;
        this.admin = admin;
        this.sceneMetricsUrl = sceneMetricsUrl;
        this.gmAdd = registry.requireId(SERVICE, "GmAddCurrency");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "gd" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        if (!admin.hasToken()) {
            report.fail("运维令牌", "没有 XM_ADMIN_TOKEN，也没有 run/xm-admin-token（先用 tools/local/start-slice.sh 起切片）",
                    REF_BLOCK);
            return report;
        }
        EnteredPlayer player = null;
        String phaseRef = REF_BLOCK;
        try {
            player = flow.enter(account, new Timings());
            GameConnection c = player.connection();

            JsonNode existing = find(admin.get("/admin/gain-blocks").path("currency"), BOUND_DIAMOND);
            if (existing != null) {
                // 名单全服生效：运维已经封了绑钻就不演练，免得覆盖、再解除真实的封禁
                report.fail("全服产出封禁", "绑钻已在全服封禁名单上（操作人 " + existing.path("operator").asText() + "，原因 "
                        + existing.path("reason").asText() + "），跳过封禁演练", REF_BLOCK);
            } else {
                blockDrill(report, c);
            }

            phaseRef = REF_ANOMALY;

            phaseRef = REF_ANOMALY;
            double before = anomalies();
            int first = add(c, DIAMOND, OVER_DEFAULT_AMOUNT);
            int second = add(c, DIAMOND, 1);
            double delta = anomalies() - before;
            report.check(first == 0 && second == 0 && delta == 1,
                    "一次加钻石 " + OVER_DEFAULT_AMOUNT + " 越过累计阈值告警一次，线外再加不重复告警",
                    "tip=" + first + "/" + second + "，告警 +" + delta, REF_ANOMALY);
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), phaseRef);
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    /** 封禁 → 被拒 → 别的币种照常 → 解封 → 恢复。生效要在通知时限内（兜底重读 10s 才生效说明通知没起作用）。 */
    private void blockDrill(CheckReport report, GameConnection c) throws RobotException {
        boolean attempted = false;
        try {
            attempted = true;
            admin.put(BLOCK_PATH + "?reason=" + AdminClient.query("xm-robot guard 演练"));
            JsonNode entry = find(admin.get("/admin/gain-blocks").path("currency"), BOUND_DIAMOND);
            report.check(entry != null && AdminClient.OPERATOR.equals(entry.path("operator").asText()),
                    "运维接口封禁绑钻后名单里有它、带操作人", String.valueOf(entry), REF_BLOCK);
            long tookMs = awaitTip(c, BOUND_DIAMOND, TIP_BLOCKED);
            report.check(tookMs < NOTIFY_BOUND.toMillis(), "封禁经 Redis 通知生效：GM 加绑钻被拒 27005",
                    tookMs + "ms 内生效（时限 " + NOTIFY_BOUND.toMillis() + "ms；兜底重读是 10s）", REF_BLOCK);
            int goldTip = add(c, GOLD, 1);
            report.check(goldTip == 0, "封禁期间别的币种照常加", "加金币 tip=" + goldTip, REF_BLOCK);
        } finally {
            // 封禁请求发出过就解封（幂等）：写成功、回包失败时名单里也可能已经有它
            if (attempted) {
                admin.delete(BLOCK_PATH + "?reason=" + AdminClient.query("xm-robot guard 演练结束"));
            }
        }
        long tookMs = awaitTip(c, BOUND_DIAMOND, 0);
        report.check(tookMs < NOTIFY_BOUND.toMillis(), "解封经 Redis 通知生效：GM 加绑钻恢复",
                tookMs + "ms 内恢复（时限 " + NOTIFY_BOUND.toMillis() + "ms）", REF_BLOCK);
    }

    private double anomalies() throws RobotException {
        return AdminClient.sum(admin.scrape(sceneMetricsUrl), "xm_scene_gain_anomalies_total",
                "category=\"currency\"", "currency_type=\"" + DIAMOND + "\"");
    }

    private static JsonNode find(JsonNode entries, int id) {
        for (JsonNode e : entries) {
            if (e.path("id").asInt(-1) == id) {
                return e;
            }
        }
        return null;
    }

    /** 反复 GM 加 1 直到回 {@code expected}（名单变更是异步生效的）；返回用时毫秒，超时抛。 */
    private long awaitTip(GameConnection c, int type, int expected) throws RobotException {
        long start = System.nanoTime();
        long deadline = start + APPLY_TIMEOUT.toNanos();
        int last;
        do {
            last = add(c, type, 1);
            if (last == expected) {
                return (System.nanoTime() - start) / 1_000_000;
            }
        } while (System.nanoTime() < deadline);
        throw new RobotException("币种 " + type + " 等 " + APPLY_TIMEOUT.toSeconds() + "s 仍未回 tip=" + expected
                + "，最后一次 tip=" + last);
    }

    /** 每次调用前歇一下：gate 按消息号限频（缺省每秒 3 条）。 */
    private int add(GameConnection c, int type, long amount) throws RobotException {
        try {
            Thread.sleep(CALL_SPACING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
        return c.call(gmAdd, GmAddCurrencyRequest.newBuilder().setCurrencyType(type).setAmount(amount).build(),
                GmAddCurrencyResponse.parser(), requestTimeout).getErrorMessage().getId();
    }
}
