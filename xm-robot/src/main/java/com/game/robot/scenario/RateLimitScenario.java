package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.RobotException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 开服限流（批次 3.4c）：本机切片打开限流、缺省阈值（IP 桶每秒 5、容量 20；冷却 5 s）。
 * <ol>
 *   <li>同一账号连发两次 {@code /api/login}（错口令，不建会话）→ 第一次照常 401，第二次 429 {@code ACCOUNT_COOLDOWN}；
 *       紧接着带同一账号的 assign-gate 不撞 login 的冷却（冷却按端点分开）；</li>
 *   <li>并发 {@link #MAX_BURST} 个 assign-gate（不带账号）：IP 桶见底的回 429 {@code IP_RATE_LIMIT}，其余照常；</li>
 *   <li>等 IP 桶补满后 assign-gate 回 0——也给后面的场景把桶留满。</li>
 * </ol>
 * 机器人从本机发请求，全部场景共用 127.0.0.1 一个 IP 桶。
 */
public final class RateLimitScenario {

    private static final String REF = "PARITY「开服限流」行；盘点 gateway-rate-limit";
    /** IP 桶容量 20：并发打两倍。 */
    private static final int MAX_BURST = 40;
    /** 补满 20 个令牌要 4 s，留余量。 */
    private static final Duration REFILL = Duration.ofSeconds(5);

    private final GatewayHttp gateway;
    private final int zone;
    private final String account;
    private final CheckReport report = new CheckReport();

    public RateLimitScenario(GatewayHttp gateway, int zone, String accountPrefix, String runTag) {
        this.gateway = gateway;
        this.zone = zone;
        this.account = accountName(accountPrefix, runTag);
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "rl" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.toString(), REF);
        }
        return report;
    }

    private void runChecks() throws RobotException {
        // 前面的场景可能刚把本机共用的 IP 桶用掉一截：先等补满
        sleep(REFILL);
        // 1. 同账号冷却（按端点分开）
        JsonNode first = login();
        JsonNode second = login();
        report.check(first.path("code").asInt() == 401, "第一次登录照常过限流（错口令 401）", first.toString(), REF);
        report.check(second.path("code").asInt() == 429 && "ACCOUNT_COOLDOWN".equals(second.path("message").asText()),
                "同账号同 IP 5 s 内再登录 429 ACCOUNT_COOLDOWN", second.toString(), REF);
        JsonNode assignSameAccount = gateway.post("/api/assign-gate",
                "{\"zone_id\":" + zone + ",\"account\":\"" + account + "\"}");
        report.check(assignSameAccount.path("code").asInt() == 0,
                "冷却按端点分开：紧接着 assign-gate 带同一账号照常分配", assignSameAccount.toString(), REF);

        // 2. IP 桶：并发打出去（顺序发的话，单次往返慢的环境里桶边发边补，可能永远见不了底）
        List<JsonNode> burst = burst();
        long ok = burst.stream().filter(r -> r.path("code").asInt() == 0).count();
        long limited = burst.stream().filter(r -> r.path("code").asInt() == 429
                && "IP_RATE_LIMIT".equals(r.path("error").asText())).count();
        report.check(limited > 0 && ok + limited == burst.size(),
                "并发 " + MAX_BURST + " 个 assign-gate：IP 桶见底的回 429 IP_RATE_LIMIT，其余照常分配",
                "分配 " + ok + "、限流 " + limited + "、其他 " + (burst.size() - ok - limited), REF);

        // 3. 补满后照常
        sleep(REFILL);
        JsonNode recovered = assign();
        report.check(recovered.path("code").asInt() == 0, "IP 桶补满后 assign-gate 照常分配", recovered.path("code") + " "
                + recovered.path("error"), REF);
    }

    private JsonNode login() throws RobotException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("zone_id", zone);
        body.put("account", account);
        body.put("password", "xm-robot-wrong-password");
        return gateway.post("/api/login", body.toString());
    }

    private List<JsonNode> burst() throws RobotException {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < MAX_BURST; i++) {
                futures.add(pool.submit(this::assign));
            }
            List<JsonNode> responses = new ArrayList<>();
            for (Future<JsonNode> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (ExecutionException e) {
            throw new RobotException("并发 assign-gate 失败", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("被中断", e);
        }
    }

    private JsonNode assign() throws RobotException {
        return gateway.post("/api/assign-gate", "{\"zone_id\":" + zone + "}");
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
