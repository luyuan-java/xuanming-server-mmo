package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.robot.client.AdminClient;
import com.game.robot.client.AssignGateClient;
import com.game.robot.client.GameConnection;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import java.time.Duration;

/**
 * 登录排队（批次 3.4b）：经 xm-data 运维接口把本区容量压到 1，看 assign-gate 排队、轮询、放行。
 * <ol>
 *   <li>连发 assign-gate 直到回 100（第一个可能走快速通道占掉唯一的位置）：{@code queue_source="login"}、令牌、名次、队长、轮询间隔；</li>
 *   <li>容量还满时轮询 {@code /api/queue-status} 仍回 100（同一令牌）；</li>
 *   <li>把容量调回原值 → 放行循环放行 → 轮询拿到 gate 令牌（code 0），用它连 gate 握手成功；</li>
 *   <li>同一令牌再轮询 410 {@code queue_token_expired}（放行槽只取一次）；缺令牌 410 {@code missing_queue_token}；乱写的令牌 410。</li>
 * </ol>
 * 结束时（含中途失败、Ctrl-C）把容量恢复原值；读到的容量已是 1（上次被 kill -9）时按缺省 5000 恢复。要求本机切片打开排队（start-slice.sh 缺省 {@code XM_GATEWAY_QUEUE_ENABLED=true}）
 * 与运维令牌。
 */
public final class QueueScenario {

    private static final String REF = "PARITY「登录排队」行；盘点 login-queue / login-queue-dispatcher";
    private static final int TIGHT_CAPACITY = 1;
    /** 读到的容量就是压紧值时（上次跑到一半被杀），按区服缺省容量恢复。 */
    private static final int DEFAULT_CAPACITY = 5000;
    /** 区服目录缓存 1 s。 */
    private static final Duration DIRECTORY_SETTLE = Duration.ofMillis(1500);
    /** 区服目录 1 s + 放行循环 1 s，留足余量。 */
    private static final Duration ADMIT_WAIT = Duration.ofSeconds(10);

    private final RobotClient client;
    private final AdminClient admin;
    private final GatewayHttp gateway;
    private final int zone;
    private final CheckReport report = new CheckReport();

    public QueueScenario(RobotClient client, AdminClient admin, GatewayHttp gateway, int zone) {
        this.client = client;
        this.admin = admin;
        this.gateway = gateway;
        this.zone = zone;
    }

    public CheckReport run() {
        if (!admin.hasToken()) {
            report.fail("缺运维令牌", "设 XM_ADMIN_TOKEN 或在仓库根目录放 run/xm-admin-token（start-slice.sh 会生成）", REF);
            return report;
        }
        JsonNode original;
        try {
            original = admin.get("/admin/zones/" + zone);
        } catch (RobotException e) {
            report.fail("读本区配置", e.toString(), REF);
            return report;
        }
        int restore = original.path("capacity").asInt();
        if (restore <= TIGHT_CAPACITY) {
            report.note("本区容量是 " + restore + "（上次 queue 场景被中断？），结束时按缺省 " + DEFAULT_CAPACITY + " 恢复");
            restore = DEFAULT_CAPACITY;
        }
        int restoreCapacity = restore;
        // Ctrl-C / kill 不走 finally，只走关闭钩子：容量留在 1 会让之后每个场景都排队
        Thread hook = new Thread(() -> restoreQuietly(original, restoreCapacity), "queue-scenario-restore");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            runChecks(original, restoreCapacity);
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.toString(), REF);
        } finally {
            try {
                setCapacity(original, restoreCapacity);
            } catch (RobotException e) {
                report.fail("恢复本区容量", e.toString(), REF);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // 已经在关闭中：钩子会跑
            }
        }
        return report;
    }

    private void restoreQuietly(JsonNode original, int capacity) {
        try {
            setCapacity(original, capacity);
        } catch (RobotException | RuntimeException e) {
            System.err.println("queue 场景：恢复本区容量失败，请经 /admin/zones 手动改回 " + capacity + ": " + e);
        }
    }

    private void runChecks(JsonNode original, int restoreCapacity) throws RobotException {
        setCapacity(original, TIGHT_CAPACITY);
        sleep(DIRECTORY_SETTLE);

        // 1. 排上队
        JsonNode queued = null;
        for (int attempt = 0; attempt < 5 && queued == null; attempt++) {
            JsonNode resp = assign();
            if (resp.path("code").asInt() == 100) {
                queued = resp;
            } else if (resp.path("code").asInt() != 0) {
                throw new RobotException("压容量后 assign-gate 回 " + resp);
            }
        }
        if (queued == null) {
            throw new RobotException("容量压到 " + TIGHT_CAPACITY + " 后连发 5 次都没排上队（排队没打开？）");
        }
        String token = queued.path("queue_token").asText();
        report.check("login".equals(queued.path("queue_source").asText()) && !token.isEmpty()
                        && queued.path("queue_rank").isIntegralNumber() && queued.path("queue_total").asLong() >= 1
                        && queued.path("retry_after_ms").asLong() > 0 && !queued.has("gate_ip"),
                "满员时 assign-gate 回 100 + 排队令牌", queued.toString(), REF);

        // 2. 还满：轮询仍在排
        JsonNode still = status(token);
        report.check(still.path("code").asInt() == 100 && token.equals(still.path("queue_token").asText()),
                "容量满时轮询仍回 100（同一令牌）", still.toString(), REF);

        // 3. 放开容量 → 放行
        setCapacity(original, restoreCapacity);
        long deadline = System.nanoTime() + ADMIT_WAIT.toNanos();
        JsonNode admitted = still;
        while (admitted.path("code").asInt() == 100 && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(500));
            admitted = status(token);
        }
        report.check(admitted.path("code").asInt() == 0 && admitted.has("gate_ip") && admitted.has("token_payload"),
                "放开容量后轮询拿到 gate 令牌", admitted.path("code") + " " + admitted.path("error"), REF);
        if (admitted.path("code").asInt() == 0) {
            GameConnection connection = client.connect(AssignGateClient.parse(200, admitted.toString()));
            report.check(connection.isOpen(), "用排队放行的 gate 令牌连 gate 握手成功", "", REF);
            connection.close();
        }

        // 4. 令牌只用一次；缺令牌 / 乱写的令牌
        JsonNode again = status(token);
        report.check(again.path("code").asInt() == 410 && "queue_token_expired".equals(again.path("error").asText()),
                "放行槽只取一次：同一令牌再轮询 410", again.toString(), REF);
        JsonNode missing = gateway.post("/api/queue-status", "{\"zone_id\":" + zone + "}");
        report.check(missing.path("code").asInt() == 410 && "missing_queue_token".equals(missing.path("error").asText()),
                "缺令牌 410 missing_queue_token", missing.toString(), REF);
        JsonNode forged = status("forged.token");
        report.check(forged.path("code").asInt() == 410, "乱写的令牌 410", forged.toString(), REF);
    }

    private JsonNode assign() throws RobotException {
        return gateway.post("/api/assign-gate", "{\"zone_id\":" + zone + "}");
    }

    private JsonNode status(String token) throws RobotException {
        return gateway.post("/api/queue-status", "{\"zone_id\":" + zone + ",\"queue_token\":\"" + token + "\"}");
    }

    /** 整行覆盖（upsert 保留 created_at），只改容量。 */
    private void setCapacity(JsonNode original, int capacity) throws RobotException {
        ObjectNode body = ((ObjectNode) original.deepCopy()).put("capacity", capacity);
        body.remove("created_at");
        body.remove("updated_at");
        admin.post("/admin/zones", body.toString());
    }

    private static void sleep(Duration d) throws RobotException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("被中断", e);
        }
    }
}
