package com.game.login.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.common.compliance.AntiAddictionRules;
import com.game.login.LoginAccountService;
import com.game.login.account.AccountLogin;
import com.game.login.auth.DevPasswordRule;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.handler.RefreshTokenHandler;
import com.game.login.testing.InMemoryLoginDevices;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.player.store.AccountLifecycle;
import com.game.player.store.PlayerMapper;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.RoleDeletionPrecheck;
import com.game.proto.login.AccountDeletionRequest;
import com.game.proto.login.AccountDeletionResponse;
import com.game.proto.login.CancelAccountDeletionRequest;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.ResetAccountRealNameRequest;
import com.game.proto.login.ResetAccountRealNameResponse;
import com.game.proto.login.ScheduleAccountDeletionRequest;
import com.game.proto.login.SubmitRealNameRequest;
import com.game.proto.login.SubmitRealNameResponse;
import com.game.table.LoginErrorTip;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 实名认证（NPPA 加解密/签名、待查回查、防沉迷时段）、账号注销（申请/撤销/执行/回执）与 LoginAdmin 全链路单测。 */
class LoginComplianceTest {

    private static final String SECRET = "dev-secret";
    private static final String NPPA_KEY_HEX = "00112233445566778899aabbccddeeff";
    // 1995-05-01 成年人 & 2014-05-01 未成年人 的合法 38 位 pi
    private static final String PI_ADULT = AntiAddictionRules.piBirthPrefix(1995, 5, 1) + "a".repeat(32);
    private static final String PI_MINOR = AntiAddictionRules.piBirthPrefix(2014, 5, 1) + "b".repeat(32);
    // 合法 18 位校验码通过的测试身份证号（11010519950501001X 计算校验码）
    private static final String VALID_ID = withCheckDigit("11010519950501001");

    private static final AtomicLong CLOCK_MS = new AtomicLong();
    private AnnotationConfigApplicationContext context;
    private PlayerStore store;

    @BeforeEach
    void setUp() {
        // 2026-11-20（周五）20:30 北京时间（未成年人可玩时段，截止 21:00）
        Instant fri2030 = ZonedDateTime.of(2026, 11, 20, 20, 30, 0, 0, AntiAddictionRules.BEIJING).toInstant();
        CLOCK_MS.set(fri2030.toEpochMilli());
        context = new AnnotationConfigApplicationContext(SqlConfig.class);
        store = context.getBean(PlayerStore.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void NPPA客户端_AES128GCM加密与SHA256验签_回环验证CheckQuery与Loginout部分拒绝() throws Exception {
        AtomicReference<String> decryptedCheck = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/check", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String ts = ex.getRequestHeaders().getFirst("timestamps");
            String sign = ex.getRequestHeaders().getFirst("sign");
            String expected = NppaClient.sign(NPPA_KEY_HEX,
                    Map.of("appId", "app1", "bizId", "biz1", "timestamps", ts), body);
            assertThat(sign).isEqualTo(expected);
            // 解密 AES-128-GCM 密文验证姓名与证件号正确封装
            String b64 = body.replaceAll(".*\"data\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            byte[] raw = Base64.getDecoder().decode(b64);
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(HexFormat.of().parseHex(NPPA_KEY_HEX), "AES"),
                        new GCMParameterSpec(128, raw, 0, 12));
                decryptedCheck.set(new String(c.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            respondJson(ex, "{\"errcode\":0,\"errmsg\":\"OK\",\"data\":{\"result\":{\"status\":0,\"pi\":\"" + PI_ADULT + "\"}}}");
        });
        server.createContext("/query", ex -> {
            String ai = ex.getRequestURI().getQuery().split("=")[1];
            String ts = ex.getRequestHeaders().getFirst("timestamps");
            String expected = NppaClient.sign(NPPA_KEY_HEX,
                    Map.of("appId", "app1", "bizId", "biz1", "timestamps", ts, "ai", ai), "");
            assertThat(ex.getRequestHeaders().getFirst("sign")).isEqualTo(expected);
            respondJson(ex, "{\"errcode\":0,\"errmsg\":\"OK\",\"data\":{\"result\":{\"status\":1}}}");
        });
        server.createContext("/loginout", ex -> respondJson(ex,
                "{\"errcode\":3001,\"errmsg\":\"partial\",\"data\":{\"results\":[{\"no\":1,\"errcode\":0},{\"no\":2,\"errcode\":2004}]}}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            NppaClient client = new NppaClient(new NppaClient.Config(
                    "app1", "biz1", NPPA_KEY_HEX, base + "/check", base + "/query", base + "/loginout"));
            NppaClient.AuthResult check = client.check("ai01", "张三", VALID_ID);
            assertThat(check.status()).isEqualTo(NppaClient.AuthStatus.OK);
            assertThat(check.pi()).isEqualTo(PI_ADULT);
            assertThat(decryptedCheck.get()).contains("张三").contains(VALID_ID);

            assertThat(client.query("ai01").status()).isEqualTo(NppaClient.AuthStatus.PENDING);

            List<Integer> rejected = client.loginout(List.of(
                    new NppaClient.Behavior(0, "s1", 1, 1000, 0, PI_ADULT),
                    new NppaClient.Behavior(0, "s2", 0, 1001, 0, PI_MINOR)));
            assertThat(rejected).containsExactly(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 实名防沉迷全流程_待查回查_未成年人时段与截止时刻_客服重置() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        AtomicReference<NppaClient.AuthResult> checkResult = new AtomicReference<>(
                new NppaClient.AuthResult(NppaClient.AuthStatus.PENDING, ""));
        AtomicReference<NppaClient.AuthResult> queryResult = new AtomicReference<>(
                new NppaClient.AuthResult(NppaClient.AuthStatus.OK, PI_MINOR));
        NppaClient.Verifier verifier = new NppaClient.Verifier() {
            @Override public NppaClient.AuthResult check(String ai, String name, String id) { return checkResult.get(); }
            @Override public NppaClient.AuthResult query(String ai) { return queryResult.get(); }
        };
        Clock clock = new MutableClock();
        RealNameService rn = new RealNameService(stateStore, verifier, store, rule -> true, 3, clock);
        AntiAddictionGate gate = new AntiAddictionGate(AntiAddictionRules.Calendar.of(List.of()), Set.of("wechat"), rn, null, clock);

        InMemoryLoginTokens tokens = new InMemoryLoginTokens();
        InMemoryLoginDevices devices = new InMemoryLoginDevices(3);
        AccountLogin accountLogin = new AccountLogin(
                LoginAuthenticator.withDevPassword(new DevPasswordRule(SECRET, List.of("dev_")), tokens),
                store, tokens, devices, new InFlightKeys<>(), gate, true);
        AccountDeletionService deletion = new AccountDeletionService(store, tokens, null, RoleDeletionPrecheck.NONE,
                Duration.ofHours(1), null, false, new SimpleMeterRegistry());
        LoginAccountService rpc = new LoginAccountService(accountLogin, new RefreshTokenHandler(tokens), tokens,
                store, gate, deletion, Runnable::run);
        LoginAdminRpcService admin = new LoginAdminRpcService(store, deletion, gate, Runnable::run);

        // 1. HTTP 登录：自有渠道未实名 -> 返回 real_name_required=true 且拿到 access_token
        var httpLogin = rpc.login(LoginRequest.newBuilder().setAccount("dev_u1").setPassword(SECRET).build()).join();
        assertThat(httpLogin.hasErrorMessage()).isFalse();
        assertThat(httpLogin.getAntiAddiction().getRealNameRequired()).isTrue();
        String accessToken = httpLogin.getAccessToken();

        // 2. TCP 登录绑定：未实名被拒 2042
        SessionContext tcp = SessionContext.newBuilder().setGateInstanceId("g1").setSessionId(11).setZoneId(1).build();
        AccountLogin.Outcome blockedTcp = accountLogin.loginOnSession(tcp,
                LoginRequest.newBuilder().setAuthType("access_token").setAuthToken(accessToken).build());
        assertThat(blockedTcp.succeeded()).isFalse();
        assertThat(blockedTcp.response().getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kLoginRealNameRequired_VALUE);

        // 3. 提交实名返回 PENDING (2044)，记下 pending ai
        SubmitRealNameResponse pendingResp = rpc.submitRealName(SubmitRealNameRequest.newBuilder()
                .setAccessToken(accessToken).setName("李四").setIdNumber(VALID_ID).build()).join();
        assertThat(pendingResp.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginRealNamePending_VALUE);
        assertThat(stateStore.readPendingAi("dev_u1")).isNotBlank();

        // 4. 下次 HTTP 登录自动回查 resolvePending -> 绑定未成年人 PI，返回 minor=true 与 21:00 截止时刻
        var afterResolve = rpc.login(LoginRequest.newBuilder().setAccount("dev_u1").setPassword(SECRET).build()).join();
        assertThat(afterResolve.getAntiAddiction().getRealNameRequired()).isFalse();
        assertThat(afterResolve.getAntiAddiction().getMinor()).isTrue();
        long expectedDeadline = ZonedDateTime.of(2026, 11, 20, 21, 0, 0, 0, AntiAddictionRules.BEIJING).toInstant().toEpochMilli();
        assertThat(afterResolve.getAntiAddiction().getPlayDeadlineMs()).isEqualTo(expectedDeadline);

        // 5. TCP 登录绑定：未成年人在周五 20:30 放行并下发 playDeadlineMs
        AccountLogin.Outcome minorOk = accountLogin.loginOnSession(tcp,
                LoginRequest.newBuilder().setAuthType("access_token").setAuthToken(afterResolve.getAccessToken()).build());
        assertThat(minorOk.succeeded()).isTrue();
        assertThat(minorOk.playDeadlineMs()).isEqualTo(expectedDeadline);

        // 6. 拨表到 21:05 -> 未成年人不在可玩时段被拒 2048
        CLOCK_MS.set(expectedDeadline + 300_000L);
        AccountLogin.Outcome minorRejected = accountLogin.loginOnSession(tcp,
                LoginRequest.newBuilder().setAuthType("access_token").setAuthToken(afterResolve.getAccessToken()).build());
        assertThat(minorRejected.succeeded()).isFalse();
        assertThat(minorRejected.response().getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kLoginMinorNotInPlayTime_VALUE);

        // 7. 客服重置实名（按 account）后，恢复未实名状态，可重绑成年人 PI
        ResetAccountRealNameResponse reset = admin.resetAccountRealName(ResetAccountRealNameRequest.newBuilder()
                .setAccount("dev_u1").setOperator("cs_1").setReason("wrong id").build()).join();
        assertThat(reset.hasErrorMessage()).isFalse();
        assertThat(reset.getHadRealName()).isTrue();

        checkResult.set(new NppaClient.AuthResult(NppaClient.AuthStatus.OK, PI_ADULT));
        SubmitRealNameResponse adultOk = rpc.submitRealName(SubmitRealNameRequest.newBuilder()
                .setAccessToken(afterResolve.getAccessToken()).setName("王五").setIdNumber(VALID_ID).build()).join();
        assertThat(adultOk.hasErrorMessage()).isFalse();
        assertThat(adultOk.getAntiAddiction().getMinor()).isFalse();

        // 8. 平台托管渠道（wechat）HTTP 换得 access_token 后走 TCP 绑定：沿用原渠道判定，不触发自管实名 2042
        LoginAuthenticator oauthAuth = new LoginAuthenticator(
                new DevPasswordRule(SECRET, List.of("dev_")),
                tokens,
                Map.of("wechat", code -> "wx_good".equals(code) ? java.util.Optional.of("wx_u99") : java.util.Optional.empty()));
        AccountLogin oauthLogin = new AccountLogin(oauthAuth, store, tokens, devices, new InFlightKeys<>(), gate, true);
        AccountLogin.Outcome wxHttp = oauthLogin.loginWithoutSession(
                LoginRequest.newBuilder().setAuthType("wechat").setAuthToken("wx_good").build());
        assertThat(wxHttp.succeeded()).isTrue();
        assertThat(wxHttp.response().getAntiAddiction().getRealNameRequired()).isFalse();
        SessionContext wxTcp = SessionContext.newBuilder().setGateInstanceId("g1").setSessionId(12).setZoneId(1).build();
        AccountLogin.Outcome wxBound = oauthLogin.loginOnSession(wxTcp,
                LoginRequest.newBuilder().setAuthType("access_token").setAuthToken(wxHttp.response().getAccessToken()).build());
        assertThat(wxBound.succeeded()).isTrue();
    }

    @Test
    void 账号注销全流程_重新认证申请_撤销_运维代办跳过帮主_到期执行删角写回执与重登可见() {
        List<Long> kickedPlayers = new ArrayList<>();
        InMemoryLoginTokens tokens = new InMemoryLoginTokens();
        InMemoryLoginDevices devices = new InMemoryLoginDevices(3);
        AccountLogin accountLogin = new AccountLogin(
                LoginAuthenticator.withDevPassword(new DevPasswordRule(SECRET, List.of("dev_")), tokens),
                store, tokens, devices, new InFlightKeys<>(), null, true);
        RoleDeletionPrecheck guildLeader = (c, p, self) -> self ? List.of("guild_leader") : List.of();
        AccountDeletionService deletion = new AccountDeletionService(store, tokens,
                (pid, epoch) -> kickedPlayers.add(pid), guildLeader, Duration.ofHours(1),
                null, false, new SimpleMeterRegistry());
        LoginAccountService rpc = new LoginAccountService(accountLogin, new RefreshTokenHandler(tokens), tokens,
                store, null, deletion, Runnable::run);
        LoginAdminRpcService admin = new LoginAdminRpcService(store, deletion, null, Runnable::run);

        // 登录建号并建角色 501
        var loginResp = rpc.login(LoginRequest.newBuilder().setAccount("dev_d1").setPassword(SECRET).build()).join();
        assertThat(loginResp.hasErrorMessage()).isFalse();
        PlayerRow p = new PlayerRow();
        p.setPlayerId(501); p.setAccount("dev_d1"); p.setZoneId(1); p.setName("道友注销"); p.setClassId(1); p.setGender(1);
        store.createPlayer(p);
        store.setAccountRealName("dev_d1", PI_ADULT);
        org.apache.dubbo.rpc.RpcContext.getServerAttachment()
                .setAttachment(org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY, "game-payment");
        try {
            assertThat(admin.realNamePiOfPlayer(501).join()).isEqualTo(PI_ADULT);
        } finally {
            org.apache.dubbo.rpc.RpcContext.removeServerAttachment();
        }

        // 1. 拒绝用 access_token 申请注销
        AccountDeletionResponse rejectTok = rpc.requestAccountDeletion(AccountDeletionRequest.newBuilder()
                .setAuthType("access_token").setAuthToken(loginResp.getAccessToken()).build()).join();
        assertThat(rejectTok.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);

        // 2. 本人重新认证申请注销：被帮主拦阻 2039
        AccountDeletionResponse blocked = rpc.requestAccountDeletion(AccountDeletionRequest.newBuilder()
                .setAccount("dev_d1").setPassword(SECRET).build()).join();
        assertThat(blocked.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginAccountDeletionBlocked_VALUE);
        assertThat(blocked.getBlockersList()).singleElement().satisfies(b -> {
            assertThat(b.getPlayerId()).isEqualTo(501L);
            assertThat(b.getReason()).isEqualTo("guild_leader");
        });

        // 3. 运维代办跳过帮主拦阻，进入冷静期并吊销旧令牌
        AccountDeletionResponse scheduled = admin.scheduleAccountDeletion(ScheduleAccountDeletionRequest.newBuilder()
                .setPlayerId(501).setOperator("ops1").setReason("ticket-99").build()).join();
        assertThat(scheduled.hasErrorMessage()).isFalse();
        assertThat(scheduled.getState()).isEqualTo(AccountLifecycle.DELETION_PENDING);
        assertThat(tokens.validateAccess(loginResp.getAccessToken())).isEmpty();

        // 4. 冷静期内重新登录仍可拿到 access_token 且响应带上 deletion_state=1，随后可用它撤销
        var coolingLogin = rpc.login(LoginRequest.newBuilder().setAccount("dev_d1").setPassword(SECRET).build()).join();
        assertThat(coolingLogin.getAccountDeletionState()).isEqualTo(AccountLifecycle.DELETION_PENDING);
        assertThat(coolingLogin.getAccountDeletionExecuteAfter()).isPositive();

        AccountDeletionResponse cancelled = rpc.cancelAccountDeletion(CancelAccountDeletionRequest.newBuilder()
                .setAccessToken(coolingLogin.getAccessToken()).build()).join();
        assertThat(cancelled.hasErrorMessage()).isFalse();
        assertThat(cancelled.getState()).isEqualTo(AccountLifecycle.DELETION_NONE);

        // 5. 再次代办注销并推进时钟到期 -> 后台执行器删角、写回执、删账号行
        admin.scheduleAccountDeletion(ScheduleAccountDeletionRequest.newBuilder()
                .setPlayerId(501).setOperator("ops1").setReason("ticket-100").build()).join();
        CLOCK_MS.addAndGet(Duration.ofHours(1).toMillis() + 1000L);
        AccountDeletionService.RoundResult round = deletion.runExecutorOnce();
        assertThat(round.claimed()).isEqualTo(1);
        assertThat(round.finished()).isEqualTo(1);
        assertThat(store.findPlayer(501)).isEmpty();

        // 6. 重新口令登录建新账号：previous_account_deleted_at 非 0
        var reborn = rpc.login(LoginRequest.newBuilder().setAccount("dev_d1").setPassword(SECRET).build()).join();
        assertThat(reborn.hasErrorMessage()).isFalse();
        assertThat(reborn.getAccountDeletionState()).isZero();
        assertThat(reborn.getPreviousAccountDeletedAt()).isEqualTo(CLOCK_MS.get() / 1000L);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange ex, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String withCheckDigit(String first17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] table = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (first17.charAt(i) - '0') * weights[i];
        }
        return first17 + table[sum % 11];
    }

    private static final class InMemoryStateStore implements RealNameService.StateStore {
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final Map<String, String> pending = new ConcurrentHashMap<>();

        @Override
        public long incrementDailyAttempts(String account, String yyyyMmDd, Duration ttl) {
            return attempts.computeIfAbsent(account + ":" + yyyyMmDd, k -> new AtomicInteger()).incrementAndGet();
        }

        @Override public void rememberPendingAi(String account, String ai, Duration ttl) { pending.put(account, ai); }
        @Override public String readPendingAi(String account) { return pending.get(account); }
        @Override public void forgetPendingAi(String account) { pending.remove(account); }
    }

    private static final class MutableClock extends Clock {
        @Override public ZoneId getZone() { return AntiAddictionRules.BEIJING; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(CLOCK_MS.get()); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class SqlConfig implements org.springframework.beans.factory.DisposableBean {
        private JdbcDataSource h2;

        @Override
        public void destroy() {
            if (h2 != null) new JdbcTemplate(h2).execute("SHUTDOWN");
        }

        @Bean
        DataSource dataSource() throws Exception {
            h2 = new JdbcDataSource();
            h2.setUser("sa");
            h2.setPassword("");
            h2.setURL("jdbc:h2:mem:login_compliance_" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/game-route-authority-schema.sql"),
                    new ClassPathResource("db/game-player-schema.sql")).execute(h2);
            try (var c = h2.getConnection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT CURRENT_USER()")) {
                if (!rs.next()) throw new java.sql.SQLException("缺主体");
                com.game.common.player.RouteAuthorityInitializer.initialize(c, UUID.randomUUID().toString(), List.of(1), rs.getString(1));
            }
            var authority = org.mockito.Mockito.mock(com.game.common.sql.WriterAuthority.class, inv -> {
                String m = inv.getMethod().getName();
                if ("begin".equals(m)) { ((java.sql.Connection) inv.getArgument(0)).setAutoCommit(false); return null; }
                if ("verifyPhysical".equals(m) || "seal".equals(m)) return null;
                if ("isSealed".equals(m)) return false;
                return org.mockito.Mockito.RETURNS_DEFAULTS.answer(inv);
            });
            var creds = new java.util.Properties();
            creds.setProperty("user", "sa");
            creds.setProperty("password", "");
            return new com.game.common.sql.AuthorityDataSource(h2.getURL(), creds, authority, false, List.of());
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<PlayerMapper> playerMapper(SqlSessionFactory factory) {
            MapperFactoryBean<PlayerMapper> bean = new MapperFactoryBean<>(PlayerMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        PlatformTransactionManager txManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        PlayerStore playerStore(PlayerMapper mapper, PlatformTransactionManager tx) {
            return new PlayerStore(mapper, CLOCK_MS::get, tx);
        }
    }
}
