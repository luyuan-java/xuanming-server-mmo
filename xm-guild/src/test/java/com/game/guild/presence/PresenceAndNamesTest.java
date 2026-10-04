package com.game.guild.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.discovery.proto.PlayerPresence;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 在线状态与展示名的 fail-open 语义（guild-spec §4.6、§4.7；基线 online_status_resolver.go:62-99、player_name_resolver.go:96-142、
 * player_name_resolver_test.go）。
 */
class PresenceAndNamesTest {

    private final Map<OnlineStatuses.Outcome, Integer> lookups = new HashMap<>();
    private final AtomicInteger nameFailures = new AtomicInteger();

    private OnlineStatuses online(java.util.function.Function<java.util.Collection<Long>,
            java.util.concurrent.CompletionStage<Map<Long, PlayerPresence>>> directory, long capMillis) {
        return new OnlineStatuses(directory, Duration.ofMillis(capMillis), o -> lookups.merge(o, 1, Integer::sum));
    }

    @Test
    void 在线_去重丢0_空列表不碰Redis() {
        List<List<Long>> calls = new ArrayList<>();
        OnlineStatuses o = online(ids -> {
            calls.add(List.copyOf(ids));
            Map<Long, PlayerPresence> out = new HashMap<>();
            out.put(2L, PlayerPresence.newBuilder().setPlayerId(2).build());
            return CompletableFuture.completedFuture(out);
        }, 800);
        assertThat(o.onlineOf(List.of(), Deadline.after(1000))).isEmpty();
        assertThat(o.onlineOf(List.of(0L, 0L), Deadline.after(1000))).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(o.onlineOf(List.of(1L, 2L, 0L, 2L), Deadline.after(1000))).containsExactly(2L);
        assertThat(calls).containsExactly(List.of(1L, 2L));
        assertThat(lookups).containsEntry(OnlineStatuses.Outcome.OK, 1);
    }

    @Test
    void 在线_独立上限超时按全体离线计timeout_读失败计error() {
        OnlineStatuses slow = online(ids -> new CompletableFuture<>(), 50);
        long start = System.nanoTime();
        assertThat(slow.onlineOf(List.of(1L), Deadline.after(3000))).isEmpty();
        assertThat(System.nanoTime() - start).isLessThan(Duration.ofMillis(2000).toNanos());
        assertThat(lookups).containsEntry(OnlineStatuses.Outcome.TIMEOUT, 1);

        OnlineStatuses broken = online(ids -> CompletableFuture.failedFuture(new IllegalStateException("down")), 800);
        assertThat(broken.onlineOf(List.of(1L), Deadline.after(1000))).isEmpty();
        OnlineStatuses throwing = online(ids -> {
            throw new IllegalStateException("redisson shut down");
        }, 800);
        assertThat(throwing.onlineOf(List.of(1L), Deadline.after(1000))).isEmpty();
        assertThat(lookups).containsEntry(OnlineStatuses.Outcome.ERROR, 2);
    }

    private PlayerNames names(java.util.function.BiFunction<List<Long>, Deadline, Map<Long, Profile>> batch) {
        return new PlayerNames(batch, nameFailures::incrementAndGet);
    }

    private static Profile profile(long id, String name) {
        return new Profile(id, name, 1, 1, 0, "", 1);
    }

    @Test
    void 名字_去重丢0丢空名_每批64人() {
        List<List<Long>> batches = new ArrayList<>();
        PlayerNames n = names((ids, d) -> {
            batches.add(List.copyOf(ids));
            Map<Long, Profile> out = new HashMap<>();
            for (long id : ids) {
                out.put(id, profile(id, id == 3 ? "" : "p" + id));
            }
            return out;
        });
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            ids.add(i);
        }
        ids.add(0L);
        ids.add(5L);
        Map<Long, String> got = n.namesOf(ids, Deadline.after(1000));
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(64);
        assertThat(batches.get(1)).hasSize(36);
        assertThat(got).hasSize(99).doesNotContainKey(3L).doesNotContainKey(0L).containsEntry(5L, "p5");
        assertThat(nameFailures.get()).isZero();
    }

    @Test
    void 名字_某批失败停在这一批_返回已读到的部分_按批计失败() {
        List<List<Long>> batches = new ArrayList<>();
        PlayerNames n = names((ids, d) -> {
            batches.add(List.copyOf(ids));
            if (batches.size() == 2) {
                throw new Deadline.DependencyException("读玩家资料失败");
            }
            Map<Long, Profile> out = new HashMap<>();
            for (long id : ids) {
                out.put(id, profile(id, "p" + id));
            }
            return out;
        });
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 200; i++) {
            ids.add(i);
        }
        Map<Long, String> got = n.namesOf(ids, Deadline.after(1000));
        assertThat(got).hasSize(64);
        assertThat(batches).hasSize(2);
        assertThat(nameFailures.get()).isEqualTo(1);
    }

    @Test
    void 名字_整次取名最多等800毫秒() {
        List<Long> remaining = new ArrayList<>();
        PlayerNames n = names((ids, d) -> {
            remaining.add(d.remainingMillis());
            return Map.of();
        });
        n.namesOf(List.of(1L), Deadline.after(3000));
        n.namesOf(List.of(1L), Deadline.after(300));
        assertThat(remaining.get(0)).isLessThanOrEqualTo(PlayerNames.LOOKUP_TIMEOUT_MS).isGreaterThan(500);
        assertThat(remaining.get(1)).isLessThanOrEqualTo(300);
    }

    @Test
    void 名字_请求预算已用完就不查_也不计失败() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        PlayerNames n = names((ids, d) -> {
            calls.incrementAndGet();
            return Map.of();
        });
        Deadline expired = Deadline.after(1);
        Thread.sleep(5);
        assertThat(n.namesOf(List.of(1L, 2L), expired)).isEmpty();
        assertThat(calls.get()).isZero();
        assertThat(nameFailures.get()).isZero();
    }
}
