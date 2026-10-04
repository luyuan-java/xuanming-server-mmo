package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.robot.client.AdminClient;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.RobotException;
import java.time.Duration;

/**
 * gate 排空（批次 3.5）：经 xm-data 运维接口给本区的 gate 打排空标记，看 assign-gate 与判定循环跟着变。
 * <ol>
 *   <li>{@code GET /admin/gates/{zone}} 列出本区 gate；本机切片只有一台，不带 force 打标记回 409（打上之后没有接客的 gate 了）；</li>
 *   <li>带 force 打上：列表里出现排空起点；assign-gate 仍分到它（全部排空时忽略标记，免得整区登不上）；</li>
 *   <li>这台 gate 没人在线时，xm-gateway 的判定循环在两个周期内写 drained = {@code below_threshold}；</li>
 *   <li>撤销：两个标记一起消失。</li>
 * </ol>
 * 结束时（含中途失败、Ctrl-C）撤销标记；开跑时这台 gate 已带排空标记（运维正在排空）就整个跳过、一个字都不写。
 * 需要运维令牌与 xm-gateway 的判定循环（缺省 5 s 一轮）。
 */
public final class DrainScenario {

    private static final String REF = "PARITY「gate 排空」行；盘点 gate-drain / gate-drain-ops";
    /** 判定 5 s 一轮 + gate 每 5 s 发布在线人数，留余量。 */
    private static final Duration DRAINED_WAIT = Duration.ofSeconds(20);

    private final AdminClient admin;
    private final GatewayHttp gateway;
    private final int zone;
    private final CheckReport report = new CheckReport();

    public DrainScenario(AdminClient admin, GatewayHttp gateway, int zone) {
        this.admin = admin;
        this.gateway = gateway;
        this.zone = zone;
    }

    public CheckReport run() {
        if (!admin.hasToken()) {
            report.fail("缺运维令牌", "设 XM_ADMIN_TOKEN 或在仓库根目录放 run/xm-admin-token（start-slice.sh 会生成）", REF);
            return report;
        }
        int node;
        try {
            JsonNode gates = admin.get("/admin/gates/" + zone);
            if (gates == null || !gates.isArray() || gates.isEmpty()) {
                report.fail("列出本区 gate", "本区没有 gate：" + gates, REF);
                return report;
            }
            node = gates.get(0).path("node_id").asInt();
            if (gates.get(0).hasNonNull("draining_since") || gates.get(0).path("stale_mark").asBoolean()) {
                // 运维正在排空这台（或有旧实例留下的标记）：一个字都不写，免得撤掉别人的排空
                report.note("本区 gate 已带排空标记，跳过 drain 场景：" + gates.get(0));
                return report;
            }
            report.check(node > 0 && gates.get(0).has("player_count") && gates.get(0).hasNonNull("instance_id"),
                    "列出本区 gate（节点号、实例、在线人数，没在排空）", gates.toString(), REF);
        } catch (RobotException e) {
            report.fail("列出本区 gate", e.toString(), REF);
            return report;
        }
        int target = node;
        Thread hook = new Thread(() -> undrainQuietly(target), "drain-scenario-undrain");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            runChecks(node);
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.toString(), REF);
        } finally {
            undrainQuietly(node);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // 已经在关闭中：钩子会跑
            }
        }
        return report;
    }

    private void runChecks(int node) throws RobotException {
        String body = "{\"zone_id\":" + zone + ",\"node_id\":" + node + ",\"ttl_sec\":1800";
        // 1. 只有一台时不带 force 拒
        JsonNode gates = admin.get("/admin/gates/" + zone);
        if (gates.size() == 1) {
            try {
                admin.post("/admin/gates/drain", body + "}");
                report.fail("最后一台 gate 不带 force 打标记被拒（409）", "竟然打上了", REF);
            } catch (RobotException e) {
                report.check(e.getMessage().contains("409"), "最后一台 gate 不带 force 打标记被拒（409）", e.getMessage(), REF);
            }
        }

        // 2. 带 force 打上
        JsonNode marked = admin.post("/admin/gates/drain", body + ",\"force\":true}");
        report.check(marked.path("marked").asBoolean() && marked.path("draining_since").asLong() > 0,
                "打上排空标记（起点是 Redis 服务器时间）", marked.toString(), REF);
        JsonNode again = admin.post("/admin/gates/drain", body + ",\"force\":true}");
        report.check(!again.path("marked").asBoolean()
                        && again.path("draining_since").asLong() == marked.path("draining_since").asLong(),
                "重复打标记不改起点", again.toString(), REF);
        JsonNode assign = gateway.post("/api/assign-gate", "{\"zone_id\":" + zone + "}");
        report.check(assign.path("code").asInt() == 0 || gates.size() > 1,
                "全部 gate 都在排空时 assign-gate 忽略标记照常分配", assign.path("code") + " " + assign.path("error"), REF);

        // 3. 没人在线时判定循环写 drained
        JsonNode self = find(admin.get("/admin/gates/" + zone), node);
        if (self != null && self.path("player_count").asLong() == 0) {
            long deadline = System.nanoTime() + DRAINED_WAIT.toNanos();
            while (System.nanoTime() < deadline && (self == null || !self.hasNonNull("drained"))) {
                sleep(Duration.ofMillis(500));
                self = find(admin.get("/admin/gates/" + zone), node);
            }
            report.check(self != null && "below_threshold".equals(self.path("drained").asText()),
                    "没人在线的 gate 判定为已排空（drained=below_threshold）", String.valueOf(self), REF);
        } else {
            report.note("这台 gate 上还有人在线，跳过 drained 检查：" + self);
        }

        // 4. 撤销
        admin.delete("/admin/gates/drain/" + zone + "/" + node);
        JsonNode after = find(admin.get("/admin/gates/" + zone), node);
        report.check(after != null && !after.hasNonNull("draining_since") && !after.hasNonNull("drained"),
                "撤销后两个标记都消失", String.valueOf(after), REF);
    }

    private static JsonNode find(JsonNode gates, int node) {
        if (gates == null) {
            return null;
        }
        for (JsonNode gate : gates) {
            if (gate.path("node_id").asInt() == node) {
                return gate;
            }
        }
        return null;
    }

    private void undrainQuietly(int node) {
        try {
            admin.delete("/admin/gates/drain/" + zone + "/" + node);
        } catch (RobotException | RuntimeException e) {
            System.err.println("drain 场景：撤销排空标记失败，请经 DELETE /admin/gates/drain/" + zone + "/" + node + " 手动撤销: " + e);
        }
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
