package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.table.LoginErrorTip;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 官方通行证状态落到游戏会话（对齐 mmorpg {@code passportsession.Enforcer}）：
 * <ul>
 *   <li>{@link #checkLogin}：通行证登录签票前与注销申请重认证前调用；判为被删/被封/已改密拒回 {@code kLoginPassportCredentialChanged}（2059），
 *       且无论放行与否都吊销改密之前的旧会话并异步踢线；通行证故障时 fail-open 放行并计告警指标；</li>
 *   <li>{@link #checkRefresh}：refresh 轮换前调用；判为失效即先吊销并踢线、不续期（吊销失败也不续期）；通行证故障时照常续期并计 error；</li>
 *   <li>{@link #handleEvent}：处理 Casdoor webhook 载荷（上限 64 KiB，提取至多 4 名非 {@code built-in} 且不含 {@code /} 的用户），
 *       逐个现查通行证并落地吊销踢线；返回 {@code >= 0} 已核对人数，{@code -1} 坏载荷（400），{@code -2} 暂不可用（503 重投）。</li>
 * </ul>
 */
public final class PassportSessionEnforcer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PassportSessionEnforcer.class);
    public static final int RESULT_BAD_EVENT = -1;
    public static final int RESULT_UNAVAILABLE = -2;
    public static final int TIP_CREDENTIAL_CHANGED = LoginErrorTip.login_error.kLoginPassportCredentialChanged_VALUE;

    static final int MAX_EVENT_BYTES = 64 << 10;
    static final int MAX_EVENT_USERS = 4;
    static final String BUILT_IN_ORG = "built-in";

    private static final String PHASE_LOGIN = "login";
    private static final String PHASE_REFRESH = "refresh";
    private static final String PHASE_EVENT = "event";
    private static final String RESULT_VALID = "valid";
    private static final String RESULT_ERROR = "error";
    private static final String RESULT_REVOKE_FAILED = "revoke_failed";
    private static final String RESULT_KICK_FAILED = "kick_failed";
    private static final String RESULT_IGNORED = "ignored";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 查通行证用户状态（抽象接口便于单测注入替身）。 */
    public interface UserLookup {
        PassportAdminClient.UserStatus lookupBySub(String sub);
        PassportAdminClient.UserStatus lookupByName(String owner, String name);

        static UserLookup of(PassportAdminClient client) {
            Objects.requireNonNull(client, "client");
            return new UserLookup() {
                @Override public PassportAdminClient.UserStatus lookupBySub(String sub) {
                    return client.lookupUser(sub);
                }
                @Override public PassportAdminClient.UserStatus lookupByName(String owner, String name) {
                    return client.lookupUserByName(owner, name);
                }
            };
        }
    }

    private record UserRef(String sub, String owner, String name) {
    }

    private final UserLookup lookup;
    private final LoginTokens tokens;
    private final BiConsumer<String, Integer> kickAccountPlayers;
    private final MeterRegistry meters;
    private final ExecutorService kickExecutor;
    private final Consumer<Runnable> kickRunner;

    public PassportSessionEnforcer(PassportAdminClient admin, LoginTokens tokens,
                                   BiConsumer<String, Integer> kickAccountPlayers, MeterRegistry meters) {
        this(UserLookup.of(admin), tokens, kickAccountPlayers, meters, null);
    }

    PassportSessionEnforcer(UserLookup lookup, LoginTokens tokens,
                            BiConsumer<String, Integer> kickAccountPlayers, MeterRegistry meters,
                            Consumer<Runnable> kickRunner) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.kickAccountPlayers = kickAccountPlayers;
        this.meters = meters;
        if (kickRunner == null) {
            this.kickExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.kickRunner = task -> kickExecutor.execute(task);
        } else {
            this.kickExecutor = null;
            this.kickRunner = kickRunner;
        }
    }

    /**
     * 通行证登录与注销申请重认证前调用：返回 0 表示放行；否则返回 {@link #TIP_CREDENTIAL_CHANGED}（2059）。
     * 不论本次是否放行，均吊销改密之前的旧会话并异步踢线；通行证不可达时 fail-open 放行并计数。
     */
    public int checkLogin(String account, long identityIssuedAtSeconds) {
        Optional<String> sub = PassportProvider.subjectOf(account);
        if (sub.isEmpty()) {
            return 0;
        }
        PassportAdminClient.UserStatus status;
        try {
            status = lookup.lookupBySub(sub.get());
        } catch (RuntimeException e) {
            count(PHASE_LOGIN, RESULT_ERROR);
            log.error("[passport] login check failed, letting login through account={} err={}",
                    account, e.getClass().getSimpleName());
            return 0;
        }
        try {
            settle(PHASE_LOGIN, account, status);
        } catch (RuntimeException e) {
            count(PHASE_LOGIN, RESULT_REVOKE_FAILED);
            log.error("[passport] revoking earlier sessions at login failed account={} err={}",
                    account, e.getClass().getSimpleName());
        }
        String verdict = status.sessionVerdict(identityIssuedAtSeconds);
        if (verdict.isEmpty()) {
            count(PHASE_LOGIN, RESULT_VALID);
            return 0;
        }
        count(PHASE_LOGIN, verdict);
        log.info("[passport] login refused account={} reason={}", account, verdict);
        return TIP_CREDENTIAL_CHANGED;
    }

    /**
     * 刷新令牌轮换前调用：返回 true 表示拒绝续期（且已吊销并踢线，或吊销失败不敢续期）。
     * 非通行证令牌或通行证暂不可达返回 false（照常续期并计数）。
     */
    public boolean checkRefresh(TokenData data) {
        if (data == null || !PassportProvider.AUTH_TYPE.equals(data.authType())) {
            return false;
        }
        Optional<String> sub = PassportProvider.subjectOf(data.account());
        if (sub.isEmpty()) {
            return false;
        }
        PassportAdminClient.UserStatus status;
        try {
            status = lookup.lookupBySub(sub.get());
        } catch (RuntimeException e) {
            count(PHASE_REFRESH, RESULT_ERROR);
            log.error("[passport] refresh check failed, renewing anyway account={} err={}",
                    data.account(), e.getClass().getSimpleName());
            return false;
        }
        String verdict = status.sessionVerdict(data.idTokenIat());
        if (verdict.isEmpty()) {
            count(PHASE_REFRESH, RESULT_VALID);
            return false;
        }
        try {
            settle(PHASE_REFRESH, data.account(), status);
        } catch (RuntimeException e) {
            count(PHASE_REFRESH, RESULT_REVOKE_FAILED);
            log.error("[passport] revoking sessions failed, refresh refused account={} reason={} err={}",
                    data.account(), verdict, e.getClass().getSimpleName());
            return true;
        }
        count(PHASE_REFRESH, verdict);
        log.info("[passport] refresh refused, sessions revoked account={} reason={}", data.account(), verdict);
        return true;
    }

    /**
     * 处理 Casdoor webhook 载荷：回 {@code >= 0} 已核对用户数（200）；{@link #RESULT_BAD_EVENT}（400）；{@link #RESULT_UNAVAILABLE}（503 重投）。
     */
    public int handleEvent(byte[] payload) {
        List<UserRef> refs = extractEventUsers(payload);
        if (refs == null) {
            count(PHASE_EVENT, RESULT_IGNORED);
            return RESULT_BAD_EVENT;
        }
        int checked = 0;
        Set<String> seen = new HashSet<>();
        for (UserRef ref : refs) {
            PassportAdminClient.UserStatus status;
            try {
                status = !ref.sub().isEmpty()
                        ? lookup.lookupBySub(ref.sub())
                        : lookup.lookupByName(ref.owner(), ref.name());
            } catch (RuntimeException e) {
                count(PHASE_EVENT, RESULT_ERROR);
                log.error("[passport] webhook lookup failed err={}", e.getClass().getSimpleName());
                return RESULT_UNAVAILABLE;
            }
            if (status.sub() == null || status.sub().isEmpty() || !seen.add(status.sub())) {
                continue;
            }
            String account = PassportProvider.accountForSubject(status.sub());
            boolean revoked;
            try {
                revoked = settle(PHASE_EVENT, account, status);
            } catch (RuntimeException e) {
                count(PHASE_EVENT, RESULT_REVOKE_FAILED);
                log.error("[passport] webhook revoke failed account={} err={}", account, e.getClass().getSimpleName());
                return RESULT_UNAVAILABLE;
            }
            checked++;
            String result = status.gone() ? PassportAdminClient.VERDICT_USER_GONE
                    : status.forbidden() ? PassportAdminClient.VERDICT_USER_FORBIDDEN
                    : revoked ? PassportAdminClient.VERDICT_PASSWORD_CHANGED
                    : RESULT_VALID;
            count(PHASE_EVENT, result);
        }
        if (checked == 0) {
            count(PHASE_EVENT, RESULT_IGNORED);
        }
        return checked;
    }

    /** 从 Casdoor webhook 记录提取待核对用户（跳过 built-in 及名字含 '/' 的应用伪用户，最多 4 名）；格式不对返回 null。 */
    static List<UserRef> extractEventUsers(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_EVENT_BYTES) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        List<UserRef> refs = new ArrayList<>(MAX_EVENT_USERS);
        JsonNode ext = root.get("extendedUser");
        if (ext != null && ext.isObject()) {
            addRef(refs, ext.path("owner").asText(""), ext.path("name").asText(""), ext.path("id").asText(""));
        }
        addRef(refs, root.path("organization").asText(""), root.path("user").asText(""), "");
        String objStr = root.path("object").asText("");
        if (!objStr.isEmpty()) {
            try {
                JsonNode obj = JSON.readTree(objStr);
                if (obj != null && obj.isObject()) {
                    addRef(refs, obj.path("owner").asText(""), obj.path("name").asText(""), obj.path("id").asText(""));
                }
            } catch (Exception ignored) {
                // object 并非 JSON 对象（例如纯字符串记录），按无目标对象忽略
            }
        }
        return refs;
    }

    private static void addRef(List<UserRef> refs, String owner, String name, String id) {
        if (refs.size() >= MAX_EVENT_USERS || BUILT_IN_ORG.equals(owner)
                || owner.indexOf('/') >= 0 || name.indexOf('/') >= 0) {
            return;
        }
        if (!id.isEmpty()) {
            refs.add(new UserRef(id, "", ""));
        } else if (!owner.isEmpty() && !name.isEmpty()) {
            refs.add(new UserRef("", owner, name));
        }
    }

    private boolean settle(String phase, String account, PassportAdminClient.UserStatus status) {
        boolean revoked = false;
        if (status.gone() || status.forbidden()) {
            tokens.revokeAll(account);
            revoked = true;
        } else if (status.passwordChangedAtSeconds() > 0L) {
            revoked = tokens.revokeIssuedBefore(account, status.passwordChangedAtSeconds()) > 0;
        }
        if (revoked) {
            log.info("[passport] sessions revoked phase={} account={} gone={} forbidden={} password_changed_at={}",
                    phase, account, status.gone(), status.forbidden(), status.passwordChangedAtSeconds());
            kickAsync(phase, account);
        }
        return revoked;
    }

    private void kickAsync(String phase, String account) {
        if (kickAccountPlayers == null) {
            return;
        }
        kickRunner.accept(() -> {
            try {
                kickAccountPlayers.accept(account, TIP_CREDENTIAL_CHANGED);
            } catch (RuntimeException e) {
                count(phase, RESULT_KICK_FAILED);
                log.error("[passport] kicking online players failed account={} err={}",
                        account, e.getClass().getSimpleName());
            }
        });
    }

    private void count(String phase, String result) {
        if (meters != null) {
            meters.counter("login.passport.session.check", "phase", phase, "result", result).increment();
        }
    }

    @Override
    public void close() {
        if (kickExecutor != null) {
            kickExecutor.shutdownNow();
        }
    }
}
