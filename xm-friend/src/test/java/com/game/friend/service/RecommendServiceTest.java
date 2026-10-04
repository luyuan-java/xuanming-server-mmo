package com.game.friend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.presence.PlayerPresenceDirectory.StrictLookup;
import com.game.discovery.proto.PlayerPresence;
import com.game.friend.directory.OnlineDirectory;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.profile.PlayerProfiles.Profile;
import com.game.friend.quota.DirectoryQuota;
import com.game.friend.store.RecommendSource;
import com.game.friend.store.RecommendStore.Candidate;
import com.game.friend.support.Deadline;
import com.game.proto.TipInfoMessage;
import com.game.proto.friend.RecommendEntry;
import com.game.proto.friend.RecommendFriendsRequest;
import com.game.proto.friend.RecommendFriendsResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RecommendServiceTest {

    private static final long ME = 100;

    private final FriendMetrics metrics = new FriendMetrics(new SimpleMeterRegistry());
    private final List<List<Long>> mutualExcludes = new ArrayList<>();
    private final List<Integer> wants = new ArrayList<>();
    private List<Candidate> mutual = List.of();
    private List<Candidate> random = List.of();
    private boolean failMutual;
    private boolean failOnline;
    private final Map<Long, PlayerPresence> online = new HashMap<>();
    private final Map<Long, Profile> profiles = new HashMap<>();
    private final AtomicLong quotaCount = new AtomicLong();
    private boolean failQuota;
    private final List<OnlineDirectory.ScanPage> scans = new ArrayList<>();
    private final Map<String, byte[]> presenceValues = new HashMap<>();

    private RecommendService service() {
        RecommendSource source = new RecommendSource() {
            @Override
            public List<Candidate> mutual(long me, List<Long> exclude, int limit, Deadline deadline) throws SQLException {
                mutualExcludes.add(List.copyOf(exclude));
                wants.add(limit);
                if (failMutual) {
                    throw new SQLException("mysql down");
                }
                return mutual.subList(0, Math.min(limit, mutual.size()));
            }

            @Override
            public List<Candidate> random(long me, List<Long> exclude, int limit, Deadline deadline) {
                mutualExcludes.add(List.copyOf(exclude));
                wants.add(limit);
                return random.subList(0, Math.min(limit, random.size()));
            }
        };
        OnlineDirectory directory = new OnlineDirectory(new OnlineDirectory.DirectoryRedis() {
            @Override
            public java.util.concurrent.CompletionStage<OnlineDirectory.ScanPage> scan(long cursor) {
                return CompletableFuture.completedFuture(scans.isEmpty() ? new OnlineDirectory.ScanPage(0, List.of()) : scans.remove(0));
            }

            @Override
            public java.util.concurrent.CompletionStage<Map<String, byte[]>> mget(List<String> keys) {
                Map<String, byte[]> out = new HashMap<>();
                keys.forEach(k -> out.put(k, presenceValues.get(k)));
                return CompletableFuture.completedFuture(out);
            }
        }, "xm:presence:", (ids, d) -> lookup(ids));
        DirectoryQuota quota = new DirectoryQuota(k -> failQuota
                ? CompletableFuture.failedFuture(new IllegalStateException("redis down"))
                : CompletableFuture.completedFuture(quotaCount.incrementAndGet()), metrics);
        return new RecommendService(source, directory, quota, (ids, d) -> lookup(ids),
                ids -> failOnline ? CompletableFuture.failedFuture(new IllegalStateException("down"))
                        : CompletableFuture.completedFuture(new StrictLookup(onlineOf(ids), ids.size() - onlineOf(ids).size(), 0)),
                metrics, 10, 20, 64);
    }

    private Map<Long, Profile> lookup(List<Long> ids) {
        Map<Long, Profile> out = new HashMap<>();
        ids.forEach(id -> {
            if (profiles.containsKey(id)) {
                out.put(id, profiles.get(id));
            }
        });
        return out;
    }

    private Map<Long, PlayerPresence> onlineOf(List<Long> ids) {
        Map<Long, PlayerPresence> out = new HashMap<>();
        ids.forEach(id -> {
            if (online.containsKey(id)) {
                out.put(id, online.get(id));
            }
        });
        return out;
    }

    private static Deadline deadline() {
        return Deadline.after(2000);
    }

    private static TipInfoMessage tip(int id, String message) {
        return TipInfoMessage.newBuilder().setId(id).addParameters(message).build();
    }

    @Test
    void exclude超过64条回1005不截断_恰好64条放行() {
        RecommendFriendsRequest.Builder tooMany = RecommendFriendsRequest.newBuilder();
        for (long i = 1; i <= 65; i++) {
            tooMany.addExcludePlayerIds(i);
        }
        RecommendFriendsResponse r = service().recommendFriends(ME, tooMany.build(), deadline());
        assertThat(r.getErrorMessage()).isEqualTo(tip(1005, "too many exclude_player_ids"));
        assertThat(r.getOnlineDirectory()).isFalse();
        assertThat(wants).isEmpty();
        RecommendFriendsRequest exactly64 = RecommendFriendsRequest.newBuilder()
                .addAllExcludePlayerIds(tooMany.getExcludePlayerIdsList().subList(0, 64)).build();
        assertThat(service().recommendFriends(ME, exactly64, deadline()).hasErrorMessage()).isFalse();
    }

    @Test
    void limit钳制_0取10_超20取20_无符号大值取20() {
        RecommendService s = service();
        assertThat(s.clampLimit(0)).isEqualTo(10);
        assertThat(s.clampLimit(50)).isEqualTo(20);
        assertThat(s.clampLimit(-1)).isEqualTo(20);
        assertThat(s.clampLimit(7)).isEqualTo(7);
    }

    @Test
    void 两级策略只补缺口_选中的追加进排除集_只补在线态() {
        mutual = List.of(new Candidate(1, 3), new Candidate(2, 1));
        random = List.of(new Candidate(5, 0), new Candidate(6, 0), new Candidate(7, 0));
        online.put(5L, PlayerPresence.newBuilder().setPlayerId(5).setOnlineSinceMs(777).build());
        RecommendFriendsResponse r = service().recommendFriends(ME, RecommendFriendsRequest.newBuilder().setLimit(4)
                .addExcludePlayerIds(9).build(), deadline());
        assertThat(r.getCandidatesList()).extracting(RecommendEntry::getCandidatePlayerId).containsExactly(1L, 2L, 5L, 6L);
        assertThat(r.getCandidates(0).getMutualFriends()).isEqualTo(3);
        assertThat(r.getCandidates(2).getIsOnline()).isTrue();
        assertThat(r.getCandidates(2).getLastActiveMs()).isEqualTo(777);
        assertThat(r.getCandidates(0).getName()).isEmpty();
        assertThat(r.getOnlineDirectory()).isFalse();
        assertThat(wants).containsExactly(4, 2);
        assertThat(mutualExcludes.get(0)).containsExactly(9L, ME);
        assertThat(mutualExcludes.get(1)).containsExactly(9L, ME, 1L, 2L);
    }

    @Test
    void 第一级凑满就不跑第二级_任一级出错回1003不做部分降级_在线读失败按离线() {
        mutual = List.of(new Candidate(1, 1), new Candidate(2, 1));
        assertThat(service().recommendFriends(ME, RecommendFriendsRequest.newBuilder().setLimit(2).build(), deadline())
                .getCandidatesCount()).isEqualTo(2);
        assertThat(wants).containsExactly(2);

        failMutual = true;
        assertThat(service().recommendFriends(ME, RecommendFriendsRequest.getDefaultInstance(), deadline()).getErrorMessage())
                .isEqualTo(tip(1003, "recommend candidates unavailable"));

        failMutual = false;
        failOnline = true;
        online.put(1L, PlayerPresence.newBuilder().setPlayerId(1).build());
        RecommendFriendsResponse r = service().recommendFriends(ME, RecommendFriendsRequest.newBuilder().setLimit(2).build(),
                deadline());
        assertThat(r.hasErrorMessage()).isFalse();
        assertThat(r.getCandidatesList()).noneMatch(RecommendEntry::getIsOnline);
    }

    @Test
    void 在线目录_非法输入1005不占额度_配额故障1003_超额1008_都带online_directory() {
        RecommendFriendsRequest bad = RecommendFriendsRequest.newBuilder().setOnlineOnly(true).setCursor("bad").build();
        RecommendFriendsResponse r = service().recommendFriends(ME, bad, deadline());
        assertThat(r.getOnlineDirectory()).isTrue();
        assertThat(r.getErrorMessage()).isEqualTo(tip(1005, "invalid online directory cursor or query"));
        assertThat(quotaCount).hasValue(0);

        RecommendFriendsRequest ok = RecommendFriendsRequest.newBuilder().setOnlineOnly(true).build();
        failQuota = true;
        assertThat(service().recommendFriends(ME, ok, deadline()).getErrorMessage())
                .isEqualTo(tip(1003, "online directory limiter unavailable"));
        failQuota = false;
        quotaCount.set(60);
        RecommendFriendsResponse limited = service().recommendFriends(ME, ok, deadline());
        assertThat(limited.getErrorMessage()).isEqualTo(tip(1008, "online directory rate limited"));
        assertThat(limited.getOnlineDirectory()).isTrue();
    }

    @Test
    void 在线目录_调用者home_zone未知回1003_成功列一页() {
        RecommendFriendsRequest ok = RecommendFriendsRequest.newBuilder().setOnlineOnly(true).build();
        assertThat(service().recommendFriends(ME, ok, deadline()).getErrorMessage())
                .isEqualTo(tip(1003, "online directory unavailable"));

        profiles.put(ME, new Profile(ME, "me", 1, 1, 1, "", 3));
        profiles.put(7L, new Profile(7, "seven", 9, 2, 1, "ap", 3));
        presenceValues.put("xm:presence:7", PlayerPresence.newBuilder().setPlayerId(7).setOnlineSinceMs(55).build().toByteArray());
        scans.add(new OnlineDirectory.ScanPage(0, List.of("xm:presence:7", "xm:presence:" + ME)));
        RecommendFriendsResponse r = service().recommendFriends(ME, ok, deadline());
        assertThat(r.hasErrorMessage()).isFalse();
        assertThat(r.getOnlineDirectory()).isTrue();
        assertThat(r.getCandidatesList()).singleElement().satisfies(e -> {
            assertThat(e.getCandidatePlayerId()).isEqualTo(7);
            assertThat(e.getName()).isEqualTo("seven");
            assertThat(e.getZoneId()).isEqualTo(3);
        });
        assertThat(r.getNextCursor()).isEmpty();
    }
}
