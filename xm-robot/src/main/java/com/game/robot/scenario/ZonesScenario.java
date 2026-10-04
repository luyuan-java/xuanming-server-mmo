package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.robot.client.AdminClient;
import com.game.robot.client.RobotException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 区服目录 / 健康探测 / 登录公告（批次 3.4a）：经 xm-data 运维接口改，看 xm-gateway 的客户端接口跟着变。
 * <ol>
 *   <li>新建一个临时区（预告、带开放时刻）→ 区服列表出现 PREVIEW + open_time + is_new，assign-gate 503 {@code zone_not_open}；</li>
 *   <li>置维护带文案 → 列表 MAINTENANCE + 文案，assign-gate 503 {@code zone_maintenance}；</li>
 *   <li>置开放 → 这个区一台 gate 都没有，健康探测判 DOWN → 列表显示 MAINTENANCE、不下发负载档；本区（有 gate、有 scene）显示 OPEN + 负载档；</li>
 *   <li>白名单加入 / 查询 / 移出；删区 → 列表里没了，assign-gate 404；</li>
 *   <li>公告：新建一条生效的、一条已过期的 → {@code /api/announcement} 只有生效的那条；删掉后没了。</li>
 * </ol>
 * 结束时（含中途失败）删掉临时区与公告。需要 xm-data 与运维令牌（XM_ADMIN_TOKEN 或 run/xm-admin-token）。
 */
public final class ZonesScenario {

    private static final String REF = "PARITY「区服目录与运维接口」「区服健康探测」「登录公告」「区服白名单」行";
    /** 区服目录缓存 1 s；健康探测每 5 s 一轮。 */
    private static final Duration DIRECTORY_WAIT = Duration.ofSeconds(4);
    private static final Duration PROBE_WAIT = Duration.ofSeconds(15);
    private static final long OPEN_TIME = 1_900_000_000L;

    private final AdminClient admin;
    private final String gatewayUrl;
    private final int homeZone;
    private final int tempZone;
    private final String runTag;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final CheckReport report = new CheckReport();
    private final List<Long> announcements = new ArrayList<>();

    public ZonesScenario(AdminClient admin, String gatewayUrl, int homeZone, String runTag, Duration requestTimeout) {
        this.admin = admin;
        this.gatewayUrl = gatewayUrl;
        this.homeZone = homeZone;
        this.runTag = runTag;
        this.requestTimeout = requestTimeout;
        // 临时区号：按运行标签散到 [900000, 999999]，不撞本区
        this.tempZone = 900_000 + Math.floorMod(runTag.hashCode(), 100_000);
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    public int tempZone() {
        return tempZone;
    }

    public CheckReport run() {
        if (!admin.hasToken()) {
            report.fail("缺运维令牌", "设 XM_ADMIN_TOKEN 或在仓库根目录放 run/xm-admin-token（start-slice.sh 会生成）", REF);
            return report;
        }
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.toString(), REF);
        } finally {
            cleanup();
        }
        return report;
    }

    private void runChecks() throws RobotException {
        // 1. 预告区
        JsonNode created = admin.post("/admin/zones", "{\"zone_id\":" + tempZone + ",\"name\":\"robot-" + runTag
                + "\",\"manual_status\":3,\"open_time\":" + OPEN_TIME + ",\"sort_order\":999999}");
        report.check(created.path("zone_id").asInt() == tempZone && created.path("created_at").asLong() > 0,
                "运维接口新建区服", created.toString(), REF);
        JsonNode preview = awaitZone(z -> "PREVIEW".equals(z.path("status").asText()), DIRECTORY_WAIT);
        report.check(preview != null && preview.path("open_time").asLong() == OPEN_TIME && preview.path("is_new").asBoolean(),
                "区服列表：预告区带 open_time、是新区", String.valueOf(preview), REF);
        expectAssign(503, "zone_not_open", "预告区 assign-gate 503 zone_not_open");

        // 2. 维护
        admin.post("/admin/zones/" + tempZone + "/maintenance", "{\"maintenance_msg\":\"robot 维护演练\"}");
        JsonNode maintenance = awaitZone(z -> "MAINTENANCE".equals(z.path("status").asText())
                && "robot 维护演练".equals(z.path("maintenance_msg").asText()), DIRECTORY_WAIT);
        report.check(maintenance != null, "置维护后区服列表 MAINTENANCE + 文案", String.valueOf(maintenance), REF);
        expectAssign(503, "zone_maintenance", "维护区 assign-gate 503 zone_maintenance");

        // 3. 开放：没有 gate → 探测 DOWN → 显示维护；本区 OPEN + 负载档
        JsonNode opened = admin.post("/admin/zones/" + tempZone + "/open", "{}");
        report.check(opened.path("manual_status").asInt() == 0 && opened.path("maintenance_msg").asText().isEmpty(),
                "置开放清空文案", opened.toString(), REF);
        // 文案已清空才是「开放之后被探测降级」，不是缓存里还没过期的维护状态
        JsonNode down = awaitZone(z -> "MAINTENANCE".equals(z.path("status").asText()) && !z.has("load_level")
                && z.path("maintenance_msg").asText("x").isEmpty(), PROBE_WAIT);
        report.check(down != null, "没有 gate 的开放区经健康探测显示 MAINTENANCE、不下发负载档", String.valueOf(down), REF);
        JsonNode home = awaitZone(homeZone, z -> "OPEN".equals(z.path("status").asText()) && z.has("load_level"),
                PROBE_WAIT);
        report.check(home != null && List.of("SMOOTH", "BUSY", "FULL").contains(home.path("load_level").asText()),
                "本区（有 gate 与 scene）显示 OPEN + 负载档", String.valueOf(home), REF);

        // 4. 白名单与删区
        admin.post("/admin/whitelist", "{\"zone_id\":" + tempZone + ",\"account\":\"robot_wl\",\"note\":\"内测\"}");
        JsonNode list = admin.get("/admin/whitelist/" + tempZone);
        report.check(list.size() == 1 && "robot_wl".equals(list.get(0).path("account").asText()), "白名单加入与查询",
                list.toString(), REF);
        admin.delete("/admin/whitelist/" + tempZone + "/robot_wl");
        report.check(admin.get("/admin/whitelist/" + tempZone).isEmpty(), "白名单移出", "", REF);
        admin.delete("/admin/zones/" + tempZone);
        boolean gone = awaitCondition(() -> zoneOf(serverList(), tempZone) == null, DIRECTORY_WAIT);
        report.check(gone, "删区后区服列表里没了", "", REF);
        expectAssign(404, "zone_not_found", "删掉的区 assign-gate 404 zone_not_found");

        // 5. 公告
        String title = "robot-" + runTag;
        // 带终点（10 分钟后过期）：即使清理没跑成，也不会留下一条永久的公告
        long endTime = System.currentTimeMillis() / 1000 + 600;
        JsonNode live = admin.post("/admin/announcements", "{\"title\":\"" + title + "\",\"content\":\"生效中\","
                + "\"type\":\"notice\",\"start_time\":1000,\"end_time\":" + endTime + "}");
        announcements.add(live.path("id").asLong());
        JsonNode expired = admin.post("/admin/announcements", "{\"title\":\"" + title + "-expired\",\"end_time\":1000}");
        announcements.add(expired.path("id").asLong());
        // 公告接口缓存 1 s：轮询到出现为止
        long liveId = live.path("id").asLong();
        JsonNode[] seen = new JsonNode[1];
        awaitCondition(() -> {
            seen[0] = announcementItem(liveId);
            return seen[0] != null;
        }, DIRECTORY_WAIT);
        report.check(seen[0] != null && seen[0].path("start_time").asLong() == 1000
                        && seen[0].path("end_time").asLong() == endTime, "/api/announcement 下发生效中的，时刻是 Unix 秒",
                String.valueOf(seen[0]), REF);
        report.check(announcementItem(expired.path("id").asLong()) == null, "已过期的公告不下发", "", REF);
        admin.delete("/admin/announcements/" + liveId);
        report.check(awaitCondition(() -> announcementItem(liveId) == null, DIRECTORY_WAIT), "删掉的公告不再下发", "", REF);
    }

    private JsonNode announcementItem(long id) throws RobotException {
        for (JsonNode item : get("/api/announcement").path("items")) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        return null;
    }


    private void cleanup() {
        try {
            admin.delete("/admin/whitelist/" + tempZone + "/robot_wl");
        } catch (RobotException e) {
            // 不影响结论
        }
        try {
            admin.delete("/admin/zones/" + tempZone);
        } catch (RobotException e) {
            // 已删或删不掉（404 走这里），不影响结论
        }
        for (long id : announcements) {
            try {
                admin.delete("/admin/announcements/" + id);
            } catch (RobotException e) {
                // 同上
            }
        }
    }

    private void expectAssign(int code, String error, String name) throws RobotException {
        JsonNode resp = post("/api/assign-gate", "{\"zone_id\":" + tempZone + "}");
        report.check(resp.path("code").asInt() == code && error.equals(resp.path("error").asText()), name,
                resp.toString(), REF);
    }

    private JsonNode awaitZone(Predicate<JsonNode> match, Duration timeout) throws RobotException {
        return awaitZone(tempZone, match, timeout);
    }

    /** 轮询区服列表直到这个区满足条件；超时返回 null。 */
    private JsonNode awaitZone(int zoneId, Predicate<JsonNode> match, Duration timeout) throws RobotException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            JsonNode zone = zoneOf(serverList(), zoneId);
            if (zone != null && match.test(zone)) {
                return zone;
            }
            if (System.nanoTime() > deadline) {
                return null;
            }
            sleep(Duration.ofMillis(300));
        }
    }

    private interface Check {
        boolean holds() throws RobotException;
    }

    private boolean awaitCondition(Check check, Duration timeout) throws RobotException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!check.holds()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            sleep(Duration.ofMillis(300));
        }
        return true;
    }

    private JsonNode serverList() throws RobotException {
        return get("/api/server-list");
    }

    private static JsonNode zoneOf(JsonNode serverList, int zoneId) {
        for (JsonNode zone : serverList.path("zones")) {
            if (zone.path("zone_id").asInt() == zoneId) {
                return zone;
            }
        }
        return null;
    }

    private JsonNode get(String path) throws RobotException {
        return exchange(HttpRequest.newBuilder(URI.create(gatewayUrl + path)).timeout(requestTimeout).GET().build());
    }

    private JsonNode post(String path, String body) throws RobotException {
        return exchange(HttpRequest.newBuilder(URI.create(gatewayUrl + path)).timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private JsonNode exchange(HttpRequest request) throws RobotException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RobotException(request.uri().getPath() + " 返回 HTTP " + response.statusCode() + "："
                        + response.body());
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new RobotException("调用 xm-gateway 失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-gateway 被中断", e);
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
