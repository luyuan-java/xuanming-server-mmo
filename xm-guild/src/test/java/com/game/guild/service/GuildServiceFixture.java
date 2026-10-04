package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.proto.PlayerPresence;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.presence.OnlineStatuses;
import com.game.guild.presence.PlayerNames;
import com.game.guild.push.GuildPushes;
import com.game.guild.rank.GuildRanks;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import com.game.guild.rules.GuildTableRules.LevelDisplay;
import com.game.guild.zone.HomeZones;
import com.game.guild.zone.MergeFence;
import com.game.proto.MessageContent;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildChangedS2C;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 服务层单测的装配：真实的 {@link GuildService} / {@link GuildManageService} / {@link GuildRankService} / {@link GuildAccess} /
 * {@link GuildViews} / {@link GuildCache} / {@link GuildCacheInvalidator} / {@link GuildRanks} / {@link GuildPushes}，外部依赖全换成替身：
 * 存储（{@link ScriptedStore}）、缓存与排行 Redis（内存版）、归属区、闸门、在线、名字、推送通道、申请推送冷却、配表、发号器、时钟。
 * 所有替身把调用写进同一个 {@link #journal}，用例据此断言调用顺序（「归属区未知时不查闸门」「推送发生在失效之后」）。
 */
public final class GuildServiceFixture implements AutoCloseable {

    static final long NOW = 1_700_000_000_000L;
    public static final int ZONE = 2;
    static final int MAX_MEMBERS = 30;
    static final int MAX_OFFICERS = 2;
    static final long UPGRADE_COST = 1000;
    static final ApplicationRules RULES = new ApplicationRules(72L * 3_600_000L, 3, 50);
    static final int NOTIFY_ID = 220;

    final List<String> journal = Collections.synchronizedList(new ArrayList<>());
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final GuildMetrics metrics = new GuildMetrics(meters);
    public final ScriptedStore store = new ScriptedStore(journal::add);
    final FakeCacheRedis cacheRedis = new FakeCacheRedis(journal::add);
    final FakeRankRedis rankRedis = new FakeRankRedis(journal::add);
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor();

    // ---- 归属区 ----
    public final Map<Long, Integer> zones = new HashMap<>();
    final List<List<Long>> zoneCalls = Collections.synchronizedList(new ArrayList<>());
    volatile RuntimeException zoneFailure;

    // ---- 闸门 ----
    volatile boolean merging;
    volatile Exception fenceFailure;
    final List<Integer> fenceZones = Collections.synchronizedList(new ArrayList<>());

    // ---- 在线 / 名字 ----
    final Set<Long> online = Collections.synchronizedSet(new HashSet<>());
    volatile boolean onlineFails;
    final List<List<Long>> onlineCalls = Collections.synchronizedList(new ArrayList<>());
    final Map<Long, String> names = new HashMap<>();
    volatile boolean namesFail;
    final List<List<Long>> nameCalls = Collections.synchronizedList(new ArrayList<>());

    // ---- 推送 ----
    record Push(GuildChangedS2C change, List<Long> recipients) {
    }

    final List<Push> pushes = Collections.synchronizedList(new ArrayList<>());
    volatile boolean cooldownAllows = true;
    /** 推送通道同步抛异常 / 异常完成。 */
    volatile boolean pushThrows;
    volatile boolean pushFails;
    final List<String> cooldownCalls = Collections.synchronizedList(new ArrayList<>());

    // ---- 配表 / 发号 / 时钟 ----
    volatile ApplicationRules rules = RULES;
    volatile OptionalInt initialMaxMembers = OptionalInt.of(MAX_MEMBERS);
    volatile LevelDisplay levelDisplay = new LevelDisplay(MAX_OFFICERS, UPGRADE_COST);
    final AtomicLong nextGuildId = new AtomicLong(900);
    public volatile LongSupplier guildIds = nextGuildId::incrementAndGet;

    final GuildCacheInvalidator invalidator;
    final GuildCache cache;
    final GuildRanks ranks;
    final GuildPushes guildPushes;
    final GuildAccess access;
    final GuildViews views;
    public final GuildService guilds;
    public final GuildManageService manage;
    public final GuildRankService rankService;

    public GuildServiceFixture() {
        HomeZones homeZones = (ids, deadline) -> {
            zoneCalls.add(List.copyOf(ids));
            journal.add("zones " + ids);
            RuntimeException e = zoneFailure;
            if (e != null) {
                throw e;
            }
            Map<Long, Integer> out = new HashMap<>();
            for (long id : ids) {
                Integer z = zones.get(id);
                if (z != null) {
                    out.put(id, z);
                }
            }
            return out;
        };
        MergeFence fence = zoneId -> {
            fenceZones.add(zoneId);
            journal.add("fence " + zoneId);
            Exception e = fenceFailure;
            if (e != null) {
                throw e;
            }
            return merging;
        };
        GuildTableLookup tables = new GuildTableLookup() {
            @Override
            public ApplicationRules applicationRules() {
                return rules;
            }

            @Override
            public OptionalInt officerCap(int level) {
                return OptionalInt.of(MAX_OFFICERS);
            }

            @Override
            public OptionalInt initialMaxMembers() {
                return initialMaxMembers;
            }

            @Override
            public LevelDisplay levelDisplay(int level) {
                return levelDisplay;
            }
        };
        invalidator = new GuildCacheInvalidator(cacheRedis, Duration.ofMinutes(30), background, metrics);
        cache = new GuildCache(cacheRedis, Duration.ofMinutes(30),
                (gid, d) -> store.loadGuild(gid, d).map(GuildSnapshots::toSnapshot).orElse(null),
                store::playerGuildId, invalidator, metrics);
        ranks = new GuildRanks(rankRedis, background, metrics);
        guildPushes = new GuildPushes(this::push, metrics, NOTIFY_ID, Duration.ofSeconds(3));
        access = new GuildAccess(cache, homeZones, fence, invalidator);
        OnlineStatuses onlineStatuses = new OnlineStatuses(this::onlineLookup, Duration.ofMillis(800), metrics);
        PlayerNames playerNames = new PlayerNames(this::nameLookup, metrics);
        views = new GuildViews(onlineStatuses, playerNames, tables, store::countLiveApplications, () -> NOW);
        guilds = new GuildService(store, cache, access, views, ranks, guildPushes, () -> guildIds.getAsLong(), tables,
                () -> NOW);
        manage = new GuildManageService(store, cache, access, views, guildPushes, (g, p, d) -> {
            cooldownCalls.add(g + ":" + p);
            journal.add("cooldown " + g + " " + p);
            return cooldownAllows;
        }, tables, () -> NOW);
        rankService = new GuildRankService(ranks, cache, access, views);
    }

    private CompletableFuture<Map<Long, PlayerPushes.Outcome>> push(Collection<Long> ids, MessageContent content) {
        GuildChangedS2C change;
        try {
            change = GuildChangedS2C.parseFrom(content.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
        if (content.getMessageId() != NOTIFY_ID || content.getId() != 0) {
            throw new AssertionError("推送消息号 / id 不对: " + content);
        }
        pushes.add(new Push(change, List.copyOf(ids)));
        journal.add("push " + GuildPushes.kindLabel(change.getKind()));
        if (pushThrows) {
            throw new IllegalStateException("redisson shut down");
        }
        if (pushFails) {
            return CompletableFuture.failedFuture(new IllegalStateException("presence down"));
        }
        Map<Long, PlayerPushes.Outcome> outcomes = new LinkedHashMap<>();
        for (long id : ids) {
            outcomes.put(id, online.contains(id) ? PlayerPushes.Outcome.SENT : PlayerPushes.Outcome.OFFLINE);
        }
        return CompletableFuture.completedFuture(outcomes);
    }

    private CompletableFuture<Map<Long, PlayerPresence>> onlineLookup(Collection<Long> ids) {
        onlineCalls.add(List.copyOf(ids));
        journal.add("online " + ids);
        if (onlineFails) {
            return CompletableFuture.failedFuture(new IllegalStateException("presence down"));
        }
        Map<Long, PlayerPresence> out = new HashMap<>();
        for (long id : ids) {
            if (online.contains(id)) {
                out.put(id, PlayerPresence.newBuilder().setPlayerId(id).build());
            }
        }
        return CompletableFuture.completedFuture(out);
    }

    private Map<Long, Profile> nameLookup(List<Long> ids, Deadline deadline) {
        nameCalls.add(List.copyOf(ids));
        journal.add("names " + ids);
        if (namesFail) {
            throw new IllegalStateException("player table down");
        }
        Map<Long, Profile> out = new HashMap<>();
        for (long id : ids) {
            String name = names.get(id);
            if (name != null) {
                out.put(id, new Profile(id, name, 1, 1, 0, "", ZONE));
            }
        }
        return out;
    }

    static Deadline d() {
        return Deadline.after(3_500);
    }

    /** journal 里第一个以 {@code prefix} 开头的条目的下标；没有为 -1。 */
    int indexOf(String prefix) {
        synchronized (journal) {
            for (int i = 0; i < journal.size(); i++) {
                if (journal.get(i).startsWith(prefix)) {
                    return i;
                }
            }
        }
        return -1;
    }

    boolean called(String prefix) {
        return indexOf(prefix) >= 0;
    }

    Push onlyPush() {
        if (pushes.size() != 1) {
            throw new AssertionError("期望恰好一条推送，实际 " + pushes);
        }
        return pushes.getFirst();
    }

    double pushCount(GuildChangeKind kind, String outcome) {
        return meters.get("xm.guild.pushes").tag("kind", GuildPushes.kindLabel(kind)).tag("outcome", outcome).counter()
                .count();
    }

    @Override
    public void close() {
        background.shutdownNow();
    }
}
