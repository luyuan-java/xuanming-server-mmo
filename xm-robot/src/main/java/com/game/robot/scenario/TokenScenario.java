package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.contract.MessageIdRegistry;
import com.game.proto.login.CreatePlayerRequest;
import com.game.proto.login.CreatePlayerResponse;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.LoginHttpClient;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 令牌与 HTTP 登录（批次 3.1）：一个新账号走完
 * <ol>
 *   <li>HTTP {@code /api/login} 口令登录：code 0、角色列表在、两枚 43 字符 base64url 令牌、过期秒 ≈ now + 2h / 720h；错口令 401、未知区 500；</li>
 *   <li>TCP {@code Login{auth_type:"access_token"}}：账号取令牌里的（请求里的 account 忽略）、不签新令牌（字段 3–6 空），会话已绑定（建角成功）；</li>
 *   <li>127 RefreshToken 轮换：新的一对；旧 refresh 再用回 2000；HTTP {@code /api/refresh-token} 轮换成功、用过的 401、空的 401；
 *       轮换不作废旧 access（另一条连接仍能用它登录）；</li>
 *   <li>设备数上限：同一账号处于「已登录、未进游戏」窗口的连接到 3 个后第 4 个回 2024，断开一个之后第 4 个能登录。</li>
 * </ol>
 */
public final class TokenScenario {

    private static final String LOGIN_SERVICE = "ClientPlayerLogin";
    private static final int TIP_ACCOUNT_NOT_FOUND = 2000;
    private static final int TIP_TOO_MANY_DEVICES = 2024;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final long ACCESS_TTL_SECONDS = 2 * 3600;
    private static final long REFRESH_TTL_SECONDS = 720 * 3600;
    private static final long CLOCK_SLACK_SECONDS = 120;
    private static final Duration DEVICE_RELEASE_WAIT = Duration.ofSeconds(5);
    private static final String REF = "PARITY「access / refresh 令牌」「HTTP 登录」「设备数上限」行；登录契约 §4 / §11";

    private final RobotClient client;
    private final LoginHttpClient http;
    private final int zoneId;
    private final String password;
    private final String account;
    private final Duration requestTimeout;
    private final int login;
    private final int createPlayer;
    private final int refreshToken;
    private final CheckReport report = new CheckReport();
    private final List<GameConnection> connections = new ArrayList<>();

    public TokenScenario(RobotClient client, LoginHttpClient http, MessageIdRegistry registry, int zoneId, String password,
                         String accountPrefix, String runTag, Duration requestTimeout) {
        this.client = client;
        this.http = http;
        this.zoneId = zoneId;
        this.password = password;
        this.account = accountName(accountPrefix, runTag);
        this.requestTimeout = requestTimeout;
        this.login = registry.requireId(LOGIN_SERVICE, "Login");
        this.createPlayer = registry.requireId(LOGIN_SERVICE, "CreatePlayer");
        this.refreshToken = registry.requireId(LOGIN_SERVICE, "RefreshToken");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "tok" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.toString(), REF);
        } finally {
            for (GameConnection connection : connections) {
                connection.close();
            }
        }
        return report;
    }

    private void runChecks() throws RobotException {
        // 1. HTTP 口令登录
        long now = Instant.now().getEpochSecond();
        JsonNode ok = http.loginWithPassword(zoneId, account, password);
        String access1 = text(ok, "access_token");
        String refresh1 = text(ok, "refresh_token");
        boolean shapeOk = ok.get("code").asInt() == 0 && ok.has("players") && ok.get("players").isArray()
                && TOKEN.matcher(access1).matches() && TOKEN.matcher(refresh1).matches() && !access1.equals(refresh1)
                && near(ok, "access_token_expire", now + ACCESS_TTL_SECONDS)
                && near(ok, "refresh_token_expire", now + REFRESH_TTL_SECONDS);
        report.check(shapeOk, "HTTP 口令登录：code 0、角色列表、两枚 43 字符令牌、过期秒 ≈ now + 2h / 720h",
                "code=" + ok.get("code") + " players=" + ok.get("players") + " access_len=" + access1.length()
                        + " access_expire=" + ok.get("access_token_expire") + " refresh_expire=" + ok.get("refresh_token_expire"),
                REF);
        if (!shapeOk) {
            return;
        }
        // 换一个账号：本机切片打开了开服限流，同一账号同一 IP 5 s 内第二次 /api/login 回 429 ACCOUNT_COOLDOWN
        JsonNode badPassword = http.loginWithPassword(zoneId, account + "b", password + "x");
        report.check(badPassword.get("code").asInt() == 401, "HTTP 错口令回 401", "code=" + badPassword.get("code"), REF);
        JsonNode badZone = http.loginWithPassword(999, account, password);
        report.check(badZone.get("code").asInt() == 500, "HTTP 未知区回 500", "code=" + badZone.get("code")
                + " message=" + badZone.get("message"), REF);

        // 2. TCP access token 登录
        GameConnection first = connect();
        LoginResponse byToken = first.call(login, LoginRequest.newBuilder().setAuthType("access_token")
                .setAuthToken(access1).setAccount("robot_ignored").build(), LoginResponse.parser(), requestTimeout);
        report.check(!byToken.hasErrorMessage() && byToken.getAccessToken().isEmpty()
                        && byToken.getRefreshToken().isEmpty() && byToken.getAccessTokenExpire() == 0,
                "TCP access token 登录：成功、不签新令牌（字段 3–6 空）",
                "error=" + byToken.getErrorMessage().getId() + " access=" + byToken.getAccessToken().length(), REF);
        CreatePlayerResponse created = first.call(createPlayer, CreatePlayerRequest.getDefaultInstance(),
                CreatePlayerResponse.parser(), requestTimeout);
        report.check(!created.hasErrorMessage() && created.getPlayersCount() == 1,
                "access token 登录后会话已绑定账号（建角成功，角色在令牌里的账号下）",
                "error=" + created.getErrorMessage().getId() + " players=" + created.getPlayersCount(), REF);

        // 3. 轮换
        RefreshTokenResponse rotated = first.call(refreshToken, RefreshTokenRequest.newBuilder()
                .setRefreshToken(refresh1).build(), RefreshTokenResponse.parser(), requestTimeout);
        boolean rotatedOk = !rotated.hasErrorMessage() && TOKEN.matcher(rotated.getRefreshToken()).matches()
                && !rotated.getRefreshToken().equals(refresh1) && !rotated.getAccessToken().equals(access1)
                && rotated.getAccessTokenExpire() > 0;
        report.check(rotatedOk, "127 RefreshToken 轮换：新的一对", "error=" + rotated.getErrorMessage().getId(), REF);
        RefreshTokenResponse reused = first.call(refreshToken, RefreshTokenRequest.newBuilder()
                .setRefreshToken(refresh1).build(), RefreshTokenResponse.parser(), requestTimeout);
        report.check(reused.getErrorMessage().getId() == TIP_ACCOUNT_NOT_FOUND, "用过的 refresh 再用回 2000（一次性）",
                "error=" + reused.getErrorMessage().getId(), REF);
        JsonNode httpRotated = http.refresh(rotated.getRefreshToken());
        report.check(httpRotated.get("code").asInt() == 0 && TOKEN.matcher(text(httpRotated, "refresh_token")).matches(),
                "HTTP /api/refresh-token 轮换成功", "code=" + httpRotated.get("code"), REF);
        JsonNode httpReused = http.refresh(rotated.getRefreshToken());
        JsonNode httpEmpty = http.refresh(null);
        report.check(httpReused.get("code").asInt() == 401 && httpEmpty.get("code").asInt() == 401
                        && "empty_refresh_token".equals(text(httpEmpty, "message")),
                "HTTP 用过的 refresh 回 401、空 refresh 回 401 empty_refresh_token",
                "reused=" + httpReused.get("code") + " empty=" + httpEmpty, REF);

        // 4. 旧 access 不随轮换作废；设备数上限
        GameConnection second = connect();
        LoginResponse oldAccess = second.call(login, LoginRequest.newBuilder().setAuthType("access_token")
                .setAuthToken(access1).build(), LoginResponse.parser(), requestTimeout);
        report.check(!oldAccess.hasErrorMessage(), "轮换之后旧 access token 仍可登录（access 不随轮换作废）",
                "error=" + oldAccess.getErrorMessage().getId(), REF);
        GameConnection third = connect();
        LoginResponse thirdLogin = passwordLogin(third);
        GameConnection fourth = connect();
        LoginResponse fourthLogin = passwordLogin(fourth);
        report.check(!thirdLogin.hasErrorMessage() && fourthLogin.getErrorMessage().getId() == TIP_TOO_MANY_DEVICES,
                "同一账号窗口内第 4 个连接回 2024", "third=" + thirdLogin.getErrorMessage().getId() + " fourth="
                        + fourthLogin.getErrorMessage().getId(), REF);
        second.close();
        long deadline = System.nanoTime() + DEVICE_RELEASE_WAIT.toNanos();
        LoginResponse retried;
        do {
            // 同一会话的 Login 每秒至多 3 条（gate 按消息号限频，缺省 3/s）：间隔 1 秒，等多久都不会被限频挡掉
            sleep(1000);
            retried = passwordLogin(fourth);
        } while (retried.hasErrorMessage() && System.nanoTime() < deadline);
        report.check(!retried.hasErrorMessage(), "断开一个连接后第 4 个连接能登录（断线注销设备）",
                "error=" + retried.getErrorMessage().getId(), REF);
    }

    private GameConnection connect() throws RobotException {
        GameConnection connection = client.connect(client.assignGate());
        connections.add(connection);
        return connection;
    }

    private LoginResponse passwordLogin(GameConnection connection) throws RobotException {
        return connection.call(login, LoginRequest.newBuilder().setAccount(account).setPassword(password).build(),
                LoginResponse.parser(), requestTimeout);
    }

    private static boolean near(JsonNode root, String field, long expected) {
        JsonNode node = root.get(field);
        return node != null && node.isIntegralNumber() && Math.abs(node.asLong() - expected) <= CLOCK_SLACK_SECONDS;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
