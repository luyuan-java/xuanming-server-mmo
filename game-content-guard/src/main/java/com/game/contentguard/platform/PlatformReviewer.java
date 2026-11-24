package com.game.contentguard.platform;

import com.game.contentguard.platform.PlatformAdapter.Outcome;
import com.game.contentguard.platform.PlatformAdapter.ReviewResult;
import com.game.proto.content_guard.TextScene;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外部平台审核路由器（对齐 Go {@code platform.Router}）：按场景与渠道分发到对应 {@link PlatformAdapter}。
 */
public final class PlatformReviewer {

    private static final Logger log = LoggerFactory.getLogger(PlatformReviewer.class);
    private static final Duration DEFAULT_IDENTITY_TTL = Duration.ofMinutes(5);
    private static final int IDENTITY_CACHE_MAX_SIZE = 65_536;

    public record Identity(String channel, String openId, String account) {
        public static final Identity EMPTY = new Identity("", "", "");

        boolean isPresent() {
            return (channel != null && !channel.isEmpty())
                    || (openId != null && !openId.isEmpty())
                    || (account != null && !account.isEmpty());
        }
    }

    @FunctionalInterface
    public interface IdentityLookup {
        Identity lookup(long playerId) throws Exception;
    }

    private record CachedIdentity(Identity identity, Instant expiresAt) {
    }

    private record Resolved(PlatformAdapter adapter, String openId) {
    }

    private final Map<String, PlatformAdapter> adapters;
    private final IdentityLookup identityLookup;
    private final ConcurrentHashMap<Long, CachedIdentity> identityCache = new ConcurrentHashMap<>();
    private final Duration syncTimeout;
    private final Duration worldTimeout;
    private final Duration asyncTimeout;
    private final Clock clock;

    public PlatformReviewer(
            Collection<PlatformAdapter> adapters,
            IdentityLookup identityLookup,
            Duration syncTimeout,
            Duration worldTimeout,
            Duration asyncTimeout,
            Clock clock) {
        Map<String, PlatformAdapter> byProvider = new HashMap<>();
        if (adapters != null) {
            for (PlatformAdapter a : adapters) {
                if (a != null && a.provider() != null && !a.provider().isEmpty()) {
                    byProvider.put(a.provider(), a);
                }
            }
        }
        this.adapters = Map.copyOf(byProvider);
        this.identityLookup = identityLookup;
        this.syncTimeout = syncTimeout != null && !syncTimeout.isNegative() && !syncTimeout.isZero()
                ? syncTimeout : Duration.ofMillis(1500);
        this.worldTimeout = worldTimeout != null && !worldTimeout.isNegative() && !worldTimeout.isZero()
                ? worldTimeout : Duration.ofMillis(250);
        this.asyncTimeout = asyncTimeout != null && !asyncTimeout.isNegative() && !asyncTimeout.isZero()
                ? asyncTimeout : Duration.ofMillis(2500);
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public boolean hasAdapters() {
        return !adapters.isEmpty();
    }

    /** 检查渠道账号前缀：{@code wx_<openid>} / {@code dy_<openid>}。 */
    public static String[] parseChannelAccount(String account) {
        if (account != null && account.startsWith("wx_") && account.length() > 3) {
            return new String[] {PlatformAdapter.PROVIDER_WECHAT, account.substring(3)};
        }
        if (account != null && account.startsWith("dy_") && account.length() > 3) {
            return new String[] {PlatformAdapter.PROVIDER_DOUYIN, account.substring(3)};
        }
        return new String[] {"", ""};
    }

    public ReviewResult review(TextScene scene, long playerId, String account, String text, boolean asyncWorld) {
        if (adapters.isEmpty() || text == null || text.isEmpty() || isSkippedScene(scene)) {
            return ReviewResult.skip();
        }
        Resolved resolved;
        try {
            resolved = resolveAdapter(playerId, account);
        } catch (Exception e) {
            if (scene == TextScene.TEXT_SCENE_CHAT_WORLD) {
                log.error("[content_guard/platform] lookup identity failed for world chat: err={}", e.getMessage());
                return asyncWorld ? ReviewResult.pending("") : ReviewResult.skip();
            }
            log.error("[content_guard/platform] lookup identity failed for name/notice review: err={}", e.getMessage());
            return ReviewResult.unavailable("");
        }
        if (resolved == null) {
            return ReviewResult.skip();
        }
        if (PlatformAdapter.PROVIDER_WECHAT.equals(resolved.adapter().provider())
                && (resolved.openId() == null || resolved.openId().isEmpty())) {
            return ReviewResult.skip(PlatformAdapter.PROVIDER_WECHAT);
        }
        if (scene == TextScene.TEXT_SCENE_CHAT_WORLD) {
            ReviewResult res = resolved.adapter().check(scene, resolved.openId(), text, worldTimeout);
            if (res.outcome() == Outcome.UNAVAILABLE) {
                return asyncWorld ? ReviewResult.pending(res.provider()) : ReviewResult.skip(res.provider());
            }
            return res;
        }
        return resolved.adapter().check(scene, resolved.openId(), text, syncTimeout);
    }

    /** 完整预算的同步复检（{@code CheckPlatformText} RPC 使用）。 */
    public ReviewResult reviewSync(TextScene scene, long playerId, String account, String text) {
        if (adapters.isEmpty() || text == null || text.isEmpty() || isSkippedScene(scene)) {
            return ReviewResult.skip();
        }
        Resolved resolved;
        try {
            resolved = resolveAdapter(playerId, account);
        } catch (Exception e) {
            log.error("[content_guard/platform] lookup identity failed during sync review: err={}", e.getMessage());
            return ReviewResult.unavailable("");
        }
        if (resolved == null) {
            return ReviewResult.skip();
        }
        if (PlatformAdapter.PROVIDER_WECHAT.equals(resolved.adapter().provider())
                && (resolved.openId() == null || resolved.openId().isEmpty())) {
            return ReviewResult.skip(PlatformAdapter.PROVIDER_WECHAT);
        }
        Duration budget = scene == TextScene.TEXT_SCENE_CHAT_WORLD ? asyncTimeout : syncTimeout;
        return resolved.adapter().check(scene, resolved.openId(), text, budget);
    }

    private Resolved resolveAdapter(long playerId, String account) throws Exception {
        if (account != null && !account.isEmpty()) {
            String[] parsed = parseChannelAccount(account);
            if (!parsed[0].isEmpty()) {
                PlatformAdapter a = adapters.get(parsed[0]);
                return a != null ? new Resolved(a, parsed[1]) : null;
            }
        }
        if (playerId != 0L && identityLookup != null) {
            Identity id = getCachedIdentity(playerId);
            String[] parsed = providerFromIdentity(id);
            if (!parsed[0].isEmpty()) {
                PlatformAdapter a = adapters.get(parsed[0]);
                return a != null ? new Resolved(a, parsed[1]) : null;
            }
            return null;
        }
        if (playerId == 0L && (account == null || account.isEmpty())) {
            PlatformAdapter dy = adapters.get(PlatformAdapter.PROVIDER_DOUYIN);
            if (dy != null) {
                return new Resolved(dy, "");
            }
        }
        return null;
    }

    private Identity getCachedIdentity(long playerId) throws Exception {
        Instant now = clock.instant();
        CachedIdentity cached = identityCache.get(playerId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.identity();
        }
        Identity loaded = identityLookup.lookup(playerId);
        if (loaded == null) {
            return Identity.EMPTY;
        }
        if (loaded.isPresent()) {
            if (identityCache.size() >= IDENTITY_CACHE_MAX_SIZE) {
                identityCache.clear();
            }
            identityCache.put(playerId, new CachedIdentity(loaded, now.plus(DEFAULT_IDENTITY_TTL)));
        }
        return loaded;
    }

    private static String[] providerFromIdentity(Identity id) {
        if (id == null) {
            return new String[] {"", ""};
        }
        String ch = id.channel() == null ? "" : id.channel().trim().toLowerCase(Locale.ROOT);
        if ("wechat".equals(ch) || "wechat_minigame".equals(ch)) {
            return new String[] {PlatformAdapter.PROVIDER_WECHAT, id.openId() == null ? "" : id.openId()};
        }
        if ("douyin".equals(ch) || "douyin_minigame".equals(ch)) {
            return new String[] {PlatformAdapter.PROVIDER_DOUYIN, id.openId() == null ? "" : id.openId()};
        }
        String[] fromAcc = parseChannelAccount(id.account());
        if (!fromAcc[0].isEmpty()) {
            String oid = (id.openId() != null && !id.openId().isEmpty()) ? id.openId() : fromAcc[1];
            return new String[] {fromAcc[0], oid};
        }
        return new String[] {"", ""};
    }

    private static boolean isSkippedScene(TextScene scene) {
        return scene == TextScene.TEXT_SCENE_PRIVATE_LABEL
                || scene == TextScene.TEXT_SCENE_CHAT_TEAM
                || scene == TextScene.TEXT_SCENE_CHAT_PRIVATE;
    }
}
